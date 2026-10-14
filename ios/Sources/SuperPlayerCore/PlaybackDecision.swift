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

/// A ``PlaybackPolicy``'s answer: how far ahead to buffer, what to cap variant selection at, how to
/// hold a live window, how far ahead of a feed to prefetch, how often to ask again for a licence,
/// what to download, and how decoded media meets the output — the Kotlin `PlaybackDecision`, half for
/// half (`docs/ios-plan.md` §3.3's first row, ADR-0005 and ADR-0009).
///
/// **It names no AVFoundation type.** `EngineBinding.swift` is the one place a decision becomes
/// `AVPlayerItem` and `AVPlayer` configuration, so a policy is written, read and compared as a value
/// a consumer can construct by hand and log.
///
/// **Every half has the Kotlin half's name and meaning, and the fields of each are the ones this
/// platform has a knob for**, because ADR-0005's standing obligation is that a field nothing reads
/// is removed rather than kept for later. Three parts are read by no part of an iOS player and are
/// present for exactly one reason — a policy, a log line and a warehouse row read the same on both
/// platforms — and each says so where it is declared: ``TrackSelectionPolicy/pace``, the
/// ``RetryPolicy/manifest`` budget, and ``output``. Three more are read by no player but by another
/// product: ``preload`` by the preload coordinator (i4), and ``download`` and the
/// ``RetryPolicy/segment`` budget by the download store (#424); a player still decides them, and
/// ``SuperPlayer/playbackDecision`` still says what was decided, which is the Kotlin contract for a
/// player outside a pool.
///
/// **`nil` is "Apple's own default", never a number standing in for one.** Where the Kotlin halves
/// spell "no ceiling" as `TrackSelectionPolicy.UNLIMITED` (`Int.MAX_VALUE`, Media3's own "unset"),
/// an optional is the Swift spelling of the same fact, and AVFoundation's knobs are each told
/// "none" by a value of their own that a number here would only restate. So `PlaybackDecision()` is
/// a meaningful value — the engine exactly as Apple builds it — and is what a policy that decides
/// nothing returns.
///
/// Every half is a `var`, so a policy that starts from a profile's decision and changes one thing
/// says only that thing:
///
/// ```swift
/// var decision = PlaybackProfile.shortForm.policy.decide(conditions)
/// decision.buffer.maxBufferMs = 10_000
/// ```
public struct PlaybackDecision: Sendable, Hashable {

    /// How far ahead of the playhead to buffer, and whether to wait before starting.
    public var buffer: BufferPolicy

    /// The ceilings variant selection may not choose above.
    public var trackSelection: TrackSelectionPolicy

    /// How a live window is held, or nil for the playlist's own behaviour. A half of its own rather
    /// than a field of ``buffer`` for the Kotlin reason — it reaches different knobs — and inert on
    /// on-demand content, where AVFoundation reads neither of them.
    public var liveLatency: LiveLatencyPolicy?

    /// How much of a feed to prefetch around the row that is playing — read by the preload
    /// coordinator (i4, `docs/ios-plan.md` §3.4) and by nothing else. ``PreloadPolicy/none`` unless
    /// a policy says otherwise.
    public var preload: PreloadPolicy

    /// How often a failed load is asked for again. **No player honours the manifest and segment
    /// budgets**, because `AVPlayer` retries inside the engine and nothing configures it (§3.5);
    /// ``RetryPolicy/licence`` is spent by the FairPlay key request this library makes itself (#422,
    /// `docs/ios-plan.md` §3.6), and ``RetryPolicy/segment`` by the download store re-enqueueing a
    /// failed task (#424, §3.7).
    public var retry: RetryPolicy

    /// The ceiling a download's rendition is chosen under — read by the download store (#424,
    /// `docs/ios-plan.md` §3.7) once per item as it is enqueued, and by no player.
    public var download: DownloadSelectionPolicy

    /// How decoded media meets the output. **Present and ignored on iOS**: Apple TV's output path is
    /// always tunneled and nothing configures it (`docs/ios-plan.md` §3.8), so the half is kept for
    /// a decision that reads the same on both platforms and is applied to nothing.
    public var output: OutputPolicy

    public init(
        buffer: BufferPolicy = BufferPolicy(),
        trackSelection: TrackSelectionPolicy = TrackSelectionPolicy(),
        liveLatency: LiveLatencyPolicy? = nil,
        preload: PreloadPolicy = .none,
        retry: RetryPolicy = .standard,
        download: DownloadSelectionPolicy = .unlimited,
        output: OutputPolicy = .none
    ) {
        self.buffer = buffer
        self.trackSelection = trackSelection
        self.liveLatency = liveLatency
        self.preload = preload
        self.retry = retry
        self.download = download
        self.output = output
    }
}

