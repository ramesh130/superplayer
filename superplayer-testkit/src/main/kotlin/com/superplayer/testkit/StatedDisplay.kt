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

package com.superplayer.testkit

import android.os.Build
import android.view.Display
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowLooper.shadowMainLooper
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Writes the default display [DeviceStatement] states into Robolectric's display service, as **one**
 * event to every `DisplayManager.DisplayListener` registered.
 *
 * One, because that is what makes a mid-session change testable. Robolectric's public setters each
 * rebuild or re-publish the display: a qualifier change drops the HDR capabilities, and a mode change
 * is a second event after it. A listener would hear a 1080p display with no HDR answer, then the modes,
 * then the HDR types — three readings, the first of which constrains nothing, so a player that
 * re-selects on a change (ADR-0014 rule 5) would climb to a rung the new display cannot show and come
 * back down. A real display service reports the display it ended up with, so this builds the whole
 * `DisplayInfo` first and hands it to the service once.
 *
 * That needs the service's hidden half, and every hidden member this module names for the display is
 * here, named once: `DisplayManagerGlobal` and `DisplayInfo`, which the platform hides; Robolectric's
 * own `DisplayInfo` factory and its fake service's `changeDisplay`, `addDisplay` and next display id,
 * which Robolectric keeps package-private; and `Display.Mode`'s HDR types and `DisplayInfo`'s
 * app-visible modes, which exist only from API 34 and 35. Each is read by name and fails loudly if a
 * Robolectric upgrade moves it, rather than stating a display that is not the one written down.
 *
 * A statement that changes nothing is published to nobody. On a device the platform filters it: its
 * `DisplayManagerGlobal` delivers `EVENT_DISPLAY_CHANGED` to a listener only where the display's info
 * differs from the last that listener saw. Robolectric's fake service delivers every change it is
 * handed, so the comparison is made here instead.
 *
 * ref: https://cs.android.com/android/platform/superproject/+/android-15.0.0_r1:frameworks/base/core/java/android/hardware/display/DisplayManagerGlobal.java
 */
internal object StatedDisplay {

    /**
     * What the display reported when [disconnect] removed it, so that it comes back reporting the same
     * HDR types unless a test restates them — an unplugged cable changes the sink, not what the sink is.
     */
    private var lastDisconnected: Any? = null

    fun isConnected(): Boolean = currentInfo() != null

    /**
     * Publishes [modes] with [activeMode] the one in force, keeping the HDR types the display reports
     * now. A disconnected display is connected again under the default display's id.
     */
    fun publishModes(modes: List<DisplayMode>, activeMode: DisplayMode) {
        val capabilities = (currentInfo() ?: lastDisconnected)?.let { ReflectionHelpers.getField<Display.HdrCapabilities?>(it, "hdrCapabilities") }
        val info = infoFor(activeMode)
        ReflectionHelpers.setField(info, "hdrCapabilities", capabilities)
        // Deprecated from API 34 in favour of each mode's own types, which is exactly what this writes
        // those from: the display-wide capabilities are the statement's record of the types.
        @Suppress("DEPRECATION")
        setModes(info, modes, capabilities?.supportedHdrTypes)
        val activeId = modes.indexOf(activeMode) + FIRST_MODE_ID
        ReflectionHelpers.setField(info, "modeId", activeId)
        ReflectionHelpers.setField(info, "defaultModeId", activeId)
        publish(info)
    }

    /** Publishes the display as it is, with [hdrTypes] as its HDR types on every mode. */
    fun publishHdrTypes(hdrTypes: IntArray) {
        val info = checkNotNull(currentInfo()) { "No display is connected to state HDR types for; state its modes first" }
        val capabilities = ReflectionHelpers.callConstructor(
            Display.HdrCapabilities::class.java,
            ClassParameter.from(IntArray::class.java, hdrTypes),
            ClassParameter.from(Float::class.javaPrimitiveType, REFERENCE_MAX_LUMINANCE),
            ClassParameter.from(Float::class.javaPrimitiveType, REFERENCE_MAX_AVERAGE_LUMINANCE),
            ClassParameter.from(Float::class.javaPrimitiveType, REFERENCE_MIN_LUMINANCE),
        )
        ReflectionHelpers.setField(info, "hdrCapabilities", capabilities)
        setModes(info, ReflectionHelpers.getField<Array<Display.Mode>>(info, "supportedModes").map { it.toStated() }, hdrTypes)
        publish(info)
    }

    /** Removes the default display, which every listener hears as `onDisplayRemoved(DEFAULT_DISPLAY)`. */
    fun disconnect() {
        lastDisconnected = checkNotNull(currentInfo()) { "The display is already disconnected" }
        ShadowDisplayManager.removeDisplay(Display.DEFAULT_DISPLAY)
    }

