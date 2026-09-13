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

import android.media.MediaCodecInfo.CodecProfileLevel
import android.os.Handler
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.DeviceConstraints
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.ThroughputEstimate
import com.superplayer.core.ThroughputSource
import com.superplayer.core.TrackSelectionPolicy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The selection's three refusals and the retarget, each over a hand-built ladder and a stub
 * meter — the unit of `NetworkAwareTrackSelectionPlaybackTest`'s claims, as
 * `AdaptiveLoadControlTest` is of the load control's. Media3's own climb and descent thresholds
 * are bypassed by evaluating with a full buffer, so what is asserted is *eligibility*.
 */
@RunWith(AndroidJUnit4::class)
class NetworkAwareTrackSelectionTest {

    private val meter = StubMeter()

    @Test
    fun aRetargetedCeilingIsHonouredOnTheNextEvaluation() {
        val gate = gate(policy = UNCAPPED)
        val selection = selection(ladder(), gate)
        meter.estimateBps = 20_000_000
        selection.evaluate()
        assertThat(selection.selectedFormat.bitrate).isEqualTo(6_000_000)

        // A ceiling below the playing rung is honoured at once, even with a buffer so deep that
        // Media3 would defer a descent the estimate alone asked for.
        gate.policy = TrackSelectionPolicy(maxVideoBitrateBps = 1_000_000, maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED)
        selection.evaluate(bufferedUs = DEEP_BUFFER_US)
        assertThat(selection.selectedFormat.bitrate).isEqualTo(800_000)

        // Height is the other axis, and either alone refuses.
        gate.policy = TrackSelectionPolicy(maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED, maxVideoHeightPx = 480)
        selection.evaluate()
        assertThat(selection.selectedFormat.height).isEqualTo(480)

        // And a raised ceiling is climbed back to: the hold lapses when the target says so.
        gate.policy = UNCAPPED
        selection.evaluate()
        assertThat(selection.selectedFormat.bitrate).isEqualTo(6_000_000)
    }

    @Test
    fun theDisplayRefusesARungLargerThanItsShorterEdge() {
        val fullHd = DeviceConstraints(displayShortEdgePx = 1_080, displayHdrTypes = null, decodableProfileLevels = emptyMap())
        val selection = selection(ladder(topHeightPx = 2_160), gate(constraints = fullHd))
        meter.estimateBps = 50_000_000
        selection.evaluate()
        // The top rung is refused whatever the link affords; the next one down is the choice.
        assertThat(selection.selectedFormat.height).isEqualTo(720)
        assertThat(selection.selectedFormat.bitrate).isEqualTo(2_400_000)

        // A portrait display shows a landscape rung of its own short edge.
        val portraitPhone = DeviceConstraints(displayShortEdgePx = 1_080, displayHdrTypes = null, decodableProfileLevels = emptyMap())
        assertThat(NetworkAwareTrackSelection.Gate(portraitPhone, null, UNCAPPED).deviceRefuses(video(6_000_000, 1_080))).isFalse()
        assertThat(NetworkAwareTrackSelection.Gate(portraitPhone, null, UNCAPPED).deviceRefuses(video(6_000_000, 1_440))).isTrue()
    }

    @Test
    fun theDisplayRefusesAPqRungItCannotShowOnlyBesideAnSdrOne() {
        val sdrOnly = DeviceConstraints(displayShortEdgePx = null, displayHdrTypes = emptySet(), decodableProfileLevels = emptyMap())
        val hdr10 = DeviceConstraints(displayShortEdgePx = null, displayHdrTypes = setOf(Display.HdrCapabilities.HDR_TYPE_HDR10), decodableProfileLevels = emptyMap())
        val pq = video(6_000_000, 1_080, colorTransfer = C.COLOR_TRANSFER_ST2084)
        val hlg = video(6_000_000, 1_080, colorTransfer = C.COLOR_TRANSFER_HLG)
        val sdr = video(6_000_000, 1_080, colorTransfer = C.COLOR_TRANSFER_SDR)

        assertThat(NetworkAwareTrackSelection.Gate(sdrOnly, null, UNCAPPED).displayRefusesHdr(pq)).isTrue()
        assertThat(NetworkAwareTrackSelection.Gate(sdrOnly, null, UNCAPPED).displayRefusesHdr(sdr)).isFalse()
        assertThat(NetworkAwareTrackSelection.Gate(hdr10, null, UNCAPPED).displayRefusesHdr(pq)).isFalse()
        // HLG is shown by an SDR display by design, and a display that did not answer refuses nothing.
        assertThat(NetworkAwareTrackSelection.Gate(sdrOnly, null, UNCAPPED).displayRefusesHdr(hlg)).isFalse()
        assertThat(NetworkAwareTrackSelection.Gate(DeviceConstraints.UNKNOWN, null, UNCAPPED).displayRefusesHdr(pq)).isFalse()

        // Beside an SDR rung the PQ rung is refused; alone, the ladder is played from the top.
        meter.estimateBps = 50_000_000
        val mixed = selection(TrackGroup(video(300_000, 360), video(2_400_000, 720, colorTransfer = C.COLOR_TRANSFER_SDR), pq), gate(constraints = sdrOnly))
        mixed.evaluate()
        assertThat(mixed.selectedFormat.bitrate).isEqualTo(2_400_000)
        val pqOnly = selection(
            TrackGroup(video(300_000, 360, colorTransfer = C.COLOR_TRANSFER_ST2084), video(2_400_000, 720, colorTransfer = C.COLOR_TRANSFER_ST2084), pq),
            gate(constraints = sdrOnly),
        )
        pqOnly.evaluate()
        assertThat(pqOnly.selectedFormat.bitrate).isEqualTo(6_000_000)
    }

