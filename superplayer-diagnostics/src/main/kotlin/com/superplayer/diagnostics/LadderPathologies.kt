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

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Util

/**
 * What a ladder of declared renditions can get wrong, in either protocol's vocabulary.
 *
 * Three of the corpus's defects are the same defect twice — a gap between adjacent rungs, a rung whose
 * declared rate overstates what its format can carry, and a rung that declares no codecs — and the corpus
 * says so itself: `dash-ladder-gap`'s cause is "the same dropped middle rungs as its HLS counterpart". What
 * differs between an `EXT-X-STREAM-INF` and a `Representation` is the attribute's spelling, and both parsers
 * have already resolved that into a Media3 [Format] by the time a rule runs. So the judgement is written
 * once, over a list of formats, and each protocol's file hands it the rungs it found and the [Pathology] its
 * own vocabulary names them by.
 *
 * Writing it twice would be the duplication that matters rather than the kind tooling catches: two copies of
 * a *threshold*, drifting apart, with a stream called degraded in one protocol and advisory in the other
 * after the same packager wrote both from one ladder definition. ADR-0015 rule 6 makes that a bug where core
 * already holds the judgement; here there is no core judgement to call, so one file holds it instead.
 *
 * The thresholds and their arguments are therefore here, once, each at the constant that carries it — the
 * arguments are `HlsPathologies`' own, moved rather than restated, and none of them was ever specific to
 * HLS: TN2224's step is about bit rates, and AAC's bit reservoir is about AAC.
 */
internal object LadderPathologies {

    /**
     * A ladder with any rung that declares no codec string, as one finding.
     *
     * One finding rather than one per rung: the defect is of the manifest — a packager or a template that
     * does not write the attribute at all — so a report with a line per rung would be one fact printed four
     * times.
     *
     * [FindingSeverity.DEGRADED] rather than advisory: the absence costs something measurable on every
     * start. A player that cannot tell from the manifest whether it can decode a rendition has to fetch a
     * segment of it to find out, so a capability check becomes a download and a rendition it cannot decode
     * becomes a stall instead of a rung it never chose. It is not blocking, because content whose single
     * rung the device can decode plays perfectly.
     *
     * No magnitude, for the reason the corpus entries have none: the attribute is present or it is not, and
     * there is no milder absence. How *many* rungs are missing it is a count of the manifest rather than a
     * reading of how far the defect is pushed, and a report that printed one in the magnitude column would
     * be saying a binary defect came in degrees.
     */
    fun missingCodecs(rungs: List<Format>, pathology: Pathology): Finding? {
        if (rungs.none { it.codecs == null }) return null
        return Finding(pathology, FindingSeverity.DEGRADED, magnitude = null)
    }

    /**
     * The widest step between two adjacent rungs of the declared ladder, where it is wider than a ladder
     * built to the published guidance ever is.
     *
     * Read off the declared peak rate alone — `BANDWIDTH` in HLS, `@bandwidth` in DASH — which is the only
     * thing either manifest says about a rung's weight, and off the *distinct* declared rates sorted,
     * because two renditions at one rate (a second CDN, a second container) are one rung of the ladder and
     * not a step of nothing.
     *
     * What the step costs, which is what the severities grade: a client whose throughput falls just short of
     * the upper rung can only sustain the lower one, so it uses about `1 / step` of its link.
     */
    fun gap(rungs: List<Format>, pathology: Pathology): Finding? {
        val rates = declaredRates(rungs)
        if (rates.size < 2) return null
        // The widest step and the two rungs that make it, so the magnitude names the gap rather than the
        // ladder's endpoints — on a ladder of five those are two different pairs.
        val widest = rates.zipWithNext().maxBy { (lower, upper) -> upper.toDouble() / lower }
        val step = widest.second.toDouble() / widest.first
        val severity = severityOfStep(step) ?: return null
        return Finding(
            pathology,
            severity,
            magnitude = "adjacent rungs ${decimal(step)}× apart, " +
                "${kilobits(widest.first)} to ${kilobits(widest.second)}",
        )
    }

