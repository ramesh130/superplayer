package com.superplayer.core

/**
 * What kind of playback this is, chosen once at construction:
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setProfile(PlaybackProfile.LIVE_LINEAR)
 *     .build()
 * ```
 *
 * A profile is a *name for a use case*, not a bag of settings. The settings it produces — how much
 * to buffer, what to cap track selection at — are [PlaybackPolicy]'s answer, and the numbers live
 * with the reasoning for them in that policy's per-profile table rather than here, so that the
 * profile a consumer names in their code does not change when the policy behind it is retuned.
 *
 * ## Why a named profile rather than loose constants
 *
 * The alternative is what every app currently does: copy four `DefaultLoadControl` numbers out of a
 * StackOverflow answer, never revisit them, and discover in production that the values were tuned
 * for someone else's content. The numbers are not the hard part — knowing *which* numbers a use case
 * wants is, and that knowledge is what a profile carries. A consumer who picks
 * [VIDEO_ON_DEMAND] over [SHORT_FORM] can read why the two differ and defend the choice in review,
 * which is not something `setBufferDurationsMs(30000, 60000, 2500, 5000)` at a call site allows.
 *
 * ## What a profile is not
 *
 * It is not a quality setting a user picks in a settings screen, and it is not adaptive. A profile
 * says what *shape* the playback has; adapting within that shape to the network, the device and the
 * buffer is `superplayer-abr`'s subject, and it arrives behind the same [PlaybackPolicy] boundary
 * these profiles are already reached through.
 */
public enum class PlaybackProfile {

    /**
     * Long-form on-demand content — a film, an episode, a lecture.
     *
     * The default, because it is the case with the widest tolerance: the content is worth waiting a
     * moment for, the viewer is committed to it, and a rebuffer half an hour in is far more costly
     * than a slightly slower start. Buffers are deep and quality is uncapped.
     */
    VIDEO_ON_DEMAND,

    /**
     * A live linear channel — a stream with a moving edge that the viewer expects to be *at*.
     *
     * Buffering deeply here is not caution, it is falling behind: every buffered second is a second
     * of latency behind live that playback will not give back on its own. The trade is a lower
     * rebuffer margin in exchange for staying near the edge.
     */
    LIVE_LINEAR,

    /**
     * Short-form feed content — clips of seconds, in a list the viewer swipes through.
     *
     * The dominant cost is time-to-first-frame and data spent on clips nobody watched: a viewer who
     * swipes away after two seconds has paid for whatever was buffered ahead. Start fast, buffer
     * little.
     */
    SHORT_FORM,

    /**
     * The viewer has asked to spend less data, or the app has decided on their behalf.
     *
     * The only profile that caps quality rather than only shaping buffering, and the only one whose
     * ceilings a consumer can *see* in the picture. It is a deliberate trade of resolution for
     * bytes, which is the trade the viewer asked for.
     */
    DATA_SAVER,
}
