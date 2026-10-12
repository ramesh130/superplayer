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
import os

/// Records a ``SessionTrace`` of one ``SuperPlayer``: the engine's own state, loads and errors, read
/// from what `AVPlayer` reports, and every `TelemetryEvent` this recorder is handed as a
/// ``TelemetrySink`` — the Kotlin `SessionTraceRecorder`, in its grammar and under its rules.
///
/// Wiring, in the order that matters:
///
/// ```swift
/// let recorder = SessionTraceRecorder()
/// var configuration = SuperPlayer.Configuration()
/// configuration.telemetry = { QoeCollector(sink: .composite(appSink, recorder)) }
/// let player = SuperPlayer(configuration: configuration)
/// recorder.attach(to: player)     // before the first setMediaRequest: time is measured from here
/// // ... play ...
/// player.release()
/// let trace = recorder.trace()    // once the sink has seen SessionEnded; see below
/// recorder.detach()
/// ```
///
/// A sink and not a collector, for the Kotlin reason: ``QoeCollector`` already derives the
/// vocabulary, and a second derivation of the same metrics would be the drift
/// `docs/telemetry-schema.md` exists to prevent. What this adds is the engine layer under those
/// metrics, which is why it also observes the player — in this product, because ADR-0008 rule 2 puts
/// every engine observation here — through a register of its own, `RecorderSignals.swift`.
///
/// ## Redaction rules
///
/// A trace is meant to be attached to a bug report and read by a CDN engineer, so it is redacted by
/// construction: the recorder never holds the material, rather than stripping it later. The rules
/// are the Kotlin recorder's, word for word where the engine allows, and the reasons are its.
///
/// 1. **No URL, host, path, query or token.** A load is named by what it is — a playlist, an
///    initialization segment or a media segment, and its track type — never by where it came from.
///    A metric event's `url`, `indexFileURL` and `serverAddress` are not read. The one consumer
///    string that is printed, the content id of an item made current, is withheld when it is shaped
///    like a URI, because an app that names content by URL would otherwise put its signed URLs in
///    every trace.
/// 2. **No request or response header.** A request's transaction metrics are read for their byte
///    count and their status code and nothing else; a response's other headers, and a cookie or an
///    `Authorization` a session carried, are never read.
/// 3. **No exception message.** AVFoundation's errors name the failing URL in their descriptions as
///    Media3's do, so an error is recorded as its domain and code, a load's as its HTTP status where
///    the response carried one, and a telemetry failure as its category and code, never its
///    `message`.
/// 4. **No session id and no wall clock.** Both differ every run; the session id is also the CMCD
///    `sid` a CDN log could be joined on — here the asset's `httpSessionIdentifier`, which is the
///    metric event's `sessionID` too and is not read. Time is milliseconds since ``attach(to:)``.
/// 5. **No device identity.** Nothing about the device, build, account or network is recorded.
/// 6. **No DRM payload.** A licence exchange would appear as `load ... drm` and nothing more; until
///    FairPlay (#422) there is none to record.
/// 7. **No device identity, and a capability is admitted only where the library itself branches on
///    it.** ADR-0015 rule 10, written for the Phase 9 *bundle* and stated here because it is one rule
///    set rather than two. Its first clause repeats rule 5 deliberately: rule 5 says it of a trace,
///    which records nothing of a device at all, and this of a snapshot, which records a device's
///    *capabilities* and must still refuse its identity — what the device can **do**, never what it
///    **is**: no model or OS build string, no hardware component name, no DRM identifier, no display
///    name, nothing of the network. Nothing in this recorder holds such a snapshot; the bundle
///    (`SuperPlayerDiagnostics`' `SessionBundle`, #429) is where the rule is carried out — over
///    core's `CapabilitySnapshot`, which carries the platform, the OS's `major.minor`, the memory
///    budget, a hardware-decode yes or no per codec and the display's short edge and HDR
///    eligibility, and nothing else — and `SessionBundlePlaybackTests` is where it is tested. It is
///    stated here so that the six a trace obeys and the seventh a bundle obeys are read together.
///
/// `SessionTraceRecorderPlaybackTests` drives a session that really carried a signed URL, a
/// URL-shaped content id and an `Authorization` header answering a `Basic` challenge, asserts each
/// present in what the origin received, and then absent from the trace.
///
/// ## What iOS records, and from what
///
/// | Kind | From | iOS 17 |
/// | --- | --- | --- |
/// | `item` | `AVPlayer.currentItem` made non-nil; the content id ``SuperPlayer/currentContentId`` reads | yes |
/// | `state` | `BUFFERING` on adoption and on a stall after playback began, `READY` at `.readyToPlay` and on resuming, `ENDED` at the item's end, `IDLE` at `.failed` | yes |
/// | `playing` | `timeControlStatus` entering or leaving `.playing` | yes |
/// | `load` | `AVMetricHLSPlaylistRequestEvent` and `AVMetricHLSMediaSegmentRequestEvent` | **18 and later** |
/// | `bandwidth` | the same events: a finished network transfer's bytes over its own duration | **18 and later** |
/// | `error` | the item's error at `.failed`, as `code=<domain>:<code>` | yes |
/// | `telemetry` | every event handed to this sink | yes |
///
/// **`load` and `bandwidth` need iOS or tvOS 18**, because the request events are where
/// AVFoundation first reports a request at all; below 18 those lines are absent rather than
/// approximated from the access log, whose entries are per rendition and not per request. A `load`
/// line differs from the Kotlin one in what the event carries: no media time span and no rendition
/// bitrate, because a segment request event names neither. Each request is two lines, `started` at
/// its request start and `completed` (or `error`) at its response end, with the duration between —
/// both written when the event arrives, since an event arrives only once its request has ended, and
/// so never a `canceled` line for a request whose event never came.
///
/// Two kinds of the grammar are not recorded. `tracks` is the selected format's bitrate, and
/// AVFoundation names the selected variant only on the iOS 18 variant events, while the collector's
/// `TrackSwitched` already carries a video rendition's — a second reading would be the drift the
/// paragraph above refuses. `discontinuity` is a position jump with its reason and both positions,
/// and `AVPlayerItem.timeJumpedNotification` carries none of the three; the collector reconstructs a
/// seek's, and a trace line from that reconstruction would be an inference printed as a fact.
///
/// ## Threads and timing
///
/// Engine facts arrive on the main actor and telemetry on the delivery queue; the recorder is safe
/// to call from both, and ``trace()`` from any third. ``trace()`` does not wait for delivery, for the
/// Kotlin reason, so a trace taken before `SessionEnded` reached this sink lacks the tail.
///
/// Every time is on `CLOCK_MONOTONIC`, the clock telemetry's `monotonicTimeMs` is on: an engine fact
/// is stamped as its handler runs on the main actor — AVFoundation stamps none of them — and a
/// telemetry fact keeps the time its event carries. A request's two times are the metric event's
/// own, which are `Date`s; they are placed on the monotonic clock through the one pair of readings
/// taken together at ``attach(to:)``, so a load sits at the moment it happened rather than at the
/// moment its event was delivered, which is after the request ended. A wall-clock step during a
/// session — an NTP correction — would move every `load` and `bandwidth` line against the other
/// kinds by its size in the *timed* trace; the structural reading a golden compares keeps no order
/// across kinds and is untouched by it.
///
/// Two smaller departures, both masked structurally: `SessionStarted` prints its profile and none of
/// the Kotlin decision's buffer and bitrate fields — the halves exist since #415, and printing them
/// is a change of the trace's lines and its goldens that ticket left to its own change;
/// and a bandwidth sample divides by the transfer's `Date`s in seconds rather than by Media3's
/// whole milliseconds, so the two platforms can differ in the last digits for one transfer.
public final class SessionTraceRecorder: TelemetrySink, Sendable {

