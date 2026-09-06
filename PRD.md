# SuperPlayer — Product Requirements

> **Thesis:** AndroidX Media3 (ExoPlayer) is an excellent *media engine*. It is not a *playback
> product*. The recurring failures teams report in production live in the gap between those two
> things: policy, resilience, observability, and lifecycle. SuperPlayer is that gap, shipped as a
> library — not a fork of Media3, and not another player.

This document states what SuperPlayer is for, what it must do, and in what order. It is the
product-level companion to the architectural decisions in [`docs/adr/`](docs/adr/); where the two
overlap, an ADR is the binding record and this document is the intent behind it.

---

## Part 0 — Ground rules

### 0.1 What this library is not

- **Not a fork of Media3.** SuperPlayer composes. Every extension point it uses is a public,
  documented Media3 interface — `LoadControl`, `TrackSelector`, `DataSource.Factory`,
  `DrmSessionManager`, `LoadErrorHandlingPolicy`, `AnalyticsListener`, `MediaSource.Factory`,
  `RenderersFactory`. An `@UnstableApi` surface that must be used is wrapped behind a SuperPlayer
  interface, with a compat shim and a test pinning the Media3 version.
  ([ADR-0001](docs/adr/0001-compose-dont-fork.md))
- **Not a new protocol stack.** HLS and DASH parsing is Media3's. Its parsers have met more
  real-world manifest pathology than a reimplementation ever will.
- **Not a local HTTP proxy.** ([ADR-0002](docs/adr/0002-no-local-http-proxy.md), and §2.3 below for
  the product argument.)

### 0.2 Clean-room constraint

SuperPlayer must be defensibly independent of any commercial player SDK. The binding rules live in
[`CONTRIBUTING.md`](CONTRIBUTING.md) — no proprietary material consulted, no mirroring of a
commercial SDK's API shape, Media3's own idiom as the tie-breaker, and a public `// spec:` or
`// ref:` citation on every non-obvious algorithm.

Two consequences are product requirements rather than contribution rules, and so are stated here:

- **Every performance number this project publishes is produced by its own harness (§6), against
  public test streams.** No measurement is carried in from a proprietary platform, and none is
  attributed to one.
- **The design is derived from public specifications and from measurement**, not from a codebase.
  Where the design looks clever, the citation is what makes it defensible.

General knowledge and skill are portable; specific implementations are not. That distinction is the
actual line, and §0.2 of `CONTRIBUTING.md` draws it in detail.

---

## Part 1 — The problem

These are the failures that recur across teams building playback on Media3. Each is stated as the
symptom teams report, its actual root cause, and where SuperPlayer addresses it.

| # | Symptom | Root cause | Module |
| --- | --- | --- | --- |
| F1 | "Works on WiFi, falls apart on mobile data" — over-aggressive quality, constant rebuffer. | One ABR/buffer policy for all transports. Media3's defaults target a *general* case, and `DefaultBandwidthMeter` throughput estimates lag a transport switch by seconds. | `abr`: network-class-aware policy, estimate reseeding on transport change |
| F2 | Platform-specific tuning is rediscovered per app; cross-platform layers have even less control. | No shared policy layer. Every team re-derives `DefaultLoadControl` constants from forum answers. | `core` profiles, plus an FFI-friendly facade |
| F3 | "Instant start" is a product expectation — prefetch, warm decoders, first-segment priority. | Time-to-first-frame is dominated by manifest RTT, the initial buffer floor, and codec init. Default config addresses none of them. | `preload`, `abr` startup policy |
| F4 | ABR "is an art"; DRM causing frame drops on low-end devices; background downloads draining battery. | Secure-decoder paths and codec limits are device-fragmented; the download stack is hand-rolled per app. | `abr` (capability gating), `drm`, `offline` |
| F5 | Sub-100ms start achieved with a local loopback proxy and manifest rewriting — then a second workaround (proxy-side ABR) to undo the "infinite bandwidth" illusion the first one created. | A real problem solved at the wrong layer. The proxy defeats the player's bandwidth estimation, then re-implements ABR outside the player, blind to buffer occupancy and track selection. | `preload`, solving it inside the player — §2.3 |
| F6 | Live and linear need dynamic buffer targets; fixed watermarks are wrong in both directions, and buffer caps must respect device memory. | `DefaultLoadControl` is static after construction. Low-latency live and VOD stability want opposite settings, and the right setting changes *within* a session. | `abr`: `AdaptiveLoadControl` |
| F7 | Mature in-house libraries converge on the same pieces: a custom `HttpDataSource`, player recycling in feeds, a disk cache keyed by content identity rather than URL, a bandwidth meter feeding initial track choice, QoE metrics on a dashboard. | The correct instincts, rebuilt from scratch each time. Cache-keyed-by-URL in particular is a silent, common bug: the same content at a different bitrate is a guaranteed cache miss. | `core` (player pool), `cache` (content-keyed), `telemetry` |
| F8 | QoE metric definitions differ per app, so results cannot be compared across apps, platforms, or vendors. | The industry has agreed definitions (CTA-2066); most apps invent their own. | `telemetry`, implementing the standard definitions |