    /**
     * A rung whose declared peak rate is past what the format it declares can carry.
     *
     * **This is read from declarations and never from delivery** (ADR-0015 rule 7): the doctor downloads no
     * segment, so it cannot weigh what a rung really sends, and a preflight that did would cost what a start
     * costs. What it can do is hold the number against the codec the same manifest declares beside it — the
     * corpus entries are "a declaration that disagrees with the media the manifest itself describes", and
     * this is that disagreement read.
     *
     * The rule is therefore narrow on purpose, and applies only to a rung the doctor can bound: one whose
     * codec string names an audio format and no video format, and which declares no picture. A rendition
     * carrying video is not bounded here at all, because a video codec's rate depends on a resolution, a
     * frame rate and a profile that neither manifest need declare, and a guess would be the false positive
     * rule 12 punishes.
     *
     * Graded on how far past that ceiling the declaration sits, like every other defect with a magnitude,
     * and never blocking, because the lower rungs still play. The two grades are different stories about the
     * same number: just past a ceiling drawn at the most generous reading of the codec is a rung whose rate
     * was rounded up from a multichannel or high-rate authoring the doctor cannot see, and that is advisory;
     * a multiple past it is a number no encoder could have measured under any of those readings, and a
     * selector then refuses the rung on links that would carry it easily.
     */
    fun overstatedBitrate(rungs: List<Format>, pathology: Pathology): Finding? {
        val worst = rungs.mapNotNull { overstatement(it) }.maxByOrNull { it.factor } ?: return null
        return Finding(
            pathology,
            if (worst.factor > OVERSTATEMENT_NO_AUTHORING_EXPLAINS) FindingSeverity.DEGRADED else FindingSeverity.ADVISORY,
            magnitude = "declares ${kilobits(worst.declared)} where the format it names carries " +
                "at most ${kilobits(worst.ceiling)}",
        )
    }

    /** The distinct declared peak rates of [rungs], ascending: the ladder as a manifest states it. */
    fun declaredRates(rungs: List<Format>): List<Int> = rungs
        .map { it.peakBitrate }
        .filter { it != Format.NO_VALUE && it > 0 }
        .distinct()
        .sorted()

    /**
     * How grave a step of [step] between two rates is, or null where it is a step a ladder built to the
     * published guidance has and therefore not a defect at all.
     *
     * The one pair of thresholds both the static ladder and the ladder that changes mid-stream are graded
     * by, shared for the reason this file exists: the two defects are the same arithmetic asked at two
     * moments — how far apart two rungs a client must move between sit — so one set of thresholds answers
     * both, and `DashPathologies.midStreamLadderChange` says why the *reason* for the grade differs there.
     */
    fun severityOfStep(step: Double): FindingSeverity? = when {
        step <= LADDER_STEP_WITHIN_GUIDANCE -> null
        step > LADDER_STEP_COSTING_MOST_OF_THE_LINK -> FindingSeverity.DEGRADED
        else -> FindingSeverity.ADVISORY
    }

    /** What one rung declares and what it could deliver, where the second is knowable and smaller. */
    private class Overstatement(val declared: Int, val ceiling: Int) {

        /** How many times what it could deliver it claims to: what the severities are read off. */
        val factor: Double get() = declared.toDouble() / ceiling
    }

    /** [format]'s [Overstatement], or null where it declares no rate or none this file can bound. */
    private fun overstatement(format: Format): Overstatement? {
        val declared = format.peakBitrate.takeIf { it != Format.NO_VALUE } ?: return null
        val codecs = format.codecs ?: return null
        if (Util.getCodecsOfType(codecs, C.TRACK_TYPE_VIDEO) != null) return null
        if (format.width != Format.NO_VALUE || format.height != Format.NO_VALUE) return null
        val audio = Util.getCodecsOfType(codecs, C.TRACK_TYPE_AUDIO) ?: return null
        if (aacObjectType(audio) == null) return null
        val channels = format.channelCount.takeIf { it != Format.NO_VALUE } ?: ASSUMED_AUDIO_CHANNELS
        val ceiling = MAX_AAC_BITS_PER_SECOND_PER_CHANNEL * channels
        return if (declared > ceiling) Overstatement(declared, ceiling) else null
    }

