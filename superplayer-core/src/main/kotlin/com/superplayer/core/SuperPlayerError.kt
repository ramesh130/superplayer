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

/**
 * Rung 6 of ADR-0011's ladder: what a failure *was*, once every rung below it has been exhausted.
 *
 * A consumer is handed one where errors are already handed over — as the `cause` of the
 * `PlaybackException` `Player.Listener.onPlayerError` carries, and of the one
 * [SuperPlayer.getPlayerError] returns — because the facade is a `Player` and errors arrive as a
 * `Player`'s do (ADR-0011 rule 10: no new listener, no callback of SuperPlayer's own). The engine's
 * own exception is *this* exception's [cause], so nothing Media3 said is lost: the error code is on
 * the `PlaybackException` around this, and the stack that produced it is underneath.
 *
 * ## The shape, and why it is this shape
 *
 * `PRD.md` §3.2 fixes it, for the DRM phase as much as for this one: **a cause class, a user-facing
 * string key, and whether it is retryable**. The three answer the three questions an app actually
 * has at the moment playback stops — what broke, what do I put on the screen, and is there any point
 * offering a retry button — and none of them can be answered from an error code without a table the
 * app would have to keep. [rungsTried], [positionMs] and [likelyCause] are rule 10's additions: what
 * was already attempted on the viewer's behalf, where they had got to, and which party to go and
 * look at.
 *
 * Every value here is decided by `superplayer-resilience`'s `ErrorClassifier` and its taxonomy, which
 * is the one place a failure acquires a meaning (ADR-0011 rule 1). This type carries that conclusion
 * across the module boundary and adds nothing to it: the strings are the classifier's own stable
 * names, and core neither reads an error code nor re-derives a class of its own. That is also why the
 * constructor is internal — a consumer receives one, and nothing outside the library mints one.
 *
 * A player built without resilience has nobody to ask, so it never produces one: its errors are
 * Media3's, unchanged, and [SuperPlayer.classify] answers null (ADR-0011 rule 14).
 */
public class SuperPlayerError internal constructor(

    /**
     * What the failure is, as the classifier's stable name — `"Transient.CdnEdge"`,
     * `"Content.ManifestInvalid"`, `"Device.DecoderTransient"` and the rest.
     *
     * A string rather than an enum of core's own, and deliberately: the taxonomy belongs to
     * `superplayer-resilience` (ADR-0011 rule 1) and a copy of it here would be the second answer to
     * one question that rule exists to prevent. It is the same string a log line, a bug report and
     * `PlaybackFailure.classification` carry, which is what lets all three be grouped together.
     */
    public val causeClass: String,

    /**
     * The key an app looks a viewer-facing string up by — a resource name, not a message.
     *
     * The library does not choose words for a viewer: it has no locale, no tone of voice and no idea
     * what the app calls its content. What it can say is *which* of an app's messages this failure
     * deserves, and that is a coarser question than [causeClass] — several classes share a key,
     * because "check your connection" is the right thing to say about all of them.
     */
    public val userMessageKey: String,

    /**
     * Whether asking for the same bytes again could change the outcome.
     *
     * The classifier's own answer, and the same one rung 1 acted on. Never a promise that a retry
     * will work: it is the difference between a failure worth offering a retry button for and one
     * where the button is a lie.
     */
    public val isRetryable: Boolean,

    /**
     * The coarse bucket telemetry reports this failure in, derived from the class by the one-to-one
     * table the classifier owns (ADR-0011 rule 3).
     *
     * Here as well as on `PlaybackFailure` because a consumer showing an error screen wants the same
     * six-way split a dashboard groups by, and deriving it a second time from [causeClass] is exactly
     * the second table rule 3 forbids.
     */
    public val category: FailureCategory,

    /**
     * The rungs the ladder actually climbed for this failure, in the order it climbed them, by their
     * stable names — `"RETRY_SAME_URL"`, `"NEXT_HOST"`, and so on.
     *
     * What it is for is the bug report: "we retried twice, moved to the second CDN, excluded the
     * rendition and opened the second source, and it still failed" is a different conversation with a
     * CDN than "it failed". Strings for [causeClass]'s reason — the rungs are the ladder's vocabulary
     * and not core's.
     *
     * Empty is a fact rather than an absence: a class whose ceiling is the typed error is offered no
     * rung at all, which is what "goes to rung 6 at once" means.
     */
    public val rungsTried: List<String>,

    /**
     * Where playback had reached when the failure surfaced, in milliseconds, or `C.TIME_UNSET` when
     * this player had no position to report.
     *
     * Read before any rung ran, so it is where the *viewer* was rather than where a re-prepared
     * player happens to report — the same instant rule 9 makes every rung resume at.
     */
    public val positionMs: Long,

    /**
     * Which party the evidence points at, where core's own detection named one, and null everywhere
     * else.
     *
     * Today that is exactly [StaleLivePlaylistException]'s: a live playlist that stopped advancing
     * points either at an intermediary serving a copy it holds or at an origin that has stopped
     * publishing, and the two are different phone calls. It survives the mapping into the taxonomy
     * rather than being collapsed into it, which ADR-0011 rule 10 requires in as many words, because
     * the class says what to do and this says who to ask.
     */
    public val likelyCause: StaleLivePlaylistException.LikelyCause?,

    /** The engine's own exception, kept underneath so nothing Media3 said is lost. */
    cause: Throwable?,
) : Exception(describe(causeClass, positionMs, rungsTried), cause)

/**
 * The one-line summary a log, a crash reporter and `toString` all show.
 *
 * Built from the fields rather than taken from the engine's message, because the engine's message is
 * what this type exists to replace — and it stays on the exception underneath for anyone who wants
 * it.
 */
private fun describe(causeClass: String, positionMs: Long, rungsTried: List<String>): String {
    val rungs = if (rungsTried.isEmpty()) "no rung attempted" else "rungs tried: ${rungsTried.joinToString()}"
    return "$causeClass at ${positionMs}ms ($rungs)"
}
