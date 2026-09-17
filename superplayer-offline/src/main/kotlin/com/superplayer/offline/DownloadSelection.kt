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

package com.superplayer.offline

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.offline.DownloadHelper
import com.superplayer.core.DownloadSelectionPolicy
import com.superplayer.core.TrackSelectionPolicy

/**
 * What one download takes out of its manifest: one video rendition under [policy]'s ceiling and the
 * device's, the audio in [audioLanguages], and the subtitles in [subtitleLanguages] (ADR-0013 rule 12).
 *
 * The choosing itself is Media3's `DownloadHelper` over Media3's own track selector, told only what this
 * says, so what a download selects is what the engine would select under the same constraints: the
 * highest rendition under the ceiling that the device's decoders report they can play, and within a
 * language, the highest audio rendition.
 */
internal class DownloadSelection(
    private val policy: DownloadSelectionPolicy,
    /** The display's shorter edge, or null where the device has not said: ADR-0009 rule 2's reading, never observed. */
    private val displayShortEdgePx: Int?,
    private val audioLanguages: List<String>,
    private val subtitleLanguages: List<String>,
) {

    /**
     * The parameters every pass starts from: Media3's for a download — the highest bitrate a selection
     * allows rather than an adaptive set, so one rendition rather than every one under the ceiling — with
     * the ceiling applied.
     *
     * The display is a ceiling on height, which is the shorter edge of the landscape content a ladder
     * is encoded as: a rendition taller than the screen's shorter edge is one the device can never show
     * at its own size, and a policy may narrow below what the device allows and never widen past it.
     */
    val parameters: TrackSelectionParameters = DownloadHelper.DEFAULT_TRACK_SELECTOR_PARAMETERS_WITHOUT_CONTEXT.buildUpon()
        .setMaxVideoBitrate(policy.maxVideoBitrateBps)
        .setMaxVideoSize(TrackSelectionPolicy.UNLIMITED, minOf(policy.maxVideoHeightPx, displayShortEdgePx ?: TrackSelectionPolicy.UNLIMITED))
        .build()

    /** Replaces whatever [helper] selected by default with this selection, in every period. Once prepared. */
    fun applyTo(helper: DownloadHelper) {
        val video = parameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        val defaultAudio = parameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        val audio = carried(helper, C.TRACK_TYPE_AUDIO, audioLanguages)
        val subtitles = carried(helper, C.TRACK_TYPE_TEXT, subtitleLanguages)
        for (period in 0 until helper.periodCount) {
            helper.clearTrackSelections(period)
            helper.addTrackSelection(period, video)
            // No declared language is carried — none was declared, or the content has none of them — so
            // the audio is the one a player of this content would choose: a download with no audio at all
            // is not the content the viewer will watch, while one in a language nobody declared is at
            // worst the language the content is mostly watched in (ADR-0013 rule 12's addendum).
            if (audio.isEmpty()) helper.addTrackSelection(period, defaultAudio)
        }
        // Once per language rather than one pass preferring them all, because a pass selects one audio
        // track: each declared language the content carries is a download of its own track.
        if (audio.isNotEmpty()) helper.addAudioLanguagesToSelection(*audio.toTypedArray())
        // Subtitles no declared language names are not downloaded — `false` keeps an undetermined one out
        // too — and a declared language the content does not carry is skipped rather than refused.
        if (subtitles.isNotEmpty()) helper.addTextLanguagesToSelection(false, *subtitles.toTypedArray())
    }

    /**
     * Those of [declared] that some track of [trackType] in [helper]'s content carries, in the order
     * declared.
     *
     * Filtered here because Media3's selector treats a preferred language as a preference: asked for a
     * language no track carries, it selects another rather than nothing, and that other would be a
     * language the viewer did not ask for, downloaded under a declaration that did not name it.
     */
    private fun carried(helper: DownloadHelper, trackType: Int, declared: List<String>): List<String> {
        if (declared.isEmpty()) return emptyList()
        val offered = (0 until helper.periodCount).flatMap { period ->
            helper.getTracks(period).groups
                .filter { it.type == trackType }
                .flatMap { group -> (0 until group.length).mapNotNull { group.getTrackFormat(it).language } }
        }.mapNotNull { language -> normalized(language) }.toSet()
        return declared.filter { language ->
            val wanted = normalized(language) ?: return@filter false
            offered.any { it == wanted || it.startsWith("$wanted-") || wanted.startsWith("$it-") }
        }
    }

    // Typed here: Media3's signature carries a nullness annotation this module's classpath does not.
    private fun normalized(language: String): String? = Util.normalizeLanguageCode(language)
}
