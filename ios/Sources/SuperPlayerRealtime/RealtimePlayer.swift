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
import AVKit
import CoreMedia
import SuperPlayerCore
import UIKit

/// A player of realtime streams: encoded frames pushed by a ``FrameSource``, decoded and shown by
/// the platform's sample-buffer renderers under one clock.
///
/// ```swift
/// let player = RealtimePlayer(
///     configuration: .init(sources: Realtime.transport(scheme: "moq") { MoqFrameSource($0) }))
/// try player.setMediaRequest(
///     MediaRequest(contentId: "studio-a", sources: [URL(string: "moq://relay.example/studio-a")!]))
/// player.play()
/// // and on screen: PlayerView(player: player)
/// ```
///
/// **This is the one place the "one player" claim splits** (ADR-0020 rule 11): an `AVPlayer` cannot
/// take pushed frames, so realtime is a second public type, over an `AVSampleBufferRenderSynchronizer`
/// with the `AVSampleBufferDisplayLayer` ADR-0022 also chose and an `AVSampleBufferAudioRenderer`.
/// Core's `PlaybackEngine` says what the two types share.
///
/// **Nothing here decodes** (ADR-0018 rule 1). A frame is wrapped in a `CMSampleBuffer` under the
/// format description its codec string names (`RealtimeFormats.swift`) and enqueued; the renderers
/// decode it.
///
/// **The scheme is the only discriminator** (ADR-0018 rule 12): a request plays here when its first
/// source's scheme is one ``Configuration/sources`` answers, and is refused with
/// ``UnansweredRealtimeSchemeError`` — naming `SuperPlayer` as the player for it — when it is not.
///
/// **A realtime timeline is live and unseekable**, so a request starting anywhere but at its edge is
/// refused typed (``RealtimeStreamNotSeekableError``, ADR-0018 rule 5), and the clock starts at the
/// first frame delivered — its timestamp subtracted from every frame's — and is never sought.
///
/// **What it shares with `SuperPlayer`** (#461), each through core's engine protocols: the library's
/// view; the lifecycle's rules — the shared audio session and idle timer, an interruption that
/// pauses and whose end resumes, headphones unplugged that pause; measurement, through
/// ``Configuration/telemetry`` and ``declarePlaybackIntent()``, deriving what the schema can say of
/// a realtime stream (`docs/telemetry-schema.md`, *Realtime sessions*); publishing, through
/// `PlaybackSession(player:)`, as a live stream with no seek command; and Picture in Picture from
/// its display layer, over a live, unseekable timeline.
///
/// Isolated to the main actor, as ``SuperPlayer`` is and for its reason; the frames themselves are
/// handled on a queue of the player's own.
@MainActor
public final class RealtimePlayer {

    /// What a realtime player is configured with, fixed for its lifetime (ADR-0020 rule 5).
    public struct Configuration: Sendable {

        /// The transports this player plays, and the schemes that reach them.
        public var sources: RealtimeSources

        /// Makes the collector that measures this player, or nil — the default — for a player that
        /// measures nothing and pays nothing for it (ADR-0008 rule 2), as
        /// ``SuperPlayer/Configuration/telemetry`` is.
        ///
        /// ```swift
        /// configuration.telemetry = { QoeCollector(sink: analyticsSink) }
        /// ```
        ///
        /// `QoeCollector` derives the schema's events from the stream's clock and renderers. A
        /// collector of your own is told each session's edges and the declared intent, and is
        /// attached to nothing: its `attach(to:)` takes a `SuperPlayer`, and what there is to
        /// observe on a realtime stream is a seam of this library's own.
        public var telemetry: (@MainActor @Sendable () -> any TelemetryCollector)?

        public init(
            sources: RealtimeSources,
            telemetry: (@MainActor @Sendable () -> any TelemetryCollector)? = nil
        ) {
            self.sources = sources
            self.telemetry = telemetry
        }
    }

    /// The configuration this player was made with.
    public let configuration: Configuration

    /// The content id of the request playing, or nil when none is.
    public private(set) var currentContentId: String?

