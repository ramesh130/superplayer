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
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import com.superplayer.core.PlaybackFailure
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.core.TrackSelectionPolicy
import kotlinx.coroutines.delay

/**
 * What a developer watches while the thing is playing in front of them: `PRD.md` §3.6's on-device
 * debug HUD, as a composable the app places over its own video surface.
 *
 * ```kotlin
 * // Once per player, beside the app's own sink.
 * val hudTelemetry = remember { DebugHudTelemetry() }
 * val player = SuperPlayer.Builder(context)
 *     .setTelemetry(QoeCollector(TelemetrySink.composite(appSink, hudTelemetry)))
 *     .build()
 *
 * Box {
 *     AndroidView({ PlayerView(it).apply { this.player = player } })
 *     DebugHud(player, hudTelemetry)      // nothing at all in a release build
 * }
 * ```
 *
 * Six rows: where the numbers come from, the rendition playing, the buffer ahead of the playhead,
 * the frames the renderer dropped, **the throughput estimate beside the rung that was selected**,
 * and the failures the session has seen.
 *
 * ## The line worth the HUD
 *
 * The `estimate` row, and it is the reason this exists rather than a logcat filter. A stall with a
 * healthy estimate and a low rung is a different defect from a stall with a collapsed estimate — the
 * first is selection, a ceiling or a device refusal, the second is the link — and in a vocabulary
 * that reports only what was selected the two are indistinguishable. Telling them apart used to mean
 * exporting a session and reading it afterwards. The row puts the estimate, the rung and the policy
 * ceiling in force on one line, which is the whole trick: all three at one instant, where a reader
 * can see which of them is the small number.
 *
 * ## What it reads, and what it does not
 *
 * Only published API (ADR-0015 rule 11): the [TelemetryEvent] vocabulary through [DebugHudTelemetry],
 * `SuperPlayer.playbackDecision` for the ceiling, and the [Player]'s own state. No internal seam is
 * opened for the HUD, and it holds no analytics listener of its own — a second reader of Media3's
 * analytics beside `QoeCollector` is exactly the drift ADR-0008 rule 2 puts one collector in one
 * module to prevent.
 *
 * ## It takes any `Player`, and degrades rather than refusing
 *
 * [player] is a Media3 `Player`, ADR-0015 rule 2's one named exception, exactly as
 * `superplayer-tv`'s `TvPlaybackControls` is: `Player` is stable, and it is the only supertype a
 * stock `ExoPlayer` and a [SuperPlayer] share. So a stock player shows the rows it can answer — the
 * rendition it selected, the buffer, the position, its own `playerError` — and reads `unavailable` on
 * the rest, while a [SuperPlayer] with a collector attached answers all six. The estimate is the one
 * half of the row worth the HUD that only telemetry can answer, which is why the rung beside it is
 * read off the player: see `selectedVideoBitrateBps`. A missing reading is named as missing; nothing
 * here throws, and nothing is invented to fill a row.
 *
 * ## Debug builds only, and what that guarantees
 *
 * This composable renders **nothing** unless the application is debuggable — see
 * [isDebugHudAvailable], which argues the mechanism and says plainly what it does not guarantee.
 *
 * @param player the player to watch: a [SuperPlayer], or any other Media3 `Player`.
 * @param telemetry the sink the app attached to this player's collector, or null on a player that
 *   has no collector — in which case the rows only telemetry can answer read `unavailable`.
 * @param modifier applied to the overlay's outermost layout.
 */
