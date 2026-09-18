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

package com.superplayer.core

import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What core's adapter makes of a status a consumer's transport *reported* — ADR-0016 rule 8, at the
 * one place it is discharged.
 *
 * The consumer returns a number. Everything the rest of the library reads about that failure is
 * built here: `TokenRefreshLayer` decides whether a credential may be repaired off `responseCode`,
 * `ErrorClassifier` decides what the failure *is* off the same field and off the `LoadKind` stamped
 * on the `DataSpec` the refusal carries, `superplayer-diagnostics` prints the status into a finding's
 * magnitude, and `SessionTraceRecorder` writes `http=<code>` rather than `cause=<class>`. None of
 * those fails loudly when the evidence goes missing — sessions simply end unclassified — so this
 * class asserts the evidence itself, and `superplayer-resilience`'s `ConsumersTransportParityTest`
 * asserts what the library then does with it.
 *
 * ## Why the transport is the origin, again
 *
 * [ServingTransport]'s reason, which `docs/testing.md`'s *The one player that keeps its own
 * transport* gives: every other harness in this repository substitutes a fake data source exactly
 * where a stack goes, which makes this seam invisible to all of them. A transport that is itself the
 * origin also controls the *response headers*, which no fake origin here can express and which are
 * one of the three things rule 8 says must survive.
 */
@RunWith(AndroidJUnit4::class)
class ConsumersTransportEvidenceTest {

    /** Robolectric has no real codecs; the renderer pipeline runs against shadow ones. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    /** What a [RecordingTelemetry] built here writes to. */
    private val events = mutableListOf<TelemetryEvent>()

    /**
     * The three facts rule 8 names, on one refusal: the status, the response headers, and the URI.
     *
     * A 403 with a `WWW-Authenticate` challenge, because that is the shape the rule's own example
     * takes — an expiring CDN credential — and because the header is the part a consumer's client
     * hands back separately from the status, so a transport wired up to report one and not the other
     * would pass a status-only assertion.
     *
     * The URI is asserted as the one the chain asked for rather than the one the origin holds: a
     * refusal whose `DataSpec` had been rebuilt from somewhere else is the defect, and it is the
     * same defect that would lose the `LoadKind` stamp with it (asserted below).
     */
    @Test
    fun aStatusAConsumerReportsBecomesTheEvidenceTheLibraryReads() {
        val resources = ServingTransport.hlsOverHttps()
        val segment = ServingTransport.segmentUriIn(resources)
        val transport = ServingTransport(
            resources,
            refusals = mapOf(segment to ServingTransport.Refusal(FORBIDDEN, mapOf(CHALLENGE to listOf(BEARER)))),
        )
        val player = playToFailure(transport)

        val refused = refusalIn(player)
        assertThat(refused.responseCode).isEqualTo(FORBIDDEN)
        assertThat(refused.headerFields).containsEntry(CHALLENGE, listOf(BEARER))
        assertThat(refused.dataSpec.uri.toString()).isEqualTo(segment)
    }

    /**
     * The stamp, in the one place it can be read without a resilience module: the `DataSpec` a
     * refusal carries is the one the chain handed *down*, not one the adapter built.
     *
     * `ErrorClassifier` reads `LoadKind.of(refused.dataSpec)` and gives up unless it says media,
     * which is what tells a refused segment from a refused manifest. An adapter that built a fresh
     * `DataSpec` from the `HttpRequest` would lose the stamp, every refused segment would quietly
     * reclassify as a refused manifest, and nothing anywhere would throw. Here the stamp is asserted
     * directly; `ConsumersTransportParityTest` asserts the classification it decides.
     *
     * The player is built with a resilience of its own — the hand-written one
     * [SuperPlayerResilienceSeamTest] uses, doing nothing but filling a slot — because a filled slot
     * is what makes core stamp a kind at all (ADR-0011 rule 14).
     */
    @Test
    fun aRefusalCarriesTheStampedRequestAndNotAFreshlyBuiltOne() {
        val resources = ServingTransport.hlsOverHttps()
        val segment = ServingTransport.segmentUriIn(resources)
        val transport = ServingTransport(
            resources,
            refusals = mapOf(segment to ServingTransport.Refusal(FORBIDDEN)),
        )
        val player = playToFailure(transport, resilience = SlotFillingResilience())

        assertThat(LoadKind.of(refusalIn(player).dataSpec)).isEqualTo(LoadKind.MEDIA)
    }

