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
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rung 5 as core performs it: the player re-prepared where it stood, once the ladder says a decoder
 * recreated is the remedy (ADR-0011 rules 5, 7 and 9; issue #182).
 *
 * ## What this test cannot show, and why
 *
 * **No decoder fails here, because nothing in this repository can make one fail.** Robolectric's
 * codecs are shadows: `ShadowMediaCodecConfig` says which of them *exist* and
 * `DeviceStatement.declareVideoDecoder` says how many instances a device admits, and neither can
 * raise a `MediaCodec.CodecException` part-way through a stream — which is the event rung 5 exists
 * for, and the only event that produces the `Device.DecoderTransient` classification the rung acts
 * on. Nor is there a device in `check` to produce one: `docs/testing.md` bars both the device and the
 * network.
 *
 * So the rung is forced here the one honest way the harness allows, and the split is deliberate:
 *
 * - **That the classification routes to this rung and to no other** is asserted where the
 *   classification lives, over real `PlaybackException`s, in `superplayer-resilience`'s
 *   `FallbackLadderTest` — including that a `Device.DecoderInit` is refused it, which is the
 *   acceptance criterion this file cannot state.
 * - **That core performs the rung correctly once asked** is asserted below, with a `PlayerStateRungs`
 *   written in this file standing in for the ladder and an ordinary transfer failure standing in for
 *   the decoder failure. What is under test is the *player's* half — the position, the identity, the
 *   bound, the order, the withheld error — and every one of those is independent of which failure
 *   asked for the rung.
 *
 * What remains unshown is the middle: that a real transient decoder failure on a real device
 * surfaces as a `PlaybackException` this path sees, and that a decoder rebuilt by `prepare()` renders
 * again. That is a claim a device settles and this harness cannot, and it is the same limit
 * `superplayer-preload`'s `DecoderWarmupTest` states for a warm first frame.
 *
 * Nothing reaches past the facade: the player is built as a consumer builds one, and the seam the
 * test resilience fills is the one ADR-0011 rule 13 opened for `superplayer-resilience`.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerDecoderRecreationTest {

    /** Robolectric has no real codecs; the renderer pipeline runs against shadow ones. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    @Test
    fun aFailureTheLadderAnswersWithARecreatedDecoderIsRePreparedAndPlaysOn() {
        val ladder = TestRungs(recreates = true)
        val player = buildPlayer(ladder, relents = Relenting.ONCE_THE_RUNG_IS_PERFORMED)

        playFromTheWatchedPosition(player)

        // The session was rescued rather than ended, which is what makes this a rung: ADR-0011 rule
        // 10 reserves the delivered error for rung 6, and this failure never got that far.
        assertThat(player.playerError).isNull()
        assertWithMessage("the ladder was asked for a decoder recreation")
            .that(ladder.decoderQuestions).isEqualTo(1)
        // And it played on past the failure rather than merely surviving it.
        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
    }

    @Test
    fun theRecreatedDecoderResumesWherePlaybackHadReached() {
        // Rule 9 for the rung core performs: the assertion is against the position the failure
        // interrupted rather than against zero, because a remedy that restarts the programme is the
        // defect `PRD.md` §3.3 calls worse than the error.
        val positions = PositionsAcrossTheFailure()
        val player = buildPlayer(TestRungs(recreates = true), relents = Relenting.ONCE_THE_RUNG_IS_PERFORMED)
        positions.player = player
        player.addListener(positions)

        playFromTheWatchedPosition(player)

        // The two readings are taken from a consumer's listener rather than after the fact, because
        // the failure and the re-prepare are one dispatch: a test that waited for `STATE_IDLE` would
        // never see it, the rung having moved the player on before the wait could look.
        val atTheFailureMs = positions.atTheFailureMs
        // The viewer was twenty seconds in when the decoder failed, so the position under test is a
        // real one rather than rounding near zero ([playFromTheWatchedPosition]).
        assertWithMessage("playback had got somewhere before the decoder failed")
            .that(atTheFailureMs).isAtLeast(SyntheticHlsStream.SEGMENT_DURATION_MS)
        // Never backwards, which is the whole of rule 9's promise. No tolerance is given and none is
        // needed: the rung replaces no item and seeks nothing, so the position Media3 held across the
        // re-prepare is the position it had.
        assertWithMessage("where the recreated decoder resumed")
            .that(positions.atTheResumptionMs).isAtLeast(atTheFailureMs)
        assertThat(player.playerError).isNull()
    }

    @Test
    fun theSourceAndTheSessionAreTheOnesTheViewingStartedWith() {
        // Rung 5 replaces nothing, so the identity, the measurement session and the CMCD `sid` are
        // untouched: one viewing rescued is one session (ADR-0011 rule 10). Counted rather than
        // inferred, because a rung routed through `adopt` or through `setMediaItem` would look
        // identical to a viewer and would report two viewings of one programme.
        val sessions = SessionCounting()
        val player = buildPlayer(
            TestRungs(recreates = true),
            relents = Relenting.ONCE_THE_RUNG_IS_PERFORMED,
            telemetry = sessions,
        )

        playFromTheWatchedPosition(player)

        assertThat(player.playerError).isNull()
        assertThat(player.currentMediaItem?.mediaId).isEqualTo(CONTENT)
        assertThat(player.currentMediaItem?.localConfiguration?.uri.toString()).isEqualTo(SOURCE)
        assertWithMessage("measurement sessions opened for one viewing")
            .that(sessions.startedSessions).isEqualTo(1)
    }

    @Test
    fun theNextSourceIsOfferedTheFailureBeforeTheDecoderIs() {
        // ADR-0011 rule 7's order, between the two rungs core performs and in the one place both are
        // decided. It costs a real `Device.DecoderTransient` nothing, because the ladder refuses that
        // class the next source (`FallbackLadderTest`) — but the order has to hold here whatever the
        // ladder answers, which is why the stand-in ladder refuses rung 4 rather than having no rung
        // 4 to be offered.
        //
        // The request names its source twice for that reason and no other: rung 4's question is put
        // only where there is a next source to open, so a request with one source would prove the
        // order by an absence. Nothing opens the second one — the ladder declines it — and the two
        // being the same stream is what makes that visible if it ever stops being true.
        val ladder = TestRungs(recreates = true)
        val player = buildPlayer(ladder, relents = Relenting.ONCE_THE_RUNG_IS_PERFORMED)

        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(SOURCE).addSource(SOURCE).build())
        player.seekTo(WATCHED_TO_MS)
        player.prepare()
        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_ENDED)

        assertThat(ladder.questions).containsExactly(NEXT_SOURCE_QUESTION, RECREATE_QUESTION).inOrder()
    }

    @Test
    fun aDecoderThatDoesNotComeBackIsRecreatedATimeOrTwoAndThenTheFailureIsDelivered() {
        // The bound, which is the difference between a remedy and a loop: the fault never relents, so
        // every re-prepare meets it again at the position it met it at first. After
        // `MAX_DECODER_RECREATIONS` the rung is not offered the failure at all and rung 6 has it.
        val ladder = TestRungs(recreates = true)
        val errors = ErrorsSeenByAConsumer()
        val player = buildPlayer(ladder, relents = Relenting.NEVER)
        player.addListener(errors)

        player.setMediaRequest(request())
        player.seekTo(WATCHED_TO_MS)
        player.prepare()
        player.play()
        TestPlayerRunHelper.advance(player).untilPlayerError()

        assertWithMessage("decoder recreations performed for one stuck position")
            .that(ladder.decoderQuestions).isEqualTo(SuperPlayer.MAX_DECODER_RECREATIONS)
        // The consumer is told once, about the failure nothing repaired — and not about the ones the
        // rung took on, which is ADR-0011 rule 10's addendum holding for this rung too.
        assertThat(player.playerError).isNotNull()
        assertThat(errors.errors).hasSize(1)
    }

    @Test
    fun aPlayerWithoutResilienceRePreparesNothing() {
        // ADR-0011 rule 14 for this rung, counted as behaviour: a player with nobody to ask performs
        // no rung, so the same fault ends the same session where it always did. It is what makes
        // every test above a test of the ladder rather than of something Media3 does on its own.
        val errors = ErrorsSeenByAConsumer()
        val player = harness.buildPlayer(
            fakeDataSet = SyntheticHlsStream.addTo(FakeDataSet(), segmentCount = SEGMENTS),
            resilience = RefusingResilience(relents = Relenting.NEVER, ladder = null),
        )
        player.addListener(errors)

        player.setMediaRequest(request())
        player.seekTo(WATCHED_TO_MS)
        player.prepare()
        player.play()
        TestPlayerRunHelper.advance(player).untilPlayerError()

        assertThat(player.playerError).isNotNull()
        assertWithMessage("failures delivered to a consumer that has no rung to hide one")
            .that(errors.errors).hasSize(1)
    }

    /**
     * A player with the third slot filled by [ladder] and a transfer that refuses one segment.
     *
     * The refusal is injected through the header-refresh slot, as `SuperPlayerResilienceSeamTest`
     * injects one: it is the layer closest to the transport, so a request refused there is a request
     * that failed on the wire, which is the shape of failure the rest of the chain and the engine
     * expect.
     */
    private fun buildPlayer(
        ladder: TestRungs,
        relents: Relenting,
        telemetry: TelemetryCollector? = null,
    ): SuperPlayer = harness.buildPlayer(
        fakeDataSet = SyntheticHlsStream.addTo(FakeDataSet(), segmentCount = SEGMENTS),
        telemetry = telemetry,
        resilience = RefusingResilience(relents, ladder),
    )

    /**
     * Plays the request from [WATCHED_TO_MS], which is where the faulted segment is, to the end.
     *
     * **The seek is what gives the rung a position to preserve, and it is not decoration.** Nothing
     * paces a transfer under this harness and the clock advances as fast as the playback thread will
     * let it, so a player pointed at the beginning of a short stream has buffered and played the whole
     * of it in one step — every failure of such a session happens at a position of zero, and "the
     * position playback had reached" would be a claim about nothing. A seek before `prepare` is the
     * cheap way to put the playhead somewhere a viewer could recognise: the first segment the session
     * asks for is the faulted one, and the position it is asked at is the position the rung has to
     * come back to. It is a *seek* rather than a `StartPosition` on the request for the sharper
     * version of the same reason: the held position is then one the request does not name, so a rung
     * that read the request instead of the player would resume at the beginning and be caught.
     */
    private fun playFromTheWatchedPosition(player: SuperPlayer) {
        player.setMediaRequest(request())
        player.seekTo(WATCHED_TO_MS)
        player.prepare()
        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_ENDED)
    }

    private fun request(): MediaRequest = MediaRequest.Builder(CONTENT).addSource(SOURCE).build()

    /**
     * A collector that counts the session edges and nothing else.
     *
     * Hand-written rather than this package's `RecordingTelemetry`, because the claim under test is
     * core's signalling — how many sessions one viewing opened — and a sink and an analytics listener
     * are not part of it.
     */
    private class SessionCounting : TelemetryCollector {

        var startedSessions: Int = 0
            private set

        override fun attach(player: SuperPlayer) = Unit

        override fun startSession(contentId: String, sessionId: String) {
            startedSessions++
        }

        override fun endSession() = Unit

        override fun declareIntent(monotonicTimeMs: Long) = Unit

        override fun decisionChanged(decision: PlaybackDecision, trigger: DecisionTrigger) = Unit

        override fun detach() = Unit
    }

    /**
     * When the faulted segment starts being served again: once the rung has been performed, which is
     * a decoder that came back, or never, which is one that did not.
     */
    private enum class Relenting { ONCE_THE_RUNG_IS_PERFORMED, NEVER }

    /**
     * The ladder, as far as core can see one: a `PlayerStateRungs` that records what it was asked and
     * answers rung 4 no.
     *
     * No for rung 4 because a request with one source has no next one anyway, and because what these
     * tests are about is the rung above it; recording *both* questions is what lets the order be
     * asserted rather than assumed.
     */
    private class TestRungs(private val recreates: Boolean) : PlayerStateRungs {

        /**
         * What was asked, in order. Guarded, because the questions are put on the application thread
         * while the fault below reads the count from a loading thread.
         */
        private val asked = mutableListOf<String>()

        val questions: List<String> get() = synchronized(asked) { asked.toList() }

        val decoderQuestions: Int get() = questions.count { it == RECREATE_QUESTION }

        override fun opensNextSource(error: PlaybackException): Boolean {
            synchronized(asked) { asked += NEXT_SOURCE_QUESTION }
            return false
        }

        override fun recreatesDecoder(error: PlaybackException): Boolean {
            synchronized(asked) { asked += RECREATE_QUESTION }
            return recreates
        }
    }

    /**
     * A resilience that fills the third slot with [ladder] and the header-refresh slot with a layer
     * that refuses one segment, and does nothing else.
     *
     * A null [ladder] leaves the third slot empty, which is the player rule 14 describes: the two
     * slots that configure the engine are filled exactly as before, and the one core interrogates is
     * not, so no error listener is registered and no rung is performed.
     */
    private class RefusingResilience(
        private val relents: Relenting,
        private val ladder: TestRungs?,
    ) : EngineResilienceExtension {

        override fun configureEngine(configuration: EngineConfiguration) {
            configuration.headerRefresh = RefusingLayer(relents, ladder)
            ladder?.let { configuration.playerStateRungs = it }
        }
    }

    /**
     * The transfer failure that stands in for the decoder failure: a 502 on one segment, refused
     * until the rung has been performed or for ever.
     *
     * A 502 rather than a 403 or a 404 because Media3's own fallback table does not list it
     * (// ref: RFC 9110 §15.6.3, and `DefaultLoadErrorHandlingPolicy.isEligibleForFallback`), so the
     * load fails after Media3's own retries rather than being answered by an exclusion this test
     * would then be measuring instead.
     *
     * "Until the rung has been performed" is how the fault relents: a decoder that comes back is one
     * whose failure does not recur, and the rung being performed is the only event on this side that
     * says the player has taken the failure on. It leaves the count of refusals unfixed, which is
     * deliberate — how many times Media3 retries a load before it surfaces is Media3's number, and a
     * test that hard-coded it would fail on a Media3 upgrade for a reason that has nothing to do with
     * this rung.
     */
    private class RefusingLayer(
        private val relents: Relenting,
        private val ladder: TestRungs?,
    ) : HeaderRefreshLayer {

        override fun over(upstream: DataSource.Factory): DataSource.Factory =
            DataSource.Factory { Refusing(upstream.createDataSource()) }

        /** Whether [dataSpec] is the segment this layer refuses, and is still refusing. */
        private fun refuses(dataSpec: DataSpec): Boolean {
            if (LoadKind.of(dataSpec) != LoadKind.MEDIA) return false
            if (!dataSpec.uri.toString().endsWith(FAULTED_SEGMENT_NAME)) return false
            if (relents == Relenting.NEVER) return true
            // The stand-in ladder is asked once per surfaced failure and only for a failure core is
            // taking on, so a question asked is a re-prepare performed.
            return ladder?.decoderQuestions == 0
        }

        private inner class Refusing(private val upstream: DataSource) : DataSource {

            /**
             * Whether [upstream] was ever opened, so that a refused request closes nothing:
             * `FakeDataSource` — unlike an HTTP one — rejects a close it was never opened for, and
             * Media3 closes a source whose `open` threw.
             */
            private var opened = false

            override fun addTransferListener(transferListener: TransferListener) {
                upstream.addTransferListener(transferListener)
            }

            override fun open(dataSpec: DataSpec): Long {
                if (refuses(dataSpec)) {
                    throw HttpDataSource.InvalidResponseCodeException(
                        BAD_GATEWAY,
                        "Refused $BAD_GATEWAY for ${dataSpec.uri}",
                        /* cause= */ null,
                        /* headerFields= */ emptyMap(),
                        dataSpec,
                        /* responseBody= */ ByteArray(0),
                    )
                }
                return upstream.open(dataSpec).also { opened = true }
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                upstream.read(buffer, offset, length)

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

    /**
     * Where playback stood each time the engine went idle, read from a consumer's listener.
     *
     * The failure itself is withheld from a consumer (ADR-0011 rule 10's addendum) and the state
     * change is not, which is what makes this the one reading of the interrupted position a test can
     * take through the public API.
     */
    private class PositionsAcrossTheFailure : Player.Listener {

        /** Where playback stood when the engine went idle, and where it stood when it came back. */
        var atTheFailureMs: Long = C.TIME_UNSET
            private set

        var atTheResumptionMs: Long = C.TIME_UNSET
            private set

        /** The player being listened to, since a state change is delivered without one. */
        lateinit var player: Player

        // The state comes from the callback rather than from `player.playbackState`, which is what an
        // `onEvents` reading would have used and which would be wrong here: the rung re-prepares the
        // player from inside the same dispatch, so by the time a listener asks, the player it is
        // being told went idle is already buffering. The *position* is read live, and is the one the
        // re-prepare kept.
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY ->
                    if (atTheFailureMs != C.TIME_UNSET && atTheResumptionMs == C.TIME_UNSET) {
                        atTheResumptionMs = player.currentPosition
                    }

                Player.STATE_IDLE ->
                    if (atTheFailureMs == C.TIME_UNSET) atTheFailureMs = player.currentPosition

                else -> Unit
            }
        }
    }

    /**
     * Every failure a consumer's listener was told about.
     *
     * `onPlayerError` alone, because that is the callback a consumer acts on: that its sibling
     * `onPlayerErrorChanged` is withheld with it is `NextSourcePlaybackTest`'s assertion, on the same
     * mechanism, and a second copy here would be a second place to edit.
     */
    private class ErrorsSeenByAConsumer : Player.Listener {

        val errors = mutableListOf<PlaybackException>()

        override fun onPlayerError(error: PlaybackException) {
            errors += error
        }
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e03"
        const val SOURCE = SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI

        /** Twenty-four seconds of programme, in two-second segments. */
        const val SEGMENTS = 12

        /**
         * The segment that fails: the eleventh, which is the one that covers [WATCHED_TO_MS] and so
         * the first the seeking session asks for. There are segments after it, so there is programme
         * left to play once the decoder is back.
         */
        const val FAULTED_SEGMENT_NAME = "segment10" + SyntheticHlsStream.SEGMENT_SUFFIX

        /** // ref: RFC 9110 §15.6.3, Bad Gateway. See [RefusingLayer] for why this status. */
        const val BAD_GATEWAY = 502

        /**
         * How far into the programme the viewer had got when the decoder failed: twenty seconds,
         * which is the start of the faulted segment. See [playFromTheWatchedPosition].
         */
        const val WATCHED_TO_MS = 20_000L

        const val NEXT_SOURCE_QUESTION = "opensNextSource"
        const val RECREATE_QUESTION = "recreatesDecoder"
    }
}
