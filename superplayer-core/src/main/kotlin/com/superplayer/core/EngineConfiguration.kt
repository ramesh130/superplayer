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
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.util.ReleasableExecutor
import com.google.common.base.Supplier

/**
 * What may be changed about an engine as it is built, by the two things allowed to: a test, through
 * `SuperPlayer.Builder.setEngineConfigurator` — the one internal seam `docs/testing.md` describes —
 * and a policy that is also an [EnginePolicyExtension], through `SuperPlayer.Builder.setPolicy`.
 * Both reach the same object; the extension runs first, so a test's configuration still wins over
 * the policy's exactly as it wins over the profile's (ADR-0009 rule 7).
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
 *
 * [loadExecutor] is where the chain's loads run. Media3 gives every loader a thread of its own,
 * which is right in production and is the one thread a deterministic harness cannot see: a load
 * that finishes on it tells the playback thread by a message, and whether that message is handled
 * before or after the harness advances its clock is a race the harness has to be able to close.
 * A harness that supplies the executor knows when a load task has *finished*, message posted and
 * all, rather than only when its transfer closed. Null is Media3's own threading.
 *
 * ## The retargetable components
 *
 * [loadControl], [trackSelectionFactory], [bandwidthMeter] and [decisionTarget] are the slots
 * ADR-0009 rule 7 enumerates for an extension: the engine components that can accept a changed
 * [PlaybackDecision] after construction, and the hook a changed decision is handed back through.
 * They are slots rather than calls on [engine] for the reason the media slots are: `build()` has to
 * know whether one was installed, because it changes what `build()` does with the decision. A
 * [decisionTarget] is the whole of that signal — with one, the policy is consulted again on
 * [DecisionTrigger]s and every re-consultation is handed to the target whole; without one, the
 * policy is consulted once, and the buffer half becomes a `DefaultLoadControl` while the selection
 * half is laid into the engine's `TrackSelectionParameters` (ADR-0009 rule 5). An extension that
 * sets a target is promising that its components honour *both* halves of every decision it is
 * handed, from the first one at construction on. The decision's live half is not the target's: it
 * travels on the media item, which core lays it into on every player (`EngineBinding.kt`).
 *
 * Each slot is one both friends of core can see, and the question to ask of any slot added here is
 * whether it is a slot or a widening: a fake in the transport slot stands in for the HTTP stack and
 * nothing else, and a component in one of these stands in for the engine part it replaces and
 * nothing else. Nothing here is a way to reach past the facade.
 */
internal class EngineConfiguration(val engine: ExoPlayer.Builder) {

    /** The data source the chain's layers are composed over, in place of the HTTP stack. */
    var transport: DataSource.Factory? = null

    /** The whole loading path, chain and all, for content with no transport to substitute. */
    var mediaSourceFactory: MediaSource.Factory? = null

    /** Where the chain's loaders run; null for a thread per loader, which is Media3's default. */
    var loadExecutor: Supplier<ReleasableExecutor>? = null

    /**
     * A load control that can be re-targeted after construction, in place of the
     * `DefaultLoadControl` the decision's buffer half would otherwise become. Its initial targets
     * arrive through [decisionTarget] with the first decision; it is not handed a [BufferPolicy]
     * any other way.
     */
    var loadControl: LoadControl? = null

    /**
     * A track selection factory that takes its ceiling from [decisionTarget] rather than from the
     * engine's `TrackSelectionParameters`. Installed under Media3's own `DefaultTrackSelector`, so
     * the device-derived defaults are built upon rather than replaced.
     */
    var trackSelectionFactory: ExoTrackSelection.Factory? = null

    /**
     * The bandwidth meter the engine estimates with. If it also implements [ThroughputSource], core
     * reads [PlaybackConditions.throughput] from it and consults the policy when it reports a
     * material move; if it does not, throughput stays unobserved.
     */
    var bandwidthMeter: BandwidthMeter? = null

    /**
     * Where a decision goes after construction. Non-null means the engine can honour a changed
     * decision whole, and is what turns re-consultation on — see the class KDoc.
     */
    var decisionTarget: DecisionTarget? = null

    /**
     * Told the media source factory the engine loads through, once it is assembled and before the
     * engine is built — the entry `superplayer-preload` builds its sources through (ADR-0010 rule 6).
     *
     * A reading slot rather than a substituting one: what it is handed is the factory installed on
     * the engine, the same instance, so a source built from it runs through every layer of the chain,
     * the cache slot included, and carries the same content identity a source the player built for
     * that item would. Null, which is every player without preload attached, calls nothing.
     */
    var preloadEntry: PreloadEntry? = null
}

/**
 * Where the media source factory an engine was built with is handed on — see
 * [EngineConfiguration.preloadEntry].
 */
internal fun interface PreloadEntry {

    /** Called once per `build()`, on the thread building the player. */
    fun onLoadingPathAssembled(mediaSourceFactory: MediaSource.Factory)
}
