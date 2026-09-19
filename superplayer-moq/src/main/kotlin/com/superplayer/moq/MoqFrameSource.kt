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
import com.superplayer.core.EncodedFrame
import com.superplayer.core.FrameSink
import com.superplayer.core.FrameSource
import uniffi.moq.MoqMediaFrame
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A MoQ broadcast, as SuperPlayer's realtime seam sees it: subscribe, receive encoded frames, cancel.
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setRealtime(Realtime.transport(MoqFrameSource.SCHEME) { uri -> MoqFrameSource(uri) })
 *     .build()
 *
 * player.setMediaRequest(
 *     MediaRequest.Builder("studio-a").addSource(Uri.parse("moq://relay.example/studio-a")).build(),
 * )
 * ```
 *
 * This is the whole of this module's bridge, and the test that it is a bridge rather than a leak is
 * that **no signature here names a Media3 type**: what it implements is `superplayer-core`'s public
 * [FrameSource], and this module is deliberately not a Kotlin friend of core (ADR-0018 rule 11).
 * Nothing here decodes and nothing here parses a container — the bindings strip the framing below
 * the FFI boundary (ADR-0018 rule 3, #340), so a payload arrives as codec bitstream and is handed on
 * untouched.
 *
 * ## What one subscription does
 *
 * One connect thread, and one pump thread per track. The connect thread opens the session, reads the
 * catalog **once**, turns it into tracks through [MoqCatalogTracks], subscribes to each of them,
 * declares them all in a single [FrameSink.onTracks], and then hands each track to a pump. Each pump
 * pulls frames until its track ends, the source is cancelled, or something fails.
 *
 * A thread per track rather than one pump for all of them is forced by the seam being a **blocking
 * pull**: a single pump asking track 0 for its next frame cannot also be asking track 1, so audio
 * would arrive only as fast as video did. What that costs is obligation 1 — every call on the sink
 * ordered against every other — and it is paid by [delivery], the one monitor every callback goes
 * through. That is the happens-before the obligation permits rather than the single delivering
 * thread it prefers, and it is what `FrameSourceConformance`'s overlap check reads.
 *
 * ## The three obligations this class makes true rather than trusts
 *
 * **Obligation 2, tracks declared once before the first frame**, is structural: no pump exists until
 * [FrameSink.onTracks] has returned, so there is no arrangement of threads in which a frame precedes
 * the declaration.
 *
 * **Obligation 3, each track starts with a keyframe**, is enforced per track by [TrackPump] rather
 * than assumed of the publisher. A MoQ subscription is entitled to begin part-way through a group,
 * and a decoder fed a dependent frame whose reference this subscription never received renders
 * corruption and reports nothing — the "it connects and the picture is garbage" failure the
 * obligation exists to prevent.
 *
 * **Obligation 9, cancellation stops delivery**, is [cancel]'s own paragraph.
 *
 * ## What reaches the consumer when something fails
 *
 * Everything, as [FrameSink.onError]. Nothing here throws out of [subscribe] or off a pump thread: a
 * throw on a thread the player does not own is lost, and a session that failed while the player
 * waits in `STATE_BUFFERING` for ever is the worst shape a bug report can take. The first failure
 * wins and the rest are dropped, because a subscription ends once.
 */
public class MoqFrameSource internal constructor(
    private val uri: Uri,
    private val relay: MoqRelay,
) : FrameSource {

    /** Opens [uri] over MoQ's own bindings, which is what a consumer wants and what #367 first runs. */
    public constructor(uri: Uri) : this(uri, UniffiMoqRelay)

    /**
     * The monitor every callback on the sink is made under (obligation 1).
     *
     * It also carries [terminated], because "has this subscription ended" and "may this callback be
     * made" are one question asked at one moment: a pump reaching the end of its track at the
     * instant another pump fails must produce one terminal callback and not two.
     */
    private val delivery = Any()

    /** Whether a terminal callback has been made. Read and written under [delivery] alone. */
    private var terminated = false

    /**
     * The lifecycle monitor: what has been opened, and whether [cancel] has run.
     *
     * Separate from [delivery] deliberately. A pump inside [FrameSink.onFrame] holds [delivery] for
     * as long as SuperPlayer takes to write into its sample queue, and a [cancel] that had to wait
     * for that before it could begin closing the session would make the playback thread pay a
     * frame's write for every cancellation.
     */
    private val lifecycle = Any()

    private val cancelled = AtomicBoolean(false)
    private var session: MoqBroadcastSession? = null
    private val streams = mutableListOf<MoqTrackStream>()
    private val threads = mutableListOf<Thread>()

    override fun subscribe(sink: FrameSink) {
        // Connecting happens on a thread of this source's own rather than on the caller's: the
        // caller is the thread preparing the player, and a QUIC handshake against a relay on the
        // other side of the world is not a thing to block it on. The seam's KDoc permits either,
        // and this is the half of that permission a real transport has to take.
        start("moq-connect", sink) { connect(sink) }
    }

    /**
     * Ends delivery: closes the streams, closes the session, and **joins every thread this source
     * started** before returning (obligation 9).
     *
     * The join is the whole of it. Closing the session stops the bytes, but a pump already inside
     * [FrameSink.onFrame] would still return from it and go round its loop once more, and the player
     * releases its sample queues the moment this method returns — so a write that lands afterwards
     * is a write into a queue that is gone, which surfaces as a crash at an unrelated moment in a
     * stack that names nothing of this library. Closing first and joining second is what makes the
     * join finite: a pump parked in a blocking [MoqTrackStream.next] is woken by its stream closing,
     * which is the obligation [MoqTrackStream.close] carries for exactly this reason.
     *
     * Safe to call twice — the second call closes nothing and joins threads that have already
     * finished — and safe to call from any thread, which is what the playback thread needs. A call
     * from one of this source's own threads does not join itself, because a thread joining itself
     * waits for ever; that is not a call this library makes, and deadlocking would be a worse answer
     * than returning.
     */
    override fun cancel() {
        val opened = synchronized(lifecycle) {
            cancelled.set(true)
            val closing: List<AutoCloseable> = streams.toList() + listOfNotNull(session)
            streams.clear()
            session = null
            closing
        }
        opened.forEach { it.closeQuietly() }
        // Read under the monitor and joined outside it, so a pump on its way out can still take
        // `lifecycle` rather than deadlocking against the thread that is waiting for it.
        val running = synchronized(lifecycle) { threads.toList() }
        running.filter { it !== Thread.currentThread() }.forEach { it.join() }
    }

    /** The connect thread's whole body: session, catalog, subscriptions, declaration, pumps. */
    private fun connect(sink: FrameSink) {
        val connected = registerSession(relay.connect(uri)) ?: return
        val declared = MoqCatalogTracks.declaredTracksOf(connected.catalog())
        val pumps = declared.mapIndexed { index, track ->
            TrackPump(index, registerStream(connected.subscribe(track.trackName, track.container)) ?: return)
        }
        // Declared before any pump exists, which is obligation 2 made true by construction rather
        // than by ordering care. The order is `MoqCatalogTracks`' — video first, then audio — and
        // `EncodedFrame.trackIndex` is a position in this same list, which is why the index a pump
        // stamps is the one the declaration was built from rather than a second numbering.
        if (!deliver(sink) { onTracks(declared.map { it.track }) }) return
        val running = AtomicInteger(pumps.size)
        pumps.forEach { pump ->
            start("moq-track-${pump.index}", sink) {
                pump.pump(sink)
                // The last track to end ends the subscription. Earlier ones say nothing: a publisher
                // that stops its audio and keeps sending video has not ended the broadcast, and
                // SuperPlayer bounds a silent track itself, in media time against the leading one.
                if (running.decrementAndGet() == 0) deliver(sink, terminal = true) { onEnded() }
            }
        }
    }

    /** One track's pull loop, and the keyframe gate in front of it. */
    private inner class TrackPump(val index: Int, private val stream: MoqTrackStream) {

        /** Whether this track has yet delivered the keyframe obligation 3 requires it to start with. */
        private var started = false

        fun pump(sink: FrameSink) {
            while (!cancelled.get()) {
                val frame = stream.next() ?: return
                if (!started && !frame.keyframe) {
                    // A MoQ subscription may begin part-way through a group, so the frames before
                    // its first keyframe reference a picture this subscription never received.
                    // Dropped rather than handed over: a decoder fed them renders corruption and
                    // reports nothing, which is obligation 3's cost.
                    continue
                }
                started = true
                if (!deliver(sink) { onFrame(frame.asEncodedFrame(index)) }) return
            }
        }
    }

    /**
     * Runs [body] on a daemon thread of this source's own, turning anything it raises into the one
     * terminal failure the sink is told about.
     *
     * Daemon because a subscription nobody cancelled must not hold the process open; that stopping
     * is *correct* is [cancel]'s business and rests on the join rather than on the JVM.
     */
    private fun start(name: String, sink: FrameSink, body: () -> Unit) {
        val thread = Thread(
            {
                try {
                    body()
                } catch (failure: Throwable) {
                    // A cancellation is what most transports report as a failure on the way down —
                    // a closed session, an interrupted read — and reporting one as the stream's
                    // failure would show a viewer an error for a screen they had already left.
                    //
                    // `Throwable` and not `Exception`, which is `TelemetryDelivery`'s reason taken
                    // again: this is the last frame on a thread nothing else watches, so an `Error`
                    // raised here — an `OutOfMemoryError` on a pump, most plausibly — would
                    // otherwise vanish and leave the player buffering for ever with nothing to read
                    // in a bug report. It reaches the consumer as the cause of a session that
                    // failed, which is the truthful thing to call it.
                    if (!cancelled.get()) deliver(sink, terminal = true) { onError(failure) }
                }
            },
            "$name-${uri.host.orEmpty()}",
        )
        thread.isDaemon = true
        // Registered **and started** under the one monitor, which is what makes [cancel]'s join mean
        // what its KDoc says. Appending first and starting after the monitor was released left a
        // window in which `cancel` read the list, joined a thread that had not started — which
        // returns at once — and returned while the body was still to run. Nothing could have been
        // delivered through it, since `deliver` gates on the same flag, but a session opened
        // afterwards would have been closed by nobody.
        synchronized(lifecycle) {
            if (cancelled.get()) return
            threads += thread
            thread.start()
        }
    }

    /**
     * Keeps the session so that [cancel] can close it, or closes it at once and answers null where a
     * cancellation has already run.
     *
     * The window this closes is the one every connecting transport has: a [cancel] arriving between
     * the session being opened and this source learning of it would otherwise leave a QUIC session
     * nobody holds, outliving the screen it was opened from. [registerStream] is its twin, written
     * out rather than shared behind a type test, because what the two do with what they are handed
     * is the only interesting line in either.
     */
    private fun registerSession(opened: MoqBroadcastSession): MoqBroadcastSession? = synchronized(lifecycle) {
        if (cancelled.get()) {
            opened.closeQuietly()
            return null
        }
        session = opened
        opened
    }

    /** [registerSession] for one track's subscription, which is the other thing a cancel must close. */
    private fun registerStream(opened: MoqTrackStream): MoqTrackStream? = synchronized(lifecycle) {
        if (cancelled.get()) {
            opened.closeQuietly()
            return null
        }
        streams += opened
        opened
    }

    /**
     * Makes one callback on [sink], under the monitor obligation 1 needs, and answers whether the
     * subscription is still running.
     *
     * A false answer is the caller's signal to stop rather than an error: the subscription was
     * cancelled or has already ended, and there is nothing left to deliver into.
     *
     * [terminal] says that [callback] is the one that ends the subscription, and it is set *before*
     * the call rather than after it: a sink that re-entered from inside `onEnded` would otherwise
     * find the subscription still open.
     */
    private fun deliver(
        sink: FrameSink,
        terminal: Boolean = false,
        callback: FrameSink.() -> Unit,
    ): Boolean = synchronized(delivery) {
        if (cancelled.get() || terminated) return false
        terminated = terminal
        sink.callback()
        true
    }

    /** The scheme a consumer writes. */
    public companion object {

        /**
         * The URI scheme this transport answers, which is what `Realtime.transport` is given.
         *
         * `moq` is the scheme MoQ's own tooling addresses a broadcast with, and it is bare, without
         * the `:`, because that is what `Realtime.transport` takes.
         */
        public const val SCHEME: String = "moq"
    }
}

/** What crossed the FFI, as the seam's own frame. */
private fun MoqMediaFrame.asEncodedFrame(trackIndex: Int): EncodedFrame = EncodedFrame(
    // The bindings carry the timestamp as an unsigned 64-bit microsecond count and the seam as a
    // signed one. The conversion is exact for every value a live broadcast can reach — 2^63 us is
    // some 292,000 years — so nothing is bounded here, which would only add a branch no test could
    // reach for a value that cannot arise.
    timestampUs = timestampUs.toLong(),
    // Handed on as received: not copied, not reframed. The bindings answer a fresh array per frame,
    // which is obligations 6 and 8 both — codec bitstream with the container framing already gone
    // (ADR-0018 rule 3), and an array nothing here keeps or hands over twice.
    payload = payload,
    keyFrame = keyframe,
    trackIndex = trackIndex,
)