    private let recording = OSAllocatedUnfairLock(initialState: Recording())

    /// The engine half, the player's for the life of the attachment; nil when detached.
    private let attachment = OSAllocatedUnfairLock<RecorderAttachment?>(initialState: nil)

    public init() {}

    /// Starts recording `player`. Time in the trace is measured from this call.
    @MainActor
    public func attach(to player: SuperPlayer) {
        let origin = Origin(monotonicMs: QoeCollector.now(), wallClock: Date())
        recording.withLock { recording in
            precondition(
                recording.origin == nil,
                "A SessionTraceRecorder records one player; make one per player")
            recording.origin = origin
        }
        let attached = RecorderAttachment(player: player.avPlayer, recorder: self) {
            [weak player] in player?.currentContentId
        }
        attachment.withLock { $0 = attached }
        attached.start()
    }

    /// Stops recording engine facts. Telemetry handed to the sink after this is still recorded.
    @MainActor
    public func detach() {
        attachment.withLock { attached in
            let current = attached
            attached = nil
            return current
        }?.stop()
    }

    /// Everything recorded so far, in trace order. Safe to call at any time and more than once.
    public func trace() -> SessionTrace {
        let ordered = recording.withLock { $0.entries }.sorted {
            ($0.timeMs, $0.kind, $0.key, $0.sequence) < ($1.timeMs, $1.kind, $1.key, $1.sequence)
        }
        return SessionTrace(
            lines: ordered.map { "+\($0.timeMs) \($0.kind.token) \($0.text)" }, timed: true)
    }

