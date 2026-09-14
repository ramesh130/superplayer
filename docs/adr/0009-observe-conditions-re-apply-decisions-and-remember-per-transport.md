# ADR-0009: Observe conditions in core, re-apply a decision on named triggers, admit the adaptive engine as a friend, and remember an estimate only per transport and only for the process

- **Status:** Accepted
- **Date:** 2026-09-13
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Extends:** [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md), whose
  rules 3 and 4 are reworded below and whose rules 1 and 2 stand as written.
- **Summary:** Cashes in ADR-0005's bet for the adaptive engine (`superplayer-abr`). Six concrete,
  optional observations (transport, throughput with its spread, stall history, stream type, heap
  budget, playback speed) are added to `PlaybackConditions`; the policy is re-consulted on named
  triggers only, and only where the engine can honor a changed decision whole; `superplayer-abr`
  reaches core's engine construction as a second Kotlin friend, behind one public `setPolicy` call;
  and a throughput estimate is remembered per transport, in process memory, for the process's
  lifetime only — never persisted.

## Context

ADR-0005 made a bet. It published one engine-agnostic boundary — `PlaybackConditions` in,
`PlaybackDecision` out — put a static per-profile table behind it, and left the input type **empty**,
because designing what an adaptive policy observes before the adaptive policy exists means guessing
at a public API. Its "standing obligation" is that `PlaybackConditions` grows a property when an
implementation needs to *read* one, not before. Phase 3 (`PRD.md` Part 4) is that implementation:
`superplayer-abr`'s `BandwidthOracle`, `AdaptiveLoadControl` and `NetworkAwareTrackSelection`, all
behind the boundary and none around it (#96). Its first act is to cash the bet in, and the bet was
that four questions could be answered better later than earlier. This ADR answers them.

**What a policy observes.** `PRD.md` §3.1 lists the inputs: transport class, throughput mean *and
variance*, recent stall history, stream type, available heap, playback speed. It lists them in
Android's and Media3's vocabulary — `NetworkCapabilities`, `DefaultBandwidthMeter` — and ADR-0005
rule 1 says none of that vocabulary may cross the boundary. Each input therefore needs a SuperPlayer
shape, and each shape is public API the moment it lands.

Variance is on that list for a reason the code should not have to re-argue. Yin, Jindal, Sekar and
Sinopoli (SIGCOMM 2015) — already the `ref:` on `QoeScore.kt` — show that the throughput predictor is
the weakest link of a rate-based adaptation algorithm: the error in the prediction, not the level of
the prediction, is what produces a rebuffer, and an adaptation that discounts its estimate by that
error is robust where one that trusts the mean is not. Media3's `DefaultBandwidthMeter` keeps a
sliding window of weighted samples and reports one number from it, the weighted median; it holds
the distribution and exposes none of it, so a policy sees a level and nothing about how far the
samples scatter around it. (`PRD.md` §3.1 calls Media3's estimator an EWMA; it is a windowed
median, and the point stands either way — what is missing is the spread, not the window.) A
buffer floor sized from one central number is sized for the network the viewer had typically,
which is not the network that stalls them.

**How a decision is re-applied.** ADR-0005 rule 4 consults the policy once, at construction, because
half of a decision — `BufferPolicy` — is handed to Media3's `DefaultLoadControl` at construction and
cannot be changed after. The rule is honest about being a workaround: "until the buffer half can be
re-applied". `AdaptiveLoadControl` is what lifts the constraint. A `LoadControl` of SuperPlayer's own
can be asked for new targets whenever SuperPlayer likes; Media3 polls it on every loading decision,
so a changed target is honoured on the next poll. The selection half never had the constraint —
`TrackSelectionParameters` may be set at any time — but ADR-0005 chose not to re-apply it alone,
because a decision half in force is a worse contract than one documented as construction-time.

Once a decision can change, three things that were fixed acquire a time axis: what
`SuperPlayer.playbackDecision` reports, what the telemetry's `SessionStarted.decision` means (the
schema says "in force for the whole session", which stops being true), and when the policy is asked.
Re-asking on every sample the bandwidth meter takes is the oscillation `PRD.md` §3.1 warns against
by name; re-asking on a timer asks with nothing new to say.