/// How much to buffer — the Kotlin `BufferPolicy`, cut to what AVFoundation exposes.
///
/// AVFoundation takes one buffer preference and no floor, so the Kotlin range's *ceiling* is what
/// ports, under its Kotlin name. The two start floors (`bufferForPlaybackMs` and its after-rebuffer
/// twin), the back buffer and whether it is kept from a keyframe have no knob: AVFoundation decides
/// when to start from its own prediction, which ``waitsToMinimizeStalling`` switches on or off but
/// does not tune, and no preference reaches a back buffer. They are absent rather than present and
/// ignored, because no future product on this platform could read them either — unlike the parts
/// of a decision that are ignored today (``PlaybackDecision``).
public struct BufferPolicy: Sendable, Hashable {

    /// How far ahead of the playhead to buffer, in milliseconds, or nil for AVFoundation's automatic
    /// choice — `AVPlayerItem.preferredForwardBufferDuration`, whose default of zero is that choice.
    /// The Kotlin `maxBufferMs`. A preference rather than a guarantee: the system buffers less where
    /// its resources require.
    public var maxBufferMs: Int?

    /// Whether the player waits before starting or resuming until it predicts it will not stall —
    /// `AVPlayer.automaticallyWaitsToMinimizeStalling`, whose default is yes. The one knob of a
    /// decision that is the player's rather than the item's; `StaticProfilePolicy` argues why no
    /// profile departs from it. Has no Kotlin twin: the nearest are the start floors above.
    public var waitsToMinimizeStalling: Bool

    public init(maxBufferMs: Int? = nil, waitsToMinimizeStalling: Bool = true) {
        precondition(
            maxBufferMs.map { $0 > 0 } ?? true,
            "maxBufferMs must be positive when decided, was \(String(describing: maxBufferMs))")
        self.maxBufferMs = maxBufferMs
        self.waitsToMinimizeStalling = waitsToMinimizeStalling
    }
}

/// The ceilings variant selection may not choose above — the Kotlin `TrackSelectionPolicy`.
///
/// A ceiling rather than a choice, for the Kotlin reason: which variant under it plays is Apple's to
/// decide, continuously, and a policy that pinned one would take that decision away at the moment
/// the network has just changed. On iOS that is not only a preference: AVFoundation has no per-rung
/// hook, so a ceiling is all a policy *can* say (`docs/ios-plan.md` §3.3).
public struct TrackSelectionPolicy: Sendable, Hashable {

    /// Ceiling on a variant's declared bitrate, in bits per second, or nil for none —
    /// `AVPlayerItem.preferredPeakBitRate`.
    public var maxVideoBitrateBps: Int?

    /// Ceiling on a video variant's height, in pixels, or nil for none — the height of
    /// `AVPlayerItem.preferredMaximumResolution`. A height alone, for the Kotlin reason: it names a
    /// rung of every ladder in use, so a ceiling means the same on both platforms.
    public var maxVideoHeightPx: Int?

    /// ``maxVideoBitrateBps`` on a network the system marks expensive, or nil for none —
    /// `preferredPeakBitRateForExpensiveNetworks`. AVFoundation applies the plain ceiling on every
    /// network and this one only where it is stricter, so it says something only where it is; it is
    /// what the Kotlin `TransportCaps` becomes on cellular (`docs/ios-plan.md` §3.3). iOS's own,
    /// because Media3 has no such pair and the Kotlin policy reads the transport instead.
    public var expensiveNetworkMaxVideoBitrateBps: Int?

    /// ``maxVideoHeightPx`` on a network the system marks expensive, or nil for none —
    /// `preferredMaximumResolutionForExpensiveNetworks`, as ``expensiveNetworkMaxVideoBitrateBps``.
    public var expensiveNetworkMaxVideoHeightPx: Int?

    /// How eagerly selection climbs and descends under the ceiling, or nil for the engine's own pace.
    ///
    /// **Present and ignored on iOS.** Apple selects variants with no per-rung hook and no pace
    /// setting, so nothing on this platform can honour a pace (`docs/ios-plan.md` §3.3, the
    /// `NetworkAwareTrackSelection` row); it is carried so a policy written for both platforms
    /// compiles on both and a decision logged on either reads the same.
    public var pace: SelectionPace?

