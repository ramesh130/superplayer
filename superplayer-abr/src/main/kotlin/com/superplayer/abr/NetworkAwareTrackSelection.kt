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

import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.superplayer.abr.OracleBandwidthMeter.Companion.isStable
import com.superplayer.core.DeviceConstraints
import com.superplayer.core.ThroughputSource
import com.superplayer.core.TrackSelectionPolicy

/**
 * Media3's adaptive selection, choosing among what is *eligible*: under the ceiling the policy
 * currently emits, on the estimate the oracle's spread says can be trusted, and never a rung the
 * display cannot show or the decoder cannot decode. The third of Phase 3's components; the
 * Media3 half of [AdaptiveSelectionPolicy], as `AdaptiveLoadControl` is of the buffer policy.
 *
 * Everything that *chooses* is Media3's own — the estimate, the climb and descent thresholds
 * ([SelectionThresholds]), the queue discard — and this class overrides one hook,
 * [canSelectFormat], which Media3 consults for every rung on every evaluation, from the top of
 * the ladder down, returning the first rung the hook accepts. Three refusals, in order:
 *
 * 1. **The device.** A rung whose shorter edge is longer than the display's, whose HDR transfer
 *    the display does not list, or whose codec profile and level no declared decoder reaches, is
 *    refused whatever the network delivers. Read once from [DeviceConstraints] when the selection
 *    is built (ADR-0009 rule 2), and *unknown* refuses nothing — the KDoc there says why that
 *    direction is load-bearing. Refused through this hook and not through `isTrackExcluded`,
 *    deliberately: when every rung is refused Media3 falls back to the lowest one it evaluated,
 *    which is the bottom of the ladder, whereas a ladder whose every rung is *excluded* falls
 *    back to the top. The renderer's own capability check is the last line either way.
 * 2. **The policy's ceiling.** The [TrackSelectionPolicy] the [Factory] currently holds — the
 *    profile's cap, narrowed by the transport and by a post-rebuffer hold — is applied to a video
 *    rung's declared bitrate and height. It arrives through the `DecisionTarget` and is read on
 *    every evaluation, which is how a ceiling that fell below the playing rung becomes a `DOWN`
 *    switch at the next chunk and how a hold lapses on the trigger that lifted it, never on time
 *    (ADR-0009 rule 4).
 * 3. **The estimate, discounted by its spread.** Media3 offers each rung the meter's estimate
 *    times the profile's bandwidth fraction. When the oracle's spread says the mean cannot be
 *    trusted — the same line [OracleBandwidthMeter.isStable] draws and `AdaptiveBufferPolicy`'s
 *    branch 2 reads, so there is one threshold — the offer is scaled by the conservative
 *    percentile over the mean: a noisy link selects on what it delivers most of the time, a
 *    steady one on the mean. ADR-0009 rule 1 assigns the spread to this selector by name.
 *
 * The startup choice needs no code here: Media3's first evaluation reads the meter, which is the
 * per-transport memory's estimate or its cold default (#99), so a seeded transport starts above
 * the bottom rung and an unseeded one on the default the transport's own numbers argue for.
 *
 * `mtp` in CMCD is Media3's reading of this selection's latest estimate, unchanged.
 */
