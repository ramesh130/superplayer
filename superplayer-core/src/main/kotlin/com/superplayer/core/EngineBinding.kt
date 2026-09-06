package com.superplayer.core

import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl

/**
 * The one place a [PlaybackDecision] becomes Media3 configuration.
 *
 * [PlaybackPolicy] and everything it names is engine-agnostic on purpose, which is only true for as
 * long as the translation into Media3's vocabulary happens here rather than being spread through
 * whatever code happens to be building an engine. This file is deliberately thin and deliberately
 * the only importer of Media3 types on the policy path.
 */

/**
 * The decision's buffering half, as the `LoadControl` the engine is built with.
 *
 * Applied at construction and fixed thereafter — Media3's [DefaultLoadControl] takes its durations
 * when it is built and does not accept new ones — which is the constraint that makes today's policy
 * a construction-time consultation. See [PlaybackPolicy].
 */
internal fun BufferPolicy.toLoadControl(): LoadControl =
    DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs,
        )
        .setBackBuffer(backBufferMs, retainBackBufferFromKeyframe)
        .build()

/**
 * The decision's selection half, as Media3's own [TrackSelectionParameters], applied on top of
 * whatever the engine already has.
 *
 * Built upon rather than replaced: the engine's defaults carry the device's own constraints —
 * viewport size, preferred audio language from the system — and a policy that expresses a ceiling
 * has no business discarding those.
 */
internal fun TrackSelectionPolicy.applyTo(
    parameters: TrackSelectionParameters,
): TrackSelectionParameters =
    parameters.buildUpon()
        .setMaxVideoBitrate(maxVideoBitrateBps)
        // Media3 takes the video ceiling as a width-and-height pair; the policy expresses one in
        // height alone. Translating it means leaving the other axis unset rather than inventing a
        // width the policy did not decide — no ceiling is decided here, which is ADR-0005 rule 2.
        .setMaxVideoSize(TrackSelectionPolicy.UNLIMITED, maxVideoHeightPx)
        .build()
