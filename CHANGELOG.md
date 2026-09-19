# Changelog

Every notable change to SuperPlayer, for someone deciding whether to take a new version.

The format is [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the versions are
[Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html) as
[ADR-0017](docs/adr/0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
reads it here: one version for all thirteen published modules, moved in lockstep (rule 1), with the
tracked API surface in `<module>/api/<module>.api` as the arbiter of how far it moves (rule 2). A
behaviour change that moves no declaration still moves the version, and this file is where that
judgement is written down rather than made silently (rule 3).

`./gradlew verifyVersion` holds this file and `gradle/libs.versions.toml` to each other.

## [Unreleased]

**Nothing has been released.** The catalog reads `0.1.0-SNAPSHOT`, which under ADR-0017 rule 8
means unreleased and is the absence of a promise rather than a promise kept: an artefact resolved
from it may change under its own coordinates without notice. There are no dated headings below
because there is nothing to date, and inventing a `0.1.0` row for work that never shipped would be
the first lie in a file whose only job is to be believed.

What follows is the ten functional phases of `PRD.md` Part 4, at the granularity they were planned,
built and measured in. That is the unit this repository ships work in — each phase ends on
something demonstrable, and an adopter taking the first release is taking all ten at once — so a
per-commit back-fill of several hundred commits would be longer, later and no more useful. Phase 11
is tuning and is open.

### Added

- **Phase 0 — Scaffold.** Thirteen library modules under one Gradle build with the convention
  plugins in an included `build-logic`, the version catalog as the single source of every
  dependency version, the tracked API surface and its checks, and the clean-room rules.
- **Phase 1 — Core and demo.** `SuperPlayer`, a Media3 `Player` built by `SuperPlayer.Builder`;
  `MediaRequest`, which names content by a stable `contentId` rather than a URL; `PlaybackProfile`;
  `PlaybackSnapshot`; `PlayerPool`; `PlaybackSession` and `PlaybackService`; the platform lifecycle
  rules; and `TransferChain`, the one place the loading chain is composed.
- **Phase 2 — Telemetry and testkit.** `TelemetrySink` and the sealed `TelemetryEvent` vocabulary in
  core with `QoeCollector` beside it, the whole CTA-2066 schema documented in
  `docs/telemetry-schema.md`; CMCD (CTA-5004) joined to telemetry by one session id; bounded,
  at-most-once delivery; `SessionTraceRecorder` and the golden traces; and `PlaybackHarness`,
  `superplayer-testmedia` and the benchmark's reproducible baseline report.
- **Phase 3 — ABR and buffering.** `BandwidthOracle`, `AdaptiveBufferPolicy` and
  `NetworkAwareTrackSelection`, all three behind `PlaybackPolicy` and reached through
  `AdaptivePolicy.forProfile`; the per-transport estimate memory; and the QoE regression gate in
  `check`.
- **Phase 4 — Preload and cache.** `ContentKeyedCache` with content-keyed keys and pinning;
  `PreloadCoordinator` over a `PlayerPool`, with decoder warm-up and the memory and Data Saver
  platform rules; and the 200-row feed demo.
- **Phase 5 — Resilience.** `FailureClass` and `ErrorClassifier`, the one place a failure acquires a
  meaning; the six-rung fallback ladder; retry budgets, backoff and jitter; token refresh through
  `HeaderProvider`; and `SuperPlayerError`, the typed error a session ends on.
- **Phase 6 — DRM.** Widevine playback on both protocols, licence loads over the player's own chain
  and budget, provisioning, the security-level ladder, key rotation, secure-surface handling, and
  the offline licence lifecycle behind `OfflineLicences`.
- **Phase 7 — Offline downloads.** `Downloads` over the cache the consumer opened, track and
  language selection, process-death and network resumption, the `WorkManager` schedule and its
  conditions, a full disk failing one item, protected downloads carrying their licence, and
  `DownloadsService`.
- **Phase 8 — TV.** `TvOutput`: frame-rate matching, display hotplug re-selection, audio-capability
  re-selection, tunneling as a decision half, and `TvPlaybackControls` for Compose for TV.
- **Phase 9 — Diagnostics and docs.** `MediaSourceDoctor`, which names twenty pathologies over the
  chain a player would load through; the postmortem reading; `SessionBundle`; the debug HUD; and
  the documents in `docs/`.
- **Phase 10 — HTTP stack.** `HttpTransport` and `HttpStack` at the bottom of all four chains, the
  five obligations carried by `HttpTransportConformance` in `superplayer-testkit`, and
  `docs/http-transport.md`.

### Known limitations

- Phase 11 — tuning — is open: the adaptive policy's win over the static profile, the feed demo's
  p50 TTFF target, and peak RSS and battery from a device are measured but not met. `PRD.md` Part 4
  and `benchmark/phase3/` carry the verdicts.
- The library is `0.x` and, under ADR-0017 rule 7, a minor release may remove or change public
  declarations. What takes it to `1.0.0` is listed in that rule.
