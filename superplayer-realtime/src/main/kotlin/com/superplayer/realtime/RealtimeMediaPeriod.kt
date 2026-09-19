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

package com.superplayer.realtime

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleQueue
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.superplayer.core.EncodedFrame
import com.superplayer.core.FrameSink
import com.superplayer.core.FrameSource
import com.superplayer.core.RealtimeTrack
import com.superplayer.core.UnsupportedRealtimeCodecException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The `MediaPeriod` a [FrameSource]'s frames are written into, as Media3 samples.
 *
 * This is the whole of ADR-0018 rule 1: frames arrive **encoded**, go into a Media3 [SampleQueue],
 * and Media3's own `MediaCodec` renderers read them out. No `Renderer` is written here, so the TV
 * path, secure decoders, tunneling, audio focus and dropped-frame telemetry are untouched.
 *
 * Media3's RTSP module is the precedent for the shape — RTP packets arrive pushed, are depacketised
 * into sample queues and surface as an ordinary source under a live, unseekable timeline. Nothing
 * about being push-based requires leaving the sample pipeline.
 *
 * ## Threading
 *
 * Two threads meet here and the split is the one [SampleQueue] is built for:
 *
 * - the **transport's** thread writes, through [onTrack] and [onFrame]. [FrameSource]'s obligation 1
 *   is what makes it *one* thread, or several with a happens-before between them; a queue with two
 *   concurrent writers is corrupt and fails inside Media3 rather than here.
 * - the **playback** thread reads, through [selectTracks]'s streams and the rest of `MediaPeriod`.
 *
 * Everything that must reach Media3 on the playback thread is posted through [handler]: Media3 calls
 * [MediaPeriod.Callback] on the thread that called [prepare], and a callback delivered from a
 * transport's thread is a race with no symptom until it has one.
 */
