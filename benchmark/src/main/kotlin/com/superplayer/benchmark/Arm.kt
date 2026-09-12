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

package com.superplayer.benchmark

import com.superplayer.core.BufferPolicy
import com.superplayer.core.TrackSelectionPolicy

/**
 * The three players `PRD.md` §6 compares, and the only place this project says what each one is.
 *
 * ```text
 * (a) STOCK_DEFAULTS       ExoPlayer.Builder(context).build(), and nothing else
 * (b) STOCK_NAIVE_TUNING   the same, plus the buffer configuration an app writes from intuition
 * (c) SUPERPLAYER          SuperPlayer.Builder(context).setProfile(…).build()
 * ```
 *
 * All three, or the report has no claim in it — which is `PRD.md` §6's wording and is a statement
 * about what a two-arm comparison would be worth rather than about completeness. Against [STOCK_DEFAULTS]
 * alone, any configuration at all looks like an improvement; [STOCK_NAIVE_TUNING] is what makes the
 * question the one an adopter is actually choosing between, which is whether configuring *by use
 * case with the reasoning written down* beats configuring *by intuition*.
 *
 * ## Where these definitions are used
 *
 * Twice, in two builds that share nothing but this file. `BenchmarkMatrixTest` runs all three under
 * Robolectric through `superplayer-testkit`'s `PlaybackHarness`; [BenchmarkActivity] runs all three
 * on a device against public streams, where peak RSS and battery are measurable and Robolectric is
 * nowhere. A benchmark that described arm (b) differently in those two places would publish a device
 * number and a Robolectric number for players that were not the same player, so the numbers live
 * here and each caller translates them.
 */
internal enum class Arm(

    /** How the arm appears in a report's column heading and in a raw trace's `arm` field. */
    val label: String,

    /**
     * What this arm configures the engine's buffering with, or null for the arm that configures
     * nothing.
     *
     * A [BufferPolicy] rather than a Media3 `DefaultLoadControl` because two different places have
     * to build one — `PlaybackHarness.buildStockPlayer` under Robolectric and [StockPlayers] on a
     * device — and because it is the same type arm (c)'s own configuration is reported in, so the
     * three columns of a report are in one set of units.
     *
     * Null for [SUPERPLAYER] too, and for a different reason: arm (c)'s buffering is
     * `PlaybackPolicy`'s answer for its profile, chosen inside the library, and stating it here as
     * well would be a second copy of `StaticProfilePolicy`'s table that could disagree with it. What
     * arm (c) actually ran with is read back off the player, as `SuperPlayer.playbackDecision`, and
     * reported from there.
     */
    val stockBufferPolicy: BufferPolicy?,
) {

    /**
     * (a) The control arm: `ExoPlayer.Builder(context).build()` with Media3's own defaults.
     *
     * What an app that wrote no configuration gets, which `PRD.md` F2 says a great many apps ship.
     * Whatever SuperPlayer's profiles are worth has to be worth it against this.
     */
    STOCK_DEFAULTS(label = "stock (defaults)", stockBufferPolicy = null),

    /**
     * (b) Stock, plus the buffer configuration an app writes when it has decided its player stalls
     * too much and has not measured why.
     *
     * ref: derivation (CONTRIBUTING.md rule 4), stated rather than attributed. There is no one
     * canonical forum post to cite, and holding up a particular person's answer as the naive arm
     * would be unfair and unverifiable both. What *is* citable is the baseline these numbers depart
     * from — `DefaultLoadControl`'s documented defaults, [MEDIA3_DEFAULT_BUFFER_POLICY] below:
     * https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl
     * `superplayer-core`'s `StaticProfilePolicy` departs from that same page, so arms (b) and (c)
     * are described against one reference rather than two.
     *
     * The departures are the two changes every such recipe makes, and the point of the arm is that
     * they pull against each other:
     *
     * - **Buffer much more** — 60 s/120 s against 50 s/50 s. The change made in response to "it keeps
     *   stalling", and not obviously wrong: a deeper buffer really does ride out a longer dropout.
     *   What it costs is memory, and on a constrained link it costs startup, because the player is
     *   still loading.
     * - **Start much sooner** — 1 s/2 s against 2.5 s/5 s. The change made in response to "it takes
     *   too long to start", usually by somebody else, on a different day. Starting on one second of
     *   buffer at 1 Mbit/s is starting into a stall — and whether that is *true* is the kind of thing
     *   this harness exists to answer rather than to assert.
     *
     * No back buffer, which is the default: a recipe that had thought about seeking would not be
     * the arm this is.
     */
    STOCK_NAIVE_TUNING(
        label = "stock (naive tuning)",
        stockBufferPolicy = BufferPolicy(
            minBufferMs = 60_000,
            maxBufferMs = 120_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 2_000,
            backBufferMs = 0,
            retainBackBufferFromKeyframe = false,
        ),
    ),

    /**
     * (c) A SuperPlayer built with the profile the content calls for.
     *
     * Which profile is [BenchmarkContent]'s to say, not this enum's: a live channel is measured
     * under `LIVE_LINEAR` and a feed clip under `SHORT_FORM`, because comparing a profile against
     * stock on content the profile was not written for measures nothing anybody would ship.
     */
    SUPERPLAYER(label = "SuperPlayer", stockBufferPolicy = null),
    ;

    /** Whether this arm is one of the two stock ones, which is what decides how a player is built. */
    val isStock: Boolean get() = this != SUPERPLAYER

    companion object {

        /**
         * Media3's own buffering, as a value, so a report can state what arm (a) ran with.
         *
         * ref: `DefaultLoadControl`'s documented defaults — 50 s of buffer in both directions, 2.5 s
         * before playback starts, 5 s before it resumes after a rebuffer, and no back buffer:
         * https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl
         *
         * **Never used to build a player.** Arm (a) is `ExoPlayer.Builder(context).build()` and
         * nothing else, and passing this to it instead would quietly make the control arm a
         * configured player that happens to agree with the defaults today — so the day Media3
         * changed one of these numbers, the control arm would keep measuring the old one and nothing
         * would say so. This is for the report to print, and the null in [stockBufferPolicy] is what
         * the player is built from.
         */
        val MEDIA3_DEFAULT_BUFFER_POLICY: BufferPolicy = BufferPolicy(
            minBufferMs = 50_000,
            maxBufferMs = 50_000,
            bufferForPlaybackMs = 2_500,
            bufferForPlaybackAfterRebufferMs = 5_000,
            backBufferMs = 0,
            retainBackBufferFromKeyframe = false,
        )

        /**
         * What a stock player caps track selection at, which is nothing.
         *
         * Stated so that a stock arm's `SessionStarted` can carry a truthful `PlaybackDecision`
         * rather than an invented one: an unconfigured `DefaultTrackSelector` applies no bitrate and
         * no resolution ceiling of its own, which in `superplayer-core`'s vocabulary is exactly
         * [TrackSelectionPolicy.UNLIMITED] in both.
         */
        val STOCK_TRACK_SELECTION: TrackSelectionPolicy = TrackSelectionPolicy(
            maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
            maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
        )
    }
}
