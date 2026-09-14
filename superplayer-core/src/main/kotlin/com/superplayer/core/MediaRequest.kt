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

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

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
 *
 * ## Why a request carries what to display
 *
 * [title], [subtitle] and [artworkUri] are here because content that leaves the app has to describe
 * itself. A notification, a lock screen, a car head unit and a watch face all show what is playing,
 * and none of them can ask the app — they read the `MediaMetadata` the session publishes. A request
 * that carried only a URL would produce a notification with a blank title, which is the default an
 * app gets today and the one nobody notices until the first screenshot.
 *
 * All three are optional, and a player with no session shows them nowhere. Nothing about playback
 * behaves differently for their absence.
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
    /**
     * What to call this content wherever it is displayed outside the app — the notification, the
     * lock screen, Android Auto, a wearable.
     *
     * Reaches those surfaces as [MediaMetadata.title]. Null means "nothing to show", which is what
     * every one of them renders as an empty line.
     */
    public val title: String?,
    /**
     * The second line beneath [title] — a series name, a channel, a byline.
     *
     * Reaches Media3's own notification as [MediaMetadata.artist], which is the field that provider
     * reads for its second line, and Android Auto's browse and playback surfaces as
     * [MediaMetadata.subtitle]. Both are set from this one value rather than making a caller decide
     * which surface they are writing for.
     */
    public val subtitle: String?,
    /**
     * Artwork for the content, as a URI the *platform* fetches — not the app.
     *
     * Reaches those surfaces as [MediaMetadata.artworkUri]. A `content://`, `file://` or `https://`
     * URI all work; Media3's notification provider loads it through the bitmap loader the session
     * was built with.
     */
    public val artworkUri: Uri?,
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
         * memory, for the life of the player, and are not persisted. Outliving the player — a
         * configuration change, or process death — is what [PlaybackSnapshot] is for: it hands the
         * whole of this memory over as a `Bundle`, and where that `Bundle` is kept stays the
         * consumer's decision rather than a storage mechanism chosen on their behalf.
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
        private var title: String? = null
        private var subtitle: String? = null
        private var artworkUri: Uri? = null

        /** Appends a candidate source. Order is preference order; the first is the one used. */
        public fun addSource(uri: Uri): Builder = apply { sources += uri }

        /** Appends a candidate source, parsed as a [Uri]. */
        public fun addSource(uri: String): Builder = addSource(Uri.parse(uri))

        public fun setStartPosition(startPosition: StartPosition): Builder =
            apply { this.startPosition = startPosition }

        /** What to call this content wherever it is displayed outside the app. */
        public fun setTitle(title: String?): Builder = apply { this.title = title }

        /** The second line beneath the title — a series name, a channel, a byline. */
        public fun setSubtitle(subtitle: String?): Builder = apply { this.subtitle = subtitle }

        /** Artwork for the content, as a URI the platform fetches. */
        public fun setArtworkUri(artworkUri: Uri?): Builder = apply { this.artworkUri = artworkUri }

        /** Artwork for the content, as a URI string the platform fetches. */
        public fun setArtworkUri(artworkUri: String): Builder = setArtworkUri(Uri.parse(artworkUri))

        public fun build(): MediaRequest {
            require(contentId.isNotBlank()) { "A MediaRequest needs a non-blank content id" }
            require(sources.isNotEmpty()) {
                "A MediaRequest for '$contentId' needs at least one source"
            }
            return MediaRequest(
                contentId,
                sources.toList(),
                startPosition,
                title,
                subtitle,
                artworkUri,
            )
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
 *
 * The display fields travel as Media3's own [MediaMetadata], which is what a `MediaSession`
 * publishes to everything outside the app. They are attached to the *item* rather than pushed onto
 * the player, so they change when the content does and cannot go stale against it.
 *
 * [identified] lays a [ContentIdentity] on the item as its tag, which is how the identity reaches a
 * cache's key. A player with no cache passes false, so its items are exactly what they were before a
 * cache existed (ADR-0010 rule 13).
 */
internal fun MediaRequest.toMediaItem(identified: Boolean = false): MediaItem =
    MediaItem.Builder()
        .setMediaId(contentId)
        .setUri(sources.first())
        .setMediaMetadata(toMediaMetadata())
        .apply { if (identified) setTag(ContentIdentity(contentId)) }
        .build()

/**
 * The display half of a request, in the vocabulary every external surface reads.
 *
 * [MediaRequest.subtitle] is written to both [MediaMetadata.artist] and [MediaMetadata.subtitle]
 * because the two surfaces that matter read different ones: Media3's `DefaultMediaNotificationProvider`
 * takes its second line from `artist`, and Android Auto takes its from `subtitle`. Making a caller
 * choose between them would be making them name a surface they cannot see from here.
 *
 * `isPlayable` is set because a session that does not say so is treated by a browsing controller —
 * Android Auto, Assistant — as a folder it should try to open rather than as something it can play.
 *
 * ref: https://developer.android.com/media/media3/session/background-playback#notification
 */
private fun MediaRequest.toMediaMetadata(): MediaMetadata =
    MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(subtitle)
        .setSubtitle(subtitle)
        .setArtworkUri(artworkUri)
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .build()
