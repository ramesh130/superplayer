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

import Foundation
import SuperPlayerCore

/// A publisher of two tracks, correct by default and wrong in exactly one way per ``Defect`` — the
/// Kotlin `PublishingFrameSource`, for its reason: one type taking a defect rather than one type per
/// defect, so the correct delivery is written once and each wrong one differs from it by the single
/// thing its check is supposed to catch.
///
/// With no defect it publishes an H.264 video track beside an AAC audio track, interleaved by each
/// frame's offset from its own track's epoch, on transport timestamps that are not zero. The video is
/// `avc1` with a configuration record and length-prefixed samples by default, and `avc3` with Annex-B
/// samples when `selfDescribing`, because obligation 7 has two branches. Nothing here decodes, so the
/// units are the right shape and carry no picture.
///
/// Delivery is synchronous inside ``subscribe(_:)`` unless `asynchronous`: a publisher finished by
/// the time `subscribe` returns satisfies obligation 9 by having nothing left to stop, so the
/// positive side of cancellation needs one that delivers from a thread of its own and joins it in
/// ``cancel()``.
///
/// `@unchecked Sendable`: `cancelled` and `stopped` are semaphores, `isCancelled` is read and written
/// under `cancelLock`, and `allocated` and the raw buffers obligation 8's two defects write through
/// are touched only by the one thread delivering — the defects that use them deliver on one — and
/// freed in `deinit`, after every reference to this source is gone.
final class PublishingFrameSource: FrameSource, @unchecked Sendable {

    /// The one thing this publisher does wrong. One per check, which the register test holds to
    /// ``FrameSourceConformance/Check``.
    enum Defect: CaseIterable, Sendable {
        /// Obligation 1: two threads deliver into the sink at once, with no happens-before.
        case deliversFromTwoThreadsAtOnce
        /// Obligation 2: frames arrive before the declaration that would give them a track.
        case deliversAFrameBeforeDeclaringItsTracks
        /// Obligation 2: a frame names a position past the end of the declared list.
        case sendsAFrameOnAnUndeclaredTrack
        /// Obligation 3: the first frame of a track is a dependent one, as joining mid-GOP gives.
        case startsATrackWithoutAKeyframe
        /// Obligation 4: a track's timestamps step backwards, as an unhandled RTP wrap does.
        case stepsOneTracksTimestampsBackwards
        /// The codec string: a track declared under a spelling nothing maps.
        case declaresAnUnmappedCodec
        /// Obligation 7, first half: a record handed over for a self-describing fourcc.
        case handsARecordForASelfDescribingFourcc
        /// Obligation 7, second half: #340's own defect, Annex-B samples under a record.
        case framesSamplesAsAnnexBUnderARecord
        /// Obligation 8: one pooled buffer, refilled for every frame and handed over by
        /// `bytesNoCopy` — the only way left to share memory across frames when `Data` is a value.
        case handsTheSameBufferOverTwice
        /// Obligation 8's other half: memory of its own per frame, written into once it is gone.
        case writesIntoAPayloadItHasHandedOver
        /// The finality of a terminal callback: a frame after the subscription has ended.
        case deliversAFrameAfterEnding
        /// Obligation 9: `cancel` returns while the delivery goes on.
        case keepsDeliveringAfterCancel
    }

    private let defect: Defect?
    private let selfDescribing: Bool
    private let asynchronous: Bool

    /// Signalled by ``cancel()``; what a defective shutdown then ignores.
    private let cancelled = DispatchSemaphore(value: 0)
    private let cancelLock = NSLock()
    private var isCancelled = false

    /// Signalled by an asynchronous delivery once it has stopped, which is what ``cancel()`` joins.
    private let stopped = DispatchSemaphore(value: 0)

    /// One buffer for the whole session, which is obligation 8's ordinary way of going wrong.
    private let pooled = UnsafeMutableRawPointer.allocate(
        byteCount: Fixture.pooledBufferBytes, alignment: 1)

    /// Every buffer handed over by `bytesNoCopy`, freed when this source is.
    private var allocated: [UnsafeMutableRawPointer] = []

