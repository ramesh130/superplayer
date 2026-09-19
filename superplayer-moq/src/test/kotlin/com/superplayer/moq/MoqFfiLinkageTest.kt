/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.moq

import org.junit.Assert.assertNotNull
import org.junit.Test
import uniffi.moq.MoqOriginOptions
import uniffi.moq.MoqOriginProducer
import uniffi.moq.uniffiEnsureInitialized

/**
 * The bindings load their native library and answer across the FFI boundary.
 *
 * This is #364's whole deliverable and the reason the ticket exists before any transport code: the
 * artifact these tests reach is **built on this machine** rather than resolved from Maven
 * (`third-party/moq/README.md`), so the question "does it link" has no answer until something
 * calls. A failure here is a linkage failure with a stack, rather than a silent no-op three
 * tickets later.
 *
 * **What makes this a real FFI call rather than a Kotlin one.** The library the JVM loads is the
 * *host* build, `darwin-aarch64/libmoq_ffi.dylib`, which arrives on the test classpath from
 * `dev.moq:moq-ffi-jvm` at JNA's own resource layout. The Android variant's `libmoq_ffi.so` cannot
 * be used here at all — `docs/testing.md` records that a Robolectric JVM cannot load an Android
 * `.so` — so the host build is not a convenience, it is the only way this criterion is met
 * honestly. Both are the same crate at the same commit, built with the same features off.
 *
 * These tests reach `uniffi.moq` directly rather than through anything of this module's, because
 * there is nothing of this module's yet: the seam over it is #365's.
 */
class MoqFfiLinkageTest {

    /**
     * Every checksum the generated bindings hold is answered by the binary that is loaded.
     *
     * `uniffiEnsureInitialized` is not a no-op: it touches UniFFI's integrity-checking object,
     * which loads the native library and then calls one `uniffi_moq_ffi_checksum_*` function per
     * exported method, comparing each answer against a constant baked into the Kotlin at
     * generation time. So this is dozens of round trips rather than one, and it fails loudly if
     * the bindings and the binary were generated from different builds — which is exactly the
     * failure a locally built artifact is exposed to and a Maven-resolved one is not.
     */
    @Test
    fun theBindingsLoadTheirNativeLibraryAndAgreeWithItsChecksums() {
        uniffiEnsureInitialized()
    }

    /**
     * An object allocated in Rust is handed back, used, and freed.
     *
     * The checksum walk above proves the symbols are there; this proves the calling convention
     * works in both directions on a real object — a struct lowered into a `RustBuffer` on the way
     * in, a handle on the way out, a `String` argument lowered on a second call, and the cleaner
     * running on `close`. An origin is the cheapest object in the API that needs no network: it is
     * a local cache of broadcasts, so nothing here reaches a relay, which keeps `docs/testing.md`'s
     * no-network rule intact.
     */
    @Test
    fun anObjectAllocatedInRustIsHandedBackAndFreed() {
        MoqOriginProducer(MoqOriginOptions()).use { origin ->
            origin.createBroadcast(SMOKE_BROADCAST_PATH).use { broadcast ->
                assertNotNull("a broadcast consumer over the origin", broadcast.consume())
            }
            assertNotNull("a consumer over the origin", origin.consume())
        }
    }

    private companion object {
        /**
         * Any path will do — nothing announces it and no relay ever sees it. It is named after
         * this test so that a stray log line from the native tracing layer says where it came
         * from.
         */
        const val SMOKE_BROADCAST_PATH = "superplayer/ffi-smoke"
    }
}
