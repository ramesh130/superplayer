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

package com.superplayer.tv

import android.content.Context
import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackOutput
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.SyntheticDashPassthrough
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An AV receiver powered on or off under playback, on a player built with `TvOutput.standard` (#270,
 * ADR-0014 rule 6).
 *
 * The content is DASH offering stereo AAC beside 5.1 AC-3 (`TestContent.dashWithPassthroughAudio`), on a
 * television whose audio output `DeviceStatement.declareAudioOutput` states and a scheduled change restates.
 * The harness's audio renderer plays AAC on any output and AC-3 only where the output passes it through, and
 * tells the selector when the output changes, as Media3's own audio renderer does. The selection is read
 * where a consumer reads it, from `currentTracks`, and confirmed by what the player fetched: each encoding is
 * at a path of its own.
 *
 * Each claim carries its control: a change of capabilities the content's tracks do not use, which re-selects
 * nothing, and the same receiver on a player built without the module, which keeps Media3's default and so
 * keeps its audio.
 *
 * What is not asserted, and why: a sink refusing a format mid-change. The harness's renderer configures no
 * audio track, so no `AudioSink` exception can arise here. How such a refusal is named is asserted over real
 * exceptions in `superplayer-resilience`'s `ErrorClassifierTest` and `FallbackLadderTest`. Whether the
 * receiver then decoded the passthrough stream, and the silence while its HDMI link renegotiates, are a
 * device's to show (`docs/testing.md`, *A TV device*).
 */