    /// The registrations live on the attached player, per signal — what the register's test
    /// counts, as `QoeCollector.liveObservations` is.
    @MainActor
    var liveObservations: [RecorderSignal: Int] {
        attachment.withLock { $0 }?.liveObservations ?? [:]
    }

    // MARK: - TelemetrySink

    public func onEvent(_ event: TelemetryEvent) {
        // Keyed by nothing, for the Kotlin reason: the delivery queue is FIFO on one thread, so
        // within a millisecond the emission order is the deterministic one, and it keeps
        // SessionEnded last.
        record(
            atMonotonicMs: event.monotonicTimeMs, .telemetry, key: "",
            "\(Self.name(of: event)) \(Self.describe(event))")
    }

    // MARK: - Recording

    /// Records one fact at `atMonotonicMs`. A fact before ``attach(to:)`` has no origin to be
    /// measured from and is dropped: this runs on the delivery queue, where a trap would take the
    /// process down with it rather than be contained (`TelemetrySink.onEvent`'s doc comment).
    func record(atMonotonicMs: Int64, _ kind: TraceKind, key: String, _ text: String) {
        recording.withLock { recording in
            guard let origin = recording.origin else { return }
            recording.entries.append(
                Entry(
                    timeMs: atMonotonicMs - origin.monotonicMs, kind: kind, key: key, text: text,
                    sequence: recording.sequence))
            recording.sequence += 1
        }
    }

    /// `date` on the monotonic clock, through the readings taken together at attach.
    func monotonicMs(of date: Date) -> Int64? {
        recording.withLock { $0.origin }.map {
            $0.monotonicMs + Int64((date.timeIntervalSince($0.wallClock) * 1_000).rounded())
        }
    }

    private struct Origin: Sendable {
        let monotonicMs: Int64
        let wallClock: Date
    }

    private struct Recording: Sendable {
        var origin: Origin?
        var entries: [Entry] = []
        var sequence: Int64 = 0
    }

    private struct Entry: Sendable {
        let timeMs: Int64
        let kind: TraceKind
        let key: String
        let text: String
        let sequence: Int64
    }
}

// MARK: - Telemetry lines

extension SessionTraceRecorder {

