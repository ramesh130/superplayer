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
 * - **Only media is cached.** Segments, initialization segments and progressive files are; HLS
 *   playlists, DASH manifests and encryption keys never are, so a live stream is never played from a
 *   stored playlist and revalidation sees every playlist the origin sends.
 * - **A hit is not a throughput sample.** Reads answered from here report themselves as local, so the
 *   bandwidth estimate an adaptive policy selects on is untouched by a warm replay.
 *
 * Released by [release], after every player using it has been released; a player still loading
 * through a released cache fails its loads.
 */
public class ContentKeyedCache internal constructor(
    /** The directory the consumer named, which holds everything this cache writes. */
    public val directory: File,
    /** The budget the consumer passed, in bytes of media. */
    public val maxBytes: Long,
    private val storage: CacheStorage,
) : ContentCache(ContentKeyedCacheLayer(storage.cache)) {

    /** Closes the cache's files and releases its directory for another cache to open. Idempotent. */
    public fun release() {
        storage.release()
    }

    /** Every key the cache holds an entry under. For this module's tests. */
    internal fun keys(): Set<String> = storage.cache.keys
}
