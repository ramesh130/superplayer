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

package com.superplayer.testkit

import android.media.DeniedByServerException
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.test.utils.FakeExoMediaDrm

/**
 * How well a device protects the content it decodes, in Widevine's own two-rung vocabulary.
 *
 * ref: Android's `MediaDrm.PROPERTY_SECURITY_LEVEL` is the string `securityLevel`, and a Widevine
 * implementation answers it with `L1` or `L3`:
 * https://developer.android.com/reference/android/media/MediaDrm#PROPERTY_SECURITY_LEVEL
 *
 * The distinction is not a shade of the same thing, which is ADR-0012 rule 11's whole argument for
 * keeping a security-level downgrade out of the fallback ladder: at [L1] the keys and the decode both
 * sit in the device's trusted execution environment and a licence server may allow HD, while at [L3]
 * they do not and the same server usually will not. A viewer moved from one to the other has had a
 * different thing delivered rather than the same thing rescued.
 */
public enum class SecurityLevel {

    /** Keys and decoding inside the trusted execution environment; a secure decoder is required. */
    L1,

    /** Keys handled in software; the content plays on an ordinary decoder, usually capped. */
    L3,
}

/**
 * What a test has said about this device's Widevine implementation, read by [PlaybackHarness] when it
 * builds a player for protected content.
 *
 * A statement the harness reads rather than a shadow the platform answers, and that is forced rather
 * than chosen: **Robolectric 4.16 ships no `ShadowMediaDrm`**, so `MediaDrm` cannot be instantiated
 * under `check` at all and there is no platform object for a shadow to state anything about. What
 * stands in for the device is Media3's `FakeExoMediaDrm`, which is the object a
 * `DrmSessionManager` actually talks to, and this is what configures one — which means a test states
 * its device in the same place and at the same moment as it states a display or a decoder
 * ([DeviceStatement]), and nothing about that difference shows up in the test.
 */
internal class WidevineStatement(
    val securityLevel: SecurityLevel,
    val maxConcurrentSessions: Int,
    val provisioningRequired: Boolean,
    val provisioningFails: Boolean,
) {

    /**
     * Every implementation this statement has handed out, so that a test can make the *device* do
     * something to a session that is already open.
     *
     * Held here and nowhere else because there is nowhere else: a real provider hands out a new
     * implementation per acquisition and keeps no register of them, and neither does
     * [PlaybackHarness] — the slot it fills is a provider, and what the provider returns goes
     * straight into a session manager. Everything a device does *before* a session exists is stated
     * as a field of this class; a key rotation is the one thing it does *after*, so a handle is the
     * only way to say it. Cleared with the statement itself ([DeviceStatement.forgetWidevine]), so
     * one test's device is not the next one's here either.
     */
    private val handedOut = mutableListOf<FakeExoMediaDrm>()

    /** The device as an `ExoMediaDrm`, built fresh per session as a real provider's would be. */
    fun exoMediaDrm(): ExoMediaDrm {
        val fake = FakeExoMediaDrm.Builder()
            .setMaxConcurrentSessions(maxConcurrentSessions)
            .setProvisionsRequired(if (provisioningRequired || provisioningFails) 1 else 0)
            .build()
        handedOut += fake
        return StatedExoMediaDrm(fake, this)
    }

    /**
     * The device telling every session it holds open that its keys must be renewed.
     *
     * ref: `MediaDrm.EVENT_KEY_REQUIRED` is the event a Widevine implementation raises when the
     * keys a session holds need replacing — which on a live stream is a key rotation, since the
     * packager changes the key under a running session rather than ending it:
     * https://developer.android.com/reference/android/media/MediaDrm#EVENT_KEY_REQUIRED
     *
     * The *device* raises it and not the licence server, which is why it is stated here rather than
     * on [FakeLicenceServer]: the server changes the key it will issue, the device notices that the
     * keys in force no longer decrypt, and what a player sees is this event. A harness that made a
     * rotation a property of the server would be describing the half no client can observe.
     *
     * `EVENT_KEY_EXPIRED` is deliberately not offered beside it. Media3 routes only
     * `EVENT_KEY_REQUIRED` to a session at all (`DefaultDrmSession.onMediaDrmEvent`), so a lever for
     * the other would be a lever for nothing, and a test using it would be asserting that Media3
     * ignores an event rather than that SuperPlayer renews a licence.
     */
    fun signalKeyRotation() {
        handedOut.forEach { device ->
            // Every open session of every implementation: the predicate is the *device's* way of
            // addressing a rotation at one session id, and a test here has no session id to name —
            // it knows the content it asked for and not the bytes a session was opened under.
            device.triggerEvent({ true }, ExoMediaDrm.EVENT_KEY_REQUIRED, /* extra= */ 0, /* data= */ ByteArray(0))
        }
    }
}

