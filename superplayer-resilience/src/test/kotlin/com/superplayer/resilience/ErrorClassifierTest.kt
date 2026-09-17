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
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.FailureCategory
import com.superplayer.core.LiveWindowTooShortException
import com.superplayer.core.LoadKind
import com.superplayer.core.RequestStamp
import com.superplayer.core.SecurityDowngradeRefusedException
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
        FailureClass.Device.SecureDecoderInit,
        FailureClass.Drm.Provisioning,
        FailureClass.Drm.LicenceAcquisition,
        FailureClass.Drm.LicenceExpired,
        FailureClass.Drm.SystemError,
        FailureClass.Drm.Unsupported,
        FailureClass.Drm.DowngradeRefused,
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
    fun aDowngradeTheLicenceServerRefusedIsItsOwnClassWithItsOwnSentence() {
        // ADR-0012 rule 11 and #208. Read off the exception core raised rather than off any code,
        // which is why it is asserted with no `PlaybackException` around it at all: the evidence is
        // the exception's own fields, and a classifier that needed a band to find it would be reading
        // the weaker of the two things it was given (ADR-0011 rule 4).
        val refusal = refusedDowngrade()

        assertThat(ErrorClassifier.classify(refusal)).isEqualTo(FailureClass.Drm.DowngradeRefused)
        // Wrapped the way a renderer wraps it, because that is the shape a consumer catches.
        val delivered = PlaybackException(
            "delivered",
            DrmSession.DrmSessionException(refusal, PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION),
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        )
        assertThat(ErrorClassifier.classify(delivered)).isEqualTo(FailureClass.Drm.DowngradeRefused)
        // And the point of the leaf: the same code with no such cause is still `Drm.Unsupported`, so
        // the class is the exception's doing and not the band's.
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION))
            .isEqualTo(FailureClass.Drm.Unsupported)
    }

    @Test
    fun aRefusedDowngradeIsNotConfusedWithALicenceThatCouldNotBeFetched() {
        // The issue's own requirement, as two assertions: a key of its own, and a class that does not
        // offer a retry. An app that showed this one "check your connection and try again" would be
        // telling a viewer to mend something that is not broken.
        val refused = FailureClass.Drm.DowngradeRefused
        assertThat(refused.userMessageKey).isEqualTo(FailureClass.PROTECTION_UNAVAILABLE_MESSAGE_KEY)
        assertThat(refused.userMessageKey).isNotEqualTo(FailureClass.DRM_MESSAGE_KEY)
        assertThat(refused.userMessageKey).isNotEqualTo(FailureClass.LICENCE_EXPIRED_MESSAGE_KEY)
        assertThat(refused.retryable).isFalse()
        // Rung 6 at once: every rung below is a different place to get the bytes from, and this is
        // the same device meeting the same policy wherever they come from.
        assertThat(refused.rungCeiling).isEqualTo(FallbackRung.TYPED_ERROR)
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
            // #226's leaf is the third `DECODER` and not a seventh `DRM`: a secure decoder that
            // would not start is the device failing, not the entitlement, so the branch grew and
            // this table did not move — which is why `TelemetryEvent.SCHEMA_VERSION` did not either.
            FailureCategory.DECODER,
            // Every leaf of the DRM branch — the two #206 added and the one #208 added included:
            // the branch grew and the coarse bucket did not, which is why
            // `TelemetryEvent.SCHEMA_VERSION` did not move for either (`docs/telemetry-schema.md`,
            // *Release notes*).
            FailureCategory.DRM,
            FailureCategory.DRM,
            FailureCategory.DRM,
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
    fun aSecureDecoderThatWouldNotStartIsItsOwnClassAndTheOnlyOneALowerLevelMayCure() {
        // `PRD.md` §3.2's third way L1 becomes unusable, and #226's whole production change: a
        // secure decoder was selected for a protected session and the protected output path it
        // needs could not be allocated. The evidence is the `MediaCodecInfo` Media3's own renderer
        // attached — nothing about the code says it.
        val error = PlaybackException(
            "init failed",
            decoderInitializationException(secure = true),
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )

        val failure = ErrorClassifier.classify(error)
        assertThat(failure).isEqualTo(FailureClass.Device.SecureDecoderInit)
        assertThat(failure.lowerSecurityLevelMayHelp).isTrue()
        // A device failure, not a protection one: the licence was issued and the keys are held, so
        // rule 3's table does not move and telemetry buckets it where it always bucketed.
        assertThat(failure.category).isEqualTo(FailureCategory.DECODER)
        // What a viewer is told is the seventh key rather than an eighth: a programme refused on
        // this device, which is not the missing codec `DEVICE_MESSAGE_KEY` means.
        assertThat(failure.userMessageKey).isEqualTo(FailureClass.PROTECTION_UNAVAILABLE_MESSAGE_KEY)
        // Every rung below rung 6 is a different place to get bytes that need the same surface.
        assertThat(failure.rungCeiling).isEqualTo(FallbackRung.TYPED_ERROR)
    }

    @Test
    fun anOrdinaryDecoderThatWouldNotStartLowersNothing() {
        // The control that keeps #226 from being "downgrade on any decoder failure", and the reason
        // the flag could not simply be set on `Device.DecoderInit`: the identical code, the
        // identical exception type, and the decoder that failed not being the secure one.
        val error = PlaybackException(
            "init failed",
            decoderInitializationException(secure = false),
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )

        val failure = ErrorClassifier.classify(error)
        assertThat(failure).isEqualTo(FailureClass.Device.DecoderInit)
        assertThat(failure.lowerSecurityLevelMayHelp).isFalse()
        // And the rungs it could already climb are still open to it.
        assertThat(failure.rungCeiling).isEqualTo(FallbackRung.NEXT_SOURCE)
    }

    @Test
    fun noSecureDecoderAtAllKeepsItsRungsRatherThanConcedingALevel() {
        // The second control, and the narrower one. Media3 raises the same exception where it found
        // *no* decoder for the format, with `secureDecoderRequired` true on a protected session
        // exactly as above — so a classification reading that flag alone would land here too. It
        // must not: a device with no secure decoder for this codec may have one for the next variant
        // or the next source, and rung 4 is a cheaper remedy than a security level conceded for the
        // rest of the session. What tells the two apart is that no `MediaCodecInfo` was selected.
        val error = PlaybackException(
            "no suitable decoder",
            noDecoderWasFound(secureDecoderRequired = true),
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )

        val failure = ErrorClassifier.classify(error)
        assertThat(failure).isEqualTo(FailureClass.Device.DecoderInit)
        assertThat(failure.lowerSecurityLevelMayHelp).isFalse()
        assertThat(failure.rungCeiling).isEqualTo(FallbackRung.NEXT_SOURCE)
    }

    @Test
    fun aSecureDecoderTheCodecCallsTransientIsTransientRatherThanADowngrade() {
        // The order in `fromBand`, asserted rather than left to reading order. A momentary shortage
        // of secure decoder instances — a feed holding every one the device has — is cured by the
        // moment passing, and rung 5 is bounded; conceding a security level for the rest of the
        // session is not something a transient condition earns.
        val error = PlaybackException(
            "init failed",
            decoderInitializationException(secure = true, cause = transientCodecException()),
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )

        val failure = ErrorClassifier.classify(error)
        assertThat(failure).isEqualTo(FailureClass.Device.DecoderTransient)
        assertThat(failure.lowerSecurityLevelMayHelp).isFalse()
    }

    @Test
    fun everyCodeMedia3AssignsInTheDrmBandHasALeafOfItsOwn() {
        // The whole of Media3 1.11's DRM band, code by code, because the acceptance of #206 is that
        // none of them shares a leaf with a failure someone would act on differently.
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED))
            .isEqualTo(FailureClass.Drm.Provisioning)
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED))
            .isEqualTo(FailureClass.Drm.LicenceAcquisition)
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED))
            .isEqualTo(FailureClass.Drm.LicenceExpired)
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR))
            .isEqualTo(FailureClass.Drm.SystemError)
        for (refusal in listOf(
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        )) {
            assertWithMessage("code $refusal").that(classify(refusal)).isEqualTo(FailureClass.Drm.Unsupported)
        }
    }

    @Test
    fun theTitularUnspecifiedCodeIsNamedRatherThanCalledALicenceFailure() {
        // The issue's own title: nothing may reach a consumer as `ERROR_CODE_DRM_UNSPECIFIED` and
        // nothing else. It is protection that failed and that is the whole of what is known, which
        // is what `Drm.SystemError` says — and what it deliberately does not say is that a licence
        // was ever asked for, which the band's old fall-through did.
        assertThat(classify(PlaybackException.ERROR_CODE_DRM_UNSPECIFIED))
            .isEqualTo(FailureClass.Drm.SystemError)
        // And the same for a code the band does not yet have, which a later Media3 may add.
        assertThat(classify(6_999)).isEqualTo(FailureClass.Drm.SystemError)
    }

    @Test
    fun aDeadLicenceIsToldFromAProtectionFailureByTheSentenceItCarries() {
        // `PRD.md` §3.2's named split: a downloaded asset with a dead licence must not say "playback
        // error", which means its message key cannot be the one every other protection failure
        // carries. Asserted on the keys rather than on the classes, because the key is the thing an
        // app actually switches on.
        assertThat(FailureClass.Drm.LicenceExpired.userMessageKey)
            .isEqualTo(FailureClass.LICENCE_EXPIRED_MESSAGE_KEY)
        for (other in listOf(
            FailureClass.Drm.Provisioning,
            FailureClass.Drm.LicenceAcquisition,
            FailureClass.Drm.SystemError,
            FailureClass.Drm.Unsupported,
        )) {
            assertWithMessage("$other").that(other.userMessageKey).isEqualTo(FailureClass.DRM_MESSAGE_KEY)
        }
        // Not retryable, and no rung below the typed error: the same request produces the same
        // expired keys, and renewing an entitlement is the app's and not the ladder's.
        assertThat(FailureClass.Drm.LicenceExpired.retryable).isFalse()
        assertThat(FailureClass.Drm.LicenceExpired.rungCeiling).isEqualTo(FallbackRung.TYPED_ERROR)
        // The one DRM leaf that reaches rung 4, for ADR-0012 rule 1's reason: another source is
        // another container and another scheme mapping, never another entitlement.
        assertThat(FailureClass.Drm.Unsupported.rungCeiling).isEqualTo(FallbackRung.NEXT_SOURCE)
        assertThat(FailureClass.Drm.LicenceAcquisition.rungCeiling).isEqualTo(FallbackRung.RETRY_SAME_URL)
    }

    @Test
    fun aDrmSessionExceptionCarriesTheBandBeforeAnyRendererHasWrappedIt() {
        // What the load-error path and a consumer's own `catch` both meet: Media3 assigns the code in
        // `DrmUtil.getErrorCodeForMediaDrmException` and only a renderer copies it onto a
        // `PlaybackException`, so a classifier that read the wrapper alone would call a session
        // failure caught early a transient network failure.
        assertThat(ErrorClassifier.classify(drmSessionFailure(PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED)))
            .isEqualTo(FailureClass.Drm.LicenceExpired)
        assertThat(ErrorClassifier.classify(drmSessionFailure(PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED)))
            .isEqualTo(FailureClass.Drm.Unsupported)
        // Wrapped as a renderer delivers it, the two agree — the wrapper is preferred and says the
        // same thing, which is the property that makes reading both safe.
        val delivered = PlaybackException(
            "drm",
            drmSessionFailure(PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR),
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
        )
        assertThat(ErrorClassifier.classify(delivered)).isEqualTo(FailureClass.Drm.SystemError)
    }

    @Test
    fun aRefusedLicenceLoadIsNamedByItsStampWhenThereIsNoBandToRead() {
        // The failure `RetryingLoadErrors` is asked about: an `IOException` off the licence
        // transport, before Media3 has wrapped it in anything. The stamp core puts on the request
        // (#205) is the only evidence there is, and it settles the class whatever the status was —
        // which is what keeps a refused entitlement out of the network bucket.
        for (status in listOf(403, 500, 503)) {
            assertWithMessage("$status on a licence")
                .that(ErrorClassifier.classify(httpFailure(status, LoadKind.LICENCE)))
                .isEqualTo(FailureClass.Drm.LicenceAcquisition)
        }
        // Retryable and bounded by rung 1, which is what makes #205's licence budget the thing that
        // stops the asking.
        assertThat(FailureClass.Drm.LicenceAcquisition.retryable).isTrue()
        // A band, where there is one, is the better evidence and wins: Media3 knows which of the two
        // entitlement round trips it dispatched and the transport does not.
        val provisioning = PlaybackException(
            "drm",
            httpFailure(500, LoadKind.LICENCE),
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        )
        assertThat(ErrorClassifier.classify(provisioning)).isEqualTo(FailureClass.Drm.Provisioning)
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

    /** A DRM failure as a session raises it: the code assigned, and no `PlaybackException` yet. */
    private fun drmSessionFailure(errorCode: Int): DrmSession.DrmSessionException =
        DrmSession.DrmSessionException(IllegalStateException("drm"), errorCode)

    /** A refusal as `superplayer-drm` raises it: the evidence, and no `PlaybackException` yet. */
    private fun refusedDowngrade() =
        SecurityDowngradeRefusedException(
            deviceSecurityLevel = "L1",
            refusedLevel = "L3",
            permittedLevels = emptySet(),
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
     * The exception `MediaCodecRenderer` raises when a decoder it had selected will not initialise —
     * the real class, through the real constructor, because a stand-in would be asserting that the
     * stand-in behaved.
     *
     * The `MediaCodecInfo` is what the classification turns on, and passing one at all is half of it:
     * Media3 raises this exception with a `codecInfo` only from the path where a decoder was chosen
     * and its initialisation threw, and with a custom diagnostic code instead where none was found or
     * the query failed ([noDecoderWasFound] is that other shape). `secure` is the other half.
     */
    private fun decoderInitializationException(
        secure: Boolean,
        cause: Throwable? = null,
    ): DecoderInitializationException =
        DecoderInitializationException(
            INITIALISING_FORMAT,
            cause,
            /* secureDecoderRequired= */ secure,
            MediaCodecInfo.newInstance(
                /* name= */ if (secure) "c2.android.avc.decoder.secure" else "c2.android.avc.decoder",
                /* mimeType= */ MimeTypes.VIDEO_H264,
                /* codecMimeType= */ MimeTypes.VIDEO_H264,
                /* capabilities= */ null,
                /* hardwareAccelerated= */ true,
                /* softwareOnly= */ false,
                /* vendor= */ false,
                /* forceDisableAdaptive= */ false,
                /* forceSecure= */ secure,
            ),
        )

    /**
     * The other shape of the same exception: no decoder was found for the format at all, so Media3
     * has no `MediaCodecInfo` to name and passes a custom diagnostic code instead.
     *
     * // ref: `MediaCodecRenderer.DecoderInitializationException.NO_SUITABLE_DECODER_ERROR`, which
     * is `CUSTOM_ERROR_CODE_BASE + 1` and therefore -49999. Read from Media3 1.11's bytecode, because
     * the constant is private and the offsets count upward from a negative base.
     */
    private fun noDecoderWasFound(secureDecoderRequired: Boolean): DecoderInitializationException =
        DecoderInitializationException(
            INITIALISING_FORMAT,
            /* cause= */ null,
            secureDecoderRequired,
            /* errorCode= */ -49999,
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

    private companion object {
        /** The video a protected session would have been obliged to decode in protected memory. */
        val INITIALISING_FORMAT: Format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
    }
}
