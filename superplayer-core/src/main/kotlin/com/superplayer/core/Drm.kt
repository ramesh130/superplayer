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
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm

/**
 * How a player acquires the licences its content is protected by: what `superplayer-drm` does on its
 * behalf (ADR-0012).
 *
 * The handle a consumer names in [SuperPlayer.Builder.setDrm], and nothing else — the peer of
 * [PlaybackResilience] and [ContentCache], and here for their reason. Every Media3 type this phase
 * deals in is `@UnstableApi` — `ExoMediaDrm`, `DrmSessionManager`, `DrmSessionManagerProvider`,
 * `MediaItem.DrmConfiguration`, every one — so what the builder takes is this, and the object graph
 * behind it stays inside the module that built it (ADR-0001 rule 2, ADR-0012 rule 3).
 *
 * **Protection is the player's, and is fixed for its lifetime** (ADR-0012 rule 1). It is not a
 * property of a [MediaRequest] or of one of its sources: a request's `sources` list means *this
 * content, somewhere else*, and entries needing different licence servers would be two pieces of
 * content under one [MediaRequest.contentId] — which is the defect `MediaRequest` exists to prevent.
 * A second source is a second container and scheme mapping for the same entitlement.
 *
 * Implementations come from `superplayer-drm`, and reach the engine as [EngineDrmExtension]; a
 * `PlaybackDrm` that is not one fills no slot, opens no session and acquires no licence, which is
 * the same contract a [PlaybackPolicy] that is not an [EnginePolicyExtension] has.
 */
public interface PlaybackDrm

/**
 * The seam by which `superplayer-drm` reaches an engine core builds: a [PlaybackDrm] that *also*
 * implements this fills the one slot ADR-0012 rule 3 names.
 *
 * Internal, and reachable from `superplayer-drm` because that module compiles as the **sixth**
 * Kotlin friend of core (`build-logic`'s `KotlinFriendModules.kt` carries the argument, and the
 * ceiling ADR-0012 rule 4 puts on the count). The public half of the same call is
 * [SuperPlayer.Builder.setDrm], whose parameter is [PlaybackDrm].
 *
 * One slot and not two, which is rule 3's own emphasis: the session manager, the `ExoMediaDrm` under
 * it, the licence callback and the security level it asks for are one object graph built once per
 * player, and a second slot would let an implementation fill one and not the other.
 *
 * Called once per `build()`, on the thread building the player, before the test configurator — so a
 * test's engine configuration still wins, exactly as it does over the policy's and the resilience's.
 */
internal interface EngineDrmExtension : PlaybackDrm {

    /** Fills [EngineConfiguration.drm]. Called once per `build()`, before the test configurator. */
    fun configureEngine(configuration: EngineConfiguration)
}

/**
 * What goes in the DRM slot: the module's whole session graph, as a function of the two things only
 * core can hand it.
 *
 * A function rather than a ready-made `DrmSessionManagerProvider` because neither argument exists
 * when [EngineDrmExtension.configureEngine] runs.
 *
 * [licenceTransport] is the chain a licence request travels, and it is deliberately not the whole
 * chain: it is what sits *below* the cache slot — the transport with the header-refresh slot over it
 * — because a licence is not media. Caching one would store a device-bound credential under a
 * content key, and revalidating one is meaningless; carrying the app's credential on it is not, which
 * is why the header-refresh slot is inside rather than outside (ADR-0012 rule 2, #205).
 *
 * [mediaDrm] is the device, and is null everywhere but a test. Robolectric ships no `ShadowMediaDrm`,
 * so a real `MediaDrm` cannot be constructed under `check` at all and the harness stands one in
 * through [EngineConfiguration.exoMediaDrm]. Null means the platform's own
 * `FrameworkMediaDrm.DEFAULT_PROVIDER`, which is what every player on a device gets.
 *
 * What comes back is given to the `MediaSource.Factory` `TransferChain` assembles, at the one line
 * its stamping factory has always replayed and nothing has ever filled.
 */
internal fun interface LicenceSessions {

    /** The provider Media3 asks for a session, for a player loading licences through [licenceTransport]. */
    fun over(licenceTransport: DataSource.Factory, mediaDrm: ExoMediaDrm.Provider?): DrmSessionManagerProvider
}
