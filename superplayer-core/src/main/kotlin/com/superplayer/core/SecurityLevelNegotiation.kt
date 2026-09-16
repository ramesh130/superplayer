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

import java.io.IOException

/**
 * How a licence server says whether content it protects may be delivered at a lower security level
 * than the one it was entitled at — ADR-0012 rule 11's permission, on the wire.
 *
 * **Why there is a wire format here at all.** Rule 11 allows the permission to be stated in the
 * licence response or in the configuration the app was given for the server, and forbids the client
 * to decide for itself; ADR-0012's *Alternatives considered* rejects a `setAllowSecurityDowngrade`
 * builder flag by name, because a flag is the app asserting a permission that is the server's to
 * give. That leaves the server's own answer, and Widevine's licence body is opaque to everything but
 * the device — SuperPlayer cannot read one and should not try. So the permission travels beside the
 * licence, in headers, where both parties can read it.
 *
 * **It is SuperPlayer's own and is not a standard.** Nothing in CTA-5004, ISO/IEC 23001-7 or
 * Android's `MediaDrm` defines a downgrade negotiation, and no commercial licence protocol is
 * mirrored here (`CONTRIBUTING.md`'s clean-room rule): what follows is defined by this file and by
 * nothing else, so that a server operator implementing it has one page to read.
 *
 * ## The exchange
 *
 * A player asks **only** when its device cannot honour the level it reports — the case `PRD.md` §3.2
 * names, an `L1` device with no secure decoder — and asks once per player, before any licence is
 * requested. A device that can honour its own level sends nothing and the exchange does not happen.
 *
 * ```http
 * GET <licence server URI>
 * SuperPlayer-Security-Level: L3
 * ```
 *
 * A `GET` because this asks a question rather than acquiring anything: it carries no key request, it
 * changes nothing, and a server may answer it from a policy table without touching a device's
 * entitlement. It travels the licence transport, so it carries the app's credential and is repaired
 * by the header-refresh layer exactly as a licence request is (ADR-0012 rule 2).
 *
 * The answer is a response header naming the level the server permits for this request:
 *
 * ```http
 * 200 OK
 * SuperPlayer-Security-Level-Permitted: L3
 * ```
 *
 * **Anything else is a refusal**: a missing header, a header naming a different level, a status the
 * server refused with, a transport that failed. Silence is "no" and never "yes", which is the whole
 * of what keeps the failing-hard half of `PRD.md` §3.2 correct and the downgrading-silently half
 * impossible — a server that has never heard of this exchange refuses every downgrade by doing
 * nothing at all.
 */
public object SecurityLevelNegotiation {

    /**
     * What the client sends: the level it can actually honour, and therefore the level it is asking
     * about. Never a demand — the client has not downgraded anything when it sends this.
     */
    public const val LEVEL_REQUEST_HEADER: String = "SuperPlayer-Security-Level"

    /**
     * What the server answers with to permit the downgrade, valued with the level it permits. Absent
     * means refused, which is the default a server that ignores this exchange gives.
     */
    public const val LEVEL_PERMITTED_HEADER: String = "SuperPlayer-Security-Level-Permitted"

    /**
     * ref: `MediaDrm.PROPERTY_SECURITY_LEVEL` — the property a Widevine device answers `L1` or `L3`
     * to, and the vocabulary these headers are valued in, so that the wire and the device use one set
     * of names rather than two:
     * https://developer.android.com/reference/android/media/MediaDrm#PROPERTY_SECURITY_LEVEL
     */
    public const val LEVEL_L1: String = "L1"

    /** The lower rung: keys handled in software, no secure decoder required. See [LEVEL_L1]. */
    public const val LEVEL_L3: String = "L3"
}

/**
 * The session could not be opened at a level this device can honour, and the licence server did not
 * permit the lower one — so nothing was delivered rather than something weaker (ADR-0012 rule 11).
 *
 * Raised by `superplayer-drm` and carried out of it as evidence rather than as a classification,
 * which is ADR-0012 rule 6's shape and the reason this type is core's: `superplayer-resilience`'s
 * `ErrorClassifier` reads the fields below and names the failure, and the module that found them
 * keeps no taxonomy of its own (ADR-0012 rule 5).
 *
 * A consumer sees it as the cause of the `PlaybackException` `onPlayerError` carries, under the
 * `SuperPlayerError` rung 6 builds where the fallback ladder is attached. It is **not** a licence
 * that could not be fetched: the round trip may have succeeded perfectly and said no.
 */
public class SecurityDowngradeRefusedException internal constructor(

    /** What the device reports for `securityLevel`, and cannot honour — `L1` in the case that occurs. */
    public val deviceSecurityLevel: String,

    /** The level the client asked the server about, and the one it will not open a session at. */
    public val refusedLevel: String,

    /**
     * What the server actually answered with, for a bug report: the value of
     * [SecurityLevelNegotiation.LEVEL_PERMITTED_HEADER], or null where it sent none — which is the
     * commonest reason a downgrade is refused and is not an error on anyone's part.
     */
    public val permittedLevel: String?,
) : IOException(
    "This device reports $deviceSecurityLevel but cannot honour it, and the licence server did not " +
        "permit $refusedLevel (it answered ${permittedLevel ?: "nothing"})",
)
