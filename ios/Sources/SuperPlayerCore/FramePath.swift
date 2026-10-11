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
import QuartzCore
import os

// The frame output's display path, ADR-0022 rule 5 — the one place a decoded frame leaves
// `AVPlayer` for a consumer's `FrameOutput` and comes back to the screen. It is the iOS half of the
// slot Android fills from `superplayer-effects`, and it is core's own, because the pull path is
// AVFoundation's and needs no module of its own to carry a dependency (ADR-0022 rule 3's #406
// addendum says why iOS takes the `FrameOutput` directly).
//
// What is made, and only on a player configured with a frame output that is not off (rule 9):
//
// | made | when | why |
// | --- | --- | --- |
// | `AVSampleBufferDisplayLayer` | with the player | what the frames are shown on, in place of `AVPlayerLayer`; a Picture in Picture content source, and the layer the realtime path renders through |
// | `AVPlayerItemVideoOutput` | per item the facade plays | where a decoded frame is pulled from; `AVPlayerItem.videoComposition` is not supported for HLS (ref below) |
// | `CADisplayLink` | while an item is attached | asks the output for the buffer due at each display refresh |
// | a serial queue | with the player | where the consumer's `process` runs, off the main actor |
// | the `frameOutputItemTimeJumped` observation | per item | flushes the layer on a seek (`ObservedSignals.swift`) |
//
// ref: https://developer.apple.com/documentation/avfoundation/avplayeritem/videocomposition —
// "Video compositions are not supported for use with media served using HTTP Live Streaming."
//
// **The display link runs while an item is attached, paused or not.** A tick that finds no new
// buffer costs one `hasNewPixelBuffer` call; pausing the link with the player would need a second
// observation of `timeControlStatus` in the register for a saving no test here could measure, so
// it is left to the device run (#435) to say whether it is worth one.
//
// A player without one makes none of it, and a view hosting it makes an `AVPlayerLayer`, exactly as
// a stock app would (rule 7); `FrameOutputPaysNothingTests` counts both halves.

extension SuperPlayer {

    /// The pixel format a ``FrameOutput`` is handed: 32-bit BGRA, IOSurface-backed and
    /// Metal-compatible.
    ///
    /// BGRA because it is what Core Image, Metal and Vision all read without a conversion, and what
    /// an effect writing its own output most naturally writes; the decoder's native biplanar YUV
    /// would make every effect convert. IOSurface-backed and Metal-compatible because an effect
    /// renders through a `CVMetalTextureCache` (ADR-0022 rule 5) and the display layer shows an
    /// IOSurface without a copy. Public so an effect allocating its own output buffers can match it.
    ///
    /// ref: https://developer.apple.com/documentation/corevideo/kcvpixelbuffermetalcompatibilitykey
    nonisolated public static let frameOutputPixelFormat: OSType = kCVPixelFormatType_32BGRA

    /// Why this player's frame output is not running, or nil — ADR-0022 rule 6, the Kotlin
    /// `frameOutputOffReason`.
    ///
    /// ``FrameOutputOffReason/protectedContent`` on a player configured with both a frame output and
    /// protection. Nil on a player whose output runs, and on one configured without a frame output
    /// at all, which knows it has none.
    ///
    /// **Fixed when the player is made** and the same on every item it plays, because protection and
    /// the output are both fixed per player and they meet there. So it is a property and not a
    /// publisher: a value that cannot change after the player exists has nothing to publish, and
    /// reading it at build is reading it on every item. It is a reading and never an error — such a
    /// player plays every item, its clear ones included, exactly as it would without the output, on
    /// an `AVPlayerLayer`.
    public var frameOutputOffReason: FrameOutputOffReason? {
        frameOutputOff(output: configuration.frameOutput, drm: configuration.drm)
    }
}

/// One player's frame output: the layer, the per-item video output, the display link and the queue
/// the consumer's ``FrameOutput`` runs on. Made only where ``SuperPlayer/frameOutputOffReason`` is
/// nil and a frame output was configured (``make(configuration:player:)``).
@MainActor
final class FramePath {

