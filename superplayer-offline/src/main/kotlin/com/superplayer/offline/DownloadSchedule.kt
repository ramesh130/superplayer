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
 * **What it does when it runs** is ask each open store to read its conditions again, and ask to be retried —
 * so it stays persisted, under `WorkManager`'s own backoff — until a store finds nothing pending and cancels it.
 * In a process with no store open, after a reboot say, it can resume nothing yet: opening one is the
 * consumer's service's (#246). It retries there too rather than ending, so the schedule survives until
 * something opens a store.
 *
 * **Nothing here caches what `WorkManager` holds.** Work is enqueued again whenever a store's want changes —
 * something became pending, the network it accepts changed — so work an app cancelled itself is scheduled
 * again at the next change, and the constraint follows the stores still open rather than one released.
 */
internal object DownloadSchedule {

    private const val UNIQUE_WORK_NAME = "com.superplayer.offline.downloads"

    /** The network each open store with a download pending needs, by store. */
    private val wanted = HashMap<Downloads, NetworkType>()

    /**
     * [store] has a download pending that needs [network], or with null has none. Enqueues the work afresh, or
     * cancels it, where that changes what the store wants; a store that asks for what it already wanted costs
     * nothing, which is what lets it ask on every condition it hears.
     */
    @Synchronized
    fun want(context: Context, store: Downloads, network: NetworkType?) {
        if (wanted[store] == network) return
        if (network == null) wanted -= store else wanted[store] = network
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
        val network = if (wanted.values.all { it == NetworkType.CONNECTED }) NetworkType.CONNECTED else NetworkType.UNMETERED
        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, workFor(network))
    }

    private fun workFor(network: NetworkType) = OneTimeWorkRequestBuilder<PendingDownloadsWorker>()
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
            openStores().forEach(Downloads::onScheduledWorkRan)
            return Result.retry()
        }
    }
}
