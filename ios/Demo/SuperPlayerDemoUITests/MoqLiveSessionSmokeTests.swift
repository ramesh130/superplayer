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
import SuperPlayerCore
import SuperPlayerMoQ
import XCTest

/// The first real MoQ session on an iPhone: a QUIC handshake against a public relay, a broadcast
/// subscribed, frames counted, and then the same broadcast played by the demo — the Kotlin
/// `MoqLiveSessionSmokeTest` (#367) and the i9 exit, *a broadcast plays on a device* (#438).
///
/// ## Why a UI test, and why it is in no check
///
/// `docs/ios-plan.md` §3.10 ports `devicelab/moq-smoke` as an XCUITest target, and a UI test is the
/// one shape that holds both halves: its runner is a process of its own that can link
/// `SuperPlayerMoQ` and open a transport with no player in it, as the instrumented test does, and it
/// can launch the demo and read what the demo shows, which no other process on a phone can. Nothing
/// under `ios/check` runs it — ADR-0021 bars the network, and three of the claims are reachable
/// nowhere but a device: the XCFramework's **device** slice loading, a QUIC handshake completing from
/// this build, and `UniffiMoqRelay`'s address split, which no public document states.
/// `devicelab/lab run moq-smoke --platform ios` is how it runs (`devicelab/README.md`, *iOS*).
///
/// ## The artifact is the observation, not the assertion
///
/// A relay that is up today may be down tomorrow, so each test prints everything it learned as one
/// block between ``begin`` and ``end`` before any assertion runs, and the lab scrapes the blocks out
/// of the test log into its report. Three outcomes are encoded differently on purpose, the Kotlin
/// test's: a broadcast that declares tracks and delivers frames passes; a relay that answers and
/// announces nothing **skips**, since there is nothing to subscribe to and nothing here could make
/// one; anything else fails with the cause and the relay's own listing in the message.
///
/// ## What it does not do
///
/// The transport half plays nothing — no player, renderer or decoder, ADR-0018 rule 1 at its
/// narrowest — and the demo half asserts no rate. Neither takes a latency figure: a phone on a house
/// network against a public relay measures the house and the weather, and `MoqSessionStatistics`'
/// round trip is reported as what the session said rather than as a number this repository claims.
final class MoqLiveSessionSmokeTests: XCTestCase {

