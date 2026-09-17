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

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ErrorStateDrmSession
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

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

    /**
     * Fills [EngineConfiguration.drm], and [EngineConfiguration.protectionRepair] where the
     * protection can be re-opened. Called once per `build()`, before the test configurator.
     */
    fun configureEngine(configuration: EngineConfiguration)
}

/**
 * Who core asks whether this player's protection can be opened again at a level the licence server's
 * operator permitted — the repair for a failure the session graph could not have seen coming
 * (ADR-0012 rule 11's #225 addendum).
 *
 * **Beside the fallback ladder and not inside it.** Nothing here is a [PlayerStateRungs] question: no
 * `FallbackRung` is added, no ceiling moves, and a failure a lower level cures is one no
 * classification could have routed, because what makes it curable is a permission rather than a
 * class. Core asks this *before* it asks the rungs, which is the order and not a preference — a
 * second source is a second container for the same entitlement (ADR-0012 rule 1), so downloading one
 * to meet the same refusal is a startup a viewer waits through for nothing.
 *
 * **It is asked about no particular failure, and that is deliberate.** Which failures are worth
 * offering is core's, read off the classification the one classifier already made
 * ([SuperPlayerError.category]); `superplayer-drm` keeps no taxonomy and names no error code
 * (ADR-0012 rule 5, checked against that module's source tree). So the question here is the half only
 * the protection can answer: whether there is a permitted level below the one in force that has not
 * been tried.
 *
 * The two halves are split for the reason every rung's are: the implementation says what the
 * permission allows, and core says whether this player may perform it at all
 * ([SuperPlayer.MAX_PROTECTION_REOPENS]) and then performs the re-preparation.
 *
 * Both methods are called on the application thread, from the failure dispatch.
 */
internal interface ProtectionRepair {

    /**
     * Whether a level below the one in force was permitted and has not been asked for yet.
     *
     * Pure: it changes nothing, so core may ask it for a failure it then decides not to act on.
     */
    fun mayReopenAtLowerLevel(): Boolean

    /**
     * Builds this player's session graph again at that level, and writes the level down
     * ([DeliveredProtection]).
     *
     * What it does *not* do is re-prepare the player: the item, the position and the identity are
     * core's, and core re-sets the item afterwards so that the media source is built again and the
     * new graph is the one it asks for a session.
     */
    fun reopenAtLowerLevel()
}

/**
 * What goes in the DRM slot: the module's whole session graph, as a function of the two things only
 * core can hand it.
 *
 * A function rather than a ready-made `DrmSessionManagerProvider` because nothing it is built from
 * exists when [EngineDrmExtension.configureEngine] runs — see [LicenceContext] for what it is handed
 * and why each part of it is core's to supply.
 *
 * What comes back is given to the `MediaSource.Factory` `TransferChain` assembles, at the one line
 * its stamping factory has always replayed and nothing has ever filled.
 */
internal fun interface LicenceSessions {

    /** The provider Media3 asks for a session, for a player whose protection [licence] describes. */
    fun over(licence: LicenceContext): DrmSessionManagerProvider
}

/**
 * Everything only core can hand the DRM slot, as one value.
 *
 * One parameter rather than five because they are one thing — *this player's protection*, assembled
 * at chain-composition time — and because a slot whose signature grows a parameter per phase is a
 * slot every filler has to be recompiled against for facts it does not read.
 */
internal class LicenceContext(

    /**
     * The chain a licence request travels, and deliberately not the whole chain: it is what sits
     * *below* the cache slot — the transport with the header-refresh slot over it — because a licence
     * is not media. Caching one would store a device-bound credential under a content key, and
     * revalidating one is meaningless; carrying the app's credential on it is not, which is why the
     * header-refresh slot is inside rather than outside (ADR-0012 rule 2). Every request it opens is
     * stamped [LoadKind.LICENCE], which is how that layer knows a refused entitlement from a refused
     * segment.
     */
    val transport: DataSource.Factory,

    /**
     * The device, null everywhere but a test. Robolectric ships no `ShadowMediaDrm`, so a real
     * `MediaDrm` cannot be constructed under `check` at all and the harness stands one in through
     * [EngineConfiguration.exoMediaDrm]. Null means the platform's own
     * `FrameworkMediaDrm.DEFAULT_PROVIDER`, which is what every player on a device gets.
     */
    val mediaDrm: ExoMediaDrm.Provider?,

    /**
     * [EngineConfiguration.loadErrors] — **the same instance** the media source factory is given,
     * which is the whole of #205. Media3 asks a DRM session manager's own `LoadErrorHandlingPolicy`
     * about a failed licence load, and a session manager left to build its own would answer out of
     * Media3's defaults while every other load of the player answered out of [PlaybackDecision.retry]:
     * a licence budget nothing could reach, which is what [RetryPolicy.licence] said of itself until
     * this argument was passed. Null is a player built with no `PlaybackResilience`, and such a player
     * keeps Media3's own handling for a licence exactly as it keeps it for a segment (ADR-0011 rule
     * 14).
     */
    val loadErrors: LoadErrorHandlingPolicy?,

    /**
     * What this device can show and decode, read once in core's one place ([deviceConstraintsOf]).
     *
     * ADR-0012 rule 12: the secure-decoder reading is a device *constraint* and is read here rather
     * than by the module, so that the platform's codec list is walked once per player and the
     * selector and the session graph cannot disagree about what the device declared.
     */
    val device: DeviceConstraints,

    /** Where the session graph writes down what it actually opened, for telemetry. */
    val delivered: DeliveredProtection,
)

