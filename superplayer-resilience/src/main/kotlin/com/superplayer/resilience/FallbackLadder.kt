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
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.superplayer.core.PlayerStateRungs
import com.superplayer.core.RetryBudget
import com.superplayer.core.SuperPlayerError
import java.util.EnumSet
import kotlin.random.Random

/**
 * One failed load, as a rung sees it.
 *
 * A value rather than three parameters because every rung needs the same three facts and a rung
 * added later will need them too: what the failure *is*, what the budget for this kind of load
 * allows and how much of it has gone, and Media3's own description of the load — the last for rungs
 * that answer a question about locations or tracks and have to name one back.
 */
internal class FailedLoad(
    /** What [ErrorClassifier] made of the failure. The only reading of it any rung does. */
    val failureClass: FailureClass,
    /**
     * The budget for this kind of load, as the decision in force at *this* consultation states it
     * (ADR-0011 rule 11). Which of [com.superplayer.core.RetryPolicy]'s three it came from is
     * resolved before a rung sees it, because no rung has a use for the question separately.
     */
    val budget: RetryBudget,
    /**
     * Which retry this one would be, numbered from 1.
     *
     * Media3 counts errors rather than retries and starts at 1 for the first error, which is the
     * error the first retry answers — so the two numbers coincide and this is Media3's `errorCount`
     * unchanged. Named for what it is used for rather than for where it came from.
     */
    val retry: Int,
    /** Media3's own account of the load, for a rung that must name a location or a track back. */
    val info: LoadErrorHandlingPolicy.LoadErrorInfo,
    /**
     * What Media3 says is left to fall back to — how many locations and how many tracks this load
     * had, and how many of each it has already excluded — or null when Media3 asked the question
     * that carries none.
     *
     * Null is not "nothing is available": it is "not asked". Media3 hands these only to
     * `getFallbackSelectionFor`, and `getRetryDelayMsFor` is asked with the load alone, so a climb
     * begun from the second question can answer for rung 1 and for nothing above it. That costs
     * nothing, because Media3 asks the *first* question first wherever a fallback is possible at all
     * (see this file's KDoc), so a rung above 1 is never skipped by the absence of these — only a
     * rung that had nowhere to go is spared being asked.
     */
    val fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions? = null,
)

/** What a rung decided to do about a [FailedLoad]. */
internal sealed interface RungOutcome {

    /** This rung took it on: ask for the same load again in [delayMs]. */
    data class RetryAfter(val delayMs: Long) : RungOutcome

    /** This rung took it on by narrowing what Media3 may load next. */
    data class FallBackTo(val selection: LoadErrorHandlingPolicy.FallbackSelection) : RungOutcome

    /** This rung has nothing for it; the one above is offered it next. */
    data object Escalate : RungOutcome
}

/**
 * Whether [rung] is one the ladder may attempt for this class — [FailureClass.rungCeiling] read, in
 * the one place any rung reads it.
 *
 * A ceiling of rung 6 is the one that is not a permission to climb: rung 6 is not a remedy the ladder
 * performs but the point at which it has stopped, so a class whose ceiling is the typed error is
 * offered no rung at all — "goes to rung 6 at once" (ADR-0011 rule 7, and [FailureClass.rungCeiling],
 * which says the same in the other direction). A comparison against the ceiling alone reads that
 * case exactly backwards, which is why this is a function and not an operator at two call sites.
 */
internal fun FailureClass.mayClimb(rung: FallbackRung): Boolean =
    rungCeiling != FallbackRung.TYPED_ERROR && rung <= rungCeiling

/** What one rung of ADR-0011 rule 7's ladder does about a failure. */
internal fun interface LadderRung {

    /** What this rung does about [load], or [RungOutcome.Escalate] to hand it to the rung above. */
    fun attempt(load: FailedLoad): RungOutcome
}

