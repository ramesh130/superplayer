# SuperPlayer on iOS and tvOS

For someone building an iPhone, iPad or Apple TV app on SuperPlayer, who has no copy of this
repository and has not read its Android half.

SuperPlayer on Apple platforms is a playback layer over AVFoundation's own `AVPlayer`: policy,
resilience, observability and lifecycle, with the same vocabulary as the Android library wherever
that vocabulary names no engine. It is **not a new player** and brings no other engine. FairPlay,
AirPlay, Picture in Picture, `AVPlayerViewController` and the system's HLS stack are all Apple's,
and they keep working because the player underneath is the one they were built for
([ADR-0020](adr/0020-compose-on-avfoundations-avplayer-and-bring-no-other-engine.md) rule 1).

> **Before anything else: nothing is released yet.** No version has been cut, so there is no tag to
> resolve. SwiftPM will resolve the repository at a branch or a revision, and what that gets you is
> whatever is on it. [`compatibility.md`](compatibility.md) says what a release will promise once
> there is one, for both platforms.

---

## Installing

Swift Package Manager is the only distribution (ADR-0020 rule 10). The manifest is at the
repository's root, so the package resolves by the repository's URL:

```swift
// Package.swift
dependencies: [
    .package(url: "https://github.com/ramesh130/superplayer.git", branch: "main"),
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "SuperPlayerCore", package: "superplayer"),
            .product(name: "SuperPlayerTelemetry", package: "superplayer"),
        ]
    ),
]
```

In Xcode, *File › Add Package Dependencies…* takes the same URL. Resolving clones the whole
repository, Android half included, because the manifest has to be at the root for a URL to reach it;
only the products you name are built into your app.

**The floor is iOS 17 and tvOS 17** (ADR-0020 rule 9). A feature that needs iOS 18 is gated at
runtime in one of two ways. Where it is something you call, it is **refused typed** below 18 and
never degraded silently. Where it only feeds a measurement, the measurement is coarser below 18, and
the document that defines it says which derivation applies at which version. Three such places are
worth knowing:

- CMCD is sent from iOS 18 only.
- A session trace has `load` and `bandwidth` lines from 18 only.
- A frozen live playlist is detected exactly from 18, and from the error log below it.

Swift 6 with strict concurrency is on in every target, so the rules about which thread a callback
lands on are compile errors rather than comments.

### The products

`SuperPlayerCore` is the only product an app needs. Every other product is additive and depends
inward on core, so a product you do not name costs your app nothing.

| Product | What it adds | Platforms |
| --- | --- | --- |
| `SuperPlayerCore` | `SuperPlayer`, `MediaRequest`, `PlaybackProfile`, `PlaybackSnapshot`, `PlaybackSession`, `PlayerPool`, the policy boundary, the telemetry vocabulary, `PlayerView` and `PlayerLayerView`, and the seams the products below fill | iOS, tvOS |
| `SuperPlayerTelemetry` | `QoeCollector`, `OSLogSink`, `SessionTraceRecorder`, `SessionMetrics`, `QoeScore` | iOS, tvOS |
| `SuperPlayerABR` | `AdaptivePolicy`: the buffer and selection halves recomputed on conditions, over a per-network throughput memory | iOS, tvOS |
| `SuperPlayerPreload` | `PreloadCoordinator` over a `PlayerPool`, for feeds | iOS, tvOS |
| `SuperPlayerResilience` | `Resilience.standard(headers:)`, the failure taxonomy (`FailureClass`, `ErrorClassifier`) and the rungs of the fallback ladder that port | iOS, tvOS |
| `SuperPlayerDRM` | `Drm.fairPlay(...)` and the offline licence store | iOS, tvOS; offline licences iOS only |
| `SuperPlayerOffline` | `Downloads`: HLS downloads into a directory you name | iOS only |
| `SuperPlayerTV` | `TvOutput.standard()`: the display matched to the content | tvOS (does nothing on iOS) |
| `SuperPlayerDiagnostics` | `MediaSourceDoctor`, `SessionBundle`, `DebugHud` | iOS, tvOS |
| `SuperPlayerRealtime` | `RealtimePlayer` and `Realtime.transport`: encoded frames from a transport you supply | iOS, tvOS |
| `SuperPlayerTestkit` | For your tests: `PlaybackHarness` and its loopback origin, `FaultScript`, `ThroughputTrace`, `FrameSourceConformance` | iOS, tvOS |
| `SuperPlayerTestMedia` | For your tests: synthetic HLS streams and the hostile-manifest corpus | iOS, tvOS |