/**
 * What protection a player's session graph actually delivered, written by `superplayer-drm` and read
 * by [SuperPlayer.deliveredSecurityLevel].
 *
 * ADR-0012 rule 11's last sentence: "a session that opened at a reduced level says so in telemetry,
 * because a support engineer reading a session needs to know which thing was delivered". A holder
 * rather than an event because the two facts arrive at different times — a session opens before its
 * first licence is exchanged — and because the fact is a property of the session rather than a moment
 * in it; `QoeCollector` reads it onto `TelemetryEvent.SessionEnded`, by which time it is settled.
 *
 * Volatile because it is written on whichever thread opened the session graph and read on the
 * telemetry collector's.
 */
internal class DeliveredProtection {

    /**
     * The `securityLevel` the session graph opened at, or null on a player that opened none — which
     * is every player without `setDrm`, and every player whose device could honour what it reported
     * without any of ADR-0012 rule 11's machinery running.
     */
    @Volatile
    var securityLevel: String? = null
}

/**
 * Sessions that are one refusal: what fills the DRM slot where [SecurityDowngradeRefusedException]
 * has already settled that no session can be opened (ADR-0012 rule 11).
 *
 * Core's rather than `superplayer-drm`'s, and that is rule 5 rather than an accident of where it fit.
 * The module classifies nothing and names no Media3 error code — `DrmFailureTest` checks its source
 * tree for exactly that — so how a core exception is dressed to reach a consumer is core's, the way
 * [StaleLivePlaylistException]'s journey to a listener is.
 *
 * It opens nothing. No `MediaDrm` session, no key request, no licence round trip — because the answer
 * is already known and spending a request to arrive at it would put a refusal on
 * [RetryPolicy.licence]'s budget and in the CDN's log for no gain.
 *
 * Media3's own `ErrorStateDrmSession` carries the failure, which is the class Media3 uses for exactly
 * this — a session that failed before it existed — so it travels to the renderer, the listener and
 * [SuperPlayer.playerError] along the path every other DRM failure travels.
 *
 * The code is `ERROR_CODE_DRM_DISALLOWED_OPERATION` and that is a statement rather than a leftover:
 * the operation the server disallowed is playing this content at the level this device can reach. A
 * consumer with `superplayer-resilience` never reads the code — the classifier reads the exception
 * underneath it — and one without it lands among the codes that say *refused* rather than in a bucket
 * claiming a licence round trip failed, which would be untrue twice: there was no round trip, and
 * nothing failed.
 */
internal fun refusedSessions(refusal: SecurityDowngradeRefusedException): DrmSessionManagerProvider =
    erroringSessions(refusal, PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION)

/**
 * Sessions that are one expiry: what fills the DRM slot for a player built to play from an offline
 * licence that has run out (ADR-0012 rule 9, issue #210).
 *
 * **Refused rather than quietly re-acquired**, which is a decision and not the absence of one.
 * Media3's own answer to a restored licence inside sixty seconds of its expiry is to ask the licence
 * server for a new one (`DefaultDrmSession.doLicense`), and for a streaming player that is right. For
 * a player a consumer built *to play offline* it is the wrong surprise twice over: it is a network
 * round trip where the whole point was that there would be none, and where there is genuinely no
 * network it fails as a licence that could not be acquired rather than as the licence that expired —
 * which is exactly the "playback error" `PRD.md` §3.2 says a dead download must not produce.
 *
 * The consumer was given the fact first: [OfflineLicence][com.superplayer.core.SuperPlayer]'s store
 * answers both expiries before a player is built. This is what happens when they play it anyway, and
 * it says the same thing the store did.
 *
 * `ERROR_CODE_DRM_LICENSE_EXPIRED` is the code, so a consumer with `superplayer-resilience` reads
 * `FailureClass.Drm.LicenceExpired` and its own `userMessageKey` — go online and refresh this
 * download — and one without it lands in the same bucket off the band. No new classification and no
 * second taxonomy: unlike a downgrade refusal, whose code says only that an operation was disallowed,
 * this code already says precisely what happened.
 */
internal fun expiredLicenceSessions(refusal: OfflineLicenceExpiredException): DrmSessionManagerProvider =
    erroringSessions(refusal, PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED)

private fun erroringSessions(refusal: Throwable, errorCode: Int): DrmSessionManagerProvider {
    val manager = object : DrmSessionManager {

        override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) = Unit

        override fun acquireSession(
            eventDispatcher: DrmSessionEventListener.EventDispatcher?,
            format: Format,
        ): DrmSession = ErrorStateDrmSession(DrmSession.DrmSessionException(refusal, errorCode))

        // `CRYPTO_TYPE_UNSUPPORTED` rather than `CRYPTO_TYPE_NONE` for a format that declares
        // protection, so that Media3 asks for a session at all: answering "none" would have the
        // renderer treat protected content as clear, which is the silent downgrade rule 11 forbids.
        override fun getCryptoType(format: Format): Int =
            if (format.drmInitData != null) C.CRYPTO_TYPE_UNSUPPORTED else C.CRYPTO_TYPE_NONE
    }
    return DrmSessionManagerProvider { manager }
}
