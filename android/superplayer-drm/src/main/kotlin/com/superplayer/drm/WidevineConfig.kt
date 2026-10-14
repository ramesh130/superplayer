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

/**
 * What a player needs in order to acquire a Widevine licence: the server that issues one.
 *
 * `PRD.md` §2.2's reserved shape, and deliberately small. A licence server is nominated by the app
 * rather than by the content — the manifest declares *that* the content is protected and under which
 * key id, and nothing in it says who may issue a licence for it — so this is the one fact no profile,
 * policy or library default can stand in for.
 *
 * **It is the player's and not the request's** (ADR-0012 rule 1). Handing this to
 * `SuperPlayer.Builder.setDrm` fixes it for that player's lifetime, and a [com.superplayer.core.MediaRequest]'s
 * `sources` list stays what it has always been: one piece of content in more than one place, which
 * may mean a second container and a second protection-scheme mapping — DASH `cenc` against HLS
 * SAMPLE-AES — but never a second entitlement.
 *
 * ## The credential is not here, and where it is instead
 *
 * A licence server normally wants to know who is asking, and the answer is an entitlement token only
 * the app can mint. ADR-0012 rule 2 decides that it arrives through the **one** `HeaderProvider` the
 * library has rather than through a second one declared here, and since #205 it does: a licence
 * request travels the same chain a segment request does, entering it at the header-refresh line
 * (`TransferChain`), so the provider a consumer hands `Resilience.standard(headers = …)` repairs a
 * licence refused 401 or 403 inside the transfer that met the refusal, exactly as it repairs a
 * refused segment.
 *
 * So the credential is a property of the *player* rather than of this object, and a player that needs
 * one is built with both calls:
 *
 * ```kotlin
 * SuperPlayer.Builder(context)
 *     .setResilience(Resilience.standard(headers = myTokenProvider))
 *     .setDrm(Drm.widevine(WidevineConfig(licenceUri)))
 *     .build()
 * ```
 *
 * So the credential is a property of the *player* rather than of this object. What this type carries
 * is the two things only the server's operator knows — where it is, and what it is willing to issue
 * ([permittedSecurityLevels]) — and never anything the app decided for itself.
 */
public class WidevineConfig(

    /**
     * Where a licence is asked for: the URL the key request is POSTed to.
     *
     * spec: a Widevine licence exchange is an HTTP POST whose body is the opaque key request the
     * device composed and whose response body is the licence — which is what Media3's own
     * `HttpMediaDrmCallback` performs and what this module hands it. The URL is the app's; nothing
     * reads one out of the manifest, deliberately, because a licence server named by content is a
     * licence server an attacker can name.
     */
    public val licenceUri: String,

    /**
     * The security levels [licenceUri]'s operator has said it will issue a licence at, for content a
     * device cannot play at the level it was entitled at — ADR-0012 rule 11's permission, and empty
     * by default, which means none.
     *
     * ## Why this is the server's permission and not the app's
     *
     * Rule 11 requires that policy be server-driven and that the client never downgrade protected
     * content on its own authority. It names two channels for the server to state a permission in:
     * "in its licence response, or **in the configuration the app was given for it**." This is the
     * second, and the distinction from the `setAllowSecurityDowngrade(true)` flag ADR-0012's
     * *Alternatives considered* rejects by name is the whole of why it is allowed:
     *
     * - The rejected flag is a **boolean the app asserts**. It says "downgrade when you need to", is
     *   true of every server the app ever talks to, and encodes a permission nobody granted.
     * - This is **a set of levels the operator of this server published**, handed to the app the way
     *   the address above is and carrying exactly as much of the app's own opinion: none. An app that
     *   writes `setOf(SECURITY_LEVEL_L3)` here is repeating what its licence provider told it about
     *   what that provider will issue, and an app that invents it has misconfigured its server rather
     *   than exercised a library option.
     *
     * The channel is configuration rather than a request because there is nothing to ask. #208 built
     * an HTTP exchange of SuperPlayer's own for this and #223 withdrew it: a `GET` at a licence URI
     * is answered 405 by most real deployments, every failure of it was indistinguishable from a
     * refusal, and its answer had to be waited for on the playback thread. A fact that does not change
     * between sessions is configuration, and asking a server to restate it before every player was
     * paying a round trip and a stall for a constant.
     *
     * ## Empty is a refusal, and that is the load-bearing default
     *
     * An app that says nothing here permits nothing, so a device that cannot honour its own level
     * fails the session rather than quietly receiving a weaker one. That keeps both halves of
     * `PRD.md` §3.2 correct by construction rather than by care: failing hard is what an unstated
     * permission produces and is right, and downgrading silently is unreachable because no path
     * lowers a level that is not named here.
     *
     * ref: the values are `MediaDrm.PROPERTY_SECURITY_LEVEL`'s own vocabulary, [SECURITY_LEVEL_L1] and
     * [SECURITY_LEVEL_L3], so that the configuration, the device and the telemetry all spell a level
     * one way.
     */
    public val permittedSecurityLevels: Set<String> = emptySet(),
) {

    init {
        require(licenceUri.isNotBlank()) { "A licence server needs an address" }
        val unknown = permittedSecurityLevels - SECURITY_LEVELS
        // Rejected rather than ignored, because a typo here is a permission that silently never
        // applies — the failure mode this whole type exists to make impossible, arriving by spelling.
        require(unknown.isEmpty()) { "A Widevine security level is $SECURITY_LEVELS, not $unknown" }
    }

    override fun toString(): String = "WidevineConfig($licenceUri, permits=$permittedSecurityLevels)"

    public companion object {

        /**
         * ref: `MediaDrm.PROPERTY_SECURITY_LEVEL` — the property a Widevine implementation answers
         * `L1` or `L3` to. Keys and decoding inside the trusted execution environment, and a secure
         * decoder required:
         * https://developer.android.com/reference/android/media/MediaDrm#PROPERTY_SECURITY_LEVEL
         */
        public const val SECURITY_LEVEL_L1: String = "L1"

        /** The lower rung: keys handled in software, no secure decoder required. See [SECURITY_LEVEL_L1]. */
        public const val SECURITY_LEVEL_L3: String = "L3"

        /** The whole vocabulary, which is what [permittedSecurityLevels] is checked against. */
        private val SECURITY_LEVELS = setOf(SECURITY_LEVEL_L1, SECURITY_LEVEL_L3)
    }
}
