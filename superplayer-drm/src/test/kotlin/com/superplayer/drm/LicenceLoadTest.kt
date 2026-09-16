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
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.resilience.HeaderProvider
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A licence load is a load of this player's like any other: it spends [RetryPolicy.licence], it is
 * repaired by the one [HeaderProvider] the library has, and neither of those was true before #205.
 *
 * The mechanism both claims rest on is one object. Media3 asks a *DRM session manager's* own
 * `LoadErrorHandlingPolicy` about a failed licence load and a *media source factory's* about every
 * other load, so a session manager left to build its own answers out of Media3's defaults while the
 * rest of the player answers out of the decision in force — a licence budget nothing could reach,
 * which is what `RetryPolicy.licence` said of itself for two phases. Core hands the DRM slot the
 * player's own policy and the chain below the cache, and everything below is the consequence.
 *
 * Counted at the transport, because a retry is only a retry if the bytes were asked for again
 * (`RetryPlaybackTest`'s rule, and the same one). Every player here is built the way a consumer
 * builds one: `setResilience` beside `setDrm`, which is also the pairing that makes the two claims
 * meet — the budget is `superplayer-resilience`'s to answer with and the credential is its provider's
 * to mint, while what makes a licence load happen at all is this module.
 */
@RunWith(AndroidJUnit4::class)
class LicenceLoadTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aRefusedLicenceIsAskedForAgainOnItsOwnBudgetAndTheSessionPlaysOn() {
        // Four refusals and a fifth ask that carried the licence, out of a budget of five — and the
        // budget is deliberately larger than Media3's own patience for a licence load, which is three
        // asks. A session manager answering out of `DefaultLoadErrorHandlingPolicy` gives up on the
        // fourth; only one reading the decision in force gets to the fifth, so the number below is
        // the claim rather than a coincidence.
        val player = play(
            faults = licenceFails(firstAttempts = 4),
            policy = budgets(licence = RetryBudget(maxRetries = 5, initialBackoffMs = 50, maxBackoffMs = 200)),
        )
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNull()
        assertThat(licenceRequests(player)).isEqualTo(5)
    }

    @Test
    fun theLicenceBudgetIsWhatStopsTheAskingAndItSpendsNeitherOfTheOthers() {
        // A refusal that never relents, against a licence budget of one ask and manifest and segment
        // budgets of five. What stops the asking is the number that was spent: had the session
        // manager been answering out of Media3's own policy the count would be Media3's, and had the
        // budgets been shared it would have been the generous one.
        val player = play(
            faults = licenceFails(firstAttempts = null),
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

        assertThat(player.playerError).isNotNull()
        assertThat(licenceRequests(player)).isEqualTo(2)
        // And the two budgets the licence did not spend are visibly unspent: the manifest that the
        // protected stream was described by was served once and asked for once.
        assertThat(attemptsAtTheBusiest(player, ResourceKind.MANIFEST)).isEqualTo(1)
    }

    @Test
    fun aLicenceRefusedForItsCredentialIsRepairedInsideTheTransferRatherThanRetried() {
        // ADR-0012 rule 2 through a player. The licence budget is *nothing*, so a session that plays
        // cannot have been rescued by a retry: the repair happened inside the transfer that met the
        // refusal, which is where `HeaderRefreshLayer` sits — below the cache and over the transport,
        // and therefore under a licence request exactly as it is under a segment request.
        val minted = mutableListOf<Int>()
        val player = play(
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_FORBIDDEN, kind = ResourceKind.LICENCE, firstAttempts = 1)
                .build(),
            policy = budgets(licence = NO_RETRIES),
            headers = HeaderProvider { refusal ->
                minted += refusal.status
                mapOf("Authorization" to "Bearer minted-${minted.size}")
            },
        )
        harness.playToReady(player)

        assertThat(player.playerError).isNull()
        // The provider was asked once, about the refusal it could repair.
        assertThat(minted).containsExactly(FaultScript.HTTP_FORBIDDEN)
        // Two requests at the transport and one transfer above it: the refused ask and the repaired
        // one, with no retry in between because there was no budget to draw one from.
        assertThat(licenceRequests(player)).isEqualTo(2)
    }

    @Test
    fun aProviderWithNothingNewerToGiveDeclinesAndTheRefusalStands() {
        // The bound ADR-0011 rule 12 puts on the repair, at the one request where it matters most: a
        // provider that hands back the credential just refused makes no progress, so the refusal is
        // rethrown unchanged and is a load error like any other — which, on a budget of nothing, ends
        // the session after exactly one ask.
        var asked = 0
        val player = play(
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_FORBIDDEN, kind = ResourceKind.LICENCE)
                .build(),
            policy = budgets(licence = NO_RETRIES),
            headers = HeaderProvider {
                asked++
                emptyMap()
            },
        )
        harness.playToFailure(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNotNull()
        assertThat(asked).isEqualTo(1)
        assertThat(licenceRequests(player)).isEqualTo(1)
    }

    /** A player built as a consumer builds one, with the protection and the resilience both set. */
    private fun play(
        faults: FaultScript,
        policy: PlaybackPolicy,
        headers: HeaderProvider? = null,
    ): SuperPlayer {
        val content = TestContent.protectedDash()
        val player = harness.buildPlayer(
            content = content,
            faults = faults,
            policy = policy,
            resilience = Resilience.standard(headers = headers),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        return player
    }

    /** A licence server that refuses [firstAttempts] asks, or every one of them when null. */
    private fun licenceFails(firstAttempts: Int?): FaultScript = FaultScript.Builder()
        .failWithHttpStatus(
            FaultScript.HTTP_SERVER_ERROR,
            kind = ResourceKind.LICENCE,
            firstAttempts = firstAttempts,
        )
        .build()

    /** How many times a licence was asked for, repeats included — which is what a retry is. */
    private fun licenceRequests(player: SuperPlayer): Int =
        harness.networkRequests(player).count { it.kind == ResourceKind.LICENCE }

    private fun attemptsAtTheBusiest(player: SuperPlayer, kind: ResourceKind): Int =
        harness.networkRequests(player)
            .filter { it.kind == kind }
            .groupingBy { it.uri }
            .eachCount()
            .values
            .maxOrNull()
            ?: 0

    /**
     * A policy that decides a workable buffer and the budgets a test is about.
     *
     * Hand-written rather than a profile row for `RetryPlaybackTest`'s reason: the profile rows are
     * argued in `StaticProfilePolicy` and what each test here needs is one specific number. It is not
     * an engine extension, so it is consulted once at construction.
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
        const val CONTENT_ID = "series/expanse/s01e03"

        /** Far enough past the refusal that every retry a budget allows has been made. */
        const val PLAYED_MS = 20_000L

        /** A budget that allows nothing, so a session that plays was repaired rather than retried. */
        val NO_RETRIES = RetryBudget(maxRetries = 0, initialBackoffMs = 0, maxBackoffMs = 0)
    }
}
