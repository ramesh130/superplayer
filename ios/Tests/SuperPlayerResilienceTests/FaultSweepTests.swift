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
import Combine
import SuperPlayerCore
import SuperPlayerResilience
import SuperPlayerTestMedia
import SuperPlayerTestkit
import XCTest

/// i5's exit criterion for the injected half of it (#421): **every kind of fault the origin can
/// produce either recovers or ends in a named class** — the Kotlin `FaultSweepTest`, and `PRD.md`
/// Part 4's "every injected fault either recovers or produces a typed, actionable error. Zero
/// unclassified errors".
///
/// One session per ``FaultScript/Kind``, played through a player a consumer configures — a profile
/// and `Resilience.standard()`, nothing else — and the outcome of each recorded in ``swept``. The
/// table is the deliverable rather than the assertions around it, for the Kotlin reason: what a
/// reader wants from a phase exit is *which* faults recover and which are only named.
///
/// **One protocol, not two.** The Kotlin table is ten kinds by two protocols; `AVPlayer` plays no
/// DASH (ADR-0020 rule 6), so this one is a column. **Eight kinds, not ten**, because the script
/// holds only what a server can produce (ADR-0021 rule 3): the Kotlin `failDnsResolution`,
/// `failTlsHandshake` and `timeOutConnection` are failures inside the client, and the script's own
/// doc comment says so; it has `addResponseHeaders` instead, which the Kotlin script states on the
/// content.
///
/// ## How "a new kind is visibly missing" is made true without reflection
///
/// The Kotlin sweep reads the builder's methods by reflection, because a list kept beside the
/// builder is the thing that would be forgotten. Swift has no reflection over a type's methods, so
/// the enumeration is a type instead: ``FaultScript/Kind`` is `CaseIterable`, every builder method
/// appends a fault whose effect names its kind through an exhaustive `switch` in the script itself,
/// and this file is held to it twice over —
///
/// - ``script(for:)`` is an exhaustive `switch` over the kinds, so a kind added to the enum is a
///   **compile error** here until it is swept;
/// - ``testEveryFaultKindTheOriginCanProduceIsSwept`` holds ``swept``'s keys to `allCases`, and
///   ``testEveryScriptCarriesTheKindItIsFiledUnder`` holds each script's ``FaultScript/kinds`` to the
///   one kind it is filed under, so a builder method that appended the wrong kind — or a row filed
///   under a kind its script does not inject — fails rather than sweeping something else.
///
/// The gap that leaves is a builder method added *without* a kind: the effect's exhaustive `switch`
/// makes a new effect name a kind, and a method that reused an existing effect is a second spelling
/// of a kind already swept, which is not a gap.
///
/// ## What is asserted
///
/// - **Every cell is recovered or ended typed**, read off ``swept`` rather than off a second run
///   (``testNoSweptFaultEndsUnclassifiedOrStalls``), so a row moved into ``Outcome/stalled`` is the
///   criterion broken and not only a record out of date.
/// - **The table is what a run observes** (``testEveryInjectedFaultRecoversOrEndsInANamedClass``),
///   with the whole table reported when any cell moves, for `HostileManifestCorpusTests`' reason.
/// - **A named failure is one an app and a pipeline can act on**: a class of the taxonomy's
///   `Branch.Leaf` shape and a message key, checked as each session ends.
///
/// One instance per kind, addressed at ``faultedSegment`` and never relenting, for the Kotlin
/// reason: what varies inside a kind is each rung's own test, collected in
/// `FallbackRungCoverageTests`. The live half of the cache kind — a frozen playlist — is
/// `StaleLivePlaylistPlaybackTests`', because here, as in the Kotlin row, the cache holds content
/// that was never going to change.
@MainActor
final class FaultSweepTests: XCTestCase {