    /// The layer the processed frames are enqueued to. A ``PlayerLayerView`` hosts it.
    let layer = AVSampleBufferDisplayLayer()

    /// Picture in Picture's view of the player: what a content source made from ``layer`` asks
    /// whether to play, pause or skip. Held here because the content source holds it weakly.
    let pictureInPicturePlayback: PictureInPicturePlayback

    /// The registration flushing the layer on a seek of the current item.
    private(set) var observations: [Observation] = []

    private let processing: FrameProcessing
    private var videoOutput: AVPlayerItemVideoOutput?
    private var item: AVPlayerItem?
    private var displayLink: CADisplayLink?

    /// Whether this path holds a display link: the test seam the pays-nothing count reads, since a
    /// link is invisible to everything but its own target.
    var hasDisplayLink: Bool { displayLink != nil }

    /// The path for a player made with `configuration`, or nil where none is to run — no output
    /// configured, or one that is off (ADR-0022 rules 6 and 9). Nil makes nothing at all. The off
    /// decision is ``frameOutputOff(output:drm:)``'s, the one place it is made.
    static func make(configuration: SuperPlayer.Configuration, player: AVPlayer) -> FramePath? {
        guard let output = configuration.frameOutput,
            frameOutputOff(output: output, drm: configuration.drm) == nil
        else { return nil }
        return FramePath(output: output, player: player)
    }

    private init(output: any FrameOutput, player: AVPlayer) {
        processing = FrameProcessing(output: output, renderer: layer.sampleBufferRenderer)
        pictureInPicturePlayback = PictureInPicturePlayback(player: player)
    }

