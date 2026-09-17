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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.superplayer.cache.CachePolicy
import com.superplayer.cache.ContentKeyedCache
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.offline.DownloadItem
import com.superplayer.offline.DownloadRefusal
import com.superplayer.offline.DownloadState
import com.superplayer.offline.DownloadStopReason
import com.superplayer.offline.Downloads
import com.superplayer.offline.DownloadsListener
import com.superplayer.resilience.Resilience
import java.io.File

/**
 * The demo's streams as downloads: each row says what its download is doing and what holds it, and
 * downloads, plays or removes it.
 *
 * ## The lifecycle to copy
 *
 * [DemoDownloads] and [DemoDownloadsService] are the parts a consumer's app should look like, and the order
 * matters:
 *
 * 1. **Open the cache**, in a directory the app names and keeps — `filesDir`, not `cacheDir`, which the
 *    system clears — with a budget the app passes (ADR-0010 rule 1). Downloads are pinned and count against it.
 * 2. **Open one store over it, once per process**, on the main thread, with the service named on it
 *    (`setService`) and the same resilience the players have. Once per process because a cache directory
 *    takes one store, and because the service must find the *same* store a screen opened: both ask
 *    [DemoDownloads.open], never a screen alone.
 * 3. **Declare the service** — a [DemoDownloadsService] entry with `foregroundServiceType="dataSync"`, and the
 *    `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS` permissions — in the app's
 *    manifest (ADR-0013 rule 3). The app never starts it: the store does whenever a download can run, and the
 *    scheduled work does in a process with no store open.
 * 4. **Play a download through `setMediaRequest`**, on a player built with the same cache, and the same
 *    `MediaRequest` that was enqueued: the content id is what the bytes are stored under.
 * 5. **Release in reverse**: this screen's player with the screen; then the store, then the cache, and only
 *    when the process has no more use for either. The demo never releases them, because the service and any
 *    screen may ask for the store again at any moment and a process ending is what ends them.
 *
 * ## What a row shows
 *
 * The state and progress are the store's. *What holds* an item is the one of three things that can: a
 * [DownloadStopReason] while a condition — a metered network, a low battery, low storage — holds it or its
 * network is lost; a [DownloadRefusal] for content no download can take, live content; and a failure the store's
 * resilience named. The licence line is for protected content, and the demo's streams are both clear, so it never
 * shows here: a protected download needs `superplayer-drm`, a licence server and `Downloads.Builder.setDrm`, and
 * the demo has no licence server of its own to name.
 *
 * The rows are read again from the store on every change it reports, rather than folded from the reports: a
 * report is one item, and the list is the store's to order.
 */
