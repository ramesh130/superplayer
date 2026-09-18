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
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist

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
 * The three defects a `Representation` has in the same words — a ladder gap, an overstated rung, a rung
 * with no codec string — are judged by [LadderPathologies] over the rungs found here, so that the number
 * that separates advisory from degraded is one number rather than one per protocol.
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
    fun inMultivariant(playlist: HlsMultivariantPlaylist): List<Finding> {
        // The three an MPD gets wrong in the same words are [LadderPathologies]', judged over the rungs this
        // playlist declares; what stays here is what only a multivariant playlist has.
        val rungs = playlist.variants.map { it.format }
        return listOfNotNull(
            LadderPathologies.missingCodecs(rungs, Pathology.HLS_MISSING_CODECS),
            LadderPathologies.gap(rungs, Pathology.HLS_LADDER_GAP),
            LadderPathologies.overstatedBitrate(rungs, Pathology.HLS_OVERSTATED_BITRATE),
            danglingAudioGroup(playlist),
        )
    }

    /** What one media playlist declares about its own segments. */
    fun inMediaPlaylist(playlist: HlsMediaPlaylist): List<Finding> = listOfNotNull(
        raggedSegmentDurations(playlist),
        discontinuityWithoutTimeline(playlist),
    )

    /**
     * What each audio group declares, against the media its own playlist names.
     *
     * [mediaPlaylists] is what the examination managed to read, keyed by the URI it read it from; a group
     * whose playlist never arrived is the fetch's finding and not this rule's, so it is passed over here.
     */
    fun inAudioGroups(
        playlist: HlsMultivariantPlaylist,
        mediaPlaylists: Map<Uri, HlsMediaPlaylist>,
    ): List<Finding> = playlist.audios.mapNotNull { rendition ->
        audioGroupCodecMismatch(rendition, rendition.url?.let(mediaPlaylists::get))
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
        val objectType = rendition.format.codecs?.let(LadderPathologies::aacObjectType) ?: return null
        if (objectType <= HIGHEST_AAC_OBJECT_TYPE_ADTS_CAN_SIGNAL) return null
        val segments = itsPlaylist?.segments.orEmpty()
        if (segments.isEmpty() || segments.any { !isAdts(it.url) }) return null
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
     * **A group carried with no `URI` is not dangling**, and one such group is what the playlist is given
     * the benefit of the doubt over: a rendition with no URI is muxed into the variant's own stream, and
     * Media3 folds it into `muxedAudioFormat` while keeping no group id for it, so exactly one reference the
     * doctor cannot resolve may be that group's. A second unresolved reference cannot be, whatever the muxed
     * one was, which is why the count and not the flag is what suppresses the finding.
     */
    private fun danglingAudioGroup(playlist: HlsMultivariantPlaylist): Finding? {
        val present = playlist.audios.map { it.groupId }.toSet()
        val unresolved = playlist.variants.mapNotNull { it.audioGroupId }.distinct().count { it !in present }
        val allowed = if (playlist.muxedAudioFormat != null) 1 else 0
        if (unresolved <= allowed) return null
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

    /** Whether [segmentUrl] names an ADTS file, read off its path as Media3 reads it. */
    private fun isAdts(segmentUrl: String): Boolean =
        segmentUrl.substringBefore('#').substringBefore('?').endsWith(ADTS_SEGMENT_SUFFIX)

    // spec: ISO/IEC 14496-3 — the ADTS fixed header's `profile` field is two bits holding
    // `audioObjectType - 1`, so an ADTS stream can signal object types 1 to 4 (Main, LC, SSR, LTP) and
    // nothing above. RFC 6381 §3.3's `mp4a.40.5` (HE-AAC) and `mp4a.40.29` (HE-AACv2) are therefore
    // declarations ADTS segments cannot carry.
    private const val HIGHEST_AAC_OBJECT_TYPE_ADTS_CAN_SIGNAL = 4

    // ref: Media3's `DefaultHlsExtractorFactory`, which picks an HLS segment's extractor from the extension
    // of the segment URI's *path*. The doctor reads the container the same way the player does rather than
    // by sniffing bytes it has deliberately not fetched (ADR-0015 rules 6 and 7), and reads the path for the
    // same reason Media3 does: a segment addressed with a query string is still the file its path names.
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
}
