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
/// the origin's root, on one host or several, and — for live content — what has been published by a
/// given moment.
///
/// The port of `superplayer-testkit`'s `TestContent`, for the two kinds this harness can serve:
/// ``hls(segmentCount:variantCount:hosts:)`` and ``liveHls(hosts:)``. Android's *described* content,
/// which Media3's fakes synthesize without fetching anything, has no iOS form, because `AVPlayer`
/// plays only what it fetches (ADR-0021, *Context*); and DASH is not ported at all (ADR-0020).
///
/// The paths are the Android ones with the `fake://` host taken off: the on-demand stream at the
/// root, the live one under `live/`, so a test reading a path reads what the Kotlin test reads.
///
/// **Several hosts are several listeners of one origin** (ADR-0021 rule 2: a second listener is a
/// second host). Every host serves the same files from the same publication clock — a live window
/// is the same window on each at the same moment, which is what a CDN's two edges serve — and a
/// ``FaultScript`` tells them apart by number. The Kotlin `secondVariantHost` and `mirrorHost` put
/// one rendition, or one `BaseURL`, on a host of its own; the HLS form of that on iOS is the
/// redundant stream, below, because DASH is not ported and `AVPlayer` fails over between redundant
/// variants natively.
public struct TestContent: Sendable {

    /// Enough segments that a fault can be addressed past the first one and still play first — the
    /// Kotlin `DEFAULT_SEGMENT_COUNT`, for its reason.
    public static let defaultSegmentCount = 4

    /// The path, under the origin's root, of what a player is pointed at.
    let sourcePath: String

    /// Whether the origin keeps publishing this content, which decides how a test waits on it: a
    /// live stream never reaches its end.
    public let isLive: Bool

    /// How many hosts serve it — the listeners its origin opens.
    public let hosts: Int

    /// What exists once the origin has been serving for `elapsedMs` milliseconds, keyed by path
    /// under the origin's root, given the port of each of its hosts in order.
    ///
    /// A function rather than a map for the reason the Kotlin `publication` is one: a live media
    /// playlist is *defined* by changing (RFC 8216 §6.2.1), so a live stream served from fixed bytes
    /// is a live stream that has stopped. On-demand content ignores the time; content on one host
    /// ignores the ports.
    let publication: @Sendable (_ elapsedMs: Int64, _ ports: [UInt16]) -> [String: [UInt8]]

    /// A real HLS stream: `SyntheticHlsStream`'s multivariant playlist, `variantCount` media
    /// playlists and `segmentCount` packed-audio segments, served at the origin's root — the port of
    /// the Kotlin `TestContent.hls`, and the bytes ADR-0021 rule 6 holds both platforms to.
    ///
    /// `hosts` above one serves it from that many listeners and lists every variant once per host
    /// in the multivariant playlist — the first host's as the generator writes it, the others' as
    /// absolute URLs naming their ports, each a redundant stream of the one before. That is what
    /// lets a test fail one host and watch a stock player move to the next.
    ///
    /// ref: Apple, "HTTP Live Streaming Overview" (Documentation Archive), *Redundant Streams* —
    /// variants listed again with the same `BANDWIDTH` and a URI on another server are backups, and a
    /// client that cannot load one moves to the next.
    public static func hls(
        segmentCount: Int = defaultSegmentCount, variantCount: Int = 1, hosts: Int = 1
    ) -> TestContent {
        precondition(hosts >= 1, "Content is served by at least one host, not \(hosts)")
        let files = SyntheticHlsStream.files(segmentCount: segmentCount, variantCount: variantCount)
        return TestContent(
            sourcePath: SyntheticHlsStream.multivariantPlaylistName, isLive: false, hosts: hosts,
            publication: { _, ports in redundant(files, directory: "", ports: ports) })
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
    /// `SyntheticHlsStream.liveFiles(publishedSegmentCount:)` gives. `hosts` is
    /// ``hls(segmentCount:variantCount:hosts:)``'s, on one clock for every host.
    public static func liveHls(hosts: Int = 1) -> TestContent {
        precondition(hosts >= 1, "Content is served by at least one host, not \(hosts)")
        let window = SyntheticHlsStream.liveWindowSegmentCount
        return TestContent(
            sourcePath: liveDirectory + SyntheticHlsStream.multivariantPlaylistName, isLive: true,
            hosts: hosts,
            publication: { elapsedMs, ports in
                let published = window + Int(elapsedMs / SyntheticHlsStream.segmentDurationMs)
                let files = SyntheticHlsStream.liveFiles(publishedSegmentCount: published)
                    .reduce(into: [:]) { paths, file in paths[liveDirectory + file.key] = file.value
                    }
                return redundant(files, directory: liveDirectory, ports: ports)
            })
    }

    /// Where ``liveHls(hosts:)`` is served: a directory of its own, as the Kotlin `LIVE_BASE_URI` is.
    static let liveDirectory = "live/"

    /// One entry of the hostile corpus, served at the paths it names — `hostile/<id>/<severity>/`,
    /// the Kotlin `TestContent.hostile`'s URIs with the `fake://` host taken off — from one host.
    ///
    /// A token entry's files are named with their query, because the signed address is the
    /// defect; the origin serves by path, query excluded (``NetworkRequest/path``), so each is
    /// served under its path and the player is pointed at the signed source URL, query and all.
    /// Nothing here checks a signature, which is the Kotlin record's reason too: what the entry
    /// carries is the shape, and the refusal it would meet in the field is what its cause names.
    ///
    /// The entry's declared response headers are not content but what an origin sends, so they
    /// are a fault script's: ``FaultScript/Builder/addDeclaredResponseHeaders(of:)``. Never live,
    /// as on Android: `hls-cached-live-playlist` is a live playlist whose bytes never change, which
    /// is its defect, and nothing here publishes a newer one.
    public static func hostile(_ stream: HostileStream) -> TestContent {
        let files = stream.files.reduce(into: [String: [UInt8]]()) { paths, file in
            paths[withoutQuery(file.key)] = file.value
        }
        return TestContent(
            sourcePath: stream.sourcePath, isLive: false, hosts: 1,
            publication: { _, _ in files })
    }

    /// `path` up to its query, which is how the origin looks a request up.
    static func withoutQuery(_ path: String) -> String {
        String(path.prefix { $0 != "?" })
    }

    /// `files` with its multivariant playlist listing every variant once per port after the first,
    /// as an absolute URL on that port; `files` itself for one port.
    private static func redundant(_ files: [String: [UInt8]], directory: String, ports: [UInt16])
        -> [String: [UInt8]]
    {
        let path = directory + SyntheticHlsStream.multivariantPlaylistName
        guard ports.count > 1, let playlist = files[path] else { return files }
        var lines: [String] = []
        var pending: String?
        for line in String(decoding: playlist, as: UTF8.self).split(separator: "\n") {
            let line = String(line)
            if line.hasPrefix("#EXT-X-STREAM-INF:") {
                pending = line
                continue
            }
            guard let streamInf = pending else {
                lines.append(line)
                continue
            }
            // spec: RFC 8216 §4.3.4.2 — the URI line after EXT-X-STREAM-INF is a URI reference, so
            // an absolute URL on another host is as valid as the relative one it repeats.
            lines += [streamInf, line]
            for port in ports.dropFirst() {
                lines += [streamInf, "http://127.0.0.1:\(port)/\(directory)\(line)"]
            }
            pending = nil
        }
        var rewritten = files
        rewritten[path] = Array(lines.joined(separator: "\n").utf8)
        return rewritten
    }
}
