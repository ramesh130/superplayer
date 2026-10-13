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

import AVFoundation
import Foundation
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// What a stock `AVPlayer` does with every entry of the HLS hostile corpus — **recorded, not
/// asserted as handled** — the iOS counterpart of `superplayer-testkit`'s `HostileManifestCorpusTest`
/// (#412), in its shape: one table for the whole graded corpus, so a run reports every row that
/// moved rather than the first, and a later change arrives as a visible diff in ``recorded``.
///
/// **A stock player, not SuperPlayer.** The Kotlin table records a SuperPlayer built with a profile
/// and nothing else, which is a core-only consumer; the iOS facade adds nothing between `AVPlayer`
/// and the corpus yet — no classifier, no revalidation, no doctor — so the honest baseline is
/// AVFoundation's own. That is also why there is no `FAILS_TYPED` row: a typed failure is
/// SuperPlayer's, and nothing here raises one. When the facade does, a second table taken through
/// it is the one that differs, as the Kotlin ladder record differs from the core-only one. One public
/// property of a stock item is set: every item starts on its first variant, as in a fresh process
/// (`observe` says why).
///
/// **Real time, structural outcomes.** Every entry is served by an origin of its own and played at
/// once, all of them together, and watched for ``observationBudget`` — the Kotlin
/// `HostileObservation.OBSERVATION_MS`, for its reason: twice the longest on-demand entry. Played
/// together because one after another would be nineteen budgets rather than one; the outcomes are
/// coarse enough — failed with which error, ended, still playing, stalled, never started — that a
/// loaded host moves none of them but one, the live entry ``timingDependent`` names with its
/// mechanisms. No duration is asserted; the budget and ``readyWindow`` classify an unfinished
/// session, which ADR-0021 rule 4's #412 addendum admits and argues, and its #459 addendum records
/// the one row the host's clock decides.
///
/// **How the table is regenerated: by hand, deliberately.** A row that moves fails the test with
/// the whole observed table printed as the Swift literal ``recorded`` is, ready to paste; there is
/// no update mode. The Kotlin record has none either and for the reason that holds here: each row
/// carries a comment saying why it reads as it does, and a row that moved is a behaviour change
/// someone has to explain beside it — which a regenerating switch would let a change skip.
@MainActor
final class HostileManifestCorpusTests: XCTestCase {

    /// How a stock `AVPlayer`'s session with one entry ended within the budget — the Kotlin
    /// `HostileOutcome`, coarse for its reason, and without `FAILS_TYPED` (the class doc says why).
    enum Outcome: Hashable, CustomStringConvertible {
        /// The item failed, with the error's domain and code, and its underlying error's where it
        /// carries one: the structural part of an `NSError`, never its message, which is prose a
        /// framework update may reword.
        case fails(String)
        /// No error, and the position never moved: the player never had anything to render.
        case neverStarts
        /// Started, then the position stood still for the last ``readyWindow`` of the budget.
        case stalls
        /// Started, but reported a position outside the media it was served.
        case degrades
        /// Moving within the last ``readyWindow`` of the budget, with no end: what playing a live
        /// stream correctly looks like.
        case stillPlaying
        /// The item posted that it played to its end.
        case playsToEnd

        var description: String {
            switch self {
            case .fails(let error): ".fails(\"\(error)\")"
            case .neverStarts: ".neverStarts"
            case .stalls: ".stalls"
            case .degrades: ".degrades"
            case .stillPlaying: ".stillPlaying"
            case .playsToEnd: ".playsToEnd"
            }
        }
    }

    /// Twice the longest on-demand entry (17.5 s of ragged segments) and more, as the Kotlin budget
    /// is: an entry that can end has to end inside half of it, or whether it is recorded as ended
    /// depends on how fast the host loads. ``testTheBudgetFitsEveryEntryTwice`` holds that bound.
    static let observationBudget: Duration = .seconds(40)