/**
 * ADR-0011 rule 7's ladder, as far as it can be climbed from inside a failed load.
 *
 * A failure is offered to each rung from the bottom, and stops at the first that takes it; a rung is
 * reached only when the one below it has declined. The order is [FallbackRung]'s declaration order
 * and is not a profile's to vary — no content wants its variant excluded before its host is tried —
 * and the ceiling is the failure's own ([FailureClass.rungCeiling], rule 1), so a class that says
 * rung 1 is pointless does not spend a budget there on its way up.
 *
 * **A ceiling caps the climb; it does not route it.** Which of the rungs below its ceiling a class is
 * worth offering is this object's decision, which rule 7 puts here in as many words — it is how a
 * `Device.DecoderTransient` reaches rung 5 without trying a host, and each rung's KDoc argues its own
 * refusals. `FallbackLadderTest` is that routing written down.
 *
 * ## Which rungs are here, and which are not
 *
 * Rungs 1 to 3 — retry the same URL, the next host, the failing variant excluded — are the answers
 * Media3 asks a `LoadErrorHandlingPolicy` for, which is why they are reachable from here at all.
 * Rungs 4 to 6 are not: the next source is a re-adoption and a recreated decoder is a re-prepare,
 * both operations on the player's own state that cannot be performed from a loading thread with a
 * load in hand, and the typed error is what the consumer is handed once nothing below it worked
 * (rule 7 assigns them to rules 5 and 10). [RungOutcome.Escalate] out of the top of this object is
 * therefore where they begin: a failure that gets past it stops being a load and becomes a failure of
 * the *session*, which Media3 surfaces to the player and core asks [PlayerStateLadder] about there —
 * rungs 4 and 5, [NextSource] and [RecreateDecoder], in that order. Rung 6, [TypedError], is what is
 * left when nothing answered: not a remedy but what the viewer and the data team are told instead.
 *
 * ## The trap
 *
 * **Media3 asks the questions in the opposite order to the ladder.** A chunk source asks
 * `getFallbackSelectionFor` — rungs 2 and 3 — and only asks `getRetryDelayMsFor` if that answered
 * null. So a rung above 1 that answered the first question while rung 1 still had budget would have
 * skipped rung 1 entirely, silently, and rule 7 would be broken by the order Media3 happens to ask
 * in rather than by anything written here. [climb] is the single place that order is re-imposed:
 * both of `RetryingLoadErrors`' answers come from one climb of this ladder, so a rung above is only
 * ever offered a failure the rungs below have already declined.
 */
internal class FallbackLadder(
    private val rungs: Map<FallbackRung, LadderRung>,
    private val record: ClimbRecord = ClimbRecord(),
) {

    /** The outcome of offering [load] to each rung in turn, up to its class's ceiling. */
    fun climb(load: FailedLoad): RungOutcome {
        for (rung in FallbackRung.entries) {
            if (!load.failureClass.mayClimb(rung)) break
            val outcome = rungs[rung]?.attempt(load) ?: RungOutcome.Escalate
            if (outcome != RungOutcome.Escalate) {
                // Recorded where the rung took the failure on rather than where it was offered one:
                // what rung 6 reports is what was *tried* on the viewer's behalf, and a rung that
                // declined tried nothing (`SuperPlayerError.rungsTried`).
                record.attempted(rung)
                return outcome
            }
        }
        return RungOutcome.Escalate
    }

    companion object {

        /**
         * The ladder as it stands: rungs 1 to 3, every rung a load error can reach from inside a
         * load. Rungs 4 to 6 are not registered here at all — see this class's KDoc.
         *
         * Named here rather than assembled at each call site so that a later rung is one entry in
         * this map and no edit to `Resilience` or to `RetryingLoadErrors` — which is the whole
         * point of the rungs being separate objects.
         *
         * [random] is where every backoff draws its jitter; one source per player, so two players
         * retrying the same edge do not draw the same sequence.
         */
        fun standard(random: Random, record: ClimbRecord = ClimbRecord()): FallbackLadder = FallbackLadder(
            mapOf(
                FallbackRung.RETRY_SAME_URL to RetrySameUrl(random),
                FallbackRung.NEXT_HOST to NextHost,
                FallbackRung.EXCLUDE_VARIANT to ExcludeVariant,
            ),
            record,
        )
    }
}

/**
 * Rung 1: ask for the same bytes again, after a jittered backoff, while the budget lasts.
 *
 * Two refusals, in this order, and both are ADR-0011's rather than this object's:
 *
 * 1. **The class.** [FailureClass.retryable] is the whole of what a class says about this rung
 *    (rule 1): bytes that arrived and are not what they were described as parse the same way the
 *    second time, and a budget spent on them is a budget the rungs above do not get.
 * 2. **The budget.** [RetryBudget.maxRetries] from the decision in force at this consultation, per
 *    [RetryBudgetKind] — so a manifest that has spent its asks has spent *its* asks, and the
 *    segments of the same session still have all of theirs (rule 11, `PRD.md` §3.3).
 *
 * Nothing here re-seeks or re-prepares, which is rule 9's half of this rung: a retry Media3
 * performs inside a load leaves the position untouched by construction, and the rule is that
 * SuperPlayer adds nothing that disturbs it.
 */