    private fun publish(info: Any) {
        val current = currentInfo()
        if (current == info) return
        val service = Shadow.extract<Any>(displayManagerGlobal())
        if (current != null) {
            ReflectionHelpers.callInstanceMethod<Unit>(
                service,
                "changeDisplay",
                ClassParameter.from(Int::class.javaPrimitiveType, Display.DEFAULT_DISPLAY),
                ClassParameter.from(displayInfoClass, info),
            )
        } else {
            // The fake service numbers an added display after every display it has held, so a display
            // connected again would come back under a new id and no longer be the default one. A
            // default display is display 0 whenever it exists, so the id is put back for the add.
            val fake = ReflectionHelpers.getField<Any>(service, "mDm")
            val nextId = ReflectionHelpers.getField<Int>(fake, "nextDisplayId")
            ReflectionHelpers.setField(fake, "nextDisplayId", Display.DEFAULT_DISPLAY)
            try {
                ReflectionHelpers.callInstanceMethod<Int>(service, "addDisplay", ClassParameter.from(displayInfoClass, info))
            } finally {
                ReflectionHelpers.setField(fake, "nextDisplayId", maxOf(nextId, Display.DEFAULT_DISPLAY + 1))
            }
            lastDisconnected = null
        }
        // The service posts each event to the listener's own handler; a device delivers it promptly,
        // so the main looper is run for it here, as Robolectric's own display setters do.
        shadowMainLooper().idle()
    }

    /**
     * A `DisplayInfo` [activeMode]'s size at a density where a pixel is a pixel, built by Robolectric's
     * own factory from qualifiers so that every field it derives from a size agrees with the size — and
     * relative to the display in place where there is one, as `ShadowDisplayManager.changeDisplay` is.
     */
    private fun infoFor(activeMode: DisplayMode): Any = ReflectionHelpers.callStaticMethod(
        ShadowDisplayManager::class.java,
        "createDisplayInfo",
        ClassParameter.from(String::class.java, "+w${activeMode.widthPx}dp-h${activeMode.heightPx}dp-mdpi"),
        ClassParameter.from(Int::class.javaObjectType, if (isConnected()) Display.DEFAULT_DISPLAY else null),
    )

    /**
     * Writes [modes] as fresh platform modes, ids from one in the order stated: Robolectric's own
     * default mode is id 0, and a mode object shared with a listener's last reading would change that
     * reading too, so that the listener compared the new display with itself and heard nothing.
     *
     * From API 34 a mode carries its own HDR types (`Display.Mode.getSupportedHdrTypes`), which is what
     * ADR-0014 rule 9 reads there. The statement is display-wide, so every mode carries the same types.
     */
    private fun setModes(info: Any, modes: List<DisplayMode>, hdrTypes: IntArray?) {
        val platformModes = modes.mapIndexed { index, mode ->
            ShadowDisplayManager.ModeBuilder.modeBuilder(index + FIRST_MODE_ID)
                .setWidth(mode.widthPx)
                .setHeight(mode.heightPx)
                .setRefreshRate(mode.refreshRateHz)
                .build()
                .also { if (hdrTypes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ReflectionHelpers.setField(it, "mSupportedHdrTypes", hdrTypes.copyOf()) }
        }.toTypedArray()
        ReflectionHelpers.setField(info, "supportedModes", platformModes)
        // API 35 answers `Display.getSupportedModes` from the modes an app may see, a second array.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ReflectionHelpers.setField(info, "appsSupportedModes", platformModes)
        }
    }

    private fun Display.Mode.toStated() = DisplayMode(physicalWidth, physicalHeight, refreshRate)

    /** A copy of the display's info, or null where it is disconnected; a copy, so nothing a listener holds is written through. */
    private fun currentInfo(): Any? = ReflectionHelpers.callInstanceMethod<Any?>(
        displayManagerGlobal(),
        "getDisplayInfo",
        ClassParameter.from(Int::class.javaPrimitiveType, Display.DEFAULT_DISPLAY),
    )?.let { ReflectionHelpers.callConstructor(displayInfoClass, ClassParameter.from(displayInfoClass, it)) }

    private fun displayManagerGlobal(): Any =
        ReflectionHelpers.callStaticMethod(Class.forName("android.hardware.display.DisplayManagerGlobal"), "getInstance")

    private val displayInfoClass: Class<*>
        get() = Class.forName("android.view.DisplayInfo")

    private const val FIRST_MODE_ID = 1

    // Luminance figures for the declared HDR capabilities. SuperPlayer reads the *types* only, so
    // these are a plausible HDR10 panel's numbers rather than anything a test asserts on.
    private const val REFERENCE_MAX_LUMINANCE = 1_000f
    private const val REFERENCE_MAX_AVERAGE_LUMINANCE = 500f
    private const val REFERENCE_MIN_LUMINANCE = 0.005f
}