    init(defect: Defect? = nil, selfDescribing: Bool = false, asynchronous: Bool = false) {
        self.defect = defect
        self.selfDescribing = selfDescribing
        self.asynchronous = asynchronous
    }

    deinit {
        pooled.deallocate()
        for memory in allocated { memory.deallocate() }
    }

    func subscribe(_ sink: any FrameSink) {
        if defect == .deliversAFrameBeforeDeclaringItsTracks {
            sink.onFrame(videoFrame(0))
        }
        sink.onTracks(tracks())
        switch true {
        case defect == .deliversFromTwoThreadsAtOnce: deliverFromTwoThreads(sink)
        case defect == .keepsDeliveringAfterCancel: deliverPastCancellation(sink)
        case asynchronous: deliverOnItsOwnThread(sink)
        default: deliverScript(sink)
        }
    }

    func cancel() {
        cancelLock.withLock { isCancelled = true }
        cancelled.signal()
        // What obligation 9 asks for, and the only branch here with anything to do: return once no
        // further callback can be made. The defective one deliberately does not wait.
        if asynchronous { stopped.wait() }
    }

    private var wasCancelled: Bool { cancelLock.withLock { isCancelled } }

    /// The whole script, in order, on whatever thread is delivering.
    private func deliverScript(_ sink: any FrameSink) {
        for scripted in script() {
            // Handed over at the moment of delivery, so a pooled buffer holds this frame's bytes
            // while it is delivered and the next frame's afterwards — the refill that tears it.
            let (frame, memory) = handedOver(scripted)
            sink.onFrame(frame)
            if defect == .writesIntoAPayloadItHasHandedOver, let memory {
                // Memory handed over and then reused for the next frame's bytes, which is what an
                // encoder loop that fills before it recycles does.
                memory.initializeMemory(
                    as: UInt8.self, repeating: Fixture.overwrittenByte, count: frame.payload.count)
            }
        }
        if defect == .deliversAFrameAfterEnding {
            sink.onEnded()
            sink.onFrame(videoFrame(Fixture.framesPerTrack))
        }
    }

    /// The script from a thread of its own, and then more video for as long as nobody cancels —
    /// what a transport reading from a socket does. Endless, because a thread that had already run
    /// out by the time the suite cancels it would satisfy obligation 9 by arithmetic again: this
    /// one is still delivering when ``cancel()`` joins it.
    private func deliverOnItsOwnThread(_ sink: any FrameSink) {
        let thread = Thread { [self] in
            defer { stopped.signal() }
            for frame in script() {
                if wasCancelled { return }
                sink.onFrame(frame)
            }
            var ordinal = Fixture.framesPerTrack
            while !wasCancelled {
                sink.onFrame(videoFrame(ordinal))
                ordinal += 1
                // A frame interval, so the stream runs at its own rate rather than as fast as the
                // sink takes it.
                Thread.sleep(forTimeInterval: Double(Fixture.videoFrameDurationUs) / 1_000_000)
            }
        }
        thread.name = "PublishingFrameSource"
        thread.start()
    }

    /// The declaration, which every check reads and three defects bend.
    private func tracks() -> [RealtimeTrack] {
        let video: RealtimeTrack
        switch defect {
        case .declaresAnUnmappedCodec:
            // A real codec and a real string, and one nothing here maps: refusing a plausible one
            // proves more than refusing a nonsense token.
            video = RealtimeTrack(codec: "theora")
        case .handsARecordForASelfDescribingFourcc:
            video = RealtimeTrack(
                codec: Fixture.selfDescribingH264, codecConfiguration: .record(Fixture.avcC))
        default:
            video =
                selfDescribing
                ? RealtimeTrack(codec: Fixture.selfDescribingH264)
                : RealtimeTrack(
                    codec: Fixture.recordedH264, codecConfiguration: .record(Fixture.avcC))
        }
        return [video, RealtimeTrack(codec: Fixture.aac)]
    }

