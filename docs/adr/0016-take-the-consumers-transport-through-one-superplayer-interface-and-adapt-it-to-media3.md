# ADR-0016: Take the consumer's transport through one SuperPlayer interface, and adapt it to Media3 ourselves

- **Status:** Accepted
- **Date:** 2026-09-18
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** [ADR-0004](0004-select-the-http-stack-through-a-superplayer-type.md), which deferred
  this decision until a phase needed it and guessed at the shape it would take. Phase 10 is that
  phase. Rules 1, 4 and 5 of that record stand unchanged and are inherited here as rules 2, 12 and 9;
  its rules 2 and 3 are rewritten at the rules themselves, and the cross-module visibility question
  its *Consequences* left open is closed there rather than answered here, because the amendment
  removes the module that raised it.
  [ADR-0001](0001-compose-dont-fork.md) rule 2's `@UnstableApi` boundary, which is the whole reason
  this is a SuperPlayer interface rather than a Media3 one — a consumer cannot hand over a
  `DataSource.Factory` without the marker crossing into the API they touch, and
  `verifyNoUnstableMedia3InPublicApi` fails the build on it. Nothing about that rule changes; this
  record is what it costs to obey it here.
  [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md) rule 1,
  which gains the fact that a *foreign* transport is now under the classifier. The taxonomy is
  untouched and no class is added; what rule 8 below adds is the obligation to keep the evidence the
  classifier reads intact across a boundary the consumer now implements half of.
- **Summary:** A consumer who needs their own HTTP client implements one SuperPlayer interface,
  `HttpTransport` — open a request, answer a status, headers and a byte stream, cancel — and passes
  it as an `HttpStack`. No per-transport module ships, nothing in this repository names OkHttp,
  Cronet or Ktor, and core stays Media3-only. Everything above the bytes stays ours and is adapted
  behind the boundary: the Media3 `DataSource`, `TransferListener` reporting, the typed response-code
  exception the classifier and the token-refresh layer read, and the one place the chain's bottom is
  composed for all four chains. A stack that cannot be honoured fails at `build()`; a consumer who
  names none pays nothing.

## Context

`TransferChain` composes four chains — playback, download, download licence, and the doctor's fetch
— and each of the four opens with the same line:

```kotlin
val transport = environment?.transport ?: DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())
```

That line is deliberate and its KDoc says so: "The HTTP factory is named rather than left implicit
because it is the line ADR-0004 will replace, and a line that is not written down is a line that has
to be found first." Phase 1 wrote one of them. Phases 7, 9 and 6 added the other three as their
chains arrived, each by copying the first. The duplication cost nothing while the answer was always
`Default`; it becomes a defect the moment the answer is a consumer's, because four copies of a
default are four places for three of them to be right.

**What a consumer cannot do today, and why it is not an ergonomic gap.** `ExoPlayer` has no runtime
media-source-factory setter, so the loading path is fixed when the engine is constructed and
`player.exoPlayer` — ADR-0001 rule 2's one escape hatch — cannot reach it afterwards. There is no
route at all, which is the cost ADR-0004 accepted for nine phases.

**Why the obvious API is illegal here.** `DataSource`, `DataSource.Factory`, `HttpDataSource.Factory`
and every concrete stack Media3 ships carry `@UnstableApi` (checked against `media3-datasource`
1.11.0). A `setHttpStack(HttpDataSource.Factory)` fails `verifyNoUnstableMedia3InPublicApi` in
`check`. So whatever a consumer hands over, it is not the thing Media3 wants.

**What has changed under this record since 2026-09-06.** Two things, and both narrow the problem:

- **The motivation shrank.** ADR-0004's *Context* suspected that "teams rarely want a named transport
  as such — they want shared auth interceptors and headers with the client the app already has".
  That half is now `HeaderProvider` (ADR-0012 rule 12), a credential the app mints, repaired inside
  the transfer that met the 401 or 403. What remains as a reason to bring your own client is
  narrower and honest: one connection pool for the whole app, HTTP/3, and a client whose timeouts,
  certificate pinning and proxy configuration a team has already argued about once.
