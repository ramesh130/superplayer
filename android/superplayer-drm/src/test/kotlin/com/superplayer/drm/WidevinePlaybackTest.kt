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

package com.superplayer.drm

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackDrm
import com.superplayer.core.SuperPlayer
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tracer bullet of Phase 6 (#204): a protected stream plays through a `SuperPlayer`, with the
 * licence acquired from a server the test can see.
 *
 * Everything here is driven through the public API — `SuperPlayer.Builder.setDrm(Drm.widevine(…))`,
 * exactly as a consumer writes it — and asserted at the transport, because the claims are claims
 * about traffic. That the content played is the weakest of them and the easiest to get for the wrong
 * reason: a session manager that was never installed, a provider that answered `DRM_UNSUPPORTED`, or
 * a licence exchange that happened in memory below everything this module can observe would each
 * produce a stream that plays. So what is counted is the licence *request*, at the address the
 * config named, over the one chain — and its absence on content that declares no protection.
 *
 * What stands in for the device is `FakeExoMediaDrm`, through core's `exoMediaDrm` slot, because
 * Robolectric ships no `ShadowMediaDrm` and a real `MediaDrm` cannot be constructed under `check`.
 * `docs/testing.md`'s *A Widevine device and a licence server* is the argument, including the one
 * thing this cannot reach: the samples are not encrypted, so what is exercised is the licence round
 * trip and the session gate in front of the renderer, not decryption.
 */
@RunWith(AndroidJUnit4::class)
class WidevinePlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aProtectedStreamOfEitherProtocolPlaysThroughASuperPlayer() {
        // Both protocols, because the two declare protection in entirely different vocabularies — an
        // `EXT-X-KEY` tag against a `ContentProtection` descriptor — and a session manager reached
        // through the DRM slot is asked for a session by whichever `MediaSource` the factory built.
        // One protocol passing and the other not would be a phase that could only ship half of F5.
        listOf(TestContent.protectedHls(), TestContent.protectedDash()).forEach { content ->
            val player = play(content)

            assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
            assertThat(player.playerError).isNull()
            harness.release(player)
        }
    }

    @Test
    fun theLicenceIsAskedForAtTheAddressTheConfigNamed() {
        // The claim the whole slot exists for. A `DrmSessionManagerProvider` that reached the media
        // source factory but composed no request, or composed one that never left the player, would
        // pass the test above and fail this one.
        val player = play(TestContent.protectedDash())

        val licences = harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }
        assertThat(licences.map { it.uri }).contains(FakeLicenceServer.LICENCE_URI)
    }

    @Test
    fun aLicenceTravelsTheOneChainAndIsRefusableLikeAnyOtherTransfer() {
        // That the licence load is a transfer of this player's own chain rather than an exchange
        // Media3 performs beside it: a fault addressed at `ResourceKind.LICENCE` — which is a fault
        // in the chain, injected below every layer core composes — is enough to end the session,
        // while the media it was never about arrives perfectly. It is also the half of ADR-0012 rule
        // 11 this ticket can state: a player whose licence was refused fails rather than quietly
        // rendering what it holds no keys for.
        val player = play(
            TestContent.protectedDash(),
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_FORBIDDEN, kind = ResourceKind.LICENCE)
                .build(),
            toReady = false,
        )

        assertThat(player.playerError).isNotNull()
        // Both halves of "addressed at the licence and nothing else": a licence request really was
        // made and met the fault, and the media it was never about was served while that happened.
        val kinds = harness.networkRequests(player).map { it.kind }
        assertThat(kinds).contains(ResourceKind.LICENCE)
        assertThat(kinds).contains(ResourceKind.MANIFEST)
    }

    @Test
    fun aPlayerWithDrmAsksForNoLicenceForContentThatDeclaresNoProtection() {
        // ADR-0012 rule 1's other edge: protection is declared per *player*, and what decides whether
        // a session is opened is still the content. A provider consulted per item that answered with
        // a session for every item would pass every test above.
        val player = play(TestContent.dash())

        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(harness.networkRequests(player).map { it.kind }).doesNotContain(ResourceKind.LICENCE)
    }

    @Test
    fun aPlayerBuiltWithoutSetDrmPlaysUnprotectedContentAndAcquiresNothing() {
        // ADR-0012 rule 13 from this side of the boundary: the module on the classpath changes
        // nothing about a player that did not ask for it. The count that no *provider* was set is
        // core's, in `SuperPlayerDrmSeamTest`, because only core can see the slot; what is counted
        // here is the traffic.
        val player = harness.buildPlayer(content = TestContent.dash())
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(TestContent.dash().sourceUri).build())
        harness.playToReady(player)

        assertThat(harness.networkRequests(player).map { it.kind }).doesNotContain(ResourceKind.LICENCE)
    }

    /** A player built the way a consumer builds one, playing [content] to the state it should reach. */
    private fun play(
        content: TestContent,
        faults: FaultScript = FaultScript.NONE,
        toReady: Boolean = true,
    ): SuperPlayer {
        val player = harness.buildPlayer(content = content, faults = faults, drm = widevine())
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        if (toReady) harness.playToReady(player) else harness.playToFailure(player)
        return player
    }

    /** What a consumer writes, against the harness's licence server rather than a real one. */
    private fun widevine(): PlaybackDrm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI))

    private companion object {
        const val CONTENT_ID = "series/expanse/s01e01"
    }
}
