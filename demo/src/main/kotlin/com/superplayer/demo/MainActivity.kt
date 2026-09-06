package com.superplayer.demo

import android.app.Activity
import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
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
 * it is a `Player`. That is the whole claim of the facade, demonstrated rather than asserted.
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
class MainActivity : Activity() {

    private lateinit var playerView: PlayerView
    private lateinit var streamPicker: RadioGroup
    private lateinit var status: TextView
    private var player: SuperPlayer? = null

    private var selectedStream = DemoStream.HLS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)
        streamPicker = findViewById(R.id.stream_picker)
        status = findViewById(R.id.status)

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
                // to, survive both the protocol change and the resume.
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
        showStatus(stream, startedAtMs = currentPosition)
    }

    /**
     * Reports where the request just landed, read back off the player rather than out of any
     * bookkeeping of the demo's own.
     *
     * That is deliberate, and it is the only way the line can be trusted. A demo that remembered
     * positions itself would be re-implementing the feature it exists to demonstrate, and would
     * disagree with the player in exactly the interesting cases — content that had played to the
     * end resumes from the start, and a screen keeping its own tally would claim otherwise.
     *
     * The position is available immediately because Media3 applies a new item's start position to
     * the player's reported state at once, without waiting for the content to load.
     */
    private fun showStatus(stream: DemoStream, startedAtMs: Long) {
        val label = getString(stream.labelRes)
        status.text = if (startedAtMs <= 0) {
            getString(R.string.status_from_start, label)
        } else {
            getString(R.string.status_resuming, label, format(startedAtMs))
        }
    }

    private fun format(positionMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(positionMs)
        return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    private companion object {
        const val STATE_SELECTED_STREAM = "selectedStream"
    }
}
