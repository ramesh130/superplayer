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

package com.superplayer.testkit

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.FrameSourceFactory
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier

/**
 * Scores [FrameSourceConformance] itself, from both sides.
 *
 * ADR-0018 rule 2's conformance suite is a claim about code this repository cannot see — and, unlike
 * `HttpTransportConformance`'s, about code that cannot run under `check` **at all**, since neither
 * QUIC nor WebRTC does. So the two ways it could be worthless are the two things asserted here.
 *
 * **It could pass everything.** A check nothing has ever failed is a check that does not work, so
 * there is one deliberately wrong publisher *per obligation* — [PublishingFrameSource.Defect] — and
 * each is driven against the check that is supposed to catch it. Per obligation and not in
 * aggregate: eleven checks with nine proofs is the easy way to half-do this, and it is the way that
 * looks finished.
 *
 * **It could describe something nothing can satisfy.** So the correct publisher is run through the
 * whole suite in both of obligation 7's branches — a track carrying a configuration record with
 * length-prefixed samples, and a self-describing one with Annex-B samples — and must pass every
 * check. `superplayer-realtime`'s `ScriptedFrameSourceConformanceTest` is the other half of that
 * claim and the more load-bearing one: it runs the fake this phase's own tests play through, which
 * is the reference implementation an adopter reads.
 */
@RunWith(AndroidJUnit4::class)
class FrameSourceConformanceTest {

    @Test
    fun aCorrectlyWrittenPublisherSatisfiesEveryObligation() {
        conformance().verifyAll()
    }

    @Test
    fun aSelfDescribingPublisherSatisfiesEveryObligation() {
        conformance(selfDescribing = true).verifyAll()
    }

    /**
     * The control obligation 9 needs, and the one the two above cannot be.
     *
     * A publisher that delivers its whole script inside `subscribe` has nothing left to stop by the
     * time anything can cancel it, so it satisfies cancellation by arithmetic rather than by doing
     * what the obligation asks. This one delivers from a thread of its own and joins it in `cancel`,
     * which is the shape both of this phase's transports will have and the only shape in which
     * `verifyCancellationStopsDelivery` passing means anything.
     */
    @Test
    fun anAsynchronousPublisherSatisfiesEveryObligation() {
        conformance(asynchronous = true).verifyAll()
    }

    @Test
    fun aPublisherDeliveringFromTwoThreadsAtOnceIsRefusedByObligationOne() {
        val refusal = refusalFrom(PublishingFrameSource.Defect.DELIVERS_FROM_TWO_THREADS_AT_ONCE) {
            it.verifyDeliveriesDoNotOverlap()
        }

        assertThat(refusal).contains("obligation 1")
        // What it did, and not merely that it failed: the reader of this message has no copy of this
        // repository and needs to be told what their transport was seen doing.
        assertThat(refusal).contains("two deliveries in flight")
    }

    @Test
    fun aPublisherThatDeclaresItsTracksLateIsRefusedByObligationTwo() {
        val refusal =
            refusalFrom(PublishingFrameSource.Defect.DELIVERS_A_FRAME_BEFORE_DECLARING_ITS_TRACKS) {
                it.verifyTracksAreDeclaredOnceBeforeTheFirstFrame()
            }

        assertThat(refusal).contains("obligation 2")
        assertThat(refusal).contains("before it called onTracks")
    }

    @Test
    fun aPublisherSendingAFrameOnAnUndeclaredTrackIsRefusedByObligationTwo() {
        val refusal = refusalFrom(PublishingFrameSource.Defect.SENDS_A_FRAME_ON_AN_UNDECLARED_TRACK) {
            it.verifyEveryFrameNamesADeclaredTrack()
        }

        assertThat(refusal).contains("obligation 2")
        assertThat(refusal).contains("naming trackIndex 7")
    }

    @Test
    fun aPublisherStartingOnADependentFrameIsRefusedByObligationThree() {
        val refusal = refusalFrom(PublishingFrameSource.Defect.STARTS_A_TRACK_WITHOUT_A_KEYFRAME) {
            it.verifyEachTrackStartsWithAKeyframe()
        }

        assertThat(refusal).contains("obligation 3")
        assertThat(refusal).contains("keyFrame = false")
    }

    @Test
    fun aPublisherWhoseTimestampsGoBackwardsIsRefusedByObligationFour() {
        val refusal = refusalFrom(PublishingFrameSource.Defect.STEPS_ONE_TRACKS_TIMESTAMPS_BACKWARDS) {
            it.verifyTimestampsAreMonotonicPerTrack()
        }

        assertThat(refusal).contains("obligation 4")
        assertThat(refusal).contains("backwards")
    }

