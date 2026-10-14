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

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.FailureCategory
import com.superplayer.core.MediaRequest
import com.superplayer.core.StaleLivePlaylistException
import com.superplayer.core.SuperPlayer
import com.superplayer.core.SuperPlayerError
import com.superplayer.core.TelemetryEvent
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.SyntheticDashStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Rung 6 through a player: the typed, actionable error a session ends on when every rung below it
 * has been exhausted, and the classification telemetry reports with it (ADR-0011 rules 3 and 10,
 * issues #183 and #86).
 *
 * Two halves of one fact, asserted together because they are one fact: what the consumer is handed
 * and what the data team receives must name the same failure in the same word. The consumer's half
 * arrives where errors already arrive — `Player.Listener.onPlayerError`, as the `cause` of the
 * `PlaybackException` the `Player` API carries — and the data team's as
 * `PlaybackFailure.classification`, which is the same [SuperPlayerError.causeClass] string.
 *
 * The control runs through every test that matters: a player built with **no** resilience has nobody
 * to ask, so its error is Media3's unchanged and its `PlaybackFailure` is exactly what it was before
 * Phase 5 (ADR-0011 rule 14). It is counted here rather than assumed.
 *
 * Nothing reaches past the facade: every player is built the way a consumer builds one.
 */
@RunWith(AndroidJUnit4::class)
class TypedErrorPlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aSessionNothingRescuedEndsOnATypedError() {
        val player = harness.buildPlayer(
            content = TestContent.dash(SEGMENTS),
            faults = failEverySegment(),
            resilience = Resilience.standard(),
        )
        player.setMediaRequest(request(SyntheticDashStream.MANIFEST_URI))

        harness.playToFailure(player)

        val error = player.playerError
        assertThat(error).isNotNull()
        val typed = error?.cause
        assertThat(typed).isInstanceOf(SuperPlayerError::class.java)
        typed as SuperPlayerError
        // `PRD.md` §3.2's three-part shape, which the DRM phase reuses: a cause class, a key an app
        // looks a viewer-facing string up by, and whether asking again could change the outcome.
        assertThat(typed.causeClass).isEqualTo(FailureClass.Transient.Network.stableName)
        assertThat(typed.userMessageKey).isEqualTo(FailureClass.Transient.Network.userMessageKey)
        assertThat(typed.isRetryable).isTrue()
        // Rule 10's other two: the rungs tried, and the position the viewer had reached.
        assertWithMessage("the rungs the ladder actually climbed")
            .that(typed.rungsTried).contains(FallbackRung.RETRY_SAME_URL.name)
        assertThat(typed.positionMs).isAtLeast(0L)
        // Nothing of Media3's is lost: the engine's own exception is the typed error's own cause, so
        // a bug report still has the error code and the stack that produced it.
        assertThat(typed.cause).isNotNull()
    }

    @Test
    fun aConsumerListenerAndThePropertyAreHandedOneObject() {
        // Rule 10 fixes the delivery: no new listener and no new callback. What a consumer's
        // `onPlayerError` receives and what `player.playerError` returns are one object, or a
        // consumer reading the property would be acting on a different account of one failure.
        val delivered = mutableListOf<PlaybackException>()
        val player = harness.buildPlayer(
            content = TestContent.dash(SEGMENTS),
            faults = failEverySegment(),
            resilience = Resilience.standard(),
        )
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    delivered += error
                }
            },
        )
        player.setMediaRequest(request(SyntheticDashStream.MANIFEST_URI))

        harness.playToFailure(player)

        assertThat(delivered).hasSize(1)
        assertThat(delivered.single().cause).isInstanceOf(SuperPlayerError::class.java)
        assertThat(delivered.single()).isSameInstanceAs(player.playerError)
        // The engine's own error code survives the substitution, because it is the engine's to assign
        // and a consumer switching on it must go on working. A 502 on a segment is Media3's
        // `InvalidResponseCodeException`, which is this code and no other.
        assertThat(delivered.single().errorCode).isEqualTo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
    }

    @Test
    fun aPlayerWithoutResilienceEndsOnMedia3sOwnErrorUnchanged() {
        // ADR-0011 rule 14: nobody to ask, so nothing is substituted and the cause is whatever the
        // engine put there.
        val player = harness.buildPlayer(content = TestContent.dash(SEGMENTS), faults = failEverySegment())
        player.setMediaRequest(request(SyntheticDashStream.MANIFEST_URI))

        harness.playToFailure(player)

        assertThat(player.playerError).isNotNull()
        assertThat(player.playerError?.cause).isNotInstanceOf(SuperPlayerError::class.java)
        assertThat(player.classify(player.playerError!!)).isNull()
    }

    @Test
    fun aFrozenLivePlaylistIsReportedAsWhatItIsRatherThanAsAnUnspecifiedIoError() {
        // Issue #86, end to end. The `PlaybackException` around a `StaleLivePlaylistException` still
        // carries `ERROR_CODE_IO_UNSPECIFIED`, because error codes are the engine's to assign — so
        // before this, a data team saw the same row for a CDN holding a stale playlist as for any
        // other unexplained I/O failure.
        val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
        val player = harness.buildPlayer(
            content = TestContent.liveHls(),
            faults = cacheThatIgnoresNoCache(),
            telemetry = QoeCollector { events += it },
            resilience = Resilience.standard(),
        )
        player.setMediaRequest(request(TestContent.liveHls().sourceUri))

        harness.playToFailure(player)
        // Read before the session is ended below, because a released player has no error to report.
        // Off the facade rather than off the engine — the substitution keeps the engine's code, which
        // is what makes this readable through the `Player` API at all.
        val engineCode = player.playerError?.errorCodeName
        val typed = player.playerError?.cause as? SuperPlayerError
        assertThat(typed).isNotNull()
        typed!!
        // The evidence core detected survives the mapping, which is rule 10 in as many words: the
        // party to go and look at is the intermediary, not the origin.
        assertThat(typed.causeClass).isEqualTo(FailureClass.Transient.CdnEdge.stableName)
        assertThat(typed.likelyCause).isEqualTo(StaleLivePlaylistException.LikelyCause.INTERMEDIARY_CACHE)
        assertThat(typed.causeChain()).contains(StaleLivePlaylistException::class.java)

        val failure = failureOf(player, events)
        assertThat(failure.classification).isEqualTo(FailureClass.Transient.CdnEdge.stableName)
        // The coarse bucket stays coarse and stays right (ADR-0011 rule 3): a dashboard still groups
        // by six values, and `code` still names the engine's own code for a log.
        assertThat(failure.category).isEqualTo(FailureCategory.NETWORK)
        assertThat(failure.code).isEqualTo(engineCode)
    }

    @Test
    fun onAPlayerWithoutResilienceTelemetryReportsExactlyWhatItReportedBefore() {
        // Rule 14's telemetry half, counted rather than assumed: no classification at all, and the
        // category derived from Media3's error-code band as it always was.
        val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
        val player = harness.buildPlayer(
            content = TestContent.liveHls(),
            faults = cacheThatIgnoresNoCache(),
            telemetry = QoeCollector { events += it },
        )
        player.setMediaRequest(request(TestContent.liveHls().sourceUri))

        harness.playToFailure(player)
        val engineCode = player.playerError?.errorCodeName
        val failure = failureOf(player, events)

        assertThat(failure.classification).isNull()
        assertThat(failure.category).isEqualTo(FailureCategory.NETWORK)
        assertThat(failure.code).isEqualTo(engineCode)
    }

    /** The classes an error and its causes are made of, for the one assertion that walks the chain. */
    private fun Throwable.causeChain(): List<Class<*>> {
        val chain = mutableListOf<Class<*>>()
        var current: Throwable? = this
        while (current != null && chain.size < MAX_CAUSE_DEPTH) {
            chain += current.javaClass
            current = current.cause
        }
        return chain
    }

    /**
     * The failure the session was reported as, once delivery has caught up.
     *
     * The player is released first because delivery is asynchronous and bounded by design (ADR-0008
     * rules 3 and 4): `SessionEnded` is the event that says every earlier one has been handed over,
     * and `QoeCollector.awaitDelivered` is `internal` to `superplayer-telemetry` precisely so that a
     * consumer — which this build is — cannot wait on the thread the design exists to protect.
     */
    private fun failureOf(player: SuperPlayer, events: List<TelemetryEvent>): com.superplayer.core.PlaybackFailure {
        player.release()
        val deadline = System.currentTimeMillis() + DELIVERY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val delivered = synchronized(events) { events.toList() }
            if (delivered.any { it is TelemetryEvent.SessionEnded }) {
                val failure = delivered.firstNotNullOfOrNull {
                    when (it) {
                        is TelemetryEvent.StartupFailed -> it.failure
                        is TelemetryEvent.MidStreamFailed -> it.failure
                        else -> null
                    }
                }
                return checkNotNull(failure) { "no failure event in $delivered" }
            }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("the session's events did not arrive within $DELIVERY_TIMEOUT_MS ms")
    }

    private fun request(uri: String): MediaRequest = MediaRequest.Builder(CONTENT).addSource(uri).build()

    /**
     * A 502 on every media segment that never relents, so every rung below 6 is spent: rung 1 on its
     * budget, rung 2 with one location to move to, rung 3 with one representation to exclude.
     *
     * // ref: RFC 9110 §15.6.3, Bad Gateway — chosen for `NextSourcePlaybackTest`'s reason, that
     * Media3's own fallback table does not list it, so what the ladder does is the ladder's.
     */
    private fun failEverySegment(): FaultScript =
        FaultScript.Builder().failWithHttpStatus(BAD_GATEWAY, ResourceKind.MEDIA_SEGMENT).build()

    /**
     * The CDN cache RFC 9111 permits and issue #66 detects: one that holds a live playlist for ten
     * minutes and disregards the `no-cache` core's revalidation asks it for.
     */
    private fun cacheThatIgnoresNoCache(): FaultScript = FaultScript.Builder()
        .serveThroughCache(TEN_MINUTES_S, honoursNoCache = false, kind = ResourceKind.MANIFEST)
        .build()

    private companion object {
        const val CONTENT = "series/expanse/s01e02"

        /** // ref: RFC 9110 §15.6.3, Bad Gateway. */
        const val BAD_GATEWAY = 502

        const val SEGMENTS = 8

        /** Far past any playlist age the revalidation tolerates; `LivePlaylistRevalidationTest`'s. */
        const val TEN_MINUTES_S = 600L

        const val MAX_CAUSE_DEPTH = 32

        const val DELIVERY_TIMEOUT_MS = 10_000L
        const val POLL_MS = 10L
    }
}
