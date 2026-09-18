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

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.dash.manifest.DashManifest
import com.superplayer.core.LiveWindowDepthCheck

/**
 * What an MPD can get wrong, one rule per defect, over the model Media3's own parser built.
 *
 * Internal for [HlsPathologies]' reason (ADR-0015 rule 2), and pure for its other one: this file fetches
 * nothing and holds nothing, so [ManifestExamination] owns the transport and each rule can be read against
 * the clause it cites.
 *
 * ## What is here and what is not
 *
 * Three of the defects an MPD has are the ones a multivariant playlist has, in another spelling, and they
 * are [LadderPathologies]' — a gap between rungs, a rung whose `@bandwidth` overstates what its codec can
 * carry, and a rung with no `@codecs`. What is genuinely DASH's is here: a ladder that changes at a Period
 * boundary, and the two live-window defects `PRD.md` §3.6 calls the cause of a surprising share of "live
 * stream freezes after 30 s" tickets.
 *
 * ## The live window, and why its rule is not in this file
 *
 * A `@timeShiftBufferDepth` too short to hold a playhead is already judged by core, at playback time, in
 * `LiveWindowDepthCheck` (issue #67). ADR-0015 rule 6 makes a copy of that judgement a bug — and there is a
 * real thing to copy, because the rule is not a constant but `depthUs > lag.us` over the longest
 * availability lag any addressed segment declares. So [shortTimeShiftBufferDepth] *asks* core, over the
 * manifest Media3's parser built, and turns the answer into words. The doctor and a player of the same
 * stream therefore cannot disagree about whether its window is too short; they differ only in that one
 * reports it before a session and the other ends one.
 */
internal object DashPathologies {

    /** Everything one MPD says wrong, in the order a report reads naturally: the ladder, then the clock. */
    fun inManifest(uri: Uri, manifest: DashManifest): List<Finding> =
        inLadders(manifest) + listOfNotNull(
            midStreamLadderChange(manifest),
            availabilityStartTimeSkew(manifest),
            shortTimeShiftBufferDepth(uri, manifest),
            missingTimeShiftBufferDepth(manifest),
        )

    /**
     * The shared ladder rules, asked once per `AdaptationSet` of every Period.
     *
     * Per adaptation set, because that is DASH's unit of adaptation (// spec: ISO/IEC 23009-1 §5.3.3 — a
     * client selects one Representation from a set and may switch inside it): a step between an audio set's
     * rung and a video set's is not a step any client takes. Several sets may each answer, and
     * [ManifestExamination] keeps the worst reading of each pathology, so a report says how bad the manifest
     * is rather than how many places it was seen in.
     */
    private fun inLadders(manifest: DashManifest): List<Finding> = periods(manifest)
        .flatMap { period -> period.adaptationSets }
        .flatMap { adaptationSet ->
            val rungs = adaptationSet.representations.map { it.format }
            listOfNotNull(
                LadderPathologies.missingCodecs(rungs, Pathology.DASH_MISSING_CODECS),
                LadderPathologies.gap(rungs, Pathology.DASH_LADDER_GAP),
                LadderPathologies.overstatedBitrate(rungs, Pathology.DASH_OVERSTATED_BITRATE),
            )
        }

    /**
     * A Period that offers a different ladder from the Period before it.
     *
     * spec: ISO/IEC 23009-1 §5.3.2 — a Period is the unit across which the set of Representations may
     * change, so this is entirely conforming and no parser will object. What it costs is that a client
     * rebuilds its track selection at the boundary and lands wherever the new ladder's rungs are.
     *
     * **A Period boundary is not the defect and is not flagged.** Multi-period content is ordinary DASH, and
     * a player re-selects at every boundary whatever the new ladder holds, so a manifest whose Periods offer
     * the same rungs is reported as nothing at all. What is graded is how far the re-selection can *jump*:
     * for every rate one Period has and its neighbour does not, how many times the nearest rate on the other
     * side of the boundary it is. A jump inside what an ordinary up-switch makes anyway costs the boundary
     * no more than adaptation already does.
     *
     * **Compared within one track type and never across two**, which is [inLadders]' argument at the
     * boundary rather than inside a Period: a client selects a video rendition to replace a video rendition,
     * so an audio Period's rates held against a video Period's would be a jump nothing ever makes and a
     * false positive on the commonest content there is. A type the neighbouring Period does not carry at all
     * is passed over rather than read as an infinite move — a Period that drops its subtitles is not a
     * ladder that moved, and what such a Period costs is not this rule's to say.
     *
     * Graded by [LadderPathologies.severityOfStep], which is the static ladder's own pair of thresholds. The
     * arithmetic is the same question — how far apart two rungs a client must move between sit — and the
     * corpus grades this entry at the same three factors for that reason; what differs is only the moment
     * the client is made to move, which is why the reason is written here and the numbers are not.
     */
    private fun midStreamLadderChange(manifest: DashManifest): Finding? {
        val ladders = periods(manifest).map { period ->
            period.adaptationSets
                .groupBy { it.type }
                .mapValues { (_, sets) ->
                    LadderPathologies.declaredRates(sets.flatMap { set -> set.representations.map { it.format } })
                }
        }
        val widest = ladders.zipWithNext()
            .flatMap { (before, after) ->
                before.keys.intersect(after.keys).mapNotNull { type ->
                    widestMove(before.getValue(type), after.getValue(type))
                }
            }
            .maxOrNull()
            ?: return null
        // A jump an ordinary up-switch makes anyway is not the defect, and `severityOfStep` is where that
        // is decided — the same place, and the same number, as for a ladder that never changes.
        val severity = LadderPathologies.severityOfStep(widest) ?: return null
        return Finding(
            Pathology.DASH_MID_STREAM_LADDER_CHANGE,
            severity,
            magnitude = "the ladder moves by ${decimal(widest)}× at a Period boundary",
        )
    }

