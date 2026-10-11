// Copyright 2026 The SuperPlayer Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import AVFoundation
import Foundation
import SuperPlayerCore

/// Watches a ``SuperPlayer`` and writes what it observes to a ``TelemetrySink`` — the Kotlin
/// `QoeCollector`, deriving the same vocabulary from what `AVPlayer` reports.
///
/// ```swift
/// var configuration = SuperPlayer.Configuration()
/// configuration.telemetry = { QoeCollector(sink: analyticsSink) }
/// ```
///
/// This is the module boundary ADR-0008 draws: the sink and the event vocabulary are
/// `SuperPlayerCore`'s, so a consumer's data pipeline names no telemetry type and no AVFoundation
/// type, while every AVFoundation observation and every metric definition lives here — and the
/// translation between the two lives in this one file, with every registration it makes in the
/// one beside it, `CollectorSignals.swift`.
///
/// ## What it emits, and from what
///
/// The whole of `docs/telemetry-schema.md` but the one event no iOS source raises yet:
/// `LicenceAcquisitionEnded`, whose source is FairPlay's `AVContentKeySession` (#422).
/// `DecisionChanged` is core's to signal (``decisionChanged(_:trigger:)``), on a player whose policy
/// is consulted again. **What each number means is that document, not this
/// file**; its *How derived on iOS* column says which API each comes from, and where iOS 17 and 18
/// derive one differently. The comments below say why a value comes from where it does.
///
/// Where the Kotlin collector has one interface reporting every fact — Media3's
/// `AnalyticsListener` — AVFoundation has ten, and they disagree about threads, about timing, and
/// about whether they report a state or a change. Every handler here is therefore told only *that*
/// something changed, and reads the state it needs at that moment. That is the one structural
/// difference from the Kotlin class, and it is what makes a key-value change delivered late or
/// twice harmless.
///
/// ## How an event reaches the sink
///
/// Never on the main actor. Everything derived here is handed to ``TelemetryDelivery``, which queues
/// it under a bound and drains it on a queue the engine does not use — ADR-0008 rules 3 and 4.
///
/// ## One collector, one player
///
/// A collector measures the player it was attached to and the session currently open on it, which
/// is why ``SuperPlayer/Configuration/telemetry`` is a factory: each player made from one
/// configuration gets its own.
@MainActor
public final class QoeCollector: TelemetryCollector {

    /// The cadence the three periodic events share — the Kotlin `SAMPLING_INTERVAL_MS`, which
    /// `docs/telemetry-schema.md` states and argues. Every sample carries it as `samplingIntervalMs`,
    /// so a consumer reads the cadence off the event rather than off this constant, which is why it
    /// is not public.
    static let samplingIntervalMs: Int64 = 10_000

    /// The window after a completed seek in which a stall is still the seek's — the Kotlin
    /// `SEEK_EXCLUSION_WINDOW_MS`; the document's *Rebuffering* argues the value.
    static let seekExclusionWindowMs: Int64 = 1_000

    /// How often the periodic time observer reads the player's time while it plays: the resolution
    /// of a seek's `fromPositionMs` and of an in-buffer seek's completion. No Kotlin counterpart,
    /// because Media3 hands a discontinuity both of its positions; `AVPlayerItem.timeJumpedNotification`
    /// hands neither, so the position before the jump is the last reading, carried forward at the
    /// rate it was read at. Half a second is finer than any seek a scrub bar makes and costs two
    /// main-actor callbacks a second while playing — none while paused, when the observer is called
    /// only as time jumps or playback starts or stops.
    static let positionReadingIntervalMs: Int64 = 500

    /// How far a reading may land from where the previous one predicted before it is taken to be a
    /// jump rather than a reading: twice the reading interval, so a reading delivered one interval
    /// late is not mistaken for a seek.
    static let jumpToleranceMs: Int64 = 2 * positionReadingIntervalMs

    /// The bounded, off-actor path to the sink. One per collector, so a drop is attributable to the
    /// session that lost it, while the queue it drains on is the process's one.
    private let delivery: TelemetryDelivery

    /// The player this collector was attached to. Weak, because the player holds its collector and
    /// the Kotlin collector's strong reference would be a cycle here.
    private weak var player: SuperPlayer?

