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

package com.superplayer.testmedia

import com.superplayer.testmedia.HostileStream.Severity
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

/**
 * Valid-but-hostile HLS and DASH: the manifests a real CDN really serves, which every parser accepts
 * and every player then has to survive.
 *
 * `PRD.md` Part 5 names the shape — "a curated set of valid-but-hostile manifests — discontinuities,
 * ladder gaps, clock skew, missing codecs, mid-roll changes. This is where real playback bugs live"
 * — and §3.6 names the individual pathologies, because `MediaSourceDoctor` (Phase 9) is the thing
 * that will eventually diagnose each one. This corpus is what it will be graded against.
 *
 * ## Modifiers over a known-good stream
 *
 * Every entry is [SyntheticHlsStream] or [SyntheticDashStream] **with one thing wrong**. Where the
 * defect is in the manifest, the segments are the good stream's own, byte for byte; where it is in
 * the media's shape — segment lengths, a timestamp reset, a rung's real bitrate — the segments come
 * from the same writers with that one property changed. That is what makes a test's intent legible
 * — this is the good stream, with this one defect — and it is why the pathologies are modifiers
 * here rather than fifteen hand-written documents.
 *
 * Generated rather than vendored for `docs/testing.md`'s reason and one more. A checked-in broken
 * manifest is inert; a generated one is a builder call, so every defect that applies to both
 * protocols is generated for both, and every defect with a magnitude is generated at more than one.
 *
 * ## Severities
 *
 * [all] carries every pathology at one value, chosen to be unmistakable: a ladder gap of 48×, a
 * bitrate overstated 31×, an hour of clock skew. Against that alone, a doctor that flags every
 * manifest it sees scores perfectly, and what a doctor actually has to get right — its thresholds —
 * goes ungraded. So every pathology with a magnitude takes a [HostileStream.Severity], and [graded]
 * generates it at `BENIGN` (content a doctor must not flag), `BORDERLINE`, and the `SEVERE` value
 * [all] has always carried.
 *
 * Each level's value is argued at the branch that returns it, in the *Severities* section below: a
 * `// spec:` or `// ref:` where a published document speaks to the number, and field rationale or a
 * derivation where none does. A doctor's thresholds will be scored against these numbers, so each has
 * to stand on its own rather than only relative to its neighbours. A pathology with no magnitude is
 * generated once, and the entry says why it is binary.
 *
 * ## What each entry carries
 *
 * A [HostileStream] is not just bytes: it names the clause it stretches, the misconfiguration that
 * produces it in the field, and whether the document is still legal. Every entry below is preceded
 * by the `// spec:` citation the clean-room rules require (`CONTRIBUTING.md`), which is where the
 * argument for "valid" is actually made; the `spec` field is that citation as data.
 *
 * ## What this corpus does *not* claim
 *
 * Nothing here asserts that SuperPlayer handles these well. Most are not handled at all yet — that
 * is Phases 4 through 9 — so the corpus's test records what the player does with each one today,
 * and a later fix shows up as a diff in that record. See `docs/testing.md`.
 *
 * ## Functions, not properties
 *
 * Each entry is built on demand because the live DASH manifests are anchored to the wall clock at
 * the moment they are generated: an `availabilityStartTime` a known distance from the stream's real
 * start has to be measured from *now*, not from whenever this class happened to load.
 */
public object HostileManifests {

    /**
     * How many media segments every entry carries.
     *
     * Four, matching `TestContent.DEFAULT_SEGMENT_COUNT`, because several pathologies are only
     * visible across segments — a mid-stream ladder change needs a stream with a middle, and a
     * discontinuity needs something on each side of it.
     */
    public const val SEGMENT_COUNT: Int = 4

    /** Where a hostile stream's own files live, one directory per [HostileStream.id]. */
    private const val BASE_URI = "fake://superplayer.test/hostile/"

    /**
     * The whole corpus at its unmistakable severity, one entry per pathology, in protocol order. A test
     * iterates this; nothing may be left out of it.
     *
     * It is [graded]'s `SEVERE` half, and derived from it rather than listed beside it, so the two
     * cannot disagree about which pathologies exist.
     */
    public fun all(): List<HostileStream> = graded().filter { it.severity == Severity.SEVERE }

    /**
     * Every pathology at every severity it has, in protocol order: a pathology with a magnitude three
     * times, mildest first, and a binary one once, at `SEVERE`. This is what a doctor's thresholds are
     * scored against — see *Severities* in the class KDoc.
     *
     * A graded entry keeps its pathology's [HostileStream.id], and is told apart by its
     * [HostileStream.severity]; each level is served from its own directory, so the whole graded
     * corpus still fits in one `FakeDataSet`.
     */
    public fun graded(): List<HostileStream> = listOf(
        Severity.entries.map { hlsLadderGap(it) },
        Severity.entries.map { hlsOverstatedBitrate(it) },
        listOf(hlsMissingCodecs()),
        listOf(hlsAudioGroupCodecMismatch()),
        listOf(hlsDanglingAudioGroup()),
        Severity.entries.map { hlsInconsistentSegmentDurations(it) },
        listOf(hlsDiscontinuityWithoutTimeline()),
        listOf(hlsCachedLivePlaylist()),
        Severity.entries.map { dashLadderGap(it) },
        Severity.entries.map { dashOverstatedBitrate(it) },
        listOf(dashMissingCodecs()),
        Severity.entries.map { dashAvailabilityStartTimeSkew(it) },
        Severity.entries.map { dashShortTimeShiftBufferDepth(it) },
        listOf(dashMissingTimeShiftBufferDepth()),
        Severity.entries.map { dashMidStreamLadderChange(it) },
    ).flatten()

    // ------------------------------------------------------------------------------------------
    // HLS
    // ------------------------------------------------------------------------------------------

    // spec: RFC 8216 §4.3.4.2 — BANDWIDTH "represents the peak segment bit rate of the Variant
    // Stream", and nothing in the RFC constrains how far apart two variants' rates may be. At its
    // severe setting, a ladder of 128 kbps and 6 Mbps with nothing between is therefore a legal
    // multivariant playlist and an unplayable one on any connection in between: the selector has a
    // rung it wastes and a rung it cannot sustain. The top rung has its own playlist and its own
    // segments, as many times the size of the bottom's as the two declare — so the gap is the only
    // thing wrong. [ladderStepFactor] argues each severity's step.
    public fun hlsLadderGap(severity: Severity = Severity.SEVERE): HostileStream {
        val factor = ladderStepFactor(severity)
        return hlsStream(
            id = "hls-ladder-gap",
            validity = HostileStream.Validity.VALID_BUT_HOSTILE,
            spec = "RFC 8216 §4.3.4.2",
            cause = "A ladder whose middle rungs were dropped to save encoding cost, or a profile " +
                "template that was only ever tested on wifi and on 3G.",
            severity = severity,
            magnitude = ladderStepMagnitude(factor),
            multivariant = hlsMultivariantPlaylist(
                listOf(
                    HlsVariant(SyntheticHlsStream.DECLARED_BITRATE_BPS),
                    HlsVariant(SyntheticHlsStream.DECLARED_BITRATE_BPS * factor, playlist = HIGH_MEDIA_PLAYLIST_NAME),
                ),
            ),
            extraFiles = buildMap {
                put(
                    HIGH_MEDIA_PLAYLIST_NAME,
                    hlsMediaPlaylist(goodHlsDurations(), segmentName = ::highHlsSegmentName).toByteArray(),
                )
                repeat(SEGMENT_COUNT) { index ->
                    put(
                        highHlsSegmentName(index),
                        SyntheticHlsStream.adtsSegment(
                            startSeconds = index * SyntheticHlsStream.SEGMENT_DURATION_SECONDS,
                            durationSeconds = SyntheticHlsStream.SEGMENT_DURATION_SECONDS,
                            sizeScale = factor,
                        ),
                    )
                }
            },
        )
    }

