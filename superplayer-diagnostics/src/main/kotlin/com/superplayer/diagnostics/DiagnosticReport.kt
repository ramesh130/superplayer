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

/**
 * What a doctor found in one piece of content, and which chain it looked over.
 *
 * **One report type, for preflight and for postmortem alike** (ADR-0015 rule 8). This is the report
 * [MediaSourceDoctor.examine] answers about a `MediaRequest` before a player is built; examining the
 * request a *failed* session was playing (#291) answers the same type, carrying the classification that
 * session already ended on beside — never instead of — these findings. Two report types would be two
 * vocabularies within a week, and the corpus register (rule 12) could then score only one of them.
 *
 * A report with no findings is the ordinary answer for healthy content and is not an absence of
 * information: it is the doctor saying that nothing it can name is wrong with this manifest.
 */
public class DiagnosticReport internal constructor(

    /** The `MediaRequest.contentId` examined — the identity the app already speaks in, never a URL. */
    public val contentId: String,

    /**
     * What was found, in the order the examination made it: no order is promised beyond that, and in
     * particular severity is not a sort key, because a report is read as a list of facts rather than as a
     * ranking.
     */
    public val findings: List<Finding>,

    /**
     * Which of the chain's optional layers this examination travelled, as the doctor was built with them.
     *
     * Reported because rule 7 makes a doctor exactly as reachable as a player: one opened with no cache
     * and no credential fetches over the bare transport, which is correct — it is what such a player would
     * meet — and it also means two apps can be told different things about one manifest. ADR-0015's
     * *Consequences* asks the report to say which chain it travelled, so that the difference reads as the
     * difference it is rather than as a defect of the doctor.
     */
    public val chain: Set<ChainLayer>,
) {

    override fun toString(): String = "DiagnosticReport($contentId, over $chain, $findings)"
}

/**
 * One defect of one stream: what is wrong, how far it departs from what real content does, the clause it
 * departs from, and the sentence that says why a packager or a CDN produces it.
 *
 * **A pathology is not a failure** (ADR-0015 rule 4). A finding carries no `FailureClass`, no
 * `FailureCategory`, no `isRetryable` and no `FallbackRung`, because those are answers about acting on a
 * session that ended, and a stream with a defect has usually ended no session at all: it plays, and
 * something about it is worse than it should be. Where a session *has* failed, the classification is
 * `ErrorClassifier`'s and is read off `SuperPlayerError` rather than re-derived here (rule 5).
 */
public class Finding internal constructor(

    /** Which defect this is: the stable name a log line, a support ticket and a corpus entry share. */
    public val pathology: Pathology,

    /**
     * What this instance of the defect costs playback.
     *
     * Carried per finding rather than per [pathology] because a graded pathology's severity is a reading
     * of *its magnitude*: the same defect is advisory at one value and blocking at another, and the
     * threshold between them is what a doctor is really scored on (`HostileStream.Severity`'s argument,
     * from the other side).
     */
    public val severity: FindingSeverity,

    /**
     * The value the defect was found at, in words a report can print — "3 of 4 variants", "45 s behind" —
     * or null where there is no magnitude to state, which is a defect that is present or absent.
     */
    public val magnitude: String?,
) {

    /** The clause the stream departs from, as a citation a reader can look up. [Pathology.specCitation]. */
    public val specCitation: String get() = pathology.specCitation

    /** Which misconfiguration, encoder or packager produces this, in plain language. [Pathology.cause]. */
    public val cause: String get() = pathology.cause

    override fun toString(): String =
        "${pathology.id} (${severity.name.lowercase()}${magnitude?.let { ", $it" }.orEmpty()})"
}

/**
 * What a defect costs playback, which is what a consumer sorts their attention by.
 *
 * Deliberately *not* `HostileStream.Severity`, and never compared with one. That scale grades how far a
 * property has been pushed in a piece of test content, so that a doctor's thresholds can be scored; this
 * one grades what the defect does to a viewer. Nor is it `FailureCategory`, which buckets what ended a
 * session (ADR-0015 rule 4).
 */
public enum class FindingSeverity {

    /**
     * The stream plays correctly and something about it is untidy or fragile: a departure worth reporting
     * that costs this playback nothing by itself.
     */
    ADVISORY,

    /**
     * The stream plays and something measurable is worse for the defect — a start that takes longer, a
     * rung that cannot be chosen, a decision made on a number that is not true.
     */
    DEGRADED,

    /**
     * No player of this stream can play it as its publisher intended: the defect is why a session fails,
     * stalls, or plays outside its window.
     */
    BLOCKING,
}

