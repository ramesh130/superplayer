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

/**
 * The [PlaybackPolicy] that ships today: a static per-profile lookup.
 *
 * It ignores the conditions it is given and returns [profile]'s documented configuration every
 * time. That is deliberate rather than unfinished — [PlaybackPolicy] argues the case — and it is
 * why this class is internal: what a consumer names is the profile, and the table below is free to
 * be retuned without their code meaning something different.
 *
 * ## About the numbers
 *
 * Each profile's block says what the profile is protecting against, and each number that differs
 * from Media3's own default says why it differs. That is the standard the table is held to: a
 * constant nobody can explain is a constant nobody can safely change, which is exactly the state
 * these profiles exist to get consumers out of.
 *
 * The defaults being departed from are `DefaultLoadControl`'s: 50s of buffer in both directions,
 * 2.5s before playback starts, 5s before it resumes after a rebuffer, and no back buffer.
 *
 * ref: https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl
 */
internal class StaticProfilePolicy(private val profile: PlaybackProfile) : PlaybackPolicy {

    override fun decide(conditions: PlaybackConditions): PlaybackDecision = when (profile) {
        // Long-form on-demand. The viewer is committed, so the thing to protect is the *middle* of
        // playback: a stall thirty minutes in costs far more than a slower start.
        //
        // 30s/60s rather than Media3's 50s/50s. A range with room in it is what lets the player
        // ride out a dropout without re-requesting, and the 50s floor is more than an on-demand
        // stream needs to hold ahead at all times — it is memory spent on a margin that is rarely
        // reached down to. The 60s ceiling is where a stream keeps buffering into an unbounded
        // amount of RAM otherwise.
        //
        // Start floors are Media3's own: 2.5s is enough content to survive a first-segment hiccup
        // without making the start noticeably slower, and doubling it after a rebuffer is the
        // standard defence against a stall that immediately repeats.
        //
        // A 30s back buffer is the one addition. Long-form is where a viewer scrubs back to catch a
        // line of dialogue, and re-downloading media the player just played is both a stall the
        // viewer did not need and bytes they already paid for. Retained from the keyframe, so the
        // seek lands in the buffer rather than just before it.
        PlaybackProfile.VIDEO_ON_DEMAND -> PlaybackDecision(
            buffer = BufferPolicy(
                minBufferMs = 30_000,
                maxBufferMs = 60_000,
                bufferForPlaybackMs = 2_500,
                bufferForPlaybackAfterRebufferMs = 5_000,
                backBufferMs = 30_000,
                retainBackBufferFromKeyframe = true,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
            ),
        )

        // Live linear. Buffer depth is latency here: seconds held ahead of the playhead are seconds
        // behind the live edge, and playback does not give them back on its own.
        //
        // 10s/30s. The floor is the smallest margin that still survives an ordinary segment-fetch
        // hiccup at typical live segment durations; the ceiling exists because a live player that
        // buffered a minute ahead would be a minute behind, which is a different product.
        //
        // Start floors are cut to 1.5s/3s for the same reason: a live stream that waits for 2.5s of
        // media before showing a frame has *joined* 2.5s late, and stays there.
        //
        // No back buffer. Scrubbing back through a live stream is a DVR feature served by the
        // manifest's own window, not by media the player happens to still hold, so a back buffer
        // here is memory spent on a seek that will not be served from it anyway.
        PlaybackProfile.LIVE_LINEAR -> PlaybackDecision(
            buffer = BufferPolicy(
                minBufferMs = 10_000,
                maxBufferMs = 30_000,
                bufferForPlaybackMs = 1_500,
                bufferForPlaybackAfterRebufferMs = 3_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
            ),
        )

        // Short-form feed content. Two costs dominate, and both are paid before the viewer has
        // decided anything: how long the first frame takes, and how much data was spent on a clip
        // that was swiped past.
        //
        // 2.5s/15s. The floor is as low as it can be while still holding a full clip's worth of
        // margin at feed encoding rates, and 15s is more than most of this content *is* — buffering
        // further ahead than the clip is long is data spent on media that will never be shown.
        //
        // 1s before playback starts, which is the aggressive end of this trade and the one the
        // format is judged on. After a rebuffer 2s, still low: in a feed, a clip that stalls has
        // usually already lost the viewer, and the recovery worth optimising for is a fast one.
        //
        // No back buffer: a feed moves forward, and the memory matters more here than anywhere else
        // because several players exist at once.
        PlaybackProfile.SHORT_FORM -> PlaybackDecision(
            buffer = BufferPolicy(
                minBufferMs = 2_500,
                maxBufferMs = 15_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            // Capped at 1080p. Not a data measure — a screen one: feed content is watched on a
            // phone, in a portrait viewport, where a 4K rendition is decoded, downscaled and thrown
            // away. The bytes and the decoder time are real; the extra pixels are not visible.
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = 1_080,
            ),
        )

        // Data saver. The viewer has asked to spend fewer bytes, so every number here is chosen
        // against that budget rather than against picture quality.
        //
        // 10s/20s. Deliberately shallower than video-on-demand, because buffered media that is
        // never watched — the viewer stops, or switches away — is data spent for nothing, and this
        // is the one profile where that waste is the thing being minimised. Shallower buffers mean
        // a slightly higher rebuffer risk, which is the trade the setting asks for.
        //
        // Start floors stay at Media3's 2.5s/5s: cutting them would trade data-saving for a faster
        // start, which is not what this profile is for.
        //
        // 480p and 800 kbps are the ceilings. 480p is the highest rung that is still unambiguously
        // a data-saving choice on a phone-sized screen, and 800 kbps is a typical encode of it —
        // both caps are applied because a ladder may offer a high-bitrate encode of a low
        // resolution, and either one alone would let that rung through.
        PlaybackProfile.DATA_SAVER -> PlaybackDecision(
            buffer = BufferPolicy(
                minBufferMs = 10_000,
                maxBufferMs = 20_000,
                bufferForPlaybackMs = 2_500,
                bufferForPlaybackAfterRebufferMs = 5_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = 800_000,
                maxVideoHeightPx = 480,
            ),
        )
    }
}
