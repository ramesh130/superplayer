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

/// Valid-but-hostile HLS: the playlists a real CDN really serves, which every parser accepts and
/// every player then has to survive.
///
/// The port of the **HLS half** of `superplayer-testmedia`'s `HostileManifests` (#412), and byte for
/// byte the same corpus: the Kotlin generator's HLS entries, every severity included, are committed
/// under `ios/Tests/Fixtures/hostile/` with an index of their fields and their declared headers;
/// Gradle's `check` holds the Kotlin generator to those files and `HostileManifestsFixturesTests`
/// holds this one to them, so the two platforms grade against one corpus (ADR-0021 rule 6). The
/// DASH entries are not ported, because DASH is not (ADR-0020), and nor is the DASH live baseline.
///
/// Every entry is ``SyntheticHlsStream`` **with one thing wrong**: where the defect is in a
/// playlist, the segments are the good stream's own, and where it is in the media's shape — segment
/// lengths, a timestamp reset, a rung's real bitrate — they come from the same writer with that one
/// property changed. Every pathology with a magnitude is generated at three ``HostileStream/Severity``
/// levels; a binary one once, at `severe`. **The argument for every value is the Kotlin source's**,
/// at the branch that returns it, with the `// spec:` or `// ref:` beside it; each is repeated here
/// in brief, so a reader of this file is not sent elsewhere to learn why a number is what it is.
///
/// **Time is an argument, not a reading.** The two token entries mint a token that expires a known
/// distance from *now*, and this module names no clock — not even Foundation, for
/// ``SyntheticHlsStream``'s reason — so a caller hands now in as `nowEpochSeconds`, seconds since
/// the epoch. The Kotlin generator reads the wall clock; its fixtures mint at a fixed instant the
/// index records, and so does the Swift test that reads them.
///
/// Nothing here asserts that anything handles these well: `SuperPlayerTestkit`'s
/// `HostileManifestCorpusTests` records what a stock `AVPlayer` does with each, as the Kotlin
/// `HostileManifestCorpusTest` records what SuperPlayer does.
public enum HostileManifests {

    /// How many media segments every entry carries: four, matching `TestContent.defaultSegmentCount`,
    /// because a mid-stream defect needs a stream with a middle — the Kotlin `SEGMENT_COUNT`.
    public static let segmentCount = 4

    /// Every HLS pathology at every severity it has, in the Kotlin `graded()`'s order: a pathology
    /// with a magnitude three times, mildest first, and a binary one once, at `severe`.
    public static func graded(nowEpochSeconds: Int64) -> [HostileStream] {
        [
            Severity.allCases.map { hlsLadderGap($0) },
            Severity.allCases.map { hlsOverstatedBitrate($0) },
            [hlsMissingCodecs()],
            [hlsAudioGroupCodecMismatch()],
            [hlsDanglingAudioGroup()],
            Severity.allCases.map { hlsInconsistentSegmentDurations($0) },
            [hlsDiscontinuityWithoutTimeline()],
            [hlsCachedLivePlaylist()],
            [hlsTokenScopedToManifest(nowEpochSeconds: nowEpochSeconds)],
            Severity.allCases.map {
                hlsTokenExpiringInWindow($0, nowEpochSeconds: nowEpochSeconds)
            },
            [hlsCorsRefusesCredentials()],
        ].flatMap { $0 }
    }

    /// The corpus at its unmistakable severity, one entry per pathology: ``graded(nowEpochSeconds:)``'s
    /// `severe` half, derived from it so the two cannot disagree about which pathologies exist.
    public static func all(nowEpochSeconds: Int64) -> [HostileStream] {
        graded(nowEpochSeconds: nowEpochSeconds).filter { $0.severity == .severe }
    }

    public typealias Severity = HostileStream.Severity

    // MARK: - The entries

