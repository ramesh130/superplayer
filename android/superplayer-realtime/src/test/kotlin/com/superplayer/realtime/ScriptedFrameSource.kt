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

package com.superplayer.realtime

import com.superplayer.core.EncodedFrame
import com.superplayer.core.FrameSink
import com.superplayer.core.FrameSource
import com.superplayer.core.RealtimeTrack

/**
 * One track of a [ScriptedFrameSource]'s script: what it is declared as, and what it sends.
 *
 * @property codec The codec the track is declared as.
 * @property configuration What the track says its parameter sets come in, which its fourcc decides
 *   (ADR-0018 rule 4). The default is the self-describing shape [ScriptedTrack.H264] requires.
 * @property frames How many frames this track delivers. Zero is a track that is declared and never
 *   delivers, which is what the late-start and stall bounds are driven with.
 * @property epochUs The transport timestamp of this track's first frame. Offset from zero on
 *   purpose: the period subtracts the period's anchor, and a fake that started at zero would hide a
 *   missing subtraction. Two tracks given *different* epochs are two tracks whose origins do not
 *   share a clock, which is #346's reconciliation rule driven from the seam.
 * @property frameDurationUs The step between this track's frames, so a 30 fps video track and a
 *   ~43 fps audio track can be scripted against one another.
 * @property payload One frame's bytes, given whether it is a keyframe. A parameter because the two
 *   branches of rule 4 frame their samples differently — Annex-B for a self-describing stream,
 *   length-prefixed for one with a configuration record.
 */
internal class ScriptedTrack(
    val codec: String,
    val frames: Int,
    val configuration: RealtimeTrack.CodecConfiguration = RealtimeTrack.CodecConfiguration.InBand,
    val epochUs: Long = TRANSPORT_EPOCH_US,
    val frameDurationUs: Long = VIDEO_FRAME_DURATION_US,
    val payload: (Boolean) -> ByteArray = ::annexBH264,
) {

    /** This track's frames, in order, on its own epoch. */
    fun frames(index: Int): Sequence<EncodedFrame> = (0 until frames).asSequence().map { ordinal ->
        EncodedFrame(
            timestampUs = epochUs + ordinal * frameDurationUs,
            payload = payload(ordinal == 0),
            keyFrame = ordinal == 0,
            trackIndex = index,
        )
    }

    companion object {

        /**
         * `avc3`, so the track carries no codec-specific data and the parameter sets are in band —
         * the branch of ADR-0018 rule 4 that needs no conversion. The other branch is a test's to
         * ask for, by naming an `avc1` codec beside a [RealtimeTrack.CodecConfiguration.Record] and
         * a length-prefixed [lengthPrefixedH264] payload.
         */
        const val H264 = "avc3.42E01E"

        /** AAC-LC: MP4RA object type `0x40` and audio object type 2, which is what a publisher sends. */
        const val AAC = "mp4a.40.2"

        /** 30 fps, which makes 300 frames ten seconds — comfortably past any profile's buffer floor. */
        const val VIDEO_FRAME_DURATION_US = 33_333L

        /**
         * One AAC-LC access unit: 1024 samples at 44.1 kHz.
         *
         * spec: ISO/IEC 14496-3 §4.5.2.1 — an AAC-LC frame carries 1024 samples, so its duration is
         *   1024/44100 s. Deliberately not the video step, because two tracks whose frames land on
         *   the same instants would make a sync assertion pass on arithmetic rather than on the
         *   timeline.
         */
        const val AUDIO_FRAME_DURATION_US = 23_220L

        /** Arbitrary and non-zero, which is the whole point of it. */
        const val TRANSPORT_EPOCH_US = 987_654_321L

        /** One frame of audio: bytes of the right size and no meaning, since nothing here decodes. */
        fun aacFrame(@Suppress("UNUSED_PARAMETER") keyFrame: Boolean): ByteArray = ByteArray(96)
    }
}

