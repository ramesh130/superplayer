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
import Network

#if canImport(CoreTelephony)
    import CoreTelephony
#endif

/// **The one place a platform reading becomes a ``PlaybackConditions`` observation** — the Kotlin
/// `ConditionsBinding.kt`, the inverse of `EngineBinding.swift` and as confined (ADR-0009 rule 3,
/// `docs/ios-plan.md` §3.3's third row).
///
/// Each function translates one reading and decides nothing: *when* to read is
/// `DecisionReapplication.swift`'s, and what to do with an observation is the policy's. The readers
/// are `package` and take the platform's values as plain arguments, so `ConditionsBindingTests`
/// states a path, a radio or a memory reading and checks the observation without a device — the
/// translations a simulator could never exercise (a cellular path, a personal hotspot) are the ones
/// most worth checking.
///
/// What each observation is read from:
///
/// - **The transport** from `NWPathMonitor`'s path: its interface type and `isExpensive`
///   (``transport(of:cellularGeneration:)``), with the generation from CoreTelephony's radio access
///   technology on iOS. `isConstrained` — Low Data Mode — is read nowhere here: it is not a
///   transport and not one of ADR-0009 rule 1's six, and its Android twin, Data Saver, is a
///   platform rule of the preload coordinator's (ADR-0010 rule 11), which is i4's to read.
/// - **The throughput** from the item's access log (``AccessLogThroughput``).
/// - **The stall history** from `AVPlayer.timeControlStatus` (``StallObservation``), on the rule
///   the collector's rebuffer uses — derived again here rather than read off the collector,
///   because core cannot depend on `SuperPlayerTelemetry` and a player with a policy and no
///   telemetry has a stall history too.
/// - **The stream type** from the item's `duration` (``streamType(duration:)``).
/// - **The heap** from `os_proc_available_memory()` (``heapBudgetBytes(availableMemoryBytes:)``).
/// - **The rate** from `AVPlayer.rate` (``playbackSpeed(rate:)``).
package enum ConditionsBinding {

    /// The parts of an `NWPath` a transport is read from, as plain values a test can state.
    package struct PathReading: Sendable, Hashable {

        /// The interface the path's bytes cross, in the order a VPN makes necessary — the Kotlin
        /// binding's: a VPN over WiFi reports both, and the underlying one is the one that matters.
        package enum Interface: Sendable, Hashable {
            case wifi, wiredEthernet, cellular, other
            /// The path is not satisfied: no network at all.
            case none
        }

        package var interface: Interface

        /// `NWPath.isExpensive`: cellular, or a personal hotspot reached over WiFi or a cable.
        package var isExpensive: Bool

        package init(interface: Interface, isExpensive: Bool) {
            self.interface = interface
            self.isExpensive = isExpensive
        }
    }

    /// `path` as a ``PathReading``.
    ///
    /// ref: https://developer.apple.com/documentation/network/nwpath/usesinterfacetype(_:)
    package static func reading(of path: NWPath) -> PathReading {
        let interface: PathReading.Interface
        if path.status != .satisfied {
            interface = .none
        } else if path.usesInterfaceType(.wifi) {
            interface = .wifi
        } else if path.usesInterfaceType(.wiredEthernet) {
            interface = .wiredEthernet
        } else if path.usesInterfaceType(.cellular) {
            interface = .cellular
        } else {
            interface = .other
        }
        return PathReading(interface: interface, isExpensive: path.isExpensive)
    }

    /// The ``NetworkTransport`` of `reading`, the device's own radio being of `cellularGeneration`.
    ///
    /// **An expensive WiFi or wired path is a cellular one.** Apple marks a path expensive when it
    /// crosses "an interface that is considered expensive, such as Cellular or a Personal Hotspot",
    /// and a hotspot is a phone's cellular link reached over WiFi or a cable: the bytes cross a
    /// cellular network, and a policy that took it for WiFi would size a home link's buffer and,
    /// once `SuperPlayerABR` remembers estimates per transport (ADR-0009 rule 8), mix a hotspot's
    /// throughput into the home network's. Its generation is the *other* phone's and nothing here
    /// can read it, so it is nil. The Kotlin binding has no such case because Android reports a
    /// tethered network as WiFi and says so only through a metered flag no observation carries;
    /// this is one reading closer to the bytes, and the same vocabulary.
    ///
    /// No network is ``NetworkTransport/unknown``, for the Kotlin reason: connected to nothing is a
    /// fact about the transport, where *unobserved* — nil — is a fact about SuperPlayer.
    ///
    /// ref: https://developer.apple.com/documentation/network/nwpath/isexpensive
    package static func transport(
        of reading: PathReading, cellularGeneration: CellularGeneration?
    ) -> NetworkTransport {
        switch reading.interface {
        case .wifi: reading.isExpensive ? .cellular(nil) : .wifi
        case .wiredEthernet: reading.isExpensive ? .cellular(nil) : .ethernet
        case .cellular: .cellular(cellularGeneration)
        case .other, .none: .unknown
        }
    }

    /// The generation a radio access technology names, or nil where none is reported — the Kotlin
    /// `cellularGenerationOf`'s collapse: NR (standalone or not) and LTE apart, everything earlier
    /// as one. Always nil where CoreTelephony is absent, which is tvOS: a television has no radio.
    ///
    /// Unlike Android's reading, iOS's needs no permission, so it is read whenever a path is.
    ///
    /// ref: https://developer.apple.com/documentation/coretelephony/cttelephonynetworkinfo/servicecurrentradioaccesstechnology
    package static func cellularGeneration(radioAccessTechnology: String?) -> CellularGeneration? {
        guard let radioAccessTechnology else { return nil }
        #if canImport(CoreTelephony)
            switch radioAccessTechnology {
            case CTRadioAccessTechnologyNR, CTRadioAccessTechnologyNRNSA: return .nr
            case CTRadioAccessTechnologyLTE: return .lte
            default: return .older
            }
        #else
            return nil
        #endif
    }

    /// The radio access technology of the service carrying data now, or nil where there is none —
    /// the platform half of ``cellularGeneration(radioAccessTechnology:)``.
    @MainActor
    static func currentRadioAccessTechnology() -> String? {
        #if canImport(CoreTelephony)
            let info = CTTelephonyNetworkInfo()
            guard let service = info.dataServiceIdentifier else { return nil }
            return info.serviceCurrentRadioAccessTechnology?[service]
        #else
            return nil
        #endif
    }

    /// The heap budget a reading of `os_proc_available_memory()` gives, or nil where it is zero.
    ///
    /// **What is left, where the Kotlin budget is the whole allowance.** Android reads
    /// `ActivityManager.memoryClass`, a fixed allowance, because a live free-heap reading under a
    /// garbage collector is noise. iOS has no garbage collector and no fixed allowance a process
    /// can read; `os_proc_available_memory()` is the process's limit less its footprint, the reading
    /// `DeviceCapacity.swift` already budgets the pool against, for its argument — an app that has
    /// spent its memory should be told it has less. Read once, as the player is made, so it is a
    /// budget for this player's lifetime rather than a reading that moves under a policy.
    ///
    /// Zero is nil: Apple's header gives zero for a process that is not an app — an `xctest`
    /// runner — and one past its limit, and "the platform did not say" is the honest reading of
    /// both.
    ///
    /// ref: `<os/proc.h>`, `os_proc_available_memory`
    package static func heapBudgetBytes(availableMemoryBytes: UInt64) -> Int64? {
        availableMemoryBytes == 0 ? nil : Int64(clamping: availableMemoryBytes)
    }

    /// Each entry's `observedBitrate` in `item`'s access log, oldest first — the throughput's
    /// reading, which ``AccessLogThroughput`` takes. Empty before the log has an entry.
    ///
    /// ref: https://developer.apple.com/documentation/avfoundation/avplayeritemaccesslogevent/observedbitrate
    @MainActor
    static func observedBitrates(of item: AVPlayerItem) -> [Double] {
        item.accessLog()?.events.map(\.observedBitrate) ?? []
    }

    /// What an item's `duration` says the content is: live when indefinite, on-demand when it is a
    /// number, nil until the playlist has been read.
    ///
    /// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/duration — "the
    /// value of this property is indefinite" for live streams.
    package static func streamType(duration: CMTime) -> StreamType? {
        if duration.isIndefinite { return .live }
        if duration.isNumeric { return .onDemand }
        return nil
    }

    /// The playback speed `AVPlayer.rate` reports, or nil for a rate of zero.
    ///
    /// Zero is a pause, not a speed: the Kotlin speed is Media3's `PlaybackParameters.speed`, which a
    /// pause leaves alone, so a pause here leaves the observation where it was rather than
    /// re-consulting a policy about a speed nobody chose. A negative rate is reverse play, a speed
    /// all the same — its magnitude, as a buffer drains at it either way.
    package static func playbackSpeed(rate: Float) -> Float? {
        rate == 0 ? nil : abs(rate)
    }

    /// What `timeControlStatus`, and the reason beside it, say the player is doing — the input
    /// ``StallObservation`` takes. Only a wait *to minimise stalls* is a stall, which is the
    /// collector's line (`docs/telemetry-schema.md`, *How derived on iOS* for `RebufferStarted`): a
    /// wait for an item, or while the rate is evaluated at start, is not one.
    package static func activity(
        status: AVPlayer.TimeControlStatus, reason: AVPlayer.WaitingReason?
    ) -> StallObservation.Activity {
        switch status {
        case .playing: .playing
        case .paused: .paused
        case .waitingToPlayAtSpecifiedRate: reason == .toMinimizeStalls ? .stalled : .other
        @unknown default: .other
        }
    }

    /// `CLOCK_MONOTONIC` in milliseconds: the clock every duration here is measured on, as
    /// ``PlaybackConditions`` says.
    static func monotonicNowMs() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000)
    }
}