    /// Why the stream stopped, if it failed: a transport's own error from ``FrameSink/onError(_:)``,
    /// or the refusal of what it delivered — ``UnsupportedRealtimeCodecError``,
    /// ``MalformedRealtimeBitstreamError``, ``RealtimeTrackStalledError`` for one track of several
    /// fallen behind the others, or a broken obligation named in plain words. Nil while the stream
    /// plays, and cleared by the next ``setMediaRequest(_:)``.
    public private(set) var failure: (any Error)?

    /// Whether the transport said the stream ended of its own accord (``FrameSink/onEnded()``). A
    /// live stream that is never ended never reports it, as on Android, where a realtime timeline
    /// never reaches `STATE_ENDED`.
    public private(set) var hasEnded = false

    /// Always ``FrameOutputOffReason/realtime``: a realtime player takes no frame output, because no
    /// decoded frame exists here to hand one — the display layer decodes the pushed frames itself
    /// (ADR-0022 rule 6). Never an error, as for ``SuperPlayer``.
    public nonisolated var frameOutputOffReason: FrameOutputOffReason { .realtime }

    /// Where the stream has got to on its own clock, which reads zero at the first frame delivered
    /// and advances only while the player plays — the synchronizer's time, as a `CMTime` because
    /// that is CoreMedia's own and the unit the renderers present in, as ``FrameOutput``'s is.
    public var currentTime: CMTime { engine.synchronizer.currentTime() }

    /// Whether the decoded picture is being presented: the clock is running and the display layer
    /// has accepted the stream without failing. False before the first frame, while paused, and
    /// after a failure.
    public var isRendering: Bool {
        engine.isClockRunning && engine.videoRenderer.status == .rendering
    }

    let engine: RealtimeEngine

    /// The lifecycle's rules over this player, made as the player is (``LifecycleTarget``).
    private var lifecycle: LifecycleBinding?
    private var source: (any FrameSource)?
    private var subscription: UInt64 = 0
    private var wantsToPlay = false
    package private(set) var hasVideo = false
    private var isReleased = false

    /// The request playing, whose identity and title a ``PlaybackSession`` publishes.
    private var currentRequest: MediaRequest?

    /// Whether the current stream's first frame was enqueued, so the clock could start.
    private var firstFrameEnqueued = false

    /// Whether a renderer was last reported as run dry (``RealtimePlaybackState/starved``).
    private var isStarved = false

    /// The collector ``Configuration/telemetry`` made, or nil; with it, the id of the session it is
    /// measuring.
    private let collector: (any TelemetryCollector)?
    private var measuredSessionId: String?

    /// Who hears this player change: the collector's and a session's registrations.
    private let changes = ChangeObservers()

    /// Picture in Picture's playback questions, answered over this player.
    private lazy var pictureInPicturePlayback = RealtimePictureInPicturePlayback(player: self)

    public convenience init(configuration: Configuration) {
        self.init(configuration: configuration, lifecycle: .system)
    }

    /// A player whose lifecycle needs reach `lifecycle`'s platform rather than the system's — the
    /// seam a test passes a platform that does nothing through, since an `xctest` host has no
    /// `UIApplication` whose idle timer a video could hold (ADR-0021 rule 5's #402 addendum).
    package init(configuration: Configuration, lifecycle: SharedLifecycle) {
        self.configuration = configuration
        let owner = Owner()
        self.engine = RealtimeEngine { subscription, event in
            MainActor.assumeIsolated { owner.player?.heard(event, of: subscription) }
        }
        self.collector = configuration.telemetry?()
        owner.player = self
        // The binding's own `deinit` withdraws what this player needed, one main-actor turn after
        // this player is gone.
        self.lifecycle = LifecycleBinding(target: self, shared: lifecycle)
        if let measuring = collector as? any RealtimeMeasuringCollector {
            measuring.attach(toRealtime: self)
        }
    }

