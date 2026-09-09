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

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
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
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TrackSelectionPolicy
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
 * The second picker is the policy one. A [PlaybackProfile] is chosen when a player is *built*, so
 * switching it here builds a new player — behind the same session, so the notification and every
 * external controller survive the switch, and carrying the outgoing player's state across as a
 * `PlaybackSnapshot`. That happens in [DemoPlaybackService], which is where the player lives. The
 * profile's effect is on screen twice over: as the numbers it decided, read back off the player, and
 * as the picture itself, since data-saver caps what track selection may choose.
 *
 * The third claim is resume. Every request asks for
 * [MediaRequest.StartPosition.ResumeFromLastKnown], so switching away from a stream and back returns
 * to where it was left — with no seek-on-ready listener, no position bookkeeping, and no `onReady`
 * callback anywhere in this file. Watch a minute of one, switch, switch back.
 *
 * The fourth is that playback outlives the screen. The player is owned by [DemoPlaybackService], a
 * `PlaybackService`, so press home mid-stream and the audio keeps going with a notification whose
 * play, pause and seek controls work — and which says what is playing, because a `MediaRequest`
 * carries that. Come back and the picture is where the sound got to. A rotation is the same story
 * with nothing to save, which is why the `PlaybackSnapshot` this file used to carry is gone: the
 * service holds the player, and the player is not what a configuration change destroys.
 *
 * That last claim has one honest edge, and it is a property of Android's service lifecycle rather
 * than of SuperPlayer. This Activity only *binds*; what makes the service outlive it is Media3
 * promoting it to the foreground when playback starts. Rotate in the window before anything has
 * ever played — a cold start with no network, say — and the last binding goes with the Activity,
 * the service is destroyed, and the player goes with it. An app that wanted playback state to
 * survive even that would be back to saving a snapshot, and this demo deliberately is not, because
 * the state it would be protecting is "nothing has played yet".
 *
 * The fifth claim needs a different screen, and has one. How many players may exist at once is not
 * visible where there is only ever one, so [FeedScreen] is a sixty-item scrolling feed played out of
 * a [com.superplayer.core.PlayerPool] with a live count of how many players it has built. Scroll it:
 * the count stops at the bound the device reported, and the rows past that show artwork.
 *
 * What this Activity does *not* do is worth as much as what it does. It creates no `MediaSession`,
 * builds no notification, creates no channel, calls no `startForeground`, and asks for no audio
 * focus. Six lines of binding is the whole of the integration.
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
 * The whole app: a screen picker, and behind it either one player or a feed of them.
 *
 * There is no `ViewModel` and no state holder class. What the screen remembers is three enum values;
 * the playback state that used to be remembered here belongs to the service, which outlives every
 * rotation this screen can produce, and the feed's players belong to the pool [FeedScreen] owns.
 * Inventing a layer to hold three enums would say something about SuperPlayer that is not true.
 */
