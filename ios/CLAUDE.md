# superPlayer for iOS and tvOS

The iOS and tvOS half of SuperPlayer: one Swift package, Swift 6 with strict concurrency, iOS 17 and
tvOS 17 as the floor. `docs/ios-plan.md` is the roadmap and §2's table is the decisions taken up
front; the ADRs bind both platforms, and the root `CLAUDE.md` is still the whole of the repository's
orientation — this file adds only what is different under `ios/`.

## Orientation

| Path | What it is |
| --- | --- |
| `Package.swift` (repository root) | the manifest — at the root rather than here because SwiftPM resolves a `.package(url:)` dependency by reading the manifest at the root (`docs/ios-plan.md` §2); every target names its path under `ios/` |
| `ios/Sources/<Product>/` | one library product per Android module that ports, named `SuperPlayer<Thing>` |
| `ios/Tests/<Product>Tests/` | its XCTest target, run on an iOS simulator |
| `ios/api/<Product>.baseline` | the tracked public API, the `<module>/api/<module>.api` analog (`docs/api-surface.md`, *The iOS products*) |
| `ios/scripts/api-surface.swift` | reduces the digester's JSON to that baseline |
| `ios/check` | the whole definition of passing |
| `ios/.swift-format` | the format's configuration: four-space indent and 100 columns, as the Kotlin half |

