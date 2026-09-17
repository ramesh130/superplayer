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
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.WritableDownloadIndex

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
    /** What a download store writes into this cache through, or null for a cache nothing downloads into; see [CacheDownloads]. */
    internal val downloads: CacheDownloads? = null,
)

/**
 * The download half of a [ContentCache]: what `superplayer-offline` is built from rather than a slot it
 * fills (ADR-0013 rule 4). `superplayer-cache` fills it; a store refuses a cache that has none.
 *
 * Every member is Media3's `@UnstableApi` vocabulary or a write to the cache's own index, which is why
 * the half is internal and reached only by a friend of core.
 */
internal interface CacheDownloads {

    /**
     * Media3's download index, as a table of this cache's own index database, inside the consumer's
     * directory (ADR-0013 rule 5). Nothing is created until the index is first used, so a cache no
     * store was opened over has no such table (rule 15).
     */
    fun downloadIndex(): WritableDownloadIndex

    /**
     * What a download of [contentId] writes through: this cache over [upstream], every request keyed
     * as a player adopting a `MediaRequest` with that id would key it, so a downloaded segment is the
     * entry that player reads (rule 5). The id is bound here rather than stamped per request, because a
     * downloader opens requests no media source built.
     */
    fun writerFor(contentId: String, upstream: DataSource.Factory): CacheDataSource.Factory

    /** Pins [contentId], as `ContentKeyedCache.pin` does. A database write, so never on the main thread. */
    fun pin(contentId: String)

    /** Removes the pin on [contentId], as `ContentKeyedCache.unpin` does. A database write too. */
    fun unpin(contentId: String)
}

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
 * a `DataSpec`, and the `DataSpec` itself says which representation and which bytes of it are being
 * loaded — its key when the source set one, its URI's path, its position and length. A key is the id
 * plus the part of that which names the rendition, never the URI whole: the host is exactly what
 * must not split one piece of content into two entries (ADR-0010 rule 4). A request with no id is
 * content set through `setMediaItem`, and only such a request is keyed by its URL.
 *
 * Whether a request may be cached at all is on it too: [LoadKind.of] says whether it loads media, a
 * manifest, or something the chain could not classify. Media is a cache's to answer. A manifest
 * describes where media is *now* — a live playlist is stale the moment it is stored, and a VOD one
 * names its own host's segments — so it is passed upstream, where live-playlist revalidation above the
 * slot sees what the origin said, with one exception: a manifest a download stored for pinned content,
 * which the cache answers, because that content is meant to play with no network (ADR-0013 rule 8).
 *
 * Every media source factory setting is replayed per item by a recorded list in `TransferChain`, so a
 * setter Media3 adds to `MediaSource.Factory` later has to be added there, or it is dropped on a
 * player with a cache.
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
        fun of(dataSpec: DataSpec): String? = RequestStamp.of(dataSpec)?.identity?.contentId

        /** The identity [item] carries, or null for an item not adopted from a request on a cached player. */
        fun of(item: MediaItem): ContentIdentity? = item.localConfiguration?.tag as? ContentIdentity
    }
}

/**
 * What a request loads, as far as a cache is concerned.
 *
 * Only the media source knows: the HLS source asks its data source factory for a data source *per
 * data type* and the DASH source takes one factory for its manifest and another for its chunks, while
 * the `DataSpec` both open names a URI and nothing about what it is. So `TransferChain` builds each
 * item's source with a factory per kind and stamps the kind on, and the cache slot reads it here
 * rather than guessing from a URL's extension, which a manifest served from `/play?id=7` does not have.
 */
internal enum class LoadKind {

    /** Samples, or what decoding them needs: a segment, an initialization segment, a progressive file. */
    MEDIA,

    /** A description of where media is: an HLS playlist, a DASH MPD, a steering manifest, a time sync. */
    MANIFEST,

    /**
     * An entitlement: the round trip a DRM session makes to the licence server the app named, and the
     * provisioning round trip beneath it.
     *
     * The one kind no media source ever stamps, because no media source composes one. A licence
     * request is the player's own, addressed at a server a manifest did not name, and `TransferChain`
     * stamps it onto the transport it hands the DRM slot rather than onto an item's factory.
     *
     * It is a kind of its own rather than [UNCLASSIFIED] because the two slots that read the stamp
     * want opposite things of it: a cache must never store a device-bound credential under a content
     * key, and the header-refresh layer must repair one, since a licence request carries the app's
     * credential exactly as a segment request does (ADR-0012 rule 2, #205).
     */
    LICENCE,

    /**
     * Anything else — a full-segment AES key an `EXT-X-KEY` named, a packaging the chain does not
     * build by kind, an unstamped request.
     */
    UNCLASSIFIED,
    ;

    companion object {

        /** The kind [dataSpec] was stamped with; unstamped is [UNCLASSIFIED]. */
        fun of(dataSpec: DataSpec): LoadKind = RequestStamp.of(dataSpec)?.kind ?: UNCLASSIFIED
    }
}

/**
 * What a request carries into the cache slot: the content it belongs to, when it has an identity, and
 * what kind of load it is. Stored as the `DataSpec`'s `customData`; [ContentIdentity] says why the
 * request is where it travels. A value type, because Media3 compares a `DataSpec` by its fields.
 */
internal data class RequestStamp(val identity: ContentIdentity?, val kind: LoadKind) {

    companion object {

        /** The stamp on [dataSpec], or null for a request no stamping source opened. */
        fun of(dataSpec: DataSpec): RequestStamp? = dataSpec.customData as? RequestStamp
    }
}

/**
 * This factory, with every request its sources open stamped with [identity] — null for content set
 * through `setMediaItem` — and [kind].
 *
 * A request that already carries `customData` is passed on unchanged: that slot belongs to whoever
 * set it, and such a request is then [LoadKind.UNCLASSIFIED], which no cache answers. Nothing in the
 * HLS or DASH sources Media3 1.11.0 builds sets it.
 */
internal fun DataSource.Factory.stampedWith(identity: ContentIdentity?, kind: LoadKind): DataSource.Factory {
    val stamp = RequestStamp(identity, kind)
    return DataSource.Factory { RequestStampingDataSource(createDataSource(), stamp) }
}

/** Stamps each opened request and forwards everything else, the transfer listener included. */
private class RequestStampingDataSource(
    private val upstream: DataSource,
    private val stamp: RequestStamp,
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long = upstream.open(
        if (dataSpec.customData == null) dataSpec.buildUpon().setCustomData(stamp).build() else dataSpec,
    )

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    // A Java default method, which Kotlin delegation would not forward: written out by hand.
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}
