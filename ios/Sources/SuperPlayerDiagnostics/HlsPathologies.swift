// Copyright 2026 The SuperPlayer Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import Foundation

/// What an HLS playlist can get wrong, one rule per defect, over the lint's reading of it — the
/// Kotlin `HlsPathologies`, rule for rule, with the live-window rule that core holds on Android
/// moved in beside them (`docs/ios-plan.md` §3.1).
///
/// The rules are pure: they fetch nothing and hold nothing, so `ManifestExamination` owns the
/// fetching and this file the judgement, and each rule can be read against its clause without
/// reading a transport. **Every threshold is the Kotlin one**, at a constant carrying the source
/// or the derivation the Kotlin constant carries — a published bound where a document states one,
/// a derivation where none does, each chosen to sit above what conforming content produces, because
/// a doctor is scored on its false positives as heavily as on its misses (ADR-0015 rule 12).
///
/// **No HLS defect is ``FindingSeverity/blocking`` on the strength of a magnitude.** Two are
/// blocking, and for what the document is rather than for how large a number is: the dangling
/// audio group, because it is malformed, and the short live window, because no conforming client
/// has a segment to start at.
enum HlsPathologies {

    /// What a multivariant playlist declares about its ladder, its codecs and its groups.
    static func inMultivariant(_ playlist: MultivariantPlaylist) -> [Finding] {
        [
            LadderPathologies.missingCodecs(playlist.variants),
            LadderPathologies.gap(playlist.variants),
            LadderPathologies.overstatedBitrate(playlist.variants),
            danglingAudioGroup(playlist),
        ].compactMap { $0 }
    }

    /// What one media playlist declares about its own segments and, when live, its window.
    static func inMediaPlaylist(_ playlist: MediaPlaylist) -> [Finding] {
        [
            raggedSegmentDurations(playlist),
            discontinuityWithoutTimeline(playlist),
            shortLiveWindow(playlist),
        ].compactMap { $0 }
    }

    /// What each audio group declares, against the media its own playlist names. `mediaPlaylists`
    /// is what the examination managed to read, keyed by URL; a group whose playlist never arrived
    /// is the fetch's finding and not this rule's.
    static func inAudioGroups(
        _ playlist: MultivariantPlaylist, mediaPlaylists: [URL: MediaPlaylist]
    ) -> [Finding] {
        playlist.audioRenditions.compactMap { rendition in
            audioGroupCodecMismatch(
                declared: playlist.audioCodecs(ofGroup: rendition.groupId),
                itsPlaylist: rendition.url.flatMap { mediaPlaylists[$0] })
        }
    }

    /// An audio group whose declared codec is not a format the media it names can carry.
    ///
    /// The group's only codec declaration is the referencing variant's `CODECS` (§4.3.4.1 gives
    /// `EXT-X-MEDIA` none), so what is compared is that declaration against **the container the
    /// group's segments are in**, read off each segment URI's path as Media3 reads it to choose an
    /// extractor — and as the lint must, since it fetches no segment (ADR-0015 rule 7). ADTS can
    /// signal four AAC object types and no more, so a group declared as HE-AAC (`mp4a.40.5`) or
    /// HE-AACv2 (`mp4a.40.29`, RFC 6381 §3.3) over ADTS names a format those segments cannot be.
    ///
    /// ``FindingSeverity/degraded``: a player that configures its output from the declaration does
    /// so for media it will not receive, while one that reads the media plays on. No magnitude.
    private static func audioGroupCodecMismatch(declared: String?, itsPlaylist: MediaPlaylist?)
        -> Finding?
    {
        guard let objectType = declared.flatMap(LadderPathologies.aacObjectType),
            objectType > highestAacObjectTypeAdtsCanSignal
        else { return nil }
        let segments = itsPlaylist?.segments ?? []
        guard !segments.isEmpty, segments.allSatisfy({ isAdts($0.url) }) else { return nil }
        return Finding(pathology: .hlsAudioGroupCodecMismatch, severity: .degraded, magnitude: nil)
    }

