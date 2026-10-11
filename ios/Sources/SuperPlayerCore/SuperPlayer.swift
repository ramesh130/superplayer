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
// For `AVPlayerItem.externalMetadata` alone, which is an AVKit addition to the AVFoundation type;
// `Package.swift` says why the framework is also linked by name.
import AVKit

/// A production playback layer over `AVPlayer`: content identity and resume positions today, and
/// policy, resilience, observability and lifecycle as the phases of `docs/ios-plan.md` arrive.
///
/// ```swift
/// let player = SuperPlayer(configuration: .init())
/// playerViewController.player = player.avPlayer
/// player.setMediaRequest(MediaRequest(contentId: "catalog:episode:1138", sources: [url]))
/// player.avPlayer.play()
/// ```
///
/// **It owns an `AVPlayer` and is not one** (ADR-0020 rule 2). The Android facade *is* a Media3
/// `Player`, by delegation; here `AVPlayer`, `AVQueuePlayer` and `AVPlayerItem` are described by
/// Apple for use and not for subclassing, so the engine is held and exposed as ``avPlayer`` — which
/// `AVPlayerViewController`, `AVPlayerLayer` and SwiftUI's `VideoPlayer` take unchanged, and which
/// is therefore also where transport controls are: playing and pausing are `avPlayer`'s, as they
/// are for those views' own buttons. What the facade adds is what an `AVPlayerItem` cannot say —
/// *what* is playing and where to start it.
///
/// **It is isolated to the main actor.** `AVPlayer` is not `Sendable`, the views that host it are
/// main-actor types, and the notifications the facade hears are delivered on the main queue; the
/// Kotlin facade's rule — every `Player` call is on the application looper — is the same rule, and
/// here the compiler holds a caller to it rather than a runtime check.
@MainActor
public final class SuperPlayer {

    /// How many pieces of content one player remembers a position for.
    ///
    /// The Kotlin `SuperPlayer.MAX_REMEMBERED_POSITIONS`, for its reason: public because it bounds
    /// what ``MediaRequest/StartPosition/resumeFromLastKnown`` promises, and a consumer sizing a
    /// feed against it should be able to read the number rather than guess it. Generous next to any
    /// plausible back-stack of things a viewer might return to, and cheap — a few kilobytes at worst
    /// — while an unbounded map of ids a consumer chose the length of would be a slow leak in
    /// exactly the kind of app a feed of players is.
    nonisolated public static let maxRememberedPositions = 128

    /// What a player is configured with, fixed for its lifetime (ADR-0020 rule 5).
    ///
    /// The Android `SuperPlayer.Builder`, as a value: each builder setter arrives here as a property
    /// with a default, as the phase that needs it lands.
    ///
    /// Not `Hashable`: ``telemetry`` is a function, and a function has no equality to derive.
    public struct Configuration: Sendable {

        /// What kind of playback this is; ``PlaybackProfile/videoOnDemand`` unless set.
        public var profile: PlaybackProfile = .videoOnDemand

        /// What decides how this player plays, or nil — the default — for ``profile``'s own static
        /// decision (``PlaybackProfile/policy``). The Android `setPolicy(policy)`.
        ///
        /// A player under its profile's static policy observes nothing and is consulted once. Any
        /// other policy — a consumer's own, or `SuperPlayerABR`'s adaptive one — is consulted again
        /// on the five ``DecisionTrigger``s, and each change is laid on the playing item in place
        /// and reported as ``SuperPlayer/playbackDecision`` and a `DecisionChanged` event
        /// (``PlaybackPolicy`` says why that is every other policy here, where on Android it is only
        /// the adaptive one). An instance rather than ``telemetry``'s factory: a policy is a pure
        /// function, so one may serve many players.
        ///
        /// ``profile`` still names the use case to everything that reads it — CMCD's per-profile
        /// table and `SessionStarted.profile` — whatever policy decides the numbers.
        public var policy: (any PlaybackPolicy)?

        public init() {}

