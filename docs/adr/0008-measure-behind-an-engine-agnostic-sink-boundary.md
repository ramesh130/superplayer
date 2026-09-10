# ADR-0008: Measure behind an engine-agnostic sink boundary, and make the loss a contract

- **Status:** Accepted
- **Date:** 2026-09-09
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None

## Context

Phase 1 shipped a player that cannot say how well it played. Every phase after this one is a tuning
phase — ABR and buffering, preload and cache, resilience — and each will claim an improvement
against nothing. `PRD.md` §3.4 is the requirement: QoE metrics whose definitions follow CTA-2066,
delivered through a sink interface so an app can route them to product analytics, a QoE vendor, a
warehouse, or its own pipeline.

This is a boundary problem with the same shape as the one
[ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md) solved for policy,
running in the opposite direction. Policy is inbound: observed conditions in, a
decision out, no Media3 type in the interface. Telemetry is outbound: engine callbacks in, values
out to somebody else's pipeline.

The outbound direction is the more tempting one to get wrong, because the engine already has the
data in usable shape. `AnalyticsListener.EventTime` carries the timeline position and window that
every event needs. `PlaybackStatsListener` produces `PlaybackStats` with total rebuffer time, mean
bitrate, and a join-time figure already computed. `Format` names the bitrate and codec of the track
that switched; `LoadEventInfo` and `MediaLoadData` describe the request that just completed. A sink
interface of `fun onEvent(eventTime: EventTime, stats: PlaybackStats)` would be a few hours' work
and would carry more information than the schema this project will spend three issues defining.

Three things are wrong with it, and only the first is mechanical.

**It cannot compile on the consumer's side without opting into Media3's instability.** Every type
named above is `@UnstableApi`. `verifyNoUnstableMedia3InPublicApi` would fail the build, and the
failure would be correct rather than pedantic ([ADR-0001](0001-compose-dont-fork.md) rule 2): an
app's analytics adapter is ordinary application code, and requiring `@OptIn` there spreads the
marker to the place least equipped to reason about it.

**It ties an app's data schema to Media3's release cadence.** A field that moves in 1.12 is a
breaking change in a data pipeline, which is the most expensive and least visible place for one.
Nothing crashes. A dashboard goes quiet, and somebody notices a fortnight later.

**It defeats F8, which is the whole point.** F8 is not "apps have no metrics"; it is that every app
invents its own definitions, so no two can be compared. Media3's fields are engine facts, honestly
reported and correct on their own terms — but the boundary Media3 draws around "buffering", and the
point it starts counting a join, are the engine's boundaries, not CTA-2066's. Renaming a field does
not convert it. A schema that is "whatever Media3 reported" cannot promise that a rebuffer means the
same thing here as it does anywhere else, because it has delegated the definition to something with
no opinion about the standard.

Two further constraints bound any design, and both come from decisions already made.

`superplayer-core` may not depend on `superplayer-telemetry` — dependencies point inward, and
telemetry is a later phase (`docs/modules.md`). So if `SuperPlayer.Builder` is to accept a sink, as
`PRD.md` §2.2 shows it doing, the type it accepts cannot live in the module that produces the
events.

[ADR-0006](0006-own-the-platform-rules-and-hand-back-the-state.md) rule 2 says SuperPlayer chooses
no storage on a consumer's behalf: no preferences, no database, no file. That forecloses a durable
telemetry queue, and therefore forecloses at-least-once delivery, because surviving process death is
the only thing that would make it worth promising. Lossiness is not a shortcut here. It is what the
storage rule already implies, and the honest thing to do is state it as a contract rather than
discover it as a defect.

## Decision

**SuperPlayer defines its own telemetry event types, derives them from engine callbacks in one
internal place, and delivers them to a consumer-supplied sink that names no Media3 type. The seam —
the sink interface and the event vocabulary — lives in `superplayer-core`; everything that produces,
queues, or transports events lives in `superplayer-telemetry`. Delivery is at-most-once, bounded,
and lossy under pressure, and every dropped event is counted and reported.**

Six rules follow, and they are binding.

1. **No Media3 type appears in the telemetry boundary.** No event type, field, sink method
   parameter or return names `EventTime`, `PlaybackStats`, `Format`, `LoadEventInfo`,
   `MediaLoadData`, `AnalyticsListener`, or any other Media3 class, stable or not. The translation
   from engine callbacks into SuperPlayer events happens in one internal file in
   `superplayer-telemetry`, the way `EngineBinding.kt` is the one place a `PlaybackDecision` becomes
   Media3 configuration. Where SuperPlayer's definition of a metric departs from the Media3 field it
   is derived from, the derivation carries a comment citing both — `CONTRIBUTING.md`'s `// ref:`
   rule applies to a definition as much as to an algorithm.

