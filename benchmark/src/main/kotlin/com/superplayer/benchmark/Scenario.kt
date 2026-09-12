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

import com.superplayer.core.PlaybackProfile

/**
 * The content axis of `PRD.md` §6's matrix: what is played, and which SuperPlayer profile arm (c)
 * plays it under.
 *
 * §6 asks for content "covering VOD, live, and short-form ladders", and the profile is part of the
 * scenario rather than part of the arm for a reason worth stating: comparing `LIVE_LINEAR` against
 * stock on an on-demand asset would measure a configuration nobody would ship. A profile is a name
 * for a use case, so the use case and the content have to move together, and a row of the report is
 * therefore "short-form on congested WiFi" rather than "short-form" and "congested WiFi" crossed
 * blindly.
 *
 * [VOD_DATA_SAVER] is the same content as [VOD] under a different profile, and it is in the matrix
 * on purpose — see its own documentation. It is the one cell where `PRD.md` F1's trade is visible
 * *today*, and §6 requires that trade to appear as a bitrate loss beside the rebuffer win.
 */
internal enum class Scenario(

    /** How the scenario appears in a report's row heading and a raw trace's `scenario` field. */
    val label: String,

    /** The profile arm (c) is built with. Arms (a) and (b) have no profile; see [Arm]. */
    val profile: PlaybackProfile,

    /**
     * The rendition ladder, ascending, in bits per second.
     *
     * ref: Apple, *HLS Authoring Specification for Apple Devices*, the recommended tier tables:
     * https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
     * The specification's shape is what is followed — a ladder whose rungs roughly double, spanning
     * from a tier that plays on a poor cellular link to one that needs real bandwidth. The exact
     * rungs below are this project's choice within that shape, chosen against the network profiles
     * they are played over rather than copied from a table: `PRD.md` §6's links run from 1 Mbit/s to
     * 20 Mbit/s, and a ladder that did not straddle that range would give ABR nothing to do and
     * would make every arm's bitrate column identical.
     */
    val ladderBitratesBps: List<Int>,

    /**
     * How long each session plays, in milliseconds of the harness's clock.
     *
     * Long enough for the metrics to exist rather than long enough to be realistic — which is a
     * trade the report states. Bitrate is time-weighted from `PlaybackStateSampled` at the schema's
     * ten-second cadence, so a session has to span several samples before its bitrate column means
     * anything, and the dropout period of `LTE_WITH_DROPOUTS` is twenty seconds, so a session
     * shorter than that could miss the dropout entirely and report a suspiciously clean row.
     *
     * The device arm plays for thirty minutes, which is what `PRD.md` §6 asks for and what a battery
     * delta needs; this arm's clock is a `FakeClock` and its cost is CPU rather than time, so the
     * length here is bounded by how long a full matrix takes to run rather than by patience.
     */
    val playbackMs: Long,

    /**
     * Whether the content has a moving live edge.
     *
     * Live is not a longer VOD asset: it is the only content live-edge latency is defined for, and
     * it is the case where buffering deeply is falling behind rather than being careful. Without a
     * live row the matrix could not show the trade `LIVE_LINEAR` exists to make.
     */
    val live: Boolean,
) {

    /** Long-form on demand: the widest-tolerance case, and the default profile's own. */
    VOD(
        label = "VOD",
        profile = PlaybackProfile.VIDEO_ON_DEMAND,
        ladderBitratesBps = FULL_LADDER,
        playbackMs = SESSION_MS,
        live = false,
    ),

    /** A live linear channel, where buffer depth is latency. */
    LIVE(
        label = "live",
        profile = PlaybackProfile.LIVE_LINEAR,
        ladderBitratesBps = FULL_LADDER,
        playbackMs = SESSION_MS,
        live = true,
    ),

    /**
     * Short-form feed content: a shorter ladder, because feed encodes do not go to the top.
     *
     * The rungs above 2 Mbit/s are dropped rather than capped, which is what a feed's own encoding
     * ladder looks like — a clip watched in a portrait viewport is not published at 4.5 Mbit/s. That
     * is content, not policy: `SHORT_FORM`'s own 1080p ceiling is policy and applies on top.
     */
    SHORT_FORM(
        label = "short-form",
        profile = PlaybackProfile.SHORT_FORM,
        ladderBitratesBps = FEED_LADDER,
        playbackMs = SESSION_MS,
        live = false,
    ),

    /**
     * The same content as [VOD], under `DATA_SAVER` — the cell `PRD.md` §6's F1 rule is about.
     *
     * F1's trade is *lower bitrate for fewer stalls*, and §6 requires it to appear **as a bitrate
     * loss** beside the rebuffer win rather than as a rebuffer win alone. This row is where that is
     * visible in the Phase-1 baseline, and it is worth being precise about why it is the only one:
     *
     * `DATA_SAVER` is the one shipped profile that caps quality — 800 kbit/s and 480p — so it is the
     * one profile that gives bitrate up on purpose. The other three cap nothing a 720p ladder
     * reaches, so any bitrate difference they show is a second-order effect of buffer depth on ABR's
     * headroom rather than a trade anybody chose.
     *
     * **The F1 trade as `PRD.md` states it is adaptive, and adaptive policy is Phase 3's.** What
     * this row measures is a profile making the trade *statically*, once, at construction. That is a
     * real instance of the trade and a real loss to report; it is not yet the bandwidth-aware
     * version, and the report says so where the row appears rather than leaving a reader to assume
     * the harder thing has already been done.
     */
    VOD_DATA_SAVER(
        label = "VOD (data saver)",
        profile = PlaybackProfile.DATA_SAVER,
        ladderBitratesBps = FULL_LADDER,
        playbackMs = SESSION_MS,
        live = false,
    ),
    ;

    /** The top rung, which is what the QoE objective's rebuffer penalty is scaled to. See [QoeScore]. */
    val ladderTopBitrateBps: Int get() = ladderBitratesBps.max()
}

/**
 * A ladder straddling `PRD.md` §6's network profiles: unreachable at 1 Mbit/s at the top, comfortable
 * at 20 Mbit/s, and with rungs in between that the 3 Mbit/s and 5 Mbit/s links have to choose among.
 *
 * See [Scenario.ladderBitratesBps] for the citation and for why the exact rungs are chosen here
 * rather than copied.
 */
private val FULL_LADDER = listOf(365_000, 730_000, 2_000_000, 4_500_000)

/** [FULL_LADDER] without its top rung: what a feed's own encoding ladder looks like. */
private val FEED_LADDER = listOf(365_000, 730_000, 2_000_000)

/**
 * How long a Robolectric session plays.
 *
 * Six `PlaybackStateSampled` intervals at the schema's ten-second cadence, and three dropout periods
 * of `LTE_WITH_DROPOUTS` at its twenty-second one — so a bitrate average has something to average and
 * a dropout row cannot come out clean by having missed the dropout. See [Scenario.playbackMs].
 */
private const val SESSION_MS = 60_000L
