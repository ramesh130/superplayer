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

import android.app.PendingIntent
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A [SuperPlayer] published to the rest of Android: the notification, the lock screen, a Bluetooth
 * remote, a watch, Android Auto.
 *
 * ```kotlin
 * val session = PlaybackSession.Builder(context, player)
 *     .setMediaRequestResolver { contentId -> catalog.requestFor(contentId) }
 *     .build()
 *
 * // ...and when the player's life ends, one call ends both:
 * session.release()
 * ```
 *
 * A player that nothing outside the app can see is a player the platform treats as an anonymous
 * noise source: the media controls in the shade show nothing, a headset's pause button does nothing,
 * and the system has no way to tell the user which app is holding the audio. A session is what fixes
 * all of that, and it is one object — but it is one object whose lifecycle, ordering and content
 * resolution every app gets subtly wrong in the same three ways. Those three are what this type is.
 *
 * ## What it adds over `MediaSession.Builder(context, player)`
 *
 * **One lifetime, not two.** A `MediaSession` holds a `Player` and must be released *before* it, and
 * a session outliving its player is a crash the next time a controller touches it. Here there is one
 * object and one [release], and releasing it releases the player it was built for — see [release].
 *
 * **A session activity by default.** Tapping the notification has to open the app, and a session
 * with no `PendingIntent` opens nothing. The app's own launcher intent is the right answer for
 * almost every app and is what a session gets unless [Builder.setSessionActivity] says otherwise.
 *
 * **Content identity survives the boundary.** A controller does not speak SuperPlayer: everything
 * outside the app has only Media3's `Player` API, so a car head unit asking to play something can
 * name a `MediaItem` but cannot make a [MediaRequest]. Without a translation, content started from
 * outside the app loses its sources, its metadata and its resume position — the three things a
 * [MediaRequest] exists to carry. [Builder.setMediaRequestResolver] is that translation: the session
 * resolves an incoming media id back into a request through the app's own catalog, and the content
 * then behaves exactly as if the app had asked for it. See [MediaRequestResolver].
 *
 * ## What it deliberately does not do
 *
 * It posts no notification. A notification belongs to a foreground service, because a notification
 * that outlived the app's UI without one would be a promise Android will not keep — see
 * [PlaybackService], which is where background playback lives. A session built here, in an Activity,
 * gives the platform integrations that do not need a service; a session built by a
 * [PlaybackService] gives those *and* the notification.
 */