    /// Both tracks' frames, interleaved by their offset from their own epochs — ordered by the
    /// ordinal offset rather than the timestamp each carries, so the one defect that bends a
    /// timestamp bends the timeline and not the order.
    private func script() -> [EncodedFrame] {
        let video = (0..<Fixture.framesPerTrack).map {
            (Int64($0) * Fixture.videoFrameDurationUs, videoFrame($0))
        }
        let audio = (0..<Fixture.framesPerTrack).map {
            (Int64($0) * Fixture.audioFrameDurationUs, audioFrame($0))
        }
        // A stable sort, so a tie keeps the video frame first, as declaration order does.
        return (video + audio).enumerated()
            .sorted { ($0.element.0, $0.offset) < ($1.element.0, $1.offset) }
            .map(\.element.1)
    }

    /// The payload a frame is handed over with: as it is, through one pooled buffer, or through
    /// memory of its own — the last two by `bytesNoCopy`, so obligation 8's defects are reachable —
    /// with the memory it was made over, where there is one.
    private func handedOver(_ frame: EncodedFrame) -> (EncodedFrame, UnsafeMutableRawPointer?) {
        let memory: UnsafeMutableRawPointer
        switch defect {
        case .handsTheSameBufferOverTwice:
            memory = pooled
            memory.initializeMemory(as: UInt8.self, repeating: 0, count: Fixture.pooledBufferBytes)
        case .writesIntoAPayloadItHasHandedOver:
            memory = UnsafeMutableRawPointer.allocate(byteCount: frame.payload.count, alignment: 1)
            allocated.append(memory)
        default:
            return (frame, nil)
        }
        frame.payload.copyBytes(
            to: memory.assumingMemoryBound(to: UInt8.self), count: frame.payload.count)
        let payload = Data(bytesNoCopy: memory, count: frame.payload.count, deallocator: .none)
        return (
            EncodedFrame(
                timestampUs: frame.timestampUs, payload: payload, keyFrame: frame.keyFrame,
                trackIndex: frame.trackIndex), memory
        )
    }

    private func videoFrame(_ ordinal: Int) -> EncodedFrame {
        // A transport that joined mid-GOP and forwarded what it found rather than waiting.
        let keyFrame = defect == .startsATrackWithoutAKeyframe ? false : ordinal == 0
        let annexB =
            selfDescribing || defect == .framesSamplesAsAnnexBUnderARecord
            || defect == .handsARecordForASelfDescribingFourcc
        // A 32-bit RTP timestamp that wrapped and was forwarded as it stood.
        let timestampUs =
            defect == .stepsOneTracksTimestampsBackwards && ordinal == Fixture.backwardsStepAt
            ? Fixture.videoEpochUs - Fixture.videoFrameDurationUs
            : Fixture.videoEpochUs + Int64(ordinal) * Fixture.videoFrameDurationUs
        let trackIndex =
            defect == .sendsAFrameOnAnUndeclaredTrack && ordinal != 0 ? Fixture.undeclaredTrack : 0
        return EncodedFrame(
            timestampUs: timestampUs,
            payload: annexB ? Fixture.annexBUnit(keyFrame) : Fixture.lengthPrefixedUnit(keyFrame),
            keyFrame: keyFrame, trackIndex: trackIndex)
    }

    /// One AAC access unit: bytes of the right size and no meaning, since nothing here decodes.
    private func audioFrame(_ ordinal: Int) -> EncodedFrame {
        EncodedFrame(
            timestampUs: Fixture.audioEpochUs + Int64(ordinal) * Fixture.audioFrameDurationUs,
            payload: Data(
                (0..<Fixture.audioFrameBytes).map { UInt8(truncatingIfNeeded: ordinal + $0) }),
            keyFrame: true, trackIndex: 1)
    }

    /// Two threads delivering with nothing ordering them — not "two threads" but "two at once".
    private func deliverFromTwoThreads(_ sink: any FrameSink) {
        let start = DispatchSemaphore(value: 0)
        let done = DispatchGroup()
        for half in 0..<2 {
            done.enter()
            let thread = Thread { [self] in
                start.wait()
                for ordinal in 0..<Fixture.framesPerTrack { sink.onFrame(videoFrame(ordinal)) }
                done.leave()
            }
            thread.name = "PublishingFrameSource-\(half)"
            thread.start()
        }
        start.signal()
        start.signal()
        done.wait()
    }