/**
 * Media3's fake Widevine implementation, answering the three questions a *device* rather than a
 * licence exchange decides.
 *
 * Delegation rather than a fork of `FakeExoMediaDrm`: the session handling, the key-request encoding
 * and the provisioning bookkeeping are Media3's and are what make the exchange realistic, and the
 * four overrides below are the four things `FakeExoMediaDrm` has no opinion about because Media3's
 * own tests never needed one — its provisioning address included, which Media3 leaves a placeholder
 * because its own tests answer the callback in memory rather than over a transport.
 */
private class StatedExoMediaDrm(
    private val delegate: FakeExoMediaDrm,
    private val statement: WidevineStatement,
) : ExoMediaDrm by delegate {

    /**
     * ref: `MediaDrm.PROPERTY_SECURITY_LEVEL` — the property a Widevine device answers `L1` or `L3`
     * to, and the one every piece of software that cares about the distinction reads.
     */
    override fun getPropertyString(propertyName: String): String =
        if (propertyName == SECURITY_LEVEL_PROPERTY) {
            statement.securityLevel.name
        } else {
            delegate.getPropertyString(propertyName)
        }

    /**
     * ref: `MediaDrm.requiresSecureDecoderComponent` — whether the licence in force can only be used
     * by a decoder operating on protected memory. True at [SecurityLevel.L1] and false at
     * [SecurityLevel.L3], which is the definition of the two levels rather than a simplification.
     */
    override fun requiresSecureDecoder(sessionId: ByteArray, mimeType: String): Boolean =
        statement.securityLevel == SecurityLevel.L1

    /**
     * The provisioning round trip this device would make, addressed at [FakeLicenceServer].
     *
     * ref: `MediaDrm.getProvisionRequest` returns opaque request data **and the URL of the
     * provisioning service the device implementation trusts** — on a Widevine handset, Google's
     * certificate provisioning endpoint, which the device names and the app never does:
     * https://developer.android.com/reference/android/media/MediaDrm.ProvisionRequest#getDefaultUrl()
     * `FakeExoMediaDrm` names `bar.test`, a placeholder Media3's own tests never dial, and the whole
     * of what is changed here is the address.
     *
     * That one substitution is what lets `superplayer-drm` be tested through its **own** callback
     * rather than a stand-in (#207). Media3's `HttpMediaDrmCallback` — the production one — sends a
     * provisioning request to the URL this method names, and until the fake device named an address
     * the harness answers at, a provisioning round trip through a real `SuperPlayer` left the harness
     * entirely and could neither be counted, delayed nor refused. Bending the library's callback to
     * the harness instead would have been the wrong repair: the shape under test would then have been
     * the test's rather than the field's.
     *
     * The request *data* is the delegate's, unaltered, because that is what the licence server
     * recognises and what makes the exchange Media3's rather than this file's.
     */
    override fun getProvisionRequest(): ExoMediaDrm.ProvisionRequest =
        ExoMediaDrm.ProvisionRequest(delegate.provisionRequest.data, FakeLicenceServer.PROVISION_URI)

    /**
     * A device whose provisioning the service will not complete.
     *
     * ref: `MediaDrm.provideProvisionResponse` throws `DeniedByServerException` when the provisioning
     * server has refused this device — a revoked or untrusted implementation, which on a real handset
     * is the commonest reason L1 is unusable while L3 is fine. The refusal is the *device's* answer
     * and not the transfer's, which is why it is stated here and not as a [FaultScript] fault: a
     * fault at [ResourceKind.LICENCE] is a provisioning request that never reached a server, and this
     * is one that reached it and was turned down.
     */
    override fun provideProvisionResponse(response: ByteArray) {
        if (statement.provisioningFails) {
            throw DeniedByServerException("The provisioning service refused this device (stated by DeviceStatement)")
        }
        delegate.provideProvisionResponse(response)
    }
}

/** ref: the value of `MediaDrm.PROPERTY_SECURITY_LEVEL`. */
private const val SECURITY_LEVEL_PROPERTY = "securityLevel"