    /// How recently the position must have moved for an unfinished session to count as playing
    /// rather than stalled: a segment's worth, the Kotlin `READY_WINDOW_MS`, for its reason — a
    /// session that is playing moves at nearly every poll, and one that has stopped never again.
    static let readyWindow: Duration = .milliseconds(SyntheticHlsStream.segmentDurationMs)

    /// Any instant serves, since nothing here enforces a token; the wall clock's, so the token
    /// entries are what a CDN would mint now.
    private let now = Int64(Date().timeIntervalSince1970)

    private let harness = PlaybackHarness()

    override func tearDown() async throws {
        harness.stop()
        try await super.tearDown()
    }

    /// What each entry of `HostileManifests.graded(nowEpochSeconds:)` does in a stock `AVPlayer`,
    /// keyed `"<id> (<severity>)"`, as of the change that added it. Not a specification: a row is
    /// improved by editing it in the change that improves it, with the reason beside it.
    static let recorded: [String: Outcome] = [
        // Flat across every level, as on Android and for a cousin of its reason: the stream is
        // audio-only, so AVFoundation picks a rendition and a ladder can be as wrong as it likes
        // without anything a structural outcome sees. A flat row is a cliff this harness cannot
        // see, not the absence of one. One such cliff has been observed (#460). The severe gap's top
        // rung has frames 48 times the bottom's, larger than AAC's bit reservoir allows. A player
        // started on that rung fetched two segments, logged an audio-queue prime failure (-50) and
        // stayed at zero, with no error and no reason given. These rows start on the first variant
        // (`observe` says why), which is the bottom rung, and no run has climbed within the entry's
        // 8 s. A climb inside the budget would move the severe row to a stall.
        // spec: ISO/IEC 14496-3 — AAC's bit reservoir bounds a raw data block to 6144 bits per
        // channel, 1536 bytes for this stereo stream, against the top rung's 3408-byte frame.
        "hls-ladder-gap (benign)": .playsToEnd,
        "hls-ladder-gap (borderline)": .playsToEnd,
        "hls-ladder-gap (severe)": .playsToEnd,
        "hls-overstated-bitrate (benign)": .playsToEnd,
        "hls-overstated-bitrate (borderline)": .playsToEnd,
        "hls-overstated-bitrate (severe)": .playsToEnd,
        "hls-missing-codecs (severe)": .playsToEnd,
        "hls-audio-group-codec-mismatch (severe)": .playsToEnd,
        // One of the two rows where the platforms part, and the finding #412 brings: Media3 ignores the
        // dangling AUDIO reference and plays, while AVFoundation fails the item before it plays a
        // sample. The label is about the document — it is malformed — and this row about the
        // player, which here is the strict one.
        "hls-dangling-audio-group (severe)": .fails("CoreMediaErrorDomain -12642"),
        // 17.5 s of genuinely ragged segments at the severe level, played end to end.
        "hls-inconsistent-segment-durations (benign)": .playsToEnd,
        "hls-inconsistent-segment-durations (borderline)": .playsToEnd,
        "hls-inconsistent-segment-durations (severe)": .playsToEnd,
        "hls-discontinuity-without-timeline (severe)": .playsToEnd,
        // The frozen live stream, reproduced: the playlist never changes, the player plays to the
        // live edge and waits there with no error it fails with — its error log carries -12888, the
        // playlist unchanged, which nothing surfaces. Android's row reads `FAILS_TYPED`, because core
        // names the cache; a stock AVPlayer names nothing, within the budget at least. What a fast
        // host reads by the budget's end; ``timingDependent`` lists the others, and why.
        "hls-cached-live-playlist (severe)": .stalls,
        // The delivery entries play through for the Kotlin record's reason: nothing here refuses an
        // unsigned or expired URI, and no native player reads a CORS header. What they carry is the
        // shape a doctor reads.
        "hls-token-scoped-to-manifest (severe)": .playsToEnd,
        "hls-token-expiring-in-window (benign)": .playsToEnd,
        "hls-token-expiring-in-window (borderline)": .playsToEnd,
        "hls-token-expiring-in-window (severe)": .playsToEnd,
        "hls-cors-refuses-credentials (severe)": .playsToEnd,
    ]

