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

package com.superplayer.resilience

import android.media.MediaCodec
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import com.superplayer.core.LiveWindowTooShortException
import com.superplayer.core.LoadKind
import com.superplayer.core.StaleLivePlaylistException

/**
 * The single place a failure acquires a meaning (ADR-0011 rule 1).
 *
 * Pure: a failure in, a [FailureClass] out, nothing read from a player and nothing remembered
 * between calls. [classify] never throws and always returns a class, for any input at all — rule 2's
 * "zero unclassified errors" is a property of this function rather than of the corpus that happens to
 * test it, which is why there is no `Unknown` class to fall into.
 *
 * ## What it reads
 *
 * In this order, because each step is stronger evidence than the one after it:
 *
 * 1. **What core already concluded.** `SuperPlayer`'s transfer chain detects two defects a
 *    core-only consumer is exposed to and raises a typed exception naming what it found
 *    (`StaleLivePlaylistException.likelyCause`, `LiveWindowTooShortException`'s depths). Those are
 *    *mapped*, by reading the fields core filled in: this function re-reads no playlist age and no
 *    manifest attribute, and the cache-bypassing reload core performed before giving up is not a
 *    rung of the ladder (ADR-0011 rule 4). The rungs above such a playlist still apply, which is why
 *    a frozen intermediary is a [FailureClass.Transient.CdnEdge] and not a fatal class: the same
 *    content may be live at another host.
 * 2. **The load that failed.** An `InvalidResponseCodeException` carries the status and the
 *    `DataSpec`, and a player with resilience attached stamps every request with its [LoadKind]
 *    (ADR-0011 rule 13), so a refusal of a segment is distinguishable from a refusal of the
 *    manifest — which is the whole of what separates [FailureClass.Transient.CdnEdge] from a plain
 *    transfer failure (`PRD.md` §3.3). An unstamped request — a player with neither cache nor
 *    resilience, or a source that set `customData` itself — says nothing, and says it by falling
 *    through to the band.
 * 3. **The engine's own band.** `PlaybackException.errorCode` in the ranges Media3 documents, with
 *    a `MediaCodec.CodecException` consulted where the device is the subject. This is the
 *    *last* resort and the total one, and it is the only `when` over an error code in the
 *    repository — a second one anywhere is a bug against rule 1.
 *
 * `Fatal.Unsupported` is reached only from a code that says *unsupported*, never from the fall
 * through: an unrecognised code is far likelier to be a transfer that can be retried than content
 * that can never play, and calling it fatal would end sessions the ladder could have rescued.
 */
public object ErrorClassifier {

    /**
     * Far past any cause chain Media3 builds; see [causeChain].
     */
    private const val MAX_CAUSE_DEPTH = 32

    /**
     * The code [fromBand] reads when there is no band to read — a load error caught before the
     * engine has wrapped it in a `PlaybackException`. Zero, because no Media3 error code is zero:
     * the session codes are negative and every playback band starts at 1000.
     */
    private const val NO_BAND = 0

    /**
     * The statuses an edge returns for an object it will not or cannot serve while the manifest
     * naming it is fine (// ref: RFC 9110 §15.5.2 401, §15.5.4 403, §15.5.5 404).
     */
    private val EDGE_REFUSALS = setOf(401, 403, 404)

    /** // ref: RFC 9110 §15.5.17, Range Not Satisfiable. */
    private const val RANGE_NOT_SATISFIABLE = 416

    /**
     * What [error] is.
     *
     * [error] is whatever was caught: the `PlaybackException` a consumer holds in
     * `Player.Listener.onPlayerError`, the `IOException` a load error arrives as, or anything at all.
     * A `Throwable` rather than a Media3 type because the taxonomy names no Media3 class and neither
     * does the door to it; what is read out of the exception stays inside this file.
     */
    public fun classify(error: Throwable): FailureClass {
        val causes = causeChain(error)
        return namedByCore(causes)
            ?: namedByTheFailedLoad(causes)
            ?: fromBand(causes.firstNotNullOfOrNull { (it as? PlaybackException)?.errorCode }, causes)
    }