Two of those platform cells are the SDK's rather than the library's:

- **tvOS has no persistable FairPlay keys and no asset download session.** On tvOS the offline
  licence store's verbs and `Downloads` fail typed at once rather than behaving differently.
- **`SuperPlayerMoQ` is not in the table, deliberately.** It plays a Media over QUIC broadcast
  through `RealtimePlayer`, and it is **iOS only and unpublished**. It is a package of its own under
  `ios/MoQ/`, which resolving this repository by URL never reaches, and its native half is an
  XCFramework with an iOS device slice and an arm64 simulator slice and no tvOS slice. The reason is
  the Android module's: the bindings are compiled from a native dependency on one machine, and
  publishing them has licence and binary questions nobody has answered yet
  (`third-party/moq/README.md`, *The Swift bindings*). A checkout can depend on it by path, as this
  repository's demo does, and nothing about it is covered by any promise in
  [`compatibility.md`](compatibility.md).

---

## Playing something

A player is **configured by a value, not built by a builder** (ADR-0020 rule 5). Every property has
a default, and what the configuration fixes, it fixes for the player's lifetime:

```swift
import SuperPlayerCore
import SuperPlayerTelemetry

var configuration = SuperPlayer.Configuration(profile: .videoOnDemand)
configuration.telemetry = { QoeCollector(sink: OSLogSink()) }

let player = SuperPlayer(configuration: configuration)
player.setMediaRequest(
    MediaRequest(
        contentId: "episode-42",                       // a stable id, never a URL
        sources: [URL(string: "https://cdn.example.com/episode-42/main.m3u8")!],
        startPosition: .resumeFromLastKnown,
        title: "Episode 42"))
player.avPlayer.play()
```

| `Configuration` property | What it is | Filled by |
| --- | --- | --- |
| `profile` | What kind of playback this is: `.videoOnDemand` (the default), `.liveLinear`, `.shortForm`, `.tvLeanback`, `.dataSaver` | core |
| `policy` | A `PlaybackPolicy` deciding buffer and selection; nil is the profile's own static table | `AdaptivePolicy.forProfile(_:)`, or yours |
| `telemetry` | A factory for the collector measuring each player | `{ QoeCollector(sink: …) }` |
| `resilience` | Failure classification and the ladder's rungs | `Resilience.standard(headers:)` |
| `drm` | FairPlay protection | `Drm.fairPlay(FairPlayConfig(certificateURL:licenceURL:))` |
| `offlineContent` | Where downloaded content is opened from | a `Downloads` store |
| `output` | Matching the display to the content | `TvOutput.standard()` |
| `frameOutput` | Each decoded frame handed to your effect before it is shown | your `FrameOutput` |
| `setCmcdMode(_:)` | CMCD's transmission mode; request headers is the only one AVFoundation offers, and the query-parameter mode is refused typed | core |

**`player.avPlayer` is the `AVPlayer` itself.** `AVPlayerViewController`, `AVPlayerLayer` and
SwiftUI's `VideoPlayer` all take it unchanged. It is the one escape hatch (ADR-0020 rule 3), and
anything you do through it is outside the library's compatibility promise. `PlayerView` (SwiftUI)
and `PlayerLayerView` (UIKit) host the player's own layer, and either can give you a Picture in
Picture content source.

