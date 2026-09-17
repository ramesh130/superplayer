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

package com.superplayer.drm

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import com.superplayer.core.EngineConfiguration
import com.superplayer.core.EngineDrmExtension
import com.superplayer.core.LicenceContext
import com.superplayer.core.LicenceSessions
import com.superplayer.core.OfflineLicenceExpiredException
import com.superplayer.core.ProtectionRepair
import com.superplayer.core.SecurityDowngradeRefusedException
import com.superplayer.core.expiredLicenceSessions
import com.superplayer.core.refusedSessions

/**
 * The extension half of [Drm.widevine]: it fills the slots ADR-0012 rule 3 opens, once per engine.
 *
 * Internal, so that the core-internal interfaces it and [PlayerProtection] implement — and every
 * `@UnstableApi` Media3 type either names — stay off this module's public API.
 *
 * Stateless, because it has to be: one `PlaybackDrm` may be handed to many players, so everything
 * with a lifetime belongs to the [PlayerProtection] this builds per `build()` and not to this.
 */
internal class WidevineDrm(
    private val config: WidevineConfig,
    /**
     * The stored licence this player is to play with, or null for an ordinary online one.
     *
     * A value the consumer read out of an [OfflineLicenceStore] before building the player, which is
     * ADR-0012 rule 9 made structural: there is no way to play from the store without having first
     * read what the store holds, and therefore no way for a dead download to reach the engine as
     * "playback error" instead of as the expiry the consumer could see.
     */
    private val offline: OfflineLicence? = null,
) : EngineDrmExtension {

    override fun configureEngine(configuration: EngineConfiguration) {
        val protection = PlayerProtection(config, offline)
        configuration.drm = protection
        // The second slot, and only for a server whose operator published a level to fall to: a
        // player that could never lower anything is asked nothing at failure time, which keeps "an
        // app told nothing permits nothing" true by construction rather than by a branch taken later
        // (ADR-0012 rule 11).
        if (config.permittedSecurityLevels.isNotEmpty()) {
            configuration.protectionRepair = protection
        }
    }
}

/**
 * One player's protection: the session graph it opens with, and the one re-opening ADR-0012 rule 11's
 * #225 addendum allows it.
 *
 * Per player rather than per [Drm.widevine], which is the whole reason this is not the lambda it used
 * to be: [WidevineDrm] is handed to as many players as a consumer likes and holds nothing, while a
 * level in force, a graph in use and a re-open already spent are facts about one player's session.
 * [WidevineDrm.configureEngine] runs once per `build()`, so one of these is built per player and
 * fills both slots — which is also what makes it impossible to fill one and not the other.
 *
 * Its two faces are the two moments protection is decided: [over] composes the graph while the
 * transfer chain is built, and [reopenAtLowerLevel] composes it again after a failure only the
 * running player could have met. Both build the same graph through [sessionManager], so there is one
 * description of what a Widevine session is and not two that drift.
 */
