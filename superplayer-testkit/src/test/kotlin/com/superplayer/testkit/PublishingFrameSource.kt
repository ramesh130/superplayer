/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.testkit

import com.superplayer.core.EncodedFrame
import com.superplayer.core.FrameSink
import com.superplayer.core.FrameSource
import com.superplayer.core.RealtimeTrack
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * A publisher of two tracks, correct by default and wrong in exactly one way per [Defect].
 *
 * `PlatformClientTransport`'s shape, for its reason: one class taking a defect rather than one class
 * per defect, so the *correct* delivery is written once and each wrong one differs from it by the
 * single thing its check is supposed to catch. A stub per defect would let a check pass because two
 * things were different.
 *
 * What it publishes with no defect is the ordinary case both transports of this phase carry: an
 * H.264 video track beside an AAC audio track, interleaved by each frame's offset from its own
 * track's epoch, on transport timestamps that are not zero — a fake starting at zero would hide a
 * missing subtraction on the other side of the seam, and one that sent a whole track before starting
 * the next would not be a publisher at all.
 *
 * The video track is `avc1` with a configuration record and length-prefixed samples by default, and
 * `avc3` with in-band parameter sets and Annex-B samples when [selfDescribing] is set, because
 * obligation 7 has two branches and a suite scored against one of them is a suite that proves half
 * of it.
 */
