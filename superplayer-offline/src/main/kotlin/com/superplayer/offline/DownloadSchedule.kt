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
 * **What it does when it runs** is ask each open store to read its conditions again, and stay scheduled
 * while any download is pending — a retry, so the constraints and the backoff are `WorkManager`'s. In a
 * process with no store open there is nobody to hand the queue to: opening one is the consumer's service's
 * (#246), and until then the work ends and the next store to open with something pending schedules it again.
 */
internal object DownloadSchedule {

    private const val UNIQUE_WORK_NAME = "com.superplayer.offline.downloads"

    /** The network each open store with a download pending needs, by store. */
    private val wanted = HashMap<Downloads, NetworkType>()

    /** What this process last scheduled, or null for nothing, or for not knowing. */
    private var scheduled: NetworkType? = null

    /**
     * [store] has a download pending that needs [network], or with null has none. Enqueues or cancels the
     * work where the process's answer changed.
     */
    @Synchronized
    fun want(context: Context, store: Downloads, network: NetworkType?) {
        if (network == null) wanted -= store else wanted[store] = network
        val needed = when {
            wanted.isEmpty() -> null
            wanted.values.all { it == NetworkType.CONNECTED } -> NetworkType.CONNECTED
            else -> NetworkType.UNMETERED
        }
        if (needed == scheduled) return
        scheduled = needed
        val workManager = WorkManager.getInstance(context)
        if (needed == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
        } else {
            workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, workFor(needed))
        }
    }

    /**
     * [store] was released. Its pending downloads are still pending on disk, so the work stays; what this
     * process believes it scheduled is forgotten once no store is open, so the next store schedules afresh
     * rather than trusting a belief about work nobody here can see any more.
     */
    @Synchronized
    fun forget(store: Downloads) {
        wanted -= store
        if (wanted.isEmpty()) scheduled = null
    }

    @Synchronized
    private fun ran(): List<Downloads> = wanted.keys.toList().also { if (it.isEmpty()) scheduled = null }

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
            val stores = ran()
            if (stores.isEmpty()) return Result.success()
            stores.forEach(Downloads::onScheduledWorkRan)
            return Result.retry()
        }
    }
}
