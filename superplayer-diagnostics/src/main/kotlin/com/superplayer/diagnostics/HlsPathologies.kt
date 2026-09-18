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
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What an HLS playlist can get wrong, one rule per defect, over the model Media3's own parser built.
 *
 * Internal because every type it reads is Media3's and `@UnstableApi` (ADR-0015 rule 2); what leaves is a
 * [Finding], which names none of them. The rules are pure: they fetch nothing and hold nothing, so
 * [ManifestExamination] owns the transport and this file owns the judgement, and each rule can be read
 * against its clause without reading the fetch.
 *
 * ## Where the numbers come from
 *
 * A doctor is scored on its false positives as heavily as on its misses (ADR-0015 rule 12): a rule that
 * flags the `BENIGN` grade of a corpus entry fails the build, and `docs/testing.md` fixes what that grade
 * means — "present, but within what real content does". So **every threshold below is a published bound
 * where a document states one, and a derivation where none does**, argued at the constant that carries it,
 * and each is chosen to sit above what conforming content produces rather than at the middle of the range.
 *
 * ## What a severity means here
 *
 * [FindingSeverity] is per finding, not per pathology, precisely so that a defect with a magnitude is
 * reported at the severity *its own magnitude* earns: the same ladder gap is advisory at one step and
 * degraded at another. A defect that is present or absent carries one severity and no magnitude, because
 * there is nothing to grade. **No HLS defect here is [FindingSeverity.BLOCKING] on the strength of its
 * magnitude alone** — every one of these playlists plays — and the one that is blocking is blocking because
 * the document is malformed rather than because a number is large.
 */
internal object HlsPathologies {

    /** What a multivariant playlist declares about its ladder, its codecs and its groups. */
    fun inMultivariant(playlist: HlsMultivariantPlaylist): List<Finding> = listOfNotNull(
        missingCodecs(playlist),
        ladderGap(playlist),
        overstatedBitrate(playlist),
        danglingAudioGroup(playlist),
    )

    /** What one media playlist declares about its own segments. */
    fun inMediaPlaylist(playlist: HlsMediaPlaylist): List<Finding> = listOfNotNull(
        raggedSegmentDurations(playlist),
        discontinuityWithoutTimeline(playlist),
    )

    /**
     * What one audio group declares against the media its own playlist names, or null where [itsPlaylist]
     * could not be read — a group whose playlist never arrived is the fetch's finding and not this one's.
     */
    fun inAudioGroup(rendition: HlsMultivariantPlaylist.Rendition, itsPlaylist: HlsMediaPlaylist?): Finding? =
        audioGroupCodecMismatch(rendition, itsPlaylist)

    /**
     * A playlist with any variant that declares no `CODECS`, as one finding.
     *
     * One finding rather than one per variant: the defect is of the playlist — a packager or a template
     * that does not write the attribute at all — so a report with a line per rung would be one fact printed
     * four times. Every rule in this file answers once per playlist for the same reason.
     *
     * spec: RFC 8216 §4.3.4.2 — "Every EXT-X-STREAM-INF tag SHOULD include a CODECS attribute", and its
     * value "MUST be all of the parameters of the format" (RFC 6381). A SHOULD, so a playlist without one
     * is valid and a parser reports the absence as no codec string at all rather than as an error — which
     * is why this is read off the parsed variant rather than by re-reading the text.
     *
     * [FindingSeverity.DEGRADED] rather than advisory: the absence costs something measurable on every
     * start. A player that cannot tell from the playlist whether it can decode a rendition has to fetch a
     * segment of it to find out, so a capability check becomes a download and a rendition it cannot decode
     * becomes a stall instead of a rung it never chose. It is not blocking, because content whose single
     * rung the device can decode plays perfectly.
     *
     * No magnitude, for the reason the corpus entry has none: the attribute is present or it is not, and
     * there is no milder absence. How *many* variants are missing it is a count of the playlist rather
     * than a reading of how far the defect is pushed, and a report that printed one in the magnitude
     * column would be saying a binary defect came in degrees.
     */
    private fun missingCodecs(playlist: HlsMultivariantPlaylist): Finding? {
        if (playlist.variants.none { it.format.codecs == null }) return null
        return Finding(Pathology.HLS_MISSING_CODECS, FindingSeverity.DEGRADED, magnitude = null)
    }