    /**
     * CMCD is undisturbed, and the `sid` still joins to the telemetry session (ADR-0008 rule 6).
     *
     * **Nothing new is plumbed here and that is the assertion.** CMCD attaches to the
     * `MediaSource.Factory`, which is above the whole transfer chain, and a transport is below the
     * bottom of it — so the keys are composed into the request long before any of this reaches a
     * consumer's client. What could go wrong is the request arriving without them, which is what a
     * bottom that rebuilt a request rather than carrying the one it was given would look like, and
     * that is exactly what the transport is asked here: the headers it *received*.
     *
     * Read at the transport rather than at a bandwidth meter, unlike `SuperPlayerCmcdTest`, because
     * the transport is the last thing in the process to see a request and therefore the strongest
     * place the equality can be stated.
     */
    @Test
    fun cmcdStillTravelsToTheBottomAndItsSessionIdStillJoins() {
        val transport = ServingTransport(ServingTransport.hlsOverHttps())
        val player = harness.buildPlayerOnItsOwnTransferChain(
            httpStack = HttpStack.of(transport),
            telemetry = RecordingTelemetry { event -> events += event },
        )
        player.setMediaRequest(MediaRequest.Builder(EPISODE).addSource(multivariantPlaylist()).build())
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        val started = events.filterIsInstance<TelemetryEvent.SessionStarted>().single()
        val keys = cmcdKeysOf(segmentRequest(transport))
        // The whole value of CMCD: this equality is what joins a row in a CDN's access log to a row
        // in the app's warehouse, and `docs/telemetry-schema.md` states it as a promise.
        assertThat(keys["sid"]).isEqualTo("\"${started.sessionId}\"")
        // The content id travels as the app's own identity rather than as a URL, over here too.
        assertThat(keys["cid"]).isEqualTo("\"$EPISODE\"")
        assertThat(player.playerError).isNull()
    }

    /** A player over [transport] that runs until it fails, which every refusal here makes it do. */
    private fun playToFailure(
        transport: ServingTransport,
        resilience: PlaybackResilience? = null,
    ): SuperPlayer {
        val player = harness.buildPlayerOnItsOwnTransferChain(
            httpStack = HttpStack.of(transport),
            resilience = resilience,
        )
        player.setMediaRequest(MediaRequest.Builder(EPISODE).addSource(multivariantPlaylist()).build())
        player.prepare()
        TestPlayerRunHelper.advance(player).untilPlayerError()
        return player
    }

    /** The refusal the session ended on, found where every error a consumer sees carries its causes. */
    private fun refusalIn(player: SuperPlayer): HttpDataSource.InvalidResponseCodeException =
        generateSequence(player.playerError as Throwable?) { it.cause.takeIf { cause -> cause !== it } }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?: error("The session did not end on a status the transport reported: ${player.playerError}")

    /** The one media segment the session asked the transport for. */
    private fun segmentRequest(transport: ServingTransport): ServingTransport.Exchange =
        transport.requests.first { it.uri.path.orEmpty().endsWith(SyntheticHlsStream.SEGMENT_SUFFIX) }

    /**
     * The CMCD keys on a request as the transport received it.
     *
     * Headers only: `CmcdMode.HEADERS` is the default and is what this player uses, and the split is
     * `splitCmcdPairs`' rather than a second copy of CTA-5004 §3.1's quoting rule.
     */
    private fun cmcdKeysOf(exchange: ServingTransport.Exchange): Map<String, String> =
        exchange.requestHeaders
            .filterKeys { it.startsWith("CMCD-") }
            .values
            .flatMap(::splitCmcdPairs)
            .associate { pair -> pair.substringBefore('=') to pair.substringAfter('=', "") }

    /**
     * A resilience that fills core's two slots and changes nothing.
     *
     * The seam test's shape: what it is for is the *stamp*, which core applies whenever a slot is
     * filled, and a pass-through in both slots is the smallest thing that turns it on without
     * bringing `superplayer-resilience` — a phase 5 module core cannot depend on — into this test.
     */
    private class SlotFillingResilience :
        PlaybackResilience,
        EngineResilienceExtension {
        override fun configureEngine(configuration: EngineConfiguration) {
            // A layer that changes nothing, which is what `StandardResilience` installs when no
            // `HeaderProvider` was handed in — filled rather than left null precisely because a
            // filled slot is what turns the kind stamp on.
            configuration.headerRefresh = HeaderRefreshLayer { upstream -> upstream }
        }
    }

    private companion object {
        const val EPISODE = "series-9:episode-4"

        /** // spec: RFC 9110 §15.5.4. */
        const val FORBIDDEN = 403

        /** // spec: RFC 9110 §11.6.1 — how a refusal says what credential it wanted. */
        const val CHALLENGE = "WWW-Authenticate"
        const val BEARER = "Bearer realm=\"cdn\""

        /** The stream's entry point, over the scheme a consumer's transport is handed. */
        fun multivariantPlaylist(): String =
            ServingTransport.httpsFor(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
    }
}
