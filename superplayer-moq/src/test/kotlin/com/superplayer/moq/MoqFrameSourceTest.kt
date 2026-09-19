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
import com.superplayer.core.RealtimeTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import uniffi.moq.MoqCatalog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the frame pump does that `FrameSourceConformance` does not read (#366).
 *
 * The suite is the contract and `MoqFrameSourceConformanceTest` is where it is run; this class holds
 * the four claims that are this module's own rather than every transport's — the **order** behind
 * `EncodedFrame.trackIndex`, which is `MoqCatalogTracks`' and which a conformance suite has no way
 * to know; the **keyframe gate**, which the suite reads as an obligation honoured and cannot tell
 * from a publisher that happened to start on one; **cancellation** as an observation of the relay
 * being closed rather than only of delivery stopping; and a **failure** becoming `onError`, which
 * the suite refuses to see at all because it stops a subscription.
 *
 * Nothing here opens a QUIC session: the relay is [ScriptedMoqRelay], and what that proves and does
 * not is argued on [MoqRelay] and repeated on [MoqFrameSourceConformanceTest].
 */
@RunWith(RobolectricTestRunner::class)
class MoqFrameSourceTest {

    /**
     * `EncodedFrame.trackIndex` is a position in the list `onTracks` carried, and that list is
     * `MoqCatalogTracks`' order — **video first, then audio**. Asserted rather than assumed, because
     * the two halves are written in different files and a pump that numbered its own subscriptions
     * would agree with itself and disagree with the declaration.
     */
    @Test
    fun framesAreStampedWithTheIndexTheDeclarationGaveTheirTrack() {
        val relay = relay(bothKinds())
        val sink = RecordingSink()
        val source = MoqFrameSource(broadcast(), relay)

        source.subscribe(sink)
        sink.awaitFrames(FRAMES_WATCHED)
        source.cancel()

        assertEquals(
            listOf(DeclaredCatalogs.OBSERVED_AVC3_CODEC, DeclaredCatalogs.SYNTHESIZED_AAC_CODEC),
            sink.tracks.map { it.codec },
        )
        // The relay was asked for the catalog's own keys, in the same order, which is what carries
        // `MoqDeclaredTrack.trackName` through to `subscribeMedia`.
        assertEquals(listOf(VIDEO, AUDIO), relay.subscribed.toList())
        // Both tracks delivered, and every frame named one of the two declared positions.
        assertEquals(setOf(0, 1), sink.frames.map { it.trackIndex }.toSet())
        // The video track's own epoch reaches the seam unchanged: this module subtracts nothing,
        // because the period above it anchors the whole subscription (ADR-0018 rule 9).
        assertEquals(TRANSPORT_EPOCH_US, sink.frames.first { it.trackIndex == 0 }.timestampUs)
    }

    /**
     * The control that keeps the order from being "video is always 0": a broadcast carrying audio
     * alone indexes it 0, which is the audio-only case #346 widened the seam for.
     */
    @Test
    fun anAudioOnlyBroadcastIndexesItsOneTrackZero() {
        val relay = relay(
            DeclaredCatalogs.audioOnly(AUDIO, DeclaredCatalogs.SYNTHESIZED_AAC_CODEC, description = null),
        )
        val sink = RecordingSink()
        val source = MoqFrameSource(broadcast(), relay)

        source.subscribe(sink)
        sink.awaitFrames(FRAMES_WATCHED)
        source.cancel()

        assertEquals(listOf(DeclaredCatalogs.SYNTHESIZED_AAC_CODEC), sink.tracks.map { it.codec })
        assertEquals(setOf(0), sink.frames.map { it.trackIndex }.toSet())
    }

    /**
     * A subscription that joins part-way through a group is started at its first keyframe, and the
     * frames before it are **dropped rather than relabelled** — which is why the assertion is on the
     * timestamp: a pump that had handed over the dependent frames with the keyframe flag forced on
     * would pass an assertion about the flag alone and render corruption on a device.
     */
    @Test
    fun aTrackStartsAtItsFirstKeyframeAndTheFramesBeforeItAreDropped() {
        val sink = subscribeWatching(
            relay(videoOnly(), ScriptedMoqTrack(leadingNonKeyFrames = DEPENDENT_FRAMES_FIRST)),
        )

        val first = sink.frames.first()
        assertTrue("the first frame handed over is a keyframe", first.keyFrame)
        assertEquals(
            "and it is the keyframe itself rather than a dependent frame relabelled",
            TRANSPORT_EPOCH_US + DEPENDENT_FRAMES_FIRST * VIDEO_FRAME_DURATION_US,
            first.timestampUs,
        )
    }

    /**
     * The control for the gate: a publisher whose first frame is already a keyframe loses nothing.
     * Without this, "drop everything up to a keyframe" and "drop the first three frames" would be
     * the same test.
     */
    @Test
    fun aTrackAlreadyStartingOnAKeyframeLosesNoFrame() {
        val sink = subscribeWatching(relay(videoOnly()))

        assertEquals(TRANSPORT_EPOCH_US, sink.frames.first().timestampUs)
    }

    /**
     * Obligation 9, read as an observation of the *relay* and not only of the sink: cancellation
     * closes every stream and the session it opened, and no callback follows the return.
     *
     * A leaked QUIC session outlives the screen it was opened from, which is the failure this
     * counts. The suite's own cancellation check reads the sink instead, and a transport that
     * stopped delivering while holding its session open would pass it.
     */
    @Test
    fun cancelClosesTheSessionAndEveryStreamAndStopsDelivery() {
        val relay = relay(bothKinds())
        val sink = RecordingSink()
        val source = MoqFrameSource(broadcast(), relay)

        source.subscribe(sink)
        sink.awaitFrames(FRAMES_WATCHED)
        source.cancel()

        val afterCancel = sink.callbacks
        Thread.sleep(QUIET_AFTER_CANCEL_MS)
        assertEquals("no callback arrives after cancel() returned", afterCancel, sink.callbacks)
        assertEquals("every stream opened was closed", relay.streamsOpened.get(), relay.streamsClosed.get())
        assertEquals("and so was the session", relay.sessionsOpened.get(), relay.sessionsClosed.get())
        assertEquals("both tracks were subscribed before it", 2, relay.streamsOpened.get())
        // Neither terminal callback: a cancelled subscription is a viewer who left, not a stream
        // that ended or failed, and a player releasing its queues has nothing to be told.
        assertNull(sink.terminal)
    }

    /** A second `cancel()` is a no-op, which is what a player's own release paths make likely. */
    @Test
    fun cancelIsSafeToCallTwice() {
        val relay = relay(bothKinds())
        val sink = RecordingSink()
        val source = MoqFrameSource(broadcast(), relay)

        source.subscribe(sink)
        sink.awaitFrames(1)
        source.cancel()
        source.cancel()

        assertEquals(relay.sessionsOpened.get(), relay.sessionsClosed.get())
    }

    /**
     * A cancellation that lands while the connect thread is still opening things closes what it
     * finds, rather than leaving a session nobody holds.
     *
     * Cancelling before a single frame has been waited for is the window: the source has been asked
     * to subscribe and nothing is known yet about how far it got, which is exactly the race
     * `MoqFrameSource.register` exists for.
     */
    @Test
    fun cancelDuringConnectionLeaksNothing() {
        val relay = relay(bothKinds())
        val source = MoqFrameSource(broadcast(), relay)

        source.subscribe(RecordingSink())
        source.cancel()

        assertEquals(relay.sessionsOpened.get(), relay.sessionsClosed.get())
        assertEquals(relay.streamsOpened.get(), relay.streamsClosed.get())
    }

    /**
     * Every way a relay can fail reaches the sink as `onError`, carrying what failed.
     *
     * The whole point is the *thread*: a connect thread and a pump thread are this source's own, so
     * anything thrown on one would be lost and the player would sit in `STATE_BUFFERING` for ever
     * with nothing in a bug report. Each point is driven rather than one of them, because they are
     * on three different sides of the declaration — before it, at it, and after delivery has begun.
     */
    @Test
    fun aRelayThatFailsAnywhereReachesTheSinkAsOnError() {
        ScriptedMoqRelay.FailurePoint.entries.forEach { point ->
            val sink = RecordingSink()
            val source = MoqFrameSource(broadcast(), ScriptedMoqRelay(bothKinds(), failsAt = point))

            source.subscribe(sink)
            sink.awaitTerminal()
            source.cancel()

            assertEquals("a $point failure ends the subscription on onError", "onError", sink.terminal)
            assertNotNull(sink.failure)
            assertTrue(
                "and carries what failed: ${sink.failure?.message}",
                sink.failure?.message?.contains(ScriptedMoqRelay.FAILURE_MESSAGE) == true,
            )
        }
    }

    /**
     * A publisher that goes off air ends the subscription **once**, after its last track has, and
     * delivers nothing afterwards.
     *
     * The two tracks run out at different times by construction — audio steps faster than video — so
     * an implementation that ended on the first track to finish would end early here, which is the
     * defect worth catching: a viewer's video cut off when the audio track ran out.
     */
    @Test
    fun aBroadcastThatEndsEndsTheSubscriptionOnceAfterItsLastTrack() {
        val sink = RecordingSink()
        val relay = ScriptedMoqRelay(
            bothKinds(),
            tracks = mapOf(
                VIDEO to ScriptedMoqTrack(frames = SCRIPTED_FRAMES),
                AUDIO to ScriptedMoqTrack(
                    payload = ::aacFrame,
                    frames = SCRIPTED_FRAMES / 2,
                    frameDurationUs = AUDIO_FRAME_DURATION_US,
                ),
            ),
        )
        val source = MoqFrameSource(broadcast(), relay)

        source.subscribe(sink)
        sink.awaitTerminal()
        val whenItEnded = sink.frames.size
        Thread.sleep(QUIET_AFTER_CANCEL_MS)
        source.cancel()

        assertEquals("onEnded", sink.terminal)
        assertEquals("exactly one terminal callback", 1, sink.terminalCallbacks)
        assertEquals("and no frame after it", whenItEnded, sink.frames.size)
        assertEquals(
            "every scripted frame of both tracks arrived first",
            SCRIPTED_FRAMES + SCRIPTED_FRAMES / 2,
            whenItEnded,
        )
    }

    /** Subscribes, watches [FRAMES_WATCHED] frames arrive, and cancels. */
    private fun subscribeWatching(relay: ScriptedMoqRelay): RecordingSink {
        val sink = RecordingSink()
        val source = MoqFrameSource(broadcast(), relay)
        source.subscribe(sink)
        sink.awaitFrames(FRAMES_WATCHED)
        source.cancel()
        return sink
    }

    private fun relay(catalog: MoqCatalog, video: ScriptedMoqTrack = ScriptedMoqTrack()) = ScriptedMoqRelay(
        catalog = catalog,
        tracks = mapOf(
            VIDEO to video,
            AUDIO to ScriptedMoqTrack(payload = ::aacFrame, frameDurationUs = AUDIO_FRAME_DURATION_US),
        ),
    )

    private fun bothKinds(): MoqCatalog = DeclaredCatalogs.catalog(
        video = mapOf(VIDEO to DeclaredCatalogs.video(DeclaredCatalogs.OBSERVED_AVC3_CODEC, description = null)),
        audio = mapOf(AUDIO to DeclaredCatalogs.audio(DeclaredCatalogs.SYNTHESIZED_AAC_CODEC, description = null)),
    )

    private fun videoOnly(): MoqCatalog =
        DeclaredCatalogs.videoOnly(VIDEO, DeclaredCatalogs.OBSERVED_AVC3_CODEC, description = null)

    private fun broadcast(): Uri = Uri.parse("${MoqFrameSource.SCHEME}://relay.example/studio-a")

    private companion object {

        const val VIDEO = "video"
        const val AUDIO = "audio"

        /** Enough frames that both tracks have certainly delivered, and few enough to be quick. */
        const val FRAMES_WATCHED = 8

        /** A finite script, long enough that the two tracks run out at visibly different moments. */
        const val SCRIPTED_FRAMES = 12

        /** How many dependent frames a subscription joining mid-group receives before its keyframe. */
        const val DEPENDENT_FRAMES_FIRST = 3

        /**
         * How long the sink is watched after a subscription should have stopped.
         *
         * Several times [ScriptedMoqRelay.FRAME_INTERVAL_MS], so a pump still running would have
         * delivered, and short because every assertion that reads it is about *stopped* rather than
         * about fast.
         */
        const val QUIET_AFTER_CANCEL_MS = 50L

        /** How long a callback is waited for before a test gives up and fails as itself. */
        const val WAIT_BOUND_MS = 10_000L
    }

    /** Keeps what arrived, in order, and lets a test wait for enough of it. */
    private class RecordingSink : FrameSink {

        private val lock = Any()
        private val ended = CountDownLatch(1)
        private val delivered = mutableListOf<EncodedFrame>()
        private var wanted: CountDownLatch = CountDownLatch(0)

        var tracks: List<RealtimeTrack> = emptyList()
            private set

        var terminal: String? = null
            private set

        var terminalCallbacks: Int = 0
            private set

        var failure: Throwable? = null
            private set

        @Volatile
        var callbacks: Int = 0
            private set

        val frames: List<EncodedFrame> get() = synchronized(lock) { delivered.toList() }

        override fun onTracks(tracks: List<RealtimeTrack>) = synchronized(lock) {
            callbacks++
            this.tracks = tracks.toList()
        }

        override fun onFrame(frame: EncodedFrame) = synchronized(lock) {
            callbacks++
            delivered += frame
            wanted.countDown()
        }

        override fun onEnded() = end("onEnded", cause = null)

        override fun onError(cause: Throwable) = end("onError", cause)

        private fun end(which: String, cause: Throwable?) = synchronized(lock) {
            callbacks++
            terminalCallbacks++
            if (terminal == null) {
                terminal = which
                failure = cause
            }
            ended.countDown()
            // A subscription that is over will deliver no more frames, so a test waiting for some
            // stops waiting rather than paying the whole bound for an answer that has arrived.
            while (wanted.count > 0) wanted.countDown()
        }

        /** Waits for [count] frames, or for the subscription to end before that many arrive. */
        fun awaitFrames(count: Int) {
            synchronized(lock) {
                wanted = CountDownLatch((count - delivered.size).coerceAtLeast(0))
            }
            wanted.await(WAIT_BOUND_MS, TimeUnit.MILLISECONDS)
        }

        fun awaitTerminal() {
            ended.await(WAIT_BOUND_MS, TimeUnit.MILLISECONDS)
        }
    }
}
