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

package com.superplayer.tv

/**
 * One scrub: where it started, where the D-pad has moved the target to, and nothing else.
 *
 * A scrub moves a *target* and never the player. The controls show the target while the scrub lasts and
 * seek to it once, when it is committed (`TvPlaybackControls`), so a viewer who taps Right six times and
 * then holds it for three seconds costs the player one seek rather than sixty: one rebuffer, one burst of
 * segment requests, and one `Seeked` event, where a seek per key event would cost each of them per event.
 *
 * Pure, so the curve is a function of the key events alone. The events carry their own times, which is
 * what makes the speed a function of how long the key has been *held* rather than of how often the remote
 * repeats. Remotes repeat at different rates, and a curve counted in repeats would scrub at a different
 * speed on every one of them.
 */
internal class Scrub(startMs: Long, private val durationMs: Long) {

    /** Where the scrub would seek to if it were committed now, within the content. */
    var targetMs: Long = startMs.coerceIn(0, durationMs)
        private set

    /**
     * The time of the last event this scrub moved on, from which a held key's next movement is measured. Null
     * before the first, so a scrub that begins on a repeat (a key already held when the bar took focus) moves a
     * press's distance rather than the distance from the clock's origin.
     */
    private var lastEventTimeMs: Long? = null

    /**
     * Moves the target for one key-down event.
     *
     * [direction] is +1 for forward and -1 for back. [repeat] says whether the event is the platform's
     * auto-repeat of a held key rather than a press. [heldMs] is how long the key has been down, and
     * [eventTimeMs] is the event's own time; both are the platform's `KeyEvent` times, on one clock.
     *
     * A press moves a fixed [TAP_STEP_MS]. A repeat moves as far as [speedMsPerSecond] covers in the time
     * since the previous event, so the distance covered by a hold is the integral of the curve over the
     * hold, whatever the repeat rate.
     */
    fun move(direction: Int, repeat: Boolean, heldMs: Long, eventTimeMs: Long) {
        val previousMs = lastEventTimeMs
        val deltaMs = if (repeat && previousMs != null) {
            val elapsedMs = (eventTimeMs - previousMs).coerceAtLeast(0)
            speedMsPerSecond(heldMs, durationMs) * elapsedMs / MILLIS_PER_SECOND
        } else {
            TAP_STEP_MS
        }
        lastEventTimeMs = eventTimeMs
        targetMs = (targetMs + direction * deltaMs).coerceIn(0, durationMs)
    }

    companion object {

        /**
         * How far one press moves the target: ten seconds.
         *
         * Media3's own `DefaultTimeBar` moves a twentieth of the duration per key press, which on a
         * two-hour film is six minutes. That fits a time bar with no acceleration, which has to cross
         * the content in a reasonable number of presses. This one accelerates while held, so a press
         * can be fine, and ten seconds is the distance a viewer goes back to catch a missed line.
         */
        // ref: https://github.com/androidx/media/blob/1.11.0/libraries/ui/src/main/java/androidx/media3/ui/DefaultTimeBar.java (DEFAULT_INCREMENT_COUNT)
        const val TAP_STEP_MS: Long = 10_000

        /**
         * The scrub speed a hold starts at: thirty seconds of content per second held.
         *
         * Three taps' worth a second, so the start of a hold is visibly faster than tapping and still slow
         * enough to stop within a tap's distance of where the viewer meant.
         */
        const val HOLD_START_SPEED_MS_PER_SECOND: Long = 30_000

        /**
         * How long a key must stay held for the speed to double: two seconds.
         *
         * A geometric curve covers both ends of a scrub. Near its start it stays fine, and a long hold
         * reaches the far end of a film in a handful of seconds rather than in minutes. Two seconds is
         * long enough for a viewer to see the speed they are at before it changes.
         */
        const val DOUBLING_INTERVAL_MS: Long = 2_000

        /**
         * The speed stops doubling once the target would cross a tenth of the content per second.
         *
         * The cap is what a viewer's reaction costs. This project assumes a quarter of a second from seeing
         * the right place to letting go, which is a round figure for simple visual reaction time and not a
         * measurement of remotes, so at a tenth of the bar per second a viewer who lets go on seeing the right place
         * overshoots by about two and a half percent of the bar. On short content the start speed is
         * already above this, and the start speed is kept.
         */
        const val MAX_FRACTION_OF_CONTENT_PER_SECOND: Long = 10

        /** The hold speed after [heldMs] held on content [durationMs] long, in content milliseconds per second. */
        fun speedMsPerSecond(heldMs: Long, durationMs: Long): Long {
            val doublings = (heldMs.coerceAtLeast(0) / DOUBLING_INTERVAL_MS).coerceAtMost(MAX_DOUBLINGS)
            val accelerated = HOLD_START_SPEED_MS_PER_SECOND shl doublings.toInt()
            val cap = maxOf(HOLD_START_SPEED_MS_PER_SECOND, durationMs / MAX_FRACTION_OF_CONTENT_PER_SECOND)
            return minOf(accelerated, cap)
        }

        /** Bounds the shift. Thirty seconds a second doubled twenty times already exceeds any content's cap. */
        private const val MAX_DOUBLINGS = 20L

        private const val MILLIS_PER_SECOND = 1_000L
    }
}
