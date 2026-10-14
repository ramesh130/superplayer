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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.superplayer.abr.AdaptivePolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetrySink
import com.superplayer.diagnostics.DebugHud
import com.superplayer.diagnostics.DebugHudTelemetry
import com.superplayer.diagnostics.isDebugHudAvailable
import com.superplayer.telemetry.LogcatSink
import com.superplayer.telemetry.QoeCollector

/**
 * The debug HUD over a real stream: what a developer watches while the thing is playing in front of
 * them (#294).
 *
 * ## The lifecycle to copy
 *
 * Four steps, and the order is the whole recipe:
 *
 * 1. **Build the HUD's sink once per player**, and remember it beside the player rather than inside
 *    the composable that draws the HUD: it has to exist before the player does, because it is handed
 *    to the collector the player is built with.
 * 2. **Compose it into the app's own sink** with `TelemetrySink.composite(...)`, and hand the
 *    composite to **one** `QoeCollector`. That is the point of the HUD being a sink: the app's
 *    pipeline and the overlay read the *same* derivation of the same numbers, so a figure on the
 *    screen is a figure the warehouse has. A second collector would be a second derivation, and the
 *    two would disagree by the time anyone noticed.
 * 3. **Place the HUD over the surface**, in a `Box` above the `PlayerView`, with the app's own
 *    alignment and padding — the library chooses neither, because the app owns the screen.
 * 4. **Release the player with the screen.** The HUD holds no resource of its own: it registers a
 *    `Player.Listener` for as long as it is composed and removes it on dispose, and the sink is a
 *    value the collector already owns.
 *
 * **Nothing guards the call site here, and nothing needs to.** [DebugHud] renders nothing unless the
 * application is debuggable, so this screen's overlay is absent from a release build of the demo
 * while the same source file ships in it. An app that must ship no HUD *code* at all puts its call
 * site in a `debug` source set instead, which is the only way to guarantee that and is the app's
 * choice rather than the library's; `isDebugHudAvailable` is the same reading, offered so an app's
 * own debug affordance can ask it too — this screen uses it to say why the overlay is missing rather
 * than showing an empty rectangle.
 *
 * The player is built with `AdaptivePolicy`, which is not decoration: `superplayer-abr`'s meter
 * samples every transfer, so the estimate row fills in from the first segment. A player on a static
 * profile uses Media3's own meter, which reports on its own thresholds, and the row reads
 * `unavailable` until one is crossed — which is a true reading of such a player and a confusing first
 * impression of the HUD.
 */
@Composable
internal fun DebugHudScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var stream by remember { mutableStateOf(DemoStream.HLS) }

    // Step 1: the HUD's sink, built before the player and remembered with it.
    val hudTelemetry = remember { DebugHudTelemetry() }

    val player = remember {
        SuperPlayer.Builder(context)
            .setProfile(PlaybackProfile.VIDEO_ON_DEMAND)
            .setPolicy(AdaptivePolicy.forProfile(context, PlaybackProfile.VIDEO_ON_DEMAND))
            // Step 2: one collector, over the app's sink and the HUD's together.
            .setTelemetry(QoeCollector(TelemetrySink.composite(LogcatSink, hudTelemetry)))
            .build()
    }
    val playerView = remember { PlayerView(context) }
    DisposableEffect(player) {
        playerView.player = player
        // Step 4: the player is released with the screen, and the HUD needs no undoing.
        onDispose {
            playerView.player = null
            player.release()
        }
    }

    Column(modifier = modifier) {
        OptionPicker(
            options = DemoStream.entries,
            selected = stream,
            labelRes = DemoStream::labelRes,
            onSelect = {
                stream = it
                player.setMediaRequest(it.request(context))
                player.prepare()
                player.play()
            },
        )
        if (!isDebugHudAvailable(context)) {
            Text(
                text = stringResource(R.string.hud_release_build),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize())
            // Step 3: the overlay over the surface, placed and padded by this app. Top-left, because
            // that is the corner a phone's own system UI leaves alone.
            DebugHud(
                player = player,
                telemetry = hudTelemetry,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
            )
        }
        // Nothing starts playing on its own: a stream picked above is what adopts content, so the
        // HUD's first reading is of a player that has taken nothing on, which is worth seeing once.
        TextButton(onClick = { player.playWhenReady = !player.playWhenReady }) {
            Text(stringResource(R.string.hud_action_play_pause))
        }
    }
}
