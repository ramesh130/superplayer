package com.superplayer.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Plays public HLS and DASH test streams through SuperPlayer, chosen from a picker.
 *
 * This module resolves SuperPlayer from published Maven coordinates rather than as a source
 * dependency, so what runs here is what an adopter's app would get: if the POM is wrong or a
 * transitive dependency is missing, this build breaks rather than someone else's.
 *
 * Note what the integration does *not* contain. There is no SuperPlayer-specific player view, no
 * adapter, no bridge type — [SuperPlayer] is assigned straight to Media3's own [PlayerView], because
 * it is a `Player`. That is the whole claim of the facade, demonstrated rather than asserted, and
 * Compose changes nothing about it: the assignment is still `view.player = player`.
 *
 * The picker is here for two claims. The first is that nothing below branches on streaming protocol:
 * switching between HLS and DASH is one [MediaRequest] replacing another on the *same* player, so an
 * app that supports both is not an app that has two playback paths in it.
 *
 * The second is resume. Every request asks for
 * [MediaRequest.StartPosition.ResumeFromLastKnown], so switching away from a stream and back returns
 * to where it was left — with no seek-on-ready listener, no position bookkeeping, and no `onReady`
 * callback anywhere in this file. Watch a minute of one, switch, switch back.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From API 35 an app targeting 35+ is laid out edge to edge whether it asks or not. Opting
        // in explicitly makes every API level behave the same way, so the `safeDrawing` inset
        // padding in DemoApp is the one thing keeping the picker out from under the status bar, old
        // devices included.
        // ref: https://developer.android.com/develop/ui/views/layout/edge-to-edge
        enableEdgeToEdge()
        setContent { DemoApp() }
    }
}

/**
 * The whole app: a picker, a status line, and a playback surface.
 *
 * There is no `ViewModel` and no state holder class. What the screen remembers is one enum value;
 * inventing a layer to hold it would say something about SuperPlayer that is not true.
 */
