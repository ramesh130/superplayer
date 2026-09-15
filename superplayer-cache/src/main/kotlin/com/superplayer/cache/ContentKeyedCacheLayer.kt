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

package com.superplayer.cache

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import com.superplayer.core.CacheLayer
import com.superplayer.core.ContentIdentity
import com.superplayer.core.LoadKind

/**
 * What a [ContentKeyedCache] puts into the transfer chain's cache slot: a `CacheDataSource` keyed by
 * [ContentKeys], reached by media requests only.
 *
 * The hit is kept out of the throughput estimate by Media3 rather than by anything here: a
 * `CacheDataSource` answers a hit from a file source, which reports `isNetwork = false`, and both the
 * default meter and `superplayer-abr`'s oracle drop such a transfer (`TransferChain`'s KDoc,
 * ADR-0010 rule 4). What this layer must not do is break that — so it forwards the engine's transfer
 * listener to whichever source a request actually opens, and wraps nothing around the cache's own.
 *
 * ref: https://developer.android.com/reference/androidx/media3/datasource/cache/CacheDataSource
 */
internal class ContentKeyedCacheLayer(private val cache: Cache) : CacheLayer {

    override fun over(upstream: DataSource.Factory): DataSource.Factory {
        val cached = CacheDataSource.Factory()
            .setCache(cache)
            .setCacheKeyFactory(ContentKeys)
            .setUpstreamDataSourceFactory(upstream)
            // A cache that cannot be read is a cache to go around, not a playback failure: the request
            // is fetched upstream instead. Resilience's retries are Phase 5's, and a broken disk is not
            // a transfer to retry.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { MediaOnlyDataSource(cached, upstream) }
    }
}

/**
 * The key a media request is stored under.
 *
 * A request of identified content — one adopted from a `MediaRequest` — is keyed by its content id
 * and the *path* of its URI, and nothing else of the URI:
 *
 * - **Not the host**, because the host is what differs between two CDNs serving one piece of content,
 *   and a key that included it is the URL-keyed cache `PRD.md` F7 describes.
 * - **Not the query**, because a CDN's signed URL puts its token there and re-signs per request or per
 *   session; a key that included it would miss on every replay. The cost is stated rather than hidden:
 *   a packager that names the rendition *only* in the query would have its renditions share one key.
 *   Every HLS and DASH packaging this project plays names renditions in the path.
 *   ref: https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-creating-signed-url-canned-policy.html
 *   — a signed URL's `Expires`, `Signature` and `Key-Pair-Id` are query parameters appended to an
 *   unchanged path.
 * - **The path**, because that is where a rendition and a segment are named: `EXT-X-STREAM-INF` and
 *   `EXTINF` each point at a URI (// spec: RFC 8216 §4.3.4.2, §4.3.2.1), and a DASH
 *   `SegmentURL`/`SegmentTemplate` resolves against a `BaseURL` (// spec: ISO/IEC 23009-1 §5.6), so
 *   two renditions, and two segments of one, differ in path. A segment addressed as a byte range of a
 *   larger file (// spec: RFC 8216 §4.3.2.2) shares the file's key, and `CacheDataSource` stores the
 *   ranges as positions within that one entry.
 * - **Not the `DataSpec`'s own key**, which is Media3's URL-derived name for a DASH representation
 *   and would bring the host back.
 *
 * **The limit, stated.** The path is still part of the URL, so a key survives a change of host and of
 * query and nothing more. Two CDNs that serve one piece of content under *different paths* — a
 * customer prefix on one (`/customer42/vod/720p/seg3.m4s`) and none on the other
 * (`/vod/720p/seg3.m4s`) — are two entries, and the second play misses. Closing that would need a
 * rendition identity that does not come from the URL at all: the manifest's own names for a variant
 * and a segment, carried onto each request as the content id is. That is a larger change than F7's
 * common case needs — CDN failover and multi-CDN delivery mirror one origin's paths — and it is left
 * until content with diverging paths is a case someone has.
 *
 * The id is written with its length before it, so no id and path can be read as a different id and
 * path, and the two families of key carry different prefixes, so content set through `setMediaItem` —
 * keyed by Media3's own default, the URL — never shares a key with a request's (ADR-0010 rule 4). A
 * URI with no absolute path (an opaque one) keys by the whole URI string, which cannot be mistaken for
 * a path because it begins with a scheme rather than a slash.
 *
 * ref: RFC 3986 §3 for the parts of a URI named above.
 */
internal object ContentKeys : CacheKeyFactory {

    private const val BY_CONTENT = "content:"
    private const val BY_URL = "url:"

    override fun buildCacheKey(dataSpec: DataSpec): String {
        val contentId = ContentIdentity.of(dataSpec) ?: return BY_URL + CacheKeyFactory.DEFAULT.buildCacheKey(dataSpec)
        val path = dataSpec.uri.path?.takeIf { it.startsWith("/") }
        return "$BY_CONTENT${contentId.length}:$contentId${path ?: dataSpec.uri.toString()}"
    }

    /**
     * The content id a key of [buildCacheKey]'s was built from, read back through the length written
     * before it, or null for a key of content set through `setMediaItem`, which has none. How a pin,
     * which names content, finds the entries it covers.
     */
    fun contentIdOf(key: String): String? {
        if (!key.startsWith(BY_CONTENT)) return null
        val separator = key.indexOf(':', startIndex = BY_CONTENT.length)
        if (separator < 0) return null
        val length = key.substring(BY_CONTENT.length, separator).toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val start = separator + 1
        return if (length <= key.length - start) key.substring(start, start + length) else null
    }
}

/**
 * Sends a media request through the cache and every other request straight upstream.
 *
 * Decided per request, at `open`, from the kind `TransferChain` stamped on it: which source to open is
 * not known until then, so the engine's transfer listeners are held and handed to each source as it
 * is created. Every method is written out rather than delegated, because `getResponseHeaders` is a
 * Java default method Kotlin delegation would not forward (ADR-0003's trap).
 */
private class MediaOnlyDataSource(
    private val cached: DataSource.Factory,
    private val direct: DataSource.Factory,
) : DataSource {

    private val listeners = mutableListOf<TransferListener>()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        current?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val factory = if (LoadKind.of(dataSpec) == LoadKind.MEDIA) cached else direct
        val source = factory.createDataSource()
        listeners.forEach(source::addTransferListener)
        // Held before opening, so a failed open is still closed by the engine's `close`.
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(current) { "read before open" }.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = current ?: return
        current = null
        source.close()
    }
}
