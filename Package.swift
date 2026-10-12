// swift-tools-version: 6.0
//
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

// The iOS and tvOS half of SuperPlayer. This manifest is the one iOS file outside `ios/`, because
// SwiftPM resolves a `.package(url:)` dependency by reading the manifest at the repository root
// (docs/ios-plan.md §2). Every target therefore names its path under `ios/`.
//
// Dependencies point inward toward SuperPlayerCore under docs/modules.md's phase rule, and
// `./gradlew verifyModulePhaseRule` reads this file to hold them to that document's iOS table. It
// reads the file as text, so a target keeps the documented `.target(name:dependencies:path:)`
// shape and is never assembled from a variable — `ModulePhaseRuleTest` says what the reading sees.

import PackageDescription

let package = Package(
    name: "SuperPlayer",
    // iOS 17 and tvOS 17 are the floor; an iOS 18 feature is gated at runtime and refused typed
    // below it, which is docs/ios-plan.md §2's decision and the `HttpStack.httpEngine()` shape.
    platforms: [.iOS(.v17), .tvOS(.v17)],
    products: [
        .library(name: "SuperPlayerCore", targets: ["SuperPlayerCore"]),
        .library(name: "SuperPlayerTelemetry", targets: ["SuperPlayerTelemetry"]),
        .library(name: "SuperPlayerABR", targets: ["SuperPlayerABR"]),
        .library(name: "SuperPlayerPreload", targets: ["SuperPlayerPreload"]),
        .library(name: "SuperPlayerResilience", targets: ["SuperPlayerResilience"]),
        .library(name: "SuperPlayerDRM", targets: ["SuperPlayerDRM"]),
        .library(name: "SuperPlayerOffline", targets: ["SuperPlayerOffline"]),
        .library(name: "SuperPlayerTV", targets: ["SuperPlayerTV"]),
        .library(name: "SuperPlayerDiagnostics", targets: ["SuperPlayerDiagnostics"]),
        .library(name: "SuperPlayerRealtime", targets: ["SuperPlayerRealtime"]),
        .library(name: "SuperPlayerTestkit", targets: ["SuperPlayerTestkit"]),
        .library(name: "SuperPlayerTestMedia", targets: ["SuperPlayerTestMedia"]),
        // SuperPlayerMoQ is not declared: its binary target is i9's (docs/ios-plan.md §7).
    ],
    targets: [
        .target(
            name: "SuperPlayerCore",
            path: "ios/Sources/SuperPlayerCore",
            // Named rather than left to `import AVKit`'s autolinking: the one AVKit member core
            // uses, `AVPlayerItem.externalMetadata`, is an Objective-C category reached by message
            // send and references no symbol, so the linker drops an autolinked AVKit it sees unused
            // and the setter is then an unrecognized selector at run time — observed as exactly
            // that crash under Xcode 27 and the iOS 27.1 simulator before this line was added.
            linkerSettings: [.linkedFramework("AVKit")]
        ),
        .target(
            name: "SuperPlayerTelemetry",
            dependencies: ["SuperPlayerCore"],
            path: "ios/Sources/SuperPlayerTelemetry"
        ),
        .target(
            name: "SuperPlayerABR",
            dependencies: ["SuperPlayerCore"],
            path: "ios/Sources/SuperPlayerABR"
        ),
        .target(
            name: "SuperPlayerPreload",
            dependencies: ["SuperPlayerCore"],
            path: "ios/Sources/SuperPlayerPreload"
        ),
        .target(
            name: "SuperPlayerResilience",
            dependencies: ["SuperPlayerCore"],
            path: "ios/Sources/SuperPlayerResilience"
        ),
        .target(
            name: "SuperPlayerDRM",
            dependencies: ["SuperPlayerCore"],
            path: "ios/Sources/SuperPlayerDRM",
            // The offline store's index is SQLite, the system's: the SDK's `SQLite3` module links
            // it by itself, and naming it here is where a reader of the manifest finds the link
            // (`OfflineLicenceIndex.swift`). No package dependency.
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        .target(
            name: "SuperPlayerOffline",
            dependencies: ["SuperPlayerCore"],
            path: "ios/Sources/SuperPlayerOffline",
            // The store's index is SQLite, the system's, as SuperPlayerDRM's offline store is
            // (`DownloadIndex.swift`). No package dependency.
            linkerSettings: [.linkedLibrary("sqlite3")]
        ),
        .target(
            name: "SuperPlayerTV",
            dependencies: ["SuperPlayerCore"],
            path: "ios/Sources/SuperPlayerTV"
        ),
        // Core and telemetry, as ADR-0015 rule 1 has it for superplayer-diagnostics.
        .target(
            name: "SuperPlayerDiagnostics",
            dependencies: ["SuperPlayerCore", "SuperPlayerTelemetry"],
            path: "ios/Sources/SuperPlayerDiagnostics"
        ),
        .target(
            name: "SuperPlayerRealtime",
            dependencies: ["SuperPlayerCore"],
            path: "ios/Sources/SuperPlayerRealtime"
        ),
        // The loopback origin serves SuperPlayerTestMedia's streams (docs/ios-plan.md §5).
        .target(
            name: "SuperPlayerTestkit",
            dependencies: ["SuperPlayerCore", "SuperPlayerTestMedia"],
            path: "ios/Sources/SuperPlayerTestkit"
        ),
        // Depends on nothing, so that core's tests and testkit can both reach it — the reason
        // superplayer-testmedia is phase 1 (docs/modules.md).
        .target(
            name: "SuperPlayerTestMedia",
            path: "ios/Sources/SuperPlayerTestMedia"
        ),
        // The benchmark (#414): benchmark/'s matrix on the simulator, its sources under
        // ios/Benchmark/. A target and no product — nothing ships it — and in this manifest rather than
        // a package of its own because a SuperPlayer playing video in xctest needs core's `package`
        // lifecycle seam (ios/Benchmark/README.md). Phase 3 in docs/modules.md, the latest of what
        // it measures with since arm (d) brought in SuperPlayerABR (#416); nothing depends on it.
        .target(
            name: "SuperPlayerBenchmark",
            dependencies: [
                "SuperPlayerCore", "SuperPlayerTelemetry", "SuperPlayerTestkit", "SuperPlayerABR",
            ],
            path: "ios/Benchmark/Sources/SuperPlayerBenchmark"
        ),
        .testTarget(
            name: "SuperPlayerCoreTests",
            dependencies: ["SuperPlayerCore"],
            path: "ios/Tests/SuperPlayerCoreTests"
        ),
        // The harness too, because the collector's tests play: testkit is phase 2 as telemetry is,
        // and superplayer-telemetry's own tests drive the Kotlin harness the same way.
        .testTarget(
            name: "SuperPlayerTelemetryTests",
            dependencies: ["SuperPlayerTelemetry", "SuperPlayerTestkit", "SuperPlayerCore"],
            path: "ios/Tests/SuperPlayerTelemetryTests"
        ),
        // Core for the conditions the pure tests state, and the harness for the one that plays a
        // shaped link: testkit is phase 2, below this one, as superplayer-abr's own tests drive the
        // Kotlin harness.
        .testTarget(
            name: "SuperPlayerABRTests",
            dependencies: ["SuperPlayerABR", "SuperPlayerCore", "SuperPlayerTestkit"],
            path: "ios/Tests/SuperPlayerABRTests"
        ),
        // The harness and core too, because a prefetch is a count of what reached an origin:
        // testkit is phase 2, and superplayer-preload's own tests drive the Kotlin harness.
        .testTarget(
            name: "SuperPlayerPreloadTests",
            dependencies: ["SuperPlayerPreload", "SuperPlayerTestkit", "SuperPlayerCore"],
            path: "ios/Tests/SuperPlayerPreloadTests"
        ),
        // Core, the harness and the collector too, because the typed error's tests play and read
        // telemetry's classification: both are phase 2, below this one, as superplayer-resilience's
        // own TypedErrorPlaybackTest drives the Kotlin harness and QoeCollector.
        .testTarget(
            name: "SuperPlayerResilienceTests",
            dependencies: [
                "SuperPlayerResilience", "SuperPlayerCore", "SuperPlayerTestkit",
                "SuperPlayerTelemetry", "SuperPlayerTestMedia",
            ],
            path: "ios/Tests/SuperPlayerResilienceTests"
        ),
        // Core, the harness, the classifier and the collector too: the exchange is driven through a
        // real player against the harness's licence server, and what it fails on and measures is
        // read where a consumer reads it — all phase 5 or below, as superplayer-drm's own tests
        // drive the Kotlin harness with `setResilience` and `QoeCollector`.
        .testTarget(
            name: "SuperPlayerDRMTests",
            dependencies: [
                "SuperPlayerDRM", "SuperPlayerCore", "SuperPlayerTestkit",
                "SuperPlayerResilience", "SuperPlayerTelemetry",
            ],
            path: "ios/Tests/SuperPlayerDRMTests"
        ),
        // Core, the harness and the classifier too: a download is counted at the loopback origin,
        // played offline through a real player and named by the resilience a consumer configures —
        // all phase 5 or below, as superplayer-offline's own tests drive the Kotlin harness with
        // `setResilience`. And SuperPlayerDRM, phase 6, for a protected download's licence (#425),
        // as `DownloadLicenceTest` depends on superplayer-drm: the test target's dependency, never
        // the product's (ADR-0013 rule 1).
        .testTarget(
            name: "SuperPlayerOfflineTests",
            dependencies: [
                "SuperPlayerOffline", "SuperPlayerCore", "SuperPlayerTestkit",
                "SuperPlayerResilience", "SuperPlayerDRM",
            ],
            path: "ios/Tests/SuperPlayerOfflineTests"
        ),
        .testTarget(
            name: "SuperPlayerTVTests",
            dependencies: ["SuperPlayerTV"],
            path: "ios/Tests/SuperPlayerTVTests"
        ),
        .testTarget(
            name: "SuperPlayerDiagnosticsTests",
            dependencies: ["SuperPlayerDiagnostics"],
            path: "ios/Tests/SuperPlayerDiagnosticsTests"
        ),
        // Core for the seam the fake implements, testmedia for the H.264 it delivers, and testkit
        // for the bounded wait every playback test uses (ADR-0021 rule 4); no origin is served.
        .testTarget(
            name: "SuperPlayerRealtimeTests",
            dependencies: [
                "SuperPlayerRealtime", "SuperPlayerCore", "SuperPlayerTestMedia",
                "SuperPlayerTestkit",
            ],
            path: "ios/Tests/SuperPlayerRealtimeTests"
        ),
        // Core too, because core's playback tests live here: they need the phase 2 harness, and
        // core's own test target takes core's phase 1 (docs/testing.md, *The iOS harness*).
        .testTarget(
            name: "SuperPlayerTestkitTests",
            dependencies: ["SuperPlayerTestkit", "SuperPlayerTestMedia", "SuperPlayerCore"],
            path: "ios/Tests/SuperPlayerTestkitTests"
        ),
        // The agreement test and the report's honesty rules, which ios/check runs, and the matrix
        // run itself, which it skips: ios/Benchmark/bench is the one thing that runs it.
        .testTarget(
            name: "SuperPlayerBenchmarkTests",
            dependencies: [
                "SuperPlayerBenchmark", "SuperPlayerCore", "SuperPlayerTelemetry",
                "SuperPlayerTestkit", "SuperPlayerTestMedia",
            ],
            path: "ios/Benchmark/Tests/SuperPlayerBenchmarkTests"
        ),
        .testTarget(
            name: "SuperPlayerTestMediaTests",
            dependencies: ["SuperPlayerTestMedia"],
            path: "ios/Tests/SuperPlayerTestMediaTests"
        ),
    ],
    // Swift 6 with complete strict concurrency, which tools version 6.0 makes the default; stated
    // so that a later tools version changing its default cannot change this package's mode
    // silently. Every rule about which thread a sink or a callback runs on becomes a compile error.
    swiftLanguageModes: [.v6]
)
