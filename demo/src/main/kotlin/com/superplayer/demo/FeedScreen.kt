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

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.compose.runtime.mutableStateMapOf
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
import com.superplayer.cache.CachePolicy
import com.superplayer.cache.ContentKeyedCache
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlayerPool
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.core.TtffStartBoundary
import com.superplayer.preload.PreloadCoordinator
import com.superplayer.telemetry.QoeCollector
import kotlinx.coroutines.delay
import java.io.File

/**
 * A long short-form feed, built the way a consumer's should be: one [PlayerPool], a
 * [PreloadCoordinator] attached to it, a [ContentKeyedCache] under both, and a player only on the row
 * being watched.
 *
 * The header says what each piece is doing while the viewer scrolls — players in use and built against
 * the device's bound, decoders held warm ahead of the scroll, and requests answered from the cache —
 * and every row that has played says how long its first frame took, from the moment it became the row
 * being watched.
 *
 * ## The lifecycle to copy
 *
 * [FeedPlayback] is the part of this file a consumer's feed should look like, and its order matters:
 *
 * 1. **Open the cache**, in a directory the app names, with a budget the app passes
 *    (ADR-0010 rule 1). [CachePolicy.deviceAware] suggests one from the device.
 * 2. **Build the pool** on that cache and a collector per player. The pool's players are the only ones
 *    the feed uses, so they are the ones measured.
 * 3. **Attach the coordinator before the pool builds its first player.** From then on every player the
 *    pool builds shares one engine with the coordinator, which is what lets a prefetched row be played
 *    rather than fetched again.
 * 4. **Hand the coordinator the feed** ([PreloadCoordinator.setItems]) as soon as it is known, and the
 *    same `MediaRequest`s the rows will play: a row is warm only for the content it was prefetched as.
 * 5. **Release in reverse**: the coordinator, then the pool, then the cache — a cache released under a
 *    player still loading through it fails that player's loads.
 *
 * ## One row plays, and the rest show their label
 *
 * Every other row is the stand-in for an item's artwork: one cached image rather than a manifest, a
 * segment and a decoder spent on a row the viewer may scroll straight past. What they would have spent,
 * the coordinator spends instead, and only ahead of where the feed is going, within what the pool and
 * the device's memory allow. That is also why the demo only plays one row: every SuperPlayer requests
 * audio focus (ADR-0006 rule 1), so a second playing row would stop the first.
 *
 * When a row becomes the one being watched, [FeedRow] does four things in an order that is not
 * incidental. It tells the coordinator where the feed is *before* asking for a player, so the pool can
 * hand it the player holding that row warm. It declares playback intent before setting content, so
 * time to first frame is measured from the row becoming current — the frame in which this effect runs,
 * a frame after the scroll that caused it — rather than from whenever the player adopted the item. It sets content through `setMediaRequest`, the only path a warm source is
 * handed over on. And it plays.
 *
 * ## What the numbers can and cannot show
 *
 * A row's first frame is a [TelemetryEvent.FirstFrameRendered], delivered to the collector's sink on
 * telemetry's own thread and posted here to the main one. A row that shows "from adoption" had no
 * declared intent, and its number is not comparable with the others.
 *
 * The cache's count is every media request answered at least partly from disk, by any player in the
 * pool. It rises on a first scroll as well as on a replay, because what the coordinator prefetched was
 * stored on the way in and a row that plays it reads it back. The count alone does not say which of
 * those a hit was.
 *
 * The header is sampled twice a second rather than observed. A pool, a coordinator and a cache are
 * playback types, not UI ones, and their counts change on threads Compose does not watch.
 *
 * ## Where the recycling actually happens
 *
 * [FeedRow]'s [DisposableEffect], keyed on whether the row is the one being watched, is the whole of a
 * row's player lifecycle: acquire when it becomes current, recycle when it stops. That is the same shape
 * a `RecyclerView` feed writes on its snap-to-item callback. Nothing restores the volume the row muted
 * or removes the listener it registered; [PlayerPool.recycle] does, which is the point of it being in
 * the library.
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
    val items = remember(rowCount) { FeedItem.all(rowCount) }

    // Per row, keyed by content id, the last first frame telemetry reported for it.
    val firstFrames = remember { mutableStateMapOf<String, FirstFrame>() }

    // Built with the screen and released with it. A production app with more than one feed screen
    // would hold the cache at application scope instead, since it outlives any one screen.
    // The feed is handed over as the playback types are built, not in an effect: an effect runs after the
    // first rows have composed, and row 0 would ask for a player before the coordinator knew any row.
    val feed = remember(items) {
        FeedPlayback(context.applicationContext, items.map { it.request }) { contentId, frame ->
            firstFrames[contentId] = frame
        }
    }
    DisposableEffect(feed) {
        onDispose { feed.release() }
    }

    var summary by remember { mutableStateOf(FeedSummary.of(feed)) }
    LaunchedEffect(feed) {
        while (true) {
            summary = FeedSummary.of(feed)
            delay(SUMMARY_REFRESH_MS)
        }
    }

    // The row being watched is the first one visible: the feed snaps nothing, so this is the row at
    // the top, and a viewer scrolling sees the playing row follow them down the list.
    val listState = rememberLazyListState()
    val currentIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(
                R.string.feed_summary,
                summary.inUse,
                summary.built,
                summary.max,
                summary.warm,
                summary.cacheHits,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(items, key = { _, item -> item.contentId }) { index, item ->
                FeedRow(
                    index = index,
                    item = item,
                    feed = feed,
                    isCurrent = index == currentIndex,
                    firstFrame = firstFrames[item.contentId],
                )
            }
        }
    }
}

/**
 * The cache, the pool and the coordinator a feed plays through, opened and released in the order
 * [FeedScreen]'s KDoc gives.
 *
 * [onFirstFrame] is called on the main thread with the row's content id.
 */
