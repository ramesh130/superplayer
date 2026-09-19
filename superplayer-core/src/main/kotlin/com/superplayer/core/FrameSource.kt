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

package com.superplayer.core

import android.net.Uri

/**
 * A realtime transport, as SuperPlayer sees it: something that can be subscribed to and will then
 * push **encoded** frames until it is cancelled.
 *
 * This is the whole seam (ADR-0018 rule 2). A transport implements it over MoQ, over WebRTC, or over
 * whatever else pushes media; SuperPlayer writes what arrives into Media3's own sample queues and
 * Media3 decodes it exactly as it decodes an HLS segment. **Nothing in this library decodes**
 * (rule 1), which is why the TV path, secure decoders, tunneling, audio focus and the decoder half
 * of telemetry all keep working on a realtime stream with no new code.
 *
 * It names no Media3 type, for [HttpTransport]'s reason and for one more: it is the only boundary at
 * which a realtime transport is testable at all. Neither QUIC nor WebRTC runs under `check`, and
 * Robolectric cannot load an Android `.so` on the JVM, so a fake stands here and everything above it
 * is ordinary Robolectric work.
 *
 * It lives in `superplayer-core` beside [HttpTransport] for that seam's reason too (ADR-0018 rule 2,
 * #356): a conformance suite belongs in `superplayer-testkit`, which is phase 2 and may name nothing
 * later than core, so an interface a consumer implements has to be core's for its suite to reach it.
 *
 * ## The obligations, and what getting each one wrong costs
 *
 * Written out because **every one of them fails silently**. A transport that breaks one does not
 * throw: it produces a decoder that configures cleanly and renders nothing, or a player that sits in
 * `STATE_BUFFERING` with no error anywhere. That is the same reason [HttpTransport] states its five,
 * and the same reason this one is paired with a conformance suite (#347) rather than with KDoc
 * alone.
 *
 * 1. **Deliver on one thread, or with a happens-before between deliveries.** Every call on the
 *    [FrameSink] a subscription was given must be ordered against every other. *Cost:* SuperPlayer
 *    writes into a sample queue with one writer assumed; two concurrent writers corrupt it, and what
 *    surfaces is a crash inside Media3 whose stack names nothing of yours.
 * 2. **Call [FrameSink.onTrack] before the first frame, exactly once.** *Cost:* no `Format` exists,
 *    so nothing is prepared and the player waits in `STATE_BUFFERING` for ever, with no error to
 *    report and nothing in a bug report to read.
 * 3. **Make the first frame after [FrameSink.onTrack] a keyframe.** *Cost:* the decoder is fed a
 *    dependent frame with no reference, and renders either nothing or visible corruption — the
 *    classic "it connects and the picture is garbage" report.
 * 4. **Timestamps are microseconds on one monotonically non-decreasing timeline.** They are
 *    *yours*: SuperPlayer subtracts the first frame's value and treats the rest as offsets from it,
 *    so an epoch is not required, but a reset or a step backwards is not tolerated (ADR-0018
 *    rule 9). *Cost:* a frame timed before the queue's read position is discarded silently; a
 *    backwards step strands the playhead and playback stalls with a full buffer.
 * 5. **The precision you have is the precision you state.** Where a transport cannot fill
 *    microseconds exactly — WebRTC's Android Java seam exposes no RTP timestamp and truncates to
 *    whole milliseconds — say so in *your* documentation rather than implying a precision you do not
 *    have. *Cost:* nobody can tell your rounding from a fault in this library, and no work on this
 *    side recovers it. **This one is unenforceable**, which is exactly why it is written down.
 * 6. **[EncodedFrame.payload] is codec bitstream and nothing else.** Container framing is yours to
 *    strip before the seam (ADR-0018 rule 3); no fragmented-MP4 parsing exists on this side.
 *    *Cost:* the decoder configures cleanly and renders nothing, which is the hardest of all these
 *    to attribute.
 * 7. **[RealtimeTrack.codecConfiguration] follows the codec's fourcc, never the container**
 *    (ADR-0018 rule 4). `avc1` and `hvc1` carry an out-of-band configuration record and hand it over
 *    as [RealtimeTrack.CodecConfiguration.Record], **exactly as received** — SuperPlayer converts it
 *    to what the decoder wants, and a transport that converts it first is a transport that has to
 *    know what a decoder wants. `avc3` and `hev1` carry their parameter sets in band before every
 *    keyframe and hand over [RealtimeTrack.CodecConfiguration.InBand], which is the default.
 *    *Cost:* smaller than the rest of this list, and deliberately so (#345): naming the shape is
 *    what lets a fourcc that contradicts it be **refused** with
 *    [MalformedRealtimeBitstreamException] at preparation, instead of becoming the silent nothing
 *    of 6 in both directions. What stays silent is a record that parses and belongs to a different
 *    stream, which nothing on this side can tell from the right one.
 * 8. **Do not touch [EncodedFrame.payload] after [FrameSink.onFrame] returns**, and do not hand the
 *    same array twice. *Cost:* a torn frame, and a corruption that moves when timing moves — the
 *    least reproducible failure in this list.
 * 9. **[cancel] stops delivery, and no callback arrives after it returns.** *Cost:* a write into a
 *    queue that has been released, which is a crash at an unrelated moment.
 *
 * ## What a realtime stream does not get
 *
 * Stated rather than discovered (ADR-0018 rule 6): no content cache, no CMCD, no downloads, no
 * bandwidth estimate and none of the fallback ladder's load-error rungs, because every one of those
 * is keyed to a `DataSource` and this is not one. What survives is [MediaRequest.sources] and
 * ADR-0011's rung 4 — a realtime source falling back to an HLS one — which is the documented way to
 * have a fallback here at all.
 */