        /// Makes the collector that measures this player, or nil — the default — for a player that
        /// measures nothing and pays nothing for it (ADR-0008 rule 2): no collector made, no session
        /// id minted, nothing registered.
        ///
        /// The Android `setTelemetry(collector)`, taking a *factory* where Android takes an instance,
        /// which is `PlayerPool.Builder.setTelemetry { QoeCollector(sink) }`'s shape: a configuration
        /// is a value, copied into every player made from it, and a collector observes exactly one
        /// player, so an instance here would be shared by the second player built from the same
        /// configuration. Called once, on the main actor, as the player is made.
        ///
        /// ```swift
        /// var configuration = SuperPlayer.Configuration()
        /// configuration.telemetry = { QoeCollector(sink: analyticsSink) }
        /// ```
        public var telemetry: (@MainActor @Sendable () -> any TelemetryCollector)?

        /// How CMCD travels, or nil — the default — for the mode ``profile`` emits under, which is
        /// ``CmcdMode/requestHeaders`` for every profile. Set through ``setCmcdMode(_:)``.
        public private(set) var cmcdMode: CmcdMode?

        /// Chooses how CMCD travels — the Android `setCmcdMode(mode)` — refusing a mode this
        /// platform has no mechanism for with ``CmcdModeUnsupportedError``, which is
        /// ``CmcdMode/queryParameter``.
        ///
        /// A method rather than a settable property because a Swift property setter cannot throw,
        /// and the refusal belongs here, where the consumer made the choice, rather than at a
        /// player made later from a configuration that had already said yes (ADR-0020 rule 9).
        public mutating func setCmcdMode(_ mode: CmcdMode) throws(CmcdModeUnsupportedError) {
            guard mode != .queryParameter else { throw CmcdModeUnsupportedError(mode: mode) }
            cmcdMode = mode
        }

        /// What an effect does to each decoded frame, or nil — the default — for a player whose
        /// frames go straight to an `AVPlayerLayer` and which makes nothing for an output it lacks
        /// (ADR-0022 rules 7 and 9). The Android `setFrameOutput`, fixed for the player's lifetime
        /// as every configuration is (rule 2); ``FrameOutput`` says how a frame reaches it, and
        /// ``PlayerView`` is what shows the result — `AVPlayerViewController` would show the
        /// unprocessed picture. An instance rather than ``telemetry``'s factory, because one output
        /// may serve many players, as the Kotlin handle may.
        public var frameOutput: (any FrameOutput)?

        /// The protection this player plays under, or nil: the stub #422's public `drm` replaces
        /// (``PlaybackProtection``). A frame output on a player that has one is off
        /// (``SuperPlayer/frameOutputOffReason``).
        package var protection: (any PlaybackProtection)?
    }

    /// The configuration this player was made with.
    public let configuration: Configuration

    /// The engine: the one escape hatch (ADR-0020 rule 3), and what a view is handed.
    ///
    /// What a consumer does through it — setting a knob a policy also sets, replacing the current
    /// item — is outside SuperPlayer's compatibility promise, as anything reached through the
    /// Android `player.exoPlayer` is (ADR-0017 rule 4). An item replaced through it directly is one
    /// this player can name only if it carries the identity ``setMediaRequest(_:)`` writes.
    public let avPlayer: AVPlayer

    /// Where each piece of content was left, in memory and for the life of this player only.
    /// Persisting it would mean choosing a storage mechanism on a consumer's behalf (ADR-0006 rule
    /// 2); what outlives a player is a ``PlaybackSnapshot``.
    var rememberedPositions = RememberedPositions(bound: maxRememberedPositions)

    /// The request the current item was made from, if ``setMediaRequest(_:)`` made it — what a
    /// ``PlaybackSnapshot`` names. Cleared at every replacement the facade makes.
    private(set) var currentRequest: MediaRequest?

    /// The decision in force: what the policy answered at construction, and on a player whose
    /// policy is consulted again, its latest different answer — the Kotlin `playbackDecision`
    /// (ADR-0009 rule 6). Each change after construction is also a `DecisionChanged` event.
    ///
    /// It says what was *decided*, which on iOS is more than what is laid: the halves no player
    /// here reads — the selection pace, the manifest and segment budgets, tunneling — and the two
    /// read by products still to land are reported as decided (``PlaybackDecision``).
    public private(set) var playbackDecision: PlaybackDecision

    /// The policy's observer and re-consulter, or nil on a player under its profile's static policy,
    /// which observes nothing (`DecisionReapplication.swift`).
    private let reapplication: DecisionReapplication?

