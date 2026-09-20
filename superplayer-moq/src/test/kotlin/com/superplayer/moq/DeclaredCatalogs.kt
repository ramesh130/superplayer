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

import uniffi.moq.MoqAudio
import uniffi.moq.MoqCatalog
import uniffi.moq.MoqContainer
import uniffi.moq.MoqDimensions
import uniffi.moq.MoqVideo

/**
 * The catalogs this module's mapping is tested against, each saying where it came from.
 *
 * **What was observed and what was not.** #340 drove real MoQ code over a real encoder and dumped
 * what arrived at the transport's API boundary: the codec strings `avc1.42c01e` and `avc3.42c01e`,
 * the presence or absence of a `description`, and the first 24 bytes of the 40-byte `avcC` it saw.
 * Those are the fixtures below that say *observed*. What that spike explicitly did **not** exercise
 * is the catalog path itself — its caveat 4 says so in as many words — so no `MoqCatalog` here was
 * ever received from a broadcast. Each is **assembled** from observed fields, and the assembly is
 * the part that is not evidence.
 *
 * Everything that is not H.264 is **synthesized** outright, because #340's caveat 2 binds this file
 * as it binds `superplayer-realtime`'s `ObservedBytes`: what was seen was H.264 only. That covers
 * the `hvcC` record below and all four of the other codec strings the tests name —
 * [SYNTHESIZED_HVC1_CODEC], [SYNTHESIZED_AAC_CODEC], [SYNTHESIZED_OPUS_CODEC] and
 * [SYNTHESIZED_UNMAPPED_CODEC] — which are written here rather than recorded and are evidence of
 * nothing about a real publisher of any of those codecs.
 *
 * #340's `avcC` dump is restated here rather than shared with `ObservedBytes`, which exists so that
 * two readers of those bytes "cannot drift into disagreeing about what was seen". Sharing it is not
 * available: that object is `superplayer-realtime`'s *test* source, which no other module's tests
 * can see, and promoting it to either module's main sources would publish #340's dumps as API to
 * save a copy. What keeps the copy honest is that it is a prefix nothing here parses — this module
 * is forbidden from parsing it (ADR-0018 rule 4) — so the two cannot disagree about a *reading* of
 * the record, only about the hex, which is quoted verbatim in both places from the same comment.
 *
 * None of that weakens what the tests using them assert. The subject is a *mapping* — a catalog
 * field reaching a seam field unmodified — so what the bytes are matters far less than that they
 * are the same bytes on both sides, and the one thing a real recording would add is confidence
 * that a publisher populates these fields the way the bindings' types say. That is what #366's
 * first run against a live broadcast is for.
 */
internal object DeclaredCatalogs {

    /** #340's observed video codec string for the out-of-band shape: an `avc1` sample entry. */
    const val OBSERVED_AVC1_CODEC = "avc1.42c01e"

    /** #340's observed video codec string for the in-band shape: the same media, an `avc3` entry. */
    const val OBSERVED_AVC3_CODEC = "avc3.42c01e"

    /**
     * #340's `description` hex dump, **verbatim**: the first 24 bytes of the 40-byte `avcC` it
     * observed, identical across all three of its containers.
     *
     * It is a prefix and not the whole record, which is why nothing here parses it — this module is
     * forbidden from doing so anyway (ADR-0018 rule 4), and the assertion it serves is that the
     * bytes arrive at the seam unchanged.
     */
    val OBSERVED_AVCC_PREFIX =
        bytes("01 42 c0 1e ff e1 00 18 67 42 c0 1e d9 01 41 fb 01 10 00 00 03 00 10 00")

    /**
     * An `hvcC`-shaped record, **synthesized**. #340 saw H.264 only (its caveat 2), so this is
     * written here rather than recorded, exactly as `CodecConfigurationRecordsTest`'s own `hvcC` is
     * and labelled the same way. It is evidence that a record reaches the seam unmodified and
     * evidence of nothing about a real HEVC broadcast.
     *
     * // spec: the shape is the `HEVCDecoderConfigurationRecord`, ISO/IEC 14496-15 §8.3.3.1.2 — its
     * // `configurationVersion`, general profile, tier and level, then the arrays. Cited because it
     * // is claimed to be shaped like one; nothing in this module reads a byte of it, so any bytes
     * // would serve the assertion it is actually used for.
     */
    val SYNTHESIZED_HVCC = bytes("01 01 60 00 00 00 90 00 00 00 00 00 5d f0 00 fc fd f8 f8 00 00 0f 03")