    /// Plays what `request` describes: its first source, opened through the transport its scheme
    /// names, from the live edge.
    ///
    /// Throws **before any bookkeeping runs**, so a refused request leaves the player exactly as it
    /// was — playing whatever it was playing:
    /// - ``UnansweredRealtimeSchemeError`` when no transport of ``Configuration/sources`` answers
    ///   the first source's scheme;
    /// - ``RealtimeStreamNotSeekableError`` for ``MediaRequest/StartPosition/at(positionMs:)`` and
    ///   ``MediaRequest/StartPosition/resumeFromLastKnown`` (ADR-0018 rule 5).
    ///
    /// Otherwise the current stream is cancelled and the new one subscribed. Like `SuperPlayer`'s
    /// it starts nothing: ``play()`` does.
    public func setMediaRequest(_ request: MediaRequest) throws {
        guard !isReleased else { return }
        let url = request.sources[0]
        guard configuration.sources.answers(url.scheme) else {
            throw UnansweredRealtimeSchemeError(
                scheme: url.scheme ?? "", answered: configuration.sources.schemes)
        }
        let scheme = url.scheme?.lowercased() ?? ""
        switch request.startPosition {
        case .beginning: break
        case .at: throw RealtimeStreamNotSeekableError(scheme: scheme, startPosition: "at")
        case .resumeFromLastKnown:
            throw RealtimeStreamNotSeekableError(
                scheme: scheme, startPosition: "resumeFromLastKnown")
        }

        // Closed while the outgoing stream is still the player's, as `SuperPlayer` closes one
        // before its item is replaced.
        endMeasurement()
        cancelSource()
        failure = nil
        hasEnded = false
        hasVideo = false
        firstFrameEnqueued = false
        isStarved = false
        currentContentId = request.contentId
        currentRequest = request
        let (sink, number) = engine.beginSubscription()
        subscription = number
        engine.setWantsToPlay(wantsToPlay)
        let opened = configuration.sources.factory.open(url)
        source = opened
        opened.subscribe(sink)
        beginMeasurement(of: request.contentId)
        changed()
    }

    /// Says that a viewer just asked for playback — the start boundary of time to first frame, as
    /// ``SuperPlayer/declarePlaybackIntent()`` says it: call it at the tap, before whatever turns
    /// the tap into a ``MediaRequest``. Cheap and safe with no telemetry configured.
    public func declarePlaybackIntent() {
        guard !isReleased, let collector else { return }
        // `CLOCK_MONOTONIC`, the clock every duration in the schema is on (`TelemetryEvent`'s
        // *The clocks*), and the one the collector reads the other end of the span on.
        collector.declareIntent(
            monotonicTimeMs: Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000))
    }

    /// Starts the clock — at the first frame, if none has arrived yet — and keeps it running.
    public func play() {
        setWantsToPlay(true)
    }

    /// Stops the clock where it is. A live stream resumed later resumes from there, behind the edge
    /// by as long as it was paused.
    public func pause() {
        setWantsToPlay(false)
    }

    /// Ends what this player is doing, for good: the source is cancelled, the renderers let go, and
    /// nothing more is shown or held. Calling it again does nothing.
    public func release() {
        guard !isReleased else { return }
        endMeasurement()
        collector?.detach()
        isReleased = true
        cancelSource()
        currentContentId = nil
        currentRequest = nil
        engine.stop()
        lifecycle?.release()
        changed()
    }

    // MARK: - Internal

    private func setWantsToPlay(_ wants: Bool) {
        guard !isReleased else { return }
        wantsToPlay = wants
        engine.setWantsToPlay(wants)
        changed()
    }

    /// Opens a measurement session for `contentId` and tells the collector. The id is minted here:
    /// nothing on a realtime path sends one to a CDN (ADR-0018 rule 6), so there is no wire value
    /// for it to follow, as `SuperPlayer`'s follows its asset's. A UUID, 36 characters, inside the
    /// schema's 64.
    private func beginMeasurement(of contentId: String) {
        guard let collector else { return }
        let sessionId = UUID().uuidString.lowercased()
        measuredSessionId = sessionId
        collector.startSession(contentId: contentId, sessionId: sessionId)
    }

    /// Closes the open measurement session, if there is one, and tells the collector.
    private func endMeasurement() {
        guard measuredSessionId != nil else { return }
        measuredSessionId = nil
        collector?.endSession()
    }

    /// Cancels the source, once — obligation 9's other half is the source's: no callback after this
    /// returns. Anything it delivered before is dropped by the engine as a stale subscription's.
    private func cancelSource() {
        source?.cancel()
        source = nil
    }

    private func heard(_ event: RealtimeEngineEvent, of number: UInt64) {
        guard number == subscription, !isReleased else { return }
        switch event {
        case .tracksDeclared(let video):
            hasVideo = video
        case .firstFrameEnqueued:
            firstFrameEnqueued = true
        case .starvationChanged(let starved):
            isStarved = starved
        case .ended:
            hasEnded = true
            isStarved = false
        case .failed(let error):
            failure = error
            isStarved = false
            cancelSource()
        }
        changed()
    }

    /// Tells everyone who reads this player that it changed: the lifecycle's rules, which read what
    /// it needs of the process's audio session and idle timer — the session while it wants to play
    /// a stream that has not stopped, the idle timer while that stream has a picture — and the
    /// collector's and a session's registrations.
    private func changed() {
        lifecycle?.targetChanged()
        changes.notify()
    }
}

