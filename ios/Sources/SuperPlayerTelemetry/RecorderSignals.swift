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

/// **The recorder's observed register**: every key path, notification and metric stream
/// ``SessionTraceRecorder`` registers, in this one file and nowhere else in `SuperPlayerTelemetry`
/// (ADR-0020 rule 13).
///
/// A register of its own rather than more cases of `CollectorSignals.swift`'s, for two reasons.
/// The lifetimes differ: the collector registers per *session* and nothing between sessions, while
/// a recorder registers from ``SessionTraceRecorder/attach(to:)`` to
/// ``SessionTraceRecorder/detach()`` — the player for that whole span, and each current item while
/// it is current — so one enumeration would hold two rules about when a case is live. And the sets
/// differ: the request metric streams are the recorder's alone, which `CollectorSignals.swift`'s
/// `MetricSubscription` says it does not subscribe to because no metric reads them, and a shared
/// enumeration would make the collector's register test count signals the collector never makes.
/// So the recorder's is held the same way as the collector's: `RecorderSignalRegisterTests` counts
/// the live registrations — none before attaching, each case once per scope while attached, none
/// after detaching — and the product's "nowhere else" scan admits this file beside that one.
///
/// The Kotlin recorder has one registration, `addAnalyticsListener`, for the collector's reason:
/// Media3 reports every fact through one interface and AVFoundation does not.
enum RecorderSignal: CaseIterable, Hashable, Sendable {

    /// Key-value observation of `AVPlayer.currentItem`: an item made current is the `item` line,
    /// and moves every item-scoped registration to it.
    case currentItem

    /// Key-value observation of `AVPlayer.timeControlStatus`: the `playing` line, and a stall after
    /// playback began, which is the `state` line's `BUFFERING`.
    case timeControlStatus

    /// Key-value observation of `AVPlayerItem.status`: `.readyToPlay` is `READY`, and `.failed` is
    /// `IDLE` and an `error` line — Media3's own state after a fatal error.
    case itemStatus

    /// `AVPlayerItem.didPlayToEndTimeNotification`: `ENDED`.
    case playedToEnd

    /// The `AVMetricHLSPlaylistRequestEvent` and `AVMetricHLSMediaSegmentRequestEvent` streams over
    /// the item, merged in the order they happened — iOS and tvOS 18 and later. The `load` and
    /// `bandwidth` lines.
    case requestMetrics

    /// Whether this signal is the player's, for the whole attachment, or the current item's.
    var isItemScoped: Bool {
        switch self {
        case .currentItem, .timeControlStatus: false
        case .itemStatus, .playedToEnd, .requestMetrics: true
        }
    }

    /// The signals registered on this OS: every case, less ``requestMetrics`` below 18 — which is
    /// what the register's test expects, rather than ``allCases``.
    static var registered: [RecorderSignal] {
        if #available(iOS 18, tvOS 18, *) { return allCases }
        return allCases.filter { $0 != .requestMetrics }
    }
}

/// What a recorder registration reports, on the main actor.
enum RecorderEvent {
    /// A key-value observed property changed, or a notification fired. The handler reads the state
    /// it needs itself, for the collector's reason: a change delivered late or twice is then
    /// reconciled rather than replayed.
    case notified(RecorderSignal)
    /// A request finished, as the metric stream reported it.
    case request(RequestReport)
}

/// One HLS request as the metric stream reported it, reduced to what a trace may hold: what was
/// asked for, when, how much arrived, and how it ended — never where from (redaction rules 1, 2 and
/// 4: the event's `url`, `indexFileURL`, `serverAddress`, `sessionID` and response headers are
/// never read).
struct RequestReport: Sendable {
    /// `manifest`, `init` or `media`, with a track type after a colon where the event names one.
    let resource: String
    let startedAt: Date
    let endedAt: Date
    /// Response body bytes, summed over the request's transactions; zero where none were reported.
    let bytes: Int64
    /// Served from AVFoundation's own cache rather than the network.
    let fromCache: Bool
    /// `http=<status>` or `cause=<domain>:<code>` for a request that failed, else nil.
    let failure: String?
}

/// One registration of a ``RecorderSignal``, removed by ``cancel()``.
@MainActor
final class RecorderObservation {

    let signal: RecorderSignal

    private var cancellation: (() -> Void)?

    private init(signal: RecorderSignal, cancellation: @escaping () -> Void) {
        self.signal = signal
        self.cancellation = cancellation
    }

    /// Unregisters. Idempotent.
    func cancel() {
        cancellation?()
        cancellation = nil
    }

    /// Registers every player-scoped signal on `player`.
    static func registerPlayer(
        _ player: AVPlayer, _ handler: @escaping @MainActor @Sendable (RecorderEvent) -> Void
    ) -> [RecorderObservation] {
        [
            keyValue(.currentItem, handler) { player.observe(\.currentItem, changeHandler: $0) },
            keyValue(.timeControlStatus, handler) {
                player.observe(\.timeControlStatus, changeHandler: $0)
            },
        ]
    }

