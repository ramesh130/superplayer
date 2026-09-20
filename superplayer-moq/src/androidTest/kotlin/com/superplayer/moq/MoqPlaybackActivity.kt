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

package com.superplayer.moq

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.realtime.Realtime

/**
 * A MoQ broadcast on the screen, for a person to look at.
 *
 * ## Why this is here rather than in the demo
 *
 * `demo/` is a separate Gradle build that resolves SuperPlayer from published Maven coordinates,
 * and `superplayer-moq` is deliberately **not published** (`settings.gradle.kts`'s
 * `unpublishedModules`, ADR-0017 rule 1) — so `publishToMavenLocal` produces nothing for it and the
 * demo cannot name it. That is a real consequence of #364's decision rather than an oversight, and
 * it is the first thing #353 has to solve; #369 is where publishing is decided.
 *
 * This activity ships in the **instrumented test APK**, which reaches the module by project
 * dependency and therefore needs nothing published. It exists so that a broadcast can be watched
 * with human eyes on a device today, which `MoqLiveSessionSmokeTest` deliberately cannot do: that
 * test counts frames arriving at a `FrameSink` and **nothing in it decodes**.
 *
 * **It is not the Phase 14 exit criterion and must not be mistaken for it.** `PRD.md` asks for a
 * broadcast playing *in the demo*, and what makes that hard is the publishing question above, not
 * the playback. What this shows is that the frames the smoke counts are frames Media3's own
 * renderers will accept — one step further than the smoke and one step short of #353.
 *
 * ## Running it
 *
 * ```bash
 * adb shell am start -n com.superplayer.moq.test/com.superplayer.moq.MoqPlaybackActivity \
 *     -e broadcast moq://cdn.moq.pro/anon/catshark.hang
 * ```
 *
 * The broadcast defaults to [MoqLiveSessionSmokeTest]'s own. Nothing here is asserted: the activity
 * puts the player's state on screen beside the video so that a black rectangle can be told from a
 * stalled one, which the *Running the demo on an emulator* notes in `CLAUDE.md` call the usual way
 * a screenshot misleads.
 */
class MoqPlaybackActivity : Activity() {

    private var player: SuperPlayer? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val broadcast = intent.getStringExtra("broadcast") ?: DEFAULT_BROADCAST

        val surface = SurfaceView(this)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            textSize = 12f
            text = "opening $broadcast"
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    surface,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(
                    status,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )

        // The one line this whole phase is for: a transport answering a scheme, handed to a player
        // that is otherwise built exactly as any other (ADR-0018 rule 12).
        val built = SuperPlayer.Builder(this)
            .setRealtime(Realtime.transport(MoqFrameSource.SCHEME) { uri -> MoqFrameSource(uri) })
            .build()
        player = built

        built.setVideoSurfaceView(surface)
        built.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) = report(built, "state")

                override fun onIsPlayingChanged(isPlaying: Boolean) = report(built, "isPlaying")

                override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) =
                    report(built, "videoSize ${size.width}x${size.height}")

                override fun onPlayerError(error: PlaybackException) {
                    // The cause is what carries SuperPlayer's own typed error where there is one
                    // (ADR-0011 rule 10), and on this path it is most likely the transport's.
                    Log.e(TAG, "playback failed", error)
                    status.text = "FAILED: ${error.errorCodeName} — ${error.cause?.message ?: error.message}"
                }
            },
        )

        built.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(broadcast).build())
        built.playWhenReady = true
        built.prepare()
    }

    private fun report(player: SuperPlayer, what: String) {
        val stateName = when (player.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "?"
        }
        // The position is what tells a rendered frame from a stalled one, which is the whole of
        // CLAUDE.md's warning about screenshots as evidence.
        val line = "$stateName  pos=${player.currentPosition}ms  playing=${player.isPlaying}  ($what)"
        status.text = line
        Log.i(TAG, line)
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SuperPlayerMoqPlayback"
        const val CONTENT_ID = "moq-live"
        const val DEFAULT_BROADCAST = "moq://cdn.moq.pro/anon/catshark.hang"
    }
}
