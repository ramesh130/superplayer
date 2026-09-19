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
import androidx.media3.common.Format
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
import com.superplayer.core.RealtimeTrackStalledException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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
 * ## One queue per track, and one timeline over them
 *
 * A subscription carries as many tracks as the publisher declared in [onTracks] — audio beside video
 * is the ordinary case since #346, audio alone a legitimate one — and each gets a [SampleQueue] of
 * its own, its own `TrackGroup` and its own [QueueStream]. What they share is the *timeline*: one
 * epoch subtracted from every track's timestamps, so a skew the publisher meant to send survives
 * ([anchorFor] is where that is decided), and one buffered position, the most conservative of the
 * queues, because that is what `STATE_READY` is computed from.
 *
 * Two tracks also make silence a failure mode that one track did not have, which is what
 * [failIfATrackHasFallenBehind] is for.
 *
 * ## Threading
 *
 * Two threads meet here and the split is the one [SampleQueue] is built for:
 *
 * - the **transport's** thread writes, through [onTracks] and [onFrame]. [FrameSource]'s
 *   obligation 1 is what makes it *one* thread, or several with a happens-before between them; a
 *   queue with two concurrent writers is corrupt and fails inside Media3 rather than here. That
 *   obligation orders the deliveries against each other and says nothing about the playback thread,
 *   so every field either thread can see is `@Volatile` and every list either can see is published
 *   once and never mutated.
 * - the **playback** thread reads, through [selectTracks]'s streams and the rest of `MediaPeriod`.
 *
 * There is one deliberate exception to "the transport writes", and it is #346's: the playback thread
 * also calls [onError], from [failIfATrackHasFallenBehind], because a track that stopped arriving is
 * a fact only the reading side can notice. That is why [failure] and [streamFinished] are `@Volatile`
 * and [cancelled] an `AtomicBoolean`, and it is what [FrameSource.cancel]'s own contract already
 * anticipates — it must be safe to call from a thread other than the delivering one. A transport
 * reporting a real failure at the same instant the playback thread reaches a verdict is a race whose
 * outcome does not matter: both statements are true of the same session, both end it, and which
 * message a bug report carries is the only thing at stake. It is *not* a second writer of a sample
 * queue, which is the one race that would matter.
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
     * One [TrackState] per declared track, in the order [onTracks] declared them — which is the
     * order [EncodedFrame.trackIndex] names.
     *
     * Written by the transport's thread in [onTracks] and read by the playback thread from the
     * moment preparation completes, which is why it is `@Volatile` rather than guarded: there is
     * exactly one write, of a list that is never mutated afterwards, and every read must see it.
     */
    @Volatile
    private var tracks: List<TrackState>? = null

    @Volatile
    private var trackGroups: TrackGroupArray? = null

    /** Set by [onEnded] and by [onError]: the queues will receive nothing further. */
    @Volatile
    private var streamFinished: Boolean = false

    /** Set by [onError], and thrown from the playback thread at the next point Media3 asks. */
    @Volatile
    private var failure: IOException? = null

    /**
     * The timestamp the whole period is anchored on: the first frame delivered on **any** track.
     *
     * A transport's timestamps are on its own epoch ([FrameSource] obligation 4) while a Media3
     * period starts at zero, so the offset has to be removed somewhere. Here rather than at the
     * transport, because it is one subtraction against a contract that already promises each
     * track's timeline is monotonic — and because a transport asked to rebase would be asked to keep
     * state that this side already keeps.
     *
     * One anchor for every track and not one each, which is the decision two tracks forced:
     * rebasing each track on its own first frame would put both first frames at zero and flatten
     * away a real audio/video skew, and a skew flattened to zero is lip sync silently lost. Written
     * and read on the transport's thread alone, inside [onFrame], so it needs no `@Volatile`.
     */
    private var periodEpochUs: Long = C.TIME_UNSET

    /** So that [FrameSource.cancel] is called once, whichever of release and a failure gets there first. */
    private val cancelled = AtomicBoolean(false)

    /**
     * One declared track: what it decodes as, how its samples are framed, and where its bytes go.
     *
     * @property framing How this track's samples are framed, written in [onTracks] and read by the
     *   same transport thread in [onFrame] — [FrameSource] obligations 1 and 2 are what make that
     *   one thread and put the write first. It is per track and not per period, which is #345's
     *   NAL length size made plural: two tracks may declare different widths, and reading one
     *   track's samples at the other's frames every one of them wrongly while looking like it
     *   worked.
     */
    private class TrackState(
        val group: TrackGroup,
        val queue: SampleQueue,
        val framing: TrackConfiguration,
    ) {

        /**
         * What this track's own timestamps are rebased by: the period's anchor for a track that
         * shares the period's epoch, and something else for one that does not ([anchorFor]).
         * [C.TIME_UNSET] until the track's first frame arrives, which is also how "this track has
         * delivered nothing" is read.
         */
        @Volatile
        var offsetUs: Long = C.TIME_UNSET

        /** Whether the first frame has arrived, which is what fixes [offsetUs]. */
        val started: Boolean get() = offsetUs != C.TIME_UNSET

        /**
         * How far this track is buffered, on the period's timeline.
         *
         * `Long.MIN_VALUE` is Media3's "nothing queued yet". Answering it would be read as a
         * position, so an empty queue answers the period's own start instead — which is what
         * "buffered nothing" means here.
         */
        val bufferedPositionUs: Long
            get() {
                val largest = queue.largestQueuedTimestampUs
                return if (largest == Long.MIN_VALUE) 0L else largest
            }
    }

    // --- MediaPeriod: the playback thread's half ----------------------------------------------

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        this.handler = Util.createHandlerForCurrentLooper()
        // The subscription is what makes a transport connect, and nothing here waits for it: the
        // player is in STATE_BUFFERING until the tracks and the first frames arrive, which is what a
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
        val states = checkNotNull(tracks)
        val groups = checkNotNull(trackGroups)
        for (i in selections.indices) {
            // A stream already held is kept only where Media3 says this index's selection is
            // unchanged, which is what `mayRetainStreamFlags` means: it is computed against the
            // previous selection for the same index. With one track that guarantee was free and
            // this loop could ignore which queue a retained stream read from; with two it is the
            // whole of the correctness, because a stream retained across a change of group would go
            // on reading the other track's samples under the new track's format.
            if (streams[i] != null && (selections[i] == null || !mayRetainStreamFlags[i])) {
                streams[i] = null
            }
            val selection = selections[i]
            if (streams[i] == null && selection != null) {
                val index = groups.indexOf(selection.trackGroup)
                // Media3 builds a selection from this period's own `TrackGroupArray`, so a group it
                // does not contain is an invariant broken on Media3's side rather than a case to
                // recover from.
                check(index != C.INDEX_UNSET) { "A selection named a track group this period never published." }
                streams[i] = QueueStream(states[index])
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
        tracks?.forEach { it.queue.discardTo(positionUs, toKeyframe, /* stopAtReadPosition = */ true) }
    }

    override fun readDiscontinuity(): Long = C.TIME_UNSET

    override fun seekToUs(positionUs: Long): Long = positionUs

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long = positionUs

    override fun getBufferedPositionUs(): Long {
        val states = tracks ?: return 0L
        // The **most conservative** of the queues and never either alone: this is what STATE_READY
        // is computed from, and a period that answered the fuller queue's position would declare
        // itself ready on a buffer one of its renderers cannot read from.
        //
        // A track that has yet to deliver its first frame is left out of that minimum rather than
        // counted as zero, which is the late-start allowance: a transport whose audio arrives a
        // round trip before its video would otherwise hold the whole period at zero until the video
        // showed up. It is not unbounded — [failIfATrackHasFallenBehind] ends the session once such
        // a track has been silent for LATE_TRACK_START_BOUND_US of the others' media.
        //
        // This never answers `C.TIME_END_OF_SOURCE`, and that is a fact about live timelines rather
        // than an omission: ExoPlayer declares a period final from the *timeline*, and a dynamic
        // period of unknown duration is never final, so **a realtime stream never reaches
        // `STATE_ENDED`**. A publisher that stops leaves the player at the edge with nothing further
        // to read, exactly as a live stream that goes quiet does. The end-of-source signal that does
        // mean something here is [getNextLoadPositionUs]'s, which is about loading and not about the
        // end of playback.
        var buffered = Long.MAX_VALUE
        for (state in states) {
            if (!state.started) continue
            buffered = minOf(buffered, state.bufferedPositionUs)
        }
        return if (buffered == Long.MAX_VALUE) 0L else buffered
    }

    override fun getNextLoadPositionUs(): Long =
        if (streamFinished) C.TIME_END_OF_SOURCE else bufferedPositionUs

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        // Asked here because this is what the engine polls while a period is loading, so it is the
        // one place on the playback thread that is reached whether the player is playing, buffering
        // or waiting for a frame that is not coming.
        failIfATrackHasFallenBehind()
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
        tracks?.forEach { it.queue.release() }
        handler?.removeCallbacksAndMessages(null)
    }

    // --- FrameSink: the transport thread's half ------------------------------------------------

    override fun onTracks(tracks: List<RealtimeTrack>) {
        if (tracks.isEmpty()) {
            // Nothing to prepare and nothing to select: a subscription that declares no track would
            // otherwise publish an empty `TrackGroupArray` and leave the player ready to play
            // silence for ever. Refused where it is stated rather than waited out.
            onError(IOException("A realtime subscription declared no tracks, so there is nothing to play."))
            return
        }
        val states = mutableListOf<TrackState>()
        for (track in tracks) {
            val mapped: Format
            val configuration: TrackConfiguration
            try {
                mapped = RealtimeFormats.formatFor(track.codec)
                // Keyed on the codec's fourcc and never on the container (ADR-0018 rule 4, #345).
                // What comes back is the initialization data a decoder is configured with and, for
                // a fourcc whose samples are length-prefixed, the length size the record declared.
                configuration = CodecConfigurationRecords.of(track.codec, track.codecConfiguration)
            } catch (refusal: IOException) {
                // The two typed refusals this can raise — an unmappable codec string and a
                // configuration that contradicts its own fourcc — are reported as a failure of
                // preparation rather than thrown back at the transport: the transport called this
                // method correctly and what it handed over is what cannot be decoded, so the refusal
                // belongs on the path a consumer already watches for errors. `IOException` rather
                // than the two by name because both are one, and because anything else raised from
                // reading what a transport handed over is the same kind of fact about the same
                // delivery.
                //
                // One track refused refuses the whole subscription, because preparation is one
                // event: a player told about the tracks that did map would be playing content the
                // publisher did not send. The queues built before the refusal are released here,
                // since nothing else will ever hold them.
                states.forEach { it.queue.release() }
                onError(refusal)
                return
            }
            val format = mapped.buildUpon()
                .setInitializationData(configuration.initializationData)
                .build()
            val queue = SampleQueue.createWithoutDrm(allocator)
            queue.format(format)
            states += TrackState(TrackGroup(format), queue, configuration)
        }
        this.tracks = states
        trackGroups = TrackGroupArray(*states.map { it.group }.toTypedArray())
        // On the playback thread, because that is where Media3 requires its own callback, and after
        // the queues exist, because preparation completing is what lets tracks be selected against
        // them.
        handler?.post { callback?.onPrepared(this) }
    }

    override fun onFrame(frame: EncodedFrame) {
        val states = tracks ?: return
        if (streamFinished) return
        val state = states.getOrNull(frame.trackIndex)
        if (state == null) {
            // Obligation 2 broken: a frame for a track that was never declared. Reported rather than
            // dropped, because a whole track quietly going nowhere is the silent nothing this seam's
            // obligations exist to prevent — and reported untyped, deliberately, unlike #344's and
            // #345's refusals: those two refuse bytes a consumer may want to branch on, while this
            // is a transport bug whose only useful reader is the person writing the transport, and
            // the message names the numbers they need. `FrameSourceConformance` (#347) is where it
            // is caught before a viewer meets it.
            onError(
                IOException(
                    "A realtime frame named track index ${frame.trackIndex}, but the subscription " +
                        "declared ${states.size} track(s). A frame's trackIndex is a position in the " +
                        "list handed to FrameSink.onTracks.",
                ),
            )
            return
        }
        if (!state.started) state.offsetUs = anchorFor(frame.timestampUs)
        val payload = try {
            state.framing.toAnnexB(frame.payload)
        } catch (refusal: IOException) {
            onError(refusal)
            return
        }
        val queue = state.queue
        // Whether the reader had caught up with the writer *before* this frame went in, which is the
        // only case a wake-up below is needed for. Read here rather than after the write, when it
        // would always be false.
        val wasStarved = queue.readIndex == queue.writeIndex
        queue.sampleData(ParsableByteArray(payload), payload.size)
        queue.sampleMetadata(
            /* timeUs = */ frame.timestampUs - state.offsetUs,
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

    /**
     * What a track's first frame is rebased by, which is the whole of the timebase rule (#346).
     *
     * The first frame on **any** track anchors the period, and every track that looks like it shares
     * that epoch is rebased by the same amount — so a publisher whose audio genuinely leads its
     * video by 40 ms sends two tracks on one clock and the 40 ms arrives as 40 ms. That is the
     * common case and the one worth getting right, because it is the one where the transport had
     * something true to say.
     *
     * A track whose first frame lands further than [SHARED_EPOCH_BOUND_US] from the anchor is taken
     * to be on an epoch of its own instead. [FrameSource] obligation 4 promises monotonicity within
     * a track and deliberately promises nothing across tracks, and two origins are not hypothetical:
     * an RTP stream's initial timestamp is random per SSRC (spec: RFC 3550 §5.1 — "the initial value
     * of the timestamp SHOULD be random"), so two tracks of one session routinely start hours apart
     * on the wire. Such a track is anchored at the point the period had reached when its first frame
     * arrived — the furthest any track has been written to — because with no common clock that is
     * the only estimate of "now" either side has. It recovers playback and not lip sync, and saying
     * so is the honest half: the alternative, honouring the difference, puts one track's samples
     * hours before the read position where they are discarded silently, or hours after it where the
     * buffer wedges full.
     */
    private fun anchorFor(firstTimestampUs: Long): Long {
        val epoch = periodEpochUs
        if (epoch == C.TIME_UNSET) {
            periodEpochUs = firstTimestampUs
            return firstTimestampUs
        }
        // No overflow to guard: a microsecond timestamp from any clock a transport can read is many
        // orders of magnitude inside `Long`, which holds ~292,000 years of them.
        if (abs(firstTimestampUs - epoch) <= SHARED_EPOCH_BOUND_US) return epoch
        // The write frontier: the furthest point on the period's timeline any track has reached, or
        // its start where none has. This is the "now" the KDoc above argues for.
        return firstTimestampUs - (leadingBufferedPositionUs(tracks.orEmpty()) ?: 0L)
    }

    /**
     * How far the furthest-ahead track is buffered, or null where no track has delivered a frame.
     *
     * One reading for both of its callers, which want it for opposite purposes — [anchorFor] as the
     * point to place a track arriving on another clock, [failIfATrackHasFallenBehind] as the line
     * every other track is measured against — and which differ only in what they make of "no track
     * has started yet". Null rather than zero, so each says that for itself.
     */
    private fun leadingBufferedPositionUs(states: List<TrackState>): Long? =
        states.filter { it.started }.maxOfOrNull { it.bufferedPositionUs }

    /**
     * Ends the session where one track has fallen far enough behind the others to have stopped
     * playback, rather than letting it buffer for ever (#346).
     *
     * Two bounds rather than one, because the two silences are not the same thing. A track that has
     * never delivered a frame is still *connecting* — a transport negotiates per track and may be
     * waiting for a keyframe — so it is given [LATE_TRACK_START_BOUND_US]. A track that was
     * delivering and stopped has nothing left to connect, and gets the shorter
     * [STALLED_TRACK_BOUND_US].
     *
     * Both are measured in **media time against the furthest-ahead track**, never against a clock,
     * which is two decisions at once. It is deterministic — the same delivery produces the same
     * verdict on any host, which is what makes it assertable under `check` at all. And it is what
     * makes *all* the tracks going quiet a non-event: the leader stops advancing too, nobody falls
     * behind, and the player sits at the live edge exactly as ADR-0018 rule 5's timeline intends.
     *
     * A single-track subscription can reach neither bound, which is why it is left alone: there is
     * nothing for it to be behind, and #343's one-track behaviour is unchanged by this whole rule.
     */
    private fun failIfATrackHasFallenBehind() {
        val states = tracks ?: return
        if (streamFinished || states.size < 2) return
        val leaderUs = leadingBufferedPositionUs(states) ?: return
        for (state in states) {
            val boundUs = if (state.started) STALLED_TRACK_BOUND_US else LATE_TRACK_START_BOUND_US
            val behindUs = leaderUs - state.bufferedPositionUs
            if (behindUs > boundUs) {
                onError(
                    RealtimeTrackStalledException(
                        state.framing.codec,
                        Util.usToMs(behindUs),
                        Util.usToMs(boundUs),
                    ),
                )
                return
            }
        }
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
     * One track's `SampleStream`: a thin read side over the queue the transport writes into.
     *
     * `loadingFinished` is [streamFinished] and not a field of its own, because what it means to
     * Media3 — no more samples will arrive — is exactly what the transport said by calling
     * [onEnded] or [onError], and that is a fact about the subscription rather than about one
     * track of it.
     */
    private inner class QueueStream(private val track: TrackState) : SampleStream {

        override fun isReady(): Boolean = track.queue.isReady(streamFinished)

        override fun maybeThrowError() {
            failure?.let { throw it }
        }

        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int =
            track.queue.read(formatHolder, buffer, readFlags, streamFinished)

        override fun skipData(positionUs: Long): Int {
            val count = track.queue.getSkipCount(positionUs, streamFinished)
            track.queue.skip(count)
            return count
        }
    }

    private companion object {

        /**
         * How far apart two tracks' first frames may be and still be read as one epoch.
         *
         * Thirty seconds, chosen to sit in the wide gap between the two populations it separates.
         * Below it is every real start skew: a second track that begins a GOP or a subscription
         * round trip after the first is seconds at the very worst, and anything beyond
         * [LATE_TRACK_START_BOUND_US] ends the session anyway, so six times that bound is already
         * generous. Above it is every genuinely different origin: an RTP timestamp's random 32-bit
         * start wraps a 90 kHz video clock every ~13 hours, a monotonic clock and a wall clock
         * differ by whatever the device has been up, and none of those lands inside half a minute
         * except by coincidence. What the gap buys is that neither mistake is quiet — a shared
         * epoch read as separate would flatten a skew, and a separate one read as shared would
         * strand a track hours away.
         */
        const val SHARED_EPOCH_BOUND_US = 30_000_000L

        /**
         * How far the other tracks may advance while a declared track has delivered nothing at all.
         *
         * Five seconds. A track that has not started is still connecting, and a realtime transport
         * negotiates per track: a subscription round trip, an encoder asked for a keyframe and the
         * first GOP is a plausible few seconds on a slow link, which is more than the two-second
         * keyframe interval a live encoder typically runs. It is deliberately longer than
         * [STALLED_TRACK_BOUND_US], because connecting is slower than continuing. Beyond it there is
         * nothing to wait for that waiting longer would fix, and the player is better told than left
         * spinning.
         */
        const val LATE_TRACK_START_BOUND_US = 5_000_000L

        /**
         * How far the other tracks may advance while a track that *was* delivering stays quiet.
         *
         * Three seconds. A publisher sends its tracks together, so a gap of this size is not jitter
         * at any buffer a realtime stream runs: the point of this path is sub-second latency, and a
         * three-second hole in one track while another keeps flowing is a lost subscription rather
         * than a slow one. Shorter than [LATE_TRACK_START_BOUND_US] for that reason — this track has
         * already proved it can deliver, so its silence says something different.
         */
        const val STALLED_TRACK_BOUND_US = 3_000_000L
    }
}