public interface FrameSource {

    /**
     * Begins delivery to [sink], which is called as obligations 1 to 8 above require.
     *
     * Called once per playback, on the thread that prepares the player. A transport that needs to
     * connect does so here or on a thread of its own; nothing on this side waits for it, and the
     * player is in `STATE_BUFFERING` until [FrameSink.onTrack] and the first frames arrive.
     */
    public fun subscribe(sink: FrameSink)

    /**
     * Ends delivery, releasing whatever [subscribe] opened.
     *
     * Called when the player releases the stream, and **once** per [subscribe]. It must return only
     * once no further callback can be made — obligation 9 — and must be safe to call from a
     * different thread than the one delivering frames, because it is the playback thread that calls
     * it.
     */
    public fun cancel()
}

/**
 * Where a [FrameSource] delivers what it receives.
 *
 * Implemented by SuperPlayer and handed to [FrameSource.subscribe]; a transport calls it and never
 * implements it. Every method's contract is one of the obligations on [FrameSource], which is where
 * the reasons and the costs are.
 */
public interface FrameSink {

    /**
     * The track that is about to be delivered. Called once, before the first [onFrame]
     * (obligation 2).
     *
     * One track per subscription in phase 13. Audio and video through one source, in sync across two
     * sample queues, is #346's and widens this method rather than adding a second one.
     */
    public fun onTrack(track: RealtimeTrack)

    /** One encoded frame (obligations 3, 4, 6 and 8). */
    public fun onFrame(frame: EncodedFrame)

    /**
     * The stream ended of its own accord — the publisher stopped, the broadcast finished.
     *
     * Playback drains what is queued and ends normally. A transport that *failed* calls [onError]
     * instead: the difference is what a viewer is shown, and it is not this library's to guess.
     */
    public fun onEnded()

    /**
     * The stream failed and no further frame will arrive.
     *
     * [cause] reaches the consumer as the cause of the `PlaybackException` `onPlayerError` carries,
     * so it is read by a human in a bug report: name what failed, not that something did.
     */
    public fun onError(cause: Throwable)
}

