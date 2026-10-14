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

// MoQ's raw UniFFI Swift bindings over the XCFramework `build-xcframework.sh` builds — upstream's
// own `MoqFFI` package shape (`swift/ffi/Package.swift.template` at moq-dev/moq@4e419f9), with the
// binary as a local path rather than a release URL, because it is built here and is nobody else's to
// download (third-party/moq/README.md, *The Swift bindings*). Only `ios/MoQ/` resolves it.

import PackageDescription

let package = Package(
    name: "MoqFFI",
    platforms: [.iOS(.v17)],
    products: [.library(name: "MoqFFI", targets: ["MoqFFI"])],
    targets: [
        .target(
            name: "MoqFFI",
            dependencies: ["MoqFFIBinary"],
            path: "Sources/MoqFFI",
            // A Rust staticlib does not carry the link options its dependencies declare, so the
            // linker is told here. Security is rustls' platform verifier's (`SecTrust…`), the one
            // framework `nm -u` finds beyond the system's always-linked libraries; upstream's
            // template also names the codec frameworks and libc++, which #351's build removed.
            linkerSettings: [.linkedFramework("Security")]
        ),
        .binaryTarget(name: "MoqFFIBinary", path: "MoqFFI.xcframework"),
    ],
    // Generated code, compiled in the Swift 5 mode upstream generates it for: its handle maps are
    // `@unchecked Sendable` behind their own locks, and holding UniFFI's output to this repository's
    // strict mode would be a claim about code nobody here wrote.
    swiftLanguageModes: [.v5]
)
