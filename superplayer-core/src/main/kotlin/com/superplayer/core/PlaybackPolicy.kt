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

/**
 * The policy boundary: observed playback conditions in, a playback decision out.
 *
 * Everything SuperPlayer decides about *how* to play — how much to buffer, what to cap track
 * selection at — is decided here and nowhere else. The engine is on the other side of this
 * interface: no type in [PlaybackConditions] or [PlaybackDecision] comes from Media3, and no
 * implementation of this interface is handed a `Player`, a `LoadControl` or a `TrackSelector`. That
 * is the point of it existing at this stage, before there is anything adaptive to put behind it.
 *
 * ## Why the boundary comes before the adaptation
 *
 * The interesting version of this interface is the adaptive one: a policy that watches throughput
 * and the transport, and moves the numbers as they change. That work is `superplayer-abr`'s. The
 * boundary was published first with an *empty* [PlaybackConditions], so that its shape could be
 * fixed by what the adaptation turned out to read rather than by a guess; ADR-0009 is where that
 * was decided, and the type now carries exactly the observations it names.
 *
 * What is cheap to get right now is the *boundary*: making sure policy is reached through one
 * interface rather than being spread across Media3 callbacks and builder calls at the point where
 * a `LoadControl` happens to be constructed. Code scattered that way is what makes the adaptive
 * version a rewrite instead of a new implementation of this interface.
 *
 * ## The implementation shipped today is deliberately not adaptive
 *
 * [forProfile] returns a **static per-profile lookup**: the same [PlaybackDecision] for a given
 * [PlaybackProfile] whatever the conditions, every time. It ignores its argument, on purpose. A
 * consumer choosing a profile today gets a fixed, documented configuration — which is already the
 * thing most apps do not have — and gets it without SuperPlayer pretending to an adaptivity it has
 * not measured. A consumer who wants something else supplies it through
 * [SuperPlayer.Builder.setPolicy], which takes any implementation of this interface.
 *
 * ## When the policy is consulted
 *
 * At construction, always. Again on a fixed set of named triggers — the transport changes, the
 * stream type becomes known or changes, a rebuffer ends, the playback speed changes, the bandwidth
 * meter reports a material move — but **only on a player whose engine was built with components
 * that can honour a changed decision whole**, which today means one built with `superplayer-abr`'s
 * policy. On any other player the policy is consulted once, whatever policy it is: Media3's
 * `DefaultLoadControl` takes its durations at construction and does not accept new ones, so a
 * re-consultation there could only have half of its answer applied, and a decision that is half in
 * force is worse than one documented as construction-time. ADR-0009 rules 4 and 5 are the two
 * halves of that contract; [DecisionTrigger] is the list of triggers; [SuperPlayer.playbackDecision]
 * is the decision currently in force and `TelemetryEvent.DecisionChanged` is how a change is seen.
 *
 * Not on a cadence and not on every observation, because a policy asked once per bandwidth sample
 * oscillates and a policy asked on a timer is asked with nothing new to say. Hysteresis is the
 * policy's own: [PlaybackConditions.stallHistory] says how long ago the last stall ended, and a
 * policy that wants a cooldown holds its previous answer until that is large enough.
 */
public fun interface PlaybackPolicy {

    /**
     * The configuration to play with, given what is currently known about the playback.
     *
     * Must be a pure function of [conditions] — called on the thread that builds or drives the
     * player, and expected to be cheap and to block on nothing.
     */
    public fun decide(conditions: PlaybackConditions): PlaybackDecision

    public companion object {

        /**
         * The static per-profile lookup described in this interface's documentation: [profile]'s
         * documented configuration, for every set of conditions.
         *
         * This is what [SuperPlayer.Builder.setProfile] installs. It is public so that an app can
         * *read* what a profile asks for without building a player — to log it, or to show it in a
         * settings screen next to the switch that chooses it.
         *
         * It is not a test seam. A test that asserts on what this returns is asserting that a table
         * contains what the table contains; what is worth pinning is that the player a consumer
         * built is running it, which is only observable through the facade (`docs/testing.md`).
         */
        public fun forProfile(profile: PlaybackProfile): PlaybackPolicy = StaticProfilePolicy(profile)
    }
}

/**
 * The named events on which a [PlaybackPolicy] is consulted again after construction, and the only
 * ones (ADR-0009 rule 4).
 *
 * Each is a fact a reader can name, which is what makes a series of decisions legible after the
 * fact: `TelemetryEvent.DecisionChanged` carries the trigger that produced it, and "every ten
 * seconds" would not be a reason. A consultation that returns the decision already in force
 * produces no change and no event.
 */
public enum class DecisionTrigger {
    /** The device moved to a different [NetworkTransport]. */
    TRANSPORT_CHANGED,

    /** The manifest said what the content is — or, for a live playlist that ended, what it became. */
    STREAM_TYPE_CHANGED,

