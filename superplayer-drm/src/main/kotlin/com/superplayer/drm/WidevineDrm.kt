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

import androidx.media3.common.C
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import com.superplayer.core.EngineConfiguration
import com.superplayer.core.EngineDrmExtension
import com.superplayer.core.LicenceSessions
import com.superplayer.core.SecurityDowngradeRefusedException
import com.superplayer.core.SecurityLevelNegotiation
import com.superplayer.core.refusedSessions

/**
 * The extension half of [Drm.widevine]: it fills the one slot ADR-0012 rule 3 opens, once per engine.
 *
 * Internal, so that the core-internal interface it implements — and every `@UnstableApi` Media3 type
 * it names — stays off this module's public API.
 *
 * Stateless, because it has to be: one `PlaybackDrm` may be handed to many players, so everything
 * with a lifetime is built inside the slot, which is invoked once per player's transfer chain.
 */
internal class WidevineDrm(private val config: WidevineConfig) : EngineDrmExtension {

    override fun configureEngine(configuration: EngineConfiguration) {
        configuration.drm = LicenceSessions { licence ->
            val licenceTransport = licence.transport
            val loadErrors = licence.loadErrors
            // ADR-0012 rule 11, and the whole of #208: a device that cannot honour the level it
            // reports asks the licence server whether the lower one is permitted, and does nothing
            // at all otherwise. `levelToAskAbout` returns null for every ordinary device, and the
            // question — the thread, the request, the wrapper below — is never composed for one.
            //
            // The device is resolved to a provider first, because the probe has to read a property
            // off it and `null` here means Media3's own `FrameworkMediaDrm.DEFAULT_PROVIDER` rather
            // than "no device" — naming that default explicitly changes nothing about the session
            // graph and is what makes the probe reachable on a real handset as well as under the
            // harness.
            val device = licence.mediaDrm ?: FrameworkMediaDrm.DEFAULT_PROVIDER
            val askingAbout = SecurityLevelLadder.levelToAskAbout(device, licence.device)
            val permission = askingAbout?.let { DowngradePermission(licenceTransport, config.licenceUri, it) }
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
            val manager = DefaultDrmSessionManager.Builder()
                // The device. Set on every player rather than only where a test stands one in, and
                // that is a no-op rather than a change: `DefaultDrmSessionManager.Builder`'s own
                // default for Widevine *is* `FrameworkMediaDrm.DEFAULT_PROVIDER`, so naming it is
                // the same session graph written down. What it buys is the line below — the probe
                // and the lowering need a provider to hold, and a handset would otherwise have none.
                //
                // Where a downgrade was permitted, the same device asked to compose its key requests
                // at the lower level — which is what "requesting an L3 licence" is on the wire
                // (// ref: `MediaDrm.setPropertyString` with `securityLevel`). Where none was asked
                // for, the device untouched.
                .setUuidAndExoMediaDrmProvider(
                    C.WIDEVINE_UUID,
                    if (permission == null) device else LoweredSecurityLevel(device, askingAbout, permission),
                )
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
            // One manager for the player rather than one per item: the session graph is built once
            // per player because protection is the player's (rule 1), and — since #209 turned
            // `multiSession` on above — `DefaultDrmSessionManager` keeps one session per distinct
            // `DrmInitData` beneath that. That sentence stood here before the flag did and was false
            // while it did: at Media3's default the manager keeps one session for everything, and the
            // comment described the behaviour the line above now actually produces.
            when {
                // Nothing was negotiated, so there is nothing to say: the session opened at whatever
                // the device does, which is the ordinary case and not a support engineer's question.
                // Reading the property back here to report it would charge every protected player a
                // `MediaDrm` acquisition for a line in a log.
                permission == null -> DrmSessionManagerProvider { manager }

                // The server permitted the lower level, so the device was asked to compose its key
                // requests at it (`LoweredSecurityLevel`, installed above) and the session graph is
                // otherwise the ordinary one. What was delivered is written down, because it is not
                // what the viewer was entitled to.
                permission.permits() -> {
                    licence.delivered.securityLevel = askingAbout
                    DrmSessionManagerProvider { manager }
                }

                // And the refusal: no session is opened, no licence is asked for, and the typed
                // exception travels to the consumer where every other DRM failure does — ADR-0012
                // rule 11's "fails the session rather than downgrading it". Core dresses it, because
                // the exception is core's and this module names no Media3 error code (rule 5). The
                // device's own level is `L1` by construction: `levelToAskAbout` returns a level to
                // ask about for no other.
                else -> refusedSessions(
                    SecurityDowngradeRefusedException(
                        deviceSecurityLevel = SecurityLevelNegotiation.LEVEL_L1,
                        refusedLevel = askingAbout,
                        permittedLevel = permission.permittedLevel(),
                    ),
                )
            }
        }
    }
}
