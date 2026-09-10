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

/**
 * What a [PlaybackHarness] should play: how long, how many renditions, and whether it is live.
 *
 * Described rather than authored. A test that needs an ABR upshift wants *two bitrates that can be
 * switched between*, not a hand-written manifest, and a test that needs a live window wants the
 * window rather than a segment template — so this names the property under test and the harness
 * synthesizes something with it.
 *
 * **No Media3 type appears here, deliberately.** ADR-0001 rule 2 keeps `@UnstableApi` types out of
 * SuperPlayer's public API, and a test-support module is not exempt: a `Format` or a `Timeline` in
 * one of these signatures would put Media3's opt-in marker on every test that named it, which is
 * exactly the burden the rule exists to keep off consumers. The Media3 vocabulary is entirely
 * inside the harness.
 */
public class TestContent private constructor(

    /**
     * The declared peak bitrates of the video renditions, ascending, one entry per rendition.
     *
     * More than one is what makes an ABR switch possible at all; a single entry is content the
     * player has no choice about, which is what most tests want.
     */
    internal val videoBitratesBps: List<Int>,

    /** How long the content claims to be, in media milliseconds. */
    internal val durationMs: Long,

    /** Whether the window is a live one — what makes live-edge latency measurable. */
    internal val live: Boolean,
) {

    public companion object {

        /** A rendition bitrate that reads like real 720p, so a test's numbers look like a stream's. */
        public const val DEFAULT_BITRATE_BPS: Int = 800_000

        /** Long enough to seek inside without falling off the end of the window. */
        public const val DEFAULT_DURATION_MS: Long = 60_000

        /** On-demand video with one rendition: nothing to switch to, everything else measurable. */
        @JvmStatic
        public fun video(
            bitrateBps: Int = DEFAULT_BITRATE_BPS,
            durationMs: Long = DEFAULT_DURATION_MS,
        ): TestContent = TestContent(listOf(bitrateBps), durationMs, live = false)

        /**
         * On-demand video with a rendition ladder, which is what an ABR switch needs.
         *
         * [bitratesBps] is taken in the order given and is expected to ascend, because that is what a
         * manifest declares and what makes `UP` and `DOWN` mean what they say.
         */
        @JvmStatic
        public fun videoLadder(
            bitratesBps: List<Int> = listOf(300_000, DEFAULT_BITRATE_BPS, 2_400_000),
            durationMs: Long = DEFAULT_DURATION_MS,
        ): TestContent {
            require(bitratesBps.isNotEmpty()) { "A ladder needs at least one rendition" }
            return TestContent(bitratesBps, durationMs, live = false)
        }

        /**
         * A live window, which is the only kind of content live-edge latency is defined for.
         *
         * The schema emits `LiveLatencySampled` for live content only, precisely so that a sample of
         * zero from on-demand content cannot be averaged into a live dashboard — so a test of that
         * rule needs both this and [video] to be distinguishable by the player.
         */
        @JvmStatic
        public fun liveVideo(
            bitrateBps: Int = DEFAULT_BITRATE_BPS,
            windowDurationMs: Long = DEFAULT_DURATION_MS,
        ): TestContent = TestContent(listOf(bitrateBps), windowDurationMs, live = true)
    }
}