@Composable
public fun DebugHud(player: Player, telemetry: DebugHudTelemetry? = null, modifier: Modifier = Modifier) {
    if (!isDebugHudAvailable(LocalContext.current)) return

    var facts by remember(player, telemetry) { mutableStateOf(hudFacts(player, telemetry)) }

    // Polled rather than pushed, and the poll is the whole refresh. Three of the six rows move with
    // no event at all — the position and the buffer advance between callbacks — and the telemetry
    // rows arrive on the delivery thread, which is not a thread Compose state may be written from
    // (ADR-0008 rule 4). One reading of both halves on the UI thread is the only spelling in which
    // every row is the same instant.
    LaunchedEffect(player, telemetry) {
        while (true) {
            delay(HUD_REFRESH_MS)
            facts = hudFacts(player, telemetry)
        }
    }
    // A HUD shown while the player is paused would otherwise sit a refresh behind the last seek.
    DisposableEffect(player, telemetry) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                facts = hudFacts(player, telemetry)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Column(
        modifier = modifier
            .background(OverlayBackground)
            .padding(OverlayPadding)
            .semantics { contentDescription = HUD_DESCRIPTION },
    ) {
        facts.rows().forEach { (label, value) ->
            // Each row names itself and carries its reading, which is how a screen reader announces
            // one row rather than a wall of figures — and, incidentally, how a test reads a row by the
            // label a developer sees instead of by its position in the column.
            Row(
                modifier = Modifier.semantics {
                    contentDescription = label
                    stateDescription = value
                },
            ) {
                BasicText(text = label, style = LabelStyle, modifier = Modifier.width(LabelWidth))
                BasicText(text = value, style = ValueStyle)
            }
        }
    }
}

/**
 * Whether [DebugHud] shows anything in this application, which is true exactly when the application
 * is debuggable.
 *
 * **The mechanism, and why this one.** `ApplicationInfo.FLAG_DEBUGGABLE` is the manifest flag the
 * Android Gradle Plugin sets from the build type — true for `debug`, false for `release` — so a
 * consumer needs no flag of their own, no build-type source set and no call they might forget, and
 * an app that ships the HUD's call site in `src/main` still ships a release build that shows nothing.
 * That is ADR-0015 rule 11's requirement: the guard exists, a release build shows nothing by default,
 * and no builder call attaches a HUD to a player. The alternatives were worse in ways worth writing
 * down. A `BuildConfig.DEBUG` read is the *library's* build type and not the app's, and is `false`
 * in every published AAR, so the HUD would never show at all. A runtime flag the library reads on
 * the app's behalf — a system property, a debug menu, a `Builder.setDebugHud(true)` — is a switch
 * present in a release binary, which is the thing being avoided rather than a way to avoid it.
 *
 * **What it does not guarantee, said plainly.** It is a runtime reading, not an absence:
 * - **The code is still in the APK.** A composable the app calls is reachable, so R8 does not remove
 *   it, and a release APK of an app that calls [DebugHud] carries this file. An app that must ship
 *   no HUD *code* puts its call site in a `debug` source set, which is the only guarantee of that and
 *   is the app's to make.
 * - **A debuggable release build shows it.** Some teams ship `debuggable true` on an internal release
 *   flavour. The flag says what the build declares, and such a build declares itself debuggable.
 * - **It is not a security control.** The flag can be flipped by repackaging, and a HUD is the least
 *   of what that lets someone do. Nothing here is a secret: every number on it is one the app's own
 *   telemetry already carries.
 *
 * Public so a consumer's own debug affordance — a menu entry, a gesture — can ask the same question
 * the HUD asks rather than duplicating the reading.
 */
