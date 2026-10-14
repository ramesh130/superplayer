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

package com.superplayer.testmedia

/**
 * A DASH stream offering the choice an AV receiver changes: a stereo AAC rendition every output plays,
 * and a 5.1 AC-3 rendition only an output that passes AC-3 through can play. Each sits at a path of its
 * own, so a test can count which one a player fetched.
 *
 * The media is [SyntheticDashStream]'s, byte for byte, under both, for the reason [SyntheticDashChoice]
 * gives: a selection reads what the MPD declares, and renditions carrying different bytes would test the
 * extractor as well as the choice. No AC-3 is encoded here, so a player that selects the surround
 * rendition has *chosen* passthrough and nothing hears it.
 *
 * spec: ISO/IEC 23009-1 §5.3.3 — two `AdaptationSet`s, because stereo AAC and 5.1 AC-3 are not seamlessly
 * switchable, which one adaptation set promises of its representations.
 * spec: ETSI TS 102 366 Annex F — `ac-3` is AC-3's `@codecs` value in ISO BMFF.
 */
public object SyntheticDashPassthrough {

    /** Where every resource of this stream sits. */
    private const val BASE_URI = "fake://${SyntheticDashStream.HOST}/dash-passthrough/"

    /** What a player is pointed at. */
    public const val MANIFEST_URI: String = BASE_URI + "manifest.mpd"

    /** The path segment every URI of the stereo AAC rendition contains. */
    public const val STEREO_PATH: String = "stereo-aac/"

    /** The path segment every URI of the 5.1 AC-3 rendition contains. */
    public const val SURROUND_PATH: String = "surround-ac3/"

    /** `ac-3`, AC-3's `@codecs` value (ETSI TS 102 366 Annex F): the encoding an AV receiver passes through. */
    public const val SURROUND_CODECS: String = "ac-3"

    /** 5.1, the layout AC-3 is broadcast in, and the channel count a passthrough output is asked about. */
    public const val SURROUND_CHANNEL_COUNT: Int = 6

    /** 48 kHz, the rate AC-3 is broadcast at and every HDMI sink carries. */
    public const val SURROUND_SAMPLE_RATE_HZ: Int = 48_000

    /** A typical 5.1 AC-3 bitrate. No selection here reads it; it only has to be a plausible one. */
    public const val SURROUND_BITRATE_BPS: Int = 384_000

    /**
     * Everything a player may ask for, keyed by URI: the MPD, and for each rendition its initialization and
     * [segmentCount] media segments.
     */
    @JvmStatic
    public fun resources(segmentCount: Int): Map<String, ByteArray> {
        require(segmentCount >= 1) { "A stream needs at least one segment, was $segmentCount" }
        return buildMap {
            put(MANIFEST_URI, manifest(segmentCount).toByteArray())
            listOf(STEREO_PATH, SURROUND_PATH).forEach { path ->
                put(BASE_URI + path + SyntheticDashStream.INITIALIZATION_NAME, SyntheticDashStream.initializationSegment())
                repeat(segmentCount) { index ->
                    put(BASE_URI + path + SyntheticDashStream.segmentName(index), SyntheticDashStream.mediaSegment(index))
                }
            }
        }
    }

    public fun durationMs(segmentCount: Int): Long = SyntheticDashStream.durationMs(segmentCount)

    private fun manifest(segmentCount: Int): String {
        val durationSeconds = SyntheticDashStream.SEGMENT_DURATION_IN_TIMESCALE.toDouble() * segmentCount /
            SyntheticDashStream.TIMESCALE
        val stereo = Rendition(
            STEREO_PATH,
            SyntheticDashStream.DECLARED_BITRATE_BPS,
            SyntheticDashStream.DECLARED_CODECS,
            SyntheticDashStream.DECLARED_SAMPLE_RATE_HZ,
            SyntheticDashStream.DECLARED_CHANNEL_COUNT,
        )
        val surround = Rendition(SURROUND_PATH, SURROUND_BITRATE_BPS, SURROUND_CODECS, SURROUND_SAMPLE_RATE_HZ, SURROUND_CHANNEL_COUNT)
        // Joined lines, for the reason SyntheticDashStream's manifest gives.
        return (
            listOf(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
                "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"",
                "     profiles=\"urn:mpeg:dash:profile:isoff-main:2011\"",
                "     type=\"static\"",
                "     mediaPresentationDuration=\"${SyntheticDashStream.xsDuration(durationSeconds)}\"",
                "     minBufferTime=\"PT1S\">",
                "  <Period id=\"0\">",
            ) + stereo.adaptationSet(segmentCount) + surround.adaptationSet(segmentCount) + listOf("  </Period>", "</MPD>")
            ).joinToString(separator = "\n")
    }

    /** One of the two encodings, as the MPD declares it. */
    private class Rendition(val path: String, val bitrateBps: Int, val codecs: String, val sampleRateHz: Int, val channelCount: Int) {
        fun adaptationSet(segmentCount: Int): List<String> = listOf(
            "    <AdaptationSet mimeType=\"audio/mp4\" lang=\"en\" segmentAlignment=\"true\">",
            "      <Representation id=\"${path.trimEnd('/')}\"",
            "                      bandwidth=\"$bitrateBps\"",
            "                      codecs=\"$codecs\"",
            "                      audioSamplingRate=\"$sampleRateHz\">",
            "        <AudioChannelConfiguration",
            "            schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\"",
            "            value=\"$channelCount\"/>",
            "        <BaseURL>$path</BaseURL>",
            "        <SegmentList timescale=\"${SyntheticDashStream.TIMESCALE}\" " +
                "duration=\"${SyntheticDashStream.SEGMENT_DURATION_IN_TIMESCALE}\">",
            "          <Initialization sourceURL=\"${SyntheticDashStream.INITIALIZATION_NAME}\"/>",
        ) + (0 until segmentCount).map { "          <SegmentURL media=\"${SyntheticDashStream.segmentName(it)}\"/>" } +
            listOf("        </SegmentList>", "      </Representation>", "    </AdaptationSet>")
    }
}
