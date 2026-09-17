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

package com.superplayer.core

import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where [PlayerPool.maxSize] comes from — the device's reported decoder instances and its memory,
 * and nothing else.
 *
 * These tests assert on Robolectric's shadows of the platform, which is the same move
 * `SuperPlayerLifecycleTest` makes and for the same reason: what a pool is sized from is a fact
 * about the *device*, and the only way to test a derivation from device facts is to state different
 * device facts. `docs/testing.md`'s "Asserting on the platform" section is the rule this follows.
 *
 * No player is ever built here. A pool builds its players lazily, so a test about the bound is a test
 * about a number, and involving a decoder in it would be testing something else.
 */
@RunWith(AndroidJUnit4::class)
class PlayerPoolCapacityTest {

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    /**
     * Robolectric's default device — the one [TestDevice] deliberately does not touch here — is the
     * interesting degenerate case rather than an artificial one:
     * it reports an empty codec list and a 16 MB heap, and neither is far-fetched — a bare emulator
     * image reports no codec table, and 16 MB is what the platform's own floor for `memoryClass` is.
     *
     * One, and no crash, is the whole of the claim. A derivation that trusted either reading would
     * produce zero here and a feed with no video in it anywhere.
     */
    @Test
    fun aDeviceThatReportsNothingUsableYieldsAPoolOfOne() {
        assertThat(harness.buildPool().maxSize).isEqualTo(1)
    }

    @Test
    fun theBoundIsTheDecoderLimitWhenTheHeapIsPlentiful() {
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        TestDevice.declareAppHeap(megabytes = 2048)

        // A 2 GB heap affords 64 players at the per-player budget — more than any device has
        // decoders for — so what is left standing is the platform's own reported instance limit.
        // That it is Robolectric's 32 rather than a number this test chose is the point: the value
        // is read, not assumed.
        assertThat(harness.buildPool().maxSize).isEqualTo(TestDevice.REPORTED_DECODER_INSTANCES)
    }

    @Test
    fun theBoundIsTheHeapBudgetWhenItIsSmallerThanTheDecoderLimit() {
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        TestDevice.declareAppHeap(megabytes = 256)

        // A 256 MB heap at 32 MB per player. The device reports decoders enough for 32 and does not
        // get them, which is the half of the derivation a decoder count alone would miss — the cheap
        // phone shipping a generous codec table is the device a pool exists for.
        assertThat(harness.buildPool().maxSize).isEqualTo(8)
    }

    @Test
    fun aLowRamDeviceYieldsAPoolOfOneHoweverLargeItsHeap() {
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        TestDevice.declareAppHeap(megabytes = 2048)
        TestDevice.declareLowRamDevice()

        // The flag wins over the arithmetic. A manufacturer that declared the device memory-
        // constrained has said something the heap size does not: what the app is allowed and what
        // the system will let it keep before killing it are different numbers.
        assertThat(harness.buildPool().maxSize).isEqualTo(1)
    }