internal class PlayerProtection(
    private val config: WidevineConfig,
    private val offline: OfflineLicence?,
) : LicenceSessions,
    ProtectionRepair {

    /**
     * What the graph was composed from, kept for the one reason a re-open needs it: the licence
     * transport, the load-error policy and the device are core's to supply and are supplied once.
     * Null until the chain is composed, and on a player whose answer was a refusal — which is what
     * makes [mayReopenAtLowerLevel] false for a refused session without a second flag saying so.
     */
    private var licence: LicenceContext? = null

    /** The device the graph was composed over: the provider, never the `ExoMediaDrm` under it. */
    private var device: ExoMediaDrm.Provider? = null

    /** Whether the level in force is already the lower one, whichever moment lowered it. */
    private var lowered = false

    /**
     * The graph Media3 is handed a session out of.
     *
     * Volatile because the two ends are on different threads: it is written on the application
     * thread, where a failure is dispatched and a re-open performed, and read on whichever thread
     * the media source factory builds a source on.
     */
    @Volatile
    private var manager: DrmSessionManager? = null

    override fun over(licence: LicenceContext): DrmSessionManagerProvider {
        // The stored licence first, before any device is touched, because a dead one settles the
        // question: no graph is composed, no session opened and no licence asked for, and the
        // consumer gets back the fact the store already told them (ADR-0012 rule 9). Core dresses
        // it, as it dresses the downgrade refusal and for the same reason — the exception is core's
        // and this module names no Media3 error code (rule 5). Nothing is adopted, so there is no
        // graph for #225's repair to re-open either, which is right: a lower security level cures
        // an expiry in no way at all.
        offline?.takeIf { it.isExpired }?.let {
            return expiredLicenceSessions(
                OfflineLicenceExpiredException(
                    contentId = it.contentId,
                    playbackDurationRemainingMs = it.playbackDurationRemainingMs,
                    licenceDurationRemainingMs = it.licenceDurationRemainingMs,
                ),
            )
        }
        // ADR-0012 rule 11: a device that cannot honour the level it reports may open at a lower
        // one, but only where the licence server's operator said that server will issue at it.
        // `levelToAskAbout` returns null for every ordinary device, and nothing below — no
        // wrapper, no refusal — is composed for one.
        //
        // The permission is read out of the configuration and not off the wire, which is #223
        // withdrawing #208. What #208 built was an HTTP exchange of SuperPlayer's own: a `GET` at
        // the licence URI, answered in a header. Every real licence endpoint is POST-only, so it
        // was answered 405 and read as a refusal; a refusal, a 405, a stripped header and a
        // timeout were one indistinguishable null; and the answer had to be waited for on the
        // playback thread. A fact that does not change between sessions is configuration, and
        // rule 11 always allowed that channel — "in its licence response, or in the
        // configuration the app was given for it". `WidevineConfig.permittedSecurityLevels` says
        // why that is still the *server's* permission and not the app's.
        //
        // The device is resolved to a provider first, because the probe has to read a property
        // off it and `null` here means Media3's own `FrameworkMediaDrm.DEFAULT_PROVIDER` rather
        // than "no device" — naming that default explicitly changes nothing about the session
        // graph and is what makes the probe reachable on a real handset as well as under the
        // harness.
        val device = licence.mediaDrm ?: FrameworkMediaDrm.DEFAULT_PROVIDER
        val lowerLevel = SecurityLevelLadder.levelToAskAbout(device, licence.device)
        val permitted = lowerLevel != null && lowerLevel in config.permittedSecurityLevels
        val manager = sessionManager(
            licence,
            // Where a downgrade was permitted, the same device asked to compose its key requests
            // at the lower level — which is what "requesting an L3 licence" is on the wire
            // (// ref: `MediaDrm.setPropertyString` with `securityLevel`). Where none was
            // permitted, the device untouched.
            if (permitted) LoweredSecurityLevel(device, checkNotNull(lowerLevel)) else device,
        )
        // One manager for the player rather than one per item: the session graph is built once
        // per player because protection is the player's (rule 1), and — since #209 turned
        // `multiSession` on inside `sessionManager` — `DefaultDrmSessionManager` keeps one session
        // per distinct `DrmInitData` beneath that. That sentence stood here before the flag did
        // and was false while it did: at Media3's default the manager keeps one session for
        // everything, and the comment described the behaviour the flag now actually produces.
        return when {
            // The ordinary device, which is almost every device: it can honour the level it
            // reports, so there is nothing to lower and nothing to say. Reading the property back
            // here to report it would charge every protected player a `MediaDrm` acquisition for
            // a line in a log.
            lowerLevel == null -> adopt(licence, device, manager, loweredTo = null)

            // The server's operator permits the lower level, so the device was asked to compose
            // its key requests at it (`LoweredSecurityLevel`, installed above) and the session
            // graph is otherwise the ordinary one. What was delivered is written down, because it
            // is not what the viewer was entitled to.
            permitted -> adopt(licence, device, manager, loweredTo = lowerLevel)

            // And the refusal: no session is opened, no licence is asked for, and the typed
            // exception travels to the consumer where every other DRM failure does — ADR-0012
            // rule 11's "fails the session rather than downgrading it". Core dresses it, because
            // the exception is core's and this module names no Media3 error code (rule 5). The
            // device's own level is `L1` by construction: `levelToAskAbout` returns a lower level
            // for no other.
            //
            // Nothing is adopted, so there is no graph to re-open either: the failure a refused
            // session raises is one this player has already answered, and #225's repair must not
            // reopen its way around a refusal.
            else -> refusedSessions(
                SecurityDowngradeRefusedException(
                    deviceSecurityLevel = WidevineConfig.SECURITY_LEVEL_L1,
                    refusedLevel = lowerLevel,
                    permittedLevels = config.permittedSecurityLevels,
                ),
            )
        }
    }

    /**
     * Takes [manager] into force as this player's session graph, and answers with the provider Media3
     * asks for a session.
     *
     * The provider reads [PlayerProtection.manager] on every call rather than closing over one, which
     * is the whole mechanism behind #225's re-open: a source built after a re-open is handed the new
     * graph, and one built before it keeps the old — and Media3 builds a source per `setMediaItem`,
     * which is exactly when core re-sets the item.
     */
    private fun adopt(
        licence: LicenceContext,
        device: ExoMediaDrm.Provider,
        manager: DrmSessionManager,
        loweredTo: String?,
    ): DrmSessionManagerProvider {
        this.licence = licence
        this.device = device
        this.manager = manager
        loweredTo?.let {
            lowered = true
            licence.delivered.securityLevel = it
        }
        // Never null by the time Media3 asks: the field is written above, before this provider is
        // handed to a media source factory that could call it.
        return DrmSessionManagerProvider { checkNotNull(this.manager) }
    }

    /**
     * Whether there is a permitted level below the one in force that this player has not asked for.
     *
     * Three refusals, and every one of them is what keeps ADR-0012 rule 11's default in place. A
     * player whose graph was never composed — the refused session — has nothing to re-open; a player
     * already at the lower level has nowhere left to fall, since Widevine has one level below `L1`
     * and asking again would compose a second graph to deliver the same thing; and a level the
     * operator did not publish is a level this client may not ask for, which is the rule itself.
     */
    override fun mayReopenAtLowerLevel(): Boolean = levelToFallTo() != null

    /**
     * The session graph composed again, at that level, over the same licence transport and the same
     * device.
     *
     * The graph only: re-preparing the player is core's, because the item, the position and the
     * identity are (ADR-0012 rule 11's #225 addendum, and [ProtectionRepair] for the split). What is
     * written down as this returns is what the next session will be opened at, and
     * `SuperPlayer.deliveredSecurityLevel` reports it from here on — a licence acquired at a reduced
     * level says so whether the reduction was decided before the session or during it.
     *
     * Silent where nothing may be lowered, rather than throwing: core asks [mayReopenAtLowerLevel]
     * first, and a repair that threw on a second call would turn a declined remedy into a failure of
     * its own.
     */
    override fun reopenAtLowerLevel() {
        val level = levelToFallTo() ?: return
        val licence = checkNotNull(this.licence)
        val device = checkNotNull(this.device)
        lowered = true
        manager = sessionManager(licence, LoweredSecurityLevel(device, level))
        licence.delivered.securityLevel = level
    }

    /**
     * The level this player could still fall to, or null for the three cases that are not a fall.
     *
     * The device is probed here rather than when the graph was composed, and that is ADR-0012 rule
     * 13's posture one level in: `levelToAskAbout` short-circuits on the decoder table precisely so
     * that an ordinary protected player never acquires a `MediaDrm` to answer a question nobody
     * asked, and probing every player up front to be ready for a failure almost none of them will
     * meet would undo that. Here the player has already failed, and one acquisition is what the
     * answer costs.
     */
    private fun levelToFallTo(): String? {
        if (licence == null || lowered) return null
        val level = SecurityLevelLadder.levelToFallTo(device) ?: return null
        return level.takeIf { it in config.permittedSecurityLevels }
    }

    /**
     * A Widevine session manager for [licence], over [device] — the one description of what a session
     * graph is here, built at chain composition and again at a re-open.
     */
    private fun sessionManager(licence: LicenceContext, device: ExoMediaDrm.Provider): DrmSessionManager {
        val manager = widevineSessions(licence, device)
        offline?.let {
            // ref: `DefaultDrmSessionManager.setMode(MODE_PLAYBACK, keySetId)` is Media3's whole
            // mechanism for playing from a licence already on the device: every session this manager
            // opens restores those keys instead of composing a key request, so no licence leaves the
            // device — which is the claim `OfflineLicenceTest` counts rather than infers.
            //
            // Media3 still goes to the server on its own initiative in one case, and it is the right
            // one: a restored licence inside sixty seconds of expiry is re-requested rather than
            // played to a stop mid-view (`DefaultDrmSession.doLicense`). One already expired is a
            // `KeysExpiredException`, which reaches the consumer classified as an expired licence
            // with its own message key — the after-the-fact half of the pair
            // `OfflineLicence.isExpired` is the before-the-fact half of. This module names no
            // classification, here or anywhere (ADR-0012 rule 5, and `DrmFailureTest` checks it).
            manager.setMode(DefaultDrmSessionManager.MODE_PLAYBACK, it.keySetId)
        }
        // The one thing a licence store needs and the session graph alone cannot give it: which
        // format the engine actually asked for a session over. Only the content declares its own
        // protection data, so an acquisition for offline use is composed from what this player
        // loaded rather than from anything the store could have been told (ADR-0012 rule 9).
        return FormatRecordingSessions(manager) { protectedFormat = it }
    }

    /**
     * The format this player's engine last asked for a DRM session over, or null before it has asked
     * for one.
     *
     * Volatile because it is written on whichever thread enabled the renderer and read on the one a
     * consumer calls [OfflineLicenceExchange] from.
     */
    @Volatile
    internal var protectedFormat: Format? = null
        private set

    /**
     * A session graph of this player's, in no mode, for an [OfflineLicenceExchange] to drive.
     *
     * A second manager rather than the playing one, because Media3's `OfflineLicenseHelper` takes
     * one over: it sets the mode, prepares it, opens a session of its own on its own thread and
     * releases it. Handing it the manager the renderers are holding sessions out of would end
     * playback to download a licence. What the two share is everything that makes the exchange this
     * *player's* — the licence transport with its header-refresh layer, the retry budget, the device,
     * and the licence server the app named (ADR-0012 rule 2).
     */
    internal fun exchangeSessions(): DefaultDrmSessionManager {
        val licence = checkNotNull(this.licence) { "This player's protection composed no session graph" }
        return widevineSessions(licence, checkNotNull(this.device))
    }

    private fun widevineSessions(licence: LicenceContext, device: ExoMediaDrm.Provider): DefaultDrmSessionManager {
        val licenceTransport = licence.transport
        val loadErrors = licence.loadErrors
        // Media3's own callback rather than one of ours, which is ADR-0001's whole posture: it
        // POSTs the key request to the licence URL over the `DataSource.Factory` it is given, and
        // performs provisioning against the URL the device's own provision request names — the
        // convention Google's provisioning service uses, which nothing here should re-invent.
        // #207 is where provisioning is driven end to end, and the one thing it will find is that
        // the harness answers a provisioning POST at an address of its own rather than at that
        // one.
        //
        // `forceDefaultLicenseUri = true` is the one argument here that is a decision rather
        // than plumbing. Left false, Media3 prefers a licence server URL the *key request*
        // carries — which the device composed from the content's own protection data — and falls
        // back to the configured one only when that is empty. A licence server named by content
        // is a licence server whoever served the manifest can name, and `WidevineConfig`'s whole
        // claim is that the server is the app's (ADR-0012 rule 1). So the app's URL wins, always,
        // and content that proposes another is asked for a licence at the app's anyway.
        val callback = HttpMediaDrmCallback(config.licenceUri, /* forceDefaultLicenseUri= */ true, licenceTransport)
        return DefaultDrmSessionManager.Builder()
            // The device. Set on every player rather than only where a test stands one in, and
            // that is a no-op rather than a change: `DefaultDrmSessionManager.Builder`'s own
            // default for Widevine *is* `FrameworkMediaDrm.DEFAULT_PROVIDER`, so naming it is
            // the same session graph written down. What it buys is the caller's choice of
            // provider — the probe and the lowering need one to hold, and a handset would
            // otherwise have none.
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, device)
            // Media3's default here is `true`, and it is wrong for a player whose consumer has
            // declared protection. It means a sample that arrives in the clear is handed to a
            // renderer even though the session holds no keys — which for a stream that is
            // partially clear is a convenience, and for a stream whose licence was refused is a
            // silent downgrade of exactly the kind ADR-0012 rule 11 forbids the client to make on
            // its own authority. A player built with `setDrm` plays what it was entitled to play
            // or it fails saying so.
            .setPlayClearSamplesWithoutKeys(false)
            // #209, and the third decision here rather than plumbing. Media3's default is
            // `false`, and what `false` means is not "one session per distinct key" — it is
            // `DefaultDrmSessionManager` keeping a single `noMultiSessionDrmSession` and handing
            // it to *every* format whatever its `DrmInitData`; the open sessions are searched for
            // one whose scheme datas are equal only when this flag is true. On a player whose
            // protection is declared once and spans a whole playlist (ADR-0012 rule 1), that is
            // one session across content a licence server issued separate licences for, which is
            // a correctness bug and not a saving.
            //
            // True, so reuse is decided by what the content declared. That is Media3's own rule
            // and the conservative one: equal initialization data means the same key ids, which
            // means one licence to whichever server issued either, while different key ids were
            // licensed apart and share nothing a session may carry across. A finer line than the
            // key — a server that licenses per asset behind identical key ids — is invisible to
            // every client, so a player that drew it would be guessing rather than being careful.
            //
            // The security level #208 negotiates is not part of that identity, and the reason is
            // structural: it is asked once, here, while this player's chain is composed, and it
            // is set on the device for the player's lifetime — so every session of this manager
            // is delivered at one level and the level can separate nothing. If a level ever
            // became a per-item answer it would have to join the key, because two items delivered
            // at different levels are not one policy; `SessionReuseTest` says so too.
            //
            // Media3's own `DefaultDrmSessionManagerProvider` reads this flag off
            // `MediaItem.DrmConfiguration`, which is how a stock player answers per item. A
            // SuperPlayer has no per-item place to put it and needs none: the answer here is
            // right for every playlist, and the wrong one was only ever right for a playlist of
            // one.
            //
            // `setSessionKeepaliveMs` is deliberately **not** set beside it, which is a decision
            // now that this flag has made it reachable. Media3's own
            // `DEFAULT_SESSION_KEEPALIVE_MS` — five minutes — is what carries a session across
            // the gap between one item releasing its last renderer reference and the next item
            // acquiring one, so it is the half of "one licence for a playlist" the flag alone
            // does not buy; the number stays Media3's because nothing here has measured a better
            // one, and a constant this repository invented would be a constant it could not
            // argue. What it costs is bounded: a kept-alive session counts against the device's
            // concurrent limit, and only a player working through many *differently keyed* items
            // inside five minutes piles them up — which a playlist does not do, and a feed does
            // not either, since a feed gives each row its own player and so its own manager
            // (ADR-0010).
            //
            // ref: `DefaultDrmSessionManager.Builder.setSessionKeepaliveMs`; `C.TIME_UNSET`
            // disables it, and that is the value this must never quietly become.
            .setMultiSession(true)
            // The player's own, never one of this module's making. Media3 asks a session
            // manager's `LoadErrorHandlingPolicy` about a failed licence load and a media source
            // factory's about every other load, so a manager left to build its own would answer
            // for the licence out of Media3's defaults while the same player answered for its
            // segments out of `RetryPolicy` — which is exactly why `RetryPolicy.licence` was
            // declared and unspendable until #205. Null is a player with no
            // `PlaybackResilience`, and then Media3's default is the right answer rather than a
            // gap: such a player keeps Media3's handling for every other load too.
            .apply { loadErrors?.let(::setLoadErrorHandlingPolicy) }
            .build(callback)
    }
}

