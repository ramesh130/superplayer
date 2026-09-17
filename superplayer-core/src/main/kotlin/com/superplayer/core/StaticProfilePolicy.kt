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
 * The defaults being departed from are `DefaultLoadControl`'s as of Media3 1.11: 50s of buffer in both
 * directions, 1s before playback starts, 2s before it resumes after a rebuffer, and no back buffer.
 * Earlier releases started at 2.5s and 5s, which is where the rows below that keep 2.5s/5s come from.
 *
 * Every row sets [RetryPolicy.licence] since #205, and one fact shapes all four numbers: a licence is
 * acquired before the first frame, over a round trip of kilobytes, and there is no rung above it
 * worth much — a second source is a second container of the same entitlement (ADR-0012 rule 1), so
 * asking the licence server again is very nearly the only remedy there is. That makes patience cheap
 * in bytes and expensive in the one thing each profile guards differently: the time a viewer spends
 * looking at nothing.
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
        // Start floors are 2.5s/5s, Media3's own until its defaults dropped to 1s/2s: 2.5s is enough content to survive a first-segment hiccup
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
            // One title ahead, its manifest only. Long-form titles are chosen rather than scrolled
            // through, so media fetched for the next tile is most often media for a title nobody
            // picked; a parsed manifest costs kilobytes and still takes the manifest round trips out
            // of the start of the title that is picked.
            preload = PreloadPolicy(itemsAhead = 1, itemsBehind = 0, depth = PreloadDepth.SourcePrepared),
            // The one profile that can afford patience with a segment. A 30s floor means a segment
            // can be asked for again for several seconds before the viewer sees anything at all, so
            // this is where spending the budget is cheapest: five asks, the first after half a
            // second because a buffer that deep makes an immediate second ask free, and an 8s
            // ceiling because past that the cushion is draining and another rung is the better bet.
            //
            // The manifest keeps Media3's own: an on-demand manifest is fetched once, before there
            // is any buffer to protect, and three asks over about seven seconds is already longer
            // than a viewer waits on a black screen before another host is worth trying.
            //
            // The licence gets four asks and the shortest waits of this row: the viewer is committed
            // but is looking at a black screen until it arrives, so this is the one budget here that
            // is spent entirely before playback. A quarter of a second to the first ask and a 2s
            // ceiling keeps the whole of it inside the few seconds a committed viewer gives a title.
            retry = RetryPolicy(
                segment = LONG_FORM_SEGMENT_BUDGET,
                licence = LONG_FORM_LICENCE_BUDGET,
            ),
            // 1080p for a download, with no bitrate ceiling. A download is watched on the device that
            // fetched it, which is a phone or a tablet far more often than a television, and 1080p is
            // the highest rung such a screen shows a difference at; a 2160p rung costs about four
            // times the storage for a picture that screen cannot resolve. No bitrate ceiling, because
            // at a fixed height a higher bitrate is the better encode of the same picture, and that is
            // the trade a viewer downloading a long-form title is making on purpose.
            download = DownloadSelectionPolicy(maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED, maxVideoHeightPx = 1080),
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
            // No prefetch. A live window moves while a row waits: a playlist fetched ahead is stale
            // by the time the row plays, and media loaded ahead is media behind the edge.
            //
            // The mirror image of the on-demand row: more asks for the manifest, fewer for a
            // segment. A live playlist is reloaded once per target duration and a reload that fails
            // has to be recovered inside one, or the player falls off the edge — so four asks, each
            // cheap, with a 2s ceiling that keeps the whole budget inside a typical target duration.
            // A segment is the opposite: the 10s floor *is* the latency, and media still being
            // asked for after a second or two is media the window is about to drop, so two asks and
            // then the rung above.
            //
            // The licence is the same shape as the manifest here and gets the same budget: a live
            // join that spends seconds on an entitlement joins that far behind the edge and stays
            // there, which is the cost this whole profile is written against.
            retry = RetryPolicy(
                manifest = RetryBudget(maxRetries = 4, initialBackoffMs = 250, maxBackoffMs = 2_000),
                segment = RetryBudget(maxRetries = 2, initialBackoffMs = 250, maxBackoffMs = 1_000),
                licence = RetryBudget(maxRetries = 4, initialBackoffMs = 250, maxBackoffMs = 2_000),
            ),
            // No download ceiling of its own. Live content is not downloaded at all (ADR-0013 rule 8),
            // so what this profile would download is on-demand content a live app also offers, and for
            // that the unlimited default is the honest answer rather than a live-shaped number.
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
            buffer = SHORT_FORM_BUFFER,
            // Capped at 1080p. Not a data measure — a screen one: feed content is watched on a
            // phone, in a portrait viewport, where a 4K rendition is decoded, downscaled and thrown
            // away. The bytes and the decoder time are real; the extra pixels are not visible.
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = 1_080,
            ),
            // The profile the prefetch exists for. Two rows ahead, because a swipe moves one row and
            // a fling passes rows whose starts are skipped anyway; one behind, because a swipe back
            // is the next most common move. Each loaded to exactly this profile's
            // `bufferForPlaybackMs`, the media a player needs before it may start, so a row that
            // becomes current starts without fetching and nothing is spent past that. And decoders
            // warmed on the nearest of them, as many as the pool's idle players allow: a feed's rows
            // are seconds long, so the decoder's start is a real share of each row's, and the
            // players are already built and bounded by the device.
            preload = PreloadPolicy(
                itemsAhead = 2,
                itemsBehind = 1,
                depth = PreloadDepth.DecoderWarmed(durationMs = SHORT_FORM_BUFFER.bufferForPlaybackMs),
            ),
            // The shortest budget of the five, and the same one for both kinds, because in a feed
            // the thing being protected is the *start*: a row is seconds long and the viewer's
            // thumb is already moving, so a second spent asking again is a second of the row gone
            // and there is no cushion to hide it in. Two asks each, the first a quarter of a second
            // later — enough to clear a momentary edge failure, short enough to reach another rung
            // while the row is still on screen.
            //
            // And the licence on the same budget as the other two, which is the one profile where
            // that is right: the row is seconds long, so an entitlement still being asked for is a
            // row the thumb has already moved past, and the next row's licence is the one worth
            // spending on.
            retry = RetryPolicy(
                manifest = FEED_BUDGET,
                segment = FEED_BUDGET,
                licence = FEED_BUDGET,
            ),
            // 720p for a download. Clips are downloaded many at a time and each is watched once, in a
            // feed on a phone held upright, where the video is shown across the screen's short edge;
            // 720p fills that edge on most phones and costs about half the storage of 1080p per clip.
            download = DownloadSelectionPolicy(maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED, maxVideoHeightPx = 720),
        )

        // Lean-back television. Long-form again, with both of its costs moved further the same way:
        // holding media ahead costs less, and a stall costs more. The device is on mains power and
        // usually on a home link nobody meters, so a buffer spends neither battery nor the viewer's
        // data; the stall, when it comes, is on the room's largest screen in front of everyone
        // watching it.
        //
        // A 50s floor, which is Media3's own and what the adaptive policy's deep cushion lifts
        // on-demand to only once a stable, fast link has been measured. A television starts there,
        // because what that measurement guards against is spending a metered or battery-bound link
        // on media nobody watches, and neither is this device's case. The static table cannot see
        // the transport, so a television tethered to a phone keeps the floor; the adaptive policy's
        // transport cap is what spends less on that link.
        //
        // A 120s ceiling, the room above the floor being what rides out a household's broadband
        // blip — another device saturating the shared line, a router's restart — without a stall.
        // Bounded by what Media3 already bounds the buffer by in bytes: its default video target is
        // 125 MiB (`DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE`), which the top 1080p tier Apple
        // recommends, 7.8 Mbit/s on average, takes about 134s to fill. So 120s is the deepest a 1080p
        // ladder reaches in time before the byte target stops it, a 4K ladder stops on bytes first,
        // and no media is held that Media3's own defaults would not have allowed. The adaptive
        // policy's heap branch caps it further on a television whose heap is small.
        // ref: https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl#DEFAULT_VIDEO_BUFFER_SIZE()
        // ref: https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
        //
        // Start floors stay on-demand's 2.5s/5s rather than Media3's 1s/2s: a lean-back viewer has
        // chosen the title and sat down, so the second and a half a lower floor saves at the start
        // buys nothing against the stall a thin first buffer risks in front of the room.
        //
        // A 30s back buffer, as on-demand, and for a remote's reason too: the replay button on a
        // television remote skips back a fixed step, and that step should land in memory.
        PlaybackProfile.TV_LEANBACK -> PlaybackDecision(
            buffer = BufferPolicy(
                minBufferMs = 50_000,
                maxBufferMs = 120_000,
                bufferForPlaybackMs = 2_500,
                bufferForPlaybackAfterRebufferMs = 5_000,
                backBufferMs = 30_000,
                retainBackBufferFromKeyframe = true,
            ),
            // Uncapped. A television is the one screen every rung of a ladder was encoded for, and
            // what its panel cannot show is refused by the device constraint under any policy rather
            // than guessed at here.
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
            ),
            // One title either side, manifests only. Titles are chosen as on-demand ones are, so media
            // for a tile is most often media for a title nobody picked; but a remote moves focus one
            // tile at a time and left is one press as right is, so the tile behind is as likely next
            // as the one ahead.
            preload = PreloadPolicy(itemsAhead = 1, itemsBehind = 1, depth = PreloadDepth.SourcePrepared),
            // On-demand's budgets, for on-demand's reasons, and not raised on account of the deeper
            // floor: past the segment budget's 8s ceiling another host is the better bet however
            // much cushion is left, and the licence is spent before playback where no cushion exists.
            retry = RetryPolicy(
                segment = LONG_FORM_SEGMENT_BUDGET,
                licence = LONG_FORM_LICENCE_BUDGET,
            ),
            // 2160p for a download, with no bitrate ceiling. A download a television fetched is
            // watched on that television, and 2160p is the resolution its panel is sold at; the 4320p
            // rung above it costs about four times the storage for a picture no such panel resolves
            // (derivation, CONTRIBUTING.md rule 4: four times the pixels at a like encode).
            // A television's storage is small, and running out of it is what the download store's
            // storage conditions answer, not a lower rung chosen for every title in advance.
            download = DownloadSelectionPolicy(maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED, maxVideoHeightPx = 2_160),
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
            // No prefetch: every byte fetched for a row the viewer does not reach is the waste this
            // profile is minimising, and a static table cannot see whether the link is metered.
            //
            // A retry is a transfer paid for twice, which is exactly what this profile is spending
            // less of — so fewer asks than anywhere else, and Media3's own unhurried waits kept
            // rather than shortened, because waiting costs nothing and asking costs data. Two asks
            // is still enough for the single failure a retry is actually for.
            //
            // The one row where the licence is *more* patient than its neighbours, and for this
            // profile's own reason: a retried licence costs a round trip of kilobytes, while a
            // session that fails for want of one has spent a manifest and a segment or two on
            // nothing. Saving data by refusing to ask again for the cheapest request of the session
            // is the one way this profile could spend more of it.
            retry = RetryPolicy(
                manifest = DATA_SAVER_BUDGET,
                segment = DATA_SAVER_BUDGET,
                licence = RetryBudget(maxRetries = 4, initialBackoffMs = 250, maxBackoffMs = 2_000),
            ),
            // A download is held to the same 480p and 800 kbps as playback, for the same reason: the
            // bytes of a download are data spent exactly as a stream's are, and a viewer who asked to
            // spend less did not exempt the ones fetched ahead of time.
            download = DownloadSelectionPolicy(maxVideoBitrateBps = 800_000, maxVideoHeightPx = 480),
        )
    }

    private companion object {
        /**
         * The segment budget of the long-form rows, named because `VIDEO_ON_DEMAND` and `TV_LEANBACK`
         * share it; the numbers are argued at the `VIDEO_ON_DEMAND` row.
         */
        val LONG_FORM_SEGMENT_BUDGET = RetryBudget(maxRetries = 5, initialBackoffMs = 500, maxBackoffMs = 8_000)

        /** As [LONG_FORM_SEGMENT_BUDGET], for the licence. */
        val LONG_FORM_LICENCE_BUDGET = RetryBudget(maxRetries = 4, initialBackoffMs = 250, maxBackoffMs = 2_000)

        /**
         * The feed's budget, named because both of its kinds share it; the numbers are argued at
         * the `SHORT_FORM` row.
         */
        val FEED_BUDGET = RetryBudget(maxRetries = 2, initialBackoffMs = 250, maxBackoffMs = 1_000)

        /** As [FEED_BUDGET], for `DATA_SAVER`, and argued at that row. */
        val DATA_SAVER_BUDGET = RetryBudget(
            maxRetries = 2,
            initialBackoffMs = RetryBudget.MEDIA3_DEFAULT.initialBackoffMs,
            maxBackoffMs = RetryBudget.MEDIA3_DEFAULT.maxBackoffMs,
        )

        /**
         * The short-form buffer, named because the profile's prefetch depth is read from it: a row is
         * prefetched to exactly the media its player needs before it may start. The numbers are argued
         * at the `SHORT_FORM` row.
         */
        val SHORT_FORM_BUFFER = BufferPolicy(
            minBufferMs = 2_500,
            maxBufferMs = 15_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 2_000,
            backBufferMs = 0,
            retainBackBufferFromKeyframe = false,
        )
    }
}