    /// The rows whose outcome is decided by when things happen on the host's clock rather than by
    /// the entry, each with every outcome it has been seen to read — its ``recorded`` one among them — and why. Not a tolerance to widen
    /// when a row flakes: a row belongs here only with the mechanism that makes it host-dependent
    /// shown, and an outcome outside its set still fails.
    ///
    /// The frozen live playlist is the one row, and #459 traced each of its four readings.
    /// AVFoundation judges a live stream against the wall clock — its playlist reloads, its segment
    /// timeouts and its live timeline run on time, not on bytes — so where the budget's end finds
    /// this entry depends on how fast the host served it and on when the player gives up waiting:
    ///
    /// - **Stalls**: joined three target durations back, played to the live edge at 8 s, and waited.
    /// - **Degrades**: waited at the edge long enough — some seventeen seconds in the run traced —
    ///   that the player joined again where the live timeline had reached and replayed the same
    ///   unchanged window there, at 23 s to 31 s, which the observation reads as a position outside
    ///   the 8 s of media served. The freeze becoming a loop is the same defect, later.
    /// - **Never starts** and **-12889**, no segment in time: the corpus plays nineteen players at
    ///   once, and on a host slow enough that this entry's responses arrive a target duration late
    ///   the player never reaches the edge; it waits at position zero and fails, inside the budget
    ///   when only the segments are late and past it when everything is. Both were forced with
    ///   ``FaultScript/Builder/addLatencyMs(_:kind:index:firstAttempts:host:)`` on this entry, and
    ///   CI's macOS runner read the second.
    ///
    /// Any other outcome — playing on, ending, or failing with any other error — is a change in what
    /// AVFoundation does with a frozen playlist, and fails.
    static let timingDependent: [String: Set<Outcome>] = [
        "hls-cached-live-playlist (severe)": [
            .stalls, .degrades, .neverStarts, .fails("CoreMediaErrorDomain -12889"),
        ]
    ]

    func testEveryGradedEntryIsPlayedAndItsBehaviourRecorded() async throws {
        let corpus = HostileManifests.graded(nowEpochSeconds: now)
        let observed = try await observe(corpus)

        let moved = observed.filter { key, outcome in
            outcome != Self.recorded[key]
                && !(Self.timingDependent[key]?.contains(outcome) ?? false)
        }
        if !moved.isEmpty || observed.count != Self.recorded.count {
            XCTFail(
                "A stock AVPlayer no longer does what the record says. Observed, as the literal:\n"
                    + Self.literal(observed, in: corpus))
        }
    }

    func testEveryGradedEntryHasARowAndEveryRowAnEntry() {
        // The register half of the table, apart from playing it, so that an entry added to the
        // corpus and forgotten here fails naming it without a run of the whole budget.
        let entries = HostileManifests.graded(nowEpochSeconds: now).map(\.description)
        let rows = Set(Self.recorded.keys)

        XCTAssertEqual(entries.filter { !rows.contains($0) }, [], "entries with no recorded row")
        XCTAssertEqual(rows.subtracting(entries).sorted(), [], "rows naming no entry")
        for (row, outcomes) in Self.timingDependent {
            XCTAssertTrue(rows.contains(row), "\(row) is host-dependent but has no recorded row")
            XCTAssertTrue(
                Self.recorded[row].map(outcomes.contains) ?? false,
                "\(row)'s accepted outcomes leave out the one recorded")
        }
    }

