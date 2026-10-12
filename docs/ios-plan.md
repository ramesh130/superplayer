# SuperPlayer for iOS — port plan

**Status:** plan, drafted 2026-09-22; §3.11 added 2026-10-10 (#394). **Its decisions are accepted** as
[ADR-0020](adr/0020-compose-on-avfoundations-avplayer-and-bring-no-other-engine.md) (compose on
AVFoundation, §1–§4),
[ADR-0021](adr/0021-test-ios-against-a-loopback-origin-and-run-its-checks-in-one-script.md) (testing
on iOS, §5) and [ADR-0022](adr/0022-give-an-effect-the-decoded-frame-through-a-second-output-slot.md)
(the frame-output seam, §3.11), 2026-10-10 (#396). They were drafted here as ADR-0019 to ADR-0021 and
renumbered because ADR-0019 is #383's. §8's open decisions are answered as this plan assumed. Where
this plan and a record disagree, the record binds.

This plan takes every feature the Android library ships (phases 0–10 and 13–14 of `PRD.md`) and
says, one by one, what it becomes on Apple platforms: ported as is, ported with a different
mechanism underneath, or **not ported** because the platform offers no seam for it. The last
category is the plan's most useful part. The Android library is a policy, resilience,
observability and lifecycle layer composed on Media3's *extension points* — a `DataSource` chain, a
`LoadControl`, a `TrackSelector`, a `DrmSessionManager`, a `LoadErrorHandlingPolicy`, a
`MediaSource.Factory`. AVFoundation exposes almost none of those. What it exposes instead is a
closed HLS engine with a short list of live-settable knobs, a first-class DRM session API, a
first-class download API, and since iOS 18 a first-class metrics stream. The port is therefore
**not** a translation of the Kotlin; it is the same product built over a different set of seams,
with the same public vocabulary wherever the vocabulary names no engine.

## 1. The one decision everything else follows from

**Compose on AVFoundation's `AVPlayer`; bring no other engine.** This is ADR-0001 restated for
Apple platforms and it is the recommendation, with the two alternatives rejected:

- *Bring an engine that has a transfer chain* (an open-source player with `DataSource`-like seams,
  or a port of Media3's core). Rejected: it forfeits hardware FairPlay, background audio through
  the system's own path, AirPlay, Picture in Picture and `AVPlayerViewController`, and it makes the
  library the thing it says it is not, a new player. FairPlay is the only DRM with a hardware path
  on iOS, and it is reachable only through AVFoundation.
- *Kotlin Multiplatform for the pure modules.* The policy interfaces, the buffer policy's five
  branches, the failure taxonomy, `SessionMetrics` and `QoeScore` name no Media3 type and would
  compile under KMP. Rejected: it puts a KMP toolchain into every existing module to save perhaps
  three thousand lines of pure logic, and a Kotlin/Native framework gives a Swift consumer no
  value types, no `Codable`, no async, and no Swift idiom. On Android, Media3's idiom is the
  tie-breaker (`CONTRIBUTING.md` §0.2); on iOS, AVFoundation's is. **What is shared is the
  documents and the fixtures, never the code**: `docs/telemetry-schema.md`, the session trace
  grammar, the hostile manifest corpus, the throughput trace format, the ADRs and the PRD bind
  both platforms, and each platform has a test holding it to them.

The consequence, stated once here and then per feature below: **everything keyed to the transfer
chain does not port.** That is the content-keyed streaming cache, the consumer's HTTP stack
(ADR-0016), the fallback ladder's three load-error rungs, the bandwidth oracle's per-transfer
sampling, the token refresh inside a transfer, and DASH. What survives is everything above the
chain: the facade, content identity, profiles, the policy boundary, telemetry and its schema,
the classifier and rungs 4–6, FairPlay in the consumer's storage, downloads, tvOS output, the
doctor's HLS half, the bundle, the HUD, and the whole realtime path.

## 2. Platform decisions taken up front

| Decision | Choice | Why |
| --- | --- | --- |
| Where the code lives | This repository, decided rather than assumed: sources under `ios/`, one Swift Package with one library product per module that ports, its `Package.swift` at the **repository root** | The ADRs, the schema and the corpus bind both platforms; two repositories would drift on the one thing that must not. The manifest is at the root because SwiftPM resolves a remote dependency by git URL and reads the manifest there, so an `ios/Package.swift` could be resolved by path and by nobody's `.package(url:)`. The cost is that an adopter's resolution clones the whole repository; a read-only mirror published by `release` is the answer if that cost is ever a complaint, not before |
| Distribution | Swift Package Manager only | CocoaPods is in maintenance; SwiftPM is what an adopter's `Package.swift` and Xcode both resolve |
| Language | Swift 6, strict concurrency on | Every rule about which thread a sink or a callback runs on becomes a compiler check rather than a comment |
| Deployment floor | iOS 17, tvOS 17; iOS 18 features gated at runtime and refused typed below it | `AVMetrics` is iOS 18; the `HttpStack.httpEngine()` precedent (refuse typed below API 34) is the shape |
| Platforms | iOS and tvOS. macOS, visionOS and watchOS out of scope | tvOS is Phase 8's subject; the others add surface with no phase asking for it |
| Versioning | Same `MAJOR.MINOR.PATCH` line as the Android modules, released by the same `release` command, under ADR-0017 | One schema version, one trace format, one changelog. Under `0.x` a platform-only change bumping the other is accepted; the record says what would split the lines |
| API surface arbiter | `xcrun swift-api-digester -dump-sdk` over the simulator build, reduced to a committed `ios/api/<Product>.baseline` per product and diffed in `ios/check` (ADR-0021 rule 7); `swift package diagnose-api-breaking-changes` was the first choice and is not used, which `docs/api-surface.md`'s *The iOS products* argues | The `api/<module>.api` analog; ADR-0017's arbiter must exist on both platforms or it binds on neither |
| Facade shape | `SuperPlayer` **owns** an `AVPlayer` and exposes it as `player.avPlayer`; it does not subclass `AVPlayer` | Subclassing a system class Apple documents as not designed for it is the ADR-0003 hazard in different clothes; `AVPlayerViewController`, `AVPlayerLayer` and SwiftUI's `VideoPlayer` take the exposed player unchanged, which is the "no adapter" point kept. `avPlayer` is the `exoPlayer` escape hatch under ADR-0001 rule 2 |
| Builder idiom | `SuperPlayer(configuration:)` with a `Configuration` value; no `Builder` classes | Swift idiom; the Android builder's every setter becomes a property with a default |
| Testing | XCTest on the simulator; a loopback HTTP origin in testkit as the harness | See §5. AVPlayer takes no fake transport, so the harness must be a server. This widens `docs/testing.md`'s carve-out, which ADR-0021 rule 2 argues |
| CI | A macOS job in `ci.yml` running the `ios/check` script: `swift build`, `xcodebuild test` on an iOS and a tvOS simulator destination, the API digester and swift-format (ADR-0021 rule 7) | GitHub-hosted macOS minutes cost ten times Linux; the job runs on paths under `ios/` and on the shared documents only |

## 3. Feature by feature

Each row is one thing the Android library does. **Ports** means the public vocabulary is the
same and only the mechanism underneath changes. **Differs** means the vocabulary changes and the
document that describes it says how. **Not ported** is a rule rather than a gap to fill later.

### 3.1 Core (Phase 1)

| Android | iOS | Verdict |
| --- | --- | --- |
| `SuperPlayer` is a `Player` by delegation; `player.exoPlayer` | `SuperPlayer` owns an `AVPlayer`; `player.avPlayer` | Differs, argued above |
| `MediaRequest`, `contentId`, `sources`, `StartPosition`, title/subtitle/artwork | Identical value type, `Codable`; identity travels as the `AVPlayerItem`'s external metadata and the resolver's key | Ports |
| `setMediaRequest`, remembered positions, `MAX_REMEMBERED_POSITIONS` | Identical; positions read off `currentTime()` at item replacement | Ports |
| `PlaybackProfile` and `StaticProfilePolicy` | Identical enum; the numbers land on `AVPlayerItem.preferredForwardBufferDuration`, `preferredPeakBitRate`, `preferredMaximumResolution`, the `…ForExpensiveNetworks` pair, `configuredTimeOffsetFromLive` and `automaticallyPreservesTimeOffsetFromLive`, and `AVPlayer.automaticallyWaitsToMinimizeStalling`, which no row moves; every departure from Apple's default argued where chosen | Ports; a smaller table, since Apple exposes fewer knobs |
| `PlaybackSnapshot`, `toBundle`/`fromBundle` | `Codable` value; the app stores it in `SceneStorage`, state restoration or its own store | Ports; ADR-0006 rule 2 kept as written |
| Lifecycle: audio focus, becoming noisy, wake and Wi-Fi locks | `AVAudioSession` category and activation, interruption notifications, route-change notifications (headphones unplugged pauses), `UIApplication.isIdleTimerDisabled` while video plays; no Wi-Fi lock exists | Ports as correctness on every player; background audio needs the **app's** `UIBackgroundModes`, the manifest-is-the-app's rule of ADR-0007 rule 4 |
| `PlaybackSession` over `MediaSession`; `PlaybackService` | `MPNowPlayingInfoCenter` and `MPRemoteCommandCenter`, a choice rather than a default, one `release()`; no service exists on iOS and none is needed | Ports; the service half is not ported because the platform has no such thing |
| `MediaRequestResolver` (notification, Auto, Wear) | Same protocol, resolving CarPlay list items and remote commands back into a `MediaRequest` | Ports |
| `LivePlaylistRevalidation`, `StaleLivePlaylistException` | No request can be reissued. A stuck live playlist is *detected* from `AVMetricHLSPlaylistRequestEvent` on iOS 18 (the same playlist answered unchanged past its target duration bound) and reported as the same typed error; below 18 it is `AVPlayerItemErrorLog` only, coarser | Differs: detection without repair; the exception's fields are the same. **Built (#421)** as `StaleLivePlaylistError`, on a player configured with resilience (ADR-0011 rule 4's #421 addendum): unchanged is the live edge standing still, and below 18 the fields the log cannot carry are nil |
| `LiveWindowDepthCheck` | HLS has no `timeShiftBufferDepth`; the analog is a live playlist whose window is under three target durations (RFC 8216 §6.3.3), read from the playlist the doctor fetches, not from the player | Moves to the doctor |
| `TransferChain`, `LoadKind` stamp, `ContentIdentity` stamp | No chain. `AVAssetResourceLoaderDelegate` fires for custom schemes and key requests only, and intercepting segments through it disables the system's own HLS path | **Not ported** |
| `HttpStack`, `HttpTransport`, `HttpTransportConformance` (Phase 10) | AVPlayer uses its own stack for every HLS request | **Not ported**; ADR-0016 records that it has no Apple counterpart. `HeaderProvider` survives in a narrower form, §3.5 |
| Player delegation forwarding test | Nothing to forward; the KVO and notification set the facade observes is enumerated in one file and a test asserts every one is registered | Differs |

### 3.2 Telemetry and testkit (Phase 2)

| Android | iOS | Verdict |
| --- | --- | --- |
| `TelemetrySink`, the sealed `TelemetryEvent` hierarchy, `SCHEMA_VERSION` | Same protocol, same enum-with-payload vocabulary, same version constant, held to `docs/telemetry-schema.md` by a test on both platforms | Ports 1:1; the document gains a *How derived on iOS* column per metric |
| `QoeCollector` from `AnalyticsListener` | From KVO on `AVPlayerItem.status`, `isPlaybackLikelyToKeepUp`, `isPlaybackBufferEmpty`, `AVPlayer.timeControlStatus` and `reasonForWaitingToPlay`, `AVPlayerItemPlaybackStalled`, `AVPlayerItemNewAccessLogEntry` and `…ErrorLogEntry`, and on iOS 18 the `AVMetrics` event streams (`AVMetricPlayerItemStallEvent`, `…InitialLikelyToKeepUpEvent`, `…HLSMediaSegmentRequestEvent`) | Ports; each metric's iOS derivation cited in the schema document, and where 17 and 18 derive differently the document says so |
| First frame, `declarePlaybackIntent()` | First frame is `AVPlayerLayer.isReadyForDisplay` (or the sample-buffer layer's equivalent); intent is the same call | Ports |
| Dropped frames | Access log `numberOfDroppedVideoFrames` per entry | Ports, at access-log cadence |
| Live latency | `currentTime()` against the end of `seekableTimeRanges`, and `configuredTimeOffsetFromLive` | Ports |
| Bandwidth samples | None; the access log's `observedBitrate` is Apple's smoothed figure, not a sample | Differs: `throughputEstimateBps` carries Apple's number and the document says whose it is |
| Track switch | `AVPlayerItemNewAccessLogEntry` with a changed `indicatedBitrate` | Ports |
| `LicenceAcquisitionEnded` | From `AVContentKeySession` delegate callbacks | Ports (§3.6) |
| `TelemetryDelivery` bounded queue, drop counting | A serial `DispatchQueue` with the same bound and the same refusal rule; the engine's threads are never the sink's | Ports; Swift 6 isolation enforces the thread rule |
| `LogcatSink` | `OSLogSink`, one line per event under one subsystem and category | Ports |
| `SessionTraceRecorder`, `SessionTrace.FORMAT_VERSION`, seven redaction rules | Same grammar, same version, same rules; the `load` and `bandwidth` kinds come from `AVMetrics` request events on 18 and are absent below | Ports; the recorder's KDoc lists which kinds need 18 |
| Golden traces, byte-identical | **Not achievable**: AVPlayer runs on real time. Goldens become *structural*, kinds and order with times masked, and `docs/testing.md`'s iOS section says what a structural diff can and cannot show | Differs |
| CMCD (CTA-5004) | **Spiked (#413, [`ios-cmcd-finding.md`](ios-cmcd-finding.md)): the SDK emits it.** `AVAssetResourceLoader.sendsCommonMediaClientDataAsHTTPHeaders` (iOS 18) sends CMCD v1 headers on every request, and their `sid` is the asset's `httpSessionIdentifier`, which core reads as the telemetry `sessionId`. `AVURLAssetPrimarySessionIdentifierKey` reaches no CMCD key. Headers only (`CmcdMode.queryParameter` refused typed), no `cid`, nothing below 18 | **Built (#446)** from iOS 18 |
| `PlaybackHarness`, fault injection, network shaping, throughput trace replay | A loopback HTTP origin in `SuperPlayerTestkit` serving the synthetic streams, with `FaultScript` and `ThroughputTrace` applied at the server rather than in a transport slot. The trace format is `docs/throughput-traces.md`'s, shared | Differs in mechanism, same vocabulary; §5 |
| `SyntheticHlsStream`, `HostileManifests`, `WidevineProtection` | `SyntheticHlsStream` and the HLS half of the corpus ported to Swift; the Kotlin generators' output is committed as fixtures and each platform's check asserts its own generators produce those bytes (ADR-0021 rule 6), so one corpus exists. DASH generators not ported. Protection fixtures become FairPlay `EXT-X-KEY` with `skd://` URIs | Ports, with the cross-platform equality test as the proof it is one corpus |
| `NetworkProfile`, the six plus `ETHERNET` | Same constants, same sources | Ports |
| Benchmark, four arms, `StockTelemetry` agreement test | Stock `AVPlayer`, stock plus intuitive config, SuperPlayer profile, SuperPlayer adaptive; metrics reduced by one ported `SessionMetrics`; the agreement test attaches both collectors to one `AVPlayer` | Ports; runs on the simulator against the loopback shaper |

### 3.3 Policy and ABR (Phase 3)

| Android | iOS | Verdict |
| --- | --- | --- |
| `PlaybackPolicy`, `PlaybackConditions` (six observations), `PlaybackDecision` and its halves | Identical protocol and value types, naming no AVFoundation type | Ports 1:1 (ADR-0005, ADR-0009) |
| `EngineBinding.kt` | One file laying a decision onto the `AVPlayerItem` properties in §3.1; every property is live-settable, so a re-consulted decision is honoured whole, which is ADR-0009 rule 5's condition met by the platform | Ports |
| `ConditionsBinding.kt` | Transport from `NWPathMonitor` (`isExpensive`, interface type; an expensive WiFi or wired path is a hotspot's cellular link). `isConstrained` is not a transport and not one of the six: it is the data-saver rule's (§3.4), throughput from the access log's `observedBitrate` with a spread computed over recent entries, stall history from the collector, stream type from `seekableTimeRanges` and the playlist, heap from `os_proc_available_memory`, rate from `AVPlayer.rate` | Ports |
| `DecisionReapplication` on five triggers, `DecisionChanged` events | Same triggers, same event | Ports |
| `AdaptiveBufferPolicy`, five branches | Identical pure logic; the heap cap applies to `preferredForwardBufferDuration` | Ports |
| `AdaptiveLoadControl` | No load control; the buffer half is the property above | Not needed |
| `LiveLatencyPolicy` | `configuredTimeOffsetFromLive` and `automaticallyPreservesTimeOffsetFromLive` | Ports |
| `BandwidthOracle`, `EstimateMemory` per transport, cold defaults, aging | Per-transport memory, cold defaults and aging port unchanged; the *input* is the access log's observed bitrate rather than sampled transfers, and cache-hit exclusion has nothing to exclude | Ports with a different input, which the schema document says |
| `AdaptiveSelectionPolicy`: profile cap, `TransportCaps`, post-rebuffer hold | Ceiling lands on `preferredPeakBitRate` and `preferredMaximumResolution`; the expensive-network pair is what `TransportCaps` becomes on cellular | Ports |
| `NetworkAwareTrackSelection`: device refusals, ceiling, discounted estimate; `SelectionPace` | Apple selects; no per-rung hook exists. Device refusals are Apple's own. `SelectionPace` has no knob | **Not ported**; `TrackSelectionPolicy.pace` is present in the value type and documented as ignored on iOS |
| QoE regression gate in `check` | The lever is the buffer half only and the harness is real-time, so a floor in `check` would flake. A measured report per profile, committed as Phase 3's was, not a gate | Differs |

### 3.4 Pool, preload and cache (Phase 4)

| Android | iOS | Verdict |
| --- | --- | --- |
| `PlayerPool`, `acquire` returns nil past the bound, `recycle`, `resetForReuse` | Ports. The bound: the platform reports no concurrent-decoder limit, so `DeviceCapacity` is a per-player heap budget against `os_proc_available_memory` and a ceiling, both argued now (#404) and measured on the device matrix by i10 (#439), never below one | Ports; a different reading, one place |
| `PreloadCoordinator`, `setItems`, `setScrollPosition`, `PreloadPolicy` per profile | Ports. Every prefetched row is an `AVURLAsset` asked for its `duration`, which loads its playlists with no player; an item made on it later fetches neither again. An item buffers only on a player and is that player's for life (#417 found both, counted at the origin), so a *loaded* range is an item held on an idle pooled player, its `preferredForwardBufferDuration` the depth (one segment for `shortForm`'s second); decoder warm-up is `AVPlayer.preroll(atRate:)` on such a player | Ports; `Loaded` is an item on an idle pooled player, `DecoderWarmed` that and `preroll` |
| Memory guard, data-saver rule | Guard on `didReceiveMemoryWarning` and the quarter-heap admission; Low Data Mode is `NWPath.isConstrained`, applied at the path change | Ports |
| `ContentKeyedCache`, `ContentKeys`, `PinAwareLruEvictor`, `CachePolicy.deviceAware` | No streaming cache exists over AVPlayer. Pinned content is a downloaded `.movpkg`, which is Phase 7's; a player of a downloaded item reads it through `AVURLAsset(url:)` of the package, and an in-progress download can be played from its task's asset | **Not ported** as a streaming cache; `ContentCache` becomes the download store's directory and budget, §3.7 |
| Feed demo, `huntLeaks` | SwiftUI feed over the pool; leak hunt through `xctrace` with the Leaks and Allocations instruments | Ports |

### 3.5 Resilience (Phase 5)

| Android | iOS | Verdict |
| --- | --- | --- |
| `FailureClass`, total and public; `FailureCategory` derivation; message keys | Identical taxonomy | Ports 1:1 |
| `ErrorClassifier` | One place, over `AVError` codes, `NSURLErrorDomain`, `CoreMediaErrorDomain` and the `AVPlayerItemErrorLog` entry that accompanies a failure (status and URI, which tells a refused segment from a refused playlist without a `LoadKind` stamp) | Ports; the only `switch` over engine codes in the package |
| Rung 1 retry same URL, rung 2 next host, rung 3 exclude variant, `RetryingLoadErrors`, `Backoff` | Apple retries inside the engine and nothing configures it; content steering (`EXT-X-CONTENT-STEERING`) is the server-driven host failover and is also Apple's | **Not ported**; `RetryPolicy.manifest` and `.segment` are present and documented as ignored by every player, `.licence` is honoured (§3.6), and `.segment` is spent by the download store (§3.7, #424) |
| Rung 4 next source | Ports whole: the next `MediaRequest.sources` entry becomes a new `AVPlayerItem` at the held position, same session, same identity | Ports; **built (#420)**, resuming at the rate the viewer last asked for, since a failed item drops it to zero |
| Rung 5 recreate decoder, `MAX_DECODER_RECREATIONS` | `AVAudioSession.mediaServicesWereResetNotification` and `AVError.mediaServicesWereReset` are exactly the case: the player is rebuilt at the held position, bounded the same way | Ports, on a cleaner signal; **built (#420)** — the *item* is rebuilt and the `AVPlayer` kept, because `player.avPlayer` is what every view holds; whether a real reset needs the engine itself rebuilt is a device's question (`docs/testing.md`, *Rungs 4 and 5 on iOS*) |
| Rung 6 `SuperPlayerError`, `classification` on telemetry, withheld repaired failures | Ports; delivered as the facade's error publisher, with the `NSError` under it | Ports |
| `HeaderProvider`, token refresh inside the transfer | Headers are set at `AVURLAsset` creation and nothing re-asks a request. A 401/403 on a segment is repaired by re-adopting at the held position with what the provider returns, which is rung 4's mechanism under rung 1's rule; a signed-query URL is the shape the app controls end to end | Differs: repaired by re-adoption, and the cost (a manifest re-fetch) is documented; **built (#420)** over `AVURLAssetHTTPHeaderFieldsKey`, an option the SDK does not declare, which `CredentialRepairPlaybackTests` holds to reaching the wire; once per refused request, and the CMCD `sid` on the wire changes at the repair while the telemetry session does not |
| `FaultSweepTest`, `FallbackRungCoverageTest` | The sweep runs every fault the origin can serve; the register lists rungs 4–6 and says why 1–3 have no row | Ports at the rungs that exist; **built (#421)**. One fault the sweep found still stalls: a segment that never answers inside AVFoundation's wait (`docs/testing.md`, *The fault sweep on iOS*) |

### 3.6 DRM (Phase 6)

FairPlay replaces Widevine, and the platform's shape is closer to ADR-0012 than Media3's was.

| Android | iOS | Verdict |
| --- | --- | --- |
| `Drm.widevine(WidevineConfig)` on the builder, fixed per player | `Drm.fairPlay(FairPlayConfig(certificateURL, licenceURL))` in the configuration; an `AVContentKeySession` per player with the item added as a recipient | Ports; **built (#422)** |
| Licence as a load on the player's chain, `LoadKind.LICENCE`, `RetryPolicy.licence`, header refresh | The key request is **ours** to make: `makeStreamingContentKeyRequestData` yields the SPC, the library `POST`s it over `URLSession` with the `HeaderProvider`'s headers, retries on `RetryPolicy.licence` with `Backoff`, and repairs one 401/403 with a fresh credential. The one place the consumer's client could plug in, deliberately not offered until an adopter asks | Ports; the budget is honoured here, which it is on Android too; **built (#422)**, every failure asked again whatever its status because the product classifies nothing, and the `HeaderProvider` moved to core so neither optional product depends on the other |
| `DrmSessionManagerProvider` slot, sixth friend | No slot; the session is the facade's | Not needed |
| Multi-session by `DrmInitData` | Apple keys per `EXT-X-KEY`; nothing to fix | Not needed |
| Key rotation | Apple asks the session again; one licence request per rotation, counted | Ports as telemetry; **built (#422)** — a rotation and a renewal emit no `LicenceAcquisitionEnded`, as on Android |
| Provisioning | None; the device certificate is Apple's | **Not ported** |
| Security-level ladder, `permittedSecurityLevels`, `deliveredSecurityLevel` | FairPlay has no L1/L3. The one observable is `AVPlayer.isOutputObscuredDueToInsufficientExternalProtection` (HDCP on an external display), reported as a telemetry event and never acted on, which is ADR-0012 rule 11's "policy is the server's" kept | Differs: an observation, no ladder; `SessionEnded.securityLevel` is nil and the schema says so; **built (#422)** as `OutputObscuredChanged`, an event added to both platforms' vocabulary and emitted on Apple's alone |
| Secure decoder, tunneling | Apple's | Not needed |
| `OfflineLicenceStore` in the consumer's directory, `licenceFor`, expiry split, `renewalDue`, renewal reported not scheduled | `AVPersistableContentKeyRequest`, the persistable key saved to a SQLite file (the system `libsqlite3`) in the directory the consumer named beside the two deadlines; renewal a fresh persistable request by identifier (`processContentKeyRequest(withIdentifier:)`), since `renewExpiringResponseData(for:)` needs the request a *playing* session was handed; release the key invalidated and its proof of deletion `POST`ed | Ports; **built (#423)** as `licence(for:)`, with one difference: FairPlay reports neither duration to the client, so the two are what the key server granted, stated by the app at `acquire` and `renew`; tvOS has no persistable keys, so its verbs fail typed |
| Expired licence refused typed | Ports | Ports; **built (#423)**, core's `OfflineLicenceExpiredError` named `Drm.licenceExpired` |
| `FakeExoMediaDrm`, `FakeLicenceServer` | **The simulator has no FairPlay.** Every DRM test is a device test under the device lab (§6), against a fake licence server on the host. `check` holds the request composition, retry and store logic with the key session stubbed at the one protocol the library defines | Differs; `docs/testing.md` says which claims need a device (*FairPlay on iOS*); the licence server is testkit's `.licenceServer()` on the loopback origin, and the protocol is `FairPlayKeySession` |

### 3.7 Offline (Phase 7)

| Android | iOS | Verdict |
| --- | --- | --- |
| `Downloads` over the `ContentCache`, `enqueue(request, languages)`, `DownloadItem`, `DownloadsListener`, `remove` | Same API over `AVAssetDownloadURLSession` with a background configuration whose identifier is the store's; `AVAssetDownloadConfiguration` selects the rendition under `DownloadSelectionPolicy` and the languages through media selections; the package lands in the directory the consumer named; the index is the store's own SQLite file beside it | Ports; **built (#424)** as `Downloads(configuration:)`, a player opening the store's content through core's `Configuration.offlineContent`. The simulator test host cannot run a download task, so the transfer is stood in at the one `package` protocol `AssetDownloading` and every claim about a real task is #436's (`docs/testing.md`, *Downloads on iOS*); a store has no `stopped` state, since the system holds a task without saying why |
| Pin inside the downloader, manifest served for pinned content only | The `.movpkg` is the pin; a player opens it directly | Ports by platform; **built (#424)**, the package moved into the directory once its task completes and an unfinished download played from its task's asset |
| Survives process death and network loss | A background `URLSession` is resumed by the system and re-attached on store open; that is the platform's promise and a test on a device is the only honest check of it | Ports; device-tested (#436); **built (#424)**: a store opened again re-attaches by identifier and starts again from its index only what the session no longer holds |
| Unmetered, battery-not-low, storage-not-low; Data Saver; `meteredNetworksAllowed` | `allowsCellularAccess`, `allowsExpensiveNetworkAccess`, `allowsConstrainedNetworkAccess` (Low Data Mode), `isDiscretionary` for the battery and system-judged conditions; storage is the system's | Ports; battery is coarser and the document says so; **built (#424)**, with `meteredNetworksAllowed` fixed for a store's life because it is the session's configuration |
| `WorkManager` schedule, `DownloadsService` | The background session *is* the scheduler; no service and no foreground notification exist | **Not ported** as such; the app's `handleEventsForBackgroundURLSession` hand-off is the one thing the app wires, passed to `Downloads.handleEventsForBackgroundURLSession(_:completionHandler:)` (#424) |
| Full disk fails one item | The system fails the task; classified `Storage.Full` | Ports; **built (#424)** on a store with a resilience, failed at once and never retried |
| Licence bound to the download, released on removal | The persistable key acquired before the first byte, released with the package | Ports; **built (#425)**: `Configuration.protection` takes the `PlaybackDrm` and `LicenceStore` with the two durations FairPlay does not report; protection is a FairPlay `EXT-X-SESSION-KEY` in the multivariant playlist, read once more as text since `AVURLAsset` does not say; removal deletes the key on the device at once and tells the server once `NWPathMonitor` reports the store's network |
| Live refused at enqueue | The store refuses before any task, off the asset's indefinite duration; reported as `DownloadRefusal.liveContent` | Ports; **built (#424)** |
| Retry budgets inside the downloader | The task's own retry is Apple's; the store re-enqueues a failed task on `RetryPolicy.segment` with `Backoff` | Ports at task granularity; **built (#424)**, `Backoff` moved to core as `package` so the store and the licence exchange share one jitter |

### 3.8 TV (Phase 8)

| Android | iOS | Verdict |
| --- | --- | --- |
| `TvOutput.standard`, frame-rate matching through `Surface.setFrameRate` | tvOS `AVDisplayManager.preferredDisplayCriteria = asset.preferredDisplayCriteria`, which honours the viewer's *Match Content* setting and is the cleaner mechanism; `isDisplayModeSwitchInProgress` is the withdraw | Ports, smaller; **built (#426)**: asked per item, withdrawn at its end, on a recycle and on release and never between two items; `isDisplayModeSwitchInProgress` turned out to be a reading rather than a withdrawal — the switch it brackets is the request being honoured — so it is observed and reported, with the setting, as `DisplayModeChanged` |
| `DisplayWatch`, re-selection on display change | Apple re-selects; the facade observes `isDisplayCriteriaMatchingEnabled` and the display change for telemetry only | Not needed |
| Audio-capability change re-selection | `AVAudioSession.routeChangeNotification`; Apple re-selects | Not needed |
| Tunneling, `OutputPolicy` | Always tunneled on Apple TV; the half stays in `PlaybackDecision`, documented as ignored | Not needed |
| `TvPlaybackControls` in Compose for TV, `Scrub` curve | `AVPlayerViewController` on tvOS ships transport controls, scrubbing, info panel and Siri Remote handling; **recommend not building controls**. The `Scrub` acceleration curve is carried only if an adopter's custom SwiftUI controls need it | Not ported unless asked |
| `SurfaceView` rule, `FLAG_SECURE` | `AVPlayerLayer`; secure output is the system's | Not needed |

### 3.9 Diagnostics (Phase 9)

| Android | iOS | Verdict |
| --- | --- | --- |
| `MediaSourceDoctor.examine(request)`, `Pathology`, `Finding`, severities, the corpus register | Same API over the HLS half of the corpus. The doctor fetches over `URLSession` with the `HeaderProvider`'s headers and **parses with a lint parser of its own written to RFC 8216**, because AVFoundation exposes no parser. This is a stated departure from ADR-0015 rule 6 (the doctor and the player parse alike) and from `PRD.md` §0.1's "not a new protocol stack", argued in the ADR: the parser decides nothing about playback, it is a lint | Ports for HLS; **DASH not ported** |
| `DeliveryPathologies` (cache headers, token scope and expiry, CORS) | Header reads over `URLSession`; the one-segment headers probe is a `HEAD` (ADR-0020 rule 8's #428 addendum) | Ports; **built (#428)** |
| `LivePlaylistRevalidation.cachedPastTheUpdateBoundSeconds` seam | Core holds the rule since #421, as `StalePlaylistJudgement.cachedPastTheUpdateBoundSeconds`, `package` since #428 so the doctor asks it rather than restating it (ADR-0015 rule 6) | Ports; **built (#428)** |
| Postmortem `examine(request, classification)` | `examine(_:classification:)`, the `SuperPlayerError` read off `player.classify(_:)` onto `DiagnosticReport.classification` | Ports; **built (#428)** |
| `SessionBundle`, `capability` snapshot, seventh redaction rule | Same format and version; the snapshot is `VTIsHardwareDecodeSupported` per codec, the screen's mode and HDR modes, `os_proc_available_memory`, the OS version; device model and any identifier refused, which is rule 10 read the same way | Ports; **built (#429)** as `SessionBundle(trace:)`, the screen mode read as its short edge and the HDR modes as `AVPlayer.eligibleForHDRPlayback`, since `availableHDRModes` is deprecated from 26, with no protection line, since FairPlay has no level, and the readership reading argued at ADR-0015 rule 10's #429 addendum |
| `DebugHud` in Compose, `FLAG_DEBUGGABLE` guard | A SwiftUI overlay, guarded by `#if DEBUG` plus a runtime `isDebugHudAvailable` reading the entitlement, with the same "what it does not guarantee" note | Ports; **built (#429)**, the entitlement read from the embedded provisioning profile, over a `SuperPlayer` or a stock `AVPlayer` |
| `DoctorDocumentTest`, `docs/media-source-doctor.md` | One document with a platform column per pathology; the test runs on both platforms over the same file | Ports; **built (#430)** as a `- **Platforms:**` line per section, `DoctorDocumentTests` reading the sections naming iOS and tvOS and the Kotlin test the ones naming Android |

### 3.10 Realtime (Phases 13–15)

The realtime path ports **better** than anything above it, because ADR-0018 pushes encoded frames
into engine-owned queues and Apple has exactly that engine: `AVSampleBufferDisplayLayer` and
`AVSampleBufferAudioRenderer` under an `AVSampleBufferRenderSynchronizer` decode compressed
`CMSampleBuffer`s themselves. "Nothing here decodes" holds unchanged.

| Android | iOS | Verdict |
| --- | --- | --- |
| `FrameSource`, `FrameSink`, `RealtimeTrack`, `EncodedFrame`, `CodecConfiguration`, nine obligations | Identical protocols in the core product | Ports 1:1 |
| `RealtimeMediaSource`/`MediaPeriod` over `SampleQueue`s, unseekable timeline | A `RealtimeEngine` over the synchronizer and the two renderers; the shared-epoch anchor, the late-track and stalled-track bounds in media time, and the typed refusals of `At` and `ResumeFromLastKnown` port unchanged | Ports |
| `RealtimeFormats` table | `CMFormatDescription` per codec, keyed by the same two spellings, with the same citations | Ports |
| `CodecConfigurationRecords`: record to Annex-B, NAL length size read and used | **Reversed.** CoreMedia's native shape is the record (`avcC`/`hvcC` as the format description's atom) and length-prefixed samples; so `Record` passes through and `InBand` is what needs conversion (parameter sets to a description through `CMVideoFormatDescriptionCreateFromH264ParameterSets`, Annex-B samples re-framed to four-byte lengths). The same refusals apply, in the other direction | Ports, mirrored, and the KDoc says which way |
| `FrameSourceConformance` in testkit, eleven checks | Same checks and message shape, thrown as a plain `Error` so no framework is named (#433) | Ports 1:1 |
| Facade: a realtime item through `setMediaRequest` on the one player | An `AVPlayer` cannot take pushed frames. Realtime is a second public type, `RealtimePlayer`, sharing the telemetry, lifecycle and session code through the same internal engine protocol, and the SwiftUI `PlayerView` renders either. This is the one place the "one player" claim splits, argued in the ADR | Differs |
| `superplayer-moq` over UniFFI Kotlin bindings, `MoqFrameSource`, `MoqRelay` seam, `ScriptedMoqRelay`, statistics | UniFFI generates the Swift bindings from the **same** `third-party/moq/m2` checkout, which is the one artifact both platforms share; the bridge, the seam, the scripted relay, the pump-per-track and the one-second statistics poll port; `MoqUpstreamMediaLoss.NOT_INSTRUMENTED` stays and #354 covers both | Ports; `LOCAL_ONLY` publication has a SwiftPM analog in a binary target the demo resolves locally |
| `superplayer-whep` (contingent) | Google's WebRTC iOS framework; whether encoded frames are reachable is the same unsettled question and Phase 12's finding decides it for both | Contingent, as on Android |
| Device smoke test, `devicelab/moq-smoke` | An XCUITest target driven by the same lab script with `--platform ios` | Ports |

### 3.11 Frame output (no Android phase yet)

No Android phase gives an effect a way to reach a decoded frame before it is displayed, so this
section is not a port. It is in this plan because the iOS answer decides **how the core renders**,
and that is fixed at i1. The consumer it serves is an on-device ML effects library built as a
separate project (person segmentation, auto-reframe and similar). That library targets stock Media3
first, and SuperPlayer through an adapter. SuperPlayer ships no effects. It ships the place where an
effect can attach, on both platforms, with one rule for what is out of reach.

**Android today.** Media3 has the seam: `ExoPlayer.setVideoEffects(List<Effect>)` runs a GL effect
pipeline between the decoder and the surface. Both the method and `Effect` are `@UnstableApi` in
Media3 1.11.0, so under ADR-0001 rule 2 neither may appear on SuperPlayer's public surface. An
adopter reaches the method through `player.exoPlayer` today. The method's Javadoc sets four limits
that bind any SuperPlayer design:
- it must be called once before `prepare()`;
- it works only with the default `MediaCodecVideoRenderer`;
- it rejects effects that change frame timestamps;
- it "does not work with DRM-protected content".

**iOS.** Apple's documentation for `AVPlayerItem.videoComposition` says that a video composition "is
not supported for use with media served using HTTP Live Streaming", and a custom
`AVVideoCompositing` compositor arrives only through a video composition. What remains for streamed
content is the pull path: an `AVPlayerItemVideoOutput` added to the item, a display link asking it
for the pixel buffer due at each host time, and a display layer the effect's output goes to in place
of `AVPlayerLayer`. Developers report that the output returns no pixel buffer under FairPlay (Apple
Developer Forums thread 726854). This is a report, not documented behaviour, so i6 verifies it on a
device. The realtime path (§3.10) has no decoded frame to tap, because `AVSampleBufferDisplayLayer`
decodes internally.

| Concern | Android | iOS | Verdict |
| --- | --- | --- | --- |
| Where an effect attaches | `setVideoEffects`, before `prepare()` | `AVPlayerItemVideoOutput` on each item, enqueued to an `AVSampleBufferDisplayLayer` (ADR-0022 rule 5) | Differs. One SuperPlayer type, two mechanisms |
| Public type | A core interface naming no Media3 type; the module behind it is an internal friend that calls `setVideoEffects` | The same interface, taking a `CVPixelBuffer` and a presentation time | Ports. The vocabulary is SuperPlayer's, and no effect API is shared |
| When it is fixed | At build time, because Media3 requires the call before `prepare()` | At build time, for parity; what the output does with a frame may change during playback, inside the output itself (ADR-0022 rule 2) | Ports |
| Display path when unused | Unchanged | `AVPlayerLayer`, unchanged; the sample-buffer path exists only on a player built with a frame output | Ports. "Pays nothing" holds and a test counts it |
| Protected content | Not supported by Media3 | Reported unsupported under FairPlay | **Not ported**: a player built with protection has the output off for its lifetime (ADR-0022 rule 6) and reports it as a typed reason, never as a playback failure |
| Realtime (§3.10) | Not examined | No decoded frame exists | **Not ported** |
| Picture in Picture, `AVPlayerViewController` | Not affected | `AVPlayerViewController` is lost on a player built with a frame output. Picture in Picture survives only if processed frames are enqueued to an `AVSampleBufferDisplayLayer`, which `AVPictureInPictureController` accepts as a content source, rather than drawn to a bare Metal layer | Differs. ADR-0022 rule 5 chooses the sample-buffer layer and states what each choice loses |

**The slot.** ADR-0014 gives core one `EngineConfiguration.videoOutput` slot, which `TvOutput`
fills, and it is fixed for the player's lifetime. A frame output either shares that slot, so that
one `PlaybackOutput` composes display matching and effects, or it gets a second slot. Sharing keeps
ADR-0014's "no second slot" argument. A second slot keeps the TV module ignorant of effects.
**ADR-0022 rule 3 takes a second slot**, `EngineConfiguration.frameOutput`, and amends ADR-0014 rule 3
at the rule: that rule's argument was about a second watcher of an audio fact, and what it protects —
the filled slot as the whole signal — is kept for both slots.

**Rejected.**
- *A custom `AVVideoCompositing` compositor.* It is not supported for HLS, which is every streamed
  item this library plays. It also has a reported regression on composition-backed items under
  Xcode 27 betas (Apple Developer Forums thread 837179).
- *Decoding with VideoToolbox for the HLS path.* This brings a second engine, which §1 rejects. It
  also forfeits FairPlay and the system's HLS stack.
- *Exposing Media3's `Effect` directly.* ADR-0001 rule 2 forbids it.
- *Deferring the decision past i1.* The iOS core would build on `AVPlayerLayer` alone, and the
  frame path would arrive later as a retrofit of the facade's rendering. That retrofit is the change
  this section exists to avoid.

This is **ADR-0022**. i1 builds only the seam: the interface, the display path
behind it, and one pass-through effect in the demo that proves frames arrive on time. The
first real effect belongs to the separate effects project.

## 4. Package layout

```
Package.swift                   at the repository root; every target names `path: "ios/…"`
                                products: SuperPlayerCore, SuperPlayerTelemetry, SuperPlayerABR,
                                SuperPlayerPreload, SuperPlayerResilience, SuperPlayerDRM,
                                SuperPlayerOffline, SuperPlayerTV, SuperPlayerDiagnostics,
                                SuperPlayerRealtime, SuperPlayerTestkit, SuperPlayerTestMedia
ios/
  MoQ/Package.swift             SuperPlayerMoQ, a package of its own and no product of the root
                                manifest: the LOCAL_ONLY analog (#434), over the MoqFFI package and
                                XCFramework in third-party/moq/swift/
  Sources/<Product>/
  Tests/<Product>Tests/
  api/<Product>.baseline        the digester baseline, `ios/check` regenerates and diffs
  Demo/                         an Xcode project resolving the package by path, iOS and tvOS targets
  CLAUDE.md                     iOS orientation, loaded only when working under `ios/`
```

The manifest is the one iOS file outside `ios/`, for §2's reason. Lockstep versioning makes the
tags free: the `vX.Y.Z` tag `release` already cuts is the one SwiftPM resolves, so no second tag
line exists. The macOS CI job's path filter is `ios/**`, `Package.swift` and the shared documents.

No `SuperPlayerCache` product: its content is the offline store's. Dependencies point inward to
`SuperPlayerCore` under `docs/modules.md`'s phase rule, and `verifyModulePhaseRule` gains an
iOS reading of `Package.swift`. Kotlin friend modules have no analog: Swift's `@testable` and
`package` access level are what internal seams use, and every seam a phase 2 module reaches is
`package`-scoped rather than `internal`, which is the ceiling the friend count was standing in for.

## 5. Testing on iOS

`docs/testing.md`'s rule is *no device and no network, through the public API, against the
engine's own fakes*. AVFoundation ships no fakes and takes no fake transport, so the rule cannot
hold as written. The iOS section of that document, decided in ADR-0021, is:

- **The harness is a loopback origin.** `SuperPlayerTestkit` starts an HTTP/1.1 server on
  `127.0.0.1` per test, serving the synthetic streams from `SuperPlayerTestMedia`, with
  `FaultScript` (every fault kind that is a server's to produce: status, latency, truncation,
  token expiry, header sets, host failover through a second listener) and `ThroughputTrace`
  shaping applied at the socket. This is the `HttpTransportConformance` carve-out widened to
  every test, and the ADR argues why there is no narrower option.
- **Time is real.** No test asserts a millisecond; assertions are on counts, order and typed
  outcomes. Goldens are structural.
- **What needs a device is listed, not faked**: FairPlay, background download resumption across
  process death, tvOS display criteria, HDCP obscuring, MoQ over QUIC. The device lab (§6) runs
  them, and `check` covers the logic around each with the platform call stubbed at one
  package-scoped protocol per feature.
- **The shared documents are tested on both platforms**: the schema, the trace grammar, the
  corpus register, the doctor document, and the compatibility table.

## 6. Device lab

`devicelab/lab` gains `--platform ios|tvos`: boots or adopts a simulator or a device through
`xcrun simctl` and `devicectl`, builds the demo with `xcodebuild`, installs, confirms playback is
advancing by reading the demo's Now Playing state, and captures an `xctrace` recording as the
Perfetto analog. The memory-guard note in `device-runs-and-host-memory` applies doubly, since a
simulator and an emulator together will not fit.

**Built for iOS by #435** (`devicelab/README.md`, *iOS*; tvOS is not reached yet). Two things differ
from the sketch above: the readiness signal is the demo's own position lines rather than Now Playing,
which a shell cannot read on a phone, and the recording is the Activity Monitor template over every
process, because xctrace cannot attach to a process `devicectl`'s console launched.

## 7. Phases

Effort assumes one engineer part-time, as `PRD.md` Part 4 does. Each phase ends on something
demonstrable, and none ends on a number moving.

| Phase | Scope | Exit criteria |
| --- | --- | --- |
| **i0 — Scaffold** (1 wk) | A root `Package.swift` over `ios/` with empty products, macOS CI job, swift-format, the `ios/check` script running the API digester against its baselines, `verifyModulePhaseRule` reading `Package.swift`, ADR-0020 to ADR-0022 (accepted ahead of the scaffold, #396), a platform column in `docs/modules.md` and `docs/compatibility.md` | Green CI; empty products resolve from a local path and by git URL from a scratch package |
| **i1 — Core + demo** (4 wks) | `SuperPlayer` over `AVPlayer`, `MediaRequest`, profiles, snapshot, lifecycle, `PlaybackSession`, resolver, `PlayerPool`. The frame-output seam of §3.11 (ADR-0022) with a pass-through effect. SwiftUI demo plays public HLS on iPhone | Demo plays, with and without the frame output; the pass-through effect drops no frame that `AVPlayerLayer` would have shown, counted on a device; the observed-KVO register test; `api/` baselines recorded |
| **i2 — Telemetry + testkit** (3 wks) | Vocabulary, collector, delivery, `OSLogSink`, trace recorder; testkit's loopback origin, synthetic HLS in Swift with the cross-platform fixture equality check, faults, shaping; the CMCD spike and its written finding; benchmark baseline on the simulator | Baseline report committed; schema document carries every iOS derivation |
| **i3 — Policy** (2 wks) | Policy boundary, both bindings, static profiles, adaptive buffer and selection halves, per-transport memory over the access log, reapplication | Adaptive arm's report per profile committed, whatever it says |
| **i4 — Preload** (2 wks) | `PreloadCoordinator` over early items and `preroll`, memory guard, Low Data Mode rule, feed demo | TTFF in the feed measured; memory flat over 200 rows in `xctrace` |
| **i5 — Resilience** (2 wks) | Taxonomy, classifier, rungs 4–6, header refresh by re-adoption, licence budget plumbing, fault sweep, rung register | Every origin fault recovers or ends named; zero unclassified |
| **i6 — FairPlay + offline** (4 wks) | Key session, our licence exchange, persistable keys, `OfflineLicenceStore`, downloads over `AVAssetDownloadURLSession`, selection, constraints, licence binding, removal | Device: acquire, play offline, renew, release; a download survives process death and network loss on a device |
| **i7 — tvOS** (2 wks) | tvOS products and demo, display criteria, HDCP observation, `AVPlayerViewController` recipe | Runs on Apple TV; display criteria verified on a device |
| **i8 — Diagnostics + docs** (3 wks) | HLS doctor with its lint parser, delivery pathologies, postmortem, bundle, HUD; `docs/ios.md` for the adopter; every shared document's test running on both platforms | Every HLS corpus entry named at `SEVERE`, silent at `BENIGN`; docs tests green on both |
| **i9 — Realtime + MoQ** (4 wks) | Seam, sample-buffer engine, formats, mirrored configuration conversion, conformance suite, MoQ bridge over UniFFI Swift bindings, statistics, demo screen | Conformance passes over the fake; a broadcast plays on a device; WHEP contingent on Phase 12's finding |
| **i10 — Tuning** (open) | Profiles and the buffer policy against the matrix; peak RSS and battery from a device | As Phase 11's |

About 27 weeks before tuning, against Android's roughly 20 for the same ground, the difference being
i6's device-only testing and i2's harness. Phases i0–i5 are a complete library on their own, as
0–5 are.

## 8. What was decided before i0

These changed the plan materially and were the user's rather than a careful colleague's. Each is now
decided, the way the plan assumed, and recorded where it binds (#396).

Where the code lives was never on this list: §2 decides it, one repository with the manifest at its
root (ADR-0020 rule 10). That decision leans on the first item below, since a second version line is
the one thing that would argue for a second repository.

1. **Lockstep versioning** with the Android modules under one `release` command, under `0.x`.
   ADR-0020 rule 12, with the addendum at ADR-0017 rules 1 and 2.
2. **tvOS in scope from i0**, products declared and a CI destination added, since a product added
   later is a minor on the digester's reading. ADR-0020 rule 9.
3. **`AVPlayerViewController` over custom controls** on tvOS, and no `TvPlaybackControls` port.
   ADR-0020 rule 6.
4. **A negative CMCD spike finding closes CMCD on iOS**, rather than opening a custom-scheme
   resource-loader experiment. ADR-0020 rule 7.
5. **The frame-output seam in i1** (§3.11), at one added week, with the Android half landing as its
   own issue (#406) against ADR-0022 so that the two platforms share one interface.
