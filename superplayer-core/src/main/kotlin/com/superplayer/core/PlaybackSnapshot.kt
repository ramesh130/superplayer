package com.superplayer.core

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player

/**
 * What a [SuperPlayer] was doing, in a form that outlives the player itself.
 *
 * A configuration change destroys the Activity holding a player, and a `SuperPlayer` deliberately
 * remembers nothing beyond its own lifetime — so without something like this, rotating a device
 * restarts the video. That defect is not Media3's: the engine has no opinion about Activities. It
 * belongs to whoever owns the player, which today means every consuming app writes the same
 * bookkeeping, and each one gets a slightly different answer for content that had ended, for content
 * that was paused, and for the positions of everything the session had already watched.
 *
 * ```kotlin
 * // In the Activity, Fragment or state holder that owns the player:
 * override fun onSaveInstanceState(outState: Bundle) {
 *     super.onSaveInstanceState(outState)
 *     outState.putBundle(PLAYBACK, player.saveSnapshot().toBundle())
 * }
 *
 * // ...and on the way back, on a freshly built player:
 * savedInstanceState?.getBundle(PLAYBACK)
 *     ?.let { player.restoreSnapshot(PlaybackSnapshot.fromBundle(it)) }
 * player.prepare()
 * ```
 *
 * ## What survives, and what does not
 *
 * A snapshot carries what was playing ([request]), where it had got to ([positionMs]), whether the
 * user wanted it playing ([playWhenReady]), and the resume positions the player had accumulated for
 * everything else it had been asked for — so
 * [MediaRequest.StartPosition.ResumeFromLastKnown] keeps working across the change rather than
 * quietly falling back to the beginning, which is the failure that is hardest to notice.
 *
 * It does not carry the [PlaybackProfile]. A profile is half applied to an engine as it is built,
 * so it belongs to the *construction* of the replacement player rather than to the state poured into
 * it, and an app that offers the choice is already remembering it. Nor does it carry anything set
 * through [Player.setMediaItem] rather than [SuperPlayer.setMediaRequest]: a `MediaItem` has no
 * stable identity to restore under — the same reason [MediaRequest] exists — so [request] is null
 * for one, and restoring such a snapshot restores the positions and the intent to play but no
 * content. Media3's own `MediaItem` bundling is the tool for that case.
 *
 * ## Why a `Bundle`, and why not `Parcelable`
 *
 * [toBundle] and [fromBundle] are Media3's own convention for a type that has to cross a process
 * boundary — `MediaItem`, `Tracks` and `PlaybackException` all carry the same pair — and a `Bundle`
 * is what both `onSaveInstanceState` and `rememberSaveable` already take. `Parcelable` would bind
 * this class's field order to a binary format that a later field would break, for no gain: nothing
 * here is hot enough for the difference to be measurable.
 *
 * A snapshot is a value: taking one does not change the player, and holding one keeps nothing alive.
 */
public class PlaybackSnapshot internal constructor(
    /**
     * What was playing, or null if nothing was — including the case where the item came from
     * [Player.setMediaItem] and therefore has no [MediaRequest.contentId] to be restored under.
     *
     * Carries the [MediaRequest.StartPosition] the request was originally made with, which is a
     * record of what was asked for rather than of what happened: where playback had actually reached
     * is [positionMs], and that is what [SuperPlayer.restoreSnapshot] resumes from.
     */
    public val request: MediaRequest?,
    /**
     * Where [request] had got to, in milliseconds, or [C.TIME_UNSET] when there is no [request].
     *
     * Content that had played to the end is recorded at the beginning, for the reason
     * [MediaRequest.StartPosition.ResumeFromLastKnown] gives: restoring to the end would produce a
     * player that immediately ends again.
     *
     * Measured from the start of the current content, ads excluded — the same origin
     * [SuperPlayer.restoreSnapshot] hands back to the engine, so an ad playing at the moment the
     * snapshot was taken restores the content underneath it rather than the ad.
     *
     * **Live content is the case to be careful with.** A live window slides, so a position saved
     * against it means something slightly different by the time it is restored, and a long gap
     * between the two can put it outside the window entirely. That is a property of absolute
     * positions rather than of this type — [MediaRequest.StartPosition.ResumeFromLastKnown] has it
     * too — and an app that would rather rejoin a live stream at the edge should ignore this field
     * and re-request the content with [MediaRequest.StartPosition.Beginning], which *is* the live
     * edge for a live stream.
     */
    public val positionMs: Long,
    /**
     * Whether the player was playing, or trying to — [Player.getPlayWhenReady], which is the user's
     * intent rather than the engine's state.
     *
     * Restoring it is what makes a rotation invisible to someone watching, and what stops a
     * deliberate pause from being undone by one.
     */
    public val playWhenReady: Boolean,
    /**
     * Every position the player had remembered, keyed by [MediaRequest.contentId] — what
     * [MediaRequest.StartPosition.ResumeFromLastKnown] reads — including [request]'s own.
     *
     * Internal because it is the player's memory rather than a description of what was on screen: a
     * consumer inspecting a snapshot wants the three fields above, and one that wants a position for
     * some other content should ask the player it restored this into.
     */
    internal val rememberedPositions: Map<String, Long>,
) {

    /** This snapshot as a [Bundle], for `onSaveInstanceState`, `rememberSaveable`, or a `ViewModel`. */
    public fun toBundle(): Bundle = Bundle().apply {
        putLong(KEY_POSITION_MS, positionMs)
        putBoolean(KEY_PLAY_WHEN_READY, playWhenReady)
        request?.let { putBundle(KEY_REQUEST, it.toBundle()) }
        // Two parallel arrays rather than a nested Bundle per entry: a `Bundle` keyed by content id
        // would work, but content ids are the caller's own strings and nothing stops one colliding
        // with a key of ours. Parallel arrays cannot collide with anything.
        putStringArrayList(KEY_REMEMBERED_IDS, ArrayList(rememberedPositions.keys))
        putLongArray(KEY_REMEMBERED_POSITIONS, rememberedPositions.values.toLongArray())
    }

    public companion object {

        /**
         * Reads back what [PlaybackSnapshot.toBundle] wrote.
         *
         * A bundle that is missing or malformed in any part restores as far as it can rather than
         * throwing: this is state that crossed process death, and the cost of a field that did not
         * survive is a resumed position, while the cost of an exception is a crash on the way back
         * into the app. What cannot be read is simply absent — a snapshot with no [request] restores
         * the intent to play and nothing else.
         */
        public fun fromBundle(bundle: Bundle): PlaybackSnapshot {
            val ids = bundle.getStringArrayList(KEY_REMEMBERED_IDS).orEmpty()
            val positions = bundle.getLongArray(KEY_REMEMBERED_POSITIONS) ?: LongArray(0)

            return PlaybackSnapshot(
                request = bundle.getBundle(KEY_REQUEST)?.toMediaRequest(),
                positionMs = bundle.getLong(KEY_POSITION_MS, C.TIME_UNSET),
                playWhenReady = bundle.getBoolean(KEY_PLAY_WHEN_READY, false),
                // Zipped to the shorter of the two, so a truncated bundle costs the entries it lost
                // rather than every entry.
                rememberedPositions = ids.zip(positions.toTypedArray()).toMap(),
            )
        }

        private const val KEY_REQUEST = "request"
        private const val KEY_POSITION_MS = "positionMs"
        private const val KEY_PLAY_WHEN_READY = "playWhenReady"
        private const val KEY_REMEMBERED_IDS = "rememberedIds"
        private const val KEY_REMEMBERED_POSITIONS = "rememberedPositions"
    }
}

