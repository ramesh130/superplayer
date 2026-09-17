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
 * [WidevineConfig.permittedSecurityLevels]: one server's operator permits `L3` and one has said
 * nothing, the device is identical in both, and the two must end differently. Either half alone would
 * pass for the wrong reason — a client that downgraded always, and a client that refused always, each
 * pass one of them.
 *
 * The permission is configuration rather than an exchange because #223 withdrew the wire format #208
 * built for it; `WidevineConfig.permittedSecurityLevels` argues why that is still the server's
 * permission and not the app's. What that removed from these tests is worth noticing:
 * [aPermittedDowngradeCostsNoExtraRoundTrip] and [aRefusedSessionAsksForNothingAtAll] now count one
 * licence request and none, where the exchange made them two and one.
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

        val player = play(permits = setOf(WidevineConfig.SECURITY_LEVEL_L3))

        // It played, and it played at the level the *server's operator* named — not at the one the device
        // reports, which it cannot honour, and not at one the client picked.
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.deliveredSecurityLevel).isEqualTo(WidevineConfig.SECURITY_LEVEL_L3)
    }

    @Test
    fun aPermittedDowngradeCostsNoExtraRoundTrip() {
        declareADeviceThatCannotHonourL1()

        val player = play(permits = setOf(WidevineConfig.SECURITY_LEVEL_L3))

        // Exactly one request at the licence server: the key request. A permission that is
        // configuration costs nothing on the wire, which is the plainest statement of what #223
        // bought — under #208's exchange this count was two, and the extra one was a `GET` most real
        // licence endpoints answer 405.
        val licences = harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }
        assertThat(licences.map { it.uri }).containsExactly(FakeLicenceServer.LICENCE_URI)
        // And the licence itself was still acquired: the downgrade is a change of level, not a
        // substitute for an entitlement.
    }

    @Test
    fun aServerThatPermitsNothingEndsTheSessionTypedRatherThanDowngradingIt() {
        declareADeviceThatCannotHonourL1()
        // And no permitted levels, which is what an app whose licence provider never told it about a
        // lower level configures — that is to say, most apps. Empty is a refusal; see
        // `WidevineConfig.permittedSecurityLevels`.

        val player = playToFailure()

        val error = player.playerError
        assertThat(error).isNotNull()
        // Typed, with the evidence the module found rather than a code standing in for it.
        val refusal = causeChainOf(error!!).filterIsInstance<SecurityDowngradeRefusedException>().firstOrNull()
        assertThat(refusal).isNotNull()
        assertThat(refusal!!.deviceSecurityLevel).isEqualTo(WidevineConfig.SECURITY_LEVEL_L1)
        assertThat(refusal.refusedLevel).isEqualTo(WidevineConfig.SECURITY_LEVEL_L3)
        assertThat(refusal.permittedLevels).isEmpty()
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
    fun aRefusedSessionAsksForNothingAtAll() {
        declareADeviceThatCannotHonourL1()

        val player = playToFailure()

        // The answer was known before the player was built, so nothing reaches the licence server:
        // no key request, and since #223 no permission question either. A refusal that spent a
        // request would put it on `RetryPolicy.licence`'s budget and in the CDN's log for nothing.
        val licences = harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }
        assertThat(licences).isEmpty()
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
        // One licence request, the key request, exactly as an unprobed player makes.
        val licences = harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }
        assertThat(licences).hasSize(1)
    }

    @Test
    fun aPermittingServerDoesNotDowngradeADeviceThatNeverAskedToBe() {
        // The other direction of "no silent downgrade": a server that would permit `L3` changes
        // nothing about a device that can honour `L1`. Permission is not an instruction.
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)
        DeviceStatement.declareWidevine(SecurityLevel.L1)

        val player = play(permits = setOf(WidevineConfig.SECURITY_LEVEL_L3))

        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.deliveredSecurityLevel).isNull()
    }

    @Test
    fun aServerThatPermitsSomeOtherLevelIsStillARefusalForThisOne() {
        // The case the wire format could not express and configuration can: a server whose operator
        // published a permission that does not cover what this device needs. It is not silence and
        // it is not consent, and a client that read "permits something" as "permits this" would be
        // downgrading on its own authority — the exact failure ADR-0012 rule 11 forbids.
        declareADeviceThatCannotHonourL1()

        val player = playToFailure(permits = setOf(WidevineConfig.SECURITY_LEVEL_L1))

        val refusal = causeChainOf(player.playerError!!)
            .filterIsInstance<SecurityDowngradeRefusedException>()
            .firstOrNull()
        assertThat(refusal).isNotNull()
        assertThat(refusal!!.refusedLevel).isEqualTo(WidevineConfig.SECURITY_LEVEL_L3)
        // The evidence keeps what *was* permitted, because "permits nothing" and "permits the wrong
        // thing" are one outcome and two quite different bug reports.
        assertThat(refusal.permittedLevels).containsExactly(WidevineConfig.SECURITY_LEVEL_L1)
        assertThat(player.deliveredSecurityLevel).isNull()
    }

    @Test
    fun aLevelNoWidevineDeviceReportsIsRejectedWhereItIsWrittenRatherThanIgnored() {
        // A permission that is never going to match is a misconfiguration, and the failure mode this
        // whole type exists to prevent arriving by spelling: configured, plausible-looking, and
        // silently inert forever. It is refused at the point it is written instead.
        val thrown = runCatching { WidevineConfig(FakeLicenceServer.LICENCE_URI, setOf("L2")) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
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

    private fun play(permits: Set<String> = emptySet()): SuperPlayer =
        build(permits).also { harness.playToReady(it) }

    private fun playToFailure(permits: Set<String> = emptySet()): SuperPlayer =
        build(permits).also { harness.playToFailure(it) }

    /** What a consumer writes: protection, and the resilience both halves of ADR-0012 rule 2 need. */
    private fun build(permits: Set<String> = emptySet()): SuperPlayer {
        val content = TestContent.protectedDash()
        val player = harness.buildPlayer(
            content = content,
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI, permittedSecurityLevels = permits)),
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
