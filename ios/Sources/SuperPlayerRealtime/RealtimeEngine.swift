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
import CoreMedia
import SuperPlayerCore

// The engine under `RealtimePlayer` — the Kotlin `RealtimeMediaSource` and `RealtimeMediaPeriod`,
// over the platform's own sample-buffer renderers rather than Media3's sample queues
// (`docs/ios-plan.md` §3.10, ADR-0020 rule 11).
//
// | Android (`RealtimeMediaPeriod.kt`) | here |
// | --- | --- |
// | a `SampleQueue` per track, read by Media3's renderers | the display layer's `AVSampleBufferVideoRenderer`, or an `AVSampleBufferAudioRenderer`, per track |
// | a `SinglePeriodTimeline`, live, unseekable, of unknown duration | an `AVSampleBufferRenderSynchronizer` whose clock starts at the first frame and is never sought |
// | `Format` from `RealtimeFormats`, init data from `CodecConfigurationRecords` | a `CMFormatDescription` from `RealtimeFormats`, the record passed through as its atom |
// | the first frame's timestamp subtracted | the same, before the frame is stamped as a `CMSampleBuffer` |
//
// **Nothing here decodes** (ADR-0018 rule 1): a frame is wrapped, not transformed — its payload
// becomes a block buffer and its timestamp a presentation time — and the renderers decode it.

/// What the engine tells its player, on the main queue and in the order it happened.
enum RealtimeEngineEvent: Sendable {
    /// The tracks were declared and every one was described; `hasVideo` says whether a picture is
    /// coming, which is what the idle timer is held for.
    case tracksDeclared(hasVideo: Bool)
    /// The first frame was enqueued: the clock may start.
    case firstFrameEnqueued
    /// The transport said the stream ended.
    case ended
    /// The stream failed, and no further frame is taken.
    case failed(any Error)
}

/// One subscription's frames, from the transport's thread to the renderers.
///
/// `@unchecked Sendable`, argued member by member: every `var` is read and written on ``queue``
/// alone, and the player reads them through ``queue`` too (``isClockRunning``); the renderers are
/// not marked `Sendable`, and Apple's header says enqueueing through `sampleBufferRenderer` from a
/// background thread is what it is for ("allows the client to safely enqueue sample buffers from a
/// background thread"), as `FramePath.swift`'s processing argues — its `status` is read on the main
/// actor, a read of a renderer property the same header puts no thread on; the synchronizer's rate
/// is set on ``queue`` alone, and its clock read from anywhere, which `currentTime()` — a timebase
/// read — is.
final class RealtimeEngine: @unchecked Sendable {

    /// The layer a view hosts. Made on the main actor with the player, before any track exists, so
    /// a view can be laid out before the stream connects.
    let displayLayer: AVSampleBufferDisplayLayer

    /// The display layer's renderer, read once on the main actor where the layer was made, so the
    /// queue never reads a `CALayer` property.
    let videoRenderer: AVSampleBufferVideoRenderer

    /// The clock every renderer of the stream is driven by.
    let synchronizer = AVSampleBufferRenderSynchronizer()

    private let queue = DispatchQueue(label: "com.superplayer.realtime.engine")
    private let report: @Sendable (_ subscription: UInt64, RealtimeEngineEvent) -> Void

    // Confined to `queue`.

    /// Which subscription the engine is serving. A sink and an event carry the number they were
    /// made under, and work for any other is dropped: a callback already on the queue when its
    /// subscription was cancelled belongs to a stream nobody is showing any more.
    private var subscription: UInt64 = 0
    private var tracks: [TrackState]?
    private var anchorUs: Int64?
    private var finished = false
    private var wantsToPlay = false
    private var clockStarted = false
    private var firstFrameEnqueued = false
    private var firstPresentation: CMTime?

    /// One declared track: what its frames are described as and who renders them.
    private struct TrackState {
        let description: CMFormatDescription
        let renderer: any AVQueuedSampleBufferRendering
        /// What this track's timestamps are rebased by, fixed at its first frame (``anchor(for:)``).
        var offsetUs: Int64?
    }

    /// `report` is called on the main queue, in the order the events happened, with the number of
    /// the subscription each belongs to.
    @MainActor
    init(report: @escaping @Sendable (_ subscription: UInt64, RealtimeEngineEvent) -> Void) {
        self.displayLayer = AVSampleBufferDisplayLayer()
        self.videoRenderer = displayLayer.sampleBufferRenderer
        self.report = report
    }

