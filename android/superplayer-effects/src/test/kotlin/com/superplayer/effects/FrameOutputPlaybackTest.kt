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

package com.superplayer.effects

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.FrameOutput
import com.superplayer.core.FrameOutputOffReason
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackDrm
import com.superplayer.core.SuperPlayer
import com.superplayer.drm.Drm
import com.superplayer.drm.WidevineConfig
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testkit.VideoEffectsDelivery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * #406's tracer bullet: a [FrameOutput] reaches the engine through `VideoEffects.frameOutput`, set on a
 * real `SuperPlayer` exactly as a consumer writes it, and is off — with a reason, and with the player
 * still playing — where ADR-0022 rule 6 says it must be.
 *
 * **What this can show is the list reaching the engine, and not a frame processed.** The observation is
 * `PlaybackHarness.videoEffectsDeliveries`, read at the video renderer Media3 delivers
 * `setVideoEffects` to, which is the place a real `MediaCodecVideoRenderer` sets its pipeline up. The
 * harness's renderer is Media3's fake and Robolectric has no GPU, so no GL effect pipeline runs here:
 * whether [FrameOutput.process] is called once per frame, on time, and whether what it returns is what
 * the surface shows, is a device's to show, and `docs/testing.md`'s *A frame output* says so. The same
 * is true of the copy shader `FrameOutputShaderProgram` draws with, which nothing under `check` compiles.
 *
 * Realtime's half of rule 6 is core's `SuperPlayerFrameOutputSeamTest`: `superplayer-realtime` is phase
 * 13, and a phase 8 module's tests may not reach it.
 */
@RunWith(AndroidJUnit4::class)
class FrameOutputPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /**
     * ADR-0022 rule 2 and Media3's first limit: the effect list reaches the engine once, as one effect,
     * before `prepare()` enabled the renderer, and the player plays. The output is on, so no reason.
     */
    @Test
    fun theEffectReachesTheEngineOnceAndBeforePrepare() {
        val content = TestContent.video()
        val player = harness.buildPlayer(content = content, frameOutput = VideoEffects.frameOutput(PASS_THROUGH))
        player.setMediaRequest(request(content))
        harness.playToReady(player)

        assertThat(harness.videoEffectsDeliveries(player)).containsExactly(VideoEffectsDelivery(effectCount = 1, beforeRendererEnabled = true))
        assertThat(player.frameOutputOffReason).isNull()
        assertThat(player.playerError).isNull()
    }

    /**
     * ADR-0022 rule 9 from this side of the boundary: a player built without a frame output is never
     * handed an effect list, while the same content on a player built with one is — so the counter is
     * shown to see what it counts. The slot's half is core's `SuperPlayerFrameOutputSeamTest`.
     */
    @Test
    fun aPlayerBuiltWithoutAFrameOutputIsHandedNoEffects() {
        val content = TestContent.video()
        val without = harness.buildPlayer(content = content)
        val with = harness.buildPlayer(content = content, frameOutput = VideoEffects.frameOutput(PASS_THROUGH))
        listOf(without, with).forEach { player ->
            player.setMediaRequest(request(content))
            harness.playToReady(player)
        }

        assertThat(harness.videoEffectsDeliveries(without)).isEmpty()
        assertThat(harness.videoEffectsDeliveries(with)).hasSize(1)
    }

    /**
     * ADR-0022 rule 6, both protocols: a player built with protection and a frame output acquires its
     * licence, plays protected content **to the end** with no error, is handed no effects, and says
     * why — readable before the first item and the same after it.
     */
    @Test
    fun aProtectedPlayerPlaysToTheEndWithTheOutputOffAndSaysWhy() {
        listOf(TestContent.protectedHls(), TestContent.protectedDash()).forEach { content ->
            val player = harness.buildPlayer(content = content, drm = widevine(), frameOutput = VideoEffects.frameOutput(PASS_THROUGH))
            assertThat(player.frameOutputOffReason).isEqualTo(FrameOutputOffReason.PROTECTED_CONTENT)

            player.setMediaRequest(request(content))
            playToEnd(player)

            assertThat(player.playerError).isNull()
            assertThat(harness.networkRequests(player).map { it.kind }).contains(ResourceKind.LICENCE)
            assertThat(harness.videoEffectsDeliveries(player)).isEmpty()
            assertThat(player.frameOutputOffReason).isEqualTo(FrameOutputOffReason.PROTECTED_CONTENT)
            harness.release(player)
        }
    }

    /**
     * The cost rule 6 states rather than hides: a protected player gets no effect on its *clear* items
     * either. The clear video that the first test's player handed to the engine, this player plays with
     * nothing handed — which is also the control that the protected test above is not merely an
     * audio-only stream reaching no video renderer.
     */
    @Test
    fun aProtectedPlayerRunsNoEffectOnItsClearItemsEither() {
        val content = TestContent.video()
        val player = harness.buildPlayer(content = content, drm = widevine(), frameOutput = VideoEffects.frameOutput(PASS_THROUGH))
        player.setMediaRequest(request(content))
        harness.playToReady(player)

        assertThat(harness.videoEffectsDeliveries(player)).isEmpty()
        assertThat(player.frameOutputOffReason).isEqualTo(FrameOutputOffReason.PROTECTED_CONTENT)
        assertThat(player.playerError).isNull()
    }

    /**
     * Nothing here is a component, a permission or a provider, so the module publishes no manifest and
     * an app's merged one carries nothing of it; and core declares no `media3-effect`, so an app that
     * does not add this module does not carry the artifact (ADR-0022 rule 9). Read off the build files'
     * declarations, not the resolved classpath, so an artifact core gained transitively would pass this;
     * this module's own declaration is the control that makes the reading a reading.
     */
    @Test
    fun onlyThisModuleCarriesMediaThreesEffectPipeline() {
        assertThat(File("src/main/AndroidManifest.xml").exists()).isFalse()
        assertThat(File("../superplayer-core/build.gradle.kts").readText()).doesNotContain(EFFECT_ARTIFACT)
        assertThat(File("build.gradle.kts").readText()).contains(EFFECT_ARTIFACT)
    }

    private fun playToEnd(player: SuperPlayer) {
        player.prepare()
        player.play()
        harness.advanceUntil(player, "the end of the content") { it.playbackState == Player.STATE_ENDED }
    }

    private fun widevine(): PlaybackDrm = Drm.widevine(WidevineConfig(licenceUri = FakeLicenceServer.LICENCE_URI))

    private fun request(content: TestContent): MediaRequest = MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    private companion object {

        const val CONTENT_ID = "film/frame-output"

        /** The catalog accessor for `media3-effect`, as a build file names it. */
        const val EFFECT_ARTIFACT = "libs.media3.effect"

        /** The effect that changes nothing, which is all a test without a GPU could run anyway. */
        val PASS_THROUGH = FrameOutput { frame, _ -> frame }
    }
}
