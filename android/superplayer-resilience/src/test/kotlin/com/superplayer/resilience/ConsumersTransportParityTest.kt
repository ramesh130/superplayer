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
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.BufferPolicy
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.core.SuperPlayer
import com.superplayer.core.SuperPlayerError
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.testkit.ChainBottom
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.NetworkRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.SyntheticDashStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The library behaves **identically** over an HTTP client a consumer wrote: the same fault produces
 * the same rung, the same [FailureClass] and the same numbers (ADR-0016 rule 8).
 *
 * Not "it works". Every claim here is already pinned over the default bottom by a test of its own —
 * [TokenRefreshPlaybackTest] for the credential repair, [FallbackPlaybackTest] for the two upper
 * rungs, [RetryPlaybackTest] for a class the classifier calls unretryable — and this class is the
 * same claims asked again with the last step of the journey replaced. Each one reuses that test's
 * own control rather than inventing a second one, and says which.
 *
 * Totality — `PRD.md` Part 4's "zero unclassified errors" over a transport we did not write, across
 * every *kind* of fault rather than every status — is [FaultSweepTest]'s, beside its own sweep and
 * off its own enumeration.
 *
 * ## Why a parameter rather than a second file
 *
 * `PlaybackHarness.buildPlayer` takes a [ChainBottom], and everything else about a player it builds
 * is the same either way: one origin, one [FaultScript], one clock. So a body run over
 * `ChainBottom.entries` *is* the comparison, and a third entry added later is asked the same
 * questions rather than being silently unproven — which is why every test here iterates the entries
 * instead of naming them. Copying each of the four tests instead would have made the interesting
 * half — that the answers are equal — invisible.
 *
 * ## What is being prevented
 *
 * All of it fails silently. A transport that answered a 403 with an empty body instead of a status,
 * or whose refusal reached core carrying a freshly built `DataSpec` rather than the stamped one the
 * chain handed down, breaks nothing that throws: rung 1 would stop repairing credentials, a refused
 * segment would classify as a refused manifest, and sessions would simply end unclassified. Nothing
 * here asserts that a player *played*; each assertion is a count or an equality that a silent
 * regression moves.
 */