public fun isDebugHudAvailable(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/**
 * The telemetry half of [DebugHud]: a [TelemetrySink] the app attaches beside its own, which keeps
 * the last reading of each metric the HUD shows and the session's most recent failures.
 *
 * ```kotlin
 * val hudTelemetry = DebugHudTelemetry()
 * SuperPlayer.Builder(context)
 *     .setTelemetry(QoeCollector(TelemetrySink.composite(appSink, hudTelemetry)))
 * ```
 *
 * A sink rather than a collector, and that is the point: everything the HUD shows is already in the
 * vocabulary a consumer's pipeline receives, so the HUD adds a *reader* of the one collector rather
 * than a second derivation of the same numbers. One per player, as a collector is.
 *
 * **Thread-safe.** A sink is called on the delivery thread and never on the caller's (ADR-0008
 * rule 4), while the HUD reads on the UI thread, so the reading is swapped under a lock and handed
 * out as an immutable value. Nothing here calls the player, which from that thread would be a Media3
 * wrong-thread violation.
 *
 * **It keeps a reading, not a history.** The last rendition, the last estimate, the session's
 * dropped-frame total and the last few failures, which is what fits on an overlay. A session's whole
 * event stream is `superplayer-telemetry`'s `SessionTraceRecorder` and its [SessionBundle], and the
 * two are deliberately different artifacts: one is watched live and one is exported.
 */
public class DebugHudTelemetry : TelemetrySink {

    private val lock = Any()
    private var reading = HudTelemetryReading()

    override fun onEvent(event: TelemetryEvent) {
        synchronized(lock) { reading = reading.after(event) }
    }

    internal fun reading(): HudTelemetryReading = synchronized(lock) { reading }
}

/**
 * How many failures the HUD keeps and shows, newest first: four.
 *
 * An overlay lies over the content a developer is also trying to watch, and four lines of failure is
 * about as much as can be read at a glance without the HUD becoming the screen. A session that failed
 * more than four times has a problem the first four describe.
 *
 * It bounds the *row* and not only the sink's memory, which is why `hudFacts` applies it again after
 * adding the delivered error: a constant that argued four lines and rendered five would be arguing
 * about something else.
 *
 * A file-level constant rather than one on [DebugHudTelemetry]'s companion: a `const val` inside even
 * an `internal` companion is emitted as a public static field, and the tracked API surface would then
 * carry a number that is nobody's to read (`docs/api-surface.md`).
 */
private const val MAX_RECENT_FAILURES: Int = 4

/**
 * What [DebugHudTelemetry] has heard, as an immutable value the UI thread can read.
 *
 * It does **not** carry the selected rung, although the vocabulary reports it twice over
 * (`TrackSwitched`, and every sample's `videoBitrateBps`). That number is the player's own and is read
 * from it — ADR-0015 rule 11's sourcing, and `selectedVideoBitrateBps`' reason. Keeping a second copy
 * here would be a reading that could disagree with the player on the one row where the comparison is
 * the point, and it would leave a stock player showing `unavailable` for a rung it knows.
 */
internal data class HudTelemetryReading(
    val throughputEstimateBps: Int? = null,
    val droppedFrames: Int = 0,
    /** Newest first, at most four — see `MAX_RECENT_FAILURES`, which argues the number. */
    val failures: List<String> = emptyList(),
) {
    fun after(event: TelemetryEvent): HudTelemetryReading = when (event) {
        // A new session starts from nothing. A reading carried over would attribute the previous
        // content's rung and dropped frames to this one, which on a pooled player is the common case
        // rather than the odd one.
        is TelemetryEvent.SessionStarted -> HudTelemetryReading()

        // A sample's own null does not blank the row: the field is null before the meter has reported,
        // and a row that emptied every ten seconds and refilled would read as a fault in the HUD
        // rather than as an absence of a reading.
        is TelemetryEvent.PlaybackStateSampled ->
            copy(throughputEstimateBps = event.throughputEstimateBps ?: throughputEstimateBps)

        // Media3 reports dropped frames per interval, so the HUD's number is the session's running
        // total — the quantity a developer is asking about, since one interval's count means nothing
        // without the interval and `docs/telemetry-schema.md` defines the rate over the session.
        is TelemetryEvent.VideoFramesDropped -> copy(droppedFrames = droppedFrames + event.droppedFrames)

        is TelemetryEvent.StartupFailed -> withFailure(describe(event.failure))

        is TelemetryEvent.MidStreamFailed -> withFailure(describe(event.failure))

        else -> this
    }

    private fun withFailure(line: String): HudTelemetryReading =
        copy(failures = (listOf(line) + failures).take(MAX_RECENT_FAILURES))

    /**
     * One failure in one line: the classification where the session had a classifier, the engine's
     * own error code either way.
     *
     * Both facts, because they are two facts (ADR-0011 rule 3): what the engine raised, and what
     * SuperPlayer made of it. A player with no `superplayer-resilience` classifies nothing and the
     * line is the code alone, which is the honest reading rather than a gap.
     */
    private fun describe(failure: PlaybackFailure): String =
        listOfNotNull(failure.classification, failure.code).joinToString(" ")
            // Neither, on a failure Media3 raised no code for: the bucket is the only thing left to
            // print, and an empty row would be the one reading that says nothing.
            .ifEmpty { failure.category.name }
}

/** Every reading the HUD shows, taken at one instant on the UI thread. */
internal data class HudFacts(
    val superPlayer: Boolean,
    val telemetryAttached: Boolean,
    val bufferedDurationMs: Long,
    val positionMs: Long,
    val selectedBitrateBps: Int?,
    val throughputEstimateBps: Int?,
    val ceilingBitrateBps: Int?,
    val droppedFrames: Int?,
    val failures: List<String>,
) {
    /** The rows, in the order they are shown, each a label and its value. */
    fun rows(): List<Pair<String, String>> = listOf(
        LABEL_SOURCE to source(),
        LABEL_BITRATE to rate(selectedBitrateBps),
        LABEL_BUFFER to "${seconds(bufferedDurationMs)} ahead of ${seconds(positionMs)}",
        LABEL_DROPPED to (droppedFrames?.toString() ?: HUD_UNAVAILABLE),
        LABEL_ESTIMATE to estimate(),
        LABEL_FAILURES to if (failures.isEmpty()) HUD_NONE else failures.joinToString(", "),
    )

    private fun source(): String = buildString {
        append(if (superPlayer) "SuperPlayer" else "Player")
        append(if (telemetryAttached) ", telemetry attached" else ", no telemetry")
    }

    /**
     * The estimate, the rung it was spent on, and the ceiling the policy allowed — in that order,
     * because that is the order a reader compares them in.
     *
     * The selected rung is on this row as well as on its own, and the repetition is deliberate: the
     * `bitrate` row is a reading, and this row is the *relation*, which cannot be read at all unless
     * both numbers are adjacent. Absent parts are named rather than dropped, so the row's shape does
     * not change with what is available.
     */
    private fun estimate(): String =
        "${rate(throughputEstimateBps)} estimated, ${rate(selectedBitrateBps)} selected, " +
            "${ceiling()} ceiling"

    private fun ceiling(): String = when (ceilingBitrateBps) {
        null -> HUD_UNAVAILABLE

        // The policy's own "cap nothing" value, which is a decision and not a missing reading.
        TrackSelectionPolicy.UNLIMITED -> "unlimited"

        else -> rate(ceilingBitrateBps)
    }

    /** Kilobits a second, which is how every rendition ladder in use is named. */
    private fun rate(bps: Int?): String = if (bps == null) HUD_UNAVAILABLE else "${bps / BITS_PER_KILOBIT} kbps"

    /** Seconds to one decimal, which is the precision a buffer is reasoned about in. */
    private fun seconds(ms: Long): String = "%.1fs".format(ms / MILLIS_PER_SECOND.toFloat())
}

/**
 * One reading of both halves — the player's own state and what the sink has heard.
 *
 * The player is read here and nowhere else in this file, on the thread the composable runs on, which
 * is the only thread a [SuperPlayer] may be read from.
 */
internal fun hudFacts(player: Player, telemetry: DebugHudTelemetry?): HudFacts {
    val reading = telemetry?.reading()
    val superPlayer = player as? SuperPlayer
    // The engine's own fatal error, first in the failure list and never merged into a classified line
    // beside it: on a stock player it is the only failure there is, and on a `SuperPlayer` it is the
    // one rung 6 delivered. Two vocabularies are shown and neither is expressed in the other
    // (ADR-0015 rule 4's spirit); a reader sees both and can tell they are one event.
    val playerError = player.playerError?.errorCodeName?.let { "delivered $it" }
    return HudFacts(
        superPlayer = superPlayer != null,
        telemetryAttached = telemetry != null,
        bufferedDurationMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0),
        positionMs = player.currentPosition.coerceAtLeast(0),
        selectedBitrateBps = selectedVideoBitrateBps(player),
        throughputEstimateBps = reading?.throughputEstimateBps,
        ceilingBitrateBps = superPlayer?.playbackDecision?.trackSelection?.maxVideoBitrateBps,
        droppedFrames = reading?.droppedFrames,
        // Bounded here and not only in the sink, so the row a viewer sees is the length
        // `MAX_RECENT_FAILURES` argues: the delivered error takes a line like any other.
        failures = (listOfNotNull(playerError) + (reading?.failures ?: emptyList()))
            .take(MAX_RECENT_FAILURES),
    )
}

