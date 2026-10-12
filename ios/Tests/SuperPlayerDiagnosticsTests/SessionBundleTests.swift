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

import SuperPlayerCore
import SuperPlayerDiagnostics
import SuperPlayerTelemetry
import XCTest

/// The bundle's shape over a stated trace and a stated device: the header, where the `capability`
/// lines go and how each reads, `unknown` kept apart from an answer, and the structural reading a
/// golden compares. No player: what a session puts in a bundle is `SessionBundlePlaybackTests`'.
final class SessionBundleTests: XCTestCase {

    private let trace = SessionTrace(
        lines: [
            "+0 item id=series/expanse/s01e01 reason=PLAYLIST_CHANGED",
            "+0 state BUFFERING",
            "+250 state READY",
            "+8250 telemetry SessionEnded droppedEventCount=0",
        ], timed: true)

    func testTheBundleIsTheTraceUnderThreeHeaderLinesAndTheCapabilitiesFirst() {
        let bundle = SessionBundle(trace: trace, capabilities: .stated)

        XCTAssertEqual(
            bundle.format(),
            """
            superplayer-session-bundle v1
            superplayer-session-trace v1
            timings relative-ms
            +0 capability device platform=iOS osVersion=18.4 memoryBudgetMb=2048
            +0 capability decoder mime=video/avc hardware=true
            +0 capability decoder mime=video/hevc hardware=true
            +0 capability decoder mime=video/x-vnd.on2.vp9 hardware=false
            +0 capability decoder mime=video/av01 hardware=false
            +0 capability display shortEdgePx=1179 hdrPlayback=true
            +0 item id=series/expanse/s01e01 reason=PLAYLIST_CHANGED
            +0 state BUFFERING
            +250 state READY
            +8250 telemetry SessionEnded droppedEventCount=0

            """)
        // The version is the Kotlin bundle's, shared and not restated.
        XCTAssertEqual(SessionBundle.formatVersion, 1)
    }

    /// A reading the platform did not give is `unknown`, which is a different fact from a value;
    /// the control is the stated device above, where both read as numbers.
    func testWhatThePlatformDidNotSayReadsUnknown() {
        var device = CapabilitySnapshot.stated
        device.memoryBudgetMb = nil
        device.displayShortEdgePx = nil

        let lines = SessionBundle(trace: trace, capabilities: device).lines

        XCTAssertTrue(
            lines.contains(
                "+0 capability device platform=iOS osVersion=18.4 memoryBudgetMb=unknown"))
        XCTAssertTrue(lines.contains("+0 capability display shortEdgePx=unknown hdrPlayback=true"))
    }

    /// An untimed trace makes an untimed bundle: the `+0` column only where the trace carries one.
    func testAnUntimedTraceGetsUntimedCapabilities() {
        let bundle = SessionBundle(trace: trace.withoutTimings(), capabilities: .stated)

        XCTAssertFalse(bundle.timed)
        XCTAssertEqual(
            bundle.lines.first, "capability device platform=iOS osVersion=18.4 memoryBudgetMb=2048")
        XCTAssertTrue(bundle.format().contains("\ntimings omitted\n"))
    }

    /// The structural reading keeps every capability whole and reads the trace half as
    /// `SessionTrace.structural()` does — the time masked, the kinds in rank order.
    func testTheStructuralReadingIsTheCapabilitiesThenTheTracesOwn() {
        let timed = SessionTrace(
            lines: [
                "+0 item id=a reason=PLAYLIST_CHANGED",
                "+10 telemetry SessionStarted profile=VIDEO_ON_DEMAND",
                "+20 state READY",
                "+30 load completed manifest durationMs=12",
            ], timed: true)

        let structural = SessionBundle(trace: timed, capabilities: .stated).structural()

        XCTAssertEqual(
            structural.lines,
            SessionBundle.capabilityLines(.stated).map { "capability \($0)" }
                + [
                    "item id=a reason=PLAYLIST_CHANGED",
                    "state READY",
                    "load completed manifest durationMs=*",
                    "telemetry SessionStarted profile=VIDEO_ON_DEMAND",
                ])
        XCTAssertFalse(structural.timed)
    }

    /// The reader of the device the bundle uses outside a test: every codec asked about in order,
    /// the platform's own name and its `major.minor`, and — in the `xctest` runner, which is no app —
    /// no memory reading and no display, read as unknown rather than guessed.
    @MainActor
    func testTheDeviceReadsEveryCodecInOrderAndNothingItWasNotGiven() {
        let device = CapabilitySnapshot.current()
        let version = ProcessInfo.processInfo.operatingSystemVersion

        XCTAssertEqual(
            device.videoDecoders.map(\.mimeType),
            ["video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01"])
        XCTAssertEqual(device.platform, "iOS")
        XCTAssertEqual(device.osVersion, "\(version.majorVersion).\(version.minorVersion)")
        XCTAssertNil(device.displayShortEdgePx)
        // No hardware answer is asserted: the simulator answers no even for H.264 (observed on
        // iOS 27.1), which says nothing of a device, and a device's answer is its own.
    }
}

extension CapabilitySnapshot {

    /// A device every bundle test states, so a committed golden pins no host's OS or decoders: an
    /// iPhone-sized panel eligible for HDR, hardware H.264 and HEVC, and neither VP9 nor AV1.
    static let stated = CapabilitySnapshot(
        platform: "iOS", osVersion: "18.4", memoryBudgetMb: 2048,
        videoDecoders: [
            VideoDecoder(mimeType: "video/avc", hardware: true),
            VideoDecoder(mimeType: "video/hevc", hardware: true),
            VideoDecoder(mimeType: "video/x-vnd.on2.vp9", hardware: false),
            VideoDecoder(mimeType: "video/av01", hardware: false),
        ],
        displayShortEdgePx: 1179, hdrPlaybackEligible: true)
}
