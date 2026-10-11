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
import CoreTelephony
import XCTest

@testable import SuperPlayerCore

/// `ConditionsBinding.swift`'s translations, at its `package` readers, with no device (#415's second
/// acceptance line): a path, a radio, a memory reading, a duration, a rate, a run of player activity
/// and an access log, each stated by hand and checked as the observation a policy is handed.
///
/// These are the readings a simulator cannot vary — it has one network, no radio and no memory
/// limit an `xctest` runner is told — so stating them is the only way each translation is seen at
/// all. That the readings are *taken*, on the triggers, is `PolicyPlaybackTests`' through the
/// harness.
final class ConditionsBindingTests: XCTestCase {

    private typealias Path = ConditionsBinding.PathReading

    // MARK: - Transport

    func testEachInterfaceIsItsTransport() {
        XCTAssertEqual(transport(.wifi), .wifi)
        XCTAssertEqual(transport(.wiredEthernet), .ethernet)
        XCTAssertEqual(transport(.cellular, isExpensive: true, generation: .lte), .cellular(.lte))
        XCTAssertEqual(transport(.cellular, isExpensive: true), .cellular(nil))
    }

    /// A personal hotspot is a cellular link reached over WiFi or a cable, and Apple marks it
    /// expensive; its generation is the other phone's, so it is unobserved even where this
    /// device's own radio reports one.
    func testAnExpensiveWifiOrWiredPathIsAHotspotsCellularLink() {
        XCTAssertEqual(transport(.wifi, isExpensive: true, generation: .nr), .cellular(nil))
        XCTAssertEqual(transport(.wiredEthernet, isExpensive: true), .cellular(nil))
    }

    /// No network, and one SuperPlayer does not classify, are both a transport — unknown — and
    /// neither is the nil that means nothing was observed.
    func testNoNetworkAndAnUnclassifiedOneAreUnknown() {
        XCTAssertEqual(transport(.none), .unknown)
        XCTAssertEqual(transport(.other), .unknown)
    }

    func testTheRadioCollapsesToThreeGenerations() {
        XCTAssertEqual(generation(CTRadioAccessTechnologyNR), .nr)
        XCTAssertEqual(generation(CTRadioAccessTechnologyNRNSA), .nr)
        XCTAssertEqual(generation(CTRadioAccessTechnologyLTE), .lte)
        XCTAssertEqual(generation(CTRadioAccessTechnologyWCDMA), .older)
        XCTAssertEqual(generation(CTRadioAccessTechnologyEdge), .older)
        XCTAssertNil(generation(nil))
    }

    // MARK: - Heap, stream type, rate

    /// Zero is the header's "not an app" or "past its limit", and either is "did not say".
    func testTheHeapBudgetIsWhatIsLeftAndZeroIsUnobserved() {
        XCTAssertNil(ConditionsBinding.heapBudgetBytes(availableMemoryBytes: 0))
        XCTAssertEqual(
            ConditionsBinding.heapBudgetBytes(availableMemoryBytes: 1_500_000_000), 1_500_000_000)
        XCTAssertEqual(
            ConditionsBinding.heapBudgetBytes(availableMemoryBytes: .max), .max,
            "clamped rather than trapped")
    }

    func testAnIndefiniteDurationIsLiveAndANumberIsOnDemand() {
        XCTAssertEqual(ConditionsBinding.streamType(duration: .indefinite), .live)
        XCTAssertEqual(
            ConditionsBinding.streamType(duration: CMTime(seconds: 30, preferredTimescale: 600)),
            .onDemand)
        XCTAssertNil(ConditionsBinding.streamType(duration: .invalid), "not yet read")
    }

    /// A pause is not a speed, and reverse play is one.
    func testARateIsASpeedAndAPauseIsNot() {
        XCTAssertNil(ConditionsBinding.playbackSpeed(rate: 0))
        XCTAssertEqual(ConditionsBinding.playbackSpeed(rate: 1.5), 1.5)
        XCTAssertEqual(ConditionsBinding.playbackSpeed(rate: -2), 2)
    }

    /// Only a wait to minimise stalls is a stall: the wait at start, while the rate is evaluated,
    /// and one for an item, are not.
    func testOnlyAWaitToMinimiseStallsIsAStall() {
        XCTAssertEqual(activity(.waitingToPlayAtSpecifiedRate, .toMinimizeStalls), .stalled)
        XCTAssertEqual(activity(.waitingToPlayAtSpecifiedRate, .evaluatingBufferingRate), .other)
        XCTAssertEqual(activity(.waitingToPlayAtSpecifiedRate, .noItemToPlay), .other)
        XCTAssertEqual(activity(.playing, nil), .playing)
        XCTAssertEqual(activity(.paused, nil), .paused)
    }

    // MARK: - Stall history

    func testAStallAfterPlaybackStartedThatResumesIsARebuffer() {
        var stalls = StallObservation()
        XCTAssertFalse(stalls.onActivity(.playing, atMs: 0))
        XCTAssertFalse(stalls.onActivity(.stalled, atMs: 5_000))

        XCTAssertTrue(stalls.onActivity(.playing, atMs: 5_750))

        XCTAssertEqual(
            stalls.current(atMs: 6_000),
            StallHistory(
                rebufferCount: 1, msSinceLastRebufferEnded: 250, lastRebufferDurationMs: 750))
    }