    /// The CMCD mode in force, resolved once: the configuration's, else the profile's.
    private let cmcdMode: CmcdMode

    /// The registrations of ``ObservedSignal`` on the current item, released with it.
    private var observations: [Observation] = []

    /// Whether the current item has reached its end since it was last sought — the Kotlin facade's
    /// `playbackState == STATE_ENDED`, which `AVPlayer` has no state for (``ObservedSignal``).
    private var currentItemEnded = false

    /// The collector ``Configuration/telemetry`` made, or nil; with it, the session it is measuring.
    /// Both nil on a player configured without one, which is what the pays-nothing count reads.
    let collector: (any TelemetryCollector)?
    var measurement: MeasurementSession?

    /// Whether ``release()`` has run.
    private(set) var isReleased = false

    /// The platform's lifecycle rules, on for every player (`LifecycleBinding.swift`).
    private let lifecycle: LifecycleBinding

    /// The frame output's display path (`FramePath.swift`), or nil on a player without one running.
    let framePath: FramePath?

    public convenience init(configuration: Configuration = Configuration()) {
        self.init(configuration: configuration, lifecycle: .system)
    }

    /// A player whose lifecycle reaches `lifecycle`'s platform rather than the system's — the seam
    /// a test drives an interruption through (ADR-0021 rule 5's #402 addendum).
    package init(configuration: Configuration = Configuration(), lifecycle: SharedLifecycle) {
        self.configuration = configuration
        self.avPlayer = AVPlayer()
        let policy = configuration.policy ?? configuration.profile.policy
        if policy is StaticProfilePolicy {
            // Asked once, with nothing observed — the Kotlin static player's consultation, and an
            // answer the conditions cannot change.
            self.reapplication = nil
            self.playbackDecision = policy.decide(PlaybackConditions())
        } else {
            let reapplication = DecisionReapplication(
                policy: policy, availableMemoryBytes: DeviceCapacity.processAvailableMemory())
            self.reapplication = reapplication
            self.playbackDecision = reapplication.decision
        }
        self.cmcdMode =
            configuration.cmcdMode ?? StaticCmcdPolicy.defaultMode(for: configuration.profile)
        self.lifecycle = LifecycleBinding(player: avPlayer, shared: lifecycle)
        playbackDecision.apply(to: avPlayer)
        self.framePath = FramePath.make(configuration: configuration, player: avPlayer)
        self.collector = configuration.telemetry?()
        self.measurement = collector.map { _ in MeasurementSession() }
        collector?.attach(to: self)
        // Last, so a change heard as the observers start — the first network path — finds the
        // player and its collector whole.
        reapplication?.onDecisionChanged = { [weak self] decision, trigger in
            self?.decisionChanged(to: decision, on: trigger)
        }
        reapplication?.start(on: avPlayer)
    }

    /// Makes `decision` the one in force: laid on the player and on the item playing now, in place
    /// (`EngineBinding.swift` says why that honours it whole), and told to the collector.
    private func decisionChanged(to decision: PlaybackDecision, on trigger: DecisionTrigger) {
        playbackDecision = decision
        decision.apply(to: avPlayer)
        if let item = avPlayer.currentItem { decision.apply(to: item) }
        collector?.decisionChanged(decision, trigger: trigger)
    }

    /// Plays what `request` describes: the first of its sources, starting where its
    /// ``MediaRequest/StartPosition`` says, identified by its ``MediaRequest/contentId``.
    ///
    /// The counterpart of handing `avPlayer` a new item, and the same contract — it replaces the
    /// current item and does not start playback. The outgoing content's position is remembered
    /// first, so a later ``MediaRequest/StartPosition/resumeFromLastKnown`` for it returns here; that
    /// order is also why a request resuming the content already playing resumes where it is.
    public func setMediaRequest(_ request: MediaRequest) {
        guard !isReleased else { return }
        rememberPositionOfCurrentContent()
        load(request, startingAtMs: resolvedStartPositionMs(of: request))
    }

