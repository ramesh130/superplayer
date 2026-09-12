# SuperPlayer

A production playback layer over AndroidX Media3: policy, resilience, observability and lifecycle,
composed on the engine rather than forked from it. This glossary is the vocabulary the code, the
ADRs and the issues share; a term defined here is used as defined, and its synonyms are avoided.

## Language

### Policy

**Profile**:
The kind of playback a consumer declares for a player, fixed for its lifetime: a use case, never a
set of numbers.
_Avoid_: Preset, mode, configuration

**Policy**:
The one thing that turns conditions into a decision, built for a single profile and naming no
engine type.
_Avoid_: Strategy, tuner, config

**Decision**:
A policy's answer: how much to buffer and what ceiling to select tracks under. The *decision in
force* is the one the player is currently running.
_Avoid_: Settings, parameters, tuning

**Conditions**:
Everything known about a playback at the moment a policy is asked, each part an observation and
each part optional.
_Avoid_: State, context, metrics

**Observation**:
One fact about the playback or the device in SuperPlayer's own vocabulary. An observation not yet
made is *unobserved*, which is distinct from zero.
_Avoid_: Signal, input, sample

**Constraint**:
A fact about the device that does not change under a playing session and so bounds what is
eligible rather than being observed.
_Avoid_: Capability (for the reading), limit

**Network transport**:
The class of network a playback is on: WiFi, cellular with its generation, ethernet, or unknown.
Distinct from the transfer chain's *transport slot*, which is the HTTP stack a chain sits over.
_Avoid_: Network type, connection, interface

**Throughput estimate**:
What the network is believed to deliver: a mean, a spread, a conservative percentile, and the age
and count of the samples behind them.
_Avoid_: Bitrate estimate, speed

**Spread**:
How far recent throughput samples scatter around their mean, in the same unit.
_Avoid_: Variance (as a public name), jitter, noise

**Stall history**:
What this session has already suffered: how many rebuffers, how long since the last ended, and how
long it lasted.
_Avoid_: Rebuffer count, error history

**Stream type**:
What the manifest declared the content to be, on-demand or live, as opposed to what the profile
assumed.
_Avoid_: Content type, media type, live flag

**Trigger**:
One of the named events on which a policy is consulted again after construction. A decision
changes only on a trigger.
_Avoid_: Tick, refresh, poll

**Re-application**:
Handing a changed decision to engine components that can honour it whole after construction.
_Avoid_: Reconfiguration, hot reload, update

### Engine and boundaries

**Engine**:
Media3's player and the components it is built from, on the far side of every boundary and absent
from every public SuperPlayer type.
_Avoid_: Backend, core (which names a module), the player (which names the facade)

**Boundary**:
A public interface across which no engine type travels, translated to and from the engine's
vocabulary in one internal file per direction.
_Avoid_: Abstraction, layer, API (for the seam)

### Memory

**Estimate memory**:
The per-transport throughput estimates a process keeps in memory, keyed by network transport and
nothing finer, and forgotten with the process.
_Avoid_: Cache, store, persisted estimates

**Cold default**:
The documented throughput estimate a transport starts from when nothing recent has been measured
on it in this process.
_Avoid_: Initial bitrate, seed, fallback

**Snapshot**:
The state that outlives one player, as a value the consumer stores. Estimate memory is not a
snapshot, because it belongs to the process rather than to a player.
_Avoid_: Saved state, checkpoint