    /// The controls on the rule, so the count above is not every wait: a stall before the first
    /// play is start-up, one ended by a pause did not end in playback, and one a seek caused is the
    /// wait the viewer asked for — until the exclusion window has passed.
    func testStartUpAPausedStallAndASeeksStallAreNotRebuffers() {
        var startUp = StallObservation()
        XCTAssertFalse(startUp.onActivity(.stalled, atMs: 0))
        XCTAssertFalse(startUp.onActivity(.playing, atMs: 500))

        var paused = StallObservation()
        _ = paused.onActivity(.playing, atMs: 0)
        _ = paused.onActivity(.stalled, atMs: 1_000)
        _ = paused.onActivity(.paused, atMs: 1_500)
        XCTAssertFalse(paused.onActivity(.playing, atMs: 2_000))

        var sought = StallObservation()
        _ = sought.onActivity(.playing, atMs: 0)
        sought.onSeek(atMs: 1_000, playing: true)
        _ = sought.onActivity(.stalled, atMs: 1_000 + StallObservation.seekExclusionWindowMs)
        XCTAssertFalse(sought.onActivity(.playing, atMs: 3_000))
        _ = sought.onActivity(.stalled, atMs: 3_000 + StallObservation.seekExclusionWindowMs + 1)
        XCTAssertTrue(sought.onActivity(.playing, atMs: 5_000), "outside the window it counts")

        XCTAssertEqual(startUp.current(atMs: 9_000), .none)
        XCTAssertEqual(paused.current(atMs: 9_000), .none)
    }

    /// A seek made while not playing completes when playback starts again, and the stall before
    /// that is the seek's.
    func testASeekWhileNotPlayingCoversTheStallUntilPlaybackResumes() {
        var stalls = StallObservation()
        _ = stalls.onActivity(.playing, atMs: 0)
        _ = stalls.onActivity(.paused, atMs: 1_000)
        stalls.onSeek(atMs: 10_000, playing: false)
        _ = stalls.onActivity(.stalled, atMs: 20_000)

        XCTAssertFalse(stalls.onActivity(.playing, atMs: 30_000))
    }

    // MARK: - Throughput

    /// Eight samples' mean, population spread and nearest-rank 25th percentile, by hand: 1..8 Mbps
    /// has mean 4.5 M, spread √5.25 M ≈ 2,291,287, and its second-lowest sample, 2 M.
    func testTheEstimateIsTheWindowsThreeReadings() throws {
        var throughput = AccessLogThroughput()
        let mbps = (1...8).map { Double($0) * 1_000_000 }

        throughput.read(observedBitratesBps: [-1] + mbps, atMs: 1_000)
        let estimate = try XCTUnwrap(throughput.estimate(atMs: 1_400))

        XCTAssertEqual(estimate.meanBps, 4_500_000)
        XCTAssertEqual(estimate.spreadBps, 2_291_287)
        XCTAssertEqual(estimate.conservativeBps, 2_000_000)
        XCTAssertEqual(estimate.sampleCount, 8, "the unmeasured entry is not a sample")
        XCTAssertEqual(estimate.newestSampleAgeMs, 400)
    }

    /// Only the newest eight entries are the window, and a log that has recorded nothing measured
    /// has no estimate at all rather than one of zero.
    func testTheWindowIsTheNewestEightEntries() throws {
        var throughput = AccessLogThroughput()
        XCTAssertNil(throughput.estimate(atMs: 0))
        throughput.read(observedBitratesBps: [-1, .nan], atMs: 0)
        XCTAssertNil(throughput.estimate(atMs: 0))

        throughput.observe(
            observedBitratesBps: [100_000_000] + Array(repeating: 1_000_000, count: 8), atMs: 0)

        XCTAssertEqual(try XCTUnwrap(throughput.estimate(atMs: 0)).meanBps, 1_000_000)
    }

    /// The first estimate is material; then a move under a fifth of the last one announced is not,
    /// and one of a fifth or more is — measured from what was announced, so creeping does not
    /// accumulate unheard.
    func testAMaterialMoveIsAFifthOfTheLastEstimateAnnounced() {
        var throughput = AccessLogThroughput()
        XCTAssertFalse(throughput.movedMaterially(atMs: 0), "nothing to announce")

        throughput.read(observedBitratesBps: [1_000_000], atMs: 0)
        XCTAssertTrue(throughput.movedMaterially(atMs: 0))
        throughput.read(observedBitratesBps: [1_000_000, 1_300_000], atMs: 0)
        XCTAssertFalse(throughput.movedMaterially(atMs: 0), "1.15 M is 15% from 1 M")
        throughput.read(observedBitratesBps: [1_000_000, 1_300_000, 1_300_000], atMs: 0)
        XCTAssertTrue(throughput.movedMaterially(atMs: 0), "1.2 M is 20% from 1 M")
        XCTAssertFalse(throughput.movedMaterially(atMs: 0), "and is now what was announced")
    }

    // MARK: - Helpers

    private func transport(
        _ interface: Path.Interface, isExpensive: Bool = false,
        generation: CellularGeneration? = nil
    ) -> NetworkTransport {
        ConditionsBinding.transport(
            of: Path(interface: interface, isExpensive: isExpensive),
            cellularGeneration: generation)
    }

    private func generation(_ technology: String?) -> CellularGeneration? {
        ConditionsBinding.cellularGeneration(radioAccessTechnology: technology)
    }

    private func activity(_ status: AVPlayer.TimeControlStatus, _ reason: AVPlayer.WaitingReason?)
        -> StallObservation.Activity
    {
        ConditionsBinding.activity(status: status, reason: reason)
    }
}
