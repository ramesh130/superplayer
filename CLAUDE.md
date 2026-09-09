# superPlayer

A production playback layer on top of AndroidX Media3 — policy, resilience, observability, and
lifecycle. Not a fork of Media3 and not a new player. Apache-2.0, Kotlin, JDK 17.

## Orientation

Three Gradle builds, not one. Mistaking them for a single build is the usual first wrong turn.

| Build | What it is |
| --- | --- |
| root | the 12 `superplayer-*` library modules, listed in `settings.gradle.kts` |
| `build-logic/` | an **included build** holding the convention plugins every module applies |
| `demo/` | a **separate** build resolving the library from published Maven coordinates |

A module's own build file holds only its dependencies; Android setup, SDK levels, Java and Kotlin
targets, lint, and publishing all live in the `superplayer.android.library` convention plugin.

`superplayer-core` is the only module a consumer needs; the rest are additive. Dependencies point
inward toward core, and no module may depend on one from a later phase. `docs/modules.md` carries
the module table, the phases, and that rule — read it before adding a module dependency.

`superplayer-core` holds the only library code so far: `SuperPlayer`, a Media3 `Player` that
delegates to a wrapped `ExoPlayer` through a private `ForwardingPlayer`, built by
`SuperPlayer.Builder(context)`. Because it *is* a `Player`, existing `PlayerView`, `MediaSession`
and Compose surfaces take it unchanged — the demo assigns it to a `PlayerView` with no adapter,
which is the point. `player.exoPlayer` is public, and ADR-0001 rule 2 explains why that one
`@UnstableApi` type is allowed out.

The second public type is `MediaRequest`: what to play, as content rather than as a URL. It carries a
stable `contentId` (not a URL — that is the point, and the `MediaRequest` KDoc says which defects it
closes), an ordered `sources` list of which **only the first is used** until failover arrives, a
`StartPosition` of `Beginning`, `At(ms)` or `ResumeFromLastKnown`, and the `title`, `subtitle` and
`artworkUri` that everything outside the app displays. `player.setMediaRequest(request)`
is the counterpart of `setMediaItem`, and the identity travels as the `MediaItem`'s `mediaId`.
Resume positions are held in memory for the life of one `SuperPlayer`, bounded to the
`SuperPlayer.MAX_REMEMBERED_POSITIONS` most recently used ids, and are not persisted; surviving a
configuration change is issue #10's subject, not this one's.

The third public type is `PlaybackProfile`: what kind of playback this is, chosen by
`SuperPlayer.Builder.setProfile(...)` and fixed for a player's lifetime. It names a use case —
`VIDEO_ON_DEMAND` (the default), `LIVE_LINEAR`, `SHORT_FORM`, `DATA_SAVER` — rather than carrying
numbers. The numbers, and a written rationale for every one that departs from Media3's own default,
live in `StaticProfilePolicy`, which is internal.

The fourth public type is `PlaybackSnapshot`: what a player was doing, as a value that outlives it.
`player.saveSnapshot()` and `player.restoreSnapshot(...)` are the pair, `toBundle()`/`fromBundle()`
the round trip, and a consumer's `onSaveInstanceState` or `rememberSaveable` is where the `Bundle`
lives — SuperPlayer persists nothing itself (ADR-0006 rule 2). It carries the current `MediaRequest`,
the position within it, `playWhenReady`, and the whole of the player's remembered-position map, so
`ResumeFromLastKnown` keeps working across a configuration change. It carries no profile, and it
names no content for an item set through `setMediaItem`, which has no identity to restore under.

Android's own lifecycle rules — audio focus, becoming-noisy, wake and Wi-Fi locks — are on for every
player, switched on in `LifecycleBinding.kt` and fixed rather than per-profile: ADR-0006 rule 1 says
why they are correctness rather than policy, and therefore not behind `PlaybackPolicy`.

