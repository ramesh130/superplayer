package com.superplayer.demo

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlaybackService
import com.superplayer.core.SuperPlayer

/**
 * The demo's playback service: where the player lives, so that playback outlives the screen.
 *
 * This is the whole of what a consuming app writes to get background playback, a notification with
 * working transport controls, lock-screen controls and an Android Auto surface. Two overrides say
 * what to play with and what the app's content ids mean; everything else — the session, its
 * lifecycle, the notification, the ordering of release — is [PlaybackService]'s.
 *
 * Note what is *not* here. No notification is built, no channel is created, no `startForeground`
 * call is made, no `MediaSession` is constructed and no audio focus is requested. The last of those
 * is the one worth pausing on: focus, becoming-noisy and the wake locks belong to the player and are
 * already on for every player SuperPlayer builds, so a service that asked for them again would be
 * competing with the engine that holds them.
 *
 * ## Why the app binds to this rather than using a `MediaController`
 *
 * The usual shape for a Media3 app is an Activity holding a `MediaController` — a `Player` that
 * forwards to the session across a process boundary. It is the right shape for an app whose UI only
 * needs the transport controls, and it is the shape Android Auto and the notification themselves
 * use.
 *
 * The demo needs more than transport. It shows which [PlaybackProfile] a player was built with and
 * what that profile decided, and it switches profiles at runtime — none of which crosses a
 * `MediaController`, because a controller speaks Media3's `Player` API and nothing else. So the
 * Activity binds to this service and holds the [SuperPlayer] itself, which is an ordinary Android
 * pattern and is what an app with SuperPlayer-specific UI would do. The session is still published,
 * so the notification and every external controller work exactly as they would either way — and
 * `SuperPlayerSessionTest` is where the controller's side of the boundary is pinned.
 */
class DemoPlaybackService : PlaybackService() {

    /**
     * The profile the *next* player is built with, and the one the current player was built with.
     *
     * Held here rather than passed in because a service is created by the system, not by the app:
     * whoever starts it — a controller connecting, the notification, a car — hands it nothing. The
     * Activity's picker changes it through [usingProfile] once it has bound.
     */
    private var profile = PlaybackProfile.VIDEO_ON_DEMAND

    override fun onCreatePlayer(): SuperPlayer =
        SuperPlayer.Builder(this)
            .setProfile(profile)
            .build()
            // The service exists because something asked for playback, so the intent to play is the
            // right default. Content arrives from the Activity, or from a controller naming a
            // content id that `onResolveContent` below understands.
            .apply { playWhenReady = true }

    /**
     * The demo's catalog, in one function: an id in, the request the app would have made out.
     *
     * This is what lets a car head unit, a wearable or Assistant start demo content by naming it.
     * Everything a `MediaRequest` carries is filled in here — the source, what to display, and
     * `ResumeFromLastKnown` — so content started from outside the app resumes exactly where the app
     * left it, which is the behaviour a viewer expects and nothing else in the system can provide.
     */
    override fun onResolveContent(contentId: String): MediaRequest? =
        DemoStream.entries.firstOrNull { it.contentId == contentId }?.let { requestFor(it) }

    /**
     * The request for [stream], with everything an external surface needs to describe it.
     *
     * Public to the app rather than private, because the Activity loads content through the same
     * function. Two descriptions of the same stream — one for the app, one for the notification —
     * is exactly the drift that makes a notification say something the screen disagrees with.
     */
    internal fun requestFor(stream: DemoStream): MediaRequest =
        MediaRequest.Builder(stream.contentId)
            .addSource(stream.uri)
            .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
            .setTitle(getString(stream.titleRes))
            .setSubtitle(getString(stream.subtitleRes))
            .build()

    /**
     * The player, built with [profile], swapping it in if that is not the profile it already has.
     *
     * A profile is fixed when a player is built — half of what it decides is handed to the engine as
     * it is constructed — so changing one means building another player. What must *not* change is
     * the session: tearing it down and publishing a second one would drop the notification and every
     * connected controller for what is, to a viewer, a settings change. So the session stays and the
     * player underneath it is replaced, carrying its state across in one `PlaybackSnapshot`.
     */
    fun usingProfile(profile: PlaybackProfile): SuperPlayer {
        val session = checkNotNull(playbackSession) { "The service has no session yet" }
        if (profile == this.profile) return session.player

        this.profile = profile
        val replacement = onCreatePlayer().apply { restoreSnapshot(session.player.saveSnapshot()) }
        // Releases the outgoing player, which is why the snapshot above is taken first.
        session.setPlayer(replacement)
        return replacement
    }

    /**
     * Hands the app's own Activity a way in, and everyone else Media3's.
     *
     * A `MediaSessionService` is bound by things that want the session — a `MediaController`, the
     * notification, Android Auto — and Media3 answers those on its own action. This adds one more
     * caller with one more action: the demo's Activity, which needs the [SuperPlayer] rather than a
     * controller for the reasons in this class's documentation. Any other action still gets Media3's
     * answer, so nothing about the session's discoverability changes.
     */
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_BIND_LOCAL) LocalBinder() else super.onBind(intent)

    /** In-process only — the demo's Activity and its service are the same process by definition. */
    inner class LocalBinder : Binder() {
        val service: DemoPlaybackService get() = this@DemoPlaybackService
    }

    companion object {
        /** The action the Activity binds with; anything else is Media3's to answer. */
        const val ACTION_BIND_LOCAL: String = "com.superplayer.demo.BIND_LOCAL"
    }
}
