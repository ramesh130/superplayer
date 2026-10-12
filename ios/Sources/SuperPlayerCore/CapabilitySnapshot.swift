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
import UIKit
import VideoToolbox

/// What the device a session ran on can **do**, read whole and handed over whole — the Kotlin
/// `CapabilitySnapshot` and `capabilitySnapshotOf`, ADR-0015 rule 3's second seam, read on Apple's
/// platforms the way `docs/ios-plan.md` §3.9 decides.
///
/// It exists for one reader, `SuperPlayerDiagnostics`' `SessionBundle`, and it is that reader's
/// whole source of device facts, so **a field that is not here is a field the bundle cannot print**.
/// That is how ADR-0015 rule 10 — the seventh redaction rule, what the device can *do* and never
/// what it *is* — is enforced: by what this type carries, not by what its reader remembers to drop.
/// So it carries no model name, no model identifier, no `UIDevice.name`, no
/// `identifierForVendor`, no OS build string, no hardware component name, no display name and
/// nothing of the network, and none of them is read here at all.
///
/// What it carries, and the reading of each:
///
/// - **The platform and the OS version**, the Kotlin `apiLevel`'s analog: `iOS` or `tvOS`, decided
///   at compile time, and `major.minor` of `ProcessInfo.operatingSystemVersion`. The patch level
///   and the build string are refused — the library branches on majors (`#available(iOS 18, *)`)
///   and its documents cite minors (the 27.1 stall `SlowLinkPlaybackTests` records), and below that
///   a version is a narrower description of one device's update state than any decision here needs.
/// - **The memory budget**, `os_proc_available_memory()` in megabytes, through
///   ``ConditionsBinding/heapBudgetBytes(availableMemoryBytes:)`` — the reading the pool and the
///   policy's heap observation are made against, unknown where Apple's header gives zero (an
///   `xctest` runner). The Kotlin `lowRam` has no Apple reading and is not invented.
/// - **Hardware decode per video codec**, `VTIsHardwareDecodeSupported`, over the four video codecs
///   the realtime codec table maps (`RealtimeFormats.swift`): H.264, HEVC, VP9 and AV1. A yes or a
///   no per codec, which is the decoder table's iOS reading — Apple reports no instance limit, no
///   secure decoder and no profile ceiling (`DeviceCapacity.swift`), so those Kotlin fields are not
///   printed rather than printed `unknown` for ever.
/// - **The display**: the active mode's shorter edge in pixels, from the key window scene's screen,
///   and whether `AVPlayer` is eligible for HDR playback on it (`AVPlayer.eligibleForHDRPlayback`).
///   Apple's per-format HDR list, `availableHDRModes`, is deprecated from 26 for exactly that
///   boolean, so the iOS line says one fact where the Kotlin one lists formats. A process with no
///   scene — an `xctest` runner — has no display to read, and the edge is unknown.
///
/// **Why these pass rule 10's readership test on this platform** is argued at that rule's #429
/// addendum: the decisions the Kotlin library makes against its decoder table and display are, on
/// iOS, AVFoundation's — ADR-0020 rule 1 brought no other engine and rule 6 ports no selection gate —
/// so the snapshot carries what the engine a SuperPlayer composes on decided against.
///
/// Read on the main actor, because the display is a UIKit reading; nothing here is cached, so a
/// bundle states the device as it was when the bundle was built.
package struct CapabilitySnapshot: Sendable, Hashable {

    /// `iOS` or `tvOS`.
    package var platform: String

    /// `major.minor` of the running OS.
    package var osVersion: String

    /// `os_proc_available_memory()` in whole megabytes, or nil where the platform gave zero.
    package var memoryBudgetMb: Int?

    /// One entry per codec in ``videoCodecs``' order.
    package var videoDecoders: [VideoDecoder]

    /// The shorter edge of the display's active mode, in pixels, or nil where there is no display
    /// to read.
    package var displayShortEdgePx: Int?

    /// `AVPlayer.eligibleForHDRPlayback`.
    package var hdrPlaybackEligible: Bool

    package init(
        platform: String, osVersion: String, memoryBudgetMb: Int?, videoDecoders: [VideoDecoder],
        displayShortEdgePx: Int?, hdrPlaybackEligible: Bool
    ) {
        self.platform = platform
        self.osVersion = osVersion
        self.memoryBudgetMb = memoryBudgetMb
        self.videoDecoders = videoDecoders
        self.displayShortEdgePx = displayShortEdgePx
        self.hdrPlaybackEligible = hdrPlaybackEligible
    }

    /// One video codec, named by the MIME type the Kotlin bundle names it by, so one `grep` reads
    /// both platforms' decoder lines.
    package struct VideoDecoder: Sendable, Hashable {
        package var mimeType: String
        /// `VTIsHardwareDecodeSupported` for the codec.
        package var hardware: Bool

        package init(mimeType: String, hardware: Bool) {
            self.mimeType = mimeType
            self.hardware = hardware
        }
    }

    /// The video codecs asked about, in the order they print: the realtime codec table's four
    /// (`RealtimeFormats.swift`), which are every video codec this library names, under the MIME
    /// types Android's decoder table spells them with.
    package static let videoCodecs: [(mimeType: String, codecType: CMVideoCodecType)] = [
        ("video/avc", kCMVideoCodecType_H264),
        ("video/hevc", kCMVideoCodecType_HEVC),
        ("video/x-vnd.on2.vp9", kCMVideoCodecType_VP9),
        ("video/av01", kCMVideoCodecType_AV1),
    ]

    /// The device now — the one reader of this snapshot's facts that asks the platform.
    ///
    /// ref: https://developer.apple.com/documentation/videotoolbox/vtishardwaredecodesupported(_:)
    /// ref: https://developer.apple.com/documentation/avfoundation/avplayer/eligibleforhdrplayback
    /// ref: `<os/proc.h>`, `os_proc_available_memory`
    @MainActor
    package static func current() -> CapabilitySnapshot {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        let budget = ConditionsBinding.heapBudgetBytes(
            availableMemoryBytes: DeviceCapacity.processAvailableMemory())
        return CapabilitySnapshot(
            platform: platformName,
            osVersion: "\(version.majorVersion).\(version.minorVersion)",
            memoryBudgetMb: budget.map { Int($0 / bytesPerMegabyte) },
            videoDecoders: videoCodecs.map {
                VideoDecoder(
                    mimeType: $0.mimeType, hardware: VTIsHardwareDecodeSupported($0.codecType))
            },
            displayShortEdgePx: activeDisplayShortEdgePx(),
            hdrPlaybackEligible: AVPlayer.eligibleForHDRPlayback)
    }

    private static let bytesPerMegabyte: Int64 = 1024 * 1024

    private static var platformName: String {
        #if os(tvOS)
            "tvOS"
        #else
            "iOS"
        #endif
    }

    /// The shorter edge of the active mode of the screen the foreground window scene is on, or nil.
    ///
    /// The application is reached through key-value coding rather than `UIApplication.shared`,
    /// because a process with no application object — the `xctest` runner every iOS test runs in —
    /// answers nil to the first and traps on the second; and through a scene rather than
    /// `UIScreen.main`, which is deprecated from 26 for exactly this spelling.
    @MainActor
    private static func activeDisplayShortEdgePx() -> Int? {
        guard
            let application = (UIApplication.self as AnyObject).value(forKey: "sharedApplication")
                as? UIApplication
        else { return nil }
        let scenes = application.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        guard let size = scene?.screen.currentMode?.size, size.width > 0, size.height > 0 else {
            return nil
        }
        return Int(min(size.width, size.height))
    }
}