public class PlaybackSession private constructor(
    /**
     * Media3's own session object.
     *
     * Internal rather than public: everything a consumer needs is on this type or reachable through
     * a [MediaController][androidx.media3.session.MediaController] connected to [token], and handing
     * out the session itself would hand out a second way to release it — which is exactly the
     * ordering [release] exists to make unmissable. [PlaybackService] is the one caller, because
     * Media3's service contract is stated in terms of this type.
     */
    internal val mediaSession: MediaSession,
    player: SuperPlayer,
) {

    /**
     * The player this session publishes. Replaced by [setPlayer]; released by [release].
     */
    public var player: SuperPlayer = player
        private set

    /**
     * What a controller connects to — hand it to
     * [MediaController.Builder][androidx.media3.session.MediaController.Builder].
     *
     * An Activity in the same app connects with this one; a controller in another process — Android
     * Auto, Assistant, a wearable companion — finds the session through the service that published
     * it instead, which is why [PlaybackService] exists.
     */
    public val token: SessionToken
        get() = mediaSession.token

    /**
     * Swaps in a different player, and releases the one that was there.
     *
     * The case this exists for is a player that cannot be reconfigured: a [PlaybackProfile] is fixed
     * when a player is built, so an app with a data-saver switch has to build a new player — and
     * doing that by releasing the session and building a second one would tear the notification
     * down, drop every connected controller, and hand the user a visible flicker for a settings
     * change. The session outlives the player, and controllers never notice.
     *
     * Take a [PlaybackSnapshot] from [player] and restore it into the incoming player *before*
     * calling this: the outgoing player is released here, and a released player has nothing left to
     * ask.
     */
    public fun setPlayer(player: SuperPlayer) {
        val outgoing = this.player
        if (outgoing === player) return

        this.player = player
        mediaSession.player = player
        outgoing.release()
    }

    /**
     * Ends the session and the player together.
     *
     * One call rather than two, in one order rather than either, because the order is not a matter
     * of taste: a `MediaSession` reads its player whenever a controller asks it anything, so
     * releasing the player first leaves a live session holding a dead one, and the crash arrives
     * later — on a lock-screen button press, in someone else's process. The session goes first here
     * and always.
     *
     * Idempotent, like [Player.release][androidx.media3.common.Player.release] itself, so a consumer
     * whose Activity and service both try to tidy up does not have to coordinate which of them wins.
     */
    public fun release() {
        if (released) return
        released = true

        mediaSession.release()
        player.release()
    }

    /** Guards [release], which two owners of the same session will each reasonably call. */
    private var released = false

    /**
     * Builds a [PlaybackSession] over a [SuperPlayer]. Media3's construction idiom — see ADR-0001
     * and CONTRIBUTING rule 3.
     */
    public class Builder(private val context: Context, private val player: SuperPlayer) {

        private var id: String? = null
        private var sessionActivity: PendingIntent? = null
        private var mediaRequestResolver: MediaRequestResolver? = null

        /**
         * Distinguishes this session from any other in the same process.
         *
         * Only needed by an app that publishes more than one at a time — Media3 rejects a second
         * session sharing an id, which is the failure a feed of players walks into. A single-session
         * app should leave this alone.
         */
        public fun setId(id: String): Builder = apply { this.id = id }

        /**
         * What tapping the notification, or the media controls in the shade, opens.
         *
         * Defaults to the app's own launcher intent, which is what almost every app wants and what
         * nobody remembers to set. An app whose playback lives on a particular screen names that
         * screen here instead.
         */
        public fun setSessionActivity(sessionActivity: PendingIntent): Builder =
            apply { this.sessionActivity = sessionActivity }

        /**
         * How this session turns a content id from outside the app back into a [MediaRequest].
         *
         * Without one, a controller that asks for content by id gets nothing playable — see
         * [MediaRequestResolver], which is where the whole of that argument lives.
         *
         * Named for the type rather than for the concept, unlike every other setter here. "Content
         * resolver" is the natural name and is unusable: `Context.getContentResolver()` is Android's
         * own, so a `PlaybackService` subclass — a `Context` — reading `contentResolver` would
         * silently get the platform's. The clash was found by writing one.
         */
        public fun setMediaRequestResolver(resolver: MediaRequestResolver): Builder =
            apply { this.mediaRequestResolver = resolver }

        public fun build(): PlaybackSession {
            val callback = ResolvingSessionCallback(mediaRequestResolver)
            val mediaSession = MediaSession.Builder(context, player)
                .apply { id?.let { setId(it) } }
                .apply { (sessionActivity ?: context.launcherIntent())?.let { setSessionActivity(it) } }
                .setCallback(callback)
                .build()

            return PlaybackSession(mediaSession, player)
        }
    }
}

/**
 * Turns a content id from outside the app back into a [MediaRequest].
 *
 * ```kotlin
 * PlaybackSession.Builder(context, player)
 *     .setMediaRequestResolver { contentId -> catalog.requestFor(contentId) }
 * ```
 *
 * ## Why a session needs one at all
 *
 * Everything outside the app — a notification, Android Auto, Assistant, a wearable — talks to a
 * player through Media3's `Player` API and nothing else. It can name a `MediaItem`, and by
 * convention it names one carrying only a media id, because the id is all it was ever given: an id
 * is what SuperPlayer publishes as the content's identity, and a controller that invented a URL
 * would be inventing something only the app knows.
 *
 * A player handed such an item has nothing to play. The app's answer is here: given the id, return
 * the request the app would have made for it — the sources, the metadata to display, and the start
 * position, including [MediaRequest.StartPosition.ResumeFromLastKnown]. Content started from a car
 * then resumes where the phone left it, which is the behaviour a viewer expects and nobody
 * implements by accident.
 *
 * Returning null declines: the item is passed to the player unchanged, and an item with no URI is
 * one Media3 will reject. Null is the honest answer for an id this app does not recognise — a stale
 * notification from a previous install, say — and it is also the default, because an app with no
 * resolver has not claimed to understand ids from outside itself.
 *
 * Called on the application thread, in the middle of a controller's command, so it must not block.
 * An app whose catalog lives behind the network should resolve from a local cache here and let the
 * request's own sources be fetched by the player.
 */
public fun interface MediaRequestResolver {
    /** The request for [contentId], or null if this app does not recognise it. */
    public fun resolve(contentId: String): MediaRequest?
}

