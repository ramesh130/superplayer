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
import com.superplayer.core.PlaybackResilience
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rung 1 through a player: a real HLS stream, a real chain, a real fault, and the requests that
 * actually left it.
 *
 * Everything here is counted at the transport — `networkRequests` is what the player sent, repeats
 * included — because a retry is only a retry if the bytes were asked for again, and every other
 * reading of it is an inference. Nothing reaches past the facade: the player is built the way a
 * consumer builds one, with `Resilience.standard()` in the builder, and what is asserted is the
 * traffic and the position (`docs/testing.md`).
 *
 * HLS rather than the described content most harness tests use, and that is load-bearing: described
 * content replaces the whole loading path, so the media source factory `TransferChain` assembles —
 * the one the load-error slot is handed to — would not be in the player at all.
 */
@RunWith(AndroidJUnit4::class)
class RetryPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aFaultThatRelentsIsRetriedAndPlaybackGoesOn() {
        val player = play(
            faults = FaultScript.Builder()
                .failWithHttpStatus(
                    FaultScript.HTTP_SERVER_ERROR,
                    ResourceKind.MEDIA_SEGMENT,
                    FAULTED_SEGMENT,
                    firstAttempts = 2,
                )
                .build(),
            policy = retryPolicyOf(segment = RetryBudget(maxRetries = 3, initialBackoffMs = 50, maxBackoffMs = 200)),
        )
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        // Two refusals, a third ask that carried the bytes, and a session that never failed.
        assertThat(player.playerError).isNull()
        assertThat(attemptsAtTheFaultedSegment(player)).isEqualTo(3)
        // And playback went *past* the faulted segment rather than stopping on the recovery.
        assertThat(distinctSegmentsFetched(player)).isGreaterThan(FAULTED_SEGMENT + 1)
    }

    @Test
    fun aFaultThatNeverRelentsSpendsItsBudgetAndStopsRatherThanRetryingForever() {
        val player = play(
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, ResourceKind.MEDIA_SEGMENT, FAULTED_SEGMENT)
                .build(),
            policy = retryPolicyOf(segment = RetryBudget(maxRetries = 2, initialBackoffMs = 50, maxBackoffMs = 200)),
        )
        harness.playToFailure(player)
        // Well past the point at which more retries would have happened if the budget were not the
        // thing stopping them: a bound that stops at the failure could not tell "gave up" from
        // "had not got round to the next one yet".
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        // The first ask and the two the budget allowed, and then the failure — which is the
        // escalation seam, and today surfaces exactly as it would with no resilience at all
        // (ADR-0011 rule 7).
        assertThat(attemptsAtTheFaultedSegment(player)).isEqualTo(3)
        assertThat(player.playerError).isNotNull()
    }

    @Test
    fun aFailureTheClassifierCallsUnretryableIsNotRetriedAtAll() {
        // 416 on a segment while the playlist that named it loaded fine is the media not being
        // there as promised — `Content.SegmentGap`, which `FailureClass` marks unretryable because
        // the same bytes are the same bytes. Told from a transfer failure only by the `LoadKind`
        // stamp a player with resilience carries, which is why this is a test of the whole player
        // rather than of the classifier.
        val player = play(
            faults = FaultScript.Builder()
                .failWithHttpStatus(RANGE_NOT_SATISFIABLE, ResourceKind.MEDIA_SEGMENT, FAULTED_SEGMENT)
                .build(),
            // A generous budget, so that "not retried" is the class's doing and not the budget's.
            policy = retryPolicyOf(segment = RetryBudget(maxRetries = 5, initialBackoffMs = 50, maxBackoffMs = 200)),
        )
        harness.playToFailure(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(attemptsAtTheFaultedSegment(player)).isEqualTo(1)
        assertThat(player.playerError).isNotNull()
    }

    @Test
    fun aSpentManifestBudgetLeavesTheSegmentBudgetWhole() {
        // The separation `PRD.md` §3.3 asks for, through a player: the manifest spends every ask it
        // has and recovers on the last one, and a segment then spends *more* asks than the manifest
        // budget would have allowed — which it could only do out of a budget of its own.
        val player = play(
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, ResourceKind.MANIFEST, firstAttempts = 1)
                .failWithHttpStatus(
                    FaultScript.HTTP_SERVER_ERROR,
                    ResourceKind.MEDIA_SEGMENT,
                    FAULTED_SEGMENT,
                    firstAttempts = 3,
                )
                .build(),
            policy = retryPolicyOf(
                manifest = RetryBudget(maxRetries = 1, initialBackoffMs = 50, maxBackoffMs = 200),
                segment = RetryBudget(maxRetries = 4, initialBackoffMs = 50, maxBackoffMs = 200),
            ),
        )
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNull()
        // The manifest spent its one ask.
        assertThat(attemptsAtTheBusiestManifest(player)).isEqualTo(2)
        // And the segment spent three, one more than the manifest's whole budget: had there been a
        // single shared budget it would have been exhausted by the manifest before this began.
        assertThat(attemptsAtTheFaultedSegment(player)).isEqualTo(4)
    }

    @Test
    fun theRetryResumesWherePlaybackHadReached() {
        // ADR-0011 rule 9 for rungs Media3 performs inside a load: the position is untouched by
        // construction and the rule is that nothing SuperPlayer adds re-seeks. The forcing form of
        // that is the only one available from outside — the position never goes backwards across
        // the failure, and playback carries on past it.
        val player = play(
            faults = FaultScript.Builder()
                .failWithHttpStatus(
                    FaultScript.HTTP_SERVER_ERROR,
                    ResourceKind.MEDIA_SEGMENT,
                    FAULTED_SEGMENT,
                    firstAttempts = 1,
                )
                .build(),
            policy = retryPolicyOf(segment = RetryBudget(maxRetries = 3, initialBackoffMs = 50, maxBackoffMs = 200)),
        )
        harness.playToReady(player)

        // Sampled across the whole span rather than at its ends, because a re-prepare that restarted
        // at zero and then caught up again would be invisible to a before-and-after pair.
        var previousMs = player.currentPosition
        var advanced = 0L
        while (advanced < PLAYED_MS) {
            harness.advanceTimeInStepsMs(player, SAMPLE_MS)
            advanced += SAMPLE_MS
            assertThat(player.currentPosition).isAtLeast(previousMs)
            previousMs = player.currentPosition
        }

        assertThat(player.playerError).isNull()
        assertThat(attemptsAtTheFaultedSegment(player)).isEqualTo(2)
        assertThat(previousMs).isGreaterThan(0L)
    }

    @Test
    fun aPlayerWithoutResilienceKeepsMedia3SOwnLoadErrorHandling() {
        // ADR-0011 rule 14, counted rather than assumed — and counted as *behaviour*, which is the
        // only reading of the slot available from outside the facade. One script, two players: the
        // one with no resilience retries the way Media3's `DefaultLoadErrorHandlingPolicy` does,
        // and the one with resilience and a budget of nothing asks exactly once. A player that had
        // silently kept SuperPlayer's policy, or silently lost it, would collapse the two numbers.
        val script = FaultScript.Builder()
            .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, ResourceKind.MEDIA_SEGMENT, FAULTED_SEGMENT)
            .build()

        val stock = play(faults = script, resilience = null)
        harness.playToFailure(stock)
        harness.advanceTimeInStepsMs(stock, PLAYED_MS)

        val refusing = play(
            faults = script,
            policy = retryPolicyOf(segment = RetryBudget(maxRetries = 0, initialBackoffMs = 0, maxBackoffMs = 0)),
        )
        harness.playToFailure(refusing)
        harness.advanceTimeInStepsMs(refusing, PLAYED_MS)

        assertThat(attemptsAtTheFaultedSegment(stock)).isGreaterThan(1)
        assertThat(attemptsAtTheFaultedSegment(refusing)).isEqualTo(1)
    }

    @Test
    fun theProfileSBudgetIsWhatAPlayerBuiltWithNoPolicyAsks() {
        // The other end of ADR-0011 rule 11: with no policy of the test's own, the numbers in force
        // are `StaticProfilePolicy`'s row for the profile, and `playbackDecision` reports them —
        // including on a player with no resilience, where the half is decided and ignored.
        val player = harness.buildPlayer(
            content = TestContent.hls(),
            profile = PlaybackProfile.SHORT_FORM,
            resilience = Resilience.standard(),
        )

        assertThat(player.playbackDecision.retry.segment.maxRetries).isEqualTo(2)
        assertThat(harness.buildPlayer(content = TestContent.hls(), profile = PlaybackProfile.SHORT_FORM).playbackDecision.retry)
            .isEqualTo(player.playbackDecision.retry)
    }

    private fun play(
        faults: FaultScript,
        policy: PlaybackPolicy? = null,
        resilience: PlaybackResilience? = Resilience.standard(),
    ): SuperPlayer {
        val content = TestContent.hls()
        val player = harness.buildPlayer(
            content = content,
            faults = faults,
            policy = policy,
            resilience = resilience,
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        return player
    }

    /**
     * How many times the most-asked-for segment was fetched — which under a script with one faulted
     * segment is that segment, since every other is fetched once.
     *
     * By URI rather than by index because [com.superplayer.testkit.NetworkRequest] carries the
     * address the request went to and repeats are exactly what is being counted;
     * `requestedResources` lists each resource once, by design.
     */
    private fun attemptsAtTheFaultedSegment(player: SuperPlayer): Int = attemptsAtTheBusiest(player, ResourceKind.MEDIA_SEGMENT)

    private fun attemptsAtTheBusiestManifest(player: SuperPlayer): Int = attemptsAtTheBusiest(player, ResourceKind.MANIFEST)

    private fun attemptsAtTheBusiest(player: SuperPlayer, kind: ResourceKind): Int =
        harness.networkRequests(player)
            .filter { it.kind == kind }
            .groupingBy { it.uri }
            .eachCount()
            .values
            .maxOrNull()
            ?: 0

    /** How many distinct segments the session fetched, which is how far past the fault it got. */
    private fun distinctSegmentsFetched(player: SuperPlayer): Int =
        harness.networkRequests(player).filter { it.kind == ResourceKind.MEDIA_SEGMENT }.map { it.uri }.distinct().size

    /**
     * A policy that decides the profile's numbers for everything but the retry half.
     *
     * Hand-written rather than a profile row, because the point of each test above is a specific
     * budget and the profile rows are argued in `StaticProfilePolicy` rather than here. It is not an
     * engine extension, so it is consulted once at construction — which is exactly the player most
     * consumers build, and the one that makes the decision in force a fixed thing to assert against.
     */
    private fun retryPolicyOf(
        manifest: RetryBudget = RetryBudget.MEDIA3_DEFAULT,
        segment: RetryBudget = RetryBudget.MEDIA3_DEFAULT,
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
            retry = RetryPolicy(manifest = manifest, segment = segment),
        )
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e02"

        /** // ref: RFC 9110 §15.5.17, Range Not Satisfiable — the status a missing tail answers with. */
        const val RANGE_NOT_SATISFIABLE = 416

        /**
         * Late enough that segments play before it, so a fault that fired at the start would be
         * visible, and inside `TestContent.DEFAULT_SEGMENT_COUNT`.
         */
        const val FAULTED_SEGMENT = 2

        /** Far enough past the faulted segment that every retry a budget allows has been made. */
        const val PLAYED_MS = 20_000L

        /** One advance of [theRetryResumesWherePlaybackHadReached]'s sampling loop. */
        const val SAMPLE_MS = 1_000L
    }
}
