# ADR-0011: Classify every failure once, escalate through fixed rungs behind an engine-agnostic boundary, and never lose the position

- **Status:** Accepted
- **Date:** 2026-09-16
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md)
  rule 2 and [ADR-0009](0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md)
  rule 7, each extended by an addendum recorded at the rule; [ADR-0008](0008-measure-behind-an-engine-agnostic-sink-boundary.md)
  rule 5's vocabulary, which gains a field and no new meaning.
- **Summary:** Decides Phase 5's shape before any of its code lands (#173). A failure acquires a
  meaning in exactly one place, `superplayer-resilience`'s `ErrorClassifier`, whose taxonomy is
  public, total and free of Media3 types; telemetry reports that classification rather than keeping
  a second one. Core's chain detects the transfer defects a core-only consumer is exposed to and
  raises typed exceptions, and the classifier maps them rather than re-deriving them. The ladder's
  six rungs are fixed in order and reached one at a time; retry *budgets* are policy, a fourth half
  of `PlaybackDecision`, while jitter, token refresh, the rung order and resume-position
  preservation are correctness, on for everyone. Every rung resumes at the position playback had
  reached, and a rung that cannot is not a rung. The Media3-facing halves — a
  `LoadErrorHandlingPolicy`, a chain layer reading HTTP status — are internal, reached through two
  new slots in core's engine seam by `superplayer-resilience` as core's fifth Kotlin friend. A
  player built without the module has Media3's own load-error handling, an empty header-refresh
  slot and no listener, and a test counts it.

## Context

