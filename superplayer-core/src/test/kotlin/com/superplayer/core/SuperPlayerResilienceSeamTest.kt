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

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two slots ADR-0011 rule 13 adds to core's engine seam, driven by a resilience that is
 * hand-written here rather than `superplayer-resilience`'s.
 *
 * No rung of the ladder is built yet and none is asserted here. What is asserted is the seam itself,
 * which is the whole of issue #176: that a layer put in the header-refresh slot sees every request
 * with what it needs to tell one load from another, that the object put in the load-error slot is the
 * one Media3 asks about a failed load, that the two together are enough to turn a failure into a
 * successful retry — and that a player built without either is the Phase 4 player byte for byte.
 *
 * The test resilience below refreshes nothing. It sits in the slots, fails one segment with a 403
 * and lets the retry through, which is exactly the traffic a real token refresh will carry.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerResilienceSeamTest {

    /** Robolectric has no real codecs; the renderer pipeline runs against shadow ones. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    /**
     * ADR-0011 rule 14, counted: a player built without resilience leaves both slots empty, keeps
     * Media3's own load-error handling, and stamps no request — so its chain and its media source
     * factory are the ones Phase 4 built. The same counts on a player *with* resilience are
     * non-empty, so the counter is shown to see what it counts.
     *
     * Nothing counts a registration, because there is nothing yet to register: the rungs are reached
     * from inside a load and from core's own seam, and no listener of resilience's exists to attach
     * to a player (ADR-0011 rules 5 and 7). What a stamp counts is the thing that *would* change
     * silently — the media source factory, which is Media3's own on a player with neither slot filled
     * and a stamping one on a player with either.
     *
     * The rule's other half — that a core-only session is unchanged byte for byte — is held by the
     * golden traces in `superplayer-telemetry`, which are core-only sessions and which this change
     * leaves identical, exactly as ADR-0010 rule 13's half is held for the cache.
     */
    @Test
    fun aPlayerBuiltWithoutResilienceFillsNeitherSlotAndStampsNothing() {
        var withoutSlots: EngineConfiguration? = null
        val withoutMeter = RecordingBandwidthMeter()
        val without = harness.buildPlayer(
            alsoConfigure = { withoutSlots = it },
            alsoConfigureEngine = { it.setBandwidthMeter(withoutMeter) },
        )
        playUntilReady(without)

        assertThat(withoutSlots?.headerRefresh).isNull()
        assertThat(withoutSlots?.loadErrors).isNull()
        assertThat(withoutMeter.openedRequests()).isNotEmpty()
        assertThat(withoutMeter.openedRequests().mapNotNull { RequestStamp.of(it) }).isEmpty()

        var withSlots: EngineConfiguration? = null
        val withMeter = RecordingBandwidthMeter()
        val with = harness.buildPlayer(
            resilience = TestResilience(RecordingHeaderRefresh(), RecordingLoadErrors()),
            alsoConfigure = { withSlots = it },
            alsoConfigureEngine = { it.setBandwidthMeter(withMeter) },
        )
        playUntilReady(with)

        assertThat(withSlots?.headerRefresh).isNotNull()
        assertThat(withSlots?.loadErrors).isNotNull()
        assertThat(withMeter.openedRequests().mapNotNull { RequestStamp.of(it) }).isNotEmpty()
    }

    /**
     * What ADR-0011 rule 13 means by "the classifier's inputs reach the slot": the layer is handed
     * every request, and each one says what kind of load it is, so a 401 on a segment is
     * distinguishable from a failure of the manifest before anything is repaired.
     *
     * The identity is a cache's key and this player has no cache, so it is absent — the two stamps
     * are deliberately not one decision.
     */
    @Test
    fun theHeaderRefreshSlotSeesEveryRequestAndWhatKindOfLoadItIs() {
        val refresh = RecordingHeaderRefresh()
        val player = harness.buildPlayer(resilience = TestResilience(refresh, RecordingLoadErrors()))

        playUntilReady(player)

        val seen = refresh.seen()
        val manifests = seen.filter { it.kind == LoadKind.MANIFEST }.map { it.uri.toString() }
        assertThat(manifests).contains(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
        val media = seen.filter { it.kind == LoadKind.MEDIA }.map { it.uri.toString() }
        assertThat(media.any { it.endsWith(SyntheticHlsStream.SEGMENT_SUFFIX) }).isTrue()
        assertThat(seen.filter { it.kind == LoadKind.UNCLASSIFIED }).isEmpty()
        assertThat(seen.mapNotNull { it.contentId }).isEmpty()
    }

    /**
     * Both slots at once, on a real failure: the layer refuses one segment with a 403, Media3 asks
     * the object in the load-error slot what to do about it, and the retry the answer allows reaches
     * the transport and plays.
     *
     * The slot is the subject rather than the recovery. That a fault can be made to relent is
     * `superplayer-testkit`'s (#175); what this pins is that the two halves of a rung — the
     * transfer that failed and the question Media3 asks about it — are both reachable from one
     * object, which is the thing no player could do before this change.
     */
    @Test
    fun bothSlotsFilledTurnAFailedSegmentIntoAQuestionMedia3AsksAndARetryThatSucceeds() {
        val refresh = RecordingHeaderRefresh(failFirstMediaRequestWith = FORBIDDEN)
        val loadErrors = RecordingLoadErrors()
        val player = harness.buildPlayer(resilience = TestResilience(refresh, loadErrors))

        playUntilReady(player)

        // Media3 asked the object in the slot, and it was asked about the failure the layer raised:
        // the status and the URI a classifier reads are both on what it was handed.
        val asked = loadErrors.seen()
        assertThat(asked).isNotEmpty()
        val forbidden = asked.first()
        assertThat(forbidden.responseCode).isEqualTo(FORBIDDEN)
        assertThat(forbidden.uri.toString()).endsWith(SyntheticHlsStream.SEGMENT_SUFFIX)
        // And the answer was honoured through the built chain rather than ending the session: the
        // segment was opened twice through the slot, and the second time it carried bytes.
        assertThat(refresh.seen().count { it.uri == forbidden.uri }).isAtLeast(2)
        assertThat(player.playerError).isNull()
    }

    /**
     * `TransferChain`'s rule for every layer: the engine's transfer listener reaches the transport
     * through the header-refresh slot and the kind stamp above it, so a meter still sees each
     * transfer. A layer that swallowed the registration would blind bandwidth estimation with
     * nothing in the logs, which is what `superplayer-abr` would find out about last.
     */
    @Test
    fun transfersThroughTheHeaderRefreshSlotStillReachTheBandwidthMeter() {
        val refresh = RecordingHeaderRefresh()
        val meter = RecordingBandwidthMeter()
        val player = harness.buildPlayer(
            resilience = TestResilience(refresh, RecordingLoadErrors()),
            alsoConfigureEngine = { it.setBandwidthMeter(meter) },
        )

        playUntilReady(player)

        assertThat(meter.segmentRequest()).isNotNull()
        assertThat(refresh.seen().map { it.uri })
            .containsAtLeastElementsIn(meter.openedRequests().map { it.uri })
    }

    private fun playUntilReady(player: SuperPlayer) {
        player.setMediaRequest(
            MediaRequest.Builder("episode:resilient")
                .addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
                .build(),
        )
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
    }

    /** A resilience that survives nothing: it fills the two slots and is otherwise empty. */
    private class TestResilience(
        private val refresh: HeaderRefreshLayer,
        private val loadErrors: LoadErrorHandlingPolicy,
    ) : EngineResilienceExtension {

        override fun configureEngine(configuration: EngineConfiguration) {
            configuration.headerRefresh = refresh
            configuration.loadErrors = loadErrors
        }
    }

    /** One request as the header-refresh slot saw it. */
    private data class SeenRequest(val uri: Uri, val contentId: String?, val kind: LoadKind)

    /**
     * Records what each request carries into the slot and, when asked, refuses the first media
     * request with a status — which is how a real layer learns that a credential has expired.
     */
    private class RecordingHeaderRefresh(private val failFirstMediaRequestWith: Int? = null) : HeaderRefreshLayer {

        /**
         * What this layer saw and whether it has spent its one refusal — both guarded by [seen],
         * because loads open on loader threads while assertions run on the test's, and one lock for
         * one layer's state is one thing to reason about.
         */
        private val seen = mutableListOf<SeenRequest>()
        private var refused = false

        fun seen(): List<SeenRequest> = synchronized(seen) { seen.toList() }

        override fun over(upstream: DataSource.Factory): DataSource.Factory =
            DataSource.Factory { Recording(upstream.createDataSource()) }

        /** Records [dataSpec], and returns the status to refuse it with if it is the one to refuse. */
        private fun record(dataSpec: DataSpec): Int? = synchronized(seen) {
            seen += SeenRequest(dataSpec.uri, ContentIdentity.of(dataSpec), LoadKind.of(dataSpec))
            val status = failFirstMediaRequestWith ?: return@synchronized null
            if (refused || LoadKind.of(dataSpec) != LoadKind.MEDIA) return@synchronized null
            refused = true
            status
        }

        // Every method written out rather than delegated with `by`: `getResponseHeaders` is a Java
        // default method, which Kotlin delegation would not forward (ADR-0003's trap).
        private inner class Recording(private val upstream: DataSource) : DataSource {

            /**
             * Whether [upstream] was ever opened, so that a refused request closes nothing.
             * Media3 closes a source whose `open` threw, and `FakeDataSource` — unlike an HTTP one —
             * rejects a close it was never opened for.
             */
            private var opened = false

            override fun addTransferListener(transferListener: TransferListener) {
                upstream.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                record(dataSpec)?.let { status ->
                    throw HttpDataSource.InvalidResponseCodeException(
                        status,
                        "Refused $status for ${dataSpec.uri}",
                        /* cause= */ null,
                        /* headerFields= */ emptyMap(),
                        dataSpec,
                        /* responseBody= */ ByteArray(0),
                    )
                }
                return upstream.open(dataSpec).also { opened = true }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

            override fun getUri(): Uri? = upstream.uri

            override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

            override fun close() {
                if (opened) {
                    opened = false
                    upstream.close()
                }
            }
        }
    }

    /** One load error as the load-error slot was asked about it. */
    private data class SeenLoadError(val uri: Uri, val responseCode: Int?)

    /**
     * Media3's own policy, recording what it was asked about.
     *
     * A subclass rather than a hand-written implementation because the answers are not what this
     * test is about: the answer that matters is the one Media3's default already gives a 403 — retry
     * it — and re-deriving that here would be asserting on this file's arithmetic rather than on the
     * slot. `superplayer-resilience`'s own object replaces the answers, at #178.
     */
    private class RecordingLoadErrors : DefaultLoadErrorHandlingPolicy() {

        private val seen = mutableListOf<SeenLoadError>()

        fun seen(): List<SeenLoadError> = synchronized(seen) { seen.toList() }

        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val exception = loadErrorInfo.exception
            synchronized(seen) {
                seen += SeenLoadError(
                    loadErrorInfo.loadEventInfo.uri,
                    (exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode,
                )
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private companion object {
        const val FORBIDDEN = 403
    }
}
