# ADR-0015: Name a pathology over the player's own chain, and redact the bundle by construction

- **Status:** Accepted
- **Date:** 2026-09-18
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md)
  rule 1, whose "single place a failure acquires a meaning" gains the thing it is *not* being asked
  to cover — a defect of a stream that failed nothing — and the rule that the doctor's vocabulary is
  disjoint from the taxonomy rather than a second copy of it (rules 4 to 6 below). Recorded as an
  addendum at that rule. Rules 2 and 3 are untouched, and rule 4 says why a pathology is not admitted
  to the taxonomy as a class.
  [ADR-0008](0008-measure-behind-an-engine-agnostic-sink-boundary.md) rule 2, whose split of the sink
  from the collector gains a third reader — a bundle built in a *later* module over the trace
  `superplayer-telemetry` already records, rather than a second recorder (rules 1, 9 and 10 below).
  Recorded as an addendum at that rule. Rule 5's versioning rule is applied unchanged to the trace's
  own `FORMAT_VERSION` (rule 9), which is where `SessionTrace`'s KDoc already sent it.
  [ADR-0013](0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md) rule 4's friend
  ceiling, which gains a **ninth** friendship: its second shape — a later phase built *from* core's
  seam, filling no slot — for one of the seams, and, for the others, an admission that
  ceiling expressly asks an ADR for, bounded by a closed list rather than granted generally (rule 3).
  Recorded as an addendum at that rule, and the ceiling itself does not move.
  [ADR-0002](0002-no-local-http-proxy.md), which is not amended at all: rule 7 is its argument
  arriving through a different door, and it is cited rather than extended.
  `SessionTraceRecorder`'s six redaction rules are extended by a seventh, stated here and carried in
  that KDoc by #292 (rule 10). `PRD.md` §3.6 is *narrowed* in one place: it calls the bundle "one
  exportable JSON/Perfetto-compatible artifact", and rule 9 makes the session trace's own grammar the
  canonical format with a JSON or Perfetto rendering beside it, which is what `SessionTrace`'s KDoc
  already anticipated. Nothing is superseded.
- **Summary:** Decides Phase 9's shape before any of its code lands (#284, #285).
  `superplayer-diagnostics` depends on `superplayer-core` and `superplayer-telemetry` and reaches
  core as its **ninth** Kotlin friend, mostly of the shape `superplayer-offline` already has: built
  from core's seam, filling no engine slot, and reaching a **closed list** of internal seams
  and nothing else — three as decided here, four since #289 amended rule 3 to take a fourth — a list
  rule 3 argues rather than assumes, because all but the first are past what that shape covers.
  A **pathology is not a failure**: a defect of a stream carries a cause and a spec
  citation, a failure carries a `FailureClass`, `ErrorClassifier` stays the one place a failure
  acquires a meaning, and a postmortem prints the two side by side without either being expressed in
  the other. The doctor fetches over the chain a *player* of that request would load through, and
  parses with the parsers the engine would run. Preflight and postmortem are one report and one API.
  The bundle is the session trace one layer richer, and the capability snapshot it adds is admitted
  by a seventh redaction rule: what the device can **do**, never what the device **is**.
  `superplayer-diagnostics` becomes the second library module to carry Compose, for a HUD the app
  places and guards. The doctor is scored against the curated corpus with its false positives
  counted, and an app that opens neither doctor nor HUD registers and allocates nothing.

## Context

