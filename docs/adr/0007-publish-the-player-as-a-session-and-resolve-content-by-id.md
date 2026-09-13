# ADR-0007: Publish the player as a session, own the service, and resolve content by id

- **Status:** Accepted
- **Date:** 2026-09-07
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Summary:** A session is something a consumer chooses to create, not something every player
  gets — `PlaybackSession` publishes a `SuperPlayer` and shares its lifetime, and `PlaybackService`
  is the `MediaSessionService` that gives it a notification. Content named from outside the app
  (a car, a watch, the notification) arrives as a bare id and is resolved back into a
  `MediaRequest` by a consumer-supplied `MediaRequestResolver`.

## Context

A player nothing outside the app can see is a player Android treats as an anonymous noise source.
The media controls in the shade show nothing, a headset's pause button does nothing, the lock screen
offers no way to stop it, and Android Auto and Assistant cannot find it at all. `PRD.md` §3.7 lists
`MediaSession` and `MediaSessionService` alongside audio focus and the wake locks as "lifecycle
correctness", and issue #11 is the ticket for them.

Media3 implements all of it. `MediaSession` publishes a player to the platform,
`MediaSessionService` runs one in the foreground and posts the notification through
`DefaultMediaNotificationProvider`, and both are stable API. So this is ADR-0006's situation again —
a mechanism that exists and a default that is missing — but with two differences that make it a
larger decision than switching three builder flags on.

