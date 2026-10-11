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
import SuperPlayerCore

/// CTA-2066 metrics off a bare `AVPlayer`, in the vocabulary `QoeCollector` emits — the Kotlin
/// `StockTelemetry`, and for its reason **the most dangerous file in the benchmark**.
///
/// Arms (a) and (b) have no `SuperPlayer` in them, so `QoeCollector` — which a `SuperPlayer`'s
/// configuration makes and attaches — cannot measure them, and a comparison in which the two sides
/// measure differently is not a comparison. Three things are done about that, the Kotlin three:
///
/// 1. **This emits core's own ``TelemetryEvent``s, not numbers**, and `SessionMetrics` reduces every
///    arm's events with one function that cannot tell which arm produced them. What is left here is
///    *derivation*: when a rebuffer starts, which is a much smaller surface than a metric.
/// 2. **That surface is mirrored handler for handler from `QoeCollector` and its register,
///    `CollectorSignals.swift`**, which are the shipped implementation and therefore the definition
///    of what arm (c) does. Each handler says which one it mirrors.
/// 3. **`StockTelemetryAgreementTests` attaches both to one `AVPlayer`** — a `SuperPlayer`'s own,
///    through `player.avPlayer` — and asserts they derive the same events. That test is what makes
///    1 and 2 true rather than intended; if it goes, the benchmark's claim goes with it.
///
/// ## Three deliberate differences
///
/// **Delivery is synchronous**, the Kotlin first: `QoeCollector` hands events to the bounded
/// `TelemetryDelivery` queue ADR-0008 rules 3 and 4 require, which drops under pressure and counts
/// it; a benchmark's sink appends to a list, and a session whose `droppedEventCount` is non-zero is
/// excluded from every arm's aggregates alike (`SessionMetrics.usable`).
///
/// **The session's edges and its `SessionStarted` payload are given**, the Kotlin second: core
/// signals arm (c)'s, because only core knows which item carried a `MediaRequest`, and a stock
/// player has no content identity, so the runner says when one opens and what it ran with
/// (``startSession(contentId:sessionId:profile:decision:)``).
///
/// **No memory warning is observed**, which has no Kotlin counterpart because the Kotlin collector's
/// memory pressure arrives through a different door: the warning exists to make the delivery queue
/// give its pending events back, and synchronous delivery has no queue to give back.
@MainActor
final class StockTelemetry {

    /// `QoeCollector.samplingIntervalMs`, repeated because the collector keeps its constant
    /// internal so that a consumer reads the cadence off the event; the agreement test is what stops
    /// the repeat drifting.
    static let samplingIntervalMs: Int64 = 10_000

    /// `QoeCollector.seekExclusionWindowMs`, for the same reason.
    static let seekExclusionWindowMs: Int64 = 1_000

    /// `QoeCollector.positionReadingIntervalMs`, for the same reason.
    static let positionReadingIntervalMs: Int64 = 500

    /// `QoeCollector.jumpToleranceMs`, for the same reason.
    static let jumpToleranceMs: Int64 = 2 * positionReadingIntervalMs

    private let sink: (TelemetryEvent) -> Void
    private weak var player: AVPlayer?
    private var openSession: OpenSession?
    private var declaredIntentMonotonicMs: Int64?
    private var cancellations: [() -> Void] = []
    private var sampler: Task<Void, Never>?

    /// A mirror that hands every event to `sink`, synchronously, on the main actor.
    init(sink: @escaping (TelemetryEvent) -> Void) {
        self.sink = sink
    }

    /// Mirrors `QoeCollector.attach(to:)`, minus the facade: this arm's player *is* the engine.
    func attach(to player: AVPlayer) {
        precondition(
            self.player == nil, "A StockTelemetry measures one player; make one per player")
        self.player = player
    }

    /// Mirrors `QoeCollector.declareIntent(monotonicTimeMs:)`.
    func declareIntent(monotonicTimeMs: Int64) {
        declaredIntentMonotonicMs = monotonicTimeMs
    }