    /// The session currently open, or nil between an ``endSession()`` and the next
    /// ``startSession(contentId:sessionId:)``.
    private var openSession: OpenSession?

    /// The most recent declared intent, on `CLOCK_MONOTONIC`, not yet spent on a session — the
    /// Kotlin `declaredIntentMonotonicMs`, for its reasons: it arrives before a session does, the
    /// next session consumes it so no later session opens on a stale one, and the last declaration
    /// before a session wins.
    private var declaredIntentMonotonicMs: Int64?

    /// What `CollectorSignals.swift` registered for the open session; empty between sessions.
    private var observations: [CollectorObservation] = []

    /// Posts the periodic samples while a session is open.
    private var sampler: Task<Void, Never>?

    /// Whether the sampling timer is running — the one allocation per session that is not a
    /// registration, counted beside ``liveObservations`` by the register's test.
    var isSampling: Bool { sampler != nil }

    /// The ordinary way to make one: a sink, behind the delivery path this library chose for it.
    public convenience init(sink: any TelemetrySink) {
        self.init(delivery: TelemetryDelivery(sink: sink))
    }

    /// Injectable only from inside this product, as the Kotlin constructor is.
    init(delivery: TelemetryDelivery) {
        self.delivery = delivery
    }

    /// The registrations live for the open session, per signal — what the register's test counts.
    /// Internal and reached through `@testable`, as core's `liveObservations` is.
    var liveObservations: [CollectorSignal: Int] {
        observations.reduce(into: [:]) { counts, observation in
            counts[observation.signal, default: 0] += 1
        }
    }

    // MARK: - TelemetryCollector

    public func attach(to player: SuperPlayer) {
        precondition(self.player == nil, "A QoeCollector measures one player; make one per player")
        self.player = player
    }