    // spec: RFC 8216 §4.3.4.2 — BANDWIDTH "represents the peak segment bit rate of the Variant
    // Stream", and nothing in the RFC constrains how far apart two variants' rates may be. The top
    // rung has its own playlist and segments, as many times the size of the bottom's as the two
    // declare, so the gap is the only thing wrong. `ladderStepFactor` argues each severity's step.
    public static func hlsLadderGap(_ severity: Severity = .severe) -> HostileStream {
        let factor = ladderStepFactor(severity)
        var extraFiles = [
            highMediaPlaylistName: Array(
                mediaPlaylist(goodDurations, segmentName: highSegmentName).utf8)
        ]
        for index in 0..<segmentCount {
            extraFiles[highSegmentName(index)] = SyntheticHlsStream.adtsSegment(
                startSeconds: Double(index) * SyntheticHlsStream.segmentDurationSeconds,
                durationSeconds: SyntheticHlsStream.segmentDurationSeconds, sizeScale: factor)
        }
        return stream(
            id: "hls-ladder-gap", validity: .validButHostile, spec: "RFC 8216 §4.3.4.2",
            cause: "A ladder whose middle rungs were dropped to save encoding cost, or a profile "
                + "template that was only ever tested on wifi and on 3G.",
            multivariant: multivariantPlaylist([
                Variant(),
                Variant(
                    bandwidthBps: SyntheticHlsStream.declaredBitrateBps * factor,
                    playlist: highMediaPlaylistName),
            ]),
            severity: severity, magnitude: "top rung \(factor)× the one below it",
            extraFiles: extraFiles)
    }

    // spec: RFC 8216 §4.3.4.2 — BANDWIDTH "represents the peak segment bit rate of the Variant
    // Stream". A value above the real rate is false, but no MUST requires it to be accurate and no
    // parser can tell without measuring segments. `overstatedBitrateBps` argues each value.
    public static func hlsOverstatedBitrate(_ severity: Severity = .severe) -> HostileStream {
        let declaredBps = overstatedBitrateBps(severity)
        let ratio = Double(declaredBps) / Double(SyntheticHlsStream.declaredBitrateBps)
        return stream(
            id: "hls-overstated-bitrate", validity: .validButHostile, spec: "RFC 8216 §4.3.4.2",
            cause: "A packager that writes the encoder's configured *peak* rate rather than the "
                + "measured one, or a ladder whose bitrates were copied from a different mezzanine.",
            multivariant: multivariantPlaylist([Variant(bandwidthBps: declaredBps)]),
            severity: severity, magnitude: "declares \(decimal(ratio))× its real bitrate")
    }

    // spec: RFC 8216 §4.3.4.2 — "Every EXT-X-STREAM-INF tag SHOULD include a CODECS attribute". A
    // SHOULD, so its absence is valid. Single severity: the attribute is present or it is not.
    public static func hlsMissingCodecs() -> HostileStream {
        stream(
            id: "hls-missing-codecs", validity: .validButHostile, spec: "RFC 8216 §4.3.4.2",
            cause:
                "A hand-written or templated multivariant playlist — the CODECS string is the one "
                + "attribute a human cannot produce without reading RFC 6381, so it is the one left out.",
            multivariant: multivariantPlaylist([Variant(codecs: nil)]))
    }

    // spec: RFC 8216 §4.3.4.1, §4.3.4.2 — the variant declares HE-AAC (`mp4a.40.5`) over an
    // EXT-X-MEDIA rendition that carries AAC-LC, so the declared format is not present. That
    // contradicts the attribute's definition but breaks no MUST, so it is hostile rather than
    // malformed. Single severity: HE-AAC over AAC-LC is already the mildest mismatch there is.
    public static func hlsAudioGroupCodecMismatch() -> HostileStream {
        stream(
            id: "hls-audio-group-codec-mismatch", validity: .validButHostile,
            spec: "RFC 8216 §4.3.4.1, §4.3.4.2",
            cause: "An encoder ladder migrated from HE-AAC to AAC-LC without the manifest template "
                + "being updated — the audio group still advertises what last year's profile produced.",
            multivariant: multivariantPlaylist(
                [Variant(codecs: highEfficiencyAacCodecs, audioGroupId: audioGroupId)],
                renditions: [audioGroupId]))
    }