    /// Opens a session over the player's current item, as core's `startSession` does for arm (c).
    ///
    /// `profile` and `decision` are given rather than read, and are honest for the Kotlin reasons:
    /// `decision` is what the arm *ran with*, in the value type arm (c) reports
    /// (``Arm/stockDecision``), and `profile` is a label — the profile of the cell this session is
    /// compared in, since a stock player has none. A raw trace carries its arm beside the events,
    /// so nobody has to read this field to learn which player produced them.
    func startSession(
        contentId: String, sessionId: String, profile: PlaybackProfile, decision: PlaybackDecision
    ) {
        endSession()
        guard let player, let item = player.currentItem else { return }
        let startedAt = Self.now()
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
                    timestampMs: Self.wallClock(), monotonicTimeMs: startedAt, profile: profile,
                    decision: decision)))
        register(on: player, item: item)
        scheduleSampling()
        timeControlStatusChanged()
    }

    /// Mirrors `QoeCollector.endSession()`.
    func endSession() {
        guard let session = openSession else { return }
        openSession = nil
        stopObserving()
        closeRebuffer(session)
        emit(
            .sessionEnded(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: Self.now(),
                    // Synchronous delivery drops nothing; see the type's first difference.
                    droppedEventCount: 0, securityLevel: nil)))
    }

    /// Mirrors `QoeCollector.detach()`.
    func detach() {
        stopObserving()
        player = nil
        declaredIntentMonotonicMs = nil
    }

    // MARK: - The register, mirroring CollectorSignals.swift

    /// Every registration `CollectorObservation.registerAll` makes, less the memory warning (the
    /// type's third difference), each handler told only *that* something changed and reading the
    /// state it needs when it runs.
    private func register(on player: AVPlayer, item: AVPlayerItem) {
        // In `CollectorSignal.allCases`' order, so two callbacks AVFoundation delivers at one
        // instant reach the two collectors in one order.
        keyValue(player, \.timeControlStatus) { $0.timeControlStatusChanged() }
        keyValue(item, \.status) { $0.itemStatusChanged() }
        keyValue(item, \.isPlaybackLikelyToKeepUp) { $0.likelyToKeepUpChanged() }
        let notifications: [(Notification.Name, @MainActor (StockTelemetry, NSError?) -> Void)] = [
            (
                AVPlayerItem.playbackStalledNotification,
                { this, _ in this.stalled(byMetric: false) }
            ),
            (AVPlayerItem.timeJumpedNotification, { this, _ in this.timeJumped() }),
            (
                AVPlayerItem.newAccessLogEntryNotification,
                { this, _ in this.reconcileVideoBitrate() }
            ),
            (AVPlayerItem.failedToPlayToEndTimeNotification, { this, error in this.failed(error) }),
        ]
        for (name, handle) in notifications {
            let token = NotificationCenter.default.addObserver(
                forName: name, object: item, queue: .main
            ) { [weak self] notification in
                let error =
                    notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
                MainActor.assumeIsolated {
                    guard let self else { return }
                    handle(self, error)
                }
            }
            let box = Opaque(token)
            cancellations.append { NotificationCenter.default.removeObserver(box.value) }
        }
        let timeToken = player.addPeriodicTimeObserver(
            forInterval: CMTime(value: Self.positionReadingIntervalMs, timescale: 1_000),
            queue: .main
        ) { [weak self] time in
            MainActor.assumeIsolated { self?.positionRead(time) }
        }
        let timeBox = Opaque(timeToken)
        cancellations.append { [weak player] in player?.removeTimeObserver(timeBox.value) }
        if #available(iOS 18, tvOS 18, *) {
            let task = Self.metricEvents(of: item) { [weak self] signal in
                self?.handleMetric(signal)
            }
            cancellations.append { task.cancel() }
        }
    }

    /// A key-value observation whose change is re-delivered on the main actor, as
    /// `CollectorObservation.keyValue` re-delivers one: Apple names no thread for these key paths'
    /// changes, so the handler hops when it is not on the main thread and reads the current value
    /// when it arrives.
    private func keyValue<Object: NSObject, Value>(
        _ object: Object, _ keyPath: KeyPath<Object, Value>,
        _ handle: @escaping @MainActor (StockTelemetry) -> Void
    ) {
        let observation = object.observe(keyPath) { [weak self] _, _ in
            let deliver = { @MainActor in
                guard let self else { return }
                handle(self)
            }
            if Thread.isMainThread {
                MainActor.assumeIsolated { deliver() }
            } else {
                DispatchQueue.main.async { MainActor.assumeIsolated { deliver() } }
            }
        }
        cancellations.append { observation.invalidate() }
    }

    private func stopObserving() {
        sampler?.cancel()
        sampler = nil
        for cancel in cancellations { cancel() }
        cancellations = []
    }

    // MARK: - The handlers, mirroring QoeCollector

    /// Mirrors `QoeCollector.timeControlStatusChanged()`.
    private func timeControlStatusChanged() {
        guard let session = openSession, let player else { return }
        switch player.timeControlStatus {
        case .playing:
            if session.firstFrameAtMs == nil { firstFrame(session) }
            closeRebuffer(session)
        case .waitingToPlayAtSpecifiedRate:
            if player.reasonForWaitingToPlay == .toMinimizeStalls { openRebuffer(session) }
        case .paused:
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

    /// Mirrors `QoeCollector.stalled(byMetric:)`.
    private func stalled(byMetric: Bool) {
        guard let session = openSession, let status = player?.timeControlStatus,
            status != .playing, !byMetric || status == .waitingToPlayAtSpecifiedRate
        else { return }
        openRebuffer(session)
    }

    /// Mirrors `QoeCollector.itemStatusChanged()`.
    private func itemStatusChanged() {
        guard let session = openSession else { return }
        switch session.item.status {
        case .failed: failed(session.item.error as NSError?)
        case .readyToPlay: reconcileVideoBitrate()
        default: break
        }
    }

    /// Mirrors `QoeCollector.likelyToKeepUpChanged()`.
    private func likelyToKeepUpChanged() {
        guard let session = openSession, let player, player.timeControlStatus == .paused,
            session.item.isPlaybackLikelyToKeepUp
        else { return }
        completeSeek(session)
    }

    /// Mirrors `QoeCollector.timeJumped()`.
    private func timeJumped() {
        guard let session = openSession, session.firstFrameAtMs != nil, let player else { return }
        let now = Self.now()
        let toMs = Self.milliseconds(session.item.currentTime())
        let fromMs = session.jumpedFromMs ?? session.reading?.predicted(at: now) ?? toMs
        session.jumpedFromMs = nil
        session.reading = Reading(mediaMs: toMs, monotonicMs: now, rate: player.rate)
        session.seekRequestedAtMs = now
        session.seekTargetMs = toMs
        emit(
            .seekRequested(
                .init(
                    sessionId: session.id, contentId: session.contentId,
                    timestampMs: Self.wallClock(), monotonicTimeMs: now, fromPositionMs: fromMs,
                    toPositionMs: toMs)))
        if player.timeControlStatus == .paused, session.item.isPlaybackLikelyToKeepUp {
            completeSeek(session)
        }
    }

    /// Mirrors `QoeCollector.positionRead(_:)`.
    private func positionRead(_ time: CMTime) {
        guard let session = openSession, let player else { return }
        let now = Self.now()
        let mediaMs = Self.milliseconds(time)
        if let predicted = session.reading?.predicted(at: now),
            abs(mediaMs - predicted) > Self.jumpToleranceMs
        {
            session.jumpedFromMs = predicted
        }
        session.reading = Reading(mediaMs: mediaMs, monotonicMs: now, rate: player.rate)
        if let target = session.seekTargetMs, player.timeControlStatus == .playing,
            mediaMs > target
        {
            completeSeek(session)
        }
    }

    /// Mirrors `QoeCollector.failed(with:)`.
    private func failed(_ error: NSError?) {
        guard let session = openSession, !session.failed else { return }
        session.failed = true
        let now = Self.now()
        let failure = Self.playbackFailure(error, errorLog: session.item.errorLog())
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
        closeRebuffer(session)
        session.abandonSeek()
    }

    /// Mirrors `QoeCollector.reconcileVideoBitrate()`.
    private func reconcileVideoBitrate() {
        guard let session = openSession, Self.hasVideo(session.item),
            let indicated = session.item.accessLog()?.events.last?.indicatedBitrate,
            let bitrate = Self.bitrate(indicated)
        else { return }
        observeVideoBitrate(bitrate)
    }

    /// Mirrors `QoeCollector.observeVideoBitrate(_:)`.
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

    /// Mirrors `QoeCollector.handle(_:)`'s two metric cases.
    private func handleMetric(_ signal: MetricSignal) {
        switch signal {
        case .stalled: stalled(byMetric: true)
        case .initiallyLikelyToKeepUp(let videoPeakBitrateBps):
            videoPeakBitrateBps.map(observeVideoBitrate)
        }
    }

    // MARK: - Rebuffers and seeks, mirroring QoeCollector

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

    // MARK: - The periodic samples, mirroring QoeCollector

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
        guard let session = openSession, let player else { return }
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
                    throughputEstimateBps: log.last.flatMap { Self.bitrate($0.observedBitrate) })))
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

    /// Mirrors `QoeCollector.sampleDroppedFrames(_:log:now:)`.
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
                    droppedFrames: droppedSince, repeatedFrames: 0, elapsedPlayingMs: elapsed)))
    }

    private func emit(_ event: TelemetryEvent) {
        sink(event)
    }
}