    public func startSession(contentId: String, sessionId: String) {
        // Core always ends a session before the next begins; ending here as well keeps a collector
        // driven by anything else from merging two contents' events into one view.
        endSession()
        guard let player, let item = player.avPlayer.currentItem else { return }
        let startedAt = Self.now()
        // The start boundary of time to first frame, resolved once here rather than at the frame:
        // whether a declaration existed is a fact about this session's opening, and deciding it
        // later would let a declaration made after the session began move its own start backwards.
        // ref: CTA-2066 measures video start-up time from the viewer's request.
        let intentMs = declaredIntentMonotonicMs
        declaredIntentMonotonicMs = nil
        let session = OpenSession(
            id: sessionId, contentId: contentId, item: item,
            ttffStartMonotonicMs: intentMs ?? startedAt,
            ttffStartBoundary: intentMs == nil ? .contentAdopted : .userIntent)
        openSession = session
        emit(
            .sessionStarted(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: startedAt,
                    profile: player.configuration.profile,
                    decision: player.playbackDecision)))
        observations = CollectorObservation.registerAll(
            on: player.avPlayer, item: item, positionIntervalMs: Self.positionReadingIntervalMs
        ) { [weak self] event in self?.handle(event) }
        scheduleSampling()
        // The player may already be playing — content replaced under a running player — so the
        // state is read once now rather than waited for.
        timeControlStatusChanged()
    }

    public func decisionChanged(_ decision: PlaybackDecision, trigger: DecisionTrigger) {
        // No session, no event: the next `SessionStarted` carries the decision then in force, which
        // is the same information under the id it belongs to.
        guard let session = openSession else { return }
        emit(
            .decisionChanged(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: Self.now(),
                    decision: decision, trigger: trigger)))
    }

    public func endSession() {
        guard let session = openSession else { return }
        // Cleared first, so this collector's state is settled before anything leaves it.
        openSession = nil
        stopObserving()
        // A stall open when the session ended is closed under the session that owned it, so no
        // pipeline pairs a `RebufferStarted` across the boundary into the next viewer's numbers.
        closeRebuffer(session)
        emit(
            .sessionEnded(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: Self.now(),
                    // A placeholder the delivery path replaces as the event leaves the queue.
                    droppedEventCount: 0,
                    // Nothing negotiates protection on iOS until #422.
                    securityLevel: nil)))
    }

    public func declareIntent(monotonicTimeMs: Int64) {
        declaredIntentMonotonicMs = monotonicTimeMs
    }

    public func contentReopened(after failure: SuperPlayerError) {
        guard let session = openSession, let player, let item = player.avPlayer.currentItem,
            item !== session.item
        else { return }
        // The repaired failure is reported as any failure is (ADR-0011 rule 3), against the item it
        // happened on, unless that item's own observation already did — it is withheld from the
        // consumer, not from measurement.
        if !session.failed { failed(with: failure.underlyingError, classified: failure) }
        stopObserving()
        // The same session on the item that replaced the failed one: its first frame, if there was
        // one, stays the session's, so a later failure is mid-stream, and the replacement's own
        // failure is a new one to report.
        session.item = item
        session.failed = false
        observations = CollectorObservation.registerAll(
            on: player.avPlayer, item: item, positionIntervalMs: Self.positionReadingIntervalMs
        ) { [weak self] event in self?.handle(event) }
        scheduleSampling()
        timeControlStatusChanged()
    }

    public func detach() {
        stopObserving()
        player = nil
        // A declaration nobody spent belongs to no session.
        declaredIntentMonotonicMs = nil
    }

    // MARK: - What the registrations report

    private func handle(_ event: CollectorEvent) {
        switch event {
        case .notified(.timeControlStatus, _): timeControlStatusChanged()
        case .notified(.itemStatus, _): itemStatusChanged()
        case .notified(.likelyToKeepUp, _): likelyToKeepUpChanged()
        case .notified(.playbackStalled, _): stalled(byMetric: false)
        case .notified(.timeJumped, _): timeJumped()
        case .notified(.newAccessLogEntry, _): reconcileVideoBitrate()
        case .notified(.failedToPlayToEnd, let error): failed(with: error)
        case .notified(.memoryWarning, _): delivery.onMemoryPressure()
        case .notified(.periodicTime, _), .notified(.metricEvents, _): break
        case .positionRead(let time): positionRead(time)
        case .metric(.stalled): stalled(byMetric: true)
        case .metric(.initiallyLikelyToKeepUp(let videoPeakBitrateBps)):
            videoPeakBitrateBps.map(observeVideoBitrate)
        }
    }

    /// The player started, stopped, or began waiting.
    ///
    /// The Kotlin `onPlaybackStateChanged`, with `timeControlStatus` standing in for Media3's
    /// playback state: `.playing` is `STATE_READY` with the clock running, and a wait for
    /// `.toMinimizeStalls` is `STATE_BUFFERING`. The other reasons to wait are not stalls — a rate
    /// still being evaluated, no item, an interstitial — and open nothing.
    private func timeControlStatusChanged() {
        guard let session = openSession, let player = player?.avPlayer else { return }
        switch player.timeControlStatus {
        case .playing:
            // The first frame. `AVPlayerLayer.isReadyForDisplay` would be the video frame itself,
            // and it is the *consumer's* layer, which a collector attached to a player cannot see;
            // the clock running is the moment presentation began, for video and — since the
            // synthetic streams and any podcast are audio — for audio-only content too, which the
            // Kotlin collector reports nothing for. The document's cell says both.
            if session.firstFrameAtMs == nil { firstFrame(session) }
            closeRebuffer(session)
        case .waitingToPlayAtSpecifiedRate:
            if player.reasonForWaitingToPlay == .toMinimizeStalls { openRebuffer(session) }
        case .paused:
            // A player that stopped did not resume, but it did stop stalling; an unanswered
            // `RebufferStarted` would carry an open stall into whatever a pipeline read next.
            closeRebuffer(session)
        @unknown default:
            break
        }
    }

    private func firstFrame(_ session: OpenSession) {
        let now = Self.now()
        session.firstFrameAtMs = now
        emit(
            .firstFrameRendered(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: now,
                    timeToFirstFrameMs: now - session.ttffStartMonotonicMs,
                    startBoundary: session.ttffStartBoundary)))
    }

    /// The SDK said it stalled — a player that does not wait to minimise stalling, through the
    /// notification, or the iOS 18 stall event. Opens a rebuffer unless the player has already
    /// resumed by the time the report arrives, which a metric event delivered late can find.
    ///
    /// A metric event also needs the player to be *waiting*: one delivered after the viewer paused
    /// would otherwise open a stall that counts paused time until the next play. The notification
    /// is exempt, because on a player that does not wait the stall itself is what pauses it.
    private func stalled(byMetric: Bool) {
        guard let session = openSession, let status = player?.avPlayer.timeControlStatus,
            status != .playing, !byMetric || status == .waitingToPlayAtSpecifiedRate
        else { return }
        openRebuffer(session)
    }

    private func itemStatusChanged() {
        guard let session = openSession else { return }
        switch session.item.status {
        case .failed: failed(with: session.item.error as NSError?)
        // The item's tracks are known from here, so whether it has video — and so whether its
        // access log's bitrate is a video rendition's — can be answered.
        case .readyToPlay: reconcileVideoBitrate()
        default: break
        }
    }

    /// A paused player's seek completes when the item can play from its target: its
    /// `timeControlStatus` never moves, so this is the only signal that it could.
    private func likelyToKeepUpChanged() {
        guard let session = openSession, let player = player?.avPlayer,
            player.timeControlStatus == .paused, session.item.isPlaybackLikelyToKeepUp
        else { return }
        completeSeek(session)
    }

    /// The item's time jumped — a seek, as the Kotlin `onPositionDiscontinuity` filters for
    /// `DISCONTINUITY_REASON_SEEK`.
    ///
    /// AVFoundation's notification does not say who moved the time, so a jump the SDK makes on its
    /// own — back inside a live window that slid past the playhead — is reported as a seek too, and
    /// the document says so. A jump before playback began is not one: the item's own start
    /// position is a seek made before the item was the player's, and its notification can arrive
    /// after the session opened; start-up time is what that interval already is.
    private func timeJumped() {
        guard let session = openSession, session.firstFrameAtMs != nil,
            let player = player?.avPlayer
        else { return }
        let now = Self.now()
        let toMs = Self.milliseconds(session.item.currentTime())
        let fromMs = session.jumpedFromMs ?? session.reading?.predicted(at: now) ?? toMs
        session.jumpedFromMs = nil
        session.reading = PositionReading(mediaMs: toMs, monotonicMs: now, rate: player.rate)
        session.seekRequestedAtMs = now
        session.seekTargetMs = toMs
        emit(
            .seekRequested(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: now,
                    fromPositionMs: fromMs, toPositionMs: toMs)))
        // A seek into what is already buffered, made while paused, changes nothing a key-value
        // observation could report: the Kotlin collector's "latency for that seek is zero".
        if player.timeControlStatus == .paused, session.item.isPlaybackLikelyToKeepUp {
            completeSeek(session)
        }
    }

    /// The periodic observer read the player's time: the position a later jump came from, and the
    /// completion of a seek once the time is advancing past where it landed.
    private func positionRead(_ time: CMTime) {
        guard let session = openSession, let player = player?.avPlayer else { return }
        let now = Self.now()
        let mediaMs = Self.milliseconds(time)
        // The observer is also called as the time jumps, and that call can come before the jump's
        // notification: a reading far from the prediction is the jump, and the prediction is where
        // it came from.
        if let predicted = session.reading?.predicted(at: now),
            abs(mediaMs - predicted) > Self.jumpToleranceMs
        {
            session.jumpedFromMs = predicted
        }
        session.reading = PositionReading(mediaMs: mediaMs, monotonicMs: now, rate: player.rate)
        // Resumed at the target means the clock is running and the time has moved on from where the
        // seek landed — the Kotlin `STATE_READY`, which is a state iOS does not report.
        if let target = session.seekTargetMs, player.timeControlStatus == .playing,
            mediaMs > target
        {
            completeSeek(session)
        }
    }

    /// The item failed — before the first frame a startup failure, after it a mid-stream one: the
    /// split CTA-2066 draws and a viewer experiences. One per session, because a failed item can
    /// report through both its status and its failed-to-play-to-end notification.
    private func failed(with error: NSError?, classified known: SuperPlayerError? = nil) {
        guard let session = openSession, !session.failed else { return }
        session.failed = true
        let now = Self.now()
        // Asked of the player as a consumer would, for `classify`'s reason: this product is not
        // resilience's, and the typed error a consumer was handed and this event are one answer.
        // A failure a rung repaired arrives already classified, because the item it was the
        // player's answer for is no longer current.
        let classified = known ?? error.flatMap { player?.classify($0) }
        let failure = Self.playbackFailure(
            error, errorLog: session.item.errorLog(), classified: classified)
        if session.firstFrameAtMs == nil {
            emit(
                .startupFailed(
                    .init(
                        sessionId: session.id, contentId: session.contentId,
                        timestampMs: Self.wallClock(), monotonicTimeMs: now, failure: failure)))
        } else {
            emit(
                .midStreamFailed(
                    .init(
                        sessionId: session.id, contentId: session.contentId,
                        timestampMs: Self.wallClock(), monotonicTimeMs: now, failure: failure,
                        positionMs: Self.milliseconds(session.item.currentTime()))))
        }
        // This playback will not resume: the stall ends with it, and a seek in flight is given up
        // rather than left to make every later stall seek-induced (the Kotlin `abandonSeek`).
        closeRebuffer(session)
        session.abandonSeek()
    }

    /// Reads the access log's current rendition and reports a switch if it changed — the Kotlin
    /// `onDownstreamFormatChanged`, for video only, as there.
    ///
    /// `indicatedBitrate` is the variant's `BANDWIDTH`, the declared peak the document's *Bitrate*
    /// asks for. A new log entry is how AVFoundation reports a switch (a new entry begins at each
    /// variant change), and the item's readiness is read too, because the first entry can arrive
    /// before the tracks that say whether there is video at all.
    private func reconcileVideoBitrate() {
        guard let session = openSession, Self.hasVideo(session.item),
            let indicated = session.item.accessLog()?.events.last?.indicatedBitrate,
            let bitrate = Self.bitrate(indicated)
        else { return }
        observeVideoBitrate(bitrate)
    }

    private func observeVideoBitrate(_ bitrate: Int) {
        guard let session = openSession else { return }
        let from = session.videoBitrateBps
        guard from != bitrate else { return }
        session.videoBitrateBps = bitrate
        let direction: TrackSwitchDirection =
            switch from {
            case nil: .initial
            case let from? where bitrate > from: .up
            default: .down
            }
        emit(
            .trackSwitched(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: Self.now(),
                    fromBitrateBps: from, toBitrateBps: bitrate, direction: direction)))
    }

    // MARK: - Rebuffers and seeks

    /// Opens a rebuffer, attributing it to a seek or not. **Time before the first frame is not a
    /// rebuffer** — it is start-up time, and counting it in both would count the same seconds twice.
    private func openRebuffer(_ session: OpenSession) {
        guard session.firstFrameAtMs != nil, session.rebufferStartedAtMs == nil else { return }
        let now = Self.now()
        let seekInduced = session.isSeekInduced(at: now)
        session.rebufferStartedAtMs = now
        session.rebufferSeekInduced = seekInduced
        emit(
            .rebufferStarted(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: now, seekInduced: seekInduced)))
    }

    private func closeRebuffer(_ session: OpenSession) {
        guard let startedAt = session.rebufferStartedAtMs else { return }
        session.rebufferStartedAtMs = nil
        let now = Self.now()
        emit(
            .rebufferEnded(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: now,
                    durationMs: now - startedAt, seekInduced: session.rebufferSeekInduced)))
    }

    /// Completes the seek in flight, which is what makes seek latency a number.
    private func completeSeek(_ session: OpenSession) {
        guard let requestedAt = session.seekRequestedAtMs else { return }
        session.seekRequestedAtMs = nil
        session.seekTargetMs = nil
        let now = Self.now()
        session.seekCompletedAtMs = now
        emit(
            .seekCompleted(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: now,
                    toPositionMs: Self.milliseconds(session.item.currentTime()),
                    seekLatencyMs: now - requestedAt)))
    }

    // MARK: - The periodic samples

    /// Starts the periodic samples, at the cadence the document states — the Kotlin
    /// `scheduleSampling`, and its argument: a sample is an event in its own right, because bitrate
    /// distribution and buffer health are time-weighted and a pipeline cannot weight what it did
    /// not receive at a known cadence. Every *metric* above is derived from AVFoundation reporting
    /// what happened, never from this timer inferring it.
    private func scheduleSampling() {
        sampler = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(Self.samplingIntervalMs))
                guard !Task.isCancelled else { return }
                self?.sampleNow()
            }
        }
    }

    private func sampleNow() {
        guard let session = openSession, let player = player?.avPlayer else { return }
        let now = Self.now()
        let item = session.item
        let log = item.accessLog()?.events ?? []
        emit(
            .playbackStateSampled(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: now,
                    samplingIntervalMs: Self.samplingIntervalMs,
                    videoBitrateBps: session.videoBitrateBps,
                    bufferedDurationMs: Self.bufferedAheadMs(item),
                    playing: player.timeControlStatus == .playing,
                    // Apple's own estimate, smoothed by AVFoundation over everything the item has
                    // downloaded — not a sample, and the document says whose number it is.
                    throughputEstimateBps: log.last.flatMap { Self.bitrate($0.observedBitrate) })))
        // Live content only, decided by what the item says it is rather than by the profile, for
        // the Kotlin reason: a live profile handed on-demand content is a misconfiguration, not a
        // live session, and a zero from on-demand content is a number a dashboard would average.
        if item.duration.isIndefinite, let edge = item.seekableTimeRanges.last?.timeRangeValue.end,
            edge.isNumeric
        {
            emit(
                .liveLatencySampled(
                    .init(
                        sessionId: session.id, contentId: session.contentId,
                        timestampMs: Self.wallClock(), monotonicTimeMs: now,
                        liveLatencyMs: max(
                            0, Self.milliseconds(edge) - Self.milliseconds(item.currentTime())),
                        targetLiveLatencyMs: Self.targetLiveLatencyMs(item))))
        }
        sampleDroppedFrames(session, log: log, now: now)
    }

    /// Dropped frames over the playing time since the last sample, from the access log's running
    /// totals — video only, as the Kotlin `onDroppedVideoFrames` is a video renderer's callback.
    ///
    /// The log's counts are per entry and AVFoundation updates them on its own cadence; summing every
    /// entry and reporting the difference since the last sample gives an interval whose count and
    /// whose playing time come from the same reading, which is what the document's denominator
    /// needs. A field AVFoundation reports as negative is unknown and counts nothing.
    private func sampleDroppedFrames(
        _ session: OpenSession, log: [AVPlayerItemAccessLogEvent], now: Int64
    ) {
        guard Self.hasVideo(session.item) else { return }
        let dropped = log.reduce(0) { $0 + max(0, $1.numberOfDroppedVideoFrames) }
        let watchedMs = log.reduce(Int64(0)) { $0 + Int64(max(0, $1.durationWatched) * 1_000) }
        let elapsed = watchedMs - session.watchedMsReported
        guard elapsed > 0 else { return }
        let droppedSince = max(0, dropped - session.droppedFramesReported)
        session.watchedMsReported = watchedMs
        session.droppedFramesReported = dropped
        emit(
            .videoFramesDropped(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: now,
                    droppedFrames: droppedSince,
                    // The access log counts no frame presented twice, as Media3's renderer counts
                    // none; zero means "not observable on this engine", and the document says so.
                    repeatedFrames: 0, elapsedPlayingMs: elapsed)))
    }

    private func stopObserving() {
        sampler?.cancel()
        sampler = nil
        for observation in observations { observation.cancel() }
        observations = []
    }

    /// Queues `event` for delivery and returns: the only work on the main actor is an append.
    private func emit(_ event: TelemetryEvent) {
        delivery.submit(event)
    }
}