**The first is that a session cannot be switched on for every player.** A session is a process-wide
publication with an id, and Media3 rejects a second session sharing one. A feed of recycled players
(F7, and `superplayer-core`'s own roadmap) would publish a hundred of them, of which at most one is
what the user means. Creating a session inside `SuperPlayer.Builder` would therefore be wrong for
the very case the player pool exists for, and the notification it produced would need a foreground
service the library cannot declare in a consumer's manifest anyway.

**The second is that SuperPlayer's own vocabulary does not cross the session boundary.** A
controller — the notification, a car head unit, a watch, Assistant — reaches a session through
Media3's `Player` API and nothing else. It can name a `MediaItem`; it cannot make a `MediaRequest`.
So content started from outside the app arrives as a bare id with no source, no metadata and no
start position, which are precisely the three things `MediaRequest` exists to carry. Left alone,
the boundary silently downgrades every promise the library makes about content identity: playback
started from a car would not resume where the phone left it, and the notification would show a
blank title.

## Decision

**A session is a thing a consumer creates, not a thing every player has. `PlaybackSession` publishes
one `SuperPlayer` and shares its lifetime; `PlaybackService` is the `MediaSessionService` that gives
that session a foreground service and a notification; and content named from outside the app is
resolved back into a `MediaRequest` by a `MediaRequestResolver` the consumer supplies.**

Four rules follow.

1. **A session and its player have one lifetime and one `release()`.** `PlaybackSession.release()`
   releases the session and then the player, in that order, and `PlaybackSession.setPlayer` releases
   the player it replaces. The ordering is not a matter of taste — a `MediaSession` reads its player
   whenever a controller asks it anything, so releasing the player first leaves a live session
   holding a dead one and the crash arrives later, on a lock-screen button press, in another
   process. Making it two calls would mean documenting an ordering instead of enforcing one.

2. **Content crosses the boundary as an id, and comes back as a `MediaRequest`.** A session with a
   `MediaRequestResolver` translates an incoming media id through the app's own catalog and adopts
   the result exactly as `SuperPlayer.setMediaRequest` would, so content started from a car resumes
   where the phone left it. Without a resolver the session declines: an unrecognised id is handed
   back untouched rather than being reinterpreted as a URL. The resolver is the app's, because a
   catalog is.

3. **`MediaRequest` carries what to display.** `title`, `subtitle` and `artworkUri` are part of a
   request rather than pushed onto the player separately, because a description that is not attached
   to the content it describes goes stale against it. They travel as Media3's own `MediaMetadata` on
   the `MediaItem`.

4. **The service's manifest entry, and the permissions with it, belong to the app.** SuperPlayer
   declares neither. The `<service>` element names a class that does not exist until the app writes
   it, so no library manifest could declare it — but `FOREGROUND_SERVICE_MEDIA_PLAYBACK` could be,
   and deliberately is not. It is a foreground-service type an app must justify to Google Play, and
   a library that merged it into every consumer's manifest — including apps that never start a
   service — would be making a store-policy commitment on their behalf. That is the same line
   ADR-0006 rule 2 draws around storage.

## Consequences

**Easier.** A consuming app gets background playback, a notification with working play, pause and
seek, lock screen controls, Bluetooth and wearable transport, and an Android Auto surface, for one
subclass with two overrides and nine lines of XML. Nothing in it builds a notification, creates a
channel, calls `startForeground`, or constructs a `MediaSession`. The demo is that app, and the
claims above are checked rather than asserted: `SuperPlayerSessionTest` drives the far side of the
boundary with a real `MediaController` against a real session, and `SuperPlayerServiceTest` drives
the service through Robolectric's own lifecycle — including that playing posts a notification
carrying the title and subtitle the `MediaRequest` named, which is the far end of the metadata path
from `MediaRequest.Builder.setTitle`.

**Audio focus is not repeated here**, and that is worth stating because a service is where apps
usually put it. Focus, becoming-noisy and the wake locks belong to the player (ADR-0006 rule 1) and
are on for every player SuperPlayer builds, so a session inherits them by publishing that player. A
service that requested focus of its own would be competing with the engine that already holds it,
and the visible symptom is a player ducking itself.
`playbackStartedByAControllerRequestsAudioFocusLikeAnyOther` pins that a controller-initiated play
produces exactly the one focus request a local play does.

**A compatibility commitment, cheaply held.** `PlaybackSession`, `PlaybackService` and
`MediaRequestResolver` become public API. All three are expressed in stable Media3 types —
`MediaSession`, `MediaSessionService`, `SessionToken` and `MediaSession.ControllerInfo` are stable in
Media3 1.11, unlike almost everything else this library touches — so ADR-0001 rule 2 costs nothing
here, and the demo needed no new `@OptIn`. That is the check that would fail first if a Media3
upgrade moved one of them.

**Harder** is the case a session is deliberately not created for. An app that wants notification
controls has to write a service class and a manifest entry; a `SuperPlayer` alone still publishes
nothing. That is the accepted cost of rule 1's premise — a session is a choice about a *player*, and
the player pool is the reason it cannot be a default.

**Two gaps are left open and named rather than hidden.**

- **Playback resumption** — Media3's `onPlaybackResumption`, which lets the system restart the last
  session after a reboot from a Bluetooth button press — is not implemented; it needs somewhere
  durable to have kept the last request, and ADR-0006 rule 2 says SuperPlayer chooses no storage, so
  it belongs to an app or to a later decision about a persistence seam.
- **A playlist named from outside the app** resolves each item's content but keeps the controller's
  own start position rather than a remembered one, because a `MediaRequest` describes one piece of
  content and there is nothing yet to resume a list against.

## Alternatives considered

**Create a `MediaSession` inside `SuperPlayer.Builder`, as ADR-0006 does for the lifecycle flags.**
Rejected on rule 1's premise. A session is process-wide and uniquely identified, so "every player
has one" is not expressible for a feed of recycled players; and the notification half needs a
foreground service the library cannot declare for a consumer. ADR-0006's flags are on every player
because they are correct on every player, and this is not.

**Expose Media3's `MediaSession` directly and let consumers wire it.** Rejected: it is a smaller API
surface and a larger consumer burden, and it leaves all three defects in place — the release
ordering, the missing session activity, and the content boundary. It would also make the boundary
problem invisible rather than solved, because a hand-wired session *appears* to work right up until
someone starts playback from a car.

**Have the demo drive the service through a `MediaController`, as most Media3 apps do.** Rejected
for the demo specifically, and it is a rejection about the demo rather than about the library. A
controller speaks `Player` and nothing else, so a demo built on one could not show which
`PlaybackProfile` a player was built with, could not show what that profile decided, and could not
switch profiles — three of the claims the demo exists to make. It binds to its own service and holds
the `SuperPlayer` instead, which is an ordinary Android pattern for an app with app-specific
playback UI. The controller path is not untested as a result: `SuperPlayerSessionTest` is entirely
controller-driven.

**Make the resolver a `suspend` function, or return a `ListenableFuture`.** Rejected for now. It is
called in the middle of a controller's command on the application thread, and an app whose catalog
is behind the network should answer from a local cache — the request's own sources are fetched by
the player afterwards, which is where the latency belongs. Widening it later is additive.

**Declare `FOREGROUND_SERVICE_MEDIA_PLAYBACK` in `superplayer-core`'s manifest**, the way
`media3-exoplayer` declares `WAKE_LOCK`. Rejected under rule 4. The comparison does not hold:
`WAKE_LOCK` is a normal permission with no store consequence, and a foreground-service type is a
declaration an app answers for in the Play Console.

## References

- Background playback with a `MediaSessionService` —
  https://developer.android.com/media/media3/session/background-playback
- Controlling playback from other apps (`MediaController`) —
  https://developer.android.com/media/media3/session/connect-to-media-app
- Foreground service types —
  https://developer.android.com/develop/background-work/services/fgs/service-types
- Notification runtime permission —
  https://developer.android.com/develop/ui/views/notifications/notification-permission