    // spec: RFC 8216 §4.3.4.2 — the AUDIO attribute's "value MUST match the value of the GROUP-ID
    // attribute of an EXT-X-MEDIA tag elsewhere in the Multivariant Playlist". There is no such tag,
    // so this one is **malformed**, and labelled so. Single severity: a reference resolves or not.
    public static func hlsDanglingAudioGroup() -> HostileStream {
        stream(
            id: "hls-dangling-audio-group", validity: .malformed, spec: "RFC 8216 §4.3.4.2",
            cause: "A packaging job whose audio rendition failed after the variant lines were "
                + "written, leaving the AUDIO reference behind with nothing to point at.",
            multivariant: multivariantPlaylist([Variant(audioGroupId: audioGroupId)]))
    }

    // spec: RFC 8216 §4.3.2.1 and §4.3.3.1 — EXTINF need only be accurate, and rounded must not
    // exceed the target duration, which holds because the target is the longest segment rounded
    // up; nothing requires segments of similar length. The segments really are as long as the
    // playlist says, so the hostility is the raggedness alone. `raggedDurationsSeconds` argues each
    // severity's spread.
    public static func hlsInconsistentSegmentDurations(_ severity: Severity = .severe)
        -> HostileStream
    {
        let durations = raggedDurationsSeconds(severity)
        // One duration per segment, or a ragged list shorter than the stream tests a shorter stream.
        precondition(
            durations.count == segmentCount, "A ragged playlist needs one duration per segment")
        var segments: [String: [UInt8]] = [:]
        var start = 0.0
        for (index, duration) in durations.enumerated() {
            segments[SyntheticHlsStream.segmentName(index)] = SyntheticHlsStream.adtsSegment(
                startSeconds: start, durationSeconds: duration)
            start += duration
        }
        let nominal = decimal(SyntheticHlsStream.segmentDurationSeconds)
        return stream(
            id: "hls-inconsistent-segment-durations", validity: .validButHostile,
            spec: "RFC 8216 §4.3.2.1, §4.3.3.1",
            cause:
                "Segmentation driven by scene-change keyframes rather than by a fixed GOP, or an "
                + "ad-insertion pass that split one segment and left its neighbours alone.",
            multivariant: multivariantPlaylist([Variant()]),
            severity: severity,
            magnitude: "segments \(decimal(durations.min()!)) s to \(decimal(durations.max()!)) s "
                + "long, against a nominal \(nominal) s",
            media: mediaPlaylist(durations), segments: segments,
            durationMs: Int64(durations.reduce(0, +) * 1_000))
    }

    // spec: RFC 8216 §4.3.2.3 — EXT-X-DISCONTINUITY "MUST be present if there is a change in any of
    // the following characteristics: … timestamp sequence." The segments after the splice restart
    // at zero, as a stitched-in ad does, so the tag is required and present; what is absent is
    // EXT-X-PROGRAM-DATE-TIME (§4.3.2.6) and EXT-X-DISCONTINUITY-SEQUENCE (§4.3.3.3), which would
    // place the new timeline. Single severity: the placing tags are present or they are absent.
    public static func hlsDiscontinuityWithoutTimeline() -> HostileStream {
        var segments: [String: [UInt8]] = [:]
        for index in 0..<segmentCount {
            // Timestamps run from zero again at the splice: that is the discontinuity.
            let indexOnItsTimeline = index < spliceIndex ? index : index - spliceIndex
            segments[SyntheticHlsStream.segmentName(index)] = SyntheticHlsStream.adtsSegment(
                startSeconds: Double(indexOnItsTimeline)
                    * SyntheticHlsStream.segmentDurationSeconds,
                durationSeconds: SyntheticHlsStream.segmentDurationSeconds)
        }
        return stream(
            id: "hls-discontinuity-without-timeline", validity: .validButHostile,
            spec: "RFC 8216 §4.3.2.3",
            cause: "Mid-roll ad insertion by a stitcher that marks the splice and emits neither a "
                + "discontinuity sequence nor a program date time — the common case for server-side ads.",
            multivariant: multivariantPlaylist([Variant()]),
            media: mediaPlaylist(goodDurations, discontinuityBefore: [spliceIndex]),
            segments: segments)
    }