    // spec: RFC 8216 §4.3.4.2 — BANDWIDTH "represents the peak segment bit rate of the Variant
    // Stream". A value above the real rate is therefore false, but no MUST in the RFC requires it to
    // be accurate and no parser can tell without measuring segments, so the playlist is valid to the
    // letter and a lie in fact. Every ABR estimator compares its throughput estimate against this
    // number, so a rung declared at many times its real bitrate is one the player will refuse on a
    // connection that would carry it easily. [overstatedBitrateBps] argues each severity's value.
    public fun hlsOverstatedBitrate(severity: Severity = Severity.SEVERE): HostileStream {
        val declaredBps = overstatedBitrateBps(severity)
        return hlsStream(
            id = "hls-overstated-bitrate",
            validity = HostileStream.Validity.VALID_BUT_HOSTILE,
            spec = "RFC 8216 §4.3.4.2",
            cause = "A packager that writes the encoder's configured *peak* rate rather than the " +
                "measured one, or a ladder whose bitrates were copied from a different mezzanine.",
            severity = severity,
            magnitude = overstatedBitrateMagnitude(declaredBps),
            multivariant = hlsMultivariantPlaylist(listOf(HlsVariant(declaredBps))),
        )
    }

    // spec: RFC 8216 §4.3.4.2 — "Every EXT-X-STREAM-INF tag SHOULD include a CODECS attribute". A
    // SHOULD, so its absence is valid; what it costs is that the player cannot know whether it can
    // decode a rendition until it has fetched a segment of it, which turns a capability check into
    // a download and a fallback into a stall.
    //
    // Single severity: the attribute is present or it is not, and there is no milder absence.
    public fun hlsMissingCodecs(): HostileStream = hlsStream(
        id = "hls-missing-codecs",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "RFC 8216 §4.3.4.2",
        cause = "A hand-written or templated multivariant playlist — the CODECS string is the one " +
            "attribute a human cannot produce without reading RFC 6381, so it is the one left out.",
        multivariant = hlsMultivariantPlaylist(listOf(HlsVariant(codecs = null))),
    )

    // spec: RFC 8216 §4.3.4.2 — CODECS lists formats "where each format specifies a media sample
    // type that is present in one or more Renditions specified by the Variant Stream", and the AUDIO
    // attribute names a matching EXT-X-MEDIA group (§4.3.4.1). Both tags are present and well
    // formed. What disagrees is the content: the variant declares HE-AAC (`mp4a.40.5`) over a
    // rendition that carries AAC-LC, so the declared format is not present. That contradicts the
    // attribute's definition but breaks no MUST — no parser can check it without decoding — so it
    // is labelled hostile rather than malformed, and the argument is written here for anyone who
    // disagrees. A player that filters renditions, or configures its output, from the declared
    // codec does so for media it will not receive.
    //
    // Single severity: a declared codec is the one delivered or it is not. HE-AAC over AAC-LC is
    // already the mildest mismatch there is — one object type within the same codec family — and it
    // still names a different decoder configuration, so there is no benign form to generate.
    public fun hlsAudioGroupCodecMismatch(): HostileStream = hlsStream(
        id = "hls-audio-group-codec-mismatch",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "RFC 8216 §4.3.4.1, §4.3.4.2",
        cause = "An encoder ladder migrated from HE-AAC to AAC-LC without the manifest template " +
            "being updated — the audio group still advertises what last year's profile produced.",
        multivariant = hlsMultivariantPlaylist(
            variants = listOf(
                HlsVariant(
                    bandwidthBps = SyntheticHlsStream.DECLARED_BITRATE_BPS,
                    codecs = HIGH_EFFICIENCY_AAC_CODECS,
                    audioGroupId = AUDIO_GROUP_ID,
                ),
            ),
            renditions = listOf(HlsAudioRendition(AUDIO_GROUP_ID)),
        ),
    )

    // spec: RFC 8216 §4.3.4.2 — the AUDIO attribute's "value MUST match the value of the GROUP-ID
    // attribute of an EXT-X-MEDIA tag elsewhere in the Multivariant Playlist". There is no such tag
    // here, so this one is genuinely **malformed** rather than hostile, and is labelled so: it is
    // carried because packagers really do emit it, and a lenient parser — Media3's is one — plays
    // it anyway, which is exactly what a doctor needs to be able to flag.
    //
    // Single severity: a reference resolves or it does not.
    public fun hlsDanglingAudioGroup(): HostileStream = hlsStream(
        id = "hls-dangling-audio-group",
        validity = HostileStream.Validity.MALFORMED,
        spec = "RFC 8216 §4.3.4.2",
        cause = "A packaging job whose audio rendition failed after the variant lines were " +
            "written, leaving the AUDIO reference behind with nothing to point at.",
        multivariant = hlsMultivariantPlaylist(
            listOf(HlsVariant(audioGroupId = AUDIO_GROUP_ID)),
        ),
    )

    // spec: RFC 8216 §4.3.2.1 — EXTINF durations need only be accurate enough "to avoid perceptible
    // error when segment durations are accumulated", and these are exact — and §4.3.3.1: "The EXTINF
    // duration of each Media Segment in the Playlist file, when rounded to the nearest integer, MUST
    // be less than or equal to the target duration", which holds because the target is the longest
    // segment rounded up. Nothing in the RFC requires segments to be of similar length. The segments
    // really are as long as the playlist says, generated by the good stream's own writer, so the
    // playlist tells the truth and the hostility is the raggedness alone: at its severe setting, a
    // buffer target counted in segments means anything from half a second to ten seconds of media per
    // segment. [raggedSegmentDurationsSeconds] argues each severity's spread.
    public fun hlsInconsistentSegmentDurations(severity: Severity = Severity.SEVERE): HostileStream {
        val durations = raggedSegmentDurationsSeconds(severity)
        // The playlist names one segment per duration, so the two have to agree: a ragged list
        // shorter than the stream would quietly test a shorter stream instead.
        check(durations.size == SEGMENT_COUNT) { "A ragged playlist needs one duration per segment" }
        return hlsStream(
            id = "hls-inconsistent-segment-durations",
            validity = HostileStream.Validity.VALID_BUT_HOSTILE,
            spec = "RFC 8216 §4.3.2.1, §4.3.3.1",
            cause = "Segmentation driven by scene-change keyframes rather than by a fixed GOP, or an " +
                "ad-insertion pass that split one segment and left its neighbours alone.",
            severity = severity,
            magnitude = "segments ${decimal(durations.min())} s to ${decimal(durations.max())} s long, " +
                "against a nominal ${decimal(SyntheticHlsStream.SEGMENT_DURATION_SECONDS)} s",
            multivariant = hlsMultivariantPlaylist(listOf(HlsVariant())),
            media = hlsMediaPlaylist(durations),
            segments = durations.runningFold(0.0) { start, duration -> start + duration }
                .zip(durations)
                .mapIndexed { index, (start, duration) ->
                    SyntheticHlsStream.segmentName(index) to SyntheticHlsStream.adtsSegment(start, duration)
                }
                .toMap(),
            durationMs = (durations.sum() * 1_000).toLong(),
        )
    }

