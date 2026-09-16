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

package com.superplayer.resilience

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.BufferPolicy
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.NetworkRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An expiring CDN token through a player: a real HLS stream, a real chain, a real 403 that lasts
 * until the credential changes, and a [HeaderProvider] that changes it.
 *
 * Everything is asserted at the transport — `networkRequests` is what left the player, repeats
 * included, counted below every layer SuperPlayer composes — and on the headers those requests
 * carried, because the claims of ADR-0011 rule 12 are claims about traffic: that the second ask
 * carried a new credential, that there was no third, and that nothing above the layer was asked to
 * retry anything. Nothing reaches past the facade (`docs/testing.md`): the player is built the way a
 * consumer builds one, with `Resilience.standard(headers = …)` in the builder.
 *
 * The budget in force is nought retries wherever a repair is expected, and that is the load-bearing
 * part of the design of these tests rather than caution: with rung 1 unable to ask for anything
 * twice, a session that heals can only have healed inside the one transfer that met the refusal —
 * which is both halves of the ticket at once, "one transfer to everything above" and "the refresh
 * happens before the retry".
 */
@RunWith(AndroidJUnit4::class)
class TokenRefreshPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun anExpiredTokenIsRefreshedInsideTheTransferAndTheSessionPlaysOn() {
        val provider = MintingProvider()
        val player = play(
            faults = FaultScript.Builder().expireTokenAtSegment(FAULTED_SEGMENT, refreshable = true).build(),
            headers = provider,
            segment = NO_RETRIES,
        )
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        // The session survived a fault that refuses every segment from the third onward until the
        // credential changes — which, with no retry available to it, only a refresh can have done.
        assertThat(player.playerError).isNull()
        assertThat(provider.calls()).isEqualTo(1)
        assertThat(distinctSegmentsFetched(player)).isGreaterThan(FAULTED_SEGMENT + 1)

        // Two asks at the faulted segment and no third: the refusal, and the repaired request. A
        // refresh performed after a retry would have spent a retry first — and there was none to
        // spend, so a session that healed at all healed before one.
        val faulted = requestsAt(player, faultedSegmentUri(player))
        assertThat(faulted).hasSize(2)
        assertThat(faulted.first().headers).doesNotContainKey(AUTHORIZATION)
        assertThat(faulted.last().headers).containsEntry(AUTHORIZATION, provider.minted(1))