    /// Pulls frames from `item` from now on, letting go of the item before it. Called by the facade
    /// before the item is made current, so the output is on it before its first frame decodes.
    func attach(to item: AVPlayerItem) {
        detach()
        let output = AVPlayerItemVideoOutput(pixelBufferAttributes: Self.pixelBufferAttributes)
        item.add(output)
        self.item = item
        videoOutput = output
        observations = [
            Observation.register(.frameOutputItemTimeJumped, on: item) { [weak self] in
                self?.processing.flush(removingDisplayedImage: false)
            }
        ]
        let link = CADisplayLink(
            target: DisplayLinkTarget(path: self), selector: #selector(DisplayLinkTarget.tick(_:)))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    /// Lets go of the current item: its output removed, the display link stopped, its observation
    /// gone, and the layer emptied, so no frame of it reaches a view that shows the next item —
    /// which is why ``SuperPlayer``'s reset for reuse (#404) calls it before anything else.
    func detach() {
        displayLink?.invalidate()
        displayLink = nil
        observations = []
        if let item, let videoOutput { item.remove(videoOutput) }
        item = nil
        videoOutput = nil
        processing.flush(removingDisplayedImage: true)
    }

    /// One display refresh: if the output has a frame newer than the last one for the time this
    /// refresh will be seen, hand it to the queue.
    ///
    /// The layer's control timebase *is* the item's (`AVPlayerItem.timebase`, read-only and shared),
    /// so a frame stamped with its item time is presented when the item's clock reaches it, pauses
    /// when the item pauses, and the facade never schedules a present (ADR-0022 rule 5). It is set
    /// here rather than in ``attach(to:)`` because an item has no timebase until it is the player's.
    ///
    /// ref: https://developer.apple.com/documentation/avfoundation/avplayeritemvideooutput/itemtime(forhosttime:)
    /// ref: https://developer.apple.com/documentation/quartzcore/cadisplaylink/targettimestamp
    fileprivate func tick(_ link: CADisplayLink) {
        guard let item, let videoOutput else { return }
        if let timebase = item.timebase, layer.controlTimebase !== timebase {
            layer.controlTimebase = timebase
        }
        let itemTime = videoOutput.itemTime(forHostTime: link.targetTimestamp)
        guard videoOutput.hasNewPixelBuffer(forItemTime: itemTime),
            let generation = processing.begin()
        else { return }
        var presentationTime = CMTime.invalid
        guard
            let frame = videoOutput.copyPixelBuffer(
                forItemTime: itemTime, itemTimeForDisplay: &presentationTime)
        else { return processing.end() }
        processing.process(
            frame, at: presentationTime.isNumeric ? presentationTime : itemTime,
            generation: generation)
    }

    /// ``SuperPlayer/frameOutputPixelFormat``, as `AVPlayerItemVideoOutput` takes it.
    private static let pixelBufferAttributes: [String: any Sendable] = [
        kCVPixelBufferPixelFormatTypeKey as String: SuperPlayer.frameOutputPixelFormat,
        kCVPixelBufferMetalCompatibilityKey as String: true,
        kCVPixelBufferIOSurfacePropertiesKey as String: [String: Int](),
    ]

}

/// The display link's target. `CADisplayLink` retains its target until invalidated, so the target
/// is this small object holding the path weakly, and the path invalidates the link in ``FramePath/detach()``.
@MainActor
private final class DisplayLinkTarget: NSObject {

    private weak var path: FramePath?

    init(path: FramePath) {
        self.path = path
    }

    /// Delivered on the main run loop, where ``FramePath/attach(to:)`` added the link. A path that
    /// has gone — its player dropped without ``SuperPlayer/release()`` — stops the link here, which
    /// is the one place that learns of it without a deinit of the path's own.
    @objc func tick(_ link: CADisplayLink) {
        guard let path else { return link.invalidate() }
        path.tick(link)
    }
}

/// What runs off the main actor: the consumer's ``FrameOutput``, the wrap into a `CMSampleBuffer`,
/// and the enqueue.
///
/// `@unchecked Sendable`, argued member by member: the output is `Sendable` by its protocol; the
/// renderer is not marked so, and Apple's header says enqueueing through it from a background thread
/// is what it is for ("sampleBufferRenderer allows the client to safely enqueue sample buffers from a
/// background thread", `AVSampleBufferDisplayLayer.h`); the cached format description is touched
/// only on ``queue``; and the bookkeeping is under its own lock.
private final class FrameProcessing: @unchecked Sendable {

    private let output: any FrameOutput
    private let renderer: AVSampleBufferVideoRenderer

    /// User-interactive, because what it does is on the way to the screen.
    private let queue = DispatchQueue(
        label: "com.superplayer.frame-output", qos: .userInteractive)

    /// Whether a frame is being processed, and how many flushes there have been — a frame copied
    /// before a flush and finished after it belongs to a timeline the layer no longer shows, and is
    /// dropped rather than enqueued.
    private let state = OSAllocatedUnfairLock(initialState: (busy: false, generation: 0))

    /// The last format description, reused while the buffers keep matching it. Only on ``queue``.
    private var format: CMVideoFormatDescription?

    init(output: any FrameOutput, renderer: AVSampleBufferVideoRenderer) {
        self.output = output
        self.renderer = renderer
    }

    /// Claims the queue for one frame, returning the generation it belongs to, or nil while the
    /// previous frame is still being processed — the frame is then not copied at all, and one that
    /// is superseded before the queue frees is dropped (``FrameOutput``'s doc comment).
    func begin() -> Int? {
        state.withLock { state in
            guard !state.busy else { return nil }
            state.busy = true
            return state.generation
        }
    }

    /// Gives the claim back without processing anything.
    func end() {
        state.withLock { $0.busy = false }
    }

    func process(_ frame: CVPixelBuffer, at time: CMTime, generation: Int) {
        // Handed over: the main actor copied the buffer and keeps no reference to it.
        nonisolated(unsafe) let frame = frame
        queue.async { [self] in
            let processed = output.process(frame, presentationTime: time)
            let current = state.withLock { state in
                state.busy = false
                return state.generation == generation
            }
            if current { enqueue(processed, at: time) }
        }
    }

    /// Empties the layer's queue — on a seek, keeping the frame on screen until the next arrives;
    /// on letting go of an item, removing it.
    func flush(removingDisplayedImage: Bool) {
        state.withLock { $0.generation += 1 }
        queue.async { [self] in
            renderer.flush(removingDisplayedImage: removingDisplayedImage, completionHandler: nil)
        }
    }

    /// Wraps `buffer` in a sample buffer stamped `time` and enqueues it — a reference to the same
    /// pixels, not a copy (ADR-0022 rule 5).
    ///
    /// ref: https://developer.apple.com/documentation/coremedia/cmsamplebuffercreatereadywithimagebuffer(allocator:imagebuffer:formatdescription:sampletiming:samplebufferout:)
    private func enqueue(_ buffer: CVPixelBuffer, at time: CMTime) {
        if format.map({ !CMVideoFormatDescriptionMatchesImageBuffer($0, imageBuffer: buffer) })
            ?? true
        {
            format = nil
            CMVideoFormatDescriptionCreateForImageBuffer(
                allocator: nil, imageBuffer: buffer, formatDescriptionOut: &format)
        }
        guard let format else { return }
        var timing = CMSampleTimingInfo(
            duration: .invalid, presentationTimeStamp: time, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(
            allocator: nil, imageBuffer: buffer, formatDescription: format,
            sampleTiming: &timing, sampleBufferOut: &sample)
        guard let sample else { return }
        // A renderer interrupted — the app backgrounded, the media services reset — refuses
        // everything until it is flushed, and says so; flushing then is the documented recovery.
        // ref: https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer/requiresflushtoresumedecoding
        if renderer.requiresFlushToResumeDecoding || renderer.status == .failed {
            renderer.flush()
        }
        renderer.enqueue(sample)
    }
}

/// Picture in Picture's playback questions, answered over the `AVPlayer` — the delegate ADR-0022
/// rule 5 says core implements, so a sample-buffer layer can be a content source as an
/// `AVPlayerLayer` is.
///
/// ref: https://developer.apple.com/documentation/avkit/avpictureinpicturesamplebufferplaybackdelegate
///
/// On the main actor, as the player it reads is; AVKit calls a playback delegate on the main
/// thread, and the `@preconcurrency` conformance checks that it did.
@MainActor
final class PictureInPicturePlayback: NSObject,
    @preconcurrency AVPictureInPictureSampleBufferPlaybackDelegate
{

    private let player: AVPlayer

    init(player: AVPlayer) {
        self.player = player
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController, setPlaying playing: Bool
    ) {
        if playing { player.play() } else { player.pause() }
    }

    /// The item's whole span, from zero to its duration; a live item's is unbounded, which is how
    /// Picture in Picture is told to show no scrubber. An item not yet ready answers invalid rather
    /// than unbounded, since its duration is indefinite until then whatever it turns out to be.
    func pictureInPictureControllerTimeRangeForPlayback(
        _ pictureInPictureController: AVPictureInPictureController
    ) -> CMTimeRange {
        guard let item = player.currentItem, item.status == .readyToPlay else { return .invalid }
        if item.duration.isIndefinite {
            return CMTimeRange(start: .negativeInfinity, duration: .positiveInfinity)
        }
        return CMTimeRange(start: .zero, duration: item.duration)
    }

    func pictureInPictureControllerIsPlaybackPaused(
        _ pictureInPictureController: AVPictureInPictureController
    ) -> Bool {
        player.rate == 0
    }

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        didTransitionToRenderSize newRenderSize: CMVideoDimensions
    ) {}

    func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        skipByInterval skipInterval: CMTime, completion completionHandler: @escaping () -> Void
    ) {
        // Unchecked, and safe: AVKit's handler is called once, from the seek's completion, and
        // nothing else holds it.
        nonisolated(unsafe) let completion = completionHandler
        player.seek(to: player.currentTime() + skipInterval) { _ in completion() }
    }
}