    /// The event's name as the Kotlin line prints it — the class's simple name.
    static func name(of event: TelemetryEvent) -> String {
        switch event {
        case .sessionStarted: "SessionStarted"
        case .decisionChanged: "DecisionChanged"
        case .sessionEnded: "SessionEnded"
        case .firstFrameRendered: "FirstFrameRendered"
        case .rebufferStarted: "RebufferStarted"
        case .rebufferEnded: "RebufferEnded"
        case .startupFailed: "StartupFailed"
        case .midStreamFailed: "MidStreamFailed"
        case .trackSwitched: "TrackSwitched"
        case .seekRequested: "SeekRequested"
        case .seekCompleted: "SeekCompleted"
        case .licenceAcquisitionEnded: "LicenceAcquisitionEnded"
        case .liveLatencySampled: "LiveLatencySampled"
        case .playbackStateSampled: "PlaybackStateSampled"
        case .videoFramesDropped: "VideoFramesDropped"
        case .outputObscuredChanged: "OutputObscuredChanged"
        case .displayModeChanged: "DisplayModeChanged"
        }
    }

    /// The event's fields, in the Kotlin line's names and order. A `switch` with no `default`, for
    /// the Kotlin `when`'s reason: a new event kind fails to compile here, so the trace's vocabulary
    /// cannot fall behind the schema's silently.
    static func describe(_ event: TelemetryEvent) -> String {
        switch event {
        case .sessionStarted(let e):
            // The decision's buffer and bitrate fields are not printed yet: the halves exist since
            // #415, and adding them is a change of the trace's lines and goldens of its own.
            return "profile=\(e.profile.kotlinName)"
        case .decisionChanged(let e):
            return "trigger=\(e.trigger.kotlinName)"
        case .sessionEnded(let e):
            return "droppedEventCount=\(e.droppedEventCount)"
        case .firstFrameRendered(let e):
            return
                "timeToFirstFrameMs=\(e.timeToFirstFrameMs) startBoundary=\(e.startBoundary.kotlinName)"
        case .rebufferStarted(let e):
            return "seekInduced=\(e.seekInduced)"
        case .rebufferEnded(let e):
            return "durationMs=\(e.durationMs) seekInduced=\(e.seekInduced)"
        // Rule 3: category and code, never the message.
        case .startupFailed(let e):
            return failure(e.failure)
        case .midStreamFailed(let e):
            return failure(e.failure) + " positionMs=\(e.positionMs)"
        case .trackSwitched(let e):
            return "fromBitrateBps=\(orNull(e.fromBitrateBps)) toBitrateBps=\(e.toBitrateBps) "
                + "direction=\(e.direction.kotlinName)"
        case .seekRequested(let e):
            return "fromPositionMs=\(e.fromPositionMs) toPositionMs=\(e.toPositionMs)"
        case .seekCompleted(let e):
            return "toPositionMs=\(e.toPositionMs) seekLatencyMs=\(e.seekLatencyMs)"
        case .liveLatencySampled(let e):
            return "liveLatencyMs=\(e.liveLatencyMs) "
                + "targetLiveLatencyMs=\(orNull(e.targetLiveLatencyMs))"
        case .playbackStateSampled(let e):
            return "samplingIntervalMs=\(e.samplingIntervalMs) "
                + "videoBitrateBps=\(orNull(e.videoBitrateBps)) "
                + "bufferedDurationMs=\(e.bufferedDurationMs) playing=\(e.playing)"
        // Rule 6 holds here as over a load: a duration, an outcome and a level carry none of the
        // exchange's material.
        case .licenceAcquisitionEnded(let e):
            return "durationMs=\(e.durationMs) outcome=\(e.outcome.kotlinName)"
                + (e.securityLevel.map { " securityLevel=\($0)" } ?? "")
        case .videoFramesDropped(let e):
            return "droppedFrames=\(e.droppedFrames) repeatedFrames=\(e.repeatedFrames) "
                + "elapsedPlayingMs=\(e.elapsedPlayingMs)"
        case .outputObscuredChanged(let e):
            return "obscured=\(e.obscured)"
        case .displayModeChanged(let e):
            return "switchInProgress=\(e.switchInProgress) "
                + "criteriaMatchingEnabled=\(e.criteriaMatchingEnabled)"
        }
    }