private class FeedPlayback(
    context: Context,
    requests: List<MediaRequest>,
    onFirstFrame: (String, FirstFrame) -> Unit,
) {

    private val mainThread = Handler(Looper.getMainLooper())

    val cache: ContentKeyedCache = File(context.cacheDir, CACHE_DIRECTORY).let { directory ->
        directory.mkdirs()
        CachePolicy.contentKeyed(directory, CachePolicy.deviceAware(context, directory))
    }

    // Called on telemetry's delivery thread, never the main one (ADR-0008 rule 4), so the one event
    // this screen shows is handed across rather than written into Compose state from there.
    private val sink = TelemetrySink { event ->
        if (event is TelemetryEvent.FirstFrameRendered) {
            val frame = FirstFrame(
                ms = event.timeToFirstFrameMs,
                fromIntent = event.startBoundary == TtffStartBoundary.USER_INTENT,
            )
            mainThread.post { onFirstFrame(event.contentId, frame) }
        }
    }

    val pool: PlayerPool = PlayerPool.Builder(context)
        // What a feed is: short items, none of them watched to the end. It is also the profile that
        // decides warm decoders ahead of the scroll.
        .setProfile(PlaybackProfile.SHORT_FORM)
        .setCache(cache)
        // A collector per player, because a collector measures one.
        .setTelemetry { QoeCollector(sink) }
        .build()

    // Immediately, before any row has asked the pool for a player (step 3 above).
    val preload: PreloadCoordinator = PreloadCoordinator.Builder(pool).build().apply { setItems(requests) }

    fun release() {
        preload.release()
        pool.release()
        cache.release()
    }
}

/**
 * One row: the player if it is the row being watched and the pool had one to give, and its label
 * otherwise, with the row's last time to first frame under either.
 *
 * The label stays over the surface until the first frame is rendered — artwork until there is a
 * picture — which is the one thing this row needs a [Player.Listener] for.
 *
 * A current row with no player is not an error path: a pool hands out at most what the device can
 * afford, and a caller has to have an answer for it. Here the answer is the label, saying so.
 */