    // spec: RFC 8216 §4.3.2.3 — "The EXT-X-DISCONTINUITY tag MUST be present if there is a change in
    // any of the following characteristics: file format; number, type, and identifiers of tracks;
    // timestamp sequence." The segments after the splice restart their timestamps at zero, as a
    // stitched-in ad does, so the tag is required and present: the playlist is valid. The hostility
    // is the reset itself, which the player has to splice across; the tags that would help it place
    // the new timeline — EXT-X-PROGRAM-DATE-TIME (§4.3.2.6) and EXT-X-DISCONTINUITY-SEQUENCE
    // (§4.3.3.3, which in its absence "SHALL be considered to be 0") — are optional and absent, so it
    // has only the sum of the EXTINF durations to do it with.
    //
    // Single severity: the placing tags are present or they are absent. How far the timestamps jump
    // at the splice is a magnitude, but not this pathology's — the tag is required whatever the jump,
    // and what is missing is only the metadata that would place it.
    public fun hlsDiscontinuityWithoutTimeline(): HostileStream = hlsStream(
        id = "hls-discontinuity-without-timeline",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "RFC 8216 §4.3.2.3",
        cause = "Mid-roll ad insertion by a stitcher that marks the splice and emits neither a " +
            "discontinuity sequence nor a program date time — the common case for server-side ads.",
        multivariant = hlsMultivariantPlaylist(listOf(HlsVariant())),
        media = hlsMediaPlaylist(
            durationsSeconds = goodHlsDurations(),
            discontinuityBefore = setOf(SPLICE_INDEX),
        ),
        segments = (0 until SEGMENT_COUNT).associate { index ->
            // Timestamps run from zero again at the splice: that is the discontinuity.
            val indexOnItsTimeline = if (index < SPLICE_INDEX) index else index - SPLICE_INDEX
            SyntheticHlsStream.segmentName(index) to SyntheticHlsStream.adtsSegment(
                startSeconds = indexOnItsTimeline * SyntheticHlsStream.SEGMENT_DURATION_SECONDS,
                durationSeconds = SyntheticHlsStream.SEGMENT_DURATION_SECONDS,
            )
        },
    )

    // spec: RFC 8216 §6.3.4 — "The client MUST periodically reload a Media Playlist file to learn
    // what media is currently available" while it has no EXT-X-ENDLIST — and RFC 9111 §5.2.2.1
    // (max-age) is what an intermediary obeys instead. The playlist here is served `max-age=600`
    // while its segments are `no-store`: exactly backwards, and legal, because HTTP caching has
    // nothing to say about what a playlist means.
    //
    // Every actor conforms, and the composite still breaks RFC 8216 §6.2.1's server rule — "a new
    // version of the Playlist file ... that contains at least one new Media Segment", no later than
    // 1.5 target durations on — because the version that reaches the client is the cached one. The
    // document itself is valid at every instant, which is what `validity` describes.
    //
    // This is the one entry whose cause is not in the bytes. The headers are recorded on the stream
    // and served by testkit's harness on top of them, because Media3's `FakeDataSource` reports none
    // — see `HostileStream.declaredResponseHeaders` and `docs/testing.md`. What the bytes reproduce
    // is the consequence: a live media playlist, no EXT-X-ENDLIST, whose content never changes
    // however often it is reloaded — which is exactly what a client behind that cache rule sees, and
    // what no reload past the cache can recover, because nothing newer exists here. The recovery is
    // played over a live origin instead, in testkit's `LivePlaylistRevalidationTest`.
    //
    // Single severity, although `max-age` has a value, because the bytes cannot grade it. A shorter
    // max-age is a playlist that updates late rather than never, and what these bytes reproduce is
    // the never; the headers that would carry a milder value are applied by nothing here. Three
    // levels would be three identical streams claiming a grading the corpus does not have.
    public fun hlsCachedLivePlaylist(): HostileStream = hlsStream(
        id = "hls-cached-live-playlist",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "RFC 8216 §6.2.1, §6.3.4; RFC 9111 §5.2.2.1",
        cause = "One CDN cache rule applied to the whole path. The live media playlist is then " +
            "held for ten minutes while the segments are never held at all, so the client reloads " +
            "a playlist that cannot change and the stream appears to freeze about thirty seconds " +
            "in — a surprising share of \"live stream freezes\" tickets are this.",
        multivariant = hlsMultivariantPlaylist(listOf(HlsVariant())),
        media = hlsMediaPlaylist(
            durationsSeconds = goodHlsDurations(),
            live = true,
        ),
        declaredResponseHeaders = { uri ->
            when {
                uri.endsWith(SyntheticHlsStream.MEDIA_PLAYLIST_NAME) ->
                    mapOf("Cache-Control" to "public, max-age=600")

                uri.endsWith(SyntheticHlsStream.SEGMENT_SUFFIX) ->
                    mapOf("Cache-Control" to "no-store")

                else -> emptyMap()
            }
        },
    )

    // ------------------------------------------------------------------------------------------
    // DASH
    // ------------------------------------------------------------------------------------------

    // spec: ISO/IEC 23009-1 §5.3.5.2 — @bandwidth is required per Representation and nothing
    // constrains the spacing between them; DASH-IF IOP's ladder guidance is a recommendation. A
    // two-rung AdaptationSet with any factor between the rungs is therefore a conforming MPD, and
    // this is the DASH half of [hlsLadderGap] at the same severities — with the top rung, as there,
    // on its own segments as many times the size as it declares, so the gap is the only thing wrong.
    public fun dashLadderGap(severity: Severity = Severity.SEVERE): HostileStream {
        val factor = ladderStepFactor(severity)
        return dashStream(
            id = "dash-ladder-gap",
            validity = HostileStream.Validity.VALID_BUT_HOSTILE,
            spec = "ISO/IEC 23009-1 §5.3.5.2; DASH-IF IOP §3.2.4",
            cause = "The same dropped middle rungs as its HLS counterpart, usually because the two " +
                "manifests are generated from one ladder definition by one packager.",
            severity = severity,
            magnitude = ladderStepMagnitude(factor),
            representations = listOf(
                DashRepresentation("0", SyntheticDashStream.DECLARED_BITRATE_BPS),
                DashRepresentation(
                    "1",
                    SyntheticDashStream.DECLARED_BITRATE_BPS * factor,
                    segmentName = ::highDashSegmentName,
                ),
            ),
            extraFiles = highDashSegments(0 until SEGMENT_COUNT, factor),
        )
    }

    // spec: ISO/IEC 23009-1 §5.3.5.2 — @bandwidth is defined against a hypothetical constant-rate
    // channel that delivers each segment in time, so a value above the real rate still satisfies it.
    // The cost is the same as in HLS: every bandwidth-based selection is made against a number above
    // the truth, by as much as [overstatedBitrateBps] says for the severity.
    public fun dashOverstatedBitrate(severity: Severity = Severity.SEVERE): HostileStream {
        val declaredBps = overstatedBitrateBps(severity)
        return dashStream(
            id = "dash-overstated-bitrate",
            validity = HostileStream.Validity.VALID_BUT_HOSTILE,
            spec = "ISO/IEC 23009-1 §5.3.5.2",
            cause = "The encoder's configured peak written through to @bandwidth, unchanged, by a " +
                "packager that never measured a segment.",
            severity = severity,
            magnitude = overstatedBitrateMagnitude(declaredBps),
            representations = listOf(DashRepresentation("0", declaredBps)),
        )
    }

    // spec: ISO/IEC 23009-1 §5.3.7.2 — @codecs is an optional common attribute, so an MPD without
    // it validates; DASH-IF IOP §3.2.4 requires it precisely because without it the player has to
    // fetch and sniff the initialization segment before it can decide whether it can play the
    // representation at all.
    //
    // Single severity: the attribute is present or it is not, and there is no milder absence.
    public fun dashMissingCodecs(): HostileStream = dashStream(
        id = "dash-missing-codecs",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "ISO/IEC 23009-1 §5.3.7.2; DASH-IF IOP §3.2.4",
        cause = "An MPD assembled from a transcoder's job description rather than from the " +
            "written media — the job knows the bitrate and not the codec string.",
        representations = listOf(DashRepresentation("0", codecs = null)),
    )

