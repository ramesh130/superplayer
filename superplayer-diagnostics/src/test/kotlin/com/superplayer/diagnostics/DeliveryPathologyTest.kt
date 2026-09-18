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

/**
 * The three pathologies that are in no document: how the stream was *delivered* (#289).
 *
 * A `Cache-Control` the playlist and its segments disagree about, a token scoped to the manifest and not to
 * the media, a CORS configuration that refuses a credentialed request — none of the three is visible to any
 * parse, however careful, because each is a fact about a transfer. **That is what makes this class the proof
 * of ADR-0015 rule 7**: the doctor fetches over the chain a player of this request would load through, so it
 * meets the headers that player meets, and [aHeaderIsAFactAboutATransferAndNotAboutADocument] holds the two
 * halves side by side — the same bytes, diagnosed one way when the headers are served and another when they
 * are not.
 *
 * ## The controls are the other half
 *
 * ADR-0015 rule 12 scores a doctor on its false positives as heavily as on its misses, and a delivery rule is
 * where flagging everything is easiest: every stream has response headers and most signed URLs are signed
 * correctly. So correctly delivered content is asserted to produce **nothing** in three methods of its own,
 * one per defect, each over content that is as close to the rule's threshold as healthy content comes — a
 * live playlist served with no caching directive, a token that covers the content many times over, and an
 * origin that says nothing about CORS at all.
 */
@RunWith(AndroidJUnit4::class)
class DeliveryPathologyTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aLivePlaylistHeldPastItsUpdateBoundIsNamedAndSoIsTheSideThatIsWrong() {
        val entry = HostileManifests.hlsCachedLivePlaylist()
        val finding = findingFor(entry, Pathology.HLS_CACHED_LIVE_PLAYLIST)

