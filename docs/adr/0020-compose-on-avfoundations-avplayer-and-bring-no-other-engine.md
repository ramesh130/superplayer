# ADR-0020: Compose on AVFoundation's `AVPlayer`, and bring no other engine

- **Status:** Accepted
- **Date:** 2026-10-10
- **Amended:** 2026-10-11 (#402), by an addendum at rule 13 placing the lifecycle's signals in the
  observed register with a player scope, since ADR-0006 rule 1 puts them on every player.
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** [ADR-0001](0001-compose-dont-fork.md), restated for Apple platforms rather than
  changed: its three rules bind the Android modules exactly as written. Its rule 1 becomes rule 1
  below and its rule 2, with the escape hatch, becomes rules 2 and 3; its rule 3, one pinned Media3
  version, has no Apple counterpart, because the engine ships with the OS and what stands in for a
  pinned version is the deployment floor (rule 9). ADR-0001 carries an addendum at rule 2.
  `CONTRIBUTING.md`'s clean-room rule 3, *API idiom follows Media3*, is scoped to the Android
  modules by rule 5 below, and says so there. Two more earlier rules gain an addendum at the rule,
  because this record changes their scope:
  [ADR-0015](0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md)
  rule 6, whose "the doctor parses with the parsers the engine would run" has no Apple counterpart
  (rule 8 below), and
  [ADR-0017](0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
  rules 1 and 2, whose lockstep and whose arbiter extend to the Swift products (rule 12 below).
  [ADR-0016](0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md)
  and [ADR-0018](0018-push-encoded-frames-through-one-framesource-into-media3s-own-sample-queues.md)
  are relied on and not amended: the first is named as having no Apple counterpart (rule 6), and
  the second's seam ports whole (rule 11).
- **Summary:** The iOS and tvOS port, planned in [`docs/ios-plan.md`](../ios-plan.md), is built on
  AVFoundation's `AVPlayer` and on nothing else. The facade owns an `AVPlayer` and exposes it as
  `player.avPlayer`, the one escape hatch, and it subclasses no AVFoundation class. What is shared
  between the platforms is the documents and the fixtures, never the code, so there is no Kotlin
  Multiplatform. Everything keyed to Media3's transfer chain does not port, and that list is a set
  of rules rather than a backlog: the transfer chain, the consumer's HTTP stack, the streaming
  cache, rungs 1 to 3, per-transfer bandwidth sampling, token refresh inside a transfer, DASH,
  provisioning, per-rung track selection and its pace, and the TV controls. A player is configured
  by a value rather than built by a builder, Swift 6's strict concurrency is on, the floor is iOS 17
  and tvOS 17 with iOS 18 features gated, and the Swift products ride the Android modules' version
  line under ADR-0017 with the API digester as their arbiter. `RealtimePlayer` is the one place the
  "one player" claim splits.

## Context

SuperPlayer is a policy, resilience, observability and lifecycle layer composed on Media3's
extension points: a `DataSource` chain, a `LoadControl`, a `TrackSelector`, a `DrmSessionManager`, a
`LoadErrorHandlingPolicy`, a `MediaSource.Factory`. Phases 0 to 10 and 13 to 14 of
[`PRD.md`](../../PRD.md) built on those seams, and every record from ADR-0001 onward assumes them.

Apple platforms have one media engine an app can rely on, AVFoundation, and it exposes almost none
of those seams. What it exposes is a closed HLS engine with a short list of live-settable knobs on
`AVPlayerItem` (`preferredForwardBufferDuration`, `preferredPeakBitRate`,
`preferredMaximumResolution` and their expensive-network pair, `configuredTimeOffsetFromLive`), a
first-class content-key API (`AVContentKeySession`), a first-class download API
(`AVAssetDownloadURLSession`), and, since iOS 18, a first-class metrics stream (`AVMetrics`). There
is no hook beneath the engine through which a request passes: `AVAssetResourceLoaderDelegate` is
consulted for custom URL schemes and for key requests, and routing segments through it means
serving them from a scheme AVFoundation does not recognise, which takes the system's own HLS
loading off the path.

Three things were live:

1. **Compose on `AVPlayer`**, keeping SuperPlayer's vocabulary wherever the vocabulary names no
   engine and accepting that the layer beneath the engine is out of reach.
2. **Bring an engine that has a transfer chain**: an open-source player with `DataSource`-like
   seams, or a port of Media3's core to Apple platforms.
3. **Kotlin Multiplatform for the pure modules**: the policy interfaces, the buffer policy's five
   branches, the failure taxonomy, `SessionMetrics` and `QoeScore` name no Media3 type and would
   compile under KMP.

Two facts constrain the choice before any preference does. FairPlay Streaming is the only DRM with a
hardware path on Apple platforms, and it is reachable only through AVFoundation. And AirPlay,
Picture in Picture, background audio through the system's own path, and `AVPlayerViewController`
are all written against `AVPlayer` — the same position Media3's `Player` holds for `PlayerView`,
`MediaSession` and Android Auto, which is what ADR-0001's context called the reason the facade is
adoptable at all.

## Decision

**SuperPlayer on Apple platforms owns an AVFoundation `AVPlayer` and composes on its public API.
It brings no other engine and shares no code with the Android modules; it shares their documents
and their fixtures. Everything the Android library builds beneath the engine — the layer
AVFoundation does not expose — is not ported, by rule.**

Thirteen rules follow, and they are binding. No Swift code exists when this record is accepted, so
almost every rule is enforced by nothing yet; each says what will enforce it and which ticket brings
that, and *What nothing enforces* sums them.

### The engine

1. **`AVPlayer` is the engine, and no other is brought.** No third-party player, no port of
   Media3, and no decoding of streamed content through VideoToolbox on the HLS path. ADR-0001 rule
   1's "no vendored engine source" has nothing to bind here, since AVFoundation's source is not
   available to vendor; this rule is its replacement, and what it forbids is the second engine
   rather than a fork of the first. The realtime path (rule 11) is not a second engine: Apple's
   `AVSampleBufferDisplayLayer` and `AVSampleBufferAudioRenderer` decode the compressed samples
   handed to them, so "nothing here decodes" (ADR-0018 rule 1) holds on both platforms. *Enforced
   by nothing mechanical. A dependency on a player framework would arrive with a `THIRD_PARTY.md`
   row and be visible in review, and `Package.swift` declares no binary target but MoQ's.*

2. **The facade owns an `AVPlayer` and subclasses no AVFoundation class.** `SuperPlayer` holds an
   `AVPlayer` and exposes it as `player.avPlayer`. It does not subclass `AVPlayer`, `AVQueuePlayer`
   or `AVPlayerItem`: Apple's class references describe them for use and not for subclassing, and a subclass that
   overrides what the engine calls internally is ADR-0003's hazard in different clothes — behaviour
   that changes under an OS update with no compile error to say so. Owning rather than being the
   engine costs the "it *is* a `Player`" property the Android facade has, and what keeps the
   "no adapter" point is that `AVPlayerViewController`, `AVPlayerLayer` and SwiftUI's
   `VideoPlayer` all take an `AVPlayer`, so they take `player.avPlayer` unchanged. *Enforced by the
   API digester (rule 12), where a superclass appears as a change to the declaration.*

3. **`player.avPlayer` is the one escape hatch, and ADR-0001 rule 2's exception restated.**
   AVFoundation has no `@UnstableApi`, so the reason ADR-0001 rule 2 keeps engine types off the
   surface does not transfer as written. What transfers is the vocabulary: every type whose meaning
   a shared document fixes — `MediaRequest`, `PlaybackProfile`, `PlaybackSnapshot`,
   `TelemetryEvent`, `FailureClass`, `PlaybackDecision`, `Pathology`, `FrameSource` — names no
   AVFoundation type, so that the same document describes both platforms. A platform type appears
   on the public surface only where the subject *is* a platform object: the player itself, a pixel
   buffer handed to a frame output (ADR-0022), a layer a view hosts. `player.avPlayer` is the named
   member, and anything a consumer does through it — setting a knob the policy also sets, replacing
   the current item — is outside SuperPlayer's compatibility promise, exactly as anything reached
   through `player.exoPlayer` is (ADR-0017 rule 4). *Enforced by review of the digester's baseline
   diff; no check reads a type's module, because no Swift type carries a marker to read.*

### What is shared, and what is not ported

4. **What is shared is the documents and the fixtures, never the code.** There is no Kotlin
   Multiplatform module, and no Swift file is generated from Kotlin. The documents that bind both
   platforms are `docs/telemetry-schema.md`, the session trace grammar and
   `docs/session-bundle.md`, the hostile manifest corpus, `docs/throughput-traces.md`,
   `docs/media-source-doctor.md`, `docs/compatibility.md`, the ADRs and `PRD.md`; each gains a
   platform column or an iOS section where the derivation differs, rather than a second copy. Each
   platform holds itself to those documents with a test of its own (ADR-0021 rule 6). KMP was the
   live alternative and *Alternatives considered* records why it lost. *Enforced by ADR-0021 rule
   6's tests once they exist (#412 for the corpus, #430 for the documents); no rule detects a
   `.kt` file compiled for Apple, since none exists to detect.*

5. **On Apple platforms, API idiom follows AVFoundation's and Swift's, as on Android it follows
   Media3's.** `CONTRIBUTING.md`'s clean-room rule 3 makes Media3 the tie-breaker for a design
   question; on Apple platforms the tie-breaker is AVFoundation's own conventions and the Swift API
   Design Guidelines. That is why a player is **configured by a value, not built by a builder**:
   `SuperPlayer(configuration:)` takes a `Configuration` value type whose every property has a
   default, and each Android builder setter becomes a property. What a builder fixes for a player's
   lifetime — the profile, the protection, the resilience, the frame output — the configuration
   fixes for its lifetime too, because the reasons were never about the builder. And it is why
   **Swift 6 with strict concurrency** is on in every target: the rules this library states about
   threads — a sink is never on a thread the engine needs (ADR-0008 rule 4), a callback lands on the
   main actor — become compile errors rather than comments. The clean-room rules otherwise bind
   unchanged, rule 2 above all: the port mirrors no commercial Apple-platform player SDK's shape.
   *Enforced by the compiler for concurrency (the package's Swift language mode, set once in
   `Package.swift` by #397); the idiom by review.*

6. **What does not port is listed, and each is a rule rather than a gap.** Most of the list is keyed
   to the transfer chain, beneath the engine where AVFoundation offers no seam (*Context*); DASH,
   provisioning and the TV controls are the platform's own answers. None of the following exists on
   Apple platforms, and a change that adds one amends this rule rather than closing a gap:
   - **The transfer chain.** No `TransferChain`, no `LoadKind` stamp, no `ContentIdentity` stamp.
   - **The consumer's HTTP stack.** No `HttpStack`, `HttpTransport` or `HttpTransportConformance`;
     ADR-0016 records a decision with no Apple counterpart, because `AVPlayer` uses its own stack for
     every HLS request.
   - **The streaming cache.** No `ContentKeyedCache`. Pinned content is a downloaded `.movpkg`,
     which is the offline store's (#424), and `ContentCache` becomes that store's directory and
     budget.
   - **Rungs 1 to 3 of ADR-0011's ladder.** Apple retries inside the engine and nothing configures
     it; host failover is the server's, through content steering. `RetryPolicy.manifest` and
     `.segment` are present in the value type and documented as ignored; `.licence` is honoured,
     because the licence exchange is ours to make (#422).
   - **Per-transfer bandwidth sampling.** The oracle's per-transport memory ports; its input is the
     access log's `observedBitrate`, Apple's smoothed figure, and the schema says whose number it is.
   - **Token refresh inside a transfer.** A refused 401 or 403 is repaired by re-adopting at the
     held position with what `HeaderProvider` returns (#420), which costs a manifest fetch.
   - **DASH.** AVFoundation plays HLS. No DASH generator, no DASH half of the corpus or the doctor.
   - **Provisioning.** The device certificate is Apple's.
   - **`NetworkAwareTrackSelection` and `SelectionPace`.** Apple selects, and no per-rung hook
     exists. `TrackSelectionPolicy.pace` is present and documented as ignored.
   - **`TvPlaybackControls` and its `Scrub` curve.** On tvOS `AVPlayerViewController` ships the
     transport controls, scrubbing, the info panel and Siri Remote handling, and the library
     recommends it rather than porting controls. The curve is ported only if an adopter's own
     SwiftUI controls ask for it.

   A field the shared value types carry and iOS ignores is kept rather than removed, because a
   policy written once must compile against both; its documentation says "ignored on iOS" at the
   field. The service halves — `PlaybackService`, `DownloadsService`, the `WorkManager` schedule —
   are not ported for the plainer reason that the platform has no such thing.

   **No rule here may be reopened by intercepting segments through `AVAssetResourceLoaderDelegate`.**
   Serving segments from a custom scheme puts this library between the engine and the network and
   rewrites what the engine asked for, which is ADR-0002's local proxy arriving through a different
   door, and it takes the system's HLS loading — and with it AirPlay's and the download API's view of
   the same URL — off the path. *Enforced by nothing mechanical; a resource-loader delegate on a
   content asset is visible in review, and `docs/ios-plan.md` §3 is the long form of this list.*

7. **CMCD on Apple platforms is decided by #413's finding, and a negative finding closes it.** Whether
   the SDK emits CTA-5004 itself, and whether `AVURLAssetPrimarySessionIdentifierKey` can carry the
   session id the schema's `sid` join promises, is verified against the SDK and written down before a
   line of CMCD is. If neither holds, CMCD is **not ported**, and `docs/telemetry-schema.md` marks the
   join promise Android-only. A custom-scheme resource-loader experiment is not the fallback, for
   rule 6's reason. *Enforced by nothing; #413 is the decision's input and this rule is its outcome
   either way.*

   *Addendum (2026-10-11, #413).* The finding is positive, so CMCD **ports**
   ([`docs/ios-cmcd-finding.md`](../ios-cmcd-finding.md)). The SDK emits CTA-5004 v1 itself as
   request headers once `AVAssetResourceLoader.sendsCommonMediaClientDataAsHTTPHeaders` is set
   (iOS 18, tvOS 18), and its `sid` is the asset's read-only `httpSessionIdentifier`.
   `AVURLAssetPrimarySessionIdentifierKey` reaches no CMCD key, so the join holds by the other
   route: core reads the asset's identifier and uses it as the telemetry `sessionId`, rather than
   minting one and handing it out as Android's core does. Below 18 no CMCD is sent, which is rule
   9's measurement shape rather than a refusal. The implementation is #446, and
   `CmcdFindingTests` pins the three facts it rests on — which pins the SDK's behaviour, not this
   rule, so the rule is still enforced by nothing.

8. **The doctor parses HLS with a lint parser of its own, written to RFC 8216, and says so.**
   ADR-0015 rule 6 has the doctor parse with the parsers the engine would run, so that the doctor and
   the player never disagree about what a manifest says. AVFoundation exposes no playlist parser, so
   that is impossible here, and the alternative to a parser of our own is no doctor at all. The
   departure is bounded three ways: the parser decides nothing about playback (it is a lint, and the
   player never reads its output); a finding on iOS reads a document as RFC 8216 describes it rather
   than as AVFoundation reads it, and `docs/media-source-doctor.md`'s platform column says so; and
   ADR-0015 rule 6's other half — *a threshold copied is a bug* — still binds, which on iOS means the
   live-window and cache-header judgements that were core's on Android live in the doctor alone,
   since iOS core has no revalidation layer to ask. It is also a departure from `PRD.md` §0.1's "not
   a new protocol stack", and the same bound answers it: a lint parser is not a stack. ADR-0015 rule
   6 carries the addendum. The judgement itself now exists in two languages, which is a copy across
   platforms that rule 6's first consequence cannot forbid; what keeps the two in step is that
   `docs/media-source-doctor.md` states each threshold once as the authority and both platforms
   are scored against the one corpus register (ADR-0021 rule 6). *Enforced by the corpus register
   once #427 ports it, where a parser that misreads an entry fails that entry's row.*

### Platforms, versions and the surface

9. **The platforms are iOS and tvOS, both from i0, at a floor of 17.** macOS, visionOS and watchOS
   are out of scope: no phase asks for them, and each adds surface. tvOS is declared from the first
   scaffold rather than at i7 (`docs/ios-plan.md` §8, decision 2), because a product or a
   destination added later is a *minor* on the digester's reading and a CI destination added late
   finds a year of tvOS-incompatible code at once. The floor is iOS 17 and tvOS 17. A feature that
   needs iOS 18 is gated at runtime, and the gate takes one of two shapes: where the feature is the
   subject of a call a consumer makes, the call is **refused typed** below the floor, naming the
   feature and both versions — `HttpStackUnsupportedException`'s shape (ADR-0016 rule 12) — never
   degraded silently; where it is an input to a measurement (`AVMetrics` request events behind the
   trace's `load` and `bandwidth` kinds, stalled-playlist detection), the measurement is coarser
   below 18 and the document that defines it says which derivation applies at which version.
   *Enforced by `Package.swift`'s `platforms` for the floor (#397) and by the digester for a
   deployment-target change; the gate's two shapes by review.*

10. **The code lives in this repository, as one Swift package whose manifest is at the root, and is
    distributed through Swift Package Manager alone.** Sources are under `ios/`, one library product
    per module that ports, and `Package.swift` is the one iOS file outside `ios/`, because SwiftPM
    resolves a remote dependency by git URL and reads the manifest at the repository root. Two
    repositories would drift on the one thing that must not, the shared documents. CocoaPods is not
    offered: it is in maintenance, and SwiftPM is what Xcode and an adopter's own `Package.swift`
    both resolve. The cost is that resolving the package clones the whole repository; a read-only
    mirror cut by `release` is the answer if that is ever a complaint, and not before. Dependencies
    point inward to `SuperPlayerCore` under `docs/modules.md`'s phase rule, and a seam a later
    product reaches is `package`-scoped rather than `internal`, which is what Kotlin's friend
    modules stood in for. *Enforced by `verifyModulePhaseRule` once it reads `Package.swift`
    (#397).*

11. **`RealtimePlayer` is the one place the "one player" claim splits.** On Android a realtime URI
    reaches the same `SuperPlayer` through `TransferChain`'s scheme dispatch (ADR-0018 rule 12). An
    `AVPlayer` cannot take pushed frames, so on Apple platforms realtime is a second public type,
    `RealtimePlayer`, over an `AVSampleBufferRenderSynchronizer` with the sample-buffer video layer
    and audio renderer. It shares the telemetry, lifecycle and session code with `SuperPlayer`
    through one internal engine protocol, and the library's SwiftUI player view renders either. The
    seam ADR-0018 decided ports whole: `FrameSource`, `FrameSink`, `RealtimeTrack`, `EncodedFrame`,
    `CodecConfiguration` and their nine obligations are core's on both platforms, and the codec
    configuration conversion is mirrored, since CoreMedia's native shape is the record where Media3's
    is Annex-B. **A realtime source handed to `SuperPlayer` is refused typed**, naming
    `RealtimePlayer`, rather than reaching `AVPlayer` and failing as an unplayable URL; the split is
    only honest if the wrong half says where the right one is. *Enforced by a test in #431 once the
    type exists; the split itself by the digester, where a second player type is a declaration.*

12. **The Swift products ride the Android modules' version line, and the API digester is their
    arbiter.** One `MAJOR.MINOR.PATCH`, cut by the one `release` command, tagged once with the
    `vX.Y.Z` tag SwiftPM already resolves — ADR-0017 rule 1's lockstep, extended. Under `0.x` a
    change that touches one platform bumps both, and that is accepted: one schema version, one trace
    format and one changelog are worth more than a version number that moved for nothing on one
    side. **What would split the lines** is one platform needing a major the other could not honestly
    call one after `1.0.0`, and the split would be a superseding record, not a drift. ADR-0017 rule
    2's arbiter must exist on both platforms or it binds on neither, so each Swift product's public
    surface is recorded as a baseline under `ios/api/` and `swift package
    diagnose-api-breaking-changes` is the iOS reading of "a declaration removed is a major".
    ADR-0017 rules 1 and 2 carry the addendum. *Enforced by `ios/check` running the digester
    (ADR-0021 rule 7, #397); the release command running it too is a gap ADR-0021's *Consequences*
    names.*

### Pay nothing

13. **A player configured without a feature registers and allocates nothing for it, and a test
    counts it.** Every "pays nothing" rule of ADR-0008 to ADR-0018 holds on Apple platforms in the
    platform's terms: no key-value observation, notification observer, `AVPlayerItemVideoOutput`,
    content-key session or display link for a feature the configuration did not name. The count is
    taken the Android way — once with nothing configured and once with a hand-written stand-in, so
    the counter is shown to see what it counts — over the one file in which the facade enumerates
    every observation it registers, which is also what replaces `SuperPlayerForwardingTest`: there is
    nothing to forward, and what can go silently wrong is an observation registered and never
    removed. *Enforced by each feature's ticket bringing its count, starting with #400's observation
    register.*

    *Addendum (2026-10-11, #402).* The lifecycle is not a feature a configuration names: ADR-0006
    rule 1 makes the audio session, interruptions, the route change and the idle timer correctness
    on every player. So its four signals — the two `AVAudioSession` notifications, the player's
    rate change and its `timeControlStatus` — are in the register with a *player* scope, registered
    as each player is made and removed with it, and "nothing configured" counts exactly those and
    no item's. The rule's promise is unchanged for everything a configuration does name.

### What nothing enforces

At acceptance, every rule. Once the tickets named at each rule land, rules 1, 3, 5's idiom half, 6,
7 and 9's gate shapes remain enforced by nothing in any check, because each is a statement about
absence or about a choice made in review; rules 2, 10, 11, 12 and 13 are carried by the digester,
the phase rule and counting tests; rule 4 by ADR-0021's shared-document tests; rule 8 by the corpus
register.

## Consequences

**Easier.** Everything Apple builds on `AVPlayer` works with no adapter: AirPlay, Picture in
Picture, background audio, `AVPlayerViewController`, CarPlay's Now Playing, and FairPlay's hardware
path. The vocabulary an adopter learns on Android is the vocabulary on iOS wherever it names no
engine, and the documents that define it are one copy. FairPlay is *closer* to ADR-0012's shape than
Widevine through Media3 was, because the licence exchange is ours to make.

**Harder, and honestly.** Rule 6 is a real loss and it is permanent: an iOS adopter gets no streaming
cache, no consumer HTTP stack, no load-error rungs and no per-transfer measurement, and the library's
value on iOS is correspondingly smaller — policy above a closed engine, resilience at rungs 4 to 6,
measurement at the access log's cadence. The benchmark will say how much smaller, and `PRD.md`'s
Phase 11 discipline (a number is a finding, not a gate) applies to it. Each pure-logic piece — the
buffer policy's branches, the classifier's taxonomy, `SessionMetrics` — exists twice, once per
language, and keeping them in step is a cost the shared documents' tests bound but do not
remove.

**The maintenance burden is the second copy of the pure logic, and the document is what keeps it
one product.** A change to a metric's meaning, a failure class, a profile row or a trace kind is now
a change on two platforms, and the review that accepts it on one has to ask about the other. That is
why rule 4 makes the documents, not either implementation, the authority.

## Alternatives considered

**Bring an engine with a transfer chain.** An open-source player with `DataSource`-like seams, or a
port of Media3's core. It would make most of rule 6's list portable. Rejected: it forfeits FairPlay's
hardware path, background audio through the system, AirPlay, Picture in Picture and
`AVPlayerViewController`, and it makes the library the thing `PRD.md` says it is not, a new player.
FairPlay alone decides it, since it is reachable only through AVFoundation.

**Kotlin Multiplatform for the pure modules.** The policy interfaces, the buffer policy, the
taxonomy, `SessionMetrics` and `QoeScore` would compile. Rejected: it puts a KMP toolchain into every
existing module to save perhaps three thousand lines of pure logic, and a Kotlin/Native framework
gives a Swift consumer no value types, no `Codable`, no `async` and no Swift idiom — rule 5's
tie-breaker lost before the first line. The drift KMP would prevent is bounded instead by rule 4's
tests over the shared documents.

**Subclass `AVPlayer`, so that `SuperPlayer` is one.** It would keep the Android facade's strongest
property. Rejected under rule 2: Apple does not document `AVPlayer` as a base class, and what such a
subclass overrides is what the engine itself calls.

**Intercept segments through `AVAssetResourceLoaderDelegate` to recover the chain.** It would make a
cache, a consumer stack and per-transfer measurement possible. Rejected under rule 6 and ADR-0002: it
is a local proxy in-process, it serves the engine bytes from a scheme it does not recognise as HLS,
and every system feature that reads the asset's URL sees a URL that is not the content's.

**Builders, as on Android.** Rejected under rule 5: a builder whose every setter has a default is a
value type with a constructor, and Swift says that directly.

**A second version line for the Swift products.** Honest about independent release cadences.
Rejected under rule 12 for `0.x`: two schema-version stories and two changelogs are the drift rule 4
exists to prevent, and a second line is also the one thing that would argue for a second repository.

**macOS and visionOS from the start.** Each is mostly free at compile time. Rejected under rule 9: no
phase asks for them, and a platform declared is a platform the digester and CI must then hold.

## References

- Apple Developer Documentation: `AVPlayer`, `AVPlayerItem` (`preferredForwardBufferDuration`,
  `preferredPeakBitRate`, `preferredMaximumResolution`, `configuredTimeOffsetFromLive`),
  `AVAssetResourceLoaderDelegate`, `AVContentKeySession`, `AVAssetDownloadURLSession`, `AVMetrics`,
  `AVSampleBufferRenderSynchronizer`, `AVSampleBufferDisplayLayer`, `AVSampleBufferAudioRenderer`,
  `AVPlayerViewController`.
- Apple, *FairPlay Streaming* (developer.apple.com/streaming/fps) — the content-key exchange rule 6's
  `.licence` budget is honoured over.
- *Swift API Design Guidelines* (swift.org/documentation/api-design-guidelines) — rule 5's
  tie-breaker.
- *The Swift Programming Language* (Swift 6 edition), chapter *Concurrency*, and the Swift 6
  language mode's data-race safety checking (swift.org/migration).
- Swift Package Manager documentation, *Adding package dependencies* — a remote dependency is
  resolved by its repository URL and its `Package.swift` read at the repository root (rule 10).
- CocoaPods blog, *CocoaPods Trunk Read-only Plan* (2024) — rule 10's "in maintenance".
- Apple, *HLS Content Steering Specification* — the server-driven host failover rule 6 leaves to the
  server.
- Apple Developer Documentation: `AVURLAssetPrimarySessionIdentifierKey` — rule 7's session id.
- `swift package diagnose-api-breaking-changes` (Swift Package Manager) — rule 12's arbiter.
- RFC 8216 — HTTP Live Streaming, the document rule 8's lint parser is written to.
- CTA-5004 — Common Media Client Data, rule 7's subject.
- [`docs/ios-plan.md`](../ios-plan.md) §1–§4 and §8, the plan this record is cut from.
- [ADR-0001](0001-compose-dont-fork.md), [ADR-0002](0002-no-local-http-proxy.md),
  [ADR-0003](0003-implement-player-by-delegation.md),
  [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md),
  [ADR-0015](0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md)
  rule 6, [ADR-0016](0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md),
  [ADR-0017](0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
  rules 1, 2 and 4, [ADR-0018](0018-push-encoded-frames-through-one-framesource-into-media3s-own-sample-queues.md).
