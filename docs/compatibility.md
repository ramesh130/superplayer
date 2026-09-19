# Compatibility: what you may depend on, and for how long

For someone integrating SuperPlayer into an app, deciding what they are allowed to build on.

Every answer below is a consequence of
[ADR-0017](adr/0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md),
which is where the rules live and where they are argued. This document cites those rules **by
number** and says what each one means for you; it does not reproduce the ADR's reasoning, and where
the two ever read differently the ADR is the one that binds. Where a rule is enforced by a build
task, the task is named; where it is not, this document says so rather than implying otherwise.

> **Before anything else: there is nothing to resolve yet.** SuperPlayer has never been released,
> and publishing to a remote repository is deliberately out of scope — there is no publish
> `repositories { }` block anywhere in the repository, no credentials and no release workflow. A cut
> release lands in the local Maven repository of the machine that cut it and **is resolvable by
> nobody else** ([`releasing.md`](releasing.md), *Where the artefacts go, and where they do not*).
> Adding a `com.superplayer:…` coordinate to your build today resolves nothing. What follows is the
> contract a release will carry when one is published, stated now so that it is a promise made in
> advance rather than one invented afterwards.

## Which coordinate do I depend on, and do the versions have to match?

The group is `com.superplayer` and the artifact id is the module's own directory name, so
`superplayer-core` is `com.superplayer:superplayer-core`. Only `superplayer-core` is required; every
other module is additive, and an APK does not grow for a module the app does not depend on
([`modules.md`](modules.md)).

**Every module carries the same version, and they all move together.** A release moves all fourteen
to one number, including the modules nothing changed in (ADR-0017 rule 1). The consequence for you
is the part worth acting on:

> **Mixing two SuperPlayer versions in one build is unsupported.** `superplayer-drm:0.4.0` against
> `superplayer-core:0.3.0` is not a combination that was ever built, tested or benchmarked, and
> nothing in the library will tell you at runtime that you assembled one.

That is stronger than the usual multi-module caution, and the reason is mechanical. **Ten** of the
fourteen reach into `superplayer-core` through Kotlin *friend* compilation — a compiler flag, not a
Gradle dependency, carrying no binary-compatibility guarantee at all — so a change core is entitled
to make under rule 2 can break a module compiled against the previous core without any check seeing
it (ADR-0017 rule 1; [`modules.md`](modules.md) lists the ten). Pin one version for all of them, in
one place.

**A `-SNAPSHOT` version is not a release and carries none of this.** An artefact resolved from a
snapshot coordinate may change under that same coordinate without notice (ADR-0017 rule 8). Today's
`0.1.0-SNAPSHOT` is the absence of a promise rather than a promise being kept.

## What may change under me, and when?

SuperPlayer is versioned `MAJOR.MINOR.PATCH` by [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html).
What decides the bump is not a judgement call: it is the diff of the tracked public API surface — one
checked-in file per module, `<module>/api/<module>.api`, described in
[`api-surface.md`](api-surface.md) — against the surface recorded at the last release (ADR-0017
rule 2, gated by `./gradlew verifyVersionBump`).

| Bump | What moved in the tracked API surface | What it means for your build |
| --- | --- | --- |
| **Major** | A declaration removed, or changed incompatibly | Your code may stop compiling; read the changelog before taking it |
| **Minor** | Declarations added, none removed | Recompiling against it should succeed |
| **Patch** | Nothing — the surface is byte-identical | An internal or behavioural fix |

Two things that table does not say, and both matter more than it does.

**A behaviour change that removes no declaration is still a major** (ADR-0017 rule 3). A changed
default in a playback profile, a retry budget that shrank, a cache key that now spells a URI
differently — none of them moves a declaration and any of them can break an app. The surface is the
arbiter of *at least* how far the version moves, never of at most. No tool can enforce this; what
carries it is [`CHANGELOG.md`](../CHANGELOG.md), which is where such a change is written down
rather than shipped silently.

