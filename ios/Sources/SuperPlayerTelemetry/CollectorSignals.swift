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

#if canImport(UIKit)
    import UIKit
#endif

/// **The collector's observed register**: every key path, notification, time observer and metric
/// stream ``QoeCollector`` registers, in this one file and nowhere else in `SuperPlayerTelemetry`
/// (ADR-0020 rule 13).
///
/// Core's `ObservedSignals.swift` is the facade's register, and it is deliberately not this one.
/// That file enumerates what the *facade* observes, and its test fails if another core file
/// registers anything; what a collector observes is the collector's, in the product ADR-0008 rule 2
/// puts every engine observation in, and a core enum naming `AVPlayerItemNewAccessLogEntry` would be
/// core deciding what telemetry observes — the reason core hands a collector the player rather than
/// a list of things to watch. So the collector keeps its own, held the same way:
/// `CollectorSignalRegisterTests` counts the live registrations — none on a collector with no
/// session open, each case of ``registered`` exactly once while one is, none again once it ends —
/// and fails if any other file in this product calls a registration API.
///
/// **Everything here is per session.** A collector registers nothing on ``QoeCollector/attach(to:)``
/// and everything on the session's start, and drops it all on the session's end, so a player
/// between sessions — given a bare URL, or recycled — has nothing of the collector's listening.
///
/// Two properties the plan lists are deliberately not observed. `isPlaybackBufferEmpty` is one of
/// the inputs `timeControlStatus` and its waiting reason are AVFoundation's own reading of, so
/// observing it too would be a second source for one stall that could disagree with the first —
/// the inferring the Kotlin collector refuses. `AVPlayerItemNewErrorLogEntry` reports errors the
/// item recovered from, which are not failures in this schema; the error log is read at a failure
/// instead. The sampling timer is the one per-session allocation that is not a registration, and
/// the register's test counts it beside these.
///
/// The Kotlin collector has one registration, `addAnalyticsListener`, because Media3 reports every
/// fact through one interface. AVFoundation reports them through ten, which is why there is an
/// enumeration at all.
enum CollectorSignal: CaseIterable, Hashable, Sendable {

    /// Key-value observation of `AVPlayer.timeControlStatus`: playing, paused, or waiting — and,
    /// read beside it, `reasonForWaitingToPlay`. The first `.playing` is the first frame, a
    /// `.waitingToPlayAtSpecifiedRate` for `.toMinimizeStalls` after it is a rebuffer, and the next
    /// `.playing` ends it.
    case timeControlStatus

    /// Key-value observation of `AVPlayerItem.status`: `.failed` is a startup or mid-stream failure,
    /// and `.readyToPlay` is when the item's tracks — whether it has video at all — are known.
    case itemStatus

    /// Key-value observation of `AVPlayerItem.isPlaybackLikelyToKeepUp`: what completes a seek made
    /// while paused, since a paused player's `timeControlStatus` never moves.
    case likelyToKeepUp

    /// `AVPlayerItem.playbackStalledNotification`: a stall on a player that does not wait to
    /// minimise stalling, whose `timeControlStatus` never says `.waitingToPlayAtSpecifiedRate`.
    case playbackStalled

    /// `AVPlayerItem.timeJumpedNotification`: a seek, or any other discontinuity in the item's time.
    case timeJumped

    /// `AVPlayerItem.newAccessLogEntryNotification`: a changed `indicatedBitrate` is a track switch.
    case newAccessLogEntry

    /// `AVPlayerItem.failedToPlayToEndTimeNotification`: playback that started and then failed.
    case failedToPlayToEnd

    /// `UIApplication.didReceiveMemoryWarningNotification`: the delivery queue gives its pending
    /// events back and counts each one, as the Kotlin `onMemoryPressure` does.
    case memoryWarning

    /// `AVPlayer.addPeriodicTimeObserver(forInterval:queue:using:)` at
    /// ``QoeCollector/positionReadingIntervalMs``: where a seek came *from*, and when one resumed.
    case periodicTime

    /// An `AVMetricEventStream` over the item, iOS and tvOS 18 and later: the SDK's own stall event
    /// and its initial likely-to-keep-up event, with the variant playback started on.
    case metricEvents

    /// The signals a session registers on this OS: every case, less ``metricEvents`` below 18, which
    /// is what the register's test expects rather than ``allCases``.
    static var registered: [CollectorSignal] {
        if #available(iOS 18, tvOS 18, *) { return allCases }
        return allCases.filter { $0 != .metricEvents }
    }

