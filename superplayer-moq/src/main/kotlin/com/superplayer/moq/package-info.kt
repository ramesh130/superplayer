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

/**
 * superplayer-moq — Sub-second live over Media over QUIC, behind core's `FrameSource` (ADR-0018)
 *
 * Phase 14, and this much of it is the prefactor rather than the transport: the module exists, it
 * links MoQ's UniFFI Kotlin bindings (`uniffi.moq.*`), and a test calls across the FFI boundary —
 * so a linkage failure is a red test here rather than a mystery in the ticket that first subscribes
 * to a broadcast. It declares no public API of its own yet; #365 onwards add the `FrameSource`, the
 * catalog and the frames.
 *
 * Two facts about this module are unusual and are decisions rather than accidents.
 *
 * It is **not published** (`settings.gradle.kts`'s `unpublishedModules` list, which ADR-0017 rule 1
 * makes the only way to say so). The native library it links is built on one machine with the
 * crate's default features off — which is how #351 settled the licence question, by removing the
 * MPL-2.0 decoder from the dependency graph rather than excepting it — and it covers `arm64-v8a`
 * alone. Publishing it would offer an adopter a coordinate they cannot reproduce and an ABI set
 * that would fail on most devices. `third-party/moq/README.md` is the rebuild recipe and the
 * evidence; #369 is what shipping it would take.
 *
 * It is deliberately **not** a Kotlin friend of `superplayer-core` (ADR-0018 rule 11). What a
 * transport module implements is core's *public* `FrameSource`, and a transport that needed an
 * internal seam would be evidence the seam was drawn in the wrong place.
 */
package com.superplayer.moq