    /// A variant whose `AUDIO` attribute names a group the playlist does not carry.
    ///
    /// spec: RFC 8216 §4.3.4.2 — the attribute's value "MUST match the value of the GROUP-ID
    /// attribute of an EXT-X-MEDIA tag elsewhere in the Multivariant Playlist". A MUST, so the
    /// document is malformed: ``FindingSeverity/blocking``, the one HLS document finding that is,
    /// because a conformant client is entitled to reject the whole playlist.
    ///
    /// **A group carried with no `URI` is not dangling**, and one such group is what the playlist is
    /// given the benefit of the doubt over — the Kotlin rule's reading of Media3's, which folds a
    /// muxed rendition into `muxedAudioFormat` and keeps no group id for it, so exactly one
    /// unresolved reference may be that group's and a second cannot be.
    private static func danglingAudioGroup(_ playlist: MultivariantPlaylist) -> Finding? {
        let present = Set(playlist.audioRenditions.map(\.groupId))
        let referenced = Set(playlist.variants.compactMap(\.audioGroupId))
        let unresolved = referenced.subtracting(present).count
        let allowed = playlist.hasMuxedAudio ? 1 : 0
        guard unresolved > allowed else { return nil }
        return Finding(pathology: .hlsDanglingAudioGroup, severity: .blocking, magnitude: nil)
    }

    /// Segments whose lengths are further apart than segmentation with any published tolerance
    /// produces.
    ///
    /// The **shortest against the longest**, rather than either against `EXT-X-TARGETDURATION`:
    /// §4.3.3.1 fixes the target as the longest `EXTINF` rounded up, so comparing the two would grade
    /// a defect against itself. The severities grade what the spread costs a buffer: a cushion sized
    /// for the short segments is `n` times too small for a long one.
    private static func raggedSegmentDurations(_ playlist: MediaPlaylist) -> Finding? {
        let durations = playlist.segments.compactMap(\.durationSeconds).filter { $0 > 0 }
        guard durations.count >= 2, let shortest = durations.min(), let longest = durations.max()
        else { return nil }
        let spread = longest / shortest
        guard spread > segmentSpreadWithinTolerance else { return nil }
        return Finding(
            pathology: .hlsInconsistentSegmentDurations,
            severity: spread > segmentSpreadExhaustingACushion ? .degraded : .advisory,
            magnitude: "segments \(Magnitudes.seconds(shortest)) to \(Magnitudes.seconds(longest)) "
                + "long, a \(Magnitudes.decimal(spread))× spread")
    }

    /// A splice with nothing to place the timeline it starts.
    ///
    /// spec: RFC 8216 §4.3.2.3 — the `EXT-X-DISCONTINUITY` is required where the timestamps change,
    /// so the defect is what is missing beside it: `EXT-X-PROGRAM-DATE-TIME` (§4.3.2.6) and
    /// `EXT-X-DISCONTINUITY-SEQUENCE` (§4.3.3.3), both optional. An absent sequence and one written
    /// as zero are one document to a client, because §4.3.3.3 says the absent one "SHALL be
    /// considered to be 0", so the rule does not try to tell them apart.
    ///
    /// ``FindingSeverity/degraded``: the splice plays, and a seek or a live edge across it is placed
    /// by accumulated durations alone. No magnitude.
    private static func discontinuityWithoutTimeline(_ playlist: MediaPlaylist) -> Finding? {
        guard playlist.segments.contains(where: { $0.discontinuitiesBefore > 0 }) else {
            return nil
        }
        guard !playlist.hasProgramDateTime, playlist.discontinuitySequence == 0 else { return nil }
        return Finding(
            pathology: .hlsDiscontinuityWithoutTimeline, severity: .degraded, magnitude: nil)
    }

