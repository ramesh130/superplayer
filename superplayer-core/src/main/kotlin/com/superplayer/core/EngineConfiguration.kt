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

package com.superplayer.core

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource

/**
 * What a test may change about an engine as it is built: the argument of
 * `SuperPlayer.Builder.setEngineConfigurator`, the one internal seam `docs/testing.md` describes.
 *
 * [engine] is the builder itself, for what is the engine's own — the clock, the renderers, a
 * bandwidth meter or analytics collector a test reads through. Media is supplied one of two ways,
 * and the first is the one to reach for:
 *
 * - [transport] stands in for the bottom of the transfer chain, the HTTP stack, and nothing else.
 *   A fake data source there serves the bytes, and every layer `TransferChain` composes above the
 *   transport runs over it exactly as it runs over a consumer's network — so a layer SuperPlayer
 *   adds to the chain is in every such test from the day it is added, without the test knowing.
 * - [mediaSourceFactory] replaces the whole loading path. It is for content Media3's fakes
 *   synthesize without any `DataSource` at all, where there is no transport to stand in for and so
 *   no chain to keep.
 *
 * Both are fields rather than calls on [engine] because `build()` has to know which was asked for:
 * `ExoPlayer.Builder` offers no way to read a media source factory back, so a test that installed
 * one there directly could only win by being called last — which is what made every test before
 * this skip the chain entirely.
 */
internal class EngineConfiguration(val engine: ExoPlayer.Builder) {

    /** The data source the chain's layers are composed over, in place of the HTTP stack. */
    var transport: DataSource.Factory? = null

    /** The whole loading path, chain and all, for content with no transport to substitute. */
    var mediaSourceFactory: MediaSource.Factory? = null
}