internal class NetworkAwareTrackSelection(
    group: TrackGroup,
    tracks: IntArray,
    type: Int,
    bandwidthMeter: BandwidthMeter,
    thresholds: SelectionThresholds,
    private val gate: Gate,
    clock: Clock,
) : AdaptiveTrackSelection(
    group,
    tracks,
    type,
    bandwidthMeter,
    thresholds.minDurationForQualityIncreaseMs.toLong(),
    thresholds.maxDurationForQualityDecreaseMs.toLong(),
    thresholds.minDurationToRetainAfterDiscardMs.toLong(),
    DEFAULT_MAX_WIDTH_TO_DISCARD,
    DEFAULT_MAX_HEIGHT_TO_DISCARD,
    thresholds.bandwidthFraction,
    thresholds.bufferedFractionToLiveEdgeForQualityIncrease,
    // No checkpoints: each adaptive selection sees the whole allocatable bandwidth. That is the
    // single-video-ladder case, which is every stream this library has met; an adaptive audio
    // ladder beside a video one would share nothing and is the limit this line names.
    emptyList(),
    clock,
) {

    /** Refusal 1, decided once per rung: the device does not change under a selection. */
    private val refusedByDevice: BooleanArray = BooleanArray(length) { gate.deviceRefuses(getFormat(it)) }

    override fun canSelectFormat(format: Format, trackBitrate: Int, effectiveBitrate: Long): Boolean {
        // Media3's constructor chooses a provisional index through this hook before this class's
        // fields exist; that index is superseded on the first `updateSelectedTrack`, so the
        // provisional answer is Media3's own.
        val gate: Gate? = this.gate
        val refused: BooleanArray? = this.refusedByDevice
        if (gate == null || refused == null) return trackBitrate <= effectiveBitrate

        if (refused[indexOf(format)]) return false
        if (MimeTypes.isVideo(format.sampleMimeType)) {
            val ceiling = gate.policy
            if (trackBitrate > ceiling.maxVideoBitrateBps) return false
            if (format.height != Format.NO_VALUE && format.height > ceiling.maxVideoHeightPx) return false
        }
        return trackBitrate <= gate.trusted(effectiveBitrate)
    }

    /**
     * What one factory's selections share: the device, read once; the ceiling, retargeted by the
     * `DecisionTarget`; and the source whose spread discounts the estimate.
     */
    internal class Gate(
        private val constraints: DeviceConstraints,
        private val source: ThroughputSource?,
        initial: TrackSelectionPolicy,
    ) {
        /** Written on the thread a decision arrives on, read on the loading thread: volatile, not locked. */
        @Volatile
        var policy: TrackSelectionPolicy = initial

        fun deviceRefuses(format: Format): Boolean =
            displayRefuses(format) || decoderRefuses(format)

        private fun displayRefuses(format: Format): Boolean {
            if (!MimeTypes.isVideo(format.sampleMimeType)) return false
            val shortEdge = constraints.displayShortEdgePx
            if (shortEdge != null && format.width != Format.NO_VALUE && format.height != Format.NO_VALUE) {
                if (minOf(format.width, format.height) > shortEdge) return true
            }
            val hdrTypes = constraints.displayHdrTypes
            val color = format.colorInfo
            if (hdrTypes != null && color != null && ColorInfo.isTransferHdr(color)) {
                if (hdrTypesFor(color.colorTransfer).none { it in hdrTypes }) return true
            }
            return false
        }

        /**
         * Which of the display's HDR types show a transfer function: PQ (ST 2084) is what HDR10,
         * HDR10+ and Dolby Vision carry; HLG is its own. A transfer nothing here names is one the
         * display is not asked about.
         *
         * ref: https://developer.android.com/reference/android/view/Display.HdrCapabilities
         */
        private fun hdrTypesFor(colorTransfer: Int): List<Int> = when (colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> listOf(
                Display.HdrCapabilities.HDR_TYPE_HDR10,
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
            )

            C.COLOR_TRANSFER_HLG -> listOf(Display.HdrCapabilities.HDR_TYPE_HLG)

            else -> emptyList()
        }

        /**
         * Media3's own parsing of the `codecs` string into a profile and level, against what the
         * device declared: a rung with no `codecs`, or a codec Media3 cannot parse, is asked
         * nothing, and a MIME type the device declared nothing about refuses nothing.
         */
        private fun decoderRefuses(format: Format): Boolean {
            val mimeType = format.sampleMimeType ?: return false
            if (!MimeTypes.isVideo(mimeType)) return false
            val profileLevel = MediaCodecUtil.getCodecProfileAndLevel(format) ?: return false
            return constraints.canDecode(mimeType, profileLevel.first, profileLevel.second) == false
        }

        /** Refusal 3: the offered bitrate, scaled down by `conservative / mean` on an unstable link. */
        fun trusted(effectiveBitrate: Long): Long {
            val estimate = source?.currentEstimate() ?: return effectiveBitrate
            if (estimate.sampleCount == 0 || estimate.isStable()) return effectiveBitrate
            val conservative = estimate.conservativeBps ?: return effectiveBitrate
            if (estimate.meanBps <= 0 || conservative >= estimate.meanBps) return effectiveBitrate
            return effectiveBitrate * conservative / estimate.meanBps
        }
    }

    /**
     * SuperPlayer's own `ExoTrackSelection.Factory`, because Media3 1.11's is final where it
     * builds the selection: a [NetworkAwareTrackSelection] for every adaptive definition, and
     * Media3's fixed selection for the rest, which is what Media3's factory does with its own.
     *
     * One [Gate] for every selection it builds, so a retargeted ceiling reaches the selection that
     * is playing and the one the next period will build alike.
     *
     * ref: `androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory.createTrackSelections`
     */
    internal class Factory(
        private val thresholds: SelectionThresholds,
        private val gate: Gate,
        private val clock: Clock = Clock.DEFAULT,
    ) : ExoTrackSelection.Factory {

        /** The ceiling from now on; the `DecisionTarget` calls this with each decision's selection half. */
        fun retarget(policy: TrackSelectionPolicy) {
            gate.policy = policy
        }

        override fun createTrackSelections(
            definitions: Array<ExoTrackSelection.Definition?>,
            bandwidthMeter: BandwidthMeter,
            mediaPeriodId: MediaSource.MediaPeriodId,
            timeline: Timeline,
        ): Array<ExoTrackSelection?> = Array(definitions.size) { index ->
            val definition = definitions[index] ?: return@Array null
            if (definition.tracks.size > 1) {
                NetworkAwareTrackSelection(definition.group, definition.tracks, definition.type, bandwidthMeter, thresholds, gate, clock)
            } else {
                FixedTrackSelection(definition.group, definition.tracks[0], definition.type)
            }
        }
    }
}