**Failures arrive on `player.errors`**, a Combine publisher, and `player.playerError` keeps the last
one. On a player configured with `SuperPlayerResilience`, a failure nothing rescued is a
`SuperPlayerError` carrying its class, a message key and whether retrying could help, with
AVFoundation's own `NSError` underneath. A failure a rung repaired never reaches you.

**State that outlives a player is yours to store.** `player.saveSnapshot()` returns a `Codable`
`PlaybackSnapshot`, and `restoreSnapshot(_:)` takes it back. Keep it in `SceneStorage`, state
restoration or a store of your own: SuperPlayer persists nothing on your behalf (ADR-0006 rule 2).

The demo under `ios/Demo/` is the recipe for each of these, screen by screen; `ios/Demo/README.md`
says which screen shows what.

---

## What ports, what differs, and what is not ported

The Android library composes on Media3's extension points beneath the engine: a transfer chain, a
load control, a track selector, a DRM session manager. AVFoundation exposes almost none of them.
So the port is the same product over a different set of seams. `docs/ios-plan.md` §3 is the
feature-by-feature table, and the record that binds it is ADR-0020.

### Ports, with the same vocabulary

- **The facade and content identity:** `MediaRequest`, remembered positions, `PlaybackSnapshot`.
- **Profiles and the policy boundary:** `PlaybackPolicy`, `PlaybackConditions`, `PlaybackDecision`,
  re-consulted on the same five triggers, and `AdaptivePolicy`'s buffer and selection halves. Every
  `AVPlayerItem` knob is live-settable, so a changed decision is always applied whole.
- **Lifecycle on every player:** the audio session, interruptions, headphones unplugged, and the idle
  timer while video plays.
- **`PlaybackSession`** for the lock screen and the remote commands, and `MediaRequestResolver` for
  content named from outside the app.
- **`PlayerPool` and `PreloadCoordinator`** for feeds.
- **Telemetry:** the whole event vocabulary at the same `SCHEMA_VERSION`, held to
  [`telemetry-schema.md`](telemetry-schema.md), whose *How derived on iOS* column says where each
  number comes from. **CMCD** from iOS 18, as request headers, with the `sid` equal to the telemetry
  session id — the join a CDN log is made on ([`ios-cmcd-finding.md`](ios-cmcd-finding.md)).
- **The failure taxonomy**, leaf for leaf, and rungs 4 to 6 of the fallback ladder: the next source,
  rebuilding the item after the media services reset, and the typed error.
- **FairPlay**, with a licence exchange that is the library's own: the certificate fetched once, the
  request `POST`ed only to the server you configured, retried on the licence budget, and one refused
  credential repaired. Also the **offline licence store** in a directory you name.
- **Downloads** into a directory you name, with live content refused at enqueue.
- **tvOS display matching**, the doctor's HLS half, the session bundle, the debug HUD, and the whole
  **realtime path**: `FrameSource` and its obligations are the same protocol on both platforms.

### Differs, and how

- **Realtime is a second player type.** An `AVPlayer` cannot take pushed frames, so a realtime
  stream plays on `RealtimePlayer`. A realtime source handed to `SuperPlayer` is refused with an
  error naming `RealtimePlayer` (ADR-0020 rule 11).