    /**
     * The widest step between two adjacent rungs of the declared ladder, where it is wider than a ladder
     * built to the published guidance ever is.
     *
     * Read off `BANDWIDTH` alone, which is the only thing a multivariant playlist says about a rung's
     * weight, and off the *distinct* declared rates sorted, because two variants at one rate (a second CDN,
     * a second container) are one rung of the ladder and not a step of nothing.
     *
     * What the step costs, which is what the severities grade: a client whose throughput falls just short of
     * the upper rung can only sustain the lower one, so it uses about `1 / step` of its link.
     */
    private fun ladderGap(playlist: HlsMultivariantPlaylist): Finding? {
        val rungs = playlist.variants
            .map { it.format.peakBitrate }
            .filter { it != Format.NO_VALUE && it > 0 }
            .distinct()
            .sorted()
        if (rungs.size < 2) return null
        val step = rungs.zipWithNext { lower, upper -> upper.toDouble() / lower }.max()
        if (step <= LADDER_STEP_WITHIN_GUIDANCE) return null
        return Finding(
            Pathology.HLS_LADDER_GAP,
            if (step > LADDER_STEP_COSTING_MOST_OF_THE_LINK) FindingSeverity.DEGRADED else FindingSeverity.ADVISORY,
            magnitude = "adjacent rungs ${decimal(step)}× apart, " +
                "${kilobits(rungs.first())} to ${kilobits(rungs.last())}",
        )
    }

    /**
     * A rung whose declared `BANDWIDTH` is past what the format it declares can carry.
     *
     * **This is read from declarations and never from delivery** (ADR-0015 rule 7): the doctor downloads no
     * segment, so it cannot weigh what a rung really sends, and a preflight that did would cost what a start
     * costs. What it can do is hold the number against the codec the same playlist declares beside it — the
     * corpus entry is "a declaration that disagrees with the media the manifest itself describes", and this
     * is that disagreement read.
     *
     * The rule is therefore narrow on purpose, and applies only to a rung the doctor can bound: one whose
     * `CODECS` names an audio format and no video format, and which declares no picture. A variant carrying
     * video is not bounded here at all, because a video codec's rate depends on a resolution, a frame rate
     * and a profile that a multivariant playlist need not declare, and a guess would be the false positive
     * rule 12 punishes.
     *
     * [FindingSeverity.DEGRADED], and not graded by how far past the ceiling the declaration sits: the
     * ceiling is already the most generous reading of what the codec can produce, so everything the doctor
     * can see is past every threshold a milder grade could be drawn at, and the cost is the same one in
     * every case — a selector holds back its estimate against a number that is not true, so the rung is
     * refused on links that would carry it. It is not blocking, because the lower rungs still play.
     */
    private fun overstatedBitrate(playlist: HlsMultivariantPlaylist): Finding? {
        val worst = playlist.variants
            .mapNotNull { variant -> overstatement(variant.format) }
            .maxByOrNull { (declared, ceiling) -> declared.toDouble() / ceiling }
            ?: return null
        val (declared, ceiling) = worst
        return Finding(
            Pathology.HLS_OVERSTATED_BITRATE,
            FindingSeverity.DEGRADED,
            magnitude = "declares ${kilobits(declared)} where the format it names carries " +
                "at most ${kilobits(ceiling)}",
        )
    }

    /** What [format] declares and what it could deliver, where the second is knowable and smaller. */
    private fun overstatement(format: Format): Pair<Int, Int>? {
        val declared = format.peakBitrate.takeIf { it != Format.NO_VALUE } ?: return null
        val codecs = format.codecs ?: return null
        if (Util.getCodecsOfType(codecs, C.TRACK_TYPE_VIDEO) != null) return null
        if (format.width != Format.NO_VALUE || format.height != Format.NO_VALUE) return null
        val audio = Util.getCodecsOfType(codecs, C.TRACK_TYPE_AUDIO) ?: return null
        if (aacObjectType(audio) == null) return null
        val channels = format.channelCount.takeIf { it != Format.NO_VALUE } ?: ASSUMED_AUDIO_CHANNELS
        val ceiling = MAX_AAC_BITS_PER_SECOND_PER_CHANNEL * channels
        return if (declared > ceiling) declared to ceiling else null
    }

