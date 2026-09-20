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

import com.superplayer.core.RealtimeTrack
import uniffi.moq.MoqCatalog
import uniffi.moq.MoqContainer
import uniffi.moq.MoqDimensions
import java.io.IOException

/**
 * One track this module will subscribe to, as the catalog declared it.
 *
 * It carries the [RealtimeTrack] the seam is handed and the two things MoQ needs to open the
 * subscription behind it, which the seam has no room for and no business knowing: the broadcast's
 * own [trackName] and the [container] its frames are framed in.
 *
 * @property trackName The catalog's key for this rendition, which is what
 *   `MoqBroadcastConsumer.subscribeMedia` takes as its first argument.
 * @property container What the publisher declared this track's frames are wrapped in. It is handed
 *   straight back to `subscribeMedia`, which is the *only* thing it is for: the bindings strip the
 *   framing below the FFI boundary, so by the time a frame arrives the container is gone and
 *   **must not enter any decoding decision** (#340's second correction, ADR-0018 rules 3 and 4).
 * @property track What the Phase 13 seam accepts: the codec string verbatim, and the
 *   codec-configuration shape the publisher declared.
 */
internal class MoqDeclaredTrack(
    val trackName: String,
    val container: MoqContainer,
    val track: RealtimeTrack,
)

/**
 * What a publisher *declared* becomes what the Phase 13 seam *accepts*: a MoQ catalog becomes a
 * list of tracks.
 *
 * This is a pure mapping — no session, no QUIC, no frames — which is what makes it the one part of
 * this module that `check` can test honestly, and therefore where Phase 14's "both
 * codec-description shapes are covered by a test" criterion (`PRD.md` Part 4) is discharged.
 *
 * ## The catalog arrives typed, so nothing here parses
 *
 * `MoqBroadcastConsumer.subscribeCatalog` answers a `MoqCatalogConsumer` whose `next()` yields a
 * [MoqCatalog]: two maps of track name to rendition, one per media kind, each rendition carrying
 * its `codec` string and an optional `description`. The bindings decode the wire format below the
 * FFI boundary, so **no JSON is read on this side** and no serialization dependency is declared —
 * which is worth writing down, because #364 removed upstream's `dev.moq:moq` wrapper and with it
 * the typed JSON helpers a reader might come looking for.
 *
 * // spec: the catalog's shape — renditions keyed by track name, per media kind — is `moq-lite`'s
 * // `hang` layer, documented at doc.moq.dev; `uniffi.moq.MoqCatalog` mirrors it field for field.
 *
 * ## What this module must not do
 *
 * **It maps no codec strings and re-derives no fourcc rule.** `RealtimeFormats` (RFC 6381 strings
 * and SDP encoding names) and `CodecConfigurationRecords` (the `avc1`/`avc3` branch, the Annex-B
 * conversion and the NAL length arithmetic) are both `internal` to `superplayer-realtime`, and this
 * module is deliberately **not** a Kotlin friend of it or of core (ADR-0018 rule 11) — so they are
 * unreachable by design rather than by discipline. A second fourcc table here is precisely the
 * defect the seam exists to prevent.
 *
 * The consequence is where a refusal lands. A codec string nothing maps is **passed through
 * verbatim and kept in the list**; `RealtimeMediaPeriod.onTracks` then refuses the whole
 * subscription by name with `UnsupportedRealtimeCodecException`. Dropping it here instead would
 * play a partially understood catalog as a silently narrower stream, which is the failure the
 * criterion is written against. The same is true of a `description` that contradicts its own
 * fourcc: it is handed over as received and refused there with `MalformedRealtimeBitstreamException`
 * (ADR-0018 rule 4's #345 addendum).
 *
 * ## One rendition per kind, chosen in no way
 *
 * ADR-0018 rule 7 **defers** adaptive selection rather than excluding it: a MoQ catalog really does
 * list a ladder, and a phase that selects from it amends that record. Until then this takes the
 * *first* rendition each map declares — which is not a preference but the absence of one, the
 * catalog's own declaration order being the only ordering it carries. `MoqVideo.stalled`, the flag
 * a publisher raises so that players prefer another rendition, is read by nothing here for exactly
 * that reason.
 *
 * That order survives the FFI, and the claim is about generated third-party code rather than about
 * anything here, so it carries its reading:
 *
 * // ref: `uniffi.moq.FfiConverterMapStringTypeMoqVideo.read` at `dev.moq:moq-ffi:0.3.19-superplayer-local`
 * // builds its result through `kotlin.collections.MapsKt.createMapBuilder`, whose `MapBuilder`
 * // iterates in insertion order — so "first" is the first entry the publisher wrote and not a hash
 * // order. Nothing under `check` exercises that converter (`MoqCatalogTracksTest` builds its maps
 * // on this side), so this half is read off the bindings rather than asserted.
 */
internal object MoqCatalogTracks {