extension RealtimePlayer: LifecycleTarget {

    /// Asked to play a stream that has not stopped.
    package var isAskedToPlay: Bool {
        wantsToPlay && currentContentId != nil && failure == nil && !hasEnded && !isReleased
    }

    /// The stream declared a picture.
    package var showsPicture: Bool { hasVideo }
}

extension RealtimePlayer: RealtimeMeasurable {

    package var playbackState: RealtimePlaybackState {
        guard isAskedToPlay else { return .paused }
        guard firstFrameEnqueued else { return .waitingForFirstFrame }
        return isStarved ? .starved : .playing
    }

    package var bufferedAheadMs: Int64 { engine.bufferedAheadUs / 1_000 }

    package var videoRenderer: AVSampleBufferVideoRenderer { engine.videoRenderer }

    package func observeMeasurement(_ changed: @escaping @MainActor () -> Void) -> AnyObject {
        changes.add(changed)
    }
}

extension RealtimePlayer: PlaybackEngine {

    /// The engine's display layer, the one a view hosts.
    package func makeHostedLayer() -> CALayer { engine.displayLayer }

    /// Picture in Picture from the display layer, over a live and unseekable timeline
    /// (``RealtimePictureInPicturePlayback``) — ADR-0022 rule 5's reason for choosing that layer,
    /// which the realtime path shares.
    package func pictureInPictureContentSource(for layer: CALayer)
        -> AVPictureInPictureController.ContentSource?
    {
        guard let layer = layer as? AVSampleBufferDisplayLayer, layer === engine.displayLayer
        else { return nil }
        return AVPictureInPictureController.ContentSource(
            sampleBufferDisplayLayer: layer, playbackDelegate: pictureInPicturePlayback)
    }

    /// Play, pause and toggle, and no seek: a realtime timeline has no past to move to (ADR-0018
    /// rule 5), so the scrubber and the skips are buttons the system is not asked to draw.
    package var remoteCommands: Set<RemoteCommand> { [.play, .pause, .togglePlayPause] }

    package func perform(_ event: RemoteCommandEvent) -> Bool {
        guard !isReleased else { return false }
        switch event {
        case .play: play()
        case .pause: pause()
        case .togglePlayPause: wantsToPlay ? pause() : play()
        case .changePlaybackPosition, .skip:
            // Never targeted (``remoteCommands``), and refused rather than coerced if one arrives.
            return false
        }
        return true
    }

    package var nowPlayingRequest: MediaRequest? { currentRequest }

    /// Live, at one while the clock runs and zero while it does not — a starved stream reads as
    /// playing, as a waiting `AVPlayer`'s rate does.
    package func nowPlayingInfo(artwork: UIImage?) -> [String: Any]? {
        let running = playbackState == .playing || playbackState == .starved
        return NowPlayingInfo.live(request: currentRequest, rate: running ? 1 : 0, artwork: artwork)
    }

    package func observeNowPlaying(_ changed: @escaping @MainActor @Sendable () -> Void)
        -> [AnyObject]
    {
        [changes.add(changed)]
    }

    package func adopt(resolved request: MediaRequest) -> Bool {
        (try? setMediaRequest(request)) != nil
    }
}

