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
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.util.ReleasableExecutor
import com.superplayer.core.CacheDownloads
import com.superplayer.core.ContentCache
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.DownloadResilienceExtension
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlaybackResilience
import com.superplayer.core.StaticProfilePolicy
import com.superplayer.core.SuperPlayerError
import com.superplayer.core.TransferChain
import com.superplayer.core.currentNetworkTransportOf
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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
 * - **A download is what the viewer will watch, not the whole ladder.** One video rendition, the highest
 *   under the policy's `DownloadSelectionPolicy` that the device's decoders can play; the
 *   audio languages and subtitles [enqueue] was told; and nothing else the manifest lists (rule 12). A
 *   player of the download is narrowed to the same tracks, so it plays what is on disk.
 * - **A download survives its process.** A store opened again over the same directory finds every download
 *   the last one held and carries on with the unfinished ones, fetching only what the cache does not already
 *   hold, with their progress continuing from those bytes (ADR-0013 rule 9).
 * - **A lost network stops a download rather than failing it**, where the store was built with a resilience
 *   ([Builder.setResilience]): the item is [DownloadState.STOPPED] for [DownloadStopReason.NETWORK_LOST],
 *   keeps its progress, and resumes on its own. A failure a later attempt cannot help — a segment the origin
 *   has lost — still fails, named on [DownloadItem.failure].
 *
 * **Live content is not yet refused** at enqueue, as ADR-0013 rule 8 requires (#251). Not yet
 * here either, each with its ticket: the conditions downloads run
 * under and the `WorkManager` scheduling behind them (#243 — today the store runs whenever the device has
 * a network, as Media3's own default requirement says); a full disk (#244); protected content (#245);
 * and the service a download outlives its screen in (#246). Nor rule 14's retry budgets and token refresh for
 * a download, which have no ticket yet.
 *
 * Released by [release], before the cache it writes into.
 */
public class Downloads internal constructor(
    private val context: Context,
    private val cache: CacheDownloads,
    private val environment: DownloadEnvironment?,
    private val policy: PlaybackPolicy,
    private val resilience: DownloadResilienceExtension?,
) {

    /**
     * Opens a store over [cache], which must be one `superplayer-cache` opened: the store writes through
     * its download half, and a cache without one is refused.
     */
    public class Builder(private val context: Context, private val cache: ContentCache) {

        private var environment: DownloadEnvironment? = null

        private var profile: PlaybackProfile = PlaybackProfile.VIDEO_ON_DEMAND

        private var policy: PlaybackPolicy? = null

        private var resilience: PlaybackResilience? = null

        /**
         * The kind of playback the downloads are for, which decides the rendition each one takes: its
         * `PlaybackDecision.download` ceiling. [PlaybackProfile.VIDEO_ON_DEMAND] unless set.
         */
        public fun setProfile(profile: PlaybackProfile): Builder = apply { this.profile = profile }

        /**
         * The policy that decides the rendition each download takes, in place of [setProfile]'s static one:
         * its `PlaybackDecision.download`, consulted once per item as it is enqueued (ADR-0013 rule 12). The
         * same policy a player of this content is built with, normally.
         */
        public fun setPolicy(policy: PlaybackPolicy): Builder = apply { this.policy = policy }

        /**
         * What tells a download whose network went away from one that failed, and how long a stopped one waits
         * before trying again: `superplayer-resilience`'s `Resilience.standard()`, normally the one this
         * content's players are built with (ADR-0013 rule 14). Without one, a download that meets any failure
         * retries as Media3's download manager does and then fails, unnamed. Not yet spent on a download: its
         * `RetryPolicy` budgets, and its `HeaderProvider`'s repair of a refused 401 or 403 — a failure that is
         * not a lost network retries as Media3's manager does, with or without one.
         */
        public fun setResilience(resilience: PlaybackResilience): Builder = apply { this.resilience = resilience }

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
            return Downloads(
                context.applicationContext,
                downloads,
                environment,
                policy ?: StaticProfilePolicy(profile),
                // A resilience that is not the module's asks nothing, as it fills no slot on a player.
                resilience as? DownloadResilienceExtension,
            )
        }
    }

    private val handler = Handler(checkNotNull(Looper.myLooper()) { "A download store is built on a thread with a Looper" })

    private val listeners = CopyOnWriteArrayList<DownloadsListener>()

    /** The last item listeners were told about per content id, so no equal item is reported twice. */
    private val reported = HashMap<String, DownloadItem>()

    // Built once: every download's writer is over the one chain (ADR-0013 rule 6).
    private val upstream: DataSource.Factory = TransferChain.downloadChain(context, environment)

    private val renderers: RenderersFactory = environment?.renderersFactory ?: DefaultRenderersFactory(context)

    // Media3's default runs a segment load on the downloading thread itself.
    private val loadExecutor: Executor = environment?.loadExecutor ?: Executor(Runnable::run)

    private val manager = DownloadManager(context, cache.downloadIndex(), PinningDownloaderFactory()).apply {
        addListener(ManagerEvents())
        // A manager is built paused, for Media3's `DownloadService` to resume; this store has no service
        // yet (#246), so it resumes its own.
        resumeDownloads()
    }

    /** What each failed download failed with, while this store is open, by content id. */
    private val failures = HashMap<String, SuperPlayerError>()

    /** Downloads stopped for a lost network, with the attempt to resume each is waiting for, by content id. */
    private val resumptions = HashMap<String, Resumption>()

    init {
        // A download a dead process stopped for its network is tried again at once by this one: the wait it
        // was serving died with that process, and a new process is as good a moment to look as any.
        manager.downloadIndex.getDownloads(Download.STATE_STOPPED).use { cursor ->
            val lost = mutableListOf<String>()
            while (cursor.moveToNext()) {
                if (cursor.download.stopReason == STOP_REASON_NETWORK_LOST) lost += cursor.download.request.id
            }
            lost.forEach { manager.setStopReason(it, Download.STOP_REASON_NONE) }
        }
    }

    /** Downloads whose manifest is being read to choose their tracks, before the manager holds them, by content id. */
    private val selecting = LinkedHashMap<String, DownloadHelper>()

    /** Downloads whose tracks could not be chosen, held until removed or enqueued again, by content id. */
    private val unselectable = LinkedHashMap<String, DownloadItem>()

    private var released = false

    /**
     * Downloads [request]'s content: its first source, which is the one a player opens.
     *
     * What is downloaded is chosen from the manifest first (ADR-0013 rule 12): one video rendition, under
     * the profile's ceiling; the audio in each of [audioLanguages] the content carries, or the audio a player
     * would choose where it carries none of them; and the subtitles in each of [subtitleLanguages] it
     * carries. Languages are BCP 47 tags, such as `en` or `pt-BR`; one the content does not carry is
     * skipped. The item is [DownloadState.QUEUED] while the manifest is read, and [DownloadState.FAILED]
     * if it cannot be.
     *
     * A request already downloaded or downloading under the same `contentId` is chosen and downloaded again
     * over what is held, which fetches only what is missing.
     */
    @JvmOverloads
    public fun enqueue(
        request: MediaRequest,
        audioLanguages: List<String> = emptyList(),
        subtitleLanguages: List<String> = emptyList(),
    ) {
        checkUsable()
        val contentId = request.contentId
        selecting.remove(contentId)?.release()
        unselectable -= contentId
        // Consulted once, now, with what is observed now: bytes written at one rendition are not chosen again.
        val decision = policy.decide(PlaybackConditions(transport = currentNetworkTransportOf(context)))
        val selection = DownloadSelection(decision.download, audioLanguages.toList(), subtitleLanguages.toList())
        val helper = DownloadHelper.Factory()
            // The manifest is read over the one chain, as the download itself is (rule 6).
            .setDataSourceFactory(upstream)
            // The device's own renderers, whose decoders are the device's refusal (ADR-0009 rule 2).
            .setRenderersFactory(renderers)
            .setTrackSelectionParameters(selection.parameters)
            // Media3's own loading thread, which a release can cancel, except under a harness that owns it.
            .apply { environment?.let { owned -> setLoadExecutor { ReleasableExecutor.from(owned.loadExecutor) {} } } }
            .create(MediaItem.fromUri(request.sources.first()))
        selecting[contentId] = helper
        // Content the manager already holds keeps reporting what it holds, so a download enqueued again is
        // never seen to go backwards; what is new is queued while its manifest is read.
        if (manager.currentDownloads.none { it.request.id == contentId } && manager.downloadIndex.getDownload(contentId) == null) {
            report(queued(contentId))
        }
        helper.prepare(
            object : DownloadHelper.Callback {
                override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                    if (released || selecting[contentId] !== helper) return
                    selecting -= contentId
                    selection.applyTo(helper)
                    val chosen = helper.getDownloadRequest(contentId, null)
                    helper.release()
                    manager.addDownload(chosen)
                }

                override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                    if (released || selecting[contentId] !== helper) return
                    selecting -= contentId
                    helper.release()
                    val failed = DownloadItem(contentId, DownloadState.FAILED, bytesDownloaded = 0, percentDownloaded = null)
                    unselectable[contentId] = failed
                    report(failed)
                }
            },
        )
    }

    /**
     * The download of [contentId], or null for content this store holds no download of. A download that
     * is not running is read from the cache's database, one row, on the calling thread.
     */
    public fun download(contentId: String): DownloadItem? {
        checkUsable()
        val download = manager.currentDownloads.firstOrNull { it.request.id == contentId }
            ?: manager.downloadIndex.getDownload(contentId)
        return download?.toItem() ?: beforeTheManager(contentId)
    }

    /** Every download this store holds, in the order they were enqueued. Reads the cache's database on the calling thread. */
    public fun downloads(): List<DownloadItem> {
        checkUsable()
        val current = manager.currentDownloads.associateBy { it.request.id }
        val all = mutableListOf<Download>()
        manager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) all += current[cursor.download.request.id] ?: cursor.download
        }
        val held = all.sortedBy { it.startTimeMs }.map { it.toItem() }
        val heldIds = held.mapTo(HashSet()) { it.contentId }
        return held + (selecting.keys + unselectable.keys).filter { it !in heldIds }.mapNotNull(::beforeTheManager)
    }

    /**
     * Removes the download of [contentId]: its bytes are deleted, then its pin, and then the store
     * forgets it and [DownloadsListener.onDownloadRemoved] says so. Content with no download is ignored.
     */
    public fun remove(contentId: String) {
        checkUsable()
        val choosing = selecting.remove(contentId)
        choosing?.release()
        val failed = unselectable.remove(contentId)
        failures -= contentId
        forgetResumption(contentId)
        // Nothing reached the manager, so nothing was written and nothing is pinned: forgetting it is the removal.
        if ((choosing != null || failed != null) && manager.currentDownloads.none { it.request.id == contentId }) {
            reported -= contentId
            listeners.forEach { it.onDownloadRemoved(contentId) }
        }
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
        selecting.values.forEach(DownloadHelper::release)
        selecting.clear()
        manager.release()
    }

    private fun checkUsable() {
        check(!released) { "The download store has been released" }
        check(Looper.myLooper() == handler.looper) { "A download store is used on the thread it was built on" }
    }

    /** A download the manager does not hold yet, or never will: its tracks being chosen, or failing to be. */
    private fun beforeTheManager(contentId: String): DownloadItem? = unselectable[contentId]
        ?: if (contentId in selecting) queued(contentId) else null

    private fun queued(contentId: String) = DownloadItem(contentId, DownloadState.QUEUED, bytesDownloaded = 0, percentDownloaded = null)

    private fun report(item: DownloadItem) {
        if (released || reported[item.contentId] == item) return
        reported[item.contentId] = item
        listeners.forEach { it.onDownloadChanged(item) }
    }

    /**
     * Stops [contentId], whose loader found the network gone, and waits to resume it. On the store's thread.
     *
     * Media3's own answer to a failed load is a handful of retries a few seconds apart and then a failed
     * download, which is right for an origin that is wrong and wrong for a network that is away for a
     * minute: the stop keeps the item and its bytes, and the wait widens with each attempt that finds the
     * network still gone (`DownloadResilienceExtension.waitBeforeResumingMs`).
     */
    private fun stopForLostNetwork(contentId: String) {
        val resilience = resilience ?: return
        // Unconditionally, whatever this thread last heard of the item: its loader waits for this stop, and a
        // released store or a removed item has already cancelled it.
        if (released) return
        val attempt = (forgetResumption(contentId)?.attempt ?: 0) + 1
        manager.setStopReason(contentId, STOP_REASON_NETWORK_LOST)
        val resumption = Resumption(contentId, attempt)
        resumptions[contentId] = resumption
        handler.postDelayed(resumption, resilience.waitBeforeResumingMs(attempt))
    }

    /** Stops waiting to resume [contentId], and answers the wait that was pending, if one was. */
    private fun forgetResumption(contentId: String): Resumption? = resumptions.remove(contentId)?.also { handler.removeCallbacks(it) }

    /** The [attempt]th try at resuming a download stopped for its network. */
    private inner class Resumption(private val contentId: String, val attempt: Int) : Runnable {
        override fun run() {
            // Kept in the map while the attempt runs, so a network still gone widens the next wait.
            if (released || resumptions[contentId] !== this) return
            manager.setStopReason(contentId, Download.STOP_REASON_NONE)
        }
    }

    /** What the manager says, on the store's thread. */
    private inner class ManagerEvents : DownloadManager.Listener {

        override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
            val contentId = download.request.id
            when (download.state) {
                Download.STATE_FAILED -> {
                    forgetResumption(contentId)
                    if (resilience != null && finalException != null) failures[contentId] = resilience.failureOf(finalException)
                }

                Download.STATE_COMPLETED -> forgetResumption(contentId)

                // Queued again by an enqueue, or by a resumption: what it failed with is no longer what it is.
                else -> failures -= contentId
            }
            // Downloading is announced by the downloader's first progress report rather than by the change of
            // state. The state's progress is the index's, which Media3 writes on a timer, so in a process that
            // resumed a download it can be zero while the cache holds most of it; the downloader counts what
            // the cache holds before it fetches anything, so its first report continues from those bytes.
            if (download.state == Download.STATE_DOWNLOADING) return
            report(download.toItem())
        }

        override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
            val contentId = download.request.id
            failures -= contentId
            forgetResumption(contentId)
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

        /** Released once Media3 cancels this download, which a stop for a lost network waits for. */
        private val canceled = CountDownLatch(1)

        override fun download(progressListener: Downloader.ProgressListener?) {
            cache.pin(contentId)
            try {
                downloadReporting(progressListener)
            } catch (e: IOException) {
                if (resilience?.isNetworkLoss(e) != true || canceled.count == 0L) throw e
                // Held here until the stop cancels this download, rather than thrown: a thrown failure is one
                // Media3 counts toward failing the item, and the stop is what keeps it from failing. Returning
                // once canceled is a task Media3 forgets rather than finishes. Released by a removal or the
                // store's release as well, since both cancel. Media3 interrupts a thread it cancels, which ends
                // this wait by throwing, and a cancelled task's exception is one Media3 ignores.
                handler.post { stopForLostNetwork(contentId) }
                canceled.await()
            }
        }

        private fun downloadReporting(progressListener: Downloader.ProgressListener?) {
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
            canceled.countDown()
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
        // A downloader reports only bytes it has just cached, so a report is the network back: the next loss
        // starts from the shortest wait.
        forgetResumption(contentId)
        report(DownloadItem(contentId, DownloadState.DOWNLOADING, bytesDownloaded, percentOrNull(percentDownloaded)))
    }

    /** A download as the store reports it: Media3's, with what this store knows about why it stopped or failed. */
    private fun Download.toItem(): DownloadItem = DownloadItem(
        contentId = request.id,
        state = stateOf(this),
        bytesDownloaded = bytesDownloaded,
        percentDownloaded = percentOrNull(percentDownloaded),
        stopReason = if (state == Download.STATE_STOPPED && stopReason == STOP_REASON_NETWORK_LOST) DownloadStopReason.NETWORK_LOST else null,
        failure = if (state == Download.STATE_FAILED) failures[request.id] else null,
    )

    private companion object {

        /**
         * Media3's stop reason for a download stopped for a lost network, written into the index with it.
         * Any value but `STOP_REASON_NONE` stops a download; one is this store's first, and the conditions
         * of #243 take the next ones.
         */
        const val STOP_REASON_NETWORK_LOST = 1
    }
}

private fun stateOf(download: Download): DownloadState = when (download.state) {
    Download.STATE_QUEUED -> DownloadState.QUEUED

    Download.STATE_DOWNLOADING -> DownloadState.DOWNLOADING

    Download.STATE_STOPPED -> DownloadState.STOPPED

    Download.STATE_COMPLETED -> DownloadState.COMPLETED

    Download.STATE_FAILED -> DownloadState.FAILED

    Download.STATE_REMOVING -> DownloadState.REMOVING

    // Removing its old bytes before downloading again: queued, from the viewer's side.
    Download.STATE_RESTARTING -> DownloadState.QUEUED

    else -> error("Media3 reported a download state this store does not know: ${download.state}")
}

private fun percentOrNull(percent: Float): Float? = percent.takeUnless { it == C.PERCENTAGE_UNSET.toFloat() }
