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
 * A DASH stream that offers a choice: for each audio language, two renditions of different declared
 * bitrates, and for each subtitle language, a WebVTT file — every one at a path of its own, so a test can
 * count what a player or a download fetched per rendition and per language.
 *
 * The media is [SyntheticDashStream]'s, byte for byte, under every rendition: the renditions and the
 * languages differ only in what the MPD declares about them, which is all a selection reads. A stream
 * whose renditions carried different bytes would be testing the extractor as well as the choice.
 *
 * spec: ISO/IEC 23009-1 §5.3.3 — one `AdaptationSet` per language, carrying `lang`, since the
 * representations of one adaptation set are interchangeable encodings of one content and two languages
 * are not; §5.3.5 — two `Representation`s inside each, told apart by `bandwidth`.
 * spec: ISO/IEC 23009-1 §5.3.9.2 — a subtitle file addressed by a `BaseURL` alone is a single segment,
 * the form a side-car WebVTT file takes (W3C WebVTT §4).
 */
public object SyntheticDashChoice {

    /** Where every resource of this stream sits. */
    private const val BASE_URI = "fake://${SyntheticDashStream.HOST}/dash-choice/"

    /** What a player or a download is pointed at. */
    public const val MANIFEST_URI: String = BASE_URI + "manifest.mpd"

    /** The lower of each language's two renditions: [SyntheticDashStream]'s own declared bitrate. */
    public const val LOWER_BITRATE_BPS: Int = SyntheticDashStream.DECLARED_BITRATE_BPS

    /** The higher of each language's two renditions: twice the lower, a ladder step no selection could mistake. */
    public const val HIGHER_BITRATE_BPS: Int = 2 * SyntheticDashStream.DECLARED_BITRATE_BPS

    /** The path segment naming the rendition of [language] at [bitrateBps], which every one of its resources' URIs contains. */
    @JvmStatic
    public fun audioRenditionPath(language: String, bitrateBps: Int): String = "audio-$language-$bitrateBps/"

    /** The URI of [language]'s subtitle file. */
    @JvmStatic
    public fun subtitleUri(language: String): String = BASE_URI + "subtitles-$language.vtt"

    /**
     * Everything a player may ask for, keyed by URI: the MPD, and for each of [audioLanguages] two
     * renditions' initialization and [segmentCount] media segments, and for each of [subtitleLanguages] a
     * WebVTT file. Languages are BCP 47 tags, written into the MPD as given.
     */
    @JvmStatic
    public fun resources(
        segmentCount: Int,
        audioLanguages: List<String>,
        subtitleLanguages: List<String>,
    ): Map<String, ByteArray> {
        require(segmentCount >= 1) { "A stream needs at least one segment, was $segmentCount" }
        require(audioLanguages.isNotEmpty()) { "A stream needs at least one audio language" }
        return buildMap {
            put(MANIFEST_URI, manifest(segmentCount, audioLanguages, subtitleLanguages).toByteArray())
            audioLanguages.forEach { language ->
                listOf(LOWER_BITRATE_BPS, HIGHER_BITRATE_BPS).forEach { bitrate ->
                    val base = BASE_URI + audioRenditionPath(language, bitrate)
                    put(base + SyntheticDashStream.INITIALIZATION_NAME, SyntheticDashStream.initializationSegment())
                    repeat(segmentCount) { index ->
                        put(base + SyntheticDashStream.segmentName(index), SyntheticDashStream.mediaSegment(index))
                    }
                }
            }
            subtitleLanguages.forEach { language -> put(subtitleUri(language), webVtt(language).toByteArray()) }
        }
    }

    public fun durationMs(segmentCount: Int): Long = SyntheticDashStream.durationMs(segmentCount)

    private fun manifest(segmentCount: Int, audioLanguages: List<String>, subtitleLanguages: List<String>): String {
        val durationSeconds = SyntheticDashStream.SEGMENT_DURATION_IN_TIMESCALE.toDouble() * segmentCount /
            SyntheticDashStream.TIMESCALE
        val audio = audioLanguages.flatMap { language ->
            listOf("    <AdaptationSet mimeType=\"audio/mp4\" lang=\"$language\" segmentAlignment=\"true\">") +
                listOf(LOWER_BITRATE_BPS, HIGHER_BITRATE_BPS).flatMap { bitrate ->
                    listOf(
                        "      <Representation id=\"audio-$language-$bitrate\"",
                        "                      bandwidth=\"$bitrate\"",
                        "                      codecs=\"${SyntheticDashStream.DECLARED_CODECS}\"",
                        "                      audioSamplingRate=\"${SyntheticDashStream.DECLARED_SAMPLE_RATE_HZ}\">",
                        "        <AudioChannelConfiguration",
                        "            schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\"",
                        "            value=\"${SyntheticDashStream.DECLARED_CHANNEL_COUNT}\"/>",
                        "        <BaseURL>${audioRenditionPath(language, bitrate)}</BaseURL>",
                        "        <SegmentList timescale=\"${SyntheticDashStream.TIMESCALE}\" " +
                            "duration=\"${SyntheticDashStream.SEGMENT_DURATION_IN_TIMESCALE}\">",
                        "          <Initialization sourceURL=\"${SyntheticDashStream.INITIALIZATION_NAME}\"/>",
                    ) + (0 until segmentCount).map { "          <SegmentURL media=\"${SyntheticDashStream.segmentName(it)}\"/>" } +
                        listOf("        </SegmentList>", "      </Representation>")
                } + listOf("    </AdaptationSet>")
        }
        val subtitles = subtitleLanguages.flatMap { language ->
            listOf(
                "    <AdaptationSet mimeType=\"text/vtt\" lang=\"$language\">",
                "      <Representation id=\"subtitles-$language\" bandwidth=\"$SUBTITLE_BANDWIDTH_BPS\">",
                "        <BaseURL>subtitles-$language.vtt</BaseURL>",
                "      </Representation>",
                "    </AdaptationSet>",
            )
        }
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
            ) + audio + subtitles + listOf("  </Period>", "</MPD>")
            ).joinToString(separator = "\n")
    }

    /** spec: W3C WebVTT §4.1 — the signature line, a blank line, and one cue inside the first segment. */
    private fun webVtt(language: String): String = "WEBVTT\n\n00:00.000 --> 00:00.500\n[$language]\n"

    /** `bandwidth` is mandatory on a `Representation` (ISO/IEC 23009-1 §5.3.5.2); a text track's is its file's order of magnitude. */
    private const val SUBTITLE_BANDWIDTH_BPS = 1_000
}