Phase 5 (`PRD.md` §3.3 and Part 4, #173) ships `superplayer-resilience`: an error taxonomy, a
retry policy, a fallback ladder and a CDN token-refresh hook, with the exit criterion that every
injected fault and every hostile manifest either recovers or ends in a typed, actionable error, and
*zero unclassified errors*. Four questions would be answered at a call site by the first pull
request if they were not answered here, and each has an ADR already standing on one side of it.

**`PlaybackException` gives a code; it does not give a strategy.** Media3 reports every failure
through `Player.Listener.onPlayerError` as a `PlaybackException` — a stable type — carrying an
`errorCode` in one of a few numbered bands (I/O, content parsing, decoding, DRM, renderer) and a
`cause`. What SuperPlayer knows about a failure beyond that is spread across three places today.
Telemetry's `PlaybackFailure` sorts the band alone into six coarse `FailureCategory` buckets and
stamps `errorCodeName` into `code`, and its KDoc and `docs/telemetry-schema.md` both defer an
actionable taxonomy to `superplayer-resilience` by name, on the argument that a partial second copy
would give a data team two answers to one question. Core's transfer chain raises two typed
exceptions of its own — `StaleLivePlaylistException`, whose `likelyCause` names the party the
response headers point at, and `LiveWindowTooShortException` — and both travel only as a
`PlaybackException.cause` that nothing inspects, so a data team sees an unexplained I/O error
(#86). And Media3's own error handling has facts in it that nothing reads: an
`HttpDataSource.InvalidResponseCodeException` carries the status and the response headers, a
`ParserException` says whether the content was malformed or merely unsupported, and a
`MediaCodec.CodecException` says whether the codec is recoverable or transient.

**Media3's default load-error handling is one policy for everything.** SuperPlayer sets no
`LoadErrorHandlingPolicy`, so `DefaultLoadErrorHandlingPolicy` is in force on every player: a retry
delay of `min((errorCount − 1) × 1000, 5000)` milliseconds with no jitter, one retry count for a
manifest, a segment and a licence alike, and a fixed exclusion of sixty seconds for a track and
five minutes for a location when it falls back at all. Jitter is not a nicety here — thousands of
clients retrying a CDN blip on the same schedule are a self-inflicted denial of service, which is
the reason `PRD.md` §3.3 gives for building a policy at all. The policy is a Media3 `@UnstableApi`
interface, consulted per load error with a `LoadErrorInfo` that names the load's data type and
track type, and it is the one place Media3 asks *whether* to retry, *when*, and — through
`getFallbackSelectionFor` — whether to exclude the failing track (`FALLBACK_TYPE_TRACK`) or the
failing location (`FALLBACK_TYPE_LOCATION`, which is how DASH `BaseURL` failover happens in Media3).
Rungs 1 through 3 of §3.3's ladder are therefore answers to questions this one object is asked.

**Rungs 4 and 5 are player-state operations, and only core performs those.** `MediaRequest` has
carried an ordered `sources` list since Phase 1 and its KDoc says only the first is used; moving to
the next is a second adoption of the same request, which is `adopt`'s — identity, resume position,
live latency half, telemetry session — and nothing else's (ADR-0010 rule 7 established that a
source reaches a player through `setMediaRequest` and no other door). Recreating a decoder after a
transient device failure is, in Media3, a re-`prepare()` of a player that has gone to `STATE_IDLE`
on error, which keeps its playlist and position; that is a facade operation on the player's own
state. Neither can be done from inside a `LoadErrorHandlingPolicy`, which is consulted on a loading
thread with a load in hand.

**A fallback that restarts from zero is worse than the error.** `PRD.md` §3.3 states it as a
requirement of every rung. The position a viewer had reached is held in three places that must
agree after a fallback: the player's own position, the remembered-position map `ResumeFromLastKnown`
reads, and the telemetry session's notion of where the viewer was. A re-adoption that read the
request's `StartPosition` again would start a `Beginning` request at zero; a re-prepare that lost
the position would do the same for a decoder failure.

**Retry budgets have different right answers; jitter does not.** ADR-0005 puts a decision with
different right answers for different content behind `PlaybackPolicy`; ADR-0006 rule 1 keeps a
setting with one right answer for all content outside it, on for everyone. How many times to retry
a live segment before giving up is a number that is right for video on demand and wrong for a live
window that has moved on by the third attempt, and it is a number a stall history should shorten.
Whether to jitter a backoff, whether to refresh an expired token before retrying, in what order the
rungs are tried, and whether a rung may lose the position have one right answer each.

**The Media3-facing halves cannot be public.** A `LoadErrorHandlingPolicy`, an `HttpDataSource`
status, a `DataSource` layer, a `MediaSource.Factory` — every one is `@UnstableApi`, and
`docs/api-surface.md` fails the build on one in a public signature. The same squeeze produced
ADR-0009 rule 7 for `superplayer-abr` and ADR-0010 rules 3, 6 and 8 for `superplayer-cache` and
`superplayer-preload`: a public core type or call on the outside, a core-internal seam on the
inside, and the module compiled as a Kotlin friend of core so it can fill the seam without a Gradle
dependency from core to a later phase.

**Core already owns two detections, and `PRD.md` §3.3 says why.** A live HLS playlist an
intermediary has frozen and a live DASH window too shallow to play inside are defects *in the
transfer*, the fix has to be in the chain, and the consumer most likely to be behind a
misconfigured CDN is the one with only `superplayer-core`. `TransferChain`'s KDoc places both above
the cache slot and reserves exactly one line for resilience: a header-refresh layer closest to the
transport, so that a token refresh and the retry it triggers are a single transfer to everything
above.

**Pay nothing is a promise the project has made three times.** A player built without telemetry
registers no analytics listener (ADR-0008 rule 2), one built without abr registers no observer
(ADR-0009 rule 7), one built without cache or preload allocates nothing for either (ADR-0010 rule
13), and each is asserted by counting rather than by inspection. Phase 5 adds one more optional
module and inherits the promise.

## Decision

**A failure acquires a meaning in exactly one place, `ErrorClassifier`, whose taxonomy is
`superplayer-resilience`'s public vocabulary and names no Media3 type; telemetry reports that
classification and keeps no second one. Core's chain detects the transfer defects a core-only
consumer is exposed to and raises typed exceptions, which the classifier maps rather than
re-derives. The ladder's six rungs are fixed in order, each reached only when the one below it has
failed, and every rung resumes at the position playback had reached. Retry budgets are decided
behind `PlaybackPolicy` as a fourth half of `PlaybackDecision`; jitter, token refresh, the rung
order and position preservation are correctness, on for every player the module is attached to. The
Media3-facing halves are internal and reach the engine through two slots in core's seam, filled by
`superplayer-resilience` as core's fifth Kotlin friend. A player built without the module pays
nothing, and a test counts it.**

Fourteen rules follow, and they are binding.

### One taxonomy

1. **`ErrorClassifier` is the single place a failure acquires a meaning, and its taxonomy is a
   public, sealed SuperPlayer type that names no Media3 class.** The classes are `PRD.md` §3.3's —
   `Transient.Network`, `Transient.CdnEdge`, `Content.ManifestInvalid`, `Content.SegmentGap`,
   `Device.DecoderInit`, `Device.DecoderTransient`, `Drm.*` and `Fatal.Unsupported` — and each
   carries what acting on it needs: whether it is retryable, which rung it may reach, and a stable
   name that survives a renumbered engine. The classifier's inputs are Media3's — the
   `PlaybackException`, the HTTP status and response headers where a load failed, the manifest
   state — and every one of them stays behind an internal function. Nothing else in the library
   branches on an error code or an exception class to decide what a failure *is*: a retry policy
   asks the classifier, the ladder asks the classifier, and telemetry reports what the classifier
   said. A second `when` over `errorCode` anywhere in the repository is a bug against this rule.

2. **The classifier is total, and the taxonomy has no unknown class.** For every `PlaybackException`
   the engine can deliver the classifier returns a class and never throws; where no evidence
   narrows a failure, the engine's error-code band alone decides the branch — an I/O failure with
   no status is `Transient.Network`, a parse failure is `Content.ManifestInvalid` — and
   `Fatal.Unsupported` is for content the engine has *named* as unsupported, never a bucket for
   what the mapping did not foresee. The phase's exit test (#184) is the proof that every injected
   fault and every hostile manifest lands in an actionable class; a failure that test surfaces
   which fits no class gets a class, in a change that says why, rather than the nearest one
   stretched to cover it. "Zero unclassified errors" is a property of the function, not of the
   test corpus.

3. **Telemetry reads the classifier and keeps no second copy, in either direction.**
   `PlaybackFailure` gains a `classification` field: the class's stable name, populated on a player
   with resilience attached and null otherwise, so a pipeline that groups by it sees the same
   string a log line and a bug report carry. The coarse `category` stays and is not extended: on a
   player with resilience attached it is *derived from the class* by a one-to-one table the
   classifier owns, and on a player without it from the error-code band as today. Where the two
   derivations disagree for a failure, the class is right, the band-derived bucket changes to
   match, and that is a change of meaning under `docs/telemetry-schema.md`'s rule — a
   `SCHEMA_VERSION` bump with a release note — rather than a silent correction. An added field is
   not.

### What is core's, and what is not

4. **Core detects; resilience classifies.** A defect in the transfer that a consumer with only
   `superplayer-core` is exposed to — today a frozen live playlist and a too-short live window —
   is caught in `TransferChain` and raised as a public core exception carrying the evidence
   (`StaleLivePlaylistException.likelyCause`, `LiveWindowTooShortException`'s depths), exactly as
   `PRD.md` §3.3 and §3.7 place it. `ErrorClassifier` maps those exceptions into the taxonomy by
   reading their fields and re-derives nothing: it does not re-read playlist ages or manifest
   attributes, and the cache-bypassing reload core performs before it gives up is not a rung of the
   ladder. The rungs *above* a playlist core has given up on — the next host, the next source —
   still apply, because a playlist frozen at one edge may be live at another. Adding a detection to
   core is the same decision this rule records, taken again with the same test: is the consumer
   with only core the one who needs it?

5. **Core performs the two rungs that are player-state operations, and only when asked.** Moving
   to the next source in `MediaRequest.sources` (rung 4) is a re-adoption through core's own
   `adopt` path, at the position playback had reached, keeping the identity, the telemetry session
   and the CMCD `sid`; recreating a decoder (rung 5) is core re-preparing the player at its held
   position. Both are core-internal operations reached through the seam and performed only when
   the ladder asks; a core-only player never performs either, because nothing asks. Neither is a
   public call: a consumer who wants the next source calls `setMediaRequest` with the request they
   have, as ADR-0010 rule 7 already requires of a warm source.

6. **What `superplayer-resilience` owns is the taxonomy, the retry and backoff, the fallback
   selection, the token refresh and the escalation between rungs; what it does not own is any
   detection core makes, any storage, any HTTP stack and any proxy.** ADR-0002 and ADR-0004 stand:
   the token-refresh hook is a layer in the chain core composes, at the line `TransferChain`'s KDoc
   reserves for it, and not a stack the module assembles. `Drm.*` exists in the taxonomy so Phase 6
   has somewhere to land; no `DrmSessionManager`, licence or provisioning plumbing is this
   module's.

### The rungs

7. **The ladder is `PRD.md` §3.3's six rungs, in that order, and a rung is reached only when the
   one below it has failed.** Retry the same URL; the next CDN host or base URL; exclude the
   failing variant and continue at another bitrate; the next source in `MediaRequest.sources`; the
   decoder recreated; a typed, actionable error. Rungs 1 to 3 are answers the internal
   `LoadErrorHandlingPolicy` gives to the questions Media3 asks it — a retry delay, or none, and a
   `FallbackSelection` of location or track — driven by the class rather than by Media3's defaults;
   rungs 4 and 5 are rule 5's; rung 6 is rule 10's. The order is not a profile's to vary and not
   the consumer's: no content wants its variant excluded before its host is tried, and escalating
   only as far as needed is the whole of the ladder's value. Which rungs a *class* may reach is the
   class's (rule 1): a `Content.ManifestInvalid` does not retry the same bytes, a
   `Device.DecoderTransient` goes to rung 5 without trying a host, a `Fatal.Unsupported` goes to
   rung 6 at once.

8. **Rung 3 respects the decision in force.** Excluding a variant hands Media3 a narrower ladder,
   and the rung it continues at is still chosen under the selection ceiling and pace
   `PlaybackPolicy` decided (ADR-0005, ADR-0009): a `DATA_SAVER` player that loses its lowest
   eligible rung does not climb above its ceiling to keep playing, it moves to rung 4. The ladder
   changes what is *available*; it never changes what the policy *allows*.

9. **Every rung resumes at the position playback had reached, and a rung that cannot is not a
   rung.** For a rung Media3 performs inside a load (1 to 3) the position is untouched by
   construction and the rule is that nothing SuperPlayer adds re-seeks. For rungs 4 and 5 core
   carries the held position across the operation explicitly — the same instant, not the request's
   `StartPosition` read again — and updates the remembered-position map so `ResumeFromLastKnown`
   agrees. On a live stream the position is window-relative and the promise is the same latency
   from the edge rather than the same instant, because the instant may have left the window. Each
   rung's forcing test (`PRD.md` Part 5) asserts the position after against the position before,
   and a rung whose test cannot make that assertion is removed from the ladder rather than shipped
   with a note.

10. **Rung 6 is a typed error the consumer can act on, delivered where errors are already
    delivered.** The consumer sees it through `Player.Listener.onPlayerError`, as the `cause` of
    the `PlaybackException` the `Player` API already carries — a public SuperPlayer type naming the
    class, the rungs tried, the position reached and, where the class carries one, the likely
    party (a `StaleLivePlaylistException.likelyCause` survives the mapping) — and through
    telemetry's `StartupFailed` or `MidStreamFailed` with rule 3's classification. No new listener,
    no callback of SuperPlayer's own: the facade is a `Player` (ADR-0003) and errors arrive as a
    `Player`'s do. Each rung applied before that point is visible in the same session's telemetry,
    because a fallback that rescued a session is a fact about that session and not the start of a
    new one.

### What is policy

11. **Retry budgets are policy, a fourth half of `PlaybackDecision`.** How many attempts a
    manifest, a segment and a licence each get, and the backoff ceiling, have different right
    answers per profile and per condition — a live window has moved on before a long budget is
    spent, a stall history argues for a shorter one, a stable unmetered link for a longer — which
    is ADR-0005 rule 2's own test. `PlaybackDecision` gains a `RetryPolicy` (an addendum to that
    rule, of the kind its pace and prefetch additions were) with a default of *Media3's own*, so
    every `PlaybackPolicy` written before it keeps compiling; `StaticProfilePolicy` carries a
    per-profile row with a reason per departure, and `AdaptivePolicy` varies it. The internal
    `LoadErrorHandlingPolicy` reads the half in force on *every* consultation, the way
    `NetworkAwareTrackSelection` reads the pace on every evaluation, so a re-consulted decision is
    honoured whole (ADR-0009 rule 5) on the next load error without rebuilding anything. On a player
    with no resilience attached the half is decided and ignored, and `playbackDecision` says what
    was decided — the same contract a core-only player has for the selection ceiling. `PRD.md`
    §3.3's name `RetryPolicy` therefore goes to the *numbers*, which are public; the object that
    implements Media3's interface over them is internal and named where it is built (#178).

12. **Jitter, token refresh, the rung order and position preservation are correctness, on for
    every player the module is attached to, and not a profile's to vary.** Each has one right
    answer for all content, which is ADR-0006 rule 1's test: synchronized retries are a denial of
    service whatever the profile; a 401 or 403 on a segment while the manifest is fine is refreshed
    once, before the retry, by re-invoking the request's `HeaderProvider`, and a refresh that
    returns the same headers is not retried again; the order is rule 7's; the position is rule 9's.
    None of them appears in `PlaybackDecision`, in a profile table, or in a builder.

### The seam

13. **`superplayer-resilience` reaches the engine through two slots in core's seam and one public
    call, as core's fifth Kotlin friend.** The public call is on `SuperPlayer.Builder` and
    `PlayerPool.Builder`, taking a public resilience type that names no Media3 class — the shape
    ADR-0009 rule 7 gave `setPolicy` — and the object handed to it also implements a core-internal
    extension interface through which it fills two slots #176 adds: a **load-error slot** in
    `EngineConfiguration`, the `LoadErrorHandlingPolicy` handed to the `MediaSource.Factory`
    `TransferChain` assembles for every protocol it builds a source for; and a **header-refresh
    slot** in the chain itself, at the line its KDoc reserves, closest to the transport. Host
    substitution for an HLS mirror, if rung 2 needs one where the manifest offers no `BaseURL`
    (#180), goes through that same slot rather than a second one, for the same reason: a repaired
    request must be one transfer to every layer above it. The extension runs before the test
    configurator, so a test's engine configuration still wins. `KotlinFriendModules.kt`'s argument
    holds unchanged: a friend path is a compiler flag and not a Gradle dependency, and the
    dependency it does not create is the one `docs/modules.md` forbids.

14. **A player, a pool or a session built without resilience registers nothing and allocates
    nothing for it, and a test counts it.** Media3's `DefaultLoadErrorHandlingPolicy` is in force
    as today, the header-refresh slot is empty and the chain is what it was in Phase 4 byte for
    byte in a golden trace, no error listener of resilience's is registered, `PlaybackFailure.classification`
    is null, and no class from the module is loaded. It is asserted the way ADR-0010 rule 13 is: a
    core test builds a player with a profile alone and counts what was registered, the same counts
    are taken with the module attached so the counter is shown to see what it counts, and a golden
    trace of a core-only session that changes when Phase 5 lands is a violation of this rule
    whatever the diff says.

## Consequences

**Easier.** Every Phase 5 issue after this one (#175–#184) has a written answer to the question it
would otherwise settle at a call site: where a failure is named, whose numbers a retry reads, which
rungs are core's, what a fallback owes the viewer. #86 closes as a consequence of rules 3 and 4
rather than as a special case. A data team, a bug report and a log line say the same word for the
same failure. The forcing tests `PRD.md` Part 5 requires have a property to assert beyond "it
recovered": the position.

**Harder.** The taxonomy, the rung-6 error type and `RetryPolicy` are public API under
`docs/api-surface.md`, and `PlaybackDecision` grows a fourth half every policy must produce; the
static table gets one more column per profile with one more reason per row. Rule 3's derivation
table means the first time the classifier and the band disagree about a failure, the schema
version moves, and that will happen: `StaleLivePlaylistException` is an `IOException` the band
calls `NETWORK`, and the classifier will call a frozen intermediary something else.

Rule 9 is the expensive one. Rungs 4 and 5 are re-adoption and re-preparation of a player that has
just failed, on the facade's own state, with three positions to keep in agreement and a live window
that may have moved. The forcing tests need the fault machinery #175 builds first — a fault that
relents, so a retry can succeed — because a fault matched on address alone meets itself again on
every rung, and that prefactor is the price of a ladder anyone can prove works.

Rule 13's fifth friend is one more compilation that can see `internal`, on an argument now made
four times. The count is worth watching: a friend path is the right shape for a library's own later
phase and the wrong shape for anything else, and `KotlinFriendModules.kt`'s KDoc is where a sixth
would have to be argued.

Rule 11 puts a number into every `PlaybackPolicy` that a consumer's hand-written policy did not
mean to decide. The default of *Media3's own* keeps that policy compiling and behaving as before,
and a consumer who wants budgets writes them where a reviewer can see them.

**A standing obligation.** `Drm.*` is a branch with no leaves until Phase 6, and the DRM error
mapping `PRD.md` §3.2 asks for lands in this classifier under rule 1, not in a second one in
`superplayer-drm`. Phase 7's download resilience reads the same taxonomy and the same budgets.

## Alternatives considered

**A second, finer taxonomy in telemetry, so a pipeline sees classes without resilience attached.**
Rejected; `docs/telemetry-schema.md` already rejected it. Two vocabularies for one failure give a
data team two answers, and a classifier in core would put `HttpDataSource` and codec-exception
reading into the one module every consumer carries. Rule 3's nullable field is the honest shape: a
player without the module has no classification, and says so.

**Keep the coarse category derived from the band on every player, so the schema never moves.**
Rejected. It would let a classified failure and its category disagree — a frozen intermediary
under `NETWORK` beside a classification that names a cache — which is the two-answers problem
again, one row apart.

**Re-derive the stale-playlist and window-depth detections in the classifier from the manifest
state it already reads.** Rejected, as `PRD.md` §3.3 rejected it: the consumer with only core is
the one behind the misconfigured CDN, and a detection in resilience would leave them with Media3's
untyped `PlaylistStuckException`. Two detections would also disagree eventually.

**Put the whole ladder behind `PlaybackPolicy`, budgets and order together.** Rejected under
ADR-0006 rule 1's test. The order has one right answer for all content, and a policy that could
reorder the rungs could exclude a variant before trying a host, or lose the position, and call it
a decision.

**Make the budgets fixed for everyone, like the lifecycle rules.** Rejected. A live segment that
has failed three times is behind a window that has moved; a video-on-demand segment can be retried
for a long while at no cost but time. A number that is right for one and wrong for the other is
ADR-0005's definition of policy.

**Make the budgets a builder setting, `setRetries(n)`.** Rejected under ADR-0005 rule 3: a number
that is right on WiFi and wrong on cellular does not go in a builder, and a consumer choosing one
would rediscover F1 in a new place.

**An `onError` hook the consumer implements to choose a rung.** Rejected. The facade is a `Player`
(ADR-0003) and a consumer's error handling already lives in `onPlayerError`; a second callback
would arrive on a loading thread with a load in hand, and a consumer who wants a different
escalation has the same recourse as for any other decision, a `PlaybackPolicy` of their own for
the numbers and `setMediaRequest` for the next source.

**Media3's `DefaultLoadErrorHandlingPolicy` with larger retry counts.** Rejected. It has no jitter,
one count for every kind of load, and no class to drive its fallback selection; raising its numbers
makes the synchronized retry worse rather than better.

**Subclass `DefaultLoadErrorHandlingPolicy` publicly, so a consumer can tune it.** Rejected under
ADR-0001 rule 2 and `docs/api-surface.md`: the type is `@UnstableApi`, and a public subclass puts
that marker on every consumer that names it.

**A local proxy for host failover, so a retry against another CDN is invisible to Media3.**
Rejected; ADR-0002 stands. Media3's `FALLBACK_TYPE_LOCATION` exists for DASH, and for HLS a mirror
is a request repaired in the chain, at the one slot rule 13 names.

**A new `Player.Listener`-style callback for rung 6.** Rejected. Errors arrive where a `Player`'s
do, and the typed error is the `cause` the existing exception already carries; a consumer who
never adds resilience sees the exception they see today.

**`ServiceLoader` or reflection to find the module.** Rejected for ADR-0009's reasons, which hold
unchanged: behaviour that changes with a dependency and no line of code, and a pay-nothing claim a
classpath scan would make false.

## References

- `PlaybackException` — the stable error type, its `errorCode` bands and `cause`:
  https://developer.android.com/reference/androidx/media3/common/PlaybackException
- `LoadErrorHandlingPolicy` — the retry-delay, retry-count and `getFallbackSelectionFor` questions
  Media3 asks per load error, and `FALLBACK_TYPE_LOCATION` / `FALLBACK_TYPE_TRACK`:
  https://developer.android.com/reference/androidx/media3/exoplayer/upstream/LoadErrorHandlingPolicy
- `DefaultLoadErrorHandlingPolicy` — the retry schedule and exclusion durations in force on a player
  that sets none:
  https://developer.android.com/reference/androidx/media3/exoplayer/upstream/DefaultLoadErrorHandlingPolicy
- `HttpDataSource.InvalidResponseCodeException` — the status and headers a failed load carries:
  https://developer.android.com/reference/androidx/media3/datasource/HttpDataSource.InvalidResponseCodeException
- `ParserException` — `contentIsMalformed` and `dataType`, which separate malformed from unsupported:
  https://developer.android.com/reference/androidx/media3/common/ParserException
- `MediaCodec.CodecException` — `isRecoverable` and `isTransient`, rung 5's evidence:
  https://developer.android.com/reference/android/media/MediaCodec.CodecException
- `ExoPlayer.prepare()` after an error — a player in `STATE_IDLE` keeps its playlist and position:
  https://developer.android.com/reference/androidx/media3/common/Player#prepare()
- DASH `BaseURL` failover and multiple base URLs — ISO/IEC 23009-1 §5.6 and DASH-IF IOP §4.7:
  https://dashif.org/guidelines/
- RFC 8216, HTTP Live Streaming — §6.3.3 on playlist reload and §6.3.4 on a client's handling of a
  failed segment or playlist: https://www.rfc-editor.org/rfc/rfc8216
- Exponential backoff with jitter — the argument that synchronized retries are a self-inflicted
  outage: https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
- CTA-2066 — video start failure and video playback failure, the two events rule 10's
  classification lands on: https://shop.cta.tech/products/streaming-quality-of-experience-events-properties-and-metrics
- [ADR-0001](0001-compose-dont-fork.md) rule 2, [ADR-0002](0002-no-local-http-proxy.md),
  [ADR-0003](0003-implement-player-by-delegation.md),
  [ADR-0004](0004-select-the-http-stack-through-a-superplayer-type.md),
  [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md) rules 2 and 3,
  [ADR-0006](0006-own-the-platform-rules-and-hand-back-the-state.md) rule 1,
  [ADR-0008](0008-measure-behind-an-engine-agnostic-sink-boundary.md) rules 2 and 5,
  [ADR-0009](0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md) rules 5 and
  7, and [ADR-0010](0010-cache-in-consumer-chosen-storage-and-preload-on-the-one-chain.md) rules 7
  and 13; `TransferChain`'s KDoc; `docs/telemetry-schema.md`'s *Video start failure and mid-stream
  failure*; `docs/testing.md`'s *Reaching that seam from another module*.
- `PRD.md` §2.4, §3.3, §3.7 and Parts 4 and 5; #173 — the phase this decides for; #86 — the typed
  failure telemetry reports as unclassified I/O, which rules 3 and 4 close; #175–#184 — the issues
  that cite these rules.
