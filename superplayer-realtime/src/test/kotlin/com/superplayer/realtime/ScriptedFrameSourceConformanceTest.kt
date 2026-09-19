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

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.FrameSourceFactory
import com.superplayer.core.RealtimeTrack
import com.superplayer.core.UnsupportedRealtimeCodecException
import com.superplayer.testkit.FrameSourceConformance
import com.superplayer.testkit.FrameSourceConformanceException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * Holds this phase's own fake to the contract it defines (#347).
 *
 * [ScriptedFrameSource] is the reference implementation of [com.superplayer.core.FrameSource] in
 * this repository: it is what `RealtimePlaybackTest` and the golden traces play, and it is what an
 * adopter reads when they want to see one written. A reference implementation that does not satisfy
 * the conformance suite is either a broken reference or a broken suite, and either way it is the one
 * pairing nothing else checks — `FrameSourceConformanceTest` scores the suite against a publisher of
 * its own, which proves the checks work and proves nothing about this fake.
 *
 * ## Why the fake did not move into `superplayer-testkit`
 *
 * It was the alternative, and it costs more than it buys. Testkit's `internal` is not visible from
 * here — this module is a Kotlin friend of `superplayer-core` and not of testkit — so moving the
 * fake would mean **publishing** it: `ScriptedFrameSource`, `ScriptedTrack`, its payload builders
 * and #340's observed bytes would all land in `api/superplayer-testkit.api`, become a surface
 * ADR-0017 versions, and be pinned in the shape this module's tests happen to want them. What that
 * buys is one import. The fake therefore stays a test fixture of the module whose tests it serves,
 * and the direction the dependency already runs — phase 13's tests on phase 2's testkit, which is
 * how they reach `PlaybackHarness` — is what lets the suite come to it instead.
 */
@RunWith(RobolectricTestRunner::class)
class ScriptedFrameSourceConformanceTest {

    /**
     * The self-describing branch of ADR-0018 rule 4: `avc3` with in-band parameter sets and Annex-B
     * samples, beside the AAC track #346 added.
     */
    @Test
    fun theSelfDescribingFakeSatisfiesEveryObligation() {
        conformance(
            ScriptedTrack(codec = ScriptedTrack.H264, frames = SCRIPTED_FRAMES),
            audio(),
        ).verifyAll()
    }

    /**
     * The other branch: `avc1` with the configuration record #340 observed, and the length-prefixed
     * samples that record's own length field size describes.
     */
    @Test
    fun theRecordCarryingFakeSatisfiesEveryObligation() {
        conformance(
            ScriptedTrack(
                codec = RECORDED_H264,
                frames = SCRIPTED_FRAMES,
                configuration = RealtimeTrack.CodecConfiguration.Record(ObservedBytes.AVCC),
                payload = ::lengthPrefixedH264,
            ),
            audio(),
        ).verifyAll()
    }

    /**
     * The drift guard between the suite's list of codec families and the table that maps them.
     *
     * `FrameSourceConformance` cannot read [RealtimeFormats]: that table is this module's and
     * `internal`, while testkit is phase 2 and may name nothing later than core
     * (`VerifyModulePhaseRule`). So the families are written down twice, and this is the test that
     * keeps the two copies saying the same thing — a family added to the table and not to the suite
     * would otherwise be a codec SuperPlayer plays and the conformance suite refuses.
     *
     * What is asserted is agreement about the **family**, not about the whole string. The suite
     * reads the fourcc or the encoding name; the table also extracts a profile and a level and
     * refuses a string of a mapped family whose parameters do not parse. So `avc1` with no
     * parameters is accepted here and refused there, deliberately: the suite is telling a transport
     * author which codecs to publish, and the table is deciding what a decoder is handed.
     */
    @Test
    fun theSuiteAcceptsEveryCodecFamilyTheTableMaps() {
        // The table's own keys and not a third list: without this, a family added to
        // `RealtimeFormats` and to neither the suite nor the row below would pass silently, which is
        // the drift this test exists to catch. `FallbackRungCoverageTest` and `CorpusRegisterTest`
        // read their authority the same way rather than restating it.
        assertThat(MAPPED.map { it.substringBefore('.').lowercase(Locale.ROOT) }.toSet())
            .isEqualTo(RealtimeFormats.mappedTokens)
        MAPPED.forEach { codec ->
            assertThat(RealtimeFormats.formatFor(codec).sampleMimeType).isNotNull()
            assertThat(refusalFor(codec)).isNull()
        }
        UNMAPPED.forEach { codec ->
            val fromTheTable = runCatching { RealtimeFormats.formatFor(codec) }.exceptionOrNull()
            assertThat(fromTheTable).isInstanceOf(UnsupportedRealtimeCodecException::class.java)
            assertThat(refusalFor(codec)).contains(codec)
        }
    }

    /** What the suite says about a track declared as [codec], or null where it says nothing. */
    private fun refusalFor(codec: String): String? = try {
        conformance(ScriptedTrack(codec = codec, frames = SCRIPTED_FRAMES)).verifyCodecStringsAreMapped()
        null
    } catch (refused: FrameSourceConformanceException) {
        refused.message
    }

    /** The AAC track, so every run here is the two-track case both of this phase's transports carry. */
    private fun audio(): ScriptedTrack = ScriptedTrack(
        codec = ScriptedTrack.AAC,
        frames = SCRIPTED_FRAMES,
        frameDurationUs = ScriptedTrack.AUDIO_FRAME_DURATION_US,
        payload = ScriptedTrack::aacFrame,
    )

    /**
     * The suite over a fresh [ScriptedFrameSource] per check, which is the lifecycle a player gives
     * one: the same instance subscribed twice would have the first check's cancellation reach the
     * second check's delivery.
     */
    private fun conformance(vararg tracks: ScriptedTrack): FrameSourceConformance =
        FrameSourceConformance(FrameSourceFactory { ScriptedFrameSource(tracks.toList()) }, URI)

    private companion object {

        /** `avc1`, the fourcc that carries its parameter sets in the record [ObservedBytes] holds. */
        const val RECORDED_H264 = "avc1.42C01E"

        /** Comfortably more than the suite observes, so no check waits for a frame that is coming. */
        const val SCRIPTED_FRAMES = 24

        /** A scheme of the shape a transport registers and a path of no significance. */
        val URI: Uri = Uri.parse("moq://relay.example/studio-a")

        /**
         * One string per family [RealtimeFormats] maps, in both vocabularies. Each carries the
         * parameters its own grammar states, because the table refuses a mapped family whose
         * parameters do not parse and this test asserts both sides accept the same strings.
         */
        val MAPPED = listOf(
            "avc1.42C01E", "avc3.42E01E", "hvc1.1.6.L93.B0", "hev1.1.6.L93.B0",
            "vp09.00.10.08", "av01.0.04M.08", "mp4a.40.2", "mp4a.67.2", "opus",
            "H264", "H265", "VP9", "AV1", "MPEG4-GENERIC",
        )

        /**
         * Strings neither side maps: two real codecs this library does not carry, and the `mp4a`
         * object type indications that are MP3 rather than AAC — the one fourcc whose first element
         * does not decide the codec. // spec: RFC 6381 §3.3.
         */
        val UNMAPPED = listOf("theora", "vorbis", "mp4a.69", "mp4a.6B", "avc9.42C01E")
    }
}