    @Test
    fun aPublisherDeclaringACodecNothingMapsIsRefused() {
        val refusal = refusalFrom(PublishingFrameSource.Defect.DECLARES_AN_UNMAPPED_CODEC) {
            it.verifyCodecStringsAreMapped()
        }

        assertThat(refusal).contains("RealtimeTrack.codec")
        assertThat(refusal).contains("\"theora\"")
    }

    @Test
    fun aPublisherHandingARecordForASelfDescribingFourccIsRefusedByObligationSeven() {
        val refusal =
            refusalFrom(PublishingFrameSource.Defect.HANDS_A_RECORD_FOR_A_SELF_DESCRIBING_FOURCC) {
                it.verifyCodecConfigurationFollowsTheFourcc()
            }

        assertThat(refusal).contains("obligation 7")
        assertThat(refusal).contains("CodecConfiguration.Record")
    }

    /**
     * #340's own defect, which is the one this whole branch exists to prevent: the bytes are valid
     * H.264 and the track is declared with a record, and the two disagree about how the samples are
     * delimited.
     */
    @Test
    fun aPublisherFramingItsSamplesTheOtherWayIsRefusedByObligationSeven() {
        val refusal =
            refusalFrom(PublishingFrameSource.Defect.FRAMES_SAMPLES_AS_ANNEX_B_UNDER_A_RECORD) {
                it.verifySamplesAreFramedAsTheFourccRequires()
            }

        assertThat(refusal).contains("obligation 7")
        assertThat(refusal).contains("Annex-B start code")
    }

    @Test
    fun aPublisherHandingOnePooledBufferOverTwiceIsRefusedByObligationEight() {
        val refusal = refusalFrom(PublishingFrameSource.Defect.HANDS_THE_SAME_BUFFER_OVER_TWICE) {
            it.verifyPayloadsAreNeitherReusedNorTouchedAgain()
        }

        assertThat(refusal).contains("obligation 8")
        assertThat(refusal).contains("the same")
    }

    /**
     * Obligation 8's other half, which the pooled-buffer defect above cannot reach: that one is
     * refused on identity before the comparison is ever made, so without this the mutation branch
     * would be a refusal nothing had ever forced.
     */
    @Test
    fun aPublisherWritingIntoAPayloadItHasHandedOverIsRefusedByObligationEight() {
        val refusal = refusalFrom(PublishingFrameSource.Defect.WRITES_INTO_A_PAYLOAD_IT_HAS_HANDED_OVER) {
            it.verifyPayloadsAreNeitherReusedNorTouchedAgain()
        }

        assertThat(refusal).contains("obligation 8")
        assertThat(refusal).contains("after onFrame had returned")
    }

    @Test
    fun aPublisherDeliveringAfterItHasEndedIsRefused() {
        val refusal = refusalFrom(PublishingFrameSource.Defect.DELIVERS_A_FRAME_AFTER_ENDING) {
            it.verifyTerminationIsFinal()
        }

        assertThat(refusal).contains("onEnded")
        assertThat(refusal).contains("after it had called onEnded")
    }

    @Test
    fun aPublisherWhoseCancelStopsNothingIsRefusedByObligationNine() {
        val refusal = refusalFrom(PublishingFrameSource.Defect.KEEPS_DELIVERING_AFTER_CANCEL) {
            it.verifyCancellationStopsDelivery()
        }

        assertThat(refusal).contains("obligation 9")
        assertThat(refusal).contains("after cancel() returned")
    }

    /**
     * `verifyAll` stops at the first obligation broken, so one defect is enough to state that the
     * whole-suite entry point a consumer actually calls reaches the same conclusion a single check
     * does — which is the only thing tying the eleven together.
     */
    @Test
    fun theWholeSuiteRefusesAPublisherThatBreaksOneObligation() {
        val refusal = refusalFrom(PublishingFrameSource.Defect.STARTS_A_TRACK_WITHOUT_A_KEYFRAME) {
            it.verifyAll()
        }

        assertThat(refusal).contains("obligation 3")
    }

