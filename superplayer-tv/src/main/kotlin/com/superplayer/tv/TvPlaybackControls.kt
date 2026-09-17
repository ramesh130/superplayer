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

package com.superplayer.tv

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.tv.material3.IconButton
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.superplayer.core.SuperPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * D-pad playback controls for a television, in Compose for TV (ADR-0014 rule 12).
 *
 * A seek bar above a row of three buttons: back by the player's own seek-back increment, play or pause, and
 * forward by its seek-forward increment. Every one is reached by focus. Play/pause takes focus when the
 * controls first show, Up from the row reaches the seek bar, and Down from the seek bar returns to play/pause.
 *
 * **Scrubbing.** On the focused seek bar, Left and Right move a target rather than the player. The bar and the
 * position shown follow the target, and the player is sought **once**, when the scrub is committed. A scrub is
 * committed a second after the last D-pad release, at once on the centre key, and when the seek bar loses
 * focus or the controls hide. A release alone does not commit, because a viewer who taps Right four times is
 * making one scrub and not four. Holding a direction accelerates on the curve `Scrub` states, and the curve is
 * measured in the time the key has been held, so it runs at the same speed on every remote.
 *
 * On a `SuperPlayer`, a scrub also switches Media3's scrubbing mode on for its duration (ADR-0014 rule 12), so
 * playback is suppressed while the viewer chooses a position. Any other `Player`, a stock `ExoPlayer` included,
 * is scrubbed the same way and simply keeps playing until the seek.
 *
 * **Hiding.** [visible] false composes nothing, and the controls keep which control had focus. When they show
 * again, focus returns there, so a viewer who hid them from the seek bar comes back to the seek bar. Controls
 * that hide on a timer belong to the app, which passes [visible].
 *
 * The controls are the surface's peers, not its owner: the video surface is the app's `SurfaceView`, laid out
 * beneath them (ADR-0014 rule 12).
 *
 * @param player what the controls drive: a `SuperPlayer`, or any other Media3 `Player`.
 * @param visible whether the controls are shown.
 * @param modifier applied to the controls' outermost layout.
 */
@Composable
public fun TvPlaybackControls(player: Player, visible: Boolean, modifier: Modifier = Modifier) {
    val focus = remember { ControlFocus() }
    val reading = rememberPlayerReading(player)

    if (!visible) return

    LaunchedEffect(Unit) { focus.restore() }

    Column(modifier = modifier.fillMaxWidth().padding(ControlsPadding)) {
        SeekBar(
            player = player,
            reading = reading,
            modifier = focus.track(Control.SEEK_BAR)
                .focusProperties { down = focus.requester(Control.PLAY_PAUSE) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonSpacing, Alignment.CenterHorizontally),
        ) {
            TransportButton(
                shape = GlyphShape.BACK,
                description = R.string.superplayer_tv_seek_back,
                enabled = reading.canSeekBack,
                modifier = focus.track(Control.SEEK_BACK),
                onClick = { player.seekBack() },
            )
            val showPlay = reading.showPlay
            TransportButton(
                shape = if (showPlay) GlyphShape.PLAY else GlyphShape.PAUSE,
                description = if (showPlay) R.string.superplayer_tv_play else R.string.superplayer_tv_pause,
                enabled = true,
                modifier = focus.track(Control.PLAY_PAUSE),
                onClick = { Util.handlePlayPauseButtonAction(player) },
            )
            TransportButton(
                shape = GlyphShape.FORWARD,
                description = R.string.superplayer_tv_seek_forward,
                enabled = reading.canSeekForward,
                modifier = focus.track(Control.SEEK_FORWARD),
                onClick = { player.seekForward() },
            )
        }
    }
}

/**
 * One of the row's buttons: Compose for TV's `IconButton`, which brings the focus indication and the centre-key
 * click a remote needs, around a drawn glyph.
 *
 * The glyph's content lambda captures [shape]. That is also what keeps the compiler from hoisting it into a
 * public `ComposableSingletons` class whose generated member names would move the tracked API surface on any
 * edit to this file.
 */
@Composable
private fun TransportButton(shape: GlyphShape, @StringRes description: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(description)
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = label },
    ) { Glyph(shape) }
}

/** The controls a viewer can focus, in the order a first showing prefers them. */
private enum class Control { PLAY_PAUSE, SEEK_BAR, SEEK_BACK, SEEK_FORWARD }

