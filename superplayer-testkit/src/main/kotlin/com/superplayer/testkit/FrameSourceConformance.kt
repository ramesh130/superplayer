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

import android.net.Uri
import com.superplayer.core.EncodedFrame
import com.superplayer.core.FrameSink
import com.superplayer.core.FrameSource
import com.superplayer.core.FrameSourceFactory
import com.superplayer.core.RealtimeTrack
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Runs a [FrameSource] against the obligations ADR-0018 puts on one, and says which it broke.
 *
 * ```kotlin
 * @Test
 * fun `our transport satisfies SuperPlayer's contract`() {
 *     FrameSourceConformance(
 *         sources = { uri -> MoqFrameSource(relay, uri) },
 *         uri = Uri.parse("moq://relay.example/studio-a"),
 *     ).verifyAll()
 * }
 * ```
 *
 * ## Why this is a test you run rather than a paragraph you read
 *
 * [FrameSource]'s nine obligations are on code this library cannot see, and **every one of them
 * fails silently** — that sentence is in the seam's own KDoc and it is the whole argument for this
 * class. A transport that hands over its parameter sets in the wrong shape does not throw: it
 * produces a decoder that configures cleanly and renders nothing. One that frames its samples as
 * Annex-B under a fourcc that promised length prefixes produces a structurally plausible stream of
 * noise, because a start code read as a length declares a one-byte unit and the picture data behind
 * it becomes the next "length". One that declares a track it never delivers produces a player that
 * sits in `STATE_BUFFERING` for ever with no error anywhere in a bug report.
 *
 * `HttpTransportConformance` is the precedent, for the same reason (ADR-0016 rule 14): the code the
 * obligations bind is code this repository cannot see, so a test is the only form in which they can
 * travel to the person who has to satisfy them. It matters more here than there. `superplayer-moq`
 * and `superplayer-whep` implement this seam over native transports that **cannot run under
 * `check`** — neither QUIC nor WebRTC does, and Robolectric cannot load an Android `.so` on the JVM
 * — so this suite is not one check among several on those modules. It is the only one there is.
 *
 * ## What you supply, and what this supplies
 *
 * **You supply the transport and the stream**, which is the one place this suite differs from
 * `HttpTransportConformance` and is a difference of subject rather than of taste. An HTTP client's
 * obligations are claims about what goes on a wire, so that suite stands up an origin on the
 * loopback interface and reads both ends of the exchange. A [FrameSource]'s obligations are claims
 * about what it delivers **to a sink**, so what this supplies is the sink: a recorder that
 * subscribes, keeps everything that arrives in the order it arrived, and then cancels. There is no
 * socket here and `docs/testing.md`'s one loopback carve-out is not widened by it.
 *
 * The consequence to know before you call it: point it at a stream your transport can really play,
 * live or recorded, publishing the tracks you ship. A few frames of each track is enough — nothing
 * below waits for more than [FRAMES_OBSERVED]. A URI that never reaches [FrameSink.onTracks] is
 * refused under obligation 2, because from here a transport that declared nothing and a stream that
 * is not there are the same thing; a URI that declares its tracks and then delivers **no frames**
 * passes every check below vacuously, since each one reads the frames that arrived. Point this at a
 * stream that plays.
 *
 * ## What a failure is
 *
 * [FrameSourceConformanceException], an `AssertionError`, so that every test framework renders it as
 * a failed assertion rather than as an unexpected error — and no test framework is named here,
 * because `superplayer-testkit` declares none: the message is the deliverable, and it is carried by
 * a type rather than by JUnit so that a consumer on JUnit 5, Kotest, or an instrumentation runner
 * runs the same checks as a consumer on JUnit 4.
 *
 * Each message names the obligation, says **what this source did** and says **what the rule
 * requires**, in that order, because the reader of it is not the author of this code and has no copy
 * of this repository.
 *
 * ## What this cannot check, said plainly
 *
 * Four things are beyond it, and naming them is worth more than a check that pretends:
 *
 * - **Obligation 5, the precision you state**, is unenforceable by construction and says so where it
 *   is written: nothing here can tell a transport that truncated to milliseconds from a publisher
 *   that emitted whole milliseconds. It is a documentation obligation on you.
 * - **Obligation 6, payload is codec bitstream and nothing else**, is reached only as far as the
 *   framing walk below goes. A fragmented-MP4 sample handed over whole is usually caught by
 *   [verifySamplesAreFramedAsTheFourccRequires], because its box headers do not walk as NAL units —
 *   but a container this suite has never seen may not be, and no arrangement of bytes proves the
 *   absence of framing in general.
 * - **A configuration record that parses and belongs to a different stream** is invisible, which
 *   [RealtimeTrack.CodecConfiguration] already records. Nothing on this side can tell it from the
 *   right one.
 * - **A track declared and never delivered** is invisible too, and so is the *shared epoch* across
 *   tracks that obligation 4 asks for wherever you have one. Both are bounded by SuperPlayer at
 *   playback rather than here — see [verifyTerminationIsFinal] and
 *   [verifyTimestampsAreMonotonicPerTrack] for why.
 *
 * And one that is a limit of the **sink** rather than of the seam: obligation 1 is checked by
 * widening the window and watching for two deliveries in flight at once, so a transport with two
 * genuinely concurrent deliverers is caught, while one that delivers from several threads with a
 * happens-before between them passes — which is what the obligation permits.
 */
public class FrameSourceConformance(
    /** Opens one source per check, exactly as a player opens one per playback. */
    private val sources: FrameSourceFactory,
    /** The stream to subscribe to, whose scheme is the one this transport answers. */
    private val uri: Uri,
) {

    /**
     * Every check, in the order [FrameSource]'s KDoc numbers the obligations, stopping at the first
     * one broken.
     *
     * Two of the eleven enforce a rule that is not one of the nine — the codec string's, and the
     * finality of a terminal callback — and each runs at the point it bears on rather than at the
     * end: the codec string after obligation 4 because obligation 7 is stated in terms of its
     * fourcc, and termination before cancellation because the two are the pair of ways a
     * subscription stops. Obligation 2 is two calls, because "declared once, before the first frame"
     * and "a frame names a declared track" are read off different halves of the delivery.
     *
     * Stopping rather than collecting, for `HttpTransportConformance.verifyAll`'s reason: the checks
     * are independent of one another, but a transport that fails one has a defect to fix before the
     * rest of the report means anything, and a wall of failures is a worse first thing to read than
     * one. It matters a little more here, because several of these obligations describe the same
     * delivery from different angles — a source that declares its tracks late will usually also look
     * like one whose frames name no declared track.
     *
     * Each check subscribes afresh: [FrameSourceFactory.open] is called once per check and the
     * source it answers is cancelled before the next, which is the lifecycle a player gives one.
     */
    public fun verifyAll() {
        verifyDeliveriesDoNotOverlap()
        verifyTracksAreDeclaredOnceBeforeTheFirstFrame()
        verifyEveryFrameNamesADeclaredTrack()
        verifyEachTrackStartsWithAKeyframe()
        verifyTimestampsAreMonotonicPerTrack()
        verifyCodecStringsAreMapped()
        verifyCodecConfigurationFollowsTheFourcc()
        verifySamplesAreFramedAsTheFourccRequires()
        verifyPayloadsAreNeitherReusedNorTouchedAgain()
        verifyTerminationIsFinal()
        verifyCancellationStopsDelivery()
    }

    /**
     * **Obligation 1** — every call on the sink is ordered against every other, whether by one
     * delivering thread or by a happens-before between several.
     *
     * The window is widened deliberately: the sink this check subscribes lingers
     * [OVERLAP_PROBE_LINGER_MS] inside each callback, which is not an unfair burden but a fair
     * imitation of the real one — SuperPlayer's sink writes into a Media3 `SampleQueue` and takes
     * time doing it. Two deliveries in flight at once during that window are two concurrent writers.
     * // ref: ADR-0018 rule 2 — the seam is subscribe, receive, cancel, and the receiving side is a
     * single-writer queue.
     */
    public fun verifyDeliveriesDoNotOverlap() {
        val observed = observe(lingerMs = OVERLAP_PROBE_LINGER_MS)
        if (observed.overlapped) {
            refuse(
                OBLIGATION_1,
                "had two deliveries in flight on the sink at the same moment; " +
                    "${observed.deliveringThreads} different threads delivered during this " +
                    "subscription",
                "one delivery at a time. SuperPlayer writes what arrives into a sample queue with " +
                    "one writer assumed; two concurrent writers corrupt it, and what surfaces is a " +
                    "crash inside Media3 whose stack names nothing of yours. Delivering from " +
                    "several threads is allowed, but only with a happens-before between them",
            )
        }
    }

    /**
     * **Obligation 2** — [FrameSink.onTracks] is called exactly once, before the first frame, with
     * every track the subscription will deliver, and is never empty.
     *
     * // ref: ADR-0018 rule 2's #346 addendum — preparation completes once, with the whole track
     * group array a player then selects from, so a second declaration has nothing it could mean and
     * a track declared later has no index it could have been sent under.
     */
    public fun verifyTracksAreDeclaredOnceBeforeTheFirstFrame() {
        val observed = observe()
        if (observed.trackDeclarations == 0) {
            refuse(
                OBLIGATION_2,
                "delivered ${observed.frames.size} frame(s) and never called onTracks, within " +
                    "$TRACKS_BOUND_MS ms of being subscribed",
                "onTracks called before the first frame. Until it arrives no Format exists, so " +
                    "nothing is prepared and the player waits in STATE_BUFFERING for ever, with no " +
                    "error to report and nothing in a bug report to read",
            )
        }
        if (observed.framesBeforeTracks > 0) {
            refuse(
                OBLIGATION_2,
                "delivered ${observed.framesBeforeTracks} frame(s) before it called onTracks",
                "onTracks first. A frame names its track by position in that list, so a frame that " +
                    "arrives before the list has no track it could belong to",
            )
        }
        if (observed.trackDeclarations > 1) {
            refuse(
                OBLIGATION_2,
                "called onTracks ${observed.trackDeclarations} times",
                "exactly one declaration per subscription. Preparation completes once, with the " +
                    "whole set of tracks the player then selects from, so a second call would " +
                    "arrive after the player had already decided what it was playing and there " +
                    "would be nowhere to put what it carried. A publisher that may add a track " +
                    "later subscribes again",
            )
        }
        if (observed.tracks.isEmpty()) {
            refuse(
                OBLIGATION_2,
                "declared an empty track list",
                "at least one track. An empty list declares nothing to play and is refused; audio " +
                    "alone is a legitimate subscription and is one element of the same list",
            )
        }
    }

    /**
     * **Obligation 2** — a frame's [EncodedFrame.trackIndex] names a position in the list
     * [FrameSink.onTracks] declared.
     *
     * Its own check rather than a clause of the one above, because it is the half a single-track
     * transport passes by default and a two-track one has to get right: the index is a position in
     * the declared list, not an identifier of the transport's own.
     * // ref: ADR-0018 rule 2's #346 addendum — a frame naming a track nobody declared is reported
     * rather than dropped, because a silently discarded track is the failure the obligations exist
     * to prevent.
     */
    public fun verifyEveryFrameNamesADeclaredTrack() {
        val observed = observe()
        val declared = observed.tracksOrRefuse(OBLIGATION_2)
        val stray = observed.frames.firstOrNull { it.trackIndex !in declared.indices }
        if (stray != null) {
            refuse(
                OBLIGATION_2,
                "delivered a frame at ${stray.timestampUs} us naming trackIndex " +
                    "${stray.trackIndex}, where onTracks declared ${declared.size} track(s)" +
                    // An empty declaration is refused by its own check rather than here, so this
                    // renders the range only where there is one to render.
                    if (declared.isEmpty()) " and so has no index a frame could name" else " — indices 0..${declared.size - 1}",
                "a trackIndex that is a position in the list onTracks declared. It is an index " +
                    "into that list and not an identifier of your own; a single-track subscription " +
                    "leaves it at its default of 0",
            )
        }
    }

    /**
     * **Obligation 3** — the first frame delivered on each track can be decoded on its own.
     *
     * // spec: ITU-T H.264 §7.4.1.2.4 and ITU-T H.265 §8.1 — a decoder starts at a random access
     * point and what precedes one has references it does not hold.
     */
    public fun verifyEachTrackStartsWithAKeyframe() {
        val observed = observe()
        val declared = observed.tracksOrRefuse(OBLIGATION_3)
        observed.frames
            .groupBy { it.trackIndex }
            .forEach { (index, frames) ->
                val first = frames.first()
                if (!first.keyFrame) {
                    refuse(
                        OBLIGATION_3,
                        "delivered a frame with keyFrame = false first on track $index " +
                            "(${declared.codecAt(index)}), at ${first.timestampUs} us",
                        "a keyframe first on every track. A decoder fed a dependent frame with no " +
                            "reference renders either nothing or visible corruption, which is the " +
                            "classic \"it connects and the picture is garbage\" report. A " +
                            "transport that joins a stream mid-GOP drops frames until the first " +
                            "keyframe rather than forwarding them",
                    )
                }
            }
    }

    /**
     * **Obligation 4** — timestamps are microseconds on one monotonically non-decreasing timeline
     * **per track**.
     *
     * Per track and not across tracks, which is the half worth stating: the seam promises
     * monotonicity within a track and promises nothing about two tracks sharing an origin, because
     * an RTP stream's initial timestamp is random per SSRC and two tracks of one session routinely
     * start hours apart on the wire. SuperPlayer reconciles that rather than assuming it away, so
     * this check refuses a step backwards within a track and says nothing at all about the gap
     * between two.
     * // spec: RFC 3550 §5.1 — the initial RTP timestamp is random and is per synchronization
     * source, which is why a shared epoch is a thing to hand over rather than a thing to expect.
     * // ref: ADR-0018 rule 9 and its #346 addendum.
     */
    public fun verifyTimestampsAreMonotonicPerTrack() {
        val observed = observe()
        val declared = observed.tracksOrRefuse(OBLIGATION_4)
        val latest = mutableMapOf<Int, Long>()
        observed.frames.forEach { frame ->
            val previous = latest[frame.trackIndex]
            if (previous != null && frame.timestampUs < previous) {
                refuse(
                    OBLIGATION_4,
                    "delivered a frame at ${frame.timestampUs} us on track ${frame.trackIndex} " +
                        "(${declared.codecAt(frame.trackIndex)}) after one at $previous us on the " +
                        "same track — a step of ${previous - frame.timestampUs} us backwards",
                    "timestamps that never decrease within a track. SuperPlayer subtracts the " +
                        "first frame delivered on any track and treats the rest as offsets from " +
                        "it, so the epoch is yours — but a frame timed before the queue's read " +
                        "position is discarded silently, and a backwards step strands the playhead " +
                        "and stalls playback with a full buffer. Two tracks need not share an " +
                        "epoch; each must keep its own in order",
                )
            }
            latest[frame.trackIndex] = frame.timestampUs
        }
    }

    /**
     * **The codec string** — [RealtimeTrack.codec] is an RFC 6381 codecs string or an SDP encoding
     * name for one of the codecs this library maps, and anything else is refused rather than guessed.
     *
     * // spec: RFC 6381 §3.3 — the `codecs` parameter and its fourcc-prefixed, dot-separated
     * grammar; the second element of an `mp4a` string is an MP4 Registration Authority object type
     * indication, which is why `mp4a.69` and `mp4a.6B` are MP3 rather than AAC.
     * // spec: RFC 4566 §6 and RFC 4855 §3 — SDP's `a=rtpmap` encoding names, matched
     * case-insensitively.
     *
     * The families are listed here rather than read from the table that maps them, and the reason is
     * a module boundary rather than a preference: that table is `superplayer-realtime`'s and is
     * `internal`, while this module is phase 2 and may name nothing later than core. What is
     * duplicated is a **list of names** and deliberately not a parser — the mapping itself, with its
     * profile and level extraction, stays in one place.
     * `ScriptedFrameSourceConformanceTest.theSuiteAcceptsEveryCodecFamilyTheTableMaps` holds the two
     * lists to each other, from the module that can see both, so a family added there and not here
     * fails the build.
     */
    public fun verifyCodecStringsAreMapped() {
        val observed = observe()
        val declared = observed.tracksOrRefuse(CODEC_RULE)
        declared.forEachIndexed { index, track ->
            if (!isMapped(track.codec)) {
                refuse(
                    CODEC_RULE,
                    "declared track $index as \"${track.codec}\", which is neither an RFC 6381 " +
                        "codecs string nor an SDP encoding name for a codec SuperPlayer maps",
                    "one of H.264 (avc1, avc3, H264), H.265 (hvc1, hev1, H265), VP9 (vp09, VP9), " +
                        "AV1 (av01, AV1), AAC (mp4a.40.*, mp4a.67.*, MPEG4-GENERIC) or Opus " +
                        "(opus). SuperPlayer refuses an unrecognised string rather than guessing, " +
                        "because a codec decoded as the wrong one fails silently. Prefer the RFC " +
                        "6381 string where you have one: it states a profile and a level, which is " +
                        "what lets a rendition this device cannot decode be refused rather than " +
                        "failed at",
                )
            }
        }
    }

    /**
     * **Obligation 7, first half** — [RealtimeTrack.codecConfiguration] follows the codec's fourcc
     * and never the container the frames arrived in.
     *
     * `avc1` and `hvc1` carry an out-of-band configuration record and hand it over as
     * [RealtimeTrack.CodecConfiguration.Record], exactly as received. `avc3` and `hev1` carry their
     * parameter sets in band before every keyframe and hand over
     * [RealtimeTrack.CodecConfiguration.InBand]. Every other codec's record is passed through
     * unchanged, so nothing is required of it here.
     * // spec: ISO/IEC 14496-15 §5.3.3.1 — the `avc1` sample entry carries an
     * `AVCDecoderConfigurationRecord` while `avc3` carries its parameter sets in the samples;
     * §8.3.3.1 says the same of `hvc1` and `hev1`.
     * // ref: ADR-0018 rule 4 and its #345 addendum.
     */
    public fun verifyCodecConfigurationFollowsTheFourcc() {
        val observed = observe()
        val declared = observed.tracksOrRefuse(OBLIGATION_7)
        declared.forEachIndexed { index, track ->
            val fourcc = fourccOf(track.codec)
            val configuration = track.codecConfiguration
            if (fourcc in RECORD_FOURCCS && configuration !is RealtimeTrack.CodecConfiguration.Record) {
                refuse(
                    OBLIGATION_7,
                    "declared track $index as \"${track.codec}\" and handed over " +
                        "CodecConfiguration.InBand",
                    "a CodecConfiguration.Record for a `$fourcc` track, which carries its " +
                        "parameter sets out of band. A decoder configured from none, with none " +
                        "coming in the bitstream, renders nothing and reports nothing",
                )
            }
            if (fourcc in IN_BAND_FOURCCS && configuration is RealtimeTrack.CodecConfiguration.Record) {
                refuse(
                    OBLIGATION_7,
                    "declared track $index as \"${track.codec}\" and handed over a " +
                        "CodecConfiguration.Record of ${configuration.bytes.size} bytes",
                    "CodecConfiguration.InBand for a `$fourcc` track, which is self-describing: " +
                        "its parameter sets arrive in the bitstream before every keyframe. A " +
                        "decoder configured from a record whose parameter sets were about to " +
                        "arrive again in band is the silent failure ADR-0018 rule 4 exists to " +
                        "prevent",
                )
            }
            if (configuration is RealtimeTrack.CodecConfiguration.Record) {
                verifyRecordIsARecord(index, track, configuration.bytes)
            }
        }
    }

    /**
     * **Obligation 7, second half** — a track's samples are framed the way the same fourcc says, and
     * this is the half nothing but a suite like this can check.
     *
     * A track carrying a configuration record sends [EncodedFrame.payload] as **length-prefixed**
     * NAL units, each behind a length field of the width the record itself declared; a
     * self-describing track sends **Annex-B** start codes, the framing its in-band parameter sets
     * arrive in. Getting this backwards is worse than the rest of obligation 7, because both
     * directions read as valid bytes: a start code taken for a length declares a one-byte unit, the
     * picture data behind it becomes the next "length", and a decoder is fed a structurally
     * plausible stream of noise and reports nothing.
     *
     * What is walked is every frame of every H.264 or H.265 track. The other codecs have no NAL
     * framing to check — VP9, AV1, AAC and Opus samples are what they are — so they are left alone
     * rather than measured against a rule that does not apply to them.
     * // spec: ISO/IEC 14496-15 §5.3.3.1.2 — a sample of a track whose sample entry carries an
     * `avcC` is a run of NAL units each prefixed by its length, `lengthSizeMinusOne + 1` bytes wide;
     * §8.3.3.1.2 says the same of `hvcC`, whose `lengthSizeMinusOne` sits at a different offset.
     * // spec: ITU-T H.264 Annex B — the start code prefix `00 00 01`, optionally preceded by a
     * further zero byte, which is what a self-describing stream separates its units with.
     */
    public fun verifySamplesAreFramedAsTheFourccRequires() {
        val observed = observe()
        val declared = observed.tracksOrRefuse(OBLIGATION_7)
        // The length field size is a fact about the *track*, read once from its record, so it is
        // read here and not per frame: a record parsed again for every sample would be the same
        // answer at a cost, and a refusal from it would name a frame where the defect is the track's.
        val lengthSizes = declared.map { track ->
            val configuration = track.codecConfiguration
            if (configuration is RealtimeTrack.CodecConfiguration.Record && fourccOf(track.codec) in RECORD_FOURCCS) {
                nalLengthSize(fourccOf(track.codec), configuration.bytes, track)
            } else {
                null
            }
        }
        observed.frames.forEach { frame ->
            val track = declared.getOrNull(frame.trackIndex) ?: return@forEach
            val lengthSize = lengthSizes[frame.trackIndex]
            when {
                lengthSize != null -> verifyLengthPrefixed(frame, track, lengthSize)

                track.codecConfiguration == RealtimeTrack.CodecConfiguration.InBand &&
                    fourccOf(track.codec) in IN_BAND_FOURCCS -> verifyAnnexB(frame, track)
            }
        }
    }

    /**
     * **Obligation 8** — a payload is not touched after [FrameSink.onFrame] returns, and the same
     * array is never handed over twice.
     *
     * Both halves are read the same way: the sink keeps the array it was given *and* a copy of what
     * it held at the moment it arrived, so an array handed over twice is caught by identity and one
     * mutated after the call returned is caught by comparison. Pooling a buffer and refilling it is
     * the ordinary way this happens, and it is exactly what the obligation forbids: SuperPlayer does
     * not copy on receipt, so what a refilled buffer produces is a torn frame and a corruption that
     * moves when timing moves.
     */
    public fun verifyPayloadsAreNeitherReusedNorTouchedAgain() {
        val observed = observe()
        // Identity and not equality: two frames whose bytes happen to match are ordinary, while the
        // same array arriving twice is a pooled buffer being refilled.
        val seen = IdentityHashMap<ByteArray, ReceivedFrame>()
        observed.frames.forEach { frame ->
            val earlier = seen.put(frame.payload, frame)
            if (earlier != null) {
                refuse(
                    OBLIGATION_8,
                    "handed over the same ${frame.payload.size}-byte array for the frame at " +
                        "${earlier.timestampUs} us and the one at ${frame.timestampUs} us",
                    "a payload per frame. SuperPlayer does not copy on receipt, so a pooled buffer " +
                        "refilled for the next frame tears the one already queued — a corruption " +
                        "that moves when timing moves, and the least reproducible failure in this " +
                        "list",
                )
            }
        }
        val touched = observed.frames.firstOrNull { !it.payload.contentEquals(it.asDelivered) }
        if (touched != null) {
            refuse(
                OBLIGATION_8,
                "changed the ${touched.payload.size}-byte payload of the frame at " +
                    "${touched.timestampUs} us after onFrame had returned",
                "a payload left alone once it has been handed over. What is queued is the array " +
                    "itself, so writing into it afterwards rewrites a frame the player has already " +
                    "accepted",
            )
        }
    }

    /**
     * **[FrameSink.onEnded] and [FrameSink.onError] are final** — after either, no frame and no
     * second terminal callback arrives.
     *
     * What is deliberately *not* refused here is a source that has done neither. A realtime stream
     * has no end to reach (ADR-0018 rule 5) and a publisher going quiet is an ordinary live edge to
     * sit at rather than a failure, so a subscription still open when this check's observation
     * window closes passes. What would be a failure is a declared track going quiet while the others
     * keep arriving, and that is not this check's: SuperPlayer bounds it itself, in media time
     * against the furthest-ahead track, and ends the session with `RealtimeTrackStalledException`.
     * Nothing a transport can do makes that bound its own to enforce — what it can do is deliver
     * every track it declared, and **no check here reads that either**: a track declared and never
     * delivered is invisible to every check below, because each one iterates the frames that did
     * arrive. Bounding it here would mean deciding how long a track may take to start, which is a
     * number about a publisher rather than about a transport.
     */
    public fun verifyTerminationIsFinal() {
        val observed = observe()
        val terminal = observed.terminal ?: return
        if (observed.terminalCallbacks > 1) {
            refuse(
                TERMINATION_RULE,
                "called $terminal after it had already ended the subscription " +
                    "(${observed.terminalCallbacks} terminal callbacks in all)",
                "one terminal callback. onEnded and onError each say the subscription is over, and " +
                    "the difference between them is what a viewer is shown — a stream that ended " +
                    "of its own accord drains what is queued and ends normally, while one that " +
                    "failed reports the cause. A second call has nothing left to say",
            )
        }
        if (observed.framesAfterTerminal > 0) {
            refuse(
                TERMINATION_RULE,
                "delivered ${observed.framesAfterTerminal} frame(s) after it had called $terminal",
                "no delivery after the subscription has ended. Playback drains what was queued " +
                    "when the end arrived, so a frame handed over afterwards is written into a " +
                    "queue nobody will read and is lost without a word",
            )
        }
    }

    /**
     * **Obligation 9** — [FrameSource.cancel] stops delivery, and no callback arrives after it
     * returns.
     *
     * The bound below is the one thing this check waits for, and it is about telling *stopped* from
     * *not stopped at all* rather than about being fast. A transport that hands its shutdown to
     * another thread and returns is the ordinary way this is broken, which is why the check watches
     * after `cancel` has returned rather than merely counting what arrived before it.
     */
    public fun verifyCancellationStopsDelivery() {
        val observed = observe(watchAfterCancel = true)
        if (observed.callbacksAfterCancel > 0) {
            refuse(
                OBLIGATION_9,
                "made ${observed.callbacksAfterCancel} further callback(s) in the " +
                    "$CANCELLATION_BOUND_MS ms after cancel() returned",
                "a cancel() that returns only once no further callback can be made. The player " +
                    "releases its sample queues as soon as cancel returns, so a write that lands " +
                    "afterwards is a write into a queue that is gone — a crash at an unrelated " +
                    "moment, in a stack that names nothing of yours. A transport that shuts down " +
                    "on a thread of its own joins that thread before returning",
            )
        }
    }

    /** The record a track declared, checked as far as being a record of that codec at all. */
    private fun verifyRecordIsARecord(index: Int, track: RealtimeTrack, bytes: ByteArray) {
        if (startCodeLength(bytes) > 0) {
            refuse(
                OBLIGATION_7,
                "handed over a CodecConfiguration.Record for track $index (\"${track.codec}\") " +
                    "whose first bytes are an Annex-B start code",
                "the configuration record exactly as your publisher sent it — an " +
                    "AVCDecoderConfigurationRecord for `avc1`, an HEVCDecoderConfigurationRecord " +
                    "for `hvc1` — and not the parameter sets unwrapped out of it. SuperPlayer " +
                    "converts the record to what the decoder wants, including reading the NAL " +
                    "length field size out of it; a transport that converts first is one that has " +
                    "to know what a decoder wants",
            )
        }
        val fourcc = fourccOf(track.codec)
        // spec: ISO/IEC 14496-15 §5.3.3.1.2 — `configurationVersion` is the record's first byte and
        // is 1. Checked for `avc1` alone: the HEVC record's first byte is its own
        // `configurationVersion` and also 1, but every other codec's record is passed through
        // unchanged by this library, so there is nothing here to hold it to.
        if (fourcc == "avc1" && (bytes.size < AVCC_MINIMUM_BYTES || bytes[0].toInt() != 1)) {
            refuse(
                OBLIGATION_7,
                "handed over ${bytes.size} bytes as track $index's AVCDecoderConfigurationRecord, " +
                    "beginning ${bytes.take(AVCC_MINIMUM_BYTES).joinToString(" ") { "%02x".format(it) }}",
                "an AVCDecoderConfigurationRecord: at least $AVCC_MINIMUM_BYTES bytes, the first " +
                    "of them the configurationVersion 1. SuperPlayer reads the NAL length field " +
                    "size and the parameter sets out of it, and refuses one that does not parse " +
                    "rather than configuring a decoder from half of it",
            )
        }
    }

    /**
     * The NAL length field width this track's record declares, which is what its samples are framed
     * with.
     *
     * // spec: ISO/IEC 14496-15 §5.3.3.1.2 — in an `AVCDecoderConfigurationRecord`,
     * `lengthSizeMinusOne` is the low two bits of the fifth byte. §8.3.3.1.2 puts the same field in
     * the low two bits of the twenty-second byte of an `HEVCDecoderConfigurationRecord`.
     */
    private fun nalLengthSize(fourcc: String, record: ByteArray, track: RealtimeTrack): Int {
        val offset = if (fourcc == "hvc1") HVCC_LENGTH_SIZE_OFFSET else AVCC_LENGTH_SIZE_OFFSET
        if (record.size <= offset) {
            refuse(
                OBLIGATION_7,
                "handed over a ${record.size}-byte configuration record for \"${track.codec}\", " +
                    "which is too short to carry the NAL length field size at byte $offset",
                "the whole configuration record. Its length field size is what every sample of " +
                    "this track is framed with, so a record that stops before it leaves nothing " +
                    "able to read a frame",
            )
        }
        return (record[offset].toInt() and 0x03) + 1
    }

    /** One length-prefixed sample, walked unit by unit until it ends exactly. */
    private fun verifyLengthPrefixed(frame: ReceivedFrame, track: RealtimeTrack, lengthSize: Int) {
        val payload = frame.asDelivered
        if (startCodeLength(payload) > 0) {
            refuse(
                OBLIGATION_7,
                "framed the sample at ${frame.timestampUs} us on track ${frame.trackIndex} " +
                    "(\"${track.codec}\") with an Annex-B start code, although the track carries a " +
                    "configuration record",
                "length-prefixed NAL units on a track that carries a record, each behind a " +
                    "$lengthSize-byte length field as the record itself declares. This is the " +
                    "worst of these to get wrong, because both directions read as valid: a start " +
                    "code taken for a length declares a one-byte unit, the picture data behind it " +
                    "becomes the next length, and the decoder is fed a structurally plausible " +
                    "stream of noise and reports nothing",
            )
        }
        var offset = 0
        var units = 0
        while (offset < payload.size) {
            if (offset + lengthSize > payload.size) {
                refuse(
                    OBLIGATION_7,
                    "left ${payload.size - offset} byte(s) after $units NAL unit(s) in the sample " +
                        "at ${frame.timestampUs} us on track ${frame.trackIndex} — too few for a " +
                        "$lengthSize-byte length field",
                    "a sample that is a whole number of length-prefixed NAL units and ends exactly " +
                        "where the last one does",
                )
            }
            var length = 0L
            for (byte in 0 until lengthSize) {
                length = (length shl 8) or (payload[offset + byte].toLong() and 0xFF)
            }
            if (length <= 0 || offset + lengthSize + length > payload.size) {
                refuse(
                    OBLIGATION_7,
                    "declared a NAL unit of $length bytes at offset $offset of the " +
                        "${payload.size}-byte sample at ${frame.timestampUs} us on track " +
                        "${frame.trackIndex}, read as a $lengthSize-byte length field",
                    "a length field naming a unit that fits inside the sample. A run past the end " +
                        "is what a sample framed some other way looks like when it is read as " +
                        "length-prefixed — container framing left on, or Annex-B start codes under " +
                        "a fourcc that promised lengths",
                )
            }
            offset += lengthSize + length.toInt()
            units++
        }
        if (units == 0) {
            refuse(
                OBLIGATION_7,
                "delivered an empty payload for the frame at ${frame.timestampUs} us on track " +
                    "${frame.trackIndex}",
                "at least one NAL unit per frame. A frame with no bytes is a frame the decoder " +
                    "cannot be given and the queue cannot describe",
            )
        }
    }

    /** One Annex-B sample, checked as far as the framing its first bytes state. */
    private fun verifyAnnexB(frame: ReceivedFrame, track: RealtimeTrack) {
        if (startCodeLength(frame.asDelivered) == 0) {
            refuse(
                OBLIGATION_7,
                "framed the sample at ${frame.timestampUs} us on track ${frame.trackIndex} " +
                    "(\"${track.codec}\") with no Annex-B start code, beginning " +
                    frame.asDelivered.take(4).joinToString(" ") { "%02x".format(it) },
                "Annex-B start codes on a self-describing track, the same framing its in-band " +
                    "parameter sets arrive in. A `${fourccOf(track.codec)}` track states that its " +
                    "parameter sets are in the bitstream, and nothing can find them in a sample " +
                    "whose units are not delimited",
            )
        }
    }

    /**
     * Subscribes, keeps what arrives, and cancels.
     *
     * One subscription per check, which is the lifecycle a player gives a source: [subscribe] is
     * called on a thread of its own because a transport is entitled to deliver its whole script
     * inside it — a fake usually does — and a suite that called it on its own thread would then
     * never reach the assertion.
     */
    private fun observe(
        lingerMs: Long = 0,
        watchAfterCancel: Boolean = false,
    ): Observation {
        val source = sources.open(uri)
        val sink = RecordingSink(lingerMs)
        val failure = AtomicReference<Throwable>()
        val subscription = thread(isDaemon = true, name = "frame-source-conformance") {
            try {
                source.subscribe(sink)
            } catch (raised: Throwable) {
                failure.compareAndSet(null, raised)
            }
        }
        if (!sink.awaitTracks(TRACKS_BOUND_MS)) {
            // Not a refusal here: which obligation an absent declaration breaks depends on the
            // check that is asking, and several of them have something to say about a source that
            // delivered nothing at all. The observation is returned as it stands and each check
            // names its own rule.
            failure.get()?.let { raised -> refuseSubscription(raised) }
        }
        sink.awaitFrames(FRAMES_BOUND_MS)
        failure.get()?.let { raised -> refuseSubscription(raised) }
        // The frames a check waits for are a *lower* bound, so the delivery is given a moment to
        // finish what it was in the middle of before anything is read off it. Without it, a source
        // that ends its subscription just after the last frame this waited for would be read before
        // it had done so, and what follows the end is exactly what one of the checks is about.
        //
        // The join is the deterministic half and is there for the shape a fake usually has: a
        // transport that delivers its whole script inside `subscribe` has demonstrably finished
        // when that call returns, so nothing about those checks rests on a duration. The pause is
        // the best there is for a transport that returned from `subscribe` and delivers from a
        // thread of its own, where nothing here can know what it was in the middle of.
        subscription.join(SETTLE_BOUND_MS)
        pause(SETTLE_BOUND_MS)
        source.cancel()
        // Counted from the moment cancel *returned*, which is what the obligation says: a callback
        // made inside cancel is a transport draining what it had, and refusing that would be
        // refusing something the rule allows.
        val afterCancel = sink.callbacks
        if (watchAfterCancel) pause(CANCELLATION_BOUND_MS)
        return sink.observation(callbacksAfterCancel = if (watchAfterCancel) sink.callbacks - afterCancel else 0)
    }

    private fun refuseSubscription(raised: Throwable): Nothing = refuse(
        SUBSCRIPTION_RULE,
        "raised ${raised.javaClass.name} (${raised.message}) out of subscribe()",
        "a subscription that either delivers or reports its failure through FrameSink.onError. " +
            "Nothing on this side waits for a transport to connect, so a throw out of subscribe " +
            "reaches a thread the player does not own and is lost; onError is what reaches the " +
            "consumer, as the cause of the PlaybackException onPlayerError carries",
    )
}

/** What one subscription delivered, in the order it arrived. */
private class Observation(
    val tracks: List<RealtimeTrack>,
    val trackDeclarations: Int,
    val framesBeforeTracks: Int,
    val frames: List<ReceivedFrame>,
    val overlapped: Boolean,
    val deliveringThreads: Int,
    val terminal: String?,
    val terminalCallbacks: Int,
    val framesAfterTerminal: Int,
    val callbacksAfterCancel: Int,
) {

    /**
     * The declared tracks, or a refusal under the asking check's own rule.
     *
     * Every check but the first two reads the declaration, so a source that never made one would
     * otherwise pass them all by having delivered nothing — which is the vacuous pass a suite like
     * this exists to avoid.
     */
    fun tracksOrRefuse(rule: String): List<RealtimeTrack> {
        if (trackDeclarations == 0) {
            refuse(
                rule,
                "never called onTracks, within $TRACKS_BOUND_MS ms of being subscribed, so there " +
                    "is nothing to check this against",
                "onTracks called before the first frame, with every track the subscription will " +
                    "deliver (obligation 2). Every other obligation is stated in terms of it",
            )
        }
        return tracks
    }
}

/**
 * The one message shape, composed in one place: the rule, then what this source did, then what the
 * rule requires, then where the whole obligation is written out.
 */
private fun refuse(rule: String, did: String, requires: String): Nothing =
    throw FrameSourceConformanceException(
        buildString {
            append("This FrameSource does not satisfy $rule.\n")
            append("  What this source did: $did.\n")
            append("  What the rule requires: $requires.\n")
            append("  The obligation is written out in full in FrameSource's KDoc.")
        },
    )

/**
 * Waits [millis], and nothing else.
 *
 * A latch nobody counts down rather than a `Thread.sleep`, so a fixed wait is expressed in the same
 * vocabulary as every other bound here and is interrupted the same way. Both are real time on this
 * thread; what Robolectric simulates is `SystemClock` and the main looper, neither of which is
 * involved.
 */
private fun pause(millis: Long) {
    CountDownLatch(1).await(millis, TimeUnit.MILLISECONDS)
}

/** The codec of a declared track, for a message, or a stand-in where the index is not one. */
private fun List<RealtimeTrack>.codecAt(index: Int): String = getOrNull(index)?.codec ?: "undeclared"

/**
 * An obligation [FrameSourceConformance] found broken, naming the rule, what the source did and what
 * the rule requires.
 *
 * An `AssertionError` so that a test framework renders it as a failed assertion rather than as an
 * error in the test itself, which is what it is: the transport under test is the thing that failed.
 */
public class FrameSourceConformanceException internal constructor(message: String) : AssertionError(message)

/** One frame as it was delivered, kept with a copy of the bytes it held at that moment. */
private class ReceivedFrame(frame: EncodedFrame) {
    val trackIndex: Int = frame.trackIndex
    val timestampUs: Long = frame.timestampUs
    val keyFrame: Boolean = frame.keyFrame

    /** The array itself, so that one handed over twice is caught by identity (obligation 8). */
    val payload: ByteArray = frame.payload

    /** What it held on arrival, so that one written into afterwards is caught by comparison. */
    val asDelivered: ByteArray = frame.payload.copyOf()
}

/**
 * The sink a check subscribes: it keeps everything, refuses nothing, and never touches what it was
 * given.
 *
 * [lingerMs] is the overlap probe and is zero everywhere but in the check that reads it — see
 * [FrameSourceConformance.verifyDeliveriesDoNotOverlap] for why widening the window is a fair
 * imitation of SuperPlayer's own sink rather than an unfair burden on a transport.
 */
private class RecordingSink(private val lingerMs: Long) : FrameSink {

    private val lock = Any()
    private val inFlight = AtomicInteger()
    private val tracksDeclared = CountDownLatch(1)
    private val framesWanted = CountDownLatch(FRAMES_OBSERVED)

    private var tracks: List<RealtimeTrack> = emptyList()
    private var trackDeclarations = 0
    private var framesBeforeTracks = 0
    private val frames = mutableListOf<ReceivedFrame>()
    private val threads = mutableSetOf<Long>()
    private var overlapped = false
    private var terminal: String? = null
    private var terminalCallbacks = 0
    private var framesAfterTerminal = 0

    /** Every callback made so far, which is what the cancellation check counts across `cancel()`. */
    @Volatile
    var callbacks: Int = 0
        private set

    override fun onTracks(tracks: List<RealtimeTrack>) = record {
        trackDeclarations++
        // The first declaration is the one every check reads: a second is a violation in itself and
        // must not be allowed to overwrite what the frames were sent against.
        if (trackDeclarations == 1) this.tracks = tracks.toList()
        tracksDeclared.countDown()
    }

    override fun onFrame(frame: EncodedFrame) = record {
        if (trackDeclarations == 0) framesBeforeTracks++
        if (terminal != null) framesAfterTerminal++
        frames += ReceivedFrame(frame)
        framesWanted.countDown()
    }

    override fun onEnded() = record { end("onEnded") }

    override fun onError(cause: Throwable) = record { end("onError") }

    private fun end(which: String) {
        terminalCallbacks++
        if (terminal == null) terminal = which
        // Nothing more will arrive, so a check waiting for its frames stops waiting rather than
        // paying the whole bound for a subscription that has already said it is over.
        repeat(FRAMES_OBSERVED) { framesWanted.countDown() }
        tracksDeclared.countDown()
    }

    fun awaitTracks(boundMs: Long): Boolean = tracksDeclared.await(boundMs, TimeUnit.MILLISECONDS)

    fun awaitFrames(boundMs: Long): Boolean = framesWanted.await(boundMs, TimeUnit.MILLISECONDS)

    fun observation(callbacksAfterCancel: Int): Observation = synchronized(lock) {
        Observation(
            tracks = tracks,
            trackDeclarations = trackDeclarations,
            framesBeforeTracks = framesBeforeTracks,
            frames = frames.toList(),
            overlapped = overlapped,
            deliveringThreads = threads.size,
            terminal = terminal,
            terminalCallbacks = terminalCallbacks,
            framesAfterTerminal = framesAfterTerminal,
            callbacksAfterCancel = callbacksAfterCancel,
        )
    }

    /**
     * One callback, recorded.
     *
     * The in-flight count is raised and the linger taken **outside** the lock, because the lock is
     * this recorder's own and taking it first would serialize exactly the concurrency obligation 1
     * is about and hide it.
     */
    private fun record(body: () -> Unit) {
        val concurrent = inFlight.incrementAndGet() > 1
        try {
            if (lingerMs > 0) Thread.sleep(lingerMs)
            synchronized(lock) {
                if (concurrent || inFlight.get() > 1) overlapped = true
                threads += Thread.currentThread().id
                callbacks++
                body()
            }
        } finally {
            inFlight.decrementAndGet()
        }
    }
}

/**
 * The names a failure gives the obligation it found broken, and the bounds the checks apply.
 *
 * Top level and private rather than in a companion, because a `const val` in a companion reaches the
 * tracked public API even where the companion itself is private (`docs/api-surface.md`), and this
 * class's own spellings are not API for anyone to be held to.
 */
private const val OBLIGATION_1 = "FrameSource obligation 1 (deliveries are ordered against one another)"
private const val OBLIGATION_2 =
    "FrameSource obligation 2 (every track is declared once, before the first frame)"
private const val OBLIGATION_3 = "FrameSource obligation 3 (each track's first frame is a keyframe)"
private const val OBLIGATION_4 =
    "FrameSource obligation 4 (timestamps do not go backwards within a track)"
private const val OBLIGATION_7 =
    "FrameSource obligation 7 (codec-specific data and sample framing follow the fourcc)"
private const val OBLIGATION_8 =
    "FrameSource obligation 8 (a payload is handed over once and not touched again)"
private const val OBLIGATION_9 = "FrameSource obligation 9 (cancel stops delivery before it returns)"

/**
 * The two rules that are not one of the nine numbered obligations: the codec string's own
 * requirement, which [RealtimeTrack.codec]'s KDoc carries, and the finality of a terminal callback,
 * which [FrameSink.onEnded]'s and [FrameSink.onError]'s do.
 */
private const val CODEC_RULE = "the codec string RealtimeTrack.codec requires"
private const val TERMINATION_RULE = "the finality of FrameSink.onEnded and FrameSink.onError"

/** Not an obligation of the seam but a precondition of reading one: subscribe() must not throw. */
private const val SUBSCRIPTION_RULE = "FrameSource.subscribe's contract (it reports rather than throws)"

/**
 * How many frames a check waits for.
 *
 * Small on purpose. Every obligation these checks read is a property of the *shape* of a delivery
 * rather than of its length — a first keyframe per track, a timestamp that does not go back, a
 * framing that walks — so a longer observation would buy a longer wait and no more evidence, and a
 * consumer pointing this at a live publisher pays it eleven times over.
 */
private const val FRAMES_OBSERVED = 16

/**
 * How long a declaration is waited for. Wide enough that a transport connecting to a real relay is
 * never the finding, and finite so that one which never answers fails as itself rather than as a
 * suite that hung.
 */
private const val TRACKS_BOUND_MS = 10_000L

/** How long [FRAMES_OBSERVED] frames are waited for, once the tracks have been declared. */
private const val FRAMES_BOUND_MS = 10_000L

/**
 * How long a delivery is given to finish what it was in the middle of, once the frames a check waits
 * for have arrived. Short, because it is paid by every check: what it covers is the gap between the
 * last frame waited for and a callback that was always going to follow it, not a transport that is
 * still connecting.
 */
private const val SETTLE_BOUND_MS = 250L

/**
 * How long a callback is watched for after `cancel()` has returned. This is about telling *stopped*
 * from *not stopped at all* rather than about being fast: a transport that hands its shutdown to
 * another thread and returns is the ordinary way obligation 9 is broken, and that thread's next
 * delivery is typically its next frame.
 */
private const val CANCELLATION_BOUND_MS = 1_000L

/**
 * How long the overlap probe holds each callback. Long enough that two genuinely concurrent
 * deliverers are seen inside the window on any machine, short enough that
 * [FRAMES_OBSERVED] of them cost well under a second.
 */
private const val OVERLAP_PROBE_LINGER_MS = 10L

/** Fourccs that carry an out-of-band configuration record and frame their samples length-prefixed. */
private val RECORD_FOURCCS = setOf("avc1", "hvc1")

/** Fourccs that are self-describing: parameter sets in band, samples framed with Annex-B start codes. */
private val IN_BAND_FOURCCS = setOf("avc3", "hev1")

/**
 * The codec families SuperPlayer maps, by the token an RFC 6381 string's first element or an SDP
 * encoding name carries, lowercase. `mp4a` is absent deliberately — see [isMapped].
 */
private val MAPPED_TOKENS = setOf(
    "avc1", "avc3", "hvc1", "hev1", "vp09", "av01", "opus",
    "h264", "h265", "vp9", "av1", "mpeg4-generic",
)

/**
 * MP4 Registration Authority object type indications for AAC: `0x40` MPEG-4 Audio and `0x67` MPEG-2
 * AAC LC. The rest of `mp4a` is not AAC — `0x69` and `0x6B` are MP3 — which is why the whole fourcc
 * cannot be mapped by its first element alone.
 * // spec: RFC 6381 §3.3 and https://mp4ra.org/registered-types/object-types.
 */
private val AAC_OBJECT_TYPES = setOf(0x40, 0x67)

/**
 * The shortest `AVCDecoderConfigurationRecord` this suite can read anything out of: the six bytes
 * before the first parameter set's own length field — `configurationVersion`, the three profile and
 * level bytes, `lengthSizeMinusOne` and `numOfSequenceParameterSets`.
 *
 * Deliberately not "the shortest record that is valid", which is 8 with one parameter set: nothing
 * here parses the parameter sets, so a bound that implied it did would be a bound this suite cannot
 * stand behind. // spec: ISO/IEC 14496-15 §5.3.3.1.2.
 */
private const val AVCC_MINIMUM_BYTES = 6

/** `lengthSizeMinusOne`'s byte in an `AVCDecoderConfigurationRecord`. // spec: ISO/IEC 14496-15 §5.3.3.1.2. */
private const val AVCC_LENGTH_SIZE_OFFSET = 4

/** `lengthSizeMinusOne`'s byte in an `HEVCDecoderConfigurationRecord`. // spec: ISO/IEC 14496-15 §8.3.3.1.2. */
private const val HVCC_LENGTH_SIZE_OFFSET = 21

/** An RFC 6381 string's fourcc, or an SDP encoding name, lowercased. // spec: RFC 6381 §3.3. */
private fun fourccOf(codec: String): String = codec.substringBefore('.').lowercase(Locale.ROOT)

/** Whether SuperPlayer maps [codec] to a decoder. */
private fun isMapped(codec: String): Boolean {
    val token = fourccOf(codec)
    if (token in MAPPED_TOKENS) return true
    if (token != "mp4a") return false
    // Hexadecimal without a prefix, per the registration; an absent or unreadable one is not AAC.
    return codec.split('.').getOrNull(1)?.toIntOrNull(16) in AAC_OBJECT_TYPES
}

/**
 * The length of the Annex-B start code [bytes] begins with, or zero.
 * // spec: ITU-T H.264 Annex B — `00 00 01`, optionally preceded by a further zero byte.
 */
private fun startCodeLength(bytes: ByteArray): Int = when {
    bytes.size >= 4 && bytes[0] == ZERO && bytes[1] == ZERO && bytes[2] == ZERO && bytes[3] == ONE -> 4
    bytes.size >= 3 && bytes[0] == ZERO && bytes[1] == ZERO && bytes[2] == ONE -> 3
    else -> 0
}

private const val ZERO: Byte = 0
private const val ONE: Byte = 1
