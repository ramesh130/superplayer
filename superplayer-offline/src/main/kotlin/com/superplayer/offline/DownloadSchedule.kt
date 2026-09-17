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
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * The process's `WorkManager` schedule for pending downloads (ADR-0013 rule 11): one piece of persisted
 * work, under an unmetered or any network, battery not low and storage not low, enqueued while any open
 * store has a download pending and cancelled once none has.
 *
 * **Persisted work is the point.** `WorkManager` keeps it in a database of its own, so it outlives the
 * process and a reboot, and runs once its constraints hold. One piece for the process rather than one per
 * store or per item: what it does when it runs is look at every store, and the constraints are the same
 * for every download bar the network, which is any network only where every pending store accepts one.
 *
 * **What it does when it runs** is start the app's [DownloadsService], where a store named one, ask each open
 * store to read its conditions again, and ask to be retried — so it stays persisted, under `WorkManager`'s own
 * backoff — until a store finds nothing pending and cancels it. The service is what opens a store in a process
 * with no store open, after a reboot say, which is why its class travels in the work's own input data: that is
 * `WorkManager`'s database rather than storage of SuperPlayer's choosing, and it is the only thing a fresh
 * process has to go on. Where no store named a service, or the platform refuses the start from the background,
 * the work retries rather than ending, so the schedule survives until something opens a store.
 *
 * ref: Media3's own `WorkManagerScheduler` starts its `DownloadService` from its worker the same way.
 * https://github.com/androidx/media/blob/release/libraries/exoplayer_workmanager/src/main/java/androidx/media3/exoplayer/workmanager/WorkManagerScheduler.java
 *
 * **Nothing here caches what `WorkManager` holds.** Work is enqueued again whenever a store's want changes —
 * something became pending, the network it accepts changed — so work an app cancelled itself is scheduled
 * again at the next change, and the constraint follows the stores still open rather than one released.
 */
internal object DownloadSchedule {

    private const val UNIQUE_WORK_NAME = "com.superplayer.offline.downloads"

    private const val SERVICE_CLASS_KEY = "service"

    /** What each open store with a download pending needs, by store. */
    private val wanted = HashMap<Downloads, Want>()

    /**
     * [store] has a download pending that needs [network], or with null has none, and names [service] as what starts
     * a store in a fresh process. Enqueues the work afresh, or
     * cancels it, where that changes what the store wants; a store that asks for what it already wanted costs
     * nothing, which is what lets it ask on every condition it hears.
     */
    @Synchronized
    fun want(context: Context, store: Downloads, network: NetworkType?, service: Class<out DownloadsService>?) {
        val want = network?.let { Want(it, service) }
        if (wanted[store] == want) return
        if (want == null) wanted -= store else wanted[store] = want
        sync(context)
    }

    /**
     * [store] was released. Its pending downloads are still pending on disk, so the work is not cancelled; where
     * other stores remain, it is enqueued again for what they need.
     */
    @Synchronized
    fun forget(context: Context, store: Downloads) {
        if (wanted.remove(store) != null && wanted.isNotEmpty()) sync(context)
    }

    @Synchronized
    private fun openStores(): List<Downloads> = wanted.keys.toList()

    private fun sync(context: Context) {
        val workManager = WorkManager.getInstance(context)
        if (wanted.isEmpty()) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val network = if (wanted.values.all { it.network == NetworkType.CONNECTED }) NetworkType.CONNECTED else NetworkType.UNMETERED
        // One service per app is the shape rule 3 describes; where stores name different ones, any of them opens a store.
        val service = wanted.values.firstNotNullOfOrNull { it.service }
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, workFor(network, service))
    }

    private fun workFor(network: NetworkType, service: Class<out DownloadsService>?) = OneTimeWorkRequestBuilder<PendingDownloadsWorker>()
        .apply { service?.let { setInputData(workDataOf(SERVICE_CLASS_KEY to it.name)) } }
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(network)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build(),
        )
        .build()

    /** The work itself, instantiated by `WorkManager` by name, which is why it is a class and not a lambda. */
    internal class PendingDownloadsWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {

        override fun doWork(): Result {
            serviceNamed(inputData.getString(SERVICE_CLASS_KEY))?.let { startDownloadsService(applicationContext, it) }
            openStores().forEach(Downloads::onScheduledWorkRan)
            return Result.retry()
        }

        /** The service a store named, or null where none was named or this build of the app no longer has it. */
        private fun serviceNamed(name: String?): Class<out DownloadsService>? = name?.let {
            runCatching { Class.forName(it, false, applicationContext.classLoader).asSubclass(DownloadsService::class.java) }.getOrNull()
        }
    }

    /** What one store wants of the schedule. */
    private data class Want(val network: NetworkType, val service: Class<out DownloadsService>?)
}
