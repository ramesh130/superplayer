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

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.moq.MoqFrameSource
import com.superplayer.realtime.Realtime
import com.superplayer.telemetry.LogcatSink
import com.superplayer.telemetry.QoeCollector
import kotlinx.coroutines.delay

/**
 * A live MoQ broadcast, playing through a real `SuperPlayer` on a stock `PlayerView` (#353).
 *
 * This is Phase 14's exit criterion, and what makes it worth having as a *screen* rather than as a
 * test is the thing it demonstrates by being unremarkable: sub-second live over QUIC arrives
 * through the same four calls as an HLS VOD asset, onto a `PlayerView` with no adapter. ADR-0001's
 * whole claim is that SuperPlayer *is* a Media3 `Player`, and a realtime transport is the sharpest
 * test of it — nothing below is realtime-aware except the one builder call that names the scheme.
 *
 * ## The lifecycle to copy
 *
 * 1. **Register the transport on the builder**, once per player:
 *    `setRealtime(Realtime.transport(MoqFrameSource.SCHEME) { uri -> MoqFrameSource(uri) })`. It is
 *    a factory rather than an instance because one is opened per subscription, and it is keyed on
 *    the **URI scheme** (ADR-0018 rule 12) — `TransferChain` dispatches on that and never on a MIME
 *    type, so a player registered for `moq` plays HLS and DASH exactly as it did before.
 * 2. **Adopt the broadcast as content**, through `setMediaRequest` with the `moq://` URI as a
 *    source. It carries a `contentId` like anything else; what it must *not* carry is a
 *    `StartPosition` of `At` or `ResumeFromLastKnown`, which a realtime stream refuses with
 *    `RealtimeStreamNotSeekableException` rather than coercing to the live edge (rule 5). The
 *    default, `Beginning`, is the live edge here because that is the only position that exists.
 * 3. **Prepare and play**, unchanged.
 * 4. **Release with the screen, in reverse.** `player.release()` is what ends the QUIC session:
 *    `MoqFrameSource.cancel` closes the streams and the session and then *joins* every thread it
 *    started, so a released player leaves no pump parked in a blocking `next()`. Detach the view
 *    before releasing, as every other screen here does, so a recycled surface shows no stale frame.
 *
 * ## What is different about this screen, and what is not
 *
 * **A realtime stream never reaches `STATE_ENDED`.** A dynamic period of unknown duration is never
 * final, so a live session ends when the player is released and by no other route — which is why
 * there is no completion state to show and why leaving the screen is the whole of teardown.
 *
 * **Seeking is absent rather than disabled.** `PlayerView`'s controller is switched off below,
 * because a scrub bar on an unseekable timeline invites the one call the library refuses typed.
 *
 * **Telemetry is the same vocabulary, with holes in it.** `QoeCollector` reports rebuffers and
 * startup exactly as elsewhere, and reports **no bandwidth samples and no upstream loss** — the
 * realtime path reaches no `DataSource`, so nothing estimates throughput, and MoQ exports no loss
 * counter at all (ADR-0018 rules 6 and 8). `docs/realtime-path.md` is the table of which layer owns
 * what, and `MoqFrameSource.statistics()` is the transport's own reading, which deliberately
 * reaches no sink.
 *
 * ## The one thing this screen cannot promise
 *
 * The broadcast is somebody else's. [DEFAULT_BROADCAST] is a public relay's anonymous namespace,
 * which is whatever its visitors left running, so a name that played yesterday may be refused
 * today — and MoQ's refusal is `unroutable` for every reason at once: a name that does not exist, a
 * session URL that is wrong, and a request made too soon are one answer. A failure here is
 * therefore reported verbatim rather than interpreted, and `--es com.superplayer.demo.extra.MOQ_BROADCAST`
 * is how another one is named without rebuilding.
 */