---

## Part 2 — Architecture

### 2.1 Module layout

```
superplayer/
├─ superplayer-core          # SuperPlayer facade, PlaybackSession, config profiles, player pool
├─ superplayer-abr           # AdaptiveLoadControl, NetworkAwareTrackSelection, BandwidthOracle
├─ superplayer-preload       # PreloadCoordinator: first-segment prefetch + decoder warm-up
├─ superplayer-cache         # Content-keyed CacheDataSource, tiering, eviction policy
├─ superplayer-drm           # Widevine session management, provisioning, offline licenses, fallback
├─ superplayer-resilience    # ErrorClassifier, RetryPolicy, FallbackLadder (CDN/variant/protocol)
├─ superplayer-telemetry     # QoE collector (CTA-2066), CMCD emitter, pluggable sinks
├─ superplayer-offline       # DownloadManager wrapper, WorkManager constraints, battery policy
├─ superplayer-diagnostics   # MediaSourceDoctor, session trace bundle, on-device debug HUD
├─ superplayer-tv            # Connected TV: display capability, Leanback + Compose-for-TV surfaces
├─ superplayer-ui            # Optional Compose player surface (thin; an app may bring its own)
├─ superplayer-testkit       # Fault injection, network shaping, fake manifests, golden traces
└─ demo/                     # Phone + TV demo app, benchmark runner
```

Only `core` is required. Each module is additive, and each declares the Media3 version range it is
compatible with. Dependency direction and the phase rule are in [`docs/modules.md`](docs/modules.md).

### 2.2 The facade

```kotlin
val player = SuperPlayer.Builder(context)
    .setProfile(PlaybackProfile.VIDEO_ON_DEMAND)      // or LIVE_LINEAR, SHORT_FORM, DATA_SAVER
    .setDrm(WidevineConfig(licenseUrl, headerProvider = tokenProvider))
    .setTelemetry(TelemetrySink.composite(analytics, LogcatSink))
    .setCache(CachePolicy.contentKeyed(maxBytes = CachePolicy.deviceAware()))
    .build()

player.setMediaRequest(
    MediaRequest(
        contentId = "urn:content:12345",   // cache key — NOT the URL. Closes F7.
        sources = listOf(hlsUrl, dashUrl), // ordered fallback ladder
        startPosition = StartPosition.ResumeFromLastKnown,
    )
)
```

`SuperPlayer` implements Media3's `Player` interface by delegation, so an existing `PlayerView`,
`MediaSession`, or Compose surface takes it unchanged
([ADR-0003](docs/adr/0003-implement-player-by-delegation.md)).

**The escape hatch is mandatory.** `player.exoPlayer` is public. A playback library that hides
ExoPlayer is a library that cannot be debugged during an incident.

### 2.3 The proxy question

A published, widely-copied approach reaches a very low time-to-first-frame by running a loopback
HTTP proxy that rewrites HLS manifests to point at pre-cached segments. It works, and the write-ups
document its own defect: the proxy makes RTT ≈ 0, the player concludes bandwidth is effectively
infinite, requests the top rendition, and chokes the real network — so ABR must be reimplemented
*inside the proxy*, where it cannot see buffer occupancy, track selection state, or decoder
capability.

