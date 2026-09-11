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
 * protocols is generated for both, and severity is one argument away in the same builders — though
 * no entry varies it yet.
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
 * the moment they are generated: an `availabilityStartTime` an hour in the future has to be an hour
 * in the future from *now*, not from whenever this class happened to load.
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
     * Wildly over the ~128 kbps the segments actually carry, which is the point: a factor of thirty
     * is what an encoder ladder templated from the wrong profile produces.
     */
    private const val OVERSTATED_BITRATE_BPS = 4_000_000

    /**
     * How much heavier a gapped ladder's top rung is than its bottom, in what it declares *and* in
     * the bytes of its segments — so the gap is the only thing wrong with it, rather than a gap and an
     * overstated rung at once.
     */
    private const val LADDER_GAP_FACTOR = 48

    /**
     * About 6 Mbps over the good streams' 128 kbps, with nothing between. Both good streams declare
     * the same bitrate — they describe deliberately equivalent media — so one constant serves both.
     */
    private const val LADDER_TOP_BITRATE_BPS = SyntheticHlsStream.DECLARED_BITRATE_BPS * LADDER_GAP_FACTOR

    /** The whole corpus, in protocol order. A test iterates this; nothing may be left out of it. */
    public fun all(): List<HostileStream> = listOf(
        hlsLadderGap(),
        hlsOverstatedBitrate(),
        hlsMissingCodecs(),
        hlsAudioGroupCodecMismatch(),
        hlsDanglingAudioGroup(),
        hlsInconsistentSegmentDurations(),
        hlsDiscontinuityWithoutTimeline(),
        hlsCachedLivePlaylist(),
        dashLadderGap(),
        dashOverstatedBitrate(),
        dashMissingCodecs(),
        dashAvailabilityStartTimeSkew(),
        dashShortTimeShiftBufferDepth(),
        dashMissingTimeShiftBufferDepth(),
        dashMidStreamLadderChange(),
    )

    // ------------------------------------------------------------------------------------------
    // HLS
    // ------------------------------------------------------------------------------------------

    // spec: RFC 8216 §4.3.4.2 — BANDWIDTH "represents the peak segment bit rate of the Variant
    // Stream", and nothing in the RFC constrains how far apart two variants' rates may be. A ladder
    // of 128 kbps and 6 Mbps with nothing between is therefore a legal multivariant playlist and an
    // unplayable one on any connection in between: the selector has a rung it wastes and a rung it
    // cannot sustain. The top rung has its own playlist and its own segments, forty-eight times the
    // size of the bottom's, matching the ratio the two declare — so the gap is the only thing wrong.
    public fun hlsLadderGap(): HostileStream = hlsStream(
        id = "hls-ladder-gap",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "RFC 8216 §4.3.4.2",
        cause = "A ladder whose middle rungs were dropped to save encoding cost, or a profile " +
            "template that was only ever tested on wifi and on 3G.",
        multivariant = hlsMultivariantPlaylist(
            listOf(
                HlsVariant(SyntheticHlsStream.DECLARED_BITRATE_BPS),
                HlsVariant(LADDER_TOP_BITRATE_BPS, playlist = HIGH_MEDIA_PLAYLIST_NAME),
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
                        sizeScale = LADDER_GAP_FACTOR,
                    ),
                )
            }
        },
    )

    // spec: RFC 8216 §4.3.4.2 — BANDWIDTH "represents the peak segment bit rate of the Variant
    // Stream". A value thirty times the real rate is therefore false, but no MUST requires it to be
    // accurate and no parser can tell without measuring segments, so the playlist is valid to the
    // letter and a lie in fact. Every ABR estimator compares its throughput estimate against this
    // number, so a rung declared at thirty times its real bitrate is one the player will refuse on a
    // connection that would carry it easily.
    public fun hlsOverstatedBitrate(): HostileStream = hlsStream(
        id = "hls-overstated-bitrate",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "RFC 8216 §4.3.4.2",
        cause = "A packager that writes the encoder's configured *peak* rate rather than the " +
            "measured one, or a ladder whose bitrates were copied from a different mezzanine.",
        multivariant = hlsMultivariantPlaylist(listOf(HlsVariant(OVERSTATED_BITRATE_BPS))),
    )

    // spec: RFC 8216 §4.3.4.2 — "Every EXT-X-STREAM-INF tag SHOULD include a CODECS attribute". A
    // SHOULD, so its absence is valid; what it costs is that the player cannot know whether it can
    // decode a rendition until it has fetched a segment of it, which turns a capability check into
    // a download and a fallback into a stall.
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
    // be less than or equal to the target duration", which holds with a target of ten. Nothing
    // requires segments to be of similar length. The segments really are 0.5, 10, 1 and 6 seconds
    // long, generated by the good stream's own writer, so the playlist tells the truth and the
    // hostility is the raggedness alone: a buffer target counted in segments means anything from
    // half a second to ten seconds of media per segment.
    public fun hlsInconsistentSegmentDurations(): HostileStream = hlsStream(
        id = "hls-inconsistent-segment-durations",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "RFC 8216 §4.3.2.1, §4.3.3.1",
        cause = "Segmentation driven by scene-change keyframes rather than by a fixed GOP, or an " +
            "ad-insertion pass that split one segment and left its neighbours alone.",
        multivariant = hlsMultivariantPlaylist(listOf(HlsVariant())),
        media = hlsMediaPlaylist(RAGGED_SEGMENT_DURATIONS_SECONDS),
        segments = RAGGED_SEGMENT_DURATIONS_SECONDS.runningFold(0.0) { start, duration -> start + duration }
            .zip(RAGGED_SEGMENT_DURATIONS_SECONDS)
            .mapIndexed { index, (start, duration) ->
                SyntheticHlsStream.segmentName(index) to SyntheticHlsStream.adtsSegment(start, duration)
            }
            .toMap(),
        durationMs = (RAGGED_SEGMENT_DURATIONS_SECONDS.sum() * 1_000).toLong(),
    ).also {
        // The playlist names one segment per duration, so the two have to agree: a ragged list
        // shorter than the stream would quietly test a shorter stream instead.
        check(RAGGED_SEGMENT_DURATIONS_SECONDS.size == SEGMENT_COUNT) {
            "A ragged playlist needs one duration per segment"
        }
    }

    // spec: RFC 8216 §4.3.2.3 — "The EXT-X-DISCONTINUITY tag MUST be present if there is a change in
    // any of the following characteristics: file format; number, type, and identifiers of tracks;
    // timestamp sequence." The segments after the splice restart their timestamps at zero, as a
    // stitched-in ad does, so the tag is required and present: the playlist is valid. The hostility
    // is the reset itself, which the player has to splice across; the tags that would help it place
    // the new timeline — EXT-X-PROGRAM-DATE-TIME (§4.3.2.6) and EXT-X-DISCONTINUITY-SEQUENCE
    // (§4.3.3.3, which in its absence "SHALL be considered to be 0") — are optional and absent, so it
    // has only the sum of the EXTINF durations to do it with.
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
    // rather than applied, because Media3's `FakeDataSource` reports none — see
    // `HostileStream.declaredResponseHeaders` and `docs/testing.md`. What the bytes *do* reproduce
    // is the consequence: a live media playlist, no EXT-X-ENDLIST, whose content never changes
    // however often it is reloaded — which is exactly what a client behind that cache rule sees.
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
    // two-rung AdaptationSet with a factor of forty-eight between the rungs is therefore a conforming
    // MPD and the DASH half of [hlsLadderGap] — with the top rung, as there, on its own segments
    // forty-eight times the size, so the gap is the only thing wrong.
    public fun dashLadderGap(): HostileStream = dashStream(
        id = "dash-ladder-gap",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "ISO/IEC 23009-1 §5.3.5.2; DASH-IF IOP §3.2.4",
        cause = "The same dropped middle rungs as its HLS counterpart, usually because the two " +
            "manifests are generated from one ladder definition by one packager.",
        representations = listOf(
            DashRepresentation("0", SyntheticDashStream.DECLARED_BITRATE_BPS),
            DashRepresentation("1", LADDER_TOP_BITRATE_BPS, segmentName = ::highDashSegmentName),
        ),
        extraFiles = highDashSegments(0 until SEGMENT_COUNT),
    )

    // spec: ISO/IEC 23009-1 §5.3.5.2 — @bandwidth is defined against a hypothetical constant-rate
    // channel that delivers each segment in time, so a value above the real rate still satisfies it.
    // The cost is the same as in HLS: every bandwidth-based selection is made against a number that
    // is thirty times the truth.
    public fun dashOverstatedBitrate(): HostileStream = dashStream(
        id = "dash-overstated-bitrate",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "ISO/IEC 23009-1 §5.3.5.2",
        cause = "The encoder's configured peak written through to @bandwidth, unchanged, by a " +
            "packager that never measured a segment.",
        representations = listOf(DashRepresentation("0", OVERSTATED_BITRATE_BPS)),
    )

    // spec: ISO/IEC 23009-1 §5.3.7.2 — @codecs is an optional common attribute, so an MPD without
    // it validates; DASH-IF IOP §3.2.4 requires it precisely because without it the player has to
    // fetch and sniff the initialization segment before it can decide whether it can play the
    // representation at all.
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
     * says so: its [HostileStream.validity] is [HostileStream.Validity.HEALTHY].
     */
    // spec: ISO/IEC 23009-1 §5.3.1.2 (a dynamic MPD with @availabilityStartTime, @minimumUpdatePeriod
    // and a minute of @timeShiftBufferDepth) and §5.8.4.11 (UTCTiming) — the conforming baseline.
    public fun dashLiveBaseline(): HostileStream = dashLiveStream(
        id = "dash-live-baseline",
        validity = HostileStream.Validity.HEALTHY,
        spec = "ISO/IEC 23009-1 §5.3.1.2, §5.8.4.11",
        cause = "None: this is the healthy stream the live entries are built from.",
        timeShiftBufferDepthSeconds = TIME_SHIFT_BUFFER_DEPTH_SECONDS,
    )

    // spec: ISO/IEC 23009-1 §5.3.1.2 — for @type="dynamic", @availabilityStartTime is the anchor
    // every segment's availability window is computed from, and §5.3.9.5.3 makes a segment's
    // availability start time that anchor plus its presentation time. Nothing requires the anchor
    // to agree with the time the same MPD publishes in its UTCTiming element, so an MPD that says
    // "it is now 12:00, and this stream starts at 13:00" conforms — and says, correctly, that
    // nothing is available yet, which a player reports as a stall with no error.
    public fun dashAvailabilityStartTimeSkew(): HostileStream = dashLiveStream(
        id = "dash-availability-start-time-skew",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "ISO/IEC 23009-1 §5.3.1.2, §5.3.9.5.3",
        cause = "A packager whose own clock or timezone offset is wrong: the MPD publishes a " +
            "correct UTCTiming and an availabilityStartTime an hour ahead of it, so the origin " +
            "is telling the client, consistently, that nothing has been published yet.",
        availabilityStartOffsetMs = CLOCK_SKEW_MS,
        timeShiftBufferDepthSeconds = TIME_SHIFT_BUFFER_DEPTH_SECONDS,
    )

    // spec: ISO/IEC 23009-1 §5.3.1.2 — @timeShiftBufferDepth is the guaranteed availability window
    // for any segment, and any positive duration conforms. Half a second is shorter than one
    // segment, so by the time a segment has been fetched the manifest no longer promises it: a
    // resume, a seek back, or a slow network drops the client off the end of its own window.
    public fun dashShortTimeShiftBufferDepth(): HostileStream = dashLiveStream(
        id = "dash-short-time-shift-buffer-depth",
        validity = HostileStream.Validity.VALID_BUT_HOSTILE,
        spec = "ISO/IEC 23009-1 §5.3.1.2",
        cause = "A packager configured for the lowest possible latency, or a value in seconds " +
            "written where the packager wanted minutes.",
        timeShiftBufferDepthSeconds = SHORT_TIME_SHIFT_BUFFER_DEPTH_SECONDS,
    )

    // spec: ISO/IEC 23009-1 §5.3.1.2 — @timeShiftBufferDepth is optional, and when it is absent
    // "the time shift buffer is unlimited", i.e. every segment ever published is promised forever.
    // Valid, and untrue of every live origin: the promise the player plans against is one the CDN
    // will not keep, so a seek backwards fetches a 404 rather than media.
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
    public fun dashMidStreamLadderChange(): HostileStream {
        val firstPeriodSegments = SEGMENT_COUNT / 2
        return dashStream(
            id = "dash-mid-stream-ladder-change",
            validity = HostileStream.Validity.VALID_BUT_HOSTILE,
            spec = "ISO/IEC 23009-1 §5.3.2, §5.3.9.2",
            cause = "A mid-roll ad break, or an encoder restarted mid-event onto a different " +
                "profile — the second Period is whatever was running when it came back.",
            // The new top rung is on its own heavier segments, for [dashLadderGap]'s reason: a ladder
            // change is the defect, and an overstated rung inside it would be a second one.
            extraFiles = highDashSegments(firstPeriodSegments until SEGMENT_COUNT),
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
                        DashRepresentation("1", LADDER_TOP_BITRATE_BPS, segmentName = ::highDashSegmentName),
                    ),
                ),
            ),
        )
    }

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
            id, HostileStream.Protocol.HLS, validity, spec, cause,
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
        availabilityStartOffsetMs: Long = (-LIVE_HEAD_START_SECONDS * 1_000).toLong(),
    ): HostileStream = dashStream(
        id = id,
        validity = validity,
        spec = spec,
        cause = cause,
        live = true,
        availabilityStartOffsetMs = availabilityStartOffsetMs,
        timeShiftBufferDepthSeconds = timeShiftBufferDepthSeconds,
        segmentCount = LIVE_SEGMENT_COUNT,
        representations = listOf(DashRepresentation("0")),
    )

    /**
     * The last step both protocols share: put the stream under its own directory, so that a
     * `FakeDataSet` holding the whole corpus at once has no two entries colliding on a URI.
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
        sourceName: String,
        durationMs: Long,
        files: Map<String, ByteArray>,
        declaredResponseHeaders: (String) -> Map<String, String> = { emptyMap() },
    ): HostileStream {
        val base = BASE_URI + id + "/"
        return HostileStream(
            id = id,
            protocol = protocol,
            validity = validity,
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
            // SegmentList. Not the on-demand profile the good stream declares: §8.3 requires
            // @type="static" and indexed self-initialising segments, so claiming it here would make
            // every live entry malformed for a reason that is not its pathology.
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
     * it was published at, and its `availabilityStartTime` disagrees with it by a known hour,
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

    /** [indices] of the good DASH stream, each [LADDER_GAP_FACTOR] times heavier. */
    private fun highDashSegments(indices: IntRange): Map<String, ByteArray> = indices.associate {
        highDashSegmentName(it) to SyntheticDashStream.mediaSegment(it, sizeScale = LADDER_GAP_FACTOR)
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

    /** Half a second to ten, against a stream whose segments are all two. */
    private val RAGGED_SEGMENT_DURATIONS_SECONDS = listOf(0.5, 10.0, 1.0, 6.0)

    /** An hour, which is what a clock set to the wrong timezone offset looks like. */
    private const val CLOCK_SKEW_MS = 60L * 60 * 1_000

    /** A minute: long enough that the window is not itself the pathology. */
    private const val TIME_SHIFT_BUFFER_DEPTH_SECONDS = 60.0

    /** One segment behind the live edge — the shortest delay this stream's window can honour. */
    private const val SUGGESTED_PRESENTATION_DELAY_SECONDS = 2.0

    /** Shorter than one segment, which is the pathology in [dashShortTimeShiftBufferDepth]. */
    private const val SHORT_TIME_SHIFT_BUFFER_DEPTH_SECONDS = 0.5

    /**
     * Two minutes of published media for a live entry: comfortably longer than any observation of
     * one, so a session never runs out of window for a reason unrelated to its pathology.
     */
    private const val LIVE_SEGMENT_COUNT = 60

    /** How long a healthy live entry has been on air when a test starts watching it. */
    private const val LIVE_HEAD_START_SECONDS = 4.0
}