@Composable
internal fun MoqScreen(broadcast: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }

    val player = remember(broadcast) {
        SuperPlayer.Builder(context)
            // Step 1: the one line that is about realtime at all.
            .setRealtime(Realtime.transport(MoqFrameSource.SCHEME) { uri -> MoqFrameSource(uri) })
            // One sink, so no `TelemetrySink.composite`: this screen has no second reader of the
            // events. What is worth watching in them here is what is *missing* — no bandwidth
            // sample and no upstream loss — which is ADR-0018 rules 6 and 8 as a readable artifact.
            .setTelemetry(QoeCollector(LogcatSink))
            .build()
    }
    val playerView = remember {
        PlayerView(context).apply {
            // No controller: the timeline is unseekable, so a scrub bar would offer the one
            // operation `RealtimeStreamNotSeekableException` exists to refuse.
            useController = false
        }
    }

    // The position, sampled rather than waited for, because a screenshot cannot tell a rendered
    // frame from a stalled one (`CLAUDE.md`, *Running the demo on an emulator*) and an advancing
    // position can.
    //
    // #353 asks for that advance *across two logged state transitions*, and on this path there are
    // only ever four transitions and they all land in the first moment: a live stream that plays
    // cleanly goes IDLE → BUFFERING → READY → playing and then changes state never again, because a
    // dynamic period of unknown duration is never final and there is no end to reach. `CLAUDE.md`'s
    // recipe reads the position off the platform's media session for the same reason — Media3
    // refreshes it every few seconds while content plays — and this screen publishes no session, so
    // it samples on its own clock instead. Two consecutive lines with a position that moved is the
    // evidence; one line is not.
    LaunchedEffect(player) {
        while (true) {
            delay(POSITION_SAMPLE_MS)
            logState(player, if (player.isPlaying) "PLAYING" else stateName(player.playbackState))
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            // Logged in the same shape as the samples above, so a transition and a sample read
            // alike in one grep.
            override fun onPlaybackStateChanged(state: Int) = logState(player, stateName(state))

            override fun onIsPlayingChanged(isPlaying: Boolean) =
                logState(player, if (isPlaying) "PLAYING" else "PAUSED")

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // The first rendition seen from a public relay was 3520x3520, from a fisheye
                // camera, so the size is shown rather than assumed to be worth hiding.
                status = "${videoSize.width}x${videoSize.height}"
            }

            override fun onPlayerError(error: PlaybackException) {
                // The cause carries SuperPlayer's own typed error where there is one (ADR-0011
                // rule 10); on this path it is most often the transport's, and its message is the
                // relay's own word. Shown unedited, because "unroutable" means four things.
                status = error.cause?.message ?: error.errorCodeName
            }
        }
        player.addListener(listener)
        playerView.player = player
        logState(player, "OPENING")

        // Step 2 and 3. The default StartPosition is Beginning, which on a live stream is the edge.
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(broadcast).build())
        player.playWhenReady = true
        player.prepare()

        // Step 4: in reverse — the view lets go first, then the listener, then the player, whose
        // release is what closes the QUIC session and joins its threads.
        onDispose {
            playerView.player = null
            player.removeListener(listener)
            player.release()
        }
    }

    Column(modifier = modifier) {
        Text(
            text = broadcast,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        status?.let {
            Text(text = it, modifier = Modifier.padding(horizontal = 16.dp))
        }
        AndroidView(
            factory = { playerView },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Text(
            text = stringResource(R.string.moq_live_note),
            modifier = Modifier.padding(16.dp),
        )
    }
}

/**
 * The broadcast this screen opens when none was named.
 *
 * A public relay's anonymous namespace, and the one name that was observed playing there while
 * #367 ran — which is a fact about one afternoon rather than a guarantee. `docs/testing.md`'s
 * *The first real MoQ session* records what that run found, including that a sibling name the
 * browser played was refused from here.
 */
internal const val DEFAULT_BROADCAST: String = "moq://cdn.moq.pro/anon/catshark.hang"

private const val CONTENT_ID = "demo:moq-live"

/**
 * The tag a device run greps for:
 *
 * ```bash
 * adb logcat -c
 * adb shell am start -n com.superplayer.demo/.MainActivity --es com.superplayer.demo.extra.SCREEN MOQ
 * adb logcat -d -t 2000 | grep -o 'SuperPlayerMoqDemo: state=[A-Z]*, position=[0-9]*'
 * ```
 *
 * Bound the dump with `-t`: an unbounded `adb logcat -d` against an emulator that has been up a
 * while reads as a hang rather than as a slow command.
 */
private const val TAG = "SuperPlayerMoqDemo"

/**
 * How often the position is logged while the screen is up.
 *
 * Two seconds: short enough that a thirty-second run leaves a dozen lines to read a trend off, and
 * long enough that the log is not the reason anything is slow. It is a *reporting* interval and
 * bounds nothing — no decision anywhere reads it.
 */
private const val POSITION_SAMPLE_MS = 2_000L

private fun stateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"

    Player.STATE_BUFFERING -> "BUFFERING"

    Player.STATE_READY -> "READY"

    // Unreachable on this path and logged rather than left out: a realtime period is dynamic and of
    // unknown duration, so ExoPlayer never declares it final (ADR-0018). Seeing this line at all
    // would mean the timeline stopped being what the realtime source publishes.
    Player.STATE_ENDED -> "ENDED"

    else -> "UNKNOWN($state)"
}

private fun logState(player: Player, what: String) {
    Log.i(TAG, "state=$what, position=${player.currentPosition}, buffered=${player.bufferedPosition}")
}
