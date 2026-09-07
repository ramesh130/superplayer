package com.superplayer.core

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Playback that outlives the screen: a foreground service publishing a [PlaybackSession], with the
 * notification, the lock-screen controls and the Android Auto surface that come with one.
 *
 * A consuming app subclasses this, says how to build its player, and declares the subclass in its
 * manifest:
 *
 * ```kotlin
 * class DemoPlaybackService : PlaybackService() {
 *     override fun onCreatePlayer(): SuperPlayer = SuperPlayer.Builder(this).build()
 *     override fun onResolveContent(contentId: String): MediaRequest? = catalog.requestFor(contentId)
 * }
 * ```
 *
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
 * <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
 *
 * <service
 *     android:name=".DemoPlaybackService"
 *     android:exported="true"
 *     android:foregroundServiceType="mediaPlayback">
 *     <intent-filter>
 *         <action android:name="androidx.media3.session.MediaSessionService" />
 *     </intent-filter>
 * </service>
 * ```
 *
 * ## Why the manifest is the app's and not the library's
 *
 * Nine lines of XML is an unsatisfying amount of ceremony for a library whose argument is that
 * correct defaults belong in the library. Both halves of it are unavoidable, and for different
 * reasons. The `<service>` element names a class that does not exist until the app writes it, so no
 * library manifest can declare it. The permissions could be declared here and are deliberately not:
 * `FOREGROUND_SERVICE_MEDIA_PLAYBACK` is a foreground-service type an app must justify to Google
 * Play, and a library that added it to every consumer's merged manifest — including apps that never
 * start this service — would be making a store-policy commitment on their behalf. That is the same
 * line ADR-0006 rule 2 draws around storage: SuperPlayer chooses nothing for a consumer that has
 * consequences outside playback.
 *
 * ## What this handles that an app otherwise writes
 *
 * The session is built when the service is created, published for every controller that asks, and
 * released — before the player, which is [PlaybackSession]'s subject — when the service is
 * destroyed. The notification is Media3's own: it appears when playback starts, follows the state
 * and the metadata a [MediaRequest] carried, and its play, pause and seek controls drive the player
 * because they are the player's own commands rather than a parallel implementation of them.
 *
 * Audio focus, becoming-noisy and the wake locks are not repeated here. They belong to the player
 * and are switched on for every player SuperPlayer builds — `LifecycleBinding.kt`, ADR-0006 rule 1 —
 * so a session inherits them by publishing that player rather than by asking for them a second
 * time. A service that requested focus of its own would be competing with the engine that already
 * holds it, and the visible symptom is a player that ducks itself.
 *
 * Swiping the app out of Recents is **not** handled here, and it is worth saying so because a
 * hand-written override of `onTaskRemoved` is the advice most of the internet still gives. Media3
 * 1.11's own `MediaSessionService.onTaskRemoved` already pauses every player and stops the service
 * unless playback is ongoing — which is the correct behaviour, including the part that is easy to
 * get backwards: someone who swipes a music app away *while it is playing* wants it to keep
 * playing. Overriding it here would be re-implementing a Media3 behaviour to change nothing, which
 * is what ADR-0001 rule 1 exists to prevent.
 *
 * ## What an app still has to do
 *
 * Ask for `POST_NOTIFICATIONS` at runtime on API 33 and above. Android will not show the playback
 * notification without it, and playback then continues in the background with no way for the user
 * to stop it — which is the state that gets apps uninstalled. Nothing here can ask on the app's
 * behalf: a runtime permission needs an Activity, and this is a service.
 *
 * ref: https://developer.android.com/media/media3/session/background-playback
 */
public abstract class PlaybackService : MediaSessionService() {

    /**
     * The session this service publishes, or null before [onCreate] and after [onDestroy].
     *
     * Protected rather than public: a service is reached by binding to it, and an app that binds
     * its own Activity to its own subclass can expose whatever it needs from here. Publishing it
     * on the service's own API would invite a caller to hold it past the service's life.
     */
    protected var playbackSession: PlaybackSession? = null
        private set

    /**
     * Builds the player this service plays with. Called once, when the service is created.
     *
     * This is where a [PlaybackProfile] is chosen, because a profile is fixed when a player is
     * built. Changing it later means building a second player and handing it to
     * [PlaybackSession.setPlayer], which keeps the session — and therefore the notification and
     * every connected controller — alive across the swap.
     */
    protected abstract fun onCreatePlayer(): SuperPlayer

    /**
     * The app's catalog lookup, for content asked for from outside the app.
     *
     * Returns null by default, which declines every id. See [MediaRequestResolver] for what an app
     * loses by leaving it that way: content started from a car, a watch or Assistant arrives as a
     * bare id, and an id alone is not playable.
     */
    protected open fun onResolveContent(contentId: String): MediaRequest? = null

    /**
     * Builds the session, given the player [onCreatePlayer] produced.
     *
     * Overridable for the session settings this service does not have an opinion about — an id for
     * an app publishing more than one, or a session activity other than the launcher. An override
     * that drops [PlaybackSession.Builder.setMediaRequestResolver] drops [onResolveContent] with it.
     */
    protected open fun onCreateSession(player: SuperPlayer): PlaybackSession =
        PlaybackSession.Builder(this, player)
            .setMediaRequestResolver { contentId -> onResolveContent(contentId) }
            .build()

    /**
     * Builds the session and registers it, in that order and both here.
     *
     * Registering it is the line that is easy to leave out and expensive to leave out. Media3 learns
     * about a session either from [addSession] or from [onGetSession] when a controller connects, and
     * only a registered session gets the notification: an app whose UI holds the player directly —
     * the demo does, and so does any app with playback UI of its own — may never connect a
     * controller at all, and would then play in the background with no notification and no way for a
     * viewer to stop it. [addSession] is keyed by session id and idempotent for the same session, so
     * a controller connecting later adds nothing twice.
     */
    override fun onCreate() {
        super.onCreate()
        val session = onCreateSession(onCreatePlayer())
        playbackSession = session
        addSession(session.mediaSession)
    }

    /**
     * Hands every controller the one session this service publishes.
     *
     * Every controller: no connection is refused here. Deciding *which* controllers may connect —
     * a locked-down app that answers only its own package, say — is Media3's `MediaSession.Callback`
     * question and belongs to an app that has that requirement, not to a default that would
     * otherwise silently exclude Android Auto and Assistant.
     */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        playbackSession?.mediaSession

    /**
     * Releases the session, and with it the player.
     *
     * Before `super.onDestroy()`, which tears down the service's own bookkeeping about the sessions
     * it holds. One call rather than two, because the order the two need is [PlaybackSession]'s to
     * enforce and not this class's to remember.
     */
    override fun onDestroy() {
        playbackSession?.release()
        playbackSession = null
        super.onDestroy()
    }
}