/**
 * What is being delivered: the codec, and the codec-specific data that codec needs out of band.
 *
 * @property codec What is being sent, in either of the two vocabularies a realtime transport has:
 *   an RFC 6381 codecs string — `avc1.640028`, `avc3.42E01E`, `hev1.1.6.L93.B0`, `vp09.00.10.08`,
 *   `av01.0.04M.08`, `mp4a.40.2`, `opus` — or an SDP encoding name as an `a=rtpmap` line carries it,
 *   `H264`, `H265`, `VP9`, `AV1`, `MPEG4-GENERIC` or `opus`. Prefer the RFC 6381 string where you
 *   have one: it states a profile and a level, which is what lets a rendition a device's decoder
 *   cannot reach be refused rather than failed at, and a bare encoding name states neither.
 *   H.264, H.265, VP9, AV1, AAC and Opus are mapped and **anything else is refused** with
 *   [UnsupportedRealtimeCodecException] naming the string, because a codec decoded as the wrong one
 *   fails silently. An RFC 6381 string's **fourcc** prefix is also what decides which
 *   [CodecConfiguration] is expected, never the container the frames came in (ADR-0018 rule 4).
 * @property codecConfiguration What the decoder needs before the first frame, in the shape the
 *   codec's fourcc says it comes in: [CodecConfiguration.Record] for `avc1` and `hvc1`, and
 *   [CodecConfiguration.InBand] — the default — for `avc3` and `hev1`, which carry it in the
 *   bitstream. See obligation 7 on [FrameSource].
 */
public class RealtimeTrack(
    public val codec: String,
    public val codecConfiguration: CodecConfiguration = CodecConfiguration.InBand,
) {

    /**
     * Where the parameter sets a decoder must be configured with come from, which the codec's
     * **fourcc** decides and the container never does (ADR-0018 rule 4).
     *
     * ## Why a named shape rather than a list of bytes
     *
     * The seam carried `List<ByteArray>` of Annex-B units until #345, and every value of it
     * type-checked: a transport that handed over an `avcC` record where units were expected, or
     * units where its fourcc said a record, compiled, configured a decoder cleanly and rendered
     * nothing — #340's observed failure and the one this whole branch exists to prevent. Naming the
     * two shapes makes the contradiction a *statement* rather than a byte pattern, so SuperPlayer
     * can refuse it by name ([MalformedRealtimeBitstreamException]) at the moment the track
     * arrives. It also moves the conversion to the side that knows what a decoder wants: a
     * transport hands over the record its publisher sent and needs to know nothing about Annex-B.
     */
    public sealed class CodecConfiguration {

        /**
         * The stream is self-describing: parameter sets arrive in band, as Annex-B units before
         * every keyframe, and nothing is configured out of band.
         *
         * What `avc3` and `hev1` must hand over, and the default because it is the shape that needs
         * no bytes — a transport that says nothing says the thing that is true of the codecs
         * carrying nothing.
         */
        public object InBand : CodecConfiguration()

        /**
         * The codec's out-of-band configuration record, **exactly as the transport received it**.
         *
         * For `avc1` that is an `AVCDecoderConfigurationRecord` (`avcC`) and for `hvc1` an
         * `HEVCDecoderConfigurationRecord` (`hvcC`); for any other codec it is whatever that codec's
         * binding calls its configuration record. It is not converted, unwrapped or reordered
         * first: [bytes] is what arrived, and what a decoder is configured with is SuperPlayer's to
         * derive from it.
         *
         * @property bytes The record. Not modified after this is handed to [FrameSink.onTrack],
         *   for [EncodedFrame.payload]'s reason.
         */
        public class Record(public val bytes: ByteArray) : CodecConfiguration()
    }
}

/**
 * One encoded frame.
 *
 * @property timestampUs The frame's presentation time in microseconds, on the transport's own
 *   monotonically non-decreasing timeline (obligations 4 and 5). SuperPlayer subtracts the first
 *   frame's value, so the epoch is yours and only the differences are read.
 * @property payload The codec bitstream, with no container framing (obligation 6). Not touched after
 *   [FrameSink.onFrame] returns, and never handed over twice (obligation 8).
 * @property keyFrame Whether this frame can be decoded without reference to an earlier one. The
 *   first frame of a subscription must be one (obligation 3).
 */
public class EncodedFrame(
    public val timestampUs: Long,
    public val payload: ByteArray,
    public val keyFrame: Boolean,
)

/**
 * Opens the [FrameSource] for a URI whose scheme this transport answers.
 *
 * One source per playback: [FrameSource.subscribe] is called on what this returns, and
 * [FrameSource.cancel] when the player is done with it. Returning the same instance for two URIs
 * would have one stream's cancellation end the other's delivery, so a transport that pools
 * connections pools those rather than this.
 */
public fun interface FrameSourceFactory {

    /** The source for [uri], whose scheme is one this transport was registered under. */
    public fun open(uri: Uri): FrameSource
}