2. **The seam is core's; the collector is telemetry's.** `TelemetrySink` and the event value types
   are `superplayer-core` public API, because `SuperPlayer.Builder.setTelemetry` is core's and core
   may not depend on telemetry — the same split `PlaybackPolicy` already uses, where the boundary is
   in core and `superplayer-abr` will bring an implementation. `superplayer-telemetry` holds the
   collector, the built-in sinks, the delivery machinery, and `docs/telemetry-schema.md`. **A player
   built without a sink pays nothing**: no analytics listener registered, no queue allocated, no
   thread started, no per-event allocation. Telemetry is additive, and a consumer who did not ask
   for it should not be able to measure that it exists. Registration is therefore core's, because
   core is the module that holds the engine and the only one that knows whether a sink was
   supplied; what core may not do is name a telemetry type in order to perform it. That constraint
   is binding, and the spelling that satisfies it is left open below rather than decided here.

3. **Delivery is at-most-once and bounded, and the loss is counted.** The queue between collection
   and delivery has a stated bound; past it, events are dropped rather than queued; the drop count is
   reported to the sink as part of the session-end event. **Session end is the one event the bound
   may not drop**, and it is reserved capacity rather than a best effort — an event whose whole job
   is to declare what was lost is worthless if pressure can lose it, which is the failure this rule
   exists to prevent, arriving one level down. Nothing is retried and nothing is
   persisted (ADR-0006 rule 2), so events in flight when a process dies are gone. A session that
   dropped events says so, so a pipeline can exclude it rather than average it in. **A silently
   partial event stream is worse than no event stream**, because a rebuffer ratio computed from one
   is a plausible wrong number that nobody audits.

4. **A sink is called on SuperPlayer's own delivery context, serialized, and never on a thread the
   engine needs.** No sink call happens on the application thread or on a playback thread, so a sink
   that blocks for seconds delays no playback callback. Calls for one player are serialized, so an
   implementation needs no locking of its own. What thread a sink sees is documented in KDoc on the
   interface. Because a feed builds many players through `PlayerPool`, the delivery context is a
   property of the process rather than of each player — sixty pooled players may not mean sixty
   threads. Events within one session arrive in the order they occurred; no ordering is promised
   *between* sessions, because a shared delivery context interleaves them and a consumer that needs
   a global order has the session id and the timestamp to build one.

5. **The events are one sealed hierarchy rooted in core, every event carries a schema version, and
   the version tracks meaning rather than shape.** Sealing is what lets a sink `when` over the
   events and be told by the compiler when it has missed one; it also means the hierarchy cannot
   span modules, which is a second reason rule 2 puts the vocabulary in core rather than a
   convenience. Within a version: an event type may be added, and a field may be added to an
   existing event if it is optional. Forcing a bump: changing what an existing field counts, its
   units, or its denominator; narrowing or widening what a metric excludes; removing a field or an
   event. The distinction is F8's — a consumer's dashboard breaks on a changed *definition*, not on
   a new field it ignores. Because the events are core API, every one of these changes also appears
   in `api/superplayer-core.api` and is reviewed as an API diff (`docs/api-surface.md`).

6. **CMCD is a separate seam, joined to telemetry by a shared session id.** CMCD (CTA-5004) is not a
   sink and no event is routed through one: it annotates the requests the player already makes, it
   attaches to the `MediaSource.Factory` inside `TransferChain`, its schema is the standard's rather
   than ours, and Media3 computes and applies the keys itself. What binds the two together is one
   value — CMCD's `sid` **is** the telemetry session id — so a row in a CDN log joins to a row in
   an app's warehouse. That join is CMCD's entire value and it is a rule, not a nicety.

## Consequences

**A consumer's analytics adapter is ordinary Kotlin.** It implements one interface over value types
with no `@OptIn`, no Media3 on its compile classpath, and no knowledge of which engine produced the
numbers. That is what makes the metrics portable, which is what F8 asks for, and it is also what
makes a future engine change a library problem rather than an app problem.

**A mapping layer now exists, and it will drift.** This is the real cost of rejecting the cheap
alternative and it should not be understated: every Media3 upgrade can change what a callback fires
on, and nothing in the type system notices, because the mapping still compiles. The mitigation is
that the mapping is in one place and is tested through real playbacks against Media3's own fakes
(`docs/testing.md`), so a behaviour change appears as a failing assertion rather than as a quiet
number. The mitigation is not that the risk is small.