- **A refused credential is repaired by re-adopting the content**, at the held position, with the
  headers your `HeaderProvider` returns. That costs the playlists again, because nothing re-asks a
  single request. The headers ride `AVURLAssetHTTPHeaderFieldsKey`, an option AVFoundation reads and
  its SDK does not declare. A test holds it to reaching the wire, and whether an app review objects
  to the string is yours to weigh; a signed URL needs no provider and no option at all (ADR-0020
  rule 6's #420 addendum).
- **The throughput estimate is Apple's.** The access log's `observedBitrate` feeds the per-network
  memory, where Android samples each transfer.
- **The pool's bound is memory.** Apple reports no limit on concurrent decoders, so the bound is a
  per-player budget against the memory the app has left, under a ceiling.
- **FairPlay reports no licence durations to the client.** An offline licence's two deadlines are
  the ones your key server granted, which you state when you acquire and renew.
- **No security-level ladder.** FairPlay has no levels. What is observed instead is the output being
  obscured for want of HDCP on an external display, reported as a telemetry event and never acted on.
- **The doctor reads HLS with a lint of its own** written to RFC 8216, since AVFoundation exposes no
  parser ([`media-source-doctor.md`](media-source-doctor.md) says where that changes a reading). It
  also names one defect the Android doctor does not, `hls-short-live-window`.
- **A frame output** is pulled from an `AVPlayerItemVideoOutput` and shown on an
  `AVSampleBufferDisplayLayer`, so Picture in Picture survives while `AVPlayerViewController` cannot
  host it. On a player configured with protection it is **off for the player's lifetime**, and
  `player.frameOutputOffReason` says why; the player still plays (ADR-0022).

### Not ported, by rule

None of these exists on Apple platforms, and each is a rule rather than a gap waiting to be filled
(ADR-0020 rule 6). A change that adds one amends that rule.

- **The transfer chain**, and everything keyed to it.
- **Your own HTTP stack.** `AVPlayer` uses its own for every HLS request, so there is no
  `HttpTransport` here.
- **The streaming cache.** Pinned content is a downloaded package.
- **Rungs 1 to 3 of the fallback ladder.** Apple retries inside the engine and nothing configures
  it, and host failover is the server's, through content steering. `RetryPolicy.manifest` and
  `.segment` are present and ignored by every player; `.licence` is honoured.
- **Per-transfer bandwidth sampling**, and **token refresh inside a transfer**.
- **DASH.** AVFoundation plays HLS.
- **Provisioning.** The device certificate is Apple's.
- **The per-rung selection gate and `SelectionPace`.** Apple selects, and no per-rung hook exists.
- **TV transport controls.** On tvOS, `AVPlayerViewController`'s own controls are the
  recommendation.

None of these may be reopened by intercepting segments through `AVAssetResourceLoaderDelegate`. That
would put the library between the engine and the network — a local proxy in-process — and take the
system's HLS loading, and AirPlay's and the download API's view of the same URL, off the path.

A field the shared value types carry and iOS ignores is **kept**, so that a policy written once
compiles on both platforms, and its documentation says "ignored on iOS" at the field. These are the
selection pace, the manifest and segment retry budgets, and tunneling.

---

## What your app has to declare

**The library declares nothing in your `Info.plist` and adds no entitlement** (ADR-0007 rule 4,
restated on this platform). Everything below is yours, because each is a decision about your app
rather than about playback.

| You want | Declare | Why it is yours |
| --- | --- | --- |
| Audio that keeps playing with the screen locked or the app in the background | `UIBackgroundModes` with `audio` (Xcode's *Audio, AirPlay, and Picture in Picture* background mode) | The library sets the audio session to `.playback` on every player, which is what lets audio continue, but only an app that declares the mode is allowed to. Without it, playback stops when the app is backgrounded, and the demo deliberately shows that |
| Picture in Picture | The same `audio` background mode; then a content source from `PlayerView` or `PlayerLayerView` handed to `AVPictureInPictureController` | AVKit requires the mode for Picture in Picture to outlive the app's foreground |
| AirPlay | Nothing to start a route; the same `audio` background mode for it to continue in the background | `AVPlayer` allows external playback by default, and the system owns the route picker |
| Lock-screen and Control Center controls | Nothing beyond the `audio` background mode; create a `PlaybackSession` for the player you want published | A session is a choice, not a default: a feed of pooled players should not each claim the lock screen (ADR-0007) |
| CarPlay | The CarPlay audio entitlement Apple grants, and your own templates | An entitlement is granted to an app; `MediaRequestResolver` is where the library meets it |
| Background downloads | No background mode. Pass `application(_:handleEventsForBackgroundURLSession:completionHandler:)` to `Downloads.handleEventsForBackgroundURLSession(_:completionHandler:)`, which answers whether the identifier was the store's | A background `URLSession` is relaunched into your app delegate, which is the one place the system calls. In a SwiftUI app that is a `UIApplicationDelegateAdaptor` |
| FairPlay | No entitlement. A FairPlay Streaming deployment from Apple, giving you the application certificate and a key server; `FairPlayConfig` takes the two URLs | The certificate and the key server are your agreement with Apple and your content owner, and the licence request goes only to the server you configured |
| Plain `http://` streams | An App Transport Security exception, such as `NSAllowsArbitraryLoadsForMedia` | Whether to allow unencrypted media is a policy decision about your app |
| tvOS display matching | Nothing in `Info.plist`. With `AVPlayerViewController`, set `appliesPreferredDisplayCriteriaAutomatically = false` so `TvOutput` is the one owner of the display's criteria | Whether the TV actually switches mode is the viewer's *Match Content* setting, which the request honours rather than overrides |
| The debug HUD in a build | A Debug build, signed with a development profile | `isDebugHudAvailable()` needs both `#if DEBUG` and the profile's `get-task-allow`. In a Release build the overlay's rendering code is compiled out, but the `DebugHud` and `DebugHudTelemetry` types remain, so attach the telemetry sink under your own `#if DEBUG` too |
| A MoQ relay on your local network | `NSLocalNetworkUsageDescription` | Local network access is a privacy permission the viewer grants to your app |

---

## What a release does not promise on iOS and tvOS

The honest list, in this platform's terms. [`compatibility.md`](compatibility.md) has the rest of the
contract.

- **Anything reached through `player.avPlayer`.** It is AVFoundation's object, and code written
  against it is code written against AVFoundation.
- **Behaviour on a device this repository cannot test.** Every iOS test runs on a simulator. The
  following are **listed as a device's rather than faked**, and the logic around each is tested with
  the platform call stood in at one internal protocol:
  - FairPlay — the simulator has none.
  - A background download across a process's death.
  - tvOS display-mode switching.
  - HDCP obscuring on an external display.
  - A real MoQ session over QUIC.
  - Whether a frame output drops a frame `AVPlayerLayer` would have shown.

  [`testing.md`](testing.md) says, per stand-in, what it cannot show.
- **Behaviour on a real network.** Every test plays from an HTTP origin on the loopback interface,
  with faults and throughput applied at that server (ADR-0021). A release is not evidence that a
  particular CDN, packager or middlebox behaves.
- **Performance numbers.** Nothing promises a startup time, a rebuffer rate, a memory peak or a
  battery figure. `ios/Benchmark/` runs the fixed matrix on a simulator, which measures the library's
  decisions rather than a phone.
- **AVFoundation's own behaviour.** The engine ships with the OS, so the deployment floor stands in
  for a pinned engine version. An OS update can change how `AVPlayer` buffers, selects or retries
  without any change here.
- **What the SDK does not declare.** The credential repair's header option is read by AVFoundation
  and declared nowhere, and a later OS could stop honouring it. A test fails then, rather than a
  viewer's session.

---

## Further reading

- [`compatibility.md`](compatibility.md) — what you may depend on, and the Swift products' stability
  table.
- [`telemetry-schema.md`](telemetry-schema.md) — what every metric means, and how each is derived on
  iOS.
- [`media-source-doctor.md`](media-source-doctor.md) — every defect the doctor names, with its
  platforms.
- [`session-bundle.md`](session-bundle.md) and
  [`reading-a-session-bundle.md`](reading-a-session-bundle.md) — one session as one text artifact,
  and what an iOS one says differently.
- [`ios-plan.md`](ios-plan.md) — the feature-by-feature port, and the records it was accepted as:
  [ADR-0020](adr/0020-compose-on-avfoundations-avplayer-and-bring-no-other-engine.md),
  [ADR-0021](adr/0021-test-ios-against-a-loopback-origin-and-run-its-checks-in-one-script.md) and
  [ADR-0022](adr/0022-give-an-effect-the-decoded-frame-through-a-second-output-slot.md).
