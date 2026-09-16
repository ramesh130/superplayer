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
            // per player because protection is the player's (rule 1), and `DefaultDrmSessionManager`
            // already keeps one session per distinct `DrmInitData` beneath that.
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