/// The stall history of one item, from the player's own activity — the Kotlin `StallObservation`,
/// on its rule: a stall after playback started that ends in playing again is a rebuffer, and one a
/// seek caused, or that ends in a pause, is not.
package struct StallObservation: Sendable {

    /// What the player is doing, as ``ConditionsBinding/activity(status:reason:)`` reads it.
    package enum Activity: Sendable {
        case playing, stalled, paused, other
    }

    private var playedOnce = false
    private var stallStartedAtMs: Int64?
    private var seekPending = false
    private var seekCompletedAtMs: Int64?
    private var rebufferCount = 0
    private var lastRebufferEndedAtMs: Int64?
    private var lastRebufferDurationMs: Int64?

    package init() {}

    /// A seek moved the item's time at `nowMs`. One made while playing completes as it is made; one
    /// made otherwise completes when playback next starts.
    package mutating func onSeek(atMs nowMs: Int64, playing: Bool) {
        if playing {
            seekCompletedAtMs = nowMs
        } else {
            seekPending = true
        }
    }

    /// The player became `activity` at `nowMs`. True when a rebuffer ended.
    package mutating func onActivity(_ activity: Activity, atMs nowMs: Int64) -> Bool {
        switch activity {
        case .stalled:
            if playedOnce && stallStartedAtMs == nil && !isSeekInduced(atMs: nowMs) {
                stallStartedAtMs = nowMs
            }
            return false
        case .playing:
            playedOnce = true
            if seekPending {
                seekPending = false
                seekCompletedAtMs = nowMs
            }
            guard let startedAt = stallStartedAtMs else { return false }
            stallStartedAtMs = nil
            rebufferCount += 1
            lastRebufferEndedAtMs = nowMs
            lastRebufferDurationMs = nowMs - startedAt
            return true
        case .paused:
            stallStartedAtMs = nil
            seekPending = false
            return false
        case .other:
            return false
        }
    }

    /// The history as of `nowMs`: the "since" is a duration, and durations age.
    package func current(atMs nowMs: Int64) -> StallHistory {
        guard let endedAt = lastRebufferEndedAtMs else { return .none }
        return StallHistory(
            rebufferCount: rebufferCount, msSinceLastRebufferEnded: nowMs - endedAt,
            lastRebufferDurationMs: lastRebufferDurationMs)
    }

    private func isSeekInduced(atMs nowMs: Int64) -> Bool {
        if seekPending { return true }
        guard let completedAt = seekCompletedAtMs else { return false }
        return nowMs - completedAt <= Self.seekExclusionWindowMs
    }

    /// `docs/telemetry-schema.md`, *Rebuffering* — the number, and why it is one second; the Kotlin
    /// `SEEK_EXCLUSION_WINDOW_MS` and the collector's `seekExclusionWindowMs`.
    static let seekExclusionWindowMs: Int64 = 1_000
}

