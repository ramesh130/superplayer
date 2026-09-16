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
 * This type carries the server and nothing else, which is stated rather than left for a reader to
 * discover in an empty parameter.
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
) {

    init {
        require(licenceUri.isNotBlank()) { "A licence server needs an address" }
    }

    override fun toString(): String = "WidevineConfig($licenceUri)"
}