/**
 * A [FrameSource] that delivers a fixed script of synthetic frames and then stays open, as a live
 * publisher that has not yet sent more does.
 *
 * Delivery is synchronous inside [subscribe], on the thread that prepared the player, which is
 * [FrameSource] obligation 1 honoured in its simplest form. A transport that connects on a thread of
 * its own is the realistic case and is #347's to exercise; what this fake is for is removing every
 * source of non-determinism that is not the code under test.
 *
 * Several tracks are delivered **interleaved**, ordered by each frame's offset from its own track's
 * epoch, which is what a publisher sending audio beside video does on one connection. Interleaving
 * rather than track-by-track is load-bearing for #346's reconciliation: a track anchored on a
 * different epoch is placed at the point the period had reached when its first frame arrived, so a
 * fake that sent all of one track before starting the other would place the second one at the end
 * of the first and call it sync.
 */
internal class ScriptedFrameSource(
    private val tracks: List<ScriptedTrack>,
    /** Whether the publisher stops at the end of the script, as one that goes off air does. */
    private val endsAfterScript: Boolean = false,
) : FrameSource {

    /** The one-track shape #343 and #345 drive, kept so those tests say what they always said. */
    constructor(
        frames: Int,
        endsAfterScript: Boolean = false,
        codec: String = ScriptedTrack.H264,
        configuration: RealtimeTrack.CodecConfiguration = RealtimeTrack.CodecConfiguration.InBand,
        payload: (Boolean) -> ByteArray = ::annexBH264,
    ) : this(
        listOf(ScriptedTrack(codec = codec, frames = frames, configuration = configuration, payload = payload)),
        endsAfterScript,
    )

    @Volatile
    var subscribed: Boolean = false
        private set

    @Volatile
    var cancelled: Boolean = false
        private set

    override fun subscribe(sink: FrameSink) {
        subscribed = true
        sink.onTracks(tracks.map { RealtimeTrack(codec = it.codec, codecConfiguration = it.configuration) })
        tracks
            .flatMapIndexed { index, track -> track.frames(index).map { frame -> track.epochUs to frame }.toList() }
            // By offset from the track's own epoch, so two tracks on two clocks still interleave the
            // way the publisher sent them. `sortedBy` is stable, so a tie keeps declaration order.
            .sortedBy { (epochUs, frame) -> frame.timestampUs - epochUs }
            .forEach { (_, frame) -> sink.onFrame(frame) }
        // By default no `onEnded`: a live stream does not end because the fake ran out of script,
        // and leaving the subscription open is what keeps the period `isLoading` as a real one is.
        if (endsAfterScript) sink.onEnded()
    }

    override fun cancel() {
        cancelled = true
    }
}

/**
 * One NAL unit of the right shape and no picture data.
 *
 * spec: ITU-T H.264 §7.3.1 — the NAL unit header's `nal_unit_type` is the low five bits of its first
 *   byte: 5 is an IDR slice, 1 a non-IDR one.
 */
private fun h264NalUnit(keyFrame: Boolean): ByteArray =
    byteArrayOf(if (keyFrame) 0x65.toByte() else 0x41.toByte()) + ByteArray(64)

/** One frame of a self-describing track: [h264NalUnit] behind an Annex-B start code. */
internal fun annexBH264(keyFrame: Boolean): ByteArray = byteArrayOf(0, 0, 0, 1) + h264NalUnit(keyFrame)

/**
 * One frame of an `avc1` track: the same unit behind the four-byte length field #340's observed
 * `avcC` declares, where a self-describing track would carry a start code.
 *
 * spec: ISO/IEC 14496-15 §5.3.3.1.2 — a sample of a track whose sample entry carries an `avcC` is a
 *   run of NAL units each prefixed by its length, `lengthSizeMinusOne + 1` bytes wide.
 */
internal fun lengthPrefixedH264(keyFrame: Boolean): ByteArray {
    val unit = h264NalUnit(keyFrame)
    return byteArrayOf(0, 0, 0, unit.size.toByte()) + unit
}

/** A [FrameSource] whose subscription fails at once, as a relay that refuses one does. */
internal class FailingFrameSource(private val cause: Throwable) : FrameSource {

    override fun subscribe(sink: FrameSink) {
        sink.onError(cause)
    }

    override fun cancel() = Unit
}
