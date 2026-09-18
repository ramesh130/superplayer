# Module layout and dependency direction

SuperPlayer is a multi-module Gradle build. Every module named in the project plan exists now, even
where it carries no code yet, so that module boundaries and dependency direction are fixed and
enforceable from the start rather than negotiated once code arrives.

## The rule

**Dependencies point inward, toward `superplayer-core`. Nothing points outward, and no module
depends on a module from a later phase.**

`superplayer-core` depends on Media3 and on nothing else in this repository. Every other library
module depends on `superplayer-core` and, where a genuine need appears, on modules from an *earlier
or equal* phase only. A module may never depend on one whose phase number is higher than its own —
that dependency would make the earlier phase un-shippable without the later one.

**A tie is allowed**, and that is a decision rather than an omission. `telemetry` and `testkit` are
both phase 2; `preload` and `cache` are both phase 4. The rule exists so that an earlier phase is
shippable without a later one, and modules sharing a phase ship together — so a dependency between
peers costs nothing the rule protects. A cycle between two of them is a real problem, but it is one
Gradle already refuses to build; it is not this rule's to catch.

`./gradlew verifyModulePhaseRule` reads the phase column below and fails the build if any module's
`project(...)` dependencies break the rule. It runs as part of `check`, so this is enforced rather
than remembered. The task parses this table, which makes the document the single source of the phase
numbers; the cost is that the table's shape — a backticked module name in the first cell, the phase
in the second — is now load-bearing. A dependency on a module this table does not list fails too,
deliberately: an unlisted module has no phase to compare, and the answer is to add the row rather
than to wave the dependency through.

## Modules

| Module | Phase | Purpose | Depends on |
| --- | --- | --- | --- |
| `superplayer-core` | 1 | SuperPlayer facade, PlaybackSession, config profiles, player pool, transfer chain (live-playlist revalidation) | Media3 only |
| `superplayer-telemetry` | 2 | QoE collector (CTA-2066), CMCD emitter, pluggable sinks, session trace recorder and its goldens, and the session reduction and QoE objective (`SessionMetrics`, `QoeScore`) the benchmark and the QoE regression gate share | core, testkit (tests only) |
| `superplayer-testkit` | 2 | Fault injection, network shaping, fake manifests, golden traces | core, Media3 test utils |
| `superplayer-testmedia` | 1 | Synthetic HLS and DASH streams: the known-good media tests play | nothing |
| `superplayer-abr` | 3 | BandwidthOracle (per-transport estimates, spread beside the mean, cache hits excluded); AdaptivePolicy — AdaptiveBufferPolicy's five branches over a retargetable AdaptiveLoadControl, and AdaptiveSelectionPolicy's transport caps and hold over NetworkAwareTrackSelection, which also gates on the display and the decoder | core, testkit and telemetry (tests only) |
| `superplayer-preload` | 4 | PreloadCoordinator, attached to a PlayerPool: Media3's preload manager over the chain core assembles, first-segment prefetch in scroll order, decoder warm-up bounded by the pool, the memory guard and the data-saver rule (ADR-0010) | core; testkit and telemetry (tests only) |
| `superplayer-cache` | 4 | ContentCache opened by the consumer in a directory they name: content-keyed CacheDataSource, LRU eviction within their budget, a pinned region for offline (ADR-0010) | core; testkit, abr and telemetry (tests only) |
| `superplayer-resilience` | 5 | ErrorClassifier, RetryPolicy, FallbackLadder | core |
| `superplayer-drm` | 6 | WidevineSessionManager, provisioning, offline licenses, fallback ladder | core; testkit, resilience and telemetry (tests only) |
| `superplayer-offline` | 7 | Downloads into the ContentCache the consumer opened, on the one chain: a store over Media3's download stack, WorkManager scheduling under unmetered, battery-not-low and storage-not-low, download track selection, and the licence bound to the download (ADR-0013) | core; testkit, testmedia, cache, drm and resilience (tests only) |
| `superplayer-tv` | 8 | CTV behind one output slot: frame-rate matching, the display as a live reading, tunneling where the policy asks, and Compose-for-TV controls on a `SurfaceView` (ADR-0014) | core; testkit, testmedia, abr, drm, resilience and telemetry (tests only) |
| `superplayer-diagnostics` | 9 | MediaSourceDoctor — a manifest's pathologies named over the chain a player of that request would load through, as one report for preflight and postmortem — the session trace bundle with its capability snapshot, and the on-device debug HUD (ADR-0015) | core and telemetry (ADR-0015 rule 1); testkit, testmedia, abr, cache, drm and resilience (tests only) |
| `superplayer-ui` | — † | Optional Compose player surface | core |