@Composable
private fun FeedRow(
    index: Int,
    item: FeedItem,
    feed: FeedPlayback,
    isCurrent: Boolean,
    firstFrame: FirstFrame?,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<SuperPlayer?>(null) }
    // No transport controls: a feed row is played by scrolling to it, not by a button over the picture.
    val playerView = remember { PlayerView(context).apply { useController = false } }
    var hasFirstFrame by remember { mutableStateOf(false) }

    DisposableEffect(item.contentId, isCurrent) {
        // When the watched row moves, the row it left and the row it reached change in one frame, and
        // Compose disposes the old effect before it runs the new one — so the previous player is back
        // in the pool before this row asks for one, even on a device that affords a single player.
        val acquired = if (isCurrent) {
            // Where the feed is, first: the pool then prefers the player holding this row warm.
            feed.preload.setScrollPosition(index)
            feed.pool.acquire()
        } else {
            null
        }
        player = acquired
        hasFirstFrame = false

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
            // Muted: a feed that makes noise as it is scrolled is not a feed. The pool puts the volume
            // back, so the next item is not silently muted.
            it.volume = 0f
            // The viewer's arrival is the start of time to first frame, so it is declared before the
            // content is: a player that adopts content before intent measures from adoption instead.
            it.declarePlaybackIntent()
            // The one path a warm source is handed over on: a prefetched row keeps what was prepared.
            it.setMediaRequest(item.request)
            it.prepare()
            it.playWhenReady = true
        }

        onDispose {
            // The view first, then the pool. Media3 must stop holding a surface that belongs to a
            // view this row is about to give up; the pool then clears the player's own side of it,
            // which is what stops the next row from seeing this item's last frame.
            playerView.player = null
            player = null
            acquired?.let { feed.pool.recycle(it) }
        }
    }

    // The watched row's playhead, one greppable line a second. A pooled player publishes no session, so
    // this is what shows it advancing on a device, where a screenshot cannot tell a frame from a stall:
    //   adb logcat -d -t 2000 -s SuperPlayerFeed
    LaunchedEffect(player) {
        val playing = player ?: return@LaunchedEffect
        while (true) {
            Log.i(
                FEED_LOG_TAG,
                "row=$index contentId=${item.contentId} positionMs=${playing.currentPosition} " +
                    "state=${playing.playbackState} playWhenReady=${playing.playWhenReady} " +
                    "isPlaying=${playing.isPlaying} bufferedMs=${playing.bufferedPosition}",
            )
            delay(POSITION_LOG_INTERVAL_MS)
        }
    }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .padding(4.dp)
                .background(Color.DarkGray),
            contentAlignment = Alignment.Center,
        ) {
            val current = player
            when {
                current != null -> {
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

                isCurrent -> Text(text = stringResource(R.string.feed_row_placeholder, item.label))

                else -> Text(text = item.label)
            }
        }
        firstFrame?.let {
            Text(
                text = stringResource(
                    if (it.fromIntent) R.string.feed_row_first_frame else R.string.feed_row_first_frame_from_adoption,
                    it.ms,
                ),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

/** A row's time to first frame, and whether it was measured from declared intent. */
private data class FirstFrame(val ms: Long, val fromIntent: Boolean)

/**
 * What the header says, sampled off the feed's playback types.
 *
 * One value rather than five states, so that the numbers cannot be refreshed apart and show a moment
 * that never happened — "3 in use, 2 built".
 */
private data class FeedSummary(val inUse: Int, val built: Int, val max: Int, val warm: Int, val cacheHits: Long) {
    companion object {
        fun of(feed: FeedPlayback) = FeedSummary(
            inUse = feed.pool.inUseCount,
            built = feed.pool.size,
            max = feed.pool.maxSize,
            warm = feed.preload.warmDecoderCount,
            cacheHits = feed.cache.hitCount,
        )
    }
}

/**
 * A row's content: a label and an identity, over the public streams the demo already has.
 *
 * The items cycle through [DemoStream] rather than naming two hundred URLs, because what the screen is
 * about is how rows start, not what is in them. Each row still gets a content id of its own — the cache
 * is keyed by it, so a feed where every item were the same content would be one long cache hit.
 */
internal class FeedItem(val label: String, val contentId: String, stream: DemoStream) {

    /** Built once, so the coordinator is handed the same request the row plays. */
    val request: MediaRequest = MediaRequest.Builder(contentId)
        .addSource(stream.uri)
        .build()

    companion object {
        /**
         * The PRD's 200-item scroll (Phase 4's exit criterion), which is also comfortably past the
         * pool's bound.
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

/** Under the app's cache directory, which the platform may clear under storage pressure; so may this. */
private const val CACHE_DIRECTORY = "feed-media"

private const val SUMMARY_REFRESH_MS = 500L

private const val FEED_LOG_TAG = "SuperPlayerFeed"

private const val POSITION_LOG_INTERVAL_MS = 1_000L
