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
 * A [FrameSource] that delivers a fixed number of synthetic H.264 frames and then stays open, as a
 * live publisher that has not yet sent more does.
 *
 * Delivery is synchronous inside [subscribe], on the thread that prepared the player, which is
 * [FrameSource] obligation 1 honoured in its simplest form. A transport that connects on a thread of
 * its own is the realistic case and is #347's to exercise; what this fake is for is removing every
 * source of non-determinism that is not the code under test.
 */
internal class ScriptedFrameSource(
    private val frames: Int,
    /** Whether the publisher stops at the end of the script, as one that goes off air does. */
    private val endsAfterScript: Boolean = false,
    /** The codec the track is declared as; overridden by the test that hands over an unmappable one. */
    private val codec: String = DEFAULT_CODEC,
    /**
     * What the track says its parameter sets come in, which its fourcc decides (ADR-0018 rule 4).
     * The default is the self-describing shape [DEFAULT_CODEC] requires.
     */
    private val configuration: RealtimeTrack.CodecConfiguration = RealtimeTrack.CodecConfiguration.InBand,
    /**
     * One frame's bytes, given whether it is a keyframe.
     *
     * A parameter because the two branches of rule 4 frame their samples differently — Annex-B for
     * a self-describing stream, length-prefixed for one with a configuration record — and the
     * default is the Annex-B one every other test in this module drives.
     */
    private val payload: (Boolean) -> ByteArray = ::annexBH264,
) : FrameSource {

    @Volatile
    var subscribed: Boolean = false
        private set

    @Volatile
    var cancelled: Boolean = false
        private set

    override fun subscribe(sink: FrameSink) {
        subscribed = true
        sink.onTrack(RealtimeTrack(codec = codec, codecConfiguration = configuration))
        repeat(frames) { index ->
            sink.onFrame(
                EncodedFrame(
                    // An epoch of the transport's own, offset from zero on purpose: the period
                    // subtracts the first frame's value, and a fake that started at zero would hide
                    // a missing subtraction.
                    timestampUs = TRANSPORT_EPOCH_US + index * FRAME_DURATION_US,
                    payload = payload(index == 0),
                    keyFrame = index == 0,
                ),
            )
        }
        // By default no `onEnded`: a live stream does not end because the fake ran out of script,
        // and leaving the subscription open is what keeps the period `isLoading` as a real one is.
        if (endsAfterScript) sink.onEnded()
    }

    override fun cancel() {
        cancelled = true
    }

    private companion object {
        /**
         * `avc3`, so the track carries no codec-specific data and the parameter sets are in band —
         * the branch of ADR-0018 rule 4 that needs no conversion. The other branch is a test's to
         * ask for, by naming an `avc1` codec beside a [RealtimeTrack.CodecConfiguration.Record] and
         * a length-prefixed [lengthPrefixedH264] payload.
         */
        const val DEFAULT_CODEC = "avc3.42E01E"

        /** 30 fps, which makes 300 frames ten seconds — comfortably past any profile's buffer floor. */
        const val FRAME_DURATION_US = 33_333L

        /** Arbitrary and non-zero, which is the whole point of it. */
        const val TRANSPORT_EPOCH_US = 987_654_321L
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
