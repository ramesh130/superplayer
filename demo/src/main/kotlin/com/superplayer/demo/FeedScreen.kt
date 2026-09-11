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

package com.superplayer.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlayerPool
import com.superplayer.core.SuperPlayer

/**
 * A long scrolling feed, played out of a [PlayerPool] — the screen the pool exists for.
 *
 * The claim it demonstrates is the one that is invisible in a single-player demo: a feed has no
 * fixed number of videos in it, and the naive implementation gives every row its own player and
 * falls over somewhere down the scroll when the device runs out of decoder instances. Here the
 * number of live players never exceeds what the device reported it can afford, however far the
 * viewer scrolls, and the counter at the top says so while they do it.
 *
 * Scroll it and watch the header. "in use" rises to the pool's bound and stops; "built" rises to the
 * bound and stops. Rows past that point get no player and say so, which is the pool's actual
 * contract rather than an error path.
 *
 * ## One row plays, and the rest are deliberately more expensive than a real feed's
 *
 * That one row plays is forced rather than chosen. Every SuperPlayer requests audio focus while it
 * plays — ADR-0006 rule 1, and not a profile's to switch off — and focus is a single token, so a
 * second row calling `play()` takes it from the first and Media3 answers by clearing that row's
 * `playWhenReady`. Two playing rows is one playing row and one that stopped.
 *
 * What every *other* visible row does here is a demonstration choice, and not what a production feed
 * should copy. Each of them takes a pooled player, prepares it, and shows its first decoded frame.
 * A real feed — YouTube's is the reference — gives a player only to the active row and shows the
 * uploader's **artwork** for all the others: a designed image with the title and a duration badge on
 * it, which is one cached image decode rather than a manifest fetch, a media segment and a scarce
 * decoder instance spent on a row the viewer may scroll straight past. Frame zero is usually a fade
 * or a slate, so it is a worse picture as well as a dearer one, and `MediaRequest.artworkUri` is the
 * library's own name for the better one.
 *
 * This screen is greedier on purpose: taking a player per visible row is what drives the pool to its
 * bound where a viewer can watch it happen, which is the one thing a feed built the right way would
 * never show. Issue #28 tracks the production-shaped recipe.
 *
 * ## Where the recycling actually happens
 *
 * `LazyColumn` disposes a row's composition once it is far enough off screen, so [FeedRow]'s
 * [DisposableEffect] is the whole of the lifecycle: acquire on enter, recycle on leave. That is not
 * demo scaffolding — it is the same shape a `RecyclerView.Adapter` writes in `onViewAttachedToWindow`
 * and `onViewDetachedFromWindow`, and it is deliberately the only pool code in this file.
 *
 * Note what is *not* here. Nothing restores the volume this row muted, resets playback speed, or
 * removes the listener this row registered; [PlayerPool.recycle] does all of that, which is the point
 * of it being in the library. A hand-rolled pool is usually a pool plus a slowly-growing list of those
 * corrections, each added after someone noticed a frame from the wrong video.
 *
 * ## How long it is
 *
 * [FeedItem.DEFAULT_COUNT] rows, because `PRD.md`'s Phase 4 exit criterion is memory that stays flat
 * over a 200-item scroll, and a feed shorter than that cannot be scrolled to show it. [rowCount] is
 * what a launch argument overrides (see [MainActivity]); devicelab's leak hunt passes the count it
 * scrolls, so the rows it counts and the rows on screen are the same number by construction.
 */
@Composable
internal fun FeedScreen(rowCount: Int = FeedItem.DEFAULT_COUNT, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // One pool for the screen, sized by the device rather than by a number chosen here. It outlives
    // every row and is released when the screen goes, which is the lifetime a pool has.
    val pool = remember {
        PlayerPool.Builder(context)
            // What a feed is: short items, none of them watched to the end.
            .setProfile(PlaybackProfile.SHORT_FORM)
            .build()
    }
    DisposableEffect(pool) {
        onDispose { pool.release() }
    }

    // A pool reports `size` and `inUseCount` as plain values rather than as observable state — it is
    // a playback type, not a UI one — so the screen samples them at the two moments they can change,
    // which are the two moments this file already knows about: a row arriving and a row leaving.
    var summary by remember { mutableStateOf(PoolSummary.of(pool)) }
    val refreshSummary = { summary = PoolSummary.of(pool) }

    // Which row is playing. `firstVisibleItemIndex` rather than anything cleverer: the point is that
    // one row plays, not which one, and a viewer scrolling sees the audio follow them down the list.
    val listState = rememberLazyListState()
    val playingIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val items = remember(rowCount) { FeedItem.all(rowCount) }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(
                R.string.feed_pool_summary,
                summary.inUse,
                summary.built,
                summary.max,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, item -> item.contentId }) { index, item ->
                FeedRow(
                    item = item,
                    pool = pool,
                    isPlaying = index == playingIndex,
                    onPoolChanged = refreshSummary,
                )
            }
        }
    }
}

