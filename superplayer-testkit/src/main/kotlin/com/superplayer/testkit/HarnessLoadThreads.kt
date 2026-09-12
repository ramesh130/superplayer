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

import androidx.media3.exoplayer.util.ReleasableExecutor
import com.google.common.base.Supplier
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The loading threads under one harness-built player, supplied to Media3 in place of its own so
 * that the harness can tell when a load task has *finished* — completion posted to the playback
 * thread and all — rather than only when its transfer closed.
 *
 * The shape mirrors Media3's default exactly: one single-thread executor per loader, created when
 * the loader asks and shut down when the loader releases it, so playlist and chunk loads run in
 * parallel here as they do in production. What is added is bookkeeping: every task is counted into
 * [wait] from the moment it is submitted until the moment it returns, and
 * `HarnessClockWait.transfersHaveCaughtUp` will not report the clock caught up while one is
 * neither finished nor waiting on the clock. That is the whole of what closes the race
 * `EngineConfiguration.loadExecutor` describes.
 */
internal class HarnessLoadThreads(private val wait: HarnessClockWait) : Supplier<ReleasableExecutor> {

    private val created = AtomicInteger()

    override fun get(): ReleasableExecutor {
        val index = created.incrementAndGet()
        val service = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "superplayer-harness-load-$index") }
        val counting = Executor { task ->
            wait.loadTaskSubmitted()
            try {
                service.execute {
                    try {
                        task.run()
                    } finally {
                        wait.loadTaskFinished()
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                wait.loadTaskFinished()
                throw rejected
            }
        }
        return ReleasableExecutor.from(counting) { service.shutdown() }
    }
}