**How the policy gets into the player.** `superplayer-abr` depends on `superplayer-core` and core may
not depend on it (`docs/modules.md`). The public half is easy: `PlaybackPolicy` is already public,
and ADR-0005 anticipated a `setPolicy`. The hard half is the engine components. `AdaptiveLoadControl`
implements Media3's `LoadControl`, `NetworkAwareTrackSelection` extends `AdaptiveTrackSelection`, and
`BandwidthOracle` implements `BandwidthMeter`; every one of those is `@UnstableApi`, and ADR-0001
rule 2 keeps every one of them out of every published module's API. They have to reach
`ExoPlayer.Builder` inside `SuperPlayer.Builder.build()`, which is the one moment a load control can
be installed, and they have to reach it without core naming abr and without a Media3 type in a
public signature.

The project has solved a version of this twice. `superplayer-testkit` compiles as a Kotlin *friend*
of core (`build-logic`'s `KotlinFriendModules.kt`) and reaches the one internal seam
`docs/testing.md` describes, `SuperPlayer.Builder.setEngineConfigurator`. `TelemetryCollector`
(ADR-0008 rule 2) solves the other direction — core hands a built player to an interface it owns and
the implementation reaches the engine itself — but only *after* the engine exists, which is too late
for a load control.

**What an estimate may remember, and where.** `PRD.md` §3.1 says "per-network-type persisted
estimates": on a transport change, reseed from that transport's stored estimate rather than
carrying the WiFi estimate onto cellular. ADR-0006 rule 2 says SuperPlayer chooses no storage on a
consumer's behalf: no preferences, no database, no file, no `Context`-scoped singleton. Both are
binding, and "persisted" is the word that puts them in tension. Media3's `DefaultBandwidthMeter`
already keys its *initial* estimates by network type and can reset to them on a type change, but
what it reseeds from is a static table shipped with the library, never what the previous session on
that transport measured; a learned estimate does not survive the handover in either direction.

## Decision

**Core observes the conditions and translates them into engine-agnostic values in one internal
file; the policy is consulted at construction and again on a fixed set of named triggers, but only
when the engine was built with components that can honour a changed decision; `superplayer-abr`
supplies those components by compiling as the second friend of core and implementing a core-internal
extension interface on its policy object, so a consumer makes one public call; and a throughput
estimate is remembered per transport, for the lifetime of the process, in memory, and nowhere
else.**

Nine rules follow, and they are binding.

### What a policy observes

1. **`PlaybackConditions` grows exactly the observations Phase 3 reads, each as a SuperPlayer value
   type, and every one of them is optional.** No property names a Media3 type or an Android type:
   a transport is a SuperPlayer enum, not a `NetworkCapabilities`; a throughput estimate is a
   SuperPlayer value, not a `BandwidthMeter`. Every property has an explicit *unobserved* value
   (`null`, or an empty history), so that `PlaybackConditions()` remains what it is today — nothing
   observed — and every `PlaybackPolicy` written against the empty type keeps compiling. Phase 3
   adds these, and no others:

   | Property | Shape | Read by |
   | --- | --- | --- |
   | `transport` | `NetworkTransport`: `WIFI`, `CELLULAR`, `ETHERNET`, `UNKNOWN`; cellular carries a `CellularGeneration` of `NR`, `LTE` or `OLDER` | `BandwidthOracle` (keys its memory, rule 8); `NetworkAwareTrackSelection` (per-transport ceiling); `AdaptiveLoadControl` (cushion depth) |
   | `throughput` | `ThroughputEstimate`: mean in bits per second, a *spread* in the same unit, a conservative percentile, the sample count and the age of the newest sample | `AdaptiveLoadControl` (raises the playback floor on spread, not the ceiling); `NetworkAwareTrackSelection` (selects on the percentile when the spread is high and on the mean when it is not) |
   | `stallHistory` | `StallHistory`: rebuffers this session, milliseconds since the last one ended, and the duration of the last one | `AdaptiveLoadControl` (post-rebuffer hysteresis: the raised floor and the held ceiling, and for how long) |
   | `streamType` | `StreamType`: `ON_DEMAND` or `LIVE`, as the manifest declared it | `AdaptiveLoadControl` (latency-priority mode is keyed on this, never on the profile) |
   | `heapBudgetBytes` | the heap this app is allowed, as `DeviceCapacity.kt` already reads it from `ActivityManager.memoryClass`; a budget rather than a live free-heap reading, because free heap under a collector is noise | `AdaptiveLoadControl` (the memory-aware ceiling on every branch) |
   | `playbackSpeed` | the current playback speed as a factor | `AdaptiveLoadControl` (a buffer expressed in media time drains faster than real time at 2×) |

   The spread is a property because of the argument in Context: variance, not mean, predicts the
   stall, and a policy that cannot see it cannot size a floor against it. Code that raises a floor on
   spread cites this ADR, not the paper.

   `streamType` is an observation rather than something the profile already knows because the
   profile is the *consumer's intent* and the stream type is *what the manifest turned out to be*,
   and the two disagree in ordinary apps: a catalogue app plays live channels under
   `VIDEO_ON_DEMAND` because it never thought to switch, and an HLS event playlist that was live at
   adoption becomes on-demand when its `EXT-X-ENDLIST` arrives. Latency-priority mode keys on the
   manifest, so that the app that guessed wrong still gets the right buffer. `SHORT_FORM` is not a
   value of `StreamType`: nothing in a manifest says "short-form", so it stays what it is, a profile.

   The profile itself is not a property. A policy is built *for* a profile — `forProfile(profile)`
   today, abr's equivalent factory tomorrow — and reads it from its own constructor.

   Buffer occupancy is not a property either. Media3 hands it to the `LoadControl` on every poll,
   and comparing it to a target is the mechanism that applies a decision, not the decision. A policy
   keyed on occupancy would be re-implementing `LoadControl` behind the boundary.

2. **The observations named by `PRD.md` §3.1 and #96 that Phase 3 does not add are these, and each
   waits for the phase named, so that nobody adds it speculatively.**

   | Observation | Why not now | Arrives |
   | --- | --- | --- |
   | Display mode and HDR capability | On a phone it does not change within a session, so `NetworkAwareTrackSelection` reads it once as a device *constraint*, the way Media3's own defaults read the viewport (`EngineBinding.kt` builds upon them rather than replacing them). It becomes a *condition* only when it can change under a playing session — an HDMI mode switch — which is a TV concern | Phase 8, `superplayer-tv` |
   | Decoder profile, level and instance limits | A constraint, not a condition, for the same reason; read once by the selector | Read in Phase 3 as a constraint; the *secure*-decoder limit that varies by DRM level is Phase 6, `superplayer-drm` |
   | Whether a transfer was a cache hit | Not an observation a policy makes: `BandwidthOracle` excludes cache reads from its samples through Media3's `isNetwork` flag (`TransferChain`'s KDoc), so the estimate a policy sees is already clean | Built in Phase 3, exercised by Phase 4, `superplayer-cache` |
   | Whether a request is being retried, and what the last error was | Resilience's, and a policy that changed its buffering on an error class would be doing resilience's job behind the wrong boundary | Phase 5, `superplayer-resilience` |