/**
 * Which control had focus, kept across a hide.
 *
 * Remembered by the composable that is composed whether or not the controls are visible, so the requesters and
 * the last focused control outlive the controls' own nodes.
 */
private class ControlFocus {
    private val requesters = Control.entries.associateWith { FocusRequester() }
    private var last = Control.PLAY_PAUSE

    fun requester(control: Control): FocusRequester = requesters.getValue(control)

    fun track(control: Control): Modifier = Modifier
        .focusRequester(requester(control))
        .onFocusChanged { if (it.isFocused) last = control }

    fun restore() {
        requester(last).requestFocus()
    }
}

/** What the controls show of the player, refreshed from its events and, while it plays, on a tick. */
private class PlayerReading(private val player: Player) {
    var positionMs by mutableLongStateOf(0)
    var durationMs by mutableLongStateOf(C.TIME_UNSET)
    var showPlay by mutableStateOf(true)
    var isPlaying by mutableStateOf(false)
    var canSeek by mutableStateOf(false)
    var canSeekBack by mutableStateOf(false)
    var canSeekForward by mutableStateOf(false)

    fun refresh() {
        positionMs = player.currentPosition
        durationMs = player.duration
        showPlay = Util.shouldShowPlayButton(player)
        isPlaying = player.isPlaying
        canSeek = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && player.duration != C.TIME_UNSET
        canSeekBack = player.isCommandAvailable(Player.COMMAND_SEEK_BACK)
        canSeekForward = player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD)
    }
}

@Composable
private fun rememberPlayerReading(player: Player): PlayerReading {
    val reading = remember(player) { PlayerReading(player).apply { refresh() } }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = reading.refresh()
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    // The position moves without an event while content plays, so it is read on a tick then and only then.
    LaunchedEffect(player, reading.isPlaying) {
        while (reading.isPlaying) {
            delay(POSITION_TICK_MS)
            reading.refresh()
        }
    }
    return reading
}

/** The focusable bar Left and Right scrub on. */
@Composable
private fun SeekBar(player: Player, reading: PlayerReading, modifier: Modifier) {
    var scrub by remember { mutableStateOf<Scrub?>(null) }
    // The scrub's target as state, because `Scrub` is plain and a moved target has to recompose the bar.
    var scrubTargetMs by remember { mutableLongStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pendingCommit by remember { mutableStateOf<Job?>(null) }

    fun commit() {
        pendingCommit?.cancel()
        pendingCommit = null
        val ended = scrub ?: return
        scrub = null
        player.seekTo(ended.targetMs)
        scrubbingEngine(player)?.setScrubbingModeEnabled(false)
        reading.refresh()
    }

    // A scrub never outlives the bar: controls hidden mid-scrub commit where the viewer had got to.
    DisposableEffect(player) { onDispose { commit() } }

    val shownMs = if (scrub != null) scrubTargetMs else reading.positionMs
    val durationMs = reading.durationMs
    val label = stringResource(R.string.superplayer_tv_seek_bar)
    val withHours = durationMs != C.TIME_UNSET && durationMs >= MILLIS_PER_HOUR
    val position = "${formatTime(shownMs, withHours)} / ${formatTime(durationMs, withHours)}"
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) commit()
            }
            .onPreviewKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> 0
                }
                when {
                    direction != 0 && event.type == KeyEventType.KeyDown -> {
                        if (!reading.canSeek) return@onPreviewKeyEvent false
                        pendingCommit?.cancel()
                        val active = scrub ?: Scrub(player.currentPosition, reading.durationMs).also {
                            scrubbingEngine(player)?.setScrubbingModeEnabled(true)
                            scrub = it
                        }
                        val native = event.nativeKeyEvent
                        active.move(direction, native.repeatCount > 0, native.eventTime - native.downTime, native.eventTime)
                        scrubTargetMs = active.targetMs
                        true
                    }

                    direction != 0 && event.type == KeyEventType.KeyUp -> {
                        if (scrub == null) return@onPreviewKeyEvent false
                        pendingCommit?.cancel()
                        pendingCommit = scope.launch {
                            delay(SCRUB_COMMIT_DELAY_MS)
                            commit()
                        }
                        true
                    }

                    isCentre(event.key) && scrub != null -> {
                        if (event.type == KeyEventType.KeyUp) commit()
                        true
                    }

                    else -> false
                }
            }
            .focusable()
            .semantics {
                contentDescription = label
                stateDescription = position
            }
            .padding(vertical = BarVerticalPadding),
    ) {
        Text(text = position, color = colors.onSurface, style = MaterialTheme.typography.labelLarge)
        val fraction = if (durationMs > 0) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val barModifier = Modifier.fillMaxWidth().height(if (focused) FocusedBarHeight else BarHeight)
        Canvas(modifier = if (focused) barModifier.border(1.dp, colors.border) else barModifier) {
            drawRect(color = colors.surfaceVariant)
            drawRect(color = colors.primary, size = size.copy(width = size.width * fraction))
        }
    }
}

