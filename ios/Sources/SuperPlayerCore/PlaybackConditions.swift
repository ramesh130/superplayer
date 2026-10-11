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

/// What is known about the playback at the moment a ``PlaybackPolicy`` is asked for a decision —
/// the Kotlin `PlaybackConditions`, the same six observations (ADR-0009 rule 1).
///
/// Every property is an observation in SuperPlayer's own vocabulary — no AVFoundation or Network
/// type appears here (ADR-0005 rule 1) — and every one is optional. An observation not yet made is
/// nil, or ``StallHistory/none``, and that is distinct from zero: a policy treats a value it was not
/// given as *unknown*, never as nothing. `PlaybackConditions()` is therefore "nothing observed".
///
/// The list is closed, for the Kotlin reason: these are what the policies read, and an observation
/// nothing reads is removed rather than kept for later (ADR-0005's standing obligation; ADR-0009
/// rule 2 lists what was deferred and to which phase). How each is produced on iOS is
/// `ConditionsBinding.swift`'s, the one place that translation happens (rule 3), and each property
/// below names its reading.
public struct PlaybackConditions: Sendable, Hashable {

    /// The class of network the playback is on, or nil if unobserved — from `NWPathMonitor`.
    public var transport: NetworkTransport?

    /// What the network is believed to deliver, or nil before the item's access log has recorded a
    /// transfer — from the access log's `observedBitrate` over its recent entries.
    public var throughput: ThroughputEstimate?

    /// What this session has already suffered — from the player's own `timeControlStatus`, on the
    /// rule the telemetry schema's rebuffer uses. ``StallHistory/none`` until a rebuffer has ended,
    /// and again from the start of every item.
    public var stallHistory: StallHistory

    /// What the playlist declared the content to be, or nil until it has been read — from the
    /// item's `duration`, indefinite for live content. An observation rather than the profile, for
    /// the Kotlin reason: the profile is the consumer's *intent* and this is what the content turned
    /// out to be, and an event playlist that gains `EXT-X-ENDLIST` changes under a playing session.
    public var streamType: StreamType?

    /// The memory this app may still use, in bytes, or nil if the platform did not say — from
    /// `os_proc_available_memory()`, read once as the player is made. The Kotlin
    /// `heapBudgetBytes`; `ConditionsBinding.swift` says why the iOS reading is what is left rather
    /// than the whole allowance.
    public var heapBudgetBytes: Int64?

    /// The current playback rate as a factor, or nil if unobserved — from `AVPlayer.rate`, the last
    /// rate it played at rather than the zero a pause reads. `1.0` is real time.
    public var playbackSpeed: Float?

    public init(
        transport: NetworkTransport? = nil, throughput: ThroughputEstimate? = nil,
        stallHistory: StallHistory = .none, streamType: StreamType? = nil,
        heapBudgetBytes: Int64? = nil, playbackSpeed: Float? = nil
    ) {
        precondition(
            heapBudgetBytes.map { $0 > 0 } ?? true, "heapBudgetBytes must be positive when observed"
        )
        precondition(
            playbackSpeed.map { $0 > 0 } ?? true, "playbackSpeed must be positive when observed")
        self.transport = transport
        self.throughput = throughput
        self.stallHistory = stallHistory
        self.streamType = streamType
        self.heapBudgetBytes = heapBudgetBytes
        self.playbackSpeed = playbackSpeed
    }
}

/// The class of network a playback is on — the Kotlin `NetworkTransport`, deliberately coarse for
/// its reason: the coarsest key that still carries the difference a policy cares about, and nothing
/// that could identify a network or a place (ADR-0009 rule 8). Swift's enum carries the cellular
/// generation on its case, which is the shape ADR-0009 rule 1 asked for and Kotlin needed a sealed
/// class to spell.
public enum NetworkTransport: Sendable, Hashable {

    /// A WiFi network that is not a personal hotspot.
    case wifi

    /// A wired network that is not a tethered phone.
    case ethernet

    /// A cellular network — the device's own radio, or one it reaches through a personal hotspot —
    /// with the generation, or nil where nothing said (always, for a hotspot).
    case cellular(CellularGeneration?)

    /// Connected to a network SuperPlayer does not classify, or to none. Distinct from an
    /// unobserved transport, which is a nil ``PlaybackConditions/transport``.
    case unknown
}