// MARK: - Readings, mirroring QoeCollector's

extension StockTelemetry {

    /// `QoeCollector.now()`: `CLOCK_MONOTONIC`, the clock every duration is a difference on.
    static func now() -> Int64 { SessionRunner.now() }

    /// `QoeCollector.wallClock()`.
    static func wallClock() -> Int64 {
        Int64((Date().timeIntervalSince1970 * 1_000).rounded())
    }

    /// `QoeCollector.milliseconds(_:)`.
    static func milliseconds(_ time: CMTime) -> Int64 {
        guard time.isNumeric else { return 0 }
        return max(0, Int64((time.seconds * 1_000).rounded()))
    }

    /// `QoeCollector.bitrate(_:)`.
    nonisolated static func bitrate(_ value: Double) -> Int? {
        guard value.isFinite, value > 0 else { return nil }
        return value >= Double(Int.max) ? Int.max : Int(value.rounded())
    }

    /// `QoeCollector.hasVideo(_:)`.
    static func hasVideo(_ item: AVPlayerItem) -> Bool {
        item.tracks.contains { $0.isEnabled && $0.assetTrack?.mediaType == .video }
    }

    /// `QoeCollector.bufferedAheadMs(_:)`.
    static func bufferedAheadMs(_ item: AVPlayerItem) -> Int64 {
        let current = item.currentTime()
        guard
            let range = item.loadedTimeRanges.lazy.map(\.timeRangeValue).first(where: {
                $0.containsTime(current)
            })
        else { return 0 }
        return max(0, milliseconds(range.end) - milliseconds(current))
    }

