package com.superplayer.demo

import android.app.Activity
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.media3.common.MediaItem
import androidx.media3.ui.PlayerView
import com.superplayer.core.SuperPlayer

/**
 * Plays public HLS and DASH test streams through SuperPlayer, chosen from a picker.
 *
 * This module resolves SuperPlayer from published Maven coordinates rather than as a source
 * dependency, so what runs here is what an adopter's app would get: if the POM is wrong or a
 * transitive dependency is missing, this build breaks rather than someone else's.
 *
 * Note what the integration does *not* contain. There is no SuperPlayer-specific player view, no
 * adapter, no bridge type — [SuperPlayer] is assigned straight to Media3's own [PlayerView], because
 * it is a `Player`. That is the whole claim of the facade, demonstrated rather than asserted.
 *
 * The picker is here for a second claim: nothing below branches on streaming protocol. Switching
 * between HLS and DASH is [SuperPlayer.setMediaItem] with a different URI on the *same* player, so
 * an app that supports both is not an app that has two playback paths in it.
 */
class MainActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var streamPicker: RadioGroup
    private var player: SuperPlayer? = null

    private var selectedStream = DemoStream.HLS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)
        streamPicker = findViewById(R.id.stream_picker)

        savedInstanceState?.getString(STATE_SELECTED_STREAM)
            ?.let { name -> DemoStream.entries.firstOrNull { it.name == name } }
            ?.let { selectedStream = it }

        // The picker is built from the enum rather than declared in the layout, so adding a stream
        // is one entry in one file — plus a declared id, for the reasons in `res/values/ids.xml`.
        DemoStream.entries.forEach { stream ->
            streamPicker.addView(
                RadioButton(this).apply {
                    id = stream.viewId
                    setText(stream.labelRes)
                    setTextColor(getColor(android.R.color.white))
                },
            )
        }
        streamPicker.check(selectedStream.viewId)

        streamPicker.setOnCheckedChangeListener { _, checkedId ->
            // `checkedId` is -1 when nothing is checked, which is a state this picker never enters
            // but the callback is still entitled to report.
            val stream = DemoStream.entries.firstOrNull { it.viewId == checkedId }
            if (stream != null && stream != selectedStream) {
                selectedStream = stream
                // Deliberately no release-and-rebuild: the player, and the surface it is attached
                // to, survive the protocol change.
                player?.load(stream)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_STREAM, selectedStream.name)
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

        superPlayer.playWhenReady = true
        superPlayer.load(selectedStream)
    }

    override fun onStop() {
        super.onStop()
        playerView.player = null
        player?.release()
        player = null
    }

    /**
     * The whole of what protocol support costs a consumer: a URI and a `prepare`. There is no
     * branch here, and there is nowhere else in this app that one could hide.
     */
    private fun SuperPlayer.load(stream: DemoStream) {
        setMediaItem(MediaItem.fromUri(stream.uri))
        prepare()
    }

    private companion object {
        const val STATE_SELECTED_STREAM = "selectedStream"
    }
}