    // spec: RFC 8216 §6.3.4 — a client MUST reload a playlist with no EXT-X-ENDLIST, and §6.2.1 a
    // server publish a newer one within 1.5 target durations, which a cache defeats — and RFC 9111
    // §5.2.2.1 (max-age) is what an intermediary obeys instead. The playlist is declared
    // `max-age=600` while its segments are `no-store`: backwards, and legal. The bytes reproduce the
    // consequence — a live playlist whose content never changes — and the headers are declared for
    // `SuperPlayerTestkit` to serve. Single severity, because the bytes cannot grade a max-age.
    public static func hlsCachedLivePlaylist() -> HostileStream {
        stream(
            id: "hls-cached-live-playlist", validity: .validButHostile,
            spec: "RFC 8216 §6.2.1, §6.3.4; RFC 9111 §5.2.2.1",
            cause: "One CDN cache rule applied to the whole path. The live media playlist is then "
                + "held for ten minutes while the segments are never held at all, so the client reloads "
                + "a playlist that cannot change and the stream appears to freeze about thirty seconds "
                + "in — a surprising share of \"live stream freezes\" tickets are this.",
            multivariant: multivariantPlaylist([Variant()]),
            media: mediaPlaylist(goodDurations, live: true),
            declaredResponseHeaders: { name in
                if name.hasSuffix(SyntheticHlsStream.mediaPlaylistName) {
                    ["Cache-Control": "public, max-age=600"]
                } else if name.hasSuffix(SyntheticHlsStream.segmentSuffix) {
                    ["Cache-Control": "no-store"]
                } else {
                    [:]
                }
            })
    }

    // ref: a signed URL is a CDN convention rather than a standard, stated from first principles
    // (`CONTRIBUTING.md`'s clean-room rules): the credential travels in the query (RFC 3986 §3.4),
    // and a relative reference resolves against the base's path without its query (§5.3), so
    // signing the entry URL alone leaves every segment addressed without a credential, which a CDN
    // refuses (RFC 9110 §15.5.4). Single severity: scope has none; the lifetime is graded next.
    public static func hlsTokenScopedToManifest(nowEpochSeconds: Int64) -> HostileStream {
        stream(
            id: "hls-token-scoped-to-manifest", validity: .validButHostile,
            spec: "RFC 3986 §3.4, §5.3; RFC 9110 §15.5.4",
            cause: "A signing step applied to the URL the app was handed and to nothing below it — "
                + "the manifest is signed, its segments are addressed relatively and reach the CDN with "
                + "no credential at all, so the stream opens and then serves nothing.",
            multivariant: multivariantPlaylist([Variant()]),
            sourceName: multivariantPlaylistName
                + signature(
                    lifetimeSeconds: longTokenLifetimeSeconds, nowEpochSeconds: nowEpochSeconds))
    }

    // ref: the same convention, and its other half: every URI carries the same token, so its scope
    // is right and only its lifetime is wrong. Graded in multiples of the content's own duration,
    // the one window the stream states; `tokenLifetimeSeconds` argues each level.
    public static func hlsTokenExpiringInWindow(
        _ severity: Severity = .severe, nowEpochSeconds: Int64
    ) -> HostileStream {
        let lifetimeSeconds = tokenLifetimeSeconds(severity)
        let token = signature(lifetimeSeconds: lifetimeSeconds, nowEpochSeconds: nowEpochSeconds)
        let segments = goodSegments().reduce(into: [String: [UInt8]]()) { signed, segment in
            signed[segment.key + token] = segment.value
        }
        return stream(
            id: "hls-token-expiring-in-window", validity: .validButHostile,
            spec: "RFC 3986 §3.4; RFC 9110 §15.5.4",
            cause: "A signing service whose lifetime was set from how long a request takes rather "
                + "than from how long a viewing lasts — every URL is signed, and the signature dies "
                + "part-way through the content, so playback stops where the token ran out.",
            multivariant: multivariantPlaylist([
                Variant(playlist: SyntheticHlsStream.mediaPlaylistName + token)
            ]),
            severity: severity,
            magnitude: "a token good for \(decimal(Double(lifetimeSeconds))) s over "
                + "\(decimal(contentDurationSeconds)) s of content",
            media: mediaPlaylist(goodDurations) { SyntheticHlsStream.segmentName($0) + token },
            segments: segments, sourceName: multivariantPlaylistName + token,
            mediaName: SyntheticHlsStream.mediaPlaylistName + token)
    }