    /// How a swept session ended. Only the first two satisfy `PRD.md` Part 4.
    enum Outcome: Hashable, CustomStringConvertible {
        /// No error, and playback reached the end — or, under a slow link, the origin was still
        /// serving segments past the faulted one when the budget ran out.
        case recovered
        /// A ``SuperPlayerError`` on ``SuperPlayer/errors``: rung 6, with this class.
        case endedTyped(String)
        /// An error nothing classified: the item failed and no typed error came, or something other
        /// than a ``SuperPlayerError`` did. The criterion's "zero unclassified", broken.
        case endedUnclassified(String)
        /// Neither: no error, and nothing past the fault inside the budget.
        case stalled

        var description: String {
            switch self {
            case .recovered: ".recovered"
            case .endedTyped(let causeClass): ".endedTyped(\"\(causeClass)\")"
            case .endedUnclassified(let what): ".endedUnclassified(\"\(what)\")"
            case .stalled: ".stalled"
            }
        }
    }

    /// Six two-second segments, so there are several past ``faultedSegment`` to get to — the Kotlin
    /// `SEGMENTS`.
    static let segments = 6

    /// Late enough that segments play before the fault, and well inside ``segments`` — the Kotlin
    /// `FAULTED_SEGMENT`.
    static let faultedSegment = 2

    /// Half a target duration: slow, and inside what AVFoundation will wait for — **not** the Kotlin
    /// `LATENCY_MS`, ten segment durations, and the departure is a finding rather than a tuning.
    ///
    /// The Kotlin row is "a delay, not a failure", and Media3 waits out twenty seconds. AVFoundation
    /// does not: it gives up on a segment after about one target duration, logs a -12889 ("no
    /// response for media file"), asks again, and — every ask held as long — waits at the faulted
    /// segment for as long as the fault lasts, with no error and no end (observed, #421, for seventy
    /// seconds of a two-second-target stream). So on this platform twenty seconds is not slow but
    /// never, and a cell that waited for ever would be `.stalled` — the criterion broken, by a fault
    /// nothing here detects. The row keeps the Kotlin row's meaning, a delay AVFoundation rides out,
    /// and the never-answering segment is recorded as a gap in `docs/testing.md`'s *The fault sweep
    /// on iOS* rather than swept under a number chosen to pass.
    static let latencyMs: Int64 = 1_000

    /// A header's worth of a segment and no more — the Kotlin `TRUNCATED_AT_BYTES`.
    static let truncatedAtBytes = 64

    /// Long past the whole stream — the Kotlin `CACHE_MAX_AGE_SECONDS`.
    static let cacheMaxAgeSeconds: Int64 = 600

    /// How long the sweep waits for every cell to end: a ceiling, never an assertion (ADR-0021
    /// rule 4). Real time, so it has to hold the slowest cell that ends — twelve seconds of media
    /// with a segment at half the stream's rate inside it — with room for a loaded host; a cell
    /// still unended at the ceiling is judged by what the origin served, as the Kotlin sweep judges
    /// one at its budget.
    static let budget: Duration = .seconds(120)

    /// How each swept fault ended, as of the change that added it — a record as well as a
    /// criterion, for the Kotlin reason: a cell that moves from one class to another is a behaviour
    /// change someone has to explain even though both values pass.
    static let swept: [FaultScript.Kind: Outcome] = [
        // Slow is not broken, at a delay AVFoundation waits for (``latencyMs`` says why it is not
        // the Kotlin twenty seconds).
        .latency: .recovered,
        .throughputCap: .recovered,
        // A 500 that never relents: AVFoundation asks for the segment again itself (rung 1 is the
        // engine's, ADR-0020 rule 6), rungs 4 and 5 have nowhere to go on one source, and rung 6
        // names it — the network rather than the edge, because a 5xx is the server having failed
        // rather than having refused this request. The Kotlin row's class.
        .httpStatus: .endedTyped("Transient.Network"),
        // The Kotlin HLS cell's class: a segment whose body ends early is a failed load.
        .truncation: .endedTyped("Transient.Network"),
        .connectionReset: .endedTyped("Transient.Network"),
        // A credential that cannot be replaced: the edge refusing this session, the distinction
        // `Transient.CdnEdge` exists for.
        .tokenExpiry: .endedTyped("Transient.CdnEdge"),
        // A cache holding on-demand playlists that were never going to change: nothing is lost.
        // The row that matters is the live one, `StaleLivePlaylistPlaybackTests`'.
        .intermediaryCache: .recovered,
        // Headers on a segment no native player reads.
        .responseHeaders: .recovered,
    ]

