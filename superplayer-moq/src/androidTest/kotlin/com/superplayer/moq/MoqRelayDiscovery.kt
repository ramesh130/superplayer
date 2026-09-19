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

package com.superplayer.moq

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.moq.MoqClient

/**
 * What a relay says is being published under a prefix, asked directly through the bindings.
 *
 * ## Why this exists, and why it is test-only
 *
 * It is **not** part of the transport and must not become part of it. `MoqFrameSource` subscribes to
 * the broadcast a consumer named, because a player is handed a `MediaRequest` naming content and
 * discovering what else a relay carries is not a playback concern — ADR-0018 rule 7 defers even
 * *rendition* selection, and choosing a broadcast is a larger version of the same deferral.
 *
 * What it is for is the difference between two reports. `requestBroadcast` answers `unroutable` for
 * a name nobody publishes, and on its own that sentence cannot distinguish a relay that is down, a
 * relay that is up and empty, and a name that is simply wrong — which is the first thing #367's run
 * ran into: `moq://cdn.moq.dev/anon` is a **namespace** and not a broadcast, and the failure said
 * only `unroutable`. Listing what is announced turns that into a finding with a fact in it.
 *
 * It reaches the bindings directly rather than through [MoqRelay], deliberately: that seam carries
 * the four verbs the frame pump needs and nothing else, and widening it so a test could enumerate
 * broadcasts would put a method on the production interface that production code never calls.
 */
internal object MoqRelayDiscovery {

    /**
     * The broadcast paths announced under [prefix] at [connectUrl], or an empty list.
     *
     * Bounded by [timeoutMs] rather than run to completion, because an announce stream on a live
     * relay does not end: it stays open and reports broadcasts as they start and stop. What is
     * wanted here is a snapshot, so the stream is read until it goes quiet for the bound and then
     * cancelled.
     *
     * Never throws. A discovery that fails is reported as the absence of an answer, since its caller
     * is already reporting a failure and a second exception thrown from the diagnostic would replace
     * the finding with its own.
     */
    fun announcedUnder(connectUrl: String, prefix: String, timeoutMs: Long): Result =
        try {
            runBlocking {
                MoqClient().use { client ->
                    val session = client.connect(connectUrl)
                    session.use {
                        val announced = session.consumer().announced(prefix)
                        val paths = mutableListOf<String>()
                        announced.use {
                            while (paths.size < MAX_REPORTED) {
                                val announcement =
                                    withTimeoutOrNull(timeoutMs) { announced.next() } ?: break
                                announcement.use { paths += it.path() }
                            }
                            announced.cancel()
                        }
                        Result.Answered(paths.toList())
                    }
                }
            }
        } catch (failure: Throwable) {
            Result.Failed("${failure::class.java.name}: ${failure.message}")
        }

    /** What the announce stream said, as something that cannot throw at the reporting site. */
    internal sealed class Result {
        /** The relay answered; [paths] may legitimately be empty, which is itself the finding. */
        data class Answered(val paths: List<String>) : Result()

        /** The relay could not be asked at all. */
        data class Failed(val description: String) : Result()

        fun describe(): String = when (this) {
            is Answered ->
                if (paths.isEmpty()) {
                    "the relay answered and is publishing nothing under this prefix"
                } else {
                    "${paths.size} announced: ${paths.joinToString(", ")}"
                }

            is Failed -> "could not be asked: $description"
        }
    }

    /**
     * How many announcements are reported before the stream is cancelled.
     *
     * A public relay's anonymous namespace is whatever its visitors left running, which on a busy
     * day is more rows than a report wants. Enough to show the shape, bounded so the finding stays
     * readable.
     */
    private const val MAX_REPORTED = 12
}
