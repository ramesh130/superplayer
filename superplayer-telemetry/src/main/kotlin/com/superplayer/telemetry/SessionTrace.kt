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

package com.superplayer.telemetry

/**
 * What one playback session did, as a line-oriented value that outlives the player: the ordered
 * facts a [SessionTraceRecorder] observed, one per line, in a stable order and a fixed grammar.
 *
 * The purpose is to be diffed. Against a committed golden (`docs/testing.md`, *Golden traces*) a
 * behaviour change appears in review as a readable line diff rather than as a percentile that
 * moved — so the format is judged on that, and every choice below serves it:
 *
 * - **One fact per line**, `+<ms> <kind> <fields>`. The first column is milliseconds since the
 *   recorder attached, the second what kind of fact it is, the rest that kind's fields in a fixed
 *   order. No line depends on another to be read.
 * - **Stably ordered**, by time, then by kind in a fixed rank, then by the fact's own key, and only
 *   then by arrival — so two loads that finished in the same millisecond print in the same order
 *   whichever thread won the race. Arrival order is *not* a fact this format records.
 * - **Nothing that varies between runs of the same session** is in a line: no wall-clock time, no
 *   session id, no byte count, no thread. Under the harness's fake clock the millisecond column is
 *   deterministic and is kept; from a device it is not, and [withoutTimings] is the stated
 *   normalisation — the column is dropped and the order, which it decided, is kept.
 * - **Redacted by construction**: no URL, host, path, query or token; no request or response
 *   header; no exception message; no device identifier; no DRM payload. [SessionTraceRecorder]
 *   lists the rules and never holds the redacted material at all, so there is nothing to strip.
 *
 * The kinds, in rank order, are `item`, `state`, `playing`, `tracks`, `discontinuity`, `load`,
 * `error` — engine facts, from Media3's analytics — and then `telemetry`, one line per
 * `com.superplayer.core.TelemetryEvent` the recorder was handed as a sink. A telemetry line prints
 * the event's own fields, so a changed number in `docs/telemetry-schema.md` is a changed line here.
 *
 * The seam for `superplayer-diagnostics` (`PRD.md` §3.6, phase 9): its session trace bundle is this
 * artifact one layer richer — bandwidth samples, a capability snapshot, a Perfetto-compatible
 * export beside it — and the grammar is meant to take that without a second format. A richer trace
 * adds *kinds*; it does not reorder an existing kind's fields, and [FORMAT_VERSION] moves when a
 * line's meaning changes, on the same rule as `TelemetryEvent.SCHEMA_VERSION`. Whatever a capability
 * snapshot records will need its own redaction rule before it is added, since a device model is
 * exactly the kind of identifier this format keeps out.
 */
public class SessionTrace internal constructor(
    /** Every fact, one per element, in trace order, without the header. */
    public val lines: List<String>,
    /** Whether each line carries the `+<ms>` column. */
    public val timed: Boolean,
) {

    /** The trace as text: two header lines, then [lines], each newline-terminated. */
    public fun format(): String = buildString {
        append(FORMAT_HEADER).append('\n')
        append(if (timed) TIMINGS_RELATIVE else TIMINGS_OMITTED).append('\n')
        lines.forEach { append(it).append('\n') }
    }

    /**
     * This trace with the millisecond column removed and the order kept: the normalisation for a
     * trace captured on a device, whose timings are real and therefore differ run to run.
     */
    public fun withoutTimings(): SessionTrace =
        if (!timed) this else SessionTrace(lines.map { it.substringAfter(' ') }, timed = false)

    override fun equals(other: Any?): Boolean =
        other is SessionTrace && other.timed == timed && other.lines == lines

    override fun hashCode(): Int = 31 * lines.hashCode() + timed.hashCode()

    override fun toString(): String = format()

    public companion object {
        /** The meaning of the lines. Bumps when a line's meaning changes, not when a kind is added. */
        public const val FORMAT_VERSION: Int = 1

        /** The first line of every formatted trace. */
        public const val FORMAT_HEADER: String = "superplayer-session-trace v$FORMAT_VERSION"

        private const val TIMINGS_RELATIVE = "timings relative-ms"
        private const val TIMINGS_OMITTED = "timings omitted"

        /**
         * The inverse of [format].
         *
         * @throws IllegalArgumentException when [text] is not a trace this version can read.
         */
        @JvmStatic
        public fun parse(text: String): SessionTrace {
            val lines = text.lines().dropLastWhile { it.isEmpty() }
            require(lines.firstOrNull() == FORMAT_HEADER) {
                "Not a session trace this version reads; expected '$FORMAT_HEADER' first, was '${lines.firstOrNull()}'"
            }
            val timed = when (val timings = lines.getOrNull(1)) {
                TIMINGS_RELATIVE -> true
                TIMINGS_OMITTED -> false
                else -> throw IllegalArgumentException("Unknown timings declaration '$timings'")
            }
            return SessionTrace(lines.drop(2), timed)
        }
    }
}