    /// A live playlist whose window is shorter than three target durations — the HLS analog of
    /// `LiveWindowDepthCheck`, which judges a DASH `timeShiftBufferDepth` in core on Android and has
    /// no Apple counterpart, because iOS core reads no playlist (`docs/ios-plan.md` §3.1). Held in
    /// the doctor alone, once (ADR-0015 rule 6's #396 addendum).
    ///
    /// spec: RFC 8216 §6.2.2 — "The server MUST NOT remove a Media Segment from a Playlist file
    /// without an EXT-X-ENDLIST tag if that would produce a Playlist whose duration is less than
    /// three times the target duration"; and §6.3.3, which is what the bound protects: a client
    /// "SHOULD NOT choose a segment that starts less than three target durations from the end of the
    /// Playlist file". A shorter window has no segment a client may start at — no playhead fits
    /// inside it, which is exactly the Kotlin judgement's "too short".
    ///
    /// **Read only where the server has removed something**, which in one fetched playlist is an
    /// `EXT-X-MEDIA-SEQUENCE` above zero (§4.3.3.2 numbers the first segment listed). §6.2.2 binds
    /// *removal*, so a stream that has only just begun — or an `EVENT` playlist, which never removes
    /// — is shorter than the bound without breaking it, and flagging every live stream in its first
    /// seconds is the false positive ADR-0015 rule 12 scores as heavily as a miss. An `EVENT`
    /// playlist is passed over by its type too (§4.3.3.5: segments are only ever appended), whatever
    /// sequence it starts at. What this leaves out is stated rather than hidden: an origin that
    /// numbers its first segment above zero and is asked about in its first seconds reads as having
    /// removed segments. A window with a segment whose `EXTINF` the lint could not read is not
    /// judged at all, since summing the rest would shrink it toward a finding.
    ///
    /// ``FindingSeverity/blocking`` and **ungraded**, as the Kotlin finding is: the bound is a yes
    /// or a no, and grading it would be a second threshold. The magnitude is the window against the
    /// bound, in the words the Kotlin one uses for depth against lag.
    private static func shortLiveWindow(_ playlist: MediaPlaylist) -> Finding? {
        guard playlist.isLive, playlist.playlistType != "EVENT", playlist.mediaSequence > 0,
            let target = playlist.targetDurationSeconds, target > 0
        else { return nil }
        let durations = playlist.segments.compactMap(\.durationSeconds)
        guard durations.count == playlist.segments.count else { return nil }
        let window = durations.reduce(0, +)
        let bound = Double(liveWindowTargetDurations * target)
        guard window < bound else { return nil }
        return Finding(
            pathology: .hlsShortLiveWindow, severity: .blocking,
            magnitude: "a window of \(Magnitudes.seconds(window)), against the "
                + "\(Magnitudes.seconds(bound)) of \(liveWindowTargetDurations) target durations")
    }

    /// Whether `url` names an ADTS file, read off its path as Media3 reads it.
    private static func isAdts(_ url: URL) -> Bool { url.path.hasSuffix(adtsSegmentSuffix) }

    // spec: ISO/IEC 14496-3 — the ADTS fixed header's `profile` field is two bits holding
    // `audioObjectType - 1`, so ADTS can signal object types 1 to 4 and nothing above. The Kotlin
    // `HIGHEST_AAC_OBJECT_TYPE_ADTS_CAN_SIGNAL`.
    static let highestAacObjectTypeAdtsCanSignal = 4

    // ref: Media3's `DefaultHlsExtractorFactory`, which picks an HLS segment's extractor from the
    // extension of the segment URI's *path*. AVFoundation publishes no such rule, so the Kotlin one
    // is kept: a lint that read the container differently from the Android doctor would score the
    // same corpus entry two ways. The Kotlin `ADTS_SEGMENT_SUFFIX`.
    static let adtsSegmentSuffix = ".aac"

    // ref: DASH-IF Interoperability Points v3.0 §3.2.1 — "The maximum tolerance of segment duration
    // shall be ±50% ... of the signaled segment duration". RFC 8216 names no tolerance, so this is
    // the one published bound; applied to a spread, the widest conforming pair is half nominal
    // beside one and a half, a factor of three. The Kotlin `SEGMENT_SPREAD_WITHIN_TOLERANCE`.
    static let segmentSpreadWithinTolerance = 3.0

    // Derived: an order of magnitude is where a cushion stops covering the playlist — a buffer that
    // holds ten of the short segments holds one of the long ones. The Kotlin
    // `SEGMENT_SPREAD_EXHAUSTING_A_CUSHION`.
    static let segmentSpreadExhaustingACushion = 10.0

    // spec: RFC 8216 §6.2.2 and §6.3.3 — three target durations, the RFC's own number on both the
    // server's side and the client's. Published, not derived, and the one threshold here with no
    // Kotlin twin, since the Kotlin rule is DASH's and compares depth against availability lag.
    static let liveWindowTargetDurations = 3
}