internal class RetrySameUrl(private val random: Random) : LadderRung {

    override fun attempt(load: FailedLoad): RungOutcome {
        if (!load.failureClass.retryable) return RungOutcome.Escalate
        if (load.retry > load.budget.maxRetries) return RungOutcome.Escalate
        return RungOutcome.RetryAfter(Backoff.delayMsFor(load.budget, load.retry, random))
    }
}

/**
 * Rung 2: ask a different location for the same content — the next CDN host, or the next DASH
 * `BaseURL`.
 *
 * spec: ISO/IEC 23009-1 §5.6.4 — a DASH manifest that carries more than one `BaseURL` at one level
 * declares the same content at alternative locations, and a client may use any of them; ETSI TS
 * 103 285 §10.8.2.1 (DVB-DASH) adds the `dvb:priority` and `dvb:weight` attributes that order them.
 * Which location is chosen, and how long an excluded one stays out, is Media3's own
 * `BaseUrlExclusionList`; what is decided here is *whether* to move, which is the part ADR-0011 rule
 * 7 puts on the ladder and which Media3 would otherwise decide from its own error-code table.
 *
 * **The content is the same content, so nothing about its identity changes.** A key in
 * `superplayer-cache` is the content id and the URI *path* and never the host, so a session that
 * failed over reads and writes the same cache entries it would have; that is `ContentKeys`' rule
 * rather than this rung's, and it is why this rung has nothing to say about the cache.
 *
 * Rule 9 needs no code here either: a location fallback is performed by Media3 inside the load, and
 * the rule is that SuperPlayer adds no re-seek around it.
 *
 * ## Two refusals
 *
 * 1. **A class no host has anything to do with.** `Device` failures are failures of *this* device's
 *    decoding, and [FailureClass.Device.DecoderTransient]'s own documentation says the ladder
 *    reaches rung 5 for it without trying a host. Rung 2 is where that skip is performed, because a
 *    ceiling caps the climb and does not route it (`FailureClass.rungCeiling`).
 * 2. **Nowhere to go.** Media3 counts the locations and the exclusions, and a content with one
 *    location has no second one; HLS reports one always, since Media3 gives an HLS chunk source no
 *    location dimension at all. So an HLS session escalates through this rung to rung 3, which is
 *    the correct order rather than a gap: ADR-0011 rule 13 reserves the header-refresh slot for a
 *    host substitution should one be wanted, and nothing there is needed for a protocol whose
 *    renditions already carry their own URLs.
 */
internal object NextHost : LadderRung {

    override fun attempt(load: FailedLoad): RungOutcome {
        if (load.failureClass is FailureClass.Device) return RungOutcome.Escalate
        val options = load.fallbackOptions ?: return RungOutcome.Escalate
        if (!options.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION)) return RungOutcome.Escalate
        return RungOutcome.FallBackTo(
            LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION,
                LOCATION_EXCLUSION_MS,
            ),
        )
    }

    /**
     * How long a location that failed stays out: five minutes.
     *
     * An edge that refused or dropped a request is usually still refusing a minute later — a bad
     * node, a bad configuration, a region cut off — so a short exclusion buys a return to the host
     * that just failed and a second spend of the same budget. Five minutes is long enough that a
     * session of ordinary length does not go back, and short enough that a player left running past
     * an outage finds its nearer edge again. It is also Media3's own figure for the same decision
     * (`DefaultLoadErrorHandlingPolicy.DEFAULT_LOCATION_EXCLUSION_MS`), which is the tie-breaker:
     * this rung is about *which* failures move location, and moving Media3's duration as well would
     * be two changes argued as one.
     */
    const val LOCATION_EXCLUSION_MS: Long = 5 * 60 * 1_000L
}

