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
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.BufferPolicy
import com.superplayer.core.DecisionInForce
import com.superplayer.core.LiveWindowTooShortException
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.core.TrackSelectionPolicy
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.random.Random

/**
 * The three budgets, asked the way Media3 asks.
 *
 * Everything a player can show is shown through a player, in `RetryPlaybackTest`. Two things cannot
 * be: a licence load, because Media3 routes one through a different `LoadErrorHandlingPolicy`
 * instance than the one a media source factory is given and SuperPlayer plumbs no DRM at all
 * ([RetryPolicy.licence] says so), and the fact that the budget is re-read on *every* consultation,
 * because a decision only changes on a player whose engine can honour a changed one and the retry
 * half is not one of the halves such a player's components hold. Both are properties of this object,
 * and this is where they are pinned.
 *
 * Robolectric for `Uri`, which every `DataSpec` carries, and for nothing else — there is no player
 * here.
 */
@RunWith(AndroidJUnit4::class)
class RetryingLoadErrorsTest {

    @Test
    fun oneExhaustedBudgetDoesNotSpendAnother() {
        // A manifest that has spent everything it had, in a session whose segments have barely
        // started spending theirs. `PRD.md` §3.3 asks for exactly this separation by name.
        val policy = RetryingLoadErrors.forPlayer(
            decisionsOf(
                RetryPolicy(
                    manifest = RetryBudget(maxRetries = 1, initialBackoffMs = 10, maxBackoffMs = 10),
                    segment = RetryBudget(maxRetries = 4, initialBackoffMs = 10, maxBackoffMs = 10),
                ),
            ),
            Random(SEED),
        )

        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_MANIFEST, errorCount = 1))).isAtLeast(0L)
        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_MANIFEST, errorCount = 2)))
            .isEqualTo(C.TIME_UNSET)
        // The same second failure, on a segment, is still well inside its own budget.
        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_MEDIA, errorCount = 2))).isAtLeast(0L)
        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_MEDIA, errorCount = 4))).isAtLeast(0L)
        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_MEDIA, errorCount = 5)))
            .isEqualTo(C.TIME_UNSET)
    }

    @Test
    fun aLicenceLoadSpendsTheLicenceBudgetAndNeitherOfTheOthers() {
        // The budget `RetryPolicy.licence` declares and nothing routes to yet. Asserting it here is
        // what makes the declaration honest rather than decorative: the number is in force the day
        // Phase 6 plumbs a `DrmSessionManager` through to this object, and this is the test that
        // will fail if the addressing is wrong then.
        val policy = RetryingLoadErrors.forPlayer(
            decisionsOf(
                RetryPolicy(
                    manifest = NO_RETRIES,
                    segment = NO_RETRIES,
                    licence = RetryBudget(maxRetries = 2, initialBackoffMs = 10, maxBackoffMs = 10),
                ),
            ),
            Random(SEED),
        )

        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_DRM, errorCount = 1))).isAtLeast(0L)
        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_DRM, errorCount = 2))).isAtLeast(0L)
        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_DRM, errorCount = 3)))
            .isEqualTo(C.TIME_UNSET)
        // And the two budgets it did not spend are still where they were: at zero.
        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_MANIFEST, errorCount = 1)))
            .isEqualTo(C.TIME_UNSET)
        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_MEDIA, errorCount = 1)))
            .isEqualTo(C.TIME_UNSET)
    }

    @Test
    fun theBudgetInForceIsReadAtEveryConsultationRatherThanAtConstruction() {
        // ADR-0011 rule 11, which is the reason this object holds a window onto the decision instead
        // of a copy of it: an adaptive policy that shortens the budget after a stall has shortened
        // it for the *next failure*, with nothing rebuilt and no new player.
        val decisions = DecisionInForce()
        var inForce = decisionWith(RetryPolicy(segment = RetryBudget(maxRetries = 3, initialBackoffMs = 10, maxBackoffMs = 10)))
        decisions.fedBy { inForce }
        val policy = RetryingLoadErrors.forPlayer(decisions, Random(SEED))

        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_MEDIA, errorCount = 3))).isAtLeast(0L)
        assertThat(policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA)).isEqualTo(3)

        inForce = decisionWith(RetryPolicy(segment = NO_RETRIES))

        assertThat(policy.getRetryDelayMsFor(transferFailure(C.DATA_TYPE_MEDIA, errorCount = 1)))
            .isEqualTo(C.TIME_UNSET)
        assertThat(policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA)).isEqualTo(0)
    }

    @Test
    fun aFailureTheClassifierCallsUnretryableSpendsNoneOfItsBudget() {
        // The class decides whether rung 1 is worth anything at all, and the budget only bounds it
        // (ADR-0011 rule 1). A live window no playhead fits inside is core's own detection, mapped
        // to `Content.ManifestInvalid` — the same bytes describe the same impossible window however
        // many times they are fetched, so the budget stays unspent for the rungs above.
        val policy = RetryingLoadErrors.forPlayer(
            decisionsOf(RetryPolicy(manifest = RetryBudget(maxRetries = 5, initialBackoffMs = 10, maxBackoffMs = 10))),
            Random(SEED),
        )
        val tooShort = LiveWindowTooShortException(
            manifestUri = MANIFEST_URI,
            timeShiftBufferDepthMs = 2_000,
            segmentDurationMs = 4_000,
            availabilityTimeOffsetMs = 0,
        )

        assertThat(ErrorClassifier.classify(tooShort).retryable).isFalse()
        assertThat(policy.getRetryDelayMsFor(loadError(C.DATA_TYPE_MANIFEST, tooShort, errorCount = 1)))
            .isEqualTo(C.TIME_UNSET)
    }

    @Test
    fun noFallbackIsOfferedWhileRungOneStillHasBudget() {
        // Media3 asks a chunk source's policy for a fallback *before* it asks for a retry delay,
        // which is ADR-0011 rule 7's ladder upside down. The answer here is null for as long as rung
        // 1 has anything left, which is what stops a rung above being reached before the one beneath
        // it has failed. Delete this and rungs 2 and 3 would be reached on the first failure of every
        // load, silently, and every budget below them would go unspent.
        val policy = policyWithOneRetry()

        assertThat(policy.getFallbackSelectionFor(everywhereToGo(), transferFailure(C.DATA_TYPE_MEDIA, errorCount = 1)))
            .isNull()
    }

    @Test
    fun rungTwoIsReachedOnceRungOneSBudgetIsSpent() {
        // The other half of the order: the ask after the last the budget allowed is the one rung 2
        // takes on, and what it answers is a *location* — the next CDN host or `BaseURL` — rather
        // than the track exclusion that sits above it (ADR-0011 rule 7).
        val policy = policyWithOneRetry()

        val selection = policy.getFallbackSelectionFor(everywhereToGo(), transferFailure(C.DATA_TYPE_MEDIA, errorCount = 2))

        assertThat(selection).isNotNull()
        assertThat(checkNotNull(selection).type).isEqualTo(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)
        assertThat(selection.exclusionDurationMs).isEqualTo(NextHost.LOCATION_EXCLUSION_MS)
    }

    @Test
    fun rungThreeIsReachedOnlyOnceThereIsNoLocationLeft() {
        // A rung is reached when the one below it has failed, and "failed" for rung 2 includes
        // having nowhere to go: every location already excluded is a rung 2 that cannot act, and
        // only then is the failing rendition taken out of the ladder.
        val policy = policyWithOneRetry()
        val noLocationLeft = LoadErrorHandlingPolicy.FallbackOptions(
            /* numberOfLocations= */ 2,
            /* numberOfExcludedLocations= */ 2,
            /* numberOfTracks= */ 2,
            /* numberOfExcludedTracks= */ 0,
        )

        val selection = policy.getFallbackSelectionFor(noLocationLeft, transferFailure(C.DATA_TYPE_MEDIA, errorCount = 2))

        assertThat(checkNotNull(selection).type).isEqualTo(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)
        assertThat(selection.exclusionDurationMs).isEqualTo(ExcludeVariant.TRACK_EXCLUSION_MS)
    }

    @Test
    fun aClassTheLadderWillNotExcludeAVariantForLeavesTheLadderIntact() {
        // Which rungs a class is offered is [FallbackLadder]'s, and `FallbackLadderTest` is where
        // every routing is stated; what is pinned here is that the routing survives the trip through
        // Media3's own question. A live window no playhead fits inside is core's detection, mapped
        // to `Content.ManifestInvalid`: another host may hold a sound manifest, so rung 2 takes it,
        // and no rendition of an unusable description is usable, so rung 3 does not.
        val policy = policyWithOneRetry()
        val tooShort = LiveWindowTooShortException(
            manifestUri = MANIFEST_URI,
            timeShiftBufferDepthMs = 2_000,
            segmentDurationMs = 4_000,
            availabilityTimeOffsetMs = 0,
        )
        val noLocationLeft = LoadErrorHandlingPolicy.FallbackOptions(
            /* numberOfLocations= */ 2,
            /* numberOfExcludedLocations= */ 2,
            /* numberOfTracks= */ 2,
            /* numberOfExcludedTracks= */ 0,
        )

        assertThat(policy.getFallbackSelectionFor(everywhereToGo(), loadError(C.DATA_TYPE_MANIFEST, tooShort, errorCount = 1)))
            .isNotNull()
        assertThat(policy.getFallbackSelectionFor(noLocationLeft, loadError(C.DATA_TYPE_MANIFEST, tooShort, errorCount = 1)))
            .isNull()
    }

    /** One retry allowed, so `errorCount` 1 is inside rung 1 and 2 is the first ask above it. */
    private fun policyWithOneRetry(): RetryingLoadErrors = RetryingLoadErrors.forPlayer(
        decisionsOf(RetryPolicy(segment = RetryBudget(maxRetries = 1, initialBackoffMs = 10, maxBackoffMs = 10))),
        Random(SEED),
    )

    /** A load with a second location and a second rendition both still available. */
    private fun everywhereToGo() = LoadErrorHandlingPolicy.FallbackOptions(
        /* numberOfLocations= */ 2,
        /* numberOfExcludedLocations= */ 0,
        /* numberOfTracks= */ 2,
        /* numberOfExcludedTracks= */ 0,
    )

    private fun decisionsOf(retry: RetryPolicy): DecisionInForce = DecisionInForce().apply {
        val decision = decisionWith(retry)
        fedBy { decision }
    }

    private fun decisionWith(retry: RetryPolicy): PlaybackDecision = PlaybackDecision(
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
        retry = retry,
    )

    /**
     * A load that failed the way most do — the transfer did not complete, with nothing on it that
     * narrows the class beyond `Transient.Network`, which is what makes the budget the only thing
     * deciding the answer.
     */
    private fun transferFailure(dataType: Int, errorCount: Int) =
        loadError(dataType, IOException("Injected transfer failure"), errorCount)

    private fun loadError(dataType: Int, exception: IOException, errorCount: Int) =
        LoadErrorHandlingPolicy.LoadErrorInfo(
            LoadEventInfo(
                /* loadTaskId= */ LoadEventInfo.getNewId(),
                DataSpec(Uri.parse(MANIFEST_URI)),
                /* elapsedRealtimeMs= */ 0,
            ),
            MediaLoadData(dataType),
            exception,
            errorCount,
        )

    private companion object {
        const val SEED = 20_260_916L
        const val MANIFEST_URI = "https://cdn.superplayer.test/episode/master.m3u8"

        /** A budget that allows nothing, so a spend of it is visible as the first refusal. */
        val NO_RETRIES = RetryBudget(maxRetries = 0, initialBackoffMs = 0, maxBackoffMs = 0)
    }
}
