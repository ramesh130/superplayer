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
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.RetryBudget
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.random.Random

/**
 * Which rungs each class is offered, which is the ladder's own decision and not the taxonomy's.
 *
 * [FailureClass.rungCeiling] says how far a class *may* climb; ADR-0011 rule 7 leaves "which rungs
 * below it are worth attempting" to the ladder, and this file is that routing written down. It has
 * to be stated here rather than through a player because the classes it is about do not all reach a
 * player's load-error path: Media3 hands a `LoadErrorHandlingPolicy` an `IOException`, so a decoder
 * failure — which the ladder must route past rungs 2 and 3 to reach its own remedy — arrives at the
 * ladder from the player-error path rung 4 already sits on and rungs 5 and 6 are being built on
 * (#182, #183). A [FailedLoad] is the value every load path hands a rung, and it is what is offered
 * here; rung 4 is asked about a `PlaybackException` instead, because by then the failure has stopped
 * being a load and become a failure of the session.
 *
 * What the *player* does with the rungs this file routes to is `FallbackPlaybackTest` for rungs 2 and
 * 3, and `NextSourcePlaybackTest` for rung 4.
 *
 * Robolectric for `Uri`, which every `DataSpec` carries, and for nothing else.
 */
@RunWith(AndroidJUnit4::class)
class FallbackLadderTest {

    private val ladder = FallbackLadder.standard(Random(SEED))

    @Test
    fun aDecoderFailureWhoseRemedyIsARecreateIsOfferedNeitherAHostNorAnExclusion() {
        // `Device.DecoderTransient` says where it is defined that the ladder "reaches it [rung 5]
        // without trying a host, because no host has anything to do with it" — and its ceiling is
        // rung 5, so a ladder reading the ceiling alone would have offered it both rungs on the way
        // past. Excluding a rendition is the same mistake one step later: every instance on the
        // device being held is not a property of which rendition asked for one.
        assertThat(climb(FailureClass.Device.DecoderTransient)).isEqualTo(RungOutcome.Escalate)
    }

    @Test
    fun aDecoderThatCouldNotBeBuiltIsOfferedAnotherRenditionButNotAnotherHost() {
        // The asymmetry is `Device.DecoderInit`'s own: "another variant or another source may be
        // within reach", and a rendition is exactly what a device with no decoder for this one
        // needs. No host changes which codecs a device has, so rung 2 is not offered — which is
        // visible here as a *track* selection rather than a location one, with both available.
        val outcome = climb(FailureClass.Device.DecoderInit)

        assertThat(selectionTypeOf(outcome)).isEqualTo(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)
    }

    @Test
    fun anUnusableManifestIsOfferedAnotherHostButNotARenditionOfItself() {
        // `Content.ManifestInvalid` is the class the ladder splits the two rungs on: a publication
        // defect at one host can be absent from another, so rung 2 takes it; no rendition of an
        // unusable description is usable, so rung 3 leaves the ladder intact. This is the acceptance
        // criterion "a class that must not trigger it leaving the ladder intact", and it is the
        // classifier driving the exclusion rather than Media3's own error-code table.
        assertThat(selectionTypeOf(climb(FailureClass.Content.ManifestInvalid)))
            .isEqualTo(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)
        assertThat(climb(FailureClass.Content.ManifestInvalid, options = noLocationLeft()))
            .isEqualTo(RungOutcome.Escalate)
    }

    @Test
    fun aFatalClassIsOfferedNoRungAtAllHoweverMuchThereIsToFallBackTo() {
        // ADR-0011 rule 7: "a `Fatal.Unsupported` goes to rung 6 at once". Its ceiling is the typed
        // error, which is the one ceiling that is not a permission to climb — rung 6 is where the
        // ladder has stopped rather than something it performs. Content the engine has *named*
        // unsupported plays no better from another edge and no better at another bitrate.
        assertThat(climb(FailureClass.Fatal.Unsupported)).isEqualTo(RungOutcome.Escalate)
    }

    @Test
    fun aTransferFailureClimbsRungOneThenTheHostThenTheRendition() {
        // The order itself, on the class that may reach every one of the three: rung 1 while the
        // budget lasts, then a location, and a rendition only once there is no location left
        // (ADR-0011 rule 7). `RetryingLoadErrorsTest` pins the same order against the questions
        // Media3 actually asks, which it asks in the opposite order; this is the ladder's own.
        assertWithMessage("inside the budget").that(climb(FailureClass.Transient.Network, retry = 1))
            .isInstanceOf(RungOutcome.RetryAfter::class.java)
        assertWithMessage("budget spent").that(selectionTypeOf(climb(FailureClass.Transient.Network, retry = 2)))
            .isEqualTo(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)
        assertWithMessage("no location left")
            .that(selectionTypeOf(climb(FailureClass.Transient.Network, retry = 2, options = noLocationLeft())))
            .isEqualTo(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)
    }