/**
 * Rung 3: take the failing rendition out of the ladder and carry on at another bitrate.
 *
 * Media3's own track exclusion, handed back as a `FALLBACK_TYPE_TRACK` selection, which is the only
 * mechanism there is for this from inside a load — what is SuperPlayer's is *when* it is asked for.
 * Media3 asks for a fallback on a fixed table of statuses and on nothing else — 403, 404, 410, 416,
 * 500 and 503 (// ref: `DefaultLoadErrorHandlingPolicy.isEligibleForFallback`) — so a 502 or a
 * dropped connection ends a session it could have moved, and a 403 moves one whose token a refresh
 * would have fixed. This rung asks on the classification instead, which is ADR-0011 rule 1's point
 * of having a classifier at all and rule 7's "driven by the class rather than by Media3's defaults".
 *
 * ## Rule 8, and why it needs no code here
 *
 * "Excluding a variant hands Media3 a narrower ladder, and the rung it continues at is still chosen
 * under the selection ceiling and pace `PlaybackPolicy` decided." That holds by construction, in
 * both engines a decision can be in force on: with `superplayer-abr` the ceiling is a *refusal* in
 * `NetworkAwareTrackSelection.canSelectFormat`, re-checked on every evaluation; without it the
 * ceiling is `TrackSelectionParameters.maxVideoBitrate`, a constraint `DefaultTrackSelector` applies
 * before a selection is built. An exclusion only ever removes a rung from what is available, and
 * neither mechanism consults it, so no exclusion can widen what the policy allows. When the
 * exclusion leaves nothing under the ceiling the selection has nothing to continue on and the
 * failure escalates out of this rung — which is rule 8's "it moves to rung 4" arriving by the route
 * the rule names, rather than by this rung second-guessing a ceiling it cannot see the rungs of.
 *
 * ## Two refusals
 *
 * 1. **[FailureClass.Device.DecoderTransient].** A decoder that was working and stopped, or one that
 *    could not be had for a moment, is not a property of the rendition, and its own documentation
 *    puts its remedy at rung 5. Its sibling [FailureClass.Device.DecoderInit] is *not* refused, and
 *    the asymmetry is that class's own: "another variant or another source may be within reach" is
 *    exactly what this rung offers a rung the device could not build a decoder for.
 * 2. **[FailureClass.Content.ManifestInvalid].** A description that cannot be acted on is not one
 *    rendition's doing, and the same description is still there with a rendition taken out of it.
 *    Rung 2 does *not* refuse it, and that asymmetry is the class's own too: a publication defect at
 *    one host can be absent from another, while no rendition of one manifest escapes that manifest.
 */
internal object ExcludeVariant : LadderRung {

    override fun attempt(load: FailedLoad): RungOutcome {
        if (load.failureClass is FailureClass.Device.DecoderTransient) return RungOutcome.Escalate
        if (load.failureClass is FailureClass.Content.ManifestInvalid) return RungOutcome.Escalate
        val options = load.fallbackOptions ?: return RungOutcome.Escalate
        if (!options.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK)) return RungOutcome.Escalate
        return RungOutcome.FallBackTo(
            LoadErrorHandlingPolicy.FallbackSelection(
                LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                TRACK_EXCLUSION_MS,
            ),
        )
    }

    /**
     * How long an excluded rendition stays out: one minute.
     *
     * The mechanism is time-bounded and there is no untimed one — Media3's `excludeTrack` takes a
     * duration — so "does not reappear on the next evaluation" is met by the duration being long
     * against an evaluation rather than by construction. A minute is tens of evaluations, since a
     * selection is evaluated about once per chunk; a rendition still broken when it lapses fails
     * once more and is excluded again, which is the self-correcting end of the trade, and one that
     * has recovered is back in the ladder without the session having to end to find out.
     *
     * The other exclusion in this library is deliberately the opposite:
     * `NetworkAwareTrackSelection.CEILING_EXCLUSION_MS` is one millisecond, because there the
     * *refusal* keeps the rung out for as long as the ceiling stands and the exclusion only has to
     * unseat the rung that is playing. Nothing refuses a rendition that failed to load, so here the
     * duration is the whole of the mechanism. Media3's own figure for this decision
     * (`DefaultLoadErrorHandlingPolicy.DEFAULT_TRACK_EXCLUSION_MS`) is the same minute, and it is
     * kept for the reason [NextHost.LOCATION_EXCLUSION_MS] is kept.
     */
    const val TRACK_EXCLUSION_MS: Long = 60 * 1_000L
}