    func testTheBudgetFitsEveryEntryTwice() {
        for entry in HostileManifests.graded(nowEpochSeconds: now) {
            XCTAssertLessThanOrEqual(
                Duration.milliseconds(entry.durationMs * 2), Self.observationBudget, "\(entry)")
        }
    }

    func testTheDeclaredHeadersAreServedOnTheResponsesTheyAreDeclaredOn() async throws {
        // What makes the delivery entries more than their bytes: the origin sends what the entry
        // declares, and nothing it does not. Fetched rather than played, because the subject is
        // the origin's answer and a fetch asks for exactly the files named.
        let cached = HostileManifests.hlsCachedLivePlaylist()
        let origin = try await harness.serve(
            .hostile(cached),
            faults: FaultScript.Builder().addDeclaredResponseHeaders(of: cached).build())
        let directory = "hostile/hls-cached-live-playlist/severe/"
        for name in ["master.m3u8", "media.m3u8", "segment0.aac", "segment3.aac"] {
            _ = try await StockPlayback.fetch(origin.url(path: directory + name))
        }

        let served = Dictionary(
            uniqueKeysWithValues: origin.responses().map {
                ($0.request.path, $0.headers["cache-control"] ?? "(none)")
            })
        XCTAssertEqual(
            served,
            [
                "/\(directory)master.m3u8": "(none)",
                "/\(directory)media.m3u8": "public, max-age=600",
                "/\(directory)segment0.aac": "no-store",
                "/\(directory)segment3.aac": "no-store",
            ])

        let cors = HostileManifests.hlsCorsRefusesCredentials()
        let corsOrigin = try await harness.serve(
            .hostile(cors),
            faults: FaultScript.Builder().addDeclaredResponseHeaders(of: cors).build())
        _ = try await StockPlayback.fetch(corsOrigin.sourceURL)
        _ = try await StockPlayback.fetch(
            corsOrigin.url(path: "hostile/hls-cors-refuses-credentials/severe/segment2.aac"))
        XCTAssertEqual(corsOrigin.responses().count, 2)
        for response in corsOrigin.responses() {
            XCTAssertEqual(response.headers["access-control-allow-origin"], "*")
            XCTAssertEqual(response.headers["access-control-allow-credentials"], "true")
        }
    }

    func testATokenEntryIsServedAtItsSignedAddress() async throws {
        // The third shape a delivery defect takes: the address. The player is pointed at the signed
        // URL, query and all, and the origin serves the file under its path.
        let expiring = HostileManifests.hlsTokenExpiringInWindow(.severe, nowEpochSeconds: now)
        let origin = try await harness.serve(.hostile(expiring))
        let fetched = try await StockPlayback.fetch(origin.sourceURL)

        let request = try XCTUnwrap(origin.networkRequests().first)
        XCTAssertEqual(fetched.status, 200)
        XCTAssertEqual(request.path, "/hostile/hls-token-expiring-in-window/severe/master.m3u8")
        XCTAssertEqual(request.query, "expires=\(now + 4)&signature=corpus")
    }

    // MARK: - Observing

    /// One entry being watched: its player, and what has been seen of it so far.
    private final class Session {
        let entry: HostileStream
        let player: AVPlayer
        let ended: EndObserver
        var outcome: Outcome?
        var started = false
        var outsideMedia = false
        var lastPosition = 0.0
        var lastMovedAt: ContinuousClock.Instant?

        init(_ entry: HostileStream, _ player: AVPlayer, _ ended: EndObserver) {
            self.entry = entry
            self.player = player
            self.ended = ended
        }
    }

