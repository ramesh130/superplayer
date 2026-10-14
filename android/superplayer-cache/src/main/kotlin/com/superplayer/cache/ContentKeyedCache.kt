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

import com.superplayer.core.ContentCache
import com.superplayer.core.MediaRequest
import java.io.File

/**
 * A content cache opened by [CachePolicy.contentKeyed]: media keyed by what it is rather than where
 * it was fetched from, in a directory the consumer named.
 *
 * Handed to `SuperPlayer.Builder.setCache`, it fills the transfer chain's cache slot, below
 * live-playlist revalidation and above the transport. What that means for a consumer:
 *
 * - **One entry per content and rendition, whatever the host.** A segment of a [MediaRequest] is
 *   stored under the request's `contentId` and the path of the segment's URI. Played again from a
 *   second CDN host — the same path under another host, with whatever query string that host signs
 *   it with — it is answered from here with no request at all. A different rendition has a different
 *   path and is its own entry; a different `contentId` never shares an entry, even at an identical
 *   URL (`PRD.md` F7, ADR-0010 rule 4). The path still counts: a host that serves the same rendition
 *   under a different path is a different entry. `ContentKeys` holds the rule, the reasons for each
 *   part, and that limit.
 * - **Content set through `setMediaItem` is keyed by its URL**, because it has no content identity
 *   to key by, and it never shares an entry with a [MediaRequest]'s.
 * - **Only media is cached by playing.** Segments, initialization segments and progressive files are;
 *   HLS playlists, DASH manifests and encryption keys never are, so a live stream is never played from a
 *   stored playlist and revalidation sees every playlist the origin sends. The one exception is content
 *   `superplayer-offline` downloaded into this cache: its manifests were stored by the download, it is
 *   pinned, and a player reads them from here, which is what lets it play with no network at all
 *   (ADR-0013 rule 8).
 * - **A hit is not a throughput sample.** Reads answered from here report themselves as local, so the
 *   bandwidth estimate an adaptive policy selects on is untouched by a warm replay.
 * - **Past [maxBytes], the least recently used goes first**, where playing an entry again counts as
 *   using it, and content that is [pin]ned never goes. The order is not configurable (ADR-0010 rule
 *   12): content that needs a different one belongs in a second cache, in a second directory.
 *
 * Released by [release], after every player using it has been released; a player still loading
 * through a released cache fails its loads.
 */
public class ContentKeyedCache internal constructor(
    /** The directory the consumer named, which holds everything this cache writes. */
    public val directory: File,
    /** The budget the consumer passed, in bytes of media. */
    public val maxBytes: Long,
    internal val storage: CacheStorage,
    private val keyedLayer: ContentKeyedCacheLayer = ContentKeyedCacheLayer(storage.cache, storage::isPinned),
) : ContentCache(keyedLayer, StorageDownloads(storage)) {

    /**
     * How many media requests have been answered at least partly from this cache since it was opened, by
     * every player loading through it. A reading for a screen that shows the cache working; a replay
     * that reaches the network for nothing counts every segment it read.
     */
    public val hitCount: Long
        get() = keyedLayer.hitCount

    /**
     * Pins [contentId]: nothing stored under it is evicted, whatever the budget, until it is [unpin]ned.
     *
     * The pinned region Phase 7's downloads will live in (ADR-0010 rule 12), and its whole contract:
     *
     * - **A pin names content, not bytes.** It covers every rendition and segment of that
     *   `MediaRequest.contentId` already stored and any stored later, so it may be set before anything is.
     * - **Pinning is not fetching.** Content that was never played or stored is not downloaded by being
     *   pinned; it is kept once it arrives.
     * - **Pinned bytes count against [maxBytes].** Room is made by evicting unpinned entries alone, so
     *   pinned content crowds out what streaming would otherwise keep. When pinned content by itself
     *   exceeds the budget it is kept whole, nothing unpinned is kept beside it, and the cache holds more
     *   than [maxBytes] by exactly that excess — rather than delete what was pinned.
     * - **A pin lasts as long as the directory.** It is stored in the cache's own index, so it holds
     *   across the process's death and a reopening of [directory] under any budget, and deleting the
     *   directory deletes it with everything else.
     * - **Only identified content can be pinned.** Content set through `setMediaItem` has no content id
     *   to name and stays evictable.
     *
     * Pinning an id already pinned does nothing. A write to the cache's database, so not for the main
     * thread; throws `IllegalStateException` once the cache is released.
     */
    public fun pin(contentId: String) {
        storage.pin(contentId)
    }

    /**
     * Removes the pin on [contentId], if there is one. Its entries are evictable again from the next
     * eviction pass, which the next write to the cache starts; unpinning evicts nothing by itself.
     */
    public fun unpin(contentId: String) {
        storage.unpin(contentId)
    }

    /** Whether [contentId] is pinned in this cache's directory, by this cache or an earlier one. */
    public fun isPinned(contentId: String): Boolean = storage.isPinned(contentId)

    /** Closes the cache's files and releases its directory for another cache to open. Idempotent. */
    public fun release() {
        storage.release()
    }

    /** Every key the cache holds an entry under. For this module's tests. */
    internal fun keys(): Set<String> = storage.cache.keys

    /** Every byte the cache holds, pinned or not. For this module's tests. */
    internal fun heldBytes(): Long = storage.cache.cacheSpace
}