// MARK: - Readings

extension QoeCollector {

    /// The clock every duration in `docs/telemetry-schema.md` is defined on: `CLOCK_MONOTONIC`, the
    /// one ``SuperPlayer/declarePlaybackIntent()`` reads, so a time to first frame is a difference
    /// on one clock. Not a `CMTime` from AVFoundation's own clock, for the Kotlin collector's reason
    /// about `EventTime.realtimeMs`: subtracting a reading of one clock from another's is correct
    /// only while they happen to agree.
    static func now() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000)
    }

    /// Wall-clock epoch milliseconds, for joining and never for measuring.
    static func wallClock() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1_000).rounded())
    }

    /// `time` in whole milliseconds, never negative; zero for a time that is not a number.
    static func milliseconds(_ time: CMTime) -> Int64 {
        guard time.isNumeric else { return 0 }
        return max(0, Int64((time.seconds * 1_000).rounded()))
    }

    /// A bitrate AVFoundation reported as a `Double`, as this vocabulary's `Int`; nil for the
    /// negative or zero it uses for "unknown". Clamped rather than wrapped, for the Kotlin reason:
    /// reporting the ceiling of a link no ladder has a rung for is honest where wrapping is not.
    nonisolated static func bitrate(_ value: Double) -> Int? {
        guard value.isFinite, value > 0 else { return nil }
        return value >= Double(Int.max) ? Int.max : Int(value.rounded())
    }

    /// Whether the item is presenting video. Bitrates, switches and dropped frames are a *video*
    /// rendition's in this schema, so an audio-only item reports none of them, as on Android.
    static func hasVideo(_ item: AVPlayerItem) -> Bool {
        item.tracks.contains { $0.isEnabled && $0.assetTrack?.mediaType == .video }
    }

    /// Media milliseconds buffered ahead of the playhead: the end of the loaded range containing it.
    static func bufferedAheadMs(_ item: AVPlayerItem) -> Int64 {
        let current = item.currentTime()
        guard
            let range = item.loadedTimeRanges.lazy.map(\.timeRangeValue).first(where: {
                $0.containsTime(current)
            })
        else { return 0 }
        return max(0, milliseconds(range.end) - milliseconds(current))
    }

    /// The live latency the profile or the stream asked for: the configured offset where the
    /// profile set one (`EngineBinding.swift`), else what AVFoundation recommends from the
    /// playlist's hold-back — the Kotlin `liveConfiguration.targetOffsetMs`, which is either.
    static func targetLiveLatencyMs(_ item: AVPlayerItem) -> Int64? {
        for offset in [item.configuredTimeOffsetFromLive, item.recommendedTimeOffsetFromLive]
        where offset.isNumeric && offset.seconds > 0 {
            return milliseconds(offset)
        }
        return nil
    }

    /// What failed, in SuperPlayer's vocabulary rather than AVFoundation's — the Kotlin
    /// `toPlaybackFailure`.
    ///
    /// `classified` is `SuperPlayerResilience`'s answer where the player had one to give (#419), and
    /// it settles both fields a classification touches (ADR-0011 rule 3): `classification` is its
    /// stable name and `category` *its* row of the classifier's one-to-one table, rather than this
    /// function's reading of the same failure. Nothing here interprets it — no branch on the name,
    /// no table of its own. With no classification — a player configured without resilience — both
    /// fall back to exactly what this reported before #419: the coarse bucket below, and nil.
    ///
    /// `code` is the error's domain and code, `"<domain>:<code>"`: a string, as the Kotlin
    /// `errorCodeName` is, because it is a grouping key and must survive an SDK that adds codes.
    ///
    /// The category reads the error log as well as the error: an `AVPlayerItem` that fails on a
    /// refused segment reports a generic AVFoundation error, while its error log's last entry
    /// names the HTTP status that caused it. The log is read at the failure rather than observed,
    /// because an entry that was recovered from is not a failure in this schema.
    static func playbackFailure(
        _ error: NSError?, errorLog: AVPlayerItemErrorLog?, classified: SuperPlayerError? = nil
    ) -> PlaybackFailure {
        let logged = errorLog?.events.last
        return PlaybackFailure(
            category: classified?.category
                ?? category(error, loggedStatus: logged?.errorStatusCode),
            code: error.map { "\($0.domain):\($0.code)" },
            message: error?.localizedDescription ?? logged?.errorComment,
            classification: classified?.causeClass)
    }

    /// The bucket, from the error's domain and code where they say enough, else from the error
    /// log's status — the reading of a player with no classifier, and the analogue of the Kotlin
    /// band bucketing. It names a bucket and never a class, so it is not a second taxonomy
    /// (ADR-0011 rule 1), and where a classification exists it is not consulted at all; the
    /// classifier's switch is the only one that assigns a meaning.
    ///
    /// ref: https://developer.apple.com/documentation/avfoundation/averror/code — the codes named
    /// below; every other AVFoundation code is `unknown` rather than guessed at, which is the band
    /// fall-through the Kotlin mapping has.
    static func category(_ error: NSError?, loggedStatus: Int?) -> FailureCategory {
        if error?.domain == NSURLErrorDomain { return .network }
        if let status = loggedStatus, (400..<600).contains(status) { return .network }
        if let error, error.domain == AVFoundationErrorDomain {
            switch AVError.Code(rawValue: error.code) {
            case .contentIsUnavailable, .serverIncorrectlyConfigured, .noLongerPlayable:
                return .network
            case .fileFormatNotRecognized, .failedToParse, .contentIsNotAuthorized:
                return .source
            case .decoderNotFound, .decoderTemporarilyUnavailable, .decodeFailed:
                return .decoder
            case .mediaServicesWereReset, .videoCompositorFailed:
                return .renderer
            default:
                return .unknown
            }
        }
        return .unknown
    }
}

