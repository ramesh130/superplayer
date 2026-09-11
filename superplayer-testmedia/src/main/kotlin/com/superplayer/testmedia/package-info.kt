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
 * superplayer-testmedia — synthetic HLS and DASH streams for tests
 *
 * One home for the known-good streams every module's tests play: a multivariant playlist, a media
 * playlist and AAC segments in ADTS framing for HLS; an MPD, a fragmented-MP4 initialization
 * segment and media segments for DASH. Generated rather than vendored, so that every field a test
 * asserts on is a named constant a reader can see and the repository carries no media it would have
 * to license (`docs/testing.md`).
 *
 * A stream is a map of URI to bytes, or a directory of files. Nothing here knows about a player, a
 * `DataSource` or a `FakeDataSet` — see this module's build script for why that is load-bearing
 * rather than minimal.
 */
package com.superplayer.testmedia
