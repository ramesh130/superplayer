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
import androidx.media3.exoplayer.drm.ExoMediaDrm
import com.superplayer.core.DeviceConstraints

/**
 * `PRD.md` §3.2's security-level ladder: what a player does when its device cannot honour the
 * protection level it advertises.
 *
 * **The rule is louder than the behaviour** (ADR-0012 rule 11). Policy is the *server's*: the client
 * never downgrades protected content on its own authority, so this file's whole job is to ask a
 * question and obey the answer. Both of the failure modes `PRD.md` §3.2 names are ruled out by the
 * shape rather than by care — failing hard is what an unanswered question produces and is correct,
 * and downgrading silently is unreachable because no code path here lowers a level without a
 * permission the server's operator stated ([WidevineConfig.permittedSecurityLevels]).
 *
 * **It is not a seventh rung** of ADR-0011's `FallbackLadder`, and ADR-0012's *Alternatives
 * considered* rejects making it one. A rung is a remedy the client applies once a failure is
 * classified; this runs in the session-opening path, before any failure exists, and its precondition
 * is a permission rather than a classification. The failure taxonomy keeps the ceilings ADR-0011 gave
 * it and nothing about the ladder changes.
 *
 * ## When the question is asked
 *
 * At two moments, for two of the three ways `PRD.md` §3.2 says L1 can become unusable, and never
 * anywhere else.
 *
 * **Before the session graph is composed** ([levelToAskAbout]), and then only where the device has
 * already lost: it reports [WidevineConfig.SECURITY_LEVEL_L1] — so every licence it is issued will
 * require a decoder operating on protected memory (// ref: `MediaDrm.requiresSecureDecoderComponent`)
 * — and [DeviceConstraints.hasSecureVideoDecoder] says it has none. That is the way a player can see
 * *before* it has spent a licence round trip finding out, and #208 built it.
 *
 * **After a protection failure** ([levelToFallTo]), which is #225 and the second way: a device the
 * provisioning service will not certify at the level it reports. Nothing about such a device is
 * visible in advance — it declares a secure decoder and looks perfectly capable — and the refusal
 * arrives from inside `DefaultDrmSessionManager` once the graph is fixed. So the remedy is the graph
 * built again, which is `WidevineDrm`'s `reopenAtLowerLevel`, and the permission it needs is the same
 * permission and read from the same place. It is still not a rung: core offers the failure here
 * before it offers it to the ladder, and the ladder is neither consulted nor changed
 * (ADR-0012 rule 11's #225 addendum).
 *
 * **A secure surface that cannot be allocated** is the third way and is still not handled. It is a
 * property of the `Surface` a consumer attaches, which no test under Robolectric can produce — there
 * is no `MediaCrypto` and no protected buffer queue — and which `docs/testing.md` therefore keeps out
 * of `check` rather than faking. It reaches a player as an ordinary failure on the same path #225
 * built, so what it needs is the predicate widened rather than a mechanism (#226).
 *
 * A device that can honour what it reports runs none of this: no probe, no request, no wrapper. That
 * is ADR-0012 rule 13's posture applied one level in.
 */
internal object SecurityLevelLadder {

    /**
     * Whether [device] can use a licence issued at the level [mediaDrm] reports, or null where
     * something declined to say.
     *
     * Null is *unknown* and unknown engages nothing, which is the direction [DeviceConstraints]
     * reads every unknown in and is load-bearing twice over here: a device whose codec list is empty
     * would otherwise have every protected session refused, and a `MediaDrm` that throws from a
     * property read would take playback down with it for a question nobody asked.
     */
    fun levelToAskAbout(mediaDrm: ExoMediaDrm.Provider?, device: DeviceConstraints): String? {
        // Asked in this order because the cheap half is the one that rules the case out: reading the
        // decoder table is a walk core has already done, while acquiring an `ExoMediaDrm` to read a
        // property means instantiating the device's protection stack. A device with a secure decoder
        // — or one that declared no decoders at all — never gets that far.
        if (device.hasSecureVideoDecoder() != false) return null
        return levelToFallTo(mediaDrm)
    }