/**
 * Rung 4: the next entry of `MediaRequest.sources`, at the position playback had reached.
 *
 * The first rung that is not an answer about a load. Opening another source is a new manifest and a
 * new media source for the same content — `PRD.md` §2.2's canonical case is a DASH stream falling
 * back to an HLS one — and that is a re-adoption, which only the player can perform. So this rung is
 * split across the boundary ADR-0011 rule 5 draws: what the rung *decides* is here, on the class the
 * one classifier assigned, and what it *does* is core's, where the position and the media item are
 * (`PlayerStateRungs`). The failure reaches it once Media3 has given up on the load, which is the
 * proof that rungs 1 to 3 declined or were spent — the order is imposed by the escalation itself
 * rather than by a second climb.
 *
 * ## Two refusals
 *
 * 1. **A ceiling below this rung.** [FailureClass.rungCeiling] is the whole of what a class says
 *    about how far the ladder may climb (rule 1), and a `Fatal.Unsupported` — content this device
 *    cannot play at all — reaches rung 6 without a second source being fetched to fail the same way.
 * 2. **[FailureClass.Device.DecoderTransient].** Its ceiling is *above* this rung, and that is
 *    exactly why the refusal has to be written: a ceiling caps the climb and does not route it. A
 *    decoder that was working and stopped is not a property of the source, and its remedy is rung 5;
 *    downloading a second manifest first would cost a viewer the whole of a startup for nothing.
 *    [FallbackLadder]'s [NextHost] and [ExcludeVariant] refuse it in the same words for the same
 *    reason, which is what makes "reaches rung 5 without trying a host" true of the whole ladder.
 *
 * There is no budget and no backoff here, and that is rule 11 rather than an omission: a budget is
 * how many times the *same* thing is asked for, and each source is asked for once. What bounds the
 * climb is the list — a request with two sources falls back once — and core is what knows its length.
 */
internal object NextSource {

    fun takesOn(error: PlaybackException): Boolean {
        val failureClass = ErrorClassifier.classify(error)
        if (failureClass is FailureClass.Device.DecoderTransient) return false
        return failureClass.mayClimb(FallbackRung.NEXT_SOURCE)
    }
}

/**
 * Rung 5: the decoder recreated, which is core re-preparing the player where it stands.
 *
 * The last remedy before the ladder gives up, and the one no amount of retrying a URL reaches,
 * because the bytes were never the problem (`PRD.md` §3.3). What decides it is one class and nothing
 * else: [FailureClass.Device.DecoderTransient] is the classifier's name for a decoder or an output
 * that was working and stopped, or one that could not be had for a moment — a surface replaced, an
 * HDMI event, every instance on the device held — and a decoder built again is exactly the remedy for
 * that and for nothing else.
 *
 * spec: `MediaCodec.CodecException.isTransient()` is the platform saying the resource was momentarily
 * unavailable and `isRecoverable()` that the codec can be reset and used again; both are read once, in
 * [ErrorClassifier], and what arrives here is the conclusion (ADR-0011 rule 1).
 *
 * ## Two refusals
 *
 * 1. **[FailureClass.Device.DecoderInit].** Its sibling, and the whole reason the taxonomy splits the
 *    two: no decoder was there to lose. Recreating one that could never be initialised for this
 *    content asks the device the same question again and gets the same answer, so the class's ceiling
 *    is rung 4 and this rung is not offered it — the ladder having already tried another variant and
 *    another source by the time it got here.
 * 2. **Everything else.** A refused segment, a frozen playlist, a malformed manifest: none of them is
 *    a fault of this device's decoding, and a player re-prepared against the same broken source is a
 *    loop with a startup in it. They reach rung 6 from here, which is what rung 5 declining means.
 *
 * The ceiling is read as well as the class, in the one function every rung reads it through
 * ([mayClimb]), so that the ladder cannot climb past a ceiling whatever a rung's own routing says.
 *
 * There is no budget and no backoff here either, and for a reason of the same shape as [NextSource]'s:
 * how many recreations are worth attempting is a count of what *this player* has already tried at the
 * position it is stuck at, which is core's half of the rung and not visible from a classification
 * (`PlayerStateRungs.recreatesDecoder`, `SuperPlayer.MAX_DECODER_RECREATIONS`).
 */
internal object RecreateDecoder {

    fun takesOn(error: PlaybackException): Boolean {
        val failureClass = ErrorClassifier.classify(error)
        if (failureClass !is FailureClass.Device.DecoderTransient) return false
        return failureClass.mayClimb(FallbackRung.RECREATE_DECODER)
    }
}

