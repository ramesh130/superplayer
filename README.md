# SuperPlayer

A production playback layer on top of [AndroidX Media3](https://github.com/androidx/media) —
policy, resilience, observability, and lifecycle.

It is **not** a fork of Media3 and **not** a new player. `SuperPlayer` *is* a Media3 `Player`, so
existing `PlayerView`, `MediaSession` and Compose surfaces take it unchanged, and
`player.exoPlayer` is there for everything this library does not cover.

Apache-2.0 · Kotlin · JDK 17 · `minSdk` 24 · Media3 1.11.0

---

## Status: pre-1.0, and not resolvable by anybody

Read this before the feature list, because the feature list is longer than the guarantees.

- **Nothing is published.** There is no remote repository and no credentials anywhere in this
  build. `./gradlew publishToMavenLocal` writes to your own `~/.m2`, which is how the demo
  resolves it and the only way anyone resolves it. A cut release is a commit and a local tag.
- **`0.x`, and under `0.x` a minor may break you.** What counts as breaking is the tracked API
  surface rather than anyone's judgement —
  [`docs/adr/0017`](docs/adr/0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
  decides it and [`docs/compatibility.md`](docs/compatibility.md) says what it means for you.
- **It is verified under Robolectric to an unusual depth and on real hardware barely at all.**
  Most phases' device exit criteria are still open — DRM on L1/L3 hardware, the TV path, download
  survival across a reboot, the feed's time-to-first-frame, and the diagnostics bundle from a
  device. Everything the test suite *cannot* show is written down in
  [`docs/testing.md`](docs/testing.md) rather than left to be assumed.
- **The benchmark's Phase 3 verdict is a committed finding, not a win.** `benchmark/phase3/`
  reports what the adaptive policy did against three other arms on a fixed matrix, including
  where it lost. Moving those numbers is Phase 11 and is open.
- **`superplayer-moq` is local-only on purpose.** It links a native library built on one machine
  for one ABI. It is excluded from any release and from the tracked API surface, and
  [#369](https://github.com/ramesh130/superplayer/issues/369) is what publishing it would have to
  answer first.

If you want something to depend on today, this is not it. If you want to read how a playback layer
gets argued into shape, the [18 decision records](docs/adr/README.md) are the point.

---

## What it addresses

The problems are inventoried in `PRD.md` as F1–F8 — each a symptom teams actually report, traced
to a root cause, and assigned to a module:

| | Symptom | Where it is addressed |
| --- | --- | --- |
| **F1** | "Works on WiFi, falls apart on mobile data" | Per-transport policy and an estimate reseeded on a transport change, not carried across it |
| **F2** | `DefaultLoadControl` constants re-derived per app from forum answers | One policy boundary naming no Media3 type |
| **F3** | "Instant start" as a product expectation | Preload, warm decoders, a content-keyed cache |
| **F4** | DRM frame drops on low-end devices; downloads draining battery | Secure-decoder budgets; a battery- and network-aware download schedule |
| **F5** | Sub-100 ms start via a loopback proxy, then a second workaround to undo the first | No proxy — [ADR-0002](docs/adr/0002-no-local-http-proxy.md) refuses the whole approach |
| **F6** | Fixed buffer watermarks are wrong in both directions for live | Buffer targets recomputed on observed conditions, capped against the app's heap |
| **F7** | Every mature in-house library re-converges on the same pieces | Those pieces, behind one seam each |
| **F8** | QoE definitions differ per app, so nothing compares | CTA-2066, with every departure from it cited |

## A taste

```kotlin
val player = SuperPlayer.Builder(context)
    .setProfile(PlaybackProfile.VIDEO_ON_DEMAND)
    .setTelemetry(QoeCollector(LogcatSink))
    .build()

playerView.player = player          // a stock Media3 PlayerView, no adapter

player.setMediaRequest(
    MediaRequest.Builder("catalog:sintel")      // content identity, not a URL
        .addSource("https://cdn.example/sintel.m3u8")
        .addSource("https://backup.example/sintel.mpd")   // fallback, a different protocol
        .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
        .build(),
)
player.playWhenReady = true
player.prepare()
```

`MediaRequest` is the shape of the idea: content has a **stable id** rather than a URL, an ordered
list of sources the fallback ladder climbs through, and a start position that can say *resume*
without the app tracking where. The id is what a cache key, a CMCD session, a telemetry row and a
notification all agree on.

Everything else is additive and costs nothing when unused — a player built without a cache, a
policy, resilience, DRM or telemetry registers nothing and allocates nothing for them, and there is
a test counting that for each.

## Modules

`superplayer-core` is the only one an app needs. Dependencies point inward, and no module may
depend on one from a later phase ([`docs/modules.md`](docs/modules.md)).

| Module | What it adds |
| --- | --- |
| `superplayer-core` | The facade, `MediaRequest`, profiles, the player pool, sessions, the transfer-chain seam |
| `superplayer-telemetry` | CTA-2066 QoE collection, a logcat sink, session traces |
| `superplayer-abr` | Bandwidth estimation, adaptive buffering, network-aware track selection |
| `superplayer-cache` | A cache keyed by content identity rather than URL |
| `superplayer-preload` | Feed prefetch and decoder warm-up over one shared engine |
| `superplayer-resilience` | One error taxonomy and a six-rung fallback ladder |
| `superplayer-drm` | Widevine online and offline, provisioning, the security-level ladder |
| `superplayer-offline` | Downloads into the cache you opened, on a battery-aware schedule |
| `superplayer-tv` | Frame-rate matching, display and audio changes, D-pad controls |
| `superplayer-diagnostics` | A doctor that names a manifest's pathology, a trace bundle, a debug HUD |
| `superplayer-realtime` | Encoded frames pushed into Media3's sample queues under a live timeline |
| `superplayer-moq` | Media over QUIC over that seam — **local-only, see Status** |
| `superplayer-testkit` | The deterministic harness: shaped networks, fault injection, golden traces |
| `superplayer-testmedia` | Synthetic HLS and DASH streams, and a corpus of deliberately broken ones |

## Building it

Needs JDK 17 and an Android SDK (`ANDROID_HOME`, or `sdk.dir` in `local.properties`). The Gradle
wrapper fetches everything else.

```bash
./gradlew assemble check        # build, tests, lint, API surface, format, repo verification
```

`check` is the complete definition of passing — the same command CI runs. There are four Gradle
builds here, not one: the library, `build-logic/` (an included build of convention plugins),
`demo/` and `benchmark/`. The last two are **separate builds that resolve the library from Maven
coordinates**, exactly as an adopter would, so a malformed POM or a missing transitive dependency
fails here instead of in someone else's app:

```bash
./gradlew publishToMavenLocal
(cd demo && ./gradlew assembleDebug lintDebug spotlessCheck)
```

## How it is tested

This is the part worth copying even if the library is not for you.

Tests drive the library **through its public API, under Robolectric, against Media3's own fakes,
with no device and no network**. Nothing asserts past the facade. A shaped network is a replayed
throughput trace; a CDN failure is an injected fault addressed at a host; a Widevine device and a
licence server are stated, not mocked at the call site.

Two rules make it hold up. **Every stand-in's limits are written down** — `docs/testing.md` says
what each one cannot show, and a claim that needs a device is labelled as needing one rather than
faked. And **behaviour changes appear in review as a readable diff**: golden session traces are
committed, so a change that moves a metric shows up as a line, not as a number someone notices
later.

Two suites are written to run **outside** this repository, against code we did not write:
`HttpTransportConformance` and `FrameSourceConformance`, each naming the rule a failure broke.

## Documentation

[`docs/README.md`](docs/README.md) indexes every document and says which reader each is for. The
ones to start from:

- [**`docs/adr/README.md`**](docs/adr/README.md) — the 18 decision records, `ADR-0001` through `ADR-0018`, all Accepted. Why the library is
  shaped as it is, and the rules a change is held to.
- [**`docs/compatibility.md`**](docs/compatibility.md) — what you may depend on, and what a
  release does not promise.
- [**`docs/testing.md`**](docs/testing.md) — the testing strategy and every stand-in's limits.
- [**`docs/telemetry-schema.md`**](docs/telemetry-schema.md) — what each metric means, with its
  citation.
- [**`PRD.md`**](PRD.md) — the problem inventory, the architecture, and the phase roadmap the
  issues are cut from.

## Contributing

Read [`CONTRIBUTING.md`](CONTRIBUTING.md) first — it is short and two of its rules are unusual:

- **Clean-room discipline.** No proprietary material and no mirroring a commercial SDK's API
  shape. Every non-obvious algorithm carries a public `// spec:` or `// ref:` citation, and
  permissive licences only.
- **A change that contradicts an ADR says so and argues the case.** Winning that argument produces
  a superseding record, not an undocumented exception.

`./gradlew assemble check` must pass, `./gradlew spotlessApply` formats, and a public API change
is committed together with its regenerated `api/` diff.

## Licence

Apache-2.0 — see [`LICENSE`](LICENSE). Third-party dependencies and their licences are registered
in [`THIRD_PARTY.md`](THIRD_PARTY.md), and the one dependency this repository *builds* rather than
resolves is documented with its crate licences in
[`third-party/moq/`](third-party/moq/README.md).