    /**
     * The healthy live stream every live DASH entry is a modifier over — **not** a pathology, and
     * not in [all].
     *
     * [SyntheticDashStream] has no live form, and the on-demand one cannot stand in for it: a live
     * entry that fails might be failing because of its defect or because this harness cannot play
     * live DASH at all, and only a healthy live stream in the same harness tells the two apart. The
     * corpus's test plays this first and records that it plays on, which is what makes every live
     * row mean what it says.
     *
     * It is a [HostileStream] only so that it is served and played exactly as the entries are, and it
     * says so: its [HostileStream.validity] is [HostileStream.Validity.HEALTHY]. Its severity is
     * `BENIGN`, because that level's meaning — a doctor must not flag it — is trivially true of a
     * stream with nothing wrong, and it has no magnitude.
     */
    // spec: ISO/IEC 23009-1 §5.3.1.2 (a dynamic MPD with @availabilityStartTime, @minimumUpdatePeriod
    // and a minute of @timeShiftBufferDepth) and §5.8.4.11 (UTCTiming) — the conforming baseline.
    public fun dashLiveBaseline(): HostileStream = dashLiveStream(
        id = "dash-live-baseline",
        validity = HostileStream.Validity.HEALTHY,
        spec = "ISO/IEC 23009-1 §5.3.1.2, §5.8.4.11",
        cause = "None: this is the healthy stream the live entries are built from.",
        severity = Severity.BENIGN,
        magnitude = null,
        timeShiftBufferDepthSeconds = TIME_SHIFT_BUFFER_DEPTH_SECONDS,
    )

    // spec: ISO/IEC 23009-1 §5.3.1.2 — for @type="dynamic", @availabilityStartTime is the anchor
    // every segment's availability window is computed from, and §5.3.9.5.3 makes a segment's
    // availability start time that anchor plus its presentation time. Nothing requires the anchor
    // to agree with the time the same MPD publishes in its UTCTiming element, so an MPD that says
    // "it is now 12:00, and this stream starts at 13:00" conforms — and says, correctly, that
    // nothing is available yet, which a player reports as a stall with no error.
    //
    // The skew is measured from the healthy stream's anchor, not from "now": the baseline has been on
    // air [LIVE_HEAD_START_SECONDS], and a skewed packager publishes that same anchor late by the
    // skew. [availabilityStartTimeSkewMs] argues each severity's value.
    public fun dashAvailabilityStartTimeSkew(severity: Severity = Severity.SEVERE): HostileStream {
        val skewMs = availabilityStartTimeSkewMs(severity)
        return dashLiveStream(
            id = "dash-availability-start-time-skew",
            validity = HostileStream.Validity.VALID_BUT_HOSTILE,
            spec = "ISO/IEC 23009-1 §5.3.1.2, §5.3.9.5.3",
            cause = "A packager whose own clock or timezone offset is wrong: the MPD publishes a " +
                "correct UTCTiming and an availabilityStartTime ahead of the stream's real start, so " +
                "the origin is telling the client, consistently, that less has been published than has.",
            severity = severity,
            magnitude = "availabilityStartTime ${decimal(skewMs / 1_000.0)} s after the stream's real start",
            availabilityStartOffsetMs = LIVE_HEAD_START_OFFSET_MS + skewMs,
            timeShiftBufferDepthSeconds = TIME_SHIFT_BUFFER_DEPTH_SECONDS,
        )
    }

    // spec: ISO/IEC 23009-1 §5.3.1.2 — @timeShiftBufferDepth is the guaranteed availability window
    // for any segment, and any positive duration conforms. At its severe setting, half a second is
    // shorter than one segment, and §5.3.9.5.3 makes a segment available only once it is complete, so
    // by the time a segment can be fetched its start has already left the window: no playhead fits
    // inside it at all (issue #67). [timeShiftBufferDepthSeconds] argues each severity's depth.
    public fun dashShortTimeShiftBufferDepth(severity: Severity = Severity.SEVERE): HostileStream {
        val depthSeconds = timeShiftBufferDepthSeconds(severity)
        return dashLiveStream(
            id = "dash-short-time-shift-buffer-depth",
            validity = HostileStream.Validity.VALID_BUT_HOSTILE,
            spec = "ISO/IEC 23009-1 §5.3.1.2",
            cause = "A packager configured for the lowest possible latency, or a value in seconds " +
                "written where the packager wanted minutes.",
            severity = severity,
            magnitude = "timeShiftBufferDepth of ${decimal(depthSeconds)} s, " +
                "against ${decimal(segmentDurationSeconds())} s segments",
            timeShiftBufferDepthSeconds = depthSeconds,
        )
    }

    // spec: ISO/IEC 23009-1 §5.3.1.2 — @timeShiftBufferDepth is optional, and when it is absent
    // "the time shift buffer is unlimited", i.e. every segment ever published is promised forever.
    // Valid, and untrue of every live origin: the promise the player plans against is one the CDN
    // will not keep, so a seek backwards fetches a 404 rather than media.
    //
    // Single severity: the attribute is absent, and an absent window has no size. How short a
    // *present* window may be is [dashShortTimeShiftBufferDepth]'s grading, not this entry's.
    public fun dashMissingTimeShiftBufferDepth(): HostileStream = dashLiveStream(
        id = "dash-missing-time-shift-buffer-depth",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "ISO/IEC 23009-1 §5.3.1.2",
        cause = "A live MPD produced by a VOD packaging template, which has no reason to emit the " +
            "attribute at all.",
        timeShiftBufferDepthSeconds = null,
    )

    // spec: ISO/IEC 23009-1 §5.3.2 — a Period is the unit across which the set of Representations
    // may change, and §5.3.9.2 @presentationTimeOffset is what maps each Period's segments onto its
    // own timeline. So a second Period offering a ladder the first did not is entirely conforming —
    // and is the mid-stream ladder change that makes a player rebuild its track selection, drop its
    // buffer, and often visibly re-start.
    //
    // DASH only. HLS's equivalent is a discontinuity across a re-encode, which is
    // [hlsDiscontinuityWithoutTimeline]; a multivariant playlist's variant set cannot change
    // mid-stream, because a VOD multivariant playlist is fetched once.
    //
    // Graded by how far the ladder moves: how many times heavier the rung the second Period adds is
    // than the one the first Period had. The Period boundary itself is not what is graded. A player
    // re-selects tracks at every boundary whatever the new ladder holds, and multi-Period content is
    // ordinary DASH (§5.3.2), so that cost is paid at every level. What the magnitude adds is how
    // far the re-selection can jump. A new rung one [ladderStepFactor] `BENIGN` step away is a jump
    // an ordinary up-switch makes anyway, so the boundary costs no more than adaptation already
    // does, and a doctor must not flag it. At `BORDERLINE` the jump skips a rung the published
    // spacing would have put between them. At `SEVERE` it lands on a different ladder altogether.
    // The same three factors apply, for those reasons rather than for the link-use argument that
    // sets them for a static ladder.
    public fun dashMidStreamLadderChange(severity: Severity = Severity.SEVERE): HostileStream {
        val firstPeriodSegments = SEGMENT_COUNT / 2
        val factor = ladderStepFactor(severity)
        return dashStream(
            id = "dash-mid-stream-ladder-change",
            validity = HostileStream.Validity.VALID_BUT_HOSTILE,
            spec = "ISO/IEC 23009-1 §5.3.2, §5.3.9.2",
            cause = "A mid-roll ad break, or an encoder restarted mid-event onto a different " +
                "profile — the second Period is whatever was running when it came back.",
            severity = severity,
            magnitude = "second Period adds a rung $factor× the first Period's",
            // The new top rung is on its own heavier segments, for [dashLadderGap]'s reason: a ladder
            // change is the defect, and an overstated rung inside it would be a second one.
            extraFiles = highDashSegments(firstPeriodSegments until SEGMENT_COUNT, factor),
            periods = listOf(
                DashPeriod(
                    id = "0",
                    segmentIndices = 0 until firstPeriodSegments,
                    representations = listOf(DashRepresentation("0")),
                ),
                DashPeriod(
                    id = "1",
                    segmentIndices = firstPeriodSegments until SEGMENT_COUNT,
                    representations = listOf(
                        DashRepresentation("0"),
                        DashRepresentation(
                            "1",
                            SyntheticDashStream.DECLARED_BITRATE_BPS * factor,
                            segmentName = ::highDashSegmentName,
                        ),
                    ),
                ),
            ),
        )
    }