    /// Connects, subscribes, counts frames over a bounded window, then cancels and checks no frame
    /// arrives after — one test rather than four, because #367's four readings are of **one**
    /// session and four would open four sessions against a public relay to learn what one can say.
    func testALiveBroadcastDeclaresTracksDeliversFramesAndStopsWhenCancelled() async throws {
        let broadcast = Self.broadcast
        let url = try XCTUnwrap(URL(string: broadcast), "not a URL: \(broadcast)")
        let observations = Observations(test: "transport")
        observations.record("broadcast", broadcast)
        observations.record("relay.authority", Self.authority(of: url))
        observations.record("broadcast.namespace", Self.namespace(of: url))
        observations.record("broadcast.name", Self.name(of: url))
        observations.record("device", Self.deviceDescription)

        let source = MoqFrameSource(url: url)
        let sink = RecordingSink()
        source.subscribe(sink)

        // Two waits, because the failures they separate are the two this test exists to tell apart: a
        // session that never connected declares nothing, while one subscribed to a broadcast nobody
        // publishes declares tracks and then delivers no frame. The first wait is released by a
        // declaration **or** a terminal callback, so what is reported is `sawTracks`, never the wait.
        sink.awaitDeclarationOrEnd(boundSeconds: Self.connectBoundSeconds)
        let declared = sink.sawTracks
        observations.record("tracks.declared", declared ? "yes" : "no")

        if !declared {
            source.cancel()
            observations.record(
                "failure",
                sink.failure ?? "no declaration and no error within \(Self.connectBoundSeconds) s")
            // Asked only on the failing path, unless asked for: a run that subscribes opens one
            // session, which is all #367 asked of a public relay.
            let discovery = await Self.reportRelayAndMappings(url, observations)
            observations.emit()
            if discovery.isEmptyAnswer {
                throw XCTSkip(
                    "The relay at \(Self.authority(of: url)) answered and announced no broadcast under "
                        + "\"\(Self.namespace(of: url))\", so there was nothing to subscribe to. Name a live "
                        + "one with SP_MOQ_BROADCAST.")
            }
            XCTFail(
                "No tracks were declared within \(Self.connectBoundSeconds) s. The session reported "
                    + "\(sink.failure ?? "nothing"), and the relay's listing under "
                    + "\"\(Self.namespace(of: url))\" said: \(discovery.described).")
            return
        }

        for (index, track) in sink.tracks.enumerated() {
            observations.record("track[\(index)].codec", track.codec)
            observations.record(
                "track[\(index)].configuration", Self.describe(track.codecConfiguration))
            observations.record(
                "track[\(index)].codedSize", track.codedSize.map(String.init) ?? "-")
        }
        observations.record("tracks.count", String(sink.tracks.count))

        // A count that *advances* rather than one that is non-zero: one frame could be a catalog
        // artefact, and what #367 asks for is a subscription that is delivering.
        let atFirstReading = sink.counts.frames
        let advanced = sink.awaitFrames(
            atLeast: Self.framesWanted, boundSeconds: Self.framesBoundSeconds)
        let counts = sink.counts
        observations.record("frames.atFirstReading", String(atFirstReading))
        observations.record("frames.afterWindow", String(counts.frames))
        observations.record("frames.windowSeconds", String(Self.framesBoundSeconds))
        observations.record("frames.bytesTotal", String(counts.bytes))
        observations.record("frames.keyFrames", String(counts.keyFrames))
        observations.record("frames.perTrack", counts.perTrack)
        observations.record("session.statistics", Self.describe(source.statistics()))
        if !advanced {
            observations.record(
                "failure",
                sink.failure
                    ?? "fewer than \(Self.framesWanted) frames in \(Self.framesBoundSeconds) s")
        }

        // Obligation 9 against a real transport: nothing reaches the sink once `cancel()` returns.
        // Here the pumps are parked in a blocking `next()` across the FFI boundary, the case
        // `MoqTrackStream.close` exists for. The quiet window is the Kotlin one and its reason: any
        // value proves the point, since a callback after the return is impossible rather than unlikely.
        source.cancel()
        let atCancel = sink.counts.frames
        try await Task.sleep(for: .milliseconds(Self.quietAfterCancelMs))
        let afterQuiet = sink.counts.frames
        observations.record("frames.afterCancel", "\(atCancel) then \(afterQuiet)")
        // The Kotlin test also lists the source's threads by name after the cancel. Swift has no way
        // to enumerate another module's threads, and `MoqFrameSource.joinedThreadsHaveFinished` is
        // `internal`, so this half is `MoqFrameSourceTests`' under ios/check and is not repeated here.

        if ProcessInfo.processInfo.environment["SP_MOQ_PROBE_MAPPINGS"] == "true" {
            // After the real subscription has been cancelled, so the two never share a session.
            _ = await Self.reportRelayAndMappings(url, observations)
        }
        observations.emit()

        XCTAssertTrue(
            advanced,
            "The broadcast declared \(sink.tracks.count) track(s) but delivered \(counts.frames) frame(s) in "
                + "\(Self.framesBoundSeconds) s, wanting \(Self.framesWanted). Reported: \(sink.failure ?? "nothing")."
        )
        XCTAssertEqual(atCancel, afterQuiet, "A frame arrived after cancel() returned")
    }

    /// The demo plays the same broadcast: `MoqScreen` on a `RealtimePlayer`, its clock advancing
    /// between two readings while the display layer renders and the session's received bytes rise —
    /// the iOS reading of `CLAUDE.md`'s *a position that advances between two of them*, since a
    /// screenshot cannot tell a rendered frame from a stalled one.
    @MainActor
    func testTheDemoPlaysTheBroadcast() async throws {
        let observations = Observations(test: "demo")
        observations.record("broadcast", Self.broadcast)
        let app = XCUIApplication()
        app.launchArguments = ["-SPDemoScreen", "moq", "-SPMoqBroadcast", Self.broadcast]
        app.launch()
        defer { app.terminate() }

        let overlay = app.staticTexts[Self.readingIdentifier]
        guard overlay.waitForExistence(timeout: Self.connectBoundSeconds) else {
            observations.record("failure", "the overlay never appeared: the screen did not open")
            observations.emit()
            XCTFail("MoqScreen's overlay never appeared")
            return
        }

        // Readings until one is rendering, then one more at least two of the demo's intervals later:
        // two readings of one content with the position risen is the evidence, as it is for
        // devicelab's other iOS scenarios.
        var first: Reading?
        var second: Reading?
        let deadline = Date(
            timeIntervalSinceNow: Self.connectBoundSeconds + Self.framesBoundSeconds)
        while Date() < deadline, second == nil {
            let reading = Reading(overlay.value as? String ?? "")
            if reading.failed != nil {
                first = first ?? reading
                second = reading
                break
            }
            if let first, reading.rendering,
                reading.positionMs >= first.positionMs + Self.advanceWantedMs
            {
                second = reading
            } else if first == nil, reading.rendering {
                first = reading
            }
            if second == nil { try await Task.sleep(for: .milliseconds(500)) }
        }
        observations.record("reading.first", first?.line ?? "none rendering")
        observations.record("reading.second", second?.line ?? "none advanced")
        var played = false
        if let first, let second, second.failed == nil, second.rendering {
            let advanced = second.positionMs >= first.positionMs + Self.advanceWantedMs
            played = advanced && second.received > first.received
        }
        observations.record("played", played ? "yes" : "no")
        if let second { observations.record("demo.position", String(second.positionMs)) }
        observations.emit()

        XCTAssertTrue(
            played,
            "The demo did not play \(Self.broadcast): first reading \(first?.line ?? "none rendering"), "
                + "second \(second?.line ?? "none")")
    }