    /// Plays every entry at once, each from an origin of its own serving its declared headers,
    /// and says how far each got within ``observationBudget``.
    private func observe(_ corpus: [HostileStream]) async throws -> [String: Outcome] {
        var sessions: [Session] = []
        // Nothing left loading when the test ends, however it ends — a serve that throws part-way
        // included: an asset with loads in flight is what has held a whole run up in
        // AVFoundation's cleanup on this host.
        defer {
            for session in sessions {
                session.player.pause()
                let asset = session.player.currentItem?.asset
                session.player.replaceCurrentItem(with: nil)
                asset?.cancelLoading()
            }
        }
        for entry in corpus {
            let origin = try await harness.serve(
                .hostile(entry),
                faults: FaultScript.Builder().addDeclaredResponseHeaders(of: entry).build())
            let item = AVPlayerItem(url: origin.sourceURL)
            // The first variant listed, which is where a fresh process starts. Left unset, the
            // starting variant follows a throughput history AVFoundation keeps for the process, so
            // a later run in one process read a different row from the first (#460, ADR-0021
            // rule 4's #460 addendum). Only a multi-iteration run shows the difference. Any climb
            // after the start is still AVFoundation's.
            // ref: Apple, `AVPlayerItem.startsOnFirstEligibleVariant` (iOS 14, tvOS 14).
            item.startsOnFirstEligibleVariant = true
            let player = AVPlayer(playerItem: item)
            sessions.append(Session(entry, player, EndObserver(item)))
        }
        for session in sessions { session.player.play() }

        let clock = ContinuousClock()
        let deadline = clock.now + Self.observationBudget
        // Not `harness.until`: that bound is a failure, and here running out of it is how a live
        // or stalled entry is recorded rather than an error.
        while clock.now < deadline, sessions.contains(where: { $0.outcome == nil }) {
            for session in sessions where session.outcome == nil {
                read(session, at: clock.now)
            }
            try await Task.sleep(for: PlaybackHarness.pollInterval)
        }

        var outcomes: [String: Outcome] = [:]
        for session in sessions {
            outcomes[session.entry.description] =
                session.outcome ?? unfinished(session, at: clock.now)
        }
        return outcomes
    }

    /// Reads one session once, deciding its outcome where it has one: an error, or the end.
    private func read(_ session: Session, at now: ContinuousClock.Instant) {
        let item = session.player.currentItem
        if let error = item?.error ?? session.player.error,
            item?.status == .failed
                || session.player.status == .failed
        {
            session.outcome = .fails(Self.structural(error))
            return
        }
        if session.ended.hasEnded {
            session.outcome = session.outsideMedia ? .degrades : .playsToEnd
            return
        }
        let position = session.player.currentTime().seconds
        guard position.isFinite else { return }
        if position != session.lastPosition {
            session.lastPosition = position
            session.lastMovedAt = now
            session.started = true
        }
        // A segment of slack past the end, as the Kotlin observation allows: a position that far
        // out is one the stream cannot contain.
        let limit = Double(session.entry.durationMs + SyntheticHlsStream.segmentDurationMs) / 1_000
        if position < 0 || position > limit { session.outsideMedia = true }
    }

    /// The outcome of a session the budget ran out on.
    private func unfinished(_ session: Session, at now: ContinuousClock.Instant) -> Outcome {
        if !session.started { return .neverStarts }
        if session.outsideMedia { return .degrades }
        if let moved = session.lastMovedAt, now - moved <= Self.readyWindow { return .stillPlaying }
        return .stalls
    }

    /// An error's domain and code, and its underlying error's, as one line.
    private static func structural(_ error: Error) -> String {
        let error = error as NSError
        let underlying = (error.userInfo[NSUnderlyingErrorKey] as? NSError).map {
            " < \($0.domain) \($0.code)"
        }
        return "\(error.domain) \(error.code)\(underlying ?? "")"
    }

    /// `observed` as the literal ``recorded`` is written in, in the corpus's order.
    private static func literal(_ observed: [String: Outcome], in corpus: [HostileStream]) -> String
    {
        let rows = corpus.map { entry in
            "    \"\(entry.description)\": \(observed[entry.description].map(String.init(describing:)) ?? "nil"),"
        }
        return "[\n" + rows.joined(separator: "\n") + "\n]"
    }
}
