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

    /** The device as an `ExoMediaDrm`, built fresh per session as a real provider's would be. */
    fun exoMediaDrm(): ExoMediaDrm {
        val fake = FakeExoMediaDrm.Builder()
            .setMaxConcurrentSessions(maxConcurrentSessions)
            .setProvisionsRequired(if (provisioningRequired || provisioningFails) 1 else 0)
            .build()
        return StatedExoMediaDrm(fake, this)
    }
}

/**
 * Media3's fake Widevine implementation, answering the three questions a *device* rather than a
 * licence exchange decides.
 *
 * Delegation rather than a fork of `FakeExoMediaDrm`: the session handling, the key-request encoding
 * and the provisioning bookkeeping are Media3's and are what make the exchange realistic, and the
 * three overrides below are the three things `FakeExoMediaDrm` has no opinion about because Media3's
 * own tests never needed one.
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
