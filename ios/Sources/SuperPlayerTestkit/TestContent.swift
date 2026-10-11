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
/// ``hls(segmentCount:variantCount:hosts:)`` and ``liveHls(hosts:)`` — and one it has that Android
/// does not need, ``videoHls(segmentCount:)``, the decodable video a frame output is tested on. Android's *described* content,
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

    /// The one file at `path` under the origin's root once the origin has been serving for
    /// `elapsedMs` milliseconds, given the port of each of its hosts in order, or nil — what the
    /// origin asks per request.
    ///
    /// A function of the time for the reason the Kotlin `publication` is one: a live media playlist
    /// is *defined* by changing (RFC 8216 §6.2.1), so a live stream served from fixed bytes is a live
    /// stream that has stopped. On-demand content ignores the time; content on one host ignores the
    /// ports. A lookup by path rather than a map of everything published, so a ladder's files are
    /// built when asked for rather than held (`SyntheticVideoLadder`'s *Lookup by name*).
    let file: @Sendable (_ path: String, _ elapsedMs: Int64, _ ports: [UInt16]) -> [UInt8]?

    /// Content whose files are a map of what is published by a moment: looked up by path.
    private init(
        sourcePath: String, isLive: Bool, hosts: Int,
        publication:
            @escaping @Sendable (_ elapsedMs: Int64, _ ports: [UInt16]) -> [String: [UInt8]]
    ) {
        self.sourcePath = sourcePath
        self.isLive = isLive
        self.hosts = hosts
        self.file = { path, elapsedMs, ports in publication(elapsedMs, ports)[path] }
    }

    /// Content on one host whose files are built by path when asked for.
    private init(
        sourcePath: String, isLive: Bool,
        file: @escaping @Sendable (_ path: String, _ elapsedMs: Int64) -> [UInt8]?
    ) {
        self.sourcePath = sourcePath
        self.isLive = isLive
        self.hosts = 1
        self.file = { path, elapsedMs, _ in file(path, elapsedMs) }
    }

    /// What the origin answers a `POST` at a path with, given the body it carried, or nil where it
    /// answers none there — set only by ``licenceServer()``, the one content a client sends a body
    /// to. Content that computes an answer is a server rather than a store of files, so this is a
    /// function of the request where ``file`` is a function of the path and the time.
    var answering: (@Sendable (_ path: String, _ body: [UInt8]) -> [UInt8]?)? = nil

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

    /// A real HLS stream of decodable H.264 video: `SyntheticVideoHlsStream`'s playlists, its
    /// fragmented-MP4 initialization segment and `segmentCount` two-second segments of thirty frames a
    /// second, served under `video/` on one host.
    ///
    /// The content a frame output is tested on (#405), since every other stream here is audio and an
    /// effect is handed decoded *pictures*. It has no Kotlin twin — Android's frame-output test plays
    /// described video, which Media3's fakes synthesize without a byte (`SyntheticVideoHlsStream`'s
    /// doc comment) — so its path is its own rather than an Android one with the host taken off.
    public static func videoHls(segmentCount: Int = defaultSegmentCount) -> TestContent {
        let files = SyntheticVideoHlsStream.files(segmentCount: segmentCount)
            .reduce(into: [String: [UInt8]]()) { paths, file in
                paths[videoDirectory + file.key] = file.value
            }
        return TestContent(
            sourcePath: videoDirectory + SyntheticVideoHlsStream.multivariantPlaylistName,
            isLive: false, hosts: 1, publication: { _, _ in files })
    }

    /// An on-demand rendition ladder of decodable video: `SyntheticVideoLadder`'s rungs at
    /// `bitratesBps`, in the order given, each `segmentCount` two-second segments padded to exactly
    /// the bitrate its playlist declares, served under `ladder/` on one host — the port of the Kotlin
    /// `TestContent.videoLadder`, which the benchmark plays its matrix over (#414).
    ///
    /// Bytes where the Kotlin ladder is described: `AVPlayer` plays only what it fetches, so a rung
    /// costs a shaped link what it declares only if its segments are that large.
    public static func videoLadder(bitratesBps: [Int], segmentCount: Int) -> TestContent {
        TestContent(
            sourcePath: ladderDirectory + SyntheticVideoLadder.multivariantPlaylistName,
            isLive: false
        ) { path, _ in
            guard path.hasPrefix(ladderDirectory) else { return nil }
            return SyntheticVideoLadder.file(
                String(path.dropFirst(ladderDirectory.count)), bitratesBps: bitratesBps,
                segmentCount: segmentCount)
        }
    }

    /// ``videoLadder(bitratesBps:segmentCount:)`` live: every rung's window slides on
    /// ``liveHls(hosts:)``'s clock — a segment published every two seconds of real time from the
    /// moment the origin starts, the window full at the start — served under `live-ladder/`. The
    /// port of the Kotlin `TestContent.liveVideoLadder`.
    public static func liveVideoLadder(bitratesBps: [Int]) -> TestContent {
        TestContent(
            sourcePath: liveLadderDirectory + SyntheticVideoLadder.multivariantPlaylistName,
            isLive: true
        ) { path, elapsedMs in
            guard path.hasPrefix(liveLadderDirectory) else { return nil }
            let published =
                SyntheticVideoLadder.liveWindowSegmentCount
                + Int(elapsedMs / SyntheticVideoLadder.segmentDurationMs)
            return SyntheticVideoLadder.liveFile(
                String(path.dropFirst(liveLadderDirectory.count)), bitratesBps: bitratesBps,
                publishedSegmentCount: published)
        }
    }

    /// Where ``videoLadder(bitratesBps:segmentCount:)`` is served.
    static let ladderDirectory = "ladder/"

    /// Where ``liveVideoLadder(bitratesBps:)`` is served.
    static let liveLadderDirectory = "live-ladder/"

    /// Where ``videoHls(segmentCount:)`` is served.
    static let videoDirectory = "video/"

    /// A FairPlay key server: the application certificate at ``licenceCertificatePath``, answered to
    /// a `GET`, and a licence at ``licencePath``, answered to a `POST` of a key request with
    /// ``licence(for:)`` of its body — the Kotlin `FakeLicenceServer`, an origin of its own so that a
    /// licence is a *transfer*: addressed by ``ResourceKind/licence``, refusable and relenting
    /// through a ``FaultScript``, and counted by ``LoopbackOrigin/networkRequests()``.
    ///
    /// Serve it beside the content, from a second ``PlaybackHarness/serve(_:faults:network:)``; its
    /// ``LoopbackOrigin/sourceURL`` is the licence URL. **Nothing here is FairPlay**: the simulator has
    /// no FairPlay (ADR-0021 rule 5), so no player ever composes a real key request, and the bytes
    /// are a stand-in's on both sides. What it holds is the exchange's shape — which request, with
    /// which headers and body, how often, and in what order.
    public static func licenceServer() -> TestContent {
        let certificate = licenceCertificate
        var content = TestContent(
            sourcePath: licencePath, isLive: false, hosts: 1,
            publication: { _, _ in [licenceCertificatePath: certificate] })
        content.answering = { path, body in path == licencePath ? licence(for: body) : nil }
        return content
    }

    /// Where ``licenceServer()`` answers, a directory of its own as ``liveDirectory`` is — and what
    /// makes a request there ``ResourceKind/licence``.
    static let licenceDirectory = "fairplay/"

    /// Where ``licenceServer()`` publishes its application certificate.
    public static let licenceCertificatePath = licenceDirectory + "certificate"

    /// Where ``licenceServer()`` answers a key request.
    public static let licencePath = licenceDirectory + "licence"

    /// The certificate ``licenceServer()`` publishes: fixed bytes, opaque to everything but a
    /// test comparing them, since nothing here composes a real key request from it.
    public static let licenceCertificate: [UInt8] = Array("superplayer-test-certificate".utf8)

    /// The licence ``licenceServer()`` issues for a key request of `spc`: the request's bytes
    /// behind a fixed prefix, so a test can tell which request a licence answered.
    public static func licence(for spc: [UInt8]) -> [UInt8] {
        Array("ckc:".utf8) + spc
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
