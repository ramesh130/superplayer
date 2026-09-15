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

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * The one place a [PlaybackDecision] becomes Media3 configuration.
 *
 * [PlaybackPolicy] and everything it names is engine-agnostic on purpose, which is only true for as
 * long as the translation into Media3's vocabulary happens here rather than being spread through
 * whatever code happens to be building an engine. This file is deliberately thin and deliberately
 * the only importer of Media3 types on the policy path.
 */

/**
 * The decision's buffering half, as the `LoadControl` the engine is built with when nothing
 * retargetable was installed in its place.
 *
 * Applied at construction and fixed thereafter — Media3's [DefaultLoadControl] takes its durations
 * when it is built and does not accept new ones — which is the constraint that makes a policy on
 * such a player a construction-time consultation (ADR-0009 rule 5). See [PlaybackPolicy].
 *
 * [allocator] is for the one caller that builds these more than once for one player: a
 * retargetable load control that honours a changed decision by building a fresh
 * [DefaultLoadControl] around the durations. The sample queues hold on to the allocator they were
 * given at prepare time, so every load control built for one player has to account against the
 * same one, or the byte-target back-pressure would be measured against an allocator nothing
 * allocates from. Null builds Media3's own.
 */
internal fun BufferPolicy.toLoadControl(allocator: DefaultAllocator? = null): LoadControl =
    DefaultLoadControl.Builder()
        .apply { allocator?.let { setAllocator(it) } }
        .setBufferDurationsMs(
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs,
        )
        .setBackBuffer(backBufferMs, retainBackBufferFromKeyframe)
        .build()

/**
 * The decision's live-latency half, laid into the [MediaItem] the engine plays, which is where
 * Media3 takes a speed range from.
 *
 * Not from the `LivePlaybackSpeedControl` the engine is built with, although that is where the
 * range is *used*: the control's own range is only a fallback, and Media3's HLS and DASH sources
 * pin the range to exactly 1× for any stream whose manifest declares no low-latency hints unless the
 * item itself says otherwise — so a range set on the control alone is silently never used on an
 * ordinary live stream. The item's live configuration is the one route that always takes, and it
 * overrides both the control's fallback and the manifest's own hints, which is what a policy that
 * has decided a range wants.
 *
 * A null half leaves the item exactly as it was, whatever it declared: "nothing decided" is not
 * "decided 1×". So does an item that already declares a range of its own — a consumer who set one
 * on their `MediaItem` has made the same decision one level up, and the promise ADR-0009 rule 5
 * makes for a consumer's `TrackSelectionParameters` holds here too: nothing of theirs is rewritten
 * by a trigger. An item core built from a `MediaRequest` declares none, and takes the policy's.
 * Applied when a request is adopted, and again to the playing item when a re-consulted decision
 * changes the half — an engine can replace a playing item in place when only its live
 * configuration differs, which is what makes this half re-targetable on every player without a
 * component of its own (ADR-0009 rule 5, addendum).
 *
 * ref: https://developer.android.com/media/media3/exoplayer/live-streaming — the media item's live
 * configuration as the app's way of setting the range.
 */
internal fun MediaItem.withLiveLatency(policy: LiveLatencyPolicy?): MediaItem {
    if (policy == null) return this
    val declaresItsOwn = liveConfiguration.minPlaybackSpeed != C.RATE_UNSET ||
        liveConfiguration.maxPlaybackSpeed != C.RATE_UNSET
    if (declaresItsOwn) return this
    val configuration = liveConfiguration.buildUpon()
        .setMinPlaybackSpeed(policy.minPlaybackSpeed)
        .setMaxPlaybackSpeed(policy.maxPlaybackSpeed)
        .build()
    if (configuration == liveConfiguration) return this
    return buildUpon().setLiveConfiguration(configuration).build()
}

/**
 * The decision's selection half, as Media3's own [TrackSelectionParameters], applied on top of
 * whatever the engine already has.
 *
 * Built upon rather than replaced: the engine's defaults carry the device's own constraints —
 * viewport size, preferred audio language from the system — and a policy that expresses a ceiling
 * has no business discarding those.
 *
 * Laid in once, at construction, and only on a player with no [DecisionTarget]: where one exists the
 * ceiling belongs to the target's own selection from construction onward, because a ceiling in the
 * parameters would clamp every later decision that raised it — the half-in-force contract ADR-0009
 * rule 5 refuses. A re-application never rewrites these, so a consumer's own parameters are never
 * overwritten by a trigger.
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

/**
 * The decision's selection pace, as the factory Media3's own `DefaultTrackSelector` builds its
 * adaptive selections with, on a player with no retargetable selection factory of its own.
 *
 * Fixed at construction, as the `DefaultLoadControl` above is and for the same reason (ADR-0009
 * rule 5): Media3's adaptive selection takes its thresholds when it is built. Where a factory was
 * installed, the pace arrives through the [DecisionTarget] with the rest of the decision and this
 * is not called. A null pace installs nothing, so a policy that decides none builds exactly the
 * engine it built before. The live-edge fraction and the discard dimensions stay Media3's own:
 * they are not policy, and the pace does not carry them.
 *
 * ref: https://developer.android.com/reference/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.Factory
 */
internal fun SelectionPace.toTrackSelectionFactory(): ExoTrackSelection.Factory =
    AdaptiveTrackSelection.Factory(
        climbAfterBufferedMs,
        descendBelowBufferedMs,
        retainAfterDiscardMs,
        bandwidthFraction,
    )

/**
 * The decision's prefetch depth, as the status Media3's preload manager holds an item at — read by a
 * `PreloadCoordinator` for each item in its window (ADR-0010 rule 10).
 *
 * ref: https://developer.android.com/reference/androidx/media3/exoplayer/source/preload/DefaultPreloadManager.PreloadStatus
 */
internal fun PreloadDepth.toPreloadStatus(): DefaultPreloadManager.PreloadStatus = when (this) {
    PreloadDepth.SourcePrepared -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_SOURCE_PREPARED

    PreloadDepth.TracksSelected -> DefaultPreloadManager.PreloadStatus.PRELOAD_STATUS_TRACKS_SELECTED

    is PreloadDepth.Loaded -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(durationMs.toLong())

    // The manager's half of it: Media3's preload manager has no decoder stage, so the decoder is the
    // coordinator's, held on an idle pooled player it prepares on the loaded source.
    is PreloadDepth.DecoderWarmed -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(durationMs.toLong())
}

/**
 * The third target a decision can reach, beside the two above: the engine's own retargetable
 * components, through the hook an extension installed (ADR-0009 rule 5).
 *
 * Handed the decision whole — both halves, every time, the first time at construction included —
 * because a target that was given one half would be the half-in-force contract by another route.
 * Trivial by design: the translation into whatever the components take is theirs, and this file's
 * only job is to be the one place a decision leaves the policy's vocabulary.
 */
internal fun PlaybackDecision.applyTo(target: DecisionTarget) {
    target.apply(this)
}