    /** A rebuffer ended and playback resumed; [PlaybackConditions.stallHistory] has moved. */
    REBUFFER_ENDED,

    /** The playback speed changed. */
    PLAYBACK_SPEED_CHANGED,

    /**
     * The bandwidth meter reported that its estimate moved materially, where "materially" is a
     * threshold the meter applies to its own estimate before saying anything, so that a policy is
     * never consulted once per sample.
     */
    THROUGHPUT_CHANGED,
}

/**
 * A [PlaybackPolicy]'s answer: how to buffer, what to cap track selection at, and — for content
 * the manifest declared live — how to hold the live window.
 *
 * [liveLatency] is null for every decision about on-demand content and for any policy that has
 * nothing to say about live playback: null means the engine's own live behaviour, unchanged. It is
 * a third half rather than a field of [BufferPolicy] because it is applied by a different route —
 * the buffer durations become a load control, the speed range travels on the media item — and
 * a component that can re-target one has nothing to do with the other.
 */
public data class PlaybackDecision
@JvmOverloads
constructor(
    public val buffer: BufferPolicy,
    public val trackSelection: TrackSelectionPolicy,
    public val liveLatency: LiveLatencyPolicy? = null,
)

/**
 * How a live window is held: the range of playback speeds the player may drift through to stay at
 * its target distance behind the live edge.
 *
 * The standard low-latency technique. A live player that falls behind its target — a stall, a
 * pause, a slow segment — has two ways back: a seek, which the viewer sees, or playing a little
 * faster than real time until it has caught up, which at a few percent they do not. The same in
 * the other direction when it runs ahead. The engine measures the offset error and picks a speed
 * inside this range; the range is the policy's because how much drift a viewer tolerates is a
 * product decision and not an engine constant.
 *
 * What the player runs is this range, over both the engine's own default and any the manifest
 * declares: for an ordinary live stream — one whose manifest carries no low-latency hints — Media3
 * adjusts speed not at all unless the item it plays says it may, so a policy that decides a range
 * is the difference between a live window that is held and one that is seeked back to. A policy
 * that decides nothing (a null half) leaves the stream to the manifest and the engine.
 *
 * ref: https://developer.android.com/media/media3/exoplayer/live-streaming
 */
public data class LiveLatencyPolicy(
    /** The slowest the player may play to fall back toward its target. At most `1.0`. */
    public val minPlaybackSpeed: Float,
    /** The fastest the player may play to catch up to its target. At least `1.0`. */
    public val maxPlaybackSpeed: Float,
) {
    init {
        require(minPlaybackSpeed > 0f && minPlaybackSpeed <= 1f) {
            "minPlaybackSpeed must be in (0, 1], was $minPlaybackSpeed"
        }
        require(maxPlaybackSpeed >= 1f) { "maxPlaybackSpeed must be at least 1, was $maxPlaybackSpeed" }
    }
}

/**
 * How much to buffer, in milliseconds.
 *
 * The four durations are the ones every Media3 app ends up setting and few can explain: the range
 * the player keeps ahead of the playhead, and the two floors it will start playing at. They are
 * named as Media3 names them, deliberately — a consumer comparing a profile against the
 * `DefaultLoadControl` configuration they already have should not have to translate.
 *
 * ref: https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl
 */
public data class BufferPolicy(
    /** Buffer ahead of the playhead below which the player asks the source for more data. */
    public val minBufferMs: Int,
    /** Buffer ahead of the playhead at which the player stops asking for more. */
    public val maxBufferMs: Int,
    /** Buffer required before playback starts or resumes after a seek. */
    public val bufferForPlaybackMs: Int,
    /** Buffer required before playback resumes after it ran dry. Higher, to avoid a second stall. */
    public val bufferForPlaybackAfterRebufferMs: Int,
    /** Media kept *behind* the playhead, so a short seek back does not re-download it. */
    public val backBufferMs: Int,
    /**
     * Whether the back buffer is retained from the last keyframe before the playhead, rather than
     * from exactly [backBufferMs] before it. Retaining from the keyframe is what makes a seek back
     * into the buffer resumable without a re-download; it costs a little more memory.
     */
    public val retainBackBufferFromKeyframe: Boolean,
) {
    init {
        require(minBufferMs > 0) { "minBufferMs must be positive, was $minBufferMs" }
        require(maxBufferMs >= minBufferMs) {
            "maxBufferMs ($maxBufferMs) must be at least minBufferMs ($minBufferMs)"
        }
        require(bufferForPlaybackMs in 1..minBufferMs) {
            "bufferForPlaybackMs ($bufferForPlaybackMs) must be positive and at most " +
                "minBufferMs ($minBufferMs)"
        }
        require(bufferForPlaybackAfterRebufferMs in 1..minBufferMs) {
            "bufferForPlaybackAfterRebufferMs ($bufferForPlaybackAfterRebufferMs) must be " +
                "positive and at most minBufferMs ($minBufferMs)"
        }
        require(backBufferMs >= 0) { "backBufferMs must not be negative, was $backBufferMs" }
    }
}

