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
import com.superplayer.core.DiagnosticEnvironment
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackResilience
import com.superplayer.resilience.CredentialRefusal
import com.superplayer.resilience.HeaderProvider
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.HostileManifests
import com.superplayer.testmedia.HostileStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The tracer bullet of ADR-0015: a consumer hands the doctor the same `MediaRequest` a player adopts and is
 * told what is wrong with the stream, in the words the corpus already wrote (#286).
 *
 * One pathology is named here — the corpus's `hls-missing-codecs` — and the control beside it is the
 * healthy stream of the same protocol, in its own test method, because a doctor that flags everything
 * scores perfectly on the first half and is useless (rule 12). #290 is where every entry of the corpus is
 * scored in one register; what this class pins is the shape.
 *
 * **What the corpus entry declares is one severity, not two.** `hls-missing-codecs` is a binary pathology —
 * the attribute is present or it is not, and `HostileManifests.graded()` therefore carries it once, at
 * `SEVERE` — so "at the severities that entry declares" is read off [HostileManifests.graded] here rather
 * than assumed to be three. A later entry that is graded is iterated by the same line without a change.
 */
@RunWith(AndroidJUnit4::class)
class MediaSourceDoctorTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aVariantThatDeclaresNoCodecsIsNamedWithItsSeverityItsCitationAndItsCause() {
        val entries = HostileManifests.graded().filter { it.id == Pathology.HLS_MISSING_CODECS.id }
        assertWithMessage("the severities the corpus entry declares").that(entries.map { it.severity })
            .containsExactly(HostileStream.Severity.SEVERE)

        entries.forEach { entry ->
            val environment = harness.diagnosticEnvironment(TestContent.hostile(entry))
            val report = doctor(environment).examine(requestFor(entry))

            assertWithMessage("$entry").that(report.findings).hasSize(1)
            val finding = report.findings.single()
            assertWithMessage("$entry").that(finding.pathology).isEqualTo(Pathology.HLS_MISSING_CODECS)
            // The id and the citation are the corpus's own, which is the join ADR-0015 rule 12's register is
            // built on: the doctor keeps its own copy of the words because a library cannot depend on a
            // test module, and a copy that had drifted would fail here rather than in a report nobody reads.
            assertWithMessage("$entry").that(finding.pathology.id).isEqualTo(entry.id)
            assertWithMessage("$entry").that(finding.specCitation).isEqualTo(entry.spec)
            assertWithMessage("$entry").that(finding.cause).contains("CODECS")
            assertWithMessage("$entry").that(finding.severity).isEqualTo(FindingSeverity.DEGRADED)
            // A binary pathology has no magnitude, as the corpus entry has none.
            assertWithMessage("$entry").that(finding.magnitude).isNull()

            // One request, and it is the multivariant playlist: a doctor reads declarations and downloads no
            // segment, so a preflight does not cost what a start costs (rule 7).
            assertWithMessage("$entry").that(harness.networkRequests(environment).map { it.uri })
                .containsExactly(entry.sourceUri)
        }
    }

    @Test
    fun aHealthyStreamYieldsNoFinding() {
        val content = TestContent.hls()
        val environment = harness.diagnosticEnvironment(content)

        val report = doctor(environment).examine(
            MediaRequest.Builder(HEALTHY_CONTENT_ID).addSource(content.sourceUri).build(),
        )

        assertThat(report.findings).isEmpty()
        assertWithMessage("a healthy manifest is still fetched and read").that(harness.networkRequests(environment))
            .hasSize(1)
    }

    @Test
    fun theFetchTravelsTheChainAPlayerWouldMeet() {
        val entry = HostileManifests.hlsMissingCodecs()
        // The manifest is refused once with a 401 and served to anyone who asks again. Nothing in the doctor
        // retries, and the chain it composes has no load-error policy, so a report that names the pathology
        // at all is one whose fetch was repaired by the header-refresh layer `setResilience` filled — which
        // is rule 7's "the chain a player would meet" asserted rather than asserted by inspection.
        val environment = harness.diagnosticEnvironment(
            TestContent.hostile(entry),
            faults = FaultScript.Builder().failWithHttpStatus(401, kind = ResourceKind.MANIFEST, firstAttempts = 1).build(),
        )
        val provider = MintingProvider()

        val report = doctor(environment, resilience = Resilience.standard(headers = provider)).examine(requestFor(entry))

        assertThat(report.findings.map { it.pathology }).containsExactly(Pathology.HLS_MISSING_CODECS)
        assertWithMessage("the refusal and the repaired ask, one transfer to everything above")
            .that(harness.networkRequests(environment)).hasSize(2)
        assertWithMessage("the app's credential was minted for the doctor's own fetch").that(provider.calls()).isEqualTo(1)
        assertThat(report.chain).containsExactly(ChainLayer.HEADER_REFRESH)
    }

    @Test
    fun aManifestTheChainRefusesIsAFindingRatherThanAThrow() {
        val entry = HostileManifests.hlsMissingCodecs()
        val environment = harness.diagnosticEnvironment(
            TestContent.hostile(entry),
            faults = FaultScript.Builder().failWithHttpStatus(404, kind = ResourceKind.MANIFEST).build(),
        )

        val report = doctor(environment).examine(requestFor(entry))

        val finding = report.findings.single()
        assertThat(finding.pathology).isEqualTo(Pathology.MANIFEST_UNREACHABLE)
        assertThat(finding.severity).isEqualTo(FindingSeverity.BLOCKING)
        // The status and nothing else: which team a support ticket goes to turns on it, and an exception's
        // message may carry the URL and the token in its query.
        assertThat(finding.magnitude).isEqualTo("HTTP 404")
        assertWithMessage("a doctor with no resilience reports no chain layer").that(report.chain).isEmpty()
    }

    @Test
    fun thePublicApiNamesNoMedia3TypeAtAll() {
        // ADR-0015 rule 2, which is stronger than ADR-0001 rule 2 and is therefore asserted rather than left
        // to `verifyNoUnstableMedia3InPublicApi`: that check passes a *stable* Media3 type, and the claim
        // here is that a consumer of the doctor names none of them. The one exception the rule allows is the
        // HUD's composables (#294), which take a `Player`; this reading moves when that arrives, and moving
        // it is the point at which someone has to read the rule again.
        val surface = File(API_SURFACE).readText()
        assertWithMessage("$API_SURFACE, as `updateApiSurface` wrote it").that(surface).isNotEmpty()
        assertWithMessage(API_SURFACE).that(surface).doesNotContain("androidx/media3")
    }

    /** A doctor as a consumer builds one, over the harness's transport rather than the device's network. */
    private fun doctor(
        environment: DiagnosticEnvironment,
        resilience: PlaybackResilience? = null,
    ): MediaSourceDoctor = MediaSourceDoctor.Builder(context)
        .setEnvironment(environment)
        .apply { resilience?.let(::setResilience) }
        .build()

    /** What an app would ask about [entry]: an identity of its own and the stream's URI as its source. */
    private fun requestFor(entry: HostileStream): MediaRequest =
        MediaRequest.Builder(entry.id).addSource(entry.sourceUri).build()

    /** A provider that mints a new token every time it is asked, as an app's token service would. */
    private class MintingProvider : HeaderProvider {

        private val minted = CopyOnWriteArrayList<String>()

        override fun refreshedHeaders(refusal: CredentialRefusal): Map<String, String> {
            val token = "Bearer minted-${minted.size + 1}"
            minted += token
            return mapOf("Authorization" to token)
        }

        fun calls(): Int = minted.size
    }

    private companion object {

        /** The identity a healthy stream is asked about under: a content id, never a URL. */
        const val HEALTHY_CONTENT_ID = "film/healthy"

        /** The tracked public API of this module, relative to the module directory a test runs in. */
        const val API_SURFACE = "api/superplayer-diagnostics.api"
    }
}