**The schema is core's public API, which cuts both ways.** Every event and field is tracked in
`api/superplayer-core.api`, so no definition can change without a reviewed diff — which is exactly
the discipline a data contract deserves, and stronger than what most schemas get. The cost is that
`superplayer-core`, the module every consumer takes, carries a vocabulary most of them will not use,
and that a schema addition is a core release rather than a telemetry one. Rule 5's version is what
absorbs the churn.

**Sealed events buy exhaustiveness and charge for it.** Rule 5's sealed hierarchy is what lets a
sink `when` over the events without an `else` and be told by the compiler when it has missed
one. The same property means adding an event type is source-breaking for any sink that took the
compiler up on it. Rule 5 permits the addition within a version anyway, and the KDoc on the sink
should say plainly that an `else` branch is the forward-compatible spelling. Stating this now is
cheaper than discovering it in a consumer's minor upgrade.

**Consumers must handle gaps.** Rule 3 means a dashboard cannot assume it received everything, and a
metric computed by summing events is a metric that can undercount. The drop counter is what makes
that detectable, and `docs/telemetry-schema.md` has to say so in the section a data engineer reads
first, not in a footnote.

**Two things are left open and named rather than hidden.**

The first is **how core registers a collector it may not name.** Rule 2 requires that no analytics
listener exists unless a sink was supplied, which puts the registration in core; rule 1 puts the
derivation in `superplayer-telemetry`, which core may not depend on. Both are satisfiable and the
spelling is #34's to choose, but it is a real question and pretending otherwise would leave the next
person to rediscover it. Two candidates are already visible. A collector can be a `TelemetrySink`
that wraps another sink, the way `TelemetrySink.composite` already composes, so the consumer — who
depends on telemetry anyway — assembles the chain and core only ever sees its own interface. Or core
can accept a factory for a small core-owned collector interface and attach it itself. The first
keeps core's API at one type and is the current expectation; the second is easier to give a distinct
lifecycle to, which matters for `resetForReuse`. Whichever wins, the constraint is fixed: core names
no telemetry type, and telemetry does not reach the engine builder.

*Resolved by #34, in favour of the second candidate.* `SuperPlayer.Builder.setTelemetry` takes a
core-owned `TelemetryCollector` — `attach`, `startSession`, `endSession`, `detach` — which
`superplayer-telemetry` implements as `QoeCollector(sink)`. Two things decided it. The first is that
collector-as-sink makes the wrong call compile: `setTelemetry(mySink)` type-checks, because a bare
sink *is* a `TelemetrySink`, and produces a player that measures nothing at all — the object that
knows how to derive events being the one the consumer forgot to wrap. A distinct collector type makes
that unexpressible. The second is `resetForReuse`, which this ADR already anticipated: a pooled
player closes a session and opens another without ever being released, so the collector needs a
lifecycle the sink has no shape for. The price is the one this ADR named — core's telemetry API is
two types rather than one, and `PRD.md` §2.2's literal spelling gains a wrapper.

The registration itself sits in `QoeCollector.attach`, reaching the engine through
`SuperPlayer.exoPlayer`, and not in core. That is forced rather than chosen: the only thing Media3
offers to register is an `AnalyticsListener`, so a core interface that handed one back would put an
`@UnstableApi` type in core's public API and fail `verifyNoUnstableMedia3InPublicApi`. Rule 2 still
holds because core calls `attach` only when a collector was supplied — asserted, not asserted about,
by `SuperPlayerTelemetryTest.aPlayerBuiltWithNoTelemetryRegistersNoAnalyticsListener`, which counts
registrations on Media3's own analytics collector and compares a player built with telemetry against
one built without.

The second is **what the metrics mean.** TTFF's start boundary,
the rebuffer denominator, what "seek-induced" means at the edges, and the dropped-frame denominator
are standards questions rather than architectural ones, and they belong to the schema issue and to
`docs/telemetry-schema.md`. This ADR fixes where those definitions live and what may change them; it
does not pre-empt them. TTFF's start boundary is the one to watch, because `PRD.md` §3.4 puts it at
user intent, which is upstream of anything the engine can observe and therefore needs an API of its
own.

## Alternatives considered

**Forward Media3's own analytics types to the sink.** Rejected, and it is the alternative that has to
be beaten rather than dismissed, because it has a genuine advantage this decision gives up: no
mapping layer, and therefore no drift, and therefore no class of bug where SuperPlayer's number and
the engine's disagree. It loses on all three counts in the context above — it cannot satisfy
ADR-0001 rule 2, it binds an app's data pipeline to Media3's release cadence, and it delegates the
metric definitions to something that has no opinion about CTA-2066, which is the specific thing F8
says kills comparability. The first count alone is mechanical enough to fail the build; the third
is the one that would still matter if it did not.