    // spec: WHATWG Fetch §3.3.5, §4.10 — `Access-Control-Allow-Origin: *` fails the CORS check
    // whenever the request's credentials mode is "include", whatever
    // `Access-Control-Allow-Credentials` says, so the pair is a refusal by construction. Nothing
    // about the document is wrong and a native player notices nothing. Single severity: a CORS
    // configuration refuses the request or it does not.
    public static func hlsCorsRefusesCredentials() -> HostileStream {
        stream(
            id: "hls-cors-refuses-credentials", validity: .validButHostile,
            spec: "WHATWG Fetch §3.3.5, §4.10",
            cause: "An origin configured to \"allow everyone\" — a wildcard allowed origin emitted "
                + "beside allowed credentials — which is the one pair the CORS protocol refuses outright, "
                + "so every credentialed player of this stream is turned away and the native app never "
                + "notices.",
            multivariant: multivariantPlaylist([Variant()]),
            declaredResponseHeaders: { _ in
                [
                    "Access-Control-Allow-Origin": "*",
                    "Access-Control-Allow-Credentials": "true",
                ]
            })
    }

    // MARK: - Severities

    /// How many times heavier a ladder's top rung is than the rung below it, in what it declares
    /// and in its bytes. A client just short of the top rung uses about 1/factor of its link.
    private static func ladderStepFactor(_ severity: Severity) -> Int {
        switch severity {
        // ref: Apple Technical Note TN2224 — "Adjacent bit rates should be a factor of 1.5 to 2
        // apart". Two is its upper end: a ladder built to the guidance.
        case .benign: 2
        // One rung missing from a ladder built at that factor: a quarter of the link.
        case .borderline: 4
        // 128 kbps to about 6 Mbps with nothing between: a fiftieth of the link.
        case .severe: 48
        }
    }

    /// What an overstated rung declares, against the ~128 kbps its segments really carry.
    private static func overstatedBitrateBps(_ severity: Severity) -> Int {
        switch severity {
        // ref: HLS Authoring Specification for Apple devices, item 1.27 — the measured peak "MUST be
        // within 10% of the BANDWIDTH attribute". Five percent over is inside it.
        case .benign: SyntheticHlsStream.declaredBitrateBps * 105 / 100
        // ref: Media3 `AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION` (0.7): overstating by
        // 1/0.7 doubles a selector's margin. Outside item 1.27, and a margin doubled not exceeded.
        case .borderline: SyntheticHlsStream.declaredBitrateBps * 10 / 7
        // Thirty-one times the real rate: a ladder templated from the wrong profile.
        case .severe: 4_000_000
        }
    }

    /// The four segments' real durations, against a good stream whose segments are all two seconds.
    private static func raggedDurationsSeconds(_ severity: Severity) -> [Double] {
        switch severity {
        // ref: DASH-IF IOP v3.0 §3.2.1 — ±50% is the one published tolerance on segment duration,
        // and RFC 8216 names none. ±20% is well inside it.
        case .benign: [2.0, 1.6, 2.4, 2.0]
        // At that tolerance's edge in both directions, accumulating to -50% and back to zero.
        case .borderline: [2.0, 1.0, 3.0, 2.0]
        // Half a second to ten: twenty-fold, far past any tolerance.
        case .severe: [0.5, 10.0, 1.0, 6.0]
        }
    }

    /// How long ``hlsTokenExpiringInWindow(_:nowEpochSeconds:)``'s token lives, in multiples of the
    /// content's own duration — derived, because no document speaks to a signed URL's lifetime.
    private static func tokenLifetimeSeconds(_ severity: Severity) -> Int64 {
        switch severity {
        // A day: a token minted for a catalogue page and reused for playback, covering it many times.
        case .benign: longTokenLifetimeSeconds
        // One and a half viewings: a viewer who pauses for as long again outlives it.
        case .borderline: Int64(1.5 * contentDurationSeconds)
        // Half a viewing: the token dies in the middle of the content.
        case .severe: Int64(0.5 * contentDurationSeconds)
        }
    }

