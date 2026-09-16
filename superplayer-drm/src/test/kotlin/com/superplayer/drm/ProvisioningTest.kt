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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.BufferPolicy
import com.superplayer.core.FailureCategory
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.core.SuperPlayer
import com.superplayer.core.SuperPlayerError
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.resilience.FailureClass
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.SecurityLevel
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A device that is not provisioned gets provisioned rather than ending a session (#207).
 *
 * Provisioning is the archetype of a failure that is transient at the server and fatal in a naive
 * client: the device asks a service it trusts to certify it, the service is momentarily unwilling,
 * and a client that treats that as "this content cannot be played" has thrown away a session a second
 * ask would have had. `PRD.md` §3.2 names it because it is common on older and unusual handsets, where
 * it is also the one DRM failure a viewer can do nothing about and an app can.
 *
 * **Almost none of what this file asserts is new machinery, and that is the finding rather than a
 * shortcut.** Media3 posts a provisioning request through the same `LoadErrorHandlingPolicy` as every
 * other DRM load, #205 made that policy the player's own, and #206 gave error code 6002 the leaf
 * [FailureClass.Drm.Provisioning] — retryable, ceiling rung 1. So the recovery, the budget, the
 * jitter and the typed ending were all reachable before this ticket. What was *not* reachable was any
 * of it through a real `SuperPlayer`: the harness's fake device named a provisioning address nothing
 * in the harness answered, so a provisioning round trip through `superplayer-drm` left the test
 * entirely and could be neither counted nor refused. Closing that is what #207 did, in
 * `WidevineDevice` and `FakeLicenceServer`, and every claim below is one that could not be written
 * down before.
 *
 * ## Where the boundary of a retry is
 *
 * The one thing here that is a decision: a provisioning round trip and the licence request that
 * follows it each spend the licence budget **separately**, because a budget in this library bounds a
 * *round trip* and not a session — a manifest retry has never shortened a segment's, and Media3's own
 * error count is kept per request rather than per session. So a device that is provisioned on the
 * third ask still gets a full budget for the licence it was being provisioned in order to fetch, and
 * a session that spent one budget entirely is never charged for it twice.
 * [aProvisioningRepairDoesNotShortenTheLicenceRequestItWasFor] is that written down, and it is the
 * claim to revisit first if a session ever seems to ask too many times.
 */
@RunWith(AndroidJUnit4::class)
class ProvisioningTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aDeviceThatNeedsProvisioningIsCertifiedFirstAndThenAsksForItsLicence() {
        // The shape everything below rests on, and the thing that was unreachable through a real
        // player until this ticket: two round trips to the licence server, in that order, from a
        // `SuperPlayer` built the way a consumer builds one. A device already provisioned asks once
        // and `LicenceLoadTest` covers it.
        DeviceStatement.declareWidevine(SecurityLevel.L1, provisioningRequired = true)
        val player = play()

        harness.playToReady(player)

        assertThat(player.playerError).isNull()
        assertThat(licenceServerUris(player))
            .containsExactly(FakeLicenceServer.PROVISION_URI, FakeLicenceServer.LICENCE_URI)
            .inOrder()
    }

    @Test
    fun aProvisioningServiceThatRelentsCostsTheSessionNothingButTime() {
        // The ticket's first criterion. Two refusals and a third ask that certified the device, out
        // of a licence budget of three — and then an ordinary session, with no error delivered and
        // nothing for a consumer to have handled. A client that gave up at the first refusal would
        // have ended a session that was always going to work.
        DeviceStatement.declareWidevine(SecurityLevel.L1, provisioningRequired = true)
        val player = play(
            faults = provisioningFails(firstAttempts = 2),
            policy = budgets(licence = RetryBudget(maxRetries = 3, initialBackoffMs = 50, maxBackoffMs = 200)),
        )

        harness.playToReady(player)

        assertThat(player.playerError).isNull()
        // Three asks at the provisioning address, then the licence: the retries happened, they were
        // retries of the *provisioning* request, and what they bought was the licence request that
        // could not have been made before them.
        assertThat(attemptsAt(player, FakeLicenceServer.PROVISION_URI)).isEqualTo(3)
        assertThat(attemptsAt(player, FakeLicenceServer.LICENCE_URI)).isEqualTo(1)
    }

    @Test
    fun theProvisioningRetriesAreSpacedByTheBackoffRatherThanMadeAtOnce() {
        // ADR-0011 rule 12 through the one request the rule was hardest to reach: a provisioning
        // retry is a retry like any other, so it waits. Asserted as a *before and after* rather than
        // as an exact delay, because the wait is jittered by construction — `Backoff` draws uniformly
        // over [base/2, base], which `RetryBackoffTest` asserts the spread of — so the only honest
        // claim at this level is that the second ask had not happened while the shortest possible
        // backoff was still running, and had by the time the longest was over.
        DeviceStatement.declareWidevine(SecurityLevel.L1, provisioningRequired = true)
        val player = play(
            faults = provisioningFails(firstAttempts = 1),
            policy = budgets(licence = RetryBudget(maxRetries = 3, BACKOFF_MS, maxBackoffMs = BACKOFF_MS)),
        )
        player.prepare()
        player.play()

        harness.advanceTimeInStepsMs(player, BACKOFF_MS / 4)
        assertThat(attemptsAt(player, FakeLicenceServer.PROVISION_URI)).isEqualTo(1)

        harness.advanceUntil(player, "the device to be provisioned") {
            attemptsAt(player, FakeLicenceServer.PROVISION_URI) > 1
        }
        assertThat(attemptsAt(player, FakeLicenceServer.PROVISION_URI)).isEqualTo(2)
    }

    @Test
    fun theProvisioningRetriesAreBoundedByTheLicenceBudgetAndSpendNeitherOfTheOthers() {
        // The ticket's second criterion, and the reason `RetryPolicy.licence` is the budget rather
        // than the segment one: a service that never relents is asked exactly as often as the licence
        // budget allows and then stops. Two asks on a budget of one — the first and the one retry —
        // while the manifest budget standing at five is visibly unspent, which is what "one exhausted
        // budget cannot spend another's" means at the one address both could have been read from.
        DeviceStatement.declareWidevine(SecurityLevel.L1, provisioningRequired = true)
        val player = play(
            faults = provisioningFails(firstAttempts = null),
            policy = budgets(
                manifest = RetryBudget(maxRetries = 5, initialBackoffMs = 50, maxBackoffMs = 200),
                segment = RetryBudget(maxRetries = 5, initialBackoffMs = 50, maxBackoffMs = 200),
                licence = RetryBudget(maxRetries = 1, initialBackoffMs = 50, maxBackoffMs = 200),
            ),
        )

        harness.playToFailure(player)
        // Well past the point at which any further retry would have been made, so that "gave up" is
        // told from "had not got round to it yet".
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(attemptsAt(player, FakeLicenceServer.PROVISION_URI)).isEqualTo(2)
        // And no licence was ever asked for, which is the whole point of provisioning coming first:
        // an uncertified device has nothing to sign a key request with.
        assertThat(attemptsAt(player, FakeLicenceServer.LICENCE_URI)).isEqualTo(0)
        assertThat(attemptsAt(player, TestContent.protectedDash().sourceUri)).isEqualTo(1)
    }

    @Test
    fun aProvisioningFailureNothingRescuedIsNamedRatherThanLeftOpaque() {
        // The ticket's third criterion, and rung 6's path exactly as #206 built it. The class is the
        // interesting half: the failure arrives carrying *both* a DRM band of 6002 and core's
        // `LoadKind.LICENCE` stamp, because a provisioning request travels the licence transport —
        // and `ErrorClassifier` reads the stamp only where there is no band, which is the one guard
        // that stops a refused provisioning load being renamed as a licence acquisition. This is the
        // player-level proof of it; `ErrorClassifierTest` states the ordering directly.
        DeviceStatement.declareWidevine(SecurityLevel.L1, provisioningRequired = true)
        val player = play(
            faults = provisioningFails(firstAttempts = null),
            policy = budgets(licence = RetryBudget(maxRetries = 1, initialBackoffMs = 50, maxBackoffMs = 200)),
        )

        harness.playToFailure(player)

        val typed = typedErrorOf(player)
        assertThat(typed.causeClass).isEqualTo(FailureClass.Drm.Provisioning.stableName)
        assertThat(typed.category).isEqualTo(FailureCategory.DRM)
        // Retryable, because a provisioning service that refused twice may well certify this device
        // tomorrow — which is the one thing that separates this ending from `Drm.Unsupported`'s.
        assertThat(typed.isRetryable).isTrue()
        assertThat(typed.userMessageKey).isEqualTo(FailureClass.DRM_MESSAGE_KEY)
        // The engine's own account is underneath rather than replaced, as it is everywhere else.
        assertThat(typed.cause).isNotNull()
        // And the failure really was a refused *transfer* to the provisioning service, asked for as
        // often as the budget allowed. Without this line the test passes on a session that never
        // reached the server at all, which is a different failure wearing the same name.
        assertThat(attemptsAt(player, FakeLicenceServer.PROVISION_URI)).isEqualTo(2)
    }

    @Test
    fun aProvisioningRepairDoesNotShortenTheLicenceRequestItWasFor() {
        // The boundary this ticket had to place, stated as the behaviour it produces. Both round
        // trips are refused twice and both relent, against a licence budget of exactly two: the
        // session plays, which it could only do if each round trip was given the budget whole. Had
        // the budget been the session's rather than the request's, the licence would have arrived at
        // an allowance the provisioning had already spent, and this test would end in an error.
        DeviceStatement.declareWidevine(SecurityLevel.L1, provisioningRequired = true)
        val player = play(
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, ResourceKind.LICENCE, index = 0, firstAttempts = 2)
                .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, ResourceKind.LICENCE, index = 1, firstAttempts = 2)
                .build(),
            policy = budgets(licence = RetryBudget(maxRetries = 2, initialBackoffMs = 50, maxBackoffMs = 200)),
        )

        harness.playToReady(player)

        assertThat(player.playerError).isNull()
        assertThat(attemptsAt(player, FakeLicenceServer.PROVISION_URI)).isEqualTo(3)
        assertThat(attemptsAt(player, FakeLicenceServer.LICENCE_URI)).isEqualTo(3)
    }

    /** A player built as a consumer builds one, with the protection and the resilience both set. */
    private fun play(
        faults: FaultScript = FaultScript.NONE,
        policy: PlaybackPolicy = budgets(),
    ): SuperPlayer {
        val content = TestContent.protectedDash()
        val player = harness.buildPlayer(
            content = content,
            faults = faults,
            policy = policy,
            resilience = Resilience.standard(),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        return player
    }

    /**
     * A provisioning service that refuses [firstAttempts] asks, or every one of them when null.
     *
     * Addressed at `LICENCE#0` rather than at the kind, which is the distinction `ResourceKind`'s
     * KDoc draws: index 0 is whichever of the server's two resources the session asks for first, and
     * on a device that needs certifying that is the provisioning. A fault naming no index would refuse
     * the licence too and say nothing about provisioning in particular.
     */
    private fun provisioningFails(firstAttempts: Int?): FaultScript = FaultScript.Builder()
        .failWithHttpStatus(
            FaultScript.HTTP_SERVER_ERROR,
            kind = ResourceKind.LICENCE,
            index = 0,
            firstAttempts = firstAttempts,
        )
        .build()

    /** The licence server's addresses in the order they were first asked for, without repeats. */
    private fun licenceServerUris(player: SuperPlayer): List<String> =
        harness.networkRequests(player).filter { it.kind == ResourceKind.LICENCE }.map { it.uri }.distinct()

    /** How many times [uri] was asked for, repeats included — which is what a retry is. */
    private fun attemptsAt(player: SuperPlayer, uri: String): Int =
        harness.networkRequests(player).count { it.uri == uri }

    /** The typed error rung 6 delivered, where a consumer already looks for a cause. */
    private fun typedErrorOf(player: SuperPlayer): SuperPlayerError {
        val error = player.playerError
        assertThat(error).isNotNull()
        assertThat(error?.cause).isInstanceOf(SuperPlayerError::class.java)
        return error?.cause as SuperPlayerError
    }

    /**
     * A policy that decides a workable buffer and the budgets a test is about.
     *
     * Hand-written rather than a profile row, for `LicenceLoadTest`'s reason: the profile rows are
     * argued in `StaticProfilePolicy` and what each test here needs is one specific number.
     */
    private fun budgets(
        manifest: RetryBudget = RetryBudget.MEDIA3_DEFAULT,
        segment: RetryBudget = RetryBudget.MEDIA3_DEFAULT,
        licence: RetryBudget = RetryBudget.MEDIA3_DEFAULT,
    ): PlaybackPolicy = object : PlaybackPolicy {
        override fun decide(conditions: PlaybackConditions): PlaybackDecision = PlaybackDecision(
            buffer = BufferPolicy(
                minBufferMs = 10_000,
                maxBufferMs = 20_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
            ),
            retry = RetryPolicy(manifest = manifest, segment = segment, licence = licence),
        )
    }

    private companion object {
        const val CONTENT_ID = "series/expanse/s01e05"

        /** Far enough past the refusal that every retry a budget allows has been made. */
        const val PLAYED_MS = 20_000L

        /**
         * Long enough that a quarter of it is unambiguously inside the shortest backoff `Backoff` can
         * draw — which is half the base — and short enough that the whole session fits the harness's
         * bound comfortably.
         */
        const val BACKOFF_MS = 4_000L
    }
}