`superplayer-testkit`, `superplayer-abr`, `superplayer-cache`, `superplayer-preload`,
`superplayer-resilience`, `superplayer-drm`, `superplayer-offline`, `superplayer-tv` and
`superplayer-diagnostics` also compile as Kotlin *friends* of core (`docs/testing.md`'s *Reaching that seam from another module*,
ADR-0009 rule 7, ADR-0010 rules 3 and 6, ADR-0011 rule 13, ADR-0012 rule 4, ADR-0013 rule 4, ADR-0014
rule 3, ADR-0015 rule 3). A friend path is a compiler flag rather than a Gradle
dependency, which is why it does not appear in the column above and why it is not what the rule
measures.

ADR-0015 rule 3 admits `superplayer-diagnostics` as the **ninth** and bounds it to a closed list of
internal seams: a doctor's chain, which is ADR-0013 rule 4's second shape unchanged, plus one
function returning the capability snapshot and the judgements a doctor must share rather than copy,
which reach past it and are admitted by name. #286 took the friendship, and the first seam with it;
the capability snapshot is #292's, `LiveWindowDepthCheck`'s judgement was #288's, and #289 added a
**fourth**, `LivePlaylistRevalidation`'s, by amending that rule by name — which is what any further
seam needs, in the change that reaches it. What the module *produces* is documented for readers who
have never seen this repository, which is #295's half of the phase: `docs/media-source-doctor.md` is
every pathology with its citation, its cause and what to change, `docs/session-bundle.md` is the
bundle's format and `docs/reading-a-session-bundle.md` how to get an answer out of one.

**Phase 10 adds no module, deliberately.** The HTTP stack (ADR-0004, ADR-0016) is a seam in core and
an interface the *consumer* implements over their own client, so there is no `superplayer-net-okhttp`
or sibling in the table above and no tenth Kotlin friendship — ADR-0004's *Consequences* records the
cross-module visibility question that shape would have raised, and why removing the module closes it
rather than answering it. The conformance test a consumer runs against their own transport is
`superplayer-testkit`'s public `HttpTransportConformance` (#312), and that module is phase 2 and
depends on core alone, so it reaches nothing later than itself.

`superplayer-testmedia` is the one module that depends on nothing, and that is what it is for. Its
synthetic HLS and DASH streams are played by `superplayer-core`'s tests *and* by
`superplayer-testkit`'s main source set, and core cannot depend on testkit — a later phase, and a
cycle besides — so the one home both can see has to sit below both. Phase 1, therefore: a phase-1
module's tests may reach it, which is the constraint that fixes the number. **That number is the one
exception to the rule below that phases come from `PRD.md`** — the roadmap does not schedule this
module, because it is not a capability anyone ships but the fixture the other modules' tests share,
so it is the dependency rule rather than the roadmap that decides where it sits. It names no Media3 type
either, which is what keeps `verifyNoUnstableMedia3InPublicApi` satisfied without an `@UnstableApi`
`FakeDataSet` in a signature; serving a stream from one is a line at each call site.
`docs/testing.md` carries the rest of the argument, including why duplicating the generators or
shipping them inside `superplayer-core`'s own artifact was worse.

**The phase numbers come from [`PRD.md`](../PRD.md) Part 4, and from nowhere else.** That table is
the roadmap; this one is the roadmap expressed as a dependency constraint. When the two disagree the
PRD wins, and the fix is to correct this column rather than to reason from it — a stale number here
does not merely mislead, it changes which dependencies the rule above permits.