**Every product is empty until its phase.** i0 (#397) was the scaffold alone: twelve products, a
placeholder test each, baselines recording no API. `SuperPlayerTestMedia` (#398) and
`SuperPlayerTestkit` (#399, *The harness* below) are the first to hold code, and `SuperPlayerCore`
(#400) the first library code: `SuperPlayer(configuration:)` owning an `AVPlayer` as
`player.avPlayer`, `MediaRequest`, `setMediaRequest(_:)` and `setMediaURL(_:)`, and remembered
positions, with every notification the facade observes enumerated in `ObservedSignals.swift`
(ADR-0020 rule 13). #401 added `PlaybackProfile`, fixed by `Configuration.profile`, whose numbers
live in the internal `StaticProfilePolicy` and reach the engine only through `EngineBinding.swift`
— every departure from Apple's default argued at its row, read off a stock item by
`ProfileTableTests` — and `PlaybackSnapshot`, a `Codable` value whose JSON is a stored format
pinned by `PlaybackSnapshotTests`. The telemetry vocabulary (#408) is core's too — `TelemetryEvent`
held to `docs/telemetry-schema.md` by `TelemetrySchemaDocumentTests`, `TelemetrySink`,
`TelemetryCollector` made by `Configuration.telemetry`'s factory, the session edges in
`SessionEdges.swift` — and the bounded `TelemetryDelivery` queue is `SuperPlayerTelemetry`'s, as on
Android. #415 put the policy boundary in front of the profile: `PlaybackPolicy`,
`PlaybackConditions` (the six observations) and `PlaybackDecision` with all seven halves, the parts
no iOS player reads — the selection pace, the manifest and segment budgets, tunneling — present and
documented as ignored; `Configuration.policy`, nil for the profile's `StaticProfilePolicy`
(`PlaybackProfile.policy`); `EngineBinding.swift` laying a decision on the item and player, and
`ConditionsBinding.swift` the one place a platform reading becomes an observation, its readers
`package` so `ConditionsBindingTests` states a path or a memory reading by hand. Any policy but the
static one gets a `DecisionReapplication` — re-consulted on the five triggers, each change laid on
the playing item in place, reported as `player.playbackDecision` and to the collector's
`decisionChanged(_:trigger:)` — because every knob is live-settable; its registrations are two scopes
of the observed register, and a static player has none (`PolicyPaysNothingTests`). #402 put the
platform's lifecycle on every player, in `LifecycleBinding.swift`: the audio session active while
any player wants to play, an interruption pausing and resuming only on `.shouldResume`, headphones
unplugged pausing, and the idle timer held while video plays — process-wide state counted across
players in `SharedLifecycle`, the platform calls behind the one `package` protocol
`LifecyclePlatform`, and its signals in the observed register.
#409 added
`QoeCollector(sink:)` and `OSLogSink` there, the collector deriving the vocabulary from key-value
observation, notifications, the access and error logs and, on 18, `AVMetrics` — every registration
in its own register, `CollectorSignals.swift`, held as core's is — and
`player.declarePlaybackIntent()` on core; each event's derivation is the *How derived on iOS*
column of `docs/telemetry-schema.md`.
#446 put CMCD on every player from iOS 18, in `CmcdBinding.swift`: AVFoundation sends the keys once
the switch is set on the asset, so core builds a fresh `AVURLAsset` per adoption and **reads** the
telemetry `sessionId` off its `httpSessionIdentifier` — the `sid` the SDK sends — where Android
mints it. `Configuration.setCmcdMode(_:)` refuses `.queryParameter` typed, and `StaticCmcdPolicy`
is the per-profile table.
#404 added
`PlayerPool(configuration:)` — `acquire()` nil past the bound, `recycle(_:)` through the facade's
internal `resetForReuse`, which ends the session edge `SessionEdges.swift` names — bounded by
`DeviceCapacity.swift`, the one place the device is read: no decoder limit is reported on Apple's
platforms, so the bound is a per-player budget against `os_proc_available_memory()` under an argued
ceiling of four, never below one, both numbers i10's (#439) to measure.
#403 added
`PlaybackSession(player:resolver:)` — a choice and not a default, publishing one player to the
process's Now Playing dictionary and remote command centre behind the one `package` protocol
`NowPlayingPlatform`, the most recent session holding them; `release()` ends the session and then
the player, `setPlayer(_:)` swaps the player behind it — and `MediaRequestResolver`, through which
`setMediaRequest(forContentId:)` adopts content named from outside the app. What it observes is its
own register, `SessionSignals.swift`. There is no service, and CarPlay's entitlement and templates
are the app's.
#411 added `SessionTraceRecorder` and `SessionTrace` to `SuperPlayerTelemetry`: the Kotlin grammar,
`formatVersion` and seven redaction rules, a sink that also observes the player through a register
of its own, `RecorderSignals.swift`, its `load` and `bandwidth` kinds from iOS 18's request
events. Its goldens are **structural** — `SessionTrace.structural()` masks every reading of time,
drops the periodic samples and keeps order within each kind — under `ios/Tests/Fixtures/golden/`,
and `ios/check --update-golden` is their only writer (`docs/testing.md`, *Golden traces on iOS*).
#405 built ADR-0022's frame output: `Configuration.frameOutput` takes a `FrameOutput` — a
`CVPixelBuffer` and its `CMTime` in, the buffer to show out, called on a queue of the player's —
and `FramePath.swift` is the whole display path, made only on a player configured with one: an
`AVPlayerItemVideoOutput` per item, a `CADisplayLink`, and an `AVSampleBufferDisplayLayer` whose
control timebase is the item's own. `PlayerLayerView` (UIKit) and `PlayerView` (SwiftUI) host that
layer, or an `AVPlayerLayer` they make for a player without one, and make a Picture in Picture
content source from either. A player configured for protection has the output off for its lifetime
and says so in `frameOutputOffReason`; protection is a `package` stub, `Configuration.protection`,
until #422's `drm` replaces it.
#431 is the realtime tracer. The seam is core's (`FrameSource.swift`: `FrameSource`, `FrameSink`,
`RealtimeTrack`, `EncodedFrame`, `FrameSourceFactory` and the obligations, plus three typed errors),
and `SuperPlayerRealtime` holds `RealtimePlayer` — the second public player type, because an
`AVPlayer` cannot take pushed frames (ADR-0020 rule 11) — over `RealtimeEngine.swift`: an
`AVSampleBufferRenderSynchronizer` with the sample-buffer display layer and an audio renderer, every
frame wrapped in a `CMSampleBuffer` and decoded by them, the first frame's timestamp subtracted, the
clock never sought. `Realtime.transport(scheme:)` makes its `RealtimeSources`; a request is dispatched
on its first source's scheme, and `.at`/`.resumeFromLastKnown` are refused typed before any
bookkeeping. `RealtimeFormats.swift` is the codec table, both spellings, answering a
`CMFormatDescription` with a record passed through as its atom. What the two player types share is
core's `package` protocol `PlaybackEngine` — the view hosts either, and `SharedLifecycle` counts both
— and what they do not yet (telemetry, sessions, interruptions) is listed in `docs/testing.md`'s *A
realtime transport on iOS*. #340's observed bytes are `ios/Tests/Fixtures/realtime/observed-bytes.tsv`,
read by both platforms' tests.
#414 is the benchmark: `SessionMetrics` and `QoeScore` in `SuperPlayerTelemetry`, the matrix in
`ios/Benchmark/` as a target of the manifest and not a product (unscheduled in `docs/modules.md`),
run by `ios/Benchmark/bench` and never by `ios/check`, which runs only its agreement and honesty
tests. `ios/Benchmark/README.md` is its manual, and its *What the simulator taught the harness* is
worth reading before playing a ladder anywhere: an `AVPlayer` whose video nobody consumes never
climbs one.
`SuperPlayerMoQ` is not declared yet — its binary target is
i9's — and there is no `SuperPlayerCache`, since its content is the offline store's.

**Dependencies point inward** under `docs/modules.md`'s phase rule, and the phase a product takes is
the Android module's it ports: `docs/modules.md`'s *iOS products* table is the source, and
`./gradlew verifyModulePhaseRule` reads `Package.swift` as text against it, on Linux. So a target
keeps the documented `.target(name:dependencies:path:)` shape and is never assembled from a
variable, and a new product arrives with its row in that table in the same change. Kotlin friend
modules have no analog: a seam a later product reaches is `package`-scoped, not `internal`.

## The harness

Every iOS test plays from **`SuperPlayerTestkit`'s loopback origin** (ADR-0021 rule 2), because
`AVPlayer` takes no fake transport and never loads an HLS playlist from a `file:` URL:

```swift
let harness = PlaybackHarness()            // one per test; harness.stop() in tearDown
let origin = try await harness.serve(.hls())   // or .liveHls(), which keeps publishing
let player = AVPlayer(url: origin.sourceURL)
try await harness.until("the position advances", timeout: .seconds(60)) { … }
origin.networkRequests()                   // path, kind (manifest / mediaSegment), headers, connection, host
origin.responses()                         // how each was answered: status, faults, bytes sent
```

- **An origin is `NWListener` on `127.0.0.1` at an ephemeral port**, one per `serve`, so two tests
  never share a port or a count. It speaks `GET` and `HEAD` over persistent HTTP/1.1 connections,
  single byte ranges, and RFC 8216's content types; `HttpExchange.swift` is the protocol as pure
  functions and `LoopbackOrigin`'s doc comment argues Network.framework over BSD sockets.
- **Time is real.** Wait with `harness.until`, whose bound is a ceiling and never an assertion; the
  synchronous form reads an `AVPlayer` on the caller's actor, the `@Sendable` async one can fetch.
  Never sleep and then assert.
- **`.liveHls()` publishes a segment every two seconds of real time** from the moment the origin
  starts, in a six-segment window under `live/` — the Kotlin `TestContent.liveHls()`'s stream and
  timing, on the host's clock.
- **No App Transport Security key is set in this repository.** Plain HTTP to the origin is allowed
  by Xcode's `xctest` host, whose embedded `Info.plist` sets `NSAllowsArbitraryLoads`, and
  `LoopbackOriginTests` pins that; a test hosted in an app would need `NSAllowsLocalNetworking`
  there (`docs/testing.md`, *The iOS harness*).
- **Faults and shaping are applied at the server** (#410): `harness.serve(content, faults:
  network:)` takes a `FaultScript` — addressed by kind, index, attempt and host, as the Kotlin
  builder is — and a `ThroughputTrace` or `NetworkProfile.….trace` paced at the socket.
  `origin.responses()` is how each request was answered — status, faults, bytes sent, whether from
  the script's cache — and is what a test shows a fault happened with. What a server cannot produce
  (a DNS or TLS failure, the client's own timeout) is not in the script, and a trace's transport is
  reported on each response rather than switched.
- **Two hosts are one origin with two listeners**: `.hls(hosts: 2)` or `.liveHls(hosts: 2)`, on one
  publication clock, the multivariant playlist listing each variant again on the second host as a
  redundant stream; `origin.url(path:host:)` addresses one, and a fault's `host:` narrows to it.
- **The HLS hostile corpus** (#412) is `.hostile(entry)` with
  `FaultScript.Builder().addDeclaredResponseHeaders(of: entry)`; its fixtures are under
  `ios/Tests/Fixtures/hostile/`, and `HostileManifestCorpusTests` records what a stock `AVPlayer`
  does with each entry (`docs/testing.md`, *The hostile corpus on iOS*).
- **`.videoHls()` is the one stream with pictures** (#405): `SyntheticVideoHlsStream`'s hand-written
  H.264 in fragmented MP4, thirty frames a second, with silent AAC beside it so a player with no
  video output still has a clock (a video-only item never advances in `xctest`). It has no Kotlin
  twin, and a test playing it on a `SuperPlayer` passes a lifecycle platform that does nothing,
  because a video item holds the idle timer and `xctest` has no `UIApplication`.
- **One place a test measures time**: `NetworkShapingTests` holds a shaped transfer to the trace's
  rate as a bound — never faster, exactly, and within a stated slack slower — because #410's
  acceptance asks for a rate; ADR-0021 rule 4's #410 addendum records it. The hostile corpus's
  record reads time only to classify an outcome (its #412 addendum); nothing else does.

## Commands

```bash
ios/check                     # swift-format lint, simulator tests, tvOS build, API diff
ios/check --update-api        # the same, rewriting ios/api/*.baseline; commit the diff
ios/check --update-golden     # the same, rewriting ios/Tests/Fixtures/golden/*.trace; review and commit the diff
SP_SIMULATOR=mine ios/check   # test on a simulator of that name instead (created if absent)
ios/Benchmark/bench           # PRD.md §6's matrix on the simulator, ~2 h; NOT in ios/check (ios/Benchmark/README.md)
xcrun swift-format format --in-place --recursive --configuration ios/.swift-format ios Package.swift
```

`ios/check` is a script rather than a Gradle task (ADR-0021), and the macOS CI job runs it and
nothing else, path-filtered to `ios/**`, `Package.swift` and the shared documents. The Linux job's
`./gradlew check` covers the two iOS readings that are text, because a check that can be a Gradle
task belongs in `check`: the phase rule above, and the licence header below.

Things worth knowing before the first run:

- **The simulator is the script's, by name** — `superplayer-check` unless `SP_SIMULATOR` says
  otherwise — created on first use on the newest iOS runtime, as the first iPhone that runtime
  supports, and shut down afterwards if the run booted it. Two contributors, or two worktrees, give
  it different names and never share a booted device. Delete one with `xcrun simctl delete <name>`.
- **DerivedData lives in `.build/DerivedData`**, not Xcode's shared folder, so two checkouts never
  build into one another; `SP_DERIVED_DATA` moves it. A clean run is `rm -rf .build`.
- **A quiet `xcodebuild` exit is not evidence.** The script reads the result bundle with
  `xcrun xcresulttool get test-results summary` and fails unless it says `Passed` with at least one
  test per product; `.build/ios-check/test.xcresult` is the bundle to open when it does not.
- **tvOS is built, not tested**: a tvOS simulator runtime is not on every host, and no product holds
  tvOS-only code until i7, which is when a tvOS test run earns its place.
- **Every `.swift` file opens with `config/license-header.txt` as `//` lines** (in `Package.swift`,
  after the tools-version line). `./gradlew verifyLicenseHeader` derives the expected text from
  that file rather than keeping a copy, and checks it on Linux, since swift-format has no header
  rule; `ios/check` does not, so run the Gradle task too before pushing a new Swift file.
- **The `[MT] IDESchemeActionDeviceRecord ... invalidDigitCount` lines** an Xcode 27 `xcodebuild`
  prints against an iOS 27 runtime are Xcode's own noise and fail nothing.