    // MARK: - The relay and the address mapping

    /// Asks the relay what it announces, and asks each candidate address split which one routes — the
    /// check for #367's two defects on iOS. `UniffiMoqRelay` carries both fixes already; this is what
    /// shows whether the iOS bindings against a live relay agree with what Android found, rather than
    /// assuming they do. Reported, never asserted: which split routes is a fact about MoQ, and a test
    /// that hard-coded it would be re-asserting its own guess.
    private static func reportRelayAndMappings(
        _ url: URL, _ observations: Observations
    ) async -> MoqRelayDiscovery.Announced {
        let namespace = namespace(of: url)
        // The corrected split: the namespace is in the session URL, so a listing taken from the bare
        // authority sees nothing and would report a live relay as empty — the Kotlin review's catch.
        let discovery = await MoqRelayDiscovery.announcedUnder(
            connectURL: "https://\(authority(of: url))"
                + (namespace.isEmpty ? "" : "/\(namespace)"),
            prefix: "", boundMs: discoveryBoundMs)
        observations.record("relay.announcedUnder[\(namespace)]", discovery.described)

        observations.record("mapping.candidates", "asking the relay which one routes")
        for (label, candidate) in candidateMappings(url) {
            let listed = await MoqRelayDiscovery.announcedUnder(
                connectURL: candidate.connectURL, prefix: "", boundMs: discoveryBoundMs)
            observations.record(
                "mapping[\(label)].announced",
                "connect=\(candidate.connectURL) -> \(listed.described)")
            // Three arms, because two could not tell waiting from listening apart (#367's lesson):
            // `settling` waits exactly as long as `listening` and listens to nothing.
            for arm in [
                (label: "immediate", announceFirst: false, settleMs: Int64(0)),
                (label: "settling", announceFirst: false, settleMs: discoveryBoundMs),
                (label: "listening", announceFirst: true, settleMs: discoveryBoundMs),
            ] {
                let answer = await MoqRelayDiscovery.probeBroadcast(
                    connectURL: candidate.connectURL, name: candidate.broadcastName,
                    boundMs: discoveryBoundMs, announceFirst: arm.announceFirst,
                    settleMs: arm.settleMs)
                observations.record(
                    "mapping[\(label)].requested(\(arm.label))",
                    "broadcast=\(candidate.broadcastName) -> \(answer)")
            }
        }
        return discovery
    }

    /// The two ways a `moq://` URL can be split into what the bindings take separately: the bare
    /// authority with the whole path as the name — what #367 found wrong — and the namespace in the
    /// session URL with the rest as the name, which is what `UniffiMoqRelay` does. A URL with one
    /// segment collapses them, and the duplicate is dropped.
    private static func candidateMappings(_ url: URL) -> [(
        String, (connectURL: String, broadcastName: String)
    )] {
        let authority = authority(of: url)
        let path = trimmedPath(of: url)
        var candidates = [
            ("authority-only", (connectURL: "https://\(authority)", broadcastName: path))
        ]
        let rest = name(of: url)
        if !rest.isEmpty {
            candidates.append(
                (
                    "authority-plus-first-segment",
                    (connectURL: "https://\(authority)/\(namespace(of: url))", broadcastName: rest)
                ))
        }
        return candidates
    }

    // MARK: - Reading the URL and the demo

