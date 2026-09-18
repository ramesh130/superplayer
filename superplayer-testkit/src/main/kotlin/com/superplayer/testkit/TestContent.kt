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
import com.superplayer.testmedia.SyntheticDashChoice
import com.superplayer.testmedia.SyntheticDashPassthrough
import com.superplayer.testmedia.SyntheticDashStream
import com.superplayer.testmedia.SyntheticHlsStream
import com.superplayer.testmedia.WidevineProtection

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

    /**
     * Whether this stream declares Widevine protection, and therefore whether a player of it acquires
     * a licence from [FakeLicenceServer] before it reads a sample.
     *
     * A property of the *content* rather than an argument to the harness, because it is a property of
     * the content: what makes a session ask for a licence is the manifest, and a test that pointed a
     * protected player at an unprotected stream would be describing nothing. The device that answers
     * is the other half and is stated separately, in [DeviceStatement.declareWidevine].
     */
    internal val protected: Boolean = false,

    /**
     * Whether described content carries an audio track beside its video. Only [videoWithAudio] sets it:
     * every other described stream is video alone, which is all a measurement of the video needs.
     */
    internal val withAudio: Boolean = false,
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
     * This content, also served from [host]: every resource at its own address and at the same
     * address on [host], byte for byte, with [sourceUri] naming the copy on [host].
     *
     * A second CDN host in front of one origin, which is what `PRD.md`'s F7 is about: the same
     * rendition under a URL that differs only in its host. Playing [sourceUri] from before this call
     * and from after it is playing one piece of content from two hosts. The synthetic streams
     * reference their segments relatively, so a playlist fetched from [host] names [host]'s segments.
     *
     * Only for content served from fixed resources — a real HLS or DASH stream, not described content
     * and not a live origin that keeps publishing.
     */
    public fun servedFrom(host: String): TestContent {
        require(protocol != Protocol.DESCRIBED && publication == null) {
            "Only a real protocol stream with fixed resources can be served from a second host"
        }
        return TestContent(
            rungs = rungs,
            durationMs = durationMs,
            live = live,
            protocol = protocol,
            sourceUri = onHost(sourceUri, host),
            resources = resources + resources.mapKeys { (uri, _) -> onHost(uri, host) },
            responseHeaders = responseHeaders + responseHeaders.mapKeys { (uri, _) -> onHost(uri, host) },
            protected = protected,
        )
    }

    /**
     * This content, with [other]'s resources served from the same transport as well — one origin
     * holding two streams, each still reached by its own [sourceUri].
     *
     * What it is for is a fallback *between* streams: `MediaRequest.sources` lists candidates for one
     * piece of content, and the case ADR-0011 rule 5 is about is a DASH source falling back to an HLS
     * one, which needs both to be fetchable by one player. [servedFrom] is the other shape and not
     * this one — that is the same stream at a second host, which is what a rung 2 location failover
     * moves between.
     *
     * This content stays the subject: its [sourceUri] is what a player is pointed at, and its
     * duration and protocol are what the harness reads. Only for real protocol streams with fixed
     * resources, for [servedFrom]'s reason; the two must not serve different bytes at one address,
     * which the synthetic streams cannot do since each names its own paths.
     */
    public fun alsoServing(other: TestContent): TestContent {
        require(protocol != Protocol.DESCRIBED && publication == null) {
            "Only a real protocol stream with fixed resources can serve a second stream"
        }
        require(other.protocol != Protocol.DESCRIBED && other.publication == null) {
            "Only a real protocol stream with fixed resources can be served alongside another"
        }
        return TestContent(
            rungs = rungs,
            durationMs = durationMs,
            live = live,
            protocol = protocol,
            sourceUri = sourceUri,
            resources = other.resources + resources,
            responseHeaders = other.responseHeaders + responseHeaders,
            protected = protected || other.protected,
        )
    }

    /**
     * This content served with exactly [headers] on every one of its resources, replacing whatever it
     * declared — and with an empty map, served by an origin that reports no headers at all.
     *
     * The control a *delivery* pathology needs, and the only way to write one honestly. A defect carried as
     * [HostileStream.declaredResponseHeaders] — a live playlist served cacheable, a CORS configuration that
     * refuses a credentialed request — is invisible to any parse, so the claim "this finding came from the
     * transfer and not from the document" can only be made by diagnosing the identical document twice, once
     * with the headers and once without. The other control it needs is the opposite: an origin that speaks
     * the same protocol *correctly*, which is a header on healthy content and has nowhere else to come from.
     *
     * The headers go on every resource because that is what the configurations in question are: a cache rule
     * is set on a path and a CORS policy on an origin, neither per file. Nothing else changes — the same
     * resources, at the same URIs, over the same transport.
     */
    public fun servedWithResponseHeaders(headers: Map<String, String>): TestContent = TestContent(
        rungs = rungs,
        durationMs = durationMs,
        live = live,
        protocol = protocol,
        sourceUri = sourceUri,
        resources = resources,
        responseHeaders = if (headers.isEmpty()) emptyMap() else resources.keys.associateWith { headers },
        publication = publication,
        protected = protected,
        withAudio = withAudio,
    )

    private fun onHost(uri: String, host: String): String {
        val parsed = java.net.URI(uri)
        return java.net.URI(parsed.scheme, parsed.userInfo, host, parsed.port, parsed.path, parsed.query, parsed.fragment)
            .toString()
    }

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
     * that is not about the decoder is not accidentally about it. A non-null [codecs] also names
     * the rung's MIME type, as a manifest's would, so `dvhe.08.07` is a Dolby Vision rung; one with
     * none is H.264. Media3 adapts within one MIME type, so a ladder mixing them is not a ladder.
     *
     * [frameRate] is the rate the rung *declares*, as HLS's `FRAME-RATE` attribute and DASH's
     * `@frameRate` do, and null for a rung that declares none. It is what a player matching the display
     * reads (ADR-0014 rule 4). It does not space the samples: the fake renderer shows whatever it is
     * handed, so a declared 24 fps rung is still delivered at [DEFAULT_FRAME_RATE].
     *
     * [hdr] is a rung that declares HDR10's colour: BT.2020 primaries and the PQ (SMPTE ST 2084)
     * transfer, as HLS's `VIDEO-RANGE=PQ` and DASH's colour descriptors do. It is what a display that
     * lists no PQ type is asked about (ADR-0014 rule 9); false, the default, declares nothing, which a
     * selector reads as SDR.
     *
     * ref: RFC 6381 §3.3 for the `codecs` form; `avc1.640028` is H.264 High profile level 4.0.
     * spec: RFC 8216 §4.3.4.2 (`FRAME-RATE`, `VIDEO-RANGE`); ISO/IEC 23009-1 §5.3.7.2 (`@frameRate`);
     * ITU-R BT.2100 for PQ over BT.2020.
     */
    public data class Rung(
        public val bitrateBps: Int,
        public val heightPx: Int,
        public val codecs: String? = null,
        public val frameRate: Float? = DEFAULT_FRAME_RATE,
        public val hdr: Boolean = false,
    ) {
        init {
            require(bitrateBps > 0) { "A rung needs a positive bitrate, was $bitrateBps" }
            require(heightPx > 0) { "A rung needs a positive height, was $heightPx" }
            require(frameRate == null || frameRate > 0f) { "A declared frame rate is positive, was $frameRate" }
        }

        public companion object {

            /**
             * The frame rate every rung declared before a rung could say otherwise, and the spacing of
             * the described content's samples: 30 fps, a common broadcast and web rate.
             */
            public const val DEFAULT_FRAME_RATE: Float = 30f

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
         * On-demand video with one rendition and an AAC audio track beside it: content a player enables
         * both renderers for, which is what a tunneling decision needs, since Media3 tunnels only a video
         * renderer and an audio renderer together (ADR-0014 rule 7).
         *
         * Synthesized in memory like [video], so it cannot be played under a fault script or a network
         * trace, which need a transfer to sit in front of; the harness refuses the combination rather than
         * dropping the audio.
         */
        @JvmStatic
        public fun videoWithAudio(
            bitrateBps: Int = DEFAULT_BITRATE_BPS,
            durationMs: Long = DEFAULT_DURATION_MS,
        ): TestContent = TestContent(listOf(Rung.of(bitrateBps)), durationMs, live = false, withAudio = true)

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
         *
         * [secondVariantHost] puts the second variant's playlist and segments on a host of their
         * own, which is how a [FaultScript] addresses a fault at one *rendition*: the two renditions
         * of this stream carry identical bytes and differ only in what they declare, so without a
         * host of its own there is nothing about the higher one a fault could name.
         */
        @JvmStatic
        public fun hls(
            segmentCount: Int = DEFAULT_SEGMENT_COUNT,
            variantCount: Int = 1,
            secondVariantHost: String? = null,
        ): TestContent = TestContent(
            rungs = emptyList(),
            durationMs = SyntheticHlsStream.durationMs(segmentCount),
            live = false,
            protocol = Protocol.HLS,
            sourceUri = SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI,
            resources = SyntheticHlsStream.resources(segmentCount, variantCount, secondVariantHost),
        )

        /**
         * A real DASH stream: an MPD, a fragmented-MP4 initialization segment and [segmentCount]
         * media segments, played through Media3's own MPD parser and fragmented-MP4 extractor.
         *
         * The mirror image of [hls] — same codec, same sample rate, same declared bitrate — for the
         * reason `docs/testing.md` gives: it is what lets one [FaultScript] be run against both and
         * be a comparison of the protocols.
         *
         * [mirrorHost] makes the MPD declare the same media at two locations — its own host and that
         * one — and serves every segment from both. It is the shape a host failover needs and the
         * one DASH has in its own specification, which is why the two-location form of this stream
         * is DASH's and the two-*rendition* form is [hls]'s. Unlike [servedFrom], which moves the
         * whole stream to a second host, this leaves the manifest where it was: what the mirror
         * holds is an alternative for the media the manifest names.
         */
        @JvmStatic
        public fun dash(
            segmentCount: Int = DEFAULT_SEGMENT_COUNT,
            mirrorHost: String? = null,
        ): TestContent = TestContent(
            rungs = emptyList(),
            durationMs = SyntheticDashStream.durationMs(segmentCount),
            live = false,
            protocol = Protocol.DASH,
            sourceUri = SyntheticDashStream.MANIFEST_URI,
            resources = SyntheticDashStream.resources(segmentCount, mirrorHost),
        )

        /**
         * A real DASH stream offering the choice an AV receiver changes: stereo AAC beside 5.1 AC-3, each
         * at a path of its own that `SyntheticDashPassthrough` names, so what a player fetched can be
         * counted per encoding. The harness's audio renderer plays AAC on any output and AC-3 only on an
         * output `DeviceStatement.declareAudioOutput` says passes it through, as a device with no AC-3
         * decoder does. The media is [dash]'s under both.
         */
        @JvmStatic
        @JvmOverloads
        public fun dashWithPassthroughAudio(segmentCount: Int = DEFAULT_SEGMENT_COUNT): TestContent = TestContent(
            rungs = emptyList(),
            durationMs = SyntheticDashPassthrough.durationMs(segmentCount),
            live = false,
            protocol = Protocol.DASH,
            sourceUri = SyntheticDashPassthrough.MANIFEST_URI,
            resources = SyntheticDashPassthrough.resources(segmentCount),
        )

        /**
         * A real DASH stream offering a choice: two renditions of every one of [audioLanguages], and a
         * WebVTT subtitle file for every one of [subtitleLanguages], each at a path of its own —
         * `SyntheticDashChoice` names the paths — so what a download or a player fetched can be counted
         * per rendition and per language. The media is [dash]'s under every rendition.
         */
        @JvmStatic
        public fun dashWithChoice(
            audioLanguages: List<String>,
            subtitleLanguages: List<String> = emptyList(),
            segmentCount: Int = DEFAULT_SEGMENT_COUNT,
        ): TestContent = TestContent(
            rungs = emptyList(),
            durationMs = SyntheticDashChoice.durationMs(segmentCount),
            live = false,
            protocol = Protocol.DASH,
            sourceUri = SyntheticDashChoice.MANIFEST_URI,
            resources = SyntheticDashChoice.resources(segmentCount, audioLanguages, subtitleLanguages),
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
         * A live DASH stream: an MPD of `type="dynamic"` over a minute of time-shift window, which is
         * `HostileManifests.dashLiveBaseline()` — the healthy stream the corpus's live entries are
         * modifiers over — named for what it is rather than for the corpus it came from.
         *
         * Unlike [liveHls] it is served from fixed bytes: the MPD is written once, when this content is
         * made, and a reload reads the same one. That is enough for a test that reads the manifest to
         * learn the stream is live (#251), and it is why a test that needs a window moving on the
         * harness's clock uses [liveHls].
         */
        @JvmStatic
        public fun liveDash(): TestContent = hostile(HostileManifests.dashLiveBaseline())

        /**
         * [hls], Widevine-protected: the same media, under a playlist whose `EXT-X-KEY` names
         * Widevine and carries its initialization data.
         *
         * A player of this acquires a licence from [FakeLicenceServer] before it reads a sample, so a
         * test of it is a test of the whole exchange — the session opened, the key request composed,
         * the request carried over the harness's own transport, the response fed back. The device
         * that answers is [DeviceStatement.declareWidevine]'s, and a test that states nothing plays on
         * an ordinary provisioned L1 handset.
         *
         * The segments are not encrypted, and `WidevineProtection`'s KDoc argues at length why that
         * is the right stream rather than a shortcut.
         */
        @JvmStatic
        public fun protectedHls(segmentCount: Int = DEFAULT_SEGMENT_COUNT): TestContent = TestContent(
            rungs = emptyList(),
            durationMs = SyntheticHlsStream.durationMs(segmentCount),
            live = false,
            protocol = Protocol.HLS,
            sourceUri = SyntheticHlsStream.PROTECTED_MULTIVARIANT_PLAYLIST_URI,
            resources = SyntheticHlsStream.protectedResources(segmentCount),
            protected = true,
        )

        /**
         * [dash], Widevine-protected: the same media, under an MPD declaring Common Encryption and
         * carrying Widevine's `pssh` box.
         *
         * The mirror image of [protectedHls], as [dash] is of [hls] — the same media, the same
         * licence server, the same device — so a test run against both is comparing what the two
         * protocols do with the *same* protection rather than two unrelated arrangements. The two
         * declare it very differently (a `ContentProtection` descriptor against an `EXT-X-KEY` tag)
         * and hand the session the same initialization data, which is the fact worth pinning.
         */
        @JvmStatic
        @JvmOverloads
        public fun protectedDash(
            segmentCount: Int = DEFAULT_SEGMENT_COUNT,
            key: WidevineProtection.ContentKey = WidevineProtection.ContentKey.PRIMARY,
        ): TestContent = TestContent(
            rungs = emptyList(),
            durationMs = SyntheticDashStream.durationMs(segmentCount),
            live = false,
            protocol = Protocol.DASH,
            sourceUri = SyntheticDashStream.protectedManifestUri(key),
            resources = SyntheticDashStream.protectedResources(segmentCount, key),
            protected = true,
        )

        /**
         * [liveHls], Widevine-protected: a live window that keeps publishing, under an `EXT-X-KEY`.
         *
         * The stream a key *rotation* can be put to a player over, which no on-demand stream is: a
         * session that outlives a sliding window is the one a real origin re-keys under, and
         * `DeviceStatement.signalKeyRotation` is the device end of that. It carries no
         * `EXT-X-PROGRAM-DATE-TIME` for [liveHls]'s reason and that reason alone — the harness's
         * clock and the wall clock a dated window is measured against do not run together — and
         * nothing about a licence needs one.
         */
        @JvmStatic
        public fun protectedLiveHls(): TestContent = TestContent(
            rungs = emptyList(),
            durationMs = SyntheticHlsStream.durationMs(SyntheticHlsStream.LIVE_WINDOW_SEGMENT_COUNT),
            live = true,
            protocol = Protocol.HLS,
            sourceUri = SyntheticHlsStream.PROTECTED_LIVE_MULTIVARIANT_PLAYLIST_URI,
            publication = { elapsedMs ->
                SyntheticHlsStream.liveResources(
                    SyntheticHlsStream.LIVE_WINDOW_SEGMENT_COUNT +
                        (elapsedMs / SyntheticHlsStream.SEGMENT_DURATION_MS).toInt(),
                    firstSegmentDateTimeMs = null,
                    protected = true,
                )
            },
            protected = true,
        )

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
