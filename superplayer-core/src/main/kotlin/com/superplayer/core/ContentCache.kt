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

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * A content cache a consumer opened, in a directory they named and within a budget they passed, that
 * a player writes the media it fetches into and reads it back from.
 *
 * `superplayer-cache` is where one comes from; nothing in `superplayer-core` opens one, and a player
 * built without [SuperPlayer.Builder.setCache] has no cache at all (ADR-0010 rules 1 and 13). The
 * type is core's so that the builder can take it without naming a later phase, and its constructor is
 * `internal` so that only a friend of core can make one: a consumer has no reason to implement a
 * cache, and the half of it the transfer chain uses is Media3's `@UnstableApi` vocabulary, which
 * ADR-0001 rule 2 keeps out of public API (ADR-0010 rule 3).
 *
 * The key a cache stores under is the content's [MediaRequest.contentId] and the representation a
 * request names, never the URL, so the same rendition served from a second CDN host is one entry
 * (ADR-0010 rule 4). Content set through `setMediaItem` has no `contentId`, and its requests reach
 * the cache with none; such content is keyed by its URL.
 */
public abstract class ContentCache internal constructor(
    /** What the cache puts into the transfer chain's cache slot; see [CacheLayer]. */
    internal val layer: CacheLayer,
)

/**
 * The link a [ContentCache] contributes to `TransferChain`'s cache slot: below live-playlist
 * revalidation, above header refresh and the transport.
 *
 * Called once per player, with the chain beneath the slot. The returned factory must forward every
 * `addTransferListener` it is handed to [over]'s upstream, as every layer of the chain must, and a
 * source that answers a read from local storage must report `isNetwork = false`, which is what keeps
 * a hit out of the throughput estimate (`TransferChain`'s KDoc, ADR-0010 rule 4).
 *
 * What a cache key is built from is on each request: [ContentIdentity.of] reads the content's id off
 * a `DataSpec`, and the `DataSpec`'s own URI, position, length and key name the representation.
 */
internal fun interface CacheLayer {

    /** [upstream], with this cache answering the reads it can and writing the ones it cannot. */
    fun over(upstream: DataSource.Factory): DataSource.Factory
}

/**
 * The identity of the content a request belongs to, as carried by the requests themselves.
 *
 * A player's chain is built once and serves every item it plays, so no layer of it can know which
 * item a request is for — and a preload manager loads several items through one chain at once, so a
 * holder the facade updated on each `setMediaRequest` would be wrong the moment two items load
 * together. The identity therefore travels *with* the request: an item adopted from a
 * [MediaRequest] on a player with a cache carries one of these as its `MediaItem` tag, and the media
 * source built for that item stamps it onto every `DataSpec` it opens, as the spec's `customData`.
 * Every layer below the stamp already passes a `DataSpec` on whole — `withAdditionalHeaders` and
 * `buildUpon` both keep `customData` (checked against `media3-datasource` 1.11.0) — so the cache
 * slot reads what the item carried.
 *
 * A value type, because Media3 compares items by their tag: two adoptions of one request must build
 * equal items, which is what lets preloaded content be found again at adoption (ADR-0010 rule 7).
 */
internal data class ContentIdentity(val contentId: String) {

    companion object {

        /** The content id [dataSpec] was stamped with, or null for content with no identity. */
        fun of(dataSpec: DataSpec): String? = (dataSpec.customData as? ContentIdentity)?.contentId

        /** The identity [item] carries, or null for an item not adopted from a request on a cached player. */
        fun of(item: MediaItem): ContentIdentity? = item.localConfiguration?.tag as? ContentIdentity
    }
}

/**
 * This factory, with every request its sources open stamped with [identity].
 *
 * A request that already carries `customData` is passed on unchanged: that slot belongs to whoever
 * set it, and the content is then keyed by its URL, as content with no identity is. Nothing in the
 * HLS or DASH sources Media3 1.11.0 builds sets it.
 */
internal fun DataSource.Factory.stampedWith(identity: ContentIdentity): DataSource.Factory =
    DataSource.Factory { IdentityStampingDataSource(createDataSource(), identity) }

/** Stamps each opened request and forwards everything else, the transfer listener included. */
private class IdentityStampingDataSource(
    private val upstream: DataSource,
    private val identity: ContentIdentity,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long = upstream.open(
        if (dataSpec.customData == null) dataSpec.buildUpon().setCustomData(identity).build() else dataSpec,
    )

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    // A Java default method, which Kotlin delegation would not forward: written out by hand.
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}