    /// One fault of `kind`, addressed at ``faultedSegment`` and never relenting — an exhaustive
    /// `switch`, which is what makes a kind added to ``FaultScript/Kind`` a compile error here
    /// until it is swept.
    static func script(for kind: FaultScript.Kind) -> FaultScript {
        let segment = ResourceKind.mediaSegment
        let builder = FaultScript.Builder()
        switch kind {
        case .latency:
            // The one kind whose instance is not the Kotlin one: ``latencyMs`` says why.
            return builder.addLatencyMs(latencyMs, kind: segment, index: faultedSegment).build()
        case .throughputCap:
            // Half the stream's declared bitrate: too slow for the rendition, and still delivering —
            // the fault that must not be recorded as a failure of anything.
            return builder.capThroughputBps(
                Int64(SyntheticHlsStream.declaredBitrateBps / 2), kind: segment,
                index: faultedSegment
            ).build()
        case .httpStatus:
            return builder.failWithHttpStatus(
                FaultScript.httpServerError, kind: segment, index: faultedSegment
            ).build()
        case .truncation:
            return builder.truncateAfterBytes(
                truncatedAtBytes, kind: segment, index: faultedSegment
            )
            .build()
        case .connectionReset:
            return builder.resetConnection(kind: segment, index: faultedSegment).build()
        case .tokenExpiry:
            // Not refreshable, for the Kotlin reason: this player has no `HeaderProvider`, so the
            // row says what a session does when the credential cannot be replaced.
            return builder.expireTokenAtSegment(faultedSegment).build()
        case .intermediaryCache:
            // A shared cache holding this on-demand stream's playlists for ten minutes and
            // disregarding revalidation: for content that does not change, a cache doing its job.
            return builder.serveThroughCache(
                maxAgeSeconds: cacheMaxAgeSeconds, honoursNoCache: false, kind: .manifest
            ).build()
        case .responseHeaders:
            // A CDN's header set on the faulted segment — the delivery pathologies' raw material,
            // which no native player reads; the row that would catch a player that did.
            return builder.addResponseHeaders(
                ["Cache-Control": "no-store", "Access-Control-Allow-Origin": "*"], kind: segment,
                index: faultedSegment
            ).build()
        }
    }

    private let harness = PlaybackHarness()
    private var players: [SuperPlayer] = []
    private var subscriptions: Set<AnyCancellable> = []

    override func tearDown() async throws {
        subscriptions = []
        for player in players { player.release() }
        players = []
        harness.stop()
        try await super.tearDown()
    }

    func testEveryFaultKindTheOriginCanProduceIsSwept() {
        XCTAssertFalse(FaultScript.Kind.allCases.isEmpty)
        XCTAssertEqual(Set(Self.swept.keys), Set(FaultScript.Kind.allCases))
    }

    func testEveryScriptCarriesTheKindItIsFiledUnder() {
        for kind in FaultScript.Kind.allCases {
            XCTAssertEqual(Self.script(for: kind).kinds, [kind], "\(kind)")
        }
    }

    func testNoSweptFaultEndsUnclassifiedOrStalls() {
        // The criterion itself, read off the record: a row moved into `.stalled` or
        // `.endedUnclassified` by a later change fails here as the criterion broken, and in the
        // playing test as a record out of date.
        for (kind, outcome) in Self.swept {
            switch outcome {
            case .recovered, .endedTyped: break
            case .endedUnclassified, .stalled: XCTFail("\(kind) \(outcome)")
            }
        }
    }