    /** An `hvc1` codec string, **synthesized** for [SYNTHESIZED_HVCC]'s reason. */
    const val SYNTHESIZED_HVC1_CODEC = "hvc1.1.6.L93.B0"

    /** An AAC-LC codec string, **synthesized**: #340 saw no audio track at all. */
    const val SYNTHESIZED_AAC_CODEC = "mp4a.40.2"

    /** An Opus codec string, **synthesized** for [SYNTHESIZED_AAC_CODEC]'s reason. */
    const val SYNTHESIZED_OPUS_CODEC = "opus"

    /**
     * A codec string the seam's table does not carry, **synthesized**: VP8, chosen because it is one
     * a publisher plausibly sends rather than a string invented to fail.
     *
     * It is unmapped by `superplayer-realtime`'s `RealtimeFormats` today, and nothing mechanical
     * holds it so — that table is `internal` to a module this one is not a friend of (ADR-0018
     * rule 11), which is the same reason the refusal is not assertable here. A phase that maps VP8
     * therefore has to move this constant, and saying so is the only guard available.
     */
    const val SYNTHESIZED_UNMAPPED_CODEC = "vp08.00.41.08"

    /** A well-formed `MoqCatalog` carrying one video rendition and no audio. */
    fun videoOnly(
        trackName: String = "video",
        codec: String,
        description: ByteArray?,
        container: MoqContainer = MoqContainer.Loc,
        coded: MoqDimensions? = MoqDimensions(width = 1280u, height = 720u),
    ): MoqCatalog = catalog(video = mapOf(trackName to video(codec, description, container, coded = coded)))

    /** A well-formed `MoqCatalog` carrying one audio rendition and no video. */
    fun audioOnly(
        trackName: String = "audio",
        codec: String,
        description: ByteArray?,
        container: MoqContainer = MoqContainer.Loc,
    ): MoqCatalog = catalog(audio = mapOf(trackName to audio(codec, description, container)))

    /** The whole catalog, so a test can state exactly what a publisher declared. */
    fun catalog(
        video: Map<String, MoqVideo> = emptyMap(),
        audio: Map<String, MoqAudio> = emptyMap(),
    ): MoqCatalog = MoqCatalog(
        video = video,
        audio = audio,
        // The catalog's presentation half — how the publisher would like the whole broadcast laid
        // out. Nothing in this module reads any of it, and it is left unstated rather than filled
        // with numbers a reader might take for something the mapping depends on.
        display = null,
        rotation = null,
        flip = null,
        sections = emptyMap(),
    )

    /**
     * One video rendition. The codec, the description and the **coded size** are what this module
     * reads; the rest is a plausible constant, kept here so that a fixture is a whole rendition and
     * a reader is not left wondering whether an unset field mattered.
     *
     * [coded] is a parameter rather than a constant because its absence is a case: a publisher may
     * declare no size, and what the mapping does with that is asserted rather than assumed
     * (#353). It defaults to 720p, an ordinary rendition.
     */
    fun video(
        codec: String,
        description: ByteArray?,
        container: MoqContainer = MoqContainer.Loc,
        stalled: Boolean = false,
        coded: MoqDimensions? = MoqDimensions(width = 1280u, height = 720u),
    ): MoqVideo = MoqVideo(
        codec = codec,
        description = description,
        container = container,
        stalled = stalled,
        coded = coded,
        // Read by nothing here: `displayAspect` is presentation, and neither the bitrate nor the
        // frame rate reaches a decoder's configuration (ADR-0018 rule 1).
        displayAspect = null,
        bitrate = 2_000_000uL,
        framerate = 30.0,
    )

    /** One audio rendition, on [video]'s terms. */
    fun audio(
        codec: String,
        description: ByteArray?,
        container: MoqContainer = MoqContainer.Loc,
    ): MoqAudio = MoqAudio(
        codec = codec,
        description = description,
        container = container,
        // Stereo at 48 kHz, the ordinary case, and read by nothing here for [video]'s reason.
        sampleRate = 48_000u,
        channelCount = 2u,
        bitrate = 128_000uL,
    )

    /** A hex dump as bytes, so a fixture can be pasted out of a bug report and read back in it. */
    fun bytes(hex: String): ByteArray =
        hex.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
}