    /// `category=… code=…`, and ` classification=…` only where the failure carries one — omitted
    /// rather than printed as a null, for the Kotlin reason (ADR-0011 rule 14). The engine's code is
    /// a `<domain>:<code>` string on iOS, which holds no space and so needs no quoting.
    private static func failure(_ failure: PlaybackFailure) -> String {
        "category=\(failure.category.kotlinName) code=\(orNull(failure.code))"
            + (failure.classification.map { " classification=\($0)" } ?? "")
    }

    /// An optional as the Kotlin string template prints one: the value, or `null`.
    private static func orNull<Value>(_ value: Value?) -> String {
        value.map { "\($0)" } ?? "null"
    }

    /// Rule 1 for the one place a consumer's own string reaches the trace — the Kotlin `itemName`:
    /// anything shaped like a URI is withheld, and an item with no content id is `none`.
    static func itemName(_ contentId: String?) -> String {
        guard let contentId, !contentId.isEmpty else { return "none" }
        return isUriShaped(contentId) ? "uri-withheld" : contentId
    }

    /// A scheme prefix, or a query or fragment: what a URL has and a content id has no reason to —
    /// the Kotlin `URI_SHAPED`, `^[A-Za-z][A-Za-z0-9+.-]*:|[?#]`.
    ///
    /// ref: RFC 3986 §3.1 (scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ))
    static func isUriShaped(_ value: String) -> Bool {
        if value.contains("?") || value.contains("#") { return true }
        guard let colon = value.firstIndex(of: ":"), let first = value.first,
            first.isASCII, first.isLetter
        else { return false }
        return value[..<colon].allSatisfy {
            $0.isASCII && ($0.isLetter || $0.isNumber || "+.-".contains($0))
        }
    }
}

// MARK: - The engine half

/// What the recorder observes of one player, on the main actor, from attach to detach.
@MainActor
final class RecorderAttachment {

    /// The bits in a byte, which a bytes-over-seconds rate takes to reach bits per second.
    private static let bitsPerByte: Double = 8

    private weak var player: AVPlayer?
    private let recorder: SessionTraceRecorder
    private let contentId: @MainActor () -> String?

    private var playerObservations: [RecorderObservation] = []
    private var itemObservations: [RecorderObservation] = []
    /// The item the item-scoped registrations are on.
    private weak var item: AVPlayerItem?
    /// The last `state` and `playing` recorded, so a line is a change and never a repeat.
    private var state: PlayerState?
    private var playing = false
    /// Whether the current item has played, which is what makes a later wait a stall rather than
    /// start-up — the collector's rule that time before the first frame is not a rebuffer.
    private var itemHasPlayed = false

    init(
        player: AVPlayer, recorder: SessionTraceRecorder,
        contentId: @escaping @MainActor () -> String?
    ) {
        self.player = player
        self.recorder = recorder
        self.contentId = contentId
    }

    var liveObservations: [RecorderSignal: Int] {
        (playerObservations + itemObservations).reduce(into: [:]) { counts, observation in
            counts[observation.signal, default: 0] += 1
        }
    }

    func start() {
        guard let player else { return }
        playerObservations = RecorderObservation.registerPlayer(player) { [weak self] in
            self?.handle($0)
        }
        // An item already current at attach is recorded as if it had just been made so.
        currentItemChanged()
    }

    func stop() {
        for observation in playerObservations + itemObservations { observation.cancel() }
        playerObservations = []
        itemObservations = []
    }

    private func handle(_ event: RecorderEvent) {
        switch event {
        case .notified(.currentItem): currentItemChanged()
        case .notified(.timeControlStatus): timeControlStatusChanged()
        case .notified(.itemStatus): itemStatusChanged()
        case .notified(.playedToEnd): setState(.ended)
        case .notified(.requestMetrics): break
        case .request(let report): requestFinished(report)
        }
    }

