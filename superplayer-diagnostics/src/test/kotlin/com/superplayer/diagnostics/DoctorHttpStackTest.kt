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
import com.superplayer.core.HttpStack
import com.superplayer.core.MediaRequest
import com.superplayer.resilience.CredentialRefusal
import com.superplayer.resilience.HeaderProvider
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.ChainBottom
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR-0016 rule 13 for the doctor (#314): `MediaSourceDoctor.Builder.setHttpStack` is the HTTP client the
 * players of this content load over, and the doctor's fetch travels it.
 *
 * A doctor is on rule 13's list **deliberately rather than for symmetry**, and the reason is ADR-0015
 * rule 7: the whole promise of the doctor is that it fetches over the chain a *player* of that request
 * would load through, so that the token the app's `HeaderProvider` mints and the `ContentCache` the
 * consumer opened are the ones that player would meet. A client is part of that chain — an interceptor
 * that signs, a proxy, a pinned certificate, a pool the CDN has already routed — and a doctor that did not
 * share it would still answer findings, just not the ones that player's chain produces. That is the
 * promise made false *quietly*, which is why it is a count here rather than a sentence in a KDoc.
 *
 * The environment's transport slot is left empty throughout ([ChainBottom.CONSUMERS_HTTP_TRANSPORT]),
 * because it wins over a consumer's stack (ADR-0016 rule 3) and a doctor handed both would resolve its
 * bottom at the slot — so a "the stack was used" assertion would pass with the stack having carried
 * nothing. `DownloadHttpStackTest` says the same about a store's.
 */
@RunWith(AndroidJUnit4::class)
class DoctorHttpStackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * The fetch travels the stack, and the report still says which layers it travelled.
     *
     * The second half is the one a reader might not expect, and it is a decision rather than an omission:
     * a stack gets **no** [ChainLayer] entry, which [ChainLayer]'s KDoc argues — that field answers "was
     * this optional layer composed", and every chain has exactly one bottom. So the reading here is that
     * the header-refresh layer is still reported on a doctor that also names a stack, rather than the
     * chain's report having been displaced by it.
     */
    @Test
    fun aDoctorsFetchTravelsTheStackAndItsReportStillNamesTheLayers() {
        val content = TestContent.hls()
        val environment = harness.diagnosticEnvironment(content, bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT)

        val report = MediaSourceDoctor.Builder(context)
            .setEnvironment(environment)
            .setResilience(Resilience.standard(headers = SilentProvider))
            .setHttpStack(harness.consumersHttpStack(environment))
            .build()
            .examine(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())

        // The playlists really were fetched — over a transport the consumer wrote, since nothing else could
        // have served them with the slot empty — and a healthy stream is still healthy over it.
        assertWithMessage("the multivariant playlist and the media playlist it names")
            .that(harness.networkRequests(environment)).hasSize(2)
        assertThat(report.findings).isEmpty()
        assertThat(report.chain).containsExactly(ChainLayer.HEADER_REFRESH)
    }

    /**
     * ADR-0016 rule 14, counted for a doctor as `SuperPlayerHttpStackTest` counts it for a player: one
     * built without [MediaSourceDoctor.Builder.setHttpStack] asks the consumer's transport for nothing and
     * keeps the stack that shipped before this seam.
     *
     * The counter is shown to see what it counts. With the slot empty and no stack named, the doctor
     * resolves `DefaultDataSource` over Media3's own `DefaultHttpDataSource`, which the synthetic stream's
     * `fake:` URIs reach and which cannot serve them — so the fetch is refused, the transport records
     * nothing, and the refusal arrives as the finding rule 7 asks for rather than as an exception. The
     * same doctor with the stack named fetches both playlists and finds nothing wrong.
     */
    @Test
    fun aDoctorBuiltWithNoStackAsksTheConsumersTransportNothing() {
        val content = TestContent.hls()
        val environment = harness.diagnosticEnvironment(content, bottom = ChainBottom.CONSUMERS_HTTP_TRANSPORT)
        val request = MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

        val without = MediaSourceDoctor.Builder(context).setEnvironment(environment).build().examine(request)

        assertWithMessage("a doctor told about no stack").that(harness.networkRequests(environment)).isEmpty()
        assertThat(without.findings.map { it.pathology }).containsExactly(Pathology.MANIFEST_UNREACHABLE)

        val with = MediaSourceDoctor.Builder(context)
            .setEnvironment(environment)
            .setHttpStack(harness.consumersHttpStack(environment))
            .build()
            .examine(request)

        assertWithMessage("and the same doctor with one").that(harness.networkRequests(environment)).hasSize(2)
        assertThat(with.findings).isEmpty()
    }

    /**
     * A credential the app would mint, which nothing here refuses: the layer's presence is what is being
     * read, and a refusal of its own would be `MediaSourceDoctorTest`'s subject rather than this one's.
     */
    private object SilentProvider : HeaderProvider {
        override fun refreshedHeaders(refusal: CredentialRefusal): Map<String, String> = emptyMap()
    }

    private companion object {
        const val CONTENT_ID = "film/the-lady-vanishes"
    }
}