    /// The notification this signal is, for the cases that are one.
    @MainActor
    var notification: Notification.Name? {
        switch self {
        case .playbackStalled: AVPlayerItem.playbackStalledNotification
        case .timeJumped: AVPlayerItem.timeJumpedNotification
        case .newAccessLogEntry: AVPlayerItem.newAccessLogEntryNotification
        case .failedToPlayToEnd: AVPlayerItem.failedToPlayToEndTimeNotification
        case .memoryWarning:
            #if canImport(UIKit)
                UIApplication.didReceiveMemoryWarningNotification
            #else
                nil
            #endif
        case .timeControlStatus, .itemStatus, .likelyToKeepUp, .periodicTime, .metricEvents: nil
        }
    }
}

/// What an `AVMetricEventStream` delivered, reduced to what the collector reads, on the main actor.
enum MetricSignal: Sendable {
    /// The SDK's own stall event.
    case stalled
    /// The item first became likely to keep up, on a variant whose declared peak bitrate is
    /// `videoPeakBitrateBps` — nil when the variant has no video or declares no bitrate.
    case initiallyLikelyToKeepUp(videoPeakBitrateBps: Int?)
}

/// One registration of a ``CollectorSignal``, removed by ``cancel()``. The collector cancels every
/// one at a session's end; nothing else does, so the register's test is what holds the count.
@MainActor
final class CollectorObservation {

    let signal: CollectorSignal

    private var cancellation: (() -> Void)?

    private init(signal: CollectorSignal, cancellation: @escaping () -> Void) {
        self.signal = signal
        self.cancellation = cancellation
    }

    /// Unregisters. Idempotent.
    func cancel() {
        cancellation?()
        cancellation = nil
    }

    /// Registers `handler` for every signal in ``CollectorSignal/registered`` against `player` and
    /// `item`. Every handler runs on the main actor and is told only *that* something changed: it
    /// reads the current state itself, so a change delivered late or twice is reconciled rather than
    /// replayed — the reason no handler is handed a key-value change's old and new values.
    static func registerAll(
        on player: AVPlayer, item: AVPlayerItem, positionIntervalMs: Int64,
        _ handler: @escaping @MainActor @Sendable (CollectorEvent) -> Void
    ) -> [CollectorObservation] {
        CollectorSignal.registered.compactMap { signal in
            register(
                signal, on: player, item: item, positionIntervalMs: positionIntervalMs, handler)
        }
    }

    private static func register(
        _ signal: CollectorSignal, on player: AVPlayer, item: AVPlayerItem,
        positionIntervalMs: Int64,
        _ handler: @escaping @MainActor @Sendable (CollectorEvent) -> Void
    ) -> CollectorObservation? {
        if let name = signal.notification {
            // The memory warning is the application's, not the item's.
            let object: AnyObject? = signal == .memoryWarning ? nil : item
            // A notification posted before the cancellation and queued onto the main queue still
            // runs after it, and would be heard as the *next* item's: a rung re-adopts the content
            // on a new item within one session (`contentReopened(after:)`), so a failure of the item
            // it replaced arriving late would be counted against the one that replaced it.
            let live = Liveness()
            let token = NotificationCenter.default.addObserver(
                forName: name, object: object, queue: .main
            ) { notification in
                let error =
                    notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
                MainActor.assumeIsolated {
                    guard live.isLive else { return }
                    handler(.notified(signal, error: error))
                }
            }
            let tokenBox = UncheckedSendable(token)
            return CollectorObservation(signal: signal) {
                live.isLive = false
                NotificationCenter.default.removeObserver(tokenBox.value)
            }
        }
        switch signal {
        case .timeControlStatus:
            return keyValue(signal, handler) {
                player.observe(\.timeControlStatus, changeHandler: $0)
            }
        case .itemStatus:
            return keyValue(signal, handler) { item.observe(\.status, changeHandler: $0) }
        case .likelyToKeepUp:
            return keyValue(signal, handler) {
                item.observe(\.isPlaybackLikelyToKeepUp, changeHandler: $0)
            }
        case .periodicTime:
            let token = player.addPeriodicTimeObserver(
                forInterval: CMTime(value: positionIntervalMs, timescale: 1_000), queue: .main
            ) { time in
                MainActor.assumeIsolated { handler(.positionRead(time)) }
            }
            let tokenBox = UncheckedSendable(token)
            return CollectorObservation(signal: signal) { [weak player] in
                player?.removeTimeObserver(tokenBox.value)
            }
        case .metricEvents:
            guard #available(iOS 18, tvOS 18, *) else { return nil }
            let subscription = MetricSubscription(item: item) { handler(.metric($0)) }
            return CollectorObservation(signal: signal) { subscription.cancel() }
        case .playbackStalled, .timeJumped, .newAccessLogEntry, .failedToPlayToEnd,
            .memoryWarning:
            return nil
        }
    }