internal class RealtimeMediaPeriod(
    private val frameSource: FrameSource,
    private val allocator: Allocator,
) : MediaPeriod,
    FrameSink {

    private var callback: MediaPeriod.Callback? = null
    private var handler: Handler? = null

    /**
     * Written by the transport's thread in [onTrack] and read by the playback thread from the moment
     * preparation completes, which is why it is `@Volatile` rather than guarded: there is exactly one
     * write, and every read must see it.
     */
    @Volatile
    private var sampleQueue: SampleQueue? = null

    @Volatile
    private var trackGroups: TrackGroupArray? = null

    /** Set by [onEnded] and by [onError]: the queue will receive nothing further. */
    @Volatile
    private var streamFinished: Boolean = false

    /** Set by [onError], and thrown from the playback thread at the next point Media3 asks. */
    @Volatile
    private var failure: IOException? = null

    /**
     * The first frame's timestamp, subtracted from every frame's.
     *
     * A transport's timestamps are on its own epoch ([FrameSource] obligation 4) while a Media3
     * period starts at zero, so the offset has to be removed somewhere. Here rather than at the
     * transport, because it is one subtraction against a contract that already promises the
     * timeline is monotonic — and because a transport asked to rebase would be asked to keep state
     * that this side already keeps.
     */
    private var firstTimestampUs: Long = C.TIME_UNSET

    /** So that [FrameSource.cancel] is called once, whichever of release and a failure gets there first. */
    private val cancelled = AtomicBoolean(false)

    // --- MediaPeriod: the playback thread's half ----------------------------------------------

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        this.handler = Util.createHandlerForCurrentLooper()
        // The subscription is what makes a transport connect, and nothing here waits for it: the
        // player is in STATE_BUFFERING until a track and the first frames arrive, which is what a
        // realtime stream's startup *is*.
        frameSource.subscribe(this)
    }

    override fun maybeThrowPrepareError() {
        failure?.let { throw it }
    }

    override fun getTrackGroups(): TrackGroupArray = checkNotNull(trackGroups)

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long,
    ): Long {
        for (i in selections.indices) {
            // A stream already held is kept: there is one track, so nothing about a re-selection can
            // change which queue it reads from.
            if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                streams[i] = null
            }
            if (streams[i] == null && selections[i] != null) {
                streams[i] = QueueStream()
                streamResetFlags[i] = true
            }
        }
        // The position is returned unchanged, always: a live stream starts where it starts, and the
        // timeline said it is unseekable, so there is no other position to answer with.
        return positionUs
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
        // Bounded by the read position: a live queue is written to continuously and discarding past
        // what has been read would drop frames the renderer has not seen.
        sampleQueue?.discardTo(positionUs, toKeyframe, /* stopAtReadPosition = */ true)
    }

    override fun readDiscontinuity(): Long = C.TIME_UNSET

    override fun seekToUs(positionUs: Long): Long = positionUs

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs

    override fun getBufferedPositionUs(): Long {
        val largest = sampleQueue?.largestQueuedTimestampUs ?: return 0L
        // `Long.MIN_VALUE` is Media3's "nothing queued yet". Answering it would be read as a position,
        // so an empty queue answers the period's own start instead — which is what "buffered nothing"
        // means here, and is what keeps the player in STATE_BUFFERING rather than declaring it ready.
        //
        // This never answers `C.TIME_END_OF_SOURCE`, and that is a fact about live timelines rather
        // than an omission: ExoPlayer declares a period final from the *timeline*, and a dynamic
        // period of unknown duration is never final, so **a realtime stream never reaches
        // `STATE_ENDED`**. A publisher that stops leaves the player at the edge with nothing further
        // to read, exactly as a live stream that goes quiet does. The end-of-source signal that does
        // mean something here is [getNextLoadPositionUs]'s, which is about loading and not about the
        // end of playback.
        return if (largest == Long.MIN_VALUE) 0L else largest
    }

    override fun getNextLoadPositionUs(): Long =
        if (streamFinished) C.TIME_END_OF_SOURCE else bufferedPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        // There is nothing to *start*: frames arrive when the publisher sends them. What this answers
        // is whether more can still arrive, which is what keeps the engine asking and keeps the
        // period counted as loading until the stream ends.
        return !streamFinished
    }

    override fun isLoading(): Boolean = !streamFinished

    override fun reevaluateBuffer(positionUs: Long) {
        // Nothing to re-evaluate: this period queues no future loads whose priority could change.
    }

    /** Called from the source when the player is done with this period. */
    fun release() {
        cancelSubscription()
        sampleQueue?.release()
        handler?.removeCallbacksAndMessages(null)
    }

    // --- FrameSink: the transport thread's half ------------------------------------------------

    override fun onTrack(track: RealtimeTrack) {
        val mapped = try {
            RealtimeFormats.formatFor(track.codec)
        } catch (refusal: UnsupportedRealtimeCodecException) {
            // Reported as a failure of preparation rather than thrown back at the transport: the
            // transport called this method correctly and what it handed over is what cannot be
            // decoded, so the refusal belongs on the path a consumer already watches for errors.
            onError(refusal)
            return
        }
        val format = mapped.buildUpon()
            .setInitializationData(track.codecSpecificData.map { it.copyOf() })
            .build()
        val queue = SampleQueue.createWithoutDrm(allocator)
        queue.format(format)
        sampleQueue = queue
        trackGroups = TrackGroupArray(TrackGroup(format))
        // On the playback thread, because that is where Media3 requires its own callback, and after
        // the queue exists, because preparation completing is what lets tracks be selected against it.
        handler?.post { callback?.onPrepared(this) }
    }

    override fun onFrame(frame: EncodedFrame) {
        val queue = sampleQueue ?: return
        if (streamFinished) return
        if (firstTimestampUs == C.TIME_UNSET) firstTimestampUs = frame.timestampUs
        val payload = frame.payload
        // Whether the reader had caught up with the writer *before* this frame went in, which is the
        // only case a wake-up below is needed for. Read here rather than after the write, when it
        // would always be false.
        val wasStarved = queue.readIndex == queue.writeIndex
        queue.sampleData(ParsableByteArray(payload), payload.size)
        queue.sampleMetadata(
            /* timeUs = */ frame.timestampUs - firstTimestampUs,
            /* flags = */ if (frame.keyFrame) C.BUFFER_FLAG_KEY_FRAME else 0,
            /* size = */ payload.size,
            /* offset = */ 0,
            /* cryptoData = */ null,
        )
        // Only out of starvation. The engine polls `continueLoading` on its own while a period is
        // loading, so a wake-up per frame would be one `Handler` post per frame — sixty a second on
        // the playback looper for a stream that was never waiting for anything. What the poll cannot
        // do is shorten the wait of a player that has run dry, and that is the case this covers.
        if (wasStarved) handler?.post { callback?.onContinueLoadingRequested(this) }
    }

    override fun onEnded() {
        streamFinished = true
        // What is already queued still plays out: `loadingFinished = true` is what lets the reader
        // drain it and then see the end of the stream, rather than stopping where the flag was set.
        handler?.post { callback?.onContinueLoadingRequested(this) }
    }

    override fun onError(cause: Throwable) {
        failure = cause as? IOException ?: IOException(cause)
        streamFinished = true
        cancelSubscription()
        handler?.post { callback?.onContinueLoadingRequested(this) }
    }

    private fun cancelSubscription() {
        if (cancelled.compareAndSet(false, true)) frameSource.cancel()
    }

    /**
     * The one track's `SampleStream`: a thin read side over the queue the transport writes into.
     *
     * `loadingFinished` is [streamFinished] and not a field of its own, because what it means to
     * Media3 — no more samples will arrive — is exactly what the transport said by calling
     * [onEnded] or [onError].
     */
    private inner class QueueStream : SampleStream {

        override fun isReady(): Boolean = sampleQueue?.isReady(streamFinished) ?: false

        override fun maybeThrowError() {
            failure?.let { throw it }
        }

        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int =
            sampleQueue?.read(formatHolder, buffer, readFlags, streamFinished) ?: C.RESULT_NOTHING_READ

        override fun skipData(positionUs: Long): Int {
            val queue = sampleQueue ?: return 0
            val count = queue.getSkipCount(positionUs, streamFinished)
            queue.skip(count)
            return count
        }
    }
}