    @Test
    fun theDecoderRefusesAProfileOrLevelItDoesNotReach() {
        val mainTo41 = DeviceConstraints(
            displayShortEdgePx = null,
            displayHdrTypes = null,
            decodableProfileLevels = mapOf(
                MimeTypes.VIDEO_H264 to listOf(DeviceConstraints.ProfileLevel(CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCLevel41)),
            ),
        )
        val gate = NetworkAwareTrackSelection.Gate(mainTo41, null, UNCAPPED)
        // ref: RFC 6381 §3.3 — avc1.PPCCLL: 4D = Main, 64 = High; 1F = level 3.1, 28 = level 4.0, 33 = level 5.1.
        assertThat(gate.deviceRefuses(video(2_000_000, 720, codecs = "avc1.4D401F"))).isFalse()
        assertThat(gate.deviceRefuses(video(2_000_000, 720, codecs = "avc1.4D4028"))).isFalse()
        assertThat(gate.deviceRefuses(video(2_000_000, 720, codecs = "avc1.4D4033"))).isTrue()
        assertThat(gate.deviceRefuses(video(2_000_000, 720, codecs = "avc1.64001F"))).isTrue()
        // No `codecs`, or a MIME type the device said nothing about: nothing to refuse on.
        assertThat(gate.deviceRefuses(video(2_000_000, 720, codecs = null))).isFalse()
        assertThat(gate.deviceRefuses(video(2_000_000, 720, codecs = "hvc1.1.6.L93.B0", mimeType = MimeTypes.VIDEO_H265))).isFalse()
        // A decoder that declared no profiles is unknown, not empty.
        val undeclared = DeviceConstraints(displayShortEdgePx = null, displayHdrTypes = null, decodableProfileLevels = mapOf(MimeTypes.VIDEO_H264 to emptyList()))
        assertThat(NetworkAwareTrackSelection.Gate(undeclared, null, UNCAPPED).deviceRefuses(video(2_000_000, 720, codecs = "avc1.640033"))).isFalse()
    }

    @Test
    fun aLadderTheDeviceRefusesWholeFallsBackToItsBottomRung() {
        val tiny = DeviceConstraints(displayShortEdgePx = 240, displayHdrTypes = null, decodableProfileLevels = emptyMap())
        val selection = selection(ladder(), gate(constraints = tiny))
        meter.estimateBps = 50_000_000
        selection.evaluate()
        assertThat(selection.selectedFormat.bitrate).isEqualTo(300_000)
    }

    @Test
    fun anUnstableEstimateIsDiscountedToItsConservativePercentile() {
        val source = StubSource()
        val selection = selection(ladder(), gate(source = source))
        // 10 Mbit/s at the meter, 70 % of which affords the 6 Mbit/s rung…
        meter.estimateBps = 10_000_000
        source.estimate = estimate(meanBps = 10_000_000, spreadBps = 1_000_000, conservativeBps = 4_000_000)
        selection.evaluate()
        assertThat(selection.selectedFormat.bitrate).isEqualTo(6_000_000)

        // …until the spread crosses the oracle's stable line, when the offer is scaled by 4/10 to
        // 2.8 Mbit/s and the 2.4 Mbit/s rung is what is affordable.
        source.estimate = estimate(meanBps = 10_000_000, spreadBps = 5_000_000, conservativeBps = 4_000_000)
        selection.evaluate()
        assertThat(selection.selectedFormat.bitrate).isEqualTo(2_400_000)

        // A seeded estimate has no spread to speak of, and a percentile above the mean discounts nothing.
        source.estimate = estimate(meanBps = 10_000_000, spreadBps = 5_000_000, conservativeBps = 4_000_000, sampleCount = 0)
        selection.evaluate()
        assertThat(selection.selectedFormat.bitrate).isEqualTo(6_000_000)
    }

    @Test
    fun theFactoryBuildsAnAdaptiveSelectionForALadderAndAFixedOneForASingleTrack() {
        val factory = NetworkAwareTrackSelection.Factory(SelectionThresholds.forProfile(PlaybackProfile.VIDEO_ON_DEMAND), gate())
        val ladder = ladder()
        val single = TrackGroup(video(128_000, 360))
        val built = factory.createTrackSelections(
            arrayOf(
                androidx.media3.exoplayer.trackselection.ExoTrackSelection.Definition(ladder, 0, 1, 2, 3),
                null,
                androidx.media3.exoplayer.trackselection.ExoTrackSelection.Definition(single, 0),
            ),
            meter,
            androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(Any()),
            androidx.media3.common.Timeline.EMPTY,
        )
        assertThat(built[0]).isInstanceOf(NetworkAwareTrackSelection::class.java)
        assertThat(built[1]).isNull()
        assertThat(built[2]).isInstanceOf(androidx.media3.exoplayer.trackselection.FixedTrackSelection::class.java)
    }