    /// Ends whatever subscription was being served and returns the sink for the next, with its
    /// number: every call on it hops onto ``queue`` in the order it was made, which obligation 1's
    /// happens-before makes the order the transport delivered in. A call made after the next
    /// ``beginSubscription()`` or ``stop()`` is dropped there, so a transport that breaks obligation
    /// 9 costs nothing here.
    func beginSubscription() -> (sink: any FrameSink, number: UInt64) {
        queue.sync {
            reset()
            finished = false
            return (Sink(engine: self, subscription: subscription), subscription)
        }
    }

    /// Whether the clock is running for a stream that has not stopped — read on ``queue``, where the
    /// rate is set, rather than off the synchronizer from another thread.
    var isClockRunning: Bool {
        queue.sync { clockStarted && wantsToPlay && !finished }
    }

    /// The presentation time the current subscription's first frame was stamped with, or nil before
    /// one: zero whatever the transport's epoch, which is the subtraction made visible.
    var firstPresentationTime: CMTime? {
        queue.sync { firstPresentation }
    }

    /// Starts or stops the clock as the player is asked to play or pause.
    func setWantsToPlay(_ wants: Bool) {
        queue.async { [self] in
            wantsToPlay = wants
            applyRate()
        }
    }

    /// Stops taking frames and lets the renderers go, for good. Synchronous, so the player's release
    /// is finished when it returns.
    func stop() {
        queue.sync { reset() }
    }

    /// Lets the current subscription go: its frames off the renderers, the renderers off the
    /// clock, and the next subscription numbered apart from it.
    private func reset() {
        subscription += 1
        finished = true
        synchronizer.rate = 0
        for track in tracks ?? [] {
            track.renderer.flush()
            synchronizer.removeRenderer(track.renderer, at: .invalid, completionHandler: nil)
        }
        tracks = nil
        anchorUs = nil
        clockStarted = false
        firstFrameEnqueued = false
        firstPresentation = nil
    }

    // MARK: - On the queue

    fileprivate func onTracks(_ declared: [RealtimeTrack]) {
        guard !finished else { return }
        guard tracks == nil else {
            return fail(
                RealtimeDeliveryError(
                    "A realtime subscription declared its tracks a second time; FrameSink.onTracks "
                        + "is called once, before the first frame (FrameSource obligation 2)."))
        }
        guard !declared.isEmpty else {
            // Refused where it is stated rather than waited out: an empty declaration would leave
            // the player waiting for ever on a stream with nothing in it.
            return fail(
                RealtimeDeliveryError(
                    "A realtime subscription declared no tracks, so there is nothing to play."))
        }
        var states: [TrackState] = []
        do {
            for track in declared {
                // One track refused refuses the whole subscription, for the Kotlin reason: a player
                // told only about the tracks that did map would be playing content the publisher
                // did not send.
                let description = try RealtimeFormats.formatDescription(for: track)
                states.append(
                    TrackState(description: description, renderer: renderer(for: description)))
            }
        } catch {
            return fail(error)
        }
        for state in states { synchronizer.addRenderer(state.renderer) }
        tracks = states
        let hasVideo = states.contains { $0.description.mediaType == .video }
        deliver(.tracksDeclared(hasVideo: hasVideo))
    }

    fileprivate func onFrame(_ frame: EncodedFrame) {
        guard !finished else { return }
        guard var states = tracks else {
            return fail(
                RealtimeDeliveryError(
                    "A realtime frame arrived before FrameSink.onTracks declared any track "
                        + "(FrameSource obligation 2)."))
        }
        guard states.indices.contains(frame.trackIndex) else {
            // Reported rather than dropped, and untyped, for the Kotlin reason: a whole track
            // quietly going nowhere is the silent nothing the obligations exist to prevent, and this
            // is a transport bug whose only reader is the transport's author.
            return fail(
                RealtimeDeliveryError(
                    "A realtime frame named track index \(frame.trackIndex), but the subscription "
                        + "declared \(states.count) track(s). A frame's trackIndex is a position in "
                        + "the list handed to FrameSink.onTracks."))
        }
        let offsetUs = states[frame.trackIndex].offsetUs ?? anchor(for: frame.timestampUs)
        states[frame.trackIndex].offsetUs = offsetUs
        tracks = states
        let state = states[frame.trackIndex]
        do {
            let sample = try Self.sampleBuffer(
                frame, description: state.description, presentationUs: frame.timestampUs - offsetUs)
            // A video renderer that failed — a decoder lost while backgrounded, say — refuses
            // everything until it is flushed, and says so; flushing then is the documented recovery.
            // ref: https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer/requiresflushtoresumedecoding
            if let video = state.renderer as? AVSampleBufferVideoRenderer,
                video.requiresFlushToResumeDecoding
            {
                video.flush()
            }
            state.renderer.enqueue(sample)
        } catch {
            return fail(error)
        }
        if !firstFrameEnqueued {
            firstFrameEnqueued = true
            firstPresentation = CMTime(
                value: frame.timestampUs - offsetUs, timescale: Self.microsecondsPerSecond)
            deliver(.firstFrameEnqueued)
            applyRate()
        }
    }

