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
 * The decision's buffering half, as the `LoadControl` the engine is built with when nothing
 * retargetable was installed in its place.
 *
 * Applied at construction and fixed thereafter — Media3's [DefaultLoadControl] takes its durations
 * when it is built and does not accept new ones — which is the constraint that makes a policy on
 * such a player a construction-time consultation (ADR-0009 rule 5). See [PlaybackPolicy].
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
