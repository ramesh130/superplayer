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
import androidx.media3.common.StreamKey
import androidx.media3.database.VersionTable
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.WritableDownloadIndex
import com.superplayer.core.CacheDownloads

/**
 * A [ContentKeyedCache]'s download half: what `superplayer-offline` writes through (ADR-0013 rules 4
 * and 5). It opens nothing of its own — the index is a table of [storage]'s database, the writes go to
 * [storage]'s cache under [ContentKeys], and a pin is [storage]'s pin — so deleting the consumer's
 * directory deletes a download, its progress and its pin together.
 */
internal class StorageDownloads(private val storage: CacheStorage) : CacheDownloads {

    // Media3's index creates its table on first use rather than on construction, so building one here
    // costs a cache no store is opened over nothing (ADR-0013 rule 15).
    override fun downloadIndex(): WritableDownloadIndex = DefaultDownloadIndex(storage.index)

    override fun writerFor(contentId: String, upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(storage.cache)
            .setCacheKeyFactory(ContentKeys.boundTo(contentId))
            .setUpstreamDataSourceFactory(upstream)

    override fun pin(contentId: String) {
        storage.pin(contentId)
    }

    override fun unpin(contentId: String) {
        storage.unpin(contentId)
    }

    override fun downloadedTracks(contentId: String, uri: Uri): List<StreamKey> {
        // A download is pinned from before its first byte until its bytes are gone, so content that is
        // not pinned holds no download and the question costs no read at all.
        if (!storage.isPinned(contentId)) return emptyList()
        // Pinned by a consumer rather than by a store: reading through the index would create its table,
        // which a cache no store was opened over must not have (ADR-0013 rule 15). Media3's index names
        // its table under the empty name when it is given none, as `downloadIndex` gives it none.
        val readable = storage.index.readableDatabase
        if (VersionTable.getVersion(readable, VersionTable.FEATURE_OFFLINE, "") == VersionTable.VERSION_UNSET) return emptyList()
        val download = downloadIndex().getDownload(contentId) ?: return emptyList()
        // A download being removed is bytes on their way out, and one from another source describes
        // another manifest's tracks: neither narrows what this item plays.
        if (download.state == Download.STATE_REMOVING || download.request.uri != uri) return emptyList()
        return download.request.streamKeys
    }
}
