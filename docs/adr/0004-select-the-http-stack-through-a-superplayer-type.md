# ADR-0004: Select the HTTP stack through a SuperPlayer-owned type

- **Status:** Accepted
- **Date:** 2026-09-06
- **Amended:** 2026-09-18 — Phase 10 is the phase that needs one, which is the trigger this record
  named. Rules 2 and 3 are **rewritten** below, at the rules themselves, and the open cross-module
  visibility question in *Consequences* is **closed**: no optional per-transport module ships, so
  none of its three candidate resolutions is needed. [ADR-0016](0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md)
  is the shape that amendment takes and binds the implementation.
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Summary:** No public HTTP-stack selection API ships until a phase actually needs one. When it
  does, selection is expressed as a SuperPlayer-owned type (never a Media3 type), and a transport
  beyond what `media3-datasource` already carries arrives as an interface the *consumer* implements
  over the client their app already has — core's dependency graph stays Media3-only, and no
  per-transport module ships at all.

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

*Amendment (2026-09-18).* Phase 10 is that phase. The first clause held: nothing shipped for nine
phases, and the shape that arrived is not the shape this record guessed at. The last clause is
**withdrawn** — no optional module ships. What a consumer supplies is an implementation of one
SuperPlayer interface, over the client their app already has, which reaches further than a module per
named transport could: it covers OkHttp, Ktor, Cronet and a client this project has never heard of,
at the cost of core owning an HTTP contract rather than a list. Rules 2 and 3 are rewritten below;
rules 1, 4 and 5 stand unchanged and are the reason the amendment is cheap.
[ADR-0016](0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md)
is the full shape.

The rules that follow from this, binding on whoever implements it:

1. **No Media3 type in the signature.** `Builder.setHttpStack(HttpStack)` takes a SuperPlayer type.
   The Media3 factory is constructed behind it. A public API typed on `HttpDataSource.Factory` is
   not an option, and neither is a re-export under another name.
2. **Core carries only what `media3-datasource` already carries** — the default stack and
   `HttpEngine` — and nothing in this repository names OkHttp, Cronet or Ktor at all. Core's
   dependency graph stays Media3-only, and no non-permissive licence enters this repository.

   *Amendment (2026-09-18).* Unchanged in what it forbids; changed in how the gap is filled. The
   original sentence continued "OkHttp, Cronet and Ktor variants are produced by optional modules
   (`superplayer-net-okhttp` and siblings)". No such module ships. A per-transport module keeps the
   dependency out of core, which is the part worth keeping, but it also fixes the list — and the
   *Context* above argues at length that the list is not stable. Ktor arrived at 1.11.0-alpha01 while
   this record was open, which is the second time in one page that enumerating transports was wrong.
   An interface the consumer implements has no list to be wrong about, and it is why Cronet's licence
   and Ktor's alpha stop being this project's problem: neither artifact is ever resolved here.
3. **The consumer supplies the transport, not a client and not a flag.** Not `OKHTTP`, and — since
   the amendment — not `okHttp(callFactory)` either. The consumer implements one SuperPlayer
   interface over whatever client they have and passes that.

   *Amendment (2026-09-18).* The original rule took the app's `okhttp3.Call.Factory` directly. Its
   reasoning survives intact — the point of choosing OkHttp is nearly always to share the app's
   existing client, and an enum value that privately constructs a second one defeats the reason for
   asking — but taking the client *by its own type* means naming that type in core's public API,
   which is what rule 2 exists to prevent. Taking an implementation instead honours both rules at
   once. The consumer still passes the client they already have; they pass it wrapped.
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

*Amendment (2026-09-18) — this question is closed, and none of the three answers it.* The problem
was a consequence of the optional module, and rule 2's amendment removes the module. There is no
second Gradle module that has to construct a core-owned variant, so there is nothing for an
annotation, an opaque handle or a `compileOnly` dependency to solve. `docs/modules.md` gains no row
either, for the same reason: Phase 10 adds no module.