    /// Registers every item-scoped signal in ``RecorderSignal/registered`` on `item`.
    static func registerItem(
        _ item: AVPlayerItem, _ handler: @escaping @MainActor @Sendable (RecorderEvent) -> Void
    ) -> [RecorderObservation] {
        RecorderSignal.registered.filter(\.isItemScoped).compactMap { signal in
            switch signal {
            case .itemStatus:
                return keyValue(signal, handler) { item.observe(\.status, changeHandler: $0) }
            case .playedToEnd:
                let token = NotificationCenter.default.addObserver(
                    forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main
                ) { _ in
                    MainActor.assumeIsolated { handler(.notified(.playedToEnd)) }
                }
                let tokenBox = UncheckedSendable(token)
                return RecorderObservation(signal: signal) {
                    NotificationCenter.default.removeObserver(tokenBox.value)
                }
            case .requestMetrics:
                guard #available(iOS 18, tvOS 18, *) else { return nil }
                let subscription = RequestMetricSubscription(item: item) {
                    handler(.request($0))
                }
                return RecorderObservation(signal: signal) { subscription.cancel() }
            case .currentItem, .timeControlStatus:
                return nil
            }
        }
    }

    /// A key-value observation whose change is re-delivered on the main actor — the collector's
    /// `keyValue`, for its reason: Apple names no thread for these changes.
    private static func keyValue<Object: NSObject, Value>(
        _ signal: RecorderSignal,
        _ handler: @escaping @MainActor @Sendable (RecorderEvent) -> Void,
        _ observe: (@escaping @Sendable (Object, NSKeyValueObservedChange<Value>) -> Void) ->
            NSKeyValueObservation
    ) -> RecorderObservation {
        let observation = observe { _, _ in
            if Thread.isMainThread {
                MainActor.assumeIsolated { handler(.notified(signal)) }
            } else {
                DispatchQueue.main.async {
                    MainActor.assumeIsolated { handler(.notified(signal)) }
                }
            }
        }
        return RecorderObservation(signal: signal) { observation.invalidate() }
    }
}

/// The iOS 18 request streams over one item, reduced to a ``RequestReport`` per finished request.
///
/// These are the two streams `CollectorSignals.swift`'s `MetricSubscription` names and leaves to
/// this recorder. An event arrives once its request has ended and carries the request's own start
/// and end, so a `load started` line is placed at the time the request began rather than the time
/// the event arrived.
///
/// ref: https://developer.apple.com/documentation/avfoundation/avmetrichlsmediasegmentrequestevent
/// ref: https://developer.apple.com/documentation/avfoundation/avmetricmediaresourcerequestevent
@available(iOS 18, tvOS 18, *)
@MainActor
final class RequestMetricSubscription {

    private var task: Task<Void, Never>?

    init(item: AVPlayerItem, _ handler: @escaping @MainActor @Sendable (RequestReport) -> Void) {
        let events = item.metrics(forType: AVMetricHLSPlaylistRequestEvent.self)
            .chronologicalMerge(
                with: item.metrics(forType: AVMetricHLSMediaSegmentRequestEvent.self))
        task = Task { @MainActor in
            do {
                for try await (event, _) in events {
                    if let report = Self.report(of: event) { handler(report) }
                }
            } catch {
                // A stream that ends in an error ends these lines and nothing else: the trace's
                // other kinds come from registrations that do not depend on it.
            }
        }
    }

    /// Ends the iteration, which is the whole of unsubscribing.
    func cancel() {
        task?.cancel()
        task = nil
    }

    private static func report(of event: AVMetricEvent) -> RequestReport? {
        let resource: String
        let request: AVMetricMediaResourceRequestEvent?
        if let playlist = event as? AVMetricHLSPlaylistRequestEvent {
            // Media3 calls a multivariant and a media playlist both `manifest`, and so does this.
            resource = "manifest"
            request = playlist.mediaResourceRequestEvent
        } else if let segment = event as? AVMetricHLSMediaSegmentRequestEvent {
            let dataType = segment.isMapSegment ? "init" : "media"
            resource = trackType(segment.mediaType).map { "\(dataType):\($0)" } ?? dataType
            request = segment.mediaResourceRequestEvent
        } else {
            return nil
        }
        guard let request else { return nil }
        let transactions = request.networkTransactionMetrics?.transactionMetrics ?? []
        return RequestReport(
            resource: resource, startedAt: request.requestStartTime,
            endedAt: request.responseEndTime,
            bytes: transactions.reduce(0) { $0 + $1.countOfResponseBodyBytesReceived },
            fromCache: request.wasReadFromCache,
            failure: failure(of: request, transactions: transactions))
    }

    /// Rule 3: the status where the response carried one, else the error's domain and code —
    /// never a message, which names the URL.
    private static func failure(
        of request: AVMetricMediaResourceRequestEvent,
        transactions: [URLSessionTaskTransactionMetrics]
    ) -> String? {
        let status = (transactions.last?.response as? HTTPURLResponse)?.statusCode
        if let status, status >= 400 { return "http=\(status)" }
        guard let error = request.errorEvent?.error as NSError? else { return nil }
        return "cause=\(error.domain):\(error.code)"
    }

    /// Media3's track-type words for the media types an HLS segment can be, nil for one that names
    /// no single track — a muxed segment — as Media3 prints none for an unknown track.
    private static func trackType(_ type: AVMediaType) -> String? {
        switch type {
        case .video: "video"
        case .audio: "audio"
        case .subtitle, .closedCaption, .text: "text"
        case .metadata: "metadata"
        default: nil
        }
    }
}