/// A throughput estimate from an item's access log — the reading `docs/ios-plan.md` §3.3 gives iOS
/// in place of the Kotlin meter's sampled transfers.
///
/// Each sample is one access-log entry's `observedBitrate`, the SDK's own measure of what was
/// transferred under that entry, and the window is the most recent ``windowEntries`` of them. The
/// three readings are `SuperPlayerABR`'s `SampleWindow`'s on Android — mean, population spread, and
/// the nearest-rank ``conservativePercentile`` — in integer bits per second rounded down, so a value
/// a test computes by hand is the value this returns. The per-transport memory, cold defaults and
/// aging are not here: they are the oracle's (ADR-0009 rule 8), which is i3's ABR half.
package struct AccessLogThroughput: Sendable {

    private var samplesBps: [Int64] = []
    private var newestAtMs: Int64?
    private var lastAnnouncedMeanBps: Int64?

    package init() {}

    /// The access log read at `nowMs`: each entry's `observedBitrate`, oldest first. A negative
    /// value is the SDK's "not measured" and is not a sample.
    package mutating func read(observedBitratesBps: [Double], atMs nowMs: Int64) {
        let samples = observedBitratesBps.filter { $0.isFinite && $0 > 0 }
            .suffix(Self.windowEntries).map { Int64($0) }
        guard samples != samplesBps else { return }
        samplesBps = samples
        newestAtMs = samples.isEmpty ? nil : nowMs
    }

    /// The estimate at `nowMs`, or nil before the log has recorded a transfer.
    package func estimate(atMs nowMs: Int64) -> ThroughputEstimate? {
        guard let newestAtMs, !samplesBps.isEmpty else { return nil }
        let count = Int64(samplesBps.count)
        let sum = samplesBps.reduce(0, +)
        let mean = sum / count
        let exactMean = Double(sum) / Double(count)
        let variance =
            samplesBps.map { (Double($0) - exactMean) * (Double($0) - exactMean) }
            .reduce(0, +) / Double(count)
        let sorted = samplesBps.sorted()
        let rank = Int(
            (Double(Self.conservativePercentile) / 100 * Double(sorted.count)).rounded(.up))
        return ThroughputEstimate(
            meanBps: mean, spreadBps: Int64(variance.squareRoot()),
            conservativeBps: sorted[rank - 1], sampleCount: samplesBps.count,
            newestSampleAgeMs: max(0, nowMs - newestAtMs))
    }

    /// Whether the estimate's mean has moved by ``materialMoveFraction`` of the last one announced —
    /// or appeared — since this last said yes; saying yes records the mean as announced. ADR-0009
    /// rule 4's "materially", applied here because on iOS the estimate is core's to make.
    package mutating func movedMaterially(atMs nowMs: Int64) -> Bool {
        guard let mean = estimate(atMs: nowMs)?.meanBps else { return false }
        if let last = lastAnnouncedMeanBps,
            Double(abs(mean - last)) < Double(last) * Self.materialMoveFraction
        {
            return false
        }
        lastAnnouncedMeanBps = mean
        return true
    }

    /// The Kotlin `SampleWindow.WINDOW_SAMPLES`, eight, for its reason: long enough to see the
    /// variability, short enough to track a change. ref: Mao, Netravali and Alizadeh, SIGCOMM 2017,
    /// §4. An access-log entry spans more than one segment, so eight entries reach further back than
    /// eight Android samples; the number is kept so the spread is a reading of as many values on
    /// both platforms.
    package static let windowEntries = 8

    /// The Kotlin `SampleWindow.CONSERVATIVE_PERCENTILE`, the 25th, for its reason: a throughput the
    /// network delivered three times in four, not the minimum one dropout would pin.
    /// ref: Yin, Jindal, Sekar and Sinopoli, SIGCOMM 2015, §5.1.
    package static let conservativePercentile = 25

    /// The Kotlin `OracleBandwidthMeter.MATERIAL_MOVE_FRACTION`, a fifth, for its reason: adjacent
    /// rungs of every published ladder differ by more, so a smaller move could change no selection.
    /// ref: https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
    package static let materialMoveFraction = 0.2
}