    /**
     * Which party core's own detection pointed at, where it pointed at one, and null everywhere else.
     *
     * Read here rather than by rung 6, because this is the file that walks a cause chain and reads
     * what core concluded (ADR-0011 rule 4), and a second walk elsewhere would be a second reading of
     * the same evidence. It is *not* a classification and does not narrow one: the class of a frozen
     * playlist is already decided in [namedByCore], and this is the detail rule 10 carries out
     * alongside it so a bug report says which of the intermediary and the origin to go and look at.
     */
    internal fun likelyPartyIn(error: Throwable): StaleLivePlaylistException.LikelyCause? =
        causeChain(error).filterIsInstance<StaleLivePlaylistException>().firstOrNull()?.likelyCause

    /**
     * [error] and its causes, nearest first, bounded.
     *
     * Bounded because a cause chain is built by whoever threw: a chain that loops, or one deep
     * enough to cost real time to walk, must not turn a failure into a second failure. The bound is
     * far past any chain Media3 builds (a load error reaches the player through about half a dozen
     * wrappers), so missing evidence past it is not a case that occurs rather than one traded away.
     */
    private fun causeChain(error: Throwable): List<Throwable> {
        val chain = ArrayList<Throwable>(MAX_CAUSE_DEPTH)
        var current: Throwable? = error
        while (current != null && chain.size < MAX_CAUSE_DEPTH) {
            if (chain.any { it === current }) break
            chain.add(current)
            current = current.cause
        }
        return chain
    }

    /** The class of a defect core detected and named, or null when it named none. */
    private fun namedByCore(causes: List<Throwable>): FailureClass? = causes.firstNotNullOfOrNull { cause ->
        when (cause) {
            is StaleLivePlaylistException -> when (cause.likelyCause) {
                // An intermediary is serving a copy it holds while the origin carries on publishing:
                // an edge defect, and another edge — or another source — may hold a live copy.
                StaleLivePlaylistException.LikelyCause.INTERMEDIARY_CACHE -> FailureClass.Transient.CdnEdge

                // Nothing served points at a cache, so the segments the playlist promised are not
                // being produced. Asking the same origin again finds the same hole; the rungs above
                // it are what may still play (ADR-0011 rule 4).
                StaleLivePlaylistException.LikelyCause.ORIGIN -> FailureClass.Content.SegmentGap
            }

            // A window no playhead fits inside is a manifest that cannot be acted on, however
            // well-formed it is. Core judged the depths; this reads the verdict.
            is LiveWindowTooShortException -> FailureClass.Content.ManifestInvalid

            else -> null
        }
    }

    /** The class the failed load's status and kind settle, or null when they settle nothing. */
    private fun namedByTheFailedLoad(causes: List<Throwable>): FailureClass? {
        val refused = causes.filterIsInstance<InvalidResponseCodeException>().firstOrNull() ?: return null
        if (LoadKind.of(refused.dataSpec) != LoadKind.MEDIA) return null
        return when (refused.responseCode) {
            // A segment refused or missing while the manifest that named it loaded fine: an expired
            // token or an edge miss (`PRD.md` §3.3). 401 and 403 are what an expiring CDN token
            // looks like and 404 what an edge that has lost the object looks like; the token is
            // refreshed below this in the chain before the retry (ADR-0011 rule 12), so the same URL
            // is worth asking again.
            in EDGE_REFUSALS -> FailureClass.Transient.CdnEdge

            // The object is shorter than the byte range the description asked for
            // (// spec: RFC 9110 §15.5.17), which is the media not being there as promised rather
            // than a transfer that failed.
            RANGE_NOT_SATISFIABLE -> FailureClass.Content.SegmentGap

            // Any other status — a 5xx, a redirect loop, a 429 — is the CDN being unwell rather
            // than this object being wrong, and the band says the same thing.
            else -> null
        }
    }