SuperPlayer reaches the same target without that trade:

| Loopback-proxy approach | SuperPlayer approach |
| --- | --- |
| Rewrite manifests to local URLs | `CacheDataSource` already serves cache hits with zero network — nothing to rewrite |
| Bandwidth estimate corrupted by localhost RTT | `BandwidthOracle` excludes cache-hit reads from throughput samples |
| ABR reimplemented in the proxy | ABR stays in `TrackSelector`, where buffer level and codec caps are visible |
| Manual dual-stage low→high quality switching | Media3's `AdaptiveTrackSelection` already separates startup track logic from `minDurationForQualityIncreaseMs`; SuperPlayer tunes both per profile |
| An extra process or thread, TLS termination, an HTTP server to keep alive | No extra surface, no localhost port, no security review |
| Breaks under DRM (license binding to origin), breaks CMCD, breaks CDN token auth | All of those keep working |

**SuperPlayer adopts the goals — first-segment prefetch, warm decoders, adaptive low-quality
starts — and implements them at supported extension points.** Same outcome, a fraction of the
machinery, and it survives contact with DRM. ([ADR-0002](docs/adr/0002-no-local-http-proxy.md))

Concretely, `superplayer-preload` builds on Media3's `DefaultPreloadManager` / `PreloadMediaSource`
to hold N off-screen items in a prepared or first-frame-rendered state, plus:

- **Priority-ordered first-segment fetch**, driven by feed scroll velocity and direction.
- **Decoder warm-up**, bounded by `MediaCodecInfo` concurrent-instance limits read from
  `MediaCodecList` rather than a hardcoded N. On low-end devices this correctly degrades to 1.
