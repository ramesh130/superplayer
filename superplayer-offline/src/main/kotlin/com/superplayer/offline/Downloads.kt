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
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
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
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.work.NetworkType
import com.superplayer.core.CacheDownloads
import com.superplayer.core.ContentCache
import com.superplayer.core.DecisionInForce
import com.superplayer.core.DownloadDrmExtension
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.DownloadResilienceExtension
import com.superplayer.core.HeaderRefreshLayer
import com.superplayer.core.LicenceStore
import com.superplayer.core.MediaRequest
import com.superplayer.core.OfflineLicenceExpiredException
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackDrm
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlaybackResilience
import com.superplayer.core.SecurityDowngradeRefusedException
import com.superplayer.core.StaticProfilePolicy
import com.superplayer.core.StorageFullException
import com.superplayer.core.SuperPlayerError
import com.superplayer.core.TransferChain
import com.superplayer.core.currentNetworkTransportOf
import com.superplayer.core.expiredLicenceFailure
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
 *   keeps its progress, and resumes on its own. A network is lost where no server answered at all.
 * - **Any other failure spends the policy's retry budget, and then fails**, where the store was built with a
 *   resilience (ADR-0013 rule 14): the same request is asked for again after a jittered wait on the store's
 *   thread, as many times as the policy's `RetryPolicy` allows — the manifest budget for a manifest and the
 *   segment budget for everything else, counted afresh wherever the download has got further — while the item
 *   stays [DownloadState.DOWNLOADING] and its progress never goes back. A failure retrying cannot help, or one
 *   that outlasts its budget, fails named on [DownloadItem.failure]; a refused 401 or 403 is first repaired by
 *   the resilience's `HeaderProvider` inside the transfer, spending nothing.
 * - **A download runs only while the network is unmetered, the battery is not low and storage is not low**
 *   (rules 10 and 11). While one does not hold, nothing is fetched, the manifest included, and every pending
 *   item is [DownloadState.STOPPED] naming it on [DownloadItem.stopReason]; a download it lapses under stops
 *   keeping its bytes, and carries on from them once it holds again. Battery and storage are not a
 *   consumer's to relax. The network is the viewer's: [meteredNetworksAllowed] accepts any network, and even
 *   then Data Saver holds downloads on a metered one.
 * - **Pending downloads are scheduled as persisted `WorkManager` work** under the same constraints, which
 *   outlives the process and a reboot. `androidx.work` comes with this module, and `WorkManager` initializes
 *   itself through App Startup unless the app configures it otherwise, which is the app's to decide and adds
 *   its own entries to the app's merged manifest. A store with nothing pending schedules nothing and
 *   registers no battery receiver.
 * - **A full disk fails the one item that met it**, on every store and at once, rather than being retried
 *   onto a disk that is still full or waited out as a lost network (ADR-0013 rule 9). Every other item carries
 *   on. The failed one keeps what it wrote, pinned, so an [enqueue] once there is room continues from those
 *   bytes; [DownloadItem.failure] names it `Storage.Full`, with a message key of its own, where the store was
 *   built with a resilience. The platform's reading of free space is what is measured, before each write.
 *
 * - **Protected content carries its licence**, where the store was built with [Builder.setDrm]. A download
 *   whose content declares protection acquires an offline licence into the licence store after its manifest
 *   is read and before its first media byte, and fails typed, having written and pinned nothing, where the
 *   licence is refused — on a store with a resilience, once the policy's licence budget is spent and a refused
 *   credential has had its repair, as for a player's licence. A player built with that licence plays the
 *   download with no network at all; what the licence allows is on [DownloadItem.licence] before any player is built, and nothing renews it. [remove]
 *   deletes the bytes at once and releases the licence at the server once the store's network requirement
 *   holds: until then the licence store reports it awaiting release and gives it to no player (ADR-0013 rule
 *   13).
 *
 * - **Live content is refused** (ADR-0013 rule 8): an HLS media playlist with no `EXT-X-ENDLIST` or a DASH MPD
 *   of `type="dynamic"` has no end to download and a manifest that moves. The refusal is read off the manifest
 *   read that chooses the tracks, over the same chain, and ends the item [DownloadState.FAILED] with
 *   [DownloadRefusal.LIVE_CONTENT] on [DownloadItem.refusal], on every store, having fetched no media and
 *   pinned nothing.
 *
 * - **A download outlives its screen in the app's service**, where the store was built with [Builder.setService]:
 *   the store starts that [DownloadsService] whenever a download can run, which holds the process in the
 *   foreground until none can, and the scheduled work starts it in a process with no store open, after a
 *   reboot say, so the store it opens resumes what was pending (ADR-0013 rules 3 and 11). Without one, a
 *   download runs only while something in the process keeps it alive.
 *
 * Not yet here: an item enqueued while a condition holds it keeps its request in memory until its manifest
 * can be read, so a process that dies first loses that enqueue.
 *
 * Released by [release], before the cache it writes into.
 */