Worth recording, because it is the thing that dated this paragraph: by the time the question came
due, the repository had answered a *different* version of it nine times. `ContentCache`,
`DownloadEnvironment` and `DiagnosticEnvironment` are public shells with internal members, reached
from another module through a Kotlin friend flag — so had an optional module shipped after all, the
resolution would have been a tenth friendship rather than any of the three candidates above. It is
listed under *Alternatives considered* rather than chosen, because a friendship is the answer to
"another module of **ours** needs in", and a consumer's HTTP client is not another module of ours.

**Maintenance.** Each optional module is a version to track against both Media3 and the client
library it wraps, and each needs its own `THIRD_PARTY.md` row. A transport Media3 adds later is a
new module, not a new enum constant — cheaper to add, but the docs must say where it goes.

**Deliberately deferred.** Until this is implemented, a consumer who needs a specific transport has
no route at all. That is a real cost, accepted on the grounds that no phase currently needs it and
that shipping the wrong shape is more expensive than shipping nothing. If a consumer need arrives
before a phase does, that is the trigger to accept or replace this ADR.

*Amendment (2026-09-18).* The deferral ran for nine phases and is over; Phase 10 implements this.
The bet is worth scoring rather than quietly dropping, because deferrals usually are not. It paid:
the shape that shipped is not the one this record would have shipped in Phase 1, the motivation the
*Context* called separable — "shared auth interceptors and headers with the client the app already
has" — turned out to be `HeaderProvider`'s and was solved in Phase 5 without touching the transport
at all, and what remains as a reason to bring your own client is narrower and clearer for it: one
connection pool, HTTP/3, and an app that already has a client it trusts.

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

*The three below were considered at the 2026-09-18 amendment, when the deferral ended.*

**An optional module per transport (`superplayer-net-okhttp` and siblings)** — this record's own
original answer, and the one the amendment withdrew. It keeps the dependency out of core and makes a
missing artifact a build error rather than a crash, both of which are real. Rejected because it fixes
the list of transports that the *Context* spends a table arguing is not fixable: every client this
project has not heard of needs a module this project has not written, and the consumer who needs one
is exactly the consumer who could have written the adapter themselves. It also costs a published
Maven coordinate and a Kotlin friendship per transport, permanently, for thirty lines of adapter each.

**Take the consumer's `okhttp3.Call.Factory` through `compileOnly` dependencies in core.** One
function, one line for the consumer, and Media3's own `OkHttpDataSource` doing all the HTTP care —
which is not a small thing, since that care is where this decision's risk now lives. Rejected on
rule 2: an OkHttp type would appear in core's tracked public API surface, `docs/modules.md`'s "core
depends on Media3 and nothing else" would gain a standing exception, and calling it without OkHttp on
the classpath is a `NoClassDefFoundError` at playback rather than a failure at compile time or at
`build()`. Rule 4 asks that a selection which cannot be honoured be *reported*; a linkage error at
the moment a segment is fetched is the opposite of reporting it.

**Keep the optional module and admit it as core's tenth Kotlin friend**, the way `superplayer-cache`
and the eight others reach core's internal seam. This is the resolution the *Consequences* section's
open question was missing, and it works. Rejected because it answers the wrong question: a friendship
is how another module *of this repository* reaches in, and it still leaves the list of transports
fixed. Recorded because a future transport that genuinely cannot be expressed through the consumer's
interface — one needing to own the whole `DataSource`, not just the bytes — would arrive this way,
and it should arrive as a superseding record rather than as a surprise.

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
- Issue #22 — compose the transfer chain in one place in `superplayer-core`. **Built**: `TransferChain`,
  whose KDoc names the `DefaultHttpDataSource.Factory()` line as "the line ADR-0004 will replace"
  precisely so that this record's implementer would not have to find it. It turned out to be four
  lines by Phase 9, one per chain, which is ADR-0016 rule 1's first job.
- [ADR-0016](0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md)
  — the 2026-09-18 amendment's full shape, and what binds Phase 10's implementation.
- [ADR-0012](0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md) rule 12 —
  `HeaderProvider`, which turned out to be the separable requirement the *Context* above suspected
  was hiding inside "teams want a named transport".
