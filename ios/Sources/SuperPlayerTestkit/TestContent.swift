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

import SuperPlayerTestMedia

/// What a ``LoopbackOrigin`` serves: one of `SuperPlayerTestMedia`'s streams, at fixed paths under
/// the origin's root, and — for live content — what has been published by a given moment.
///
/// The port of `superplayer-testkit`'s `TestContent`, for the two kinds this ticket's harness can
/// serve: ``hls(segmentCount:variantCount:)`` and ``liveHls()``. Android's *described* content,
/// which Media3's fakes synthesize without fetching anything, has no iOS form, because `AVPlayer`
/// plays only what it fetches (ADR-0021, *Context*); and DASH is not ported at all (ADR-0020).
///
/// The paths are the Android ones with the `fake://` host taken off: the on-demand stream at the
/// root, the live one under `live/`, so a test reading a path reads what the Kotlin test reads.
public struct TestContent: Sendable {

    /// Enough segments that a fault can be addressed past the first one and still play first — the
    /// Kotlin `DEFAULT_SEGMENT_COUNT`, for its reason, which #410's faults are what will need.
    public static let defaultSegmentCount = 4

    /// The path, under the origin's root, of what a player is pointed at.
    let sourcePath: String

    /// Whether the origin keeps publishing this content, which decides how a test waits on it: a
    /// live stream never reaches its end.
    public let isLive: Bool

    /// What exists once the origin has been serving for `elapsedMs` milliseconds, keyed by path
    /// under the origin's root.
    ///
    /// A function rather than a map for the reason the Kotlin `publication` is one: a live media
    /// playlist is *defined* by changing (RFC 8216 §6.2.1), so a live stream served from fixed bytes
    /// is a live stream that has stopped — a defect the hostile corpus carries — rather than a
    /// healthy one. On-demand content ignores its argument.
    let publication: @Sendable (_ elapsedMs: Int64) -> [String: [UInt8]]

    /// A real HLS stream: `SyntheticHlsStream`'s multivariant playlist, `variantCount` media
    /// playlists and `segmentCount` packed-audio segments, served at the origin's root — the port of
    /// the Kotlin `TestContent.hls`, and the bytes ADR-0021 rule 6 holds both platforms to.
    ///
    /// `segmentCount` is what decides which segments a fault can address (#410); `variantCount` is
    /// one unless the test is about a selection. The Kotlin `secondVariantHost` is not here: a second
    /// host is a second listener, which is #410's.
    public static func hls(segmentCount: Int = defaultSegmentCount, variantCount: Int = 1)
        -> TestContent
    {
        let files = SyntheticHlsStream.files(segmentCount: segmentCount, variantCount: variantCount)
        return TestContent(
            sourcePath: SyntheticHlsStream.multivariantPlaylistName, isLive: false,
            publication: { _ in files })
    }

    /// A live HLS stream whose origin keeps publishing: a new segment every
    /// `SyntheticHlsStream.segmentDurationMs` of real time from the moment the origin starts, listed
    /// in a sliding window of `SyntheticHlsStream.liveWindowSegmentCount`, so a player reloading its
    /// playlist sees a new version for every segment published (RFC 8216 §6.2.1). A segment that has
    /// slid out of the window is a 404, as it is from a real origin.
    ///
    /// The port of the Kotlin `TestContent.liveHls()`, and timed the same way — by how long the
    /// origin has been serving — on a different clock: the harness's own there, the host's real one
    /// here, because nothing advances `AVPlayer`'s by hand (ADR-0021 rule 4). The window starts full,
    /// as the Kotlin one does, so a player joining at once has the three target durations of hold-back
    /// RFC 8216 §6.3.3 asks for and media behind them. Undated, for the reason
    /// `SyntheticHlsStream.liveFiles(publishedSegmentCount:)` gives.
    public static func liveHls() -> TestContent {
        let window = SyntheticHlsStream.liveWindowSegmentCount
        return TestContent(
            sourcePath: liveDirectory + SyntheticHlsStream.multivariantPlaylistName, isLive: true,
            publication: { elapsedMs in
                let published = window + Int(elapsedMs / SyntheticHlsStream.segmentDurationMs)
                return SyntheticHlsStream.liveFiles(publishedSegmentCount: published)
                    .reduce(into: [:]) { paths, file in paths[liveDirectory + file.key] = file.value
                    }
            })
    }

    /// Where ``liveHls()`` is served: a directory of its own, as the Kotlin `LIVE_BASE_URI` is.
    static let liveDirectory = "live/"

    /// This content with `path` never served: a request for it is a 404 whatever has been published.
    ///
    /// Internal, and for the harness's own tests: it is the one fault a control needs before #410's
    /// `FaultScript` exists, which is where a test outside this module will say it.
    func withholding(_ path: String) -> TestContent {
        let publication = self.publication
        return TestContent(
            sourcePath: sourcePath, isLive: isLive,
            publication: { elapsedMs in publication(elapsedMs).filter { $0.key != path } })
    }
}
