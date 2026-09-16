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

import android.media.MediaFormat
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.core.SecurityDowngradeRefusedException
import com.superplayer.core.SecurityLevelNegotiation
import com.superplayer.core.SuperPlayer
import com.superplayer.resilience.ErrorClassifier
import com.superplayer.resilience.FailureClass
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.SecurityLevel
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `PRD.md` §3.2's security-level ladder, and the rule that is louder than it: **policy is
 * server-driven, and the client never unilaterally downgrades protected content** (ADR-0012 rule 11,
 * #208).
 *
 * The device every test here states is the one the ladder exists for: a handset whose Widevine
 * implementation reports `L1` — so every licence it is issued requires a decoder on protected memory
 * — and whose codec table declares an ordinary decoder and no secure one. That is a *stated* device
 * and not a contrived one; it is what a phone whose secure path has been fused off looks like, and it
 * is the commonest way L1 becomes unusable while L3 is fine.
 *
 * The pair that makes this a test of the rule rather than of a behaviour is
 * [FakeLicenceServer.permitSecurityLevel]: one server permits `L3` and one says nothing, the device
 * is identical in both, and the two must end differently. Either half alone would pass for the wrong
 * reason — a client that downgraded always, and a client that refused always, each pass one of them.
 *
 * ## What this cannot reach, and why it is said here rather than implied
 *
 * `PRD.md` §3.2 names three ways L1 becomes unusable. This covers the one a player can see before
 * spending a licence: no secure decoder. **A failed L1 provisioning** surfaces from inside
 * `DefaultDrmSessionManager` after the session graph is fixed, and since #207 it ends at rung 6
 * (`ProvisioningTest`). **A secure surface that cannot be allocated** needs a `MediaCrypto` and a
 * protected buffer queue, neither of which exists under Robolectric — the same limit
 * `DrmFailureTest` and `SuperPlayerDecoderRecreationTest` state about their own subjects.
 *
 * Each test states its device in its own method rather than in a `@Before`, because the platform
 * caches the codec list on first read and a harness that stated one device for the class could not
 * also state the control's.
 */
@RunWith(AndroidJUnit4::class)
class SecurityLevelTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aServerThatPermitsTheDowngradeGetsASessionAtTheLowerLevel() {
        declareADeviceThatCannotHonourL1()
        FakeLicenceServer.permitSecurityLevel(SecurityLevel.L3)

        val player = play()

        // It played, and it played at the level the *server* named — not at the one the device
        // reports, which it cannot honour, and not at one the client picked.
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.deliveredSecurityLevel).isEqualTo(SecurityLevelNegotiation.LEVEL_L3)
    }

    @Test
    fun theDowngradeIsAskedForBeforeAnyLicenceIsRequested() {
        declareADeviceThatCannotHonourL1()
        FakeLicenceServer.permitSecurityLevel(SecurityLevel.L3)

        val player = play()

        // The permission question is a transfer of this player's own chain, at the licence server's
        // address and stamped as a licence load — which is what carries the app's credential onto it
        // and what makes it refusable, countable and delayable like every other (ADR-0012 rule 2).
        // A client that decided for itself would show no such request at all.
        val licences = harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }
        assertThat(licences.map { it.uri }).contains(FakeLicenceServer.LICENCE_URI)
        // And the licence itself was still acquired: the downgrade is a change of level, not a
        // substitute for an entitlement.
        assertThat(licences.size).isAtLeast(2)
    }

    @Test
    fun aServerThatPermitsNothingEndsTheSessionTypedRatherThanDowngradingIt() {
        declareADeviceThatCannotHonourL1()
        // And no `permitSecurityLevel`, which is what a licence server that has never heard of the
        // exchange does. Silence is a refusal; see `SecurityLevelNegotiation`.

        val player = playToFailure()

        val error = player.playerError
        assertThat(error).isNotNull()
        // Typed, with the evidence the module found rather than a code standing in for it.
        val refusal = causeChainOf(error!!).filterIsInstance<SecurityDowngradeRefusedException>().firstOrNull()
        assertThat(refusal).isNotNull()
        assertThat(refusal!!.deviceSecurityLevel).isEqualTo(SecurityLevelNegotiation.LEVEL_L1)
        assertThat(refusal.refusedLevel).isEqualTo(SecurityLevelNegotiation.LEVEL_L3)
        assertThat(refusal.permittedLevel).isNull()
        // No silent downgrade: nothing was delivered rather than something weaker.
        assertThat(player.deliveredSecurityLevel).isNull()
    }

    @Test
    fun aRefusalIsClassifiedAsItsOwnFailureWithItsOwnMessageKey() {
        declareADeviceThatCannotHonourL1()

        val player = playToFailure()

        // The issue's own words: it must not be confused with a licence that could not be fetched.
        // `ErrorClassifier` is where that distinction is made once, and the message key is what an
        // app reads — so both are asserted, because a class with the wrong key would still be a
        // viewer told the wrong thing.
        val classification = ErrorClassifier.classify(player.playerError!!)
        assertThat(classification).isEqualTo(FailureClass.Drm.DowngradeRefused)
        assertThat(classification.userMessageKey)
            .isEqualTo(FailureClass.PROTECTION_UNAVAILABLE_MESSAGE_KEY)
        assertThat(classification.userMessageKey).isNotEqualTo(FailureClass.DRM_MESSAGE_KEY)
    }

    @Test
    fun aRefusedSessionAsksForNoLicenceAtAll() {
        declareADeviceThatCannotHonourL1()

        val player = playToFailure()

        // The answer is already known, so spending a licence request to arrive at it would put a
        // refusal on `RetryPolicy.licence`'s budget and in the CDN's log for nothing. Exactly one
        // request goes to the licence server — the permission question — and no key request follows.
        val licences = harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }
        assertThat(licences).hasSize(1)
    }

    @Test
    fun aDeviceThatCanHonourItsOwnLevelNegotiatesNothing() {
        // The control, and the half that stops "refuse everything" and "ask always" from passing.
        // The same stated `L1` device, with the secure decoder it is missing above: the ladder must
        // not engage, no permission must be asked for, and the session must be an ordinary one.
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)
        DeviceStatement.declareWidevine(SecurityLevel.L1)

        val player = play()

        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        // Null means "nothing was negotiated", which is the ordinary case and is not the same answer
        // as `L1`: this player never asked, so it has nothing to report.
        assertThat(player.deliveredSecurityLevel).isNull()
        // One licence request — the key request — and no permission question beside it.
        val licences = harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }
        assertThat(licences).hasSize(1)
    }

    @Test
    fun aPermittingServerDoesNotDowngradeADeviceThatNeverAskedToBe() {
        // The other direction of "no silent downgrade": a server that would permit `L3` changes
        // nothing about a device that can honour `L1`. Permission is not an instruction.
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)
        DeviceStatement.declareWidevine(SecurityLevel.L1)
        FakeLicenceServer.permitSecurityLevel(SecurityLevel.L3)

        val player = play()

        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.deliveredSecurityLevel).isNull()
    }

    /**
     * The device the ladder exists for: Widevine reporting `L1`, and a codec table that declares an
     * ordinary H.264 decoder and no secure one.
     *
     * The ordinary decoder is what makes the absence of a secure one *known* rather than *unknown* —
     * core reads an empty codec table as "this device said nothing" and refuses nothing over it,
     * which is the direction `DeviceConstraints` reads every unknown in.
     */
    private fun declareADeviceThatCannotHonourL1() {
        DeviceStatement.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        DeviceStatement.declareWidevine(SecurityLevel.L1)
    }

    private fun play(): SuperPlayer = build().also { harness.playToReady(it) }

    private fun playToFailure(): SuperPlayer = build().also { harness.playToFailure(it) }

    /** What a consumer writes: protection, and the resilience both halves of ADR-0012 rule 2 need. */
    private fun build(): SuperPlayer {
        val content = TestContent.protectedDash()
        val player = harness.buildPlayer(
            content = content,
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)),
            resilience = Resilience.standard(),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        return player
    }

    /** [error] and its causes, nearest first — the walk `ErrorClassifier` does, done here by hand. */
    private fun causeChainOf(error: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size < MAX_CAUSE_DEPTH) {
            chain += current
            current = current.cause
        }
        return chain
    }

    private companion object {
        const val CONTENT_ID = "film/the-third-man"
        const val MAX_CAUSE_DEPTH = 32
    }
}