    /**
     * The register: every check has a wrong publisher that fails it, and every wrong publisher has a
     * check that catches it.
     *
     * `HttpTransportConformanceTest`'s shape and `FallbackRungCoverageTest`'s before it, for their
     * reason. The methods above are the proof; this is what makes a **twelfth** obligation — a
     * `verify` method added with no [PublishingFrameSource.Defect] beside it, or a defect nothing
     * drives — fail the build rather than go unnoticed, which is the way a suite like this quietly
     * stops being non-vacuous. The pairing is written out rather than derived from a name, because a
     * name is exactly what a rename would take with it.
     *
     * A check maps to a *list* where the HTTP suite's maps to one defect, which is the one place
     * this register departs from it: obligation 8 is two refusals inside one check — an array handed
     * over twice, and one written into after it was — and the first of them fires before the second
     * can, so a single pairing would leave a shipped refusal forced by nothing.
     */
    @Test
    fun everyCheckHasAWrongPublisherAndEveryWrongPublisherHasACheck() {
        val register = mapOf(
            "verifyDeliveriesDoNotOverlap" to
                listOf(PublishingFrameSource.Defect.DELIVERS_FROM_TWO_THREADS_AT_ONCE),
            "verifyTracksAreDeclaredOnceBeforeTheFirstFrame" to
                listOf(PublishingFrameSource.Defect.DELIVERS_A_FRAME_BEFORE_DECLARING_ITS_TRACKS),
            "verifyEveryFrameNamesADeclaredTrack" to
                listOf(PublishingFrameSource.Defect.SENDS_A_FRAME_ON_AN_UNDECLARED_TRACK),
            "verifyEachTrackStartsWithAKeyframe" to
                listOf(PublishingFrameSource.Defect.STARTS_A_TRACK_WITHOUT_A_KEYFRAME),
            "verifyTimestampsAreMonotonicPerTrack" to
                listOf(PublishingFrameSource.Defect.STEPS_ONE_TRACKS_TIMESTAMPS_BACKWARDS),
            "verifyCodecStringsAreMapped" to
                listOf(PublishingFrameSource.Defect.DECLARES_AN_UNMAPPED_CODEC),
            "verifyCodecConfigurationFollowsTheFourcc" to
                listOf(PublishingFrameSource.Defect.HANDS_A_RECORD_FOR_A_SELF_DESCRIBING_FOURCC),
            "verifySamplesAreFramedAsTheFourccRequires" to
                listOf(PublishingFrameSource.Defect.FRAMES_SAMPLES_AS_ANNEX_B_UNDER_A_RECORD),
            "verifyPayloadsAreNeitherReusedNorTouchedAgain" to listOf(
                PublishingFrameSource.Defect.HANDS_THE_SAME_BUFFER_OVER_TWICE,
                PublishingFrameSource.Defect.WRITES_INTO_A_PAYLOAD_IT_HAS_HANDED_OVER,
            ),
            "verifyTerminationIsFinal" to
                listOf(PublishingFrameSource.Defect.DELIVERS_A_FRAME_AFTER_ENDING),
            "verifyCancellationStopsDelivery" to
                listOf(PublishingFrameSource.Defect.KEEPS_DELIVERING_AFTER_CANCEL),
        )
        // Read off the class rather than listed here, so that a check added to it is a check this
        // register is missing. Public and non-synthetic; `verifyAll` is excluded by name, since it
        // is the eleven above run in order rather than a twelfth obligation.
        val checks = FrameSourceConformance::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .filter { it.startsWith("verify") && it != "verifyAll" }
            .toSet()

        assertThat(register.keys).isEqualTo(checks)
        assertThat(register.values.flatten().toSet())
            .isEqualTo(PublishingFrameSource.Defect.entries.toSet())
    }

    private fun refusalFrom(
        defect: PublishingFrameSource.Defect,
        check: (FrameSourceConformance) -> Unit,
    ): String {
        val conformance = conformance(defect)
        val refusal = try {
            check(conformance)
            null
        } catch (refused: FrameSourceConformanceException) {
            refused
        }
        val message = checkNotNull(refusal) { "$defect was not refused by the check meant to catch it" }
            .message
            .orEmpty()

        // The shape every message keeps, because the whole point of the exercise is that it is read
        // by someone who is not the author of this code.
        assertThat(message).contains("What this source did:")
        assertThat(message).contains("What the rule requires:")
        return message
    }

    /**
     * The suite over one publisher, opened afresh per check exactly as a player opens one per
     * playback — the same instance for two subscriptions would have one check's cancellation end the
     * next one's delivery.
     */
    private fun conformance(
        defect: PublishingFrameSource.Defect? = null,
        selfDescribing: Boolean = false,
        asynchronous: Boolean = false,
    ): FrameSourceConformance = FrameSourceConformance(
        // A source per call, exactly as a player opens one per playback: one instance subscribed
        // twice would have the first check's cancellation reach the second check's delivery.
        FrameSourceFactory { PublishingFrameSource(defect, selfDescribing, asynchronous) },
        URI,
    )

    private companion object {

        /** A scheme of the shape a transport registers and a path of no significance. */
        val URI: Uri = Uri.parse("moq://relay.example/studio-a")
    }
}
