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

/**
 * The bytes #340 observed, in one place, so that the conversion's unit test and the player's test
 * cannot drift into disagreeing about what was seen.
 *
 * That spike drove real MoQ code over a real encoder and dumped what arrived at the transport's own
 * API boundary — the only observation of this seam anyone here has, and the reason ADR-0018 rule 4
 * keys codec-specific data on the fourcc rather than on the container. Its **caveat 2** binds this
 * file: what it saw was **H.264 only**, so nothing here is an `hvcC` or an `av1C`, and a fixture for
 * either is written by whoever needs one and labelled as synthesized where it sits.
 *
 * Where a dump is a prefix of something longer, the property says so and says where the rest came
 * from. A fixture that invents bytes and calls them observed is worse than one that invents them
 * openly.
 */
internal object ObservedBytes {

    /**
     * #340's `description` hex dump, **verbatim**: the first 24 bytes of the 40-byte `avcC` it
     * observed, identical across all three of its containers.
     *
     * `01` configurationVersion, `42` Baseline, `c0` constraint flags, `1e` level 3.0, `ff`
     * `lengthSizeMinusOne = 3` so a four-byte NAL length field, `e1` one SPS, `00 18` a 24-byte SPS,
     * then the first 16 bytes of it.
     */
    val AVCC_DUMP = bytes("01 42 c0 1e ff e1 00 18 67 42 c0 1e d9 01 41 fb 01 10 00 00 03 00 10 00")

    /**
     * #340's `avc3` keyframe payload, **verbatim**: a start code, the encoder's 24-byte SPS, and the
     * start code of the unit that followed it. In-band parameter sets, confirmed by observation.
     */
    val AVC3_KEYFRAME =
        bytes("00 00 00 01 67 42 c0 1e d9 01 41 fb 01 10 00 00 03 00 10 00 00 03 03 c0 f1 62 e4 80 00 00 00 01")

    /**
     * #340's `avc1` keyframe payload, **verbatim**: `00 00 02 6f` is a 623-byte length prefix, then
     * NAL `0x06`, an SEI. No start code anywhere in it.
     */
    val AVC1_KEYFRAME_DUMP =
        bytes("00 00 02 6f 06 05 ff ff 6b dc 45 e9 bd e6 d9 48 b7 96 2c d8 20 d9 23 ee ef 78 32 36 34 20 2d 20")

    /** The SPS, read off [AVC3_KEYFRAME]: the 24 bytes between its two start codes, the length [AVCC_DUMP] declares. */
    val SPS = AVC3_KEYFRAME.copyOfRange(4, 28)

    /**
     * A picture parameter set, **synthesized**: neither dump reaches the record's PPS, so this is a
     * four-byte baseline one written here. It is the only part of [AVCC] that was not seen, which is
     * why it is named apart — and why that record is 39 bytes where the observed one was 40.
     */
    val SYNTHESIZED_PPS = bytes("68 ce 3c 80")

    /**
     * The whole record: [AVCC_DUMP]'s header, the SPS's full 24 bytes, then [SYNTHESIZED_PPS].
     *
     * The SPS's last eight bytes come from the same spike's `avc3` keyframe — one encoder's
     * parameter set, byte-identical to the dump wherever the two overlap, which
     * `CodecConfigurationRecordsTest` asserts rather than assumes.
     */
    val AVCC = AVCC_DUMP.copyOfRange(0, 8) + SPS +
        byteArrayOf(1) + byteArrayOf(0, SYNTHESIZED_PPS.size.toByte()) + SYNTHESIZED_PPS

    /** A hex dump as bytes, so a fixture can be pasted out of a bug report and read back in it. */
    fun bytes(hex: String): ByteArray =
        hex.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
}
