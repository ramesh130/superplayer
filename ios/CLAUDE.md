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
| `ios/MoQ/` | `SuperPlayerMoQ` (#434), a package of its own rather than a root product, so it stays unpublished; over `third-party/moq/swift/`'s committed bindings |
| `ios/Demo/` | the demo (#407): a hand-written Xcode project resolving the package **by path**, one target for iOS and tvOS, built by `ios/check` and run by hand — `ios/Demo/README.md`; its `FeedScreen` (#418) is the pool-and-coordinator recipe |
| `ios/hunt-leaks` | the leak hunt (#418), `huntLeaks`' analog: an `xctrace` Leaks recording over the feed scrolling itself on a simulator, **not** in `ios/check` — `ios/Demo/README.md`, *The feed and the leak hunt* |
| `ios/.swift-format` | the format's configuration: four-space indent and 100 columns, as the Kotlin half |

**Every product was empty until its phase.** i0 (#397) was the scaffold alone: twelve products, a
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
`LifecyclePlatform`, and its signals in the observed register. Since #494 the system platform
performs the session's activations and deactivations on a serial queue of its own, in the order
asked, because `setActive` blocks and the system reports it on the main thread.
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
and says so in `frameOutputOffReason`; protection is `Configuration.drm` set at all (#422). The link
asks for twice the content's frame rate (`DisplayLinkPace`, #491), because one at the content's own
rate skipped a frame whenever the display's clock drifted across a frame boundary; on an iPhone that
needs the app's `CADisableMinimumFrameDurationOnPhone`, which the demo's `Info.plist` declares.
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
core's `package` protocol `PlaybackEngine` — the view hosts either, and `SharedLifecycle` counts both.
Since #461 they share the rest. `PlaybackSession` holds an engine, so `session.player` is optional
and `SuperPlayerRealtime` adds `PlaybackSession(player:)` for a realtime player. A realtime session
publishes a live stream and draws no seek command. `LifecycleBinding` acts on a `LifecycleTarget`,
which is a `SuperPlayer`'s `AVPlayer` or the realtime player itself. `QoeCollector` measures a stream
through the `package` `RealtimeMeasurable`, handed by `RealtimePlayer.Configuration.telemetry`. There
the engine reports a state (playing, starved, waiting, paused), reading starvation off the
synchronizer's clock every 100 ms with a margin of one reading, and the collector derives the events. Picture in Picture from the
display layer answers for a timeline with no ends. #340's observed bytes are `ios/Tests/Fixtures/realtime/observed-bytes.tsv`,
read by both platforms' tests.
#414 is the benchmark: `SessionMetrics` and `QoeScore` in `SuperPlayerTelemetry`, the matrix in
`ios/Benchmark/` as a target of the manifest and not a product (phase 2 in `docs/modules.md`),
run by `ios/Benchmark/bench` and never by `ios/check`, which runs only its agreement and honesty
tests. `ios/Benchmark/README.md` is its manual, and its *What the simulator taught the harness* is
worth reading before playing a ladder anywhere: an `AVPlayer` whose video nobody consumes never
climbs one.
#432 mirrored the codec configuration and made it several tracks. `CodecConfigurationRecords.swift`
runs the Kotlin conversion **the other way**: an `avcC`/`hvcC` is parsed for its NAL length width and
passed into the description's atom untouched, its samples walked at that width; in-band parameter sets
are lifted into a description by `CMVideoFormatDescriptionCreateFromH264ParameterSets` (or HEVC's) and
Annex-B samples re-framed to four-byte lengths — `TrackFraming` per track, with the same refusals. The
engine anchors every track on the first frame of any, reconciles one more than thirty seconds off as an
epoch of its own, and ends the stream on core's `RealtimeTrackStalledError` past the five-second late
and three-second stalled bounds, **in media time against the leading track**. An AAC magic cookie is the
`ES_Descriptor` around the AudioSpecificConfig, never the bare config, which an audio renderer takes and
then silently fails to decode.
#433 ported `FrameSourceConformance` into `SuperPlayerTestkit`, check for check: the Kotlin suite's
eleven, a recording sink and no socket, a thrown `FrameSourceConformanceError` naming no test
framework, and its checks a `CaseIterable` behind `verify(_:)` so the register needs no reflection.
Obligation 8 is read off memory, since `Data` is a value: only a `bytesNoCopy` payload can share it
(`docs/testing.md`, *The conformance suite on iOS*).
#417 put preload over the pool: `SuperPlayerPreload`'s `PreloadCoordinator(pool:)`, attached through
core's `package` `PoolAttachment` before the pool's first player, which a pooled player asks for a
`WarmStart` at adoption. Two AVFoundation facts shape it, both counted at the origin: an asset asked
for its `duration` loads its playlists with no player and an item made on it later fetches neither
again, while an item buffers only on a player and is that player's for life — so every prefetched
row is an asset, and at a loaded depth the nearest rows are held on idle pooled players
(`SuperPlayer.holdWarm`) and, at `decoderWarmed`, prerolled. The memory guard and the Low Data Mode
rule are `PlatformRules.swift`, and what the coordinator observes is its own register,
`PreloadSignals.swift`, counted process-wide.
#419 is the first of i5: `SuperPlayerResilience` holds `FailureClass` — the Kotlin taxonomy as a
nested enum, every leaf with its stable name, three answers, category and message key — and
`ErrorClassifier`, the one `switch` that names a failure, over the `NSError` chain (cause first), the
HTTP status AVFoundation reports as a `CoreMediaErrorDomain` code, and the error log's last entry,
whose URI tells a refused segment from a refused playlist (no entry is the multivariant playlist; an
entry with no URI is a segment AVFoundation retried — both observed, and pinned by
`TypedErrorPlaybackTests`). `Configuration.resilience = Resilience.standard()` fills core's `package`
`FailureClassifying`; core then observes each item's failure (two `resilienceItem` signals in the
register, none without it — `ResiliencePaysNothingTests`) and delivers a `SuperPlayerError` on
`player.errors`, the facade's Combine publisher, with `playerError` the last one and
`player.classify(_:)` the answer `QoeCollector` reads `classification` from. The same publisher
carries core's `UnplayableSourceSchemeError`, refusing on every player a source whose scheme
`AVPlayer` cannot load and naming `RealtimePlayer` (ADR-0020 rule 11's #419 addendum).
#420 put the rungs above the typed error on the same seam, and `FailureClassifying` now makes one
`PlayerStateRungs` per player (`PlayerStateRungs.swift`, the Kotlin `PlayerStateLadder`).
`SuperPlayerResilience`'s `PlayerStateLadder` decides and core performs, asking only once its own
half holds. There are three repairs, all re-adoptions that are not an adopt, with the same identity,
the same measurement session and the position held. The credential repair goes first:
`Resilience.standard(headers:)` takes a `HeaderProvider`, asked once per refused request on a 401
or 403, whose headers ride every later asset through `AVURLAssetHTTPHeaderFieldsKey`, an option the
SDK does not declare. It costs every playlist again. Rung 4 is the next of `MediaRequest.sources`.
Rung 5 is the item rebuilt on the kept `AVPlayer` after `mediaServicesWereResetNotification`, at
most `SuperPlayer.maxDecoderRecreations` (two) times at one position. A repaired failure reaches no
consumer, and telemetry hears it through `TelemetryCollector.contentReopened(after:)`. A re-adoption
resumes at the rate the viewer last asked for (`resilienceRateChanged`), because a failed item drops
the rate to zero with no reason given. The new asset changes the CMCD `sid` and not the session.
#421 is i5's exit, with one fault left open (below). A frozen live playlist fails nothing in AVFoundation, which waits at the edge, so on a
player configured with resilience core watches for it (`StaleLivePlaylistDetection.swift`, ADR-0011
rule 4's #421 addendum) and ends the session on `StaleLivePlaylistError`, the Kotlin exception's
fields, with `SuperPlayerError.likelyCause` read off it and the ending told to telemetry through
`TelemetryCollector.sessionFailed(_:)`. "Unchanged" is the item's live edge standing
still past 2.5 target durations at a media-playlist answer from iOS 18's request events, with the
target read from the longest segment. Below 18 it is AVFoundation's third -12888 log entry, and the
fields the log cannot carry are nil. `FaultSweepTests` plays every `FaultScript.Kind`, a
`CaseIterable` that an exhaustive `switch` holds the sweep to in place of the Kotlin reflection.
`FallbackRungCoverageTests` names the forcing test per rung and says why rungs 1–3 have none. One
fault still stalls: a segment that never answers inside AVFoundation's wait (`docs/testing.md`, *The
fault sweep on iOS*).
#422 is the first of i6: `SuperPlayerDRM`'s `Drm.fairPlay(FairPlayConfig(certificateURL:licenceURL:))`
fills `Configuration.drm` — core's public marker `PlaybackDrm` over the `package` `ContentProtecting`,
which makes one `AVContentKeySession` per player with every asset the facade plays a recipient
(`Protection.swift`). The exchange is the library's own, `LicenceExchange.swift`: the certificate once
per player, the SPC `POST`ed raw to the configured server only — never an address the `skd://` URI
names — every failure asked again on `RetryPolicy.licence` with `Backoff`'s equal jitter (ported
there, and core's `package` since #424 gave it a second caller), and one 401 or 403 per request
offered to the `HeaderProvider`, which moved to core so that neither optional product depends on the
other. A failure reaches core's ladder as a
`LicenceExchangeError` the classifier maps (`Drm.LicenceAcquisition`, or `Drm.SystemError` for a key
request the device will not compose); an acquisition reaches the collector through
`TelemetryCollector.licenceAcquisitionEnded`, a rotation or renewal emitting nothing; and the collector
reports `OutputObscuredChanged` from `isOutputObscuredDueToInsufficientExternalProtection` on a
protected player alone. **The simulator has no FairPlay**: the key session is stood in at the one
`package` protocol `FairPlayKeySession`, and the licence server is testkit's `.licenceServer()`
(`docs/testing.md`, *FairPlay on iOS*).
#423 added the offline store to `SuperPlayerDRM`: `OfflineLicences.store(directory:)` opens an
`OfflineLicenceStore` — core's public marker `LicenceStore` over the `package` `StoredLicences`, so the
download products (#424, #425) take it depending on core alone — whose one file is SQLite through the
system `libsqlite3` (`OfflineLicenceIndex.swift`, `temp_store = MEMORY` so nothing lands outside the
directory), holding per `contentId` the persistable keys by key identifier and two deadlines;
`licence(for:)` reads them with no player and no network. `store.over(player)` acquires, renews and
releases through the player's key session and exchange — a persistable request asked for by
identifier, told apart from the content's own by the verb waiting on it, and a release the key's proof
of deletion `POST`ed — and `Drm.fairPlay(_:licence:)` plays from the stored keys asking nobody,
refusing an expired licence with core's `OfflineLicenceExpiredError`. **FairPlay reports neither
duration to the client**, so the two are what the key server granted, stated by the app at `acquire`
and `renew`; that is the one departure from the Kotlin store. **tvOS has no persistable keys** in its
SDK, so there the verbs fail typed at once.
#424 is `SuperPlayerOffline`: `Downloads(configuration:)` — a directory the consumer named and a
background session identifier that is the store's — reads a request's manifest with an `AVURLAsset`,
refuses live content (`DownloadRefusal.liveContent`, off an indefinite duration), chooses one variant
under `PlaybackDecision.download` and the languages asked for that the content carries
(`DownloadSelection.swift`), and hands a task the plan; its index is SQLite beside the packages
(`DownloadIndex.swift`, #423's system `libsqlite3`), a finished `.movpkg` is moved into the directory
and *is* the pin, and a failed task is started again on `RetryPolicy.segment` with `Backoff` — moved
to core as `package` for it — and named by `SuperPlayerResilience` through core's `package`
`DownloadFailureClassifying`. A player opens downloaded content through core's public marker
`OfflineContent` (`Configuration.offlineContent`) over the `package` `OfflineAssets`, asked first at
adoption. **The simulator test host cannot run an `AVAssetDownloadTask`** (a background session in
`xctest` fails every task with `NSURLErrorDomain` -1 and sends nothing), so the transfer is stood in
at the one `package` protocol `AssetDownloading`; `SystemAssetDownloads` is the shipped one and none
of it runs under `check` (`docs/testing.md`, *Downloads on iOS*; #436 is its device run). The app
wires one thing, `handleEventsForBackgroundURLSession`, passed to the store. tvOS's SDK has no
`AVAssetDownloadURLSession`, so there the store refuses to open, typed.
#425 binds a protected download's licence to it: `Configuration.protection` is a `DownloadProtection`
— core's `PlaybackDrm` and `LicenceStore` plus the two durations FairPlay does not report — and core's
`package` `DownloadProtecting`, which `SuperPlayerDRM`'s value fills, makes the store's
`DownloadLicensing`: a key session of the store's own, nothing ever its recipient, asked for
persistable keys by identifier. Protection is what the multivariant playlist's FairPlay
`EXT-X-SESSION-KEY`s declare (`SessionKeys`), read as text once more because an `AVURLAsset` does not
say; a media playlist's `EXT-X-KEY` alone downloads with no licence, the Kotlin trap kept. The key is
acquired after the manifest and before the plan reaches the index or the downloader, on one serial
licence queue; a refusal fails the item named with nothing written; `DownloadItem.licence` reads the
store whenever an item is read. `remove` owes the release at once (the licence store's
`offline_licence_owed` table, so no player is given it), deletes the key on the device, and tells the
server when `NetworkWatching` — `NWPathMonitor`, a second `package` seam — reports the store's network.
#426 is i7: `SuperPlayerTV`'s `TvOutput.standard()` fills core's `Configuration.output` — the
public marker `PlaybackOutput` over the `package` `VideoOutputting`, beside `frameOutput` — with one
`DisplayCriteriaMatching` per player, which core tells each item it makes current (`nil` on a
recycle) and releases with the player. It asks the key window's `AVDisplayManager` for the asset's
`preferredDisplayCriteria`, withdraws at the item's end (asking again if it is played again), on a
recycle and on release, never between two items, and only where its request is the one in force
(the display is one per process); a late answer is dropped. The display's mode — a switch beginning
and ending, the viewer's *Match Content* setting — reaches `TelemetryCollector.displayModeChanged` and
the event `DisplayModeChanged`, reported and never acted on. The platform is the one `package`
protocol `DisplayManaging`; the system's, `SystemDisplayManager`, is tvOS-only and runs under no test
(`docs/testing.md`, *A television on tvOS*). On iOS `standard()` makes nothing. The demo's `TvScreen`
is the recipe on `AVPlayerViewController`'s system controls, and the app opens on it on tvOS.
#434 is `SuperPlayerMoQ`, and it is **not a product of the root manifest**: it is a package of its
own, `ios/MoQ/Package.swift`, because a root product is resolvable by anyone who names this
repository's URL and the Kotlin module it ports is deliberately unpublished — the SwiftPM analog of
`LOCAL_ONLY`, which `CONTRIBUTING.md`'s #364 amendment reads for iOS. It depends on the root package
by path, `ios/check` tests it in a step of its own on the same simulator, it tracks no API baseline,
and `ios/Demo/` resolves it by path for `MoqScreen`. Its native half is `third-party/moq/swift/`: the
`MoqFFI` package — UniFFI's generated Swift, compiled in Swift 5 mode as generated code — over an
XCFramework built from the same `moq-dev/moq` commit as `third-party/moq/m2` and **committed**, since
CI's macOS job has no Rust toolchain; `build-xcframework.sh` writes both, and
`third-party/moq/README.md`'s *The Swift bindings* is the manual, with the size (58 MB on disk, about
20 MB in Git) and why it is two iOS slices and no tvOS. The product is the Kotlin module ported whole:
`MoqCatalogTracks`, `MoqFrameSource(url:)` over the internal `MoqRelay` seam with `UniffiMoqRelay`
the one implementation touching the bindings (its `async` calls made blocking on the source's own
threads), a connect thread and a pump thread per track, obligations 2, 3 and 9 made true, `cancel()`
joining the pumps and the poller behind a delivery barrier but never the connect thread (the main actor
calls it, and a handshake cannot be interrupted), and `statistics()` polled each second into `MoqSessionStatistics` beside
`MoqUpstreamMediaLoss.notInstrumented`. One departure is forced: the bindings carry a catalog's
renditions as a Swift `Dictionary` — and the Rust side already collects them into a `HashMap` — so
there is no declaration order, and the rendition taken per kind is the one whose **name sorts
first**. What `ios/check` runs is `FrameSourceConformance` in both of ADR-0018 rule 4's branches
over `ScriptedMoqRelay`, which proves the bridge and nothing about MoQ, and `MoqFfiLinkageTests`,
which calls across the FFI into the simulator slice an app links. A broadcast on a device is #438's:
`ios/Demo/SuperPlayerDemoUITests`' XCUITest, run by `devicelab/lab run moq-smoke --platform ios`,
which played `cdn.moq.dev/demo/bbb.hang` on an iPhone and found #367's two address defects there as on
Android (`docs/testing.md`, *The first real MoQ session on an iPhone*). There is no
`SuperPlayerCache`, since its content is the offline store's.
#416 is i3's adaptive half, `SuperPlayerABR`: `AdaptivePolicy.forProfile(profile)` composes
`AdaptiveSelectionPolicy` and `AdaptiveBufferPolicy`, the Kotlin pure functions with their constants,
and brings a `BandwidthOracle` per player through core's `package` seam `ThroughputSource.swift` —
`DecisionReapplication` hands it every access-log read, item change and transport change and asks it
for the estimate and the throughput trigger, where any other policy gets the item's own window. A
sample is **one access-log entry's `observedBitrate`**, revised while the entry is current, kept per
transport in the process's `EstimateMemory` with Android's cold defaults and aging. Two Kotlin
branches move start floors iOS has no knob for and so move nothing here; the heap cap applies to a
decided `preferredForwardBufferDuration` and never to Apple's automatic one; the cellular cap lands on
the expensive-network pair. `AdaptivePolicy.forProfile(_:rebufferCooldownMs:memory:)` is the
`package` seam `AdaptivePolicyPlaybackTests` shortens the cooldown through. The benchmark's arm (d) is
it, every session starting from a forgotten memory, and `benchmark/ios-phase3/` is its report.
#427 is the first of i8: `SuperPlayerDiagnostics`' `MediaSourceDoctor(configuration:)`, whose
`examine(_:)` is `async` and **not** `throws` — a playlist that cannot be fetched or read is a
`manifest-unreachable` or `manifest-unreadable` finding — and answers a `DiagnosticReport` of
`Finding`s (`Pathology`, `FindingSeverity`, magnitude, the corpus's citation and cause). It fetches
the multivariant playlist and every media playlist it names over an ephemeral `URLSession` of its own,
carrying what the configured `HeaderProvider` returns after a 401 or 403 as a player would, and no
segment. It reads them with **a lint of its own** (`PlaylistLint.swift`, ADR-0020 rule 8 and its #427
addendum): three refusals, the RFC's, and leniency everywhere else. The rules are the Kotlin ones with
the Kotlin thresholds (`HlsPathologies.swift`, `LadderPathologies.swift`), plus `hls-short-live-window`,
the HLS analog of core's `LiveWindowDepthCheck`, which lives in the doctor here. `CorpusRegisterTests`
is the register.
#428 added the delivery rules and the postmortem. `DeliveryPathologies.swift` is the Kotlin file rule for rule — a live playlist held
past its update bound, a token scoped to the manifest or dying inside the content, a wildcard origin
beside allowed credentials — reading the lowercased headers every playlist arrived with. The cache
rule **asks core's** `StalePlaylistJudgement.cachedPastTheUpdateBoundSeconds`, `package` for it, so
the doctor and a resilient player's stale-playlist watch are one comparison (ADR-0020 rule 8's #428
addendum), and only it may spend the one-segment probe, a `HEAD` carrying the credential. The
postmortem is `examine(_:classification:)`: the `SuperPlayerError` read onto
`DiagnosticReport.classification`, never re-derived, which made the report drop `Hashable` because the
error carries the engine's `NSError`.
#429 is i8's bundle and HUD. `SessionBundle(trace:)` is `docs/session-bundle.md`'s format and version
over the recorder's trace, its `capability` lines read whole from core's `package` `CapabilitySnapshot`
— platform and OS `major.minor`, `os_proc_available_memory()`, `VTIsHardwareDecodeSupported` per codec,
the display's short edge and `AVPlayer.eligibleForHDRPlayback` — so the seventh rule holds by what that
type carries (ADR-0015 rule 10's #429 addendum, which says the decoder and display fields pass the
readership test only as "what AVFoundation decided against"); it reads no player, since FairPlay has no
level for a protection line. `DebugHud(player:telemetry:)` is SwiftUI over a `SuperPlayer` or a stock
`AVPlayer`, its rows `HudFacts` re-read twice a second, its telemetry half the `DebugHudTelemetry`
sink, guarded by `#if DEBUG` and the profile's `get-task-allow` (`isDebugHudAvailable()`). `GoldenFile`
moved into testkit, throwing rather than naming XCTest; the demo's `DebugHudScreen` is the recipe.
#430 is i8's docs half and changed no library code. `docs/ios.md` is the adopter's guide. Every
section of `docs/media-source-doctor.md` carries a `- **Platforms:**` line, and `DoctorDocumentTests`
holds `Pathology` to the sections naming iOS and tvOS, as the Kotlin `DoctorDocumentTest` holds its
enum to the ones naming Android. `docs/compatibility.md`'s *Which Swift products are stable?* table
is held to `Package.swift` and `ios/MoQ/Package.swift` by `verifyCompatibilityDocument`, on Linux,
so **a new product needs its row there too**. `docs/testing.md`'s *Every shared document, on both
platforms* lists each shared document with its test on each side.

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
  never share a port or a count. It speaks `GET` and `HEAD` (and a `POST`, to `.licenceServer()`
  alone) over persistent HTTP/1.1 connections,
  single byte ranges, and RFC 8216's content types; `HttpExchange.swift` is the protocol as pure
  functions and `LoopbackOrigin`'s doc comment argues Network.framework over BSD sockets.
- **Time is real.** Wait with `harness.until`, whose bound is a ceiling and never an assertion; the
  synchronous form reads an `AVPlayer` on the caller's actor, the `@Sendable` async one can fetch.
  Never sleep and then assert.
- **`.liveHls()` publishes a segment every two seconds of real time** from the moment the origin
  starts, in a six-segment window under `live/` — the Kotlin `TestContent.liveHls()`'s stream and
  timing, on the host's clock. `.liveHls(windowSegmentCount:)` is the same stream with a shorter
  window, already slid, which is how the doctor is shown a window RFC 8216 §6.2.2 forbids (#427).
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
- **A trace's clock starts at the first media segment asked for** (#474), not at the origin's
  start, because a freshly booted simulator spends seconds between the playlists. And **a link
  slower than a stream never delivers it**: AVFoundation abandons a segment not whole within about a
  target duration and asks again from byte zero, so a session that has not started on such a link
  never does, and on 27.1 one stalled there stays stalled,
  on a stock player as on a `SuperPlayer` (`SlowLinkPlaybackTests`, `docs/testing.md`'s *The fault
  sweep on iOS*). Assert that a stall began, never that it ended.
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
ios/check                     # swift-format lint, simulator tests, tvOS build, demo build, API diff
ios/check --update-api        # the same, rewriting ios/api/*.baseline; commit the diff
ios/check --update-golden     # the same, rewriting ios/Tests/Fixtures/golden/*.trace; review and commit the diff
SP_SIMULATOR=mine ios/check   # test on a simulator of that name instead (created if absent)
ios/Benchmark/bench           # PRD.md §6's matrix on the simulator, ~2 h 40 min; NOT in ios/check (ios/Benchmark/README.md)
ios/hunt-leaks                # the leak hunt on a simulator; minutes, a network, and not a check
devicelab/lab run frames --platform ios   # i1's and i4's device exits on a phone (`feed`); devicelab/README.md, *iOS*
devicelab/lab run moq-smoke --platform ios # i9's: a MoQ broadcast over QUIC, the XCUITest; SP_MOQ_BROADCAST names one
xcrun swift-format format --in-place --recursive --configuration ios/.swift-format ios Package.swift
```

`ios/check` is a script rather than a Gradle task (ADR-0021), and the macOS CI job runs it and
nothing else, path-filtered to `ios/**`, `Package.swift`, `third-party/moq/swift/` and the shared
documents. The Linux job's
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
- **tvOS is built whole, and `SuperPlayerTVTests` runs on a tvOS simulator too** (#426), as
  `<SP_SIMULATOR>-tv` (`SP_TV_SIMULATOR`), made on the newest tvOS runtime. A host without one skips
  that run and says so; CI sets `SP_REQUIRE_TVOS=1`, so there an absence fails.
- **Every `.swift` file opens with `config/license-header.txt` as `//` lines** (in `Package.swift`,
  after the tools-version line). `./gradlew verifyLicenseHeader` derives the expected text from
  that file rather than keeping a copy, and checks it on Linux, since swift-format has no header
  rule; `ios/check` does not, so run the Gradle task too before pushing a new Swift file.
- **The `[MT] IDESchemeActionDeviceRecord ... invalidDigitCount` lines** an Xcode 27 `xcodebuild`
  prints against an iOS 27 runtime are Xcode's own noise and fail nothing.