@RunWith(AndroidJUnit4::class)
class AudioCapabilityChangeTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun aTelevision() {
        // Stated before the first player: Media3 reads the UI mode to choose where it reads the output from.
        DeviceStatement.declareTelevision()
    }

    @Test
    fun aReceiverPoweredOnMidPlaybackMovesTheAudioToPassthroughAtThePositionReached() {
        DeviceStatement.declareAudioOutput()
        val player = play(TvOutput.standard(context))
        harness.advanceTimeMs(player, PLAYED_BEFORE_MS)
        assertThat(selectedAudioMimeType(player)).isEqualTo(MimeTypes.AUDIO_AAC)
        assertThat(requestsFor(player, SyntheticDashPassthrough.SURROUND_PATH)).isEqualTo(0)
        val positionAtChange = player.currentPosition

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareAudioOutput(AudioFormat.ENCODING_AC3) }
        harness.advanceUntil(player, "the AC-3 rendition selected", RESELECT_BOUND_MS) {
            selectedAudioMimeType(player) == MimeTypes.AUDIO_AC3
        }
        harness.advanceTimeInStepsMs(player, PLAYED_ON_MS)

        assertThat(selectedAudioMimeType(player)).isEqualTo(MimeTypes.AUDIO_AC3)
        assertThat(requestsFor(player, SyntheticDashPassthrough.SURROUND_PATH)).isGreaterThan(0)
        assertThat(player.playerError).isNull()
        assertThat(player.currentPosition).isGreaterThan(positionAtChange)
    }

    @Test
    fun aReceiverPoweredOffMidPlaybackReturnsTheAudioToPcmWithNoError() {
        DeviceStatement.declareAudioOutput(AudioFormat.ENCODING_AC3)
        val player = play(TvOutput.standard(context))
        harness.advanceTimeMs(player, PLAYED_BEFORE_MS)
        assertThat(selectedAudioMimeType(player)).isEqualTo(MimeTypes.AUDIO_AC3)
        val stereoRequestsBefore = requestsFor(player, SyntheticDashPassthrough.STEREO_PATH)
        val positionAtChange = player.currentPosition

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareAudioOutput() }
        harness.advanceUntil(player, "the AAC rendition selected", RESELECT_BOUND_MS) {
            selectedAudioMimeType(player) == MimeTypes.AUDIO_AAC
        }
        harness.advanceTimeInStepsMs(player, PLAYED_ON_MS)

        assertThat(selectedAudioMimeType(player)).isEqualTo(MimeTypes.AUDIO_AAC)
        assertThat(requestsFor(player, SyntheticDashPassthrough.STEREO_PATH)).isGreaterThan(stereoRequestsBefore)
        assertThat(player.playerError).isNull()
        assertThat(player.currentPosition).isGreaterThan(positionAtChange)
    }

    /**
     * The control for "re-select on any change": DTS added to an output playing AAC-and-AC-3 content changes
     * nothing either of its tracks needs, so the selection, and what is fetched, stay as they were. The same
     * player then hears AC-3 arrive and moves, so the silence before it is not a player that heard nothing.
     */
    @Test
    fun aChangeTheContentsTracksDoNotUseReselectsNothing() {
        DeviceStatement.declareAudioOutput()
        val player = play(TvOutput.standard(context))
        harness.advanceTimeMs(player, PLAYED_BEFORE_MS)
        val tracksChanged = mutableListOf<String?>()
        player.addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    tracksChanged += selectedAudioMimeType(player)
                }
            },
        )
        val stereoInitsBefore = initializationsFor(player, SyntheticDashPassthrough.STEREO_PATH)

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareAudioOutput(AudioFormat.ENCODING_DTS) }
        harness.advanceTimeInStepsMs(player, PLAYED_ON_MS)

        assertWithMessage("tracks changed to $tracksChanged").that(tracksChanged).isEmpty()
        assertThat(selectedAudioMimeType(player)).isEqualTo(MimeTypes.AUDIO_AAC)
        assertThat(initializationsFor(player, SyntheticDashPassthrough.STEREO_PATH)).isEqualTo(stereoInitsBefore)
        assertThat(requestsFor(player, SyntheticDashPassthrough.SURROUND_PATH)).isEqualTo(0)

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareAudioOutput(AudioFormat.ENCODING_DTS, AudioFormat.ENCODING_AC3) }
        harness.advanceUntil(player, "the AC-3 rendition selected", RESELECT_BOUND_MS) {
            selectedAudioMimeType(player) == MimeTypes.AUDIO_AC3
        }
        assertThat(player.playerError).isNull()
    }

    /**
     * ADR-0014 rule 6's other half: a player built without `setOutput` keeps Media3's default, which hears
     * the receiver and does not act on it. Its audio stays on the AAC it chose, and it fetches no AC-3.
     */
    @Test
    fun aPlayerBuiltWithoutTheModuleKeepsItsAudioWhenAReceiverComesOn() {
        DeviceStatement.declareAudioOutput()
        val player = play(output = null)
        harness.advanceTimeMs(player, PLAYED_BEFORE_MS)
        assertThat(selectedAudioMimeType(player)).isEqualTo(MimeTypes.AUDIO_AAC)

        harness.scheduleDeviceChange(afterMs = 0) { DeviceStatement.declareAudioOutput(AudioFormat.ENCODING_AC3) }
        harness.advanceTimeInStepsMs(player, PLAYED_ON_MS)

        assertThat(selectedAudioMimeType(player)).isEqualTo(MimeTypes.AUDIO_AAC)
        assertThat(requestsFor(player, SyntheticDashPassthrough.SURROUND_PATH)).isEqualTo(0)
        assertThat(player.playerError).isNull()
    }

    private fun play(output: PlaybackOutput?): SuperPlayer {
        val player = harness.buildPlayer(content = CONTENT, profile = PlaybackProfile.TV_LEANBACK, output = output)
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(CONTENT.sourceUri).build())
        harness.playToReady(player)
        return player
    }

    /** The sample MIME type of the audio track the player is playing, as a consumer reads it; null when none is. */
    private fun selectedAudioMimeType(player: SuperPlayer): String? = player.currentTracks.groups
        .filter { it.type == C.TRACK_TYPE_AUDIO }
        .flatMap { group -> (0 until group.length).filter(group::isTrackSelected).map { group.getTrackFormat(it) } }
        .singleOrNull()
        ?.sampleMimeType

    private fun requestsFor(player: SuperPlayer, renditionPath: String): Int =
        harness.networkRequests(player).count { renditionPath in it.uri }

    private fun initializationsFor(player: SuperPlayer, renditionPath: String): Int =
        harness.networkRequests(player).count { "$renditionPath$INITIALIZATION" in it.uri }

    private companion object {
        val CONTENT: TestContent = TestContent.dashWithPassthroughAudio(segmentCount = 10)
        const val CONTENT_ID = "films/sintel"

        /** The initialization segment's name, which `SyntheticDashStream` gives every rendition. */
        const val INITIALIZATION = "init.mp4"

        /** Two seconds, a segment's worth: long enough that a selection has been made and media fetched under it. */
        const val PLAYED_BEFORE_MS = 2_000L

        /** A re-selection is posted on the playback thread when the change is heard, so a second is ample. */
        const val RESELECT_BOUND_MS = 1_000L

        /** Four seconds, two segments: long enough for a new selection's media to be fetched and played. */
        const val PLAYED_ON_MS = 4_000L
    }
}
