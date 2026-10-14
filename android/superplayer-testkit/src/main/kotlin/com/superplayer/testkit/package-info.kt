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
 * superplayer-testkit — Fault injection, network shaping, fake manifests, golden traces
 *
 * The deterministic playback harness every module from phase 2 onward tests against:
 * `PlaybackHarness`, the `FaultScript` it injects, the `ThroughputTrace`s and `NetworkProfile`s it
 * replays, and the synthetic content it plays. `docs/testing.md` is the seam it serves.
 */
package com.superplayer.testkit
