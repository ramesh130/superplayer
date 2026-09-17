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
import com.superplayer.core.DownloadEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The [DownloadEnvironment] a [PlaybackHarness] hands a download: the transport a harness-built player
 * would load the same content through, and loading threads the harness owns.
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
) : DownloadEnvironment()

/**
 * The thread a download's segment loads run on, counted into [wait] as [HarnessLoadThreads] counts a
 * player's, and able to stop taking work — which is how [PlaybackHarness.processDeath] freezes a
 * download at a moment without asking the download to cooperate.
 *
 * **One thread, not one per loader.** Media3's segment downloader hands each segment to the executor it
 * was given and waits for it, and with one thread segments are fetched in manifest order on every run,
 * which is what makes two runs of one download the same sequence of requests. Parallel segment
 * fetching is a throughput question for Phase 10, not a behaviour a Phase 7 test asserts on.
 *
 * **So the loads it holds count as one.** A load queued behind another on the one thread cannot act on
 * the time that passes, so counting it into [wait] as a second active load would keep the clock from
 * moving while the load ahead of it waits on that clock — which is every store downloading two items
 * with a delay injected (#244). From the first submission until the last load has returned, [wait] sees
 * one task, which is what the thread is doing.
 */
internal class HarnessDownloadLoads(private val wait: HarnessClockWait) : Executor {

    /** Loads submitted and not yet returned, queued or running. */
    private val pending = AtomicInteger()

    private val service: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "superplayer-harness-download-${created.incrementAndGet()}").apply { isDaemon = true }
    }

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
        submitted()
        // Checked again once counted: a freeze that landed between the check above and the count would
        // otherwise see no load in flight, start its copy, and have this one begin writing under it.
        if (frozen != null) {
            finished()
            return execute(task)
        }
        try {
            service.execute {
                try {
                    task.run()
                } finally {
                    finished()
                }
            }
        } catch (rejected: RejectedExecutionException) {
            finished()
            throw rejected
        }
    }

    private fun submitted() {
        if (pending.getAndIncrement() == 0) wait.loadTaskSubmitted()
    }

    private fun finished() {
        if (pending.decrementAndGet() == 0) wait.loadTaskFinished()
    }

    /** Takes no new work; a task already running finishes. */
    fun freeze() {
        if (frozen == null) frozen = CountDownLatch(1)
    }

    /**
     * Lets every caller held by [freeze] go, each refused, and stops the thread — waiting a bounded time
     * for an interrupted load to end, so a test's temporary directory is not deleted under it.
     */
    fun shutDown() {
        frozen?.countDown() ?: run { frozen = CountDownLatch(0) }
        service.shutdownNow()
        service.awaitTermination(TERMINATION_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private companion object {
        val created = AtomicInteger()

        // Real time for an interrupted segment load to return: a synthetic segment is a few kilobytes, so
        // this is reached only by a load stuck on something else, and then the test goes on without it.
        const val TERMINATION_WAIT_MS = 5_000L
    }
}