**Put the sink interface in `superplayer-telemetry` and keep core ignorant of it.** Rejected. It is
the cleaner-looking split — core stays free of a concern it does not implement — but it cannot
express `SuperPlayer.Builder.setTelemetry(...)`, which is the API `PRD.md` §2.2 promises, without
core depending on a later-phase module. The alternative spelling, where telemetry contributes an
extension on core's builder, needs core to publish a neutral attachment hook anyway; that hook is
this seam with an extra step and a worse discovery story. `PlaybackPolicy` already set the precedent
and it has held.

**Expose telemetry as a `Flow` rather than as a sink interface.** Rejected, narrowly, and it would be
reasonable. A `Flow` gives buffering, conflation and backpressure operators for free — the exact
subject of rule 3 — and reads better from Kotlin. It loses on three smaller counts that add up: it
makes the buffering policy the consumer's to get right when rule 3 exists precisely to take that
decision away from them; it puts a coroutines dependency in `superplayer-core`'s public API for
consumers who are not otherwise using them, including the FFI-facing case `PRD.md` F2 names; and a
cold `Flow` with no collector is a different "telemetry is off" story than rule 2's. A `Flow`
adapter over the sink is additive and costs nothing to add later, so this is a default rather than a
prohibition.

**Promise reliable delivery: a durable queue, retries, survival across process death.** Rejected. It
is what a data team would prefer, and it is incompatible with ADR-0006 rule 2 — durability means
choosing storage on a consumer's behalf, which is the one thing that ADR says SuperPlayer does not
do. It is also the wrong layer: an app's analytics SDK already has a durable queue, has been
configured with the app's own retention and consent rules, and is the thing that should own delivery
past the process boundary. SuperPlayer's job is to hand over a correct event promptly and to admit
what it dropped.

**Route CMCD through the sink as one more event type.** Rejected. It is superficially unifying —
both are "client state going outward" — and it is wrong in every mechanical particular: CMCD's
consumer is the CDN rather than the app, its schema is CTA-5004's and not ours to version under
rule 5, it attaches per request at `TransferChain` rather than per session, and Media3 already
computes and transmits it. Modelling it as a sink would mean re-implementing what the engine does
in order to hand it to somebody who did not ask for it. Rule 6's shared `sid` is the coupling that
is actually wanted.

**Call the sink synchronously on the analytics thread and let consumers deal with threading.**
Rejected. It is the smallest implementation and it moves the hardest guarantee onto the least
informed party. The failure mode is specific and has a known shape: a consumer's sink performs a
synchronous network write, the analytics thread blocks, and the symptom the user reports is
stuttering video — with nothing in the logs pointing at telemetry, and the app taking the blame for
the library's contract. Rule 4 exists because that failure is invisible in exactly the environment
where telemetry is developed and tested.

**Ship a sink that leaves the device — an HTTP sink, or one for a named QoE vendor.** Rejected, and
out of scope for the phase. It would put an outbound network path, a retention decision and a
consent surface inside a playback library. SuperPlayer ships the interface and a logcat
implementation; where events go is the app's decision, and the app is the party that can answer for
it.

## References

- CTA-2066, Streaming Quality of Experience Events, Properties and Metrics — the definitions the
  schema follows, and the reason rule 1 needs a mapping layer at all. Published by the Consumer
  Technology Association and located through its standards catalogue at https://shop.cta.tech; it
  is not freely mirrored, which is why this entry is a locator rather than a link.
- CTA-5004, Common Media Client Data (CMCD):
  https://cdn.cta.tech/cta/media/media/resources/standards/pdfs/cta-5004-final.pdf
- `AnalyticsListener` —
  https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener
- `PlaybackStatsListener` and `PlaybackStats` —
  https://developer.android.com/reference/androidx/media3/exoplayer/analytics/PlaybackStatsListener
- [ADR-0001](0001-compose-dont-fork.md) rule 2 — no `@UnstableApi` Media3 type in public API, which
  is why rule 1's list is a build failure rather than a preference.
- [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md) — the same boundary
  argument running inbound, and the precedent for rule 2's module split.
- [ADR-0006](0006-own-the-platform-rules-and-hand-back-the-state.md) rule 2 — SuperPlayer chooses no
  storage, which is what makes rule 3's at-most-once delivery a consequence rather than a choice.
- `PRD.md` §2.2 (the builder), §2.4 (the transfer chain CMCD attaches to), §3.4 (the telemetry
  requirement), and F8 (the failure this boundary exists to close).
