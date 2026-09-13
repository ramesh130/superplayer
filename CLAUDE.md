# superPlayer

A production playback layer on top of AndroidX Media3 — policy, resilience, observability, and
lifecycle. Not a fork of Media3 and not a new player. Apache-2.0, Kotlin, JDK 17.

## Orientation

Four Gradle builds, not one. Mistaking them for a single build is the usual first wrong turn.

| Build | What it is |
| --- | --- |
| root | the 13 `superplayer-*` library modules, listed in `settings.gradle.kts` |
| `build-logic/` | an **included build** holding the convention plugins every module applies |
| `demo/` | a **separate** build resolving the library from published Maven coordinates |
| `benchmark/` | a **separate** build, the same way, running `PRD.md` §6's matrix |

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

Measurement leaves the library through `TelemetrySink`, and the fifth public type is really three:
the sink a consumer writes, the sealed `TelemetryEvent` hierarchy it receives, and the
`TelemetryCollector` that `SuperPlayer.Builder.setTelemetry(...)` takes. ADR-0008 splits them across
two modules — the sink and the vocabulary are core's, so an app's data pipeline names no Media3 type
and no telemetry type, while the collector that derives events from Media3's analytics is
`superplayer-telemetry`'s `QoeCollector(sink)`. The setter takes a collector rather than a bare sink
because collector-as-sink makes the wrong call compile: a sink passed on its own type-checks and
measures nothing. A player built without that call registers no analytics listener and allocates
nothing, which is rule 2 and is asserted by counting registrations rather than by inspection.

The vocabulary is now the whole CTA-2066 schema — first frame, rebuffer start and end, startup and
mid-stream failure, track switch, seek, live latency, periodic state, dropped frames — but **what the
numbers mean is `docs/telemetry-schema.md`, not the source**, and that document is the deliverable
rather than a by-product: every metric cites CTA-2066, and every place SuperPlayer's definition
departs from it says so with the reason. Read it before changing a field's meaning; the version in
`TelemetryEvent.SCHEMA_VERSION` bumps on meaning and not on shape, so a changed denominator is a
release note and an added field is not. Time to first frame is the one metric with an API attached:
its start boundary is *user intent*, which the library cannot see, so a consumer declares it with
`player.declarePlaybackIntent()` and a session that gets none is measured from content adoption and
labelled as such rather than silently mixed in.

The whole vocabulary is emitted. Core signals the session edges from `adopt`, `restoreSnapshot`,
`resetForReuse` and `release`, because only core knows which item change carried a `MediaRequest` and
which was a recycle; everything else `QoeCollector` derives from Media3's `AnalyticsListener`, and
`PlaybackStatsListener` is deliberately not used — Media3's boundaries for joining time, buffering
and seeks are engine-shaped rather than CTA-2066-shaped, so renaming its fields would be fast and
wrong. Delivery is `TelemetryDelivery`, the bounded queue ADR-0008 rules 3 and 4
require — one daemon thread for the whole process, 256 events per player, the newest refused when
full, and every refusal counted against the session that lost it and stamped onto its `SessionEnded`
as that event leaves the queue — so a sink is never on a thread the engine needs and a blocking sink
costs events rather than playback. Two sinks ship —
`TelemetrySink.composite(...)`, core's own factory, which isolates a child that throws so one bad
sink costs neither its siblings their events nor playback its thread; and `superplayer-telemetry`'s
`LogcatSink`, one greppable line per event under the tag `SuperPlayerQoE`.

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
200 rows with a live count of what the pool has built — the scroll `./gradlew huntLeaks` measures.