        assertThat(finding.severity).isEqualTo(FindingSeverity.BLOCKING)
        // The whole of the acceptance criterion: not "these two disagree" but *which of them is wrong*, with
        // both readings printed, because the fix is a change to one cache rule on one path.
        assertThat(finding.magnitude)
            .isEqualTo(
                "the playlist, not its segments: held for 600 s against a 2 s target duration, " +
                    "while its segments are served \"no-store\""
            )
    }

    @Test
    fun aHeaderIsAFactAboutATransferAndNotAboutADocument() {
        // The same entry, the same bytes, at the same URIs, over the same transport — and the response
        // headers withheld. Nothing a parser can read tells the two apart, and the doctor reports the defect
        // in exactly one of them, which is what "the doctor really travels the chain" means.
        val entry = HostileManifests.hlsCachedLivePlaylist()
        assertWithMessage("the corpus carries this entry's defect as headers, which is the premise here")
            .that(entry.declaredResponseHeaders).isNotEmpty()

        val served = findings(entry).map { it.pathology }
        val withheld = findingsOverBareBytes(entry)

        assertThat(served).contains(Pathology.HLS_CACHED_LIVE_PLAYLIST)
        assertThat(withheld).doesNotContain(Pathology.HLS_CACHED_LIVE_PLAYLIST)
    }

    @Test
    fun namingTheWrongSideCostsOneSegmentsHeadersAndNoMedia() {
        val entry = HostileManifests.hlsCachedLivePlaylist()
        val environment = harness.diagnosticEnvironment(TestContent.hostile(entry))

        MediaSourceDoctor.Builder(context).setEnvironment(environment).build()
            .examine(MediaRequest.Builder(entry.id).addSource(entry.sourceUri).build())

        // Two playlists and exactly one segment: ADR-0015 rule 7's #289 addendum in a count. The probe is
        // opened for its headers and closed, so what it costs is a round trip rather than a segment, and no
        // second segment is ever asked for however many the playlist names.
        val asked = harness.networkRequests(environment).map { it.uri }
        assertThat(asked.filter { it.endsWith(".m3u8") }).hasSize(2)
        val segments = asked.filterNot { it.endsWith(".m3u8") }
        assertWithMessage("one segment's headers, and no more").that(segments).hasSize(1)
        assertThat(segments.single()).endsWith(SEGMENT_SUFFIX)
    }

    @Test
    fun aStreamNoCacheRuleIsHoldingIsNotAFindingAndCostsNoProbe() {
        // The control for the cache rule. A correctly delivered stream is one whose playlist no shared cache
        // may hold past its update bound, which is every on-demand stream — an `EXT-X-ENDLIST` says the
        // document will not change again — and every live one whose origin sets no lifetime longer than that
        // bound, as this origin does not.
        val environment = harness.diagnosticEnvironment(TestContent.hls())

        val report = MediaSourceDoctor.Builder(context).setEnvironment(environment).build()
            .examine(MediaRequest.Builder(HEALTHY_CONTENT_ID).addSource(TestContent.hls().sourceUri).build())

        assertThat(report.findings).isEmpty()
        // And nothing was probed: a stream with no defect to attribute pays nothing for the rule that would
        // have attributed it.
        assertThat(harness.networkRequests(environment).map { it.uri }.filterNot { it.endsWith(".m3u8") })
            .isEmpty()
    }

    @Test
    fun aManifestSignedWhoseSegmentsAreNotIsNamed() {
        val entry = HostileManifests.hlsTokenScopedToManifest()
        val finding = findingFor(entry, Pathology.HLS_TOKEN_SCOPED_TO_MANIFEST)

        assertThat(finding.severity).isEqualTo(FindingSeverity.BLOCKING)
        assertThat(finding.magnitude)
            .isEqualTo("the manifest is signed and its ${HostileManifests.SEGMENT_COUNT} segments are not")
    }

    @Test
    fun aTokenThatDiesInsideTheContentIsNamedAndGradedOnWhatIsLeftOfIt() {
        val severe = findingFor(
            HostileManifests.hlsTokenExpiringInWindow(Severity.SEVERE),
            Pathology.HLS_TOKEN_EXPIRING_IN_WINDOW,
        )
        // Half a viewing left: no viewer reaches the end of the content however promptly they start.
        assertThat(severe.severity).isEqualTo(FindingSeverity.BLOCKING)
        assertThat(severe.magnitude).isEqualTo("4 s of token left over 8 s of content")

        // One and a half viewings: the content plays through and a viewer who pauses loses it, which is
        // where a reasonable threshold could fall either way — reported, and reported as the lesser reading.
        val borderline = findingFor(
            HostileManifests.hlsTokenExpiringInWindow(Severity.BORDERLINE),
            Pathology.HLS_TOKEN_EXPIRING_IN_WINDOW,
        )
        assertThat(borderline.severity).isEqualTo(FindingSeverity.DEGRADED)
    }

    @Test
    fun aTokenThatOutlivesTheContentIsNotAFinding() {
        // The control for both token rules, and the corpus's own `BENIGN` grade: every URI of this stream is
        // signed, so the scope rule has nothing to say, and the token covers the content many times over.
        // A doctor that flags this flags a correctly signed URL.
        val benign = HostileManifests.hlsTokenExpiringInWindow(Severity.BENIGN)

        assertThat(findings(benign)).isEmpty()
    }

    @Test
    fun responseHeadersThatWouldRefuseAPlayersRequestAreNamed() {
        val entry = HostileManifests.hlsCorsRefusesCredentials()
        val finding = findingFor(entry, Pathology.HLS_CORS_REFUSES_CREDENTIALS)

        assertThat(finding.severity).isEqualTo(FindingSeverity.BLOCKING)
        // Binary: a configuration refuses the request or it does not, so there is nothing to grade.
        assertThat(finding.magnitude).isNull()
        // The entry plays perfectly on this transport — `HostileManifestCorpusTest` records it as playing to
        // the end — which is the whole reason the defect needs a doctor to be seen at all.
        assertThat(entry.severity).isEqualTo(Severity.SEVERE)
    }

    @Test
    fun anOriginThatSaysNothingAboutCorsIsNotAFinding() {
        // The control for the CORS rule, and the one that matters most: almost every origin serving a native
        // player emits no CORS headers whatever, and every one of them is correct. What is reported is an
        // origin that speaks the protocol and contradicts it, never one that is silent.
        val environment = harness.diagnosticEnvironment(TestContent.hls())

        val report = MediaSourceDoctor.Builder(context).setEnvironment(environment).build()
            .examine(MediaRequest.Builder(HEALTHY_CONTENT_ID).addSource(TestContent.hls().sourceUri).build())

        assertThat(report.findings.map { it.pathology })
            .doesNotContain(Pathology.HLS_CORS_REFUSES_CREDENTIALS)
    }

    /** The one finding of [pathology] [entry] produces, with the corpus's own words asserted on it. */
    private fun findingFor(entry: HostileStream, pathology: Pathology): Finding {
        val finding = findings(entry).singleOrNull { it.pathology == pathology }
        assertWithMessage("$entry — ${entry.magnitude}").that(finding).isNotNull()
        // The id, the citation and the cause are the corpus's, which is the join ADR-0015 rule 12's
        // register is built on.
        assertWithMessage("$entry").that(pathology.id).isEqualTo(entry.id)
        assertWithMessage("$entry").that(finding!!.specCitation).isEqualTo(entry.spec)
        assertWithMessage("$entry").that(finding.cause).isEqualTo(entry.cause)
        return finding
    }

    /** What a doctor an app would build says about [entry], over the harness's transport. */
    private fun findings(entry: HostileStream): List<Finding> =
        examine(entry, TestContent.hostile(entry))

    /** The same entry with its declared response headers withheld: the bytes alone, and nothing else moved. */
    private fun findingsOverBareBytes(entry: HostileStream): List<Pathology> =
        examine(entry, TestContent.hostile(entry).servedWithNoDeclaredHeaders()).map { it.pathology }

    private fun examine(entry: HostileStream, content: TestContent): List<Finding> {
        val environment = harness.diagnosticEnvironment(content)
        return MediaSourceDoctor.Builder(context).setEnvironment(environment).build()
            .examine(MediaRequest.Builder(entry.id).addSource(content.sourceUri).build())
            .findings
    }

    private companion object {

        /** The identity a healthy stream is asked about under: a content id, never a URL. */
        const val HEALTHY_CONTENT_ID = "film/healthy"

        /** What the synthetic streams' segments are named, and what a probe therefore ends in. */
        const val SEGMENT_SUFFIX = ".aac"
    }
}
