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

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.moq.MoqAnnounced
import uniffi.moq.MoqClient
import uniffi.moq.MoqOriginConsumer

/**
 * What a relay says, asked directly through the bindings, for a report rather than for an assertion.
 *
 * ## Why this exists, and why it is test-only
 *
 * It is **not** part of the transport and must not become part of it. `MoqFrameSource` subscribes to
 * the broadcast a consumer named, because a player is handed a `MediaRequest` naming content and
 * discovering what else a relay carries is not a playback concern — ADR-0018 rule 7 defers even
 * *rendition* selection, and choosing a broadcast is a larger version of the same deferral.
 *
 * What it is for is the difference between reports that "unroutable" cannot tell apart: a relay that
 * is down, a relay that is up and empty, a name that is wrong, and a session that is wrong.
 *
 * It reaches the bindings directly rather than through [MoqRelay], deliberately: that seam carries
 * the four verbs the frame pump needs, and widening it so a test could enumerate broadcasts would
 * put a method on a production interface that production code never calls.
 *
 * ## Every probe is an experiment, so it varies one thing
 *
 * [probeBroadcast] takes **`settleMs` and `announceFirst` separately**, and that separation is the
 * whole point of this file's second revision. The first one folded them together — the announcing
 * arm also waited, the silent arm did not — so a difference between the two was equally well
 * explained by the waiting, and the conclusion drawn from it ("a relay routes only to a session that
 * is listening") was not supported by the comparison that produced it. Varying one thing at a time
 * is the difference between an observation and a story about one.
 */
internal object MoqRelayDiscovery {

    /**
     * The broadcast paths announced under [prefix] at [connectUrl].
     *
     * Bounded by [timeoutMs] per announcement rather than run to completion, because an announce
     * stream on a live relay does not end: it stays open and reports broadcasts as they start and
     * stop. What is wanted here is a snapshot, so the stream is read until it goes quiet for the
     * bound and then cancelled.
     *
     * Never throws. A discovery that fails is reported as the absence of an answer, since its caller
     * is already reporting a failure and a second exception thrown from a diagnostic would replace
     * the finding with its own.
     */
    fun announcedUnder(connectUrl: String, prefix: String, timeoutMs: Long): Announced =
        try {
            runBlocking {
                MoqClient().use { client ->
                    client.connect(connectUrl).use { session ->
                        val announced = session.consumer().announced(prefix)
                        try {
                            val paths = mutableListOf<String>()
                            while (paths.size < MAX_REPORTED) {
                                val announcement =
                                    withTimeoutOrNull(timeoutMs) { announced.next() } ?: break
                                announcement.use { paths += it.path() }
                            }
                            Announced.Paths(paths.toList())
                        } finally {
                            announced.release()
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            Announced.Failed(describe(failure))
        }

    /**
     * Tries to **request** [name] at [connectUrl] and read its catalog, reporting how far it got.
     *
     * Separate from [announcedUnder] because the two answer different questions and a live relay
     * showed they can disagree: a broadcast can be listed in the announce stream and still be
     * refused by `requestBroadcast`.
     *
     * The two things that might make a request route are varied **independently**:
     *
     * - [settleMs] waits that long after connecting and before requesting, announcing nothing. This
     *   is the control the first version of this file lacked.
     * - [announceFirst] opens the announce stream on the same consumer before requesting, and reads
     *   one announcement from it (itself bounded by [settleMs] where that is non-zero, so the two
     *   arms wait the same amount and differ only in whether anything listened).
     *
     * One [MoqOriginConsumer] is used for both, because that is what `UniffiMoqRelay` does and an
     * experiment on a different structure would not be about the code it is meant to justify.
     */
    fun probeBroadcast(
        connectUrl: String,
        name: String,
        timeoutMs: Long,
        announceFirst: Boolean = false,
        settleMs: Long = 0L,
    ): String =
        try {
            runBlocking {
                MoqClient().use { client ->
                    val session = withTimeoutOrNull(timeoutMs) { client.connect(connectUrl) }
                        ?: return@runBlocking "connect timed out after ${timeoutMs}ms"
                    session.use {
                        val consumer = session.consumer()
                        val listening = if (announceFirst) {
                            runCatching { consumer.announced("") }.getOrElse { failure ->
                                // Reported rather than swallowed: an arm that could not listen is
                                // not a silent arm and not a listening one, and labelling it either
                                // would make the comparison meaningless.
                                return@use "could not open the announce stream: ${describe(failure)}"
                            }
                        } else {
                            null
                        }
                        try {
                            if (listening != null) {
                                if (settleMs > 0) withTimeoutOrNull(settleMs) { listening.next() }?.close()
                            } else if (settleMs > 0) {
                                delay(settleMs)
                            }
                            requestAndRead(consumer, name, timeoutMs)
                        } finally {
                            listening?.release()
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            "threw: ${describe(failure)}"
        }

    /** The request and catalog half, so the arms above differ only in what precedes it. */
    private suspend fun requestAndRead(
        consumer: MoqOriginConsumer,
        name: String,
        timeoutMs: Long,
    ): String {
        val broadcast = try {
            withTimeoutOrNull(timeoutMs) { consumer.requestBroadcast(name) }
                ?: return "connected, then requestBroadcast timed out"
        } catch (failure: Throwable) {
            return "connected, but requestBroadcast refused: ${describe(failure)}"
        }
        return broadcast.use {
            val catalogs = withTimeoutOrNull(timeoutMs) { broadcast.subscribeCatalog() }
                ?: return@use "requested, then subscribeCatalog timed out"
            catalogs.use {
                val catalog = withTimeoutOrNull(timeoutMs) { catalogs.next() }
                    ?: return@use "catalog subscribed, but none published within ${timeoutMs}ms"
                "catalog read: ${catalog.video.size} video, ${catalog.audio.size} audio " +
                    "(video=${catalog.video.keys.joinToString("|")} audio=${catalog.audio.keys.joinToString("|")})"
            }
        }
    }

    /** What an announce listing said, as something that cannot throw at the reporting site. */
    internal sealed class Announced {
        /** The relay answered; [paths] may legitimately be empty, which is itself the finding. */
        data class Paths(val paths: List<String>) : Announced()

        /** The relay could not be asked at all. */
        data class Failed(val description: String) : Announced()

        val isEmptyAnswer: Boolean get() = this is Paths && paths.isEmpty()

        fun describe(): String = when (this) {
            is Paths ->
                if (paths.isEmpty()) {
                    "the relay answered and is publishing nothing under this prefix"
                } else {
                    "${paths.size} announced: ${paths.joinToString(", ")}"
                }

            is Failed -> "could not be asked: $description"
        }
    }

    /**
     * Cancel, then close — the order `UniffiBroadcastSession.close` argues, because cancelling is
     * what unblocks a reader parked in `next` and closing alone would leave it waiting.
     */
    private fun MoqAnnounced.release() {
        runCatching { cancel() }
        closeQuietly()
    }

    private fun describe(failure: Throwable): String =
        "${failure::class.java.simpleName}: ${failure.message}"

    /**
     * How many announcements are reported before the stream is cancelled.
     *
     * A public relay's anonymous namespace is whatever its visitors left running, which on a busy
     * day is more rows than a report wants. Enough to show the shape, bounded so the finding stays
     * readable.
     */
    private const val MAX_REPORTED = 12
}