public class Downloads internal constructor(
    private val context: Context,
    private val cache: CacheDownloads,
    private val environment: DownloadEnvironment?,
    private val policy: PlaybackPolicy,
    private val resilience: DownloadResilienceExtension?,
    drm: DownloadDrmExtension?,
    licenceStore: LicenceStore?,
    private val service: Class<out DownloadsService>?,
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

        private var drm: PlaybackDrm? = null

        private var licenceStore: LicenceStore? = null

        private var service: Class<out DownloadsService>? = null

        /**
         * The kind of playback the downloads are for, which decides the rendition each one takes — its
         * `PlaybackDecision.download` ceiling — and, on a store with a resilience, how often a failed request
         * is asked for again: its `PlaybackDecision.retry`. [PlaybackProfile.VIDEO_ON_DEMAND] unless set.
         */
        public fun setProfile(profile: PlaybackProfile): Builder = apply { this.profile = profile }

        /**
         * The policy that decides the rendition each download takes, in place of [setProfile]'s static one:
         * its `PlaybackDecision.download`, consulted once per item as it is enqueued (ADR-0013 rule 12), and
         * the budgets a failed request spends, its `PlaybackDecision.retry`, as the store last consulted it
         * (rule 14). The same policy a player of this content is built with, normally.
         */
        public fun setPolicy(policy: PlaybackPolicy): Builder = apply { this.policy = policy }

        /**
         * What tells a download whose network went away from one that failed, how long a stopped one waits
         * before trying again, how often a failed request — a licence request included — is asked for again
         * under the policy's budgets, and what repairs a refused credential, a licence server's included:
         * `superplayer-resilience`'s `Resilience.standard(headers)`, normally the one this content's players are built with (ADR-0013 rule 14). Without one, a download
         * that meets any failure retries as Media3's download manager does and then fails, unnamed.
         */
        public fun setResilience(resilience: PlaybackResilience): Builder = apply { this.resilience = resilience }

        /**
         * The protection a protected download acquires its offline licence under, and the store it keeps the
         * licence in: `superplayer-drm`'s `Drm.widevine(config)`, the one this content's players are built
         * with, and an `OfflineLicences.store(directory)` the consumer opened (ADR-0013 rule 13). Without it,
         * content that declares protection downloads with no licence, and nothing can play it offline.
         *
         * A player of a download plays with the licence the licence store holds for its content id, read
         * before the player is built: `Drm.widevine(config, licences.licenceFor(contentId))`.
         */
        public fun setDrm(drm: PlaybackDrm, licences: LicenceStore): Builder = apply {
            this.drm = drm
            this.licenceStore = licences
        }

        /**
         * The app's [DownloadsService] subclass, which this store starts whenever a download can run and the
         * scheduled work starts in a process with no store open (ADR-0013 rules 3 and 11). The service's
         * `onDownloads()` must answer this store, or the one a fresh process opens over the same directory.
         * Its manifest entry and permissions are the app's, as that class says. Without one, nothing is
         * started: a download runs while the process lives, and the schedule waits for a store to be opened.
         */
        public fun setService(service: Class<out DownloadsService>): Builder = apply { this.service = service }

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
                // A protection that is not the module's acquires nothing, as it fills no slot on a player.
                drm as? DownloadDrmExtension,
                licenceStore,
                service,
            )
        }
    }

    private val handler = Handler(checkNotNull(Looper.myLooper()) { "A download store is built on a thread with a Looper" })

    private val listeners = CopyOnWriteArrayList<DownloadsListener>()

    /** The last item listeners were told about per content id, so no equal item is reported twice. */
    private val reported = HashMap<String, DownloadItem>()

    // The store's one header-refresh layer, under its downloads and its licence exchanges alike (rule 14): the
    // credential it repairs is the store's, as a player's one layer serves its licence and its media.
    private val headerRefresh: HeaderRefreshLayer? = resilience?.downloadHeaderRefresh()

    // Built once: every download's writer is over the one chain (ADR-0013 rule 6), with that layer innermost.
    private val upstream: DataSource.Factory = TransferChain.downloadChain(context, environment, headerRefresh)

    private val renderers: RenderersFactory = environment?.renderersFactory ?: DefaultRenderersFactory(context)

    // Media3's default runs a segment load on the downloading thread itself.
    private val loadExecutor: Executor = environment?.loadExecutor ?: Executor(Runnable::run)

    /** The battery and Data Saver, which the manager's requirements cannot state; what they report reaches this thread. */
    private val conditions = DownloadConditions(context) { handler.post(::onConditionsChanged) }

    /**
     * The policy's decision, for its retry half, as the store's thread last consulted it: when the store opened,
     * and again as each item is enqueued. Read by a downloader on Media3's download thread, which is why it is
     * held rather than decided there: `PlaybackPolicy.decide` is called on the thread that drives it. Store-wide
     * rather than per item, because a store has one policy as a player does (ADR-0013 rule 14); what differs
     * between consultations is only what was observed. Before the manager, whose downloaders read it.
     */
    @Volatile
    private var decided: PlaybackDecision = decideNow()

    /**
     * The store's licence exchanges, where it was built with a protection of the module's own. Their failed
     * requests spend the same retry half the downloaders read, through a window onto it that Media3's session
     * manager consults on its own request thread (ADR-0013 rule 14, #260); without a resilience, Media3's own
     * licence handling answers.
     */
    private val licences: DownloadLicences? = drm?.let { protection ->
        // Read through rather than decided there: `PlaybackPolicy.decide` is called on the store's thread.
        val decisions = DecisionInForce().apply { fedBy { decided } }
        DownloadLicences(
            context,
            protection,
            checkNotNull(licenceStore),
            environment,
            headerRefresh,
            resilience?.downloadLicenceErrors(decisions),
        )
    }

    /** Downloaders waiting on the store's clock to ask for a failed request again; before the manager, whose downloaders count. */
    private val waitingToRetry = AtomicInteger()

    private val manager = DownloadManager(context, cache.downloadIndex(), PinningDownloaderFactory()).apply {
        // The network and storage, which Media3's requirements state and its watcher applies on the manager's
        // own thread. Set before the listener is added, which would otherwise hear it before this store is built.
        // Built paused, for Media3's `DownloadService` to resume. This store resumes its own once it has read the
        // battery (`onConditionsChanged`): the downloads run in the store, and the app's `DownloadsService` only
        // holds the process in the foreground around them (ADR-0013 rule 3), so a store opened by a screen alone
        // downloads just as one a service holds does.
        requirements = requirementsFor(meteredNetworksAllowed = false)
        // A store with a resilience spends the policy's budgets inside its downloader (`PinningDownloader`), so
        // a failure it gives up on is final. Left at Media3's own count, the manager would ask again, sleeping a
        // thread nothing can advance, and spend a budget nobody decided. Without one, the manager's retries are
        // the only ones, as Media3 ships them.
        if (resilience != null) minRetryCount = 0
        addListener(ManagerEvents())
    }

    /**
     * Whether a download may run over a metered network, which the viewer decides: false unless set, so a
     * download spends no data allowance nobody agreed to (ADR-0013 rule 10). Store-wide, and applied at once
     * to every download, running or waiting. A store that accepts a metered network still downloads nothing
     * over one while Data Saver restricts this app's background data.
     *
     * Not remembered: the setting is the viewer's and lives with the app, which sets it on every store it
     * opens. What the last store scheduled carries it across a reboot.
     */
    public var meteredNetworksAllowed: Boolean = false
        set(allowed) {
            checkUsable()
            if (field == allowed) return
            field = allowed
            manager.requirements = requirementsFor(allowed)
            onConditionsChanged()
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

    /** Downloads enqueued while a condition held them, whose manifests are read once it lets go, by content id. */
    private val waiting = LinkedHashMap<String, Enqueued>()

    /** Downloads whose manifest is being read to choose their tracks, before the manager holds them, by content id. */
    private val selecting = LinkedHashMap<String, Selecting>()

    /** Downloads whose tracks could not be chosen, held until removed or enqueued again, by content id. */
    private val unselectable = LinkedHashMap<String, DownloadItem>()

    /** Protected downloads whose licence is being acquired, before the manager holds them, by content id. */
    private val licensing = LinkedHashMap<String, Enqueued>()

    /** What each expired licence is as a typed error, made once so an item reporting it compares equal. */
    private val expiries = HashMap<String, SuperPlayerError>()

    /**
     * Whether a removed download's licence is owed a release: set by a removal, and by a store opened over one a
     * last process left owed; cleared by a pass that released every one.
     */
    private var owesLicenceReleases = false

    private var releasingLicences = false

    private var releaseAgain = false

    private var released = false

    /** Whether a download can run now: pending, and held by no condition. What the service is in the foreground for. */
    private var running = false

    /** What hears [running] change: the service holding this store in the foreground. */
    private val runningObservers = CopyOnWriteArrayList<(Boolean) -> Unit>()

    init {
        // Read before the manager is resumed, so a download the last process left is not started under a low battery.
        onConditionsChanged()
    }

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
        selecting.remove(contentId)?.helper?.release()
        unselectable -= contentId
        waiting -= contentId
        licensing -= contentId
        // Content the manager already holds keeps reporting what it holds, so a download enqueued again is
        // never seen to go backwards; what is new is queued while its manifest is read, or stopped while a
        // condition holds it.
        val isNew = manager.currentDownloads.none { it.request.id == contentId } && manager.downloadIndex.getDownload(contentId) == null
        val enqueued = Enqueued(request, audioLanguages.toList(), subtitleLanguages.toList())
        // The battery as it is now, not as it was when this store last had anything to watch it for.
        conditions.read()
        applyConditions()
        if (heldBy() == null) select(enqueued) else waiting[contentId] = enqueued
        if (isNew) beforeTheManager(contentId)?.let(::report)
        updatePending()
    }

    /** Reads [enqueued]'s manifest and chooses its tracks, and hands what was chosen to the manager. */
    private fun select(enqueued: Enqueued) {
        val contentId = enqueued.request.contentId
        // Consulted once, now, with what is observed now: bytes written at one rendition are not chosen again.
        val decision = decideNow()
        decided = decision
        val selection = DownloadSelection(decision.download, enqueued.audioLanguages, enqueued.subtitleLanguages)
        val sessions = try {
            licences?.sessions()
        } catch (downgradeRefused: SecurityDowngradeRefusedException) {
            // The device cannot be given this content at any level the server permits: nothing to download.
            failBeforeTheManager(contentId, downgradeRefused)
            return
        }
        val helper = DownloadHelper.Factory()
            // The manifest is read over the one chain, as the download itself is (rule 6).
            .setDataSourceFactory(upstream)
            // The device's own renderers, whose decoders are the device's refusal (ADR-0009 rule 2).
            .setRenderersFactory(renderers)
            .setTrackSelectionParameters(selection.parameters)
            // A protected format is one the renderers can decrypt only where the manifest read has sessions to ask.
            .apply { sessions?.let(::setDrmSessionManager) }
            // Media3's own loading thread, which a release can cancel, except under a harness that owns it.
            .apply { environment?.let { owned -> setLoadExecutor { ReleasableExecutor.from(owned.loadExecutor) {} } } }
            .create(MediaItem.fromUri(enqueued.request.sources.first()))
        selecting[contentId] = Selecting(helper, enqueued)
        helper.prepare(
            object : DownloadHelper.Callback {
                override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                    if (released || selecting[contentId]?.helper !== helper) return
                    selecting -= contentId
                    selection.applyTo(helper)
                    val chosen = helper.getDownloadRequest(contentId, null)
                    val protectedFormat = licences?.protectedFormatOf(helper)
                    helper.release()
                    // A licence still in force is kept rather than acquired again, which would leave the first one
                    // counted against the device at the server with nothing left to release it.
                    if (protectedFormat == null || licences.standingOf(contentId)?.isExpired == false) {
                        // Pending throughout: the manager's report of the download it now holds is what updates that.
                        manager.addDownload(chosen)
                    } else {
                        acquireLicence(enqueued, protectedFormat) { manager.addDownload(chosen) }
                    }
                }

                override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                    if (released || selecting[contentId]?.helper !== helper) return
                    selecting -= contentId
                    helper.release()
                    // Media3's helper refuses a timeline whose window is live before it prepares a period, so the
                    // manifest is all that was fetched and nothing was pinned: that is ADR-0013 rule 8's refusal,
                    // reported as one rather than as a manifest that could not be read.
                    val refusal = if (e is DownloadHelper.LiveContentUnsupportedException) DownloadRefusal.LIVE_CONTENT else null
                    failBeforeTheManager(contentId, error = null, refusal = refusal)
                }
            },
        )
    }

    /**
     * Acquires [enqueued]'s licence for [format] before the manager is handed its download, and then calls
     * [addDownload]: after the manifest and before the first media byte, so a refused licence fails an item
     * that has written nothing and pinned nothing (ADR-0013 rule 13).
     */
    private fun acquireLicence(enqueued: Enqueued, format: Format, addDownload: () -> Unit) {
        val contentId = enqueued.request.contentId
        val licences = checkNotNull(licences)
        licensing[contentId] = enqueued
        licences.acquire(contentId, format) { failure, replaced ->
            handler.post {
                if (released) return@post
                // The licence it replaced — expired, or acquired by an enqueue this one overtook — is owed a release.
                if (replaced) releaseOwedLicences(owed = true)
                if (licensing[contentId] !== enqueued) {
                    // Removed while its licence was on the way: the licence arrived for a download nobody wants.
                    val wanted = contentId in licensing || contentId in selecting || contentId in waiting ||
                        manager.currentDownloads.any { it.request.id == contentId }
                    if (failure == null && !wanted && licences.owe(contentId)) releaseOwedLicences(owed = true)
                    return@post
                }
                licensing -= contentId
                if (failure == null) addDownload() else failBeforeTheManager(contentId, failure)
            }
        }
    }

    /**
     * Fails [contentId] before the manager holds it, with [error] named where there is a resilience to ask, or
     * with the store's own [refusal], which every store reports.
     */
    private fun failBeforeTheManager(contentId: String, error: Throwable?, refusal: DownloadRefusal? = null) {
        val failed = DownloadItem(
            contentId,
            DownloadState.FAILED,
            bytesDownloaded = 0,
            percentDownloaded = null,
            failure = error?.let { resilience?.failureOf(it) },
            refusal = refusal,
        )
        unselectable[contentId] = failed
        report(failed)
        updatePending()
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
        return held + (waiting.keys + selecting.keys + licensing.keys + unselectable.keys).filter { it !in heldIds }.mapNotNull(::beforeTheManager)
    }

    /**
     * Removes the download of [contentId]: its bytes are deleted, then its pin, and then the store
     * forgets it and [DownloadsListener.onDownloadRemoved] says so. Content with no download is ignored.
     */
    public fun remove(contentId: String) {
        checkUsable()
        val choosing = selecting.remove(contentId)
        choosing?.helper?.release()
        val failed = unselectable.remove(contentId)
        val held = waiting.remove(contentId)
        val acquiring = licensing.remove(contentId)
        failures -= contentId
        expiries -= contentId
        forgetResumption(contentId)
        // Nothing reached the manager, so nothing was written and nothing is pinned: forgetting it is the removal.
        if ((choosing != null || failed != null || held != null || acquiring != null) && manager.currentDownloads.none { it.request.id == contentId }) {
            reported -= contentId
            listeners.forEach { it.onDownloadRemoved(contentId) }
        }
        manager.removeDownload(contentId)
        // The bytes go now and the licence as soon as the network allows: the storage is the viewer's the moment
        // they asked, and a release is a round trip a removal made on a plane still owes (ADR-0013 rule 13).
        if (licences?.owe(contentId) == true) releaseOwedLicences(owed = true)
        updatePending()
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
        // Nothing runs on a released store, so a service holding it lets the process go.
        setRunning(false)
        handler.removeCallbacksAndMessages(null)
        listeners.clear()
        selecting.values.forEach { it.helper.release() }
        selecting.clear()
        waiting.clear()
        conditions.unwatch()
        licences?.shutDown()
        // What is pending stays pending on disk, and so stays scheduled.
        DownloadSchedule.forget(context, this)
        manager.release()
    }

    private fun checkUsable() {
        check(!released) { "The download store has been released" }
        check(Looper.myLooper() == handler.looper) { "A download store is used on the thread it was built on" }
    }

    /**
     * A download the manager does not hold yet, or never will: waiting for a condition, its tracks being chosen,
     * or failing to be.
     */
    private fun beforeTheManager(contentId: String): DownloadItem? = unselectable[contentId]
        ?: when (contentId) {
            in waiting -> heldBy()?.let { DownloadItem(contentId, DownloadState.STOPPED, 0, null, stopReason = it) } ?: queued(contentId)
            in selecting, in licensing -> queued(contentId)
            else -> null
        }

    private fun queued(contentId: String) = DownloadItem(contentId, DownloadState.QUEUED, bytesDownloaded = 0, percentDownloaded = null)

    private fun report(item: DownloadItem) {
        if (released || reported[item.contentId] == item) return
        reported[item.contentId] = item
        listeners.forEach { it.onDownloadChanged(item) }
    }

    /**
     * The first condition that holds downloads back now, or null where every one holds (ADR-0013 rules 10 and
     * 11). The network and storage are the manager's requirements, read as the manager last applied them, so
     * what an item reports is what holds it; the battery and Data Saver are this store's, as last read.
     */
    private fun heldBy(): DownloadStopReason? {
        val notMet = manager.notMetRequirements
        return when {
            // An unmetered requirement carries the plain network one with it, so it is asked first.
            notMet and Requirements.NETWORK_UNMETERED != 0 -> DownloadStopReason.NO_UNMETERED_NETWORK

            notMet and Requirements.NETWORK != 0 -> DownloadStopReason.NO_NETWORK

            conditions.dataSaverRestricts -> DownloadStopReason.DATA_SAVER

            conditions.batteryLow -> DownloadStopReason.BATTERY_LOW

            notMet and Requirements.DEVICE_STORAGE_NOT_LOW != 0 -> DownloadStopReason.STORAGE_LOW

            else -> null
        }
    }

    /**
     * Applies the conditions as last read: the manager paused for what its requirements cannot state, a
     * manifest read that a lapse caught abandoned until the conditions hold, and a waiting one begun once they do.
     */
    private fun applyConditions() {
        // Media3 moves a running download back to queued the moment it is paused, and cancels its loader.
        if (conditions.batteryLow || conditions.dataSaverRestricts) manager.pauseDownloads() else manager.resumeDownloads()
        if (heldBy() != null) {
            selecting.values.toList().forEach { choosing ->
                choosing.helper.release()
                selecting -= choosing.enqueued.request.contentId
                waiting[choosing.enqueued.request.contentId] = choosing.enqueued
            }
        } else {
            val ready = waiting.values.toList()
            waiting.clear()
            ready.forEach(::select)
        }
    }

    /** A condition changed, or may have: read them all again, apply them, and report what that changed. On the store's thread. */
    private fun onConditionsChanged() {
        if (released) return
        conditions.read()
        applyConditions()
        reportAll()
        releaseOwedLicences()
        updatePending()
    }

    /**
     * Releases the licences removed downloads owe, where the store's network requirement holds and a pass is
     * not already running; [owed] says a removal has just added one. A pass that leaves one owed is tried
     * again at the next change of conditions or run of the scheduled work, which stays scheduled until none is.
     */
    private fun releaseOwedLicences(owed: Boolean = false) {
        val licences = licences ?: return
        if (owed) owesLicenceReleases = true
        if (released || !owesLicenceReleases) return
        if (releasingLicences) {
            releaseAgain = releaseAgain || owed
            return
        }
        // The network the store downloads under, and nothing else: a release is small, and the battery and
        // Data Saver hold what is fetched rather than what is given back.
        // Read now rather than as the manager last applied it: a removal can follow a change the manager has not
        // yet heard, and a release sent over a network the viewer did not accept is the one thing not to do.
        if (manager.requirements.getNotMetRequirements(context) and (Requirements.NETWORK or Requirements.NETWORK_UNMETERED) != 0) return
        releasingLicences = true
        licences.releaseOwed { stillOwed ->
            handler.post {
                releasingLicences = false
                owesLicenceReleases = stillOwed || releaseAgain
                val again = releaseAgain
                releaseAgain = false
                if (again) releaseOwedLicences()
                updatePending()
            }
        }
    }

    /** Every download this store knows of, reported again where what it is has changed. */
    private fun reportAll() {
        // A running download is announced by its progress, for the reason `ManagerEvents` gives.
        manager.currentDownloads.filter { it.state != Download.STATE_DOWNLOADING }.forEach { report(it.toItem()) }
        (waiting.keys + selecting.keys + licensing.keys).forEach { contentId ->
            if (manager.currentDownloads.none { it.request.id == contentId }) beforeTheManager(contentId)?.let(::report)
        }
    }

    /**
     * Watches the conditions, and keeps the process's schedule, for as long as a download is pending — and not
     * otherwise, so a store with nothing to download registers and schedules nothing (ADR-0013 rule 15).
     */
    private fun updatePending() {
        if (released) return
        val pending = waiting.isNotEmpty() ||
            selecting.isNotEmpty() ||
            licensing.isNotEmpty() ||
            owesLicenceReleases ||
            manager.currentDownloads.any { it.state != Download.STATE_REMOVING }
        if (pending) conditions.watch() else conditions.unwatch()
        val network = if (meteredNetworksAllowed) NetworkType.CONNECTED else NetworkType.UNMETERED
        DownloadSchedule.want(context, this, network.takeIf { pending }, service)
        setRunning(pending && heldBy() == null)
    }

    private fun setRunning(running: Boolean) {
        if (this.running == running) return
        this.running = running
        if (running) service?.let { startDownloadsService(context, it) }
        runningObservers.forEach { it(running) }
    }

    /** Whether a download can run now: for [DownloadsService], which holds the process in the foreground while one can. */
    internal fun isRunning(): Boolean = running

    /** Tells [observer] each time [isRunning] changes, on the store's thread. */
    internal fun addRunningObserver(observer: (Boolean) -> Unit) {
        runningObservers += observer
    }

    internal fun removeRunningObserver(observer: (Boolean) -> Unit) {
        runningObservers -= observer
    }

    /** Media3's downloads the manager holds now, and the requirements it waits on: for the service's default notification. */
    internal fun currentMediaDownloads(): List<Download> = manager.currentDownloads

    internal fun notMetRequirements(): Int = manager.notMetRequirements

    /** The policy's decision under what is observed now. On the store's thread. */
    private fun decideNow(): PlaybackDecision = policy.decide(PlaybackConditions(transport = currentNetworkTransportOf(context)))

    /** Whether a pass releasing owed licences is running now: for this module's own tests to wait on. */
    internal fun isReleasingLicences(): Boolean = releasingLicences

    /** Whether a downloader is waiting to ask for a failed request again: for this module's own tests to wait on. */
    internal fun isWaitingToRetry(): Boolean = waitingToRetry.get() > 0

    /** The scheduled work ran, on whichever thread `WorkManager` ran it: the conditions it waited for may hold now. */
    internal fun onScheduledWorkRan() {
        handler.post(::onConditionsChanged)
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
            if (download.state != Download.STATE_DOWNLOADING) report(download.toItem())
            updatePending()
        }

        override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
            val contentId = download.request.id
            failures -= contentId
            forgetResumption(contentId)
            reported -= contentId
            if (!released) listeners.forEach { it.onDownloadRemoved(contentId) }
            updatePending()
        }

        override fun onInitialized(manager: DownloadManager) {
            // What the last process left pending is known only now the index has been read. The releases it owed
            // are read here too, one query of the licence store, so a store that owes none schedules nothing.
            if (licences?.owesAny() == true) releaseOwedLicences(owed = true)
            updatePending()
        }

        override fun onRequirementsStateChanged(manager: DownloadManager, requirements: Requirements, notMetRequirements: Int) {
            onConditionsChanged()
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

        /** Released by a cancellation too, while a retry's wait is under way. */
        @Volatile
        private var retryDue: CountDownLatch? = null

        /** The bytes the delegate last reported, which tell a failure further on from the same one met again. */
        private val bytesReported = AtomicLong(C.LENGTH_UNSET.toLong())

        override fun download(progressListener: Downloader.ProgressListener?) {
            cache.pin(contentId)
            // Where the last failure was met, and how many times the request there has been asked for again.
            var failedAt: Long? = null
            var retry = 0
            while (true) {
                try {
                    downloadReporting(progressListener)
                    return
                } catch (e: IOException) {
                    // A full disk fails this item now, on every store (ADR-0013 rule 9): thrown as an I/O failure,
                    // Media3 would ask for the segment again on a disk that is still full, and a resilience would
                    // read it as a lost network and wait. This decides only *when* the item fails, off the
                    // exception core raised as evidence; what it failed with is still the classifier's, which
                    // finds core's exception beneath this one.
                    if (generateSequence<Throwable>(e) { it.cause }.take(CAUSE_DEPTH).any { it is StorageFullException }) throw NotRetried(e)
                    // Without a resilience, the manager's own retries are all there are.
                    val resilience = resilience ?: throw e
                    if (canceled.count == 0L) throw e
                    if (resilience.isNetworkLoss(e)) {
                        // Held here until the stop cancels this download, rather than thrown: a thrown failure is
                        // final on this store, and the stop is what keeps it from failing. Returning once
                        // canceled is a task Media3 forgets rather than finishes. Released by a removal or the
                        // store's release as well, since both cancel. Media3 interrupts a thread it cancels, which
                        // ends this wait by throwing, and a cancelled task's exception is one Media3 ignores.
                        handler.post { stopForLostNetwork(contentId) }
                        canceled.await()
                        return
                    }
                    // A budget bounds one request, as on a player, so a failure where the download has got further
                    // starts it afresh; Media3's manager counts its own retries the same way. A downloader asked
                    // again counts what the cache holds before it fetches, so an unchanged count is the same place.
                    val at = bytesReported.get()
                    if (at != failedAt) {
                        failedAt = at
                        retry = 0
                    }
                    // Thrown as it is, and final: the manager asks nothing again on a store with a resilience.
                    val waitMs = resilience.waitBeforeRetryingMs(e, ++retry, decided.retry) ?: throw e
                    if (!awaitRetry(waitMs)) return
                }
            }
        }

        /**
         * Waits [waitMs] on the store's thread's clock before a failed request is asked for again, and answers
         * whether to ask: false once Media3 has cancelled this download meanwhile.
         *
         * A delayed post rather than a sleep, so the wait is on a clock a looper moves — the harness's included
         * — and ends the moment a stop, a removal or the store's release cancels the download. A cancellation
         * before the wait began is read here, and one after it releases the wait itself.
         */
        private fun awaitRetry(waitMs: Long): Boolean {
            val due = CountDownLatch(1)
            retryDue = due
            val wake = Runnable { due.countDown() }
            // A looper that has quit takes no post: the store's thread is gone, and the wait would never end.
            if (!handler.postDelayed(wake, waitMs)) return false
            waitingToRetry.incrementAndGet()
            try {
                if (canceled.count != 0L) due.await()
            } finally {
                waitingToRetry.decrementAndGet()
                handler.removeCallbacks(wake)
                retryDue = null
            }
            return canceled.count != 0L
        }

        private fun downloadReporting(progressListener: Downloader.ProgressListener?) {
            val lastWholePercent = AtomicInteger(Int.MIN_VALUE)
            delegate.download { contentLength, bytesDownloaded, percentDownloaded ->
                bytesReported.set(bytesDownloaded)
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
            retryDue?.countDown()
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
        // The licence as the last state change read it: a progress report is no reason to read the licence store.
        report(DownloadItem(contentId, DownloadState.DOWNLOADING, bytesDownloaded, percentOrNull(percentDownloaded), licence = last?.licence))
    }

    /** A download as the store reports it: Media3's, with what this store knows about why it stopped or failed. */
    private fun Download.toItem(): DownloadItem {
        val lostNetwork = state == Download.STATE_STOPPED && stopReason == STOP_REASON_NETWORK_LOST
        // A condition holds what would otherwise run, and outranks a lost network: it is what the item waits for
        // once the network is back.
        val held = if (state == Download.STATE_QUEUED || lostNetwork) heldBy() else null
        return DownloadItem(
            contentId = request.id,
            state = if (held != null) DownloadState.STOPPED else stateOf(this),
            bytesDownloaded = bytesDownloaded,
            percentDownloaded = percentOrNull(percentDownloaded),
            stopReason = held ?: DownloadStopReason.NETWORK_LOST.takeIf { lostNetwork },
            failure = if (state == Download.STATE_FAILED) failures[request.id] else null,
            licence = licenceOf(request.id),
        )
    }

    /** What [contentId]'s licence allows, read from the licence store now, or null where it holds none. */
    private fun licenceOf(contentId: String): DownloadLicence? {
        val standing = licences?.standingOf(contentId) ?: return null
        val expiry = if (!standing.isExpired) {
            null
        } else {
            expiries[contentId] ?: resilience?.failureOf(
                expiredLicenceFailure(
                    OfflineLicenceExpiredException(contentId, standing.playbackDurationRemainingMs, standing.licenceDurationRemainingMs),
                ),
            )?.also { expiries[contentId] = it }
        }
        return DownloadLicence(standing.isExpired, standing.renewalDue, expiry)
    }

    /** A download's failure, thrown as something other than an I/O failure so Media3 fails the item without retrying it. */
    private class NotRetried(cause: IOException) : RuntimeException(cause)

    /** A request enqueued, with the languages it was enqueued for. */
    private class Enqueued(val request: MediaRequest, val audioLanguages: List<String>, val subtitleLanguages: List<String>)

    /** An enqueued request whose manifest [helper] is reading. */
    private class Selecting(val helper: DownloadHelper, val enqueued: Enqueued)

    private companion object {

        // Far past the depth a download's failure is wrapped to — Media3's task, the cache's sink exception, the
        // stream's — and a bound because a cause chain that loops is the thrower's to build.
        private const val CAUSE_DEPTH = 8

        /**
         * Media3's stop reason for a download stopped for a lost network, written into the index with it.
         * Any value but `STOP_REASON_NONE` stops a download. The conditions take none: they pause the manager
         * or are its requirements, neither written into the index, so a process that opens it reads them afresh.
         */
        const val STOP_REASON_NETWORK_LOST = 1

        /** The network and storage a download needs, as Media3's requirements state them; the battery is the store's. */
        fun requirementsFor(meteredNetworksAllowed: Boolean) = Requirements(
            (if (meteredNetworksAllowed) Requirements.NETWORK else Requirements.NETWORK_UNMETERED) or Requirements.DEVICE_STORAGE_NOT_LOW,
        )
    }
}

/**
 * Starts [service], as a foreground service where the platform asks for one to be announced as such. A
 * start the platform refuses — from the background, where it restricts foreground-service starts — is
 * left to the scheduled work, which tries again under `WorkManager`'s backoff (ADR-0013 rule 11's addendum).
 */
internal fun startDownloadsService(context: Context, service: Class<out DownloadsService>) {
    val intent = Intent(context, service)
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    } catch (refused: IllegalStateException) {
        // `ForegroundServiceStartNotAllowedException` and the background-start refusal are both this type.
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