Phase 9 (`PRD.md` §3.6 and Part 4, #284) ships `superplayer-diagnostics`: `MediaSourceDoctor`, a
session trace bundle, an on-device debug HUD, and `androidx.tracing` points. Its exit criteria are
that the doctor names every pathology in the curated corpus and flags none of the benign ones, that a
bundle from a device is exportable, readable and redacted, and that a Perfetto capture shows
SuperPlayer's phases. The facts below decide the shape.

**The module is an empty placeholder, and everything the doctor needs is `internal`.**
`superplayer-diagnostics/build.gradle.kts` declares `api(project(":superplayer-core"))` and nothing
else, and it is not a Kotlin friend of core. `TransferChain` — the one place the loading path below
`MediaSource` is assembled, and therefore the only place the cache layer, the header-refresh layer
and the transport are composed in the order `PRD.md` §2.4 fixes — is `internal object TransferChain`
end to end, and says so in its own KDoc: `DataSource`, `DataSource.Factory`, `DefaultDataSource` and
`DefaultHttpDataSource` all carry `@UnstableApi`, so ADR-0001 rule 2 keeps every one of them behind
the facade. `DeviceConstraints` and `deviceConstraintsOf()`, and `DisplayCapability` — the readers a capability
snapshot would draw on for the decoders and the display — are internal for the same reason. So is
`LiveWindowDepthCheck`. The protection half is the exception that proves it:
`SuperPlayer.deliveredSecurityLevel` is public and a consumer reads it, which is why rule 10 admits it
and rule 3 does not list a reader for it. There is no arrangement in which a
consumer's compilation can reach any of it, and a module that is not a friend is a consumer's
compilation.

**Core already knows two of the pathologies, and the knowledge is not a constant.**
`LiveWindowDepthCheck` fails a live DASH manifest whose window no playhead fits inside, and what it
compares is not a threshold anyone could copy: the manifest passes on `depthUs > lag.us` and fails on
everything else, where `lag.us` is
`segmentDurationUs - availabilityTimeOffsetUs` taken over the *longest* such lag any segment the
manifest addresses declares, because a player plays every selected track at once. `LivePlaylistRevalidation`
holds the second, RFC 8216's own bound on an overdue live playlist. #288 must report the first
*before* a player is built, and its acceptance criteria say "on the same threshold core's own
live-window check uses rather than a second copy of it". A second copy would be right on the day it
was written and wrong on the day either half moved.

**The precedent for crossing the boundary without friendship exists, and it does not apply here.**
`ContentCache` (ADR-0010 rule 3) and `DownloadEnvironment` (ADR-0013 rule 6) are public shells whose
constructors and members are `internal`: a type both sides can *name* crosses a phase gap while
nothing is published. What makes them work is that **both** sides are friends of core — testkit is
phase 2 and `superplayer-offline` phase 7, neither can name the other's types, and core's shell is
the type in the middle. The pattern moves a type across a gap between two friends. It does not give
a non-friend a way to *call* anything.

**A failure already has exactly one home, and a manifest defect is not a failure.** ADR-0011 rule 1
makes `ErrorClassifier` the single place a failure acquires a meaning, forbids a second `when` over
an error code anywhere in the repository, and rule 2 makes the taxonomy total with no unknown class.
Every class in it answers three questions about *acting*: whether retrying the same bytes can help,
the highest rung the ladder may climb, and a stable name. A manifest that overstates a rendition's
bandwidth answers none of those, because nothing failed: the player plays, adapts badly, and ends the
session successfully. `HostileManifestLadderTest` records that fact already — **no row moves** on a
player with the ladder, because every corpus entry serves its media perfectly and a ladder climbs
only when a load fails. `dash-availability-start-time-skew` sits in `CANNOT_RECOVER` for the same
reason: nothing fails to load, and the player simply sits at a negative position. The defect and the
class are two different kinds of fact about two different things.

**The corpus is written for this and is recorded rather than scored.** `superplayer-testmedia`'s
`HostileManifests` carries fifteen pathologies, each a good stream with one thing wrong, each with a
`// spec:` citation and a plain-language `cause` — whose KDoc says that `cause` is "the sentence
Phase 9's doctor turns into its user-facing message". A pathology with a magnitude is generated at
three `HostileStream.Severity` levels, and `docs/testing.md`'s *The hostile manifest corpus* already fixes what the mildest
means: "`BENIGN`: present, but within what real content does. A doctor must not flag it." The level
exists so a doctor is scored on false positives too. `HostileManifestCorpusTest` and
`HostileManifestLadderTest` *record* what each entry does to a player; neither scores anything, and
neither is this phase's to change, because they record the player's behaviour and this phase does not
change it.

**The trace exists, is redacted by construction, and stops one field short of a bundle.**
`SessionTraceRecorder` produces a `SessionTrace`: one fact per line, sorted by time then kind rather
than by arrival, and redacted against six rules stated in its KDoc — no URL, host, path, query or
token; no request or response header; no exception message; no session id and no wall clock; no
device identity; no DRM payload. `SessionTraceRecorderTest` drives a session whose source URL carries
a token and whose failure message names it, and asserts none of it reaches the trace. `SessionTrace`'s
own KDoc names this phase as the seam and states the obstacle: "Whatever a capability snapshot
records will need its own redaction rule before it is added, since a device model is exactly the kind
of identifier this format keeps out." Rule 5 there is not "no device *model*"; it is **no device
identity**, and a snapshot of what the device can decode is at least adjacent to one.

**The catalog says no other library module carries Compose, and the HUD makes that false.**
`gradle/libs.versions.toml`'s Compose block opens with "Jetpack Compose: the demo app, and
`superplayer-tv`'s D-pad controls (ADR-0014 rule 12)", and went on, before this change, to state "No
other library module has a Compose dependency." ADR-0014 already rejected splitting `superplayer-tv`'s composables into a
fourteenth artifact, on the grounds that it would split one phase's surface from its own rules. #294
asks for an overlay a developer places over their own video surface in a debug build, which is a
composable or it is a `View` hierarchy the library would have to own.

**What a consumer pays for is a decision, again.** ADR-0009 rule 7, ADR-0010 rule 13, ADR-0011
rule 14, ADR-0012 rule 13, ADR-0013 rule 15 and ADR-0014 rule 14 each promise that a player built
without a module pays nothing for it, and each counts it. Diagnostics is the first module a consumer
may add and then *not* attach to anything: a doctor is opened, asked, and released, without a player
in the call at all.

## Decision

**`superplayer-diagnostics` declares `superplayer-core` and `superplayer-telemetry` and reaches core
as its ninth Kotlin friend — built from core's seam, filling no slot, ADR-0013 rule 4's second
shape — over a closed list of internal seams, all but the first of which that shape does not cover and
which rule 3 admits by name, bounds, and grows only by being amended. A pathology is a defect of a stream and
carries a cause and a spec citation; a failure is `ErrorClassifier`'s and carries a `FailureClass`;
the module keeps no second taxonomy and a postmortem prints both without expressing either in the
other. The doctor fetches over the chain a player of that request would load through, and parses with
the parsers that player would run. Preflight and postmortem are one report and one API. The bundle is
the session trace one layer richer, and its capability snapshot is admitted by a seventh redaction
rule: what the device can do, never what the device is. The module is the second library module to
carry Compose, for a HUD the app places and guards. The doctor is scored against the curated corpus
with its false positives counted. An app that opens neither doctor nor HUD registers and allocates
nothing, and a test counts it.**

Thirteen rules follow, and they are binding.

### Dependencies and the seam

1. **`superplayer-diagnostics` declares `superplayer-core` and `superplayer-telemetry` in its main
   sources, and no other SuperPlayer module.** Core carries the doctor's input and output vocabulary
   — `MediaRequest`, `SuperPlayerError` and the `LoadKind` stamp — and the seam of rule 3.
   `superplayer-telemetry` carries `SessionTrace` and `SessionTraceRecorder`, and the bundle is that
   artifact one layer richer rather than a second recording of the same session; a second recorder
   would be exactly the drift ADR-0008 rule 2 puts one collector in one module to prevent.
   - **Not `superplayer-resilience`.** The postmortem of rule 5 reads the classification off core's
     public `SuperPlayerError`, which is where a consumer reads it and where `QoeCollector` reads it
     (ADR-0011 rule 3's `classification` is a stable *name*, not a `FailureClass` instance). A
     dependency on the resilience module would also make "a consumer with no resilience module still
     gets the findings" (#291) a claim the module's own classpath contradicts.
   - **Not `superplayer-ui`.** It is unscheduled, and `docs/modules.md` forbids depending on an
     unscheduled module. The HUD of rule 11 lives here.
   - **Tests.** The module's tests may depend on testkit, testmedia, abr, cache, drm, resilience and
     telemetry, which is the allowed direction. #290's register is scored against testmedia's corpus,
     and #291's controls need a player that failed.
   - **Third-party rows.** The Compose and tracing libraries the phase adds each arrive with their
     `THIRD_PARTY.md` row in the change that first uses them (#293, #294).

2. **The public surface names no Media3 type at all, with one named exception whose supertype is
   stable.**
   - **The doctor.** `MediaSourceDoctor` takes a `MediaRequest` and answers a report. Its whole
     public signature — entry point, report, finding, severity, citation, cause — is SuperPlayer and
     Kotlin types. That is stronger than ADR-0001 rule 2 asks, and it is asserted rather than
     assumed: #286's acceptance criterion is "names no Media3 type at all, `@UnstableApi` or
     otherwise". The doctor is a value-in, value-out object; nothing about it is engine-shaped.
   - **The bundle.** Likewise, over `SessionTrace`, which is `superplayer-telemetry`'s own public
     type and names no Media3 type either.
   - **The exception.** The HUD's composables take a Media3 `Player`, as `superplayer-tv`'s controls
     do (ADR-0014 rule 2). `Player` is stable, ADR-0001 rule 2 permits it, and it is the only
     supertype a stock `ExoPlayer` and a `SuperPlayer` share — which is the whole of #294's "degrades
     to what it can read rather than refusing".
   - The spelling above is this ADR's. An issue that finds a better one amends this rule in the
     change that renames it, rather than drifting from it.

3. **`superplayer-diagnostics` is core's ninth Kotlin friend, and what it may reach is a closed list
   of seams — one of ADR-0013 rule 4's second shape, and the rest admitted by name here. Three as
   first decided; four since #289.** It
   fills no `EngineConfiguration` slot: there is nothing about a player that it configures, and a
   report is not a decision the engine honours. Like `superplayer-offline` it is instead *built from*
   the seam. The list is exhaustive:
   1. **`TransferChain.diagnosticChain(...)`**, a named composition beside `downloadChain` and
      `downloadLicenceChain`, which is what rule 7 fetches over.
   2. **The internal capability readers** `deviceConstraintsOf()` / `DeviceConstraints` and
      `DisplayCapability`, read once into the snapshot rule 10 admits — and read through one
      core-internal function that *returns the snapshot*, rather than by the module reaching each
      reader and assembling one. A reader the snapshot does not carry is a reader the module does not
      see. The protection half of the snapshot is **not** on this list, because
      `SuperPlayer.deliveredSecurityLevel` is already public and the module reads it as
      `QoeCollector` does.
   3. **`LiveWindowDepthCheck`'s judgement**, so that rule 6 can be obeyed by calling it rather than
      by restating `depthUs > lag.us` in a second file.
   4. **`LivePlaylistRevalidation`'s judgement** — `cachedPastTheUpdateBoundSeconds(cacheControl,
      targetDurationMs)`, the one that decides whether a served `Cache-Control` lets a shared cache
      keep a live playlist past RFC 8216 §6.2.1's update bound. **Added by #289**, under this rule's
      own amendment clause and for exactly the reason seam 3 exists: the doctor reports that defect
      before a player exists, from the headers a manifest arrived with, and rule 6 makes a copy of
      the comparison a bug. It is a whole answer of the same shape as seam 3 — one function
      returning the judgement, and returning the *lifetime* rather than a boolean only because a
      report prints the number it judged on — and the alternative was two definitions of "held too
      long", of which one would be behind within a release. The list is four and is still closed.

   **A fifth seam needs this rule amended in the change that takes it**, by name, with the reason.
   That is what "whatever this ADR decides to expose deliberately" (#285) comes to: not a public API
   and not an open friendship, but an enumerated list, each entry of which a later worker can check
   themselves against.

   **What the closed list is for, stated plainly.** Seam 1 is ADR-0013 rule 4's second shape
   unchanged: the module reaches core's seam to *build* a chain, filling no slot, exactly as
   `superplayer-offline` does. Seams 2 and 3 are not that, and pretending otherwise would be having
   it both ways: reaching a reader and reaching a judgement is what `KotlinFriendModules.kt`'s
   ceiling calls "reaching a helper", and says wants a public API or a test source set instead. This
   ADR admits them anyway, and the admission is bounded rather than general. Each is a *whole answer*
   rather than a helper — one function returning the snapshot rule 10 admits, one function returning
   the judgement rule 6 forbids copying — and each exists because the alternative is worse in a way
   the alternatives below argue: publishing the readers wholesale, or keeping two definitions of one
   rule. The closed list is what stands in for the slot an earlier friendship named, and it is the
   only thing that keeps this from being the general friendship the ceiling refuses. **This ADR is
   therefore the ADR that ceiling asks for**, which is why the admission is written out rather than
   added in a build file. The ceiling itself does not move: a tenth friendship of either established
   shape needs no ADR, and a tenth of neither still needs one of its own. The change that first takes
   the friendship (#286) records this argument in `KotlinFriendModules.kt` beside the other eight, as
   ADR-0012 rule 4 requires.

### A pathology is not a failure

4. **A pathology is a defect of a stream; a failure is an end to a session. They have different
   shapes and the module builds only the first.** A `Finding` carries what naming a defect needs:
   the pathology's stable id, its severity, the `// spec:` citation of the clause the stream departs
   from, the plain-language cause, and the magnitude where the defect has one (how far the clock is
   skewed, and which way; how much the declared bandwidth overstates delivery). It carries **no**
   `FailureClass`, no `FailureCategory`, no `isRetryable` and no `FallbackRung`, because those are
   answers about acting on a session that ended and this one did not. Conversely no `FailureClass` is
   added, renamed or given a pathology field. `ErrorClassifier` stays the single place a failure
   acquires a meaning (ADR-0011 rule 1) and this module contains no `when` over a Media3 error code,
   which `DrmFailureTest`'s source-tree check is the existing pattern for.
   - **Why a pathology is not admitted to the taxonomy as a class.** ADR-0011 rule 1's test is what
     acting on a class needs. An overstated bitrate is not retryable, reaches no rung, and ends no
     session; a class for it would answer every one of its three questions with "not applicable",
     and the ladder would carry a branch it can never take. ADR-0011 rule 2 would then have to say
     the taxonomy is total over failures *and* over manifests, which are different domains.
   - **Why the severity is not a category.** `HostileStream.Severity` grades how far a property has
     departed from what real content does. `FailureCategory` buckets what ended a session. They are
     never compared, never mapped and never printed in the same column.

5. **A postmortem prints both and merges neither.** #291's examination of a failed session answers a
   report that carries the findings *and* the classification, in two fields that keep their own
   vocabularies:
   - The classification is `ErrorClassifier`'s, **read** off the `SuperPlayerError` the session
     already ended on, never recomputed. That is the same door `QoeCollector` uses (ADR-0011 rule 3):
     telemetry is not a friend of core and asks `player.classify(error)` as a consumer does.
   - The findings are the doctor's, produced by examining the request's manifest, and they are the
     same findings the same request would have produced as a preflight. Nothing about the failure
     narrows, widens or reorders them.
   - **They may disagree, and disagreement is information.** A stream whose only defect is benign and
     whose failure was the network's answers a classification and no finding. A stream that plays on
     but is misconfigured answers findings and no failure at all. A report that made the second
     impossible would have merged the two.
   - **A consumer with no resilience module gets the findings and no classification.** That is
     ADR-0011 rule 14's existing behaviour surfacing here, not a new rule, and it is rule 1's reason
     for not depending on the module.

6. **Where core already judges a defect, the doctor calls core's judgement rather than restating
   it.** The live-window rule is `LiveWindowDepthCheck`'s (rule 3's third seam), and the doctor
   reports the same defect before a player exists by asking the same code the same question. This is
   ADR-0011 rule 1's argument in the shape this phase takes it: one place decides what a thing means,
   and the other places ask. Two consequences are binding:
   - **A threshold copied is a bug against this rule**, whatever it is worth on the day it is
     written. The rule core holds is not a constant — it is `depthUs > lag.us` over the longest
     availability lag any addressed segment declares — and a copy would be a second definition of
     "too short" that nothing keeps in step.
   - **The doctor parses with the parsers the engine would run.** Media3's `DashManifestParser` and
     its HLS playlist parser are what the player is about to run over the same bytes, so the doctor
     and the player can never disagree about what the manifest *says* before they disagree about what
     it means. `LiveWindowDepthCheck` already makes this argument for itself, and it is stronger
     here: a doctor that read a manifest with a parser of its own would be diagnosing a document the
     player never saw. Both parsers are `@UnstableApi` and both stay behind rule 2's public surface.

### The fetch, and one report

7. **The doctor fetches over the chain a player of that request would load through, and never over an
   HTTP stack of its own.** The fetch enters at `TransferChain.diagnosticChain(...)`, so the token
   the app's `HeaderProvider` mints, the refresh a 401 or 403 triggers, the `ContentCache` the
   consumer opened and the CDN edge that answers are the ones a player would meet. This is ADR-0002's
   argument arriving through a different door: a second transport makes a measurement of something
   other than the thing being measured, and the defect a doctor is most often asked about — a token
   that has expired, a `Cache-Control` header the CDN and the origin disagree about, a CORS
   misconfiguration (#289) — is *invisible* to a stack that does not carry the app's credential or
   pass through the app's cache. A doctor with its own client would report a healthy manifest for a
   stream no player of that app can fetch.
   - **Manifests only, and never media.** The doctor reads manifests and playlists. It downloads no
     segment: the pathologies are declarations, and fetching media to check one would make a
     preflight cost what a start costs.

     **#289's addendum: never media *bytes*, and one segment's headers where naming the party at
     fault needs them.** Two of the defects this rule's own paragraph lists — a `Cache-Control` the
     CDN and the origin disagree about above all — are disagreements *between two responses*, so
     saying which of the two is misconfigured cannot be done from one of them. The doctor may
     therefore open the transfer of a **single** segment of a single rendition, read its headers and
     close it without reading a byte, asking for one byte so that the request is a request rather
     than a download. The cost is one round trip and no media, which is the bound the bullet above is
     protecting; what it is not is a second HTTP stack, since the probe travels the same chain,
     stamped `LoadKind.MEDIA` because that is what it is. Two limits keep it honest and are in
     `DeliveryPathologies`: the probe is spent only by a rule that **already has a defect to
     attribute**, so a correctly delivered stream pays nothing for it, and it is never spent twice.
   - **It is measured by nothing.** The chain it composes reports to no bandwidth meter and emits no
     CMCD. A doctor's fetch is not a viewing: an estimate seeded from it would be ADR-0009 rule 8's
     memory polluted by a transfer no viewer waited for, and a CMCD `sid` on it would put a row in
     the CDN's log that joins to no telemetry session (ADR-0008 rule 6). `superplayer-offline` took
     the same two exclusions for the same reason (ADR-0013 rule 5).
   - **A request the chain refuses is reported, not thrown.** A manifest that cannot be fetched at
     all is itself a finding — with the HTTP status where there is one — because "the doctor threw"
     is the least useful thing a support ticket can say.

8. **Preflight and postmortem are one report and one API.** There is one report type, one finding
   type and one severity scale. Examining a request before playback and examining the request a
   failed session was playing produce the same report, differing only in whether the classification
   field of rule 5 is populated. Two APIs would be two vocabularies within a week — a "preflight
   warning" and a "postmortem cause" that mean the same thing about the same manifest — and the
   corpus register of rule 12 could then score only one of them. It also makes the useful workflow
   trivial: the report a support engineer reads after a failure is the report the app could have
   read before it.

### The bundle

9. **The bundle is the session trace one layer richer, in the same format, and it adds kinds rather
   than a second grammar.** `SessionTrace`'s KDoc already fixes this and this rule only holds it:
   a richer trace adds *kinds*, it does not reorder an existing kind's fields, and `FORMAT_VERSION`
   moves when a line's **meaning** changes and not when a kind is added — ADR-0008 rule 5's rule for
   `SCHEMA_VERSION`, applied to the format. So load timings and HTTP status, which rules 1 to 6
   already permit, are new fields on the `load` kind; bandwidth samples and the capability snapshot
   are new kinds. The golden that pins the format is regenerated by `./gradlew updateGoldenTraces`
   and by no second mechanism. The bundle carries a header naming itself and its format version, so
   a reader who has never seen this repository can tell what they are holding.
   - **The trace's grammar is canonical; JSON and Perfetto are renderings of it.** `PRD.md` §3.6
     calls the bundle "one exportable JSON/Perfetto-compatible artifact", and that is narrowed here:
     the facts are recorded once, in the format whose ordering, determinism and redaction are already
     argued and already pinned by a golden, and an export renders those facts into whatever a tool
     wants to read. Two authored formats would be two redaction implementations, of which one would
     be behind. `SessionTrace`'s KDoc names "a Perfetto-compatible export beside it", which is this.

10. **The seventh redaction rule: a capability snapshot records what the device can *do*, never what
    the device *is*.** This is the rule `SessionTrace`'s KDoc says a snapshot needs, and #292 carries
    it in `SessionTraceRecorder`'s KDoc beside the existing six and tests it.

    **7. No device identity, and a capability is admitted only where the library itself branches on
    it.** A snapshot may carry what a decision in this library was made against:
    - per-MIME decoder capability as the selection gate reads it — the MIME type, whether a secure
      decoder is declared for it, the concurrent-instance limit, the highest profile and level, and
      whether tunneling is declared;
    - the display's active-mode shorter edge in pixels and its `HdrType` set, as `DisplayCapability`
      carries them;
    - the audio output's declared encodings;
    - the app's heap budget in megabytes and whether the platform calls the device low-RAM;
    - the Widevine security level reported and the level delivered (`player.deliveredSecurityLevel`);
    - the platform API level.

    It may **not** carry, and the recorder does not hold: any `Build` string —
    `MANUFACTURER`, `MODEL`, `DEVICE`, `PRODUCT`, `BOARD`, `FINGERPRINT`, `ID` or `SERIAL`; the
    **decoder component name**, which names a vendor and usually a chipset and is the field a
    snapshot is most tempted by; any DRM identifier, `MediaDrm`'s device unique id above all, and no
    provisioning or key-set id, which rule 6 already refuses as payload; the display's id or name;
    and anything of the network — operator, SSID, address or interface name.

    **The test is not size but readership.** A capability is admitted because some code in this
    library reads it to decide something — the gate refuses a rung against it, the pool bounds
    itself on it, the ladder is offered it — and a capability nothing reads is a fingerprint bit with
    a plausible excuse. That is the same argument ADR-0009 rule 1's closed list of observations makes,
    and it is why rule 3's second seam returns the snapshot rather than the readers: a field that is
    not in the snapshot is a field the module cannot print by accident.
    - **Why a decoder's MIME is admitted and its component name is not.** "This device declares one
      secure `video/avc` decoder" is the fact a CDN engineer needs and the fact the library acted on
      (ADR-0012 rule 12, #211). `OMX.qcom.video.decoder.avc.secure` adds the vendor, and a vendor
      plus an API level plus a display size is a device model spelled differently.
    - **What this rule does not claim.** It bounds each field, not the aggregate. Consequences says
      so plainly, because a reader of a bundle deserves to know what they are attaching.

### Compose in a second library module

11. **`superplayer-diagnostics` is the second library module to carry Compose, and the HUD is the
    app's to place and to guard.** The catalog's comment is amended in this change to say two library
    modules carry it and why each does.
    - **Why a composable at all.** The HUD is an overlay over the consumer's own video surface, in
      the consumer's own layout. A library that shipped it as a `View` would be choosing the app's
      view system for it; a library that shipped it as a window would own a window (ADR-0014 rule 13
      refused that for `FLAG_SECURE`, for the same reason). A composable the app places is the only
      shape in which the library provides the HUD and the app keeps the screen.
    - **Why not a fourteenth module.** ADR-0014 rejected `superplayer-tv-compose` on the grounds that
      it splits one phase's surface from its own rules, and the same holds: a module holding only the
      HUD would be governed by this ADR from another artifact, and its only saving is Compose on the
      classpath of an app that adds diagnostics for the doctor alone — which R8 removes, because a
      composable nobody calls is unreachable code.
    - **The module is not debug-only, and the HUD is.** The doctor and the bundle are release-usable
      and are most valuable there: a postmortem on a viewer's device is the point (#291). So the
      module cannot be a `debugImplementation` artifact. What must be unreachable from a release
      build is the *overlay*, and the mechanism is the app's build rather than a runtime flag the
      library reads on the app's behalf. #294 chooses and argues that mechanism at the point it
      builds it; this rule fixes only that the guard exists, that a release build shows nothing by
      default, and that no builder call attaches a HUD to a player.
    - **It opens no seam.** #294's "reads only published API" is binding: the HUD reads the telemetry
      vocabulary, `player.playbackDecision` and the `Player`'s own state, and rule 3's closed list
      does not grow for it.
    - **Where the estimate-against-selected line comes from, since it is the line worth the HUD.**
      The selected rung is the `Player`'s own current track, and the estimate is **not** read from
      `superplayer-abr` — `BandwidthOracle` is a later phase's public type that rule 1 keeps out of
      the main sources, and no core getter publishes a throughput reading. The HUD derives it from
      the telemetry vocabulary the consumer's collector already emits, which is why #294 requires a
      `TelemetryCollector` attached and shows the line as unavailable without one, exactly as it
      degrades on a stock `ExoPlayer`. If #294 finds the vocabulary insufficient, it adds the field
      to `TelemetryEvent` — shape, not meaning, so `SCHEMA_VERSION` stays where it is (ADR-0008
      rule 5) — rather than reaching a fourth seam, and rule 3 is amended by name if it turns out
      otherwise.

### Scoring

12. **The doctor is scored against the curated corpus, and a false positive counts as heavily as a
    miss.** #290's register is the phase's exit criterion in the shape of `FallbackRungCoverageTest`:
    per corpus entry, the finding the doctor reports and the test that forces it, checked **against
    the corpus itself** so that a sixteenth pathology added later fails the register rather than
    going unnoticed.
    - **Both halves in one place.** Every pathology is named at its `SEVERE` grade, and no `BENIGN`
      grade and no healthy stream produces any finding. A doctor that flags everything scores
      perfectly on the first half and is useless, which is why the second half is enforced per entry
      rather than in aggregate. `docs/testing.md`'s *The hostile manifest corpus* already states what `BENIGN` means, and
      this rule is that sentence made into a gate.
    - **An entry the doctor cannot yet name is recorded, not removed.** The register may carry a
      named exception with its reason, the way `HostileManifestLadderTest` records
      `dash-availability-start-time-skew` in `CANNOT_RECOVER` rather than leaving it out. What it may
      not do is drop the entry or relax the assertion. After #288 that particular entry is *named* by
      the doctor while the ladder still cannot recover it, and both records stand: they are readings
      of two different things, which is rule 4 again.
    - **The existing recordings are untouched.** `HostileManifestCorpusTest` and
      `HostileManifestLadderTest` record the player's behaviour, and this phase does not change it. A
      row that moves carries its reason.

### Pay nothing

13. **An app that opens no doctor and places no HUD registers and allocates nothing for the module,
    and a test counts it.** Concretely:
    - No engine slot is filled, because the module fills none (rule 3).
    - No listener, sink or collector is registered by adding the module to the classpath. A doctor is
      constructed, asked and released; a bundle is built from a recorder the consumer attached.
    - A player built without a doctor composes the chain it composes today; `diagnosticChain` is
      reached only from a doctor.
    - No class from `superplayer-diagnostics` is loaded.
    - A core-only session's golden trace is byte-identical before and after Phase 9, except where a
      trace point or a new kind is deliberately added and its golden regenerated in that change.

    The count is taken the way ADR-0012 rule 13's and ADR-0014 rule 14's are: once with a plain
    player, and once with a doctor really opened, so the counter is shown to see what it counts.
    #286 lands the first half with the tracer bullet.

## Consequences

**Easier.** Every Phase 9 issue after this one (#286–#296) has a written answer to the question it
would otherwise settle at a call site: how the fetch reaches the network (#286, #289), where the
live-window threshold lives (#288), what a finding may carry and what it may not (#287–#289), how a
classification and a pathology sit in one report (#291), what a snapshot may print (#292), why the
HUD is a composable here rather than a module of its own (#294), and what "scored against the corpus"
means when an entry cannot be named (#290). Rule 3's closed list means a worker who has read only
this ADR knows both what they may reach and what they must come back and amend.

**Harder.** Rule 3 makes `superplayer-diagnostics` a friend of core, which is the thing #285's own
description leaned against. The cost is real and is stated: nine friends is nine compilations that
can see core's `internal`, and the ceiling `KotlinFriendModules.kt` writes down gets less useful each
time it is instanced rather than reargued. What bounds it here is the closed list, which no earlier
friendship carried: the first eight each named a slot, or the one seam a chain is assembled at, and
this one names three functions, two of which would otherwise be the "reaching a helper" that same
ceiling refuses.

Rule 7's "over the player's chain" means a doctor is only as reachable as a player. A doctor opened
with no cache and no resilience fetches over the bare transport, which is correct — it is what such a
player would meet — but it also means two apps asking the same doctor about the same manifest can get
different answers, and the report has to say which chain it travelled or the difference looks like a
bug in the doctor.

Rule 10 bounds each field of the snapshot and not the aggregate. An API level, a display size, an HDR
set and a decoder table are, together, close to a device model even with every `Build` string
refused. The trade is accepted because a bundle is produced and attached deliberately by someone who
wants the defect found, which is not true of telemetry a sink ships continuously — and it is the
reason the snapshot is a *bundle* kind rather than something the recorder holds on every session.
The rule is what keeps that aggregate from also carrying the vendor, the id and the network, each of
which turns a fingerprint into an identity.

Rule 11 puts Compose on the classpath of an app that wanted only the doctor. R8 removes what it does
not call, but a non-minified debug build carries it, and an app measuring its own debug APK will see
it.

Rule 12 makes a false positive a build failure. That is the intended severity and it will be
inconvenient: a heuristic that is right about fourteen entries and flags one `BENIGN` cannot be
merged with a note. The remedy is a narrower heuristic or a recorded exception with its reason, and
both are better than a doctor nobody trusts.

Rule 4 leaves one thing genuinely unowned: a defect that is *also* a failure — a manifest the parser
rejects, say — is a `Content.ManifestInvalid` to the classifier and may also be a finding to the
doctor. Nothing merges them, so a postmortem prints both. That is a small redundancy, and it is the
price of neither vocabulary being defined in terms of the other.

## Alternatives considered

**`superplayer-diagnostics` as a non-friend, reading only what a consumer can reach.** This is what
#285's own description leaned toward, and it is rejected under rule 3. It fails on a fact rather than
on a preference: there is nothing a consumer can reach that composes the chain. `TransferChain` is
internal, every type in it is `@UnstableApi`, and a non-friend module *is* a consumer's compilation.
A doctor built this way would either fetch over its own HTTP stack — rule 7's refusal, and ADR-0002's
— or report on a manifest fetched without the app's token, through no cache, which is a report about
a stream nobody plays. The capability snapshot and the live-window rule fail the same way. The option
is not weaker than the one chosen; it is unbuildable without the next alternative.

**A public core API for the fetch and the snapshot, so no friendship is needed.** Rejected under
rules 2 and 3, and it is the serious alternative rather than a straw one. Core would grow a public
type — call it `DiagnosticEnvironment` — with a public `fetch` answering bytes and a status, and a
public capability snapshot, both naming no Media3 type and therefore passing
`verifyNoUnstableMedia3InPublicApi`. It is buildable and it has a real advantage: a consumer could
write their own doctor. It is rejected for three reasons. First, it publishes a *fetcher* over the
app's credential and cache to every consumer forever, which is the "second seam" `docs/testing.md`
warns about — a widening of what is configurable and reachable, not merely one more of the library's
own compilations seeing `internal`, which is the distinction `KotlinFriendModules.kt` draws and the
only thing that has kept eight friendships honest. Second, it does not actually avoid the pattern's
precondition: `ContentCache` and `DownloadEnvironment` are public shells with **internal** members
precisely because both ends are friends, and a version with public members is a different animal
wearing the same name. Third, #292 states the requirement this option cannot meet — "Exposing it
means choosing what a report may carry, not publishing the readers wholesale" — because a public
snapshot is readable raw by any consumer, and rule 10's redaction then guards only the bundle while
the fields themselves are published. The friendship plus rule 3's closed list gets the same
narrowness with none of that, at the cost of a consumer not being able to write their own doctor,
which nobody has asked for.

**A pathology as a new `FailureClass` branch, so one taxonomy covers both.** Rejected under rule 4.
It is superficially tidy and it breaks ADR-0011 rule 1's own test: a class exists to answer what
acting on a failure needs, and a manifest defect that ended no session answers none of those three
questions. It would also make ADR-0011 rule 2's totality claim ambiguous — total over
`PlaybackException`s, or over manifests? — and put a branch in `FallbackLadder` that no climb can
reach.

**A `FailureClass` on every finding, as a cross-reference.** Rejected under rules 4 and 5. It is the
merge wearing a weaker word: once a finding carries a class, a pipeline groups by it, and the doctor
has a taxonomy whether or not it wrote one. The postmortem's two fields give a reader the same
correlation without making either vocabulary depend on the other.

**Preflight and postmortem as two APIs.** Rejected under rule 8. The postmortem's extra input is one
field, the error the session ended on, and everything else is identical. Two entry points would grow
two vocabularies for one manifest, and rule 12's register would score whichever one the test happened
to call.

**The doctor with its own HTTP client, for isolation.** Rejected under rule 7 and ADR-0002. Isolation
is exactly the wrong property: the defects #289 exists to find are properties of the path an
authenticated, cached request takes, and a clean client cannot see a token that expired, a cache that
disagrees with its origin, or a CORS rule that only fires for the app's origin.

**The doctor fetching a segment to check a declared bitrate against delivery.** Rejected under rule
7. It would make a preflight cost what a start costs, and the corpus's overstated-bitrate entry is a
*declaration* that disagrees with the media the manifest itself describes — readable without
transferring it. A later issue that genuinely needs delivered throughput argues it then, as a
separate, opt-in verb.

**A second recorder in `superplayer-diagnostics`, so the bundle does not depend on telemetry.**
Rejected under rules 1 and 9. ADR-0008 rule 2 puts the derivation in one module because a second
derivation of the same facts drifts, and `SessionTraceRecorder`'s own KDoc makes that argument for
itself. It would also produce two redaction implementations, of which one would be behind.

**The capability snapshot on every session trace, rather than only in a bundle.** Rejected under
rule 10. A trace is a golden-test artifact as well as a bug-report artifact, and a device snapshot in
it would make goldens device-dependent. It would also put the aggregate of Consequences into every
trace a sink ever ships, which is the opposite of the deliberate, one-off attachment that makes the
trade acceptable.

**A denylist of forbidden fields instead of rule 10's readership test.** Rejected under rule 10. A
denylist is complete on the day it is written: the next platform release adds a getter, and a
snapshot that grew it is compliant with the list and wrong. "Something in this library reads it to
decide" is a test a reviewer can apply to a field that did not exist when the rule was written.

**The HUD in `superplayer-ui`, or in a fourteenth `superplayer-diagnostics-hud` module.** Rejected
under rule 11. `superplayer-ui` is unscheduled and nothing may depend on it (`docs/modules.md`); a
fourteenth module is the split ADR-0014 already rejected for `superplayer-tv`, and its only saving is
removed by R8.

**The HUD guarded by the library reading the app's `FLAG_DEBUGGABLE`.** Not rejected, but not decided
here. It is a plausible mechanism and it is the library deciding on the app's behalf, which is the
tension #294 has to resolve at the point it builds it. Rule 11 fixes the requirement — a release
build shows nothing by default, and nothing attaches a HUD to a player — and leaves the mechanism to
the issue that owns it, rather than fixing a choice this ADR cannot test.

**Scoring the doctor only on true positives, with false positives reviewed by hand.** Rejected under
rule 12. `docs/testing.md` and the corpus already carry a `BENIGN` level whose entire purpose is this
gate, and a false-positive rate reviewed by hand is one nobody reviews.

## References

- `PRD.md` §3.6 (`superplayer-diagnostics`) and Part 4's phase 9 row.
- ADR-0002, the local-proxy refusal whose argument rule 7 reuses:
  [0002-no-local-http-proxy.md](0002-no-local-http-proxy.md)
- ADR-0008 rules 2 and 5, the collector's home and the versioning rule rule 9 applies to the trace:
  [0008-measure-behind-an-engine-agnostic-sink-boundary.md](0008-measure-behind-an-engine-agnostic-sink-boundary.md)
- ADR-0011 rules 1 to 3, the one place a failure acquires a meaning:
  [0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md)
- ADR-0012 rule 4 and ADR-0013 rule 4, the friend ceiling and its second shape:
  [0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md](0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md)
  and [0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md](0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md)
- ADR-0014 rules 2, 12 and 13, the Compose precedent and the refusal to own a window:
  [0014-match-the-display-and-watch-it-change-behind-one-output-slot.md](0014-match-the-display-and-watch-it-change-behind-one-output-slot.md)
- `SessionTraceRecorder`'s six redaction rules and `SessionTrace`'s format contract, in
  `superplayer-telemetry/src/main/kotlin/com/superplayer/telemetry/`.
- The curated corpus and its severity grading: `HostileManifests` and `HostileStream` in
  `superplayer-testmedia`, and `docs/testing.md`'s *The hostile manifest corpus*.
- HLS: RFC 8216 — https://www.rfc-editor.org/rfc/rfc8216
- DASH: ISO/IEC 23009-1, in particular §5.3.1.2 (`@publishTime`),
  `@availabilityStartTime` and `@timeShiftBufferDepth` —
  https://www.iso.org/standard/83314.html
- CTA-2066 (QoE) and CTA-5004 (CMCD), the vocabularies a bundle prints and rule 7 keeps a doctor's
  fetch out of — https://shop.cta.tech/products/web-application-video-ecosystem-quality-of-media-experience-consumer-technology-association-cta-2066
  and https://shop.cta.tech/products/web-application-video-ecosystem-common-media-client-data
- `androidx.tracing` — https://developer.android.com/reference/androidx/tracing/Trace
- Compose for TV and Compose's R8 behaviour, as ADR-0014 cites them.