@Composable
private fun DemoApp() {
    val context = LocalContext.current

    // Survives rotation without a declared view id and without `onSaveInstanceState`.
    var selectedStream by rememberSaveable(stateSaver = DemoStreamSaver) {
        mutableStateOf(DemoStream.HLS)
    }
    var selectedProfile by rememberSaveable(stateSaver = PlaybackProfileSaver) {
        mutableStateOf(PlaybackProfile.VIDEO_ON_DEMAND)
    }
    var selectedScreen by rememberSaveable(stateSaver = DemoScreenSaver) {
        mutableStateOf(DemoScreen.PLAYER)
    }
    var service by remember { mutableStateOf<DemoPlaybackService?>(null) }
    var player by remember { mutableStateOf<SuperPlayer?>(null) }
    var status by remember { mutableStateOf<Status?>(null) }

    // Without this the service runs, plays, and shows nobody a notification — so a viewer who
    // pressed home has audio they cannot stop from anywhere but the app. Asked for on every start
    // because the system, not this code, decides whether to show the dialog again.
    RequestNotificationPermission()

    // The surface is remembered here, not created inside [PlayerSurface], because attaching and
    // detaching the player has to happen on the *lifecycle's* schedule and not on a recomposition's.
    // See the release ordering below.
    val playerView = remember { PlayerView(context).apply { showBufferingSpinner() } }

    // Bind on the activity's START and unbind on its STOP — not on composition. From API 24 onwards
    // an activity can be visible while not resumed (multi-window), so a resume-scoped lifecycle
    // would tear the connection down while the user can still see the picture. `DisposableEffect` is
    // not an equivalent either: it is scoped to the composition, which outlives a stop.
    //
    // Keyed on nothing, unlike the version of this file that owned the player: the connection has no
    // reason to be rebuilt for a profile change, because the player being replaced is on the other
    // side of it. `BIND_AUTO_CREATE` starts the service the first time; after that the service's own
    // foreground notification is what keeps it alive while this Activity is gone, which is the whole
    // mechanism behind background playback.
    // ref: https://developer.android.com/media/media3/session/background-playback
    LifecycleStartEffect(Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as DemoPlaybackService.LocalBinder).service
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Only reached if the service process dies, which for a same-process bind means the
                // app is going down with it. Cleared anyway so nothing here holds a dead player.
                service = null
                player = null
            }
        }
        context.bindService(
            Intent(context, DemoPlaybackService::class.java)
                .setAction(DemoPlaybackService.ACTION_BIND_LOCAL),
            connection,
            Context.BIND_AUTO_CREATE,
        )

        onStopOrDispose {
            // Detached, not released. The player belongs to the service and carries on playing —
            // that is the point — so all that ends here is this screen's view of it. Done here
            // rather than by writing state a later recomposition would act on: while the window is
            // stopped nothing recomposes, so the view would keep the player until the next start.
            playerView.player = null
            player = null
            service = null
            context.unbindService(connection)
        }
    }

    // The profile picker, applied to whichever player the service currently has. A profile is fixed
    // when a player is built, so switching one builds another — behind the same session, so the
    // notification does not blink and no connected controller notices. The service does that work;
    // this line is the whole of what the UI spends on it.
    LaunchedEffect(service, selectedProfile) {
        player = service?.usingProfile(selectedProfile)
    }

    // No adapter, no wrapper, no `asMedia3Player()`: SuperPlayer *is* a `Player`, so Media3's own
    // view takes it as it is. This is the assignment the whole facade exists to make ordinary, and
    // neither Compose nor a service changes it.
    LaunchedEffect(player) {
        playerView.player = player
    }

    // Loading is a function of which player exists and which stream is selected, so a newly bound
    // service and a newly picked stream take the same one path.
    LaunchedEffect(player, selectedStream) {
        val current = player ?: return@LaunchedEffect
        val boundService = service ?: return@LaunchedEffect

        // Not reloaded if it is already what is playing. That case is the one background playback
        // creates: coming back to the app finds the service still playing the selected stream, and
        // re-requesting it would stall a stream that never stopped. Every other route through here
        // — a picked stream, a rebuilt player after a profile switch — is a genuine load.
        if (current.currentMediaItem?.mediaId != selectedStream.contentId) {
            // The same description the notification and a car head unit get, built once in the
            // service. Two descriptions of one stream is how a notification ends up disagreeing
            // with the screen.
            current.setMediaRequest(boundService.requestFor(selectedStream))
            current.prepare()
        }
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
                // Which of the demo's two claims is on screen. The player screen is about one
                // player doing the right thing; the feed is about how many players exist at all,
                // which is a claim a single-player screen cannot make.
                OptionPicker(
                    options = DemoScreen.entries,
                    selected = selectedScreen,
                    labelRes = { it.labelRes },
                    onSelect = { selectedScreen = it },
                )

                when (selectedScreen) {
                    // The pickers sit above the player rather than inside it: the point of the demo
                    // is that changing streaming protocol is an ordinary media-item change on the
                    // same player, so the control that does it must plainly be outside the playback
                    // surface.
                    DemoScreen.PLAYER -> {
                        OptionPicker(
                            options = DemoStream.entries,
                            selected = selectedStream,
                            labelRes = DemoStream::labelRes,
                            onSelect = { selectedStream = it },
                        )
                        OptionPicker(
                            options = PlaybackProfile.entries,
                            selected = selectedProfile,
                            labelRes = { it.labelRes },
                            onSelect = { selectedProfile = it },
                        )
                        StatusLine(status)
                        PolicyLine(
                            profile = player?.profile,
                            decision = player?.playbackDecision,
                        )
                        PlayerSurface(
                            playerView = playerView,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }

                    // Its own pool, built and released with the screen, and owned by nothing else
                    // here. A feed does not want the service's player: that one is published as a
                    // media session for the notification and the car, and twenty of those would be
                    // twenty notifications. ADR-0007 is where that separation is argued.
                    DemoScreen.FEED -> FeedScreen(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One row of mutually exclusive options, built from whatever list it is given.
 *
 * Generic because the demo now has two of these — the stream and the profile — and they are the same
 * control. A second hand-written picker would be the demo asserting that streams and profiles are
 * different kinds of choice, which they are not: both are one value the screen remembers.
 */
@Composable
private fun <T> OptionPicker(
    options: List<T>,
    selected: T,
    labelRes: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // Four profile labels do not fit across a phone in portrait, and a picker that silently
            // clips its last option is a picker that hides a feature.
            .horizontalScroll(rememberScrollState())
            // Announces the row as one set of mutually exclusive options, which is what a
            // `RadioGroup` used to do for accessibility services.
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Row(
                modifier = Modifier
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        // The whole row is the target, which is what the old `RadioButton` gave for
                        // free by carrying its own label. `selectable` handles the click, so the
                        // button itself takes none.
                        onClick = { onSelect(option) },
                    )
                    .padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Text(
                    text = stringResource(labelRes(option)),
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
 * What the chosen profile actually decided, read off the player rather than out of a table here.
 *
 * This is the difference between a picker that changes something and a picker that is *observable*.
 * The numbers are SuperPlayer's answer for this profile — a data-saver player says so in its buffer
 * durations and in a quality ceiling that is visible in the picture, and a screen that printed its
 * own idea of what data-saver means could say all of that while the player did something else.
 */
@Composable
private fun PolicyLine(profile: PlaybackProfile?, decision: PlaybackDecision?) {
    val text = if (profile == null || decision == null) {
        ""
    } else {
        stringResource(
            R.string.policy_summary,
            stringResource(profile.labelRes),
            TimeUnit.MILLISECONDS.toSeconds(decision.buffer.minBufferMs.toLong()).toInt(),
            TimeUnit.MILLISECONDS.toSeconds(decision.buffer.maxBufferMs.toLong()).toInt(),
            decision.trackSelection.qualityCeiling(),
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
 * The selection ceiling in words, saying only what is actually capped.
 *
 * Two ceilings, and a profile may set either, both or neither — so "uncapped" has to mean both are
 * [TrackSelectionPolicy.UNLIMITED] rather than being assumed from the profile's name.
 */
@Composable
private fun TrackSelectionPolicy.qualityCeiling(): String {
    val cappedHeight = maxVideoHeightPx != TrackSelectionPolicy.UNLIMITED
    val cappedBitrate = maxVideoBitrateBps != TrackSelectionPolicy.UNLIMITED
    val kbps = maxVideoBitrateBps / 1_000

    return when {
        cappedHeight && cappedBitrate ->
            stringResource(R.string.policy_quality_height_and_bitrate, maxVideoHeightPx, kbps)

        cappedHeight -> stringResource(R.string.policy_quality_height, maxVideoHeightPx)

        cappedBitrate -> stringResource(R.string.policy_quality_bitrate, kbps)

        else -> stringResource(R.string.policy_quality_uncapped)
    }
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
 * Media3. Every SuperPlayer call in this app — the builder and `PlaybackService` subclass next door,
 * `setMediaRequest`, `prepare`, and the `playerView.player = player` assignment — is outside this
 * function and still checked. That the session work added no second opt-in is the point: a
 * `MediaSession`, a `SessionToken` and `MediaSessionService` are all stable Media3 API, so nothing
 * SuperPlayer publishes from them needs one.
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
 * Which screen the demo is showing.
 *
 * Two, because SuperPlayer makes two different kinds of promise and they are not visible on the same
 * screen. One player played correctly — profiles, resume, background, a notification — is
 * [DemoScreen.PLAYER]. How many players may exist at once is [DemoScreen.FEED], and the only way to
 * see that is to scroll past the number.
 */
private enum class DemoScreen(val labelRes: Int) {
    PLAYER(R.string.screen_player),
    FEED(R.string.screen_feed),
}

/** [DemoStreamSaver]'s counterpart for the screen, and the same reasoning. */
private val DemoScreenSaver: Saver<DemoScreen, String> = Saver(
    save = { it.name },
    restore = { name -> DemoScreen.entries.firstOrNull { it.name == name } },
)

/** The picker's label for a profile. The library's own names say what the case is; these fit a row. */
private val PlaybackProfile.labelRes: Int
    get() = when (this) {
        PlaybackProfile.VIDEO_ON_DEMAND -> R.string.profile_video_on_demand
        PlaybackProfile.LIVE_LINEAR -> R.string.profile_live_linear
        PlaybackProfile.SHORT_FORM -> R.string.profile_short_form
        PlaybackProfile.DATA_SAVER -> R.string.profile_data_saver
    }

/**
 * Asks for `POST_NOTIFICATIONS`, which is what makes the playback notification appear at all.
 *
 * The library cannot do this: a runtime permission needs an Activity, and a `PlaybackService` is not
 * one. It matters more than a permission prompt usually does — without it the service still plays,
 * still holds the audio, and offers a viewer who has left the app no way to stop it except by coming
 * back, which is the shape of a one-star review.
 *
 * Below API 33 the permission does not exist and the notification appears regardless.
 *
 * ref: https://developer.android.com/develop/ui/views/notifications/notification-permission
 */
@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    // The result is deliberately ignored. There is nothing useful to do with a refusal that this
    // app is entitled to do — playback is what the viewer asked for, and nagging is not an answer.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
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

/** [DemoStreamSaver]'s counterpart for the profile, and the same reasoning. */
private val PlaybackProfileSaver: Saver<PlaybackProfile, String> = Saver(
    save = { it.name },
    restore = { name -> PlaybackProfile.entries.firstOrNull { it.name == name } },
)