    /**
     * The tracks [catalog] declares, in the order [com.superplayer.core.EncodedFrame.trackIndex]
     * will name them.
     *
     * **Video first, then audio**, and that is a promise rather than an accident: an index is a
     * position in this list, so the frame pump over it needs an order that does not move between
     * one catalog and the next. A subscription carrying both always indexes video 0 and audio 1; a
     * subscription carrying one indexes it 0, which is the audio-only case #346 widened the seam
     * for.
     *
     * @throws IOException if the catalog declares no video and no audio. Refused here rather than
     *   returned as an empty list: `RealtimeMediaPeriod.onTracks` would refuse the empty list too,
     *   but its message can only say that a subscription declared nothing, while this one can say
     *   which broadcast's catalog it read and that the catalog itself was empty. No exception type
     *   is added for it — the two typed refusals on this path are about *bytes*, and there is
     *   nothing here to name.
     */
    fun declaredTracksOf(catalog: MoqCatalog): List<MoqDeclaredTrack> {
        val declared = buildList {
            catalog.video.entries.firstOrNull()?.let { (trackName, video) ->
                add(
                    MoqDeclaredTrack(
                        trackName,
                        video.container,
                        RealtimeTrack(
                            video.codec,
                            configurationOf(video.description),
                            // The **coded** size, which is what a decoder is configured with;
                            // `displayAspect` is presentation and is read by nothing here
                            // (ADR-0018 rule 1 — nothing in this library decodes or scales).
                            //
                            // Carried because leaving it out is a silent failure rather than a
                            // missing feature: `MediaCodec.configure` refuses a video format with
                            // no size, so a broadcast whose catalog stated one would connect,
                            // subscribe, deliver frames and then die at the first keyframe naming
                            // the decoder. #353 observed exactly that on `hev1.1.6.L180.80`.
                            //
                            // A declaration that does not hold together is **refused by name**
                            // rather than narrowed into one that does: the bindings carry these
                            // unsigned, so a publisher declaring a dimension past `Int.MAX_VALUE`
                            // would otherwise wrap to a negative and travel to `MediaCodec` as a
                            // claim about the picture. `CodedSize` rejects anything not positive,
                            // and `codedSizeOf` turns that into this module's own refusal, which
                            // is #345's rule applied to a number instead of to bytes.
                            codedSize = codedSizeOf(video.coded, trackName),
                        ),
                    ),
                )
            }
            catalog.audio.entries.firstOrNull()?.let { (trackName, audio) ->
                add(
                    MoqDeclaredTrack(
                        trackName,
                        audio.container,
                        RealtimeTrack(audio.codec, configurationOf(audio.description)),
                    ),
                )
            }
        }
        if (declared.isEmpty()) {
            throw IOException(
                "The MoQ catalog declares no video track and no audio track, so there is nothing to subscribe to.",
            )
        }
        return declared
    }

    /**
     * The shape the publisher declared its codec configuration in, which is [description]'s
     * *presence* and nothing else.
     *
     * #340 observed the presence to follow the codec's fourcc — `avc1`/`hvc1` carry an
     * `avcC`/`hvcC`, `avc3`/`hev1` carry none — and that correspondence is ADR-0018 rule 4, which
     * `superplayer-realtime` owns and checks. **This function does not restate it**: it reports
     * what arrived, so that a publisher contradicting its own fourcc is refused by name downstream
     * rather than silently corrected here into whichever answer this module guessed.
     *
     * The bytes are handed over as received — not copied, unwrapped or reordered — which is
     * obligation 7's "exactly as received". A record that is present but empty is a
     * [RealtimeTrack.CodecConfiguration.Record] of no bytes for the same reason: deciding what an
     * empty record means is a judgement about bytes, and this module makes none.
     */

    /**
     * [declared] as the seam's own [RealtimeTrack.CodedSize], or null where none was declared.
     *
     * The narrowing is checked rather than assumed. A `UInt` past `Int.MAX_VALUE` becomes a
     * negative, and a publisher may declare a zero; both are refused here with the track named, so
     * the failure arrives as a statement about the catalog instead of as a decoder that would not
     * start. `IOException` and not one of the phase's three typed refusals, for the reason an empty
     * catalog is also a plain one: those three are about **bytes**, and a dimension is not bytes.
     */
    private fun codedSizeOf(declared: MoqDimensions?, trackName: String): RealtimeTrack.CodedSize? {
        if (declared == null) return null
        val width = declared.width.toLong()
        val height = declared.height.toLong()
        if (width !in 1..Int.MAX_VALUE.toLong() || height !in 1..Int.MAX_VALUE.toLong()) {
            throw IOException(
                "The MoQ catalog declares track \"$trackName\" as ${width}x$height, which is not a " +
                    "size a decoder can be configured with. A publisher that does not know its " +
                    "resolution declares none.",
            )
        }
        return RealtimeTrack.CodedSize(width.toInt(), height.toInt())
    }

    private fun configurationOf(description: ByteArray?): RealtimeTrack.CodecConfiguration =
        description?.let { RealtimeTrack.CodecConfiguration.Record(it) }
            ?: RealtimeTrack.CodecConfiguration.InBand
}