    private fun gate(
        constraints: DeviceConstraints = DeviceConstraints.UNKNOWN,
        source: ThroughputSource? = null,
        policy: TrackSelectionPolicy = UNCAPPED,
    ) = NetworkAwareTrackSelection.Gate(constraints, source, policy)

    private fun selection(group: TrackGroup, gate: NetworkAwareTrackSelection.Gate): NetworkAwareTrackSelection =
        NetworkAwareTrackSelection(
            group,
            IntArray(group.length) { it },
            /* type= */ 0,
            meter,
            SelectionThresholds.forProfile(PlaybackProfile.VIDEO_ON_DEMAND),
            gate,
            androidx.media3.common.util.Clock.DEFAULT,
        ).also { it.enable() }

    /**
     * One evaluation, with a buffer Media3's own thresholds let move either way: deep enough to
     * allow a climb (`minDurationForQualityIncreaseMs`) and shallow enough to allow a descent
     * (`maxDurationForQualityDecreaseMs`), so that what is asserted is eligibility and not the
     * thresholds — which are `SelectionThresholdsTest`'s.
     */
    private fun NetworkAwareTrackSelection.evaluate(bufferedUs: Long = MOVABLE_BUFFER_US) {
        // Evaluations are a chunk apart in playback; here the clock is moved by hand, so that an
        // exclusion raised for one evaluation is over by the next as it would be under a player.
        ShadowSystemClock.advanceBy(Duration.ofMillis(BETWEEN_EVALUATIONS_MS))
        updateSelectedTrack(
            /* playbackPositionUs= */ 0,
            /* bufferedDurationUs= */ bufferedUs,
            /* availableDurationUs= */ C.TIME_UNSET,
            /* queue= */ emptyList(),
            /* mediaChunkIterators= */ Array(length()) { MediaChunkIterator.EMPTY },
        )
        // Media3's first evaluation only chooses; a second applies the thresholds. Twice, so every
        // assertion is on the same footing.
        ShadowSystemClock.advanceBy(Duration.ofMillis(BETWEEN_EVALUATIONS_MS))
        updateSelectedTrack(0, bufferedUs, C.TIME_UNSET, emptyList(), Array(length()) { MediaChunkIterator.EMPTY })
    }

    private fun ladder(topHeightPx: Int = 1_080): TrackGroup = TrackGroup(
        video(300_000, 360),
        video(800_000, 480),
        video(2_400_000, 720),
        video(6_000_000, topHeightPx),
    )

    private fun video(
        bitrateBps: Int,
        heightPx: Int,
        codecs: String? = null,
        mimeType: String = MimeTypes.VIDEO_H264,
        colorTransfer: Int = Format.NO_VALUE,
    ): Format = Format.Builder()
        .setId("video-$bitrateBps")
        .setSampleMimeType(mimeType)
        .setCodecs(codecs)
        .setAverageBitrate(bitrateBps)
        .setPeakBitrate(bitrateBps)
        .setWidth(heightPx * 16 / 9)
        .setHeight(heightPx)
        .apply {
            if (colorTransfer != Format.NO_VALUE) {
                setColorInfo(ColorInfo.Builder().setColorTransfer(colorTransfer).build())
            }
        }
        .build()

    private fun estimate(meanBps: Long, spreadBps: Long?, conservativeBps: Long?, sampleCount: Int = 8) =
        ThroughputEstimate(meanBps = meanBps, spreadBps = spreadBps, conservativeBps = conservativeBps, sampleCount = sampleCount, newestSampleAgeMs = 0)

    private class StubMeter : BandwidthMeter {
        var estimateBps: Long = 0

        override fun getBitrateEstimate(): Long = estimateBps

        override fun getTransferListener(): TransferListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        }

        override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) = Unit

        override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit
    }

    private class StubSource : ThroughputSource {
        var estimate: ThroughputEstimate? = null

        override fun currentEstimate(): ThroughputEstimate? = estimate

        override fun addListener(listener: ThroughputSource.Listener) = Unit

        override fun removeListener(listener: ThroughputSource.Listener) = Unit
    }

    private companion object {
        val UNCAPPED = TransportCaps.UNCAPPED

        /** Between the on-demand profile's 10 s climb threshold and its 25 s descent threshold. */
        const val MOVABLE_BUFFER_US = 15_000_000L

        /** Above the descent threshold: a buffer on which Media3 alone would not come down. */
        const val DEEP_BUFFER_US = 60_000_000L

        /** A chunk's worth of clock between evaluations. */
        const val BETWEEN_EVALUATIONS_MS = 2_000L
    }
}