    /// A key-value observation whose change is re-delivered on the main actor.
    ///
    /// Apple names no thread for these key paths' changes, so the handler `observe` is given never
    /// trusts it is on the main thread: it hops there when it is not, and the collector reads the
    /// property's current value when it arrives — which is why nothing is lost by the hop reordering
    /// two changes.
    private static func keyValue<Object: NSObject, Value>(
        _ signal: CollectorSignal,
        _ handler: @escaping @MainActor @Sendable (CollectorEvent) -> Void,
        _ observe: (@escaping @Sendable (Object, NSKeyValueObservedChange<Value>) -> Void) ->
            NSKeyValueObservation
    ) -> CollectorObservation {
        let observation = observe { _, _ in
            if Thread.isMainThread {
                MainActor.assumeIsolated { handler(.notified(signal, error: nil)) }
            } else {
                DispatchQueue.main.async {
                    MainActor.assumeIsolated { handler(.notified(signal, error: nil)) }
                }
            }
        }
        return CollectorObservation(signal: signal) { observation.invalidate() }
    }
}

/// The iOS 18 metric stream over one item: the SDK's stall event and its initial likely-to-keep-up
/// event, merged in the order they happened and reduced to a ``MetricSignal`` on the main actor.
///
/// The two request streams the plan also names — `AVMetricHLSPlaylistRequestEvent` and
/// `AVMetricHLSMediaSegmentRequestEvent` — are not subscribed: no metric in
/// `docs/telemetry-schema.md` is per request, and a subscription nothing reads is what ADR-0020
/// rule 13 forbids. They are the session trace recorder's `load` and `bandwidth` kinds (#411).
///
/// ref: https://developer.apple.com/documentation/avfoundation/avmetriceventstreampublisher/metrics(fortype:)
@available(iOS 18, tvOS 18, *)
@MainActor
final class MetricSubscription {

    private var task: Task<Void, Never>?

    init(item: AVPlayerItem, _ handler: @escaping @MainActor @Sendable (MetricSignal) -> Void) {
        let events = item.metrics(forType: AVMetricPlayerItemStallEvent.self)
            .chronologicalMerge(
                with: item.metrics(forType: AVMetricPlayerItemInitialLikelyToKeepUpEvent.self))
        task = Task { @MainActor in
            do {
                for try await (event, _) in events {
                    if let signal = Self.signal(of: event) { handler(signal) }
                }
            } catch {
                // A stream that ends in an error ends this subscription and nothing else: the
                // key-value observations above still report every stall the event would have.
            }
        }
    }

    /// Ends the iteration, which is the whole of unsubscribing.
    func cancel() {
        task?.cancel()
        task = nil
    }

    private static func signal(of event: AVMetricEvent) -> MetricSignal? {
        if let initial = event as? AVMetricPlayerItemInitialLikelyToKeepUpEvent {
            let variant = initial.variant
            let peak = variant?.peakBitRate ?? variant?.averageBitRate
            let hasVideo = variant?.videoAttributes != nil
            return .initiallyLikelyToKeepUp(
                videoPeakBitrateBps: hasVideo ? peak.flatMap(QoeCollector.bitrate) : nil)
        }
        return event is AVMetricPlayerItemStallEvent ? .stalled : nil
    }
}

/// What a registration reports to the collector.
enum CollectorEvent {
    /// A key-value observed property or a notification of `signal` changed or fired; `error` is the
    /// failed-to-play-to-end notification's own, where it carried one.
    case notified(CollectorSignal, error: NSError?)
    /// The periodic time observer read the player's time.
    case positionRead(CMTime)
    /// An `AVMetricEventStream` delivered an event.
    case metric(MetricSignal)
}

/// Whether a registration is still wanted, read by a delivery already queued when it was cancelled.
/// Unchecked, and safe: it is written by a cancellation and read by a delivery, both on the main
/// queue, so it is never touched from two threads at once.
final class Liveness: @unchecked Sendable {
    var isLive = true
}

/// A value the compiler cannot see is safe to send, asserted so where the reason is local: a
/// notification token and a time-observer token are opaque handles only ever handed back to the API
/// that made them.
struct UncheckedSendable<Value>: @unchecked Sendable {
    let value: Value
    init(_ value: Value) { self.value = value }
}