    // MARK: - Building an entry

    /// A day, the lifetime a signed URL is minted with where nothing has gone wrong.
    private static let longTokenLifetimeSeconds: Int64 = 24 * 60 * 60

    /// Opaque, and deliberately not a real signature: nothing in this corpus verifies one.
    private static let tokenSignature = "corpus"

    private static let multivariantPlaylistName = SyntheticHlsStream.multivariantPlaylistName

    /// A gapped ladder's top rung's media playlist.
    private static let highMediaPlaylistName = "media-high.m3u8"

    /// Where ``hlsDiscontinuityWithoutTimeline()``'s splice falls: half-way, so both sides have media.
    private static let spliceIndex = segmentCount / 2

    /// `mp4a.40.5` — HE-AAC. RFC 6381 §3.3, and deliberately not what the segments carry.
    private static let highEfficiencyAacCodecs = "mp4a.40.5"

    private static let audioGroupId = "audio-aac"

    /// Where the corpus is served, one directory per id and severity — the Kotlin `BASE_URI`'s path.
    private static let baseDirectory = "hostile/"

    /// How much media an entry carries, which is what a token has to cover.
    private static let contentDurationSeconds =
        Double(segmentCount) * SyntheticHlsStream.segmentDurationSeconds

    /// The good stream's own segment durations: two seconds, ``segmentCount`` times.
    private static let goodDurations = Array(
        repeating: SyntheticHlsStream.segmentDurationSeconds, count: segmentCount)

    /// The good stream's own segments, byte for byte.
    private static func goodSegments() -> [String: [UInt8]] {
        (0..<segmentCount).reduce(into: [:]) { segments, index in
            segments[SyntheticHlsStream.segmentName(index)] = SyntheticHlsStream.adtsSegment(index)
        }
    }

    /// A gapped ladder's top rung, named apart from the good stream's so both can be served.
    private static func highSegmentName(_ index: Int) -> String {
        "segment-high\(index)\(SyntheticHlsStream.segmentSuffix)"
    }

    /// ref: RFC 3986 §3.4 — a signed URL's query, `expires` in seconds since the epoch in the clear.
    private static func signature(lifetimeSeconds: Int64, nowEpochSeconds: Int64) -> String {
        "?expires=\(nowEpochSeconds + lifetimeSeconds)&signature=\(tokenSignature)"
    }

    /// `value` to at most two decimal places with no trailing zeros — `1.43`, `2`, `3600` — for a
    /// magnitude a report prints. The Kotlin `decimal` rounds the exact binary value half up through
    /// `BigDecimal`, which this module has no counterpart of; scaling by a hundred first rounds once
    /// more, which agrees with it for every value this corpus prints — none is within a rounding
    /// error of a half-hundredth — and the fixtures are what check that.
    private static func decimal(_ value: Double) -> String {
        let hundredths = Int64((value * 100).rounded(.toNearestOrAwayFromZero))
        let whole = hundredths / 100
        let fraction = hundredths % 100
        if fraction == 0 { return "\(whole)" }
        if fraction % 10 == 0 { return "\(whole).\(fraction / 10)" }
        return "\(whole).\(fraction < 10 ? "0" : "")\(fraction)"
    }

