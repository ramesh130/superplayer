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

package com.superplayer.tv

import android.content.Context
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.abr.AdaptivePolicy
import com.superplayer.core.MediaRequest
import com.superplayer.core.OutputPolicy
import com.superplayer.core.PlaybackOutput
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tunneled playback where the device and the content support it (#271, ADR-0014 rule 7).
 *
 * Two readings, one per half of the claim. The **decision** is `player.playbackDecision.output`, public.
 * Whether it was **applied** is read where Media3 delivers it, at the harness's video renderer
 * (`PlaybackHarness.videoRendererTunneled`): the configuration the engine enabled it with, which Media3
 * makes tunneled only where the selector was asked to tunnel *and* a video renderer and an audio renderer
 * were both enabled and both answered for tunneling. The video renderer answers from the decoder the stated
 * device declares (`DeviceStatement.declareTunnelingVideoDecoder`), so the device is a statement and not an
 * assumption, and the content is `TestContent.videoWithAudio`, because the synthetic HLS and DASH streams
 * are audio-only and described video is video alone.
 *
 * That is where the observation stops. Nothing here decodes a frame, no audio session reaches a codec, and
 * no compositor receives a sideband stream, so whether video actually tunnels, whether it stays in sync, and
 * whether a vendor's tunneled path is better than the one it replaces are a device's to show (#274,
 * `docs/testing.md`'s *A TV device*). Protected content is not played here: no described stream carries
 * protection, and the rule for it is that the *secure* decoder answers, which the harness's renderer mirrors
 * and `superplayer-testkit`'s `DeviceStatementTest` asserts.
 *
 * Each control is its own method, because the platform caches the codec list on its first read.
 */
@RunWith(AndroidJUnit4::class)
class TunneledPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aLeanbackPlayerOnADeviceWhoseDecoderTunnelsPlaysVideoAndAudioTunneled() {
        DeviceStatement.declareTunnelingVideoDecoder(MimeTypes.VIDEO_H264)

        val player = play(TestContent.videoWithAudio(), PlaybackProfile.TV_LEANBACK, output = TvOutput.standard(context))

        assertThat(player.playbackDecision.output).isEqualTo(OutputPolicy(tunneling = true))
        assertThat(harness.videoRendererTunneled(player)).isTrue()
    }

    /** The adaptive policy carries the profile's output half through, so the module a TV app uses tunnels too. */
    @Test
    fun anAdaptiveLeanbackPlayerTunnelsOnTheSameDevice() {
        DeviceStatement.declareTunnelingVideoDecoder(MimeTypes.VIDEO_H264)

        val player = play(
            TestContent.videoWithAudio(),
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.TV_LEANBACK),
            output = TvOutput.standard(context),
        )

        assertThat(player.playbackDecision.output.tunneling).isTrue()
        assertThat(harness.videoRendererTunneled(player)).isTrue()
    }

    /** Control: the same player and content on a device whose decoder declares no tunneled playback. */
    @Test
    fun aDeviceWhoseDecoderDeclaresNoTunnelingPlaysUntunneled() {
        DeviceStatement.declareVideoDecoder(MimeTypes.VIDEO_H264)

        val player = play(TestContent.videoWithAudio(), PlaybackProfile.TV_LEANBACK, output = TvOutput.standard(context))

        // Decided and refused, which is a request declined rather than an error: the player plays.
        assertThat(player.playbackDecision.output.tunneling).isTrue()
        assertThat(harness.videoRendererTunneled(player)).isFalse()
    }

    /** Control: a profile that decides tunneling off, on the device that would tunnel. */
    @Test
    fun aProfileThatDecidesNoTunnelingPlaysUntunneled() {
        DeviceStatement.declareTunnelingVideoDecoder(MimeTypes.VIDEO_H264)

        val player = play(TestContent.videoWithAudio(), PlaybackProfile.VIDEO_ON_DEMAND, output = TvOutput.standard(context))

        assertThat(player.playbackDecision.output).isEqualTo(OutputPolicy.NONE)
        assertThat(harness.videoRendererTunneled(player)).isFalse()
    }

    /** Control: a consumer's policy that takes the leanback decision and turns only its output half off. */
    @Test
    fun aPolicyThatDecidesNoTunnelingPlaysUntunneled() {
        DeviceStatement.declareTunnelingVideoDecoder(MimeTypes.VIDEO_H264)
        val leanback = PlaybackPolicy.forProfile(PlaybackProfile.TV_LEANBACK)
        val untunneled = PlaybackPolicy { conditions -> leanback.decide(conditions).copy(output = OutputPolicy.NONE) }

        val player = play(TestContent.videoWithAudio(), policy = untunneled, output = TvOutput.standard(context))

        assertThat(player.playbackDecision.output).isEqualTo(OutputPolicy.NONE)
        assertThat(harness.videoRendererTunneled(player)).isFalse()
    }

    /** Control: the decision is laid only on a player built with an output (ADR-0014 rules 7 and 14). */
    @Test
    fun aPlayerBuiltWithoutAnOutputDecidesTunnelingAndIgnoresIt() {
        DeviceStatement.declareTunnelingVideoDecoder(MimeTypes.VIDEO_H264)

        val player = play(TestContent.videoWithAudio(), PlaybackProfile.TV_LEANBACK, output = null)

        assertThat(player.playbackDecision.output.tunneling).isTrue()
        assertThat(harness.videoRendererTunneled(player)).isFalse()
    }

    /** Control: content with no audio beside its video cannot tunnel, because Media3 tunnels the pair. */
    @Test
    fun videoWithNoAudioPlaysUntunneled() {
        DeviceStatement.declareTunnelingVideoDecoder(MimeTypes.VIDEO_H264)

        val player = play(TestContent.video(), PlaybackProfile.TV_LEANBACK, output = TvOutput.standard(context))

        assertThat(harness.videoRendererTunneled(player)).isFalse()
    }

    private fun play(
        content: TestContent,
        profile: PlaybackProfile? = null,
        policy: PlaybackPolicy? = null,
        output: PlaybackOutput?,
    ): SuperPlayer {
        val player = harness.buildPlayer(content = content, profile = profile, policy = policy, output = output)
        player.setMediaRequest(MediaRequest.Builder("title:tunneled").addSource(content.sourceUri).build())
        harness.playToReady(player)
        return player
    }
}
