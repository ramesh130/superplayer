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

package com.superplayer.resilience

import android.net.Uri

/**
 * Where a request's credential comes from when the one it carried has expired: the app's, because
 * only the app can mint one.
 *
 * `PRD.md` §3.3 calls the failure this closes among the commonest in production and almost never
 * handled in an app: a CDN token expires mid-session, every segment after it is refused, and the
 * session ends on an authorization refresh that nobody performed. Hand one of these to
 * [Resilience.standard] and the refusal is repaired inside the transfer that met it — the request is
 * asked again with what this returns, before any retry is spent on a credential that was always
 * going to be refused (ADR-0011 rule 12).
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setResilience(Resilience.standard(headers = HeaderProvider { mapOf("Authorization" to tokens.fresh()) }))
 *     .build()
 * ```
 *
 * ## The contract
 *
 * **It is called only on a refusal**, never on a request that was answered, so a session whose token
 * never expires never calls it at all. What it returns is applied to the refused request's second
 * ask *and* to every later request of that player, because a token is the session's: a refresh that
 * healed one segment and left the next to be refused again would pay for the round trip and keep the
 * failure.
 *
 * **Returning what was already sent — [CredentialRefusal.sentHeaders] — declines the refresh**, and
 * is how a provider says "this is not a credential problem" or "I have nothing newer". Nothing is
 * asked again, the refusal escalates to the ladder, and the whole cost of the decision was one call
 * to this. Throwing declines it the same way, and the refusal that reaches the consumer is the
 * CDN's rather than the provider's, because the CDN's is what the session actually failed on.
 *
 * **It is called on a loading thread**, one refusal at a time per player, and playback of every item
 * that loading thread serves is stopped while it runs. Fetching a token over the network here is the
 * expected use and is why it may block; waiting on a user is not.
 *
 * spec: the headers it returns are ordinary request headers (RFC 9110 §5), and the credential a CDN
 * checks is conventionally `Authorization` (RFC 9110 §11.6.2). A signed URL carries its signature in
 * the query instead and has no header to replace; a provider for one of those has nothing to return
 * here, because the URL a request is opened with is the one the manifest named.
 */
public fun interface HeaderProvider {

    /**
     * The complete set of headers the player should carry from now on, given that [refusal]'s
     * request was refused with the ones it had.
     *
     * Complete rather than additional: what is returned replaces the set this provider last
     * returned, so a provider that drops a header drops it. Returning [CredentialRefusal.sentHeaders]
     * unchanged declines the refresh.
     */
    public fun refreshedHeaders(refusal: CredentialRefusal): Map<String, String>
}

/**
 * One request a CDN would not serve with the credential it carried, as [HeaderProvider] is told
 * about it.
 *
 * A value rather than three parameters because the three travel together and are read together: a
 * provider that mints a token per host needs the URI, one that distinguishes "who are you" from
 * "you may not have this" needs the status, and one that refreshes only what it issued itself needs
 * to see what was sent.
 */
public class CredentialRefusal internal constructor(

    /** The whole address that was refused, query included. */
    public val uri: Uri,

    /**
     * The status the origin answered with: 401 or 403, and no other — the two a CDN uses for a
     * credential it will not accept (// ref: RFC 9110 §15.5.2 and §15.5.4). Every other status is a
     * failure of something this cannot repair and never reaches a provider.
     */
    public val status: Int,

    /**
     * The headers the refused request carried, which are the ones this provider returned last time
     * and are empty until it has returned anything. Returning them unchanged declines the refresh.
     */
    public val sentHeaders: Map<String, String>,
) {

    override fun toString(): String = "$status for $uri"
}
