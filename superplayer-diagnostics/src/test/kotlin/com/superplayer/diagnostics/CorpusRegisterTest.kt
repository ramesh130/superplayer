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

package com.superplayer.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.HostileManifests
import com.superplayer.testmedia.HostileStream
import com.superplayer.testmedia.HostileStream.Severity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The corpus register: what the doctor says about every curated pathology, and the test that forces it.
 *
 * This is Phase 9's exit criterion (`PRD.md` Part 4, "correctly identifies each pathology in a curated set
 * of deliberately broken manifests") in the shape ADR-0015 rule 12 asks for, which is
 * `superplayer-resilience`'s `FallbackRungCoverageTest`: [REGISTER] is a table rather than a report, it
 * prints nothing, and what makes it a gate is that its keys are checked against
 * [HostileManifests.graded] rather than against a list written here. A nineteenth pathology added to the
 * corpus fails [everyCorpusPathologyIsRegistered] the day it is added, with a message saying what to do.
 *
 * ## The two halves, and why they are in one file
 *
 * A doctor that flags everything names every pathology and is useless, so rule 12 puts the misses and the
 * false positives in one place and scores them alike:
 *
 * - **True positives.** [everySevereEntryIsNamedByTheDoctor] examines every corpus entry at its `SEVERE`
 *   grade and asserts the findings *exactly*, per entry. Exactly rather than "contains", because a finding
 *   the register does not expect is a false positive on hostile content, which is the same defect as a miss
 *   wearing a disguise.
 * - **False positives.** [noBenignGradeIsAFinding] and [noHealthyStreamIsAFinding] assert silence **per
 *   entry**, each in its own assertion with the entry named, rather than by summing findings over the corpus
 *   and asserting the total is zero. An aggregate assertion that happens to pass is what this ticket exists
 *   to prevent: it says nothing about *which* row was quiet.
 *
 * ## What the register records, and what it deliberately does not
 *
 * Per entry it records the pathologies named and the [FindingSeverity] each is named at — which is the
 * reading rule 12 scores ("named at its severe grade") and is stable. It does **not** record magnitudes.
 * Those are pinned in `HlsPathologyTest`, `DashPathologyTest` and `DeliveryPathologyTest`, beside the
 * corpus's own words for the same entry, and copying them here would be a second copy to keep in step —
 * two of them (the token rules') are minted against the wall clock and could not be pinned in any case.
 *
 * It also records **no `BORDERLINE` reading**, bar the one the caveat below turns on, and that is a
 * deliberate limit rather than an omission: rule 12 scores the severe grade and the benign one, because
 * those are the two a doctor is right or wrong about, while `BORDERLINE` is by the corpus's own definition
 * where a reasonable threshold may fall either way. Each middle grade is pinned where its threshold is
 * argued — in the per-protocol tests above — so that moving a threshold is reviewed beside its reason
 * rather than as a row of a table.
 *
 * Like its model it also checks the *names*: a register entry that names a test which no longer exists, or
 * which is no longer a `@Test`, fails [everyNamedTestExistsAndIsATest]. That is what stops a pathology from
 * quietly stopping being forced under a rename.
 *
 * ## What it does not touch
 *
 * `HostileManifestCorpusTest`'s `RECORDED`/`GRADED` and `HostileManifestLadderTest`'s `WITH_LADDER` record
 * what the same entries do to a *player*, with and without the fallback ladder. This phase does not change
 * playback and moves no row of either. This register is a third reading of the same corpus — what the
 * doctor *says* — and rule 4 is why the three do not merge: naming a defect is not recovering from it, and
 * `dash-availability-start-time-skew` is named here while it stands in `CANNOT_RECOVER` there.
 */
@RunWith(AndroidJUnit4::class)
class CorpusRegisterTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun everyCorpusPathologyIsRegistered() {
        // The corpus is the authority, never a list written here: this is the assertion that makes a
        // pathology added to `HostileManifests` fail rather than go unnoticed.
        val curated = HostileManifests.graded().map { it.id }.distinct()
        assertWithMessage(
            "Every pathology in `HostileManifests.graded()` needs a row in CorpusRegisterTest.REGISTER, in " +
                "the corpus's own order: the findings the doctor reports for it at SEVERE, and the test " +
                "that forces them. If the doctor cannot yet name it, add the row with no findings and a " +
                "`cannotYetName` saying why — ADR-0015 rule 12 admits an entry the doctor cannot name as " +
                "recorded rather than removed, and `aRowThatNamesNothingSaysWhyRatherThanPassingQuietly` " +
                "is what keeps that from being the quiet way to relax this gate.",
        ).that(REGISTER.keys).containsExactlyElementsIn(curated).inOrder()
    }

    @Test
    fun everyPathologyTheDoctorCanNameIsScoredAgainstTheCorpusOrRecordedAsUngradable() {
        // The register read the other way round. A `Pathology` added to the doctor's vocabulary is either
        // scored against a corpus entry or is one of the two that no corpus of *streams* can carry, and
        // there is no third case: a defect nothing scores is a defect nothing keeps honest.
        val scored = REGISTER.values.flatMap { it.findings.keys }.toSet()
        assertWithMessage(
            "A pathology the doctor can name is either scored against a corpus entry — add the corpus " +
                "entry and its REGISTER row — or is a defect of the fetch that no stream can carry, in " +
                "which case add it to UNGRADABLE with the test that forces it. The two already there " +
                "are excused as: " +
                UNGRADABLE.entries.joinToString("; ") { (pathology, it) -> "${pathology.id} — ${it.whyNotInTheCorpus}" },
        ).that(scored + UNGRADABLE.keys).containsExactlyElementsIn(Pathology.entries)
        // Disjoint, so that "scored against the corpus" and "cannot be" stay two answers rather than one
        // row hedging between them.
        assertThat(scored.intersect(UNGRADABLE.keys)).isEmpty()
    }

    @Test
    fun everySevereEntryIsNamedByTheDoctor() {
        // `HostileManifests.all()` is `graded()`'s `SEVERE` half, so this iterates the corpus rather than
        // the register: an entry with no row is a failure here as well as in the roster assertion above.
        HostileManifests.all().forEach { entry ->
            val reading = REGISTER[entry.id]
            assertWithMessage("$entry has no row in REGISTER — see everyCorpusPathologyIsRegistered")
                .that(reading).isNotNull()
            val found = findings(entry).associate { it.pathology to it.severity }
            assertWithMessage("$entry — ${entry.magnitude}")
                .that(found).containsExactlyEntriesIn(reading!!.findings)
        }
    }

    @Test
    fun aRowThatNamesNothingSaysWhyRatherThanPassingQuietly() {
        // The guard on rule 12's one exception, and the reason it is a field rather than a comment: an
        // empty finding set agrees with an empty report, so a row added with no findings and nothing said
        // would pass both gates above and relax the exit criterion in the act of appearing to record it.
        // A reason is therefore required of such a row and forbidden of any other, so that "the doctor
        // cannot name this yet" is a claim someone made rather than a silence.
        REGISTER.forEach { (id, reading) ->
            assertWithMessage(
                "$id names no finding, so its row needs `cannotYetName` saying why the doctor cannot name " +
                    "it (ADR-0015 rule 12: recorded, not removed) — or the doctor needs a rule for it.",
            ).that(reading.findings.isEmpty() && reading.cannotYetName == null).isFalse()
            assertWithMessage("$id names findings, so `cannotYetName` is not true of it")
                .that(reading.findings.isNotEmpty() && reading.cannotYetName != null).isFalse()
        }
    }

    @Test
    fun noBenignGradeIsAFinding() {
        // Per entry, with the entry and its magnitude in the message: "the corpus produced no findings in
        // total" would pass with one rule silently answering for another.
        val benign = HostileManifests.graded().filter { it.severity == Severity.BENIGN }
        assertThat(benign).isNotEmpty()
        benign.forEach { entry ->
            assertWithMessage("$entry — ${entry.magnitude}").that(findings(entry)).isEmpty()
        }
    }

    @Test
    fun aBenignRowRecordedAsNoControlIsOneTheDoctorIsSilentAboutAtItsBorderlineGradeToo() {
        // The honest half of the false-positive score. A `BENIGN` row only counts as a control where the
        // doctor could have spoken and chose not to; where the rule cannot see the pathology below its
        // severe grade at all, silence at `BENIGN` is the rule not firing rather than a threshold held, and
        // the register says so per row rather than letting the sweep above read as a clean pass.
        //
        // The check is the necessary condition, and it is one-way deliberately: a rule that is silent at
        // `BORDERLINE` may still be a strict threshold refusing a document that is measurably worse than
        // healthy (`hls-inconsistent-segment-durations` is exactly that, and its `BENIGN` row is a control).
        // What it catches is a row whose caveat has been fixed by a later rule and left standing.
        val caveated = REGISTER.filterValues { it.benignCaveat != null }
        assertWithMessage(
            "No row carries a benignCaveat any more, so this test asserts nothing: if a later rule made " +
                "every BENIGN row a real control, delete this method with the last caveat rather than " +
                "leaving it passing vacuously.",
        ).that(caveated).isNotEmpty()
        caveated.forEach { (id, reading) ->
            val borderline = gradeOf(id, Severity.BORDERLINE)
            assertWithMessage("$id's BENIGN row is recorded as no control: ${reading.benignCaveat}")
                .that(findings(borderline).map { it.pathology })
                .containsNoneIn(reading.findings.keys)
        }
    }

    @Test
    fun noHealthyStreamIsAFinding() {
        // The third false-positive control and the widest: content with no pathology at all, in the four
        // shapes the suite has one of. Per stream, for the same reason as the benign sweep.
        HEALTHY.forEach { (what, content) ->
            assertWithMessage(what).that(findings(content, HEALTHY_CONTENT_ID)).isEmpty()
        }
    }

    @Test
    fun everyNamedTestExistsAndIsATest() {
        val named = REGISTER.map { (id, reading) -> id to reading.forcedBy } +
            UNGRADABLE.map { (pathology, reading) -> pathology.id to reading.forcedBy }
        named.forEach { (id, method) ->
            val file = method.sourceFile()
            assertWithMessage("$id: ${file.absolutePath}").that(file.isFile).isTrue()
            assertWithMessage("$id: $method").that(declaresTest(file, method.methodName)).isTrue()
        }
    }

    /** The corpus's entry for [id] at [severity], which a graded pathology has and a binary one does not. */
    private fun gradeOf(id: String, severity: Severity): HostileStream {
        val graded = HostileManifests.graded().filter { it.id == id && it.severity == severity }
        assertWithMessage("$id has no $severity grade — a row carrying a benignCaveat is a graded pathology")
            .that(graded).hasSize(1)
        return graded.single()
    }

    /** What a doctor an app would build says about [entry], over the harness's transport. */
    private fun findings(entry: HostileStream): List<Finding> =
        findings(TestContent.hostile(entry), entry.id)

    /** The same, for content asked about under [contentId] — a doctor as a consumer builds one. */
    private fun findings(content: TestContent, contentId: String): List<Finding> {
        val environment = harness.diagnosticEnvironment(content)
        return MediaSourceDoctor.Builder(context).setEnvironment(environment).build()
            .examine(MediaRequest.Builder(contentId).addSource(content.sourceUri).build())
            .findings
    }

    /**
     * Whether [file] declares [methodName] as a JUnit test.
     *
     * The nearest `@Test` above the declaration with no other declaration in between, which is where Kotlin
     * puts the annotation — `FallbackRungCoverageTest`'s reading, unchanged and for its reason: a method
     * that still exists and is no longer a `@Test` is how a pathology would stop being forced without
     * anyone noticing, and it reads as missing.
     */
    private fun declaresTest(file: File, methodName: String): Boolean {
        val source = file.readText()
        val at = source.indexOf("    fun $methodName(")
        if (at < 0) return false
        val annotation = source.lastIndexOf("    @Test", at)
        return annotation >= 0 && !source.substring(annotation, at).contains("    fun ")
    }

    /** Where a test lives and what it is called. Every one of them is this module's. */
    private data class TestMethod(val className: String, val methodName: String) {

        fun sourceFile(): File = File("src/test/kotlin/com/superplayer/diagnostics/$className.kt")

        override fun toString(): String = "$className.$methodName"
    }

    /**
     * What the doctor says about one corpus entry, and who forces it.
     *
     * @param findings every pathology reported at the entry's `SEVERE` grade, with the severity it is
     *   reported at. Exhaustive: the assertion is an equality, so a finding missing from here is a false
     *   positive and a finding missing from the doctor is a miss.
     * @param forcedBy the test that forces this entry and asserts the corpus's own words on it — the
     *   citation, the cause and the magnitude, which this register deliberately does not copy.
     * @param benignCaveat why this entry's `BENIGN` row is **not** a real false-positive control, or null
     *   where it is one. Prose, because the reason is a fact about the document rather than a value.
     * @param cannotYetName why the doctor names nothing for this entry, for the exception ADR-0015 rule 12
     *   admits — "an entry the doctor cannot yet name is recorded, not removed". Required of a row with no
     *   findings and forbidden of a row with some, which is
     *   [aRowThatNamesNothingSaysWhyRatherThanPassingQuietly]: without it an empty finding set and an empty
     *   report agree, and the exit criterion would be relaxed by the one edit that looks like recording it.
     *   No row carries it today.
     */
    private class Reading(
        val findings: Map<Pathology, FindingSeverity>,
        val forcedBy: TestMethod,
        val benignCaveat: String? = null,
        val cannotYetName: String? = null,
    )

    /** A pathology no corpus of streams can carry, and the test that forces it instead. */
    private class Ungradable(val whyNotInTheCorpus: String, val forcedBy: TestMethod)

    private companion object {

        /** The identity a healthy stream is asked about under: a content id, never a URL. */
        const val HEALTHY_CONTENT_ID = "film/healthy"

        /**
         * **The register.** Per corpus entry, keyed by the entry's own id: the finding or findings the
         * doctor reports for it at `SEVERE`, and the test that forces them.
         *
         * In the corpus's order, which is protocol first, so that reading this beside
         * `HostileManifests.graded()` is a line-for-line comparison.
         */
        val REGISTER: Map<String, Reading> = linkedMapOf(
            // Two findings, and both are true of the document: a step of 48 puts the top rung at 6144 kbps,
            // which is also more stereo AAC than the codec can carry (the ceiling and its ISO/IEC 14496-3
            // derivation are `LadderPathologies`', where the constant is chosen). The corpus composes
            // nothing — this is one defect that reads two ways — and
            // `aPlaylistCarryingMoreThanOnePathologyReportsAllOfThem` is where that is argued.
            "hls-ladder-gap" to Reading(
                findings = mapOf(
                    Pathology.HLS_LADDER_GAP to FindingSeverity.DEGRADED,
                    Pathology.HLS_OVERSTATED_BITRATE to FindingSeverity.DEGRADED,
                ),
                forcedBy = hls("aLadderWithAStepNoClientCanClimbIsNamedAndGradedOnTheStep"),
            ),
            "hls-overstated-bitrate" to Reading(
                findings = mapOf(Pathology.HLS_OVERSTATED_BITRATE to FindingSeverity.DEGRADED),
                forcedBy = hls("aRungThatDeclaresMoreThanItsFormatCanCarryIsNamed"),
            ),
            // Forced by the tracer bullet rather than by the vocabulary sweep: it is the pathology #286
            // built the doctor's shape over, and moving it would leave that test asserting a shape with no
            // defect under it.
            "hls-missing-codecs" to Reading(
                findings = mapOf(Pathology.HLS_MISSING_CODECS to FindingSeverity.DEGRADED),
                forcedBy = doctor("aVariantThatDeclaresNoCodecsIsNamedWithItsSeverityItsCitationAndItsCause"),
            ),
            "hls-audio-group-codec-mismatch" to Reading(
                findings = mapOf(Pathology.HLS_AUDIO_GROUP_CODEC_MISMATCH to FindingSeverity.DEGRADED),
                forcedBy = hls("anAudioGroupDeclaredAsAFormatItsSegmentsCannotCarryIsNamed"),
            ),
            "hls-dangling-audio-group" to Reading(
                findings = mapOf(Pathology.HLS_DANGLING_AUDIO_GROUP to FindingSeverity.BLOCKING),
                forcedBy = hls("aVariantPointingAtAnAudioGroupThatIsNotThereIsNamed"),
            ),
            "hls-inconsistent-segment-durations" to Reading(
                findings = mapOf(Pathology.HLS_INCONSISTENT_SEGMENT_DURATIONS to FindingSeverity.DEGRADED),
                forcedBy = hls("segmentsFurtherApartThanAnyToleranceAllowsAreNamedAndGradedOnTheSpread"),
            ),
            "hls-discontinuity-without-timeline" to Reading(
                findings = mapOf(Pathology.HLS_DISCONTINUITY_WITHOUT_TIMELINE to FindingSeverity.DEGRADED),
                forcedBy = hls("aSpliceWithNothingToPlaceItsTimelineIsNamed"),
            ),
            "hls-cached-live-playlist" to Reading(
                findings = mapOf(Pathology.HLS_CACHED_LIVE_PLAYLIST to FindingSeverity.BLOCKING),
                forcedBy = delivery("aLivePlaylistHeldPastItsUpdateBoundIsNamedAndSoIsTheSideThatIsWrong"),
            ),
            "hls-token-scoped-to-manifest" to Reading(
                findings = mapOf(Pathology.HLS_TOKEN_SCOPED_TO_MANIFEST to FindingSeverity.BLOCKING),
                forcedBy = delivery("aManifestSignedWhoseSegmentsAreNotIsNamed"),
            ),
            "hls-token-expiring-in-window" to Reading(
                findings = mapOf(Pathology.HLS_TOKEN_EXPIRING_IN_WINDOW to FindingSeverity.BLOCKING),
                forcedBy = delivery("aTokenThatDiesInsideTheContentIsNamedAndGradedOnWhatIsLeftOfIt"),
            ),
            "hls-cors-refuses-credentials" to Reading(
                findings = mapOf(Pathology.HLS_CORS_REFUSES_CREDENTIALS to FindingSeverity.BLOCKING),
                forcedBy = delivery("responseHeadersThatWouldRefuseAPlayersRequestAreNamed"),
            ),
            // The DASH twin of the HLS gap, and two findings for the same reason its twin has two: one
            // packager writes both manifests from one ladder definition, so the same step is overstated in
            // both documents.
            "dash-ladder-gap" to Reading(
                findings = mapOf(
                    Pathology.DASH_LADDER_GAP to FindingSeverity.DEGRADED,
                    Pathology.DASH_OVERSTATED_BITRATE to FindingSeverity.DEGRADED,
                ),
                forcedBy = dash("aLadderWithAStepNoClientCanClimbIsNamedAndGradedOnTheStep"),
            ),
            "dash-overstated-bitrate" to Reading(
                findings = mapOf(Pathology.DASH_OVERSTATED_BITRATE to FindingSeverity.DEGRADED),
                forcedBy = dash("aRungThatDeclaresMoreThanItsFormatCanCarryIsNamed"),
            ),
            "dash-missing-codecs" to Reading(
                findings = mapOf(Pathology.DASH_MISSING_CODECS to FindingSeverity.DEGRADED),
                forcedBy = dash("aRepresentationDeclaringNoCodecsIsNamed"),
            ),
            "dash-availability-start-time-skew" to Reading(
                findings = mapOf(Pathology.DASH_AVAILABILITY_START_TIME_SKEW to FindingSeverity.BLOCKING),
                forcedBy = dash("anAvailabilityStartTimeAheadOfTheManifestsOwnClockIsNamedWithItsDirectionAndAmount"),
                benignCaveat = "the rule holds the anchor against the manifest's own clock, and at every " +
                    "grade below SEVERE the anchor is in the past — a document character for character a " +
                    "healthy stream that started later. Silence here is the rule having nothing to see " +
                    "rather than a threshold held, so this row is not a false-positive control. Holding " +
                    "the manifest against the device's clock instead is the alternative, and it flags " +
                    "every live stream on a phone set wrong.",
            ),
            "dash-short-time-shift-buffer-depth" to Reading(
                findings = mapOf(Pathology.DASH_SHORT_TIME_SHIFT_BUFFER_DEPTH to FindingSeverity.BLOCKING),
                forcedBy = dash("aWindowNoPlayheadFitsInsideIsNamedOnCoresOwnJudgement"),
            ),
            "dash-missing-time-shift-buffer-depth" to Reading(
                findings = mapOf(Pathology.DASH_MISSING_TIME_SHIFT_BUFFER_DEPTH to FindingSeverity.DEGRADED),
                forcedBy = dash("aLiveManifestPromisingAnUnlimitedWindowIsNamed"),
            ),
            // Three findings, and the register is where that is visible at all. The entry's second Period
            // adds a rung 48× the first Period's, so the MPD carries the mid-stream change *and*, inside
            // that Period, a ladder with the same unclimbable step its `dash-ladder-gap` sibling has and the
            // same overstated top rung the step puts there. Every one of the three is true of the document
            // and a packager fixing the ladder fixes all three; the entry's own test asserts the one it is
            // named for, which is why an exhaustive reading has to live here.
            "dash-mid-stream-ladder-change" to Reading(
                findings = mapOf(
                    Pathology.DASH_MID_STREAM_LADDER_CHANGE to FindingSeverity.DEGRADED,
                    Pathology.DASH_LADDER_GAP to FindingSeverity.DEGRADED,
                    Pathology.DASH_OVERSTATED_BITRATE to FindingSeverity.DEGRADED,
                ),
                forcedBy = dash("aLadderThatChangesAtAPeriodBoundaryIsNamedAndGradedOnTheJump"),
            ),
        )

        /**
         * The pathologies the corpus cannot grade, with the reason and the test that forces each.
         *
         * Both are defects of the *fetch* rather than of a document, which `Pathology`'s own KDoc records:
         * every corpus entry serves its media perfectly, so nothing in a corpus of streams can carry them.
         * They are here rather than left out so that the vocabulary is scored whole — a pathology with
         * neither a corpus row nor an entry here fails
         * [everyPathologyTheDoctorCanNameIsScoredAgainstTheCorpusOrRecordedAsUngradable].
         */
        val UNGRADABLE: Map<Pathology, Ungradable> = linkedMapOf(
            Pathology.MANIFEST_UNREACHABLE to Ungradable(
                whyNotInTheCorpus = "no stream can carry a refusal: it is the transport's answer, which " +
                    "the harness's fault script supplies instead.",
                forcedBy = doctor("aManifestTheChainRefusesIsAFindingRatherThanAThrow"),
            ),
            Pathology.MANIFEST_UNREADABLE to Ungradable(
                whyNotInTheCorpus = "every corpus entry is a document its protocol's parser reads — that " +
                    "is what `VALID_BUT_HOSTILE` and even `MALFORMED` mean there — so bytes that are no " +
                    "manifest at all have to be made by truncating a transfer.",
                forcedBy = doctor("aManifestTheParserCannotReadIsToldApartFromOneThatDidNotArrive"),
            ),
        )

        /**
         * Content with nothing wrong with it, in the shapes this suite has one of, with what each controls.
         *
         * The ladder's own control is not here: it is the corpus's `BENIGN` gap — two rungs exactly a
         * factor of two apart — which the benign sweep covers, since a synthetic healthy stream of one rung
         * has no step to be wrong about.
         */
        val HEALTHY: Map<String, TestContent> = linkedMapOf(
            "a healthy single-rung multivariant playlist" to TestContent.hls(),
            "a healthy two-rung ladder, built to the published guidance" to TestContent.hls(variantCount = 2),
            "a healthy static MPD" to TestContent.dash(),
            // The one the live rules need and no other control can be: a conforming dynamic MPD, with a
            // UTCTiming, an anchor behind its own clock and a window a playhead fits inside. A rule that
            // read "live" as "defective" passes everything else here and fails on this.
            "the corpus's healthy dynamic MPD" to TestContent.hostile(HostileManifests.dashLiveBaseline()),
        )

        fun hls(methodName: String): TestMethod = TestMethod("HlsPathologyTest", methodName)

        fun dash(methodName: String): TestMethod = TestMethod("DashPathologyTest", methodName)

        fun delivery(methodName: String): TestMethod = TestMethod("DeliveryPathologyTest", methodName)

        fun doctor(methodName: String): TestMethod = TestMethod("MediaSourceDoctorTest", methodName)
    }
}