**And while the library is `0.x`, a minor release may break you** (ADR-0017 rule 7). That is
semver's own `0.x` clause and it is meant literally here: under `0.x` a minor may remove or change
public declarations, because the major has nowhere to go until it is non-zero. The reporting still
happens — a shrunken surface is still a reviewed, changelog'd shrink — but the number cannot express
it. **Read the changelog for every minor while the version starts with a zero.** ADR-0017 rule 7
lists the four things that take the library to `1.0.0` and ends the caveat.

## Which parts are stable?

Every module below but one is published, and each published module's public API is tracked and
validated on every build ([`api-surface.md`](api-surface.md)). "Stable" therefore means the same
thing for all of them — the API is the file, and a change to it is visible and reviewed — and the
pre-1.0 caveat above applies to every row equally. What differs between rows is what the module is
*for*. The exception is the row marked **Not published**, which has no artifact and therefore no
tracked surface either; it is listed because a module you can see in this repository and cannot
resolve is worth being told about rather than left to be discovered.

| Module | Status | What you may depend on |
| --- | --- | --- |
| `superplayer-core` | **Public** | The facade and the whole vocabulary: `SuperPlayer`, `MediaRequest`, `PlaybackProfile`, `PlaybackSnapshot`, `PlaybackSession`, `PlayerPool`, the telemetry sink and event types, `PlaybackPolicy`, `HttpTransport`, `FrameSource`. The only module an app needs |
| `superplayer-testmedia` | **Public (for your tests)** | Synthetic HLS and DASH streams, and the hostile-manifest corpus. Depends on nothing, not even Media3 |
| `superplayer-telemetry` | **Public** | `QoeCollector`, `LogcatSink`, `SessionTraceRecorder`, and the session reduction (`SessionMetrics`, `QoeScore`) |
| `superplayer-testkit` | **Public (for your tests)** | `PlaybackHarness`, and `HttpTransportConformance` — the suite to run against your own `HttpTransport` ([`http-transport.md`](http-transport.md)) |
| `superplayer-abr` | **Public** | `AdaptivePolicy` and `BandwidthOracle`. The adaptive behaviour behind them is tuning work and is expected to move; the types are not |
| `superplayer-cache` | **Public** | `CachePolicy` and the `ContentKeyedCache` it opens in a directory you name. The `ContentCache` you hand to a player is `superplayer-core`'s type |
| `superplayer-preload` | **Public** | `PreloadCoordinator`, attached to a `PlayerPool` |
| `superplayer-resilience` | **Public** | `Resilience`, `HeaderProvider`, `FailureClass` and `ErrorClassifier` — the failure taxonomy a log line and a warehouse row share |
| `superplayer-drm` | **Public** | `Drm.widevine`, `WidevineConfig`, and the offline licence store |
| `superplayer-offline` | **Public** | `Downloads`, `DownloadItem`, `DownloadsListener`, and the `DownloadsService` your app subclasses |
| `superplayer-tv` | **Public** | `TvOutput` and `TvPlaybackControls` |
| `superplayer-diagnostics` | **Public** | `MediaSourceDoctor` ([`media-source-doctor.md`](media-source-doctor.md)), `SessionBundle` ([`session-bundle.md`](session-bundle.md)) and `DebugHud` |
| `superplayer-realtime` | **Public** | `Realtime.transport`. The `FrameSource` a realtime transport implements is `superplayer-core`'s ([ADR-0018](adr/0018-push-encoded-frames-through-one-framesource-into-media3s-own-sample-queues.md) rule 2's #356 addendum), so a transport module depends on core for the seam and on this one for `Realtime.transport`. What a realtime stream does **not** get is a rule rather than an omission — no cache, no CMCD, no downloads, no bandwidth estimate and none of the fallback ladder's load-error rungs ([ADR-0018](adr/0018-push-encoded-frames-through-one-framesource-into-media3s-own-sample-queues.md) rule 6) |
| `superplayer-moq` | **Not published** | Nothing, and there is no artifact to resolve. Phase 14's prototype links Media over QUIC's Kotlin bindings from a native library built on one machine, for `arm64-v8a` alone ([ADR-0018](adr/0018-push-encoded-frames-through-one-framesource-into-media3s-own-sample-queues.md), `third-party/moq/README.md`). It is in the build and out of the release |
| `superplayer-ui` | **Empty** | Nothing. The module publishes no public declaration; it exists so the boundary is fixed before code arrives |