Everything below `MediaSource` loads through one `DataSource.Factory` chain, and `TransferChain` is
the single internal place it and the `MediaSource.Factory` over it are assembled — reached from
`SuperPlayer.Builder.build()`, because `ExoPlayer` has no runtime media-source-factory setter. What it
composes today is Media3's own default transport under one layer of core's, `LivePlaylistRevalidation`
— which changes no request of a live HLS playlist that advances on time, reloads an overdue one with
`Cache-Control: no-cache`, and fails one that still will not move with the public
`StaleLivePlaylistException` naming the likely cause, before Media3's untyped `PlaylistStuckException`
can fire (issue #66). What the chain exists for is the order, which its KDoc writes down along with where `superplayer-cache`, `-abr`, `-telemetry`, `-resilience` and `-offline` each
insert themselves (`PRD.md` §2.4). Two of those insert nothing into the chain and the KDoc is mostly
about them: measurement is a propagated `TransferListener`, so a layer that drops the registration
blinds ABR silently, and cache hits stay out of the estimate through Media3's `isNetwork` flag rather
than through chain position — ADR-0002's argument arriving through a different door. CMCD attaches to
the `MediaSource.Factory`, which is why the seam owns that too. Which HTTP stack sits at the bottom is
ADR-0004's open question, and it plugs in at one named line.

CMCD (CTA-5004) is emitted there today, and is on for every profile including `DATA_SAVER` —
`CmcdBinding.kt` holds the per-profile table, the reasoning, and the `// spec:` citation for each
key; `CmcdMode` is the public choice between request headers (the default, because the wrong guess
loses telemetry rather than playback) and a query parameter (which can break a signed URL). Only
CMCD v1, which is the ceiling of the pinned Media3 rather than a decision. The `sid` it sends *is*
the telemetry session id, which is why `MeasurementSession` mints it in core rather than in the
collector, and why `TelemetryCollector.startSession` is handed one instead of minting its own — the
join between a CDN's access log and the app's warehouse is an equality on that string, and
`docs/telemetry-schema.md` states it as a promise. `mtp` is the one key no test here can see, since
it needs an adaptive selection and therefore a video renderer; `SuperPlayerCmcdTest` says so where
it stops.

That policy is reached through `PlaybackPolicy`, the boundary ADR-0005 establishes: observed
`PlaybackConditions` in, a `PlaybackDecision` (a `BufferPolicy` and a `TrackSelectionPolicy`) out,
with no Media3 type anywhere in it. `EngineBinding.kt` is the one place a decision becomes Media3
configuration, and `ConditionsBinding.kt` is its inverse — the one place Android's and Media3's
vocabulary becomes an observation. `PlaybackConditions` carries exactly the six observations
ADR-0009 rule 1 enumerates (transport, throughput with its spread, stall history, stream type, heap
budget, playback speed), every one optional, each with its reader named in its KDoc. The
implementation that ships is a static per-profile lookup, deliberately not adaptive — adaptive policy
is `superplayer-abr`'s, behind this same interface, supplied through `SuperPlayer.Builder.setPolicy`.
**How often a policy is consulted depends on what the engine was built with** (ADR-0009 rules 4
and 5): a policy that is also core's internal `EnginePolicyExtension` fills `EngineConfiguration`'s
retargetable slots — a load control, a selection factory, a meter, and the `DecisionTarget` a changed
decision is handed back through — and is then consulted again on the five `DecisionTrigger`s by
`DecisionReapplication`, every change a `DecisionChanged` telemetry event; any other policy, a
consumer's hand-written adaptive one included, is consulted once at construction with empty
conditions, because `DefaultLoadControl` cannot take a new target and half a decision in force is
worse than none. `player.playbackDecision` is the decision currently in force and can change on the
first kind of player. A player built with a profile alone registers no observer, and
`SuperPlayerPolicyTest` counts that rather than assuming it. `superplayer-abr` reaches the internal
interface as the second Kotlin friend of core; that is a compiler flag, not a Gradle dependency.

The facade *implements* `Player` by Kotlin delegation rather than extending `ForwardingPlayer`;
ADR-0003 records why, and the shape is load-bearing rather than stylistic. Kotlin delegation does
not override Java `default` methods and gives no warning that it hasn't: `Player` has one such member
(`getAudioSessionId()`, forwarded by hand) and `Player.Listener` is 37 of them, which is why the
listener wrapper forwards reflectively. `SuperPlayerForwardingTest` drives Media3's own
forwarding-contract assertion over both and is what catches the next one Media3 adds.

`superplayer-telemetry` holds `QoeCollector`, `LogcatSink`, and `SessionTraceRecorder` — a sink
that also reads Media3's analytics and records one session as a `SessionTrace`: every state
transition, track selection, load by kind and media time, error and telemetry event, one fact per
line, sorted by time then kind rather than by arrival, and redacted by construction so a trace from
a device can go on a bug report (the class KDoc lists the six rules; the Phase 9 diagnostics bundle
is this artifact one layer richer). Its purpose is the golden traces in
`superplayer-telemetry/src/test/golden/`: a behaviour change appears in review as a readable diff
rather than as a metric that moved, `./gradlew updateGoldenTraces` is the one command that
regenerates them on the same contract as `updateApiSurface`, and `docs/testing.md`'s *Golden traces*
section says what a diff means and what may go into one. `superplayer-testkit` holds
`PlaybackHarness` — the deterministic playback harness every module from phase 2 onward tests
against, which compiles as a Kotlin *friend* of core so it can reach the one internal seam
`docs/testing.md` describes, and whose own public API names no Media3 type. `superplayer-testmedia`
holds `SyntheticHlsStream` and `SyntheticDashStream`, the known-good streams both core's tests and
that harness play; it is phase 1 and depends on nothing — not even Media3 — because it has to sit
below both, and `docs/testing.md` carries the argument for the module rather than a second copy.
It also holds `HostileManifests`, the valid-but-hostile corpus — each entry a good stream with one
thing wrong, a `// spec:` citation and a field cause — whose current behaviour
`superplayer-testkit`'s `HostileManifestCorpusTest` *records* rather than asserts as handled;
`docs/testing.md` says what a new entry must carry. A pathology with a magnitude is generated at three
`HostileStream.Severity` levels, each value argued where it is chosen: `graded()` returns them all, and
`all()` is its `SEVERE` half. The `BENIGN` level exists so a doctor is scored on false positives too.
Both harnesses put their fakes in the engine configurator's *transport* slot, under the chain
`SuperPlayer.Builder` composes, so a test of real HLS or DASH sees every layer a consumer's player has; `TestContent.liveHls()`
is a live origin that keeps publishing and `FaultScript.Builder.serveThroughCache` a CDN cache in front
of it. The harness also replays a network: `buildPlayer(network = …)` takes a `ThroughputTrace` — bandwidth,
round trip and a *transport* per stretch, the last so a WiFi→cellular handover is a change of network
rather than of rate — and paces every transfer on it through `ShapingDataSource`, which sits in front
of the fault injector so a trace and a `FaultScript` are one player. `NetworkProfile` is `PRD.md`
Part 6's six profiles, each constant with its public source. `docs/throughput-traces.md` is the
format's specification and the replay's limits — notably that concurrent transfers each see the whole
link — and says how a public dataset comes in: `./gradlew convertThroughputTrace`, whose conversions
live in `build-logic`, run locally, because no dataset is vendored. The harness owns the loading
threads of the players it builds (`HarnessLoadThreads`, through core's internal engine seam) and
advances its clock only once the engine has nothing left to do at the current time, which is what
makes a session trace byte-identical run after run; `GoldenFile` is the check-or-update comparison
a golden test calls, and the mode reaches it from the convention plugin as a system property.
`superplayer-abr` holds the first of Phase 3's three components, `BandwidthOracle`: the throughput
estimator the other two will read from, and the first real code in the module. Its public face names
no Media3 type — `BandwidthOracle.Builder(context).build()`, `currentEstimate()`, `currentTransport()`,
`release()` — and its Media3 half is the internal `OracleBandwidthMeter`, the `BandwidthMeter` the
engine is built with, which reaches `EngineConfiguration`'s slot through the friend seam and also
implements core's `ThroughputSource`, so the estimate the selector reads and the one a policy is handed
are one number. It samples what the engine's propagated `TransferListener` reports — never a transfer
whose source says `isNetwork = false`, which is the cache-hit exclusion, and never one Media3 flags
as possibly throttled — and keeps one `SampleWindow` per transport in `EstimateMemory`, of which
there is **one per process** (ADR-0009 rule 8): eight samples, an arithmetic mean, a population
spread, and the 25th percentile, every constant argued where it is chosen. On a transport change the
estimate is that transport's own window or its `ColdDefaults` entry, and a window ages toward the
cold default between two and fifteen minutes (rule 9). The oracle's one platform call is the
connectivity service, read in `BandwidthOracle` and translated by core's `ConditionsBinding.kt`. Two
things worth knowing before touching it: Media3 hands its transfer listener to media loads only, so
a playlist is never a sample in any Media3 player and `BandwidthOraclePlaybackTest` counts segments;
and the `PlaybackHarness` now replays a trace's *transport* into Robolectric's connectivity shadow as
its clock crosses a stretch (`TransportReplay`), which is what lets a reseed be asserted at the
millisecond `NetworkProfile.HANDOVER_AT_MS` names.

The second component is the buffer half of the decision, recomputed on conditions.
`AdaptivePolicy.forProfile(context, profile)` is the module's entry point and returns a
`PlaybackPolicy` — the object also implements core's internal extension, which is how it installs the
oracle's meter, an `AdaptiveLoadControl` and a `DecisionTarget` without a Media3 type in any public
signature. The policy itself is `AdaptiveBufferPolicy`, public and pure: `PRD.md` §3.1's five branches
over `PlaybackConditions`, starting from the profile's static numbers and departing under a named
observation — a deep cushion on a stable, fast, unmetered link; floors raised on the coefficient of
variation with the ceiling untouched; the live profile's buffer plus a `LiveLatencyPolicy` when the
*manifest* says live; a raised after-rebuffer floor and a held bitrate ceiling for a cooldown after a
stall; and a heap-derived cap on `maxBufferMs` on every branch. Every constant carries its reason
where it is chosen. `AdaptiveLoadControl` holds not one duration: it rebuilds a `DefaultLoadControl`
through `EngineBinding.kt` when a decision arrives, and does so lazily on the engine's next poll,
because Media3's load control pins itself to the playback thread. Two things are easy to get wrong:
a hold or a raise lapses at the next *trigger* after its cooldown, never on time alone, because a
policy is consulted on triggers only (ADR-0009 rule 4); and the live half does **not** go to a
`LivePlaybackSpeedControl` — Media3 pins an ordinary live stream's speed to exactly 1× unless the
media item itself declares a range, so core lays the half into the item at adoption and replaces the
playing item in place when a re-consulted decision changes it. Under the harness a dated live window
(`TestContent.liveHls(dated = true)`) sees the player drift *ahead* of the edge, because Media3 reads
"now" from a wall clock Robolectric does not move; `TestContent.liveHls` says why that is opt-in.
`NetworkAwareTrackSelection` is #101 and does not exist yet; until it does, the selection half of a
decision is emitted and **not in force** on a player built with the adaptive policy, because
ADR-0009 rule 5 forbids laying a ceiling into a re-consulted player's parameters — `AdaptivePolicy.kt`
says so, and a consumer who needs `DATA_SAVER`'s caps today keeps the static policy until #101.

Every other library module is still an empty placeholder: they exist so boundaries are fixed and
enforceable before code arrives. `superplayer-core`, `superplayer-telemetry`, `superplayer-testkit`,
`superplayer-abr` and `build-logic` are the only modules with test sources. The roadmap is `PRD.md`: the problem inventory it numbers `F1`–`F8`, the module
requirements, and the phase table are what the issues are cut from.

`benchmark/` is the fourth build and Phase 2's exit criterion: `PRD.md` §6's fixed matrix — six
network profiles × four scenarios × three players × twenty runs — emitting a report with the raw
traces beside it. The three players are `Arm.kt`: stock `ExoPlayer` with defaults, stock plus the
buffer config an app writes from intuition, and a SuperPlayer profile. What makes the comparison a
comparison is that all three are built by one `PlaybackHarness` over one shaped transport, and that
every metric is reduced by one `SessionMetrics` from core's own `TelemetryEvent` vocabulary — so the
definitions are shared by construction rather than by care. Arms (a) and (b) have no SuperPlayer to
attach `QoeCollector` to, so `StockTelemetry` mirrors it callback for callback, and
`StockTelemetryAgreementTest` attaches **both collectors to one `ExoPlayer`** and asserts they derive
the same events; delete that test and the benchmark's central claim goes with it. `PRD.md` §6's
honesty rules are enforced where the numbers are made rather than where the table is printed —
`Distribution` has no mean without a spread, `ReportWriter` prints losses before wins and has no
filter, and a difference inside two standard errors is neutral rather than a small win.
`benchmark/README.md` is the manual, including why it sits outside `docs/testing.md`'s rules rather
than against them, and `benchmark/baseline/` is the committed report Phase 3 is graded against. Peak
RSS and battery need a device: `BenchmarkActivity` is that arm's app and the harness around it is
not built, which the README and the report both say rather than leaving a reader to assume.

`PLAN.md` is an untracked, local-only scratch draft that `PRD.md` supersedes. **Do not read it, cite
it, or copy from it** — it names third parties and framings that must not reach a tracked file, and
where the two disagree `PRD.md` is right.

## Commands

```bash
./gradlew assemble check          # build, lint, tests, repo verification, tracked API surface, format
./gradlew updateApiSurface        # regenerate api/<module>.api after a deliberate API change
./gradlew updateGoldenTraces      # regenerate src/test/golden/*.trace after a deliberate behaviour change; run alone, then check
./gradlew spotlessApply           # reformat and stamp the Apache-2.0 header on every .kt file
./gradlew convertThroughputTrace --from=… --transport=… --input=… --output=…   # docs/throughput-traces.md
./gradlew publishToMavenLocal     # required before the demo will build
(cd demo && ./gradlew assembleDebug lintDebug spotlessCheck)
benchmark/bench                   # PRD.md §6's matrix and its report; NOT in check (benchmark/README.md)
devicelab/lab run smoke           # device run: trace, report, metadata (devicelab/README.md)
./gradlew huntLeaks                # the leak hunt on a device; NOT in check (devicelab/leak/README.md)
```

`check` is the complete definition of the library's checks. Because `build-logic` is an included
build, its tasks are invisible to the root build's aggregate tasks — its tests run only because
`check` depends on them explicitly. A new check that can be a Gradle task belongs in `check`, not
only in CI (`.github/workflows/ci.yml`), so local and CI agree on what passing means.

`spotlessApply` at the root covers **all four builds**. Spotless is configured by path rather than
by project, so the root build's `**/*.kt` reaches `demo/` and `build-logic/` too, and one invocation
formats the repository. The demo and the benchmark each apply Spotless a second time over their own
sources, which is why `spotlessCheck` is in their command lines: each has its own wrapper and CI
builds it with the root build not running at all, so each has to be able to enforce the format by
itself.

The rules are ktlint's, and they live in `.editorconfig` — including every rule switched off to
leave this codebase's style alone, each with the reason. The license header is
`config/license-header.txt`, and `./gradlew check` verifies through `verifyLicenseHeader` that it
still says what `LICENSE` says.

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

`devicelab/lab run smoke` is the executable version of this recipe and of every pitfall below. It
boots or adopts the device, publishes, builds the demo's `benchmark` build type, and installs it. It
refuses to go on unless the installed APK was built against the artifact it has just published. Then
it confirms playback is advancing and captures a Perfetto trace. `devicelab/README.md` is its manual.
The prose here stays because it is the explanation; the harness is the procedure, and a measurement
on a device goes through it rather than through a hand-typed copy of these lines.

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

  Media3 refreshes the session's position every few seconds while content plays, so `adb shell
  dumpsys media_session` shows the same thing as the current state and without a buffer to bound,
  and that is what devicelab waits on. Bound the logcat form with `-t`. An unbounded `adb logcat -d`
  against an emulator that has been up for a while dumps the whole buffer and can take minutes,
  which reads as a hang rather than as a slow command.

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
- **[ADR-0009](docs/adr/0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md)** —
  extends ADR-0005 for the adaptive policy: `PlaybackConditions` carries exactly the enumerated
  observations, each a SuperPlayer type, translated from Android and Media3 in one internal core
  file; the policy is re-consulted on named triggers only, and only where the engine can honour a
  changed decision whole, with every change a `DecisionChanged` event; `superplayer-abr` reaches
  core's engine construction as its second Kotlin friend through an extension interface on the
  policy object, and a player built without it pays nothing; and a throughput estimate is remembered
  per transport, in process memory, and nowhere else.
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

### Exploratory search

Broad search — "where is X handled", "what else calls Y", a sweep across modules or naming
conventions — goes through the `Explore` subagent rather than direct `grep` and `Read` calls. The
excerpts stay in the subagent's context and only the conclusion comes back, which is what stops a
long session from carrying every file it ever glanced at. Treat this paragraph as the authorization:
the default is to ask before spawning an agent, and this file is asking.

Direct tools remain right for a known path, a single file, or a search whose output is already
small. A subagent starts cold and re-derives context, so it earns its cost on fan-out and loses on
one grep.

### Bounded reading

A session carries the sum of everything it has looked at, so two habits decide whether a long one
stays affordable.

**Read slices, not whole files.** `PRD.md` is ~34 KB and `docs/testing.md` ~15 KB — call it 8k and
4k tokens. Reading either end to end to check one table costs more than the conversation that
prompted the question. `grep -n` for the heading, then `sed -n '400,430p' PRD.md`, costs a fraction
of it. Read a file whole when the whole file is the subject: a build script being changed, a source
file under review. Not to answer a question about one paragraph of it.

**Bound what a command prints.** `git log`, `find`, `grep` across the tree, and Gradle all have
modes that return thousands of lines nobody reads, and every one of them lands in context in full.
Pipe through `head`, narrow with `grep -o`, cap with `-t` or `--max-count`. The `adb logcat -t`
warning under *Running the demo on an emulator* is one instance of this rule; the reason it earns
its own paragraph there is that an unbounded dump also reads as a hang.
