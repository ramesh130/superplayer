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

import androidx.media3.common.C
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Least-recently-used eviction within [maxBytes] that never removes a span [isPinned] claims.
 *
 * - **Use is a write or a read.** The evictor asks for span touches, so `SimpleCache` restamps a span
 *   each time a request reads it, and a replayed entry is as recent as a newly stored one.
 * - **Pinned bytes count against the budget**, and room is made from unpinned spans alone, oldest first
 *   (ADR-0010 rule 12; `ContentKeyedCache.pin` states the consumer's side). Finding the oldest unpinned
 *   span walks past every older pinned one, which is linear in pinned spans per removal: nothing at
 *   Phase 4's scale, and a split into two ordered sets when downloads make pins numerous.
 * - **Nothing is evicted while the cache opens.** `SimpleCache` reports each stored span as it loads it,
 *   in the order its files are listed rather than in the order they were used, so evicting then would
 *   remove whichever happened to load first. Waiting for [onCacheInitialized] evicts in order of use,
 *   which is what makes a directory reopened under a smaller budget keep its most recently used content.
 *
 * Media3's own `LeastRecentlyUsedCacheEvictor` is final and has no notion of a span it may not remove,
 * which is why this one exists rather than wrapping it.
 * ref: https://developer.android.com/reference/androidx/media3/datasource/cache/CacheEvictor
 *
 * Every callback arrives under the cache's own lock, which is what guards [spans].
 */
internal class PinAwareLruEvictor(
    private val maxBytes: Long,
    private val isPinned: (key: String) -> Boolean,
) : CacheEvictor {

    // Least recently used first. A tie in time falls back to Media3's own span order, so two distinct
    // spans touched in one millisecond are never one element.
    private val spans = TreeSet(compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it }))

    // Handed over by the first span the cache reports: `onCacheInitialized` carries no cache, and a cache
    // that reported no span has nothing to evict.
    private var cache: Cache? = null
    private var initialized = false

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() {
        initialized = true
        cache?.let { evict(it, requiredBytes = 0) }
    }

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, requiredBytes = length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        this.cache = cache
        spans += span
        if (initialized) evict(cache, requiredBytes = 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans -= span
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** Removes the least recently used unpinned span until [requiredBytes] more fit or none is left. */
    private fun evict(cache: Cache, requiredBytes: Long) {
        while (cache.cacheSpace + requiredBytes > maxBytes) {
            val victim = spans.firstOrNull { !isPinned(it.key) } ?: return
            cache.removeSpan(victim)
            // The cache reports the removal back through `onSpanRemoved`; dropped here as well, so a span
            // the cache no longer knows cannot be chosen again and hold this loop.
            spans -= victim
        }
    }
}