/**
 * The declared bitrate of the video track the player has selected, or null where it has selected
 * none — audio-only content, and any player before its first selection.
 *
 * Read from the **player** rather than from telemetry, which is ADR-0015 rule 11's own sourcing: "the
 * selected rung is the `Player`'s own current track". It is also what makes the row worth anything on
 * a stock `ExoPlayer`, which has no collector to hear a `TrackSwitched` from — leaving the *estimate*
 * as the one half of that comparison telemetry alone can answer, and it is the half no `Player` getter
 * publishes. `Tracks` and `Format` are stable
 * Media3 types and neither appears in a signature here (ADR-0001 rule 2).
 *
 * The peak where the format declares one, as `QoeCollector` reports it, so the two numbers a reader
 * may see side by side are the same quantity: a peak and an average of one rendition differ by enough
 * to look like two rungs.
 */
private fun selectedVideoBitrateBps(player: Player): Int? {
    if (!player.isCommandAvailable(Player.COMMAND_GET_TRACKS)) return null
    player.currentTracks.groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
        for (index in 0 until group.length) {
            if (!group.isTrackSelected(index)) continue
            val format = group.getTrackFormat(index)
            val declared = format.peakBitrate.takeIf { it != Format.NO_VALUE }
                ?: format.bitrate.takeIf { it != Format.NO_VALUE }
            if (declared != null) return declared
        }
    }
    return null
}