3. **Observation is core's, and the translation from Android's and Media3's vocabulary into
   `PlaybackConditions` happens in one internal core file (`ConditionsBinding.kt`), the inverse of
   `EngineBinding.kt`.** Core reads the transport from `ConnectivityManager`, the stream type from the
   timeline, the stall history from the player's own state transitions, the heap from
   `DeviceCapacity.kt` and the speed from the playback parameters. The one property core cannot
   produce alone is `throughput`: its source is *whatever bandwidth meter the engine was built with*,
   read through a core-internal interface that meter implements. When the meter is `BandwidthOracle`
   the estimate carries a spread and a percentile; when it is Media3's default, they are unobserved,
   and a policy that needs them treats "unobserved" as "unknown", not as zero. That interface is
   internal and of the kind `docs/testing.md` calls a *reading* seam — it substitutes no behaviour
   — and it is not the configuring "second seam" that document warns against.

### How a decision is re-applied

4. **The policy is consulted at construction, and again on these named triggers and no others:**
   the transport changes; the stream type becomes known, or changes; a rebuffer ends; the playback
   speed changes; and the bandwidth meter reports that its estimate has moved *materially*, where
   "materially" is a threshold the meter applies to its own estimate before it says anything, so that
   a policy is never called once per sample. Not on a cadence, and not on every observation change.
   Each trigger is a fact a reader can name, which is what makes a decision series legible after the
   fact. Hysteresis is the policy's own: `stallHistory` tells it how long ago the last stall was,
   and a policy that wants a cooldown holds its previous answer until that number is large enough.
   `PlaybackPolicy.decide` stays a pure function of its conditions.