/**
 * A session graph that also writes down which format it was asked about.
 *
 * Every member forwarded **by hand** and not by Kotlin delegation, which is ADR-0003's rule arriving
 * at a Media3 interface other than `Player`: four of `DrmSessionManager`'s six members are Java
 * `default` methods, and `by delegate` would silently leave them at the interface's own defaults —
 * a `prepare`/`release` pair that never reached the real manager, so the device would be acquired
 * and never let go. The compiler says nothing about it, which is why it is written out.
 */
private class FormatRecordingSessions(
    private val delegate: DrmSessionManager,
    private val onFormat: (Format) -> Unit,
) : DrmSessionManager {

    override fun setPlayer(playbackLooper: Looper, playerId: PlayerId) = delegate.setPlayer(playbackLooper, playerId)

    override fun prepare() = delegate.prepare()

    override fun release() = delegate.release()

    override fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format,
    ): DrmSessionManager.DrmSessionReference {
        record(format)
        return delegate.preacquireSession(eventDispatcher, format)
    }

    override fun acquireSession(eventDispatcher: DrmSessionEventListener.EventDispatcher?, format: Format): DrmSession? {
        record(format)
        return delegate.acquireSession(eventDispatcher, format)
    }

    override fun getCryptoType(format: Format): Int = delegate.getCryptoType(format)

    // Only a format that carries protection data, because that is the only kind a licence can be
    // acquired for: Media3 asks a manager about clear formats too, and the last one asked about is
    // otherwise whichever track the engine enabled last.
    private fun record(format: Format) {
        if (format.drmInitData != null) onFormat(format)
    }
}
