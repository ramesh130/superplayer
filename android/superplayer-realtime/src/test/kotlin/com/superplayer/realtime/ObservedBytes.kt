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

import java.io.File

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
     * `01` configurationVersion, `42` Baseline, `c0` `profile_compatibility`, `1e` level 3.0, `ff`
     * `lengthSizeMinusOne = 3` so a four-byte NAL length field, `e1` one SPS, `00 18` a 24-byte SPS,
     * then the first 16 bytes of it.
     */
    val AVCC_DUMP = committed("AVCC_DUMP")

    /**
     * #340's `avc3` keyframe payload, **verbatim**: a start code, the encoder's 24-byte SPS, and the
     * start code of the unit that followed it. In-band parameter sets, confirmed by observation.
     */
    val AVC3_KEYFRAME = committed("AVC3_KEYFRAME")

    /**
     * #340's `avc1` keyframe payload, **verbatim**: `00 00 02 6f` is a 623-byte length prefix, then
     * NAL `0x06`, an SEI. No start code anywhere in it.
     */
    val AVC1_KEYFRAME_DUMP = committed("AVC1_KEYFRAME_DUMP")

    /** The SPS, read off [AVC3_KEYFRAME]: the 24 bytes between its two start codes, the length [AVCC_DUMP] declares. */
    val SPS = AVC3_KEYFRAME.copyOfRange(4, 28)

    /**
     * A picture parameter set, **synthesized**: neither dump reaches the record's PPS, so this is a
     * four-byte baseline one written here. It is the only part of [AVCC] that was not seen, which is
     * why it is named apart — and why that record is 39 bytes where the observed one was 40.
     */
    val SYNTHESIZED_PPS = committed("SYNTHESIZED_PPS")

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

    /**
     * The row [name] of `ios/Tests/Fixtures/realtime/observed-bytes.tsv`, where the dumps live so the
     * Swift port's tests read the same bytes as these (#431, `docs/testing.md`'s *A realtime transport
     * on iOS*). One copy rather than two that agree today: an observation edited on one side
     * would otherwise be an observation only that side made. The directory reaches the test JVM as a
     * system property, relative to the module, as `superplayer-testmedia`'s fixtures do.
     */
    private fun committed(name: String): ByteArray {
        val directory = checkNotNull(System.getProperty(FIXTURES_PROPERTY)) {
            "$FIXTURES_PROPERTY is not set; superplayer-realtime/build.gradle.kts sets it for every test task"
        }
        val row = File(directory, "observed-bytes.tsv").readLines()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { it.split('\t') }
            .singleOrNull { it.first() == name }
            ?: error("observed-bytes.tsv has no single row named $name")
        check(row.size == 3) { "observed-bytes.tsv: the row $name is not name, provenance and hex" }
        return bytes(row[2])
    }

    private const val FIXTURES_PROPERTY = "superplayer.realtimeFixtures.dir"
}
