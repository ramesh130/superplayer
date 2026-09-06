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
 * The interesting version of this interface is the adaptive one: a policy that watches throughput,
 * buffer occupancy and the transport, and moves the numbers as they change. That work is
 * `superplayer-abr`'s, and writing it now would mean fixing the shape of [PlaybackConditions]
 * against a guess at what an adaptive policy needs to see. Guessing wrong there is expensive —
 * it is a public interface — and the way to guess less is to build the adaptation first and let it
 * say what it needs.
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
 * not measured.
 *
 * ## When the policy is consulted
 *
 * Once, when the player is built. Half of a decision — [BufferPolicy] — can only be applied at
 * construction, because Media3's `DefaultLoadControl` is fixed once the engine has it; consulting
 * the policy repeatedly today would therefore produce answers that could only be half honoured, and
 * a decision that is half in force is worse than one that is documented as construction-time.
 * Consulting it continuously is what `superplayer-abr`'s `AdaptiveLoadControl` makes possible, and
 * it is the same interface when it does.
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
 * What is known about the playback at the moment a [PlaybackPolicy] is asked for a decision.
 *
 * **It carries nothing today, and that is the honest state of it.** The policy is consulted once,
 * when the player is built, and at that moment nothing about the content has been observed: no
 * manifest has been read, no track has been seen, nothing has been played. A type with an `isLive`
 * flag here would be a type reporting an observation nobody made.
 *
 * It exists as a type rather than as no parameter at all because it is the boundary's input, and
 * because what an adaptive policy needs to see — throughput, buffer occupancy, transport, whether
 * the content turned out to be live — is exactly what this grows. Growing it is a source-compatible
 * addition for every implementation that ignores the new property, which is what makes the empty
 * version worth having now instead of a signature change later.
 *
 * ADR-0005 makes the discipline explicit: a property arrives here when an implementation reads it,
 * not in anticipation of one.
 */
public class PlaybackConditions

/** A [PlaybackPolicy]'s answer: how to buffer, and what to cap track selection at. */
public data class PlaybackDecision(
    public val buffer: BufferPolicy,
    public val trackSelection: TrackSelectionPolicy,
)

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
 * The ceiling track selection may not choose above.
 *
 * A ceiling rather than a choice: which of the tracks *under* it to play is adaptive selection's
 * decision, made continuously by the engine from throughput and buffer level, and a policy that
 * pinned a track instead of a ceiling would be taking that decision away at the worst possible
 * moment — the one where the network has just changed.
 */
public data class TrackSelectionPolicy(
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
