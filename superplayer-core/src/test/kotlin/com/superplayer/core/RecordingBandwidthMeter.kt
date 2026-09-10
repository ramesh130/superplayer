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

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.test.core.app.ApplicationProvider

/**
 * Every request the engine opens, recorded — the test's window onto what a player actually sent.
 *
 * A bandwidth meter rather than a data source wrapper, because the requests worth asserting on are
 * the ones the *real* transfer chain issues and that chain is assembled inside `TransferChain` with
 * no seam for a test to reach into. What the engine does offer is the [TransferListener] it hands the
 * media source at prepare time, which propagates down every layer of the chain — the same
 * registration `TransferChain`'s KDoc warns a future layer not to swallow. A bandwidth meter is how
 * one is supplied, so this is one, and the estimate itself is delegated to Media3's own rather than
 * stubbed, so that nothing a test observes is downstream of a number this class invented.
 *
 * Recording on transfer *start* is what makes the [DataSpec] worth having: it is the spec the chunk
 * source built, with CMCD already attached, before any layer has had a chance to rewrite it.
 */
class RecordingBandwidthMeter(
    private val delegate: BandwidthMeter =
        DefaultBandwidthMeter.Builder(ApplicationProvider.getApplicationContext()).build(),
) : BandwidthMeter by delegate {

    /** Guarded because transfers begin on loader threads and assertions run on the test's. */
    private val opened = mutableListOf<DataSpec>()

    override fun getTransferListener(): TransferListener = object : TransferListener {

        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            delegate.transferListener?.onTransferInitializing(source, dataSpec, isNetwork)
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            synchronized(opened) { opened += dataSpec }
            delegate.transferListener?.onTransferStart(source, dataSpec, isNetwork)
        }

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            delegate.transferListener?.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            delegate.transferListener?.onTransferEnd(source, dataSpec, isNetwork)
        }
    }

    /**
     * The first media segment fetched — the request CMCD has the most to say about, and the only one
     * with a bitrate to report.
     *
     * A playlist fetch carries CMCD too, but with the keys of a manifest request; asserting on the
     * segment is asserting on the case the CDN's own logs are read for.
     */
    fun segmentRequest(): DataSpec = synchronized(opened) { opened.toList() }
        .firstOrNull { it.uri.path.orEmpty().endsWith(SEGMENT_SUFFIX) }
        ?: error("No segment request was opened; saw ${synchronized(opened) { opened.map { spec -> spec.uri } }}")

    /**
     * The CMCD keys of every media segment fetched, in the order they were requested — what a test
     * asserting that a value *moved* needs, since a single request cannot show that.
     */
    fun cmcdKeysOfSegmentRequests(): List<Map<String, String>> =
        synchronized(opened) { opened.toList() }
            .filter { it.uri.path.orEmpty().endsWith(SEGMENT_SUFFIX) }
            .map { it.cmcdKeys() }

    /** [segmentRequest]'s CMCD keys, however this player was configured to send them. */
    fun cmcdKeysOfSegmentRequest(): Map<String, String> = segmentRequest().cmcdKeys()

    private companion object {
        /** What `SyntheticHlsStream` names its segments; the playlists are `.m3u8`. */
        const val SEGMENT_SUFFIX = ".aac"
    }
}

/**
 * The CMCD keys on this request, from whichever transport carried them — the four `CMCD-*` headers,
 * the `CMCD` query parameter, or neither, which is an empty map.
 *
 * Reading both transports in one helper is deliberate: a test that asserts a key is *absent* should
 * not be able to pass because the key moved to the other transport.
 */
internal fun DataSpec.cmcdKeys(): Map<String, String> {
    // spec: CTA-5004 §3.2 — the four header groups, whose values concatenate into one key set.
    val fromHeaders = httpRequestHeaders
        .filterKeys { it.startsWith("CMCD-") }
        .values
    // spec: CTA-5004 §3.3 — or a single URL-encoded `CMCD` query parameter carrying all of them.
    val fromQuery = listOfNotNull(
        uri.takeIf { it.isHierarchical }?.getQueryParameter("CMCD"),
    )

    return (fromHeaders + fromQuery)
        .flatMap(::splitCmcdPairs)
        .associate { pair -> pair.substringBefore('=') to pair.substringAfter('=', "") }
}

/**
 * One CMCD header or parameter value, split into its `key=value` pairs.
 *
 * Not `split(",")`: a string-valued key is sent quoted and its value may itself contain a comma —
 * `nor` carries a URL — so the split has to ignore separators inside quotes. spec: CTA-5004 §3.1.
 */
internal fun splitCmcdPairs(value: String): List<String> {
    val pairs = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false

    value.forEach { character ->
        when {
            character == '"' -> {
                inQuotes = !inQuotes
                current.append(character)
            }

            character == ',' && !inQuotes -> {
                pairs += current.toString()
                current.clear()
            }

            else -> current.append(character)
        }
    }

    if (current.isNotEmpty()) pairs += current.toString()
    return pairs.filter(String::isNotBlank)
}
