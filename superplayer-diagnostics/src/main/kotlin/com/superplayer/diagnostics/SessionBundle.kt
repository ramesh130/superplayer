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

package com.superplayer.diagnostics

import android.content.Context
import com.superplayer.core.CapabilitySnapshot
import com.superplayer.core.DisplayCapability
import com.superplayer.core.SuperPlayer
import com.superplayer.core.capabilitySnapshotOf
import com.superplayer.telemetry.SessionTrace
import com.superplayer.telemetry.SessionTraceRecorder

/**
 * One session, as one artifact a viewer's device can produce and a CDN engineer can read: the
 * session trace one layer richer (`PRD.md` §3.6, ADR-0015 rule 9).
 *
 * `docs/session-bundle.md` is the format's reference and is written for a reader who has never seen
 * this repository; what follows is why it has the shape it has.
 *
 * ```kotlin
 * val recorder = SessionTraceRecorder()
 * val player = SuperPlayer.Builder(context)
 *     .setTelemetry(QoeCollector(TelemetrySink.composite(appSink, recorder)))
 *     .build()
 * recorder.attach(player)
 * // ... play, fail, release; take the trace once the sink has seen SessionEnded ...
 * val bundle = SessionBundle.Builder(context)
 *     .setTrace(recorder.trace())
 *     .setPlayer(player)
 *     .build()
 * bugReport.attach(bundle.format())
 * ```
 *
 * **It is the trace's own grammar, not a second one.** Every line is `+<ms> <kind> <fields>`, ordered
 * and redacted exactly as [SessionTrace] is, because two authored formats would be two redaction
 * implementations of which one would be behind. The timeline, the track switches, the loads with
 * their timings and HTTP status, the bandwidth samples and the errors are therefore the trace's,
 * recorded by [SessionTraceRecorder] where the engine can be observed; what this type adds is the one
 * kind no recorder of a session can hold, the `capability` snapshot, and the header that names the
 * artifact.
 *
 * **The snapshot is the phase's one deliberate exposure, and it obeys ADR-0015 rule 10** — the
 * seventh redaction rule, stated in [SessionTraceRecorder]'s KDoc beside the six a trace obeys. What
 * the device can *do* is admitted, what the device *is* is refused, and the boundary is not enforced
 * here: core reads the snapshot and hands it over whole ([CapabilitySnapshot], rule 3's second seam),
 * so a field this bundle could print is a field that rule admitted. The one half that does not come
 * from there is the security level a session was delivered at, which is read from the public
 * [SuperPlayer.deliveredSecurityLevel] as `QoeCollector` reads it — rule 3 keeps the protection half
 * off the seam list for exactly that reason.
 *
 * **Versioning.** [FORMAT_VERSION] is the bundle's own and follows the trace's rule, which is
 * `TelemetryEvent.SCHEMA_VERSION`'s (ADR-0008 rule 5): it moves when a line's *meaning* changes and
 * not when a kind or a field is added. `SessionTrace.FORMAT_VERSION` did not move for this artifact
 * and is printed on its own line, so a reader holding a bundle knows which grammar its trace lines
 * are in without knowing anything about this library's releases.
 *
 * **Not an export format.** `PRD.md` §3.6 asks for "one exportable JSON/Perfetto-compatible
 * artifact", and rule 9 narrows that: the facts are recorded once, here, and a JSON or Perfetto
 * rendering is a *rendering* of these lines rather than a second authoring of the same session.
 */