@Composable
internal fun DownloadsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val opened = remember { DemoDownloads.open(context) }
    var items by remember { mutableStateOf(opened.store.downloads().associateBy { it.contentId }) }

    DisposableEffect(opened) {
        val listener = object : DownloadsListener {
            override fun onDownloadChanged(item: DownloadItem) {
                items = opened.store.downloads().associateBy { it.contentId }
            }

            override fun onDownloadRemoved(contentId: String) {
                items = opened.store.downloads().associateBy { it.contentId }
            }
        }
        opened.store.addListener(listener)
        // Removed on dispose: the store outlives this screen, and a listener left on it would hold the screen.
        onDispose { opened.store.removeListener(listener) }
    }

    // The screen's own player, on the downloads' cache. Built and released with the screen: the service's player
    // is built with no cache, so it would play a download from the network.
    val player = remember {
        SuperPlayer.Builder(context)
            .setCache(opened.cache)
            .setResilience(Resilience.standard())
            .build()
    }
    val playerView = remember { PlayerView(context) }
    DisposableEffect(player) {
        playerView.player = player
        onDispose {
            playerView.player = null
            player.release()
        }
    }

    Column(modifier = modifier) {
        DemoStream.entries.forEach { stream ->
            DownloadRow(
                title = stringResource(stream.titleRes),
                item = items[stream.contentId],
                onDownload = { opened.store.enqueue(requestFor(context, stream)) },
                onPlay = {
                    player.setMediaRequest(requestFor(context, stream))
                    player.prepare()
                    player.play()
                },
                onRemove = { opened.store.remove(stream.contentId) },
            )
        }
        AndroidView(
            factory = { playerView },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

@Composable
private fun DownloadRow(title: String, item: DownloadItem?, onDownload: () -> Unit, onPlay: () -> Unit, onRemove: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = title)
        Text(text = stateText(item))
        item?.percentDownloaded?.let { percent ->
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        heldText(item)?.let { Text(text = it) }
        item?.licence?.let { licence ->
            Text(
                text = stringResource(
                    when {
                        licence.isExpired -> R.string.downloads_licence_expired
                        licence.renewalDue -> R.string.downloads_licence_renewal_due
                        else -> R.string.downloads_licence_valid
                    },
                ),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Enqueued again over what is held fetches only what is missing, so the action stays offered on a
            // stopped or failed item rather than being a second button.
            TextButton(onClick = onDownload, enabled = item?.state != DownloadState.COMPLETED) {
                Text(stringResource(R.string.downloads_action_download))
            }
            TextButton(onClick = onPlay, enabled = item?.state == DownloadState.COMPLETED) {
                Text(stringResource(R.string.downloads_action_play))
            }
            TextButton(onClick = onRemove, enabled = item != null) {
                Text(stringResource(R.string.downloads_action_remove))
            }
        }
    }
}

@Composable
private fun stateText(item: DownloadItem?): String {
    if (item == null) return stringResource(R.string.downloads_state_none)
    val state = stringResource(
        when (item.state) {
            DownloadState.QUEUED -> R.string.downloads_state_queued
            DownloadState.DOWNLOADING -> R.string.downloads_state_downloading
            DownloadState.STOPPED -> R.string.downloads_state_stopped
            DownloadState.COMPLETED -> R.string.downloads_state_completed
            DownloadState.FAILED -> R.string.downloads_state_failed
            DownloadState.REMOVING -> R.string.downloads_state_removing
        },
    )
    return stringResource(R.string.downloads_row_state, state, item.bytesDownloaded / BYTES_PER_MEGABYTE)
}

/** What holds [item], if anything does: a condition, a refusal, or a named failure, in that order of likelihood. */
@Composable
private fun heldText(item: DownloadItem?): String? {
    item ?: return null
    item.stopReason?.let { reason ->
        return stringResource(
            when (reason) {
                DownloadStopReason.NO_UNMETERED_NETWORK -> R.string.downloads_held_no_unmetered_network
                DownloadStopReason.NO_NETWORK -> R.string.downloads_held_no_network
                DownloadStopReason.DATA_SAVER -> R.string.downloads_held_data_saver
                DownloadStopReason.BATTERY_LOW -> R.string.downloads_held_battery_low
                DownloadStopReason.STORAGE_LOW -> R.string.downloads_held_storage_low
                DownloadStopReason.NETWORK_LOST -> R.string.downloads_held_network_lost
            },
        )
    }
    item.refusal?.let { refusal ->
        return stringResource(
            when (refusal) {
                DownloadRefusal.LIVE_CONTENT -> R.string.downloads_refused_live
            },
        )
    }
    // The stable key a translated message would be looked up by; the demo shows the key rather than inventing copy.
    return item.failure?.let { stringResource(R.string.downloads_failed_with, it.userMessageKey) }
}

/** The request a download is enqueued as and played as: one value, so the content id cannot differ between them. */
private fun requestFor(context: Context, stream: DemoStream): MediaRequest = MediaRequest.Builder(stream.contentId)
    .addSource(stream.uri)
    .setTitle(context.getString(stream.titleRes))
    .setSubtitle(context.getString(stream.subtitleRes))
    .build()

/**
 * The process's download store and the cache under it, opened once and never released.
 *
 * An object rather than state a screen remembers, because two things ask for the store — [DownloadsScreen] and
 * [DemoDownloadsService] — and the service may be the first, in a process the scheduled work started with no
 * screen at all. One store per cache directory is the library's constraint, so whichever asks first opens both.
 * Main thread only, as the store is.
 */
internal object DemoDownloads {

    // Its own directory, apart from the feed's cache: a directory holds one cache, and the feed's is in
    // `cacheDir`, which the system may clear under a download.
    private const val DIRECTORY = "downloads"

    private var opened: Opened? = null

    class Opened(val cache: ContentKeyedCache, val store: Downloads)

    fun open(context: Context): Opened = opened ?: run {
        val application = context.applicationContext
        val directory = File(application.filesDir, DIRECTORY)
        val cache = CachePolicy.contentKeyed(directory, CachePolicy.deviceAware(application, directory))
        val store = Downloads.Builder(application, cache)
            // Named so a lost network stops a download rather than failing it, and a failure has a class to show.
            .setResilience(Resilience.standard())
            .setService(DemoDownloadsService::class.java)
            .build()
        Opened(cache, store).also { opened = it }
    }
}

private const val BYTES_PER_MEGABYTE = 1024L * 1024L
