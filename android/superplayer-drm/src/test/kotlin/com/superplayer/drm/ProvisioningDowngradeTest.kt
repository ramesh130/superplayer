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
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.resilience.FailureClass
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.SecurityLevel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The second of `PRD.md` §3.2's three ways L1 becomes unusable: **the provisioning service will not
 * certify this device at L1** (#225).
 *
 * The device here is not [SecurityLevelTest]'s. That one has lost before it asks for anything — it
 * reports `L1` and declares no secure decoder, which a player can see before spending a licence, and
 * its session graph is therefore opened at the lower level to begin with. This device declares a
 * secure decoder and looks perfectly capable, and the thing that is wrong with it is only visible
 * once `DefaultDrmSessionManager` has asked the provisioning service and been refused — after the
 * session graph is fixed. So the remedy cannot be a different graph; it has to be the graph built
 * again (`WidevineDrm`'s re-open, and ADR-0012 rule 11's #225 addendum for why that is still not a
 * seventh rung).
 *
 * The tests that matter are the one that falls and the three that must not:
 *
 * - a refused device whose server permits `L3` plays, at `L3`, and says so;
 * - the same device with nothing permitted ends exactly where `DrmFailureTest` says it ends today —
 *   the control that separates "fall to what the operator permitted" from "downgrade on any refusal";
 * - a device the service *does* certify negotiates nothing even where `L3` is permitted, which is
 *   [SecurityLevelTest.aPermittingServerDoesNotDowngradeADeviceThatNeverAskedToBe] asked of this
 *   path: a permission is not an instruction;
 * - and a protection failure that is not about the level — a licence the server would not issue —
 *   lowers nothing either, which is the control that separates this from "downgrade on any DRM
 *   failure" and is why the trigger is a question the classifier answers rather than a category.
 *
 * **A secure surface that cannot be allocated** — §3.2's third way, and #226's — is still not
 * reachable here: it needs a `MediaCrypto` and a protected buffer queue, neither of which exists
 * under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class ProvisioningDowngradeTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aDeviceRefusedAtL1PlaysAtTheLevelTheOperatorPermitted() {
        aDeviceRefusedByTheProvisioningService()

        val player = harness.buildDowngradePlayer(permits = setOf(WidevineConfig.SECURITY_LEVEL_L3))
        harness.playToReady(player)

        // It played, and the failure that got it there is the consumer's business no more than a
        // rung 4 fallback is (ADR-0011 rule 10): one viewing rescued is one session, not a session
        // plus an error.
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.deliveredSecurityLevel).isEqualTo(WidevineConfig.SECURITY_LEVEL_L3)
    }

    @Test
    fun theLevelItFellToIsWhatTelemetryReports() {
        aDeviceRefusedByTheProvisioningService()

        val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
        val player = harness.buildDowngradePlayer(
            permits = setOf(WidevineConfig.SECURITY_LEVEL_L3),
            telemetry = QoeCollector { events += it },
        )
        harness.playToReady(player)
        player.release()

        // ADR-0012 rule 11's last sentence, for a level settled in the middle of a session rather
        // than before it: `SessionEnded` is read at the end, which is late enough for a re-open to
        // have happened and is why nothing about this is a new event.
        val ended = endedEventOf(events)
        assertThat(ended.securityLevel).isEqualTo(WidevineConfig.SECURITY_LEVEL_L3)
    }

    @Test
    fun aDeviceRefusedAtL1WithNothingPermittedEndsExactlyWhereItEndsToday() {
        // The control, and the acceptance criterion that keeps this from being "downgrade on any DRM
        // failure": the identical device, the identical refusal, and an app whose licence provider
        // published nothing. `DrmFailureTest` pins the same ending for the same device, and neither
        // moves.
        aDeviceRefusedByTheProvisioningService()

        val player = harness.buildDowngradePlayer()
        harness.playToFailure(player)

        val typed = typedErrorOf(player)
        assertThat(typed.causeClass).isEqualTo(FailureClass.Drm.Unsupported.stableName)
        assertThat(typed.isRetryable).isFalse()
        // Nothing was delivered, rather than something weaker.
        assertThat(player.deliveredSecurityLevel).isNull()
    }

    @Test
    fun aDeviceTheServiceCertifiesFallsToNothingEvenWhereTheLowerLevelIsPermitted() {
        // The second control: the permission is in force and must change nothing, because nothing
        // failed. A client that re-opened on the permission alone would downgrade every device whose
        // operator was generous.
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)
        DeviceStatement.declareWidevine(SecurityLevel.L1, provisioningRequired = true)

        val player = harness.buildDowngradePlayer(permits = setOf(WidevineConfig.SECURITY_LEVEL_L3))
        harness.playToReady(player)

        assertThat(player.playerError).isNull()
        assertThat(player.deliveredSecurityLevel).isNull()
        // One certification and one licence, which is what an ordinary provisioned session costs
        // (`ProvisioningTest`): no second session graph was built, so nothing was asked twice.
        assertThat(attemptsAt(player, FakeLicenceServer.PROVISION_URI)).isEqualTo(1)
        assertThat(attemptsAt(player, FakeLicenceServer.LICENCE_URI)).isEqualTo(1)
    }

    @Test
    fun aProtectionFailureALowerLevelCannotExplainIsNotAnExcuseToLowerOne() {
        // The third control, and the narrowest one: a *licence* the server would not issue, on the
        // same L1 device with `L3` permitted. Nothing about that failure is a device the service
        // would not certify — the level asked for was never in question — so the permission must
        // stay unused. `FailureClass.Drm.LicenceAcquisition.lowerSecurityLevelMayHelp` is where that
        // is decided, and it is decided there rather than here because the coarse "some DRM failure
        // happened" would have downgraded this session (ADR-0011 rule 1).
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)
        DeviceStatement.declareWidevine(SecurityLevel.L1)

        val player = harness.buildDowngradePlayer(
            permits = setOf(WidevineConfig.SECURITY_LEVEL_L3),
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, kind = ResourceKind.LICENCE)
                .build(),
        )
        harness.playToFailure(player)

        assertThat(typedErrorOf(player).causeClass).isEqualTo(FailureClass.Drm.LicenceAcquisition.stableName)
        // Nothing was lowered, so nothing was delivered at a reduced level.
        assertThat(player.deliveredSecurityLevel).isNull()
    }

    @Test
    fun aRefusalTheLowerLevelDoesNotCureIsBoundedRatherThanRepeated() {
        // The bound, forced through the one refusal a lower level cannot cure: a provisioning
        // *transfer* the service never answers, which fails again at `L3` exactly as it failed at
        // `L1`. `SuperPlayer.MAX_PROTECTION_REOPENS` is what stops the second failure producing a
        // third session graph, and without it this test would not fail — it would never finish.
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)
        DeviceStatement.declareWidevine(SecurityLevel.L1, provisioningRequired = true)

        val player = harness.buildDowngradePlayer(
            permits = setOf(WidevineConfig.SECURITY_LEVEL_L3),
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, kind = ResourceKind.LICENCE, index = 0)
                .build(),
        )
        harness.playToFailure(player)

        // It ended, typed, rather than looping. The level it was lowered to is still reported,
        // because it is still what the one session graph that got as far as asking was opened at.
        assertThat(typedErrorOf(player).causeClass).isEqualTo(FailureClass.Drm.Provisioning.stableName)
        assertThat(player.deliveredSecurityLevel).isEqualTo(WidevineConfig.SECURITY_LEVEL_L3)
    }

    /**
     * A device that looks capable and is not: Widevine reporting `L1`, a secure decoder declared, and
     * a provisioning service that refuses to certify it at that level.
     *
     * The secure decoder is the half that makes this #225's case and not #208's — with it missing,
     * `SecurityLevelLadder` would have lowered the graph before the player was ever built and the
     * test would pass without a re-open having happened.
     */
    private fun aDeviceRefusedByTheProvisioningService() {
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)
        DeviceStatement.declareWidevineProvisioningFailure(SecurityLevel.L1)
    }

    /** How many times [uri] was asked for, repeats included — which is what a retry is. */
    private fun attemptsAt(player: SuperPlayer, uri: String): Int =
        harness.networkRequests(player).count { it.uri == uri }
}
