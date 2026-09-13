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

package com.superplayer.abr

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.DecisionTarget
import com.superplayer.core.DeviceConstraints
import com.superplayer.core.EngineConfiguration
import com.superplayer.core.EnginePolicyExtension
import com.superplayer.core.MediaRequest
import com.superplayer.core.NetworkTransport
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The oracle under a real player, on the harness's clock, over a replayed network.
 *
 * What the unit tests cannot show is here: that the oracle sees every transfer the real chain makes
 * and no more; that a trace's handover reaches it as a change of network at the millisecond the
 * trace names; that the estimate the engine selects on and the one a policy is handed are the same
 * number; and that CMCD's `mtp` — the one key `SuperPlayerCmcdTest` could not reach, because it
 * needs an adaptive selection — travels once there is one.
 *
 * The oracle reaches the engine the way `superplayer-abr`'s policy will: through core's extension
 * interface, which this module sees as core's second friend. [OracleInstallingPolicy] is that seam
 * with nothing else in it, the way `SuperPlayerPolicyTest`'s stand-in is.
 */
@RunWith(AndroidJUnit4::class)
class BandwidthOraclePlaybackTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val oracles = mutableListOf<BandwidthOracle>()

    @Before
    fun forgetTheProcessMemory() {
        EstimateMemory.PROCESS.forget()
    }

    @After
    fun releaseOracles() {
        oracles.forEach { it.release() }
        EstimateMemory.PROCESS.forget()
    }

    @Test
    fun theEstimateAfterTheHandoverIsCellularsAndNotWifisCarriedOver() {
        val oracle = build()
        val player = harness.buildPlayer(
            // Long enough that the buffer has not reached the end of the content by the handover.
            content = TestContent.videoLadder(durationMs = LONG_CONTENT_MS),
            network = NetworkProfile.WIFI_TO_CELLULAR_HANDOVER.trace,
            policy = OracleInstallingPolicy(oracle),
        )
        val builtAtMs = harness.elapsedRealtimeMs()
        player.setMediaRequest(request())
        harness.playToReady(player)

        // One step short of the handover: WiFi, measured, and measured as WiFi — every sample on a
        // 20 Mbit/s link is at most 20 Mbit/s and, being at least 16 KiB over at least 20 ms of a
        // link with no round trip, well over the 5 Mbit/s the trace moves to.
        harness.advanceTimeInStepsMs(player, NetworkProfile.HANDOVER_AT_MS - STEP_MS - (harness.elapsedRealtimeMs() - builtAtMs))
        val onWifi = oracle.currentEstimate()
        assertThat(oracle.currentTransport()).isEqualTo(NetworkTransport.Wifi)
        assertThat(onWifi.sampleCount).isGreaterThan(0)
        assertThat(onWifi.meanBps).isAtMost(STABLE_WIFI_BPS)
        assertThat(onWifi.meanBps).isGreaterThan(LTE_BPS)

        // The millisecond the trace names: the device is on cellular, and the estimate is cellular's
        // cold default — nothing has been measured there in this process — not WiFi's carried over.
        harness.advanceTimeInStepsMs(player, STEP_MS)
        assertThat(harness.elapsedRealtimeMs() - builtAtMs).isEqualTo(NetworkProfile.HANDOVER_AT_MS)
        val atHandover = oracle.currentEstimate()
        assertThat(oracle.currentTransport()).isEqualTo(NetworkTransport.Cellular(null))
        assertThat(atHandover.sampleCount).isEqualTo(0)
        assertThat(atHandover.meanBps).isEqualTo(ColdDefaults.CELLULAR_UNKNOWN_GENERATION_BPS)
        assertThat(atHandover.meanBps).isNotEqualTo(onWifi.meanBps)

        // A buffer WiFi filled asks for nothing until it drains below the profile's minimum, so
        // the first cellular sample is waited for, bounded by that drain (`NetworkShapingPlaybackTest`
        // says why a fixed span is wrong here). Once it arrives it is a cellular number.
        val buffer = player.playbackDecision.buffer
        harness.advanceUntil(player, "a sample on cellular", (buffer.maxBufferMs - buffer.minBufferMs) + DRAIN_MARGIN_MS) {
            oracle.currentEstimate().sampleCount > 0
        }
        val onCellular = oracle.currentEstimate()
        assertThat(onCellular.meanBps).isAtMost(LTE_BPS)
        assertThat(onCellular.meanBps).isGreaterThan(0)
        assertThat(player.playerError).isNull()
    }

    @Test
    fun everyTransferThroughTheRealChainIsSampledExactlyOnce() {
        val oracle = build()
        val content = TestContent.hls(segmentCount = SEGMENTS)
        val player = harness.buildPlayer(
            content = content,
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = OracleInstallingPolicy(oracle),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)
        harness.advanceUntil(player, "the stream to end") { it.playbackState == Player.STATE_ENDED }

        // Every segment, each fetched once by a VOD session, and the origin's own bytes for each:
        // what the oracle counted is what moved — the registration is propagated down the chain
        // rather than re-raised by any layer, so a byte reported is a byte the origin handed over,
        // and one it handed over once. The two playlists are not in the count, and that is the
        // engine's doing rather than the oracle's: Media3 hands its media transfer listener to the
        // chunk loads and not to the playlist tracker's, so a manifest is never a throughput sample
        // in any Media3 player. `DefaultBandwidthMeter` sees the same four transfers this did.
        val segments = SyntheticHlsStream.resources(SEGMENTS).filterKeys { it.endsWith(SyntheticHlsStream.SEGMENT_SUFFIX) }
        assertThat(segments).hasSize(SEGMENTS)
        assertThat(oracle.meter.countedTransfers).isEqualTo(segments.size)
        assertThat(oracle.meter.countedBytes).isEqualTo(segments.values.sumOf { it.size.toLong() })
        assertThat(oracle.currentEstimate().sampleCount).isGreaterThan(0)
        assertThat(player.playerError).isNull()
    }

    @Test
    fun thePolicyIsHandedTheSameEstimateTheEngineSelectsOn() {
        val oracle = build()
        val policy = OracleInstallingPolicy(oracle, retargetable = true) { conditions ->
            if ((conditions.throughput?.sampleCount ?: 0) > 0) CAPPED else UNCAPPED
        }
        val player = harness.buildPlayer(
            content = TestContent.videoLadder(),
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = policy,
        )
        // At construction: the transport core read, and the oracle's cold default for it — a
        // number, not "unobserved", because the meter implements the reading seam.
        val atConstruction = policy.consultations.single()
        assertThat(atConstruction.transport).isEqualTo(NetworkTransport.Wifi)
        assertThat(checkNotNull(atConstruction.throughput).sampleCount).isEqualTo(0)
        assertThat(atConstruction.throughput?.meanBps).isEqualTo(ColdDefaults.WIFI_BPS)
        assertThat(player.playbackDecision).isEqualTo(UNCAPPED)

        player.setMediaRequest(request())
        harness.playToReady(player)
        harness.advanceUntil(player, "the policy to be consulted with a measured throughput") {
            player.playbackDecision == CAPPED
        }

        // The first sample is a material move by definition, and what the policy was handed on it
        // is the oracle's own reading: measured, on a 20 Mbit/s link, with a spread.
        val measured = checkNotNull(policy.consultations.last().throughput)
        assertThat(measured.sampleCount).isGreaterThan(0)
        assertThat(measured.meanBps).isAtMost(STABLE_WIFI_BPS)
        assertThat(measured.spreadBps).isNotNull()
        assertThat(measured.conservativeBps).isNotNull()
        assertThat(policy.consultations.last().transport).isEqualTo(NetworkTransport.Wifi)
        assertThat(player.playerError).isNull()
    }

    @Test
    fun theMeasuredThroughputKeyTravelsOnceTheSelectionIsAdaptive() {
        val oracle = build()
        val requests = RecordingMeter(oracle.meter)
        val content = TestContent.hls(segmentCount = SEGMENTS, variantCount = 2)
        val player = harness.buildPlayer(
            content = content,
            network = NetworkProfile.STABLE_WIFI.trace,
            policy = OracleInstallingPolicy(oracle, meter = requests, adaptiveAudio = true),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)
        harness.advanceUntil(player, "the stream to end") { it.playbackState == Player.STATE_ENDED }

        // spec: CTA-5004 §3.1 — `mtp`, the throughput the player measured, in kbps, rounded to the
        // nearest 100 kbps. Media3 fills it from the adaptive selection's latest estimate, which is
        // this oracle's; a fixed selection reports none, which is why the two-variant stream. The
        // last segment's value is asserted rather than the first's because the first request goes
        // out before a byte has moved, and its `mtp` is the cold default rather than a measurement.
        val segments = requests.segmentRequests()
        assertThat(segments).isNotEmpty()
        val last = checkNotNull(segments.last().cmcdKeys()["mtp"]) { "No mtp on ${segments.last().uri}: ${segments.last().httpRequestHeaders}" }
        assertThat(last.toLong()).isGreaterThan(0)
        assertThat(last.toLong()).isAtMost(STABLE_WIFI_BPS / 1_000)
        // spec: CTA-5004 §3.1 — `br`, the encoded bitrate of the requested object in kbps. On a
        // link that affords the whole ladder the selection settles on its top variant, and `br` is
        // that variant's declared rate: the selection is what CMCD describes.
        val br = checkNotNull(segments.last().cmcdKeys()["br"]) { "No br on ${segments.last().uri}" }
        assertThat(br.toInt()).isEqualTo(SyntheticHlsStream.HIGHER_DECLARED_BITRATE_BPS / 1_000)
        assertThat(player.playerError).isNull()
    }

    private fun build(): BandwidthOracle = BandwidthOracle.Builder(context).build().also { oracles += it }

    private fun request(): MediaRequest = MediaRequest.Builder(CONTENT).addSource(SOURCE).build()

    /**
     * The seam `superplayer-abr`'s policy object will be, with only the oracle in it: the policy
     * decides with [decideWith], and the engine is built with the oracle's meter — or [meter], a
     * decorator over it, for the test that records what the engine requested.
     *
     * [retargetable] adds a decision target, which is what turns core's re-consultation on
     * (ADR-0009 rule 5): Media3's own load control stands in the slot `AdaptiveLoadControl` will
     * fill, and the selection half is honoured the one way a selector can take a ceiling.
     *
     * [adaptiveAudio] lets Media3's selector adapt between the synthetic stream's two audio
     * variants. They declare a codec and a bitrate and nothing else, and the selector will not
     * adapt between audio tracks whose channel count or sample rate it cannot compare unless told
     * that it may; told, the selection over them is a real `AdaptiveTrackSelection` over this
     * oracle's estimate, and a fake of neither.
     */
    private class OracleInstallingPolicy(
        private val oracle: BandwidthOracle,
        private val retargetable: Boolean = false,
        private val meter: BandwidthMeter? = null,
        private val adaptiveAudio: Boolean = false,
        private val decideWith: (PlaybackConditions) -> PlaybackDecision = { UNCAPPED },
    ) : PlaybackPolicy,
        EnginePolicyExtension {

        val consultations = mutableListOf<PlaybackConditions>()

        override fun decide(conditions: PlaybackConditions): PlaybackDecision {
            consultations += conditions
            return decideWith(conditions)
        }

        override fun configureEngine(configuration: EngineConfiguration) {
            oracle.configure(configuration)
            meter?.let { configuration.bandwidthMeter = it }
            if (adaptiveAudio) {
                // SuperPlayer's own selection factory, over the recording meter, so what `mtp`
                // reports is what `NetworkAwareTrackSelection` inherits from Media3 unchanged.
                val selections = NetworkAwareTrackSelection.Factory(
                    SelectionThresholds.forProfile(PlaybackProfile.VIDEO_ON_DEMAND),
                    NetworkAwareTrackSelection.Gate(DeviceConstraints.UNKNOWN, oracle.meter, UNCAPPED.trackSelection),
                )
                configuration.engine.setTrackSelector(
                    DefaultTrackSelector(ApplicationProvider.getApplicationContext(), selections).apply {
                        parameters = buildUponParameters()
                            .setAllowAudioMixedChannelCountAdaptiveness(true)
                            .setAllowAudioMixedSampleRateAdaptiveness(true)
                            .build()
                    },
                )
            }
            if (retargetable) {
                val selector = DefaultTrackSelector(ApplicationProvider.getApplicationContext())
                configuration.loadControl = DefaultLoadControl.Builder().build()
                configuration.engine.setTrackSelector(selector)
                configuration.decisionTarget = DecisionTarget { decision ->
                    selector.setParameters(
                        selector.buildUponParameters()
                            .setMaxVideoBitrate(decision.trackSelection.maxVideoBitrateBps)
                            .build(),
                    )
                }
            }
        }
    }

    /**
     * Every request the engine opens, recorded, over the oracle's own meter — the same shape
     * `SuperPlayerCmcdTest`'s recorder has, and delegating for the same reason: nothing a test
     * observes here is downstream of a number this class invented.
     */
    private class RecordingMeter(private val delegate: OracleBandwidthMeter) : BandwidthMeter by delegate {

        private val opened = mutableListOf<DataSpec>()

        override fun getTransferListener(): TransferListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.onTransferInitializing(source, dataSpec, isNetwork)
            }

            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                synchronized(opened) { opened += dataSpec }
                delegate.onTransferStart(source, dataSpec, isNetwork)
            }

            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                delegate.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
            }

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                delegate.onTransferEnd(source, dataSpec, isNetwork)
            }
        }

        fun segmentRequests(): List<DataSpec> = synchronized(opened) { opened.toList() }
            .filter { it.uri.path.orEmpty().endsWith(SyntheticHlsStream.SEGMENT_SUFFIX) }
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e02"
        const val SOURCE = "fake://superplayer.test/never-fetched"
        const val LONG_CONTENT_MS = 180_000L
        const val SEGMENTS = 4

        /** The harness's own load step, so the handover lands on a step boundary. */
        const val STEP_MS = 250L

        /** `NetworkShapingPlaybackTest`'s margin, for the same drain. */
        const val DRAIN_MARGIN_MS = 5_000L

        /** The trace's own rates: WiFi before the handover, LTE after — bounds derived from it, not tolerances. */
        val STABLE_WIFI_BPS = NetworkProfile.WIFI_TO_CELLULAR_HANDOVER.trace.bandwidthBpsAt(0)
        val LTE_BPS = NetworkProfile.WIFI_TO_CELLULAR_HANDOVER.trace.bandwidthBpsAt(NetworkProfile.HANDOVER_AT_MS)

        val UNCAPPED = PlaybackPolicy.forProfile(PlaybackProfile.VIDEO_ON_DEMAND).decide(PlaybackConditions())
        val CAPPED = UNCAPPED.copy(
            trackSelection = TrackSelectionPolicy(maxVideoBitrateBps = 800_000, maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED),
        )

        /** spec: CTA-5004 §3.2 — the header groups; every key set is comma-separated `key=value` pairs. */
        fun DataSpec.cmcdKeys(): Map<String, String> = httpRequestHeaders
            .filterKeys { it.startsWith("CMCD-") }
            .values
            .flatMap { it.split(',') }
            .associate { pair -> pair.trim().substringBefore('=') to pair.trim().substringAfter('=', "") }
    }
}
