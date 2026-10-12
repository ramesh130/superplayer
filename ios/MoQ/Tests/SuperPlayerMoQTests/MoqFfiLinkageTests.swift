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

import MoqFFI
import XCTest

/// The bindings link their native library and answer across the FFI boundary — the Kotlin
/// `MoqFfiLinkageTest`. The library is **built on one machine** and committed
/// (`third-party/moq/swift/`), so "does it link" has no answer until something calls; a failure
/// here is a linkage failure with a stack, rather than a silent no-op in the session that first
/// subscribes.
///
/// It reaches the bindings directly rather than through this product, because the claim is about
/// the bindings. Unlike the Kotlin test, which loads a *host* build because a JVM cannot load an
/// Android `.so`, this calls the very slice an app on the simulator links — the XCFramework's arm64
/// simulator library — and so **runs wherever `ios/check` does**, CI's macOS job included: nothing
/// is skipped by host. What it cannot reach is the device slice, which only a device runs.
final class MoqFfiLinkageTests: XCTestCase {

    /// Every checksum the generated Swift holds is answered by the linked library.
    /// `uniffiEnsureMoqFfiInitialized()` compares UniFFI's contract version and then one
    /// `uniffi_moq_ffi_checksum_*` per exported method against constants baked into the Swift at
    /// generation time, and traps on a mismatch — the failure a library built separately from its
    /// bindings is exposed to and a published one is not. Dozens of round trips, not one.
    func testTheBindingsAgreeWithTheLinkedLibrarysChecksums() {
        uniffiEnsureMoqFfiInitialized()
    }

    /// An object allocated in Rust is handed back, used and freed: a record lowered on the way in, a
    /// handle on the way out, a string argument lowered on a second call, and the handles released by
    /// ARC as the scope ends. An origin is the cheapest object that needs no network — a local cache
    /// of broadcasts — so nothing here reaches a relay, which keeps ADR-0021's loopback rule intact.
    func testAnObjectAllocatedInRustIsHandedBackAndFreed() throws {
        let origin = MoqOriginProducer(options: MoqOriginOptions())
        let broadcast = try origin.createBroadcast(path: Self.smokeBroadcastPath)
        XCTAssertNotNil(try broadcast.consume())
        XCTAssertNotNil(origin.consume())
    }

    /// Any path will do — nothing announces it and no relay sees it — named after this test so a
    /// stray line from the native tracing layer says where it came from.
    private static let smokeBroadcastPath = "superplayer/ffi-smoke"
}