- **Memory guard** — total preload buffer capped against `ActivityManager.MemoryInfo` and
  `isLowRamDevice`, released on `onTrimMemory` (F6's device-constraint requirement).
- **Prefetch budget** — never on a metered network with data-saver active, honoring
  `ConnectivityManager.getRestrictBackgroundStatus()`. Prefetching on a capped plan is a support
  ticket waiting to happen.

### 2.4 The transfer chain — composed once, in core

Everything below `MediaSource` reaches the network through one `DataSource.Factory` chain, and five
modules want a piece of it:

| Module | What it inserts | Section |
| --- | --- | --- |
| `cache` | content-keyed `CacheDataSource` | §3.5 |
| `abr` | `BandwidthOracle` reading transfers, cache hits excluded from samples | §3.1 |
| `telemetry` | CMCD (CTA-5004) emission | §3.4 |
| `resilience` | `HeaderProvider` re-invoked on 401/403 for CDN token refresh | §3.3 |
| `offline` | the download stack, sharing the same cache and key policy | §3.5 |

**Core assembles that chain in exactly one internal place, and each later phase inserts itself
there.** This is a structural decision with no public API attached, and it belongs to no single
module, which is why it is stated here.

The reason to fix it in Phase 1 is §2.3's argument arriving through a different door. Composition
order decides whether the cache sits inside or outside measurement — that is, whether a cache hit
becomes a throughput sample and the estimate starts describing the disk instead of the network.
That is the same corruption the loopback proxy causes, and if four phases each reach for
`ExoPlayer.Builder` independently, whichever is written last decides it by accident. The rule that
keeps it honest: **every wrapper preserves `TransferListener` reporting end to end.** A wrapper
that swallows transfer events blinds `DefaultBandwidthMeter`, with nothing in the logs.

One constraint is worth recording because it is easy to assume otherwise: `ExoPlayer` has no runtime
media-source-factory setter, only `setMediaSource`/`setMediaSources`. The chain is fixed when the
engine is constructed, so it belongs in `SuperPlayer.Builder.build()`, and the `player.exoPlayer`
escape hatch cannot substitute for it.

**Which HTTP transport sits at the bottom of the chain is a separate, public question, and it is
deliberately left open here.** Media3 offers `DefaultHttpDataSource` and `HttpEngineDataSource` in
`media3-datasource`, plus OkHttp, Cronet and Ktor in artifacts of their own — a list that grew twice
in the 1.x line, whose non-default members each need a client object the consumer supplies, and some
of whose artifacts sit outside the dependency licence policy in `CONTRIBUTING.md`. Every one of
those types is `@UnstableApi`, so none may appear in a public signature.
[ADR-0004](docs/adr/0004-select-the-http-stack-through-a-superplayer-type.md) records the shape the
answer should take — a SuperPlayer-owned `HttpStack` type, with non-default transports supplied by
optional modules so core stays Media3-only — and the question it still carries. Building the
internal seam now is what keeps that later decision from being a rewrite of five modules.

### 2.5 Policy behind an engine-agnostic boundary

All buffering and track-selection policy is decided behind `PlaybackPolicy`: observed
`PlaybackConditions` in, a `PlaybackDecision` out, in types that name no Media3 class. One file
turns a decision into Media3 configuration. A policy constant at a Media3 call site, in a listener,
or in a builder is a defect against this boundary whatever its value is worth.
([ADR-0005](docs/adr/0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md))

This is what lets §3.1's adaptive policy replace the shipped static one without touching a Media3
call site, and it is why `PlaybackProfile` names a use case rather than carrying numbers.

---

## Part 3 — Module requirements

### 3.1 `superplayer-abr` — buffering and quality policy

**Problem (F1, F6):** `DefaultLoadControl` constants are fixed at construction; ABR treats all
transports alike; a throughput estimate survives a WiFi→cellular handover and causes an immediate
stall.

**`AdaptiveLoadControl`** implements `LoadControl`, delegating to a `DefaultLoadControl` whose
targets are recomputed on state change.

- Inputs: transport class (`NetworkCapabilities`: WIFI / CELLULAR with `NR`/`LTE` subtype /
  ETHERNET for TV), throughput mean **and variance**, recent stall history, stream type
  (VOD / live / short-form), available heap, playback speed.
- Outputs: `minBufferMs`, `maxBufferMs`, `bufferForPlaybackMs`, `bufferForPlaybackAfterRebufferMs`,
  target back-buffer.
- Policy:
  - **Stable and high throughput → build a deep cushion** (VOD up to ~50s), so brief dips are
    coasted through.
  - **High variance → raise the *playback floor*, not the ceiling.** Variance, not mean, predicts
    stalls; most implementations look only at the mean.
  - **Live and linear → latency-priority mode.** Buffer sized to survive one segment download, with
    micro-speed correction (0.97–1.03×) to hold the live window — the standard low-latency
    technique, which Media3 exposes through `LivePlaybackSpeedControl`.
  - **After a rebuffer → hysteresis.** Raise `bufferForPlaybackAfterRebufferMs` and hold a quality
    ceiling for a cooldown. Never oscillate: "quality drops randomly" is usually oscillation rather
    than the drop itself.
  - **A memory-aware ceiling on every branch.**

**`BandwidthOracle`** wraps `DefaultBandwidthMeter`.

- **Per-network-type persisted estimates.** On transport change, reseed from that transport's stored
  EWMA rather than carrying the WiFi estimate onto cellular. This alone addresses most of F1.
- **Cache-hit exclusion** — reads served from `CacheDataSource` are not throughput samples (§2.3;
  the ordering that makes this possible is §2.4).
- **Sliding percentile, not only EWMA.** EWMA — Media3's default — is mean-tracking; keeping a
  windowed distribution lets ABR select on a conservative percentile during high variance and on
  the mean when stable. `ref:` the standard throughput-predictor critique in the MPC literature.
- **CMCD round-trip** — emit `br`, `bl`, `mtp`, `dl`, `su` (CTA-5004) so a CDN can see what the
  player sees. Media3 provides `CmcdConfiguration`; SuperPlayer wires it and makes it non-optional
  in the CDN-facing profiles.

**`NetworkAwareTrackSelection`** extends `AdaptiveTrackSelection`.

- Per-profile bitrate caps by transport — a cellular ceiling is not a WiFi ceiling. This is F1's
  explicit trade: slightly lower quality for significantly fewer interruptions.
- **Screen-size and display-capability gating** — never select 4K for a 1080p panel or a phone where
  it is invisible; on TV, read `Display.getMode()` and `HdrCapabilities` and select accordingly.
- **Device capability gating** — query `MediaCodecList` for the actually supported profile/level and
  concurrent secure-decoder limits before allowing a variant. F4's "DRM causing frame drops on
  low-end devices" is usually a variant the secure decoder cannot sustain, not the cost of DRM.
- **Startup track selection** — the first variant chosen from the persisted per-transport estimate
  rather than the manifest default: an adaptive low-quality start, then an upshift once the buffer
  target is met. `AdaptiveTrackSelection` already supports asymmetric up/down switch thresholds;
  SuperPlayer sets them per profile instead of accepting the defaults.

### 3.2 `superplayer-drm` — Widevine that survives the field

**Problem (F4):** DRM failures are opaque, device-specific, and frequently fatal when they should
not be.

- **Provisioning handling.** `MediaDrm` provisioning failures on older or unusual devices are a
  known, documented class of problem. Catch, retry with backoff, and — where the license server
  permits — fall back to a lower security level rather than failing the session.
- **Security-level ladder.** Probe `MediaDrm.getPropertyString("securityLevel")` and actual
  secure-decoder availability; if L1 provisioning or secure-surface allocation fails, request an L3
  license and a non-secure path **if and only if policy permits**. Policy is server-driven: the
  client never unilaterally downgrades protected content. In-house implementations typically get
  this wrong in one of two directions — failing hard, or downgrading silently.
- **Key rotation and renewal** for live, with `DrmSessionManager` reuse across items sharing a
  license policy, to avoid a license round-trip per asset in a playlist.
- **Offline licenses** via `OfflineLicenseHelper`: acquire, persist with expiry, proactively renew
  before `getLicenseDurationRemainingSec()` reaches zero, release on delete. The playback-versus-
  license expiry split is handled explicitly — a downloaded asset with a dead license must produce a
  *specific* message, not "playback error".
- **Secure surface discipline** — `SurfaceView` rather than `TextureView` on secure paths, correct
  `FLAG_SECURE`, and graceful behavior on display-capability change (HDMI hotplug on TV, screen
  recording attempts).
- **Error mapping** — every `DrmSession.DrmSessionException` maps to a `SuperPlayerError` with a
  cause class, a user-facing string key, and a boolean `isRetryable`. No
  `ERROR_CODE_DRM_UNSPECIFIED` reaches the UI.

### 3.3 `superplayer-resilience` — errors, retries, fallback

**Problem:** `PlaybackException` gives a code; it does not give a strategy.

- **`ErrorClassifier`** maps `PlaybackException`, `HttpDataSource` status, and manifest state into a
  taxonomy: `Transient.Network`, `Transient.CdnEdge` (a 403/404 on a segment while the manifest is
  fine — classic token expiry or edge miss), `Content.ManifestInvalid`, `Content.SegmentGap`,
  `Device.DecoderInit`, `Device.DecoderTransient`, `Drm.*`, `Fatal.Unsupported`.
- **`RetryPolicy`** implements `LoadErrorHandlingPolicy` — exponential backoff with jitter, per
  error class, with separate budgets for manifest, segment, and license loads. Jitter matters:
  synchronized retries from thousands of clients after a CDN blip are a self-inflicted DDoS.
- **`FallbackLadder`**, escalating only as far as needed:
  1. Retry the same URL (transient).
  2. Next CDN host or base URL (DASH `BaseURL` failover is in the spec; HLS via a configured mirror
     list).
  3. Blacklist the failing variant and continue at another bitrate — Media3's track blacklisting,
     driven by the classifier rather than by defaults.
  4. Next source in `MediaRequest.sources` (for example DASH → HLS).
  5. Recreate the decoder (`Device.DecoderTransient`; some devices require it after a surface or
     HDMI event).
  6. Surface a typed, actionable error.
- **Resume-position preservation across every rung.** A fallback that restarts from zero is worse
  than the error.
- **Token refresh hook** — `HeaderProvider` re-invoked on 401/403 before the retry, so expiring CDN
  tokens self-heal instead of ending the session. It is inserted into the transfer chain of §2.4,
  not into a stack this module assembles itself. This is among the most common real-world production
  failures and is almost never handled in-app.

### 3.4 `superplayer-telemetry` — QoE a data team can use

**Problem (F8):** every app invents its own metric definitions and then cannot compare across apps,
platforms, or vendors.

- Built on `AnalyticsListener` and `PlaybackStatsListener` — **not** polling.
- **Metric definitions follow CTA-2066**, so they mean what the industry means:
  - Time to first frame, with a documented start boundary — user intent, not `prepare()`.
  - Rebuffer count and rebuffer ratio, excluding seek-induced buffering, which is counted
    separately.
  - Startup failure rate, mid-stream failure rate.
  - Bitrate distribution, time-weighted average bitrate, upshift and downshift counts.
  - Seek latency, live-edge latency drift.
  - Exit-before-video-start.
- **A sessionized event schema** — stable, versioned, and documented in `docs/telemetry-schema.md` —
  with a sink interface so an app can route events to a product analytics service, a QoE vendor, a
  data warehouse, or its own pipeline.
- **CMCD emission** (CTA-5004), so client state reaches CDN logs. This is what makes client↔CDN
  debugging a joint exercise rather than a finger-pointing exercise.
- **Sampling and backpressure**: telemetry never blocks playback, never queues without a bound, and
  drops under memory pressure with a counter recording the drop.

### 3.5 `superplayer-cache` and `superplayer-offline`

These plug into the transfer chain of §2.4 rather than assembling one of their own.

- **Content-keyed cache** (F7, generalized): a `CacheKeyFactory` derived from
  `MediaRequest.contentId` plus representation identity, so the same content at a different bitrate
  or from a different CDN host is one cache entity. URL-keyed caches miss silently; this is a free,
  large win that teams typically discover a year late.
- **Device-aware size policy** from free space and `isLowRamDevice`, LRU eviction, and a pinned
  region for downloads that eviction may not touch.
- **Offline downloads** on Media3's `DownloadManager` and `DownloadService`, plus:
  - `WorkManager` scheduling with `NetworkType.UNMETERED`, `requiresBatteryNotLow`, and
    `requiresStorageNotLow` — F4's battery requirement.
  - Track selection for download — a sensible bitrate, subtitles, audio languages — rather than
    downloading everything.
  - Resumable across process death and network loss, with per-item progress, and storage-full
    handling that fails one item rather than the queue.
  - Offline license lifecycle bound to the download lifecycle (§3.2).

### 3.6 `superplayer-diagnostics`

- **`MediaSourceDoctor`** — a preflight and postmortem analyzer that fetches a manifest and reports
  pathologies in plain language: variant ladder gaps, missing `CODECS` attributes, audio-group
  mismatches, wildly inconsistent segment durations, `#EXT-X-DISCONTINUITY` without matching
  timeline metadata, DASH `availabilityStartTime` clock skew, missing or short
  `timeShiftBufferDepth`, CORS and token misconfiguration, and mismatched `Cache-Control` on
  manifest versus segments — the cause of a surprising share of "live stream freezes after 30s"
  tickets.
- **Session trace bundle** — one exportable JSON/Perfetto-compatible artifact: timeline of state
  changes, track switches, load events with timings and HTTP status, bandwidth samples, errors, and
  a device/codec/DRM capability snapshot. Attachable to a bug report, and readable by a CDN engineer.
- **On-device debug HUD**, debug builds only — live bitrate, buffer occupancy, dropped frames,
  estimate versus selected, error log.
- **Perfetto trace points** on the hot path via `androidx.tracing`, so profiling shows SuperPlayer's
  phases rather than only `MediaCodec`.

### 3.7 `superplayer-core` extras

- **Player pool and recycling** (F7): a bounded pool of `ExoPlayer` instances for feed and grid UIs,
  with correct surface detach/attach and a still-frame placeholder, so a hundred-item feed does not
  OOM. Bounded by concurrent-decoder limits, not by a constant.
- **Lifecycle correctness**: audio focus, becoming-noisy, `MediaSession` and `MediaSessionService`
  for background, notification and automotive surfaces, `WakeLock`/`WifiLock` held only while
  actually playing, and correct behavior across process death and configuration change.
- **Profiles** — `VIDEO_ON_DEMAND`, `LIVE_LINEAR`, `LIVE_LOW_LATENCY`, `SHORT_FORM`, `TV_LEANBACK`,
  `DATA_SAVER` — each a documented, tested set of constants with a written rationale for every
  departure from Media3's own default. The profiles *are* the product: they are what every team
  currently re-derives badly.

### 3.8 `superplayer-tv`

- Display capability negotiation: `Display.Mode` and `HdrCapabilities`, frame-rate matching via
  `Surface.setFrameRate` (judder on 24fps content on a 60Hz panel is a top living-room complaint),
  and tunneled playback where supported.
- HDMI hotplug and audio-capability change handling via the `AudioCapabilities` receiver —
  passthrough formats change underneath you when an AV receiver is powered on mid-playback.
- D-pad-first controls, Leanback and Compose-for-TV surfaces, correct focus and seek-scrubbing.
- An ethernet-transport ABR profile: a TV on ethernet should not behave like a phone on WiFi.

---

## Part 4 — Roadmap

Each phase ends with something demonstrable and measured. Effort assumes one engineer.

| Phase | Scope | Exit criteria |
| --- | --- | --- |
| **0 — Scaffold** (1 wk) | Gradle multi-module build, Media3 version catalog, CI (build, unit, lint, API compatibility), `CONTRIBUTING.md` clean-room rules, ADR-0001. | Green CI; empty modules publish to `mavenLocal`. |
| **1 — Core + demo** (2 wks) | `SuperPlayer` facade, profiles, `MediaRequest`, player pool, lifecycle and `MediaSession`, the transfer-chain seam (§2.4). Demo plays HLS and DASH public test streams on phone. | Demo plays; `Player` delegation verified against Media3's own forwarding-contract suite; the chain is composed in one place with insertion points documented. |
| **2 — Telemetry + testkit** (2 wks) | QoE collector, CTA-2066 metrics, CMCD, sinks. Testkit: network shaping, fault injection, golden traces. **Built before tuning, deliberately.** | The benchmark harness (§6) emits a reproducible baseline report. |
| **3 — ABR + buffering** (3 wks) | `AdaptiveLoadControl`, `BandwidthOracle`, `NetworkAwareTrackSelection`, behind the §2.5 boundary. | Measured improvement over the Phase-1 baseline on the shaped-network suite, with no regression on stable WiFi. |
| **4 — Preload + cache** (2–3 wks) | `PreloadCoordinator`, decoder warm-up, content-keyed cache. Short-form feed demo. | p50 TTFF in the feed demo under the §6 target; memory flat over a 200-item scroll. |
| **5 — Resilience** (2 wks) | Classifier, retry policy, fallback ladder, token refresh. | Every injected fault either recovers or produces a typed, actionable error. Zero unclassified errors. |
| **6 — DRM** (3 wks) | Widevine online and offline, provisioning, security-level ladder, key rotation, secure surface. | Playback on L1 and L3 devices; offline license acquire → play offline → renew → release; all DRM errors typed. |
| **7 — Offline downloads** (2 wks) | Download stack, `WorkManager` policy, license binding. | A download survives process death, network loss, and reboot; battery policy verified with a battery profiler. |
| **8 — TV** (2–3 wks) | `superplayer-tv`, leanback demo. | Runs on a TV device and emulator; frame-rate matching verified; HDMI and audio-capability changes handled. |
| **9 — Diagnostics + docs** (2 wks) | `MediaSourceDoctor`, trace bundle, debug HUD, docs, the ADR set. | Doctor correctly identifies each pathology in a curated set of deliberately broken manifests. |

Roughly five months at a sustainable part-time pace. Phases 0–5 (about three months) constitute a
complete, coherent library on their own.

---

## Part 5 — Testing strategy

The binding rules are in [`docs/testing.md`](docs/testing.md); this is what the strategy must cover.

- **Unit (JVM/Robolectric):** policy logic is pure and deterministic — feed `AdaptiveLoadControl`
  and `BandwidthOracle` synthetic throughput traces and assert on decisions. Policy must be testable
  without a device; if it is not, the design is wrong.
- **Simulation:** replay recorded real-world throughput traces from public datasets through the
  selection logic, and score with the standard QoE objective (bitrate utility − rebuffer penalty −
  switch penalty). Regression-gate on that score in CI.
- **Fault injection:** a `superplayer-testkit` `DataSource` wrapper injects latency, throughput caps,
  HTTP 403/404/500, truncated responses, DNS failures, and mid-stream token expiry at chosen segment
  indices. Every fallback rung has a test that forces exactly that rung.
- **Manifest corpus:** a curated set of valid-but-hostile manifests — discontinuities, ladder gaps,
  clock skew, missing codecs, mid-roll changes. This is where real playback bugs live.
- **Instrumented / device matrix:** small, and spanning the fragmentation axes deliberately — a
  low-RAM older device, a mid-range device, a current flagship, an L3-only device, a TV box, and an
  emulator with software codecs only.
- **Golden traces:** capture session traces from the demo app and diff them structurally against
  known-good, so a behavior change appears as a reviewable diff rather than a flaky metric.

---

## Part 6 — Benchmarks

`benchmark/` runs a fixed matrix and emits a report. **Every number is generated here, from public
streams** (§0.2).

- **Network profiles:** stable WiFi 20Mbps; congested WiFi 3Mbps ±50%; LTE 5Mbps with 2s dropouts;
  3G 1Mbps; WiFi→cellular handover mid-playback; high-latency (600ms RTT).
- **Content:** public HLS and DASH conformance streams and public Widevine test content, covering
  VOD, live, and short-form ladders.
- **Baselines compared:** (a) stock `ExoPlayer.Builder(context).build()` with defaults, (b) stock
  plus naive tuning — the copy-pasted forum config, (c) a SuperPlayer profile.
- **Reported:** p50/p95 TTFF, rebuffer ratio, rebuffer count, time-weighted average bitrate, switch
  count, startup failure rate, peak RSS, battery delta over a 30-minute session.
- **Honesty rules:** at least 20 runs per cell, report variance and not only means, publish the raw
  traces, and **publish the cases where SuperPlayer is neutral or worse.** A benchmark table with no
  losses in it is a marketing document and will be read as one. F1's trade — lower cellular bitrate
  for fewer stalls — must show up *as a bitrate loss* alongside the rebuffer win.

---

## Part 7 — Design principles

These are the commitments the rest of the document is downstream of. A change that contradicts one
argues the case explicitly and produces a superseding ADR.

1. **Measurement before tuning.** Telemetry and the benchmark harness are Phase 2, ahead of ABR in
   Phase 3. Tuning without a harness is guessing, and the phase order says so out loud.
2. **Composition over forking**, using public extension points only, with a documented compatibility
   strategy for Media3 upgrades. The library is meant to be maintained, not only shipped.
3. **Solve the problem at the layer that owns it** (§2.3). Where a popular approach works around a
   layer, prefer reaching the same goal at the supported extension point with less machinery.
4. **Policy is engine-agnostic** (§2.5). Buffer and track-selection decisions are made in types that
   name no Media3 class, so the policy can be replaced without touching a call site.
5. **Variance, not only mean**, in bandwidth estimation and buffer policy.
6. **Cache keyed by content identity, not by URL.**
7. **An error taxonomy with a fallback ladder** — including CDN token refresh, resume-position
   preservation, and secure-decoder recovery.
8. **Standards fluency** — CTA-2066 metrics, CTA-5004 CMCD, RFC 8216, ISO/IEC 23009-1, DASH-IF IOP.
   Speaking the CDN and analytics teams' language is a product requirement, not a nicety.
9. **Device fragmentation handled by querying capabilities**, never by hardcoding model allowlists.
10. **Honest benchmarks, including losses** (§6).
11. **Clean-room discipline, documented up front** (§0.2).