    // ------------------------------------------------------------------------------------------
    // Severities
    // ------------------------------------------------------------------------------------------
    //
    // One function per magnitude, returning the value each severity is generated at. The argument for
    // a value is at the branch that returns it.

    /**
     * How many times heavier a ladder's top rung is than the rung below it — in what it declares
     * *and* in the bytes of its segments, so the step is the only thing wrong rather than a step and
     * an overstated rung at once. Both protocols' ladder gaps use it, and so does the mid-stream
     * ladder change, whose magnitude is how far the rung it adds sits from the ladder it joins.
     *
     * What the step costs: a client whose throughput falls just short of the top rung can only
     * sustain the one below, so it uses about 1/factor of its link. That fraction is what the levels
     * grade.
     */
    private fun ladderStepFactor(severity: Severity): Int = when (severity) {
        // ref: Apple Technical Note TN2224, "Best Practices for Creating and Deploying HTTP Live
        // Streaming Media for Apple Devices" — "Adjacent bit rates should be a factor of 1.5 to 2
        // apart". The HLS Authoring Specification has since superseded the note and states no
        // spacing, but this is still the one published number for a ladder's step, and nothing in it
        // is specific to HLS. Two is its upper end: a ladder built to the guidance, using at least
        // half of any link between its rungs, which a doctor must not call a gap.
        Severity.BENIGN -> 2

        // One rung missing from a ladder built at that factor: two steps of two with nothing between,
        // so a client just short of the top rung uses a quarter of its link. Twice the guidance's
        // ceiling and exactly what trimming one rung produces, so one reasonable doctor calls it a
        // gap and another a sparse ladder.
        Severity.BORDERLINE -> 4

        // 128 kbps to about 6 Mbps with nothing between: a client just short of the top rung uses a
        // fiftieth of its link. The value [all] has always carried.
        Severity.SEVERE -> 48
    }

    private fun ladderStepMagnitude(factor: Int): String = "top rung $factor× the one below it"

    /**
     * What an overstated rung declares, against the ~128 kbps its segments really carry. Both good
     * streams declare that same bitrate — they describe deliberately equivalent media — so one
     * function serves both protocols.
     */
    private fun overstatedBitrateBps(severity: Severity): Int = when (severity) {
        // ref: HLS Authoring Specification for Apple devices, item 1.27 — "For VOD content, the
        // measured peak bit rate MUST be within 10% of the BANDWIDTH attribute". Five percent over is
        // inside that tolerance, on either protocol: the rounding up a packager does when it writes a
        // measured rate, which a doctor must not flag.
        Severity.BENIGN -> SyntheticHlsStream.DECLARED_BITRATE_BPS * 105 / 100

        // ref: Media3 `AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION` (0.7) — a selector keeps
        // back 30% of its throughput estimate as margin, so it picks a rung only on a link at least
        // 1/0.7 times what the rung declares. Overstating by that same 1/0.7 doubles the margin: the
        // rung is refused on every link up to about twice its real rate rather than 1.43 times.
        // Outside item 1.27's tolerance, so a doctor holding content to that document flags it; a
        // margin doubled rather than exceeded, so a doctor judging by what it costs a selector may not.
        Severity.BORDERLINE -> SyntheticHlsStream.DECLARED_BITRATE_BPS * 10 / 7

        // Thirty-one times the real rate, which is what an encoder ladder templated from the wrong
        // profile produces. The value [all] has always carried.
        Severity.SEVERE -> 4_000_000
    }

    private fun overstatedBitrateMagnitude(declaredBps: Int): String =
        "declares ${decimal(declaredBps.toDouble() / SyntheticHlsStream.DECLARED_BITRATE_BPS)}× its real bitrate"

    /**
     * The four segments' real durations, against a good stream whose segments are all two seconds.
     * Every level adds up to a stream short enough to end inside half the corpus test's budget.
     */
    private fun raggedSegmentDurationsSeconds(severity: Severity): List<Double> = when (severity) {
        // ref: DASH-IF IOP v3.0 §3.2.1 — "The maximum tolerance of segment duration shall be ±50% and
        // the maximum accumulated deviation over multiple segments shall be ±50% of the signaled
        // segment duration". RFC 8216 names no tolerance, so this is the one published bound on how
        // ragged segmentation may be, and an HLS packager can meet it as easily as a DASH one. ±20%
        // around the nominal two seconds is well inside it — keyframes placed at scene cuts rather
        // than on a fixed cadence — and a doctor must not flag it.
        Severity.BENIGN -> listOf(2.0, 1.6, 2.4, 2.0)

        // At that tolerance's edge in both directions: one segment half the nominal and the next half
        // as long again, so the accumulated deviation reaches -50% and returns to zero. Within the
        // letter of the tolerance, and a threefold spread between neighbours, so a doctor may
        // reasonably flag it or not.
        Severity.BORDERLINE -> listOf(2.0, 1.0, 3.0, 2.0)

        // Half a second to ten: twenty-fold, far past any tolerance. The value [all] has always
        // carried.
        Severity.SEVERE -> listOf(0.5, 10.0, 1.0, 6.0)
    }

    /**
     * How long after the stream's real start its `availabilityStartTime` says it began.
     *
     * Late rather than early, because late is the direction this corpus can show: every segment the
     * template names exists as bytes, so an anchor that promises segments too soon costs nothing
     * observable here, while one that promises them too late holds the player back. A client
     * computes its live edge from the anchor, so a skew of s puts it s further behind live than the
     * origin asked — which is what the levels grade.
     */
    private fun availabilityStartTimeSkewMs(severity: Severity): Long = when (severity) {
        // ref: DASH-IF IOP v3.0 §3.5 — servers and clients "should synchronize their clocks to a
        // globally accurate time standard", with NTP (RFC 5905) as the example, and no accuracy is
        // stated. So this value is derived rather than cited. A client can only place the live edge
        // to a whole segment: segment N becomes available at the anchor plus N+1 durations (ISO/IEC
        // 23009-1 §5.3.9.5.3). A skew shorter than a segment therefore moves the computed edge by at
        // most one segment, and only for skew/duration of each segment period. At half a second that
        // is a quarter of the time, the client never reaches a segment the origin lacks, and the
        // latency added is smaller than the edge's own one-segment granularity. That is noise a doctor
        // must not flag.
        Severity.BENIGN -> 500

        // One segment, which is also this stream's @suggestedPresentationDelay: the player sits twice
        // as far behind live as the origin asked, with no error to say so. Whether doubled latency is
        // a defect or a tolerance is exactly the threshold question.
        Severity.BORDERLINE -> (SUGGESTED_PRESENTATION_DELAY_SECONDS * 1_000).toLong()

        // An hour, which is what a clock set to the wrong timezone offset looks like. The value [all]
        // has always carried.
        Severity.SEVERE -> 60L * 60 * 1_000
    }

    /** A live entry's `@timeShiftBufferDepth`, against segments two seconds long. */
    private fun timeShiftBufferDepthSeconds(severity: Severity): Double = when (severity) {
        // ref: RFC 8216 §6.2.2 — a live HLS server "MUST NOT remove a Media Segment from a Playlist
        // file without an EXT-X-ENDLIST tag if that would produce a Playlist whose duration is less
        // than three times the target duration". DASH states no floor — DASH-IF IOP v3.0 §4.3.2.2.3
        // only recommends that the attribute be present — so HLS's is the one published number for
        // how short a live window may be. Four segments clears it: a short, low-latency window that a
        // doctor must not flag.
        Severity.BENIGN -> 4 * segmentDurationSeconds()

        // One segment: the first depth that is not shorter than a segment, and below RFC 8216's floor.
        // Not yet shorter, but already too short: a segment is fetchable only once complete
        // (ISO/IEC 23009-1 §5.3.9.5.3), so a playhead starting it is a whole segment behind the edge —
        // at the window's far end only if the fetch took no time. Measured, it sat outside the window
        // just as `SEVERE` did (issue #67), so this is the level where the cliff is, not a judgement.
        Severity.BORDERLINE -> segmentDurationSeconds()

        // Half a second, shorter than one segment: by the time a segment has been fetched the
        // manifest no longer promises it. The value [all] has always carried.
        Severity.SEVERE -> 0.5
    }