5. **A re-applied decision is honoured whole, or the policy is not re-consulted at all.** A changed
   decision reaches the engine only through components built to accept one after construction —
   `AdaptiveLoadControl` re-targets its buffer and `NetworkAwareTrackSelection` takes the new
   ceiling — and both are `superplayer-abr`'s. On a player built without them, rule 4 of ADR-0005
   stands exactly as written: the policy is consulted once, whatever policy it is. This is the same
   judgment ADR-0005 made — a decision half in force is worse than one documented as construction-time
   — applied to the case where a consumer supplies an adaptive `PlaybackPolicy` to a core-only
   player. The translation stays in `EngineBinding.kt`, which gains a second target beside the two it
   has: the engine's own retargetable components. On a player built *with* them, the selection
   ceiling belongs to `NetworkAwareTrackSelection` from construction onward, and `EngineBinding.kt`
   lays no ceiling into Media3's `TrackSelectionParameters` at all — otherwise a construction-time
   ceiling would clamp every later decision that raised it, which is the half-in-force contract this
   rule exists to refuse. The parameters are set once, at construction, as today, and a
   re-application never rewrites them, so a consumer's own parameters are never overwritten by a
   trigger.

   *Addendum (2026-09-13, #100).* A decision has a third half beside the two this rule names: a
   `LiveLatencyPolicy`, the speed range a live window is held with. It reaches the engine by neither
   target. Media3 takes a live speed range from the **media item** — its HLS and DASH sources pin
   the range to exactly 1× on any stream whose manifest carries no low-latency hints unless the item
   declares one, so a `LivePlaybackSpeedControl` built from the half would silently never be used —
   and so core lays the half into the item when a request is adopted and, on a player that is
   re-consulted, replaces the playing item in place when the half changes, which Media3's own
   sources do without re-preparing. `EngineBinding.kt` still holds the one translation. The promise
   above holds for the item as it does for the parameters: an item that already declares a range of
   its own is never rewritten. This is an addition to the rule, not a contradiction of it, and it
   is recorded here rather than in a superseding ADR for that reason.

   *Addendum (2026-09-14, #114).* The selection half now carries a pace as well as a ceiling
   (ADR-0005 rule 2, addendum), and "honoured whole" reaches it. Media3's adaptive selection fixes
   its thresholds when it is built, so `NetworkAwareTrackSelection` builds Media3's own thresholds
   inert and applies the pace in force through its per-rung hook on every evaluation — the same
   hysteresis, compared against the same rung and skipped in the same cases. A re-consulted pace
   therefore reaches the selection already playing rather than the next period's. On a player
   without that component the pace becomes Media3's own adaptive factory at construction and, like
   the rest of such a player's decision, is fixed there.

6. **`SuperPlayer.playbackDecision` is the decision currently in force; `SessionStarted.decision`
   is the decision in force when the session started; and every change afterwards is a
   `DecisionChanged` telemetry event.** `DecisionChanged` carries the new decision and the trigger
   that produced it, from the list in rule 4. It is a new event type in the sealed hierarchy — an
   addition of shape, not of meaning, so ADR-0008 rule 5 says it is **not** a `SCHEMA_VERSION` bump —
   and `docs/telemetry-schema.md` defines it, in the same change that adds it, as the thing a
   pipeline joins to `SessionStarted.decision` to reconstruct the decision a session was running at
   any moment. The schema's sentence "in force for the whole session" is rewritten by that change
   to "in force at the start; see `DecisionChanged`", which is a clarification of a definition that
   was only ever true because nothing could change it, not a change to what the field counts.

### How the policy gets into the player

7. **A consumer supplies a policy through one public call, `SuperPlayer.Builder.setPolicy`, and
   `superplayer-abr` reaches core's engine construction as the second Kotlin friend of core, by
   implementing a core-internal extension interface on the policy object it hands to that call.**
   `setPolicy(policy: PlaybackPolicy)` replaces the profile's static policy and takes any
   implementation, including a consumer's own. abr's public entry point is a factory that returns a
   `PlaybackPolicy` for a profile — a public type on both sides, and no Media3 type in sight. That
   object *also* implements an interface that is `internal` to core, which abr can see because
   `build-logic` declares it a friend the way it declares `superplayer-testkit` one. Inside `build()`,
   core checks whether the supplied policy is such an extension and, if it is, lets it configure the
   engine through the same `EngineConfiguration` the test seam uses, *before* the test configurator
   runs, so that a test's engine configuration still wins over the policy's exactly as it wins over
   the profile's today. What the extension may install — a load control, a track selection factory,
   a bandwidth meter, and the hook through which a changed decision is handed back to them — is
   `EngineConfiguration`'s to enumerate, and #98's to add.

   **A consumer who never adds `superplayer-abr` pays nothing, and that is asserted rather than
   assumed.** A policy that is not an extension takes the path that exists today; no class from
   abr is on the classpath, none is looked up, and no observer is registered. The assertion is
   the one ADR-0008 rule 2 uses for telemetry: a test builds a player with `setProfile` alone and
   counts what was registered, and the count is zero.

   The friend relationship is the honest description of what abr is. `KotlinFriendModules.kt`'s
   argument for testkit — two compilations of one library, shipped together, a consumer never a
   friend of anything — holds for abr unchanged, with one difference worth stating: testkit is the
   *same phase* as the modules it serves and abr is a later phase than core. That is fine under
   `docs/modules.md`'s rule, because a friend path is a compiler flag and not a Gradle dependency,
   and the dependency it does not create is the one the rule forbids.

   *Addendum (2026-09-14, ADR-0010 rules 3, 6 and 8).* `superplayer-cache` and
   `superplayer-preload` are the third and fourth friends, on this argument unchanged: the cache
   fills `TransferChain`'s cache slot from behind a public core type with an internal constructor,
   and the coordinator fills the engine seam of the pool it is attached to. Rule 9 of that ADR
   also reaches this one's rule 5: on a pool with preload attached the retargetable components are
   shared by every player on it, and a re-applied decision is honoured whole across the pool.

### What an estimate may remember, and where

8. **A throughput estimate is remembered per transport, for the lifetime of the process, in memory
   owned by `superplayer-abr`, and nowhere else.** The key is a `NetworkTransport` value and nothing
   finer: not an SSID, not a cell identity, not a location, not anything that would let the memory
   identify a network or a place. One entry per transport, so the memory is bounded by the enum.
   Each entry is a `ThroughputEstimate` as rule 1 defines it, with the age of its newest sample.
   The holder is one per process, the way `TelemetryDelivery`'s thread is one per process, because
   the estimate is a property of the device's network and not of any one player, and sixty pooled
   players may not mean sixty estimates. ADR-0006 rule 2 is intact, including the clause that
   forbids a `Context`-scoped singleton: that clause names a way of *holding storage* — a handle
   to preferences or a database that outlives the player that opened it — and this holder needs
   no `Context`, opens nothing, and keeps nothing that outlives the process. Memory is not storage,
   nothing is written anywhere, and process death forgets everything, which is stated rather than
   hidden. `TelemetryDelivery`'s one thread per process is the precedent for a process-wide object
   that is not a storage decision.

9. **On a transport change the estimate that follows is the previous estimate for the transport
   arrived at; on a cold process, or when that estimate is older than a stated age, it is a
   documented default per transport.** The default table lives in abr beside the oracle, one value
   per `NetworkTransport` and per `CellularGeneration`, each with a `// ref:` to its public source and
   a written rationale, and it is SuperPlayer's own rather than a copy of Media3's per-country table.
   An estimate older than the stated age is not trusted merely because it was measured here: it
   decays toward the default rather than being used as if it were fresh, and the age is a constant
   argued where it is chosen. Handing the memory to a consumer to store — a value the consumer keeps
   the way it keeps a `PlaybackSnapshot`, so that the second process on a device starts warm — is
   *permitted* by this ADR and is not built by Phase 3: it is added when the benchmark's cold-start
   cells show that a process-lifetime memory costs enough to be worth an API, and not before.

### What changes in ADR-0005

ADR-0005 is not superseded. Its rules 1 and 2 stand as written; this ADR adds to rule 1 that the
translation *into* the boundary is as confined as the translation out of it (rule 3 above). Two of
its rules change wording. The change is recorded here, and ADR-0005 carries a pointer to it at each
rule so that a reader of that document alone is not misled:

- **Rule 3** read "The consumer names a profile, not a number." It now reads: *the consumer names a
  profile, or supplies a `PlaybackPolicy`, and never a number.* A supplied policy names decisions,
  which is the boundary's own vocabulary, so the thing rule 3 forbids — a constant in a builder —
  stays forbidden.
- **Rule 4** read "The policy is consulted once, at construction, until the buffer half can be
  re-applied." The buffer half can now be re-applied, and rule 4 becomes rules 4 and 5 above: *at
  construction, and again on named triggers, only when the engine was built with components that
  can honour a changed decision whole; otherwise once.*

Its "standing obligation" is discharged for the properties rule 1 names and remains in force for
every property after them.

## Consequences

**Easier.** Phase 3 has a written answer to every question that would otherwise be decided at a
call site under deadline pressure, which is the phase ADR-0005's "harder" paragraph said would pay
for the boundary. The observations are enumerated, so a reviewer can tell a property that was read
from one that was anticipated. A decision series is reconstructible from telemetry alone, which is
what lets #102's regression gate replay a trace through a policy and score what it decided. A
consumer with no interest in adaptation sees one new builder method and nothing else.

**Harder.** `PlaybackConditions` goes from zero public properties to six, plus four public value
types, and every one of them is a compatibility commitment under `docs/api-surface.md`. The empty
type was cheap to keep; this one has to be kept the way `TelemetryEvent` is kept.

Core acquires a second friend, and the argument that friendship is not a widening of the seam has
to hold twice. It holds because the same `EngineConfiguration` is reached, and what it exposes is
still enumerated in one internal class; but every slot #98 adds to it is a slot both friends can
see, and a reviewer has to ask of each whether it is a slot or a widening.

Rule 5 has a cost a consumer can meet: a hand-written adaptive `PlaybackPolicy` on a core-only
player is consulted once and silently behaves as a static one. The KDoc on `setPolicy` says so,
and it is a documented limit rather than a surprise, but it is the kind of limit people discover
by measuring.

Rule 8's cost is the one ADR-0006 already paid, arriving again: the first session after process
death starts from a table rather than from what the device measured an hour ago. Rule 9 names the
condition under which that becomes worth an API, so it is a deferral with a trigger rather than an
open question.

`DecisionChanged` adds an event to every sink's `when`, which a sealed hierarchy makes a compile
error rather than a silent omission — that is the point of sealing, and it is the cost of it too.

**A standing obligation, inherited.** The tables in rules 1 and 2 are now the list. A property
that is not in rule 1 is added by an ADR that amends this one, with the component that reads it,
and a phase in rule 2's table that arrives adds its row to rule 1's at the same time.

## Alternatives considered

**Consult the policy on every bandwidth sample.** Rejected. It is the oscillation `PRD.md` §3.1
warns against, and it makes the decision series unreadable — hundreds of `DecisionChanged` events
a session, most of them differing by nothing a viewer could see. The meter's own threshold (rule 4)
is where the noise is removed, once, rather than in every policy.

**Consult the policy on a cadence.** Rejected. A timer asks a question with nothing new to say
most of the time, and asks it late the one time something happened. Named triggers are also what
makes `DecisionChanged.trigger` meaningful: "every ten seconds" is not a reason.

**Re-apply the selection half on a core-only player, since `TrackSelectionParameters` allow it.**
Rejected, for ADR-0005's reason: half a decision in force is worse than a documented construction-time
one, and a consumer would have no way to tell which half they had.

**Make the profile a `PlaybackConditions` property.** Rejected. A policy is built for a profile and
reads it from its own constructor; making it an observation would let a caller hand a `LIVE_LINEAR`
policy `VIDEO_ON_DEMAND` conditions, and the type would then have to define what that means.

**`ServiceLoader` for the engine components.** Rejected. Adding a dependency would change a
player's behaviour with no line of code in the app, which is exactly the invisibility that makes a
mystery bug; a consumer could not build two players with different policies in one process, which
`PlayerPool` needs; and on Android it costs a classpath scan at startup and a shrinker rule to keep
the registration alive. The extension interface makes the choice explicit at one call.

**A public seam with an opaque handle: `setEngineExtension(extension: EngineExtension)` where the
type is public but empty.** Rejected. The handle would be empty only until abr needed to hand
something through it, at which point the something is an `ExoPlayer.Builder` or a `LoadControl`,
both `@UnstableApi`; the seam would either fail `verifyNoUnstableMedia3InPublicApi` or route around
it through reflection. `internal` plus a friend path is the same access with the compiler enforcing
who has it.

**Persist the estimate in a SuperPlayer-chosen file or preference.** Rejected under ADR-0006
rule 2, and for the reason that ADR gives: choosing storage means choosing a threading model, a
migration story, a clean-up policy and a place where data about a person's network lives.

**Key the memory by network identity — SSID, BSSID, cell — for a better reseed.** Rejected. It
would make the estimate a record of where the device has been, which is a privacy decision made by
a library; the transport class is the coarsest key that still carries the F1 win, and `PRD.md` §3.1
asks for nothing finer.

**Ship the consumer-stored estimate memory now.** Rejected, narrowly, as premature: it is a public
type nobody has measured the need for. Rule 9 says what measurement would justify it.

**Supersede ADR-0005 outright.** Rejected. Every rule it made still holds in substance; two change
in wording because a constraint they were written around has lifted. A superseding ADR would make
a reader believe the boundary had been reconsidered, and it has not.

## References

- Yin, Jindal, Sekar and Sinopoli, *A Control-Theoretic Approach for Dynamic Adaptive Video
  Streaming over HTTP*, SIGCOMM 2015 — the throughput-predictor critique behind rule 1's spread,
  and the `ref:` `QoeScore.kt` already carries. https://doi.org/10.1145/2785956.2787486
- Mao, Netravali and Alizadeh, *Neural Adaptive Video Streaming with Pensieve*, SIGCOMM 2017 — the
  same objective, and the same observation that past throughput and its variability are the inputs
  that matter. https://doi.org/10.1145/3098822.3098843
- Spiteri, Urgaonkar and Sitaraman, *BOLA: Near-Optimal Bitrate Adaptation for Online Videos*,
  INFOCOM 2016 — the buffer-based counterpoint, and why buffer occupancy is the mechanism's input
  rather than the policy's. https://doi.org/10.1109/INFOCOM.2016.7524428
- `LoadControl` — polled by the player, which is what makes re-targeting possible:
  https://developer.android.com/reference/androidx/media3/exoplayer/LoadControl
- `DefaultBandwidthMeter` and its per-network-type initial estimates:
  https://developer.android.com/reference/androidx/media3/exoplayer/upstream/DefaultBandwidthMeter
- `NetworkCapabilities` — the Android transport vocabulary rule 1 keeps out of the boundary:
  https://developer.android.com/reference/android/net/NetworkCapabilities
- `TelephonyManager` network types (`NETWORK_TYPE_LTE`, `NETWORK_TYPE_NR`) — the source of
  `CellularGeneration`: https://developer.android.com/reference/android/telephony/TelephonyManager
- `ActivityManager.getMemoryClass()` — the heap reading `DeviceCapacity.kt` already makes:
  https://developer.android.com/reference/android/app/ActivityManager#getMemoryClass()
- RFC 8216 §4.3.3.4, `EXT-X-ENDLIST` — the moment a live stream becomes on-demand:
  https://www.rfc-editor.org/rfc/rfc8216#section-4.3.3.4
- `java.util.ServiceLoader` — the rejected mechanism:
  https://developer.android.com/reference/java/util/ServiceLoader
- [ADR-0001](0001-compose-dont-fork.md) rule 2, [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md),
  [ADR-0006](0006-own-the-platform-rules-and-hand-back-the-state.md) rule 2,
  [ADR-0008](0008-measure-behind-an-engine-agnostic-sink-boundary.md) rules 2 and 5, and
  `docs/testing.md`'s *Reaching that seam from another module*.
- `PRD.md` §3.1 and Part 7 principle 5 — the inputs and the variance principle; #96 — the phase this
  decides for.
