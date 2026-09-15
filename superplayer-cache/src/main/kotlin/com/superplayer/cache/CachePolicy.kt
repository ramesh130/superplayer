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

import java.io.File

/**
 * Where a content cache comes from: opened by the consumer, in a directory they name, within a budget
 * they pass.
 *
 * ```kotlin
 * val cache = CachePolicy.contentKeyed(File(context.cacheDir, "media"), maxBytes = 512L * 1024 * 1024)
 * val player = SuperPlayer.Builder(context).setCache(cache).build()
 * // … and when nothing plays from it any more:
 * player.release()
 * cache.release()
 * ```
 *
 * Nothing in SuperPlayer opens a cache on its own, in `cacheDir` or anywhere else (ADR-0010 rule 1):
 * the directory is the consumer's decision, with the storage-permission and clean-up story that comes
 * with it, and the number of bytes is one they wrote down where a reviewer can see it.
 */
public object CachePolicy {

    /**
     * A cache keyed by content rather than by URL, writing into [directory] and holding at most
     * [maxBytes] of media.
     *
     * The same rendition of one `MediaRequest.contentId` is one entry whichever host served it
     * ([ContentKeyedCache] says exactly what a key is). Everything the cache writes — media and its
     * index — lands inside [directory], so deleting the directory deletes the cache (ADR-0010 rule 2).
     *
     * One directory is one cache: opening a second over a directory a cache that has not been
     * released already holds throws `IllegalStateException`, which is Media3's lock on the folder and
     * what stops two indexes corrupting one another. Hold the returned cache for as long as players
     * use it, share it between them, and [ContentKeyedCache.release] it after the last is released.
     *
     * Past the budget the least recently used entries are evicted.
     */
    @JvmStatic
    public fun contentKeyed(directory: File, maxBytes: Long): ContentKeyedCache {
        require(maxBytes > 0) { "A cache budget is a positive number of bytes, was $maxBytes" }
        return ContentKeyedCache(directory, maxBytes, CacheStorage(directory, maxBytes))
    }
}
