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

package com.superplayer.moq

import android.net.Uri
import uniffi.moq.MoqCatalog
import uniffi.moq.MoqContainer
import uniffi.moq.MoqMediaFrame
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [MoqRelay] that publishes a script, so that [MoqFrameSource] can be driven with no relay, no
 * QUIC and no native library.
 *
 * **What running against this proves, and what it does not.** It proves that *this module's bridge*
 * honours `FrameSource`'s obligations, which is what `MoqFrameSourceConformanceTest` asserts through
 * the shipped conformance suite. It proves **nothing about MoQ's bindings and nothing about a
 * relay**: no line of [UniffiMoqRelay] runs here, because `docs/testing.md` bars the network and
 * Robolectric cannot load an Android `.so` on the JVM. #367 is the first real session and it needs a
 * device. That split is the reason the seam exists at all, and [MoqRelay]'s KDoc argues it.
 *
 * It is written to be *hostile in the ways that matter*. A track is **endless by default**, because
 * a fake that finishes delivering before anything cancels it satisfies obligation 9 by arithmetic —
 * the trap `FrameSourceConformance`'s own third positive control exists for. Delivery is paced
 * rather than as fast as the host will go, both because a real publisher is and because the
 * conformance suite keeps every frame it is handed. And each track's frames carry a non-zero
 * transport epoch of their own, so a subtraction missing somewhere above is visible rather than
 * hidden by a timeline that starts at zero.
 */
internal class ScriptedMoqRelay(
    private val catalog: MoqCatalog,
    /** What each catalog track sends, by the catalog's own track name; [defaultTrack] fills the rest. */
    private val tracks: Map<String, ScriptedMoqTrack> = emptyMap(),
    private val defaultTrack: ScriptedMoqTrack = ScriptedMoqTrack(),
    /** Where this relay fails, or null for one that does not. */
    private val failsAt: FailurePoint? = null,
) : MoqRelay {

    /** How many sessions have been opened, and how many of those have been closed. */
    val sessionsOpened: AtomicInteger = AtomicInteger()
    val sessionsClosed: AtomicInteger = AtomicInteger()

    /** How many track subscriptions have been opened, and how many of those have been closed. */
    val streamsOpened: AtomicInteger = AtomicInteger()
    val streamsClosed: AtomicInteger = AtomicInteger()

    /** The track names this relay was asked to subscribe to, in the order they were asked for. */
    val subscribed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override fun connect(uri: Uri): MoqBroadcastSession {
        if (failsAt == FailurePoint.CONNECT) throw IOException(FAILURE_MESSAGE)
        sessionsOpened.incrementAndGet()
        return ScriptedSession()
    }

    /** Where a scripted relay fails, one point per thing that can go wrong on the way to a frame. */
    enum class FailurePoint { CONNECT, CATALOG, SUBSCRIBE, FRAME }

    private inner class ScriptedSession : MoqBroadcastSession {

        override fun catalog(): MoqCatalog {
            if (failsAt == FailurePoint.CATALOG) throw IOException(FAILURE_MESSAGE)
            return catalog
        }

        override fun subscribe(trackName: String, container: MoqContainer): MoqTrackStream {
            if (failsAt == FailurePoint.SUBSCRIBE) throw IOException(FAILURE_MESSAGE)
            subscribed += trackName
            streamsOpened.incrementAndGet()
            return ScriptedStream(tracks[trackName] ?: defaultTrack, failsAt == FailurePoint.FRAME)
        }

        override fun close() {
            sessionsClosed.incrementAndGet()
        }
    }

    private inner class ScriptedStream(
        private val script: ScriptedMoqTrack,
        private val failsOnAFrame: Boolean,
    ) : MoqTrackStream {

        // `java.lang.Object` by its full name: Kotlin's `Any` carries no `wait`/`notifyAll`, and a
        // monitor is what a blocking pull that must be woken by `close()` needs.
        private val paced = java.lang.Object()
        private var delivered = 0

        @Volatile
        private var closed = false

        override fun next(): MoqMediaFrame? {
            // Paced on a wait rather than a sleep, so that `close()` wakes a reader at once. That is
            // the obligation `MoqTrackStream.close` carries, and a fake that did not honour it would
            // make `MoqFrameSource.cancel`'s join look like a hang rather than a defect here.
            synchronized(paced) {
                if (!closed) paced.wait(FRAME_INTERVAL_MS)
            }
            if (closed) return null
            if (failsOnAFrame && delivered == FAILING_FRAME) throw IOException(FAILURE_MESSAGE)
            if (script.frames != ENDLESS && delivered >= script.frames) return null
            val ordinal = delivered++
            return MoqMediaFrame(
                payload = script.payload(ordinal == script.leadingNonKeyFrames),
                timestampUs = (script.epochUs + ordinal * script.frameDurationUs).toULong(),
                // One group: the frames before [ScriptedMoqTrack.leadingNonKeyFrames] are the
                // dependent ones a subscription joining part-way through a group receives, that
                // index is the group's keyframe, and everything after it depends on that keyframe.
                keyframe = ordinal == script.leadingNonKeyFrames,
            )
        }

        override fun close() {
            closed = true
            synchronized(paced) { paced.notifyAll() }
            streamsClosed.incrementAndGet()
        }
    }

    companion object {

        /** A track that keeps publishing, which is what a live broadcast does. */
        const val ENDLESS: Int = -1

        /**
         * How long a scripted frame takes to arrive.
         *
         * A pace rather than no pace, for two reasons that point the same way. A publisher sends
         * frames at the rate it encodes them, so an unpaced fake is a fake of nothing; and
         * `FrameSourceConformance` keeps a copy of every payload it is handed, so an endless track
         * running at host speed through its settle window would be a fake that exhausted the heap
         * rather than a check that failed. Two milliseconds is under one video frame's own duration,
         * so the sixteen frames every check waits for still cost well under a tenth of a second.
         */
        const val FRAME_INTERVAL_MS: Long = 2

        /** Far enough in that a failing relay has declared its tracks and delivered before it fails. */
        const val FAILING_FRAME: Int = 3

        /** What a scripted failure says, so a test can assert the cause reached the sink intact. */
        const val FAILURE_MESSAGE: String = "the scripted relay was told to fail here"
    }
}