† `PRD.md` Part 4 does not schedule `superplayer-ui`. It appears in the module layout of §2.1 and in
no phase, so it carries no number here rather than an invented one — a number would have to be either
a schedule the roadmap has not made, or a tie with a module it has no stated relationship to. Both
halves of the rule above still resolve without one: no module may depend on it, because an unscheduled
module is not an earlier phase than anything; and it may depend on any scheduled module. The row gains
a number when the roadmap schedules it.

Only `superplayer-core` is required by a consumer. Every other module is additive: an app depends on
what it uses and its APK does not grow for features it does not need.

## Two separate builds beside this one

`demo/` and `benchmark/` are **not** modules of the root build. It is a standalone Gradle build that resolves
SuperPlayer through published Maven coordinates:

```kotlin
implementation("com.superplayer:superplayer-core:0.1.0-SNAPSHOT")
```

not through `project(":superplayer-core")`. This is deliberate. A source dependency would hide
exactly the class of defect an adopter hits first — a missing transitive dependency, a malformed
POM, a wrong artifactId, a variant that does not resolve. Consuming the published artifact makes
those failures happen here, during development, rather than in someone else's app.

The cost is one extra step: the library must be published locally before either will build.

```bash
./gradlew publishToMavenLocal        # from the repo root
cd demo && ./gradlew assembleDebug
benchmark/bench                      # publishes first, then runs the matrix
```

`benchmark/` is the same arrangement for the same reason, and one more: `PRD.md` §0.2 says every
performance number this project publishes comes from its own harness, so the harness had better be
measuring the artifact a consumer would actually get rather than a source dependency that skips half
of what shipping means. It also resolves `superplayer-telemetry` and, for its Robolectric arm,
`superplayer-testkit`. [`benchmark/README.md`](../benchmark/README.md) is its manual, including why a
benchmark sits outside [`docs/testing.md`](testing.md)'s no-device, no-network rule rather than
against it.

## Building

A clean checkout needs a JDK 17 or newer and an Android SDK with the platform named by
`compileSdk` in the version catalog. Point `ANDROID_HOME` at the SDK, or write `sdk.dir` into a
`local.properties` file — that file is machine-specific and gitignored. Gradle itself does not need
to be installed; the checked-in wrapper fetches the pinned version.

```bash
export ANDROID_HOME=/path/to/android/sdk
./gradlew build check                # compiles all 13 modules, runs lint and tests
./gradlew publishToMavenLocal        # publishes all 12 to ~/.m2
```

## The version catalog

`gradle/libs.versions.toml` is the single source of truth for every dependency version, and both
builds — the library and the demo — read from that one file. Media3's version in particular is
declared there and nowhere else, per ADR-0001 rule 3, so no consumer can hit a version-skew crash
between transitively resolved media modules.

`./gradlew verifyNoHardcodedMedia3Versions` fails the build if any build script pins a Media3
version directly. It runs as part of `check`, so the rule is enforced rather than remembered.

### Supported Media3 versions

ADR-0001 rule 3 requires each module to document the Media3 range it supports. Every module in this
repository currently supports **Media3 1.11.x**, the version pinned in the catalog, and no other.

The range is deliberately a single minor version for now. SuperPlayer builds on Media3 surfaces that
are largely `@UnstableApi` and may change within the 1.x line, so claiming a wider range would be
claiming compatibility nobody has tested. It widens when there is a compatibility test proving it,
not before. A module that comes to support a different range from the rest states so in its own
build file, and this table gains a column.

`./gradlew verifyMedia3SupportedVersion` fails the build when the catalog pins a Media3 outside the
minor version stated above. A Media3 bump therefore cannot land with this section left behind: a
patch release inside 1.11.x passes, and anything else fails until the section is rewritten. It
reads the bold **Media3 X.Y.x** in this section, so a rewording has to keep that form or teach
the check the new one. It runs as part of `check`.

## Conventions live in `build-logic`

The 12 library modules share one definition of what a SuperPlayer module is — Android library setup,
SDK levels, Java and Kotlin target, lint configuration, and publishing — in the
`superplayer.android.library` convention plugin under `build-logic/`. A module's own build file
therefore contains only what is genuinely specific to it: its dependencies.
