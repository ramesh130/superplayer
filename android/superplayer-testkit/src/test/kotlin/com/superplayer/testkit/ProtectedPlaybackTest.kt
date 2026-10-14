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

package com.superplayer.testkit

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Widevine device and a licence server this suite can state, driven end to end (#203).
 *
 * The player here is a **stock** `ExoPlayer`, and that is the point rather than a limitation: no
 * SuperPlayer DRM code exists yet (#204), so everything this asserts is a claim about the harness.
 * What has to be true before any of Phase 6 can be tested at all is that a protected stream makes a
 * real `DefaultDrmSessionManager` open a session, compose a key request, carry it over this module's
 * own transport to a server that decides an entitlement, and play. Each of those is a separate way
 * for the seam to be silently inert — a manifest whose protection nothing parses, a licence exchange
 * that happens in memory below the injector, a server that allows everything — so each is asserted
 * rather than inferred from the fact that the content played.
 */
@RunWith(AndroidJUnit4::class)
class ProtectedPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aStockPlayerPlaysAProtectedStreamOfEitherProtocol() {
        // Both protocols, because the two declare the protection in entirely different vocabularies
        // — an `EXT-X-KEY` tag against a `ContentProtection` descriptor — and hand the session the
        // same initialization data. A stream that parsed under one and not the other would be a
        // harness that could only test half of Phase 6.
        listOf(TestContent.protectedHls(), TestContent.protectedDash()).forEach { content ->
            val player = harness.buildStockPlayer(content = content)
            player.setMediaItem(MediaItem.fromUri(content.sourceUri))

            harness.playToReady(player)

            assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
            assertThat(player.playerError).isNull()
            harness.release(player)
        }
    }

    @Test
    fun theLicenceIsAskedForOverTheHarnessTransport() {
        // The claim that decides whether anything later in this phase is testable. A licence request
        // that did not reach this module's transport would be invisible to `networkRequests`, to a
        // `FaultScript`, and to the clock wait — so #205's "licence loads travel the one chain" and
        // #206's typed errors would both have nothing to observe.
        val content = TestContent.protectedDash()
        val player = harness.buildStockPlayer(content = content)
        player.setMediaItem(MediaItem.fromUri(content.sourceUri))

        harness.playToReady(player)

        val licenceRequests = harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }
        assertThat(licenceRequests).isNotEmpty()
        assertThat(licenceRequests.map { it.uri }).contains(FakeLicenceServer.LICENCE_URI)
    }

    @Test
    fun aRefusedLicenceEndsTheSessionWhileTheMediaArrivesPerfectly() {
        // The shape of every DRM failure worth having a taxonomy for: nothing is wrong with the
        // content or the network, and the session still cannot start. Addressed at the kind rather
        // than at a URL, which is what `ResourceKind.LICENCE` exists to make possible.
        val content = TestContent.protectedDash()
        val player = harness.buildStockPlayer(
            content = content,
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_FORBIDDEN, kind = ResourceKind.LICENCE)
                .build(),
        )
        player.setMediaItem(MediaItem.fromUri(content.sourceUri))

        harness.playToFailure(player)

        assertThat(player.playerError).isNotNull()
        // The manifest and the first segment were served while the licence was being refused, which
        // is the half of the claim that says the fault was addressed at the licence and nothing else.
        assertThat(harness.networkRequests(player).map { it.kind }).contains(ResourceKind.MANIFEST)
    }

    @Test
    fun aLicenceRefusalCanRelentOnALaterAttempt() {
        // `firstAttempts` on a licence request, which is the machinery Phase 5 built and this ticket
        // only had to give an address to: a licence server that answers 500 once and then serves is
        // the commonest thing a retry budget exists for, and until now it could not be written down.
        val content = TestContent.protectedDash()
        val player = harness.buildStockPlayer(
            content = content,
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, kind = ResourceKind.LICENCE, firstAttempts = 1)
                .build(),
        )
        player.setMediaItem(MediaItem.fromUri(content.sourceUri))

        harness.playToReady(player)

        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        // Twice at the same address: the refusal and the retry that was served. One would mean the
        // fault never fired, three would mean it never relented.
        val licenceRequests = harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }
        assertThat(licenceRequests).hasSize(2)
    }

    @Test
    fun aDeviceThatNeedsProvisioningIsProvisionedFirstAndThenPlays() {
        // The claim `ResourceKind.LICENCE`'s KDoc makes about indices: provisioning and licence
        // acquisition are two resources of one kind, asked for in that order, so a later ticket can
        // address either one alone. A device already provisioned asks for the licence and nothing
        // else, which `theLicenceIsAskedForOverTheHarnessTransport` covers.
        DeviceStatement.declareWidevine(SecurityLevel.L1, provisioningRequired = true)
        val content = TestContent.protectedDash()
        val player = harness.buildStockPlayer(content = content)
        player.setMediaItem(MediaItem.fromUri(content.sourceUri))

        harness.playToReady(player)

        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        val licenceUris = harness.networkRequests(player)
            .filter { it.kind == ResourceKind.LICENCE }
            .map { it.uri.substringBefore('?') }
        assertThat(licenceUris).containsExactly(FakeLicenceServer.PROVISION_URI, FakeLicenceServer.LICENCE_URI).inOrder()
    }

    @Test
    fun aDeviceWhoseProvisioningIsRefusedCannotPlay() {
        // The device's own refusal rather than the transfer's, which `DeviceStatement` draws the
        // distinction between: the provisioning request reaches the service and is turned down. It
        // is the case ADR-0012 rule 11's security-level downgrade exists for, and #208 is where a
        // remedy for it is decided; what is owed here is only that a test can say it.
        DeviceStatement.declareWidevineProvisioningFailure(SecurityLevel.L1)
        val content = TestContent.protectedDash()
        val player = harness.buildStockPlayer(content = content)
        player.setMediaItem(MediaItem.fromUri(content.sourceUri))

        harness.playToFailure(player)

        assertThat(player.playerError).isNotNull()
        // And the provisioning request really went out, which is the other half of `ResourceKind`'s
        // claim that a provisioning request is a resource of that kind with an address of its own.
        assertThat(harness.networkRequests(player).map { it.uri }).contains(FakeLicenceServer.PROVISION_URI)
    }

    @Test
    fun anUnprotectedStreamOpensNoSessionAndAsksForNoLicence() {
        // The claim that costs nothing to state and is easy to break: the DRM seam is reached only by
        // content that declares protection. A session manager installed unconditionally would still
        // play this stream and would still pass every other test in this file.
        val content = TestContent.dash()
        val player = harness.buildStockPlayer(content = content)
        player.setMediaItem(MediaItem.fromUri(content.sourceUri))

        harness.playToReady(player)

        assertThat(harness.networkRequests(player).map { it.kind }).doesNotContain(ResourceKind.LICENCE)
    }
}