    /// `QoeCollector.targetLiveLatencyMs(_:)`.
    static func targetLiveLatencyMs(_ item: AVPlayerItem) -> Int64? {
        for offset in [item.configuredTimeOffsetFromLive, item.recommendedTimeOffsetFromLive]
        where offset.isNumeric && offset.seconds > 0 {
            return milliseconds(offset)
        }
        return nil
    }

    /// `QoeCollector.playbackFailure(_:errorLog:classified:)` with nothing classified, and its
    /// `category(_:loggedStatus:)`: a stock player has no classifier (#419), as arm (c), configured
    /// without `Resilience.standard()`, has none either. The one
    /// derivation the agreement test's session cannot reach, since nothing in it fails, so
    /// `StockTelemetryFailureTableTests` holds the two tables to each other code by code.
    static func playbackFailure(_ error: NSError?, errorLog: AVPlayerItemErrorLog?)
        -> PlaybackFailure
    {
        let logged = errorLog?.events.last
        return PlaybackFailure(
            category: category(error, loggedStatus: logged?.errorStatusCode),
            code: error.map { "\($0.domain):\($0.code)" },
            message: error?.localizedDescription ?? logged?.errorComment)
    }

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

    /// `MetricSubscription`: the iOS 18 stall and initial likely-to-keep-up events, merged in the
    /// order they happened, each reduced to a ``MetricSignal`` on the main actor.
    @available(iOS 18, tvOS 18, *)
    static func metricEvents(
        of item: AVPlayerItem, _ handler: @escaping @MainActor @Sendable (MetricSignal) -> Void
    ) -> Task<Void, Never> {
        let events = item.metrics(forType: AVMetricPlayerItemStallEvent.self)
            .chronologicalMerge(
                with: item.metrics(forType: AVMetricPlayerItemInitialLikelyToKeepUpEvent.self))
        return Task { @MainActor in
            do {
                for try await (event, _) in events {
                    if let initial = event as? AVMetricPlayerItemInitialLikelyToKeepUpEvent {
                        let variant = initial.variant
                        let peak = variant?.peakBitRate ?? variant?.averageBitRate
                        let hasVideo = variant?.videoAttributes != nil
                        handler(
                            .initiallyLikelyToKeepUp(
                                videoPeakBitrateBps: hasVideo ? peak.flatMap(bitrate) : nil))
                    } else if event is AVMetricPlayerItemStallEvent {
                        handler(.stalled)
                    }
                }
            } catch {
                // As `MetricSubscription`: the key-value observations still report every stall.
            }
        }
    }
}

