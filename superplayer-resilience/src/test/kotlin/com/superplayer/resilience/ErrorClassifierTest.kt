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

import android.media.MediaCodec
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.FailureCategory
import com.superplayer.core.LiveWindowTooShortException
import com.superplayer.core.LoadKind
import com.superplayer.core.RequestStamp
import com.superplayer.core.StaleLivePlaylistException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.IOException

/**
 * That a failure acquires exactly one meaning here, and that it always acquires one.
 *
 * Pure: there is nothing to play to classify a failure, so nothing is played (`docs/testing.md`
 * allows a unit test where the input allows one, and ADR-0011 rule 2's totality is a property of the
 * function rather than of a corpus that happens to exercise it). What a player produces is the
 * subject of the phase's exit test (#184); what this asserts is the mapping itself.
 *
 * Robolectric, not for a player but for the two Android types the evidence is made of: `Uri`, which
 * every `DataSpec` carries, and `MediaCodec.CodecException`, whose constructor the framework hides
 * from an app and the mocked `android.jar` of a plain JVM test does not implement at all.
 */
@RunWith(RobolectricTestRunner::class)
class ErrorClassifierTest {

    // Every leaf of the taxonomy, listed by hand: a leaf added without a row here is a leaf nothing
    // asserts a name, a category or a ceiling for.
    private val everyClass = listOf(
        FailureClass.Transient.Network,
        FailureClass.Transient.CdnEdge,
        FailureClass.Content.ManifestInvalid,
        FailureClass.Content.SegmentGap,
        FailureClass.Device.DecoderInit,
        FailureClass.Device.DecoderTransient,
        FailureClass.Drm.Provisioning,
        FailureClass.Drm.LicenceAcquisition,
        FailureClass.Drm.Unsupported,
        FailureClass.Fatal.Unsupported,
    )

    @Test
    fun theLadderIsTheSixRungsInTheOrderAdr0011Rule7Fixes() {
        assertThat(FallbackRung.entries.map { it.name }).containsExactly(
            "RETRY_SAME_URL",
            "NEXT_HOST",
            "EXCLUDE_VARIANT",
            "NEXT_SOURCE",
            "RECREATE_DECODER",
            "TYPED_ERROR",
        ).inOrder()
    }

    @Test
    fun everyClassCarriesADistinctStableNameAndAnswersTheThreeQuestionsRule1Asks() {
        assertThat(everyClass.map { it.stableName }).containsNoDuplicates()
        for (failure in everyClass) {
            // A name a pipeline groups by and a log line carries: no spaces, and the branch it
            // belongs to is readable in it.
            assertWithMessage("$failure").that(failure.stableName).matches("[A-Za-z]+\\.[A-Za-z]+")
            assertWithMessage("$failure").that(failure.toString()).isEqualTo(failure.stableName)
        }
    }

    @Test
    fun theCeilingSaysHowFarEachClassMayClimb() {
        // Nothing the ladder can do helps content the engine has named unsupported, so the ceiling
        // is the typed error itself: rung 6 at once (ADR-0011 rule 7).
        assertThat(FailureClass.Fatal.Unsupported.rungCeiling).isEqualTo(FallbackRung.TYPED_ERROR)
        assertThat(FailureClass.Fatal.Unsupported.retryable).isFalse()
        // The one class whose remedy is the top rung; rule 7 names it.
        assertThat(FailureClass.Device.DecoderTransient.rungCeiling).isEqualTo(FallbackRung.RECREATE_DECODER)
        // Bytes that did not arrive are what the transfer rungs are for, and a decoder is not
        // implicated in one.
        assertThat(FailureClass.Transient.Network.rungCeiling).isEqualTo(FallbackRung.NEXT_SOURCE)
        assertThat(FailureClass.Transient.Network.retryable).isTrue()
        // The same bytes parse the same way, so rung 1 is refused even though the rungs above apply.
        assertThat(FailureClass.Content.ManifestInvalid.retryable).isFalse()
        assertThat(FailureClass.Content.ManifestInvalid.rungCeiling).isEqualTo(FallbackRung.NEXT_SOURCE)
    }

    @Test
    fun theCategoryTableIsOneToOneAndEveryClassHasARow() {
        assertThat(everyClass.map { it.category }).containsExactly(
            FailureCategory.NETWORK,
            FailureCategory.NETWORK,
            FailureCategory.SOURCE,
            FailureCategory.SOURCE,
            FailureCategory.DECODER,
            FailureCategory.DECODER,
            FailureCategory.DRM,
            FailureCategory.DRM,
            FailureCategory.DRM,
            FailureCategory.SOURCE,
        ).inOrder()
    }

