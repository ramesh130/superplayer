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

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.superplayer.abr.AdaptivePolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlaybackSession
import com.superplayer.core.PlaybackSnapshot
import com.superplayer.core.SuperPlayer
import com.superplayer.resilience.Resilience
import com.superplayer.tv.TvOutput
import com.superplayer.tv.TvPlaybackControls
import kotlinx.coroutines.delay

/**
 * One stream, full screen, on a television: the picture on a `SurfaceView`, and D-pad controls over it.
 *
 * It is what the demo opens on a TV (see [MainActivity]), and it has no picker, because a TV's player is the
 * whole screen and a row of options above the picture is a phone's layout. The phone's screens are still there
 * on a TV, by the launch argument `DemoLaunch` documents.
 *
 * ## The lifecycle to copy
 *
 * [TvScreen] is the part a consumer's TV player should look like, and the order matters:
 *
 * 1. **Build the player on the activity's start**, with the profile, a policy for it, and the output:
 *    `setProfile(TV_LEANBACK)`, `setPolicy(AdaptivePolicy.forProfile(context, TV_LEANBACK))` and
 *    `setOutput(TvOutput.standard(context))`. The output is what matches the display's refresh rate to the
 *    content and re-selects when an HDMI display or an AV receiver changes (ADR-0014 rules 4 to 6), and the
 *    adaptive policy is what refuses an HDR rendition the display cannot show when it does (rule 10). The
 *    profile turns tunneling on where the device supports it (rule 7).
 * 2. **Publish it as a session**, with an id of its own. A remote's play and pause keys, and the platform's
 *    "now playing", reach a TV app through its media session, and the id keeps it apart from the one
 *    [DemoPlaybackService] publishes in the same process: Media3 refuses a second session sharing an id.
 * 3. **Show it on a `SurfaceView`**, handed to the player with `setVideoSurfaceView`. Not a `TextureView`,
 *    which the app composites itself, so no frame-rate request, tunneled stream or secure buffer reaches
 *    the display (ADR-0014 rule 12). The view is remembered with the screen, so the surface outlives a
 *    stop that only the player does not.
 * 4. **Put [TvPlaybackControls] over it**, passing whether they are shown. Hidden, they keep the control that
 *    had focus. Shown again, focus returns there, so the controls stay composed and only `visible` changes.
 * 5. **Release in reverse on the activity's stop**: take a [PlaybackSnapshot], detach the surface, then
 *    release the session, which releases the player after it. The session goes first because a session
 *    holding a released player crashes on the next remote key. The snapshot is saved with the screen, so
 *    coming back from Home, or from the process being killed, plays on from where it was.
 *
 * ## What it does not do
 *
 * It sets no `FLAG_SECURE`. That flag is the app's to set on the window of an activity showing protected
 * content, and it blanks every screenshot of the window, the app's own UI included (ADR-0014 rule 13). The
 * demo's streams are clear, so the flag would protect nothing and cost the screenshots devicelab takes.
 *
 * It keeps no service. Playback here ends with the activity, which is what a viewer pressing Home on a TV
 * expects. Background playback is the phone screen's claim, and [DemoPlaybackService] makes it.
 */
