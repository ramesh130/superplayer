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

// SuperPlayerMoQ: superplayer-moq's iOS port, in a package of its own and deliberately **not** a
// product of the root manifest. That is the SwiftPM analog of the Kotlin module's `LOCAL_ONLY`
// publication (ADR-0017 rule 1's #364 addendum, docs/ios-plan.md §3.10): an adopter resolves this
// repository by URL, and SwiftPM reads only the root `Package.swift`, so nothing declared here can be
// depended on from outside — while `ios/Demo/` resolves it by path, as `android/demo/` resolves
// the Kotlin module from one machine's Maven local. The native half it links is built on one
// machine and carries MPL-2.0 code (third-party/moq/README.md, THIRD_PARTY.md), which is why that
// line matters.
//
// It depends on the root package by path, for the core product's `FrameSource` it implements and the
// harness its tests use; ios/check tests and builds it in a step of its own.

import PackageDescription

let package = Package(
    name: "SuperPlayerMoQ",
    // iOS alone: the XCFramework carries the iOS device and the arm64 iOS simulator and no tvOS
    // slice (third-party/moq/README.md, *The Swift bindings*, argues the committed bytes).
    platforms: [.iOS(.v17)],
    products: [.library(name: "SuperPlayerMoQ", targets: ["SuperPlayerMoQ"])],
    dependencies: [
        .package(name: "SuperPlayer", path: "../.."),
        .package(name: "MoqFFI", path: "../../third-party/moq/swift"),
    ],
    targets: [
        // Core alone, as superplayer-moq's main sources import core alone: the seam it implements
        // is core's, and `Realtime.transport(scheme:)` is the consumer's call, not this product's.
        .target(
            name: "SuperPlayerMoQ",
            dependencies: [
                .product(name: "SuperPlayerCore", package: "SuperPlayer"),
                .product(name: "MoqFFI", package: "MoqFFI"),
            ],
            path: "Sources/SuperPlayerMoQ"
        ),
        // The harness for the conformance suite (#433) and its bounded waits.
        .testTarget(
            name: "SuperPlayerMoQTests",
            dependencies: [
                "SuperPlayerMoQ",
                .product(name: "SuperPlayerCore", package: "SuperPlayer"),
                .product(name: "SuperPlayerTestkit", package: "SuperPlayer"),
                .product(name: "MoqFFI", package: "MoqFFI"),
            ],
            path: "Tests/SuperPlayerMoQTests"
        ),
    ],
    // The root manifest's mode and reason.
    swiftLanguageModes: [.v6]
)