    private static func authority(of url: URL) -> String {
        (url.host ?? "") + (url.port.map { ":\($0)" } ?? "")
    }

    private static func trimmedPath(of url: URL) -> String {
        let path = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedPath ?? ""
        return String(path.drop { $0 == "/" })
    }

    private static func namespace(of url: URL) -> String {
        String(trimmedPath(of: url).prefix { $0 != "/" })
    }

    private static func name(of url: URL) -> String {
        let path = trimmedPath(of: url)
        guard let slash = path.firstIndex(of: "/") else { return "" }
        return String(path[path.index(after: slash)...])
    }

    private static func describe(_ configuration: RealtimeTrack.CodecConfiguration) -> String {
        switch configuration {
        case .inBand:
            "in-band (no description; parameter sets in the payload)"
        case .record(let bytes):
            "out-of-band record, \(bytes.count) B, opening "
                + bytes.prefix(8).map { String(format: "%02x", $0) }.joined(separator: " ")
        }
    }

    private static func describe(_ statistics: MoqSessionStatistics?) -> String {
        guard let statistics else { return "none polled yet" }
        func field(_ value: Int64?) -> String { value.map(String.init) ?? "-" }
        return
            "rttUs=\(field(statistics.roundTripTimeUs)) bytesReceived=\(field(statistics.bytesReceived))"
            + " packetsReceived=\(field(statistics.packetsReceived))"
            + " receiveRateBps=\(field(statistics.receiveRateBps))"
    }

    /// The model and OS as the runner sees them; the lab's report carries devicectl's reading too.
    private static var deviceDescription: String {
        var system = utsname()
        uname(&system)
        let machine = withUnsafeBytes(of: system.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return "\(machine), \(ProcessInfo.processInfo.operatingSystemVersionString)"
    }

    /// One of `MoqScreen`'s reading lines, as the overlay's accessibility value carries it:
    /// `content=… position=<ms> rendering=<bool> received=<bytes> rtt=<ms>[ failed=…]`.
    private struct Reading {
        let line: String
        let positionMs: Int64
        let rendering: Bool
        let received: Int64
        let failed: String?

        init(_ line: String) {
            self.line = line
            var fields: [String: String] = [:]
            for field in line.split(separator: " ") {
                guard let equals = field.firstIndex(of: "=") else { continue }
                fields[String(field[..<equals])] = String(field[field.index(after: equals)...])
            }
            positionMs = Int64(fields["position"] ?? "") ?? -1
            rendering = fields["rendering"] == "true"
            received = Int64(fields["received"] ?? "") ?? -1
            failed = line.range(of: " failed=").map { String(line[$0.upperBound...]) }
        }
    }

    // MARK: - Arguments and bounds

    /// The broadcast: `SP_MOQ_BROADCAST`, which the lab hands the runner as `TEST_RUNNER_SP_MOQ_BROADCAST`
    /// (xcodebuild strips the prefix), or the demo's own default — a **default and not a fixture**,
    /// because nothing here controls what a public relay carries.
    private static var broadcast: String {
        let named = ProcessInfo.processInfo.environment["SP_MOQ_BROADCAST"] ?? ""
        return named.isEmpty ? defaultBroadcast : named
    }

    /// `MoqScreen.defaultBroadcast`, restated because a UI test cannot import the app it drives.
    static let defaultBroadcast = "moq://cdn.moq.pro/anon/catshark.hang"

    /// `MoqDemoSession.readingIdentifier`, restated for the same reason.
    static let readingIdentifier = "moq.reading"

    /// A connect, a catalog and a declaration: **15 s**, the Kotlin bound, wide because the failure it
    /// produces — no tracks — is the ambiguous one this test exists to separate, and a phone on a
    /// house network against a relay overseas is no faster than an emulator's NAT.
    static let connectBoundSeconds: TimeInterval = 15

    /// Thirty frames in ten seconds, the Kotlin pair: about a second of 30 fps video, so a
    /// subscription that is running rather than one that sent a keyframe and stopped; a rate is not
    /// measured.
    static let framesWanted = 30
    static let framesBoundSeconds: TimeInterval = 10

    /// How far the demo's clock must advance between two rendering readings: **4 s**, two of
    /// `MoqDemoSession`'s logging intervals, so the second reading is not the first read twice.
    static let advanceWantedMs: Int64 = 4_000

    /// The Kotlin window after `cancel()`, and its reason: long enough for a frame already crossing
    /// the FFI boundary to have arrived, short enough not to pad the run.
    static let quietAfterCancelMs = 500

    /// Each discovery read and each probe step: **3 s**, the Kotlin bound — per announcement rather
    /// than per listing, because a live announce stream never ends.
    static let discoveryBoundMs: Int64 = 3_000
}

/// What a test learned, in the order it learned it, printed as one block the lab cuts between the
/// markers: one write per block, so lines from elsewhere in the runner cannot land inside it.
final class Observations: @unchecked Sendable {
    static let begin = "--- moq-smoke begin ---"
    static let end = "--- moq-smoke end ---"

