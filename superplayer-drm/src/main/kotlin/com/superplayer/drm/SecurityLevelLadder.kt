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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.ExoMediaDrm
import com.superplayer.core.DeviceConstraints
import com.superplayer.core.SecurityDowngradeRefusedException
import com.superplayer.core.SecurityLevelNegotiation
import java.io.IOException

/**
 * `PRD.md` §3.2's security-level ladder: what a player does when its device cannot honour the
 * protection level it advertises.
 *
 * **The rule is louder than the behaviour** (ADR-0012 rule 11). Policy is the *server's*: the client
 * never downgrades protected content on its own authority, so this file's whole job is to ask a
 * question and obey the answer. Both of the failure modes `PRD.md` §3.2 names are ruled out by the
 * shape rather than by care — failing hard is what an unanswered question produces and is correct,
 * and downgrading silently is unreachable because no code path here lowers a level without a
 * permission it read off the wire ([SecurityLevelNegotiation]).
 *
 * **It is not a seventh rung** of ADR-0011's `FallbackLadder`, and ADR-0012's *Alternatives
 * considered* rejects making it one. A rung is a remedy the client applies once a failure is
 * classified; this runs in the session-opening path, before any failure exists, and its precondition
 * is a permission rather than a classification. The failure taxonomy keeps the ceilings ADR-0011 gave
 * it and nothing about the ladder changes.
 *
 * ## When the question is asked
 *
 * Exactly once per player, and only where the device has already lost: it reports
 * [SecurityLevelNegotiation.LEVEL_L1] — so every licence it is issued will require a decoder
 * operating on protected memory (// ref: `MediaDrm.requiresSecureDecoderComponent`) — and
 * [DeviceConstraints.hasSecureVideoDecoder] says it has none. That is one of the three ways `PRD.md`
 * §3.2 says L1 can be unusable, and it is the one a player can see *before* it has spent a licence
 * round trip finding out.
 *
 * The other two are named rather than handled, because naming what is not covered is worth more than
 * implying it is. **A failed L1 provisioning** surfaces asynchronously from inside
 * `DefaultDrmSessionManager`, after the session graph this file builds is fixed, and since #207 it
 * ends at rung 6 on `Drm.Provisioning` or `Drm.Unsupported` rather than here. **A secure surface that
 * cannot be allocated** is a property of the `Surface` a consumer attaches, which no test under
 * Robolectric can produce — there is no `MediaCrypto` and no protected buffer queue — and which
 * `docs/testing.md` therefore keeps out of `check` rather than faking.
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
        val reported = reportedSecurityLevel(mediaDrm) ?: return null
        if (reported != SecurityLevelNegotiation.LEVEL_L1) return null
        return SecurityLevelNegotiation.LEVEL_L3
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
 * The licence server's answer to "may this session open at [level]?", asked once and remembered.
 *
 * **Asked eagerly, on a thread of its own, and awaited where the answer is needed.** The session
 * graph is composed on the thread that called `SuperPlayer.Builder.build()` — the app's main thread
 * in every real app — so the question cannot be put there; and it is *consumed* on the playback
 * thread, where a round trip of unbounded length would be a stall a viewer sees. Starting it at
 * composition time and waiting for it at first use overlaps it with the manifest load that follows,
 * which is where the latency goes to hide. The wait is bounded by [ANSWER_TIMEOUT_MS] and a timeout
 * is a refusal, for [SecurityLevelNegotiation]'s reason: silence is never a permission.
 *
 * One thread per player that engages the ladder, which is a device that cannot honour its own
 * protection level — not a common device, and not a thread any other player pays for. It is a daemon
 * so that a process exiting mid-question is not held open by it.
 */
internal class DowngradePermission(
    private val transport: DataSource.Factory,
    private val licenceUri: String,
    private val level: String,
) {

    /** What the server answered with, or null for "nothing usable"; read only through [permits]. */
    @Volatile
    private var answer: String? = null

    private val asked = Thread({ answer = ask() }, "SuperPlayerDowngradePermission").apply {
        isDaemon = true
        start()
    }

    /**
     * Whether the server permitted [level]. Blocks for at most [ANSWER_TIMEOUT_MS] the first time,
     * and not at all afterwards.
     */
    fun permits(): Boolean = permittedLevel() == level

    /** The raw answer, for the evidence [SecurityDowngradeRefusedException] carries. */
    fun permittedLevel(): String? {
        try {
            asked.join(ANSWER_TIMEOUT_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return answer
    }

    /**
     * One request, one header read. See [SecurityLevelNegotiation] for the exchange and for why a
     * failure of any kind is indistinguishable from a refusal here: this returns null for a refused
     * status, a failed transfer and a server that has never heard of the header alike, because all
     * three are the server not having permitted anything.
     */
    private fun ask(): String? {
        val source = transport.createDataSource()
        return try {
            source.open(
                DataSpec.Builder()
                    .setUri(licenceUri)
                    .setHttpMethod(DataSpec.HTTP_METHOD_GET)
                    .setHttpRequestHeaders(mapOf(SecurityLevelNegotiation.LEVEL_REQUEST_HEADER to level))
                    .build(),
            )
            // Drained before the headers are read, because a `DataSource` is entitled to have sent
            // nothing back until it has been read from, and a response left half-open would hold a
            // connection for a question that is already answered.
            DataSourceUtil.readToEnd(source)
            // Case-insensitively, because HTTP field names are (// spec: RFC 9110 §5.1) and the
            // stack at the bottom of the chain is ADR-0004's open question — one that returns the
            // server's own spelling must not turn a permission into a refusal.
            source.responseHeaders.entries
                .firstOrNull { it.key.equals(SecurityLevelNegotiation.LEVEL_PERMITTED_HEADER, ignoreCase = true) }
                ?.value?.firstOrNull()
        } catch (failure: IOException) {
            null
        } catch (failure: RuntimeException) {
            null
        } finally {
            DataSourceUtil.closeQuietly(source)
        }
    }

    private companion object {

        /**
         * How long a session graph waits for the answer before treating it as a refusal.
         *
         * Five seconds: long enough for the round trip the header-refresh layer may have had to
         * repair on a slow cellular link, and short enough that a viewer on a device that cannot play
         * the content anyway is told so rather than left watching a spinner. It bounds a wait on the
         * playback thread, which is why it is a small number and not a generous one.
         */
        const val ANSWER_TIMEOUT_MS = 5_000L
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
 * The property is set **only** where [permission] says the server allowed it, and [permission] is
 * read here rather than at composition time because this is the first moment a thread that may block
 * is available. A device whose implementation refuses the property is left exactly as it was: the
 * session then opens at the level the device insists on, which is the same outcome as never having
 * asked and is never a downgrade nobody permitted.
 */
internal class LoweredSecurityLevel(
    private val delegate: ExoMediaDrm.Provider,
    private val level: String,
    private val permission: DowngradePermission,
) : ExoMediaDrm.Provider {

    override fun acquireExoMediaDrm(uuid: java.util.UUID): ExoMediaDrm {
        val drm = delegate.acquireExoMediaDrm(uuid)
        if (permission.permits()) {
            try {
                drm.setPropertyString(SECURITY_LEVEL_PROPERTY, level)
            } catch (e: RuntimeException) {
                // A device that will not be lowered plays at the level it has or fails on the
                // licence; either way nothing here has downgraded anything it was not permitted to.
            }
        }
        return drm
    }
}

/** ref: the name of `MediaDrm.PROPERTY_SECURITY_LEVEL`, shared by the probe and the lowering. */
private const val SECURITY_LEVEL_PROPERTY = "securityLevel"
