# ADR-0021: Test iOS against a loopback origin on real time, and run its checks in one script

- **Status:** Accepted
- **Date:** 2026-10-10
- **Amended:** 2026-10-11 (#400), by an addendum at rule 1 recording what a test may read through
  `player.avPlayer` while the facade publishes no state of its own. It narrows nothing the rule
  decided and lapses as the facade's state arrives. Amended again (#401) at the same rule, admitting
  a profile's knobs and an item's readiness until the facade publishes its own. Amended again (#402)
  at rule 1, admitting a pause or resume the lifecycle made, and at rule 5, adding the lifecycle to
  the device list with its one protocol.
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** [`docs/testing.md`](../testing.md)'s rule — *no device and no network, through the
  public API, against the engine's own fakes* — for the Swift products only. Its loopback carve-out,
  argued in that document's *The conformance test a consumer runs* for `HttpTransportConformance`
  alone, is **widened to every iOS test** (rule 2), and that document's opening carries a pointer
  here. The Kotlin tests are untouched: the loopback carve-out stays one suite on Android.
  `CLAUDE.md`'s "a new check that can be a Gradle task belongs in `check`" gains its one departure
  (rule 7); `CLAUDE.md`'s *Binding rules* names it, and its *Commands* section is #397's to update
  in the change that adds the script. It relies on
  [ADR-0020](0020-compose-on-avfoundations-avplayer-and-bring-no-other-engine.md), whose rule 6 and
  *Context* are why there is no fake transport to test against.
- **Summary:** iOS tests drive the public API with XCTest on the simulator. The harness is an
  HTTP/1.1 origin on `127.0.0.1`, started per test, serving the synthetic streams, with faults and
  throughput shaping applied at the socket — the only harness `AVPlayer` admits, since it takes no
  fake transport. Time is real, so no test asserts a millisecond and golden traces are structural.
  What needs a device is listed and not faked; `check` covers the logic around it at one
  package-scoped protocol per feature. The shared documents are tested on both platforms. Gradle's
  `check` runs every check that only reads files; the Swift build, tests, format and API digester
  run in one `ios/check` script that the macOS CI job and a contributor both run, which departs from
  the one-`check` rule only in that the Swift products cannot be built where Gradle's `check` runs.

## Context

[`docs/testing.md`](../testing.md) holds every Kotlin test to one seam: the public API, under
Robolectric, on the JVM, against Media3's own fakes, with no device and no network. That rule is
achievable on Android because Media3 ships the fakes — `FakeDataSet`, `FakeExoMediaDrm`, fake
renderers — and because the transfer chain has a slot beneath it into which a fake transport can be
put, so a test of real HLS sees every layer a consumer's player has. Its one socket is
`HttpTransportConformance`'s loopback origin, admitted because an HTTP client's obligations are
claims about a wire.

None of that exists on Apple platforms. AVFoundation ships no fakes. `AVPlayer` takes no transport:
its HLS loading uses the system's own stack, AVFoundation's media loads are not routed through an
app's `URLProtocol` registrations (an observation rather than documented behaviour, which #399
confirms before the harness relies on it), and `AVAssetResourceLoaderDelegate` is consulted only for a scheme
the engine does not recognise, which takes the system's HLS path off the route under test
(ADR-0020 rule 6). There is no simulated clock: `AVPlayer` runs on the host's real time, and nothing
in the public SDK advances it by hand. And the simulator lacks the hardware some features are made
of: it has no FairPlay, it cannot show a tvOS display mode switch, and it cannot kill a process the
way the background download daemon expects.

There is a second, plainer constraint. `./gradlew check` runs on Linux in CI and is the repository's
whole definition of passing (`CLAUDE.md`, *Commands*). The Swift products import AVFoundation,
CoreMedia and UIKit, which exist only in Apple's SDKs under Xcode on macOS, so no Linux runner can
build them, and a Gradle task that shelled out to Xcode would mean one thing on a Mac and nothing on
the runner that decides whether a change merges.

## Decision

**iOS tests drive SuperPlayer's public API with XCTest on the simulator, against a loopback HTTP
origin that SuperPlayerTestkit starts per test. They assert counts, order and typed outcomes on real
time, never a millisecond. What needs a device is listed and not faked. Gradle's `check` keeps every
check that only reads files, and the Swift build, tests, format and digester run in `ios/check`.**

Eight rules follow, and they are binding. No Swift test exists when this record is accepted; each
rule names the ticket that brings its enforcement, and *What nothing enforces* sums the rest.

1. **Tests drive the public API, with XCTest, on the simulator, and nothing asserts past the
   facade.** `docs/testing.md`'s rule survives in the half that does not depend on Media3: a test
   reaches SuperPlayer as a consumer does, and `player.avPlayer` is an escape hatch for consumers
   rather than a way in for tests, as `player.exoPlayer` is. A test that must observe the engine
   observes what a consumer could — the player's published state, the telemetry it emits, the
   requests the origin received. XCTest rather than a newer framework, because it is what
   `xcodebuild test` and every CI image run without a second toolchain decision, and because
   `FrameSourceConformance`'s Swift port must name no framework anyway (#433). *Enforced by review;
   no tool can tell an assertion past the facade from one through it, on either platform.*

   *Addendum (2026-10-11, #400).* The first facade publishes no state: `SuperPlayer` owns an
   `AVPlayer` and is not one (ADR-0020 rule 2), so it has no `play()`, no position and no playback
   status of its own, and a consumer's transport controls and position readout — their own buttons,
   or `AVPlayerViewController`'s — are `player.avPlayer`'s. Until the facade publishes that state, a
   test may do through `player.avPlayer` exactly what a consumer's controls do and nothing more:
   call `play()` and `pause()`, and read `currentTime()`, `timeControlStatus` and an item's
   end-of-playback notification. What SuperPlayer *adds* is still asserted through SuperPlayer —
   identity through `currentContentId`, start and resume positions as where the engine was first
   seen playing — and no test reads AVFoundation state the facade wrote in order to check that the
   facade wrote it. A package-internal seam a test reaches with `@testable`, such as the observed
   register's count (ADR-0020 rule 13), is past the facade too and is admitted where the test's own
   comment argues why no public reading exists; `docs/testing.md`'s *The iOS harness* lists each.
   This lapses, reading by reading, as the facade publishes a position or a state (#401, and the
   telemetry vocabulary of #408), from which point a test reads that instead.

   *Addendum (2026-10-11, #401).* Two readings are admitted beside those, each until the facade
   publishes its own. **A profile's knobs**: the numbers `StaticProfilePolicy` puts on the engine
   are internal by design (a consumer names the profile, not the table) and the facade publishes no
   decision until the policy boundary's `playbackDecision` (`docs/ios-plan.md` §3.3), while the
   synthetic streams carry one audio rendition, so no variant the origin serves can show a ceiling.
   `ProfileTableTests` therefore reads the knobs off the item and the player through
   `player.avPlayer` — the one place a test reads AVFoundation state the facade wrote to check that
   it wrote it, which the issue that built it asked for by name — and holds the reading against a
   stock item's, so a departure is a departure from what the engine reports and not from a comment.
   **An item's readiness**: `currentItem.status`, which a consumer's own controls read to enable a
   play button, so a test can say a restored player is ready and still paused rather than waiting
   on a time. Both lapse as the facade publishes a decision and a state.

   *Addendum (2026-10-11, #402).* One reading more, of the same two values the #400 addendum
   admits: **a pause or a resume the lifecycle made** is read off `timeControlStatus` and
   `currentTime()`, though the facade made it rather than a consumer's control. The behaviour
   under test *is* the player pausing and resuming — `LifecycleBinding` publishes no state of its
   own, and a consumer sees an interruption exactly there, in the player its controls read — so
   there is no reading through SuperPlayer to prefer. It lapses with the others as the facade
   publishes a playback state.

2. **The harness is a loopback HTTP origin, started per test, and the carve-out is widened to every
   iOS test.** `SuperPlayerTestkit` starts an HTTP/1.1 server on `127.0.0.1` at an ephemeral port
   for each test, serving the synthetic streams `SuperPlayerTestMedia` generates, and shuts it down
   after. Nothing is reached off the host and no public network is touched; the rule's "no
   network" half survives as "no network but the loopback interface". **There is no narrower
   option**, which is the whole argument for widening a carve-out the Android side keeps to one
   suite: `AVPlayer` takes no fake transport (*Context*), so the alternatives are a server or a
   different engine path under test, and a test of a path no consumer's player runs proves nothing
   about theirs. A second listener on another port is what stands in for a second host. *Enforced by
   the origin binding the loopback address alone, which #399 builds and tests; a test that fetched
   from the public network would also fail on CI's network policy, which is a backstop and not the
   rule.*

3. **Faults and network shaping are applied at the server, in the vocabulary the Android harness
   uses.** `FaultScript` carries every fault kind a server can produce — a status, a latency, a
   truncation, a token's expiry, a response header set, a host that refuses — and `ThroughputTrace`
   paces each response at the socket, in [`docs/throughput-traces.md`](../throughput-traces.md)'s
   format, unchanged. What a server cannot produce is said rather than approximated: a fault inside
   the client (a DNS failure, a TLS handshake refusal on a plaintext origin) is out of reach, and
   the shaping is per connection, so concurrent transfers each see the whole link — the same limit
   the Android replay states. *Enforced by #410's tests, which force each fault kind and read it
   back at the origin.*

4. **Time is real: no test asserts a duration, and golden traces are structural.** Every assertion
   is on a count, an order or a typed outcome. A golden trace compares **kinds and order with every
   time masked**, in the same grammar and at the same `SessionTrace.FORMAT_VERSION`, since masking a
   field is a reading of the grammar and not a change to it. A structural diff shows a kind that
   appeared, vanished or moved; it cannot show a startup that got slower, and `docs/testing.md`'s iOS
   section says so in as many words. The QoE regression gate (`superplayer-abr`'s, in Android's
   `check`) has no iOS counterpart in a check, because a floor on a real-time harness is a flake by
   construction; the benchmark's report per profile is committed instead, as Phase 3's was.
   *Enforced by #411's structural comparison; a timing assertion is visible in review and, on a
   loaded CI host, by flaking.*

   *Addendum (2026-10-11, #410).* One test asserts a duration, and it is the one whose subject is a
   duration: #410's acceptance asks that a shaped transfer's measured rate be within a stated
   tolerance of its `ThroughputTrace`, and no count or order can say that. `NetworkShapingTests`
   holds it as a bound with two sides argued differently. **Never faster than the trace** is exact,
   not a tolerance: the origin sends each chunk no earlier than the trace says its last byte is due,
   scheduling from the request's arrival rounded *up* to the millisecond, and the client's stopwatch
   starts before the request reaches the origin — so a loaded host can only lengthen the transfer.
   **Not more than a second slower**, on transfers sized to take two and a half, is the tolerance:
   a late wake is not carried into the next chunk's schedule, so what a loaded host adds is one
   wake's lateness and a loopback connection's setup, not one per chunk. Nothing else under
   `ios/check` asserts a duration, an unshaped transfer's included, and a new timing assertion needs
   an addendum here of its own.

5. **What needs a device is listed and not faked, and `check` covers the logic around it at one
   package-scoped protocol per feature.** The list: FairPlay key exchange and playback; background
   download resumption across process death; tvOS display criteria switching the display mode; HDCP
   obscuring on an external display; and MoQ over QUIC. Each is a device run (#435 to #438) under
   `devicelab` with `--platform ios|tvos`. Beneath each, the platform call is stubbed at **one**
   `package`-scoped protocol — the content-key session, the download session, the display manager,
   the relay — so that the request composition, the retry, the store and the classification are
   tested in `check` while the platform's half is not pretended. One protocol per feature, and not
   one per call, so that the stub is a seam a reviewer can read whole rather than a mock of
   AVFoundation. *Enforced by review and by each device ticket existing; nothing detects a fake that
   pretends to be FairPlay.*

   *Addendum (2026-10-11, #402).* The lifecycle joins the list: that the system honours an audio
   session activation and interrupts it for a real call, that pulling out headphones posts the
   route change, and that a video item holds the idle timer once it plays all need a device. Its
   one protocol is `LifecyclePlatform` — activate and deactivate the session, hold and release the
   idle timer — and the notifications are not stubbed at all: a test posts the real ones from
   `AVAudioSession.sharedInstance()`. Its device run is the i1 demo's on an iPhone (`docs/ios-plan.md`
   §7), which plays video and is where a call and a headphone unplug are made by hand.

6. **The shared documents are tested on both platforms.** ADR-0020 rule 4 makes the documents the
   authority, and each platform holds itself to them with a test of its own: the telemetry schema
   (every event and field the document names, and none it does not), the trace grammar, the corpus
   register, the doctor's document, and the compatibility table. The synthetic streams and the HLS
   half of the corpus are **one corpus** by construction: the Kotlin generators' output is
   committed as fixtures, Gradle's `check` asserts the Kotlin generators still produce those bytes,
   and the Swift tests assert the Swift generators produce the same bytes. Both platforms are held
   to one set of files, so neither needs the other's toolchain. *Enforced by #398 and #412 for the
   fixtures and #430 for the documents.*

7. **Gradle's `check` runs every check that only reads files; the Swift build, tests, format and
   digester run in `ios/check`, which the macOS CI job and a contributor both run.** Into `check`
   go the checks that need no Swift toolchain: `verifyModulePhaseRule` reading `Package.swift`,
   `verifyCompatibilityDocument` reading its platform column, the license header on every `.swift`
   file, the Kotlin half of rule 6's fixture equality. Into `ios/check` go `swift build`, the tests
   under `xcodebuild test` on an iOS and a tvOS simulator destination, `swift-format lint`, and the
   API digester against `ios/api/`. This **departs** from `CLAUDE.md`'s "a new check that can be a
   Gradle task belongs in `check`", and only in one respect: the Swift products cannot be built
   where `check` runs. Everything that *can* run there does. Gradle's `check` deliberately does not
   call `ios/check` on a host that could run it, because then "passing" would mean one thing on a Mac
   and another on the runner that decides a merge — the disagreement the one-`check` rule exists to
   prevent. One script rather than a set of CI steps, so that a contributor runs exactly what the CI
   job runs, which is the other half of the same rule. *Enforced by the macOS CI job running
   `ios/check` (#397), path-filtered to `ios/**`, `Package.swift` and the shared documents.*

8. **"Pays nothing" is counted on iOS as on Android.** ADR-0020 rule 13's counts are tests in this
   seam, taken twice — with the feature unconfigured and with a hand-written stand-in — so the
   counter is shown to see what it counts. *Enforced by each feature's ticket.*

### What nothing enforces

Rules 1 and 5 are enforced by review alone, and rule 5 never can be by anything else: a fake that
pretends to be the platform is indistinguishable, to a tool, from one that stubs it honestly. Rule
4's ban on timing assertions is enforced by review and by flakes. Rules 2, 3, 6, 7 and 8 are carried
by tests and the CI job once their tickets land.

## Consequences

**Easier.** The harness is the most realistic one in the repository: a real engine, a real HTTP
exchange, real time. A test that passes here passed through the system's own HLS stack. And a
contributor on a Mac has one command for the iOS half of passing.

**Harder, and honestly.** Real time costs determinism. The Android harness makes a session trace
byte-identical run after run; the iOS harness cannot, so a structural golden misses a regression
that only moved a number, and that class of regression is found by the benchmark or not at all. A
real engine on real time is also slower, and iOS tests will take minutes where the Kotlin tests take
seconds. And the device list in rule 5 is the most valuable part of the iOS product — protected
playback and offline — tested least often.

**The departure in rule 7 has a cost the release command inherits.** The release command (#329,
ADR-0017 rule 8's enforcement) depends on `check` and on every module's `check`; it does not run `ios/check`, and on a Linux host it
could not. Before a Swift product is in a release, `release` must run `ios/check` and refuse on a host
that cannot; until it does, this is a gap named here rather than a rule met. The macOS CI job is
also ten times the price of a Linux minute on GitHub's hosted runners, which is why its path filter
exists.

## Alternatives considered

**`URLProtocol` as a fake transport.** It is the ordinary way to fake HTTP on Apple platforms.
Rejected because AVFoundation's media loads do not go through an app's registered `URLProtocol`s, so
it would fake nothing the player fetches.

**`AVAssetResourceLoaderDelegate` serving the synthetic streams in-process.** No socket, and a
deterministic fetch. Rejected under ADR-0020 rule 6: it takes the system's HLS loading off the path,
so every test would exercise a path no consumer's player runs.

**Keep the carve-out to one suite, and test everything else without the network.** The rule as
written. Rejected because there is nothing else: without a fake transport, a test with no origin
plays nothing.

**A Gradle task that runs `ios/check` when the host is a Mac.** One command for everything.
Rejected under rule 7: `check` would then pass on Linux for a change that fails on a Mac, which is
the disagreement the one-`check` rule is there to prevent, moved rather than removed.

**Byte-identical golden traces with a scheduler of our own.** Rejected: there is no public way to
drive `AVPlayer`'s clock, and a trace whose times came from a fake clock would describe a session
the engine did not run.

**Swift Testing instead of XCTest.** Rejected under rule 1 for now, as a second toolchain decision
with no test it enables; a later ticket may revisit it without a new record, since nothing in the
public API names the framework.

## References

- Apple Developer Documentation: `URLProtocol`, `AVAssetResourceLoaderDelegate`, `XCTest`,
  `xcodebuild` test destinations, `AVAssetDownloadURLSession` background configuration.
- RFC 9112 — HTTP/1.1 message syntax, which the loopback origin speaks.
- GitHub Docs, *About billing for GitHub Actions* — the per-minute multiplier for macOS runners.
- [`docs/testing.md`](../testing.md), *The conformance test a consumer runs* — the carve-out widened.
- [`docs/throughput-traces.md`](../throughput-traces.md) — the shaping format rule 3 reuses.
- [`docs/ios-plan.md`](../ios-plan.md) §5 and §6.
- [ADR-0017](0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
  rule 8, [ADR-0020](0020-compose-on-avfoundations-avplayer-and-bring-no-other-engine.md).
