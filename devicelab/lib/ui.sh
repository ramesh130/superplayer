# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# Driving the demo's UI from a scenario.
#
# Only gestures that read nothing from the screen. The obvious way to find a button, `uiautomator
# dump`, waits for the screen to go idle, and the demo never idles while it plays: on API 36 every
# dump of the player screen or the feed during playback answers "ERROR: could not get idle state."
# Scenarios run during playback by construction, so a tap-by-text helper would work only when it
# happened to catch a still moment, which is worse than not having one. README.md says what a
# scenario that needs another screen should do instead.

# The screen's size in pixels, as "<width> <height>".
ui_screen_size() {
    adb_s shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)$/\1 \2/p' | tail -1
}

# Swipes content upward — a scroll down a list — over `$1` ms (default 300; lower is a fling).
# Positions are fractions of the screen, so the gesture is the same one on any device.
ui_swipe_up() {
    local duration="${1:-300}" size width height
    size="$(ui_screen_size)"
    width="${size% *}"
    height="${size#* }"
    adb_s shell input swipe $((width / 2)) $((height * 3 / 4)) $((width / 2)) $((height / 4)) "$duration"
}

# Swipes content downward — a scroll back up a list — over `$1` ms (default 300).
ui_swipe_down() {
    local duration="${1:-300}" size width height
    size="$(ui_screen_size)"
    width="${size% *}"
    height="${size#* }"
    adb_s shell input swipe $((width / 2)) $((height / 4)) $((width / 2)) $((height * 3 / 4)) "$duration"
}

# Taps the middle of the screen. A fraction of the screen like the swipes, so it is the same tap on any
# device, and a tap for a screen whose whole middle is one target — the feed, which takes a tap anywhere
# on the list (FeedScreen.kt) — rather than for a button, which would need finding first.
ui_tap_centre() {
    local size width height
    size="$(ui_screen_size)"
    width="${size% *}"
    height="${size#* }"
    adb_s shell input tap $((width / 2)) $((height / 2))
}
