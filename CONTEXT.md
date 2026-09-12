# SuperPlayer

A production playback layer over AndroidX Media3: policy, resilience, observability and lifecycle,
composed on the engine rather than forked from it. This glossary is the vocabulary the code, the
ADRs and the issues share; a term defined here is used as defined, and its synonyms are avoided.

## Language

### Policy

**Profile**:
The kind of playback a consumer declares for a player — on-demand, live linear, short-form, data
saver — fixed for the player's lifetime. A profile is intent, and names a use case rather than
carrying numbers.
_Avoid_: Preset, mode, configuration

**Policy**:
The thing that turns observed conditions into a decision. It names no engine type, is built for one
profile, and is the only place buffering and track selection are decided.
_Avoid_: Strategy, tuner, config

**Decision**:
A policy's answer: how much to buffer and what ceiling to select tracks under. The *decision in
force* is the one the player is currently running.
_Avoid_: Settings, parameters, tuning

**Conditions**:
What is known about a playback at the moment a policy is asked. Every part of it is an
observation, and every observation is optional, because at construction nothing has been observed.
_Avoid_: State, context, metrics

**Observation**:
One fact about the playback or the device, expressed in SuperPlayer's own vocabulary rather than
the platform's or the engine's. An observation that has not been made is *unobserved*, which is
distinct from zero.
_Avoid_: Signal, input, sample

**Constraint**:
A fact about the device that does not change under a playing session — the display's mode, what
the decoder can sustain — and is therefore read once rather than observed. A constraint bounds what
is eligible; it is not a condition.
_Avoid_: Capability (when meaning the reading), limit

**Transport**:
The class of network a playback is on: WiFi, cellular, ethernet, or unknown. Cellular carries a
*generation* (NR, LTE, or older). A transport is the coarsest key that still distinguishes the
networks whose throughput differs.
_Avoid_: Network type, connection, interface

**Throughput estimate**:
What the network is believed to deliver, as a mean, a *spread*, a conservative percentile, and the
age and count of the samples behind it. The spread is what predicts a stall; the mean is what most
players use.
_Avoid_: Bandwidth, bitrate estimate, speed

**Spread**:
How far recent throughput samples scatter around their mean, in the same unit. High spread raises
a playback floor; it never raises a ceiling.
_Avoid_: Variance (as a public name), jitter, noise

**Stall history**:
What this session has already suffered: how many rebuffers, how long since the last one ended, and
how long it lasted. A policy's hysteresis is computed from it.
_Avoid_: Rebuffer count, error history

**Stream type**:
What the manifest declared the content to be — on-demand or live — as opposed to what the profile
assumed. Short-form is a profile, not a stream type, because no manifest says it.
_Avoid_: Content type, media type, live flag

**Trigger**:
One of the named events on which a policy is consulted again after construction: a transport
change, the stream type becoming known or changing, a rebuffer ending, a speed change, or the
throughput estimate moving materially. A decision changes only on a trigger.
_Avoid_: Tick, refresh, poll

**Re-application**:
Handing a changed decision to engine components that can honour it whole after construction. A
player without such components is consulted once and never re-applies.
_Avoid_: Reconfiguration, hot reload, update

### Modules and seams

**Engine**:
Media3's `ExoPlayer` and the components it is built from. The engine is on the far side of every
boundary; nothing of it appears in a public SuperPlayer type.
_Avoid_: Backend, core (which names a module), the player (which names the facade)

**Facade**:
`SuperPlayer` itself: a Media3 `Player` by delegation, which is what lets existing surfaces take it
unchanged.
_Avoid_: Wrapper, adapter

**Boundary**:
A public interface across which no engine type travels: the policy boundary, the telemetry sink
boundary. The translation to and from the engine's vocabulary happens in one internal file per
direction.
_Avoid_: Abstraction, layer, API (when meaning the seam)

**Seam**:
The one internal place a test, or a sibling module compiled as a friend, may reach the engine's
construction. It is enumerated, not open-ended, and it is never public.
_Avoid_: Hook, backdoor, extension point (when meaning this one)

**Friend**:
A module of this repository compiled with access to core's internal declarations, as core's own
tests have. A consumer is never a friend. Testkit and abr are the two.
_Avoid_: Plugin, extension module

**Extension**:
What an adaptive policy is when it also carries the engine components its decisions need: a policy
to the consumer, an engine configurator to core. It reaches core through the seam, as a friend.
_Avoid_: Plugin, provider, service

### Memory

**Estimate memory**:
The per-transport throughput estimates a process keeps, in memory, so that a handover reseeds from
the transport arrived at rather than the one left. Keyed by transport and nothing finer; forgotten
with the process.
_Avoid_: Cache, store, persisted estimates

**Cold default**:
The documented throughput estimate a transport starts from when nothing has been measured on it in
this process, or when what was measured is too old to trust.
_Avoid_: Initial bitrate, seed, fallback

**Snapshot**:
The state that outlives a player, as a value the consumer stores. SuperPlayer chooses no storage on
a consumer's behalf; estimate memory is not a snapshot, because it belongs to the process rather
than to a player.
_Avoid_: Saved state, checkpoint