/**
 * One row: a pooled player if the pool has one to give, and the row's own label if it does not.
 *
 * With a player, the label stays over the surface until the first frame is rendered. That is the
 * production shape — artwork until there is a picture — with a label standing in for the artwork, and
 * it is the one thing this row needs a [Player.Listener] for.
 *
 * The null branch is not an error path. A pool hands out at most what the device can afford, so a
 * feed scrolled quickly will find it empty, and a caller has to have an answer for that. A
 * production feed's answer is the item's artwork; this screen's is a label naming the row, because
 * the demo carries no images and an image loader is a dependency it does not need to make the point.
 * A demo that hid this case would be hiding the pool's actual contract.
 */
@Composable
private fun FeedRow(
    item: FeedItem,
    pool: PlayerPool,
    isPlaying: Boolean,
    onPoolChanged: () -> Unit,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<SuperPlayer?>(null) }
    val playerView = remember { PlayerView(context) }
    var hasFirstFrame by remember { mutableStateOf(false) }

    DisposableEffect(item.contentId) {
        val acquired = pool.acquire()
        player = acquired
        onPoolChanged()

        acquired?.let {
            // Registered and never removed here. Recycling a player removes every listener registered
            // on it, and a consumer of the pool is entitled to rely on that rather than repeat it.
            // It also makes this row the probe for that promise: if recycling kept a listener, one
            // would outlive every row scrolled past, and devicelab's leak hunt would name the growth
            // and the field that holds it (devicelab/leak/README.md).
            it.addListener(
                object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        hasFirstFrame = true
                    }
                },
            )
            it.setMediaRequest(item.toMediaRequest())
            // Prepared but not played. Preparing is what decodes the first frame, so a paused row is
            // a still frame rather than a black rectangle; whether it also *runs* is the effect
            // below, because that changes as the viewer scrolls and this does not.
            it.prepare()
            // Muted: a feed that makes noise as it is scrolled is not a feed, and every real one
            // does this. The pool puts the volume back, so the next item is not silently muted.
            it.volume = 0f
        }

        onDispose {
            // The view first, then the pool. Media3 must stop holding a surface that belongs to a
            // view this row is about to give up; the pool then clears the player's own side of it,
            // which is what stops the next row from seeing this item's last frame.
            playerView.player = null
            player = null
            acquired?.let {
                pool.recycle(it)
                onPoolChanged()
            }
        }
    }

    // Follows the viewport rather than the row's lifetime, so scrolling moves playback between rows
    // that are already prepared. Nothing here is a seek or a reload.
    LaunchedEffect(player, isPlaying) {
        player?.playWhenReady = isPlaying
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .padding(4.dp)
            .background(Color.DarkGray),
        contentAlignment = Alignment.Center,
    ) {
        val current = player
        if (current == null) {
            Text(text = stringResource(R.string.feed_row_placeholder, item.label))
        } else {
            AndroidView(
                factory = { playerView },
                update = { view ->
                    // The assignment the whole facade exists to make ordinary, in a feed this time.
                    view.player = current
                },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize(),
            )
            if (!hasFirstFrame) Text(text = item.label)
        }
    }
}

/**
 * What the header says, sampled off the pool.
 *
 * A value class rather than three states, so that the three numbers cannot be refreshed apart and
 * show a moment that never happened — "3 in use, 2 built".
 */
private data class PoolSummary(val inUse: Int, val built: Int, val max: Int) {
    companion object {
        fun of(pool: PlayerPool) = PoolSummary(pool.inUseCount, pool.size, pool.maxSize)
    }
}

/**
 * A row's content: a label and an identity, over the public streams the demo already has.
 *
 * The items cycle through [DemoStream] rather than naming a hundred URLs, because what the screen is
 * about is *how many players exist*, not what is in them. Each row still gets a content id of its
 * own — a feed where every item were the same content would be a feed where recycling could not go
 * wrong in the way this screen exists to show it does not.
 */
internal class FeedItem(val label: String, val contentId: String, private val stream: DemoStream) {

    fun toMediaRequest(): MediaRequest = MediaRequest.Builder(contentId)
        .addSource(stream.uri)
        .build()

    companion object {
        /**
         * The PRD's 200-item scroll (Phase 4's exit criterion), which is also comfortably past the
         * pool's bound — the only way the counter at the top says anything at all.
         */
        const val DEFAULT_COUNT = 200

        fun all(count: Int): List<FeedItem> = List(count) { index ->
            val stream = DemoStream.entries[index % DemoStream.entries.size]
            FeedItem(
                label = "#${index + 1} · ${stream.name}",
                contentId = "demo:feed-$index",
                stream = stream,
            )
        }
    }
}