    public init(
        maxVideoBitrateBps: Int? = nil, maxVideoHeightPx: Int? = nil,
        expensiveNetworkMaxVideoBitrateBps: Int? = nil,
        expensiveNetworkMaxVideoHeightPx: Int? = nil, pace: SelectionPace? = nil
    ) {
        for (name, ceiling) in [
            ("maxVideoBitrateBps", maxVideoBitrateBps), ("maxVideoHeightPx", maxVideoHeightPx),
            ("expensiveNetworkMaxVideoBitrateBps", expensiveNetworkMaxVideoBitrateBps),
            ("expensiveNetworkMaxVideoHeightPx", expensiveNetworkMaxVideoHeightPx),
        ] {
            precondition(
                ceiling.map { $0 > 0 } ?? true,
                "\(name) must be positive when decided, was \(String(describing: ceiling))")
        }
        self.maxVideoBitrateBps = maxVideoBitrateBps
        self.maxVideoHeightPx = maxVideoHeightPx
        self.expensiveNetworkMaxVideoBitrateBps = expensiveNetworkMaxVideoBitrateBps
        self.expensiveNetworkMaxVideoHeightPx = expensiveNetworkMaxVideoHeightPx
        self.pace = pace
    }
}

/// How adaptive selection moves under a ceiling — the Kotlin `SelectionPace`, value for value.
///
/// **Read by nothing on iOS** (``TrackSelectionPolicy/pace``): the type is here so the half it sits
/// in is the same shape on both platforms.
public struct SelectionPace: Sendable, Hashable {

    /// Media buffered ahead of the playhead before selection may choose a higher rung.
    public var climbAfterBufferedMs: Int

    /// The buffer below which selection chooses a lower rung as soon as the estimate asks for one.
    public var descendBelowBufferedMs: Int

    /// Media of an already-buffered rung that is kept, rather than discarded, on a climb.
    public var retainAfterDiscardMs: Int

    /// The share of the throughput estimate a chosen rung may use. In (0, 1].
    public var bandwidthFraction: Float

    public init(
        climbAfterBufferedMs: Int, descendBelowBufferedMs: Int, retainAfterDiscardMs: Int,
        bandwidthFraction: Float
    ) {
        precondition(climbAfterBufferedMs >= 0, "climbAfterBufferedMs must not be negative")
        precondition(descendBelowBufferedMs >= 0, "descendBelowBufferedMs must not be negative")
        precondition(retainAfterDiscardMs >= 0, "retainAfterDiscardMs must not be negative")
        precondition(
            bandwidthFraction > 0 && bandwidthFraction <= 1,
            "bandwidthFraction must be in (0, 1], was \(bandwidthFraction)")
        self.climbAfterBufferedMs = climbAfterBufferedMs
        self.descendBelowBufferedMs = descendBelowBufferedMs
        self.retainAfterDiscardMs = retainAfterDiscardMs
        self.bandwidthFraction = bandwidthFraction
    }

    /// The Kotlin `SelectionPace.ENGINE_DEFAULT`, stated: Media3's `AdaptiveTrackSelection` defaults
    /// of 10 s to climb, 25 s to descend, 25 s retained and 70 % of the estimate. Named for parity;
    /// it is no engine's default here.
    ///
    /// ref: https://developer.android.com/reference/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.Factory
    public static let engineDefault = SelectionPace(
        climbAfterBufferedMs: 10_000, descendBelowBufferedMs: 25_000,
        retainAfterDiscardMs: 25_000, bandwidthFraction: 0.7)
}

/// How a live window is held — the Kotlin `LiveLatencyPolicy`'s purpose, reached through the knobs
/// AVFoundation has for it (`docs/ios-plan.md` §3.3's `LiveLatencyPolicy` row).
///
/// **A different mechanism from Android's, so different fields.** Media3 holds a live window by
/// drifting the playback speed inside a range, and the Kotlin half is that range. AVFoundation
/// exposes no speed range for catching up; it holds a target *offset* from the live edge and can be
/// told to skip forward to it after buffering, and those two are this half. A policy written for
/// both platforms decides each half on its own platform.
public struct LiveLatencyPolicy: Sendable, Hashable {

    /// How far behind the live edge to play, in milliseconds, or nil for the playlist's own
    /// recommendation — `AVPlayerItem.configuredTimeOffsetFromLive` left invalid, which AVFoundation
    /// reads as `recommendedTimeOffsetFromLive` (RFC 8216bis's `HOLD-BACK`).
    public var targetOffsetMs: Int?