    /**
     * The class [errorCode]'s band decides, consulting [causes] where the device is the subject.
     *
     * The bands are Media3's documented ranges (// ref: `PlaybackException` — codes 1000–1999
     * miscellaneous, 2000–2999 input/output, 3000–3999 content parsing, 4000–4999 decoding,
     * 5000–5999 audio rendering, 6000–6999 DRM, 7000–7999 video frame processing), and each range
     * carries the band's own fall-through so a code added by a later Media3 lands where its
     * neighbours do rather than at the end.
     */
    private fun fromBand(errorCode: Int?, causes: List<Throwable>): FailureClass = when (errorCode ?: NO_BAND) {
        // A read past the end of the object: what was published is shorter than what the manifest
        // described, which is the media missing rather than the transfer failing.
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> FailureClass.Content.SegmentGap

        // Rule 2's named fall-through for the band: an I/O failure with no evidence narrowing it is
        // a transient network failure, including the statuses `namedByTheFailedLoad` declined to
        // read anything into.
        in 2000..2999 -> FailureClass.Transient.Network

        // Malformed media is the object, malformed description is the manifest; "unsupported" is the
        // engine naming content it will never play, which is the only door to `Fatal.Unsupported`.
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> FailureClass.Content.SegmentGap

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        -> FailureClass.Fatal.Unsupported

        in 3000..3999 -> FailureClass.Content.ManifestInvalid

        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> FailureClass.Fatal.Unsupported

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        ->
            // A decoder that could not be had *this time* is the one init failure a recreate fixes,
            // and the codec is the only thing that knows which it was: a feed holding every instance
            // the device has raises the same code as content the device has no decoder for.
            if (recreatingMayHelp(causes)) FailureClass.Device.DecoderTransient else FailureClass.Device.DecoderInit

        // The format is past what this device lists: no recreate helps, another rung may fit.
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> FailureClass.Device.DecoderInit

        // A decoder that was working and stopped, or one the platform took back, is rung 5's case.
        in 4000..4999 -> FailureClass.Device.DecoderTransient

        // The output half of the same device: initialising an audio track or a frame processor is a
        // decoder init in every way that matters to the ladder, and a write that failed mid-stream
        // is what a recreate is for.
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        -> FailureClass.Device.DecoderInit

        in 5000..5999, in 7000..7999 -> FailureClass.Device.DecoderTransient

        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> FailureClass.Drm.Provisioning

        // A scheme this device has no key system for, a device the licence server will not serve, an
        // operation the licence forbids, protection data the content itself got wrong: asking again
        // changes none of them.
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
        PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        -> FailureClass.Drm.Unsupported

        in 6000..6999 -> FailureClass.Drm.LicenceAcquisition

        // Everything left: the miscellaneous band, the session codes Media3 numbers below zero, a
        // custom code an app assigned, a code a later Media3 invented, and no band at all — which is
        // what the load-error path (#178) holds, an `IOException` with no `PlaybackException` around
        // it yet. Retryable is the honest default here; see this object's KDoc for why it is not
        // `Fatal.Unsupported`.
        else -> FailureClass.Transient.Network
    }

    /**
     * Whether the codec said the failure was one a recreated decoder survives.
     *
     * `isTransient` is the platform saying the resource was momentarily unavailable and `isRecoverable`
     * that the codec can be reset and used again; either is rung 5's evidence and neither is anything
     * the error code carries (// ref: `MediaCodec.CodecException.isTransient()` and `isRecoverable()`).
     */
    private fun recreatingMayHelp(causes: List<Throwable>): Boolean =
        causes.filterIsInstance<MediaCodec.CodecException>().any { it.isTransient || it.isRecoverable }
}
