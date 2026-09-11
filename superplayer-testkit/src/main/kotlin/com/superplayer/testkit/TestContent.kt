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

import com.superplayer.testmedia.SyntheticDashStream
import com.superplayer.testmedia.SyntheticHlsStream

/**
 * What a [PlaybackHarness] should play: how long, how many renditions, and whether it is live — or,
 * for [hls] and [dash], a real stream of that protocol.
 *
 * Described rather than authored. A test that needs an ABR upshift wants *two bitrates that can be
 * switched between*, not a hand-written manifest, and a test that needs a live window wants the
 * window rather than a segment template — so this names the property under test and the harness
 * synthesizes something with it.
 *
 * [hls] and [dash] are the exception, and they are a different kind of content rather than another
 * property: they name `superplayer-testmedia`'s synthetic streams, which are parsed by Media3's own
 * HLS and DASH parsers, demuxed by its own extractors, and fetched one segment at a time through a
 * `DataSource` chain. Everything else here is loaded by Media3's fakes, which is faster and enough
 * for a test about a *measurement*; a test about what the protocols actually fetch — a fault
 * addressed at a segment, a manifest that arrives late — needs the real thing.
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

    /** Which protocol's real stream this is, or [Protocol.DESCRIBED] for content Media3 fakes. */
    internal val protocol: Protocol = Protocol.DESCRIBED,

    /** How many media segments the synthetic stream carries. Meaningless when [protocol] is not. */
    internal val segmentCount: Int = 1,
) {

    /**
     * What a test hands to `setMediaRequest`: the multivariant playlist, the MPD, or — for described
     * content, which Media3's fakes synthesize without fetching anything — a URI nothing resolves.
     *
     * A `String`, because it is the only part of a synthetic stream a test needs to name and a
     * `Uri` would drag Android's own type through this module's API for no gain.
     */
    public val sourceUri: String = protocol.sourceUri

    /**
     * How the harness loads this content. Internal: a test says [hls] or [dash] and means it.
     *
     * Each constant carries what the harness needs to serve it — where the stream starts, and what
     * bytes it is — rather than leaving the harness to switch on the constant twice.
     */
    internal enum class Protocol(
        /** The URI a session starts from: a multivariant playlist, an MPD, or nothing fetchable. */
        val sourceUri: String,

        /** Everything a session of [segmentCount] segments will fetch, keyed by URI. */
        val resources: (segmentCount: Int) -> Map<String, ByteArray>,
    ) {

        /** Content Media3's fakes synthesize: nothing is fetched, so there is nothing to serve. */
        DESCRIBED(DESCRIBED_SOURCE_URI, { emptyMap() }),

        HLS(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI, { SyntheticHlsStream.resources(it) }),

        DASH(SyntheticDashStream.MANIFEST_URI, { SyntheticDashStream.resources(it) }),
    }

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

        /**
         * A real HLS stream: a multivariant playlist, a media playlist, and [segmentCount] AAC
         * segments in ADTS framing, played through Media3's own HLS parser and extractor.
         *
         * Audio-only, because that is the smallest thing an `HlsMediaSource` will parse, demux and
         * expose as a track — and what the protocol half of a test is about is the *fetching*, not
         * the pixels. Its DASH counterpart [dash] describes deliberately equivalent media, so a test
         * run against both is comparing the protocols rather than two unrelated streams.
         *
         * [segmentCount] is what decides which segment indices a [FaultScript] can address: a fault
         * at media segment 2 needs a stream with a segment 2.
         */
        @JvmStatic
        public fun hls(segmentCount: Int = DEFAULT_SEGMENT_COUNT): TestContent = TestContent(
            videoBitratesBps = emptyList(),
            durationMs = SyntheticHlsStream.durationMs(segmentCount),
            live = false,
            protocol = Protocol.HLS,
            segmentCount = segmentCount,
        )

        /**
         * A real DASH stream: an MPD, a fragmented-MP4 initialization segment and [segmentCount]
         * media segments, played through Media3's own MPD parser and fragmented-MP4 extractor.
         *
         * The mirror image of [hls] — same codec, same sample rate, same declared bitrate — for the
         * reason `docs/testing.md` gives: it is what lets one [FaultScript] be run against both and
         * be a comparison of the protocols.
         */
        @JvmStatic
        public fun dash(segmentCount: Int = DEFAULT_SEGMENT_COUNT): TestContent = TestContent(
            videoBitratesBps = emptyList(),
            durationMs = SyntheticDashStream.durationMs(segmentCount),
            live = false,
            protocol = Protocol.DASH,
            segmentCount = segmentCount,
        )

        /** Enough segments that a fault can be addressed past the first one and still play first. */
        public const val DEFAULT_SEGMENT_COUNT: Int = 4

        /**
         * The source of content Media3's fakes synthesize: nothing fetches it, and a request for it
         * would be a test failure rather than a 404.
         */
        private const val DESCRIBED_SOURCE_URI = "fake://superplayer.test/described"
    }
}