    /// Whether playback skips forward to its offset after buffering, rather than resuming where it
    /// stopped and staying that much further behind — `automaticallyPreservesTimeOffsetFromLive`,
    /// whose default is no.
    public var preservesOffset: Bool

    public init(targetOffsetMs: Int? = nil, preservesOffset: Bool = false) {
        precondition(
            targetOffsetMs.map { $0 > 0 } ?? true,
            "targetOffsetMs must be positive when decided, was \(String(describing: targetOffsetMs))"
        )
        self.targetOffsetMs = targetOffsetMs
        self.preservesOffset = preservesOffset
    }
}

/// How much of a feed to prefetch around the row that is playing — the Kotlin `PreloadPolicy`.
///
/// Counts rather than indices, for the Kotlin reason: which item is next is the feed's to say at
/// runtime, and a policy decides only what a prefetch may cost (ADR-0010 rule 10). Read by the
/// preload coordinator (i4) and by nothing in core.
public struct PreloadPolicy: Sendable, Hashable {

    /// Items in the direction the feed is moving, nearest first.
    public var itemsAhead: Int

    /// Items against the direction the feed is moving, for a viewer who swipes back.
    public var itemsBehind: Int

    /// How far into each of those items to go.
    public var depth: PreloadDepth

    public init(itemsAhead: Int, itemsBehind: Int, depth: PreloadDepth) {
        precondition(itemsAhead >= 0, "itemsAhead must not be negative, was \(itemsAhead)")
        precondition(itemsBehind >= 0, "itemsBehind must not be negative, was \(itemsBehind)")
        self.itemsAhead = itemsAhead
        self.itemsBehind = itemsBehind
        self.depth = depth
    }

    /// Whether this prefetches anything at all.
    public var isNone: Bool { itemsAhead == 0 && itemsBehind == 0 }

    /// No prefetch: what every policy decides unless it says otherwise — the Kotlin `NONE`.
    public static let none = PreloadPolicy(itemsAhead: 0, itemsBehind: 0, depth: .sourcePrepared)
}

/// How far into an item a prefetch goes, from cheapest to dearest — the Kotlin `PreloadDepth`. Each
/// stage includes the ones before it; `docs/ios-plan.md` §3.4 says what each becomes on iOS, where
/// ``decoderWarmed(durationMs:)`` is `AVPlayer.preroll(atRate:)` on an idle pooled player.
public enum PreloadDepth: Sendable, Hashable {

    /// The playlist is fetched and parsed, so the item's variants are known.
    case sourcePrepared

    /// As ``sourcePrepared``, and a variant has been chosen.
    case tracksSelected

    /// As ``tracksSelected``, and `durationMs` of media from the item's start has been loaded.
    case loaded(durationMs: Int)

    /// As ``loaded(durationMs:)``, and for the nearest of those items a decoder warmed on it.
    case decoderWarmed(durationMs: Int)
}

/// How many times a failed load is asked for again, and how long to wait between asks — the Kotlin
/// `RetryPolicy`, its three budgets kept apart for its reason: one exhausted budget must not spend
/// another's.
///
/// **No player honours ``manifest`` or ``segment`` on iOS** (`docs/ios-plan.md` §3.5 and §3.6).
/// `AVPlayer` retries a playlist and a segment inside the engine, nothing configures how often, and
/// content steering is the server's host failover — so both are carried for a player so a policy and
/// a logged decision read the same on both platforms. The FairPlay key request is this library's own
/// round trip (#422), and it spends ``licence``; the download store starts a failed download task
/// again on ``segment`` (#424, §3.7), the one budget a task's failure can spend, since the requests
/// inside it are AVFoundation's.
public struct RetryPolicy: Sendable, Hashable {

    /// What a playlist gets. **Ignored on iOS.**
    public var manifest: RetryBudget

    /// What a segment gets. **Ignored by every player**; spent by the download store, which starts
    /// a failed download task again on it (#424, `docs/ios-plan.md` §3.7).
    public var segment: RetryBudget

    /// What a licence round trip gets — honoured, by the key request #422 makes.
    public var licence: RetryBudget

    public init(
        manifest: RetryBudget = .standard, segment: RetryBudget = .standard,
        licence: RetryBudget = .standard
    ) {
        self.manifest = manifest
        self.segment = segment
        self.licence = licence
    }

    /// ``RetryBudget/standard`` for all three: what a policy that says nothing about retries asks
    /// for — the Kotlin `RetryPolicy.MEDIA3_DEFAULT`.
    public static let standard = RetryPolicy()
}