/**
 * The ceiling track selection may not choose above, and the pace it moves at under it.
 *
 * A ceiling rather than a choice: which of the tracks *under* it to play is adaptive selection's
 * decision, made continuously by the engine from throughput and buffer level, and a policy that
 * pinned a track instead of a ceiling would be taking that decision away at the worst possible
 * moment — the one where the network has just changed.
 */
public data class TrackSelectionPolicy
@JvmOverloads
constructor(
    /** Ceiling on a video track's declared bitrate in bits per second, or [UNLIMITED]. */
    public val maxVideoBitrateBps: Int,
    /**
     * Ceiling on a video track's height in pixels, or [UNLIMITED].
     *
     * Height rather than a width-and-height pair because height is what names a rung of every
     * rendition ladder in use — 480, 720, 1080 — and a ceiling expressed in the vocabulary the
     * content was encoded in is one a consumer can check against their own ladder.
     */
    public val maxVideoHeightPx: Int,
    /**
     * How eagerly selection climbs and descends under the ceiling, or null for the engine's own
     * pace. Null is not "decided the engine's defaults": as for [PlaybackDecision.liveLatency], it
     * leaves the engine exactly as it would otherwise be built.
     */
    public val pace: SelectionPace? = null,
) {
    init {
        require(maxVideoBitrateBps > 0) {
            "maxVideoBitrateBps must be positive, was $maxVideoBitrateBps"
        }
        require(maxVideoHeightPx > 0) { "maxVideoHeightPx must be positive, was $maxVideoHeightPx" }
    }

    public companion object {
        /**
         * No ceiling: every track the content offers is selectable.
         *
         * The value is Media3's own "unset" for these parameters, so a policy that caps nothing and
         * a consumer who has set nothing produce identical `TrackSelectionParameters`.
         */
        public const val UNLIMITED: Int = Int.MAX_VALUE
    }
}

/**
 * How adaptive selection moves under a [TrackSelectionPolicy]'s ceiling: how much media must be
 * buffered before it climbs, how little before it descends at once, how much of a rung already
 * buffered it keeps when it climbs, and how much of the throughput estimate a rung may spend.
 *
 * Pace rather than a choice, for the ceiling's own reason: which rung plays stays the engine's
 * decision, made on every chunk. What a policy decides is the hysteresis around it — a climb that
 * waits for a cushion is one the next dip does not undo, and a descent that waits for nothing is
 * a stall avoided — and the right hysteresis depends on the buffer the same policy decided. A
 * climb threshold above the most media the buffer may hold is a climb that never happens, which
 * is why the pace travels in the decision beside the buffer rather than in a table beside the
 * engine: only a policy that decided both can keep one within reach of the other.
 *
 * Named as Media3's `AdaptiveTrackSelection` names the values, less its vocabulary of minimums
 * and maximums, so a consumer comparing a pace against their own selector's configuration can.
 *
 * ref: https://developer.android.com/reference/androidx/media3/exoplayer/trackselection/AdaptiveTrackSelection.Factory
 */
public data class SelectionPace(
    /** Media buffered ahead of the playhead before selection may choose a higher rung. */
    public val climbAfterBufferedMs: Int,
    /**
     * The buffer below which selection chooses a lower rung as soon as the estimate asks for one.
     * Above it a descent waits, because the buffer can absorb the shortfall for a while.
     */
    public val descendBelowBufferedMs: Int,
    /** Media of an already-buffered rung that is kept, rather than discarded and refetched, on a climb. */
    public val retainAfterDiscardMs: Int,
    /** The share of the throughput estimate a chosen rung may use, the rest being headroom. In (0, 1]. */
    public val bandwidthFraction: Float,
) {
    init {
        require(climbAfterBufferedMs >= 0) { "climbAfterBufferedMs must not be negative, was $climbAfterBufferedMs" }
        require(descendBelowBufferedMs >= 0) { "descendBelowBufferedMs must not be negative, was $descendBelowBufferedMs" }
        require(retainAfterDiscardMs >= 0) { "retainAfterDiscardMs must not be negative, was $retainAfterDiscardMs" }
        require(bandwidthFraction > 0f && bandwidthFraction <= 1f) {
            "bandwidthFraction must be in (0, 1], was $bandwidthFraction"
        }
    }

    public companion object {
        /**
         * The engine's own pace, stated: Media3's `AdaptiveTrackSelection` defaults of 10 s to
         * climb, 25 s to descend, 25 s retained and 70 % of the estimate. What a selection that
         * reads a pace uses where the decision carries none.
         */
        @JvmField
        public val ENGINE_DEFAULT: SelectionPace = SelectionPace(
            climbAfterBufferedMs = 10_000,
            descendBelowBufferedMs = 25_000,
            retainAfterDiscardMs = 25_000,
            bandwidthFraction = 0.7f,
        )
    }
}