    private func currentItemChanged() {
        let current = player?.currentItem
        guard current !== item else { return }
        for observation in itemObservations { observation.cancel() }
        itemObservations = []
        item = current
        itemHasPlayed = false
        guard let current else { return }
        // `PLAYLIST_CHANGED` is Media3's reason for content replaced by the app, which is the only
        // way an `AVPlayer` that is not a queue player changes item.
        record(.item, "id=\(SessionTraceRecorder.itemName(contentId())) reason=PLAYLIST_CHANGED")
        state = nil
        setState(.buffering)
        itemObservations = RecorderObservation.registerItem(current) { [weak self] in
            self?.handle($0)
        }
        itemStatusChanged()
    }

    private func itemStatusChanged() {
        guard let item else { return }
        switch item.status {
        case .readyToPlay:
            if state == .buffering && !itemHasPlayed { setState(.ready) }
        case .failed:
            setState(.idle)
            // Rule 3: the domain and code, never the description.
            let code = (item.error as NSError?).map { "\($0.domain):\($0.code)" } ?? "null"
            record(.error, "code=\(code)")
        default:
            break
        }
    }

    private func timeControlStatusChanged() {
        guard let player else { return }
        let nowPlaying = player.timeControlStatus == .playing
        if nowPlaying != playing {
            playing = nowPlaying
            record(.playing, "\(nowPlaying)")
        }
        if nowPlaying {
            itemHasPlayed = true
            if state == .buffering { setState(.ready) }
        } else if itemHasPlayed, state == .ready,
            player.timeControlStatus == .waitingToPlayAtSpecifiedRate,
            player.reasonForWaitingToPlay == .toMinimizeStalls
        {
            setState(.buffering)
        }
    }

    private func setState(_ next: PlayerState) {
        guard next != state else { return }
        state = next
        record(.state, next.rawValue)
    }

    /// Two lines per request, and a bandwidth sample after a network transfer that completed.
    ///
    /// The sample is the transfer's own throughput, never an estimate, for the Kotlin reasons: it
    /// is the quantity an estimate smooths, so a reader sees the dropout rather than the average
    /// that hid it; no byte count is printed; a transfer with no measurable duration has no rate
    /// and none is invented; and only a *completed* transfer is a sample. One exclusion is iOS's:
    /// a request served from AVFoundation's own cache is no reading of the link — the Kotlin
    /// meter's `isNetwork` exclusion, arriving through the event's `wasReadFromCache`.
    private func requestFinished(_ report: RequestReport) {
        guard let startedAt = recorder.monotonicMs(of: report.startedAt),
            let endedAt = recorder.monotonicMs(of: report.endedAt)
        else { return }
        let durationMs = max(0, endedAt - startedAt)
        // Loads are the one kind whose arrival order is not causal, so within a millisecond they
        // sort by what they are and then by phase, so a start precedes its end.
        recorder.record(
            atMonotonicMs: startedAt, .load, key: "\(report.resource) 0",
            "started \(report.resource)")
        let phase = report.failure == nil ? "completed" : "error"
        let cause = report.failure.map { " \($0)" } ?? ""
        recorder.record(
            atMonotonicMs: endedAt, .load, key: "\(report.resource) 1",
            "\(phase) \(report.resource)\(cause) durationMs=\(durationMs)")
        guard report.failure == nil, !report.fromCache, report.bytes > 0 else { return }
        let seconds = report.endedAt.timeIntervalSince(report.startedAt)
        guard seconds > 0 else { return }
        let bitsPerSecond = Int64((Double(report.bytes) * Self.bitsPerByte / seconds).rounded())
        recorder.record(atMonotonicMs: endedAt, .bandwidth, key: "", "bitrateBps=\(bitsPerSecond)")
    }

    private func record(_ kind: TraceKind, _ text: String) {
        recorder.record(atMonotonicMs: QoeCollector.now(), kind, key: "", text)
    }
}

/// A `state` line's word — the Kotlin `stateName`'s four, for the facts iOS reports that are the
/// same fact (``SessionTraceRecorder``'s table says which).
enum PlayerState: String {
    case idle = "IDLE"
    case buffering = "BUFFERING"
    case ready = "READY"
    case ended = "ENDED"
}