@RunWith(AndroidJUnit4::class)
class ConsumersTransportParityTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /**
     * The trap ADR-0016 rule 8 is really about, end to end.
     *
     * `ErrorClassifier` reads two fields off the `InvalidResponseCodeException` core's adapter
     * raises: the status, and the `LoadKind` stamped on the `DataSpec` the refusal carries. The
     * second is the one an adapter gets wrong by accident — building a fresh `DataSpec` from the
     * request would lose the stamp, and *every refused segment would then classify as a refused
     * manifest* with nothing failing to say so. `ErrorClassifierTest`'s
     * `aRefusedSegmentIsAnEdgeFailureAndTheSameStatusOnAManifestIsNot` states the discrimination
     * over a hand-built exception, which cannot catch that: this asks a real player the same
     * question with a consumer's transport underneath.
     *
     * A 404 is the status because it is in `EDGE_REFUSALS` and is not a credential's business, so
     * nothing between here and the classification can repair it.
     */
    @Test
    fun aRefusedSegmentKeepsItsKindAcrossATransportWeDidNotWrite() {
        for (bottom in ChainBottom.entries) {
            val onASegment = endsOn(refusing(FaultScript.HTTP_NOT_FOUND, ResourceKind.MEDIA_SEGMENT), bottom)
            assertWithMessage("a refused segment over $bottom")
                .that(onASegment.causeClass)
                .isEqualTo(FailureClass.Transient.CdnEdge.stableName)

            // The other half of the discrimination, and the reason the first half is worth
            // asserting: the same status on the document that named the segment is not an edge
            // failure at all, because the stamp says it is a manifest.
            val onAManifest = endsOn(refusing(FaultScript.HTTP_NOT_FOUND, ResourceKind.MANIFEST), bottom)
            assertWithMessage("a refused manifest over $bottom")
                .that(onAManifest.causeClass)
                .isNotEqualTo(FailureClass.Transient.CdnEdge.stableName)
        }
    }

    /**
     * Rung 1's credential repair, over a consumer's transport.
     *
     * The control is [TokenRefreshPlaybackTest]'s own and is the load-bearing half of it: the
     * segment budget is **nought retries**, so a session that heals can only have healed inside the
     * one transfer that met the refusal. Both statuses `TokenRefreshLayer` may act on are forced,
     * because the set is read off `InvalidResponseCodeException.responseCode` and a status that
     * failed to arrive would take the whole mechanism out with it.
     */
    @Test
    fun aRefusedCredentialIsRepairedInsideTheTransferOverEitherBottom() {
        for (bottom in ChainBottom.entries) {
            for (status in setOf(UNAUTHORIZED, FaultScript.HTTP_FORBIDDEN)) {
                val provider = MintingProvider()
                val player = build(
                    content = TestContent.hls(segmentCount = SEGMENTS),
                    faults = FaultScript.Builder()
                        .failWithHttpStatus(status, ResourceKind.MEDIA_SEGMENT, FAULTED_SEGMENT, firstAttempts = 1)
                        .build(),
                    headers = provider,
                    segment = NO_RETRIES,
                    bottom = bottom,
                )
                harness.playToReady(player)
                harness.advanceTimeInStepsMs(player, PLAYED_MS)

                assertWithMessage("$status over $bottom").that(player.playerError).isNull()
                assertWithMessage("providers asked for $status over $bottom").that(provider.calls()).isEqualTo(1)
                // Two asks at the refused segment and no third: the refusal, and the repaired
                // request carrying the minted credential. With nothing able to retry, a third ask
                // is the only shape a repair-after-retry could have taken.
                val faulted = busiest(player, ResourceKind.MEDIA_SEGMENT)
                assertWithMessage("asks at the refused segment for $status over $bottom").that(faulted).hasSize(2)
                assertThat(faulted.first().headers).doesNotContainKey(AUTHORIZATION)
                assertThat(faulted.last().headers).containsEntry(AUTHORIZATION, provider.minted(1))
            }
        }
    }

    /**
     * The ladder climbs the same rung over either bottom.
     *
     * The status is **502**, for [FallbackPlaybackTest]'s reason and it is the whole reason this
     * test says anything: Media3's own fallback table does not list a bad gateway
     * (// ref: `DefaultLoadErrorHandlingPolicy.isEligibleForFallback`), so a location fallback that
     * happens is the ladder's rather than Media3's. The content and the budget are that test's too
     * — DASH across two `BaseURL`s, one retry — so the count read here is the count it reads.
     */
    @Test
    fun theSameFaultClimbsTheSameRungOverEitherBottom() {
        for (bottom in ChainBottom.entries) {
            val content = TestContent.dash(SEGMENTS, mirrorHost = MIRROR_HOST)
            val player = build(
                content = content,
                faults = FaultScript.Builder()
                    .failWithHttpStatus(BAD_GATEWAY, ResourceKind.MEDIA_SEGMENT, FAULTED_SEGMENT, host = SyntheticDashStream.HOST)
                    .build(),
                headers = null,
                segment = ONE_RETRY,
                bottom = bottom,
            )
            harness.playToReady(player)
            harness.advanceTimeInStepsMs(player, PLAYED_MS)

            val segments = harness.networkRequests(player).filter { it.kind == ResourceKind.MEDIA_SEGMENT }
            assertWithMessage("the session over $bottom").that(player.playerError).isNull()
            // Rung 1 first and whole: the origin was asked once and then once more, which is the
            // budget and no more — a rung 2 reached early would show as fewer.
            assertWithMessage("attempts at the faulted segment on the origin over $bottom")
                .that(segments.count { it.host == SyntheticDashStream.HOST && it.uri.endsWith(FAULTED_SEGMENT_NAME) })
                .isEqualTo(ATTEMPTS_ALLOWED)
            // Then rung 2: the mirror served what the origin would not, and went on serving.
            assertWithMessage("what the mirror served over $bottom")
                .that(segments.filter { it.host == MIRROR_HOST }.map { it.uri }.distinct().size)
                .isGreaterThan(1)
        }
    }

    /**
     * A class the classifier calls unretryable costs nothing extra over a consumer's transport
     * either.
     *
     * [RetryPlaybackTest]'s control: a **416** on a segment under a budget of five retries is asked
     * for exactly once, because `Content.SegmentGap` is unretryable and the same bytes are the same
     * bytes. It is the second status that reaches the classifier off `responseCode` alone, and the
     * only one that reaches it by a route other than `EDGE_REFUSALS`, which is why it is here
     * beside the 404.
     */
    @Test
    fun aRangeNobodyCanSatisfyIsNotRetriedOverEitherBottom() {
        for (bottom in ChainBottom.entries) {
            val player = build(
                content = TestContent.hls(segmentCount = SEGMENTS),
                faults = refusing(RANGE_NOT_SATISFIABLE, ResourceKind.MEDIA_SEGMENT),
                headers = null,
                // Generous, so that "not retried" is the class's doing and not the budget's.
                segment = RetryBudget(maxRetries = 5, initialBackoffMs = 50, maxBackoffMs = 200),
                bottom = bottom,
            )
            harness.playToFailure(player)
            harness.advanceTimeInStepsMs(player, PLAYED_MS)

            assertWithMessage("asks at the faulted segment over $bottom")
                .that(busiest(player, ResourceKind.MEDIA_SEGMENT)).hasSize(1)
            assertWithMessage("what the session ended on over $bottom")
                .that(classificationOf(player).causeClass)
                .isEqualTo(FailureClass.Content.SegmentGap.stableName)
        }
    }

    /**
     * `ErrorClassifier` is total over a consumer's transport, and says the same thing.
     *
     * The statuses are the bands a CDN actually refuses in — a credential's two, an object that is
     * not there, a range nobody can satisfy, a server that is unwell and a gateway that is. Totality
     * is asserted by [classificationOf], which refuses a null rather than an unexpected value: a
     * session that "simply ends unclassified" is exactly what a lost status looks like from outside,
     * and it is the failure this whole class exists to prevent.
     *
     * What is compared beyond that is the class itself against the *default* bottom's answer for the
     * same fault, rather than a table written down here. A table would have to be kept in step with
     * `ErrorClassifier`, which is `ErrorClassifierTest`'s job; the equality is this ticket's, and it
     * moves the moment the two bottoms stop agreeing.
     */
    @Test
    fun everyStatusEndsOnTheSameClassOverEitherBottom() {
        for (status in REFUSALS) {
            val refusal = refusing(status, ResourceKind.MEDIA_SEGMENT)
            // Every entry against the same reference, so a third bottom is compared here too.
            val reference = endsOn(refusal, ChainBottom.HARNESS_TRANSPORT_SLOT).causeClass
            for (bottom in ChainBottom.entries) {
                assertWithMessage("the class $status ended on over $bottom")
                    .that(endsOn(refusal, bottom).causeClass)
                    .isEqualTo(reference)
            }
        }
    }

    /** What the session [player] ran ended on, as a consumer reads it. */
    private fun classificationOf(player: SuperPlayer): SuperPlayerError =
        checkNotNull(player.classify(checkNotNull(player.playerError) { "The session did not fail" })) {
            "The session ended unclassified, which is the failure ADR-0016 rule 8 exists to prevent"
        }

    /** The typed error a session refused by [faults] over [bottom] ends on. */
    private fun endsOn(faults: FaultScript, bottom: ChainBottom): SuperPlayerError {
        val player = build(
            content = TestContent.hls(segmentCount = SEGMENTS),
            faults = faults,
            headers = null,
            segment = NO_RETRIES,
            bottom = bottom,
        )
        harness.playToFailure(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)
        return classificationOf(player)
    }

    /** A refusal with [status] at every attempt at every resource of [kind]. */
    private fun refusing(status: Int, kind: ResourceKind): FaultScript =
        FaultScript.Builder().failWithHttpStatus(status, kind, index = null).build()

    private fun build(
        content: TestContent,
        faults: FaultScript,
        headers: HeaderProvider?,
        segment: RetryBudget,
        bottom: ChainBottom,
    ): SuperPlayer {
        val player = harness.buildPlayer(
            content = content,
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            faults = faults,
            policy = retryPolicyOf(segment),
            resilience = Resilience.standard(headers = headers),
            bottom = bottom,
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        return player
    }

    /** Every ask at the most-asked-for resource of [kind], which is the one a fault addressed. */
    private fun busiest(player: SuperPlayer, kind: ResourceKind): List<NetworkRequest> {
        val requests = harness.networkRequests(player).filter { it.kind == kind }
        val uri = requests.groupingBy { it.uri }.eachCount().maxByOrNull { it.value }?.key ?: return emptyList()
        return requests.filter { it.uri == uri }
    }

    /** Which CDN served a request, parsed back out of the URL as `FallbackPlaybackTest` does. */
    private val NetworkRequest.host: String?
        get() = android.net.Uri.parse(uri).host

    /**
     * A policy that decides [segment] for the retry half and a workable shape for the rest.
     *
     * Hand-written for the reason [RetryPlaybackTest]'s is: the point of each test is a specific
     * budget, and the profile rows are argued in `StaticProfilePolicy` rather than here. It is not
     * an engine extension, so it is consulted once at construction.
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

    private companion object {
        const val CONTENT = "series/expanse/s01e02"

        /** // ref: RFC 9110 §11.6.2 — where a request's credentials travel. */
        const val AUTHORIZATION = "Authorization"

        /** // ref: RFC 9110 §15.5.2 — the other status a refused credential arrives as. */
        const val UNAUTHORIZED = 401

        /** // ref: RFC 9110 §15.5.17 — a range the object cannot satisfy. */
        const val RANGE_NOT_SATISFIABLE = 416

        /** // ref: RFC 9110 §15.6.3 — the status Media3's own fallback table does not list. */
        const val BAD_GATEWAY = 502

        /** Every status a CDN refuses in that reaches the classifier by any route. */
        val REFUSALS = listOf(
            UNAUTHORIZED,
            FaultScript.HTTP_FORBIDDEN,
            FaultScript.HTTP_NOT_FOUND,
            RANGE_NOT_SATISFIABLE,
            FaultScript.HTTP_SERVER_ERROR,
            BAD_GATEWAY,
        )

        const val SEGMENTS = 8

        /**
         * Late enough that segments play before the fault, so a repair or a fallback taken at the
         * very first request would be visible, and inside [SEGMENTS].
         */
        const val FAULTED_SEGMENT = 2

        /** The DASH segment [FAULTED_SEGMENT] names, which is what a rung-2 count is taken at. */
        const val FAULTED_SEGMENT_NAME = "segment$FAULTED_SEGMENT.m4s"

        /** The first ask and the one retry the budget allows, and then the rung above. */
        const val ATTEMPTS_ALLOWED = 2

        /** The second location the DASH manifest names, and the only other host its media is at. */
        const val MIRROR_HOST = "mirror.superplayer.test"

        /** Far enough past the fault that every ask anything would make has been made. */
        const val PLAYED_MS = 20_000L

        /**
         * Nothing above the layer may ask for anything twice: what makes a session that heals a
         * session that healed inside one transfer.
         */
        val NO_RETRIES = RetryBudget(maxRetries = 0, initialBackoffMs = 0, maxBackoffMs = 0)

        val ONE_RETRY = RetryBudget(maxRetries = 1, initialBackoffMs = 50, maxBackoffMs = 200)
    }
}