@Composable
private fun DemoApp() {
    val context = LocalContext.current

    // Survives rotation without a declared view id and without `onSaveInstanceState`.
    var selectedStream by rememberSaveable(stateSaver = DemoStreamSaver) {
        mutableStateOf(DemoStream.HLS)
    }
    var player by remember { mutableStateOf<SuperPlayer?>(null) }
    var status by remember { mutableStateOf<Status?>(null) }

    // The surface is remembered here, not created inside [PlayerSurface], because attaching and
    // detaching the player has to happen on the *lifecycle's* schedule and not on a recomposition's.
    // See the release ordering below.
    val playerView = remember { PlayerView(context).apply { showBufferingSpinner() } }

    // Acquire on the activity's START and release on its STOP — not on composition. From API 24
    // onwards an activity can be visible while not resumed (multi-window), so a resume-scoped
    // lifecycle would tear playback down while the user can still see it. `DisposableEffect` is not
    // an equivalent either: it is scoped to the composition, which outlives a stop.
    // ref: https://developer.android.com/media/media3/exoplayer/hello-world#a-note-on-releasing
    LifecycleStartEffect(Unit) {
        val superPlayer = SuperPlayer.Builder(context).build()
        superPlayer.playWhenReady = true
        // No adapter, no wrapper, no `asMedia3Player()`: SuperPlayer *is* a `Player`, so Media3's
        // own view takes it as it is. This is the assignment the whole facade exists to make
        // ordinary, and Compose does not change it.
        playerView.player = superPlayer
        player = superPlayer

        onStopOrDispose {
            // Detach before releasing, and do it here rather than by writing state that some later
            // recomposition would act on. While the window is stopped nothing recomposes, so a
            // state write would leave the view holding a released player until the next start.
            playerView.player = null
            player = null
            superPlayer.release()
        }
    }

    // Loading is a function of which player exists and which stream is selected, so a newly
    // acquired player and a newly picked stream take the same one path. Note what the key list
    // means: `selectedStream` changing does *not* re-run the effect above, so the player — and the
    // surface it is attached to — survives both the protocol change and the resume.
    LaunchedEffect(player, selectedStream) {
        val current = player ?: return@LaunchedEffect
        current.load(selectedStream)
        // Where the request landed, read straight off the player. Media3 applies a new item's start
        // position to the reported state at once, without waiting for the content to load.
        status = Status(selectedStream, startedAtMs = current.currentPosition)
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(color = Color.Black, contentColor = Color.White) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // The Compose counterpart of the old layout's `fitsSystemWindows`: without it
                    // the picker's labels are drawn on top of the clock.
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                // The picker sits above the player rather than inside it: the point of the demo is
                // that changing streaming protocol is an ordinary media-item change on the same
                // player, so the control that does it must plainly be outside the playback surface.
                StreamPicker(
                    selected = selectedStream,
                    onSelect = { selectedStream = it },
                )
                StatusLine(status)
                PlayerSurface(
                    playerView = playerView,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/**
 * The stream picker, built from [DemoStream] rather than declared anywhere, so adding a stream is
 * one entry in one file.
 */
@Composable
private fun StreamPicker(
    selected: DemoStream,
    onSelect: (DemoStream) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            // Announces the row as one set of mutually exclusive options, which is what a
            // `RadioGroup` used to do for accessibility services.
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DemoStream.entries.forEach { stream ->
            val isSelected = stream == selected
            Row(
                modifier = Modifier
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        // The whole row is the target, which is what the old `RadioButton` gave for
                        // free by carrying its own label. `selectable` handles the click, so the
                        // button itself takes none.
                        onClick = { onSelect(stream) },
                    )
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Text(
                    text = stringResource(stream.labelRes),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/**
 * What the player was last asked for. Switching streams and switching back is the demo's
 * resume-from-position exercise, and this says which of the two just happened.
 */
@Composable
private fun StatusLine(status: Status?) {
    val text = when {
        status == null -> ""
        status.startedAtMs <= 0 ->
            stringResource(R.string.status_from_start, stringResource(status.stream.labelRes))

        else -> stringResource(
            R.string.status_resuming,
            stringResource(status.stream.labelRes),
            format(status.startedAtMs),
        )
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp),
    )
}

/**
 * Media3's [PlayerView], through [AndroidView].
 *
 * The route was a choice between this and `androidx.media3:media3-ui-compose`, Media3's own Compose
 * surface. This one wins on the thing the demo exists to prove. `media3-ui-compose` is `@UnstableApi`
 * in its entirety, so taking it would put an opt-in around the player assignment itself — and that
 * assignment is the call site the demo's `UnsafeOptInUsageError` check exists to watch, the one
 * place a leaked `@UnstableApi` SuperPlayer type would show up (see `build.gradle.kts`). An opt-in
 * there is a blindfold over the check. `PlayerView` is stable, it brings the transport controls with
 * it, and the one opt-in the rewrite does need is confined to [showBufferingSpinner], which touches
 * no SuperPlayer type.
 *
 * The interop boundary is the cost, and it is one composable wide — and it is only the *placing* of
 * the view. What plays in it is attached and detached in DemoApp's start effect, so this composable
 * takes an already-configured [playerView] rather than owning one.
 */
@Composable
private fun PlayerSurface(playerView: PlayerView, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { playerView },
        onRelease = { view -> view.player = null },
        modifier = modifier.background(Color.Black),
    )
}

/**
 * The buffering spinner the old layout asked for with `app:show_buffering="when_playing"`.
 *
 * `PlayerView.setShowBuffering` is Media3's own `@UnstableApi`, and this one-line function exists so
 * that the opt-in it needs covers nothing else. That matters: the demo is the only place
 * `UnsafeOptInUsageError` is left on, so an opt-in appearing here has to stay attributable to
 * Media3. Every SuperPlayer call in this file — the builder, `setMediaRequest`, `prepare`, and the
 * `playerView.player = superPlayer` assignment — is outside this function and still checked.
 */
@OptIn(markerClass = [UnstableApi::class])
private fun PlayerView.showBufferingSpinner() {
    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
}

/**
 * Where the player reports it started, read back off the player rather than out of any bookkeeping
 * of the demo's own.
 *
 * That is deliberate, and it is the only way the status line can be trusted. A demo that remembered
 * positions itself would be re-implementing the feature it exists to demonstrate, and would disagree
 * with the player in exactly the interesting cases — content that had played to the end resumes from
 * the start, and a screen keeping its own tally would claim otherwise.
 */
private data class Status(val stream: DemoStream, val startedAtMs: Long)

/**
 * The whole of what protocol support and resume cost a consumer: a request and a `prepare`.
 * There is no branch here, and there is nowhere else in this app that one could hide.
 */
private fun SuperPlayer.load(stream: DemoStream) {
    setMediaRequest(
        MediaRequest.Builder(stream.contentId)
            .addSource(stream.uri)
            .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
            .build(),
    )
    prepare()
}

private fun format(positionMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(positionMs)
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
}

/**
 * Saves the picked stream as the enum's name rather than relying on `Bundle`'s serializable support,
 * so what crosses process death is a string this file can read.
 *
 * `restore` returns null — the supported "could not restore this" answer, which falls back to the
 * initial value — for a name no longer in the enum, rather than throwing at a renamed stream the way
 * `valueOf` would.
 */
private val DemoStreamSaver: Saver<DemoStream, String> = Saver(
    save = { it.name },
    restore = { name -> DemoStream.entries.firstOrNull { it.name == name } },
)
