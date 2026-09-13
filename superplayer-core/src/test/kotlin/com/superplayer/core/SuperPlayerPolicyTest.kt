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

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * A supplied [PlaybackPolicy], and what it takes for one to be consulted more than once.
 *
 * ADR-0009 rules 4, 5, 6 and 7, each asserted through the facade. The engine components an adaptive
 * policy brings are `superplayer-abr`'s and do not exist yet, so the stand-in here is
 * [RetargetablePolicy]: a policy that is also an [EnginePolicyExtension], fills the same slots abr
 * will, and honours the decisions core hands it in the one way this harness can see — by moving the
 * ceiling on the engine's own selector, which for the audio-only synthetic stream means its audio
 * ladder, since that is the only ladder Robolectric can play (`SuperPlayerCmcdTest` says why video
 * is out of reach). What the test double proves is therefore that the *second* decision reached the
 * engine and moved what it selects, observed as a track switch on the facade's own listener; what
 * it does not prove is anything about how an adaptive load control re-targets, which is abr's to
 * test. Every assertion is on `playbackDecision`, `currentTracks`, a `Player.Listener`, or the
 * telemetry a collector received — nothing reaches past the facade.
 *
 * A rebuffer ending is the one trigger not driven here: core's tests have no fault injection, and
 * `superplayer-testkit`'s harness is where a stall can be scripted.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerPolicyTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val streamDirectory: TemporaryFolder = TemporaryFolder()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aSuppliedPolicyDecidesInPlaceOfTheProfiles() {
        val supplied = PlaybackDecision(
            buffer = BufferPolicy(
                minBufferMs = 5_000,
                maxBufferMs = 12_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            trackSelection = TrackSelectionPolicy(maxVideoBitrateBps = 900_000, maxVideoHeightPx = 720),
        )

        val player = harness.buildPlayer(
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            policy = PlaybackPolicy { supplied },
        )

        // The profile still names what kind of playback this is; the policy decides how.
        assertThat(player.profile).isEqualTo(PlaybackProfile.VIDEO_ON_DEMAND)
        assertThat(player.playbackDecision).isEqualTo(supplied)
        assertThat(player.trackSelectionParameters.maxVideoBitrate).isEqualTo(900_000)
        assertThat(player.trackSelectionParameters.maxVideoHeight).isEqualTo(720)
    }

    /**
     * ADR-0009 rule 5: a policy without engine components that can honour a changed decision is
     * consulted once, with empty conditions, however adaptive it is — and the facade reports that
     * one answer for the player's lifetime rather than half of a later one.
     */
    @Test
    fun aPolicyWithoutEngineComponentsIsConsultedOnceWithEmptyConditions() {
        val policy = RecordingPolicy()
        val player = harness.buildPlayer(policy = policy)

        player.setMediaRequest(request(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
        // A trigger on a player that could honour it; here it must not even be asked about.
        player.setPlaybackSpeed(2f)
        harness.settle(player)

        assertThat(policy.consultations).containsExactly(PlaybackConditions())
        assertThat(player.playbackDecision).isEqualTo(UNCAPPED)
    }

    /**
     * ADR-0009 rule 7's "pays nothing", counted rather than assumed: a player built with a profile
     * alone registers no observer and runs the profile's own decision. The same count is taken for
     * a player that *does* bring a target, so the counter is shown to see what it counts.
     */
    @Test
    fun aPlayerBuiltWithoutASuppliedPolicyRegistersNothingAndDecidesAsBefore() {
        val callbacksBefore = shadowOf(connectivityManager()).networkCallbacks.size

        val player = harness.buildPlayer(profile = PlaybackProfile.DATA_SAVER)

        assertThat(shadowOf(connectivityManager()).networkCallbacks.size).isEqualTo(callbacksBefore)
        assertThat(player.playbackDecision)
            .isEqualTo(PlaybackPolicy.forProfile(PlaybackProfile.DATA_SAVER).decide(PlaybackConditions()))

        harness.buildPlayer(policy = RetargetablePolicy(context) { UNCAPPED })
        assertThat(shadowOf(connectivityManager()).networkCallbacks.size).isEqualTo(callbacksBefore + 1)
    }

    /**
     * The claim the issue is about: on a player whose engine can honour a changed decision, a second,
     * different decision takes effect — reported by `playbackDecision`, handed whole to the engine's
     * components, visible as a track switch, and signalled as a `DecisionChanged` with its trigger.
     */
    @Test
    fun aSecondDecisionTakesEffectThroughTheFacade() {
        val events = mutableListOf<TelemetryEvent>()
        val policy = RetargetablePolicy(context) { conditions ->
            if ((conditions.playbackSpeed ?: 1f) > 1f) CAPPED else UNCAPPED
        }
        val player = harness.buildPlayerOnItsOwnTransferChain(
            policy = policy,
            telemetry = RecordingTelemetry(TelemetrySink { events += it }),
        )
        val selectedBitrates = mutableListOf<Int>()
        player.addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    tracks.selectedAudioBitrate()?.let { selectedBitrates += it }
                }
            },
        )

        val uri = SyntheticHlsStream.writeTo(streamDirectory.root, segmentCount = 12, variantCount = 2)
        player.setMediaRequest(request(uri))
        player.prepare()
        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(player.playbackDecision).isEqualTo(UNCAPPED)
        assertThat(selectedBitrates).containsExactly(SyntheticHlsStream.HIGHER_DECLARED_BITRATE_BPS)

        player.setPlaybackSpeed(2f)
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_ENDED)

        assertThat(player.playbackDecision).isEqualTo(CAPPED)
        assertThat(policy.applied).containsExactly(UNCAPPED, CAPPED).inOrder()
        // The switch: from the rung the first decision allowed to the rung the second one does.
        assertThat(selectedBitrates)
            .containsExactly(
                SyntheticHlsStream.HIGHER_DECLARED_BITRATE_BPS,
                SyntheticHlsStream.DECLARED_BITRATE_BPS,
            )
            .inOrder()
        val changed = events.filterIsInstance<TelemetryEvent.DecisionChanged>().single()
        assertThat(changed.decision).isEqualTo(CAPPED)
        assertThat(changed.trigger).isEqualTo(DecisionTrigger.PLAYBACK_SPEED_CHANGED)
        assertThat(events.filterIsInstance<TelemetryEvent.SessionStarted>().single().decision)
            .isEqualTo(UNCAPPED)
    }

    /**
     * What the policy is handed is what core observed: the transport and the heap at construction,
     * the stream type once the manifest is read, and a transport change as it happens — each a
     * SuperPlayer value, none of them a guess.
     */
    @Test
    fun thePolicyIsHandedWhatCoreObserved() {
        setActiveTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val events = mutableListOf<TelemetryEvent>()
        val policy = RetargetablePolicy(context) { conditions ->
            if (conditions.transport is NetworkTransport.Cellular) CAPPED else UNCAPPED
        }
        val player = harness.buildPlayer(policy = policy, telemetry = RecordingTelemetry(TelemetrySink { events += it }))

        val atConstruction = policy.consultations.single()
        assertThat(atConstruction.transport).isEqualTo(NetworkTransport.Wifi)
        assertThat(atConstruction.streamType).isNull()
        assertThat(atConstruction.throughput).isNull()
        assertThat(atConstruction.stallHistory).isEqualTo(StallHistory.NONE)
        assertThat(atConstruction.playbackSpeed).isEqualTo(1f)
        assertThat(checkNotNull(atConstruction.heapBudgetBytes)).isGreaterThan(0L)

        player.setMediaRequest(request(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(policy.consultations.last().streamType).isEqualTo(StreamType.ON_DEMAND)
        // Same answer, so no change and no event: a consultation is not a decision change.
        assertThat(events.filterIsInstance<TelemetryEvent.DecisionChanged>()).isEmpty()

        // The generation is read only with the app's own permission, which the library never asks
        // for; this app holds it.
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(telephonyManager()).setDataNetworkType(TelephonyManager.NETWORK_TYPE_LTE)
        setActiveTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        harness.settle(player)

        assertThat(policy.consultations.last().transport)
            .isEqualTo(NetworkTransport.Cellular(CellularGeneration.LTE))
        assertThat(player.playbackDecision).isEqualTo(CAPPED)
        assertThat(events.filterIsInstance<TelemetryEvent.DecisionChanged>().single().trigger)
            .isEqualTo(DecisionTrigger.TRANSPORT_CHANGED)
    }

    @Test
    fun releasingUnregistersTheObservers() {
        val callbacksBefore = shadowOf(connectivityManager()).networkCallbacks.size
        val player = harness.buildPlayer(policy = RetargetablePolicy(context) { UNCAPPED })
        assertThat(shadowOf(connectivityManager()).networkCallbacks.size).isEqualTo(callbacksBefore + 1)

        player.release()

        assertThat(shadowOf(connectivityManager()).networkCallbacks.size).isEqualTo(callbacksBefore)
    }

    /** Sets the device's default network to one of [transport], and tells every registered callback. */
    private fun setActiveTransport(transport: Int) {
        val connectivity = connectivityManager()
        val network = checkNotNull(connectivity.activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(transport)
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities)
        shadowOf(connectivity).networkCallbacks.forEach { it.onCapabilitiesChanged(network, capabilities) }
    }

    private fun connectivityManager(): ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun telephonyManager(): TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private fun request(uri: String): MediaRequest =
        MediaRequest.Builder("test:ladder").addSource(uri).build()

    /** A policy that keeps what it was asked, and answers with a fixed cap or none. */
    private open class RecordingPolicy(
        private val decideWith: (PlaybackConditions) -> PlaybackDecision = { UNCAPPED },
    ) : PlaybackPolicy {
        val consultations = mutableListOf<PlaybackConditions>()

        override fun decide(conditions: PlaybackConditions): PlaybackDecision {
            consultations += conditions
            return decideWith(conditions)
        }
    }

    /**
     * The stand-in for `superplayer-abr`'s policy object: the same interface, the same slots, and
     * a target that records every decision it is handed and honours the selection half the one way
     * a fixed selection can take a ceiling — through the selector it installed itself. See the
     * class KDoc for what that proves and what it does not.
     */
    private class RetargetablePolicy(
        context: Context,
        decideWith: (PlaybackConditions) -> PlaybackDecision,
    ) : RecordingPolicy(decideWith),
        EnginePolicyExtension {

        val applied = mutableListOf<PlaybackDecision>()
        private val selector = DefaultTrackSelector(context)

        override fun configureEngine(configuration: EngineConfiguration) {
            // Media3's own, standing in the slot AdaptiveLoadControl will fill: what this test can
            // see of the buffer half is that the decision reached the target, not how it re-targets.
            configuration.loadControl = DefaultLoadControl.Builder().build()
            configuration.engine.setTrackSelector(selector)
            configuration.decisionTarget = DecisionTarget { decision ->
                applied += decision
                // The decision's ceiling is a video ceiling; this stream has only an audio ladder,
                // so the double lays the same number over the only rungs there are to choose from.
                selector.setParameters(
                    selector.buildUponParameters()
                        .setMaxAudioBitrate(decision.trackSelection.maxVideoBitrateBps)
                        .build(),
                )
            }
        }
    }

    private companion object {
        val UNCAPPED = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND).decide(PlaybackConditions())

        val CAPPED = UNCAPPED.copy(
            buffer = UNCAPPED.buffer.copy(minBufferMs = 15_000, maxBufferMs = 40_000),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = SyntheticHlsStream.DECLARED_BITRATE_BPS,
                maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
            ),
        )

        /** The declared bitrate of the one audio track selected, or null when none is. */
        fun Tracks.selectedAudioBitrate(): Int? = groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).filter(group::isTrackSelected).map(group::getTrackFormat) }
            .singleOrNull()
            ?.bitrate
            ?.takeIf { it != androidx.media3.common.Format.NO_VALUE }
    }
}
