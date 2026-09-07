# ADR-0005: Decide playback policy behind an engine-agnostic boundary, and ship a static one

- **Status:** Accepted
- **Date:** 2026-09-06
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None

## Context

Buffering and track-selection settings are the part of a playback stack every team re-derives and
nobody can defend. Media3's own defaults are tuned for a general case — 50 seconds of buffer in both
directions, 2.5 seconds before playback starts, no back buffer — and an app that wants something
else reaches `DefaultLoadControl.Builder().setBufferDurationsMs(a, b, c, d)` with four numbers
copied out of an answer written for someone else's content. `PLAN.md` §1.1 records this as P2 and
P6: one policy for every transport and every content shape, static after construction, re-derived
badly per app.

Two things have to be decided at once, and they are usually conflated.

The first is *what the numbers should be*, which is a product question with different answers for
long-form on-demand, a live channel, a short-form feed and a viewer who has asked to spend less
data. Those answers can be written down, and writing them down is most of the value.

The second is *where the decision lives*. In an ordinary Media3 integration it lives nowhere: it is
scattered across a `LoadControl` built at one call site, `TrackSelectionParameters` set at another,
and whatever a listener does to both when the network changes. That scattering is what makes an
adaptive policy a rewrite rather than an addition, because there is no one place to make adaptive.

The adaptive version is real work, and it is not this phase's. `superplayer-abr` (phase 2) is where
throughput estimation, transport awareness and an `AdaptiveLoadControl` belong, and it is the work
that will reveal what an adaptive policy actually needs to observe. Designing the input type to a
policy interface *before* that work exists means guessing at the shape of a public API, and guessing
wrong there is expensive.

One Media3 constraint bounds every answer: `DefaultLoadControl` takes its durations when it is built
and does not accept new ones. Buffer policy is therefore construction-time until SuperPlayer ships a
`LoadControl` of its own, which is `superplayer-abr`'s subject. Track-selection ceilings, by
contrast, are `TrackSelectionParameters` and can be set at any time.

## Decision

**All playback policy is decided behind one engine-agnostic interface, `PlaybackPolicy`, which maps
observed conditions (`PlaybackConditions`) to a decision (`PlaybackDecision`). The implementation
shipped in phase 1 is a static per-profile lookup, and it is documented as deliberately
non-adaptive.**

Four rules follow, and they are binding:

1. **No Media3 type appears in the boundary.** Neither `PlaybackConditions`, `PlaybackDecision`,
   `BufferPolicy` nor `TrackSelectionPolicy` names a Media3 type, and no `PlaybackPolicy` is handed a
   `Player`, a `LoadControl` or a `TrackSelector`. The translation into Media3's vocabulary happens
   in one internal file (`EngineBinding.kt`) and nowhere else.
2. **Policy is not decided anywhere but here.** A buffering or selection constant that appears at a
   Media3 call site, in a listener callback, or in a builder, is a bug against this ADR, whatever it
   is worth. Adding an adaptive policy is then a new implementation of this interface rather than a
   restructuring of the code around it. The rule is about *policy* — buffering and track selection,
   the things that have different right answers for different content. It does not reach settings
   that have one right answer for all of it: [ADR-0006](0006-own-the-platform-rules-and-hand-back-the-state.md)
   draws that line for Android's lifecycle rules, and argues why they sit outside this boundary
   rather than inside it.
3. **The consumer names a profile, not a number.** `SuperPlayer.Builder.setProfile` takes a
   `PlaybackProfile`; the constants behind it, and the rationale for each of them, live in the
   policy's per-profile table, which is internal and free to be retuned. Every constant that departs
   from Media3's default says why it departs.
4. **The policy is consulted once, at construction, until the buffer half can be re-applied.** Half
   of a decision cannot be honoured after the engine exists, so consulting the policy repeatedly
   today would produce answers that are half in force — a worse contract than a documented
   construction-time one. `SuperPlayer.playbackDecision` reports what was applied.

## Consequences

**Easier.** A consumer chooses a use case and gets a documented configuration, which is already more
than most apps have. The decision is readable back off the player, so what a profile did is
observable rather than asserted — the demo prints it, and the tests assert it through the facade
without reaching for `exoPlayer`. When `superplayer-abr` arrives, it implements this interface and
the facade's construction path is the only thing that has to learn about it.

**Harder.** There are now two public types (`PlaybackProfile` and the policy boundary) where a
simpler library would have four builder setters, and the indirection buys nothing measurable in
phase 1 — its whole value is in phase 2. That is the bet this ADR makes, and it is worth stating as
a bet rather than as a benefit.

**A standing obligation.** `PlaybackConditions` is public and currently empty of observations: the
one call site passes nothing, because at construction nothing about the content is known. Every
property it grows is a public API addition, and the temptation will be to add properties
speculatively. It grows when an implementation needs to *read* one, not before.

**A cost that lands on consumers.** Profiles retune. A consumer who has measured against
`VIDEO_ON_DEMAND`'s current numbers and depends on them exactly has no way to pin them, because the
table is internal. That is deliberate — a profile is a use case, not a configuration — but it means
a retune is a behavioural change in a minor release, and it has to be released as one.

## Alternatives considered

**Builder setters for the constants: `setMinBufferMs`, `setMaxVideoBitrate`, and so on.** Rejected.
It is the status quo with a different spelling: the consumer still has to know which numbers a use
case wants, which is the hard part, and the library ends up with a public setter for every knob it
ever wants to make adaptive. Profiles do not preclude this — a `setPolicy` or per-knob override can
be added later — but shipping it first would make the knobs the API and the profiles a convenience,
which is backwards.

**Ship the adaptive policy now.** Rejected, and it is the alternative the issue itself argues
against. It would fix `PlaybackConditions` against a guess at what an adaptive implementation needs
to see, before the implementation that would have said so exists.

**Skip the interface; put the per-profile table where the engine is built.** Rejected. It is the
cheaper half of this change and it is the half that does not pay: a table inlined into the builder is
exactly the scattering rule 2 exists to prevent, and making it adaptive later means moving every
call site that reads it.

**Keep the boundary internal, exposing only `PlaybackProfile` and the decision a player is
running.** Rejected, narrowly. It is the smaller public surface, and no consumer can supply a policy
today, so the interface is published for reading rather than for implementing. It is published
because a boundary nobody can see is one nobody can hold the library to: `PlaybackPolicy.forProfile`
lets an app show what data-saver actually means next to the switch that turns it on, without building
a player. If a `setPolicy` follows, it needs no API change; if profiles turn out to be the whole
story, this is one interface to have published and not four builder setters.

**Make `PlaybackProfile` carry its own numbers as constructor arguments.** Rejected. It reads well,
but it makes the public enum a public configuration: a consumer could read `profile.minBufferMs`,
and a retune would then be a visible API-surface change to a value nobody promised. The profile is a
name; the numbers are the policy's.

## References

- `DefaultLoadControl` and its defaults:
  https://developer.android.com/reference/androidx/media3/exoplayer/DefaultLoadControl
- `TrackSelectionParameters`:
  https://developer.android.com/reference/androidx/media3/common/TrackSelectionParameters
- [ADR-0001](0001-compose-dont-fork.md) rule 2 — no `@UnstableApi` Media3 type in public API, which
  is why the boundary's own types name none.
- `PLAN.md` §1.1 (P2, P6) and §3.1 — the field complaints this policy layer answers, and where the
  adaptive implementation belongs.
