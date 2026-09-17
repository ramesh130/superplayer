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

import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.ExoMediaDrm
import com.superplayer.core.DownloadEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The [DownloadEnvironment] a [PlaybackHarness] hands a download: the transport a harness-built player
 * would load the same content through, and a load executor the harness counts.
 *
 * [injector] and [wait] are kept for the harness's own questions — what the download fetched, and
 * whether its loads have caught up — exactly as a player's are.
 */
internal class HarnessDownloadEnvironment(
    override val transport: DataSource.Factory,
    val injector: FaultInjectingDataSource.Factory,
    val wait: HarnessClockWait,
    override val loadExecutor: HarnessDownloadLoads,
    override val renderersFactory: RenderersFactory,
    override val mediaDrm: ExoMediaDrm.Provider?,
) : DownloadEnvironment()

/**
 * Where a download's segment loads run: on the download's own thread, as Media3's default executor runs them,
 * counted into [wait] as [HarnessLoadThreads] counts a player's, and able to stop taking work — which is how
 * [PlaybackHarness.processDeath] freezes a download at a moment without asking the download to cooperate.
 *
 * **On the caller's thread, not a thread of the harness's.** Media3's segment downloader hands each segment to
 * the executor it was given and waits for it, so run where it is handed over a download's segments are fetched
 * in manifest order on every run, as they are in an app. A thread shared by every download in an environment
 * was the earlier shape, and it coupled two items that are independent in an app: a load queued behind another
 * item's delayed one could not act on the time that passed, and an item that failed waited for its queued next
 * segment to be cancelled behind the other item's held load (#244). Parallel segment fetching within one item is
 * a throughput question for Phase 10, not a behaviour a Phase 7 test asserts on.
 */
internal class HarnessDownloadLoads(private val wait: HarnessClockWait) : Executor {

    /** Loads running now, which [shutDown] waits for. */
    private val running = AtomicInteger()

    private val lock = Object()

    /** Released once the process this executor belonged to is dead and its directory copied. */
    @Volatile
    private var frozen: CountDownLatch? = null

    override fun execute(task: Runnable) {
        // A dead process runs nothing more. The caller — the download's own thread — is held here rather
        // than refused at once, so that it writes nothing into the directory while it is being copied,
        // and refused afterwards, so that it ends rather than waiting for ever.
        frozen?.let { latch ->
            latch.await()
            throw RejectedExecutionException("The process this download ran in is dead")
        }
        wait.loadTaskSubmitted()
        running.incrementAndGet()
        // Checked again once counted: a freeze that landed between the check above and the count would
        // otherwise see no load in flight, start its copy, and have this one begin writing under it.
        val frozenSinceTheCheck = frozen != null
        try {
            if (!frozenSinceTheCheck) task.run()
        } finally {
            running.decrementAndGet()
            wait.loadTaskFinished()
            synchronized(lock) { lock.notifyAll() }
        }
        if (frozenSinceTheCheck) execute(task)
    }

    /** Takes no new work; a task already running finishes. */
    fun freeze() {
        if (frozen == null) frozen = CountDownLatch(1)
    }

    /**
     * Lets every caller held by [freeze] go, each refused, and waits a bounded time for a load still running
     * to end — the store's release interrupts it — so a test's temporary directory is not deleted under it.
     */
    fun shutDown() {
        frozen?.countDown() ?: run { frozen = CountDownLatch(0) }
        val deadline = System.currentTimeMillis() + TERMINATION_WAIT_MS
        synchronized(lock) {
            while (running.get() > 0) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return
                lock.wait(left)
            }
        }
    }

    private companion object {

        // Real time for an interrupted segment load to return: a synthetic segment is a few kilobytes, so
        // this is reached only by a load stuck on something else, and then the test goes on without it.
        const val TERMINATION_WAIT_MS = 5_000L
    }
}