private fun isCentre(key: Key): Boolean = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * The engine whose scrubbing mode a scrub switches, which is a `SuperPlayer`'s own and nobody else's.
 *
 * ADR-0014 rule 12 puts scrubbing mode behind a `SuperPlayer` and keeps the controls' public face `Player`,
 * which is why this reaches for `exoPlayer` rather than asking the caller for an engine.
 */
private fun scrubbingEngine(player: Player) = (player as? SuperPlayer)?.exoPlayer

/**
 * `m:ss`, or `h:mm:ss` where [withHours], and `--:--` for a time the player does not know. The position takes
 * the duration's form, so the two sides of `0:15:32 / 2:00:00` line up and do not change width mid-scrub.
 */
private fun formatTime(ms: Long, withHours: Boolean): String {
    if (ms == C.TIME_UNSET || ms < 0) return "--:--"
    val totalSeconds = ms / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (withHours || hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private const val MILLIS_PER_HOUR = 3_600_000L

private enum class GlyphShape { PLAY, PAUSE, BACK, FORWARD }

/** The buttons' glyphs, drawn rather than taken from an icon library, which would be a dependency for four shapes. */
@Composable
private fun Glyph(shape: GlyphShape) {
    val color = LocalContentColor.current
    Canvas(modifier = Modifier.size(GlyphSize)) {
        when (shape) {
            GlyphShape.PLAY -> triangle(color, left = 0.2f, right = 0.85f, pointsRight = true)

            GlyphShape.PAUSE -> {
                drawRect(color, topLeft = Offset(size.width * 0.2f, size.height * 0.15f), size = size.copy(width = size.width * 0.2f, height = size.height * 0.7f))
                drawRect(color, topLeft = Offset(size.width * 0.6f, size.height * 0.15f), size = size.copy(width = size.width * 0.2f, height = size.height * 0.7f))
            }

            GlyphShape.BACK -> {
                triangle(color, left = 0.1f, right = 0.5f, pointsRight = false)
                triangle(color, left = 0.5f, right = 0.9f, pointsRight = false)
            }

            GlyphShape.FORWARD -> {
                triangle(color, left = 0.1f, right = 0.5f, pointsRight = true)
                triangle(color, left = 0.5f, right = 0.9f, pointsRight = true)
            }
        }
    }
}

private fun DrawScope.triangle(color: Color, left: Float, right: Float, pointsRight: Boolean) {
    val base = if (pointsRight) left else right
    val tip = if (pointsRight) right else left
    val path = Path().apply {
        moveTo(size.width * base, size.height * 0.15f)
        lineTo(size.width * tip, size.height * 0.5f)
        lineTo(size.width * base, size.height * 0.85f)
        close()
    }
    drawPath(path, color)
}

/**
 * How long after the last D-pad release a scrub commits: a second.
 *
 * Media3's own time bar ends a key-driven scrub on the same timeout. It is long enough that a viewer tapping
 * a direction repeatedly, which remotes make a common way to scrub, stays inside one scrub, and short enough
 * that a viewer who stops sees playback resume from the new position without asking for it.
 */
// ref: androidx.media3.ui.DefaultTimeBar, STOP_SCRUBBING_TIMEOUT_MS (Media3 1.11, Apache-2.0)
internal const val SCRUB_COMMIT_DELAY_MS: Long = 1_000

/**
 * How often the position shown is read while content plays: every half second.
 *
 * The position is shown to the second, so a half-second read keeps it at most half a second behind, and a
 * position that moves only while playing is not read at all while paused.
 */
private const val POSITION_TICK_MS: Long = 500

// Layout. Television-sized, read from across a room: the overscan margin Android TV's design guidance asks
// for around the screen's edge is the app's, because the app places the controls.
private val ControlsPadding = 16.dp
private val ButtonSpacing = 24.dp
private val BarVerticalPadding = 8.dp
private val BarHeight = 4.dp
private val FocusedBarHeight = 8.dp
private val GlyphSize = 24.dp