    /**
     * [value] to at most two decimal places and with no trailing zeros — `1.43`, `2`, `3600` — for a
     * magnitude a report prints.
     */
    private fun decimal(value: Double): String =
        BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    // ------------------------------------------------------------------------------------------
    // Building an entry
    // ------------------------------------------------------------------------------------------

    /**
     * One HLS entry: the good stream's segments, under the playlists given.
     *
     * [media] defaults to a media playlist that is itself exactly the good stream's, so a pathology
     * that lives entirely in the multivariant playlist says nothing about the media one. [segments]
     * likewise defaults to the good stream's own; [extraFiles] is anything else an entry serves — a
     * second rung's playlist and segments.
     */
    private fun hlsStream(
        id: String,
        validity: HostileStream.Validity,
        spec: String,
        cause: String,
        multivariant: String,
        severity: Severity = Severity.SEVERE,
        magnitude: String? = null,
        media: String = hlsMediaPlaylist(goodHlsDurations()),
        segments: Map<String, ByteArray> = goodHlsSegments(),
        extraFiles: Map<String, ByteArray> = emptyMap(),
        durationMs: Long = SyntheticHlsStream.durationMs(SEGMENT_COUNT),
        declaredResponseHeaders: (String) -> Map<String, String> = { emptyMap() },
    ): HostileStream {
        val files = buildMap {
            put(MULTIVARIANT_PLAYLIST_NAME, multivariant.toByteArray())
            put(SyntheticHlsStream.MEDIA_PLAYLIST_NAME, media.toByteArray())
            putAll(segments)
            putAll(extraFiles)
        }
        return hostileStream(
            id, HostileStream.Protocol.HLS, validity, spec, cause, severity, magnitude,
            sourceName = MULTIVARIANT_PLAYLIST_NAME,
            durationMs = durationMs,
            files = files,
            declaredResponseHeaders = declaredResponseHeaders,
        )
    }

    /**
     * One DASH entry: the good stream's initialization and media segments, under the MPD described.
     *
     * The single-period form — [representations] — is what almost every entry needs; [periods] is
     * for the one that is *about* periods. Passing both is a programming error rather than a
     * composition.
     */
    private fun dashStream(
        id: String,
        validity: HostileStream.Validity,
        spec: String,
        cause: String,
        severity: Severity = Severity.SEVERE,
        magnitude: String? = null,
        representations: List<DashRepresentation>? = null,
        periods: List<DashPeriod>? = null,
        live: Boolean = false,
        availabilityStartOffsetMs: Long = 0,
        timeShiftBufferDepthSeconds: Double? = null,
        segmentCount: Int = SEGMENT_COUNT,
        extraFiles: Map<String, ByteArray> = emptyMap(),
    ): HostileStream {
        require((representations == null) != (periods == null)) {
            "A DASH entry is described by its representations or by its periods, not both"
        }
        val allPeriods = periods
            ?: listOf(DashPeriod("0", 0 until segmentCount, representations!!))

        val files = buildMap {
            put(
                MANIFEST_NAME,
                dashManifest(
                    allPeriods,
                    live,
                    availabilityStartOffsetMs,
                    timeShiftBufferDepthSeconds,
                ).toByteArray(),
            )
            put(SyntheticDashStream.INITIALIZATION_NAME, SyntheticDashStream.initializationSegment())
            repeat(segmentCount) { index ->
                put(SyntheticDashStream.segmentName(index), SyntheticDashStream.mediaSegment(index))
            }
            putAll(extraFiles)
        }
        return hostileStream(
            id,
            HostileStream.Protocol.DASH,
            validity,
            spec,
            cause,
            severity,
            magnitude,
            sourceName = MANIFEST_NAME,
            durationMs = SyntheticDashStream.durationMs(segmentCount),
            files = files,
        )
    }

    /**
     * A live entry: a window that keeps producing media for as long as a test watches it.
     *
     * Two things separate it from the on-demand entries, and both exist so that an entry records its
     * own pathology rather than the harness's limits. It carries [LIVE_SEGMENT_COUNT] segments
     * rather than [SEGMENT_COUNT], because a live session that runs out of published media falls off
     * the end of its own window and fails for a reason that has nothing to do with the defect under
     * test. And it starts [LIVE_HEAD_START_SECONDS] ago rather than a whole stream ago, so the live
     * edge is near the beginning and advances, through the manifest's `UTCTiming`, with the clock
     * the harness is moving.
     */
    private fun dashLiveStream(
        id: String,
        validity: HostileStream.Validity,
        spec: String,
        cause: String,
        timeShiftBufferDepthSeconds: Double?,
        severity: Severity = Severity.SEVERE,
        magnitude: String? = null,
        availabilityStartOffsetMs: Long = LIVE_HEAD_START_OFFSET_MS,
    ): HostileStream = dashStream(
        id = id,
        validity = validity,
        spec = spec,
        cause = cause,
        severity = severity,
        magnitude = magnitude,
        live = true,
        availabilityStartOffsetMs = availabilityStartOffsetMs,
        timeShiftBufferDepthSeconds = timeShiftBufferDepthSeconds,
        segmentCount = LIVE_SEGMENT_COUNT,
        representations = listOf(DashRepresentation("0")),
    )

    /**
     * The last step both protocols share: put the stream under its own directory, so that a
     * `FakeDataSet` holding the whole corpus at once has no two entries colliding on a URI. The
     * directory is the id *and* the severity, `hostile/<id>/<severity>/`, because [graded] serves
     * three levels of one pathology side by side.
     *
     * Every reference inside every manifest here is relative, which is what makes that a rekeying
     * rather than a rewrite — the same property that lets [SyntheticHlsStream] serve identical bytes
     * from a `fake:` URI and from a directory on disk.
     */
    private fun hostileStream(
        id: String,
        protocol: HostileStream.Protocol,
        validity: HostileStream.Validity,
        spec: String,
        cause: String,
        severity: Severity,
        magnitude: String?,
        sourceName: String,
        durationMs: Long,
        files: Map<String, ByteArray>,
        declaredResponseHeaders: (String) -> Map<String, String> = { emptyMap() },
    ): HostileStream {
        val base = BASE_URI + id + "/" + severity.name.lowercase() + "/"
        return HostileStream(
            id = id,
            protocol = protocol,
            validity = validity,
            severity = severity,
            magnitude = magnitude,
            spec = spec,
            cause = cause,
            sourceUri = base + sourceName,
            durationMs = durationMs,
            declaredResponseHeaders = files.keys
                .associate { name -> (base + name) to declaredResponseHeaders(name) }
                .filterValues { it.isNotEmpty() },
            files = files.mapKeys { (name, _) -> base + name },
        )
    }

    // ------------------------------------------------------------------------------------------
    // HLS playlist writing
    // ------------------------------------------------------------------------------------------

    /** One `EXT-X-STREAM-INF` and the playlist it names. */
    private class HlsVariant(
        val bandwidthBps: Int = SyntheticHlsStream.DECLARED_BITRATE_BPS,
        val codecs: String? = SyntheticHlsStream.DECLARED_CODECS,
        val audioGroupId: String? = null,
        val playlist: String = SyntheticHlsStream.MEDIA_PLAYLIST_NAME,
    )

    /** One `EXT-X-MEDIA` audio rendition, pointing at the same media playlist as everything else. */
    private class HlsAudioRendition(val groupId: String)