    /**
     * How far the ladder moves between [before] and [after]: the largest factor between a rate on one side
     * that the other side lacks and the nearest rate it has.
     *
     * Symmetric, because a Period that *drops* its top rung moves the ladder exactly as far as one that adds
     * it, and a client meets both as the same re-selection. Null where neither side has a rate the other
     * lacks, which is a boundary that changes nothing.
     */
    private fun widestMove(before: List<Int>, after: List<Int>): Double? =
        (movesFrom(before, after) + movesFrom(after, before)).maxOrNull()

    /** For each rate in [rungs] that [others] lacks, how many times the nearest rate in [others] it is. */
    private fun movesFrom(rungs: List<Int>, others: List<Int>): List<Double> {
        if (others.isEmpty()) return emptyList()
        return rungs.filter { it !in others }
            .map { moved -> others.minOf { maxOf(moved.toDouble() / it, it.toDouble() / moved) } }
    }

    /**
     * A dynamic MPD whose `@availabilityStartTime` is at or after the time the same MPD says it is.
     *
     * spec: ISO/IEC 23009-1 §5.3.1.2 — for `@type="dynamic"`, `@availabilityStartTime` is the anchor every
     * segment's availability window is computed from, and §5.3.9.5.3 makes a segment's availability start
     * time that anchor plus its presentation time. Nothing requires the anchor to agree with the clock, so
     * an MPD that says "it is now 12:00, and this stream starts at 13:00" conforms — and says, correctly,
     * that nothing is available yet. A player of it sits at a negative position with no error, which is why
     * this entry is the one the fallback ladder is recorded as unable to recover
     * (`HostileManifestLadderTest`'s `CANNOT_RECOVER`): nothing fails to load, so no rung is ever offered.
     * **Naming it is all this ticket claims; it is still not repaired**, and that difference is what Phase 9
     * exists for.
     *
     * ## Which way, and by how much
     *
     * The report says both, because the direction is what decides whose clock is wrong. An anchor *behind*
     * the MPD's own time is what every healthy live stream has — it is how long the stream has been on air —
     * so only the forward direction is a defect, and the magnitude is how long the viewer is being told to
     * wait.
     *
     * ## What it is measured against, and what cannot be seen
     *
     * Against the MPD's **own** notion of now — its `UTCTiming` where it carries a direct one (§5.8.4.11),
     * and its `@publishTime` otherwise — and never against the device's clock. A doctor run on a phone whose
     * clock is wrong would otherwise report the phone as a defect of the stream, on every stream at once.
     *
     * The cost of that is a real limit, and it is stated rather than papered over: a skew *smaller* than the
     * time the stream has been on air is invisible here, because such an MPD is character for character the
     * MPD of a healthy stream that started that much later. Nothing in the document distinguishes them, so
     * the doctor reports nothing — a miss, deliberately taken, rather than the false positive ADR-0015
     * rule 12 weighs equally and which flagging every live stream would be.
     *
     * [FindingSeverity.BLOCKING] with no grading: the stream has published nothing yet, so no player of it
     * can play anything at all until the anchor arrives.
     */
    private fun availabilityStartTimeSkew(manifest: DashManifest): Finding? {
        if (!manifest.dynamic) return null
        if (manifest.availabilityStartTimeMs == C.TIME_UNSET) return null
        val nowMs = serverNowMs(manifest) ?: return null
        val skewMs = manifest.availabilityStartTimeMs - nowMs
        if (skewMs <= 0) return null
        return Finding(
            Pathology.DASH_AVAILABILITY_START_TIME_SKEW,
            FindingSeverity.BLOCKING,
            magnitude = "availabilityStartTime ${seconds(Util.msToUs(skewMs))} ahead of the manifest's " +
                "own clock, so no segment is available yet",
        )
    }