    /**
     * The level below the one [mediaDrm] reports, or null where there is none to fall to or the
     * device would not say.
     *
     * The other half of [levelToAskAbout], asked on its own after a protection failure (#225), where
     * the device's decoder table has already been proved irrelevant: a device the provisioning
     * service refuses at `L1` is refused whatever its decoders can do.
     *
     * Null for a device that already reports [WidevineConfig.SECURITY_LEVEL_L3], which is what makes
     * this an actual *fall*: there is exactly one level below `L1` in Widevine's vocabulary, and
     * asking to be lowered to the level already in force would spend a session graph to change
     * nothing. Null too for a device that will not answer, which is [reportedSecurityLevel]'s rule
     * that unknown engages nothing.
     */
    fun levelToFallTo(mediaDrm: ExoMediaDrm.Provider?): String? {
        val reported = reportedSecurityLevel(mediaDrm) ?: return null
        if (reported != WidevineConfig.SECURITY_LEVEL_L1) return null
        return WidevineConfig.SECURITY_LEVEL_L3
    }

    /**
     * What the device answers for `securityLevel`, or null where it would not say.
     *
     * ref: `MediaDrm.PROPERTY_SECURITY_LEVEL`, the string `securityLevel`, answered `L1` or `L3` by a
     * Widevine implementation:
     * https://developer.android.com/reference/android/media/MediaDrm#PROPERTY_SECURITY_LEVEL
     *
     * Acquired and released around the one read. A `MediaDrm` instance is a scarce device resource —
     * holding one open for the life of a player to answer a question asked once would be charging
     * every protected session for this file.
     */
    private fun reportedSecurityLevel(mediaDrm: ExoMediaDrm.Provider?): String? {
        val provider = mediaDrm ?: return null
        return try {
            val drm = provider.acquireExoMediaDrm(C.WIDEVINE_UUID)
            try {
                drm.getPropertyString(SECURITY_LEVEL_PROPERTY)
            } finally {
                drm.release()
            }
        } catch (e: RuntimeException) {
            // Vendor implementations throw from property reads for reasons of their own, and a
            // device that will not answer has not said "L1". Unknown, and therefore no ladder.
            null
        }
    }
}

/**
 * The device, asked to compose its key requests at [level] instead of the one it reports.
 *
 * ref: `MediaDrm.setPropertyString` with `securityLevel` is the lever a Widevine implementation
 * offers for asking it to operate at a lower level than it is capable of; there is no other, and
 * there is deliberately none of SuperPlayer's own:
 * https://developer.android.com/reference/android/media/MediaDrm#setPropertyString(java.lang.String,%20java.lang.String)
 *
 * **Composed only where the permission was already given.** `WidevineDrm` reads
 * [WidevineConfig.permittedSecurityLevels] before it builds the session graph and installs this
 * wrapper for no other case, so there is no permission left to consult here and no moment at which
 * one could be refused — which is what withdrawing #208's wire format bought (#223). A device whose
 * implementation refuses the property is left exactly as it was: the session then opens at the level
 * the device insists on, which is the same outcome as never having tried and is never a downgrade
 * nobody permitted.
 */
internal class LoweredSecurityLevel(
    private val delegate: ExoMediaDrm.Provider,
    private val level: String,
) : ExoMediaDrm.Provider {

    override fun acquireExoMediaDrm(uuid: java.util.UUID): ExoMediaDrm {
        val drm = delegate.acquireExoMediaDrm(uuid)
        try {
            drm.setPropertyString(SECURITY_LEVEL_PROPERTY, level)
        } catch (e: RuntimeException) {
            // A device that will not be lowered plays at the level it has or fails on the
            // licence; either way nothing here has downgraded anything it was not permitted to.
        }
        return drm
    }
}

/** ref: the name of `MediaDrm.PROPERTY_SECURITY_LEVEL`, shared by the probe and the lowering. */
private const val SECURITY_LEVEL_PROPERTY = "securityLevel"
