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
// For ``SuperPlayer/errors``, the facade's error publisher: Apple's own, and what AVFoundation's
// key-value observations are already consumed through.
import Combine

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

    /// How many times rung 5 rebuilds the item at one position before the ladder gives up on it:
    /// two — the Kotlin `SuperPlayer.MAX_DECODER_RECREATIONS`, for its reasons. The first covers the
    /// case the rung exists for, the media services reset once and back at once; the second a
    /// device where that moment had not passed when the first rebuild asked. A third failure at a
    /// position that has played nothing in between is a decoder that is not coming back, and a
    /// viewer is better served by rung 6's typed error than by a player rebuilding itself behind a
    /// spinner. The count starts over wherever playback advanced (``mayRecreatePlayer(at:)``), so it
    /// bounds futile rebuilds only.
    ///
    /// `package` rather than public, for the Kotlin `internal`'s reason — it bounds no promise a
    /// consumer writes code against — and rather than internal because `SuperPlayerResilience`'s
    /// tests drive the bound through a real player.
    nonisolated package static let maxDecoderRecreations = 2

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

        /// The protection this player plays under, or nil — the default — for a player that makes
        /// no key session and pays nothing for one (ADR-0012 rule 13). The Android `setDrm`, taking
        /// `SuperPlayerDRM`'s `Drm.fairPlay(_:)`, and fixed for the player's lifetime as every
        /// configuration is (ADR-0012 rule 1): every item the player plays is a recipient of its one
        /// key session, clear items included.
        ///
        /// ```swift
        /// var configuration = SuperPlayer.Configuration()
        /// configuration.drm = Drm.fairPlay(FairPlayConfig(certificateURL: cert, licenceURL: ksm))
        /// configuration.resilience = Resilience.standard(headers: Tokens())
        /// ```
        ///
        /// A frame output on a player that has one is off (``SuperPlayer/frameOutputOffReason``).
        /// The licence's credential is the one `Resilience.standard(headers:)` was given, and its
        /// failure is named only on a player configured with that resilience, as on Android, where
        /// both halves need `setResilience` beside `setDrm`.
        public var drm: (any PlaybackDrm)?

        /// What names this player's failures, or nil — the default — for a player that classifies
        /// nothing and pays nothing for it (ADR-0011 rule 14): no observation of an item's failure,
        /// no ``SuperPlayerError``, ``SuperPlayer/classify(_:)`` answering nil, and telemetry's
        /// `classification` nil. Its errors stay AVFoundation's, on the item. The Android
        /// `setResilience`, taking `SuperPlayerResilience`'s `Resilience.standard()`.
        ///
        /// ```swift
        /// var configuration = SuperPlayer.Configuration()
        /// configuration.resilience = Resilience.standard()
        /// ```
        public var resilience: (any PlaybackResilience)?
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
    var currentRequest: MediaRequest?

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

    /// The key session ``Configuration/drm`` made for this player (`Protection.swift`), or nil on a
    /// player configured without one — what the pays-nothing count reads, and what
    /// `SuperPlayerDRM`'s offline store performs its verbs over (`store.over(player)`, #423).
    package private(set) var protection: (any ProtectionSession)?

    /// The pool attachment this player adopts through, set by the ``PlayerPool`` that made it while
    /// one is attached; nil on every other player, which asks nothing at adoption (ADR-0010 rule
    /// 13). Weak, because the pool holds the attachment and the attachment the pool.
    weak var poolAttachment: (any PoolAttachment)?

    /// When the pool that made this player last handed it out, in acquires: what lets the pool read
    /// the newest player's decision (``PlayerPool/playbackDecision``). Zero on any other player.
    var acquiredOrder = 0

    /// The item ``holdWarm(_:)`` made current, until a request adopts it or the player lets it go.
    private var heldWarm: AVPlayerItem?
    /// The ladder ``Configuration/resilience`` made for this player (`PlayerStateRungs.swift`), or
    /// nil on a player configured without one, or with a value that conforms to
    /// ``PlaybackResilience`` alone.
    let rungs: (any PlayerStateRungs)?

    /// The typed error the current item's failure was classified as, decided once per item so that
    /// ``classify(_:)``, the publisher and the collector are one answer rather than three (the Kotlin
    /// `failureTypedError` memo). Cleared at every replacement.
    var decidedFailure: SuperPlayerError?

    /// Whether the current item's failure has been taken up — climbed for, or handed to ``errors``.
    var failureDelivered = false

    /// Which of the current request's ``MediaRequest/sources`` is playing: rung 4 moves it on.
    var sourceIndex = 0

    /// Where rung 5 last rebuilt the item, and how many times at that position — core's half of the
    /// rung (``mayRecreatePlayer(at:)``).
    var recreatedAtMs: Int64?
    var recreations = 0

    /// The position the current item was made to start at, or nil — what the held position is
    /// while the item reports none of its own (``heldPositionMs(of:)``).
    var itemStartMs: Int64?

    /// The rate the viewer last asked for, heard on a player configured with resilience: what a
    /// rung's re-adoption resumes at (``ObservedSignal/resilienceRateChanged``).
    var requestedRate: Float = 0

    /// The media services reset and the requested rate, registered for the player's whole life on a
    /// player configured with resilience and on no other (ADR-0011 rule 14).
    private var resilienceObservations: [Observation] = []

    /// What ``errors`` publishes from.
    private let errorSubject = PassthroughSubject<any Error, Never>()

    /// The last error ``errors`` delivered, or nil — the Kotlin `player.playerError` — cleared when
    /// the facade replaces the content, so it is always about what is current.
    ///
    /// A ``SuperPlayerError`` for an item that failed on a player configured with
    /// ``Configuration/resilience``, and an ``UnplayableSourceSchemeError`` for a source refused on
    /// any player. Nil for an item that failed on a player without resilience, whose error is
    /// AVFoundation's and stays on `avPlayer.currentItem?.error`, unchanged (ADR-0011 rule 14).
    public private(set) var playerError: (any Error)?

    /// **The facade's error publisher**: every failure this player has something to say about, once,
    /// on the main actor, as it happens — the Kotlin `Player.Listener.onPlayerError` and rung 6's
    /// delivery (ADR-0011 rule 10, `docs/ios-plan.md` §3.5).
    ///
    /// What arrives is what ``playerError`` describes. Combine's publisher rather than a callback of
    /// SuperPlayer's own, because it is Apple's idiom for an event several parties hear (ADR-0020
    /// rule 5) — a view, the app's error reporting — and rather than an `AsyncStream`, which has one
    /// consumer. A subscriber hears what fails after it subscribed; ``playerError`` is the last one.
    public var errors: AnyPublisher<any Error, Never> { errorSubject.eraseToAnyPublisher() }

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
        self.lifecycle = LifecycleBinding(player: avPlayer, shared: lifecycle)
        playbackDecision.apply(to: avPlayer)
        self.framePath = FramePath.make(configuration: configuration, player: avPlayer)
        self.rungs = (configuration.resilience as? any FailureClassifying)?.playerStateRungs()
        self.collector = configuration.telemetry?()
        self.measurement = collector.map { _ in MeasurementSession() }
        collector?.attach(to: self)
        // Last, so a change heard as the observers start — the first network path — finds the
        // player and its collector whole.
        reapplication?.onDecisionChanged = { [weak self] decision, trigger in
            self?.decisionChanged(to: decision, on: trigger)
        }
        reapplication?.start(on: avPlayer)
        // After every stored property, because the session holds the player as its host.
        protection = (configuration.drm as? any ContentProtecting)?.protectionSession(host: self)
        if rungs != nil {
            resilienceObservations = [
                Observation.register(.resilienceMediaServicesReset, on: avPlayer) {
                    [weak self] _ in self?.mediaServicesWereReset()
                },
                Observation.register(.resilienceRateChanged, on: avPlayer) {
                    [weak self] userInfo in
                    let reason = userInfo?[AVPlayer.rateDidChangeReasonKey] as? String
                    guard let self, reason == AVPlayer.RateDidChangeReason.setRateCalled.rawValue
                    else { return }
                    self.requestedRate = self.avPlayer.rate
                },
            ]
        }
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
    ///
    /// A request whose first source names a scheme `AVPlayer` cannot load — a realtime transport's —
    /// is refused before anything else runs, with ``UnplayableSourceSchemeError`` on ``errors``,
    /// leaving the player exactly as it was (ADR-0020 rule 11).
    public func setMediaRequest(_ request: MediaRequest) {
        guard !isReleased, admits(request.sources[0]) else { return }
        rememberPositionOfCurrentContent()
        load(request, startingAtMs: resolvedStartPositionMs(of: request))
    }

    /// Makes the item for `request`, starting at `startMs` or at the content's own default
    /// position, and makes it current — what ``setMediaRequest(_:)`` and ``restoreSnapshot(_:)``
    /// share, the second with a position it was handed rather than one it resolved.
    func load(_ request: MediaRequest, startingAtMs startMs: Int64?) {
        let item = adoptedItem(for: request)
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
        forgetClimb()
        replaceCurrentItem(with: item, identified: true)
        currentRequest = request
        itemStartMs = startMs
        // Every item adopted here was made by `item(for:configuration:)`, on an `AVURLAsset`.
        if let asset = item.asset as? AVURLAsset {
            beginMeasurement(of: request.contentId, playing: asset)
        }
    }

    /// The item `request` plays from: the warm start a pool's attachment holds for it, if this is a
    /// pooled player and the attachment holds one, and a fresh item otherwise (ADR-0010 rule 7,
    /// `PoolAttachment.swift`). Asked after the outgoing position was remembered and the start
    /// resolved, so a warm start changes which item plays and nothing about what plays.
    private func adoptedItem(for request: MediaRequest) -> AVPlayerItem {
        switch poolAttachment?.warmStart(for: self, adopting: request) {
        case .item(let prefetched):
            return prefetched
        case .prepared:
            if let held = heldWarm, avPlayer.currentItem === held { return held }
            return freshItem(for: request, source: 0)
        case nil:
            return freshItem(for: request, source: 0)
        }
    }

    /// An item on a fresh asset of `request`'s source at `index`, carrying the credential this
    /// player's ladder last refreshed: what an adoption and a rung's re-adoption
    /// (`PlayerStateRungs.swift`) share.
    func freshItem(for request: MediaRequest, source index: Int) -> AVPlayerItem {
        Self.item(
            for: request,
            on: Self.asset(
                of: request.sources[index], configuration: configuration,
                headers: rungs?.requestHeaders ?? [:]))
    }

    /// The item a player of `configuration` plays `request` from: an item on a fresh asset
    /// (``asset(for:configuration:)``) carrying the request's identity.
    package static func item(for request: MediaRequest, configuration: Configuration)
        -> AVPlayerItem
    {
        item(for: request, on: asset(for: request, configuration: configuration))
    }

    /// A fresh `AVURLAsset` of `request`'s first source, with CMCD switched on as `configuration`
    /// says.
    ///
    /// A fresh asset per adoption, never a copy: its `httpSessionIdentifier` is the session's id, on
    /// the wire and in telemetry (`CmcdBinding.swift`). `package` because the preload coordinator
    /// makes its prefetched assets here, ahead of the adoption that plays them — the Kotlin
    /// coordinator's items are built through core's own adoption for the same reason (ADR-0010 rule
    /// 7): an asset made anywhere else would carry another CMCD switch. And because the session id
    /// is the asset's, minted as it is made, a prefetched asset's requests carry the `sid` its
    /// session later reports — the Kotlin `PremintedSessionIds`, by construction.
    package static func asset(for request: MediaRequest, configuration: Configuration)
        -> AVURLAsset
    {
        asset(of: request.sources[0], configuration: configuration, headers: [:])
    }

    /// A fresh `AVURLAsset` of `source`, with CMCD as `configuration` says and `headers` on every
    /// request it issues. Headers only where a refreshed credential gave some, so every other asset
    /// is built exactly as before (`assetHTTPHeaderFieldsKey`). An asset the preload coordinator
    /// prefetched was made through ``asset(for:configuration:)`` and carries no refreshed
    /// credential. That is a gap, recorded in `docs/testing.md`'s *Rungs 4 and 5 on iOS*.
    static func asset(of source: URL, configuration: Configuration, headers: [String: String])
        -> AVURLAsset
    {
        let asset = AVURLAsset(
            url: source, options: headers.isEmpty ? nil : [assetHTTPHeaderFieldsKey: headers])
        CmcdBinding.apply(
            configuration.cmcdMode ?? StaticCmcdPolicy.defaultMode(for: configuration.profile),
            to: asset)
        return asset
    }

    /// An item on `asset` carrying `request`'s identity as its external metadata. `package` for
    /// ``asset(for:configuration:)``'s reason: the coordinator makes a second item on a prefetched
    /// asset when the first is spent, since an item is one player's for life and the asset — its
    /// playlists already loaded — is what the prefetch bought.
    package static func item(for request: MediaRequest, on asset: AVURLAsset) -> AVPlayerItem {
        let item = AVPlayerItem(asset: asset)
        item.externalMetadata = ContentIdentity.metadata(of: request)
        return item
    }

    /// Makes `item` this idle player's current item, paused and with no identity — the Kotlin
    /// `holdWarm`, the decoder stage `AVPlayer.preroll(atRate:)` is then asked of (ADR-0010 rule 7's
    /// #159 addendum, `docs/ios-plan.md` §3.4).
    ///
    /// Nothing is measured or remembered for it and ``currentContentId`` stays nil: a warm player
    /// plays nothing and reports nothing until ``setMediaRequest(_:)`` for that row adopts it, when
    /// the attachment answers ``WarmStart/prepared`` and the item is kept rather than replaced. The
    /// decision is not laid on it until then, so the prefetch's own forward-buffer preference — the
    /// depth it was asked to load to — holds while it waits. `package` because the coordinator is
    /// the caller, on a player its pool says is idle.
    package func holdWarm(_ item: AVPlayerItem) {
        guard !isReleased else { return }
        observations = []
        currentItemEnded = false
        currentRequest = nil
        heldWarm = item
        avPlayer.pause()
        avPlayer.replaceCurrentItem(with: item)
    }

    /// Lets go of the item ``holdWarm(_:)`` gave this player, if it still holds it: the reset a pool
    /// makes on a recycle, so the player is as a fresh one is. Nothing if it holds none.
    package func letGoOfWarmItem() {
        guard heldWarm != nil else { return }
        resetForReuse()
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
    ///
    /// A URL whose scheme `AVPlayer` cannot load is refused as ``setMediaRequest(_:)`` refuses one.
    public func setMediaURL(_ url: URL) {
        guard !isReleased, admits(url) else { return }
        rememberPositionOfCurrentContent()
        endMeasurement()
        forgetClimb()
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
        resilienceObservations = []
        heldWarm = nil
        forgetFailure()
        reapplication?.stop()
        lifecycle.release()
        framePath?.detach()
        protection?.release()
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
        heldWarm = nil
        forgetFailure()
        forgetClimb()
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
        guard let item = avPlayer.currentItem, item !== heldWarm else { return nil }
        return ContentIdentity.contentId(of: item)
    }

    /// The registrations live on this player, per signal — what the observed register's test
    /// counts (ADR-0020 rule 13). Internal, and reached by that test through `@testable`: no later
    /// product needs it, so it is not a `package` seam and stays off the tracked API surface.
    var liveObservations: [ObservedSignal: Int] {
        let all =
            observations + lifecycle.observations + (framePath?.observations ?? [])
            + (reapplication?.observations ?? []) + resilienceObservations
        return all.reduce(into: [:]) { counts, observation in
            counts[observation.signal, default: 0] += 1
        }
    }

    func replaceCurrentItem(with item: AVPlayerItem, identified: Bool) {
        observations = []
        currentItemEnded = false
        currentRequest = nil
        forgetFailure()
        itemStartMs = nil
        playbackDecision.apply(to: item)
        reapplication?.itemChanged(to: item)
        if identified {
            observations += [
                Observation.register(.itemPlayedToEnd, on: item) { [weak self] in
                    self?.currentItemEnded = true
                },
                Observation.register(.itemTimeJumped, on: item) { [weak self] in
                    self?.currentItemEnded = false
                },
            ]
        }
        if rungs != nil {
            // On every item, a bare URL's included: a failure is classifiable whatever named it.
            observations += [ObservedSignal.resilienceItemStatus, .resilienceItemFailedToPlayToEnd]
                .map { signal in
                    Observation.registerFailure(signal, on: item) { [weak self, weak item] error in
                        guard let self, let item else { return }
                        self.itemFailed(item, with: error)
                    }
                }
            // A live playlist that stops advancing fails nothing in AVFoundation, so it is watched
            // for; on-demand content is never judged (`StaleLivePlaylistDetection.swift`).
            observations += StaleLivePlaylistDetection.watch(item) {
                [weak self, weak item] stale in
                guard let self, let item else { return }
                self.livePlaylistWentStale(item, stale)
            }
        }
        framePath?.attach(to: item)
        // Before the item is current, so no key request can precede the session's answer for it. A
        // prefetched asset is handed over here, at adoption, and not as it was prefetched — a gap
        // `docs/testing.md`'s *FairPlay on iOS* records.
        if let asset = item.asset as? AVURLAsset { protection?.addRecipient(asset) }
        // A warm item adopted is already current, and is kept rather than replaced with itself.
        let keepsWarmItem = item === heldWarm && avPlayer.currentItem === item
        heldWarm = nil
        if !keepsWarmItem { avPlayer.replaceCurrentItem(with: item) }
    }

    /// Climbs the ladder for `item`'s failure (`PlayerStateRungs.swift`), once per item — the status
    /// and the notification can both report one failure — and only while `item` is still the current
    /// one, so a late notification for content already replaced is not mistaken for the new
    /// content's. What reaches ``errors`` is rung 6's, and only where no rung below took it on.
    private func itemFailed(_ item: AVPlayerItem, with error: NSError?) {
        guard item === avPlayer.currentItem, !failureDelivered, let rungs,
            // The item's own error first, so the classification reads the item's evidence.
            let error = item.error as NSError? ?? error
        else { return }
        failureDelivered = true
        climb(evidence(of: error, on: item), of: item, rungs)
    }

    /// Climbs the ladder for a live playlist `item` stopped advancing on — a failure AVFoundation never
    /// reports, so the typed error is the evidence (the Kotlin `StaleLivePlaylistException` as the
    /// load's cause), once per item as an item's own failure is.
    ///
    /// Where no rung takes it on, the session ends: the typed error is delivered and the player is
    /// paused, because AVFoundation would otherwise go on waiting at the frozen edge — the Kotlin
    /// player stops in its error state the same way. A viewer who presses play again asks
    /// AVFoundation to go on waiting, and nothing here judges that item again.
    private func livePlaylistWentStale(_ item: AVPlayerItem, _ stale: StaleLivePlaylistError) {
        guard item === avPlayer.currentItem, !failureDelivered, let rungs else { return }
        failureDelivered = true
        // The playlist's own log entry is not handed over: the typed error is the stronger evidence,
        // and an entry naming a playlist would read to the classifier as a refused one.
        climb(
            FailureEvidence(
                error: stale as NSError, loggedFailure: nil,
                playlistURL: (item.asset as? AVURLAsset)?.url),
            of: item, rungs, askingForCredential: false)
        // Still this item: no rung re-adopted, so rung 6 was delivered and the session ends here.
        guard avPlayer.currentItem === item, let decidedFailure else { return }
        collector?.sessionFailed(decidedFailure)
        avPlayer.pause()
    }

    /// `error` with `item`'s own evidence beside it: its error log's last entry and its asset's URL.
    private func evidence(of error: NSError, on item: AVPlayerItem) -> FailureEvidence {
        FailureEvidence(
            error: error, loggedFailure: item.errorLog()?.events.last,
            playlistURL: (item.asset as? AVURLAsset)?.url)
    }

    /// What `error` was, in SuperPlayer's vocabulary: the ``SuperPlayerError`` it ended — or will
    /// end — the current item on, or nil on a player configured without
    /// ``Configuration/resilience``, which has nobody to ask (ADR-0011 rule 14). The Kotlin
    /// `player.classify(error)`.
    ///
    /// Public for the Kotlin reason: `SuperPlayerTelemetry` is not core's, and reaches the answer as
    /// a consumer does — asking the player is what makes telemetry's `classification` and the typed
    /// error a consumer was handed one answer rather than two. An app with error reporting of its
    /// own has the same question. Pass AVFoundation's error, the item's `error` or the one a
    /// notification carried; a ``SuperPlayerError`` is answered with itself.
    ///
    /// The current item's evidence — its error log's last entry, which is where AVFoundation names
    /// the request that failed, and its asset's URL — is read only for the current item's own
    /// failure, so ask while the failed item is current; that answer is decided once per item and
    /// the same thereafter. Any other error is classified on itself alone, with no evidence of an
    /// item it may not belong to.
    public func classify(_ error: any Error) -> SuperPlayerError? {
        guard let rungs else { return nil }
        if let typed = error as? SuperPlayerError { return typed }
        let nsError = error as NSError
        if let decidedFailure, decidedFailure.underlyingError.isEqual(nsError) {
            return decidedFailure
        }
        let item = avPlayer.currentItem
        if let item, item.status == .failed, (item.error as NSError?)?.isEqual(nsError) == true {
            let typed = rungs.typedError(
                for: evidence(of: nsError, on: item), positionMs: heldPositionMs(of: item))
            decidedFailure = typed
            return typed
        }
        return rungs.typedError(
            for: FailureEvidence(error: nsError, loggedFailure: nil, playlistURL: nil),
            positionMs: item.flatMap { Self.positionMs(of: $0) })
    }

    /// Hands `error` to ``errors`` and leaves it on ``playerError``.
    func deliver(_ error: any Error) {
        playerError = error
        errorSubject.send(error)
    }

    /// Refuses `source` when `AVPlayer` cannot load its scheme, delivering
    /// ``UnplayableSourceSchemeError`` and answering false — before anything else a replacement does,
    /// so a refused request leaves the player exactly as it was (ADR-0020 rule 11).
    private func admits(_ source: URL) -> Bool {
        guard !UnplayableSourceSchemeError.isLoadable(source) else { return true }
        deliver(UnplayableSourceSchemeError(scheme: source.scheme ?? ""))
        return false
    }

    /// Drops what is known about the outgoing content's failure, at every replacement.
    private func forgetFailure() {
        decidedFailure = nil
        failureDelivered = false
        playerError = nil
    }

    /// Where `item` had reached, or nil where it reports no numeric time — an item that failed
    /// before it was ready, typically.
    private static func positionMs(of item: AVPlayerItem) -> Int64? {
        let time = item.currentTime()
        return time.isNumeric ? milliseconds(time) : nil
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
    static func milliseconds(_ time: CMTime) -> Int64 {
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