    /**
     * What the MPD says the time is, in Unix milliseconds, or null where it says nothing this can read.
     *
     * spec: ISO/IEC 23009-1 §5.8.4.11 — a `UTCTiming` descriptor whose `urn:mpeg:dash:utc:direct` scheme
     * carries the server's current time in its `@value`; DASH-IF IOP §4.7 requires a descriptor on every
     * dynamic MPD. That is the origin's own statement of now and so the right thing to hold its anchor
     * against. A scheme that names a *service* rather than a time is not resolved here, because resolving it
     * is a second fetch of something that is not a manifest (rule 7), and `@publishTime` — which §5.3.1.2
     * requires of a dynamic MPD — is what the origin said instead.
     */
    private fun serverNowMs(manifest: DashManifest): Long? {
        val direct = manifest.utcTiming
            ?.takeIf { it.schemeIdUri.startsWith(UTC_DIRECT_SCHEME) }
            // Media3 raises on a value that is not an `xs:dateTime`; a descriptor nobody can read is one
            // this rule has no now from, and `@publishTime` is then what the origin said instead.
            ?.let { runCatching { Util.parseXsDateTime(it.value) }.getOrNull() }
        return direct ?: manifest.publishTimeMs.takeIf { it != C.TIME_UNSET }
    }

    /**
     * A live window no playhead fits inside, **as core judges it** (ADR-0015 rules 3 and 6).
     *
     * The comparison is `LiveWindowDepthCheck`'s and is asked rather than restated: a `@timeShiftBufferDepth`
     * no deeper than the lag between a segment starting and becoming fetchable puts every playable position
     * before the window's start, and that lag is read off whatever the manifest addresses rather than
     * assumed. Copying the arithmetic here would be a second definition of "too short" that nothing keeps in
     * step — which is exactly what rule 6 forbids — and it would drift the first time a low-latency
     * `@availabilityTimeOffset` changed the answer.
     *
     * What this file adds is the words. Core's answer is an exception a player ends on; a finding is what a
     * report prints, so the three numbers the comparison was made from become the magnitude and nothing is
     * thrown.
     *
     * [FindingSeverity.BLOCKING], and **ungraded on purpose**. Every other defect with a magnitude is graded
     * by reading it, but this magnitude is not a scale the doctor may read: core's judgement is a yes or a
     * no, and a doctor that called a window *slightly* too short advisory would be holding the same
     * manifest to a second threshold — rule 6 again, arriving by the back door. Either a playhead fits or no
     * player can start, and the numbers printed say by how much.
     */
    private fun shortTimeShiftBufferDepth(uri: Uri, manifest: DashManifest): Finding? {
        val tooShort = LiveWindowDepthCheck.tooShort(uri, manifest) ?: return null
        return Finding(
            Pathology.DASH_SHORT_TIME_SHIFT_BUFFER_DEPTH,
            FindingSeverity.BLOCKING,
            magnitude = "timeShiftBufferDepth of ${seconds(Util.msToUs(tooShort.timeShiftBufferDepthMs))}, " +
                "against the ${seconds(Util.msToUs(tooShort.availabilityLagMs))} a segment takes " +
                "to become available",
        )
    }

    /**
     * A dynamic MPD carrying no `@timeShiftBufferDepth` at all.
     *
     * spec: ISO/IEC 23009-1 §5.3.1.2 — the attribute is optional, and when it is absent "the value is
     * infinite", so the MPD promises that every segment ever published stays available forever. Valid, and
     * untrue of every live origin: the window the client plans its seeks and its recovery against is one the
     * CDN will not keep, and a seek backwards fetches a 404 rather than media.
     *
     * Told from [shortTimeShiftBufferDepth] rather than folded into it, because core's judgement deliberately
     * declines the case — an unlimited window cannot be too short — and a doctor reporting one defect where
     * a packager made two different mistakes would send a support engineer to change the wrong line.
     *
     * [FindingSeverity.DEGRADED] rather than blocking: playback from the live edge is unaffected, which is
     * what most of the session is, and what is measurably worse is bounded — a seek into the promised window,
     * and a recovery that plans against it, meet a 404. No magnitude: the attribute is absent, and an absent
     * window has no size.
     */
    private fun missingTimeShiftBufferDepth(manifest: DashManifest): Finding? {
        if (!manifest.dynamic || manifest.timeShiftBufferDepthMs != C.TIME_UNSET) return null
        return Finding(Pathology.DASH_MISSING_TIME_SHIFT_BUFFER_DEPTH, FindingSeverity.DEGRADED, magnitude = null)
    }

    /** Every Period of [manifest], which Media3 exposes by index rather than as a list. */
    private fun periods(manifest: DashManifest) =
        (0 until manifest.periodCount).map { manifest.getPeriod(it) }

    /** // spec: ISO/IEC 23009-1 §5.8.4.11 — the UTCTiming scheme whose `@value` *is* the time, dated or not. */
    private const val UTC_DIRECT_SCHEME = "urn:mpeg:dash:utc:direct"
}
