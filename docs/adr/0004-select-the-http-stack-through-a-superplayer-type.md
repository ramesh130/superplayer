# ADR-0004: Select the HTTP stack through a SuperPlayer-owned type

- **Status:** Proposed
- **Date:** 2026-09-06
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None

## Context

Media3 can load over several HTTP transports, and they are not peers:

| Transport | Ships in | What the consumer must supply | Cost |
| --- | --- | --- | --- |
| `DefaultHttpDataSource` | `media3-datasource` | nothing | none; the current default |
| `HttpEngineDataSource` | `media3-datasource` | an `android.net.http.HttpEngine` | none in APK size; the platform API is Android 14 (API 34) and up |
| `OkHttpDataSource` | `media3-datasource-okhttp` | an `okhttp3.Call.Factory` | a dependency, and a client the app usually already has |
| `CronetDataSource` | `media3-datasource-cronet` | a `CronetEngine` | `play-services-cronet` ships under the Android Software Development Kit License rather than a permissive one; `cronet-embedded` is megabytes of APK |
| `KtorDataSource` | `media3-datasource-ktor` (1.11.0-alpha01) | a Ktor `HttpClient` | a dependency; the artifact is alpha |

Two of the five arrived after the first three. The list is not stable, and any API that enumerates
it will be wrong within a release or two.

Today the choice cannot be made at all. `SuperPlayer.Builder` exposes only `build()`, and the seam
that reaches `ExoPlayer.Builder` is `internal` so that tests can substitute Media3's fakes without
widening public API. The `player.exoPlayer` escape hatch that ADR-0001 rule 2 guarantees does not
help here: `ExoPlayer` has no runtime media-source-factory setter — only `setMediaSource` and
`setMediaSources` — so the transfer chain is fixed when the engine is constructed and cannot be
replaced afterwards. This is a genuine gap in what a consumer can express, not an ergonomic one.

Three constraints bound any answer:

1. **`@UnstableApi`.** `DataSource`, `DataSource.Factory`, `HttpDataSource.Factory`,
   `DefaultDataSource`, `DefaultHttpDataSource` and `HttpEngineDataSource` all carry the marker
   (checked against `media3-datasource` 1.11.0). ADR-0001 rule 2 and
   `verifyNoUnstableMedia3InPublicApi` mean none of them may appear in a public signature: a leak
   fails the build.
2. **Core depends on Media3 and nothing else** (`docs/modules.md`). OkHttp, Cronet and Ktor support
   cannot arrive as core dependencies without every consumer paying for a choice most will not make,
   and the Cronet artifacts additionally sit outside the permissive-licence policy in
   `CONTRIBUTING.md`.
3. **Measurement must survive the choice.** Every transport reaches `DefaultBandwidthMeter` through
   `TransferListener`. A stack that is wired so that transfer events are lost blinds bandwidth
   estimation, and phase 3's ABR then degrades with nothing in the logs. ADR-0002 rejected the
   loopback proxy for that same failure — measurement describing something other than the network.

Worth separating from the mechanism: teams rarely want a named transport as such. They want shared
auth interceptors and headers with the client the app already has, or HTTP/3, or a single connection
pool. Those are separable requirements, and per-request headers in particular probably belong on
`MediaRequest` rather than on a stack selector.

## Decision

**No public HTTP-stack API ships until a phase needs one. When it does, selection is expressed as a
SuperPlayer-owned type, and every transport beyond what `media3-datasource` already carries is
supplied by an optional module the consumer adds.**

The rules that follow from this, binding on whoever implements it:

1. **No Media3 type in the signature.** `Builder.setHttpStack(HttpStack)` takes a SuperPlayer type.
   The Media3 factory is constructed behind it. A public API typed on `HttpDataSource.Factory` is
   not an option, and neither is a re-export under another name.
2. **Core carries only what `media3-datasource` already carries** — the default stack and
   `HttpEngine`. OkHttp, Cronet and Ktor variants are produced by optional modules
   (`superplayer-net-okhttp` and siblings), so core's dependency graph stays Media3-only and no
   non-permissive licence enters this repository.
3. **The consumer supplies the client, not a flag.** `okHttp(callFactory)`, not `OKHTTP`. The point
   of choosing OkHttp is nearly always to share the app's existing client; an enum value that
   constructs a second one privately would defeat the reason for asking.
4. **A selection that cannot be honoured is reported, not silently dropped.** `HttpEngine` on
   API 33, or a module that is not on the classpath, must fail at `build()` with a typed error or
   fall back with a recorded reason. Silent substitution makes a bandwidth-estimate anomaly
   impossible to explain later.