/// Picture in Picture's playback questions over a ``RealtimePlayer`` — `FramePath.swift`'s
/// `PictureInPicturePlayback` for a timeline that is live and unseekable.
///
/// ref: https://developer.apple.com/documentation/avkit/avpictureinpicturesamplebufferplaybackdelegate
///
/// The time range is unbounded at both ends, which is how a live stream is described to Picture in
/// Picture: on an iPhone (#461) the window then draws its LIVE badge and a bar with no thumb. It
/// still draws both skip buttons, whatever the range's start — the app's
/// `requiresLinearPlayback` greys the forward one out and nothing reaches the back one — so a skip
/// completes here having moved nothing, because there is nowhere to move to. On the main actor, as the player is; AVKit calls a playback delegate
/// on the main thread, and the `@preconcurrency` conformance checks that it did.
@MainActor
final class RealtimePictureInPicturePlayback: NSObject,
    @preconcurrency AVPictureInPictureSampleBufferPlaybackDelegate
{

    private weak var player: RealtimePlayer?

    init(player: RealtimePlayer) {
        self.player = player
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController, setPlaying playing: Bool
    ) {
        if playing { player?.play() } else { player?.pause() }
    }

    func pictureInPictureControllerTimeRangeForPlayback(
        _ pictureInPictureController: AVPictureInPictureController
    ) -> CMTimeRange {
        CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity)
    }

    func pictureInPictureControllerIsPlaybackPaused(
        _ pictureInPictureController: AVPictureInPictureController
    ) -> Bool {
        player?.isAskedToPlay != true
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        didTransitionToRenderSize newRenderSize: CMVideoDimensions
    ) {}

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        skipByInterval skipInterval: CMTime, completion completionHandler: @escaping () -> Void
    ) {
        completionHandler()
    }
}

/// The registrations a ``RealtimePlayer`` tells of every change, in the order they were made.
@MainActor
private final class ChangeObservers {

    private var handlers: [(id: UInt64, handler: @MainActor () -> Void)] = []
    private var nextId: UInt64 = 0

    /// Registers `handler` until the object returned is gone.
    func add(_ handler: @escaping @MainActor () -> Void) -> AnyObject {
        nextId += 1
        let id = nextId
        handlers.append((id, handler))
        return ChangeRegistration { [weak self] in self?.handlers.removeAll { $0.id == id } }
    }

    func notify() {
        for (_, handler) in handlers { handler() }
    }
}

/// One ``ChangeObservers`` registration, removed as it goes.
///
/// `deinit` is not isolated to the main actor on the iOS 17 floor; a registration is held and let go
/// by main-actor objects, so the removal runs there and then, and is queued there otherwise.
private final class ChangeRegistration {
    private let remove: @MainActor () -> Void

    init(_ remove: @escaping @MainActor () -> Void) {
        self.remove = remove
    }

    deinit {
        let remove = self.remove
        if Thread.isMainThread {
            MainActor.assumeIsolated { remove() }
        } else {
            Task { @MainActor in remove() }
        }
    }
}

/// The player an engine's events are reported to, held weakly so the engine does not keep it alive.
///
/// `@unchecked Sendable` because the one reference is written once on the main actor as the player
/// is made, and read only inside `MainActor.assumeIsolated` on the main queue.
private final class Owner: @unchecked Sendable {
    weak var player: RealtimePlayer?
}

/// A request reached a ``RealtimePlayer`` with a first source no transport of its configuration
/// answers — an `https` URL, typically, which is a `SuperPlayer`'s to play.
///
/// The mirror of ADR-0020 rule 11's refusal: the split between the two player types is only honest
/// if the wrong half says where the right one is, so this names ``SuperPlayer`` rather than failing
/// as a stream that never connects. Thrown before any bookkeeping, as
/// ``RealtimeStreamNotSeekableError`` is.
public struct UnansweredRealtimeSchemeError: Error, Sendable, Hashable, CustomStringConvertible {

    /// The scheme the request's first source carried, as written; empty for a URL with none.
    public let scheme: String

    /// The schemes the player's transports answer.
    public let answered: Set<String>

    public var description: String {
        "No realtime transport of this RealtimePlayer answers the scheme \"\(scheme)\"; it answers "
            + "\(answered.sorted().joined(separator: ", ")). A realtime stream's first source "
            + "carries a transport's scheme, and content over HTTP is a SuperPlayer's to play."
    }
}