/**
 * Every defect the doctor can name, with the citation and the cause it names it by.
 *
 * The words are here rather than read from `superplayer-testmedia`'s corpus, which carries the same three
 * facts for the same pathologies: that module is test-only and a library cannot depend on it. What keeps
 * the two in step is the [id], which is the corpus entry's id exactly, and the register of ADR-0015
 * rule 12 (#290), which checks this vocabulary against the corpus so that a sixteenth pathology added
 * there fails the build rather than going unnoticed.
 *
 * An enum rather than a string, because the set is closed at any one release and a consumer branching on
 * a defect should do it on a name the compiler knows. It grows as the doctor learns a pathology (#287 to
 * #289), and an entry is added with its citation and its cause together — a claim about a specification
 * without the citation is an opinion, which is the corpus's rule taken unchanged.
 *
 * **Two entries name a defect of the *fetch* rather than of a document, and have no corpus entry**:
 * [MANIFEST_UNREACHABLE] and [MANIFEST_UNREADABLE], which ADR-0015 rule 7 asks for in place of an
 * exception. Nothing in a corpus of streams can carry them, since each entry there serves its media
 * perfectly, so the register of rule 12 scores the corpus against the entries that name a document and
 * these two are named by tests of their own.
 */
public enum class Pathology(

    /** The stable kebab-case name, protocol first: the corpus entry's id, and the join between the two. */
    public val id: String,

    /**
     * The clause this stretches or violates — RFC 8216 for HLS, ISO/IEC 23009-1 for DASH.
     *
     * For the two entries that name a defect of the fetch, where no stream departed from anything, it is
     * the clause that defines what went wrong instead: the status the server answered with, or the syntax
     * the bytes were supposed to be in.
     */
    public val specCitation: String,

    /** Which misconfiguration, encoder or packager produces this in the field, in plain language. */
    public val cause: String,
) {

    // spec: RFC 8216 §4.3.4.2 — "Every EXT-X-STREAM-INF tag SHOULD include a CODECS attribute". A SHOULD,
    // so a playlist without one is valid; what its absence costs is that the player cannot know whether it
    // can decode a rendition until it has fetched a segment of it, which turns a capability check into a
    // download and a fallback into a stall.
    HLS_MISSING_CODECS(
        id = "hls-missing-codecs",
        specCitation = "RFC 8216 §4.3.4.2",
        cause = "A hand-written or templated multivariant playlist — the CODECS string is the one " +
            "attribute a human cannot produce without reading RFC 6381, so it is the one left out.",
    ),

    // spec: RFC 9110 §15 — the status class of the response is what the origin or the edge said about the
    // request. Not a defect of the document, because there is no document: it is the finding rule 7 asks
    // for in place of an exception, since "the doctor threw" is the least useful thing a support ticket
    // can say, and a manifest no player of this app can fetch is the commonest report of all.
    MANIFEST_UNREACHABLE(
        id = "manifest-unreachable",
        specCitation = "RFC 9110 §15",
        cause = "The manifest could not be fetched over the chain this app's players load through — an " +
            "origin or an edge that refused it, a credential it would not accept, or a host that did " +
            "not answer.",
    ),

    // spec: RFC 8216 §4 (the playlist grammar) and ISO/IEC 23009-1 §5.3 (the MPD's) — the bytes arrived
    // and are not a document either grammar admits. Told apart from the entry above because "nothing came
    // back" and "something came back that is not a manifest" send a support engineer to two different
    // teams, and it is read off the *engine's own parser* rejecting them, so what the doctor calls
    // unreadable is exactly what the player would have failed on (rule 6).
    MANIFEST_UNREADABLE(
        id = "manifest-unreadable",
        specCitation = "RFC 8216 §4, ISO/IEC 23009-1 §5.3",
        cause = "What arrived is not a manifest the parser this app's players run can read — a truncated " +
            "or half-written document, an error or consent page served with a 200, or a packager that " +
            "emitted something else entirely.",
    ),
}

/**
 * An optional layer of the chain a doctor's fetch travelled, as [DiagnosticReport.chain] reports it.
 *
 * Only the layers a consumer *chooses* are listed. The transport is always there and says nothing about
 * one doctor against another, and the layers a player has and a doctor deliberately has not —  the two
 * live checks, the load-error policy, CMCD, the bandwidth meter — are `TransferChain.diagnosticChain`'s
 * to explain rather than a report's to advertise.
 */
public enum class ChainLayer {

    /** The `ContentCache` the consumer opened, so a manifest this app holds on disk was read from disk. */
    CONTENT_CACHE,

    /**
     * The header-refresh slot, filled from the `PlaybackResilience` the doctor was built with, as on a
     * player: the app's credential repaired inside the transfer that met a 401 or a 403.
     *
     * It says the slot was filled and not that a credential was minted — a resilience built with no
     * `HeaderProvider` fills it with a layer that repairs nothing, which is what such a player carries too.
     */
    HEADER_REFRESH,
}