/// The position the periodic observer last read, and the rate it was read at.
struct PositionReading {
    let mediaMs: Int64
    let monotonicMs: Int64
    let rate: Float

    /// Where the playhead would be at `nowMs` had nothing jumped.
    func predicted(at nowMs: Int64) -> Int64 {
        mediaMs + Int64((Double(nowMs - monotonicMs) * Double(rate)).rounded())
    }
}

/// A session that has started and not yet ended, and everything a metric is derived from — the
/// Kotlin `OpenSession`. Per session, so a recycled player's next session starts with nothing of
/// the last one's.
@MainActor
private final class OpenSession {
    let id: String
    let contentId: String
    /// The item the session is of: the one core made current before opening it, or the one a rung
    /// re-adopted the content on since (`contentReopened(after:)`).
    var item: AVPlayerItem
    let ttffStartMonotonicMs: Int64
    let ttffStartBoundary: TtffStartBoundary

    init(
        id: String, contentId: String, item: AVPlayerItem, ttffStartMonotonicMs: Int64,
        ttffStartBoundary: TtffStartBoundary
    ) {
        self.id = id
        self.contentId = contentId
        self.item = item
        self.ttffStartMonotonicMs = ttffStartMonotonicMs
        self.ttffStartBoundary = ttffStartBoundary
    }

