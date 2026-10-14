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

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.BufferPolicy
import com.superplayer.core.DecisionTrigger
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryCollector
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.NetworkRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.SyntheticDashStream
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rung 4 through a player: the next entry of `MediaRequest.sources`, opened where playback had
 * reached (ADR-0011 rules 5, 7 and 9; issue #181).
 *
 * **Why the two sources are two protocols.** A second source is not a second host — that is rung 2,
 * and `FallbackPlaybackTest` forces it through a DASH manifest's own `BaseURL`s. A second *source* is
 * what an app lists when it publishes the same programme twice, which `PRD.md` §2.2's example makes a
 * DASH stream and an HLS one: a different manifest, a different media source, the same content id. So
 * every player below is pointed at a DASH source it cannot play and an HLS source it can, over one
 * transport serving both (`TestContent.alsoServing`).
 *
 * **What forces the rung.** The first source is faulted at every host it has, on media segments only,
 * with a budget of one retry — so rung 1 spends its two attempts at the origin, rung 2 moves to the
 * mirror the MPD names and finds it faulted too, rung 3 has no second representation in this stream to
 * exclude, and the load fails. That failure is what reaches the player, which is the only place rung 4
 * can be performed; the traffic is what says the rungs happened in that order, because `networkRequests`
 * is what left the chain, host and all.
 *
 * Nothing reaches past the facade: every player is built the way a consumer builds one.
 */
@RunWith(AndroidJUnit4::class)
class NextSourcePlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aSourceThatFailsEveryRungBelowIsLeftForTheNextOneInTheRequest() {
        val player = harness.buildPlayer(
            content = dashThenHls(),
            faults = failEveryAttemptOnDash(),
            policy = retryPolicyOf(ONE_RETRY),
            resilience = Resilience.standard(),
        )

        player.setMediaRequest(dashThenHlsRequest())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        // The session was rescued rather than ended: a fallback that leaves an error standing is the
        // thing ADR-0011 rule 10 reserves for rung 6, and this failure never got that far.
        assertThat(player.playerError).isNull()
        // The second source is the one playing, and the whole of the ladder below it was tried first.
        assertThat(player.currentMediaItem?.localConfiguration?.uri.toString())
            .isEqualTo(fallbackHls.sourceUri)
        assertThat(hlsSegments(player).map { it.uri }.distinct().size).isGreaterThan(1)
        assertThat(player.currentPosition).isGreaterThan(0L)
    }

    @Test
    fun theSecondSourceIsOpenedOnlyAfterTheRungsBelowItHaveBeenSpent() {
        val player = harness.buildPlayer(
            content = dashThenHls(),
            faults = failEveryAttemptOnDash(),
            policy = retryPolicyOf(ONE_RETRY),
            resilience = Resilience.standard(),
        )

        player.setMediaRequest(dashThenHlsRequest())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        val requests = harness.networkRequests(player)
        // Rung 1: the origin was asked for the faulted segment the first time and once more, which is
        // the whole of the budget. A rung 4 taken early would show as one.
        assertWithMessage("attempts at the faulted segment on the DASH origin")
            .that(requests.count { it.kind == ResourceKind.MEDIA_SEGMENT && it.host == SyntheticDashStream.HOST })
            .isAtLeast(ATTEMPTS_ALLOWED)
        // Rung 2: the mirror the MPD names was tried before any of this moved to another source.
        // (Rung 3 declines in between: this stream has one representation, so Media3 reports no track
        // to fall back to — the same "correct order rather than a gap" an HLS stream meets at rung 2.)
        val firstMirrorAsk = requests.indexOfFirst { it.host == MIRROR_HOST }
        assertWithMessage("the DASH mirror was asked at all").that(firstMirrorAsk).isAtLeast(0)
        // Rung 4 last: nothing of the second source was fetched until both locations had failed.
        val firstHlsAsk = requests.indexOfFirst { it.host == FALLBACK_HOST }
        assertWithMessage("the HLS source was opened at all").that(firstHlsAsk).isAtLeast(0)
        assertThat(firstHlsAsk).isGreaterThan(firstMirrorAsk)
    }

    @Test
    fun theNextSourceStartsWherePlaybackHadReached() {
        // Rule 9 for a rung core performs: the position is carried across the operation explicitly,
        // and the assertion is against the position *before* the switch rather than against zero,
        // because a fallback that restarts is the defect `PRD.md` §3.3 calls worse than the error.
        val player = harness.buildPlayer(
            content = dashThenHls(),
            faults = failEveryAttemptOnDash(originIndex = FAULTED_SEGMENT),
            policy = retryPolicyOf(ONE_RETRY),
            resilience = Resilience.standard(),
        )

        player.setMediaRequest(dashThenHlsRequest())
        harness.playToReady(player)
        // Far enough that the DASH stream has played whole segments before its faulted one, so the
        // position under test is a real one rather than the zero every session starts at.
        harness.advanceUntil(player, "the fallback to have been taken") {
            it.currentMediaItem?.localConfiguration?.uri.toString() == fallbackHls.sourceUri
        }
        val atTheSwitchMs = player.currentPosition
        // A whole segment of the first source at least, so the position being preserved is a claim
        // about a viewer who was watching rather than about rounding near zero.
        assertWithMessage("playback had got somewhere before the fallback")
            .that(atTheSwitchMs).isAtLeast(SyntheticDashStream.DURATION_MS)

        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNull()
        // Never backwards, which is the whole of rule 9's promise. The tolerance is one segment: the
        // held position is carried exactly, but the second source is seekable only at its own segment
        // boundaries, so Media3 begins the new source at the boundary at or before it — the viewer
        // sees at most one segment of the programme again, and never the whole of it.
        assertThat(player.currentPosition)
            .isAtLeast(atTheSwitchMs - SyntheticHlsStream.SEGMENT_DURATION_MS)
    }

    @Test
    fun theContentIdentityAndItsSessionSurviveTheSwitch() {
        // What the identity being unchanged buys: one telemetry session for one viewing, and a cache
        // key that still matches. Counted rather than inferred — a rung 4 routed through `adopt`
        // would open a second session, and the whole of `MediaRequest`'s argument for an identity
        // that is not a URL would be undone by the one operation that changes the URL.
        val sessions = RecordingCollector()
        val player = harness.buildPlayer(
            content = dashThenHls(),
            faults = failEveryAttemptOnDash(),
            policy = retryPolicyOf(ONE_RETRY),
            resilience = Resilience.standard(),
            telemetry = sessions,
        )

        player.setMediaRequest(dashThenHlsRequest())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNull()
        assertThat(player.currentMediaItem?.mediaId).isEqualTo(CONTENT)
        assertWithMessage("measurement sessions opened for one viewing")
            .that(sessions.started).containsExactly(CONTENT)
        assertThat(sessions.ended).isEqualTo(0)
    }

    @Test
    fun aConsumerIsNotToldAboutAFailureTheRungRepaired() {
        // ADR-0011 rule 10 makes the delivered error rung 6's — "a typed error the consumer can act
        // on" — so a failure a rung below repairs is not one the `Player` API reports. A consumer's
        // listener seeing `onPlayerError` for a session that then played on would show an error
        // dialogue over a playing video, which is the whole reason this is asserted and not assumed.
        val errors = mutableListOf<Any?>()
        val player = harness.buildPlayer(
            content = dashThenHls(),
            faults = failEveryAttemptOnDash(),
            policy = retryPolicyOf(ONE_RETRY),
            resilience = Resilience.standard(),
        )
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    errors += error
                }

                override fun onPlayerErrorChanged(error: PlaybackException?) {
                    errors += error
                }
            },
        )

        player.setMediaRequest(dashThenHlsRequest())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNull()
        assertWithMessage("failures reported to a consumer's listener").that(errors).isEmpty()
    }

    @Test
    fun aRequestWithOneSourceFailsWhereItAlwaysDid() {
        // The rung is reached only when there is somewhere to go. A request naming one source is what
        // most requests are, and this is the assertion that says they behave exactly as they did
        // before rung 4 existed.
        val content = TestContent.dash(SEGMENTS, mirrorHost = MIRROR_HOST)
        val player = harness.buildPlayer(
            content = content,
            faults = failEveryAttemptOnDash(),
            policy = retryPolicyOf(ONE_RETRY),
            resilience = Resilience.standard(),
        )

        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToFailure(player)

        assertThat(player.playerError).isNotNull()
        assertThat(player.currentMediaItem?.localConfiguration?.uri.toString())
            .isEqualTo(SyntheticDashStream.MANIFEST_URI)
    }

    @Test
    fun aPlayerWithoutResilienceOpensTheFirstSourceAndNothingElse() {
        // ADR-0011 rule 14 for this rung, counted as behaviour: a core-only player asks nobody, so a
        // request carrying a perfectly good second source still ends where its first one failed. It is
        // what makes every test above a test of the ladder rather than of something Media3 does.
        val player = harness.buildPlayer(
            content = dashThenHls(),
            faults = failEveryAttemptOnDash(),
            policy = retryPolicyOf(ONE_RETRY),
        )

        player.setMediaRequest(dashThenHlsRequest())
        harness.playToFailure(player)

        assertThat(player.playerError).isNotNull()
        assertWithMessage("anything at all fetched from the second source")
            .that(harness.networkRequests(player).filter { it.host == FALLBACK_HOST })
            .isEmpty()
    }

    /**
     * The two sources of every request below, over one transport: a DASH stream that will be faulted
     * and an HLS stream that will not.
     *
     * The DASH stream is the subject — it is what a player is pointed at first — and it carries a
     * mirror so that rung 2 has a location to move to and be seen failing at. The HLS stream is
     * reached only by URI, which is all a second source ever is.
     */
    private fun dashThenHls(): TestContent =
        TestContent.dash(SEGMENTS, mirrorHost = MIRROR_HOST).alsoServing(fallbackHls)

    private fun dashThenHlsRequest(): MediaRequest =
        MediaRequest.Builder(CONTENT)
            .addSource(SyntheticDashStream.MANIFEST_URI)
            .addSource(fallbackHls.sourceUri)
            .build()

    /**
     * A 502 at both of the DASH stream's locations that never relents, on media segments only.
     *
     * Media segments only, and a 502 rather than a 500, for `FallbackPlaybackTest`'s reasons: the
     * manifest is what names the alternatives rung 2 moves between, and Media3's own fallback table
     * lists 403, 404, 410, 416, 500 and 503 and not this one (// ref: RFC 9110 §15.6.3, and
     * `DefaultLoadErrorHandlingPolicy.isEligibleForFallback`) — so a fallback that happens is the
     * ladder's rather than Media3's.
     *
     * Addressed at each of the first source's hosts rather than at neither, because a fault with no
     * host would also fault the second source and there would be nothing to fall back *to*.
     *
     * [originIndex] narrows the *origin's* half to one segment, which is how a test gets playback to
     * happen before the failure. The mirror's half is never narrowed, and that is a property of the
     * addressing rather than a choice: indices count distinct resources per host, so the mirror's
     * segment 2 is its own index 0 — "the segment the origin refused" has no index at the mirror, and
     * a mirror faulted at the same number would serve the very segment the fallback moved for.
     */
    private fun failEveryAttemptOnDash(originIndex: Int? = null): FaultScript = FaultScript.Builder()
        .failWithHttpStatus(BAD_GATEWAY, ResourceKind.MEDIA_SEGMENT, originIndex, host = SyntheticDashStream.HOST)
        .failWithHttpStatus(BAD_GATEWAY, ResourceKind.MEDIA_SEGMENT, host = MIRROR_HOST)
        .build()

    private fun hlsSegments(player: SuperPlayer): List<NetworkRequest> =
        harness.networkRequests(player)
            .filter { it.kind == ResourceKind.MEDIA_SEGMENT && it.host == FALLBACK_HOST }

    /** Which CDN served a request, parsed back out of the URL for `FallbackPlaybackTest`'s reason. */
    private val NetworkRequest.host: String?
        get() = Uri.parse(uri).host

    /**
     * A policy that decides the profile's numbers for everything but the retry half, hand-written for
     * the reason `FallbackPlaybackTest`'s is: the point of each test is a specific budget, and the
     * profile rows are argued in `StaticProfilePolicy` rather than here.
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
            retry = RetryPolicy(segment = segment),
        )
    }

    /**
     * A collector that records the session edges and nothing else.
     *
     * Hand-written rather than `superplayer-telemetry`'s, because the claim under test is core's
     * signalling — how many sessions one viewing opened — and not what any collector derives from
     * Media3's analytics. It also keeps a phase 5 module's tests off a phase 2 one it has no other
     * use for.
     */
    private class RecordingCollector : TelemetryCollector {
        val started = mutableListOf<String>()
        var ended: Int = 0

        override fun attach(player: SuperPlayer) = Unit
        override fun startSession(contentId: String, sessionId: String) {
            started += contentId
        }

        override fun endSession() {
            ended++
        }

        override fun declareIntent(monotonicTimeMs: Long) = Unit
        override fun decisionChanged(decision: PlaybackDecision, trigger: DecisionTrigger) = Unit
        override fun detach() = Unit
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e02"

        /** // ref: RFC 9110 §15.6.3, Bad Gateway. See [failEveryAttemptOnDash] for why this one. */
        const val BAD_GATEWAY = 502

        /** Enough segments that the second source has somewhere to play on to. */
        const val SEGMENTS = 8

        /**
         * Late enough that whole segments of the first source play before it, which is what gives the
         * position test a position to preserve, and inside [SEGMENTS].
         */
        const val FAULTED_SEGMENT = 2

        /** The first ask and the one retry the budget allows, before the rung above. */
        const val ATTEMPTS_ALLOWED = 2

        val ONE_RETRY = RetryBudget(maxRetries = 1, initialBackoffMs = 50, maxBackoffMs = 200)

        /** The second location the DASH manifest names — rung 2's, faulted here so rung 4 is reached. */
        const val MIRROR_HOST = "mirror.superplayer.test"

        /** Far enough past the switch that the second source is playing rather than merely opened. */
        const val PLAYED_MS = 20_000L

        /**
         * Where the second source is published.
         *
         * A host of its own, and not decoration: both synthetic streams sit on
         * `SyntheticHlsStream.HOST` under different paths, so a fault addressed at the DASH stream's
         * host would fault the fallback too and there would be nothing to fall back *to*. Which host
         * served a request is also how each assertion below tells the two sources apart.
         */
        const val FALLBACK_HOST = "fallback.superplayer.test"

        /** The second source: the same programme as HLS, wherever [FALLBACK_HOST] is. */
        val fallbackHls: TestContent = TestContent.hls(SEGMENTS).servedFrom(FALLBACK_HOST)
    }
}