    /**
     * spec: RFC 8216 §4.3.4 — a multivariant playlist is `#EXTM3U`, then its `EXT-X-MEDIA` tags,
     * then each `EXT-X-STREAM-INF` followed on the next line by the URI of its media playlist.
     *
     * Built by joining lines rather than as an indented raw string, for the reason
     * [SyntheticHlsStream]'s own playlist writer gives: a playlist whose lines are indented is not a
     * playlist any parser will accept.
     */
    private fun hlsMultivariantPlaylist(
        variants: List<HlsVariant>,
        renditions: List<HlsAudioRendition> = emptyList(),
    ): String {
        val media = renditions.map { rendition ->
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"${rendition.groupId}\",NAME=\"Main\"," +
                "DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"2\"," +
                "URI=\"${SyntheticHlsStream.MEDIA_PLAYLIST_NAME}\""
        }
        val streams = variants.flatMap { variant ->
            val attributes = buildList {
                add("BANDWIDTH=${variant.bandwidthBps}")
                variant.codecs?.let { add("CODECS=\"$it\"") }
                variant.audioGroupId?.let { add("AUDIO=\"$it\"") }
            }
            listOf(
                "#EXT-X-STREAM-INF:${attributes.joinToString(separator = ",")}",
                variant.playlist,
            )
        }
        return (listOf("#EXTM3U") + media + streams).joinToString(separator = "\n")
    }

    /**
     * spec: RFC 8216 §4.3.3 — a media playlist. `EXT-X-TARGETDURATION` is the maximum `EXTINF`
     * rounded up, as §4.3.3.1 requires even when the durations are ragged; `EXT-X-ENDLIST` is what
     * makes it finite, and [live] is what leaves it off.
     *
     * [discontinuityBefore] names the segment indices an `EXT-X-DISCONTINUITY` precedes;
     * [segmentName] is how the playlist names segment n, which only a second rung changes.
     */
    private fun hlsMediaPlaylist(
        durationsSeconds: List<Double>,
        discontinuityBefore: Set<Int> = emptySet(),
        live: Boolean = false,
        segmentName: (Int) -> String = SyntheticHlsStream::segmentName,
    ): String {
        val header = listOf(
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:${ceil(durationsSeconds.max()).toInt()}",
            "#EXT-X-MEDIA-SEQUENCE:0",
        )
        val segments = durationsSeconds.flatMapIndexed { index, duration ->
            buildList {
                if (index in discontinuityBefore) add("#EXT-X-DISCONTINUITY")
                add("#EXTINF:$duration,")
                add(segmentName(index))
            }
        }
        val tail = if (live) emptyList() else listOf("#EXT-X-ENDLIST")
        return (header + segments + tail).joinToString(separator = "\n")
    }

    // ------------------------------------------------------------------------------------------
    // MPD writing
    // ------------------------------------------------------------------------------------------

    /** One `Representation`, over whichever of the good stream's segments its Period carries. */
    private class DashRepresentation(
        val id: String,
        val bandwidthBps: Int = SyntheticDashStream.DECLARED_BITRATE_BPS,
        val codecs: String? = SyntheticDashStream.DECLARED_CODECS,
        /** How this representation's `SegmentList` names segment n; only a second rung changes it. */
        val segmentName: (Int) -> String = SyntheticDashStream::segmentName,
    )

    /** One `Period`, and the half-open range of the good stream's segments it presents. */
    private class DashPeriod(
        val id: String,
        val segmentIndices: IntRange,
        val representations: List<DashRepresentation>,
    )

    /**
     * spec: ISO/IEC 23009-1 §5.3 — an MPD, static or dynamic, with one AdaptationSet per Period.
     * Segments are addressed by `SegmentList` when static and by `SegmentTemplate` when live, and
     * [liveSegmentTemplate] says why the live half cannot use a list.
     *
     * Joined lines rather than an indented raw string for its other reason: an XML declaration
     * preceded by whitespace is not a document any parser will accept.
     */
    private fun dashManifest(
        periods: List<DashPeriod>,
        live: Boolean,
        availabilityStartOffsetMs: Long,
        timeShiftBufferDepthSeconds: Double?,
    ): String {
        val now = System.currentTimeMillis()
        val attributes = buildList {
            add("xmlns=\"urn:mpeg:dash:schema:mpd:2011\"")
            // spec: ISO/IEC 23009-1 §8.4 (ISOBMFF live profile) for a dynamic MPD addressed by
            // SegmentTemplate, §8.5 (ISOBMFF main profile) for a static one addressed by
            // SegmentList — the latter being what the good stream declares too. Never the on-demand
            // profile: §8.3 requires @type="static" and indexed self-initialising segments, so
            // claiming it would make an entry malformed for a reason that is not its pathology.
            add("profiles=\"urn:mpeg:dash:profile:${if (live) "isoff-live" else "isoff-main"}:2011\"")
            add("type=\"${if (live) "dynamic" else "static"}\"")
            add("minBufferTime=\"PT1S\"")
            if (live) {
                add("availabilityStartTime=\"${utcTime(now + availabilityStartOffsetMs)}\"")
                add("publishTime=\"${utcTime(now)}\"")
                // spec: ISO/IEC 23009-1 §5.3.1.2 — without @minimumUpdatePeriod a dynamic MPD is
                // never reloaded, which would make every live pathology here a one-shot read.
                add("minimumUpdatePeriod=\"PT2S\"")
                // spec: ISO/IEC 23009-1 §5.3.1.2 — @suggestedPresentationDelay is how far behind
                // the live edge the origin asks a client to play. Without it Media3 falls back to
                // thirty seconds, which on a window this short is *before* the stream begins: every
                // live entry here would then record "the harness cannot play a short live window"
                // rather than its own pathology.
                add("suggestedPresentationDelay=\"${SyntheticDashStream.xsDuration(SUGGESTED_PRESENTATION_DELAY_SECONDS)}\"")
                timeShiftBufferDepthSeconds?.let { add("timeShiftBufferDepth=\"${SyntheticDashStream.xsDuration(it)}\"") }
            } else {
                // spec: ISO/IEC 23009-1 §5.3.1.2 — @mediaPresentationDuration is required for a
                // static MPD whose last Period has no @duration.
                val seconds = periods.sumOf { it.segmentIndices.count() } * segmentDurationSeconds()
                add("mediaPresentationDuration=\"${SyntheticDashStream.xsDuration(seconds)}\"")
            }
        }

        val open = "<MPD " + attributes.joinToString(separator = "\n     ") + ">"
        val body = periods.flatMap { period -> dashPeriod(period, live) } + utcTiming(live, now)
        return (
            listOf("<?xml version=\"1.0\" encoding=\"utf-8\"?>", open) + body + "</MPD>"
            ).joinToString(separator = "\n")
    }

    /**
     * spec: ISO/IEC 23009-1 §5.8.4.11 — a `UTCTiming` descriptor, and the `urn:mpeg:dash:utc:direct`
     * scheme, whose `@value` *is* the server's current time. Placed after the Periods, which is
     * where the MPD schema's sequence puts it.
     *
     * DASH-IF IOP §4.7 requires one on every dynamic MPD, and Media3 is why it is not optional here:
     * a dynamic manifest with no `UTCTiming` sends it to an NTP server for the time, which under
     * Robolectric is a network call that never resolves — so the live half of this corpus would
     * record "no clock" rather than its own pathology.
     *
     * It also makes the skew in [dashAvailabilityStartTimeSkew] exact: the manifest states the time
     * it was published at, and its `availabilityStartTime` disagrees with it by a known amount,
     * instead of by however far the test host's clock happens to be from the media.
     */
    private fun utcTiming(live: Boolean, nowMillis: Long): List<String> = if (!live) {
        emptyList()
    } else {
        listOf(
            "  <UTCTiming schemeIdUri=\"urn:mpeg:dash:utc:direct:2014\" " +
                "value=\"${utcTime(nowMillis)}\"/>",
        )
    }

