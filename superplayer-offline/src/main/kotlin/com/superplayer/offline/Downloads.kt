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

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import com.superplayer.core.CacheDownloads
import com.superplayer.core.ContentCache
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.MediaRequest
import com.superplayer.core.TransferChain
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * A download store: content fetched ahead of time into the `ContentCache` the consumer opened, so that a
 * player built over that cache plays it with no network at all.
 *
 * Opened with [Builder] over a cache from `superplayer-cache`, one store per cache directory per process,
 * and held for as long as the cache is (ADR-0013 rule 2). What a consumer can rely on:
 *
 * - **A download is the content a player plays.** [enqueue] takes the same `MediaRequest` a player adopts,
 *   and every byte is stored under the key that request's player reads, so the id a download is stored
 *   under and the id it plays under are one value by construction (rule 5). The manifests are stored
 *   with the media, and a player serves them from disk for downloaded content only (rule 8).
 * - **Nothing goes anywhere else.** The progress, the index and the pin live in the cache's own
 *   database, inside the directory the consumer named; this store opens no directory, no database and no
 *   cache of its own, so deleting the directory deletes the downloads with everything else.
 * - **A download is pinned** before its first request, and until [remove] has deleted its bytes, so
 *   filling the cache past its budget with other content evicts none of it (rule 7). Pinned bytes count
 *   against the budget, as `ContentKeyedCache.pin` says.
 * - **Everything happens on the thread the store was built on**, which must have a `Looper` — the main
 *   thread, normally. Listeners are called there too.
 *
 * **Live content is not yet refused** at enqueue, as ADR-0013 rule 8 requires: telling live from on-demand
 * needs the manifest, which no ticket after this one has yet been cut to fetch before enqueueing. Not yet
 * here either, each with its ticket: the rendition and languages a download takes (#241 — today it is
 * what Media3's downloader fetches by default, every rendition the manifest lists); resuming across
 * process death and stopping rather than failing on a lost network (#242); the conditions downloads run
 * under and the `WorkManager` scheduling behind them (#243 — today the store runs whenever the device has
 * a network, as Media3's own default requirement says); a full disk (#244); protected content (#245);
 * and the service a download outlives its screen in (#246).
 *
 * Released by [release], before the cache it writes into.
 */
public class Downloads internal constructor(
    context: Context,
    private val cache: CacheDownloads,
    environment: DownloadEnvironment?,
) {

    /**
     * Opens a store over [cache], which must be one `superplayer-cache` opened: the store writes through
     * its download half, and a cache without one is refused.
     */
    public class Builder(private val context: Context, private val cache: ContentCache) {

        private var environment: DownloadEnvironment? = null

        /**
         * Loads over [environment] rather than the device's network: `superplayer-testkit`'s transport and
         * loading thread, for this module's own tests. Internal, so no consumer can reach it.
         */
        internal fun setEnvironment(environment: DownloadEnvironment): Builder = apply { this.environment = environment }

        /** Opens the store. On a thread with a `Looper`, which every later call must be made on. */
        public fun build(): Downloads {
            val downloads = requireNotNull(cache.downloads) {
                "This cache cannot be downloaded into: open one with superplayer-cache's CachePolicy.contentKeyed"
            }
            return Downloads(context.applicationContext, downloads, environment)
        }
    }

    private val handler = Handler(checkNotNull(Looper.myLooper()) { "A download store is built on a thread with a Looper" })

    private val listeners = CopyOnWriteArrayList<DownloadsListener>()

    /** The last item listeners were told about per content id, so no equal item is reported twice. */
    private val reported = HashMap<String, DownloadItem>()

    // Built once: every download's writer is over the one chain (ADR-0013 rule 6).
    private val upstream: DataSource.Factory = TransferChain.downloadChain(context, environment)

    // Media3's default runs a segment load on the downloading thread itself.
    private val loadExecutor: Executor = environment?.loadExecutor ?: Executor(Runnable::run)

    private val manager = DownloadManager(context, cache.downloadIndex(), PinningDownloaderFactory()).apply {
        addListener(ManagerEvents())
        // A manager is built paused, for Media3's `DownloadService` to resume; this store has no service
        // yet (#246), so it resumes its own.
        resumeDownloads()
    }

    private var released = false

    /**
     * Downloads [request]'s content: its first source, which is the one a player opens. A request already
     * downloaded or downloading under the same `contentId` is downloaded again over what is held, which
     * fetches only what is missing.
     */
    public fun enqueue(request: MediaRequest) {
        checkUsable()
        manager.addDownload(DownloadRequest.Builder(request.contentId, request.sources.first()).build())
    }

    /**
     * The download of [contentId], or null for content this store holds no download of. A download that
     * is not running is read from the cache's database, one row, on the calling thread.
     */
    public fun download(contentId: String): DownloadItem? {
        checkUsable()
        val download = manager.currentDownloads.firstOrNull { it.request.id == contentId }
            ?: manager.downloadIndex.getDownload(contentId)
        return download?.toItem()
    }

    /** Every download this store holds, in the order they were enqueued. Reads the cache's database on the calling thread. */
    public fun downloads(): List<DownloadItem> {
        checkUsable()
        val current = manager.currentDownloads.associateBy { it.request.id }
        val all = mutableListOf<Download>()
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) all += current[cursor.download.request.id] ?: cursor.download
        }
        return all.sortedBy { it.startTimeMs }.map { it.toItem() }
    }

    /**
     * Removes the download of [contentId]: its bytes are deleted, then its pin, and then the store
     * forgets it and [DownloadsListener.onDownloadRemoved] says so. Content with no download is ignored.
     */
    public fun remove(contentId: String) {
        checkUsable()
        manager.removeDownload(contentId)
    }

    /** Tells [listener] about every change from now on. */
    public fun addListener(listener: DownloadsListener) {
        listeners += listener
    }

    /** Stops telling [listener] about changes. */
    public fun removeListener(listener: DownloadsListener) {
        listeners -= listener
    }

    /**
     * Stops every download where it is, keeping what each has on disk, and lets go of the store's threads.
     * The cache is not released: it is the consumer's, and is released after this. Idempotent.
     */
    public fun release() {
        if (released) return
        released = true
        handler.removeCallbacksAndMessages(null)
        listeners.clear()
        manager.release()
    }

    private fun checkUsable() {
        check(!released) { "The download store has been released" }
        check(Looper.myLooper() == handler.looper) { "A download store is used on the thread it was built on" }
    }

    private fun report(item: DownloadItem) {
        if (released || reported[item.contentId] == item) return
        reported[item.contentId] = item
        listeners.forEach { it.onDownloadChanged(item) }
    }

    /** What the manager says, on the store's thread. */
    private inner class ManagerEvents : DownloadManager.Listener {

        override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
            report(download.toItem())
        }

        override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
            val contentId = download.request.id
            reported -= contentId
            if (!released) listeners.forEach { it.onDownloadRemoved(contentId) }
        }
    }

    /**
     * Media3's own downloader for each request, writing through the cache's download half keyed for that
     * request's content id, with the pin and the progress report around it.
     *
     * Per request because the key is per content id (ADR-0013 rule 5): the factory Media3 would otherwise
     * be given once keys every download alike.
     */
    private inner class PinningDownloaderFactory : DownloaderFactory {

        override fun createDownloader(request: DownloadRequest): Downloader = PinningDownloader(
            DefaultDownloaderFactory(cache.writerFor(request.id, upstream), loadExecutor).createDownloader(request),
            request.id,
        )
    }

    /**
     * A downloader that pins its content before the first request and unpins it once removal has deleted
     * the bytes, and reports progress as it is made.
     *
     * The pin is taken as the download begins rather than on [enqueue]'s thread: it is a database write,
     * and [enqueue] is called from a screen (ADR-0013 rule 7's addendum). What the rule pins at enqueue for — that no early
     * segment is evicted while a later one arrives — holds either way, because nothing is written before
     * [download] runs, and it is taken again on every resumption, which costs nothing when already held.
     */
    private inner class PinningDownloader(private val delegate: Downloader, private val contentId: String) : Downloader {

        override fun download(progressListener: Downloader.ProgressListener?) {
            cache.pin(contentId)
            val lastWholePercent = AtomicInteger(Int.MIN_VALUE)
            delegate.download { contentLength, bytesDownloaded, percentDownloaded ->
                progressListener?.onProgress(contentLength, bytesDownloaded, percentDownloaded)
                // A report per whole percent rather than per read, which Media3 calls this on: at most a
                // hundred and one per download, and every one of them a visible step on a progress bar.
                // The values travel with the post, so the order they were made in is the order reported.
                val wholePercent = percentDownloaded.toInt()
                if (lastWholePercent.getAndSet(wholePercent) != wholePercent) {
                    handler.post { reportProgress(contentId, bytesDownloaded, percentDownloaded) }
                }
            }
        }

        override fun cancel() {
            delegate.cancel()
        }

        override fun remove() {
            try {
                delegate.remove()
            } finally {
                // After the bytes are gone, so nothing unpinned is ever half-deleted by eviction instead.
                cache.unpin(contentId)
            }
        }
    }

    private fun reportProgress(contentId: String, bytesDownloaded: Long, percentDownloaded: Float) {
        // A download that has already moved on — completed, stopped, removed — reports that instead.
        val download = manager.currentDownloads.firstOrNull { it.request.id == contentId } ?: return
        if (download.state != Download.STATE_DOWNLOADING) return
        // A state change reads the download's progress live, and parallel segment loads post their own
        // readings, so a reading can arrive behind a newer one already reported: it is dropped rather than
        // reported as progress going backwards.
        val last = reported[contentId]
        if (last != null && last.bytesDownloaded > bytesDownloaded) return
        if (last?.percentDownloaded != null && last.percentDownloaded > percentDownloaded) return
        report(DownloadItem(contentId, DownloadState.DOWNLOADING, bytesDownloaded, percentOrNull(percentDownloaded)))
    }
}

private fun Download.toItem(): DownloadItem = DownloadItem(
    contentId = request.id,
    state = when (state) {
        Download.STATE_QUEUED -> DownloadState.QUEUED

        Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING

        Download.STATE_STOPPED -> DownloadState.STOPPED

        Download.STATE_COMPLETED -> DownloadState.COMPLETED

        Download.STATE_FAILED -> DownloadState.FAILED

        Download.STATE_REMOVING -> DownloadState.REMOVING

        // Removing its old bytes before downloading again: queued, from the viewer's side.
        Download.STATE_RESTARTING -> DownloadState.QUEUED

        else -> error("Media3 reported a download state this store does not know: $state")
    },
    bytesDownloaded = bytesDownloaded,
    percentDownloaded = percentOrNull(percentDownloaded),
)

private fun percentOrNull(percent: Float): Float? = percent.takeUnless { it == C.PERCENTAGE_UNSET.toFloat() }