- **The list got longer, again.** `media3-datasource-ktor` arrived at `1.11.0-alpha01` while this
  record was open — the second time in one page that an enumeration of transports was wrong, the
  first being the table in ADR-0004's own *Context*. Cronet remains excluded on licence
  (`play-services-cronet` ships under the Android Software Development Kit License, which
  `CONTRIBUTING.md`'s permissive-only policy refuses) and `cronet-embedded` on APK size. A design
  whose correctness depends on the list being complete is a design that is already wrong.

**What is genuinely hard, and is the whole risk of this phase.** Not the plumbing — the contract.
An HTTP client that "works" for REST is not sufficient for adaptive streaming, and the ways it is
insufficient are silent:

- **Byte ranges.** HLS `EXT-X-BYTERANGE` and DASH initialization and index segments are `Range`
  requests. A transport that ignores the header returns the whole resource with status 200, and the
  player decodes garbage or stalls — a failure that reads as a corrupt stream rather than as a
  wrong adapter.
- **Content coding.** A client that transparently gzips and decompresses changes the byte counts the
  bandwidth meter sees, so the estimate describes the compressed link rather than the one the player
  is about to use. That is ADR-0002's corruption arriving through a third door.
- **Status codes.** `TokenRefresh.kt:134` catches `InvalidResponseCodeException` and reads
  `responseCode` to decide whether rung 1 may repair a credential; `ErrorClassifier.kt:214` reads the
  same field to decide what the failure *is*. A transport that returns an empty body on a 403 instead
  of raising, or that raises its own exception type, silently disables the credential repair and
  every classification downstream of it. Nothing would fail; sessions would simply end unclassified.
- **Cancellation.** Media3 closes a `DataSource` to abandon a load — on a seek, a track switch, a
  release. A transport that blocks past `close()` holds a loader thread and stalls the release.

Each of those is a way for a consumer's fifty-line adapter to be subtly wrong in production, on
their users' devices, in a component we shipped the interface for. The design question is therefore
not "how does the client get in" but **how little the consumer has to get right**.

## Decision

**A consumer supplies a transport, not a client and not a module: one SuperPlayer interface that
moves bytes, with everything a stream needs above the bytes adapted behind the boundary by core.**

### The seam

1. **One composition point.** The four `DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())`
   lines in `TransferChain` collapse into one internal function that resolves the stack, and the
   stack is resolved there and nowhere else. This is a prefactor and lands before any public API
   does — "make the change easy, then make the easy change". A fifth chain added by a later phase
   gets the consumer's transport by construction rather than by the author remembering.
2. **No Media3 type in a public signature** — ADR-0004 rule 1 unchanged, and ADR-0001 rule 2 behind
   it. `HttpStack` and `HttpTransport` are SuperPlayer's. The `DataSource` that adapts one to the
   other is `internal`, and it is where every obligation in rules 8 to 10 is discharged.
3. **The harness's transport slot wins over the consumer's stack, and they are not the same thing.**
   `EngineConfiguration.transport` substitutes for the network *itself* and is a test's; an
   `HttpStack` substitutes for the HTTP client over a real network and is a consumer's. Resolution
   order is the test slot, then the stack, then the default. A test of a consumer's own transport
   therefore drives `HttpTransport` directly rather than through the slot, which is what keeps
   `docs/testing.md`'s "nothing asserts past the facade" true of both.

### What the consumer implements

4. **`HttpTransport` is the whole of it, and it is about bytes.** Open a request for a URI with
   headers and an optional byte range; answer a status, the response headers and a stream; close.
   Nothing about HLS, DASH, manifests, caching, retry, CMCD, tokens or measurement crosses that
   interface, because every one of those is a layer above it that already exists and that a consumer
   must not be able to get wrong.
5. **Byte ranges are mandatory and are part of the contract, not an optimisation.**
   // spec: RFC 9110 §14.2 — a `Range` request a server honours answers **206** with
   `Content-Range`. A transport that returns 200 and the whole resource is not a slow transport, it
   is a broken one, and rule 14's conformance test is what says so before a user's device does.
6. **The transport reports the URI it actually read from**, after any redirect it followed, because
   `DataSource.getUri()` is what the chain above reads. It does not decide whether to follow
   redirects — it follows them, as Media3's own stacks do, and says where it ended.
7. **The transport adds no content coding of its own.** It sends the request headers it is given and
   no others; if the chain wants `Accept-Encoding: identity`, the chain says so. A client configured
   to gzip transparently is the one configuration a consumer must switch off, and the interface's
   KDoc says it in those words, because it is the failure that shows up as a bandwidth estimate
   nobody can explain rather than as an error.

### What stays ours

8. **Typing the failure is the adapter's, never the consumer's.** A non-2xx status the transport
   *reports* becomes `HttpDataSource.InvalidResponseCodeException` inside core's adapter, carrying
   the status, the headers and the URI. The consumer returns a number; we turn it into the evidence
   `TokenRefreshLayer` reads to repair a 401 or 403 inside the transfer that met it, and that
   `ErrorClassifier` reads to give the failure a meaning. This is the single most load-bearing rule
   in the record: it is what makes ADR-0011 rule 1 survive a transport we did not write.
9. **Measurement is the adapter's** — ADR-0004 rule 5 unchanged, and now with one place to be true
   rather than one per transport. The adapter extends Media3's own `BaseDataSource` so that
   `TransferListener` registration and the start/bytes/end bookkeeping are inherited rather than
   reimplemented, and it reports `isNetwork = true`, which is what keeps `BandwidthOracle`'s
   cache-hit exclusion (ADR-0009 rule 8) meaning what it says.
10. **Cancellation is bounded by us and asked of them.** `close()` cancels an in-flight request; the
    interface's one non-obvious obligation on the consumer is that a cancelled request returns
    rather than blocking, and the KDoc says what holding a loader thread costs.

### Choosing one

11. **`HttpStack` is a public shell with an internal constructor** — `ContentCache`,
    `DownloadEnvironment` and `DiagnosticEnvironment`'s pattern, for the reason it was used there:
    the set of things core can build is core's, and a consumer can hold one without being able to
    forge one. Three factories: `HttpStack.default()` (what ships today, unchanged),
    `HttpStack.httpEngine()` (the platform's, which core builds itself — it is in
    `media3-datasource` already and costs no dependency), and `HttpStack.of(transport)`.
12. **A selection that cannot be honoured is reported, not silently dropped** — ADR-0004 rule 4
    unchanged. `httpEngine()` below API 34 is the one real case, and it fails at `build()` with a
    typed error. Silent substitution is refused for the reason that record gave: it makes a
    bandwidth-estimate anomaly impossible to explain afterwards.
13. **Every entry point that composes a chain takes the stack**, and that is four:
    `SuperPlayer.Builder`, `PlayerPool.Builder`, `Downloads.Builder` and `MediaSourceDoctor.Builder`.
    A player that loads over the app's client while its downloads use the platform's is the defect
    rule 1 makes preventable and this rule actually prevents. A doctor is included deliberately: its
    whole promise (ADR-0015 rule 7) is to fetch over the chain a *player* of that request would load
    through, and an HTTP stack it did not share would make that promise false.
14. **A consumer who names no stack pays nothing**, and a consumer who names one is held to the
    contract mechanically. The default resolves to exactly the factory composed today, which a test
    counts rather than assumes. `HttpTransport` gets a **conformance test** — a public one, in
    `superplayer-testkit`, that a consumer can run against their own implementation — covering the
    range request of rule 5, the redirect of rule 6, the identity coding of rule 7, the status
    surfaced for rule 8 and the cancellation of rule 10. An interface whose obligations are only in
    its KDoc is an interface that gets implemented wrongly once per adopter.

## Consequences

**Easier.** A consumer with any HTTP client has a route, including clients this project has never
heard of and clients that do not exist yet. Core's dependency graph stays Media3-only with no
exception and no `compileOnly` caveat; `docs/modules.md` gains no row, no module and no tenth Kotlin
friendship. Cronet's licence and Ktor's alpha status stop being this repository's problem, because
neither artifact is ever resolved here — the consumer resolves their own. And the four chains gain a
single bottom, which is worth having even for the default.

**Harder, and this is the real cost.** We now own an HTTP contract. Media3's `OkHttpDataSource` is
several hundred lines of exactly the care rules 5 to 10 enumerate, and by taking an interface instead
of a `Call.Factory` we decline to reuse it — that care is redistributed, with the mechanical half in
our adapter and an irreducible remainder in the consumer's fifty lines. Rule 14's conformance test is
the mitigation and it is not a complete one: it can prove a transport answers a range request
correctly, and it cannot prove the consumer runs it.

**A support surface that is not ours but will arrive as ours.** "Playback is broken on SuperPlayer"
where the cause is a transport that gunzips will be reported here first. Two things reduce it rather
than one: the conformance test, and the fact that Phase 9 shipped a doctor and a session bundle — a
bundle from such a session shows the byte counts and the status codes, which is the evidence the
question needs.

**A number that does not move.** `TelemetryEvent.SCHEMA_VERSION` stays **2**. Nothing here changes
what a metric means; a transport is where the bytes come from, and every definition downstream is
measured at the same place it was before. The same is true of `SessionTrace.FORMAT_VERSION`.

**Deliberately not decided.** Per-request headers on `MediaRequest`, which ADR-0004's *Context*
noted "probably belong on `MediaRequest` rather than on a stack selector". They still do, and they
are still not this record's: `HeaderProvider` covers the credential case, and the residue has no
phase asking for it.

## Alternatives considered

The three shapes for what a consumer hands over were weighed at the same time; ADR-0004's
*Alternatives considered* records the two that were not chosen — a per-transport optional module, and
taking `okhttp3.Call.Factory` through `compileOnly` — together with why the tenth-Kotlin-friend
resolution answers a question nobody is asking here. They are not repeated.

**Give the consumer a `DataSource.Factory` after all, behind an opt-in annotation.** Rejected on
ADR-0001 rule 2, which admits exactly one escape hatch (`player.exoPlayer`) and admits it for
consumers reading the engine rather than for consumers supplying it. An annotation would make the
`@UnstableApi` marker spread through an API that must not carry it, and `verifyNoUnstableMedia3InPublicApi`
would have to learn an exemption — a mechanical check with an exemption is a check that will grow a
second one.

**Let the transport raise its own exceptions and classify those too.** Rejected on ADR-0011 rule 1.
A failure acquires its meaning in exactly one place, and a taxonomy that had to accommodate whatever
a consumer's client throws would be a second place. Rule 8's direction — the consumer reports a
status, core types it — keeps the classifier total without it having to know what an `IOException`
from someone else's client means.

**Ship the interface without the conformance test, and document the obligations.** Rejected as the
same bet this project has refused elsewhere: `docs/testing.md`'s argument for the golden traces and
ADR-0011's for `FallbackRungCoverageTest` are both that a rule nothing executes is a rule that goes
stale. Here it is worse than stale — the obligations are on code we cannot see, so the test is the
only form in which they can travel to the person who has to satisfy them.

## References

- [ADR-0004](0004-select-the-http-stack-through-a-superplayer-type.md) — the deferral this record
  ends, its five rules (two rewritten at the 2026-09-18 amendment), and the transport table whose
  instability is the argument against enumerating them.
- [ADR-0001](0001-compose-dont-fork.md) rule 2 — the `@UnstableApi` boundary and the one escape hatch.
- [ADR-0002](0002-no-local-http-proxy.md) — bandwidth estimation must describe the network the player
  is about to use, which rule 7 and rule 9 are two more doors into.
- [ADR-0009](0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md) rule 8 — the
  per-transport estimate and the cache-hit exclusion the adapter's `isNetwork` flag preserves.
- [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md) rules 1 and
  12 — the single classification point, and the token refresh that reads a response code.
- [ADR-0015](0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md)
  rule 7 — the doctor fetches over the chain a player would load through, which is why rule 13 counts
  four entry points rather than three.
- RFC 9110 §14 — range requests, `206 Partial Content` and `Content-Range`:
  https://www.rfc-editor.org/rfc/rfc9110#section-14
- RFC 9110 §8.4 — `Content-Encoding`, and §12.5.3 `Accept-Encoding`:
  https://www.rfc-editor.org/rfc/rfc9110#section-8.4
- `android.net.http.HttpEngine`, added in Android 14 (API 34):
  https://developer.android.com/reference/android/net/http/HttpEngine
- Media3 `DataSource` and network stack customisation:
  https://developer.android.com/media/media3/exoplayer/customization
- [`docs/api-surface.md`](../api-surface.md) — `verifyNoUnstableMedia3InPublicApi`.
- [`docs/modules.md`](../modules.md) — dependencies point inward; core depends on Media3 only, which
  this record leaves true.