The four statuses are the four answers there are: **Public** is a module whose API you build
your app on, **Public (for your tests)** is one whose API belongs in a test source set, **Empty**
is one that publishes nothing to depend on yet, and **Not published** is one that is in this
repository's build and is *not released with the others* — so there is no coordinate for it at
any version, and ADR-0017 rule 1's "every module at one version" reads over the first three.
The last exists because a module can carry something that is nobody else's to resolve: today
that is a natively built dependency, and `settings.gradle.kts` is where the exclusion is
declared, in the same line the build's check and the publishing plugin both read.

`./gradlew verifyCompatibilityDocument` holds that table to `settings.gradle.kts`, so a module the
build publishes cannot be left out of this document, a row cannot outlive its module, and a status
cannot disagree with what that file declares about publishing. It compares the two as *sets*: the rows are ordered by the phase each module was built in, which is a choice
about reading rather than a fact the check enforces. Everything in the third column is **prose** —
it describes each module, and nothing verifies it.

## What does the Media3 version have to do with mine?

SuperPlayer composes on AndroidX Media3 and does not fork it. Every module here supports
**one** Media3 minor version, stated under *Supported Media3 versions* in
[`modules.md`](modules.md) and enforced against the build's own pin by
`./gradlew verifyMedia3SupportedVersion`. Read it there; it is the one copy.

**A Media3 upgrade is not automatically a SuperPlayer major** (ADR-0017 rule 4). No Media3 type marked
`@UnstableApi` crosses SuperPlayer's public boundary — that is
[ADR-0001](adr/0001-compose-dont-fork.md) rule 2, and `./gradlew verifyNoUnstableMedia3InPublicApi`
fails the build on a breach rather than merely showing it in a diff — so a Media3 release that
rearranges its buffering, selection or analytics interfaces moves no declaration in any tracked
surface file. Absorbing that is the library's job, not yours. What a Media3 bump *does* move is the
**minor**, never the patch, because the Media3 version your build resolves changes with it and a
pin of your own can be forced off (ADR-0017 rule 4).

**There is exactly one exception, and it is `player.exoPlayer`.** That property hands you the
wrapped `ExoPlayer`, an `@UnstableApi` type, deliberately — it is ADR-0001 rule 2's one named
breach, so that an app needing something the facade does not expose is not blocked by the boundary.
**What you reach through it carries Media3's own compatibility, not SuperPlayer's**, and a Media3
change that breaks code written against it moves no version here (ADR-0017 rule 4). If you use that
property, track Media3's own release notes as well as this library's; that is the price of the
hatch, and it is worth knowing before you reach through it rather than after.

## What about the telemetry schema and the trace format?

There are two other version numbers in this library, and **neither of them is the library version,
in either direction** (ADR-0017 rule 6).

| Constant | Reads | What it versions | Where it is defined |
| --- | --- | --- | --- |
| `TelemetryEvent.SCHEMA_VERSION` | **2** | What a telemetry metric *means* to a data pipeline | [`telemetry-schema.md`](telemetry-schema.md) |
| `SessionTrace.FORMAT_VERSION` | **1** | What a line of a session trace or bundle *means* to its reader | [`session-bundle.md`](session-bundle.md) |

