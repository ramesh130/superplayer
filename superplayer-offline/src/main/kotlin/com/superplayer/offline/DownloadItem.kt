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

package com.superplayer.offline

/**
 * One download as a store answers it: the content it is for, where it has got to, and how much of it
 * is on disk. A value — a store hands out a new one on every change rather than updating one in place —
 * so two readings can be compared to see whether anything moved.
 */
public class DownloadItem internal constructor(
    /** The `MediaRequest.contentId` the download was enqueued under, and the id a player plays it by. */
    public val contentId: String,
    /** Where the download has got to. */
    public val state: DownloadState,
    /** Bytes of the content written to the cache so far. */
    public val bytesDownloaded: Long,
    /**
     * How much of the content is on disk, from 0 to 100, or null while that is not yet known — before
     * the manifest has said how much there is to fetch. For segmented content with no declared lengths
     * it is the share of segments fetched rather than of bytes, which is Media3's own measure.
     */
    public val percentDownloaded: Float?,
) {

    override fun equals(other: Any?): Boolean = other is DownloadItem &&
        contentId == other.contentId &&
        state == other.state &&
        bytesDownloaded == other.bytesDownloaded &&
        percentDownloaded == other.percentDownloaded

    override fun hashCode(): Int = listOf(contentId, state, bytesDownloaded, percentDownloaded).hashCode()

    override fun toString(): String = "DownloadItem($contentId, $state, $bytesDownloaded bytes, $percentDownloaded%)"
}

/**
 * Where a download has got to.
 *
 * What holds a [STOPPED] item and why a [FAILED] one failed are carried beside the state as the tickets
 * that can produce them arrive (#242, #243, #244); this ticket's store neither stops nor classifies.
 */
public enum class DownloadState {

    /** Enqueued and waiting its turn, or for the conditions it downloads under. */
    QUEUED,

    /** Fetching now. */
    DOWNLOADING,

    /** Held, keeping what it has, until what holds it lets go. */
    STOPPED,

    /** Everything is on disk, and a player built over the cache plays it with no network. */
    COMPLETED,

    /** Ended without completing. */
    FAILED,

    /** Being removed: its bytes are being deleted, after which the store forgets it. */
    REMOVING,
}

/**
 * Told about downloads as they change, on the thread the store was built on.
 */
public interface DownloadsListener {

    /**
     * [item] changed: its state, or its progress by at least a whole percent. Consecutive calls for one
     * content id never carry an equal item, and a download's progress is reported in the order it was
     * made.
     */
    public fun onDownloadChanged(item: DownloadItem)

    /** The download of [contentId] was removed: its bytes are deleted and its pin is gone. */
    public fun onDownloadRemoved(contentId: String)
}
