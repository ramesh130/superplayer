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

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import java.io.IOException

/**
 * The realtime transports a player can reach, and the URI schemes that reach them.
 *
 * `superplayer-realtime` is where one comes from; nothing in `superplayer-core` builds one, and a
 * player built without [SuperPlayer.Builder.setRealtime] has no realtime path at all — a URI with a
 * realtime scheme on such a player is whatever Media3 makes of it, which is a failed load naming a
 * scheme it has no source for (ADR-0018 rule 12).
 *
 * The type is core's so that the builder can take it without naming a phase 13 module, and its
 * constructor is `internal` so that only a friend of core can make one: what it carries is a
 * `MediaSource`, which is Media3 `@UnstableApi` vocabulary that ADR-0001 rule 2 keeps out of public
 * API. This is [ContentCache]'s shape taken again, for [ContentCache]'s reason.
 *
 * **The discriminator is the scheme and nothing else** (ADR-0018 rule 12). A `MediaItem` carrying a
 * realtime scheme and a contradictory MIME type resolves on the scheme, because the MIME type is
 * supplied by the same consumer who wrote the URI and nothing can check it against the transport
 * that answers.
 */
public abstract class RealtimeSources internal constructor(

    /**
     * The URI schemes these transports answer, lowercase and without the `:`.
     *
     * A set rather than one string because a module may carry more than one protocol, and compared
     * case-insensitively at the dispatch point: `Uri.getScheme()` returns what the consumer typed.
     */
    internal val schemes: Set<String>,

    /** What builds the `MediaSource` for a URI one of [schemes] named; see [RealtimeMediaSources]. */
    internal val mediaSources: RealtimeMediaSources,
) {

    /** Whether [scheme] is one of [schemes]. Case-insensitive, because a URI's scheme is the consumer's spelling. */
    internal fun answers(scheme: String?): Boolean =
        scheme != null && schemes.contains(scheme.lowercase())
}

/**
 * What a [RealtimeSources] contributes to `TransferChain`'s one dispatch point: the `MediaSource`
 * for an item whose scheme it answers.
 *
 * Called once per item, from the media source factory `TransferChain` assembles, beside the HLS and
 * DASH branches rather than at a second composition point (ADR-0018 rule 12, `TransferChain`'s KDoc).
 *
 * What it returns reaches none of the chain: no `DataSource` is opened for a realtime item, so the
 * cache slot, the header-refresh layer, CMCD, the bandwidth meter and the load-error policy are all
 * absent by construction rather than by choice (ADR-0018 rule 6). That is why this takes the item
 * alone and is handed no chain to load through.
 */
internal fun interface RealtimeMediaSources {

    /** The source for [item], whose URI carries one of the answering schemes. */
    fun create(item: MediaItem): MediaSource
}

/**
 * A realtime stream was asked to start somewhere other than at its live edge, and the request was
 * refused rather than coerced (ADR-0018 rule 5).
 *
 * Raised from [SuperPlayer.setMediaRequest] — and from a session's own adoption, which is the same
 * call — for a [MediaRequest] whose first source carries a realtime scheme and whose
 * [MediaRequest.StartPosition] is [MediaRequest.StartPosition.At] or
 * [MediaRequest.StartPosition.ResumeFromLastKnown].
 *
 * ## Why refused rather than coerced
 *
 * A realtime timeline is live and unseekable: there is no buffer behind the edge to seek into,
 * because nothing was received before the subscription began. Quietly starting at the edge instead
 * would be a resume that *looks like it worked* — the app asked for 12:04 and got live, with nothing
 * anywhere saying so, and the viewer's own reading of where they were is the only evidence. That is
 * the defect this refusal exists to make impossible, and it is the same argument
 * [HttpStackUnsupportedException] makes for refusing a substituted HTTP stack.
 *
 * ## Why an `UnsupportedOperationException`
 *
 * The three shapes core already raises are described on [HttpStackUnsupportedException], and this is
 * that record's third case: a *configuration* that cannot be honoured, before any frame is asked for
 * and with no session to end. It is not an `IOException`, because no load went wrong — and because
 * Kotlin emits no `throws` clause, so a checked exception out of [SuperPlayer.setMediaRequest] is one
 * a Java consumer could not name in a `catch` at all.
 *
 * It carries no `FailureClass` for [HttpStackUnsupportedException]'s reason: `ErrorClassifier`'s
 * every answer is about which rung of the fallback ladder may be climbed, and there is no rung for
 * "this stream has no past".
 *
 * ## What to do instead
 *
 * Play the realtime source at [MediaRequest.StartPosition.Beginning], which on a live stream is its
 * edge. Content that must resume where a viewer left it needs a source that has a past to resume
 * into — a later entry of [MediaRequest.sources] is where one goes, which is also ADR-0018 rule 6's
 * documented way to have a fallback at all.
 */
public class RealtimeStreamNotSeekableException internal constructor(

    /** The realtime scheme the refused source named, without the `:`. */
    public val scheme: String,

    /** The start position that was asked for, as the class name a consumer wrote — `At`, `ResumeFromLastKnown`. */
    public val startPosition: String,
) : UnsupportedOperationException(
    "A $scheme: stream is live and unseekable, so StartPosition.$startPosition cannot be honoured. " +
        "SuperPlayer refuses it rather than starting at the live edge, because a coerced resume is " +
        "indistinguishable from one that worked. Use StartPosition.Beginning, or give the request a " +
        "non-realtime source to resume into.",
)

