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
/// **What it does not have yet**, stated rather than discovered: a telemetry collector and a
/// ``PlaybackSession`` take a `SuperPlayer` today, so a realtime stream is measured and published by
/// neither; and the lifecycle's interruption, route-change and resume rules are an `AVPlayer`'s, so
/// the synchronizer's own rule — it stops for an interruption by itself — is the one in force
/// (`docs/testing.md`, *A realtime transport on iOS*).
///
/// Isolated to the main actor, as ``SuperPlayer`` is and for its reason; the frames themselves are
/// handled on a queue of the player's own.
@MainActor
public final class RealtimePlayer {

    /// What a realtime player is configured with, fixed for its lifetime (ADR-0020 rule 5).
    public struct Configuration: Sendable {

        /// The transports this player plays, and the schemes that reach them.
        public var sources: RealtimeSources

        public init(sources: RealtimeSources) {
            self.sources = sources
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

    private let lifecycle: SharedLifecycle
    private let lifecycleToken: UInt64
    private var source: (any FrameSource)?
    private var subscription: UInt64 = 0
    private var wantsToPlay = false
    private var hasVideo = false
    private var isReleased = false

    public convenience init(configuration: Configuration) {
        self.init(configuration: configuration, lifecycle: .system)
    }

    /// A player whose lifecycle needs reach `lifecycle`'s platform rather than the system's — the
    /// seam a test passes a platform that does nothing through, since an `xctest` host has no
    /// `UIApplication` whose idle timer a video could hold (ADR-0021 rule 5's #402 addendum).
    package init(configuration: Configuration, lifecycle: SharedLifecycle) {
        self.configuration = configuration
        self.lifecycle = lifecycle
        self.lifecycleToken = lifecycle.issueToken()
        let owner = Owner()
        self.engine = RealtimeEngine { subscription, event in
            MainActor.assumeIsolated { owner.player?.heard(event, of: subscription) }
        }
        owner.player = self
    }

    deinit {
        // `deinit` is not isolated to the main actor on the iOS 17 floor, so the need is withdrawn
        // one main-actor turn after it, as `LifecycleBinding`'s is.
        Task { @MainActor [lifecycle, lifecycleToken] in lifecycle.withdraw(lifecycleToken) }
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

        cancelSource()
        failure = nil
        hasEnded = false
        hasVideo = false
        currentContentId = request.contentId
        let (sink, number) = engine.beginSubscription()
        subscription = number
        engine.setWantsToPlay(wantsToPlay)
        let opened = configuration.sources.factory.open(url)
        source = opened
        opened.subscribe(sink)
        publishLifecycle()
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
        isReleased = true
        cancelSource()
        currentContentId = nil
        engine.stop()
        lifecycle.withdraw(lifecycleToken)
    }

    // MARK: - Internal

    private func setWantsToPlay(_ wants: Bool) {
        guard !isReleased else { return }
        wantsToPlay = wants
        engine.setWantsToPlay(wants)
        publishLifecycle()
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
            break
        case .ended:
            hasEnded = true
        case .failed(let error):
            failure = error
            cancelSource()
        }
        publishLifecycle()
    }

    /// What this player needs of the process's audio session and idle timer, stated to the count
    /// `SuperPlayer`s state theirs to: the session while it wants to play a stream that has not
    /// stopped, and the idle timer while that stream has a picture.
    private func publishLifecycle() {
        let playing = wantsToPlay && currentContentId != nil && failure == nil && !hasEnded
        lifecycle.state(lifecycleToken, wantsToPlay: playing, showsVideo: playing && hasVideo)
    }
}

extension RealtimePlayer: PlaybackEngine {

    /// The engine's display layer, the one a view hosts.
    package func makeHostedLayer() -> CALayer { engine.displayLayer }

    /// None yet: Picture in Picture from a sample-buffer layer needs a playback delegate answering
    /// for a live, unseekable timeline, which nothing here has built or a device has shown.
    package func pictureInPictureContentSource(for layer: CALayer)
        -> AVPictureInPictureController.ContentSource?
    {
        nil
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