/**
 * The session's side of [MediaRequestResolver]: Media3's callback, doing nothing but translation.
 *
 * Both hooks are needed and they are not the same hook. Media3 routes a controller's
 * `setMediaItem` through `onSetMediaItems`, which may also answer *where* playback starts, and
 * everything else — `addMediaItem`, a playlist append — through `onAddMediaItems`, which may not.
 * Overriding only the second would resolve the content and silently drop the resume position, which
 * is the failure that looks like it works.
 */
private class ResolvingSessionCallback(
    private val resolver: MediaRequestResolver?,
) : MediaSession.Callback {

    /**
     * Resolves each item, and answers no start position for any of them, because this hook has
     * nowhere to put one.
     *
     * That is Media3's shape rather than an omission: `onAddMediaItems` returns items alone, so an
     * item arriving here plays from its own beginning even if its request asked for
     * [MediaRequest.StartPosition.ResumeFromLastKnown]. It is also the right answer for what this
     * hook is for — appending to a playlist does not change where the *current* content is — and
     * the case that does carry a position, a controller replacing what is playing, goes through
     * [onSetMediaItems] below.
     *
     * Nothing is adopted here either, for the same reason: an added item is not necessarily what is
     * playing, so naming it as the player's current request would make a later
     * [SuperPlayer.saveSnapshot] describe the wrong content. Positions still accumulate for it —
     * the player remembers those against the item's media id rather than against the request.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFuture(
            mediaItems.map { item ->
                resolver.resolve(item)?.let { request ->
                    // The player decides whether its items carry identity; a session is only ever
                    // built over a SuperPlayer, so the fallback is for the type system, not a case.
                    (mediaSession.player as? SuperPlayer)?.itemOf(request) ?: request.toMediaItem(identified = false, source = FIRST_SOURCE)
                } ?: item
            }.toMutableList(),
        )

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        // One item is the case a start position can be answered for, and it is the case a
        // controller actually produces: `MediaRequest` describes one piece of content, so a
        // playlist is not something this library can claim a resume position for. A longer list
        // falls through to Media3's own handling, which resolves each item through the hook above
        // and keeps the position the controller asked for.
        val request = mediaItems.singleOrNull()?.let { resolver.resolve(it) }
            ?: return super.onSetMediaItems(
                mediaSession,
                controller,
                mediaItems,
                startIndex,
                startPositionMs,
            )

        // Adopted rather than loaded. Media3 loads what this future returns, so a callback that
        // called `setMediaRequest` itself would load the content twice and race its own second
        // load. What the player needs from the request — the identity to remember a position
        // under — is taken here; the loading stays Media3's.
        // The session's own player rather than a reference held here, so that a `setPlayer` swap
        // cannot leave this callback adopting into a player that was released underneath it.
        val player = mediaSession.player as? SuperPlayer
            ?: return super.onSetMediaItems(
                mediaSession,
                controller,
                mediaItems,
                startIndex,
                startPositionMs,
            )

        val adopted = player.adopt(request)
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                listOf(adopted.mediaItem),
                /* startIndex= */ 0,
                adopted.startPositionMs,
            ),
        )
    }

    /**
     * The request for [mediaItem]'s content, or null if this session cannot name one.
     *
     * An item that already carries a URI is left alone: it came from an app that knows what it is
     * doing — `SuperPlayer.setMediaRequest` produces one — and re-resolving it would let a resolver
     * override what the app explicitly asked for. So is an item carrying Media3's default media id,
     * which says nothing about identity and is shared by every such item.
     */
    private fun MediaRequestResolver?.resolve(mediaItem: MediaItem): MediaRequest? {
        if (this == null || mediaItem.localConfiguration != null) return null
        if (mediaItem.mediaId == MediaItem.DEFAULT_MEDIA_ID) return null
        return resolve(mediaItem.mediaId)
    }
}

/**
 * A `PendingIntent` that opens this app the way its launcher icon does.
 *
 * `FLAG_IMMUTABLE` because nothing may rewrite this intent: it is handed to the system and,
 * through it, to whatever process draws the media controls. From API 31 the platform requires the
 * flag to be stated either way, and mutable is the wrong half of that choice for an intent leaving
 * the app.
 *
 * Null for an app with no launcher activity at all — a wearable's background service, say. A
 * session with no activity is one whose notification cannot be tapped, which is worse than the
 * alternative only if there was an alternative.
 *
 * ref: https://developer.android.com/guide/components/intents-filters#PendingIntent
 */
private fun Context.launcherIntent(): PendingIntent? =
    packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
        PendingIntent.getActivity(
            this,
            /* requestCode= */ 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