    /**
     * An audio group whose declared codec is not a format the media it names can carry.
     *
     * The comparison a reader expects — the group's declared codec against the variant's — cannot be made,
     * and saying so is part of the rule: RFC 8216 gives `EXT-X-MEDIA` no `CODECS` attribute, so a group's
     * only codec declaration *is* the referencing variant's, and Media3's parser writes that string onto the
     * rendition for exactly that reason. Two declarations of one thing never disagree.
     *
     * What does disagree is the declaration and the **container the group's own segments are in**, which the
     * doctor reads the way the engine does: Media3 chooses an HLS segment's extractor from the segment
     * URI's extension, so a playlist of `.aac` segments is ADTS to the player and to the doctor alike
     * (ADR-0015 rule 6). ADTS can signal four AAC object types and no more — its `profile` field is two bits
     * holding `audioObjectType - 1` (ISO/IEC 14496-3, the ADTS fixed header) — so a group declared as HE-AAC
     * (`mp4a.40.5`, RFC 6381 §3.3) or HE-AACv2 (`mp4a.40.29`) over ADTS names a format those segments cannot
     * be. The declaration is for media the group will not deliver, which is the defect.
     *
     * [FindingSeverity.DEGRADED]: a player that filters renditions or configures its output from the
     * declared format does so for media it will not receive — a measurable cost on every start — while a
     * player that reads the media itself plays on, so it is not blocking.
     *
     * No magnitude: a declared codec is the one delivered or it is not.
     */
    private fun audioGroupCodecMismatch(
        rendition: HlsMultivariantPlaylist.Rendition,
        itsPlaylist: HlsMediaPlaylist?,
    ): Finding? {
        val objectType = rendition.format.codecs?.let(::aacObjectType) ?: return null
        if (objectType <= HIGHEST_AAC_OBJECT_TYPE_ADTS_CAN_SIGNAL) return null
        val segments = itsPlaylist?.segments.orEmpty()
        if (segments.isEmpty() || segments.any { !it.url.endsWith(ADTS_SEGMENT_SUFFIX) }) return null
        return Finding(Pathology.HLS_AUDIO_GROUP_CODEC_MISMATCH, FindingSeverity.DEGRADED, magnitude = null)
    }

    /**
     * A variant whose `AUDIO` attribute names a group the playlist does not carry.
     *
     * spec: RFC 8216 §4.3.4.2 — the attribute's "value MUST match the value of the GROUP-ID attribute of an
     * EXT-X-MEDIA tag elsewhere in the Multivariant Playlist". A MUST, so this one document is genuinely
     * malformed; it is a doctor's to name rather than the engine's to fail on because Media3 parses it
     * leniently and plays it.
     *
     * [FindingSeverity.BLOCKING] — the one HLS finding here that is, and it is not a magnitude's doing. A
     * conformant client is entitled to reject the whole multivariant playlist, so the stream cannot be
     * relied on to play as its publisher intended anywhere but on the lenient parsers; that the app's own
     * player survives it is a property of Media3 rather than of the content.
     *
     * **A group carried with no `URI` is not dangling**, and the doctor declines to answer where the
     * playlist has one: such a rendition is muxed into the variant's own stream, Media3 folds it into
     * `muxedAudioFormat` and keeps no group id for it, so a report either way would be a guess.
     */
    private fun danglingAudioGroup(playlist: HlsMultivariantPlaylist): Finding? {
        if (playlist.muxedAudioFormat != null) return null
        val present = playlist.audios.map { it.groupId }.toSet()
        if (playlist.variants.mapNotNull { it.audioGroupId }.all { it in present }) return null
        return Finding(Pathology.HLS_DANGLING_AUDIO_GROUP, FindingSeverity.BLOCKING, magnitude = null)
    }

    /**
     * Segments whose lengths are further apart than segmentation with any published tolerance produces.
     *
     * What is compared is the **shortest segment against the longest**, rather than either against
     * `EXT-X-TARGETDURATION`: §4.3.3.1 fixes the target as the longest EXTINF rounded up, so a ragged
     * playlist's target is a reading of its own worst segment and comparing the two would grade a defect
     * against itself.
     *
     * The severities grade what the spread costs a buffer. A load control holds a duration of media, so a
     * playlist whose segments differ by a factor of *n* asks it for wildly different amounts of work from
     * one segment to the next, and a cushion sized for the short ones is `n` times too small for a long one.
     */
    private fun raggedSegmentDurations(playlist: HlsMediaPlaylist): Finding? {
        val durationsUs = playlist.segments.map { it.durationUs }.filter { it > 0 }
        if (durationsUs.size < 2) return null
        val shortest = durationsUs.min()
        val longest = durationsUs.max()
        val spread = longest.toDouble() / shortest
        if (spread <= SEGMENT_SPREAD_WITHIN_TOLERANCE) return null
        return Finding(
            Pathology.HLS_INCONSISTENT_SEGMENT_DURATIONS,
            if (spread > SEGMENT_SPREAD_EXHAUSTING_A_CUSHION) FindingSeverity.DEGRADED else FindingSeverity.ADVISORY,
            magnitude = "segments ${seconds(shortest)} to ${seconds(longest)} long, " +
                "a ${decimal(spread)}× spread",
        )
    }