5. **Every variant preserves `TransferListener` reporting end to end**, verified by a test rather
   than asserted in a comment.

The internal seam this plugs into is separate work and is already tracked: issue #22 composes the
transfer chain in one place in `superplayer-core`, with cache, measurement, CMCD and header refresh
as the named insertion points. That seam is worth building on its own schedule; this decision is
about what, if anything, is exposed above it.

## Consequences

**Easier.** Later phases plug into one documented composition point rather than each reaching for
the engine builder. The public surface stays small while the question is still open, and the
answer can arrive without breaking anyone, because there is nothing to break yet. The licence and
APK-size questions stay outside core permanently rather than being argued once per consumer.

**Harder.** Optional per-transport modules are not in the module table in `docs/modules.md`, so
adopting this decision means extending that table and the phase ordering with it. More seriously,
the cross-module visibility does not fall out cleanly: a `sealed` `HttpStack` cannot be extended
from another Gradle module, and Kotlin's `internal` does not span modules either, so an optional
module cannot construct a core-owned variant through a hidden constructor. Three candidate
resolutions, none yet chosen and all of which should be settled before this ADR moves to Accepted:

- an opt-in `@InternalSuperPlayerApi` annotation on the plumbing the optional modules call, in the
  same spirit as Media3's own `@UnstableApi` discipline;
- a non-sealed `HttpStack` interface with a single method returning an opaque handle, which trades
  exhaustiveness for extensibility;
- `compileOnly` dependencies plus runtime detection inside core, which keeps one module but puts
  reflection on a path that must not fail silently.

**Maintenance.** Each optional module is a version to track against both Media3 and the client
library it wraps, and each needs its own `THIRD_PARTY.md` row. A transport Media3 adds later is a
new module, not a new enum constant — cheaper to add, but the docs must say where it goes.

**Deliberately deferred.** Until this is implemented, a consumer who needs a specific transport has
no route at all. That is a real cost, accepted on the grounds that no phase currently needs it and
that shipping the wrong shape is more expensive than shipping nothing. If a consumer need arrives
before a phase does, that is the trigger to accept or replace this ADR.

## Alternatives considered

**An enum of four values (`DEFAULT`, `OKHTTP`, `CRONET`, `HTTP_ENGINE`).** Rejected on three
counts. The list is not stable — Ktor arrived at 1.11.0-alpha01 and would already make it wrong.
Three of the four need a consumer-supplied client object that an enum has nowhere to carry. And it
would put OkHttp and Cronet dependencies in core for the benefit of the consumers who name them,
charging everyone else.

**Expose Media3's `HttpDataSource.Factory` directly.** Rejected: it is `@UnstableApi`, so
`verifyNoUnstableMedia3InPublicApi` fails the build, and ADR-0001 rule 2 limits the escape hatch to
`player.exoPlayer` precisely so that the marker does not spread across the API a consumer touches.

**Depend on OkHttp in core and make it the default.** Rejected: it charges every consumer for one
team's requirement, and picking a winner in core is the kind of decision that is very hard to
reverse once apps depend on the transitive dependency being there.

**Do nothing and point at the escape hatch.** Rejected on fact, not on taste: `ExoPlayer` has no
runtime media-source-factory setter, so `player.exoPlayer` cannot install a transport after
`build()` has run.

**Ship the public selector at the same time as the internal seam.** Rejected as premature. The seam
has four internal consumers already named in the roadmap and pays for itself immediately; the
selector has none, and its shape depends on a cross-module visibility question that is still open.

## References

- Media3 artifact index — `media3-datasource`, `media3-datasource-okhttp`,
  `media3-datasource-cronet`, `media3-datasource-ktor`:
  https://dl.google.com/dl/android/maven2/androidx/media3/group-index.xml
- `android.net.http.HttpEngine`, added in Android 14 (API 34):
  https://developer.android.com/reference/android/net/http/HttpEngine
- Media3 `DataSource` and network stack customisation:
  https://developer.android.com/media/media3/exoplayer/customization
- [ADR-0001](0001-compose-dont-fork.md) rule 2 — the `@UnstableApi` boundary and the single escape
  hatch.
- [ADR-0002](0002-no-local-http-proxy.md) — bandwidth estimation must describe the network the
  player is about to use.
- [`docs/modules.md`](../modules.md) — dependencies point inward toward core; core depends on Media3
  only.
- [`docs/api-surface.md`](../api-surface.md) — `verifyNoUnstableMedia3InPublicApi`.
- Issue #22 — compose the transfer chain in one place in `superplayer-core`.
