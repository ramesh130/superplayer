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
import com.superplayer.testkit.FrameSourceConformance
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.moq.MoqCatalog

/**
 * Holds `MoqFrameSource` to the contract every realtime transport is held to (#366).
 *
 * `FrameSourceConformance` is `superplayer-testkit`'s public suite of eleven checks over the nine
 * obligations `FrameSource`'s KDoc carries, written to be run **outside** this repository by a third
 * party writing a third transport. Running this module's bridge through `verifyAll` is this
 * ticket's central assertion, and it is the reason the suite exists in that shape.
 *
 * ## What a green run here proves, and what it does not
 *
 * It proves that **this bridge** — the connect thread, the declaration, the pumps, the delivery
 * monitor and the cancellation — honours all nine obligations. It proves **nothing about MoQ**: no
 * QUIC session is opened, no relay is reached and not a line of [UniffiMoqRelay] runs, because
 * `docs/testing.md` bars the network and Robolectric cannot load an Android `.so` on the JVM at all.
 * What stands behind the seam is [ScriptedMoqRelay]. A reader who takes this class as evidence that
 * MoQ works has read it wrongly, and #367 — a real session against a public relay, from a device —
 * is the ticket that answers that question instead.
 *
 * The consequence that is worth having anyway: these tests load **no native library**, so they run
 * on every host and in CI. That is `MoqCatalogTracksTest`'s shape rather than `MoqFfiLinkageTest`'s,
 * and `docs/testing.md`'s *The MoQ bindings* says why the module has both kinds.
 *
 * ## Both branches, because the fourcc decides and this module does not
 *
 * ADR-0018 rule 4 keys the codec configuration on the codec's **fourcc**, and the two branches frame
 * their samples differently — so a bridge is only shown to carry them if it carries both. The
 * catalog is what decides which: a `description` present is a `Record` and absent is `InBand`
 * (`MoqCatalogTracks`), which is exactly the fact #365 established and is the reason nothing in this
 * module has to know what an `avcC` is.
 *
 * Both runs carry **audio beside video**, since that is the ordinary MoQ broadcast and it is what
 * makes `EncodedFrame.trackIndex` mean anything. `MoqFrameSourceTest` is where the order behind that
 * index is asserted against the order `MoqCatalogTracks` produced.
 */
@RunWith(RobolectricTestRunner::class)
class MoqFrameSourceConformanceTest {

    /**
     * The self-describing branch: an `avc3` video track carrying no `description`, whose samples are
     * Annex-B, beside an AAC track.
     */
    @Test
    fun theBridgeSatisfiesEveryObligationOnASelfDescribingBroadcast() {
        conformance(
            DeclaredCatalogs.catalog(
                video = mapOf(VIDEO to DeclaredCatalogs.video(DeclaredCatalogs.OBSERVED_AVC3_CODEC, description = null)),
                audio = mapOf(AUDIO to DeclaredCatalogs.audio(DeclaredCatalogs.SYNTHESIZED_AAC_CODEC, AUDIO_SPECIFIC_CONFIG)),
            ),
        ).verifyAll()
    }

    /**
     * The other branch: an `avc1` video track carrying #340's observed `avcC`, whose samples are
     * length-prefixed behind the four-byte length field that record itself declares.
     */
    @Test
    fun theBridgeSatisfiesEveryObligationOnARecordCarryingBroadcast() {
        conformance(
            DeclaredCatalogs.catalog(
                video = mapOf(
                    VIDEO to DeclaredCatalogs.video(
                        DeclaredCatalogs.OBSERVED_AVC1_CODEC,
                        DeclaredCatalogs.OBSERVED_AVCC_PREFIX,
                    ),
                ),
                audio = mapOf(AUDIO to DeclaredCatalogs.audio(DeclaredCatalogs.SYNTHESIZED_AAC_CODEC, AUDIO_SPECIFIC_CONFIG)),
            ),
            video = ScriptedMoqTrack(payload = ::lengthPrefixedH264),
        ).verifyAll()
    }

    /** The suite over a `MoqFrameSource` whose relay publishes [catalog]. */
    private fun conformance(
        catalog: MoqCatalog,
        video: ScriptedMoqTrack = ScriptedMoqTrack(),
    ): FrameSourceConformance {
        val relay = ScriptedMoqRelay(
            catalog = catalog,
            tracks = mapOf(
                VIDEO to video,
                AUDIO to ScriptedMoqTrack(payload = ::aacFrame, frameDurationUs = AUDIO_FRAME_DURATION_US),
            ),
        )
        // A source per subscription, which is the lifecycle a player gives one and the lifecycle the
        // suite drives: it opens one per check and cancels it before the next.
        return FrameSourceConformance(
            sources = { uri -> MoqFrameSource(uri, relay) },
            uri = Uri.parse(BROADCAST),
        )
    }

    private companion object {

        /** The catalog's own keys for the two renditions; what `subscribeMedia` takes. */
        const val VIDEO = "video"
        const val AUDIO = "audio"

        /** A broadcast address in the scheme this transport answers, reached by no network here. */
        const val BROADCAST = "${MoqFrameSource.SCHEME}://relay.example/studio-a"

        /**
         * An AAC-LC `AudioSpecificConfig`, **synthesized**: #340 observed no audio track at all, so
         * this is written here and is evidence of nothing about a real publisher.
         *
         * // spec: ISO/IEC 14496-3 §1.6.2.1 — `audioObjectType` 2 (AAC-LC) in five bits,
         * // `samplingFrequencyIndex` 3 (48 kHz) in four and `channelConfiguration` 2 (stereo) in
         * // four, which is `0x12 0x10`. Nothing in this module or in the suite reads a byte of it:
         * // it is here because an AAC rendition really does carry one, so a fixture that omitted it
         * // would be a catalog no publisher sends.
         */
        val AUDIO_SPECIFIC_CONFIG = DeclaredCatalogs.bytes("12 10")
    }
}
