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

/**
 * Every stream the device arm plays, and where each one comes from.
 *
 * `PRD.md` §0.2 is a product requirement rather than a style note: **every performance number this
 * project publishes is produced by its own harness against public test streams**, and none is
 * carried in from a proprietary platform. A list of URLs with no provenance would satisfy the letter
 * of that and none of its point, so each entry below carries the public page that publishes it, and
 * the report prints this table beside the numbers rather than only linking to it.
 *
 * ## What is here and what is a gap
 *
 * The Robolectric arm plays generated content instead — see [Scenario] and `benchmark/README.md` —
 * because it is the arm that has to be reproducible, and a stream fetched over the internet is the
 * one thing that cannot be. These are the device arm's, where the point is the opposite: a real
 * CDN, a real network, and real decoders are exactly what the device arm is for.
 *
 * Two cells of `PRD.md` §6's content axis are **documented gaps rather than silent omissions**, and
 * they are [gaps] below. A gap that is written down can be closed; one that is left out of a table
 * reads as a cell that passed.
 */
internal object PublicStreams {

    /**
     * A public test stream, with the page that publishes it.
     *
     * [source] is not decoration. Clean-room rule 4 asks for a public citation on anything a reader
     * would otherwise have to take on trust, and where a benchmark's content came from is precisely
     * that: a number is only reproducible by someone else if they can fetch the same bytes.
     */
    data class Stream(
        val label: String,
        val protocol: String,
        val uri: String,
        val source: String,
    )

    /**
     * A cell of the content axis that this harness does not measure, and why.
     *
     * `PRD.md` §6 names content the matrix should cover; where a cell is not covered, the honest
     * report says which one and what would close it. `PRD.md` §6's honesty rules are about not
     * hiding losses, and an uncovered cell is a smaller version of the same thing — it is a place
     * the harness has no evidence, and a table without the row invites the reader to assume it does.
     */
    data class Gap(
        val cell: String,
        val why: String,
        val closedBy: String,
    )

    /**
     * The streams the device arm plays.
     *
     * The same two the demo plays, deliberately: they are already verified as public and already
     * carry their sources in this repository, and a benchmark that introduced two more URLs would
     * be two more things to keep alive. `demo/src/main/kotlin/com/superplayer/demo/DemoStream.kt`
     * is the other place they appear.
     */
    val streams: List<Stream> = listOf(
        Stream(
            label = "VOD, HLS",
            protocol = "HLS",
            // Apple's own "Advanced stream (HEVC/H.264, fMP4)" example — the reference stream the
            // HLS specification's authors publish, multi-bitrate with alternate audio and subtitles.
            uri = "https://devstreaming-cdn.apple.com/videos/streaming/examples/" +
                "img_bipbop_adv_example_fmp4/master.m3u8",
            source = "https://developer.apple.com/streaming/examples/",
        ),
        Stream(
            label = "VOD, DASH",
            protocol = "DASH",
            // Clear (unencrypted) H.264 from the media set ExoPlayer's own demo application has
            // shipped against for years, so it is a stream Media3 is known to be exercised on.
            uri = "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd",
            source = "https://github.com/androidx/media/blob/release/demos/main/src/main/assets/media.exolist.json",
        ),
    )

    /** The cells of `PRD.md` §6's content axis this harness does not measure. See [Gap]. */
    val gaps: List<Gap> = listOf(
        Gap(
            cell = "Widevine (DRM) content, every network profile and every arm",
            // `PRD.md` §6 lists public Widevine test content, and issue #43 is explicit that the
            // cell is to be a documented gap rather than a silent omission.
            why = "DRM is Phase 6. `superplayer-drm` is an empty placeholder module today, so " +
                "arm (c) would be a SuperPlayer with no DRM behaviour of its own and the row would " +
                "compare three players that are, on this axis, the same player. Public Widevine " +
                "test content exists — the CENC counterpart of the DASH stream above, at " +
                "`https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears.mpd` — and nothing " +
                "here fetches it.",
            closedBy = "Phase 6, with `superplayer-drm`. The row is worth measuring when there is " +
                "a security-level ladder and a provisioning path to measure, and its interesting " +
                "metric is licence-acquisition time, which is not in this matrix yet.",
        ),
        Gap(
            cell = "Live content, device arm",
            why = "The Robolectric arm covers live — `Scenario.LIVE`, over a synthetic window with " +
                "a moving edge — so live is measured, and this gap is the device half of it only. " +
                "It is open because a live URL is a claim that something is still publishing right " +
                "now, and `docs/testing.md` bars this repository's own checks from the network, so " +
                "nothing here can verify one. Vendoring a live URL that had quietly stopped would " +
                "produce a device row of startup failures that looked like a finding.",
            closedBy = "A public live stream verified by a device run, added to `streams` with its " +
                "source. The device arm's harness needs no change to take one.",
        ),
    )
}