public class SessionBundle internal constructor(
    /** Every line of the bundle, in order, without the header: the `capability` kind, then the trace's. */
    public val lines: List<String>,

    /** Whether each line carries the `+<ms>` column; [SessionTrace.timed] of the trace it was built from. */
    public val timed: Boolean,
) {

    /** The bundle as text: three header lines, then [lines], each newline-terminated. */
    public fun format(): String = buildString {
        append(FORMAT_HEADER).append('\n')
        append(SessionTrace.FORMAT_HEADER).append('\n')
        append(if (timed) TIMINGS_RELATIVE else TIMINGS_OMITTED).append('\n')
        lines.forEach { append(it).append('\n') }
    }

    override fun toString(): String = format()

    /**
     * Builds a [SessionBundle] from a session's trace and the device it ran on.
     *
     * The trace is required and the player is not: a session whose player has already gone still has
     * a trace worth attaching to a bug report, and what it loses is one field.
     */
    public class Builder(private val context: Context) {

        private var trace: SessionTrace? = null
        private var player: SuperPlayer? = null

        /** The session this bundle is of, as [SessionTraceRecorder.trace] answered it. */
        public fun setTrace(trace: SessionTrace): Builder = apply { this.trace = trace }

        /**
         * The player the trace was recorded from, read for one field: the security level its
         * protected session was delivered at ([SuperPlayer.deliveredSecurityLevel]).
         *
         * A whole player rather than that string, so that a caller is not the one deciding what of a
         * player a bundle may read — the same reason rule 3's snapshot seam returns the snapshot.
         */
        public fun setPlayer(player: SuperPlayer): Builder = apply { this.player = player }

        /** Reads the device now, and answers the bundle. */
        public fun build(): SessionBundle {
            val trace = checkNotNull(trace) { "A SessionBundle is of a session: call setTrace(recorder.trace())" }
            // The `+0` column only where the trace it joins carries one: `SessionTrace.withoutTimings`
            // is the stated normalisation for a trace captured on a device, and a bundle of one has to
            // be readable by the same reader.
            val capabilities = capabilityLines(capabilitySnapshotOf(context), player?.deliveredSecurityLevel)
                .map { if (trace.timed) "+0 capability $it" else "capability $it" }
            return SessionBundle(capabilities + trace.lines, trace.timed)
        }
    }

    public companion object {
        /** The meaning of the lines. Bumps when a line's meaning changes, not when a kind is added. */
        public const val FORMAT_VERSION: Int = 1

        /** The first line of every formatted bundle. */
        public const val FORMAT_HEADER: String = "superplayer-session-bundle v$FORMAT_VERSION"

        private const val TIMINGS_RELATIVE = "timings relative-ms"
        private const val TIMINGS_OMITTED = "timings omitted"
    }
}

/**
 * The `capability` lines, in a fixed order: the device, then one line per video decoder ascending by
 * MIME type, then the display, then the protection.
 *
 * Printed before the trace's own lines, and stamped `+0` where the trace carries a time column at
 * all. A capability is a fact about the whole session rather than about a moment in it, and the
 * alternative — the time the bundle was built — would be the one number in the artifact that says
 * when a *report* was made rather than when something happened.
 *
 * Every value here is one [CapabilitySnapshot] carries, and that is the whole of rule 10's
 * enforcement: this function cannot print what it was not handed. Unknown prints as `unknown`, in the
 * direction every reading of a device in this library takes — "the platform did not say" is a
 * different fact from "none", and a bug report that confused them would send a reader after the
 * wrong device.
 */
private fun capabilityLines(snapshot: CapabilitySnapshot, deliveredSecurityLevel: String?): List<String> =
    buildList {
        add(
            "device apiLevel=${snapshot.apiLevel} lowRam=${snapshot.lowRamDevice} " +
                "heapBudgetMb=${snapshot.heapBudgetMb.orUnknown()}",
        )
        snapshot.videoDecoders.forEach { add(decoderLine(it)) }
        add("display shortEdgePx=${snapshot.display.shortEdgePx.orUnknown()} hdr=${hdrField(snapshot.display)}")
        // The protection half, and the only line of the snapshot that is not core's to read. Null is
        // the session that negotiated nothing — an unprotected one, or one the device honoured at the
        // level it reported — a third answer beside a level and an unknown, so it prints as itself.
        add("protection deliveredSecurityLevel=${deliveredSecurityLevel ?: NOT_NEGOTIATED}")
    }

/** One video MIME type's line; `docs/session-bundle.md` is what a reader makes of each field. */
private fun decoderLine(decoder: CapabilitySnapshot.VideoDecoderCapability): String =
    "decoder mime=${decoder.mimeType} secure=${decoder.secure.orUnknown()} " +
        "instances=${decoder.maxInstances.orUnknown()} " +
        "profile=${decoder.highestProfileLevel?.profile.orUnknown()} " +
        "level=${decoder.highestProfileLevel?.level.orUnknown()} " +
        "tunneling=${decoder.tunneling}"

/**
 * The display's HDR field, where the three answers are three different words.
 *
 * A display that listed its formats prints them, comma-separated and sorted so the line is stable; a
 * display that listed *none* prints [NONE]; and a display the platform said nothing about prints
 * [UNKNOWN]. Collapsing the last two is the mistake this spells out to avoid: "this panel cannot show
 * HDR10" and "we could not ask" send a reader of a bug report to two different places.
 */
private fun hdrField(display: DisplayCapability): String {
    val types = display.hdrTypes ?: return UNKNOWN
    if (types.isEmpty()) return NONE
    return types.map { it.name }.sorted().joinToString(",")
}

/** The value, or [UNKNOWN] where the platform did not answer. */
private fun Any?.orUnknown(): String = this?.toString() ?: UNKNOWN

/** What the platform did not say, as against [NONE], which is what it said it has none of. */
private const val UNKNOWN = "unknown"

private const val NONE = "none"

/** A session that never asked for a security level below the one the device reported. */
private const val NOT_NEGOTIATED = "not-negotiated"