    /// Makes the item for `request`, starting at `startMs` or at the content's own default
    /// position, and makes it current — what ``setMediaRequest(_:)`` and ``restoreSnapshot(_:)``
    /// share, the second with a position it was handed rather than one it resolved.
    func load(_ request: MediaRequest, startingAtMs startMs: Int64?) {
        // A fresh asset per adoption, never a copy: its `httpSessionIdentifier` is this session's
        // id, on the wire and in telemetry (`CmcdBinding.swift`).
        let asset = AVURLAsset(url: request.sources[0])
        CmcdBinding.apply(cmcdMode, to: asset)
        let item = AVPlayerItem(asset: asset)
        item.externalMetadata = ContentIdentity.metadata(of: request)
        if let startMs {
            // Before the item is the player's, so the first segment fetched is the one the start
            // position needs rather than the first of the content — `testAnAtPositionStartsThere`
            // counts that no earlier segment is fetched. A completion handler is not passed:
            // `AVPlayerItem` raises if handed one before it is ready, and nothing here waits.
            // ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/seek(to:tolerancebefore:toleranceafter:completionhandler:)
            item.seek(
                to: CMTime(value: startMs, timescale: Self.millisecondsPerSecond),
                toleranceBefore: .zero, toleranceAfter: .zero, completionHandler: nil)
        }
        endMeasurement()
        replaceCurrentItem(with: item, identified: true)
        currentRequest = request
        beginMeasurement(of: request.contentId, playing: asset)
    }

    /// Plays `url` with no identity — the Kotlin `setMediaItem` with a bare `MediaItem`, for parity.
    ///
    /// Content played this way is nobody's: ``currentContentId`` is nil while it plays, and no
    /// position is remembered for it when it is replaced, since there is no key it could be
    /// remembered under that would not collide with the next such item. Nothing is observed on it,
    /// and nothing is measured: the session of the content it replaces ends and none starts, since
    /// a session is *of* a content id. Nor is it sent CMCD, whose `sid` would join to no session
    /// (`CmcdBinding.swift`). On Android `setMediaItem` leaves the previous session open —
    /// the schema document's *One gap* — because the facade never sees it; this method is the
    /// facade's.
    ///
    /// One difference from the Kotlin, deliberately: the *outgoing* content's position is
    /// remembered, as ``setMediaRequest(_:)`` remembers it. On Android `setMediaItem` is Media3's own
    /// `Player` method, forwarded by delegation and never seen by the facade, so a bare item
    /// replacing a request forgets where the request was; here this method is the facade's, and
    /// remembering at every replacement it makes is the promise
    /// ``MediaRequest/StartPosition/resumeFromLastKnown`` states.
    public func setMediaURL(_ url: URL) {
        guard !isReleased else { return }
        rememberPositionOfCurrentContent()
        endMeasurement()
        replaceCurrentItem(with: AVPlayerItem(url: url), identified: false)
    }

    /// Ends what this player is doing, for good: the open measurement session closes, the collector
    /// lets go of the player, and the current item is dropped so nothing more loads. The Kotlin
    /// `release()`, in its order — the session ends while the content it measured is still there to
    /// read. Calling it again does nothing, and so does giving a released player content: it is
    /// finished.
    public func release() {
        guard !isReleased else { return }
        endMeasurement()
        collector?.detach()
        isReleased = true
        observations = []
        reapplication?.stop()
        lifecycle.release()
        framePath?.detach()
        avPlayer.replaceCurrentItem(with: nil)
    }