/// One budget: how many times to ask again, and the window the waits are drawn from — the Kotlin
/// `RetryBudget`. The wait grows exponentially from ``initialBackoffMs`` to ``maxBackoffMs``; where
/// each falls inside that window is jitter's, which is correctness and not a number here (ADR-0011
/// rule 12).
public struct RetryBudget: Sendable, Hashable {

    /// How many times the same load may be asked for again after the first attempt failed.
    public var maxRetries: Int

    /// The wait the first retry is drawn around; each subsequent retry doubles it.
    public var initialBackoffMs: Int64

    /// The longest wait any retry may be drawn around.
    public var maxBackoffMs: Int64

    public init(maxRetries: Int, initialBackoffMs: Int64, maxBackoffMs: Int64) {
        precondition(maxRetries >= 0, "maxRetries must not be negative, was \(maxRetries)")
        precondition(initialBackoffMs >= 0, "initialBackoffMs must not be negative")
        precondition(
            maxBackoffMs >= initialBackoffMs,
            "maxBackoffMs (\(maxBackoffMs)) must not be below initialBackoffMs (\(initialBackoffMs))"
        )
        self.maxRetries = maxRetries
        self.initialBackoffMs = initialBackoffMs
        self.maxBackoffMs = maxBackoffMs
    }

    /// Three retries, a wait growing from one second to a five-second ceiling: the Kotlin
    /// `RetryBudget.MEDIA3_DEFAULT`, which is Media3's own count and ceiling. Kept on iOS, where no
    /// Media3 exists, so that a policy saying nothing about retries decides the same budget on both
    /// platforms; the reason for each number is the Kotlin constant's.
    ///
    /// ref: `android/superplayer-core/src/main/kotlin/com/superplayer/core/PlaybackPolicy.kt`,
    /// `RetryBudget.MEDIA3_DEFAULT`
    public static let standard = RetryBudget(
        maxRetries: 3, initialBackoffMs: 1_000, maxBackoffMs: 5_000)
}

/// The ceiling a download's rendition is chosen under — the Kotlin `DownloadSelectionPolicy`, read
/// by the download store (#424) and by no player.
public struct DownloadSelectionPolicy: Sendable, Hashable {

    /// Ceiling on the downloaded variant's declared bitrate, in bits per second, or nil for none.
    public var maxVideoBitrateBps: Int?

    /// Ceiling on the downloaded variant's height, in pixels, or nil for none.
    public var maxVideoHeightPx: Int?

    public init(maxVideoBitrateBps: Int? = nil, maxVideoHeightPx: Int? = nil) {
        precondition(
            maxVideoBitrateBps.map { $0 > 0 } ?? true, "maxVideoBitrateBps must be positive")
        precondition(maxVideoHeightPx.map { $0 > 0 } ?? true, "maxVideoHeightPx must be positive")
        self.maxVideoBitrateBps = maxVideoBitrateBps
        self.maxVideoHeightPx = maxVideoHeightPx
    }

    /// No ceiling: the highest rendition the device can play — the Kotlin `UNLIMITED`.
    public static let unlimited = DownloadSelectionPolicy()
}

/// How decoded media meets the platform's output — the Kotlin `OutputPolicy`.
///
/// **Present and ignored on iOS** (``PlaybackDecision/output``): Apple TV's output path is always
/// tunneled and nothing there asks (`docs/ios-plan.md` §3.8).
public struct OutputPolicy: Sendable, Hashable {

    /// Whether tunneled playback is asked for. Read by nothing on this platform.
    public var tunneling: Bool

    public init(tunneling: Bool) {
        self.tunneling = tunneling
    }

    /// Tunneling not asked for — the Kotlin `NONE`.
    public static let none = OutputPolicy(tunneling: false)
}

/// What made a policy be consulted again — the Kotlin `DecisionTrigger`'s closed list of five, which
/// ADR-0009 rule 4 names and `docs/telemetry-schema.md`'s *Decision changes* lists. Each is raised
/// by `DecisionReapplication.swift` from a reading `ConditionsBinding.swift` translates.
public enum DecisionTrigger: Sendable, Hashable {
    /// The device moved to a different ``NetworkTransport`` — or the first one was observed.
    case transportChanged
    /// The playlist said what the content is — or, for a live playlist that ended, what it became.
    case streamTypeChanged
    /// A rebuffer ended and playback resumed; ``PlaybackConditions/stallHistory`` has moved.
    case rebufferEnded
    /// The playback rate changed.
    case playbackSpeedChanged
    /// The throughput estimate moved far enough that a policy should hear about it.
    case throughputChanged
}
