package com.superplayer.demo

import android.app.Activity
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView
import com.superplayer.core.SuperPlayer

/**
 * Plays a public HLS test stream through SuperPlayer.
 *
 * This module resolves SuperPlayer from published Maven coordinates rather than as a source
 * dependency, so what runs here is what an adopter's app would get: if the POM is wrong or a
 * transitive dependency is missing, this build breaks rather than someone else's.
 *
 * Note what the integration does *not* contain. There is no SuperPlayer-specific player view, no
 * adapter, no bridge type — [SuperPlayer] is assigned straight to Media3's own [PlayerView], because
 * it is a `Player`. That is the whole claim of the facade, demonstrated rather than asserted.
 */
class MainActivity : Activity() {

    private lateinit var playerView: PlayerView
    private var player: SuperPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)
    }

    // Acquire in onStart and release in onStop: from API 24 onwards an activity can be visible
    // while not resumed (multi-window), so onResume/onPause would tear playback down while the
    // user can still see it.
    // ref: https://developer.android.com/media/media3/exoplayer/hello-world#a-note-on-releasing
    override fun onStart() {
        super.onStart()
        val superPlayer = SuperPlayer.Builder(this).build()
        player = superPlayer
        playerView.player = superPlayer

        superPlayer.setMediaItem(MediaItem.fromUri(HLS_TEST_STREAM))
        superPlayer.playWhenReady = true
        superPlayer.prepare()
    }

    override fun onStop() {
        super.onStop()
        playerView.player = null
        player?.release()
        player = null
    }

    private companion object {
        /**
         * Apple's public "Advanced stream (HEVC/H.264, fMP4)" HLS example — the reference stream the
         * HLS specification's own authors publish, multi-bitrate with alternate audio and subtitle
         * renditions.
         *
         * ref: https://developer.apple.com/streaming/examples/
         */
        const val HLS_TEST_STREAM =
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/" +
                "img_bipbop_adv_example_fmp4/master.m3u8"
    }
}