        // And the credential is the session's rather than that one request's: every segment fetched
        // after the repair carried it, which is what keeps one refresh from becoming one per object.
        val afterTheRepair = harness.networkRequests(player)
            .dropWhile { !it.headers.containsKey(AUTHORIZATION) }
        assertThat(afterTheRepair.size).isGreaterThan(1)
        assertThat(afterTheRepair.all { it.headers[AUTHORIZATION] == provider.minted(1) }).isTrue()
    }

    @Test
    fun theProviderIsAskedOnAnUnauthorizedAndOnAForbiddenAndOnNothingElse() {
        // One refusal each, relenting on the second ask, so that every one of these sessions plays
        // to ready whether or not the provider was consulted — and the count is therefore about the
        // status and nothing else.
        assertThat(callsWhenRefusedWith(UNAUTHORIZED)).isEqualTo(1)
        assertThat(callsWhenRefusedWith(FaultScript.HTTP_FORBIDDEN)).isEqualTo(1)
        // A CDN's own trouble is not a credential's: a new token would not mend it, so no token is
        // minted and rung 1 is what the session recovers on.
        assertThat(callsWhenRefusedWith(FaultScript.HTTP_SERVER_ERROR)).isEqualTo(0)
        // Nor is an object the edge does not have, though it shares the refusal band.
        assertThat(callsWhenRefusedWith(FaultScript.HTTP_NOT_FOUND)).isEqualTo(0)
    }

    @Test
    fun aProviderWithNothingNewerToGiveDoesNotLoopAndTheRefusalEscalates() {
        // The provider hands back exactly what was refused, which ADR-0011 rule 12 says is not asked
        // again. What proves it is the count: one ask per retry the budget allowed and not one more,
        // where a refresh that re-asked with an unchanged credential would have doubled every one of
        // them and a refresh that re-asked in a loop would never have finished.
        val provider = DecliningProvider()
        assertThat(attemptsWhenTheProviderDeclines(provider)).isEqualTo(BUDGETED.maxRetries + 1)
        assertThat(provider.calls()).isAtMost(BUDGETED.maxRetries + 1)
    }

    @Test
    fun aProviderThatFailsDeclinesTheSameWayAndTheCdnSFailureIsWhatSurfaces() {
        val provider = FailingProvider()
        assertThat(attemptsWhenTheProviderDeclines(provider)).isEqualTo(BUDGETED.maxRetries + 1)
        assertThat(provider.calls()).isAtMost(BUDGETED.maxRetries + 1)
    }

    @Test
    fun theRepairedRequestResumesWherePlaybackHadReached() {
        // ADR-0011 rule 9, in the form available from outside the facade: the position never goes
        // backwards across the refusal and its repair, and playback carries on past it. Sampled
        // across the span rather than at its ends, because a re-prepare that restarted at zero and
        // caught up again would be invisible to a before-and-after pair.
        val player = play(
            faults = FaultScript.Builder().expireTokenAtSegment(FAULTED_SEGMENT, refreshable = true).build(),
            headers = MintingProvider(),
            segment = NO_RETRIES,
        )
        harness.playToReady(player)

        var previousMs = player.currentPosition
        var advanced = 0L
        while (advanced < PLAYED_MS) {
            harness.advanceTimeInStepsMs(player, SAMPLE_MS)
            advanced += SAMPLE_MS
            assertThat(player.currentPosition).isAtLeast(previousMs)
            previousMs = player.currentPosition
        }

        assertThat(player.playerError).isNull()
        assertThat(previousMs).isGreaterThan(0L)
    }

    @Test
    fun aPlayerWithNoProviderSendsNoCredentialAndMeetsTheRefusalAsALoadError() {
        // The other end of the argument, and what keeps "before the retry" meaningful: the same
        // fault against the same resilience with no provider is not repaired at all. It is also what
        // shows the header on the requests above is this layer's doing rather than the harness's.
        val player = play(
            faults = FaultScript.Builder().expireTokenAtSegment(FAULTED_SEGMENT, refreshable = true).build(),
            headers = null,
            segment = NO_RETRIES,
        )
        harness.playToFailure(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNotNull()
        assertThat(harness.networkRequests(player).none { it.headers.containsKey(AUTHORIZATION) }).isTrue()
    }

    /** How many times a provider is asked when one relenting refusal with [status] is injected. */
    private fun callsWhenRefusedWith(status: Int): Int {
        val provider = MintingProvider()
        val player = play(
            faults = FaultScript.Builder()
                .failWithHttpStatus(status, ResourceKind.MEDIA_SEGMENT, FAULTED_SEGMENT, firstAttempts = 1)
                .build(),
            headers = provider,
            segment = BUDGETED,
        )
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)
        return provider.calls()
    }

    /** What the faulted segment cost in asks when [provider] refuses to make progress. */
    private fun attemptsWhenTheProviderDeclines(provider: HeaderProvider): Int {
        val player = play(
            faults = FaultScript.Builder().expireTokenAtSegment(FAULTED_SEGMENT, refreshable = true).build(),
            headers = provider,
            segment = BUDGETED,
        )
        harness.playToFailure(player)
        // Well past the point at which more asks would have been made if anything were still making
        // them: a count taken at the failure could not tell "stopped" from "not got round to it".
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNotNull()
        return requestsAt(player, faultedSegmentUri(player)).size
    }

    private fun play(
        faults: FaultScript,
        headers: HeaderProvider?,
        segment: RetryBudget,
    ): SuperPlayer {
        val content = TestContent.hls()
        val player = harness.buildPlayer(
            content = content,
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            faults = faults,
            policy = retryPolicyOf(segment),
            resilience = Resilience.standard(headers = headers),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        return player
    }

    /**
     * The address of the segment the fault addressed, which is the most-asked-for one under every
     * script here — every other segment is fetched once.
     */
    private fun faultedSegmentUri(player: SuperPlayer): String =
        harness.networkRequests(player)
            .filter { it.kind == ResourceKind.MEDIA_SEGMENT }
            .groupingBy { it.uri }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            .orEmpty()

    /** Every ask at [uri], in the order they left the player. */
    private fun requestsAt(player: SuperPlayer, uri: String): List<NetworkRequest> =
        harness.networkRequests(player).filter { it.uri == uri }

    private fun distinctSegmentsFetched(player: SuperPlayer): Int =
        harness.networkRequests(player).filter { it.kind == ResourceKind.MEDIA_SEGMENT }.map { it.uri }.distinct().size

    /**
     * A policy that decides [segment] for the retry half and the profile's shape for the rest.
     *
     * Hand-written rather than a profile row for [RetryPlaybackTest]'s reason: the point of each test
     * is a specific budget, and the profile rows are argued in `StaticProfilePolicy` rather than
     * here. It is not an engine extension, so it is consulted once at construction.
     */
    private fun retryPolicyOf(segment: RetryBudget): PlaybackPolicy = object : PlaybackPolicy {
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
            retry = RetryPolicy(manifest = RetryBudget.MEDIA3_DEFAULT, segment = segment),
        )
    }

    /** A provider that mints a new token every time it is asked, and remembers what it minted. */
    private class MintingProvider : HeaderProvider {

        private val minted = CopyOnWriteArrayList<String>()

        override fun refreshedHeaders(refusal: CredentialRefusal): Map<String, String> {
            val token = "Bearer minted-${minted.size + 1}"
            minted += token
            return mapOf(AUTHORIZATION to token)
        }

        fun calls(): Int = minted.size

        /** The [ordinal]th token this minted, counting from one. */
        fun minted(ordinal: Int): String = minted[ordinal - 1]
    }

    /** A provider with nothing newer to give: it hands back exactly what was refused. */
    private class DecliningProvider : HeaderProvider {

        private val calls = CopyOnWriteArrayList<CredentialRefusal>()

        override fun refreshedHeaders(refusal: CredentialRefusal): Map<String, String> {
            calls += refusal
            return refusal.sentHeaders
        }

        fun calls(): Int = calls.size
    }

    /** A provider whose own token service is down. */
    private class FailingProvider : HeaderProvider {

        private val calls = CopyOnWriteArrayList<CredentialRefusal>()

        override fun refreshedHeaders(refusal: CredentialRefusal): Map<String, String> {
            calls += refusal
            throw IllegalStateException("The token service is down")
        }

        fun calls(): Int = calls.size
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e02"

        /** // ref: RFC 9110 §11.6.2 — where a request's credentials travel. */
        const val AUTHORIZATION = "Authorization"

        /** // ref: RFC 9110 §15.5.2 — the other status a refused credential arrives as. */
        const val UNAUTHORIZED = 401

        /**
         * Late enough that segments play before it, so a fault that fired at the start would be
         * visible, and inside `TestContent.DEFAULT_SEGMENT_COUNT`.
         */
        const val FAULTED_SEGMENT = 2

        /** Far enough past the faulted segment that every ask anything would make has been made. */
        const val PLAYED_MS = 20_000L

        /** One advance of [theRepairedRequestResumesWherePlaybackHadReached]'s sampling loop. */
        const val SAMPLE_MS = 1_000L

        /**
         * Nothing above the layer may ask for anything twice: what makes a session that heals a
         * session that healed inside one transfer.
         */
        val NO_RETRIES = RetryBudget(maxRetries = 0, initialBackoffMs = 0, maxBackoffMs = 0)

        /** A budget big enough to count asks against, and small enough that a test does not wait. */
        val BUDGETED = RetryBudget(maxRetries = 2, initialBackoffMs = 50, maxBackoffMs = 200)
    }
}