    /// Plays every kind at once — one origin and one player each, so no cell's requests are
    /// another's — and compares the whole table.
    func testEveryInjectedFaultRecoversOrEndsInANamedClass() async throws {
        var cells: [FaultScript.Kind: Cell] = [:]
        for kind in FaultScript.Kind.allCases {
            let origin = try await harness.serve(
                .hls(segmentCount: Self.segments), faults: Self.script(for: kind))
            cells[kind] = play(origin)
        }

        // Every cell ended — or the ceiling, after which a cell is judged by what it reached.
        try? await harness.until("every swept session ends", timeout: Self.budget) {
            cells.values.allSatisfy(\.hasEnded)
        }
        let observed = cells.mapValues(\.outcome)

        if observed != Self.swept {
            let literal = FaultScript.Kind.allCases.map { "        .\($0): \(observed[$0]!)," }
            XCTFail(
                "The sweep no longer reads as recorded. Observed, as the literal:\n"
                    + literal.joined(separator: "\n"))
        }
    }

    /// One swept session: its player, its origin, and what its error publisher said.
    private func play(_ origin: LoopbackOrigin) -> Cell {
        var configuration = SuperPlayer.Configuration()
        configuration.resilience = Resilience.standard()
        let player = SuperPlayer(configuration: configuration)
        players.append(player)
        let cell = Cell(player: player, origin: origin)
        player.errors.sink { error in MainActor.assumeIsolated { cell.errors.append(error) } }
            .store(in: &subscriptions)
        player.setMediaRequest(
            MediaRequest(contentId: "series/expanse/s01e05", sources: [origin.sourceURL]))
        player.avPlayer.play()
        return cell
    }

    @MainActor
    final class Cell {
        let player: SuperPlayer
        let origin: LoopbackOrigin
        var errors: [any Error] = []

        init(player: SuperPlayer, origin: LoopbackOrigin) {
            self.player = player
            self.origin = origin
        }

        /// The last second of the stream — its segments are `SyntheticHlsStream.segmentDurationMs`
        /// long — where playback has got past every segment.
        private var endSeconds: Double {
            Double(FaultSweepTests.segments) * Double(SyntheticHlsStream.segmentDurationMs) / 1_000
                - 1
        }

        var hasEnded: Bool {
            !errors.isEmpty || player.avPlayer.currentItem?.status == .failed
                || player.avPlayer.currentTime().seconds >= endSeconds
        }

        var outcome: Outcome {
            if let typed = errors.first as? SuperPlayerError {
                // "Named" is a class a pipeline groups by and a message an app can write, not
                // merely a non-null string (ADR-0011 rule 1).
                guard
                    typed.causeClass.range(
                        of: #"^[A-Za-z]+\.[A-Za-z]+$"#, options: .regularExpression) != nil,
                    !typed.userMessageKey.isEmpty
                else { return .endedUnclassified("\(typed)") }
                return .endedTyped(typed.causeClass)
            }
            if let other = errors.first { return .endedUnclassified("\(type(of: other))") }
            if let error = player.avPlayer.currentItem?.error as NSError? {
                return .endedUnclassified("\(error.domain) \(error.code)")
            }
            return playedPastTheFault ? .recovered : .stalled
        }

        /// Whether the origin served a segment beyond the faulted one — the Kotlin
        /// `playedPastTheFault`, counted at the origin, because a slow link is a recovery and a
        /// stall is not, and the two look alike from the player's state.
        private var playedPastTheFault: Bool {
            Set(origin.networkRequests().filter { $0.kind == .mediaSegment }.map(\.path)).count
                > FaultSweepTests.faultedSegment + 1
        }
    }
}