internal class PublishingFrameSource(
    private val defect: Defect? = null,
    private val selfDescribing: Boolean = false,
) : FrameSource {

    /**
     * The one thing this publisher does wrong, or nothing at all.
     *
     * One per check on [FrameSourceConformance], which
     * `everyCheckHasAWrongPublisherAndEveryWrongPublisherHasACheck` holds to that class by
     * reflection.
     */
    enum class Defect {
        /** Obligation 1: two threads deliver into the sink at once, with no happens-before. */
        DELIVERS_FROM_TWO_THREADS_AT_ONCE,

        /** Obligation 2: frames arrive before the declaration that would give them a track. */
        DELIVERS_A_FRAME_BEFORE_DECLARING_ITS_TRACKS,

        /** Obligation 2: a frame names a position past the end of the declared list. */
        SENDS_A_FRAME_ON_AN_UNDECLARED_TRACK,

        /** Obligation 3: the first frame of a track is a dependent one, as joining mid-GOP gives. */
        STARTS_A_TRACK_WITHOUT_A_KEYFRAME,

        /** Obligation 4: a track's timestamps step backwards, as an unhandled RTP wrap does. */
        STEPS_ONE_TRACKS_TIMESTAMPS_BACKWARDS,

        /** The codec string: a track declared under a spelling nothing maps. */
        DECLARES_AN_UNMAPPED_CODEC,

        /** Obligation 7, first half: a record handed over for a self-describing fourcc. */
        HANDS_A_RECORD_FOR_A_SELF_DESCRIBING_FOURCC,

        /** Obligation 7, second half: #340's own defect, Annex-B samples under a record. */
        FRAMES_SAMPLES_AS_ANNEX_B_UNDER_A_RECORD,

        /** Obligation 8: one pooled buffer, refilled and handed over for every frame. */
        HANDS_THE_SAME_BUFFER_OVER_TWICE,

        /** The finality of a terminal callback: a frame after the subscription has ended. */
        DELIVERS_A_FRAME_AFTER_ENDING,

        /** Obligation 9: `cancel` returns while its delivery thread is still going. */
        KEEPS_DELIVERING_AFTER_CANCEL,
    }

    /** Counted down by [cancel]; what a defective shutdown then ignores. */
    private val cancelled = CountDownLatch(1)

    /** One buffer for the whole session, which is obligation 8's ordinary way of going wrong. */
    private val pooled = ByteArray(POOLED_BUFFER_BYTES)

    override fun subscribe(sink: FrameSink) {
        if (defect == Defect.DELIVERS_A_FRAME_BEFORE_DECLARING_ITS_TRACKS) {
            sink.onFrame(videoFrame(ordinal = 0))
        }
        sink.onTracks(tracks())
        when (defect) {
            Defect.DELIVERS_FROM_TWO_THREADS_AT_ONCE -> deliverFromTwoThreads(sink)

            Defect.KEEPS_DELIVERING_AFTER_CANCEL -> deliverPastCancellation(sink)

            else -> {
                script().forEach { sink.onFrame(it) }
                if (defect == Defect.DELIVERS_A_FRAME_AFTER_ENDING) {
                    sink.onEnded()
                    sink.onFrame(videoFrame(ordinal = FRAMES_PER_TRACK))
                }
            }
        }
    }

    override fun cancel() {
        cancelled.countDown()
    }

    /** The declaration, which every check reads and three defects bend. */
    private fun tracks(): List<RealtimeTrack> {
        val video = when {
            defect == Defect.DECLARES_AN_UNMAPPED_CODEC ->
                // A real codec and a real string, and one nothing here maps: refusing it is the
                // point, and inventing a nonsense token would prove less than a plausible one does.
                RealtimeTrack("theora")

            defect == Defect.HANDS_A_RECORD_FOR_A_SELF_DESCRIBING_FOURCC ->
                RealtimeTrack(SELF_DESCRIBING_H264, RealtimeTrack.CodecConfiguration.Record(AVCC))

            selfDescribing -> RealtimeTrack(SELF_DESCRIBING_H264)

            else -> RealtimeTrack(RECORDED_H264, RealtimeTrack.CodecConfiguration.Record(AVCC))
        }
        return listOf(video, RealtimeTrack(AAC))
    }

    /**
     * Both tracks' frames, interleaved by their offset from their own epochs, which is the order one
     * connection carries them in.
     */
    private fun script(): List<EncodedFrame> {
        val video = (0 until FRAMES_PER_TRACK).map { it * VIDEO_FRAME_DURATION_US to videoFrame(it) }
        val audio = (0 until FRAMES_PER_TRACK).map { it * AUDIO_FRAME_DURATION_US to audioFrame(it) }
        return (video + audio)
            // Ordered by each frame's *ordinal* offset rather than by the timestamp it carries, so
            // that the one defect which bends a timestamp bends the timeline and not the order it
            // was sent in. `sortedBy` is stable, so a tie keeps the video frame first, as
            // declaration order does.
            .sortedBy { (offsetUs, _) -> offsetUs }
            .map { (_, frame) -> handedOver(frame) }
    }

    /** The payload a frame is handed over with, which one defect makes the same array every time. */
    private fun handedOver(frame: EncodedFrame): EncodedFrame =
        if (defect != Defect.HANDS_THE_SAME_BUFFER_OVER_TWICE) {
            frame
        } else {
            pooled.fill(0)
            frame.payload.copyInto(pooled, endIndex = minOf(frame.payload.size, pooled.size))
            EncodedFrame(frame.timestampUs, pooled, frame.keyFrame, frame.trackIndex)
        }

    private fun videoFrame(ordinal: Int): EncodedFrame {
        val keyFrame = when (defect) {
            // A transport that joined mid-GOP and forwarded what it found rather than waiting.
            Defect.STARTS_A_TRACK_WITHOUT_A_KEYFRAME -> false

            else -> ordinal == 0
        }
        val annexB = selfDescribing || defect == Defect.FRAMES_SAMPLES_AS_ANNEX_B_UNDER_A_RECORD ||
            defect == Defect.HANDS_A_RECORD_FOR_A_SELF_DESCRIBING_FOURCC
        val timestampUs = when {
            // A 32-bit RTP timestamp that wrapped and was forwarded as it stood.
            defect == Defect.STEPS_ONE_TRACKS_TIMESTAMPS_BACKWARDS && ordinal == BACKWARDS_STEP_AT ->
                VIDEO_EPOCH_US - VIDEO_FRAME_DURATION_US

            else -> VIDEO_EPOCH_US + ordinal * VIDEO_FRAME_DURATION_US
        }
        val trackIndex = when (defect) {
            Defect.SENDS_A_FRAME_ON_AN_UNDECLARED_TRACK -> if (ordinal == 0) 0 else UNDECLARED_TRACK
            else -> 0
        }
        return EncodedFrame(
            timestampUs = timestampUs,
            payload = if (annexB) annexBUnit(keyFrame) else lengthPrefixedUnit(keyFrame),
            keyFrame = keyFrame,
            trackIndex = trackIndex,
        )
    }

    /** One AAC access unit: bytes of the right size and no meaning, since nothing here decodes. */
    private fun audioFrame(ordinal: Int): EncodedFrame = EncodedFrame(
        timestampUs = AUDIO_EPOCH_US + ordinal * AUDIO_FRAME_DURATION_US,
        payload = ByteArray(AUDIO_FRAME_BYTES) { (ordinal + it).toByte() },
        keyFrame = true,
        trackIndex = 1,
    )

    /**
     * Two threads delivering with nothing ordering them, which is obligation 1's failure exactly:
     * not "two threads" but "two threads at once".
     */
    private fun deliverFromTwoThreads(sink: FrameSink) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) { half ->
            thread(isDaemon = true, name = "publisher-$half") {
                start.await()
                try {
                    (0 until FRAMES_PER_TRACK).forEach { sink.onFrame(videoFrame(it)) }
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        done.await()
    }

    /**
     * A shutdown handed to another thread: [cancel] returns as soon as it has asked, and the
     * delivery goes on afterwards — the ordinary way obligation 9 is broken, and why the suite
     * watches after `cancel` has returned rather than only counting what arrived before it.
     */
    private fun deliverPastCancellation(sink: FrameSink) {
        script().forEach { sink.onFrame(it) }
        cancelled.await()
        (0 until FRAMES_PER_TRACK).forEach { sink.onFrame(videoFrame(FRAMES_PER_TRACK + it)) }
    }
}

/**
 * `avc1`: the fourcc that carries its parameter sets out of band, in a record, and frames its
 * samples length-prefixed. // spec: ISO/IEC 14496-15 §5.3.3.1.
 */
private const val RECORDED_H264 = "avc1.42C01E"

/** `avc3`: the self-describing fourcc, parameter sets in band and Annex-B samples. */
private const val SELF_DESCRIBING_H264 = "avc3.42C01E"

/** AAC-LC: MP4RA object type `0x40` and audio object type 2, which is what a publisher sends. */
private const val AAC = "mp4a.40.2"

/** 30 fps, which is what the video half of both of this phase's transports carries. */
private const val VIDEO_FRAME_DURATION_US = 33_333L

/**
 * One AAC-LC access unit: 1024 samples at 44.1 kHz.
 * // spec: ISO/IEC 14496-3 §4.5.2.1 — an AAC-LC frame carries 1024 samples, so its duration is
 * 1024/44100 s. Deliberately not the video step, so the two tracks interleave rather than landing on
 * the same instants.
 */
private const val AUDIO_FRAME_DURATION_US = 23_220L

/** Arbitrary and non-zero, which is the whole point of it: the epoch is the transport's. */
private const val VIDEO_EPOCH_US = 987_654_321L

/**
 * The audio track's own epoch, 40 ms ahead of the video's. A publisher's two tracks share a clock
 * wherever it has one and a real skew is what it sends — the seam promises monotonicity per track
 * and nothing across two, so this is what a correct publisher looks like rather than a defect.
 */
private const val AUDIO_EPOCH_US = VIDEO_EPOCH_US + 40_000L

/** Enough that every check sees several frames of each track and none waits for more. */
private const val FRAMES_PER_TRACK = 24

/** Which frame the backwards step lands on: past the first, so the step is a step and not an epoch. */
private const val BACKWARDS_STEP_AT = 4

/** A position past the end of a two-track declaration. */
private const val UNDECLARED_TRACK = 7

private const val AUDIO_FRAME_BYTES = 96

/** Wide enough to hold either video framing, so the pooled-buffer defect changes nothing else. */
private const val POOLED_BUFFER_BYTES = 128

/**
 * An `AVCDecoderConfigurationRecord` for the profile the fake declares: `01` configurationVersion,
 * `42 c0 1e` the profile, compatibility and level of `avc1.42C01E`, `ff` `lengthSizeMinusOne = 3` so
 * a four-byte NAL length field, `e1` one SPS of `00 04` bytes, then one PPS of `00 04`.
 *
 * Synthesized rather than observed, and labelled so: `superplayer-realtime`'s `ObservedBytes` holds
 * #340's real dump, and this module is phase 2 and cannot name it. Nothing here parses the parameter
 * sets — what the suite reads out of this record is its length field size — so a record of the right
 * shape is what the fixture needs to be.
 * // spec: ISO/IEC 14496-15 §5.3.3.1.2.
 */
private val AVCC = byteArrayOf(
    0x01, 0x42, 0xC0.toByte(), 0x1E, 0xFF.toByte(),
    0xE1.toByte(), 0x00, 0x04, 0x67, 0x42, 0xC0.toByte(), 0x1E,
    0x01, 0x00, 0x04, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte(),
)

/**
 * One NAL unit of the right shape and no picture data.
 * // spec: ITU-T H.264 §7.3.1 — `nal_unit_type` is the low five bits of the first byte: 5 is an IDR
 * slice, 1 a non-IDR one.
 */
private fun h264Unit(keyFrame: Boolean): ByteArray =
    byteArrayOf(if (keyFrame) 0x65.toByte() else 0x41.toByte()) + ByteArray(64)

/** One sample of a self-describing track: the unit behind an Annex-B start code. */
private fun annexBUnit(keyFrame: Boolean): ByteArray = byteArrayOf(0, 0, 0, 1) + h264Unit(keyFrame)

/**
 * One sample of a track carrying a record: the same unit behind the four-byte length field [AVCC]
 * declares. // spec: ISO/IEC 14496-15 §5.3.3.1.2.
 */
private fun lengthPrefixedUnit(keyFrame: Boolean): ByteArray {
    val unit = h264Unit(keyFrame)
    return byteArrayOf(0, 0, 0, unit.size.toByte()) + unit
}