/**
 * How often the HUD re-reads everything: twice a second.
 *
 * Fast enough that the buffer and the position look live to a developer watching them move, and slow
 * enough to be irrelevant beside the frames being decoded next to it. It is deliberately *not* tied
 * to the telemetry sampling cadence, which is ten seconds: three of the six rows move with no event,
 * and a HUD that refreshed on samples alone would show a frozen buffer on a playing stream.
 */
private const val HUD_REFRESH_MS: Long = 500

private const val BITS_PER_KILOBIT = 1_000
private const val MILLIS_PER_SECOND = 1_000

/** Read by a reader, never localised: a debug overlay's audience is whoever built the debug build. */
internal const val LABEL_SOURCE = "source"
internal const val LABEL_BITRATE = "bitrate"
internal const val LABEL_BUFFER = "buffer"
internal const val LABEL_DROPPED = "dropped"
internal const val LABEL_ESTIMATE = "estimate"
internal const val LABEL_FAILURES = "failures"
internal const val HUD_UNAVAILABLE = "unavailable"
internal const val HUD_NONE = "none"

/** What the overlay is, for a screen reader and for a test that has to find it. */
internal const val HUD_DESCRIPTION = "SuperPlayer debug HUD"

// Layout. Where the overlay sits and how big it is are the app's, through the modifier, because the
// app owns the screen; what is fixed here is only what makes the rows readable over moving video.
//
// Near-opaque black rather than a tint: the HUD lies over content of unknown brightness, and the one
// thing worse than an overlay covering the video is an overlay nobody can read. Not fully opaque, so
// a developer can still see that frames are arriving underneath it.
private val OverlayBackground = Color(0xD0000000)

// A half-step of Material's 8dp grid inside the app's own placement, so the text does not touch the
// background's edge.
private val OverlayPadding = 8.dp

// Wide enough for the longest label ("estimate", eight monospace characters) plus a word space, so
// every value starts at the same column and a changing number does not move the one below it.
private val LabelWidth = 72.dp

// 11sp: small, because this covers the content, and above the 10sp floor below which a monospace
// digit stops being legible on a dense phone display. Monospace so a number that changes width does
// not shift the row, which is the whole reason a HUD is read as a column of figures.
// The colours are the pair and not two choices: a mid grey label beside a white value, so the eye
// reads down the values column and the labels recede once a reader knows the six by their order.
// Both are light on the near-opaque dark background above, which is the combination that keeps a
// readable contrast over video of any brightness — the reason that background is near-opaque rather
// than a tint.
private val LabelStyle = TextStyle(color = Color(0xFF9E9E9E), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
private val ValueStyle = TextStyle(color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
