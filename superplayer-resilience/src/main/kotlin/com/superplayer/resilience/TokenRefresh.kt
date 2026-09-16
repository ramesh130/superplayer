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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import com.superplayer.core.HeaderRefreshLayer

/**
 * The header-refresh slot, filled: a request a CDN refused for its credential is asked again with a
 * credential the app has just minted, inside the one transfer that met the refusal.
 *
 * ## Why here and not above
 *
 * `TransferChain` composes this directly over the transport and below the cache slot, so the second
 * ask is invisible to everything above it: the cache stores one object, measurement sees one
 * transfer, CMCD sends one request's worth of keys, and the ladder is never told about a failure
 * that healed itself (ADR-0011 rules 12 and 13, `TransferChain`'s composition order). The ordering
 * is the point of the whole ticket: refresh first and retry second, so a
 * [RetryingLoadErrors] budget is never spent on a request that was always going to be refused.
 *
 * ## Why a status is read here, and why that is not a second taxonomy
 *
 * ADR-0011 rule 1 keeps the naming of failures in [ErrorClassifier] and nowhere else, and nothing
 * here names one: what this reads is the CDN's answer to *"is this credential acceptable"*, which is
 * a question about the request rather than about the session, and it is asked only to decide whether
 * a repair is even possible. A refusal this cannot repair leaves unchanged, is classified by
 * [ErrorClassifier] exactly as it would have been with no provider at all, and reaches the ladder as
 * itself.
 *
 * spec: RFC 9110 §15.5.2 — 401 means the request lacked valid credentials for the target resource;
 * §15.5.4 — 403 means the server understood the credentials and refuses to serve the resource, which
 * is what a CDN answers an expired signature or token with. Those two and no others: a 404 is an
 * object the edge does not have (a rung above this one), and a 5xx is the edge being unwell, and
 * neither is mended by a new token.
 *
 * ## The bound
 *
 * One refresh per ask, and only where the refresh made progress. A provider that returns the
 * credential just refused, or throws, declines: the original refusal is rethrown unchanged and
 * nothing is asked twice, so a provider with nothing newer to give costs one call and no traffic
 * (ADR-0011 rule 12 — "a refresh that returns the same headers is not retried again"). What bounds
 * the *session* rather than the ask is the ladder above: every refusal this declines is a load error
 * like any other, spending the retry budget in force and escalating when it is gone.
 *
 * One of these per player, because the credential it holds is the session's — [over] is called once
 * per chain and a chain is built per player.
 */
internal class TokenRefreshLayer(private val provider: HeaderProvider) : HeaderRefreshLayer {

    /**
     * The credential every request of this player now carries: what the last refresh returned, and
     * empty until there has been one.
     *
     * Read on every open and written on a refresh, from whichever loading threads the player has, so
     * it is volatile; the refresh itself is serialised by [refreshed] so that two loads refused at
     * once make one call to the provider rather than two.
     */
    @Volatile
    private var credential: Map<String, String> = emptyMap()

    override fun over(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { Refreshing(upstream.createDataSource()) }

    /**
     * The credential to ask [refusal] again with, or null to decline and let the refusal stand.
     *
     * [sent] is what the refused request carried. When it is no longer what [credential] holds,
     * another load has already refreshed since this request was composed, and that newer credential
     * is the answer: asking the provider again would mint a second token for one expiry, which on a
     * feed — where several loads are refused within milliseconds of each other — is the ordinary
     * case rather than the rare one.
     */
    @Synchronized
    private fun refreshed(refusal: CredentialRefusal, sent: Map<String, String>): Map<String, String>? {
        val current = credential
        if (current != sent) return current
        // A provider that throws declines the refresh rather than replacing the failure: what ended
        // the load is the CDN's refusal, and the consumer who has to act on it needs to see that
        // rather than a token service's own trouble.
        val refreshed = try {
            provider.refreshedHeaders(refusal)
        } catch (_: Exception) {
            return null
        }
        if (refreshed == sent) return null
        credential = refreshed
        return refreshed
    }

    /**
     * One request, with one repair available to it.
     *
     * Every method written out rather than delegated with `by`: `getResponseHeaders` and
     * `getUri` reach a Kotlin implementation as Java default methods, which Kotlin delegation does
     * not override and gives no warning about (ADR-0003's trap). [addTransferListener] is the one
     * that matters most — a layer that accepted a registration and did not pass it on would blind
     * bandwidth estimation with nothing in the logs (`TransferChain`'s rule for every layer).
     */
    private inner class Refreshing(private val upstream: DataSource) : DataSource {

        /**
         * Whether [upstream] has ever been opened, so that [close] on a source that never got that
         * far closes nothing: Media3 closes a source whose `open` threw, and a source that was never
         * opened may refuse it.
         */
        private var attempted = false

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val sent = credential
            return try {
                ask(dataSpec, sent)
            } catch (refusal: InvalidResponseCodeException) {
                if (refusal.responseCode !in REFRESHABLE_STATUSES) throw refusal
                val refreshed = refreshed(
                    CredentialRefusal(dataSpec.uri, refusal.responseCode, sent),
                    sent,
                ) ?: throw refusal
                // Closed before it is asked again, which is what Media3's own loaders do between two
                // attempts at one source, and what keeps a transport's accounting of open transfers
                // balanced; a close it does not owe is a no-op in every source in the chain.
                closeQuietly()
                ask(dataSpec, refreshed)
            }
        }

        /** [dataSpec] as [credential] makes it, opened upstream. */
        private fun ask(dataSpec: DataSpec, headers: Map<String, String>): Long {
            attempted = true
            // The request untouched while there is no credential to add, so that a session which
            // never met a refusal sends byte for byte what it would with an empty slot. Additional
            // rather than replacing: CMCD's headers and anything the engine set are the request's
            // too, and only the credential is this layer's to say.
            return upstream.open(if (headers.isEmpty()) dataSpec else dataSpec.withAdditionalHeaders(headers))
        }

        private fun closeQuietly() {
            try {
                upstream.close()
            } catch (_: Exception) {
                // The refusal being repaired is what the caller has to hear about; a close that
                // failed on a request already refused adds nothing it can act on.
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() {
            if (!attempted) return
            attempted = false
            upstream.close()
        }
    }

    private companion object {

        /**
         * The two statuses a new credential can mend. See the class KDoc for the citation and for
         * why 404 and the 5xx band are not among them.
         */
        val REFRESHABLE_STATUSES = setOf(401, 403)
    }
}