    /// Returns this player to the state a freshly made one is in, so ``PlayerPool`` can hand it to a
    /// different item with nothing of the previous one coming along — the Kotlin `resetForReuse`.
    ///
    /// Internal because it is the pool's: a consumer holding a player it made has no second item to
    /// hand it to, and one holding a pooled player goes through ``PlayerPool/recycle(_:)`` so the
    /// pool's bookkeeping stays true.
    ///
    /// **The output goes first**, for the Kotlin reason: a player that cleared its content before
    /// its surface has a window in which the outgoing item's last frame reaches a view that now
    /// belongs to another row — the tell of a hand-rolled pool. Today the facade owns no output to
    /// detach; ``detachOwnedOutput()`` is where #405's frame output will be let go.
    ///
    /// **Then the content**, after the session that measured it ends (`SessionEdges.swift`: before
    /// the outgoing item is replaced, while its content is still there to read). The Kotlin facade
    /// ends the session last; the order moved with the edge's contract, not with any argument
    /// about reuse. The collector stays attached, so the next request opens a fresh session.
    ///
    /// **Then the observers**: every registration of the observed register goes with the item, so
    /// the next user's item is observed exactly once (ADR-0020 rule 13). The lifecycle's player
    /// signals stay, being the player's for its life as a fresh player's are, and the binding is
    /// left idle — needing neither the session nor the idle timer. Observers a consumer added
    /// to ``avPlayer`` itself — a periodic time observer, a key-value observation — are the
    /// consumer's and are not the facade's to find; a recycling row removes its own, as it detaches
    /// its own layer.
    ///
    /// **Then the playback state** a previous holder may have changed through ``avPlayer``: paused,
    /// the default rate, full volume and unmuted, pausing at an item's end (`AVPlayer`'s own
    /// default), the decision's player knobs again, and no media
    /// selection criteria — the Kotlin reset's forced audio language, which here a holder sets on
    /// the player rather than on the selector.
    ///
    /// **And the remembered positions go**, for the Kotlin KDoc's reason: resume that survives a
    /// recycle works only when the row lands on the same pooled player, which a viewer experiences
    /// as resume that works sometimes. A feed that wants resume across recycling keeps a
    /// ``PlaybackSnapshot`` per row, which is right every time.
    func resetForReuse() {
        guard !isReleased else { return }
        detachOwnedOutput()

        endMeasurement()
        avPlayer.pause()
        observations = []
        currentItemEnded = false
        currentRequest = nil
        avPlayer.replaceCurrentItem(with: nil)

        avPlayer.defaultRate = 1
        avPlayer.volume = 1
        avPlayer.isMuted = false
        avPlayer.actionAtItemEnd = .pause
        playbackDecision.apply(to: avPlayer)
        reapplication?.itemChanged(to: nil)
        for characteristic in [AVMediaCharacteristic.audible, .legible] {
            avPlayer.setMediaSelectionCriteria(nil, forMediaCharacteristic: characteristic)
        }

        // After the item has gone, so the binding reads no picture: idle, as a fresh player's is.
        lifecycle.resetForReuse()

        rememberedPositions = RememberedPositions(bound: Self.maxRememberedPositions)
    }

    /// Lets go of the output this player owns, before anything else in ``resetForReuse()``.
    ///
    /// The one output the facade owns is the frame output's (#405, ADR-0022): its item output comes
    /// off, its display link stops, and its sample-buffer layer is flushed with the displayed image
    /// removed, so the next row's view never shows this one's last frame. The layer itself stays the
    /// player's, for the next item to be shown on. An `AVPlayerLayer` an app made from ``avPlayer``
    /// is the app's, and the app detaches it, as ``PlayerPool/recycle(_:)`` says.
    private func detachOwnedOutput() {
        framePath?.detach()
    }

    /// The ``MediaRequest/contentId`` of what is playing, read back off the current item's external
    /// metadata, where ``setMediaRequest(_:)`` wrote it; nil for an item played from a bare URL or
    /// when nothing is.
    public var currentContentId: String? {
        avPlayer.currentItem.flatMap(ContentIdentity.contentId(of:))
    }

    /// The registrations live on this player, per signal — what the observed register's test
    /// counts (ADR-0020 rule 13). Internal, and reached by that test through `@testable`: no later
    /// product needs it, so it is not a `package` seam and stays off the tracked API surface.
    var liveObservations: [ObservedSignal: Int] {
        let all =
            observations + lifecycle.observations + (framePath?.observations ?? [])
            + (reapplication?.observations ?? [])
        return all.reduce(into: [:]) { counts, observation in
            counts[observation.signal, default: 0] += 1
        }
    }

    private func replaceCurrentItem(with item: AVPlayerItem, identified: Bool) {
        observations = []
        currentItemEnded = false
        currentRequest = nil
        playbackDecision.apply(to: item)
        reapplication?.itemChanged(to: item)
        if identified {
            observations = [
                Observation.register(.itemPlayedToEnd, on: item) { [weak self] in
                    self?.currentItemEnded = true
                },
                Observation.register(.itemTimeJumped, on: item) { [weak self] in
                    self?.currentItemEnded = false
                },
            ]
        }
        framePath?.attach(to: item)
        avPlayer.replaceCurrentItem(with: item)
    }

