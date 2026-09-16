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

import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import com.google.common.base.Supplier

/**
 * What may be changed about an engine as it is built, by the four things allowed to: a test, through
 * `SuperPlayer.Builder.setEngineConfigurator` — the one internal seam `docs/testing.md` describes —
 * a policy that is also an [EnginePolicyExtension], through `SuperPlayer.Builder.setPolicy`, a
 * resilience that is also an [EngineResilienceExtension], through
 * `SuperPlayer.Builder.setResilience`, and a protection that is also an [EngineDrmExtension], through
 * `SuperPlayer.Builder.setDrm`. All four reach the same object; the extensions run first, so a
 * test's configuration still wins over theirs exactly as it wins over the profile's (ADR-0009 rule
 * 7, ADR-0011 rule 13, ADR-0012 rule 3).
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
     * What Media3 asks whether to retry a failed load, when, and whether to fall back to another
     * location or another track — rungs 1 to 3 of ADR-0011's ladder. Handed to the
     * `MediaSource.Factory` `TransferChain` assembles, and so to every protocol's source. Null
     * leaves Media3's own `DefaultLoadErrorHandlingPolicy` in force, which is what a player without
     * `superplayer-resilience` has (ADR-0011 rule 14).
     */
    var loadErrors: LoadErrorHandlingPolicy? = null

    /**
     * The chain's header-refresh slot: a layer closest to the transport that repairs and retries a
     * request whose credential has expired. See [HeaderRefreshLayer] and `TransferChain`'s
     * composition order. Null leaves the slot empty and the chain exactly what Phase 4 built.
     *
     * A slot rather than a call on [engine] because the chain is not the engine builder's to
     * compose: `TransferChain` owns the order, and a layer that reached `ExoPlayer.Builder` directly
     * would be one whose position was an accident of when it was installed.
     */
    var headerRefresh: HeaderRefreshLayer? = null

    /**
     * Who core asks whether a failure that got past every rung a load could answer may climb to rung
     * 4 — the next entry of [MediaRequest.sources], re-adopted at the position reached.
     *
     * The one slot that faces the other way: the two above are things core *calls*, and this is a
     * question core *puts* (see [PlayerStateRungs]). Null is a player that asks nobody and therefore
     * performs no rung, which is every player built without `superplayer-resilience` — and unlike
     * the slots above, filling it registers a listener on the engine, so its emptiness is what
     * ADR-0011 rule 14's accounting rests on here.
     */
    var playerStateRungs: PlayerStateRungs? = null

    /**
     * The DRM slot ADR-0012 rule 3 opens: the session graph `superplayer-drm` builds, as a function
     * of the licence transport and the device, handed to the `MediaSource.Factory` `TransferChain`
     * assembles. See [LicenceSessions] for why it is a function rather than a provider.
     *
     * Null is a player built without `SuperPlayer.Builder.setDrm`, and that player sets no provider
     * on any media source factory, instantiates no `ExoMediaDrm` and loads no class of the module —
     * which is rule 13, and is counted rather than asserted about. A provider that is *set* and
     * answers `DRM_UNSUPPORTED` is not nothing and would not satisfy it.
     */
    var drm: LicenceSessions? = null

    /**
     * The Widevine implementation a test stands the device in with, and null everywhere else.
     *
     * A slot for [transport]'s reason and with the same bound: it substitutes the platform's
     * `MediaDrm` and nothing else, and every layer above it — the session manager, the licence
     * callback, the chain the request travels — is the one a consumer's player has. Robolectric ships
     * no `ShadowMediaDrm` at all, so without this slot no protected stream could be played under
     * `check` by any player, and `docs/testing.md`'s *A Widevine device and a licence server* is what
     * fills it.
     *
     * Read by [drm] at chain-composition time rather than by the extension, because an extension runs
     * *before* the test configurator: when `superplayer-drm` is asked to fill its slot, a test has not
     * yet stated the device.
     */
    var exoMediaDrm: ExoMediaDrm.Provider? = null

    /**
     * The decision in force on this player, readable from whatever thread a load fails on.
     *
     * Core fills it; the extensions read it. It exists because the two halves of ADR-0011 rule 11
     * meet at different times: the object that answers Media3 about a failed load is built here,
     * before the policy has been consulted at all, and it has to read *the decision in force at each
     * consultation* rather than the one that happened to be current when it was constructed — the
     * same contract `NetworkAwareTrackSelection` has for the selection pace (ADR-0009 rule 5).
     *
     * Not a widening: what it hands back is [SuperPlayer.playbackDecision], which is public, on a
     * player the reader is a part of. It is here rather than on the facade only because the facade
     * does not exist yet when an extension is called.
     */
    val decisionInForce: DecisionInForce = DecisionInForce()

    /**
     * The clock the engine runs on, or null for the platform's.
     *
     * A slot rather than a call on [engine] for the preload manager's sake: a pooled player's engine
     * shares its clock with the manager a coordinator builds (ADR-0010 rule 9), and `build()` can
     * only hand on a clock it can read back. A test's fake clock goes here.
     */
    var clock: Clock? = null

    /**
     * The renderers the engine is built with, or null for Media3's own.
     *
     * A slot for [clock]'s reason: a preload manager selects tracks against the capabilities of the
     * renderers its players will play with, so a pool hands it the renderers factory its first player
     * was built with. A test's fake renderers go here.
     */
    var renderersFactory: RenderersFactory? = null
}

/**
 * A window onto one player's [SuperPlayer.playbackDecision], opened before the player exists.
 *
 * Written once by `SuperPlayer.Builder.build()` as soon as the policy has answered, and thereafter
 * reading straight through to whatever the decision currently is — so a player whose policy is
 * re-consulted on triggers hands its reader the new decision on the next consultation, with nothing
 * rebuilt (ADR-0009 rule 5, ADR-0011 rule 11).
 *
 * [current] is null only in the window between an extension being handed the configuration and the
 * policy being consulted, which is over before the engine is built and so before any load can fail.
 * A reader that finds null has nothing to honour and falls back to its own default.
 */
internal class DecisionInForce {

    /**
     * Volatile because it is written on the thread building the player and read on loading threads;
     * a supplier rather than a value because the decision it reports is not this object's to track —
     * `DecisionReapplication` already holds it, and two copies could disagree.
     */
    @Volatile
    private var source: (() -> PlaybackDecision?)? = null

    /** Points this window at where the decision in force actually lives. Called once, by core. */
    fun fedBy(source: () -> PlaybackDecision?) {
        this.source = source
    }

    /** The decision in force right now, or null before the policy has been consulted. */
    fun current(): PlaybackDecision? = source?.invoke()
}