    fileprivate func onEnded() {
        guard !finished else { return }
        finished = true
        deliver(.ended)
    }

    fileprivate func onError(_ error: any Error) {
        guard !finished else { return }
        fail(error)
    }

    /// What a track's first frame is rebased by — the first frame delivered on **any** track, which
    /// anchors the stream (the Kotlin `anchorFor`'s shared epoch). The reconciliation of a track
    /// plainly on another epoch, and the bounds on a track that starts late or stops, are #432's,
    /// and land here.
    private func anchor(for timestampUs: Int64) -> Int64 {
        if let anchorUs { return anchorUs }
        anchorUs = timestampUs
        return timestampUs
    }

    /// Starts the clock at the stream's zero the first time there is both a frame and a wish to
    /// play, and otherwise follows the wish. Never sought: a realtime timeline has no past.
    private func applyRate() {
        guard !finished, firstFrameEnqueued else { return }
        if !wantsToPlay {
            synchronizer.rate = 0
        } else if !clockStarted {
            clockStarted = true
            synchronizer.setRate(1, time: .zero)
        } else {
            synchronizer.rate = 1
        }
    }

    private func fail(_ error: any Error) {
        finished = true
        synchronizer.rate = 0
        deliver(.failed(error))
    }

    private func deliver(_ event: RealtimeEngineEvent) {
        // The main queue rather than a `Task`: a queue keeps the order the events happened in, which
        // separately scheduled tasks do not promise.
        let report = self.report
        let subscription = self.subscription
        DispatchQueue.main.async { report(subscription, event) }
    }

    /// The renderer a track of `description` is enqueued to: the display layer's for video, a fresh
    /// audio renderer for audio.
    private func renderer(for description: CMFormatDescription) -> any AVQueuedSampleBufferRendering
    {
        description.mediaType == .video ? videoRenderer : AVSampleBufferAudioRenderer()
    }

    /// `frame` as a sample buffer: its payload copied into a block buffer, stamped at
    /// `presentationUs` microseconds, and marked as not a sync sample unless it is a keyframe.
    ///
    /// spec: CoreMedia's `kCMSampleAttachmentKey_NotSync` — a sample without it is taken to be a
    /// sync sample, so a dependent frame must say it is one.
    static func sampleBuffer(
        _ frame: EncodedFrame, description: CMFormatDescription, presentationUs: Int64
    ) throws -> CMSampleBuffer {
        let blockBuffer = try CMBlockBuffer(length: frame.payload.count)
        try frame.payload.withUnsafeBytes { bytes in
            try blockBuffer.replaceDataBytes(with: bytes)
        }
        let timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: CMTime(value: presentationUs, timescale: microsecondsPerSecond),
            decodeTimeStamp: .invalid)
        let sample = try CMSampleBuffer(
            dataBuffer: blockBuffer, formatDescription: description, numSamples: 1,
            sampleTimings: [timing], sampleSizes: [frame.payload.count])
        if !frame.keyFrame { sample.sampleAttachments[0][.notSync] = true }
        return sample
    }

    /// Microseconds, the seam's unit (``EncodedFrame/timestampUs``), as a `CMTime` timescale.
    static let microsecondsPerSecond: CMTimeScale = 1_000_000
}

/// The ``FrameSink`` a source is subscribed with — the transport's half, which only hops.
private struct Sink: FrameSink {
    let engine: RealtimeEngine
    let subscription: UInt64

    func onTracks(_ tracks: [RealtimeTrack]) { run { $0.onTracks(tracks) } }
    func onFrame(_ frame: EncodedFrame) { run { $0.onFrame(frame) } }
    func onEnded() { run { $0.onEnded() } }
    func onError(_ error: any Error) { run { $0.onError(error) } }

    private func run(_ work: @escaping @Sendable (RealtimeEngine) -> Void) {
        engine.enqueueWork(for: subscription, work)
    }
}

extension RealtimeEngine {
    /// Runs `work` on ``queue``, after everything enqueued before it, if `number` is still the
    /// subscription being served.
    fileprivate func enqueueWork(
        for number: UInt64, _ work: @escaping @Sendable (RealtimeEngine) -> Void
    ) {
        queue.async { [self] in
            if number == subscription { work(self) }
        }
    }
}

/// A transport broke the seam's own contract — a frame before the declaration, a track index
/// nobody declared, a declaration with nothing in it. Untyped on purpose, as the Kotlin
/// `IOException`s are: a consumer has nothing to branch on, and the message names the numbers the
/// transport's author needs.
struct RealtimeDeliveryError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}
