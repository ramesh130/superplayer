package com.superplayer.demo

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Placeholder demo entry point.
 *
 * The library's playback surface does not exist yet — `superplayer-core` currently ships no
 * public API. What this module proves today is the integration path: that SuperPlayer
 * resolves and builds from published Maven coordinates rather than from a source dependency.
 * Real playback of public HLS and DASH streams lands with the facade.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = getString(R.string.app_name)
            }
        )
    }
}