    @Test
    fun theCodecWithTheSmallestLimitDecidesAcrossFormats() {
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        TestDevice.declareAppHeap(megabytes = 2048)
        val avcOnly = harness.buildPool().maxSize

        // A second format the device also decodes, and one the default feed codecs name. The bound
        // may not go *up* for adding one: a pool bounded for H.264 alone is no bound for a feed that
        // also plays HEVC.
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC)
        assertThat(harness.buildPool().maxSize).isAtMost(avcOnly)
    }

    @Test
    fun aDeclaredFeedCodecWithALowerInstanceLimitDecidesTheBound() {
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, maxSupportedInstances = 16)
        TestDevice.declareVideoDecoder(VideoCodec.AV1.mimeType, maxSupportedInstances = 4)
        TestDevice.declareAppHeap(megabytes = 2048)

        // An AV1 feed on a device whose AV1 decoder runs four: a pool of sixteen would find the fifth
        // decoder partway down the scroll, which is the failure the bound is there to prevent.
        assertThat(harness.buildPool(feedCodecs = setOf(VideoCodec.AV1)).maxSize).isEqualTo(4)
        assertThat(harness.buildPool(feedCodecs = setOf(VideoCodec.H264, VideoCodec.AV1)).maxSize).isEqualTo(4)
        // And a pool that did not declare AV1 is not bounded by it: a feed that never plays AV1 does
        // not pay for a decoder it never opens. H.264 and HEVC are the default, so HEVC's sixteen.
        assertThat(harness.buildPool().maxSize).isEqualTo(16)
    }

    @Test
    fun aDeclaredFeedCodecTheDeviceHasNoDecoderForDoesNotCollapseTheBound() {
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 12)
        TestDevice.declareAppHeap(megabytes = 2048)

        // No VP9 decoder is declared. The device has said nothing about VP9, and nothing is not
        // "none" — the same direction the selector reads an unknown in — so H.264's limit stands.
        assertThat(harness.buildPool(feedCodecs = setOf(VideoCodec.H264, VideoCodec.VP9)).maxSize).isEqualTo(12)
    }

    /**
     * #211: a pool of protected players is bounded by the *secure* decoder's limit, which is the
     * number such a feed runs out of — a device commonly ships several ordinary video decoders and
     * exactly one secure one, and the two share a MIME type, so the merged reading is the plain
     * decoder's and wrong (ADR-0012 rule 12).
     *
     * Both directions in one test, because the same device answers both: what changes is only whether
     * the pool was built with `setDrm`.
     */
    @Test
    fun aPoolOfProtectedPlayersIsBoundedByTheSecureDecoderLimit() {
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 12)
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, maxSupportedInstances = 12)
        TestDevice.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 2)
        TestDevice.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, maxSupportedInstances = 2)
        TestDevice.declareAppHeap(megabytes = 2048)

        // The control: the same device, and a pool of clear players gets the plain limit. Twelve
        // players of protected content on it would find the third secure decoder missing.
        assertThat(harness.buildPool().maxSize).isEqualTo(12)
        assertThat(harness.buildPool(drm = PROTECTED).maxSize).isEqualTo(2)
    }

    /**
     * And the unknown reading is the same one everything else here takes: a device that declared
     * decoders but no secure one has said nothing about protected playback, and nothing is not
     * "none" — the ordinary limit stands rather than collapsing to the floor.
     *
     * The device this is really about is Widevine **L3**, which plays protected content on ordinary
     * decoders and declares no `FEATURE_SecurePlayback` anywhere. A pool that read a missing secure
     * entry as a limit of one would give a whole class of common devices a feed of one player.
     */
    @Test
    fun aProtectedPoolOnADeviceThatDeclaredNoSecureDecoderKeepsTheOrdinaryBound() {
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 12)
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC, maxSupportedInstances = 12)
        TestDevice.declareAppHeap(megabytes = 2048)

        assertThat(harness.buildPool(drm = PROTECTED).maxSize).isEqualTo(12)
    }

    @Test
    fun aConsumerMayAskForFewerPlayersButNotForMore() {
        TestDevice.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        TestDevice.declareAppHeap(megabytes = 2048)

        assertThat(harness.buildPool(maxSize = 2).maxSize).isEqualTo(2)
        // Asking past the device's answer gets the device's answer. A screen's guess about how many
        // videos it can afford is a guess made on somebody's desk; this one was made on the phone.
        assertThat(harness.buildPool(maxSize = TestDevice.REPORTED_DECODER_INSTANCES * 4).maxSize)
            .isEqualTo(TestDevice.REPORTED_DECODER_INSTANCES)
    }

    private companion object {
        /**
         * What makes a pool's players protected as far as the bound is concerned: the builder was told
         * `setDrm`. A bare [PlaybackDrm] rather than `superplayer-drm`'s, because core may not depend
         * on a later phase's module and because an implementation that is not an `EngineDrmExtension`
         * fills no slot — which is exactly the player a pool of clear content would otherwise build.
         */
        val PROTECTED: PlaybackDrm = object : PlaybackDrm {}
    }
}