    /**
     * spec: ISO/IEC 23009-1 §5.3.2 — a Period, with @start where it does not begin at zero and
     * @duration where the MPD is static.
     */
    private fun dashPeriod(period: DashPeriod, live: Boolean): List<String> {
        val startSeconds = period.segmentIndices.first * segmentDurationSeconds()
        val attributes = buildList {
            add("id=\"${period.id}\"")
            add("start=\"${SyntheticDashStream.xsDuration(startSeconds)}\"")
            if (!live) {
                add("duration=\"${SyntheticDashStream.xsDuration(period.segmentIndices.count() * segmentDurationSeconds())}\"")
            }
        }
        return listOf("  <Period ${attributes.joinToString(separator = " ")}>") +
            "    <AdaptationSet mimeType=\"audio/mp4\" segmentAlignment=\"true\">" +
            period.representations.flatMap { dashRepresentation(it, period.segmentIndices, live) } +
            "    </AdaptationSet>" +
            "  </Period>"
    }

    /**
     * spec: ISO/IEC 23009-1 §5.3.5 for the Representation, §5.3.9.3 for `SegmentList`, and §5.3.9.2
     * for `@presentationTimeOffset` — which is what maps a later Period's segments, whose decode
     * times run from the start of the whole stream, onto that Period's own timeline.
     */
    private fun dashRepresentation(
        representation: DashRepresentation,
        segmentIndices: IntRange,
        live: Boolean,
    ): List<String> {
        val attributes = buildList {
            add("id=\"${representation.id}\"")
            add("bandwidth=\"${representation.bandwidthBps}\"")
            representation.codecs?.let { add("codecs=\"$it\"") }
            add("audioSamplingRate=\"${SyntheticDashStream.DECLARED_SAMPLE_RATE_HZ}\"")
        }
        val offset = segmentIndices.first * SyntheticDashStream.SEGMENT_DURATION_IN_TIMESCALE
        return listOf("      <Representation ${attributes.joinToString(separator = " ")}>") +
            "        <AudioChannelConfiguration" +
            "            schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\"" +
            "            value=\"${SyntheticDashStream.DECLARED_CHANNEL_COUNT}\"/>" +
            (if (live) liveSegmentTemplate() else segmentList(segmentIndices, offset, representation.segmentName)) +
            "      </Representation>"
    }

    /** spec: ISO/IEC 23009-1 §5.3.9.3 — every segment named, which is what an on-demand MPD does. */
    private fun segmentList(
        segmentIndices: IntRange,
        presentationTimeOffset: Int,
        segmentName: (Int) -> String,
    ): List<String> =
        listOf(
            "        <SegmentList timescale=\"${SyntheticDashStream.TIMESCALE}\" " +
                "duration=\"${SyntheticDashStream.SEGMENT_DURATION_IN_TIMESCALE}\" " +
                "presentationTimeOffset=\"$presentationTimeOffset\">",
            "          <Initialization sourceURL=\"${SyntheticDashStream.INITIALIZATION_NAME}\"/>",
        ) +
            segmentIndices.map {
                "          <SegmentURL media=\"${segmentName(it)}\"/>"
            } +
            "        </SegmentList>"

    /**
     * spec: ISO/IEC 23009-1 §5.3.9.4 — a `SegmentTemplate` with `$Number$` and a constant @duration,
     * and §5.3.9.5.3 — which is what makes a segment's availability a function of the clock.
     *
     * Live entries need it rather than a `SegmentList`, and not for style: a list names a fixed set
     * of segments, so a player treats all of them as published whatever the time, and neither
     * `availabilityStartTime` nor `timeShiftBufferDepth` — the two things the live entries are
     * *about* — would decide anything. A template's segment N is available from
     * `availabilityStartTime` plus N+1 segment durations, which is the live edge DASH-IF IOP §4.3
     * describes and the one a static set of bytes can still simulate: the manifest never has to
     * change for the edge to move. `startNumber` 0 makes `$Number$` the good stream's own segment
     * index, so the names resolve to [SyntheticDashStream]'s segments unchanged.
     */
    private fun liveSegmentTemplate(): List<String> = listOf(
        "        <SegmentTemplate timescale=\"${SyntheticDashStream.TIMESCALE}\" " +
            "duration=\"${SyntheticDashStream.SEGMENT_DURATION_IN_TIMESCALE}\" startNumber=\"0\" " +
            "initialization=\"${SyntheticDashStream.INITIALIZATION_NAME}\" " +
            "media=\"${SyntheticDashStream.SEGMENT_NAME_TEMPLATE}\"/>",
    )

    private fun segmentDurationSeconds(): Double =
        SyntheticDashStream.SEGMENT_DURATION_IN_TIMESCALE.toDouble() / SyntheticDashStream.TIMESCALE

    /**
     * spec: ISO/IEC 23009-1 §5.3.1.2 — `@availabilityStartTime` and `@publishTime` are `xs:dateTime`
     * values, and DASH-IF IOP §4.3 requires them in UTC.
     *
     * `SimpleDateFormat` rather than `java.time`, which needs API 26 and this build's `minSdk` is 24.
     */
    private fun utcTime(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    /** The good stream's own segment durations: two seconds, [SEGMENT_COUNT] times. */
    private fun goodHlsDurations(): List<Double> =
        List(SEGMENT_COUNT) { SyntheticHlsStream.SEGMENT_DURATION_SECONDS }

    /** The good stream's own segments, byte for byte. */
    private fun goodHlsSegments(): Map<String, ByteArray> = (0 until SEGMENT_COUNT)
        .associate { SyntheticHlsStream.segmentName(it) to SyntheticHlsStream.adtsSegment(it) }

    /** A gapped ladder's top rung, named apart from the good stream's so both can be served. */
    private fun highHlsSegmentName(index: Int) = "segment-high$index${SyntheticHlsStream.SEGMENT_SUFFIX}"

    private fun highDashSegmentName(index: Int) = "segment-high$index.m4s"

    /** [indices] of the good DASH stream, each [factor] times heavier. */
    private fun highDashSegments(indices: IntRange, factor: Int): Map<String, ByteArray> = indices.associate {
        highDashSegmentName(it) to SyntheticDashStream.mediaSegment(it, sizeScale = factor)
    }

    private const val MULTIVARIANT_PLAYLIST_NAME = "master.m3u8"

    /** A gapped ladder's top rung's media playlist. */
    private const val HIGH_MEDIA_PLAYLIST_NAME = "media-high.m3u8"

    /** Where [hlsDiscontinuityWithoutTimeline]'s splice falls: half-way, so both sides have media. */
    private const val SPLICE_INDEX = SEGMENT_COUNT / 2

    /** `.mpd` is load-bearing: Media3 infers the content type from the URI's extension. */
    private const val MANIFEST_NAME = "manifest.mpd"

    /** `mp4a.40.5` — HE-AAC. RFC 6381 §3.3, and deliberately not what the segments carry. */
    private const val HIGH_EFFICIENCY_AAC_CODECS = "mp4a.40.5"

    private const val AUDIO_GROUP_ID = "audio-aac"

    /** A minute: long enough that the window is not itself the pathology. */
    private const val TIME_SHIFT_BUFFER_DEPTH_SECONDS = 60.0

    /** One segment behind the live edge — the shortest delay this stream's window can honour. */
    private const val SUGGESTED_PRESENTATION_DELAY_SECONDS = 2.0

    /**
     * Two minutes of published media for a live entry: comfortably longer than any observation of
     * one, so a session never runs out of window for a reason unrelated to its pathology.
     */
    private const val LIVE_SEGMENT_COUNT = 60

    /** How long a healthy live entry has been on air when a test starts watching it. */
    private const val LIVE_HEAD_START_SECONDS = 4.0

    /** [LIVE_HEAD_START_SECONDS] as the `availabilityStartTime` offset from now that expresses it. */
    private const val LIVE_HEAD_START_OFFSET_MS = (-LIVE_HEAD_START_SECONDS * 1_000).toLong()
}
