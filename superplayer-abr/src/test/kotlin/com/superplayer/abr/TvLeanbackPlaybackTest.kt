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

package com.superplayer.abr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TV_LEANBACK` on a wired link against `VIDEO_ON_DEMAND` on WiFi (#267): a television on Ethernet
 * that does not behave like a phone on WiFi, forced through the harness rather than read off the
 * table. Both players carry the adaptive policy, so the phone is compared at its best — its cushion
 * deepened by branch 1 on a stable, fast link — and not as a cold start.
 *
 * What a viewer would notice is the media held ahead, which is what rides a household's broadband
 * blip out on the room's screen. `NetworkProfile.ETHERNET` is `STABLE_WIFI`'s rate on a wired
 * transport, so the link's speed is not what differs.
 *
 * Said plainly, because the pairing hides it: the buffer difference is the *profile's*. No buffer or
 * cap rule departs on Ethernet from WiFi — a wired link is uncapped as WiFi is, and branch 1 deepens
 * on both — so a television on WiFi would hold the same. What the wired transport changes is the cold
 * estimate a session starts from (`ColdDefaults`), which the QoE gate's `ETHERNET` row scores. The
 * transport is replayed here so that the television is observed on the link the profile is for, not
 * to claim a second variable.
 */
@RunWith(AndroidJUnit4::class)
class TvLeanbackPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun aCapableDeviceAndACleanMemory() {
        // Robolectric's 16 MB heap would put both players under branch 5's memory ceiling, which is
        // right for a device that small and would make the two decisions agree for a reason neither
        // profile is about.
        DeviceStatement.declareAppHeap(LARGE_HEAP_MB)
        EstimateMemory.PROCESS.forget()
    }

    @After
    fun forgetTheProcessMemory() {
        EstimateMemory.PROCESS.forget()
    }

    @Test
    fun aTelevisionOnEthernetHoldsMoreAheadThanAPhoneOnWifiAtItsDeepest() {
        // The control: the phone's cushion deepens once the link is measured, and that deepened
        // ceiling is the most it holds on this link. A television that only matched it would be a
        // phone with a bigger screen.
        val phone = build(PlaybackProfile.VIDEO_ON_DEMAND, NetworkProfile.STABLE_WIFI)
        val phoneStatic = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND).decide(PlaybackConditions()).buffer
        harness.advanceUntil(phone, "the phone's cushion to deepen") {
            phone.playbackDecision.buffer.maxBufferMs > phoneStatic.maxBufferMs
        }
        val phoneDeepest = phone.playbackDecision.buffer
        assertThat(phoneDeepest.minBufferMs).isEqualTo(AdaptiveBufferPolicy.DEEP_CUSHION_MS.toInt())
        harness.release(phone)
        EstimateMemory.PROCESS.forget()

        // Deeper from its first decision, before anything is measured: a television does not have to
        // earn the cushion the phone did.
        val television = build(PlaybackProfile.TV_LEANBACK, NetworkProfile.ETHERNET)
        assertThat(television.playbackDecision.buffer.minBufferMs).isAtLeast(phoneDeepest.minBufferMs)
        assertThat(television.playbackDecision.buffer.maxBufferMs).isGreaterThan(phoneDeepest.maxBufferMs)

        // And the media is really there: more held ahead than the phone's deepest ceiling, by more
        // than the one chunk a load control may overshoot a ceiling by.
        harness.advanceUntil(television, "the television to hold more than the phone may", FILL_BOUND_MS) {
            it.totalBufferedDuration > phoneDeepest.maxBufferMs + CHUNK_ALLOWANCE_MS
        }
        assertThat(television.playbackDecision.buffer.maxBufferMs).isGreaterThan(phoneDeepest.maxBufferMs)
        assertThat(television.playerError).isNull()
    }

    private fun build(profile: PlaybackProfile, network: NetworkProfile): SuperPlayer {
        val content = TestContent.videoLadder(durationMs = LONG_CONTENT_MS)
        val player = harness.buildPlayer(
            content = content,
            profile = profile,
            network = network.trace,
            policy = AdaptivePolicy.forProfile(context, profile),
        )
        player.setMediaRequest(MediaRequest.Builder("$CONTENT/$profile").addSource(content.sourceUri).build())
        harness.playToReady(player)
        return player
    }

    private companion object {
        const val CONTENT = "films/leanback"

        /** Longer than any ceiling either profile decides, so the content's end never stops a fill. */
        const val LONG_CONTENT_MS = 300_000L

        const val LARGE_HEAP_MB = 2_048

        /** One chunk, as `SelectionPaces` allows for one: the overshoot a ceiling permits. */
        const val CHUNK_ALLOWANCE_MS = SelectionPaces.SEGMENT_ALLOWANCE_MS

        /** Two minutes of media on a link several times the ladder's top rung: well inside this. */
        const val FILL_BOUND_MS = 120_000L
    }
}