/**
 * A realtime transport delivered a track whose codec string SuperPlayer cannot map to a decoder.
 *
 * Raised from the seam's own mapping, when [RealtimeTrack.codec] is neither an RFC 6381 codecs
 * string for a codec this library maps nor an SDP encoding name for one.
 *
 * ## Why a refusal rather than a guess
 *
 * Elsewhere in this repository an unknown refuses nothing — a device that reports an empty codec
 * table must not exclude every rendition. That rule is about **constraints**, and this is an
 * **assertion about bytes**: a codec nobody mapped that is quietly treated as the one codec we do
 * know would configure a decoder cleanly and then render nothing, or render corruption, with the
 * player reporting a decoder failure whose stated cause names the wrong codec entirely. A refusal
 * that says which string arrived is the only version of this a bug report can act on, which is why
 * the string is a property and is in the message.
 *
 * ## Why an `IOException`
 *
 * The three shapes core raises are described on [HttpStackUnsupportedException], and this is the
 * first of them: something that travels a transfer. The track arrived over a transport that was
 * already subscribed, so a session exists and a delivery is what went wrong — and Media3 asks for
 * exactly this type at the two points a `MediaPeriod` may report a failure of preparation, so the
 * refusal reaches the consumer as the cause of the `PlaybackException` `onPlayerError` carries,
 * exactly as a failed segment load does. [RealtimeStreamNotSeekableException] is the *other* shape
 * for the neighbouring reason: it refuses a **configuration** before any frame is asked for.
 *
 * It adds no `FailureClass` leaf. ADR-0018 rule 6 keeps a realtime stream off the ladder's
 * load-error rungs, and the rung that still applies — a later entry of [MediaRequest.sources], which
 * is where a transport-independent fallback belongs — is reached by the ordinary player-error path
 * and needs no taxonomy of its own.
 *
 * ## What to do instead
 *
 * Publish one of the codecs this library maps — H.264, H.265, VP9, AV1, AAC or Opus — or, where the
 * codec is one of those under a spelling that is neither RFC 6381's nor SDP's, hand over the RFC
 * 6381 string for it, which is what [RealtimeTrack.codec] is documented to carry.
 */
public class UnsupportedRealtimeCodecException internal constructor(

    /** The codec string the transport delivered, verbatim, so a bug report carries the thing itself. */
    public val codec: String,
) : IOException(
    "No decoder mapping for realtime codec \"$codec\". SuperPlayer maps H.264 (avc1, avc3, H264), " +
        "H.265 (hvc1, hev1, H265), VP9 (vp09, VP9), AV1 (av01, AV1), AAC (mp4a.40.*, MPEG4-GENERIC) " +
        "and Opus (opus), as RFC 6381 codecs strings or SDP encoding names. It refuses an " +
        "unrecognised string rather than guessing, because a codec decoded as the wrong one fails " +
        "silently.",
)

/**
 * A realtime transport delivered bytes that are not the shape its own codec string says they are.
 *
 * Raised where SuperPlayer reads what the transport handed over and finds it cannot be what the
 * fourcc claims: an `avcC` or `hvcC` record that does not parse or ends inside a parameter set, a
 * [RealtimeTrack.CodecConfiguration.Record] on a self-describing fourcc (`avc3`, `hev1`) or
 * [RealtimeTrack.CodecConfiguration.InBand] on one that carries a record (`avc1`, `hvc1`), or a
 * length-prefixed sample whose length field runs past the end of the frame.
 *
 * ## Why a refusal rather than a best effort
 *
 * [UnsupportedRealtimeCodecException]'s argument, one layer in. That one refuses a codec nobody
 * mapped; this one refuses bytes that *are* a mapped codec's and do not hold together. Reading a
 * record up to the point it stops making sense, or a frame up to the byte the length field
 * overran, configures a decoder that then renders nothing or renders corruption — the failure
 * ADR-0018 rule 4 and [FrameSource]'s obligation 7 exist to prevent, and the one #340 observed is
 * indistinguishable from a working stream until a viewer looks at it. [reason] says which of the
 * cases above it was, because that is what tells a transport author whether the bug is in their
 * publisher or in their adapter.
 *
 * ## Why an `IOException`
 *
 * [UnsupportedRealtimeCodecException]'s shape and for its reason — the first of the three described
 * on [HttpStackUnsupportedException]: the transport was already subscribed, so a session exists and
 * what went wrong is a delivery. It is deliberately **not** a fourth shape.
 *
 * It adds no `FailureClass`, for [UnsupportedRealtimeCodecException]'s reason.
 *
 * ## What to do instead
 *
 * Hand over the configuration record **as received**, under the shape the fourcc names — a record
 * for `avc1` and `hvc1`, [RealtimeTrack.CodecConfiguration.InBand] for `avc3` and `hev1`. Nothing
 * needs converting first: reading the record, including its NAL length field size, is this
 * library's.
 */
public class MalformedRealtimeBitstreamException internal constructor(

    /** The codec string the track was declared as, verbatim. */
    public val codec: String,

    /** What was wrong, in one clause, naming the offset or the count that did not add up. */
    public val reason: String,
) : IOException("Malformed realtime bitstream for codec \"$codec\": $reason.")
