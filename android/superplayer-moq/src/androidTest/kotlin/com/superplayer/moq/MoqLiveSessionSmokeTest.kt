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

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.superplayer.core.EncodedFrame
import com.superplayer.core.FrameSink
import com.superplayer.core.RealtimeTrack
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The first real MoQ session: a QUIC handshake against a public relay, a broadcast subscribed, and
 * frames counted — from a device, with no `SuperPlayer`, no surface and no Compose screen (#367).
 *
 * ## Why this is the repository's only instrumented test
 *
 * `docs/testing.md` bars the device and the network, and every other claim this library makes is
 * reachable without either. Three of Phase 14's are not, and they fail together in ways that are
 * indistinguishable from one another in a demo app:
 *
 *  1. **The bindings load.** `libmoq_ffi.so` is `arm64-v8a` and nothing else, and Robolectric runs
 *     on the host JVM, so `MoqFfiLinkageTest` reaches the *host* `.dylib` and says so. Whether the
 *     Android library loads on an Android runtime is answered here and nowhere else.
 *  2. **QUIC works from this build.** Every check in #366 runs against [ScriptedMoqRelay], which
 *     proves the pump honours its nine obligations and proves nothing whatever about MoQ's relay
 *     protocol.
 *  3. **The address mapping is right.** [UniffiMoqRelay]'s split of a `moq://` URI into a `connect`
 *     URL and a broadcast name is, in that file's own words, *"the one thing in this module no test
 *     here can confirm"* — a derivation from the bindings' signature, cited to nothing, because no
 *     public document states it. **This test is what confirms or corrects it.**
 *
 * ## The artifact is the observation, not the assertion
 *
 * A relay that is up today may be down tomorrow, so everything learned is written to logcat under
 * [TAG] whether the run passes or fails, and `devicelab/moq-smoke` scrapes it into a report. A
 * failure that reports *which* of the three unknowns broke is worth more than a green run that
 * records nothing — so the assertions below are deliberately placed after the reporting, and each
 * failure message names what was reached and what was not.
 *
 * ## What it does not do
 *
 * It plays nothing. There is no player, no renderer and no decoder anywhere in it, which is
 * ADR-0018 rule 1 at its narrowest: what is under test is a transport handing frames to a sink.
 * Whether those frames *decode* is #353's, in the demo. Nor does it measure latency: the emulator's
 * user-mode NAT and the host's own scheduling sit between the relay and this code, so any number
 * taken here would describe the host rather than the link — `docs/testing.md`'s rule about a
 * measurement whose stand-in cannot support it, applied before the number is taken rather than
 * after it is published.
 */
@RunWith(AndroidJUnit4::class)
class MoqLiveSessionSmokeTest {

    /**
     * Connects, subscribes, counts frames over a bounded window, then cancels and checks nothing
     * was left running.
     *
     * One test rather than four, because the four acceptance criteria #367 lists are four readings
     * of **one** session and splitting them would open four QUIC sessions against a public relay to
     * learn what one can say. The reporting is what keeps them individually legible.
     */
    @Test
    fun aLiveBroadcastDeclaresTracksDeliversFramesAndStopsWhenCancelled() {
        val broadcast = argument("broadcast", DEFAULT_BROADCAST)
        val uri = Uri.parse(broadcast)
        val observations = Observations()

        observations.record("broadcast", broadcast)
        observations.record("relay.authority", uri.encodedAuthority.orEmpty())
        observations.record("broadcast.name", uri.encodedPath.orEmpty().trimStart('/'))
        observations.record("device.abis", android.os.Build.SUPPORTED_ABIS.joinToString(","))
        observations.record("device.api", android.os.Build.VERSION.SDK_INT.toString())

        val source = MoqFrameSource(uri)
        val sink = RecordingSink()

        try {
            source.subscribe(sink)

            // Two separate waits, because the two failures they separate are the two this test
            // exists to tell apart: a session that never connected declares nothing, while a
            // session that connected and subscribed to a broadcast nobody is publishing to declares
            // tracks and then delivers no frame.
            //
            // The latch is released by a declaration **or** by a terminal callback, so what is
            // waited on and what is reported are deliberately two different readings. Reporting the
            // latch would say "declared" for a session that failed to connect, which is the exact
            // ambiguity this test exists to remove — and did, on its first run.
            sink.tracks.await(CONNECT_BOUND_MS, TimeUnit.MILLISECONDS)
            val declared = sink.sawOnTracks()
            observations.record("tracks.declared", if (declared) "yes" else "no")

            if (!declared) {
                observations.record("failure", sink.failureDescription() ?: "no declaration and no error within ${CONNECT_BOUND_MS}ms")

                // Asked only on the failing path, and that is the whole of its cost: a run that
                // subscribes successfully opens one session, which is what #367 asked for. A run
                // that does not is already a finding, and the finding is worth more with the
                // relay's own answer in it than without.
                val discovery = reportRelayAndMappings(uri, observations)
                observations.emit()

                // A relay that answered and is publishing nothing is an **absent precondition**,
                // not a defect, and the two are encoded differently on purpose. There is no
                // broadcast to subscribe to, so there is no observation to be made and nothing this
                // repository could change would make one — while a broadcast that exists and then
                // fails to declare tracks is exactly the defect this test is for. `assumeTrue`
                // marks the first as skipped, which is what a harness pointed at a public relay has
                // to do to stay useful: a run that fails whenever the internet is quiet is a run
                // whose red gets ignored, and then the real red gets ignored with it.
                //
                // The finding is emitted **above** this line either way, so a skip is still a
                // report — which is #367's "the artifact is the observation and not the assertion",
                // taken literally.
                val relayIsEmpty = discovery.isEmptyAnswer
                assumeTrue(
                    "The relay at ${uri.encodedAuthority} answered and announced no broadcast under " +
                        "\"${namespaceOf(uri)}\", so there was nothing to subscribe to. The session itself " +
                        "connected: see the $TAG block. Point this at a live broadcast with " +
                        "--broadcast to observe frames.",
                    !relayIsEmpty,
                )

                fail(
                    "No tracks were declared within ${CONNECT_BOUND_MS}ms. The session reported " +
                        "${sink.failureDescription() ?: "nothing"}, and asking the relay what it " +
                        "announces under \"${namespaceOf(uri)}\" said: ${discovery.describe()}. " +
                        "The logcat block under $TAG has what was reached.",
                )
            }

            reportTracks(sink.declaredTracks(), observations)

            // A count that *advances* rather than a count that is non-zero: a single frame could be
            // a catalog artefact, and what #367 asks for is a subscription that is delivering.
            val firstReading = sink.frames.get()
            val advanced = sink.awaitFrames(atLeast = FRAMES_WANTED, boundMs = FRAMES_BOUND_MS)
            val secondReading = sink.frames.get()

            observations.record("frames.atFirstReading", firstReading.toString())
            observations.record("frames.afterWindow", secondReading.toString())
            observations.record("frames.windowMs", FRAMES_BOUND_MS.toString())
            observations.record("frames.bytesTotal", sink.bytes.get().toString())
            observations.record("frames.keyFrames", sink.keyFrames.get().toString())
            observations.record("frames.perTrack", sink.perTrackCounts())

            val statistics = source.statistics()
            observations.record(
                "session.statistics",
                statistics?.let { "rttUs=${it.roundTripTimeUs} bytesReceived=${it.bytesReceived} packetsReceived=${it.packetsReceived}" }
                    ?: "none polled yet",
            )

            if (!advanced) {
                observations.record("failure", sink.failureDescription() ?: "fewer than $FRAMES_WANTED frames in ${FRAMES_BOUND_MS}ms")
                observations.emit()
                fail(
                    "The broadcast declared ${sink.declaredTracks().size} track(s) but delivered " +
                        "$secondReading frame(s) in ${FRAMES_BOUND_MS}ms, wanting $FRAMES_WANTED. " +
                        "The session connected, so this is a publisher that is not sending rather " +
                        "than a transport that does not work. Reported: ${sink.failureDescription() ?: "nothing"}.",
                )
            }
        } finally {
            // In `finally` because a session left open outlives the test process on a device and
            // holds a subscription against a public relay. The cancellation *assertions* are below,
            // outside it, so a failure above reports its own cause rather than this one.
            source.cancel()
        }

        // Obligation 9, against a real transport for the first time: `cancel()` returns only once
        // no further callback can arrive and every thread it started has been joined. Under
        // `check` this is asserted against a fake whose `close` is a flag; here the threads are
        // parked in a blocking `next()` across the FFI boundary, which is the case
        // `MoqTrackStream.close`'s KDoc says the unblocking obligation exists for.
        val survivors = threadsNamedFor(uri)
        observations.record("threads.afterCancel", if (survivors.isEmpty()) "none" else survivors.joinToString(","))

        val framesAtCancel = sink.frames.get()
        Thread.sleep(QUIET_AFTER_CANCEL_MS)
        val framesAfterQuiet = sink.frames.get()
        observations.record("frames.afterCancel", "$framesAtCancel then $framesAfterQuiet")

        if (argument("probeMappings", "false") == "true") {
            // After the real subscription has been cancelled, so the two never share a session.
            reportRelayAndMappings(uri, observations)
        }

        observations.emit()

        assertTrue(
            "cancel() returned but these threads are still running: $survivors",
            survivors.isEmpty(),
        )
        assertTrue(
            "A frame arrived after cancel() returned: $framesAtCancel then $framesAfterQuiet",
            framesAfterQuiet == framesAtCancel,
        )
    }

    /**
     * Records what the live publisher declared, and in particular **which codec-description branch
     * it used** — #367's fourth criterion and the open half of #340's finding.
     *
     * #340 observed `avc1` and `avc3` from moq's own code driven in process, with no packet crossing
     * a network, and its caveat 1 named a real subscriber as the corroboration that remained open.
     * This is that corroboration. The branch is read off [RealtimeTrack.codecConfiguration] rather
     * than off the codec string, because the *shape* is what ADR-0018 rule 4 keys on and reading it
     * from the string here would assume exactly the rule under observation.
     */
    private fun reportTracks(tracks: List<RealtimeTrack>, observations: Observations) {
        observations.record("tracks.count", tracks.size.toString())
        tracks.forEachIndexed { index, track ->
            val configuration = when (val declared = track.codecConfiguration) {
                is RealtimeTrack.CodecConfiguration.InBand -> "in-band (no description; parameter sets in the payload)"

                is RealtimeTrack.CodecConfiguration.Record ->
                    "out-of-band record, ${declared.bytes.size} B, opening ${declared.bytes.take(8).hex()}"
            }
            observations.record("track[$index].codec", track.codec)
            observations.record("track[$index].configuration", configuration)
        }
    }

    /**
     * Asks the relay what it announces, and asks each candidate address mapping which one routes.
     *
     * Run on the failing path, and on demand through `-e probeMappings true` even when the
     * subscription succeeded — because the interesting comparison is against a broadcast that is
     * **live**, and on that broadcast the failing path is never taken. An experiment reachable only
     * when the thing under test is broken can compare a working arm against nothing.
     */
    private fun reportRelayAndMappings(uri: Uri, observations: Observations): MoqRelayDiscovery.Announced {
        val prefix = uri.encodedPath.orEmpty().trimStart('/').substringBefore('/')
        // The **corrected** mapping, which is what `UniffiMoqRelay` now uses. Asking the
        // bare authority here — as this probe did until the review caught it — reports a
        // live relay as empty and silently skips the run, because the namespace is in the
        // session URL and a listing taken without it sees nothing.
        val discovery = MoqRelayDiscovery.announcedUnder(
            connectUrl = "https://${uri.encodedAuthority.orEmpty()}" +
                if (prefix.isEmpty()) "" else "/$prefix",
            prefix = "",
            timeoutMs = DISCOVERY_BOUND_MS,
        )
        observations.record("relay.announcedUnder[$prefix]", discovery.describe())

        // The address mapping, asked as a question rather than assumed — and asked again
        // after it was corrected, because the reason it stays here is that a relay is the
        // only thing that can answer it. `UniffiMoqRelay` now puts the namespace in the
        // session URL and requests the remainder; the other split is kept as the control
        // that shows why, and a relay that ever answered the other way would show here
        // rather than in a support ticket.
        //
        // Written to report rather than to assert: which one routes is a fact about MoQ,
        // not about this repository, and a test that hard-coded the answer would be
        // re-asserting its own guess.
        observations.record("mapping.candidates", "asking the relay which one routes")
        candidateMappings(uri).forEach { (label, candidate) ->
            val answer = MoqRelayDiscovery.announcedUnder(
                connectUrl = candidate.connectUrl,
                prefix = "",
                timeoutMs = DISCOVERY_BOUND_MS,
            )
            observations.record(
                "mapping[$label].announced",
                "connect=${candidate.connectUrl} -> ${answer.describe()}",
            )
            // Asked as well as listed, because the two can disagree: a name in the announce
            // stream that `requestBroadcast` still refuses means the split is wrong
            // somewhere other than where the listing suggests. Both with and without the
            // announce stream open, since a relay may route only what this session has
            // already been told about.
            // Three arms, because two could not tell the two explanations apart. The
            // first version of this matrix let the listening arm wait and the silent arm
            // not, so "listening routes" and "settling routes" predicted the same result
            // and the difference was read as the former. `settling` is the control that
            // separates them: it waits exactly as long and listens to nothing.
            listOf(
                Arm("immediate", announceFirst = false, settleMs = 0L),
                Arm("settling", announceFirst = false, settleMs = DISCOVERY_BOUND_MS),
                Arm("listening", announceFirst = true, settleMs = DISCOVERY_BOUND_MS),
            ).forEach { arm ->
                observations.record(
                    "mapping[$label].requested(${arm.label})",
                    "broadcast=${candidate.broadcastName} -> " +
                        MoqRelayDiscovery.probeBroadcast(
                            connectUrl = candidate.connectUrl,
                            name = candidate.broadcastName,
                            timeoutMs = DISCOVERY_BOUND_MS,
                            announceFirst = arm.announceFirst,
                            settleMs = arm.settleMs,
                        ),
                )
            }
        }
        return discovery
    }

    /** The one leading path segment a relay publishes under, which the session URL carries. */
    private fun namespaceOf(uri: Uri): String =
        uri.encodedPath.orEmpty().trimStart('/').substringBefore('/')

    /** One arm of the routing experiment: what it waits for, and whether it listens. */
    private data class Arm(val label: String, val announceFirst: Boolean, val settleMs: Long)

    /** One way of splitting a `moq://` URI into the two things the bindings take separately. */
    private data class Mapping(val connectUrl: String, val broadcastName: String)

    /**
     * The ways this URI could be split, in the order they are worth trying.
     *
     * `authority-only` is what [UniffiMoqRelay] does today. `authority-plus-first-segment` is what
     * MoQ's own web player does with the same address, taking the namespace into the session. A
     * URI with no path beyond a single segment collapses the two, and the duplicate is dropped so
     * the report does not show one candidate twice.
     */
    private fun candidateMappings(uri: Uri): List<Pair<String, Mapping>> {
        val authority = uri.encodedAuthority.orEmpty()
        val path = uri.encodedPath.orEmpty().trimStart('/')
        val firstSegment = path.substringBefore('/')
        val rest = path.substringAfter('/', missingDelimiterValue = "")

        val candidates = mutableListOf<Pair<String, Mapping>>(
            "authority-only" to Mapping("https://$authority", path),
        )
        if (rest.isNotEmpty()) {
            candidates += "authority-plus-first-segment" to
                Mapping("https://$authority/$firstSegment", rest)
        }
        return candidates
    }

    /** The threads [MoqFrameSource] names after the broadcast's host, if any are still alive. */
    private fun threadsNamedFor(uri: Uri): List<String> {
        val host = uri.host.orEmpty()
        if (host.isEmpty()) return emptyList()
        return Thread.getAllStackTraces().keys
            .filter { it.isAlive && it.name.endsWith("-$host") }
            .map { it.name }
            .sorted()
    }

    /**
     * An instrumentation argument, or [fallback].
     *
     * The relay is an argument rather than a constant because #367 says in as many words that a
     * relay up today may be down tomorrow, and because the useful next run of this test is against
     * whatever relay the person running it can reach.
     */
    private fun argument(name: String, fallback: String): String =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * What the run learned, in the order it learned it, emitted as one logcat block.
     *
     * One block rather than a line per observation, because logcat interleaves writers and the
     * scraper's job should not be reassembling a report out of a shared buffer. The markers are
     * what `devicelab/moq-smoke` cuts between.
     */
    private class Observations {
        private val entries = mutableListOf<Pair<String, String>>()

        fun record(key: String, value: String) {
            entries += key to value
        }

        fun emit() {
            val report = buildString {
                appendLine(BEGIN)
                entries.forEach { (key, value) -> appendLine("$key = $value") }
                append(END)
            }
            // Split by line: logcat truncates a single message at about 4 kB, and a report that
            // loses its END marker is one the scraper reports as a crashed run.
            report.lineSequence().forEach { Log.i(TAG, it) }
        }
    }

    /**
     * A [FrameSink] that counts and keeps nothing it was handed.
     *
     * Payloads are **not** retained — only their length is read — because obligation 8 lets a
     * transport reuse a payload buffer once the callback returns, and a sink that kept the arrays
     * would be measuring its own defect. That obligation is asserted under `check` by
     * `FrameSourceConformance`; here it is simply honoured.
     */
    private class RecordingSink : FrameSink {
        val tracks = CountDownLatch(1)
        val frames = AtomicInteger()
        val bytes = java.util.concurrent.atomic.AtomicLong()
        val keyFrames = AtomicInteger()

        private val declared = AtomicReference<List<RealtimeTrack>>(emptyList())

        /**
         * Whether [onTracks] was called, as distinct from whether the latch was released.
         *
         * A separate flag rather than `declaredTracks().isNotEmpty()`, because an empty declaration
         * is a state the seam permits a transport to be refused for and a reading that conflated
         * the two would report a refusal as a publisher with no tracks.
         */
        private val onTracksCalled = java.util.concurrent.atomic.AtomicBoolean()
        private val perTrack = java.util.concurrent.ConcurrentHashMap<Int, AtomicInteger>()
        private val failure = AtomicReference<Throwable?>()
        private val ended = java.util.concurrent.atomic.AtomicBoolean()
        private val progress = java.lang.Object()

        override fun onTracks(tracks: List<RealtimeTrack>) {
            onTracksCalled.set(true)
            declared.set(tracks)
            this.tracks.countDown()
        }

        override fun onFrame(frame: EncodedFrame) {
            frames.incrementAndGet()
            bytes.addAndGet(frame.payload.size.toLong())
            if (frame.keyFrame) keyFrames.incrementAndGet()
            perTrack.computeIfAbsent(frame.trackIndex) { AtomicInteger() }.incrementAndGet()
            synchronized(progress) { progress.notifyAll() }
        }

        override fun onEnded() {
            ended.set(true)
            tracks.countDown()
            synchronized(progress) { progress.notifyAll() }
        }

        override fun onError(cause: Throwable) {
            failure.set(cause)
            tracks.countDown()
            synchronized(progress) { progress.notifyAll() }
        }

        fun declaredTracks(): List<RealtimeTrack> = declared.get()

        fun sawOnTracks(): Boolean = onTracksCalled.get()

        /**
         * The failure with its **whole cause chain**, innermost included.
         *
         * `UniffiMoqRelay` wraps everything it catches in one `IOException` naming the URI, so the
         * outermost message says only that a session could not be opened — which is true of a relay
         * that is down, a broadcast name nobody publishes, a TLS refusal and a wrong address mapping
         * alike. The chain is where those separate, and the first run of this test proved the point
         * by reporting the wrapper alone and settling nothing.
         */
        fun failureDescription(): String? = failure.get()?.let { chainOf(it) }
            ?: if (ended.get()) "the subscription ended" else null

        private fun chainOf(cause: Throwable): String = buildString {
            var current: Throwable? = cause
            var depth = 0
            while (current != null && depth < MAX_CAUSE_DEPTH) {
                if (depth > 0) append(" <- ")
                append("${current::class.java.name}: ${current.message}")
                current = current.cause.takeIf { it !== current }
                depth++
            }
        }

        /**
         * Waits on the sink's own progress rather than sleeping for the window.
         *
         * A sleep would make the bound the *measurement* — a slow relay and a dead one would be
         * indistinguishable until the whole window elapsed — and it is `docs/testing.md`'s
         * determinism rule arriving in the one place here that has a real clock: wait for the
         * event, bound the wait, and report elapsed time rather than assert on it.
         */
        fun awaitFrames(atLeast: Int, boundMs: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMs)
            synchronized(progress) {
                while (frames.get() < atLeast && failure.get() == null && !ended.get()) {
                    val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (remaining <= 0) break
                    progress.wait(remaining)
                }
            }
            return frames.get() >= atLeast
        }

        fun perTrackCounts(): String =
            perTrack.entries.sortedBy { it.key }.joinToString(" ") { "track${it.key}=${it.value.get()}" }
    }

    private companion object {

        /** What `devicelab/moq-smoke` greps for. */
        const val TAG = "SuperPlayerMoqSmoke"
        const val BEGIN = "--- moq-smoke begin ---"
        const val END = "--- moq-smoke end ---"

        /**
         * The relay this runs against unless one is named.
         *
         * `cdn.moq.dev` is MoQ's own public deployment and `anon` its unauthenticated namespace —
         * the address #340's caveat 1 named as the corroboration that remained open. It is a
         * **default and not a fixture**: nothing here controls what is published there, so a run
         * that finds no broadcast is a finding about that relay rather than a failure of this code,
         * which is what the two separated waits above exist to say.
         */
        const val DEFAULT_BROADCAST = "moq://cdn.moq.pro/anon/catshark.hang"

        /**
         * How long a connect, a catalog and a declaration are waited for: **15 s**.
         *
         * A QUIC handshake to a public relay plus the publisher's first catalog, over an emulator's
         * user-mode NAT, on a host that may be building at the same time. Chosen wide because the
         * failure this bound produces — "no tracks" — is the ambiguous one this test exists to
         * disambiguate, and a bound too tight would manufacture it.
         */
        const val CONNECT_BOUND_MS = 15_000L

        /**
         * How long frames are collected for, and how many make a count that has *advanced*.
         *
         * Thirty frames is about a second of a 30 fps video track and several seconds of an audio
         * one, so it is a subscription that is running rather than one that emitted a keyframe and
         * stopped. Ten seconds is the bound around it, which is generous by design: what is being
         * established is that frames flow at all, and a rate is not being measured here.
         */
        const val FRAMES_WANTED = 30
        const val FRAMES_BOUND_MS = 10_000L

        /**
         * How long the sink is watched after `cancel()` returned: **500 ms**.
         *
         * Obligation 9 makes a callback after `cancel()` returns impossible rather than unlikely, so
         * any value proves the point; this one is long enough for a frame already crossing the FFI
         * boundary to have arrived and short enough not to pad the run.
         */
        const val QUIET_AFTER_CANCEL_MS = 500L

        /** How far a reported cause chain is followed. Deep enough for FFI wrapping, bounded so a
         *  cycle a foreign runtime produced cannot hang the report. */
        const val MAX_CAUSE_DEPTH = 8

        /**
         * How long the announce stream is waited on for each broadcast it reports: **3 s**.
         *
         * Per announcement and not for the whole listing, because a live announce stream never
         * ends — it reports broadcasts as they start and stop — so what bounds the read is a quiet
         * period rather than a total. Short, because this runs only on a path that has already
         * failed and its job is to add a fact to a report rather than to keep trying.
         */
        const val DISCOVERY_BOUND_MS = 3_000L

        fun List<Byte>.hex(): String = joinToString(" ") { "%02x".format(it) }
    }
}
