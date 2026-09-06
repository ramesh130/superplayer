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
closes), an ordered `sources` list of which **only the first is used** until failover arrives, and a
`StartPosition` of `Beginning`, `At(ms)` or `ResumeFromLastKnown`. `player.setMediaRequest(request)`
is the counterpart of `setMediaItem`, and the identity travels as the `MediaItem`'s `mediaId`.
Resume positions are held in memory for the life of one `SuperPlayer`, bounded to the
`SuperPlayer.MAX_REMEMBERED_POSITIONS` most recently used ids, and are not persisted; surviving a
configuration change is issue #10's subject, not this one's.

The third public type is `PlaybackProfile`: what kind of playback this is, chosen by
`SuperPlayer.Builder.setProfile(...)` and fixed for a player's lifetime. It names a use case —
`VIDEO_ON_DEMAND` (the default), `LIVE_LINEAR`, `SHORT_FORM`, `DATA_SAVER` — rather than carrying
numbers. The numbers, and a written rationale for every one that departs from Media3's own default,
live in `StaticProfilePolicy`, which is internal.

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
sources. The roadmap is `PLAN.md`, which is untracked and local-only, so it is absent from a fresh
clone.

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