    /**
     * A splice with nothing to place the timeline it starts.
     *
     * spec: RFC 8216 §4.3.2.3 — an `EXT-X-DISCONTINUITY` is required where the timestamp sequence changes,
     * so its presence is correct and the defect is what is missing beside it: `EXT-X-PROGRAM-DATE-TIME`
     * (§4.3.2.6) and `EXT-X-DISCONTINUITY-SEQUENCE` (§4.3.3.3), both optional. Without either, a client has
     * only the accumulated EXTINF durations with which to place the media after the splice, which is why a
     * seek across one lands where the arithmetic says rather than where the media is.
     *
     * An absent `EXT-X-DISCONTINUITY-SEQUENCE` and one written as zero are the same document to a client,
     * because §4.3.3.3 says the value "SHALL be considered to be 0" in its absence; the doctor reads what
     * the client reads and therefore cannot tell them apart, and does not try to.
     *
     * [FindingSeverity.DEGRADED]: the splice plays, and what is worse is measurable — a seek across it, and
     * a live edge computed across it, are placed by accumulation alone. No magnitude: how far the timestamps
     * jump is a property of the media and not of what the playlist failed to say, and the metadata is
     * present or absent with nothing in between.
     */
    private fun discontinuityWithoutTimeline(playlist: HlsMediaPlaylist): Finding? {
        if (playlist.segments.none { it.relativeDiscontinuitySequence > 0 }) return null
        if (playlist.hasProgramDateTime || playlist.discontinuitySequence != 0) return null
        return Finding(Pathology.HLS_DISCONTINUITY_WITHOUT_TIMELINE, FindingSeverity.DEGRADED, magnitude = null)
    }

    /** The AAC object type [codecs] names — `mp4a.40.<n>` (RFC 6381 §3.3) — or null where it names no AAC. */
    private fun aacObjectType(codecs: String): Int? =
        AAC_CODEC.matchEntire(codecs.trim())?.groupValues?.get(1)?.toIntOrNull()

    private fun decimal(value: Double): String = String.format(Locale.US, "%.1f", value).removeSuffix(".0")

    private fun kilobits(bitsPerSecond: Int): String = "${(bitsPerSecond / 1_000.0).roundToInt()} kbps"

    private fun seconds(durationUs: Long): String = "${decimal(durationUs / 1_000_000.0)} s"

    // ref: Apple Technical Note TN2224, "Best Practices for Creating and Deploying HTTP Live Streaming Media
    // for Apple Devices" — "Adjacent bit rates should be a factor of 1.5 to 2 apart". The HLS Authoring
    // Specification has since superseded the note and states no spacing, so this remains the one published
    // number for a ladder's step, and nothing in it is specific to HLS. Two is its upper end, and the
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
    // twice over. Anything a playlist declares above this for an audio-only rung is a number no AAC encoder
    // could have measured.
    private const val MAX_AAC_BITS_PER_SECOND_PER_CHANNEL = 576_000

    // RFC 8216 §4.3.4.1 says an audio EXT-X-MEDIA tag SHOULD carry CHANNELS, and an EXT-X-STREAM-INF has no
    // way to state one at all, so the count is often simply absent. Two is what is assumed then: stereo is
    // what an audio rendition that declares nothing is, and assuming more would raise the ceiling on every
    // undeclared rung and let a real overstatement through.
    private const val ASSUMED_AUDIO_CHANNELS = 2

    // spec: ISO/IEC 14496-3 — the ADTS fixed header's `profile` field is two bits holding
    // `audioObjectType - 1`, so an ADTS stream can signal object types 1 to 4 (Main, LC, SSR, LTP) and
    // nothing above. RFC 6381 §3.3's `mp4a.40.5` (HE-AAC) and `mp4a.40.29` (HE-AACv2) are therefore
    // declarations ADTS segments cannot carry.
    private const val HIGHEST_AAC_OBJECT_TYPE_ADTS_CAN_SIGNAL = 4

    // How Media3 itself decides an HLS segment is ADTS: `DefaultHlsExtractorFactory` picks an extractor from
    // the segment URI's extension, so the doctor reads the container the same way the player does rather
    // than by sniffing bytes it has deliberately not fetched (ADR-0015 rules 6 and 7).
    private const val ADTS_SEGMENT_SUFFIX = ".aac"

    // ref: DASH-IF Interoperability Points v3.0 §3.2.1 — "The maximum tolerance of segment duration shall be
    // ±50% ... of the signaled segment duration". RFC 8216 names no tolerance at all, so this is the one
    // published bound on how ragged segmentation may be, and an HLS packager can meet it as easily as a DASH
    // one. Applied to a spread rather than to one segment: the widest pair a conforming playlist can hold is
    // a segment at half nominal beside one at one and a half, which is a factor of three.
    private const val SEGMENT_SPREAD_WITHIN_TOLERANCE = 3.0

    // Derived. An order of magnitude is where a cushion stops covering the playlist: a buffer that holds ten
    // of the short segments holds one of the long ones, so a single segment can consume the whole of it and
    // the next rebuffer is one badly-timed segment away. Below that the raggedness is untidy and survivable,
    // which is the difference between advisory and degraded.
    private const val SEGMENT_SPREAD_EXHAUSTING_A_CUSHION = 10.0

    /** `mp4a.40.<objectType>`, RFC 6381 §3.3: the only codec string this file reads a number out of. */
    private val AAC_CODEC = Regex("""mp4a\.40\.(\d+)""")
}