    /// Records where the current content has got to, if it is content this player can name. Content
    /// that ended is recorded at the beginning (``MediaRequest/StartPosition/resumeFromLastKnown``).
    private func rememberPositionOfCurrentContent() {
        guard let contentId = currentContentId else { return }
        rememberedPositions.remember(positionOfCurrentContentMs, for: contentId)
    }

    /// Where the current content has got to, as a resume position: the beginning for content that
    /// ended (``MediaRequest/StartPosition/resumeFromLastKnown``).
    var positionOfCurrentContentMs: Int64 {
        currentItemEnded ? 0 : Self.milliseconds(avPlayer.currentTime())
    }

    /// Where `request` starts, in milliseconds, or nil for the content's own default position — the
    /// start of on-demand content and wherever `AVPlayer` joins a live stream.
    private func resolvedStartPositionMs(of request: MediaRequest) -> Int64? {
        switch request.startPosition {
        case .beginning: nil
        case .at(let positionMs): positionMs
        case .resumeFromLastKnown: rememberedPositions.position(for: request.contentId)
        }
    }

    /// The timescale positions are written and read in: the request's milliseconds, exactly.
    private static let millisecondsPerSecond: CMTimeScale = 1_000

    /// `time` in whole milliseconds, never negative; zero for a time that is not a number, which
    /// is what an item not yet ready reports.
    private static func milliseconds(_ time: CMTime) -> Int64 {
        guard time.isNumeric else { return 0 }
        return max(0, Int64((time.seconds * Double(millisecondsPerSecond)).rounded()))
    }
}

/// How a ``MediaRequest``'s identity and display fields travel on an `AVPlayerItem`: as its external
/// metadata, the field AVKit adds to the item for what the app knows about it and the media does
/// not (`docs/ios-plan.md` §3.1) — the Kotlin `MediaItem.mediaId` and `MediaMetadata`, for that
/// type's reason: the identity travels *with* the item, through everything that already reads one,
/// rather than in a map the facade would have to keep in step with the player.
///
/// ref: https://developer.apple.com/documentation/avkit/avplayeritem/externalmetadata
enum ContentIdentity {

    /// `contentId` as the asset identifier, and the title and subtitle under the identifiers
    /// `AVPlayerViewController`'s title view reads. Each item is tagged `und`, the undetermined
    /// language, since a request states no language for its text.
    ///
    /// ref: AVKit's `AVPlayerViewController.h`, on the title view: "Supported keys are:
    /// AVMetadataCommonIdentifierTitle for title, AVMetadataIdentifieriTunesMetadataTrackSubTitle for
    /// subtitle."
    /// ref: RFC 5646 §4.1 (BCP 47), which reserves `und` for an undetermined language
    static func metadata(of request: MediaRequest) -> [AVMetadataItem] {
        var items = [item(.commonIdentifierAssetIdentifier, request.contentId)]
        if let title = request.title { items.append(item(.commonIdentifierTitle, title)) }
        if let subtitle = request.subtitle {
            items.append(item(.iTunesMetadataTrackSubTitle, subtitle))
        }
        return items
    }

    /// The content id ``metadata(of:)`` wrote on `item`, or nil if it carries none. On the main
    /// actor because AVKit declares `externalMetadata` there.
    ///
    /// Read off the mutable item this type made, whose `value` is synchronous, rather than through
    /// `AVMetadataItem.stringValue`, which iOS 16 deprecated for an asynchronous load: an item's own
    /// external metadata is the app's and was never loaded from anywhere, so there is nothing to
    /// wait for, and an identity that had to be awaited would make every replacement asynchronous.
    @MainActor
    static func contentId(of item: AVPlayerItem) -> String? {
        item.externalMetadata
            .first { $0.identifier == .commonIdentifierAssetIdentifier }
            .flatMap { ($0 as? AVMutableMetadataItem)?.value as? String }
    }

    private static func item(_ identifier: AVMetadataIdentifier, _ value: String)
        -> AVMetadataItem
    {
        let item = AVMutableMetadataItem()
        item.identifier = identifier
        item.value = value as NSString
        item.extendedLanguageTag = "und"
        return item
    }
}