/**
 * One track's script: what it sends, how much of it, and on what timeline.
 *
 * @property payload One frame's bytes, given whether it is a keyframe. A parameter because the two
 *   branches of ADR-0018 rule 4 frame their samples differently — Annex-B for a self-describing
 *   track, length-prefixed for one that declared a configuration record.
 * @property frames How many frames this track sends, or [ScriptedMoqRelay.ENDLESS] for a publisher
 *   that keeps going. Endless is the default for obligation 9's reason.
 * @property leadingNonKeyFrames How many dependent frames arrive before the first keyframe, which is
 *   what a subscription joining part-way through a group receives. Zero is a publisher whose first
 *   frame is already a keyframe.
 * @property frameDurationUs The step between this track's frames.
 * @property epochUs This track's first frame's transport timestamp. Non-zero on purpose: a fake that
 *   started at zero would hide a missing subtraction on the other side of the seam.
 */
internal class ScriptedMoqTrack(
    val payload: (Boolean) -> ByteArray = ::annexBH264,
    val frames: Int = ScriptedMoqRelay.ENDLESS,
    val leadingNonKeyFrames: Int = 0,
    val frameDurationUs: Long = VIDEO_FRAME_DURATION_US,
    val epochUs: Long = TRANSPORT_EPOCH_US,
)

/** 30 fps. */
internal const val VIDEO_FRAME_DURATION_US: Long = 33_333

/**
 * One AAC-LC access unit: 1024 samples at 44.1 kHz.
 *
 * // spec: ISO/IEC 14496-3 §4.5.2.1 — an AAC-LC frame carries 1024 samples, so its duration is
 * // 1024/44100 s. Deliberately not the video step, so that two tracks' frames do not land on the
 * // same instants and an ordering assertion cannot pass on arithmetic.
 */
internal const val AUDIO_FRAME_DURATION_US: Long = 23_220

/** Arbitrary and non-zero, which is the whole point of it. */
internal const val TRANSPORT_EPOCH_US: Long = 987_654_321

/**
 * One NAL unit of the right shape and no picture data.
 *
 * // spec: ITU-T H.264 §7.3.1 — the NAL unit header's `nal_unit_type` is the low five bits of its
 * // first byte: 5 is an IDR slice, 1 a non-IDR one.
 */
private fun h264NalUnit(keyFrame: Boolean): ByteArray =
    byteArrayOf(if (keyFrame) 0x65.toByte() else 0x41.toByte()) + ByteArray(64)

/**
 * One frame of a self-describing track: [h264NalUnit] behind an Annex-B start code.
 *
 * These two builders are `superplayer-realtime`'s `ScriptedFrameSource`'s, written again here rather
 * than shared, for the reason `DeclaredCatalogs` gives about #340's `avcC` dump: that file is
 * another module's *test* source, which no module can see, and promoting it would publish a fixture
 * as API to save an import. What keeps the copies honest is the citation above each, which is the
 * same sentence of the same specification in both places.
 */
internal fun annexBH264(keyFrame: Boolean): ByteArray = byteArrayOf(0, 0, 0, 1) + h264NalUnit(keyFrame)

/**
 * One frame of an `avc1` track: the same unit behind the four-byte length field #340's observed
 * `avcC` declares, where a self-describing track would carry a start code.
 *
 * // spec: ISO/IEC 14496-15 §5.3.3.1.2 — a sample of a track whose sample entry carries an `avcC` is
 * // a run of NAL units each prefixed by its length, `lengthSizeMinusOne + 1` bytes wide.
 */
internal fun lengthPrefixedH264(keyFrame: Boolean): ByteArray {
    val unit = h264NalUnit(keyFrame)
    return byteArrayOf(0, 0, 0, unit.size.toByte()) + unit
}

/** One frame of audio: bytes of the right size and no meaning, since nothing here decodes. */
internal fun aacFrame(@Suppress("UNUSED_PARAMETER") keyFrame: Boolean): ByteArray = ByteArray(96)
