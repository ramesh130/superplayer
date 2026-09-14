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

import com.superplayer.testmedia.HostileManifests
import com.superplayer.testmedia.HostileStream
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
     * The video renditions, ascending by bitrate, one entry per rendition.
     *
     * More than one is what makes an ABR switch possible at all; a single entry is content the
     * player has no choice about, which is what most tests want.
     */
    internal val rungs: List<Rung>,

    /** How long the content claims to be, in media milliseconds. */
    internal val durationMs: Long,

    /** Whether the window is a live one — what makes live-edge latency measurable. */
    internal val live: Boolean,

    /** Which protocol's real stream this is, or [Protocol.DESCRIBED] for content Media3 fakes. */
    internal val protocol: Protocol = Protocol.DESCRIBED,

    /**
     * What a test hands to `setMediaRequest`: the multivariant playlist, the MPD, or — for described
     * content, which Media3's fakes synthesize without fetching anything — a URI nothing resolves.
     *
     * A `String`, because it is the only part of a synthetic stream a test needs to name and a
     * `Uri` would drag Android's own type through this module's API for no gain.
     */
    public val sourceUri: String = DESCRIBED_SOURCE_URI,

    /**
     * Everything a session of this content will fetch, keyed by URI, and nothing else.
     *
     * Held per instance rather than derived from [protocol], because a hostile stream is a stream of
     * its protocol with different bytes and a different URI: [HostileManifests] hands one over
     * exactly as [SyntheticHlsStream] does, and the harness serves both the same way.
     */
    internal val resources: Map<String, ByteArray> = emptyMap(),

    /**
     * The response headers each resource is served with, keyed by URI — empty for everything but a
     * stream whose defect is in its headers, which is [HostileStream.declaredResponseHeaders].
     */
    internal val responseHeaders: Map<String, Map<String, String>> = emptyMap(),

    /**
     * For content an origin keeps publishing, what exists after a given number of milliseconds of
     * the harness's clock; null for content fixed when the test began, which is served from
     * [resources]. See `LiveOriginDataSource` for why a live HLS stream cannot be the other kind.
     */
    internal val publication: ((elapsedMs: Long) -> Map<String, ByteArray>)? = null,
) {

    /**
     * How the harness loads this content. Internal: a test says [hls] or [dash] and means it.
     *
     * Only the distinction from [DESCRIBED] is load-bearing — real protocol streams go through
     * Media3's own parsers and a `DataSource` chain, described ones through its fakes — but naming
     * the protocol keeps a test's failure message saying which one it was.
     */
    internal enum class Protocol { DESCRIBED, HLS, DASH }

    /**
     * One rendition of a ladder: a bitrate, **the resolution a stream would encode it at**, and
     * optionally the codec profile it declares, as an RFC 6381 `codecs` string.
     *
     * The resolution is part of the rung rather than decorative. A ladder whose every rung is 720p
     * is not a ladder a resolution cap can choose within: a policy capping height at 480p excludes
     * *all* of it, and Media3's selector then falls back to the lowest rung. The measurement that
     * comes out is a real number produced by a degenerate mechanism — the cap did not pick a rung,
     * it disqualified the ladder — and a benchmark reporting it as a quality trade would be
     * describing something that did not happen. `superplayer-core`'s `DATA_SAVER` caps both height
     * and bitrate, so this is the difference between measuring that profile and measuring a
     * fallback path.
     *
     * [codecs] is null for a rung that declares no profile, which is most of them: a selector gating
     * on the decoder's profile levels has nothing to gate on and lets the rung through, so a test
     * that is not about the decoder is not accidentally about it.
     *
     * ref: RFC 6381 §3.3 for the `codecs` form; `avc1.640028` is H.264 High profile level 4.0.
     */
    public data class Rung(
        public val bitrateBps: Int,
        public val heightPx: Int,
        public val codecs: String? = null,
    ) {
        init {
            require(bitrateBps > 0) { "A rung needs a positive bitrate, was $bitrateBps" }
            require(heightPx > 0) { "A rung needs a positive height, was $heightPx" }
        }

        public companion object {
            /**
             * A rung of [bitrateBps] at the frame height a stream would encode it at.
             *
             * ref: Apple, *HLS Authoring Specification for Apple Devices*, whose recommended tier
             * tables pair each average bitrate with a frame size:
             * https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
             * The thresholds follow that pairing at the rung boundaries rather than reproducing the
             * table, because a synthetic ladder chooses its own bitrates: what has to be true is that
             * a higher rung is a larger picture, which is what makes a resolution ceiling select
             * rather than exclude.
             */
            @JvmStatic
            public fun of(bitrateBps: Int): Rung = Rung(bitrateBps, heightPxFor(bitrateBps))

            private fun heightPxFor(bitrateBps: Int): Int = when {
                bitrateBps < LADDER_480P_FLOOR_BPS -> 360
                bitrateBps < LADDER_720P_FLOOR_BPS -> 480
                bitrateBps < LADDER_1080P_FLOOR_BPS -> 720
                else -> 1080
            }

            // The rung boundaries [heightPxFor] reads, which its KDoc cites. Below the first a
            // rendition is 360p; the last is where 1080p starts.
            private const val LADDER_480P_FLOOR_BPS = 600_000
            private const val LADDER_720P_FLOOR_BPS = 1_200_000
            private const val LADDER_1080P_FLOOR_BPS = 3_000_000
        }
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
        ): TestContent = TestContent(listOf(Rung.of(bitrateBps)), durationMs, live = false)

        /**
         * On-demand video with a rendition ladder, which is what an ABR switch needs.
         *
         * [bitratesBps] is taken in the order given and is expected to ascend, because that is what a
         * manifest declares and what makes `UP` and `DOWN` mean what they say.
         *
         * Each two-second chunk is **one sample**: Media3's fake chunk source loads every chunk as a
         * `SingleSampleMediaChunk`. The player enters `READY` on buffered *duration*, but the
         * renderer consumes that one sample at once and is ready again only when the next chunk's
         * sample is in — so a player that starts on a single chunk goes back to buffering as soon
         * as its position moves, with most of the chunk still reported as buffered, until the next
         * chunk lands. Any start floor of at most one chunk does that on a link that has not
         * delivered the second chunk by the time playback starts: `SHORT_FORM`'s does, with no
         * switch, ceiling or policy involved (#117). That is this content's granularity, not the
         * profile's behaviour; a real stream's samples are frames tens of milliseconds apart.
         *
         * ref: `androidx.media3.test.utils.FakeChunkSource.createMediaChunk` (Media3 1.11)
         */
        @JvmStatic
        public fun videoLadder(
            bitratesBps: List<Int> = listOf(300_000, DEFAULT_BITRATE_BPS, 2_400_000),
            durationMs: Long = DEFAULT_DURATION_MS,
        ): TestContent {
            require(bitratesBps.isNotEmpty()) { "A ladder needs at least one rendition" }
            return TestContent(bitratesBps.map(Rung::of), durationMs, live = false)
        }

        /**
         * On-demand video with a ladder whose every rung is stated in full — the resolution and the
         * codec profile as well as the bitrate — which is what a selector that gates on the display
         * and on the decoder needs something to exclude by.
         *
         * [videoLadder] derives each rung's resolution from its bitrate and declares no codec, which
         * is enough for a test about *switching*; a test about *gating* names the rung that should
         * be refused, so it names the property it is refused on.
         *
         * [rungs] is taken in the order given and is expected to ascend by bitrate, as
         * [videoLadder]'s is and for the same reason.
         */
        @JvmStatic
        public fun ladder(rungs: List<Rung>, durationMs: Long = DEFAULT_DURATION_MS): TestContent {
            require(rungs.isNotEmpty()) { "A ladder needs at least one rendition" }
            return TestContent(rungs, durationMs, live = false)
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
        ): TestContent = TestContent(listOf(Rung.of(bitrateBps)), windowDurationMs, live = true)

        /**
         * A live window with a rendition ladder: the two properties ABR on live content needs at once.
         *
         * [liveVideo] has one rendition and [videoLadder] is on demand, so neither can express "a
         * live channel the player has to keep choosing a rung of" — which is the case a live linear
         * profile exists for, and the one `PRD.md` §6's live row measures. This is those two
         * combined and nothing else; both of them stay because most tests want exactly one of the
         * properties and naming the other would be noise.
         *
         * [bitratesBps] is taken in the order given and is expected to ascend, as [videoLadder]'s is
         * and for the same reason.
         */
        @JvmStatic
        public fun liveVideoLadder(
            bitratesBps: List<Int> = listOf(300_000, DEFAULT_BITRATE_BPS, 2_400_000),
            windowDurationMs: Long = DEFAULT_DURATION_MS,
        ): TestContent {
            require(bitratesBps.isNotEmpty()) { "A ladder needs at least one rendition" }
            return TestContent(bitratesBps.map(Rung::of), windowDurationMs, live = true)
        }

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
         * at media segment 2 needs a stream with a segment 2. [variantCount] is one unless the test
         * is about a *selection*: two variants are what gives the selector something to choose
         * between, and only an adaptive selection reports an estimate of its own.
         */
        @JvmStatic
        public fun hls(segmentCount: Int = DEFAULT_SEGMENT_COUNT, variantCount: Int = 1): TestContent = TestContent(
            rungs = emptyList(),
            durationMs = SyntheticHlsStream.durationMs(segmentCount),
            live = false,
            protocol = Protocol.HLS,
            sourceUri = SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI,
            resources = SyntheticHlsStream.resources(segmentCount, variantCount),
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
            rungs = emptyList(),
            durationMs = SyntheticDashStream.durationMs(segmentCount),
            live = false,
            protocol = Protocol.DASH,
            sourceUri = SyntheticDashStream.MANIFEST_URI,
            resources = SyntheticDashStream.resources(segmentCount),
        )

        /**
         * A live HLS stream whose origin keeps publishing: a new segment every
         * [SyntheticHlsStream.SEGMENT_DURATION_MS] of the harness's clock, listed in a sliding window
         * of [SyntheticHlsStream.LIVE_WINDOW_SEGMENT_COUNT].
         *
         * The healthy live stream the HLS half of this module lacked. [hls] is on-demand, and a live
         * playlist served from fixed bytes stops advancing — which is a defect the corpus carries
         * rather than a stream — so this is served by an origin that advances with the clock, and a
         * player reloading its playlist sees a new version for every segment published, as RFC 8216
         * §6.2.1 promises it. [FaultScript.Builder.serveThroughCache] puts a cache in front of it.
         */
        @JvmStatic
        @JvmOverloads
        public fun liveHls(dated: Boolean = false): TestContent {
            // A dated window (`EXT-X-PROGRAM-DATE-TIME`) is what a player measures its live offset
            // against, and the harness cannot keep the two clocks that measurement needs together:
            // Media3 anchors a dated HLS window on `System.currentTimeMillis()`, which Robolectric
            // leaves to the real machine, while every other clock here is the harness's and runs
            // far ahead of real time. A dated window therefore sees the player drift *ahead* of the
            // live edge at one second per harness second, and the engine holds the window the only
            // way it can — by playing at the slowest speed its live policy allows. That is a real
            // engine behaviour on an unreal clock, so it is opt-in: `LiveLatencyBindingTest` wants
            // exactly that, and everything else wants a window with no date and no offset at all,
            // which is also what many real origins serve. Segment 0 is dated so that the first
            // window's live edge is the moment this content was made, which is the moment the origin
            // starts publishing from: a player joining at the default position starts at its target.
            val liveEdgeAtStartMs = System.currentTimeMillis()
            val firstSegmentDateTimeMs = if (dated) {
                liveEdgeAtStartMs - SyntheticHlsStream.durationMs(SyntheticHlsStream.LIVE_WINDOW_SEGMENT_COUNT)
            } else {
                null
            }
            return TestContent(
                rungs = emptyList(),
                durationMs = SyntheticHlsStream.durationMs(SyntheticHlsStream.LIVE_WINDOW_SEGMENT_COUNT),
                live = true,
                protocol = Protocol.HLS,
                sourceUri = SyntheticHlsStream.LIVE_MULTIVARIANT_PLAYLIST_URI,
                publication = { elapsedMs ->
                    SyntheticHlsStream.liveResources(
                        SyntheticHlsStream.LIVE_WINDOW_SEGMENT_COUNT +
                            (elapsedMs / SyntheticHlsStream.SEGMENT_DURATION_MS).toInt(),
                        firstSegmentDateTimeMs,
                    )
                },
            )
        }

        /**
         * One entry of [HostileManifests]: a known-good stream of its protocol with one thing wrong.
         *
         * The corpus lives in `superplayer-testmedia` alongside the streams it is built from, and
         * arrives here the same way they do — as URI-to-bytes, naming no Media3 type. What this adds
         * is the one thing a test needs and the corpus cannot know: how the harness should load it,
         * which is exactly how it loads the good stream of the same protocol.
         *
         * The corpus records what SuperPlayer does with each of these today rather than asserting
         * that any of them is handled; see `docs/testing.md`.
         */
        @JvmStatic
        public fun hostile(stream: HostileStream): TestContent = TestContent(
            rungs = emptyList(),
            durationMs = stream.durationMs,
            live = false,
            protocol = when (stream.protocol) {
                HostileStream.Protocol.HLS -> Protocol.HLS
                HostileStream.Protocol.DASH -> Protocol.DASH
            },
            sourceUri = stream.sourceUri,
            resources = stream.resources(),
            // Served, not merely carried: the cache-control entry's defect is a header, and the
            // player reads it to say what an unrecovered failure was likely caused by.
            responseHeaders = stream.declaredResponseHeaders,
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