/// `MetricSignal`: what the metric stream delivered, reduced to what the handlers read.
enum MetricSignal: Sendable {
    case stalled
    case initiallyLikelyToKeepUp(videoPeakBitrateBps: Int?)
}

/// `PositionReading`: the position last read, and the rate it was read at.
struct Reading {
    let mediaMs: Int64
    let monotonicMs: Int64
    let rate: Float

    func predicted(at nowMs: Int64) -> Int64 {
        mediaMs + Int64((Double(nowMs - monotonicMs) * Double(rate)).rounded())
    }
}

/// `QoeCollector`'s `OpenSession`, field for field.
@MainActor
private final class OpenSession {
    let id: String
    let contentId: String
    let item: AVPlayerItem
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

    var firstFrameAtMs: Int64?
    var failed = false
    var videoBitrateBps: Int?
    var rebufferStartedAtMs: Int64?
    var rebufferSeekInduced = false
    var seekRequestedAtMs: Int64?
    var seekTargetMs: Int64?
    var seekCompletedAtMs: Int64?
    var reading: Reading?
    var jumpedFromMs: Int64?
    var droppedFramesReported = 0
    var watchedMsReported: Int64 = 0

    func abandonSeek() {
        seekRequestedAtMs = nil
        seekTargetMs = nil
    }

    /// `OpenSession.isSeekInduced(at:)`.
    func isSeekInduced(at nowMs: Int64) -> Bool {
        if seekRequestedAtMs != nil { return true }
        guard let completedAt = seekCompletedAtMs else { return false }
        return nowMs - completedAt <= StockTelemetry.seekExclusionWindowMs
    }
}

/// An opaque registration token handed back only to the API that made it — `UncheckedSendable`.
private struct Opaque<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}
