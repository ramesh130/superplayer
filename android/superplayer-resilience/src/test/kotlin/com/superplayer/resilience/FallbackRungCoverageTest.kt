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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * The register of the ladder: for each of [FallbackRung]'s six rungs, the test that *forces* that
 * rung and the test that counts what a player without resilience pays for it.
 *
 * `PRD.md` Part 5 asks for a forcing test per rung, and issue #184 asks that they be collected and
 * named in one place so that a missing rung is visible. They cannot be collected as code: the rungs
 * are forced across two modules — rungs 4 and 5 are performed by core and their player half is
 * `superplayer-core`'s own test — and a module's test classes are not on another module's test
 * classpath. So they are collected as *names*, and the names are checked against the source tree:
 * a rung whose entry names a file that does not exist, or a method that file does not declare as a
 * `@Test`, fails here.
 *
 * That is the mechanism that cannot rot quietly. A renamed test fails this file rather than leaving
 * a rung silently unforced; a rung added to [FallbackRung] with no entry fails
 * [everyRungHasAForcingTestAndARuleFourteenCount] the day it is added. Reading the source tree for
 * it follows `superplayer-abr`'s `NoDeviceModelStringTest`, which reads `src/main` the same way and
 * for the same reason — some facts about a repository are facts about its files.
 *
 * It asserts nothing about playback, and deliberately: what each named test proves is that test's
 * business, and re-asserting it here would be the second copy this file exists to avoid.
 */
class FallbackRungCoverageTest {

    /**
     * Where a test lives and what it is called.
     *
     * [modulePath] is relative to this module's directory, which is where Gradle runs a test from —
     * so a sibling module is reached by `..`, exactly as it is on the command line.
     */
    private data class TestMethod(val modulePath: String, val className: String, val methodName: String) {

        fun sourceFile(): File = File("$modulePath/src/test/kotlin/${className.replace('.', '/')}.kt")

        override fun toString(): String = "${className.substringAfterLast('.')}.$methodName"
    }

    /** What is claimed about one rung: the test that forces it, and the test that counts its absence. */
    private data class RungCoverage(val forces: TestMethod, val countsAbsence: TestMethod)

    @Test
    fun everyRungHasAForcingTestAndARuleFourteenCount() {
        // The roster is the assertion: a rung added to the ladder with no entry here fails, which is
        // what "a missing rung is visible" means.
        assertThat(COVERAGE.keys).containsExactlyElementsIn(FallbackRung.entries).inOrder()
    }

    @Test
    fun everyNamedTestExistsAndIsATest() {
        COVERAGE.forEach { (rung, coverage) ->
            listOf(coverage.forces, coverage.countsAbsence).forEach { named ->
                val file = named.sourceFile()
                assertWithMessage("$rung: ${file.absolutePath}").that(file.isFile).isTrue()
                assertWithMessage("$rung: $named").that(declaresTest(file, named.methodName)).isTrue()
            }
        }
    }

    /**
     * Whether [file] declares [methodName] as a JUnit test.
     *
     * The nearest `@Test` above the declaration, with no other declaration in between — which is
     * where Kotlin puts the annotation. A method that still exists but is no longer a `@Test`, which
     * is how a rung would stop being forced without anyone noticing, reads as missing.
     */
    private fun declaresTest(file: File, methodName: String): Boolean {
        val source = file.readText()
        val at = source.indexOf("    fun $methodName(")
        if (at < 0) return false
        val annotation = source.lastIndexOf("    @Test", at)
        return annotation >= 0 && !source.substring(annotation, at).contains("    fun ")
    }

    private companion object {

        const val RESILIENCE = "."
        const val CORE = "../superplayer-core"

        val COVERAGE: Map<FallbackRung, RungCoverage> = linkedMapOf(
            // Rung 1: a 500 that relents on the third ask, retried out of the profile's segment
            // budget and played through. Its absence is counted as behaviour, which is the only
            // reading of an empty load-error slot available from outside the facade.
            FallbackRung.RETRY_SAME_URL to RungCoverage(
                forces = resilience("RetryPlaybackTest", "aFaultThatRelentsIsRetriedAndPlaybackGoesOn"),
                countsAbsence = resilience("RetryPlaybackTest", "aPlayerWithoutResilienceKeepsMedia3SOwnLoadErrorHandling"),
            ),
            // Rung 2: DASH, because Media3 gives an HLS chunk source no location dimension — the
            // second `BaseURL` serves what the first refused.
            FallbackRung.NEXT_HOST to RungCoverage(
                forces = resilience("FallbackPlaybackTest", "aLocationThatFailsEveryAttemptIsLeftForTheOneTheManifestAlsoNames"),
                countsAbsence = resilience("FallbackPlaybackTest", "aPlayerWithoutResilienceIsLeftToMedia3SOwnFallbackTable"),
            ),
            // Rung 3: HLS, for the same reason the other way round — the failing rendition is
            // excluded and playback continues at another bitrate.
            FallbackRung.EXCLUDE_VARIANT to RungCoverage(
                forces = resilience("FallbackPlaybackTest", "aRenditionThatKeepsFailingIsExcludedAndPlaybackGoesOnAtAnotherBitrate"),
                countsAbsence = resilience("FallbackPlaybackTest", "aPlayerWithoutResilienceIsLeftToMedia3SOwnFallbackTable"),
            ),
            // Rung 4: the second entry of `MediaRequest.sources`, opened by core once every rung
            // below it has been spent.
            FallbackRung.NEXT_SOURCE to RungCoverage(
                forces = resilience("NextSourcePlaybackTest", "aSourceThatFailsEveryRungBelowIsLeftForTheNextOneInTheRequest"),
                countsAbsence = resilience("NextSourcePlaybackTest", "aPlayerWithoutResilienceOpensTheFirstSourceAndNothingElse"),
            ),
            // Rung 5: core's, because nothing in this module can fail a decoder — the player half is
            // forced against core's own harness with a hand-written ladder, and which class routes to
            // the rung is `FallbackLadderTest`'s
            // `theLastRungRecreatesADecoderForTheOneClassARecreatedDecoderIsTheRemedyFor`.
            FallbackRung.RECREATE_DECODER to RungCoverage(
                forces = core("SuperPlayerDecoderRecreationTest", "aFailureTheLadderAnswersWithARecreatedDecoderIsRePreparedAndPlaysOn"),
                countsAbsence = core("SuperPlayerDecoderRecreationTest", "aPlayerWithoutResilienceRePreparesNothing"),
            ),
            // Rung 6: a session nothing rescued, ending on a `SuperPlayerError` where a consumer
            // already reads errors.
            FallbackRung.TYPED_ERROR to RungCoverage(
                forces = resilience("TypedErrorPlaybackTest", "aSessionNothingRescuedEndsOnATypedError"),
                countsAbsence = resilience("TypedErrorPlaybackTest", "aPlayerWithoutResilienceEndsOnMedia3sOwnErrorUnchanged"),
            ),
        )

        fun resilience(className: String, methodName: String): TestMethod =
            TestMethod(RESILIENCE, "com.superplayer.resilience.$className", methodName)

        fun core(className: String, methodName: String): TestMethod =
            TestMethod(CORE, "com.superplayer.core.$className", methodName)
    }
}