    /// One entry: the good stream's segments under the playlists given, put under its own
    /// directory, `hostile/<id>/<severity>/`, so the whole graded corpus is served by one origin with
    /// no two entries on one path. Every reference inside every playlist is relative, which is what
    /// makes that a rekeying rather than a rewrite.
    ///
    /// `sourceName` and `mediaName` are what the playlists are *published at*, which only the token
    /// entries change.
    private static func stream(
        id: String, validity: HostileStream.Validity, spec: String, cause: String,
        multivariant: String, severity: Severity = .severe, magnitude: String? = nil,
        media: String? = nil, segments: [String: [UInt8]]? = nil,
        extraFiles: [String: [UInt8]] = [:],
        durationMs: Int64 = SyntheticHlsStream.durationMs(segmentCount: segmentCount),
        declaredResponseHeaders: (String) -> [String: String] = { _ in [:] },
        sourceName: String = multivariantPlaylistName,
        mediaName: String = SyntheticHlsStream.mediaPlaylistName
    ) -> HostileStream {
        var files = segments ?? goodSegments()
        files[sourceName] = Array(multivariant.utf8)
        files[mediaName] = Array((media ?? mediaPlaylist(goodDurations)).utf8)
        files.merge(extraFiles) { _, extra in extra }

        let base = baseDirectory + id + "/" + severity.rawValue + "/"
        var headers: [String: [String: String]] = [:]
        for name in files.keys {
            let declared = declaredResponseHeaders(name)
            if !declared.isEmpty { headers[base + name] = declared }
        }
        return HostileStream(
            id: id, validity: validity, severity: severity, magnitude: magnitude, spec: spec,
            cause: cause, sourcePath: base + sourceName, durationMs: durationMs,
            declaredResponseHeaders: headers,
            files: files.reduce(into: [:]) { keyed, file in keyed[base + file.key] = file.value })
    }

    /// One `EXT-X-STREAM-INF` and the playlist it names.
    private struct Variant {
        var bandwidthBps = SyntheticHlsStream.declaredBitrateBps
        var codecs: String? = SyntheticHlsStream.declaredCodecs
        var audioGroupId: String?
        var playlist = SyntheticHlsStream.mediaPlaylistName
    }

    /// spec: RFC 8216 §4.3.4 — `#EXTM3U`, then its `EXT-X-MEDIA` tags, then each
    /// `EXT-X-STREAM-INF` followed by the URI of its media playlist. Each audio rendition, named by
    /// group id, points at the same media playlist as everything else.
    private static func multivariantPlaylist(_ variants: [Variant], renditions: [String] = [])
        -> String
    {
        let media = renditions.map { groupId in
            "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"\(groupId)\",NAME=\"Main\","
                + "DEFAULT=YES,AUTOSELECT=YES,CHANNELS=\"2\","
                + "URI=\"\(SyntheticHlsStream.mediaPlaylistName)\""
        }
        let streams = variants.flatMap { variant in
            let attributes =
                ["BANDWIDTH=\(variant.bandwidthBps)"]
                + (variant.codecs.map { ["CODECS=\"\($0)\""] } ?? [])
                + (variant.audioGroupId.map { ["AUDIO=\"\($0)\""] } ?? [])
            return ["#EXT-X-STREAM-INF:" + attributes.joined(separator: ","), variant.playlist]
        }
        return (["#EXTM3U"] + media + streams).joined(separator: "\n")
    }

    /// spec: RFC 8216 §4.3.3 — a media playlist: `EXT-X-TARGETDURATION` the longest `EXTINF`
    /// rounded up (§4.3.3.1), `EXT-X-ENDLIST` what makes it finite and `live` what leaves it off.
    /// Each `EXTINF` is the duration as Swift prints a `Double`, which is as Kotlin prints one for
    /// every duration here — `2.0`, `0.5`, `1.6` — and the fixtures are what hold that.
    private static func mediaPlaylist(
        _ durationsSeconds: [Double], discontinuityBefore: Set<Int> = [], live: Bool = false,
        segmentName: (Int) -> String = SyntheticHlsStream.segmentName
    ) -> String {
        let header = [
            "#EXTM3U",
            "#EXT-X-VERSION:3",
            "#EXT-X-TARGETDURATION:\(Int(durationsSeconds.max()!.rounded(.up)))",
            "#EXT-X-MEDIA-SEQUENCE:0",
        ]
        let segments = durationsSeconds.enumerated().flatMap { index, duration in
            (discontinuityBefore.contains(index) ? ["#EXT-X-DISCONTINUITY"] : [])
                + ["#EXTINF:\(duration),", segmentName(index)]
        }
        return (header + segments + (live ? [] : ["#EXT-X-ENDLIST"])).joined(separator: "\n")
    }
}
