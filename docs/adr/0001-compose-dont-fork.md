# ADR-0001: Compose on Media3, do not fork it

- **Status:** Accepted
- **Date:** 2026-09-05
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None

## Context

SuperPlayer's purpose is the policy, resilience, observability, and lifecycle layer that sits above
a media engine. AndroidX Media3 (ExoPlayer) is that engine, and it is a good one: its HLS and DASH
parsers have absorbed more real-world manifest pathology than this project could ever reproduce,
and its `Player` interface is the integration contract that `PlayerView`, `MediaSession`,
notifications, Android Auto, and the Compose media surfaces are all written against.

There are three ways to build on top of it:

1. **Fork it** — vendor the Media3 source, patch it where the design gets in the way, and ship the
   result.
2. **Compose on it** — depend on released Media3 artifacts and extend only through its public,
   documented extension points.
3. **Replace it** — write a new engine.

Forking is tempting because it removes every "the extension point doesn't quite let me do that"
obstacle immediately. It is also how a project acquires a permanent, compounding maintenance
liability: each upstream release must be re-merged by hand, security fixes arrive late, and the
divergence grows monotonically because there is never a moment when re-basing is cheaper than
patching again. Replacing the engine is out of scope by an order of magnitude and would throw away
the ecosystem compatibility that makes the facade adoptable at all.

Media3 also publishes a substantial surface behind `@UnstableApi`, which by its own documentation
may change or be removed in any release. Consumers must opt in to use it. Any dependence SuperPlayer
takes on that surface is therefore both an upgrade risk for this project and, if it leaks into the
public API, a compile-time burden pushed onto every consumer.

## Decision

**SuperPlayer depends on Media3 as an ordinary, versioned dependency and extends it only through
public, documented extension points. No Media3 source is vendored, patched, or forked into this
repository.**

The extension points SuperPlayer builds on are Media3's own: `LoadControl`, `TrackSelector`,
`DataSource.Factory`, `MediaSource.Factory`, `DrmSessionManager`, `LoadErrorHandlingPolicy`,
`RenderersFactory`, `AnalyticsListener`, and `Player` itself, which the `SuperPlayer` facade
implements by delegation to a wrapped `ExoPlayer`.

Three rules follow, and they are binding:

1. **No vendored engine source.** A copy of Media3 code in this repository — whole files, patched
   files, or a class copied out to change three lines — is not accepted. Where the engine's
   behavior is wrong for our purposes, the fix is an extension point, an upstream contribution, or
   a documented limitation.
2. **Unstable Media3 surface is wrapped, never re-exported.** Where an `@UnstableApi` type is
   genuinely required, it is used only behind a SuperPlayer-owned interface expressed in
   SuperPlayer's own types, with a compat shim absorbing the differences between supported Media3
   versions. No `@UnstableApi` Media3 type appears in SuperPlayer's public API — not as a
   parameter, a return type, a supertype, a type argument, or a public field. Consumers are never
   forced to opt in to instability that SuperPlayer chose to take on. Each such wrapper carries a
   test that pins the behavior against the pinned Media3 version, so an upgrade that changes the
   surface fails in CI here rather than silently in a consumer's app.

   **One named exception: the engine escape hatch.** The wrapped `ExoPlayer` instance is exposed as
   public API. `ExoPlayer` is itself an `@UnstableApi` type, so this is a deliberate, recorded
   breach of the rule above rather than an oversight. A playback library that hides its engine
   cannot be debugged in production, and the cost of that — a consumer who reaches for the escape
   hatch opts in to Media3's instability at the point of use — is smaller than the cost of a
   contributor being blocked at 2am by a missing wrapper method. The exception is exactly one
   member. It does not license re-exporting any other `@UnstableApi` type, and anything reachable
   only through the escape hatch is outside SuperPlayer's compatibility promise.
3. **One pinned Media3 version.** Media3 versions are declared in exactly one place — the Gradle
   version catalog — and every module resolves through it. Modules document the Media3 version
   range they support.

## Consequences

**Easier.** Upgrading Media3 is a version-catalog edit plus a test run, not a merge. Upstream bug
fixes and security patches arrive on Media3's release cadence rather than ours. Every Media3-
compatible UI, session, and analytics integration keeps working against the facade unchanged,
because the facade is a real `Player`. Contributors who know ExoPlayer already know most of this
codebase.

**Harder.** Where Media3 has no extension point for something we want, we cannot simply take it.
The available responses are narrower and slower: find a supported composition of existing hooks,
contribute the extension point upstream, or accept the limitation and record it. Some of what a
fork would make trivial will instead be deferred.

**Ongoing cost.** The `@UnstableApi` wrappers are real maintenance: each one is an interface, an
implementation, and a pinning test that must be revisited on Media3 upgrades. That cost is
deliberate — it is bounded, visible in the module it lives in, and it is what keeps the instability
from reaching consumers.

**Constraint on future work.** A proposal to fork must argue against this record. Growing pain at a
Media3 boundary is not by itself grounds to fork; it is grounds to open the question of whether the
extension point should exist upstream.

## Alternatives considered

**Fork Media3.** Rejected. It converts every upstream release into manual merge work, delays
security fixes, and diverges without bound. The problems SuperPlayer exists to solve — buffer and
bitrate policy, failover, telemetry, lifecycle — are all reachable from the public extension points,
so the cost buys nothing we need.

**Write a new engine.** Rejected. Re-implementing HLS and DASH parsing, the renderer pipeline, and
DRM integration is a multi-year effort whose output would be strictly worse than the engine already
available under Apache-2.0, and it would forfeit compatibility with the Media3 UI and session
ecosystem.

**Depend on Media3 but re-export its `@UnstableApi` types freely.** Rejected. It is the cheapest
short-term option and it pushes the instability directly onto consumers, who must then opt in and
absorb breaking changes on Media3's schedule for a dependency they did not choose.

**Wrap everything, with no escape hatch.** Rejected. It is the consistent position, and it fails the
first time a consumer needs a method the wrapper does not expose. Every playback library that has
hidden its engine has been worked around by reflection or forked outright. One documented exception
is cheaper than either.

## References

- AndroidX Media3 documentation, including the customization and extension guides:
  https://developer.android.com/media/media3
- Media3 `@UnstableApi` policy: https://developer.android.com/reference/androidx/media3/common/util/UnstableApi
- Media3 source and release notes (Apache-2.0): https://github.com/androidx/media
