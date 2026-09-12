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

package com.superplayer.testkit

import com.superplayer.core.BufferPolicy

/**
 * How a stock `ExoPlayer` built by [PlaybackHarness.buildStockPlayer] is configured — the two arms a
 * SuperPlayer profile is compared against in `PRD.md` §6.
 *
 * `PRD.md` §6 names three arms: (a) stock `ExoPlayer.Builder(context).build()` with defaults, (b)
 * stock plus naive tuning, and (c) a SuperPlayer profile. This enum is (a) and (b); (c) is
 * [PlaybackHarness.buildPlayer] with a `PlaybackProfile`. All three are built by the same harness,
 * over the same content, under the same shaped transport, which is what makes the comparison a
 * comparison rather than three unrelated measurements.
 *
 * **No Media3 type appears here, deliberately**, for the reason [TestContent] gives: a
 * `DefaultLoadControl` or a `LoadControl` in this module's public API would put Media3's
 * `@UnstableApi` opt-in marker on every caller, and `verifyNoUnstableMedia3InPublicApi` would fail
 * the build (ADR-0001 rule 2). What an arm *is* therefore travels as [bufferPolicy], `superplayer-core`'s
 * own engine-agnostic value type, and each place that needs a real `LoadControl` translates it.
 *
 * That indirection is load-bearing rather than tidy. The same two arms are run twice — under
 * Robolectric by [PlaybackHarness.buildStockPlayer], and on a device by `benchmark/`'s app against
 * public streams — and if the numbers were written out in both, a benchmark could report a
 * device result for an arm that no longer matched the one it reports a Robolectric result for.
 * One value, two translations, is the version of that which cannot drift.
 */
public enum class StockTuning(

    /**
     * What this arm configures, or null for the arm that configures nothing.
     *
     * A [BufferPolicy] because it is the shape the numbers have and it names no Media3 type. It is
     * *not* a claim that this arm goes through `PlaybackPolicy` — a stock player has no SuperPlayer
     * in it and reaches no policy of any kind. Borrowing core's value type here says only that the
     * two arms are described in the same units as the profile they are compared against, which is
     * what makes the three columns of a report readable side by side.
     */
    public val bufferPolicy: BufferPolicy?,
) {

    /**
     * `ExoPlayer.Builder(context).build()` and nothing else: Media3's own defaults, untouched.
     *
     * The control arm. Whatever SuperPlayer's profiles are worth has to be worth it against *this*,
     * because this is what an app that wrote no configuration gets — and `PRD.md` F2's premise is
     * that a great many apps ship exactly this.
     */
    MEDIA3_DEFAULTS(bufferPolicy = null),

    /**
     * Stock, plus the buffer configuration an app writes when it has decided its player stalls too
     * much and has not measured why.
     *
     * The second arm exists because [MEDIA3_DEFAULTS] alone would make SuperPlayer's profiles look
     * good against a straw man. The interesting question is not whether configuring beats not
     * configuring — it is whether configuring *by use case, with the reasoning written down* beats
     * configuring *by intuition*, which is the comparison an adopter is actually choosing between.
     * A cell where this arm wins is a cell where SuperPlayer's profile has nothing to offer, and
     * `PRD.md` §6's honesty rule says it is reported like any other.
     *
     * ref: derivation (CONTRIBUTING.md rule 4), stated rather than attributed. There is no one
     * canonical forum post to cite, and holding up a particular person's answer as the naive arm
     * would be both unfair and unverifiable. What *is* citable is the baseline these numbers depart
     * from — `DefaultLoadControl`'s documented defaults of 50 s of buffer in both directions, 2.5 s
     * before playback starts, 5 s before it resumes after a rebuffer, and no back buffer:
     * https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl
     * `superplayer-core`'s `StaticProfilePolicy` departs from that same baseline and cites the same
     * page, so arm (b) and arm (c) are described against one reference rather than two.
     *
     * The departures are the two changes every such recipe makes, and the point of the arm is that
     * they pull against each other:
     *
     * - **Buffer much more** — 60 s/120 s. The change made in response to "it keeps stalling", and
     *   not obviously wrong: a deeper buffer really does ride out a longer dropout. What it costs is
     *   memory, and on a constrained link it costs startup too, because the player keeps loading.
     * - **Start much sooner** — 1 s/2 s. The change made in response to "it takes too long to
     *   start", usually by someone else, on a different day. Starting on one second of buffer at
     *   1 Mbit/s is starting into a stall, and whether that is true is exactly the kind of thing
     *   this benchmark exists to answer rather than assert.
     *
     * No back buffer, which is the default: the recipe is about stalls and startup, and a recipe
     * that had thought about seeking would not be the arm this is.
     */
    NAIVE_FORUM_CONFIG(
        bufferPolicy = BufferPolicy(
            minBufferMs = 60_000,
            maxBufferMs = 120_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 2_000,
            backBufferMs = 0,
            retainBackBufferFromKeyframe = false,
        ),
    ),
}