Both move on **meaning and not on shape**: an added event type or an appended field does not move
them, while a changed denominator does. That is why several event types have been added since
`SCHEMA_VERSION` last moved and it still reads 2.
[`telemetry-schema.md`](telemetry-schema.md)'s *Release notes*
is where a moved schema version is argued, and is the document to hand whoever owns your warehouse.

The independence runs both ways. A SuperPlayer major does not move either constant, and a moved
constant does not by itself decide SuperPlayer's next bump — a changed metric definition that adds
no field and removes no declaration is a `SCHEMA_VERSION` bump *and*, under ADR-0017 rule 3, a
library major on its own separate merits. **A pipeline that read the library version as a schema
version would mis-parse every event.** Read the schema version off the events.

## How much warning do I get before something goes?

A public symbol on its way out is annotated `@Deprecated`, **with its replacement named** —
`ReplaceWith` wherever the replacement is expressible — and **survives at least one minor release**
in that state; the removal itself happens in a major (ADR-0017 rule 9). So the first you hear of a
removal is a compiler warning in a release you chose to take, which is a migration you can schedule,
rather than a build that stopped working.

Rule 7's "a minor may break" does not cancel this. Under `0.x` the removal still happens in a minor,
because the major has nowhere to go — but it is still deprecated first, and the changelog row still
says so. The `0.x` licence is for the version *number* to be small, not for the removal to be a
surprise.

**Nothing enforces this mechanically**, and ADR-0017 rule 9 says why rather than implying otherwise.
What you can check for yourself is the changelog row, which is where a removal and the release that
deprecated it are both written down.

## What does a release not promise?

The honest list. Each of these is a real gap rather than a disclaimer.

- **Anything reached through `player.exoPlayer`.** The one `@UnstableApi` escape hatch, outside this
  document's promise entirely (ADR-0001 rule 2, ADR-0017 rule 4). Code written against it is code
  written against Media3.
- **Behaviour on a device this repository cannot test.** Every test here runs under Robolectric
  against Media3's own fakes, with **no device** ([`testing.md`](testing.md)). Decoder behaviour,
  secure surfaces, real display modes, tunneling, audio passthrough and the platform's own HTTP
  engine are all exercised against stand-ins; `testing.md` says per stand-in what it cannot show.
- **Behaviour on a real network.** The tests reach **no network**: streams are synthetic, faults are
  scripted, and throughput is a replayed trace ([`throughput-traces.md`](throughput-traces.md)). The
  one carve-out is the transport conformance suite, which binds a server socket on the **loopback**
  interface and reaches nothing off the host ([`testing.md`](testing.md), *The conformance test a
  consumer runs*). A release is not evidence that a particular CDN, packager or middlebox behaves.
- **Performance numbers.** Nothing in a version number promises a startup time, a rebuffer rate, a
  peak RSS or a battery figure. What exists is a fixed benchmark matrix and its published report
  (`benchmark/README.md`), measured on the machine that ran it.
- **A behaviour you depended on but the API never named.** ADR-0017 rule 3 says such a change moves
  the major; it cannot say that every such change was noticed. The changelog is where the ones that
  were are written down.
- **Compatibility of a mixed set of versions**, and of anything resolved from a `-SNAPSHOT`
  coordinate (ADR-0017 rules 1 and 8).

## Where to look next

| Question | Document |
| --- | --- |
| Why is it versioned this way? | [ADR-0017](adr/0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md) — the nine rules and the alternatives rejected |
| What changed between two versions? | [`CHANGELOG.md`](../CHANGELOG.md) |
| What exactly is the public API? | [`api-surface.md`](api-surface.md), and `<module>/api/<module>.api` |
| Which modules exist and how do they depend on each other? | [`modules.md`](modules.md) |
| What does a telemetry field mean? | [`telemetry-schema.md`](telemetry-schema.md) |
| How is a release cut? | [`releasing.md`](releasing.md) — for a maintainer, not an adopter |
