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

package com.superplayer.testkit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/**
 * `WorkManager` under test, and the one decision its test driver leaves to the test: whether a piece
 * of scheduled work's constraints hold.
 *
 * Kept out of [PlaybackHarness]'s own body so that WorkManager's classes are loaded only by a test that
 * asks for them — `superplayer-testkit` compiles against WorkManager without carrying it, and a module
 * whose tests never schedule work never resolves it.
 */
internal object ScheduledWork {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Replaces the process's `WorkManager` with WorkManager's own test implementation, running work
     * synchronously on the thread that releases it. What a download store asks `WorkManager.getInstance`
     * for from then on is this one.
     */
    fun install() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
    }

    /**
     * Runs every enqueued piece of work whose constraints the device, as `DeviceStatement` stated it,
     * satisfies, and answers how many that was. Work whose constraints do not hold stays enqueued.
     */
    fun runWhereConstraintsHold(): Int {
        val workManager = WorkManager.getInstance(context)
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context)) {
            "WorkManager is not under test: call PlaybackHarness.useScheduledWork() before anything schedules work"
        }
        val enqueued = workManager.getWorkInfos(WorkQuery.fromStates(WorkInfo.State.ENQUEUED)).get()
        val runnable = enqueued.filter { holds(it) }
        runnable.forEach { driver.setAllConstraintsMet(it.id) }
        return runnable.size
    }

    private fun holds(work: WorkInfo): Boolean {
        val constraints = work.constraints
        // A constraint this harness cannot state is refused loudly rather than read as met: a test of
        // work that waits for a charger would otherwise pass without a charger ever being stated.
        check(!constraints.requiresCharging() && !constraints.requiresDeviceIdle()) {
            "Work ${work.id} requires charging or an idle device, which DeviceStatement cannot state"
        }
        val network = when (constraints.requiredNetworkType) {
            NetworkType.NOT_REQUIRED -> true
            NetworkType.CONNECTED -> DownloadConditions.connected
            NetworkType.UNMETERED -> DownloadConditions.unmetered
            else -> error("Work ${work.id} requires network ${constraints.requiredNetworkType}, which DeviceStatement cannot state")
        }
        return network &&
            !(constraints.requiresBatteryNotLow() && DownloadConditions.batteryLow) &&
            !(constraints.requiresStorageNotLow() && DownloadConditions.storageLow)
    }
}