    @Test
    fun aRungWithNowhereToGoIsNotARungTaken() {
        // Media3 counts what is left, and a content with one location and one rendition has neither
        // dimension to move in — an HLS chunk source reports exactly that for locations, always. The
        // climb then leaves the top of this ladder, which is where rungs 4 to 6 begin.
        val nowhereToGo = LoadErrorHandlingPolicy.FallbackOptions(
            /* numberOfLocations= */ 1,
            /* numberOfExcludedLocations= */ 0,
            /* numberOfTracks= */ 1,
            /* numberOfExcludedTracks= */ 0,
        )

        assertThat(climb(FailureClass.Transient.Network, retry = 2, options = nowhereToGo))
            .isEqualTo(RungOutcome.Escalate)
    }

    @Test
    fun aClimbFromTheQuestionThatCarriesNoOptionsReachesRungOneAndNoFurther() {
        // `getRetryDelayMsFor` is asked with the load alone, so the rungs that answer with a
        // selection have nothing to name back and decline. Null options are "not asked" rather than
        // "nothing available", and the reason that costs nothing is in `FallbackLadder`'s KDoc.
        assertThat(climb(FailureClass.Transient.Network, retry = 1, options = null))
            .isInstanceOf(RungOutcome.RetryAfter::class.java)
        assertThat(climb(FailureClass.Transient.Network, retry = 2, options = null))
            .isEqualTo(RungOutcome.Escalate)
    }

    @Test
    fun theRungAbovePlaysAnotherSourceForEveryClassThatMayReachItAndNoOther() {
        // Rung 4 is `NextSource`, and it is the ladder's routing on the other side of ADR-0011 rule
        // 5's boundary: core performs the re-adoption, this decides whether it is worth one. What a
        // player then does with the answer is `NextSourcePlaybackTest`.
        //
        // Offered to every class whose ceiling reaches it — a network failure, and a manifest one
        // rung 3 could do nothing for, since a publication defect at one source can be absent from
        // another the same way it can be absent from another host.
        assertThat(NextSource.opensNextSource(errorOf(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))).isTrue()
        assertThat(NextSource.opensNextSource(errorOf(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)))
            .isTrue()
        // Refused where the ceiling stops below it: content this device cannot play at all is content
        // a second manifest of the same programme would fail on too, so nothing is downloaded to find
        // that out and the failure goes to rung 6 (`Fatal.Unsupported`).
        assertThat(NextSource.opensNextSource(errorOf(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)))
            .isFalse()
        // And refused where the ceiling is *above* it, which is the case only a routing decision can
        // catch: a decoder that was working and stopped has a remedy at rung 5, and a second manifest
        // fetched on the way there costs a viewer a startup for nothing.
        assertWithMessage("a transient decoder failure was offered another source")
            .that(NextSource.opensNextSource(errorOf(PlaybackException.ERROR_CODE_DECODING_FAILED)))
            .isFalse()
    }

    /** A failure as the player delivers one: a code and nothing else for the classifier to read. */
    private fun errorOf(errorCode: Int): PlaybackException =
        PlaybackException("Injected failure", /* cause= */ null, errorCode)

    private fun climb(
        failureClass: FailureClass,
        retry: Int = 2,
        options: LoadErrorHandlingPolicy.FallbackOptions? = everywhereToGo(),
    ): RungOutcome = ladder.climb(
        FailedLoad(
            failureClass = failureClass,
            budget = ONE_RETRY,
            retry = retry,
            info = LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(LoadEventInfo.getNewId(), DataSpec(Uri.parse(SEGMENT_URI)), /* elapsedRealtimeMs= */ 0),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                IOException("Injected transfer failure"),
                retry,
            ),
            fallbackOptions = options,
        ),
    )

    private fun selectionTypeOf(outcome: RungOutcome): Int {
        assertThat(outcome).isInstanceOf(RungOutcome.FallBackTo::class.java)
        return (outcome as RungOutcome.FallBackTo).selection.type
    }

    /** A load with a second location and a second rendition both still available. */
    private fun everywhereToGo() = LoadErrorHandlingPolicy.FallbackOptions(
        /* numberOfLocations= */ 2,
        /* numberOfExcludedLocations= */ 0,
        /* numberOfTracks= */ 2,
        /* numberOfExcludedTracks= */ 0,
    )

    /** Every location already excluded, which is a rung 2 that has failed by having nowhere to go. */
    private fun noLocationLeft() = LoadErrorHandlingPolicy.FallbackOptions(
        /* numberOfLocations= */ 2,
        /* numberOfExcludedLocations= */ 2,
        /* numberOfTracks= */ 2,
        /* numberOfExcludedTracks= */ 0,
    )

    private companion object {
        const val SEED = 20_260_916L
        const val SEGMENT_URI = "https://cdn.superplayer.test/episode/segment3.ts"

        /** One retry allowed, so retry 1 is inside rung 1 and retry 2 is the first ask above it. */
        val ONE_RETRY = RetryBudget(maxRetries = 1, initialBackoffMs = 10, maxBackoffMs = 10)
    }
}