Publishing a player to the rest of Android is `PlaybackSession`, and it is a *choice* rather than a
default — ADR-0007 explains why a player pool makes "every player gets a session" unexpressible.
`PlaybackSession.Builder(context, player).build()` wraps a Media3 `MediaSession`; one `release()`
ends the session and then the player, in the order that matters; `setPlayer` swaps the player behind
a live session so a profile change does not tear the notification down. `PlaybackService` is the
`MediaSessionService` on top of it — a consumer subclasses it, overrides `onCreatePlayer` and
usually `onResolveContent`, and declares the subclass plus the foreground-service permissions in
their own manifest (rule 4 says why the permissions are not the library's). The notification is
Media3's own; nothing here builds one.

`MediaRequestResolver` is how content identity survives the boundary. A controller — the
notification, Android Auto, a watch — speaks Media3's `Player` API only, so it names a `MediaItem`
carrying a bare media id; the session resolves that id back through the app's catalog into a
`MediaRequest` and adopts it exactly as `setMediaRequest` would, which is what makes content started
from a car resume where the phone left it. `SuperPlayerSessionTest` drives a real `MediaController`
against a real session and is where every claim in this paragraph is checked.

A screen that needs many players at once — a feed, a grid — builds them through `PlayerPool` rather
than one per item, because concurrent hardware decoder instances are a device resource that a long
scroll will find the end of. `PlayerPool.Builder(context).build()` derives its bound from what the
device reports — `DeviceCapacity.kt` is the only place that reading happens, and it is the min of the
platform's concurrent-decoder limit for H.264/HEVC and a per-player budget against the *app's* heap
(`ActivityManager.memoryClass`, since Media3 buffers on the Java heap), never below one. `acquire()` returns null rather than growing past the bound, `recycle(player)` hands
one back, and `SuperPlayer.resetForReuse` is what makes a reused player carry nothing of the last
item: surface detached first (a stale frame in a recycled view is the tell of a hand-rolled pool),
then content, playback state, listeners, audio attributes and the remembered-position map. Audio
focus is a single token, so concurrently *playing* pooled players contend for it — a feed plays one
row and holds prepared first frames for the rest, which is what the demo's `FeedScreen` does across
sixty rows with a live count of what the pool has built.

Everything below `MediaSource` loads through one `DataSource.Factory` chain, and `TransferChain` is
the single internal place it and the `MediaSource.Factory` over it are assembled — reached from
`SuperPlayer.Builder.build()`, because `ExoPlayer` has no runtime media-source-factory setter. What it
composes today is exactly Media3's own default; what it exists for is the order, which its KDoc writes
down along with where `superplayer-cache`, `-abr`, `-telemetry`, `-resilience` and `-offline` each
insert themselves (`PRD.md` §2.4). Two of those insert nothing into the chain and the KDoc is mostly
about them: measurement is a propagated `TransferListener`, so a layer that drops the registration
blinds ABR silently, and cache hits stay out of the estimate through Media3's `isNetwork` flag rather
than through chain position — ADR-0002's argument arriving through a different door. CMCD attaches to
the `MediaSource.Factory`, which is why the seam owns that too. Which HTTP stack sits at the bottom is
ADR-0004's open question, and it plugs in at one named line.

That policy is reached through `PlaybackPolicy`, the boundary ADR-0005 establishes: observed
`PlaybackConditions` in, a `PlaybackDecision` (a `BufferPolicy` and a `TrackSelectionPolicy`) out,
with no Media3 type anywhere in it. `EngineBinding.kt` is the one place a decision becomes Media3
configuration. The implementation that ships is a static per-profile lookup, deliberately not
adaptive — adaptive policy is `superplayer-abr`'s, behind this same interface. The policy is
consulted once, at construction, because `DefaultLoadControl` cannot be re-configured afterwards;
`player.playbackDecision` reports what was applied, which is the only way to see the buffer half at
all.

The facade *implements* `Player` by Kotlin delegation rather than extending `ForwardingPlayer`;
ADR-0003 records why, and the shape is load-bearing rather than stylistic. Kotlin delegation does
not override Java `default` methods and gives no warning that it hasn't: `Player` has one such member
(`getAudioSessionId()`, forwarded by hand) and `Player.Listener` is 37 of them, which is why the
listener wrapper forwards reflectively. `SuperPlayerForwardingTest` drives Media3's own
forwarding-contract assertion over both and is what catches the next one Media3 adds.

Every other library module is still an empty placeholder: they exist so boundaries are fixed and
enforceable before code arrives. `superplayer-core` and `build-logic` are the only modules with test
sources. The roadmap is `PRD.md`: the problem inventory it numbers `F1`–`F8`, the module
requirements, and the phase table are what the issues are cut from.

`PLAN.md` is an untracked, local-only scratch draft that `PRD.md` supersedes. **Do not read it, cite
it, or copy from it** — it names third parties and framings that must not reach a tracked file, and
where the two disagree `PRD.md` is right.

## Commands

```bash
./gradlew assemble check          # build, lint, tests, repo verification, tracked API surface
./gradlew updateApiSurface        # regenerate api/<module>.api after a deliberate API change
./gradlew publishToMavenLocal     # required before the demo will build
(cd demo && ./gradlew assembleDebug lintDebug)
```

`check` is the complete definition of the library's checks. Because `build-logic` is an included
build, its tasks are invisible to the root build's aggregate tasks — its tests run only because
`check` depends on them explicitly. A new check that can be a Gradle task belongs in `check`, not
only in CI (`.github/workflows/ci.yml`), so local and CI agree on what passing means.

The demo build needs the library published first: it consumes
`com.superplayer:superplayer-core:<version>` rather than `project(":superplayer-core")`, so that an
adopter's first failures — a missing transitive dependency, a malformed POM, a wrong artifactId —
happen here instead of in someone else's app.

Building needs an Android SDK: set `ANDROID_HOME`, or write `sdk.dir` into `local.properties`
(gitignored). Gradle itself does not need installing; the checked-in wrapper fetches the pinned
version.

**Run Gradle on JDK 17**, the version CI uses and the catalog's `jvmTarget`. This matters more than
it looks: the Robolectric runtime the tests load is pinned in
`superplayer-core/src/test/resources/robolectric.properties`, and Robolectric's runtimes each
require a minimum Java version. A daemon started on a newer JDK — Android Studio's bundled JBR, say
— will happily run configurations that then fail in CI. Check with `./gradlew --version` when a
test passes locally and fails there.

### Running the demo on an emulator

Some claims cannot be checked by any test here: `docs/testing.md` bars the network, so whether a
change still plays real HLS and DASH over a real CDN is a question only the demo on a device answers.
There is an AVD kept for it — `superplayer_verify_36`, API 36 — alongside whatever Android Studio has
created (`emulator -list-avds`).

```bash
emulator -avd superplayer_verify_36 -no-snapshot-load -no-boot-anim &
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 5; done
./gradlew publishToMavenLocal && (cd demo && ./gradlew assembleDebug)
adb install -r -t demo/build/outputs/apk/debug/superplayer-demo-debug.apk
adb shell am start -n com.superplayer.demo/.MainActivity
```

Things that cost time the first time round:

- **The APK is `superplayer-demo-debug.apk`**, after the module name, not `demo-debug.apk` after the
  directory.
- **`adb devices` reports the emulator `offline` for a while after it appears.** Waiting on
  `sys.boot_completed` rather than on the device listing is what makes the install reliable.
- **The first launch raises the notification-permission dialog over the player surface.** A
  screenshot taken before it is dismissed shows a dialog and a black rectangle, which looks exactly
  like a playback failure and is not one. Dismiss it, then capture.
- **A screenshot is weak evidence on its own** — it cannot tell a rendered frame from a stalled one.
  The demo publishes a `PlaybackSession`, so the platform logs the state transitions, and a
  *position that advances between two of them* is the thing worth asserting on:

  ```bash
  adb logcat -c                       # before launching, so the window below is this run's
  adb logcat -d -t 2000 | grep -o 'state=PLAYING(3), position=[0-9]*'
  ```

  Bound it with `-t`. An unbounded `adb logcat -d` against an emulator that has been up for a while
  dumps the whole buffer and can take minutes, which reads as a hang rather than as a slow command.

Re-run `publishToMavenLocal` and reinstall after any library change: the demo resolves SuperPlayer
from Maven local, so an APK built against a stale artifact will happily test the previous version.

The emulator is disposable and does exit on its own — a crash, a host sleep, an `adb` client that
takes it down with it. If `adb devices` comes back empty mid-session, boot it again and reinstall;
nothing about the verification depends on the instance surviving.

## Binding rules

Style preferences these are not. A change violating one is not accepted, whatever its merit.

- **Clean-room discipline** — `CONTRIBUTING.md`. No proprietary material, no mirroring a commercial
  SDK's API shape, and a public `// spec:` or `// ref:` citation on every non-obvious algorithm.
- **[ADR-0001](docs/adr/0001-compose-dont-fork.md)** — compose on Media3, do not fork it. Its rule 3
  puts Media3's version in `gradle/libs.versions.toml` and nowhere else, enforced mechanically by
  `./gradlew verifyNoHardcodedMedia3Versions`.
- **[ADR-0002](docs/adr/0002-no-local-http-proxy.md)** — no local HTTP proxy.
- **[ADR-0003](docs/adr/0003-implement-player-by-delegation.md)** — the facade implements `Player`
  by Kotlin delegation and extends no Media3 class. Every Media3 `Player` base class is
  `@UnstableApi`; extending one puts that marker on every method a consumer calls.
- **[ADR-0005](docs/adr/0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md)** — all
  buffering and track-selection policy is decided behind `PlaybackPolicy`, in types that name no
  Media3 class. A policy constant at a Media3 call site, in a listener, or in a builder is a bug
  against this ADR whatever it is worth.
- **[ADR-0006](docs/adr/0006-own-the-platform-rules-and-hand-back-the-state.md)** — Android's
  lifecycle rules are on by default and are not a profile's to vary, and state that outlives a
  player travels as a `PlaybackSnapshot` the consumer stores. SuperPlayer chooses no storage on a
  consumer's behalf: no preferences, no database, no file.
- **[ADR-0007](docs/adr/0007-publish-the-player-as-a-session-and-resolve-content-by-id.md)** — a
  session is created by a consumer rather than owned by every player; a `PlaybackSession` and its
  player share one lifetime and one `release()`; content named from outside the app is resolved
  back into a `MediaRequest` rather than reinterpreted as a URL; and the service's manifest entry
  and foreground-service permissions are the app's, not the library's.
- **[ADR-0008](docs/adr/0008-measure-behind-an-engine-agnostic-sink-boundary.md)** — telemetry
  leaves the library through a sink that names no Media3 type; the sink interface and the event
  vocabulary are `superplayer-core`'s while the collector is `superplayer-telemetry`'s; delivery is
  at-most-once, bounded, and lossy under pressure, with every drop counted and reported; a sink is
  never called on a thread the engine needs; the events are one sealed, versioned hierarchy whose
  version tracks a metric's *meaning* rather than its shape; and CMCD is a separate seam joined to
  telemetry by a shared session id.
- **[`docs/api-surface.md`](docs/api-surface.md)** — every published module's public API is tracked
  in `<module>/api/<module>.api` and validated by `check`. Changing it means running
  `./gradlew updateApiSurface` and committing the diff in the same change. A leaked `@UnstableApi`
  Media3 type fails the build rather than merely showing up in a diff.
- **[`docs/testing.md`](docs/testing.md)** — tests drive the library through its public API under
  Robolectric, against Media3's own fakes, with no device and no network. Nothing asserts past the
  facade, and `player.exoPlayer` is an escape hatch for consumers rather than a way in for tests.
  The rule binds every change, not only the one that introduced it.
- Every dependency version lives in `gradle/libs.versions.toml`; the library and demo builds read
  that one file.
- A new dependency arrives with its `THIRD_PARTY.md` row in the same change.
- A change contradicting an ADR says so explicitly and argues the case. Winning that argument
  produces a superseding ADR, not an undocumented exception.

## Agent skills

### Issue tracker

Issues live in this repo's GitHub Issues, managed via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context: `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
