package com.superplayer.core

import android.net.Uri
import androidx.media3.common.MediaItem

/**
 * What to play, described as content rather than as a URL.
 *
 * A [MediaItem] answers *where* the bytes are. A [MediaRequest] answers *what is being played*, and
 * keeps that answer separate from the location it currently happens to be served from:
 *
 * ```kotlin
 * player.setMediaRequest(
 *     MediaRequest.Builder("catalog:episode:1138")
 *         .addSource("https://cdn-a.example.com/1138/master.m3u8")
 *         .addSource("https://cdn-b.example.com/1138/master.m3u8")
 *         .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
 *         .build(),
 * )
 * player.prepare()
 * ```
 *
 * ## Why identity is not the URL
 *
 * Keying anything on the source URL produces a well-known family of defects, because one piece of
 * content has many URLs. The same title served from a second CDN host after a failover, or at a
 * second bitrate, or with a re-signed query string, is a different URL every time — so a cache keyed
 * on it misses content it already holds, and an analytics pipeline sessionized on it reports one
 * viewing as several unrelated ones. Neither failure announces itself: playback still works, the
 * cache hit rate is merely worse than it looks and the numbers are merely wrong.
 *
 * [contentId] is the caller's own stable identifier for the content — a catalog id, not a URL. It is
 * what SuperPlayer keys resume positions on today, and what cache keying and telemetry
 * sessionization will key on as they arrive.
 *
 * ## Why more than one source
 *
 * [sources] is ordered, and **only the first is used in this version**. The rest are validated,
 * retained, and deliberately not opened: failing over to the next source is later work, and the
 * shape lands now so that adding it is not a breaking change to every call site that already
 * describes its mirrors. A request carrying three sources plays the first and behaves exactly as one
 * carrying only that source.
 */
public class MediaRequest private constructor(
    /**
     * The caller's stable identifier for the content, in whatever namespace the caller already uses.
     *
     * Must not be blank. It should be stable across CDN hosts, bitrates and re-signed URLs — that
     * stability is the whole value of it — which is why a source URL makes a poor one, though
     * nothing here can check that a caller has chosen well.
     */
    public val contentId: String,
    /**
     * Candidate locations for the content, most-preferred first. Never empty; only the first entry
     * is used in this version, for the reasons in the class documentation.
     */
    public val sources: List<Uri>,
    /** Where playback of this request begins. Defaults to [StartPosition.Beginning]. */
    public val startPosition: StartPosition,
) {

    /**
     * Where playback begins, as a decision rather than as a number a caller has to compute.
     *
     * The alternative — a nullable `startPositionMs` — cannot express "resume", so every consumer
     * ends up hand-rolling the same seek-on-ready listener, and each one gets a slightly different
     * answer for content that had already finished.
     */
    public sealed class StartPosition {

        /**
         * The content's own default position: the start of on-demand content, and the live edge of a
         * live stream. Deliberately not "zero" — zero is not where a live stream should begin.
         */
        public data object Beginning : StartPosition()

        /**
         * An explicit position, in milliseconds from the start of the content.
         *
         * A position beyond the content's duration is **clamped to the end of the content**: the
         * player becomes ready at the last moment of it, and playing from there immediately reaches
         * [androidx.media3.common.Player.STATE_ENDED].
         *
         * The duration is not known until the content has been prepared, so a too-large position
         * cannot be rejected here — and it is deliberately not reinterpreted as the start either. A
         * position past the end means the caller's stored position is wrong, and content that
         * instantly ends shows that; content that quietly restarts looks like a successful playback
         * of the wrong thing.
         */
        public data class At(public val positionMs: Long) : StartPosition() {
            init {
                require(positionMs >= 0) { "A start position must not be negative, was $positionMs" }
            }
        }

        /**
         * Resume where this request's [MediaRequest.contentId] was last left, or [Beginning] if
         * this player has no position for it.
         *
         * "Last known" means what this [SuperPlayer] instance observed: positions are remembered in
         * memory, for the life of the player, and are not persisted. Surviving process death — or a
         * configuration change that releases the player — needs a store of the consumer's own, and
         * that is deliberately not invented here.
         *
         * The memory is also bounded: the [SuperPlayer.MAX_REMEMBERED_POSITIONS] most recently used
         * content ids are kept and the least recently used is dropped, so a session that moves
         * through more content than that resumes the oldest of it from [Beginning]. Resuming is
         * therefore a best effort rather than a guarantee — which is the honest contract for
         * something a consumer has not asked to be stored anywhere.
         *
         * Content that played to the end resumes from [Beginning]: resuming to the end would
         * produce a player that immediately ends again, which is nobody's intent.
         */
        public data object ResumeFromLastKnown : StartPosition()
    }

    /** Builds a [MediaRequest]. Media3's construction idiom — see ADR-0001 and CONTRIBUTING rule 3. */
    public class Builder(private val contentId: String) {

        private val sources = mutableListOf<Uri>()
        private var startPosition: StartPosition = StartPosition.Beginning

        /** Appends a candidate source. Order is preference order; the first is the one used. */
        public fun addSource(uri: Uri): Builder = apply { sources += uri }

        /** Appends a candidate source, parsed as a [Uri]. */
        public fun addSource(uri: String): Builder = addSource(Uri.parse(uri))

        public fun setStartPosition(startPosition: StartPosition): Builder =
            apply { this.startPosition = startPosition }

        public fun build(): MediaRequest {
            require(contentId.isNotBlank()) { "A MediaRequest needs a non-blank content id" }
            require(sources.isNotEmpty()) {
                "A MediaRequest for '$contentId' needs at least one source"
            }
            return MediaRequest(contentId, sources.toList(), startPosition)
        }
    }
}

/**
 * The request as the engine sees it: the first source, carrying [MediaRequest.contentId] as the
 * item's media id.
 *
 * Media3's own field for a caller's identifier is [MediaItem.mediaId], so the identity travels with
 * the item through everything that already handles one — `MediaSession`, `Player.Listener`
 * callbacks, the timeline — instead of being held alongside it in a map SuperPlayer would have to
 * keep in step. It is also how a resume position is recognised as belonging to this content when the
 * player later moves away from it.
 */
internal fun MediaRequest.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(contentId)
        .setUri(sources.first())
        .build()