/**
 * A [MediaRequest] as a [Bundle]. The counterpart of [toMediaRequest].
 *
 * Internal rather than a public `MediaRequest.toBundle()`: a request is something a consumer builds
 * from what they already know about their own catalog, so there is no case yet for one crossing a
 * process boundary on its own. It crosses inside a [PlaybackSnapshot], which is where the need
 * actually arose.
 */
private fun MediaRequest.toBundle(): Bundle = Bundle().apply {
    putString(KEY_CONTENT_ID, contentId)
    putStringArrayList(KEY_SOURCES, ArrayList(sources.map { it.toString() }))
    when (val position = startPosition) {
        is MediaRequest.StartPosition.Beginning -> putInt(KEY_START_KIND, START_BEGINNING)
        is MediaRequest.StartPosition.ResumeFromLastKnown ->
            putInt(KEY_START_KIND, START_RESUME_FROM_LAST_KNOWN)

        is MediaRequest.StartPosition.At -> {
            putInt(KEY_START_KIND, START_AT)
            putLong(KEY_START_POSITION_MS, position.positionMs)
        }
    }
}

/**
 * Reads back what [toBundle] wrote, or null if what it wrote cannot make a valid [MediaRequest].
 *
 * Null rather than an exception, and null rather than a request with invented parts: a blank content
 * id or an empty source list is what a truncated or foreign bundle looks like, and
 * [MediaRequest.Builder] would rightly reject either. The caller's fallback is a player that starts
 * from nothing, which is recoverable; a crash while restoring an Activity is not.
 */
private fun Bundle.toMediaRequest(): MediaRequest? {
    val contentId = getString(KEY_CONTENT_ID) ?: return null
    val sources = getStringArrayList(KEY_SOURCES).orEmpty()
    if (contentId.isBlank() || sources.isEmpty()) return null

    val startPosition = when (getInt(KEY_START_KIND, START_BEGINNING)) {
        // Clamped, because `At` rightly rejects a negative position and a bundle that carries one
        // is corrupt rather than expressive.
        START_AT ->
            MediaRequest.StartPosition.At(getLong(KEY_START_POSITION_MS, 0L).coerceAtLeast(0L))
        START_RESUME_FROM_LAST_KNOWN -> MediaRequest.StartPosition.ResumeFromLastKnown
        else -> MediaRequest.StartPosition.Beginning
    }

    return MediaRequest.Builder(contentId)
        .apply { sources.forEach { addSource(Uri.parse(it)) } }
        .setStartPosition(startPosition)
        .build()
}

private const val KEY_CONTENT_ID = "contentId"
private const val KEY_SOURCES = "sources"
private const val KEY_START_KIND = "startKind"
private const val KEY_START_POSITION_MS = "startPositionMs"

// Written rather than derived from an ordinal, so that reordering `StartPosition`'s subclasses
// cannot silently change what an already-saved bundle means.
private const val START_BEGINNING = 0
private const val START_AT = 1
private const val START_RESUME_FROM_LAST_KNOWN = 2