@Composable
internal fun TvScreen(stream: DemoStream, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val surfaceView = remember { SurfaceView(context) }
    var snapshot by rememberSaveable(stateSaver = PlaybackSnapshotSaver) { mutableStateOf<PlaybackSnapshot?>(null) }
    var player by remember { mutableStateOf<SuperPlayer?>(null) }
    var videoAspectRatio by remember { mutableStateOf<Float?>(null) }

    // Keyed on nothing: the stream is the launch's, fixed for this screen's life.
    LifecycleStartEffect(Unit) {
        val started = SuperPlayer.Builder(context)
            .setProfile(PlaybackProfile.TV_LEANBACK)
            .setPolicy(AdaptivePolicy.forProfile(context, PlaybackProfile.TV_LEANBACK))
            .setOutput(TvOutput.standard(context))
            .setResilience(Resilience.standard())
            .build()
        val session = PlaybackSession.Builder(context, started).setId(TV_SESSION_ID).build()
        started.setVideoSurfaceView(surfaceView)

        // The snapshot is what resumes: this player is new, so it remembers no position of its own. It is used only
        // where it names this stream, since a snapshot whose request did not survive the bundle would restore
        // the intent to play and nothing to play.
        val restored = snapshot?.takeIf { it.request?.contentId == stream.contentId }
        if (restored != null) {
            // Carries playWhenReady too, so a viewer who paused and pressed Home comes back paused.
            started.restoreSnapshot(restored)
        } else {
            started.setMediaRequest(stream.request(context))
            started.playWhenReady = true
        }
        started.prepare()
        player = started

        onStopOrDispose {
            snapshot = started.saveSnapshot()
            player = null
            started.clearVideoSurfaceView(surfaceView)
            session.release()
        }
    }

    // The picture keeps the content's shape. A `SurfaceView` stretches whatever it is given to its own bounds,
    // and a film at 2.4:1 on a 16:9 panel would otherwise fill the height and be squashed.
    DisposableEffect(player) {
        val current = player ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoAspectRatio = videoSize.aspectRatio()
            }
        }
        current.addListener(listener)
        // A size reported before this effect ran would otherwise wait for the next change.
        videoAspectRatio = current.videoSize.aspectRatio()
        onDispose { current.removeListener(listener) }
    }

    var controlsVisible by remember { mutableStateOf(true) }
    // Every key press while the controls show, so the hide timer below starts again from the last one.
    var presses by remember { mutableIntStateOf(0) }
    val screenFocus = remember { FocusRequester() }

    LaunchedEffect(controlsVisible, presses) {
        if (!controlsVisible) return@LaunchedEffect
        delay(CONTROLS_HIDE_AFTER_MS)
        controlsVisible = false
    }
    // Hidden controls take their focused node with them, and a window with nothing focused hears no D-pad. The
    // screen itself takes focus instead, so the next press can bring them back.
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) screenFocus.requestFocus()
    }
    // Back hides the controls first, and leaves the app only once they are hidden, as a TV player's Back does.
    BackHandler(enabled = controlsVisible) { controlsVisible = false }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    controlsVisible -> {
                        presses++
                        false
                    }

                    // Only the D-pad shows the controls, and the press that shows them does nothing else: a
                    // viewer pressing centre to see where they are has not asked to pause. Media keys are not
                    // consumed, so a remote's play and pause still reach the session.
                    event.key in DPAD_KEYS -> {
                        controlsVisible = true
                        true
                    }

                    else -> false
                }
            }
            .focusRequester(screenFocus)
            .focusable(),
    ) {
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier
                .align(Alignment.Center)
                .then(videoAspectRatio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize()),
        )
        player?.let { current ->
            TvPlaybackControls(
                player = current,
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Whether the demo is running on a television, which is what it opens [TvScreen] for.
 *
 * The UI mode rather than the leanback feature devicelab reads, because an app decides its layout by the mode it is
 * shown in. The two agree on a TV, and devicelab reads the feature only because it asks from a shell, where there
 * is no configuration to read.
 */
internal fun Context.isTelevision(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
        Configuration.UI_MODE_TYPE_TELEVISION

/**
 * The stream a TV opens on when it was launched naming none: [DemoStream.DASH], because it is film. Its manifest
 * declares 24 frames a second, which is the content frame-rate matching exists for, where the HLS stream's 30 and
 * 60 are rates a TV panel already refreshes at.
 */
internal val TV_DEFAULT_STREAM = DemoStream.DASH

/** The shape the picture is shown at, pixel aspect ratio included, or null before the first frame's size is known. */
private fun VideoSize.aspectRatio(): Float? =
    if (width == 0 || height == 0) null else width * pixelWidthHeightRatio / height

/**
 * Five seconds after the last press: long enough to read the position and reach for the next key, including the
 * second a scrub waits before it commits, and short enough that the controls do not sit over a whole scene.
 */
private const val CONTROLS_HIDE_AFTER_MS = 5_000L

/**
 * Apart from the default id [DemoPlaybackService]'s session has. A controller can start that service while this
 * screen plays, and two sessions sharing an id in one process is the failure `PlaybackSession.Builder.setId`
 * exists for.
 */
private const val TV_SESSION_ID = "tv"

/**
 * A remote's navigation keys, the ones that show hidden controls: its four directions and its centre, which some
 * remotes and an emulator's keyboard send as Enter. Every other key is left to whatever it means, media keys
 * above all.
 */
private val DPAD_KEYS = setOf(
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionRight,
    Key.DirectionCenter,
    Key.Enter,
    Key.NumPadEnter,
)

/** The snapshot as the `Bundle` it defines, so it crosses process death as the library says it may. */
private val PlaybackSnapshotSaver: Saver<PlaybackSnapshot?, Bundle> = Saver(
    save = { it?.toBundle() },
    restore = { PlaybackSnapshot.fromBundle(it) },
)
