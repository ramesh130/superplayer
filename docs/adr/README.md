# Architecture decision records

Why SuperPlayer is shaped as it is: one record per decision, each with its own numbered rules.

An ADR here is not a design note. **Its rules bind**, and `CLAUDE.md`'s *Binding rules* section is
the list of the ones a change is held to: a change that contradicts a rule says so explicitly and
argues the case, and winning that argument produces a superseding ADR rather than an undocumented
exception. Several rules are enforced mechanically — by a Gradle task, by a test that counts what a
player without a module allocates, or by the tracked API surface — and each record says which of its
rules are.

`0000-template.md` is the shape a new record takes.

---

## The index

| ADR | The decision, in a line |
| --- | --- |
| [0001 — Compose on Media3, do not fork it](0001-compose-dont-fork.md) | Depend on Media3 as an ordinary versioned library and extend it only through its public extension points; wrap every `@UnstableApi` type behind SuperPlayer's own, with one named exception (`player.exoPlayer`), and pin one Media3 version in one place. |
| [0002 — No local HTTP proxy for startup latency](0002-no-local-http-proxy.md) | Never run a local HTTP proxy or rewrite manifests to fake a fast start: it corrupts bandwidth estimation and breaks ABR, licence binding, CMCD and CDN tokens. Pursue startup through caching, preload and selection parameters instead. |
| [0003 — Implement `Player` by delegation, never extend a Media3 base class](0003-implement-player-by-delegation.md) | The facade implements `Player` by Kotlin delegation rather than extending `ForwardingPlayer` or any other Media3 base, because every one of them is `@UnstableApi`; a contract test guards against delegation silently skipping Java `default` members. |
| [0004 — Select the HTTP stack through a SuperPlayer-owned type](0004-select-the-http-stack-through-a-superplayer-type.md) | No HTTP-stack selection API ships until a phase needs one; when it does, the choice is a SuperPlayer type that no Media3 type crosses, and core's dependency graph stays Media3-only. Accepted at Phase 10, with rules 2 and 3 amended at the rules: the transport arrives as an interface the consumer implements rather than as an optional module per named client. |
| [0005 — Decide playback policy behind an engine-agnostic boundary, and ship a static one](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md) | All buffering and track-selection policy is decided behind one `PlaybackPolicy` interface that no Media3 type crosses; the consumer names a use-case profile rather than tuning numbers, and the first implementation is a static per-profile lookup. |
| [0006 — Turn Android's lifecycle rules on by default, and hand back the state that outlives a player](0006-own-the-platform-rules-and-hand-back-the-state.md) | Audio focus, becoming-noisy, and wake and Wi-Fi locks are on for every player because they are correctness rather than policy; state that outlives a player travels as a `PlaybackSnapshot` the consumer stores, and SuperPlayer persists nothing itself. |
| [0007 — Publish the player as a session, own the service, and resolve content by id](0007-publish-the-player-as-a-session-and-resolve-content-by-id.md) | A session is something a consumer chooses to create rather than something every player gets; a `PlaybackSession` shares its player's lifetime, and content named from outside the app arrives as a bare id resolved back into a `MediaRequest`. |
| [0008 — Measure behind an engine-agnostic sink boundary, and make the loss a contract](0008-measure-behind-an-engine-agnostic-sink-boundary.md) | Telemetry leaves through SuperPlayer's own event vocabulary and a sink naming no Media3 type — the seam in core, the collector in `superplayer-telemetry` — and delivery is at-most-once, bounded and lossy under pressure, with every dropped event counted and reported. |
| [0009 — Observe conditions in core, re-apply on named triggers, admit the adaptive engine as a friend, remember per transport](0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md) | Extends ADR-0005 for `superplayer-abr`: six enumerated optional observations in `PlaybackConditions`, re-consultation on named triggers only and only where the engine can honour a whole decision, the module as core's second Kotlin friend, and a throughput estimate remembered per transport in process memory alone. |
| [0010 — Cache only into storage the consumer opened, and preload on the one chain](0010-cache-in-consumer-chosen-storage-and-preload-on-the-one-chain.md) | A cache is storage the consumer named and SuperPlayer writes only inside it; preload builds its sources through the chain core assembles and reaches a player only through `setMediaRequest`; `PlayerPool` owns the players and the decoder budget, and prefetch depth is policy. |
| [0011 — Classify every failure once, escalate through fixed rungs, never lose the position](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md) | A failure acquires its meaning in exactly one place, `ErrorClassifier`, whose taxonomy is public, total and Media3-free; the ladder's six rungs are fixed in order and reached one at a time, every rung resumes at the position reached, retry budgets are policy while jitter and rung order are correctness, and a player without the module pays nothing. |
| [0012 — Acquire licences behind the boundary, in storage the consumer opened, and never downgrade a client unasked](0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md) | Protection is declared once per player and fixed for its lifetime, the credential is the app's through the one `HeaderProvider`, the Media3-facing half is internal to `superplayer-drm` behind one slot, an offline licence lives in a directory the consumer named, and a lower security level is requested only where the licence server permitted it. |
| [0013 — Download into the cache the consumer opened, on the one chain, under constraints that are correctness](0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md) | `superplayer-offline` depends on core alone and takes every collaborator as a core type; a download writes into the consumer's cache under the keys a player reads, pinned from enqueue to removal, over the chain core assembles; unmetered is the default the viewer may relax, and battery, storage and scheduling are the module's own. |
| [0014 — Match the display, watch it change, and reach the engine through one output slot](0014-match-the-display-and-watch-it-change-behind-one-output-slot.md) | `superplayer-tv` is core's eighth Kotlin friend through one `videoOutput` slot; frame-rate matching and re-selecting on a display or audio-capability change are correctness on every player built with it, tunneling is policy, and the display becomes a live reading the selection gate re-reads. |
| [0015 — Name a pathology over the player's own chain, and redact the bundle by construction](0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md) | `superplayer-diagnostics` is core's ninth Kotlin friend, bounded by a closed list of seams; a pathology is a defect of a stream and never a failure class, the doctor fetches over the chain a player of that request would load through and downloads no segment, a bundle is the session trace one layer richer with a capability snapshot redacted by construction, and the doctor is scored against the curated corpus with false positives counting as heavily as misses. |
| [0016 — Take the consumer's transport through one SuperPlayer interface, and adapt it to Media3 ourselves](0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md) | A consumer who needs their own HTTP client implements one bytes-only `HttpTransport` and passes it as an `HttpStack`; no per-transport module ships and nothing here names OkHttp, Cronet or Ktor, while the Media3 `DataSource`, the `TransferListener` bookkeeping and the typed response code the classifier reads are all adapted behind the boundary, for all four chains at one composition point. |
| [0017 — Version the library by semver, in lockstep, with the tracked API surface as the arbiter](0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md) | Every published module carries one version and moves with the others, because the friend-module seams make a mixed set a combination nothing here builds; what "breaking" means is a declaration removed from a tracked `.api` file rather than a reviewer's judgement; a Media3 bump is not a library major, with `player.exoPlayer` named as the one member outside the promise; the telemetry and trace format versions move on their own rules and neither moves this one; and the library stays on `0.x`, where a minor may break, until the phases close. |

Each record states its own status in its header. **All seventeen are Accepted**, and nothing has
been superseded. 0004 was the one exception until 2026-09-18: it was **Proposed**, deliberately, and shipped
no API for nine phases on the grounds that shipping the wrong shape costs more than shipping nothing.
Phase 10 is the trigger it named, and it was accepted with two of its five rules amended — the shape
it guessed at was not the shape that arrived, which is the outcome a Proposed record exists to allow.

## How they relate

The set is not flat. A later record that changes an earlier one's scope names it in a **Refines** or
**Extends** header and records the change as an *addendum at the rule it touches*, in the earlier
document, so a reader of a rule sees what has happened to it without reading the whole set. Some
records also carry an **Amended** header saying when, by which issue and at which rules, and some
record an amendment at the rule alone — ADR-0015's rule 3, amended by #289 to take a fourth seam, is
one, and its header says nothing about it.

Consequently: **read a rule where it lives**, addenda included, rather than trusting a summary of it.
The one-liners above are an index, not an authority.

## Where to start

- **The library's shape:** 0001, then 0003. Everything else assumes both.
- **Policy and adaptation:** 0005, then 0009.
- **Failures:** 0011, and 0012 for protection's own failures.
- **Storage:** 0010, then 0013.
- **Measurement:** 0008, with [`docs/telemetry-schema.md`](../telemetry-schema.md) as the metric
  definitions it decided the shape of.
- **Diagnosing a stream:** 0015, with [`docs/media-source-doctor.md`](../media-source-doctor.md) and
  [`docs/session-bundle.md`](../session-bundle.md) as what it produced.
- **The bottom of the chain:** 0004 for why nothing shipped for nine phases, then 0016 for what
  eventually did.

The domain vocabulary these records share is `CONTEXT.md` at the repository root; the phases and the
problem inventory they are cut from are `PRD.md`'s.

## Adding one

Copy `0000-template.md`, take the next number, and write the title in the imperative — the decision,
not the topic. Then add its row to the index above in the same change, because an index that lags is
worse than none: a reader who trusts it stops looking.
