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
// | `Format` from `RealtimeFormats`, init data from `CodecConfigurationRecords` | a `CMFormatDescription` from `RealtimeFormats`, the record passed through as its atom; in-band parameter sets converted by `TrackFraming` — mirrored (`CodecConfigurationRecords.swift`) |
// | the period's anchor, shared across tracks, and `SHARED_EPOCH_BOUND_US` | the same anchor and bound, in `anchor(for:among:)` |
// | `LATE_TRACK_START_BOUND_US` and `STALLED_TRACK_BOUND_US`, polled in `continueLoading` | the same bounds, checked as each frame is enqueued (`failIfATrackHasFallenBehind()`) |
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

    /// One declared track: how its frames are framed and described, and who renders them.
    ///
    /// The framing is per track and not per subscription, which is #345's NAL length size made
    /// plural, as on Android: two tracks may declare different widths, and reading one track's
    /// samples at the other's frames every one of them wrongly while looking like it worked.
    private struct TrackState {
        var framing: TrackFraming
        let renderer: any AVQueuedSampleBufferRendering
        /// What this track's timestamps are rebased by, fixed at its first frame
        /// (``anchor(for:among:)``). Nil until that frame.
        var offsetUs: Int64?
        /// The rebased presentation time of the first sample enqueued, and of the latest — the
        /// Kotlin queue's `largestQueuedTimestampUs` — in microseconds on the stream's timeline.
        var firstUs: Int64?
        var largestUs: Int64?

        /// How far this track has been written, on the stream's timeline: its start where it has
        /// written nothing, which is what "buffered nothing" means here, as on Android.
        var bufferedUs: Int64 { largestUs ?? 0 }

        /// Whether a sample of this track has reached its renderer — what the bounds read as
        /// "started", rather than ``offsetUs``, because a first frame carrying parameter sets alone
        /// or refused is anchored and enqueues nothing, and a track that has enqueued nothing is
        /// still connecting.
        var hasDelivered: Bool { largestUs != nil }
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

    /// Per declared track, the rebased presentation times of its first and its latest enqueued
    /// sample, in microseconds — nil for a track that has enqueued nothing — in declaration order.
    /// What each renderer was handed to render against the one clock, which is the reading of A/V
    /// sync a test has (`docs/testing.md`, *A realtime transport on iOS*).
    var enqueuedPresentations: [(firstUs: Int64, largestUs: Int64)?] {
        queue.sync {
            (tracks ?? []).map { state in
                guard let first = state.firstUs, let largest = state.largestUs else { return nil }
                return (first, largest)
            }
        }
    }

    /// The renderer of each declared track, in declaration order — an audio renderer's status and
    /// error being the one reading of a played audio track a test has.
    var trackRenderers: [any AVQueuedSampleBufferRendering] {
        queue.sync { (tracks ?? []).map(\.renderer) }
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
                let framing = try TrackFraming(track: track)
                states.append(TrackState(framing: framing, renderer: renderer(for: framing.kind)))
            }
        } catch {
            return fail(error)
        }
        for state in states { synchronizer.addRenderer(state.renderer) }
        tracks = states
        let hasVideo = states.contains { $0.framing.kind == .video }
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
        let offsetUs =
            states[frame.trackIndex].offsetUs ?? anchor(for: frame.timestampUs, among: states)
        states[frame.trackIndex].offsetUs = offsetUs
        let presentationUs = frame.timestampUs - offsetUs
        do {
            guard let payload = try states[frame.trackIndex].framing.sample(from: frame.payload),
                let description = states[frame.trackIndex].framing.description
            else {
                // Parameter sets and nothing to decode: the description moved, no sample did. No
                // bound is checked, since no track's written position moved either.
                tracks = states
                return
            }
            let renderer = states[frame.trackIndex].renderer
            let sample = try Self.sampleBuffer(
                payload, keyFrame: frame.keyFrame, description: description,
                presentationUs: presentationUs, codec: states[frame.trackIndex].framing.codec)
            // A video renderer that failed — a decoder lost while backgrounded, say — refuses
            // everything until it is flushed, and says so; flushing then is the documented recovery.
            // ref: https://developer.apple.com/documentation/avfoundation/avsamplebuffervideorenderer/requiresflushtoresumedecoding
            if let video = renderer as? AVSampleBufferVideoRenderer,
                video.requiresFlushToResumeDecoding
            {
                video.flush()
            }
            renderer.enqueue(sample)
        } catch {
            tracks = states
            return fail(error)
        }
        states[frame.trackIndex].firstUs = states[frame.trackIndex].firstUs ?? presentationUs
        states[frame.trackIndex].largestUs = max(
            states[frame.trackIndex].largestUs ?? presentationUs, presentationUs)
        tracks = states
        if !firstFrameEnqueued {
            firstFrameEnqueued = true
            firstPresentation = CMTime(value: presentationUs, timescale: Self.microsecondsPerSecond)
            deliver(.firstFrameEnqueued)
            applyRate()
        }
        failIfATrackHasFallenBehind()
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

    /// What a track's first frame is rebased by, which is the whole of the timebase rule — the
    /// Kotlin `anchorFor`, ported with its reasons.
    ///
    /// The first frame on **any** track anchors the stream, and every track that looks like it
    /// shares that epoch is rebased by the same amount — so a publisher whose audio genuinely leads
    /// its video by 40 ms sends two tracks on one clock and the 40 ms arrives as 40 ms. Rebasing
    /// each track on its own first frame would put both at zero and flatten the skew away, and a
    /// skew flattened to zero is lip sync silently lost.
    ///
    /// A track whose first frame lands further than ``sharedEpochBoundUs`` from the anchor is taken
    /// to be on an epoch of its own instead: obligation 4 promises monotonicity within a track and
    /// nothing across tracks, and an RTP stream's initial timestamp is random per SSRC (spec: RFC
    /// 3550 §5.1 — "the initial value of the timestamp SHOULD be random"). Such a track is anchored
    /// at the point the stream had reached when its first frame arrived — the furthest any track has
    /// been written — because with no common clock that is the only estimate of "now" either side
    /// has. It recovers playback and not lip sync. Honouring the difference instead would stamp one
    /// track's samples hours before the synchronizer's clock, where the renderer drops them as late,
    /// or hours after it, where they wait for ever.
    private func anchor(for timestampUs: Int64, among states: [TrackState]) -> Int64 {
        guard let anchorUs else {
            anchorUs = timestampUs
            return timestampUs
        }
        // No overflow to guard: a microsecond timestamp from any clock a transport can read is many
        // orders of magnitude inside `Int64`, which holds ~292,000 years of them.
        if abs(timestampUs - anchorUs) <= Self.sharedEpochBoundUs { return anchorUs }
        return timestampUs - (Self.leadingBufferedUs(states) ?? 0)
    }

    /// How far the furthest-ahead track has been written, or nil where no track has delivered —
    /// one reading for ``anchor(for:among:)``, as the point to place a track on another clock, and
    /// for ``failIfATrackHasFallenBehind()``, as the line every other track is measured against.
    private static func leadingBufferedUs(_ states: [TrackState]) -> Int64? {
        states.filter(\.hasDelivered).map(\.bufferedUs).max()
    }

    /// Ends the stream where one track has fallen far enough behind the others to have stopped
    /// playback, rather than letting it play on half silent or half frozen — the Kotlin
    /// `failIfATrackHasFallenBehind`.
    ///
    /// Two bounds rather than one, because the two silences are not the same thing: a track that
    /// has never delivered is still *connecting* and gets ``lateTrackStartBoundUs``; a track that
    /// was delivering and stopped gets the shorter ``stalledTrackBoundUs``.
    ///
    /// Both are **media time against the furthest-ahead track, never a clock** — so the same
    /// delivery reaches the same verdict on any host, which is what makes it assertable under
    /// ADR-0021's real time, and so *all* the tracks going quiet together is a non-event: the leader
    /// stops advancing too, nobody falls behind, and the player sits at the live edge. Checked as
    /// each frame is enqueued, the one moment the leader can move; Android polls it from
    /// `continueLoading` because that is where Media3 asks. A single-track subscription can reach
    /// neither bound, so #431's behaviour is unchanged by this whole rule.
    private func failIfATrackHasFallenBehind() {
        guard let states = tracks, !finished, states.count > 1,
            let leaderUs = Self.leadingBufferedUs(states)
        else { return }
        for state in states {
            let boundUs =
                state.hasDelivered ? Self.stalledTrackBoundUs : Self.lateTrackStartBoundUs
            let behindUs = leaderUs - state.bufferedUs
            if behindUs > boundUs {
                return fail(
                    RealtimeTrackStalledError(
                        codec: state.framing.codec, behindByMs: behindUs / 1_000,
                        boundMs: boundUs / 1_000))
            }
        }
    }

    /// How far apart two tracks' first frames may be and still be read as one epoch: thirty
    /// seconds, the Kotlin `SHARED_EPOCH_BOUND_US`, chosen to sit in the wide gap between the two
    /// populations it separates. Below it is every real start skew — a second track a GOP or a
    /// subscription round trip behind the first is seconds at worst, and anything past
    /// ``lateTrackStartBoundUs`` ends the stream anyway. Above it is every genuinely different
    /// origin — a random RTP start, a monotonic clock beside a wall clock — none of which lands
    /// inside half a minute except by coincidence.
    static let sharedEpochBoundUs: Int64 = 30_000_000

    /// How far the other tracks may advance while a declared track has delivered nothing: five
    /// seconds, the Kotlin `LATE_TRACK_START_BOUND_US`. A realtime transport negotiates per track —
    /// a subscription round trip, an encoder asked for a keyframe and the first GOP — a plausible
    /// few seconds on a slow link, more than the two-second keyframe interval a live encoder
    /// typically runs. Longer than ``stalledTrackBoundUs`` because connecting is slower than
    /// continuing.
    static let lateTrackStartBoundUs: Int64 = 5_000_000

    /// How far the other tracks may advance while a track that *was* delivering stays quiet: three
    /// seconds, the Kotlin `STALLED_TRACK_BOUND_US`. A publisher sends its tracks together, so a gap
    /// this size is not jitter at any buffer a sub-second-latency stream runs; it is a lost
    /// subscription rather than a slow one.
    static let stalledTrackBoundUs: Int64 = 3_000_000

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

    /// The renderer a track of `kind` is enqueued to: the display layer's for video, a fresh audio
    /// renderer for audio.
    private func renderer(for kind: RealtimeFormat.MediaKind) -> any AVQueuedSampleBufferRendering {
        kind == .video ? videoRenderer : AVSampleBufferAudioRenderer()
    }

    /// `payload` as a sample buffer: copied into a block buffer, stamped at `presentationUs`
    /// microseconds, and for video marked as not a sync sample unless it is a keyframe.
    ///
    /// spec: CoreMedia's `kCMSampleAttachmentKey_NotSync` — a sample without it is taken to be a
    /// sync sample, so a dependent frame must say it is one.
    ///
    /// Compressed audio is described by a **packet description** rather than a sample size: an
    /// audio sample buffer counts packets, and one encoded audio frame is one packet of a variable
    /// size, which is how CoreMedia is told where it ends.
    /// ref: https://developer.apple.com/documentation/coremedia/cmaudiosamplebuffercreatereadywithpacketdescriptions(allocator:databuffer:formatdescription:samplecount:presentationtimestamp:packetdescriptions:samplebufferout:)
    static func sampleBuffer(
        _ payload: Data, keyFrame: Bool, description: CMFormatDescription, presentationUs: Int64,
        codec: String
    ) throws -> CMSampleBuffer {
        let blockBuffer = try CMBlockBuffer(length: payload.count)
        try payload.withUnsafeBytes { bytes in
            try blockBuffer.replaceDataBytes(with: bytes)
        }
        let presentation = CMTime(value: presentationUs, timescale: microsecondsPerSecond)
        if description.mediaType == .audio {
            var packet = AudioStreamPacketDescription(
                mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(payload.count))
            var sample: CMSampleBuffer?
            let status = CMAudioSampleBufferCreateReadyWithPacketDescriptions(
                allocator: kCFAllocatorDefault, dataBuffer: blockBuffer,
                formatDescription: description, sampleCount: 1,
                presentationTimeStamp: presentation, packetDescriptions: &packet,
                sampleBufferOut: &sample)
            guard status == noErr, let sample else {
                throw RealtimeDescriptionError(
                    codec: codec, status: status)
            }
            return sample
        }
        let timing = CMSampleTimingInfo(
            duration: .invalid, presentationTimeStamp: presentation, decodeTimeStamp: .invalid)
        let sample = try CMSampleBuffer(
            dataBuffer: blockBuffer, formatDescription: description, numSamples: 1,
            sampleTimings: [timing], sampleSizes: [payload.count])
        if !keyFrame { sample.sampleAttachments[0][.notSync] = true }
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