    private let lock = NSLock()
    private var entries: [(String, String)]

    init(test: String) { entries = [("test", test)] }

    func record(_ key: String, _ value: String) {
        // One line per entry, whatever the value carried: a newline would end the entry early for
        // the lab's reader.
        let flat = value.replacingOccurrences(of: "\n", with: " ")
        lock.withLock { entries.append((key, flat)) }
    }

    func emit() {
        let body = lock.withLock { entries.map { "\($0.0) = \($0.1)" } }
        print(([Self.begin] + body + [Self.end]).joined(separator: "\n"))
    }
}

/// A ``FrameSink`` that counts and keeps nothing it was handed — obligation 8 lets a transport
/// reuse a payload once the callback returns, and a sink that kept them would be measuring its own
/// defect. `@unchecked Sendable`: every field is under `condition`.
final class RecordingSink: FrameSink, @unchecked Sendable {

    struct Counts {
        var frames = 0
        var bytes = 0
        var keyFrames = 0
        var byTrack: [Int: Int] = [:]
        var perTrack: String {
            byTrack.keys.sorted().map { "track\($0)=\(byTrack[$0] ?? 0)" }.joined(separator: " ")
        }
    }

    private let condition = NSCondition()
    private var declared: [RealtimeTrack]?
    private var tally = Counts()
    private var failureDescription: String?
    private var ended = false

    func onTracks(_ tracks: [RealtimeTrack]) {
        condition.withLock {
            declared = tracks
            condition.broadcast()
        }
    }

    func onFrame(_ frame: EncodedFrame) {
        condition.withLock {
            tally.frames += 1
            tally.bytes += frame.payload.count
            if frame.keyFrame { tally.keyFrames += 1 }
            tally.byTrack[frame.trackIndex, default: 0] += 1
            condition.broadcast()
        }
    }

    func onEnded() {
        condition.withLock {
            ended = true
            condition.broadcast()
        }
    }

    func onError(_ error: any Error) {
        condition.withLock {
            failureDescription = Self.chain(of: error)
            condition.broadcast()
        }
    }

    /// Whether ``onTracks(_:)`` was called — kept apart from the wait, which a terminal callback also
    /// releases, so a session that failed to connect is never reported as one that declared.
    var sawTracks: Bool { condition.withLock { declared != nil } }
    var tracks: [RealtimeTrack] { condition.withLock { declared ?? [] } }
    var counts: Counts { condition.withLock { tally } }

    /// The failure with its whole chain: `UniffiMoqRelay` wraps what it catches, so the outermost
    /// message says only that a session could not be opened, and the chain is where a relay that is
    /// down, a wrong name and a wrong split separate.
    var failure: String? {
        condition.withLock { failureDescription ?? (ended ? "the subscription ended" : nil) }
    }

    func awaitDeclarationOrEnd(boundSeconds: TimeInterval) {
        let deadline = Date(timeIntervalSinceNow: boundSeconds)
        condition.withLock {
            while declared == nil, failureDescription == nil, !ended,
                condition.wait(until: deadline)
            {}
        }
    }

    /// Waits on the sink's own progress rather than sleeping out the window, so a slow relay and a
    /// dead one are told apart before the bound rather than at it.
    func awaitFrames(atLeast wanted: Int, boundSeconds: TimeInterval) -> Bool {
        let deadline = Date(timeIntervalSinceNow: boundSeconds)
        return condition.withLock {
            while tally.frames < wanted, failureDescription == nil, !ended,
                condition.wait(until: deadline)
            {}
            return tally.frames >= wanted
        }
    }

    private static func chain(of error: any Error) -> String {
        var parts: [String] = []
        var current: (any Error)? = error
        while let error = current, parts.count < 8 {
            parts.append("\(type(of: error)): \(error)")
            current = (error as NSError).userInfo[NSUnderlyingErrorKey] as? any Error
        }
        return parts.joined(separator: " <- ")
    }
}
