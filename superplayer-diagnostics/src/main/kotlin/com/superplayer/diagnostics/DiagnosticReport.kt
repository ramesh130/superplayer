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

import com.superplayer.core.SuperPlayerError

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

    /**
     * What ended the session this report is the postmortem of, or null where no session ended — which is
     * every preflight, and is also a session that failed on a player built without `superplayer-resilience`.
     *
     * **Read, never re-derived** (ADR-0015 rule 5). It is exactly the `SuperPlayerError` handed to
     * [MediaSourceDoctor.examine], which a consumer got from `player.classify(error)` or off the
     * `PlaybackException` it arrived as the cause of. `ErrorClassifier` stays the single place a failure
     * acquires a meaning (ADR-0011 rule 1), and this module holds no taxonomy with which to recompute one.
     *
     * **It keeps its own vocabulary, and [findings] keep theirs.** A `FailureClass` says what to do about a
     * session that ended; a [Pathology] says what is wrong with a stream. Nothing here maps one onto the
     * other: the two fields may disagree, and disagreement is the information a support engineer wants. A
     * stream whose failure was the network's answers a classification and no finding; a stream that plays
     * on but is misconfigured answers findings and no classification at all.
     */
    public val classification: SuperPlayerError?,
) {

    override fun toString(): String =
        "DiagnosticReport($contentId, over $chain, $findings" +
            (classification?.let { ", ended on ${it.causeClass}" } ?: "") + ")"
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

    // spec: RFC 8216 §4.3.4.2 — BANDWIDTH "represents the peak segment bit rate of the Variant Stream", and
    // nothing in the RFC constrains how far apart two variants' rates may be. A ladder with a step no
    // client can climb gracefully is therefore legal and unplayable in between: the selector has a rung it
    // wastes and a rung it cannot sustain. `HlsPathologies.ladderGap` argues the step the doctor calls a gap.
    HLS_LADDER_GAP(
        id = "hls-ladder-gap",
        specCitation = "RFC 8216 §4.3.4.2",
        cause = "A ladder whose middle rungs were dropped to save encoding cost, or a profile " +
            "template that was only ever tested on wifi and on 3G.",
    ),

    // spec: RFC 8216 §4.3.4.2 — BANDWIDTH "represents the peak segment bit rate of the Variant Stream". A
    // value above the real rate is false, and no MUST requires it to be accurate, so the playlist is valid
    // to the letter and a lie in fact. Every ABR estimator compares its throughput estimate against this
    // number, so an overstated rung is one a player refuses on a link that would carry it easily.
    // `HlsPathologies.overstatedBitrate` argues what the doctor can read without transferring media.
    HLS_OVERSTATED_BITRATE(
        id = "hls-overstated-bitrate",
        specCitation = "RFC 8216 §4.3.4.2",
        cause = "A packager that writes the encoder's configured *peak* rate rather than the " +
            "measured one, or a ladder whose bitrates were copied from a different mezzanine.",
    ),

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

    // spec: RFC 8216 §4.3.4.1 and §4.3.4.2 — CODECS lists formats "where each format specifies a media
    // sample type that is present in one or more Renditions specified by the Variant Stream", and the AUDIO
    // attribute names the group those Renditions are in. A group whose media is not the format declared for
    // it breaks no MUST, because no parser can check it; a player that filters renditions, or configures its
    // output, from the declared codec does so for media it will not receive.
    HLS_AUDIO_GROUP_CODEC_MISMATCH(
        id = "hls-audio-group-codec-mismatch",
        specCitation = "RFC 8216 §4.3.4.1, §4.3.4.2",
        cause = "An encoder ladder migrated from HE-AAC to AAC-LC without the manifest template " +
            "being updated — the audio group still advertises what last year's profile produced.",
    ),

    // spec: RFC 8216 §4.3.4.2 — the AUDIO attribute's "value MUST match the value of the GROUP-ID attribute
    // of an EXT-X-MEDIA tag elsewhere in the Multivariant Playlist". A playlist with no such tag is
    // **malformed** rather than hostile, and a lenient parser — Media3's is one — plays it anyway, which is
    // exactly what makes it a doctor's to flag rather than the engine's to fail on.
    HLS_DANGLING_AUDIO_GROUP(
        id = "hls-dangling-audio-group",
        specCitation = "RFC 8216 §4.3.4.2",
        cause = "A packaging job whose audio rendition failed after the variant lines were " +
            "written, leaving the AUDIO reference behind with nothing to point at.",
    ),

    // spec: RFC 8216 §4.3.2.1 — EXTINF durations need only be accurate enough "to avoid perceptible error
    // when segment durations are accumulated" — and §4.3.3.1, which binds each to the target duration.
    // Nothing in the RFC requires segments to be of *similar* length, so a ragged playlist tells the truth
    // and is still hostile: a buffer counted in segments holds wildly different amounts of media from one
    // moment to the next. `HlsPathologies.raggedSegmentDurations` argues the spread the doctor calls ragged.
    HLS_INCONSISTENT_SEGMENT_DURATIONS(
        id = "hls-inconsistent-segment-durations",
        specCitation = "RFC 8216 §4.3.2.1, §4.3.3.1",
        cause = "Segmentation driven by scene-change keyframes rather than by a fixed GOP, or an " +
            "ad-insertion pass that split one segment and left its neighbours alone.",
    ),

    // spec: RFC 8216 §4.3.2.3 — the EXT-X-DISCONTINUITY tag "MUST be present if there is a change in ...
    // timestamp sequence", so a playlist carrying one is valid. What is missing is the metadata that would
    // place the new timeline: EXT-X-PROGRAM-DATE-TIME (§4.3.2.6) and EXT-X-DISCONTINUITY-SEQUENCE (§4.3.3.3,
    // which in its absence "SHALL be considered to be 0"), both optional, leaving a client the sum of the
    // EXTINF durations and nothing else.
    HLS_DISCONTINUITY_WITHOUT_TIMELINE(
        id = "hls-discontinuity-without-timeline",
        specCitation = "RFC 8216 §4.3.2.3",
        cause = "Mid-roll ad insertion by a stitcher that marks the splice and emits neither a " +
            "discontinuity sequence nor a program date time — the common case for server-side ads.",
    ),

    // spec: RFC 8216 §6.2.1 — a server MUST publish a new version of a live Media Playlist, carrying at
    // least one new segment, within 1.5 target durations — and RFC 9111 §5.2.2.1, which is what an
    // intermediary obeys instead. A `max-age` longer than that bound lets a shared cache answer with a
    // version of the playlist that is already late, and every actor conforms: HTTP caching has nothing to
    // say about what a playlist means. The comparison is core's own (`LivePlaylistRevalidation`), asked
    // rather than restated (ADR-0015 rules 3 and 6), which is how the doctor's reading before playback and
    // the failure a player ends on cannot drift apart.
    HLS_CACHED_LIVE_PLAYLIST(
        id = "hls-cached-live-playlist",
        specCitation = "RFC 8216 §6.2.1, §6.3.4; RFC 9111 §5.2.2.1",
        cause = "One CDN cache rule applied to the whole path. The live media playlist is then " +
            "held for ten minutes while the segments are never held at all, so the client reloads " +
            "a playlist that cannot change and the stream appears to freeze about thirty seconds " +
            "in — a surprising share of \"live stream freezes\" tickets are this.",
    ),

    // ref: RFC 3986 §3.4 — a signed URL carries its credential in the *query component*, so it belongs to
    // that one URI; §5.3 — a relative reference in a playlist is resolved against the base URI's path, and
    // the query is not part of it, so a segment named relatively is addressed with no credential at all.
    // That the CDN then refuses it is RFC 9110 §15.5.4 (403). A signing scheme is a CDN convention rather
    // than a standard, and this is the part of it every scheme shares, stated from first principles.
    HLS_TOKEN_SCOPED_TO_MANIFEST(
        id = "hls-token-scoped-to-manifest",
        specCitation = "RFC 3986 §3.4, §5.3; RFC 9110 §15.5.4",
        cause = "A signing step applied to the URL the app was handed and to nothing below it — " +
            "the manifest is signed, its segments are addressed relatively and reach the CDN with " +
            "no credential at all, so the stream opens and then serves nothing.",
    ),

    // ref: RFC 3986 §3.4 again — a signed URL states its own expiry in the clear, so that an intermediary
    // can refuse an expired request without asking the signer. A token whose expiry falls inside the
    // content it was minted for is valid when playback starts and refused (RFC 9110 §15.5.4) part-way
    // through, which is a failure no rung of the fallback ladder can repair: every source of one piece of
    // content is signed by the same service and dies at the same instant.
    HLS_TOKEN_EXPIRING_IN_WINDOW(
        id = "hls-token-expiring-in-window",
        specCitation = "RFC 3986 §3.4; RFC 9110 §15.5.4",
        cause = "A signing service whose lifetime was set from how long a request takes rather " +
            "than from how long a viewing lasts — every URL is signed, and the signature dies " +
            "part-way through the content, so playback stops where the token ran out.",
    ),

    // spec: WHATWG Fetch §3.3.5 (CORS protocol and credentials) — an `Access-Control-Allow-Origin` of `*`
    // fails §4.10's CORS check whenever the request's credentials mode is "include", whatever
    // `Access-Control-Allow-Credentials` says. The pair is therefore a refusal by construction, and the one
    // CORS configuration that can be called wrong without knowing which origin is asking. Nothing about the
    // document is wrong and a native player never notices, which is why it needs a doctor to be seen at all.
    HLS_CORS_REFUSES_CREDENTIALS(
        id = "hls-cors-refuses-credentials",
        specCitation = "WHATWG Fetch §3.3.5, §4.10",
        cause = "An origin configured to \"allow everyone\" — a wildcard allowed origin emitted " +
            "beside allowed credentials — which is the one pair the CORS protocol refuses outright, " +
            "so every credentialed player of this stream is turned away and the native app never " +
            "notices.",
    ),

    // spec: ISO/IEC 23009-1 §5.3.5.2 — @bandwidth is required per Representation and nothing constrains the
    // spacing between two of them; DASH-IF IOP §3.2.4's ladder guidance is a recommendation. So the DASH
    // twin of HLS_LADDER_GAP is conforming for exactly the same reason, and is judged by the same rule over
    // the same threshold (`LadderPathologies`), because a packager writes both manifests from one ladder
    // definition and a doctor that graded them differently would be reporting on its own two minds.
    DASH_LADDER_GAP(
        id = "dash-ladder-gap",
        specCitation = "ISO/IEC 23009-1 §5.3.5.2; DASH-IF IOP §3.2.4",
        cause = "The same dropped middle rungs as its HLS counterpart, usually because the two " +
            "manifests are generated from one ladder definition by one packager.",
    ),

    // spec: ISO/IEC 23009-1 §5.3.5.2 — @bandwidth is defined against a hypothetical constant-rate channel
    // that delivers each segment in time, so a value above the real rate still satisfies it. The cost is
    // HLS_OVERSTATED_BITRATE's: every bandwidth-based selection is made against a number above the truth.
    DASH_OVERSTATED_BITRATE(
        id = "dash-overstated-bitrate",
        specCitation = "ISO/IEC 23009-1 §5.3.5.2",
        cause = "The encoder's configured peak written through to @bandwidth, unchanged, by a " +
            "packager that never measured a segment.",
    ),

    // spec: ISO/IEC 23009-1 §5.3.7.2 — @codecs is an optional common attribute, so an MPD without one
    // validates; DASH-IF IOP §3.2.4 requires it precisely because without it the player has to fetch and
    // sniff the initialization segment before it can decide whether it can play the Representation at all.
    DASH_MISSING_CODECS(
        id = "dash-missing-codecs",
        specCitation = "ISO/IEC 23009-1 §5.3.7.2; DASH-IF IOP §3.2.4",
        cause = "An MPD assembled from a transcoder's job description rather than from the " +
            "written media — the job knows the bitrate and not the codec string.",
    ),

    // spec: ISO/IEC 23009-1 §5.3.2 — a Period is the unit across which the set of Representations may
    // change, and §5.3.9.2's @presentationTimeOffset is what maps each Period's segments onto its own
    // timeline. A second Period offering a ladder the first did not is therefore entirely conforming, and
    // is what makes a player rebuild its track selection mid-stream and land wherever the new rungs are.
    DASH_MID_STREAM_LADDER_CHANGE(
        id = "dash-mid-stream-ladder-change",
        specCitation = "ISO/IEC 23009-1 §5.3.2, §5.3.9.2",
        cause = "A mid-roll ad break, or an encoder restarted mid-event onto a different " +
            "profile — the second Period is whatever was running when it came back.",
    ),

    // spec: ISO/IEC 23009-1 §5.3.1.2 — for @type="dynamic", @availabilityStartTime is the anchor every
    // segment's availability is computed from, and §5.3.9.5.3 makes a segment's availability start time
    // that anchor plus its presentation time. Nothing requires the anchor to agree with the clock the same
    // MPD publishes, so an anchor in the future conforms and says, correctly, that nothing is available
    // yet. This is the one corpus entry the fallback ladder is recorded as unable to recover: nothing fails
    // to load, so no rung is offered, and a player simply sits at a negative position.
    DASH_AVAILABILITY_START_TIME_SKEW(
        id = "dash-availability-start-time-skew",
        specCitation = "ISO/IEC 23009-1 §5.3.1.2, §5.3.9.5.3",
        cause = "A packager whose own clock or timezone offset is wrong: the MPD publishes a " +
            "correct UTCTiming and an availabilityStartTime ahead of the stream's real start, so " +
            "the origin is telling the client, consistently, that less has been published than has.",
    ),

    // spec: ISO/IEC 23009-1 §5.3.1.2 — @timeShiftBufferDepth is the guaranteed availability window for any
    // segment, and any positive duration conforms; §5.3.9.5.3 makes a segment available only once it is
    // complete, so a window no deeper than that lag has no position a playhead can sit at. Core already
    // judges exactly this at playback time (`LiveWindowTooShortException`, issue #67), and the doctor asks
    // core rather than restating the comparison — ADR-0015 rule 6.
    DASH_SHORT_TIME_SHIFT_BUFFER_DEPTH(
        id = "dash-short-time-shift-buffer-depth",
        specCitation = "ISO/IEC 23009-1 §5.3.1.2",
        cause = "A packager configured for the lowest possible latency, or a value in seconds " +
            "written where the packager wanted minutes.",
    ),

    // spec: ISO/IEC 23009-1 §5.3.1.2 — @timeShiftBufferDepth is optional, and in its absence the window is
    // infinite: every segment ever published is promised forever. Valid, and untrue of every live origin,
    // so the window a client plans its seeks against is one the CDN will not keep.
    DASH_MISSING_TIME_SHIFT_BUFFER_DEPTH(
        id = "dash-missing-time-shift-buffer-depth",
        specCitation = "ISO/IEC 23009-1 §5.3.1.2",
        cause = "A live MPD produced by a VOD packaging template, which has no reason to emit the " +
            "attribute at all.",
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
 *
 * The **HTTP stack** `MediaSourceDoctor.Builder.setHttpStack` takes is on the transport's side of that
 * line and gets no entry here, which is a decision rather than an omission (#314). This field answers
 * "was the layer composed", and it exists because a setter being called does not settle that — a
 * `PlaybackResilience` a consumer wrote themselves contributes no layer. A stack has no such gap: every
 * chain has exactly one bottom, a stack that was named is the one resolved, and one that this device
 * cannot honour is refused at `build()` with an `HttpStackUnsupportedException` rather than silently
 * swapped (ADR-0016 rule 12). An entry saying "this chain had a transport" would be true of every report
 * ever printed.
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