/**
 * Rung 6: the typed, actionable error a session ends on when nothing below it worked (ADR-0011
 * rule 10, `PRD.md` §3.3's sixth rung and §3.2's shape).
 *
 * Not a remedy and therefore not a [LadderRung]: every other rung answers "what do we try next", and
 * this one answers "what do we tell them". What it builds is core's public [SuperPlayerError] — core's
 * because it travels out through core's `Player` API and through core's `PlaybackFailure`, and because
 * a consumer with resilience in their build still names one SuperPlayer type rather than two.
 *
 * Everything on it is read from the one classification and nothing is re-derived (rule 1): the class's
 * own stable name, its message key, its [FailureClass.retryable], its [FailureClass.category] row, and
 * the likely party core's own detection named where it named one. The position is core's half, and the
 * rungs are [ClimbRecord]'s.
 */
internal object TypedError {

    fun of(error: PlaybackException, positionMs: Long, rungsTried: List<String>): SuperPlayerError {
        val failureClass = ErrorClassifier.classify(error)
        return SuperPlayerError(
            causeClass = failureClass.stableName,
            userMessageKey = failureClass.userMessageKey,
            isRetryable = failureClass.retryable,
            category = failureClass.category,
            rungsTried = rungsTried,
            positionMs = positionMs,
            likelyCause = ErrorClassifier.likelyPartyIn(error),
            // Underneath rather than discarded: the engine's error code, message and stack are what a
            // bug report needs after the class has said which conversation to have.
            cause = error,
        )
    }
}

/**
 * The rungs of one player's climb that actually took a failure on, in ladder order.
 *
 * One per player, because a climb is a fact about a player: the ladder itself holds nothing, and
 * `PlaybackResilience`'s KDoc makes one object serving many players the ordinary case. Written from
 * the loading threads Media3 fails loads on and read on the application thread where a failure
 * surfaces, so every access is synchronized — a `java.util.EnumSet` is not, and the cost here is a
 * lock taken once per failed load.
 *
 * Cleared when core says the content changed ([PlayerStateRungs.forgetClimb]), because a new
 * programme gets the whole ladder again and a failure reported with the previous one's history would
 * send someone to look at the wrong CDN.
 */
internal class ClimbRecord {

    private val climbed = EnumSet.noneOf(FallbackRung::class.java)

    @Synchronized
    fun attempted(rung: FallbackRung) {
        climbed += rung
    }

    /** The rungs tried, in ladder order — which `EnumSet` iterates in, being ordinal-ordered. */
    @Synchronized
    fun rungsTried(): List<String> = climbed.map { it.name }

    @Synchronized
    fun forget() {
        climbed.clear()
    }
}

/**
 * The rungs core performs and the error it delivers, as the one object core asks (ADR-0011 rule 13's
 * addendum).
 *
 * One interface rather than a slot each, because the rung order is one order and an implementation
 * that filled one and not the other would be a ladder with a hole in it. Which rung answers a failure
 * is still each rung's own decision, taken by the object that documents it; what is here is the set,
 * and core asks them in the order rule 7 fixes.
 *
 * One per player rather than shared, which is a change from the rung-4 and rung-5 shape and is owed
 * entirely to rung 6: [ClimbRecord] is a player's own history, and a record shared across a feed would
 * report one row's retries on another row's error. Nothing else here holds state — the request, the
 * position and the decoders in hand are still the player's, and none of them is copied to this side.
 */
internal class PlayerStateLadder(private val record: ClimbRecord) : PlayerStateRungs {

    override fun opensNextSource(error: PlaybackException): Boolean =
        NextSource.takesOn(error).also { if (it) record.attempted(FallbackRung.NEXT_SOURCE) }

    override fun recreatesDecoder(error: PlaybackException): Boolean =
        RecreateDecoder.takesOn(error).also { if (it) record.attempted(FallbackRung.RECREATE_DECODER) }

    // Recorded on `true` and not on being asked, for [FallbackLadder.climb]'s reason: core performs
    // exactly the rung it is told may be performed — it asks only once its own half already holds —
    // so a `true` here is a rung tried and a `false` is a rung declined.

    override fun typedErrorFor(error: PlaybackException, positionMs: Long): SuperPlayerError =
        TypedError.of(error, positionMs, record.rungsTried())

    override fun forgetClimb(): Unit = record.forget()
}
