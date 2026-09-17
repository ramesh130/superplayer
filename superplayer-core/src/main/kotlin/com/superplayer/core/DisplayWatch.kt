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

package com.superplayer.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection

/**
 * One player's watch on the display it is shown on: ADR-0014 rule 5, surviving a display change.
 *
 * Built only on a player whose `EngineConfiguration.videoOutput` is filled — the filled slot is the
 * whole signal (rule 3) — so a player without `superplayer-tv` registers no `DisplayListener` and holds
 * no instance of this (rule 14).
 *
 * When the default display reports a [DisplayCapability] that differs from the one in force, in this
 * order:
 * 1. The reading is written to [DisplayInForce], which the selection gate reads on every evaluation.
 * 2. The selection is invalidated through [ReselectingTrackSelector], so tracks are selected again
 *    under the new display. That one call is both of rule 5's middle steps, as Media3 1.11 stands:
 *    the parameters no longer hold a viewport derived from the display at construction — they hold
 *    `isViewportSizeLimitedByPhysicalDisplaySize`, and `DefaultTrackSelector` reads the display's mode
 *    size afresh on every video selection — so there is no stored reading to rewrite, and a re-selection
 *    is what makes Media3's own viewport bound read the new display. A viewport the consumer set is
 *    untouched, as the rule requires, because nothing here writes the parameters at all (rule 5's
 *    #269 addendum).
 * 3. The output is told, so the binding asks the new display for the content's frame rate afresh.
 *
 * **A change of refresh rate alone is not a change**, because [DisplayCapability] carries no rate: the
 * binding's own frame-rate request changes the active mode's rate, and re-selecting on it would loop.
 *
 * **A removed display is not a reading.** An HDMI sink unplugged leaves nothing to show on, and treating
 * it as *unknown* would lift every refusal only for the next sink to bring them back. The capability in
 * force is held until a display is added again, which is read as any change is.
 *
 * What re-selection costs is Media3's: a new selection that changes the playing period's tracks
 * discards what was buffered for them, so a hotplug may cost a rebuffer. `superplayer-abr`'s gate
 * refuses through its hook rather than by narrowing the tracks, so there the selection Media3 compares
 * is unchanged, the buffer is kept, and the lesser rung arrives at the next chunk choice. It may cost
 * neither an error nor the position.
 *
 * ref: https://developer.android.com/reference/android/hardware/display/DisplayManager.DisplayListener
 * ref: https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/DefaultTrackSelector.java
 *   (`selectVideoTrack`, the display's mode size read per selection)
 */
internal class DisplayWatch(
    context: Context,
    /** The player's application looper, where the facade and the binding are called. */
    private val looper: Looper,
    private val selector: ReselectingTrackSelector,
    private val displayInForce: DisplayInForce,
) : DisplayManager.DisplayListener {

    /**
     * The reading this watch last acted on, kept apart from [displayInForce] because a pool's players
     * share that window: the first watch to hear a hotplug writes it, and every other player must still
     * re-select and ask its own surface again rather than finding the window already current.
     */
    private var heard: DisplayCapability = displayInForce.current

    /**
     * Step 3: what the output is told once the selection has been asked again. Set by the
     * [VideoOutputAttachment] that owns this watch, which is built after it.
     */
    var afterChange: () -> Unit = {}

    private val context: Context = context.applicationContext

    private val displays: DisplayManager? = this.context.getSystemService(DisplayManager::class.java)

    private var watching = false

    fun start() {
        val displays = displays ?: return
        displays.registerDisplayListener(this, Handler(looper))
        watching = true
    }

    fun stop() {
        if (!watching) return
        displays?.unregisterDisplayListener(this)
        watching = false
    }

    override fun onDisplayAdded(displayId: Int) {
        onDisplayChanged(displayId)
    }

    override fun onDisplayRemoved(displayId: Int) = Unit

    override fun onDisplayChanged(displayId: Int) {
        if (displayId != Display.DEFAULT_DISPLAY) return
        val reading = displayCapabilityOf(context.defaultDisplay() ?: return)
        if (reading == heard) return
        heard = reading
        displayInForce.current = reading
        selector.reselect()
        afterChange()
    }
}

/**
 * Media3's own `DefaultTrackSelector`, with its protected `invalidate` exposed — the subclass ADR-0014
 * rule 5 names, and its only job.
 *
 * Built in place of Media3's selector on a player whose output slot is filled, over the same selection
 * factory the player would otherwise have been given, so everything the selector decides is Media3's.
 * A display change changes nothing Media3 watches, and this is how both Media3's viewport bound and the
 * gate's refusals are asked again. Every Media3 upgrade checks that the protected method has not moved
 * (ADR-0014, *Consequences*).
 *
 * ref: `androidx.media3.exoplayer.trackselection.TrackSelector.invalidate(TrackSelectionParameters)`
 */
internal class ReselectingTrackSelector(context: Context, factory: ExoTrackSelection.Factory) : DefaultTrackSelector(context, factory) {

    /** Asks the engine to select tracks again under the parameters in force. */
    fun reselect() {
        invalidate(parameters)
    }
}