    /** The AAC object type [codecs] names — `mp4a.40.<n>` (RFC 6381 §3.3) — or null where it names no AAC. */
    fun aacObjectType(codecs: String): Int? =
        AAC_CODEC.matchEntire(codecs.trim())?.groupValues?.get(1)?.toIntOrNull()

    // ref: Apple Technical Note TN2224, "Best Practices for Creating and Deploying HTTP Live Streaming Media
    // for Apple Devices" — "Adjacent bit rates should be a factor of 1.5 to 2 apart". The HLS Authoring
    // Specification has since superseded the note and states no spacing, so this remains the one published
    // number for a ladder's step, and nothing in it is specific to HLS — which is why DASH, whose own
    // §5.3.5.2 constrains the spacing not at all, is held to it too. Two is its upper end, and the
    // comparison is strict: a ladder built exactly to the guidance uses at least half of any link between
    // two of its rungs and is not a gap, whatever a doctor would prefer.
    private const val LADDER_STEP_WITHIN_GUIDANCE = 2.0

    // Derived, because no document grades a gap once it is one. A client just short of the upper rung uses
    // about `1 / step` of its link, so the question is how much of a link a step may waste before the
    // ladder has stopped adapting. Eight is two whole rungs missing from a ladder built at the guidance's
    // ceiling (2 × 2 × 2): past it, a client between the two rungs is held to under an eighth of what it
    // could sustain, which is a rung that cannot be chosen rather than a ladder that is merely sparse.
    private const val LADDER_STEP_COSTING_MOST_OF_THE_LINK = 8.0

    // spec: ISO/IEC 14496-3 — AAC's bit reservoir bounds one raw data block to 6144 bits per channel, and a
    // block carries 1024 samples, so a channel cannot exceed 6144 × sampleRate / 1024 bits per second. At
    // 96 kHz, the highest sampling frequency the AAC sampling-frequency index admits, that is 576 kbps per
    // channel. The highest rate, deliberately: a ceiling is only useful if content that conforms is under
    // it, and a rendition authored at 44.1 or 48 kHz — which is all of them in practice — is then bounded
    // twice over. Anything a manifest declares above this for an audio-only rung is a number no AAC encoder
    // could have measured.
    private const val MAX_AAC_BITS_PER_SECOND_PER_CHANNEL = 576_000

    // Derived, because a ceiling this generous is only crossed deliberately. The ceiling already assumes the
    // highest sampling rate AAC admits, so a rung just past it is one whose rate was rounded up from an
    // authoring the manifest does not declare — more channels, say — and calling that degraded would be
    // reading a packager's rounding as a lie. Twice it is past every such reading at once: no channel count
    // or sampling rate a stereo-declared rendition could really have doubles its ceiling again.
    private const val OVERSTATEMENT_NO_AUTHORING_EXPLAINS = 2.0

    // spec: RFC 8216 §4.3.4.1 — an audio EXT-X-MEDIA tag SHOULD carry CHANNELS, and §4.3.4.2 gives
    // EXT-X-STREAM-INF no way to state one at all, so the count is often simply absent; ISO/IEC 23009-1
    // §5.3.7.2 makes DASH's AudioChannelConfiguration optional in the same way. Two is what is assumed
    // then: stereo is what an audio rendition that declares nothing is, and assuming more would raise the
    // ceiling on every undeclared rung and let a real overstatement through.
    private const val ASSUMED_AUDIO_CHANNELS = 2

    /** `mp4a.40.<objectType>`, RFC 6381 §3.3: the only codec string a rule here reads a number out of. */
    private val AAC_CODEC = Regex("""mp4a\.40\.(\d+)""")
}