    @Test
    fun anIoBandWithNoEvidenceIsATransientNetworkFailure() {
        assertThat(classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            .isEqualTo(FailureClass.Transient.Network)
        assertThat(classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
            .isEqualTo(FailureClass.Transient.Network)
        assertThat(classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
            .isEqualTo(FailureClass.Transient.Network)
    }

    @Test
    fun aParseFailureIsAnInvalidManifestAndAnUnsupportedOneIsFatal() {
        assertThat(classify(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED))
            .isEqualTo(FailureClass.Content.ManifestInvalid)
        // The engine named it: unsupported, not merely unparsed.
        assertThat(classify(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED))
            .isEqualTo(FailureClass.Fatal.Unsupported)
        assertThat(classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
            .isEqualTo(FailureClass.Fatal.Unsupported)
        assertThat(classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
            .isEqualTo(FailureClass.Fatal.Unsupported)
        // A malformed container is the media itself, not its description.
        assertThat(classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
            .isEqualTo(FailureClass.Content.SegmentGap)
    }

    @Test
    fun theDecodingAndRendererBandsAreDeviceFailuresSplitByWhetherARecreateCanHelp() {
        assertThat(classify(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
            .isEqualTo(FailureClass.Device.DecoderInit)
        assertThat(classify(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES))
            .isEqualTo(FailureClass.Device.DecoderInit)
        assertThat(classify(PlaybackException.ERROR_CODE_DECODING_FAILED))
            .isEqualTo(FailureClass.Device.DecoderTransient)
        assertThat(classify(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED))
            .isEqualTo(FailureClass.Device.DecoderInit)
        assertThat(classify(PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED))
            .isEqualTo(FailureClass.Device.DecoderTransient)
    }

    @Test
    fun aCodecThatSaysItIsTransientIsTransientWhateverTheCodeSays() {
        // The case a feed meets: the decoder could not be initialised because every instance the
        // device has is held, which a recreate after one is released succeeds at.
        val error = PlaybackException(
            "init failed",
            transientCodecException(),
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )
        assertThat(ErrorClassifier.classify(error)).isEqualTo(FailureClass.Device.DecoderTransient)
    }

    @Test
    fun theDrmBandSplitsIntoTheLeavesPhase6WillGrowInto() {
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED))
            .isEqualTo(FailureClass.Drm.Provisioning)
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED))
            .isEqualTo(FailureClass.Drm.LicenceAcquisition)
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED))
            .isEqualTo(FailureClass.Drm.LicenceAcquisition)
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED))
            .isEqualTo(FailureClass.Drm.Unsupported)
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED))
            .isEqualTo(FailureClass.Drm.Unsupported)
    }

    @Test
    fun aRefusedSegmentIsAnEdgeFailureAndTheSameStatusOnAManifestIsNot() {
        for (status in listOf(401, 403, 404)) {
            assertWithMessage("$status on a segment")
                .that(ErrorClassifier.classify(httpFailure(status, LoadKind.MEDIA)))
                .isEqualTo(FailureClass.Transient.CdnEdge)
            // A manifest the CDN refuses is not "the manifest is fine" — the evidence that separates
            // the two classes is missing, so the band decides (PRD.md §3.3).
            assertWithMessage("$status on a manifest")
                .that(ErrorClassifier.classify(httpFailure(status, LoadKind.MANIFEST)))
                .isEqualTo(FailureClass.Transient.Network)
        }
        // A player with neither cache nor resilience stamps nothing, and an unstamped request says
        // nothing about which load it was.
        assertThat(ErrorClassifier.classify(httpFailure(403, LoadKind.UNCLASSIFIED)))
            .isEqualTo(FailureClass.Transient.Network)
        // An edge that is merely unwell is not an edge that has lost the object.
        assertThat(ErrorClassifier.classify(httpFailure(503, LoadKind.MEDIA)))
            .isEqualTo(FailureClass.Transient.Network)
    }

    @Test
    fun aSegmentShorterThanItsDescriptionPromisedIsAGap() {
        assertThat(ErrorClassifier.classify(httpFailure(416, LoadKind.MEDIA)))
            .isEqualTo(FailureClass.Content.SegmentGap)
        assertThat(classify(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE))
            .isEqualTo(FailureClass.Content.SegmentGap)
    }

    @Test
    fun coresStalePlaylistIsMappedByTheCauseCoreNamedRatherThanReDerived() {
        // An intermediary holding a copy is an edge defect, and the rungs above the playlist — the
        // next host, the next source — are exactly what may still play it (ADR-0011 rule 4).
        assertThat(ErrorClassifier.classify(stalePlaylist(StaleLivePlaylistException.LikelyCause.INTERMEDIARY_CACHE)))
            .isEqualTo(FailureClass.Transient.CdnEdge)
        // An origin that has stopped publishing has left a hole where segments were promised;
        // retrying the same bytes finds the same hole.
        assertThat(ErrorClassifier.classify(stalePlaylist(StaleLivePlaylistException.LikelyCause.ORIGIN)))
            .isEqualTo(FailureClass.Content.SegmentGap)
        // Wrapped as the player delivers it, and the mapping still wins over the band the engine
        // assigned — which is the disagreement ADR-0011 rule 3 predicted.
        val delivered = PlaybackException(
            "io",
            stalePlaylist(StaleLivePlaylistException.LikelyCause.ORIGIN),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        )
        assertThat(ErrorClassifier.classify(delivered)).isEqualTo(FailureClass.Content.SegmentGap)
        assertThat(ErrorClassifier.classify(delivered).category).isEqualTo(FailureCategory.SOURCE)
    }

    @Test
    fun coresTooShortLiveWindowIsAnInvalidManifest() {
        val tooShort = LiveWindowTooShortException(
            manifestUri = "https://cdn.example/live.mpd",
            timeShiftBufferDepthMs = 4_000,
            segmentDurationMs = 6_000,
            availabilityTimeOffsetMs = 0,
        )
        assertThat(ErrorClassifier.classify(tooShort)).isEqualTo(FailureClass.Content.ManifestInvalid)
        assertThat(
            ErrorClassifier.classify(
                PlaybackException("io", tooShort, PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
            ),
        ).isEqualTo(FailureClass.Content.ManifestInvalid)
    }

    @Test
    fun everyCodeTheEngineCouldDeliverLandsInTheTaxonomyAndNothingThrows() {
        // Every band Media3 documents, every gap between them, and the codes a renumbered or newer
        // engine could invent: ADR-0011 rule 2 is a property of this function, so it is asserted
        // over the whole space rather than over the corpus that happens to be at hand.
        for (code in -200..8_000) {
            val classified = ErrorClassifier.classify(PlaybackException("code $code", null, code))
            assertWithMessage("error code $code").that(everyClass).contains(classified)
        }
        // The session codes Media3 numbers below zero, the base an app's own codes start at, and the
        // far ends of the type.
        for (code in listOf(Int.MIN_VALUE, Int.MAX_VALUE, PlaybackException.CUSTOM_ERROR_CODE_BASE)) {
            assertThat(everyClass).contains(ErrorClassifier.classify(PlaybackException("far", null, code)))
        }
    }

    @Test
    fun aFailureThatIsNoPlaybackExceptionAtAllStillClassifies() {
        assertThat(ErrorClassifier.classify(IOException("socket closed"))).isEqualTo(FailureClass.Transient.Network)
        assertThat(ErrorClassifier.classify(IllegalStateException("nothing to do with playback")))
            .isEqualTo(FailureClass.Transient.Network)
        // The load-error path (#178) holds an `IOException` and no band at all, and the evidence on
        // it is read the same way.
        assertThat(ErrorClassifier.classify(httpFailure(403, LoadKind.MEDIA)))
            .isEqualTo(FailureClass.Transient.CdnEdge)
    }

    @Test
    fun aCauseChainThatNeverEndsDoesNotHangOrThrow() {
        val looping = object : IOException("looping") {
            override val cause: Throwable get() = this
        }
        assertThat(ErrorClassifier.classify(looping)).isEqualTo(FailureClass.Transient.Network)
        // Deep, and with the evidence past the bound: a bounded walk is allowed to miss it, but not
        // to fail.
        var deep: Throwable = httpFailure(403, LoadKind.MEDIA)
        repeat(200) { deep = IOException("wrapper", deep) }
        assertThat(everyClass).contains(ErrorClassifier.classify(deep))
    }

    private fun classify(errorCode: Int): FailureClass =
        ErrorClassifier.classify(PlaybackException("code $errorCode", null, errorCode))

    private fun httpFailure(status: Int, kind: LoadKind): InvalidResponseCodeException =
        InvalidResponseCodeException(
            status,
            "refused",
            /* cause= */ null,
            emptyMap(),
            DataSpec.Builder()
                .setUri("https://cdn.example/segment-1.ts")
                .setCustomData(RequestStamp(identity = null, kind = kind))
                .build(),
            /* responseBody= */ ByteArray(0),
        )

    private fun stalePlaylist(likelyCause: StaleLivePlaylistException.LikelyCause) =
        StaleLivePlaylistException(
            playlistUri = "https://cdn.example/live.m3u8",
            targetDurationMs = 6_000,
            unchangedForMs = 30_000,
            cacheBypassingReloads = 3,
            servedCacheControl = "max-age=60",
            likelyCause = likelyCause,
        )

    /**
     * A `MediaCodec.CodecException` the framework would have raised when a resource was momentarily
     * unavailable. Its constructor is hidden from apps (`@hide`), so the only way to hold a real one
     * — rather than a stand-in whose `isTransient` the classifier would not be reading — is
     * Robolectric's reflection helper against the real framework class.
     */
    private fun transientCodecException(): MediaCodec.CodecException =
        ReflectionHelpers.callConstructor(
            MediaCodec.CodecException::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            // ref: MediaCodec.CodecException.ACTION_TRANSIENT, the action code `isTransient()` reads.
            ClassParameter.from(Int::class.javaPrimitiveType, 1),
            ClassParameter.from(String::class.java, "resource busy"),
        )
}
