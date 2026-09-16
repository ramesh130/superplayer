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

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.core.SuperPlayerError
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 5's exit criterion for the injected half of it: **every kind of fault the harness can
 * inject, under both protocols, either recovers or ends in a named class** (`PRD.md` Part 4, "every
 * injected fault either recovers or produces a typed, actionable error. Zero unclassified errors").
 *
 * One session per cell of a ten-by-two table, played through a player a consumer builds — a profile
 * and `Resilience.standard()`, nothing else — and the outcome of each recorded in [SWEPT]. The table
 * is the deliverable rather than the assertions around it: what a reader wants from a phase exit is
 * *which* faults recover and which are only named, and a pass/fail line cannot say that.
 *
 * Three things are asserted about it.
 *
 * - **Every cell is [Outcome.RECOVERED] or [Outcome.ENDED_TYPED].** An [Outcome.ENDED_UNCLASSIFIED]
 *   is the criterion's "zero unclassified errors" broken; an [Outcome.STALLED] is the half that is
 *   easy to forget — a session that neither plays nor fails tells a viewer nothing at all, and is
 *   not rescued by a taxonomy however total it is.
 * - **The kinds swept are exactly the kinds there are**, read off [FaultScript.Builder] by
 *   reflection ([everyFaultKindTheHarnessCanInjectIsSwept]). A fault kind added later and not swept
 *   fails here rather than being silently missing, which is the enumeration this file is asked for.
 * - **A named failure is one an app and a pipeline can act on** — a stable class name of the
 *   taxonomy's shape and a message key — checked as each session ends, so "typed" cannot degrade
 *   into a non-null string nothing groups by.
 *
 * One instance per kind, not every instance of it: what varies inside a kind — which resource it is
 * addressed at, whether it relents, whether a credential can be refreshed — is each rung's own test
 * (`RetryPlaybackTest`, `FallbackPlaybackTest`, `TokenRefreshPlaybackTest`, and the rest, collected
 * in [FallbackRungCoverageTest]). What this file adds is breadth: the kinds no rung's test happened
 * to use, under the protocol nobody ran them against.
 */
@RunWith(AndroidJUnit4::class)
class FaultSweepTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /** Which stream the fault was injected into. Both, for every kind, which is the sweep. */
    private enum class Protocol { HLS, DASH }

    /** How a swept session ended. Only the first two satisfy `PRD.md` Part 4. */
    private enum class Outcome {
        /** No error, and playback got past the faulted segment: the ladder, or the fault, relented. */
        RECOVERED,

        /** A `PlaybackException` carrying a [SuperPlayerError]: rung 6, with a class and a message key. */
        ENDED_TYPED,

        /** A `PlaybackException` nothing classified. The criterion's "zero unclassified errors", broken. */
        ENDED_UNCLASSIFIED,

        /** Neither: no error, and no progress past the fault inside the budget. */
        STALLED,
    }

    /**
     * One cell: how the session ended and, where it ended typed, which class it was named with.
     *
     * The class is in the record rather than checked and dropped, because "ended typed" on its own
     * is the claim a reader has least reason to believe: a table saying *which* class each fault
     * acquired is what shows the taxonomy is discriminating rather than defaulting.
     */
    private data class Ending(val outcome: Outcome, val causeClass: String? = null)

    @Test
    fun everyInjectedFaultUnderEitherProtocolRecoversOrEndsInANamedClass() {
        val observed = sweep()

        // The whole table at once, for `HostileManifestCorpusTest`'s reason: a run reports every
        // cell that moved rather than the first.
        assertThat(observed).containsExactlyEntriesIn(SWEPT)
    }

    @Test
    fun noSweptFaultEndsUnclassifiedAndNoneLeavesTheSessionWithNothingToSay() {
        // The criterion itself, read off the same table rather than off a second run — so that a row
        // moved into [Outcome.STALLED] or [Outcome.ENDED_UNCLASSIFIED] by a later change fails here
        // as a criterion broken, and in the test above as a record out of date.
        SWEPT.forEach { (kind, byProtocol) ->
            byProtocol.forEach { (protocol, ending) ->
                assertWithMessage("$kind under $protocol")
                    .that(ending.outcome)
                    .isAnyOf(Outcome.RECOVERED, Outcome.ENDED_TYPED)
            }
        }
    }

    @Test
    fun everyFaultKindTheHarnessCanInjectIsSwept() {
        // The enumeration, so a kind added later is visibly missing rather than silently unswept.
        // Read off the builder by reflection rather than from a list kept here, because a list kept
        // here is the thing that would be forgotten: every fault kind is a builder method that takes
        // an address and returns the builder, and there is no other way to declare one.
        val kinds = FaultScript.Builder::class.java.methods
            .filter { it.returnType == FaultScript.Builder::class.java }
            .map { it.name }
            .filterNot { it.endsWith(DEFAULT_ARGUMENT_SUFFIX) }
            .distinct()

        assertThat(kinds).isNotEmpty()
        assertThat(SWEPT.keys).containsExactlyElementsIn(kinds)
    }

    /** Plays one session per fault kind per protocol, and says how each ended. */
    private fun sweep(): Map<String, Map<Protocol, Ending>> =
        faultKinds().mapValues { (_, script) -> Protocol.entries.associateWith { observe(it, script) } }

    /**
     * How a session ends with [script] injected into [protocol]'s stream.
     *
     * Everything about the player is a consumer's default — the profile's own retry budgets, the
     * standard ladder, one source — because what is being swept is what an app gets rather than what
     * a test can arrange. A second source or a `HeaderProvider` would rescue several of these rows,
     * and each is the subject of its own rung's test; handing them to the sweep would hide which
     * faults the library survives on its own.
     */
    private fun observe(protocol: Protocol, script: FaultScript): Ending {
        val content = when (protocol) {
            Protocol.HLS -> TestContent.hls(segmentCount = SEGMENTS)
            Protocol.DASH -> TestContent.dash(segmentCount = SEGMENTS)
        }
        val player = harness.buildPlayer(content = content, faults = script, resilience = Resilience.standard())
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        player.prepare()
        player.play()

        // Bounded, and in load-sized steps for `HostileManifestCorpusTest`'s reason: a fault that
        // leaves the engine buffering for ever is an outcome to record rather than a hang to wait
        // out, and a single long advance gives the engine one pass and never reaches the fault.
        var advanced = 0L
        while (advanced < BUDGET_MS && player.playerError == null && player.playbackState != Player.STATE_ENDED) {
            harness.advanceTimeInStepsMs(player, STEP_MS)
            advanced += STEP_MS
        }

        val error = player.playerError
        val typed = error?.cause as? SuperPlayerError
        if (typed != null) {
            // "Named" is a class a pipeline groups by and a message an app can write, not merely a
            // non-null string (ADR-0011 rule 1): asserted here rather than off the table below,
            // because the table carries the class and not the rest of what came with it.
            assertThat(typed.causeClass).matches("[A-Za-z]+\\.[A-Za-z]+")
            assertThat(typed.userMessageKey).isNotEmpty()
        }
        return when {
            typed != null -> Ending(Outcome.ENDED_TYPED, typed.causeClass)
            error != null -> Ending(Outcome.ENDED_UNCLASSIFIED)
            playedPastTheFault(player) -> Ending(Outcome.RECOVERED)
            else -> Ending(Outcome.STALLED)
        }
    }

    /**
     * Whether the session fetched a segment beyond the faulted one, which is what recovery means
     * here.
     *
     * Counted at the transport rather than read off the state, because a slow link is a recovery and
     * a stall is not, and the two look alike from `playbackState`: a session under a throughput cap
     * spends most of its budget buffering while bytes keep arriving, and one that has given up
     * buffers identically while nothing does.
     */
    private fun playedPastTheFault(player: SuperPlayer): Boolean =
        harness.networkRequests(player)
            .filter { it.kind == ResourceKind.MEDIA_SEGMENT }
            .map { it.uri }
            .distinct()
            .size > FAULTED_SEGMENT + 1

    /**
     * One fault of every kind, keyed by the builder method that declares it.
     *
     * Every one is addressed at [FAULTED_SEGMENT] and never relents, which is the shape that makes a
     * sweep a sweep: a fault that relents is answered by rung 1 and says nothing about the rungs
     * above it, and a fault at the manifest ends the session before there is any playback to rescue.
     * The exception is the last, whose subject is not a failed request at all.
     */
    private fun faultKinds(): Map<String, FaultScript> = mapOf(
        // A delay, not a failure: four segment durations of it, which is long enough that the engine
        // rebuffers and short enough that the session finishes inside the budget.
        "addLatencyMs" to script { addLatencyMs(LATENCY_MS, SEGMENT, FAULTED_SEGMENT) },
        // Half the stream's declared bitrate, so the link is genuinely too slow for the rendition and
        // still delivers: the fault that must not be recorded as a failure of anything.
        "capThroughputBps" to script { capThroughputBps(TestContent.DEFAULT_BITRATE_BPS / 2L, SEGMENT, FAULTED_SEGMENT) },
        "failWithHttpStatus" to script { failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, SEGMENT, FAULTED_SEGMENT) },
        // Nothing but the first bytes of the segment, with the full length promised: the request
        // succeeded and the promise was broken, which every layer above treats differently.
        "truncateAfterBytes" to script { truncateAfterBytes(TRUNCATED_AT_BYTES, SEGMENT, FAULTED_SEGMENT) },
        "failDnsResolution" to script { failDnsResolution(SEGMENT, FAULTED_SEGMENT) },
        "failTlsHandshake" to script { failTlsHandshake(SEGMENT, FAULTED_SEGMENT) },
        "resetConnection" to script { resetConnection(SEGMENT, FAULTED_SEGMENT) },
        "timeOutConnection" to script { timeOutConnection(SEGMENT, FAULTED_SEGMENT) },
        // Not refreshable, which is deliberate: this player is built with no `HeaderProvider`, so
        // the row says what a session does when the credential cannot be replaced. The other half —
        // a refreshable token and a provider to replace it — is `TokenRefreshPlaybackTest`'s.
        "expireTokenAtSegment" to FaultScript.Builder().expireTokenAtSegment(FAULTED_SEGMENT).build(),
        // The one kind that fails no request: a shared cache holding this on-demand manifest for ten
        // minutes and disregarding the revalidation above it, which for content that does not change
        // is a cache doing its job. Addressed at the manifest because that is the resource a cache
        // rule is about; at a segment it would be a hit rather than a fault.
        "serveThroughCache" to script {
            serveThroughCache(CACHE_MAX_AGE_SECONDS, honoursNoCache = false, kind = ResourceKind.MANIFEST)
        },
    )

    /** One script, so that the table above reads as a list of faults rather than of builders. */
    private fun script(fault: FaultScript.Builder.() -> FaultScript.Builder): FaultScript =
        FaultScript.Builder().fault().build()

    private companion object {
        const val CONTENT = "series/expanse/s01e05"

        /** The kind nine of the ten faults are addressed at, named for the table's sake. */
        val SEGMENT = ResourceKind.MEDIA_SEGMENT

        /** Enough segments that there are several past [FAULTED_SEGMENT] to get to. */
        const val SEGMENTS = 6

        /** Late enough that segments play before the fault, and well inside [SEGMENTS]. */
        const val FAULTED_SEGMENT = 2

        /** Four segment durations: a rebuffer rather than a hiccup, and well inside [BUDGET_MS]. */
        const val LATENCY_MS = 20_000L

        /** A header's worth of a segment and no more: enough to open, not enough to decode. */
        const val TRUNCATED_AT_BYTES = 64L

        /** Long past the whole stream, so a cache that held the manifest holds it for the session. */
        const val CACHE_MAX_AGE_SECONDS = 600L

        /**
         * How much playback time one cell is watched for: well past the content's own length, so a
         * session that is going to finish has finished and one that is going to fail has failed.
         */
        const val BUDGET_MS = 60_000L

        /** One turn of the loop: a few loads, as `HostileManifestCorpusTest` uses it. */
        const val STEP_MS = 500L

        /** What Kotlin names the synthetic overload carrying a method's default arguments. */
        const val DEFAULT_ARGUMENT_SUFFIX = "\$default"

        /** The two classes this sweep produces, spelled as `FailureClass` spells them. */
        val NETWORK: String = FailureClass.Transient.Network.stableName
        val CDN_EDGE: String = FailureClass.Transient.CdnEdge.stableName

        /**
         * How each swept fault ended, as of the change that added it. Ten kinds, two protocols, and
         * every cell recovered or named — which is `PRD.md` Part 4's criterion for this half of
         * Phase 5.
         *
         * A record as well as a criterion, for `HostileManifestCorpusTest`'s reason: a cell that
         * moves from a class to another class, or from a recovery to a named failure, is a behaviour
         * change someone has to explain in a commit message even though both values pass.
         */
        val SWEPT: Map<String, Map<Protocol, Ending>> = mapOf(
            // Slow is not broken: both protocols rebuffer through the delay and play on, which is
            // the row that would catch a ladder that treated a wait as a failure.
            "addLatencyMs" to both(recovered()),
            "capThroughputBps" to both(recovered()),
            // A 500 that never relents: rung 1 spends the profile's segment budget on it, rungs 2 to
            // 5 have nowhere to go on a single-source, single-host stream, and rung 6 names it. The
            // class is the transport's rather than the edge's, because a 5xx is the server having
            // failed rather than having refused this request (`ErrorClassifier`).
            "failWithHttpStatus" to both(typed(NETWORK)),
            // The one kind the two protocols answer differently, and it is Media3's answer rather
            // than the ladder's: an HLS segment whose body ends early is a failed load, while the
            // fragmented-MP4 extractor reads the truncated DASH segment's opening boxes, finds no
            // samples in it, and moves on to the next one. Recorded rather than evened out — the
            // criterion asks that each end recovered or named, and each does.
            "truncateAfterBytes" to mapOf(Protocol.HLS to typed(NETWORK), Protocol.DASH to recovered()),
            "failDnsResolution" to both(typed(NETWORK)),
            "failTlsHandshake" to both(typed(NETWORK)),
            "resetConnection" to both(typed(NETWORK)),
            "timeOutConnection" to both(typed(NETWORK)),
            // A credential that cannot be replaced, so every segment from the second on is refused:
            // named as the edge refusing this session rather than as the network failing, which is
            // the distinction `Transient.CdnEdge` exists for and the one an app acts on differently.
            "expireTokenAtSegment" to both(typed(CDN_EDGE)),
            // A cache holding an on-demand manifest that was never going to change: nothing is lost
            // and the session plays to the end. The row that matters is the live one, where the same
            // cache freezes a playlist and ends the session named — `TypedErrorPlaybackTest`'s
            // `aFrozenLivePlaylistIsReportedAsWhatItIsRatherThanAsAnUnspecifiedIoError`.
            "serveThroughCache" to both(recovered()),
        )

        /** The same ending under both protocols, which is how nine of the ten rows read. */
        private fun both(ending: Ending): Map<Protocol, Ending> = Protocol.entries.associateWith { ending }

        private fun recovered(): Ending = Ending(Outcome.RECOVERED)

        private fun typed(causeClass: String): Ending = Ending(Outcome.ENDED_TYPED, causeClass)
    }
}