/// The generation of a cellular network, as coarsely as a policy needs it — the Kotlin
/// `CellularGeneration`'s three steps.
public enum CellularGeneration: Sendable, Hashable {
    /// 5G New Radio, standalone or not.
    case nr
    /// 4G LTE, including LTE-Advanced.
    case lte
    /// Anything earlier, all of which a policy treats as slow.
    case older
}

/// What the network is believed to deliver: a level, how much to trust it, and how fresh it is —
/// the Kotlin `ThroughputEstimate`.
///
/// On iOS each sample is an access-log entry's `observedBitrate`, the SDK's own measure of the
/// transfers made under it, rather than one transfer timed — `docs/ios-plan.md` §3.3's "a different
/// input" — so a sample covers everything fetched in that entry's span.
public struct ThroughputEstimate: Sendable, Hashable {

    /// The estimated throughput, in bits per second.
    public var meanBps: Int64

    /// How far recent samples scatter around ``meanBps``, or nil if not measured.
    public var spreadBps: Int64?

    /// A throughput the network delivered most of the time, or nil if not measured; which
    /// percentile is the reader's to state.
    public var conservativeBps: Int64?

    /// How many samples the estimate rests on.
    public var sampleCount: Int

    /// Milliseconds since the newest sample was taken, on the monotonic clock.
    public var newestSampleAgeMs: Int64

    public init(
        meanBps: Int64, spreadBps: Int64?, conservativeBps: Int64?, sampleCount: Int,
        newestSampleAgeMs: Int64
    ) {
        precondition(meanBps >= 0, "meanBps must not be negative")
        precondition(spreadBps.map { $0 >= 0 } ?? true, "spreadBps must not be negative")
        precondition(
            conservativeBps.map { $0 >= 0 } ?? true, "conservativeBps must not be negative")
        precondition(sampleCount >= 0, "sampleCount must not be negative")
        precondition(newestSampleAgeMs >= 0, "newestSampleAgeMs must not be negative")
        self.meanBps = meanBps
        self.spreadBps = spreadBps
        self.conservativeBps = conservativeBps
        self.sampleCount = sampleCount
        self.newestSampleAgeMs = newestSampleAgeMs
    }
}

/// What this session has already suffered — the Kotlin `StallHistory`. A rebuffer is a stall for
/// data after playback started that ended in playback resuming; one a seek caused is not one, on
/// `docs/telemetry-schema.md`'s line, drawn here in core so the two agree.
public struct StallHistory: Sendable, Hashable {

    /// Rebuffers that have ended in this session.
    public var rebufferCount: Int

    /// Milliseconds since the last rebuffer ended, on the monotonic clock, or nil if none has.
    public var msSinceLastRebufferEnded: Int64?

    /// How long the last rebuffer lasted, in milliseconds, or nil if none has ended.
    public var lastRebufferDurationMs: Int64?

    public init(
        rebufferCount: Int, msSinceLastRebufferEnded: Int64?, lastRebufferDurationMs: Int64?
    ) {
        precondition(rebufferCount >= 0, "rebufferCount must not be negative")
        precondition(
            (rebufferCount == 0) == (msSinceLastRebufferEnded == nil),
            "msSinceLastRebufferEnded is observed exactly when a rebuffer has ended")
        precondition(
            (rebufferCount == 0) == (lastRebufferDurationMs == nil),
            "lastRebufferDurationMs is observed exactly when a rebuffer has ended")
        self.rebufferCount = rebufferCount
        self.msSinceLastRebufferEnded = msSinceLastRebufferEnded
        self.lastRebufferDurationMs = lastRebufferDurationMs
    }

    /// No rebuffer has ended in this session — the state at every session's start.
    public static let none = StallHistory(
        rebufferCount: 0, msSinceLastRebufferEnded: nil, lastRebufferDurationMs: nil)
}

/// What the playlist declared the content to be — the Kotlin `StreamType`.
///
/// spec: RFC 8216 §4.3.3.4 — `EXT-X-ENDLIST`, the moment a live playlist becomes on-demand.
public enum StreamType: Sendable, Hashable {
    /// Content with a fixed duration, playable from any position.
    case onDemand
    /// Content whose window advances on time, played near its live edge.
    case live
}