    /// When presentation began, or nil; read by the failure split and the rebuffer rule too.
    var firstFrameAtMs: Int64?
    var failed = false
    var videoBitrateBps: Int?
    var rebufferStartedAtMs: Int64?
    var rebufferSeekInduced = false
    var seekRequestedAtMs: Int64?
    /// Where the seek in flight landed: what the time must move past before it has resumed.
    var seekTargetMs: Int64?
    var seekCompletedAtMs: Int64?
    var reading: PositionReading?
    /// The position a jump came from, when the periodic observer saw the jump before the
    /// notification did.
    var jumpedFromMs: Int64?
    /// The access log's totals already reported on a `VideoFramesDropped`.
    var droppedFramesReported = 0
    var watchedMsReported: Int64 = 0

    func abandonSeek() {
        seekRequestedAtMs = nil
        seekTargetMs = nil
    }

    /// Whether a stall beginning at `nowMs` is a seek's — between a `SeekRequested` and its
    /// `SeekCompleted`, or within ``QoeCollector/seekExclusionWindowMs`` after it; the Kotlin
    /// `isSeekInducedAt`, and the document's *Rebuffering*.
    func isSeekInduced(at nowMs: Int64) -> Bool {
        if seekRequestedAtMs != nil { return true }
        guard let completedAt = seekCompletedAtMs else { return false }
        return nowMs - completedAt <= QoeCollector.seekExclusionWindowMs
    }
}