    /// A shutdown handed to another thread: ``cancel()`` returns as soon as it has asked, and the
    /// delivery goes on afterwards.
    private func deliverPastCancellation(_ sink: any FrameSink) {
        deliverScript(sink)
        cancelled.wait()
        for ordinal in 0..<Fixture.framesPerTrack {
            sink.onFrame(videoFrame(Fixture.framesPerTrack + ordinal))
        }
    }
}

/// The publisher's constants, the Kotlin fixture's values and reasons.
private enum Fixture {

    /// `avc1`: parameter sets out of band in a record, samples length-prefixed.
    /// // spec: ISO/IEC 14496-15 §5.3.3.1.
    static let recordedH264 = "avc1.42C01E"

    /// `avc3`: the self-describing fourcc, parameter sets in band and Annex-B samples.
    static let selfDescribingH264 = "avc3.42C01E"

    /// AAC-LC: MP4RA object type `0x40` and audio object type 2, which is what a publisher sends.
    static let aac = "mp4a.40.2"

    /// 30 fps.
    static let videoFrameDurationUs: Int64 = 33_333

    /// One AAC-LC access unit, 1024 samples at 44.1 kHz — deliberately not the video step, so the
    /// tracks interleave rather than landing on the same instants.
    /// // spec: ISO/IEC 14496-3 §4.5.2.1.
    static let audioFrameDurationUs: Int64 = 23_220

    /// Arbitrary and non-zero, which is the whole point of it: the epoch is the transport's.
    static let videoEpochUs: Int64 = 987_654_321

    /// The audio's own epoch, 40 ms ahead: the seam promises monotonicity per track and nothing
    /// across two, so a real skew is what a correct publisher looks like.
    static let audioEpochUs: Int64 = videoEpochUs + 40_000

    /// Enough that every check sees several frames of each track and none waits for more.
    static let framesPerTrack = 24

    /// Past the first frame, so the step is a step and not an epoch.
    static let backwardsStepAt = 4

    /// A position past the end of a two-track declaration.
    static let undeclaredTrack = 7

    /// More than a `Data` stores inline (fourteen bytes on a 64-bit platform), so the audio is
    /// handed over by reference where obligation 8's defects use `bytesNoCopy`.
    static let audioFrameBytes = 96

    /// What a reused buffer's second fill leaves behind, and nothing a frame here ever carries.
    static let overwrittenByte: UInt8 = 0x7F

    /// Wide enough to hold either video framing or an audio frame, so the pooled defect changes
    /// nothing else.
    static let pooledBufferBytes = 128

    /// An `AVCDecoderConfigurationRecord` for `avc1.42C01E`: version 1, the profile, compatibility
    /// and level, `ff` for four-byte lengths, one four-byte SPS and one four-byte PPS. Synthesized
    /// and labelled so, as the Kotlin one is: nothing here parses the parameter sets.
    /// // spec: ISO/IEC 14496-15 §5.3.3.1.2.
    static let avcC = Data([
        0x01, 0x42, 0xC0, 0x1E, 0xFF,
        0xE1, 0x00, 0x04, 0x67, 0x42, 0xC0, 0x1E,
        0x01, 0x00, 0x04, 0x68, 0xCE, 0x3C, 0x80,
    ])

    /// One NAL unit of the right shape and no picture data.
    /// // spec: ITU-T H.264 §7.3.1 — `nal_unit_type` is the low five bits of the first byte: 5 an IDR
    /// slice, 1 a non-IDR one.
    static func h264Unit(_ keyFrame: Bool) -> [UInt8] {
        [keyFrame ? 0x65 : 0x41] + [UInt8](repeating: 0, count: 64)
    }

    /// One sample of a self-describing track: the unit behind an Annex-B start code.
    static func annexBUnit(_ keyFrame: Bool) -> Data { Data([0, 0, 0, 1] + h264Unit(keyFrame)) }

    /// The same unit behind the four-byte length field ``avcC`` declares.
    static func lengthPrefixedUnit(_ keyFrame: Bool) -> Data {
        let unit = h264Unit(keyFrame)
        return Data([0, 0, 0, UInt8(unit.count)] + unit)
    }
}
