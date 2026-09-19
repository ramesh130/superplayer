# ADR-0017: Version the library by semver, in lockstep, with the tracked API surface as the arbiter

- **Status:** Accepted
- **Date:** 2026-09-19
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** None — this record **changes no earlier rule's scope**, and so adds an addendum to no
  earlier document. It rests on two rules it leaves exactly as they are:
  [ADR-0001](0001-compose-dont-fork.md) rule 2, whose `@UnstableApi` boundary
  is what makes rule 4 below true rather than hopeful and whose one named exception —
  `player.exoPlayer` — is named again here because a compatibility promise that leaves it to be
  inferred is not a promise; and
  [ADR-0008](0008-measure-behind-an-engine-agnostic-sink-boundary.md) rule 5, which versions the
  telemetry vocabulary on its *meaning* and which rule 6 below states the independence of rather
  than restating.

## Context

Thirteen library modules publish to one Maven group from one version string,
`gradle/libs.versions.toml`'s `superplayer`, which the `superplayer.android.library` convention
plugin reads once and applies to every module. The string currently reads `0.1.0-SNAPSHOT` and has
never moved, because nothing has been released: ten phases of work have been committed against a
version that means "not yet anything".

That is affordable only until the first release. After it, the string is the entire contract a
consumer has with this repository. An adopter deciding whether `0.4.0` can replace `0.3.2` in their
build has nothing to read: no document says what a bump means here, no procedure says what has to
move with it, and nothing in `check` would notice a version that stayed still through a change that
removed a public method. The question also arrives five times over in this set of tickets — the
version-and-changelog check (#327), the surface-versus-bump gate (#328), the release command (#329)
and the adopter-facing document (#330) each need a rule to cite, and four tickets inventing four
compatible rules independently is the outcome this record exists to prevent.

Three facts of this repository, rather than three preferences, decide most of what follows.

**Mixed versions are not expressible.** `docs/modules.md`'s rule is that dependencies point inward
toward `superplayer-core` and no module depends on one from a later phase. Every optional module
therefore compiles against core's internal seams — nine of them reach core as Kotlin *friend*
modules, which is a compiler flag rather than a Gradle dependency and carries no ABI guarantee
whatsoever. `superplayer-drm` 0.4.0 against `superplayer-core` 0.3.0 is not a configuration that was
ever built, tested or benchmarked here.

**"Breaking" already has a mechanical definition.** Every published module's consumer-facing API is
checked into `<module>/api/<module>.api` and compared on every build by `checkApiSurface`
(`docs/api-surface.md`). A repository that already computes the diff does not need a second,
softer notion of breakage decided per pull request.

**Media3's instability is already walled off.** ADR-0001 rule 2 keeps every `@UnstableApi` Media3
type out of the public API, and `verifyNoUnstableMedia3InPublicApi` fails the build rather than
merely showing the leak in a diff. The version of Media3 this library composes on is therefore an
implementation fact, with exactly one named hole in that claim.

## Decision

**SuperPlayer is versioned `MAJOR.MINOR.PATCH` by Semantic Versioning 2.0.0. Every published module
carries the same version and moves with the others. What counts as a breaking change is what the
tracked API surface says is a breaking change, not what a reviewer judges it to be. The library
stays on `0.x` until the phases are closed, and under `0.x` a minor release may break.**

Nine rules follow, and they are binding. Each says whether it is enforced mechanically and by what,
or that it is not yet and which ticket will enforce it.

### The set and its number

1. **One version for every published module, moved in lockstep.** All thirteen library modules are
   released together at one number, and a module with no change in it is re-released at the new
   number rather than left behind. The reason is not tidiness: a consumer who could mix versions
   would be assembling a combination nothing here has built. Optional modules reach core through
   internal seams and, in nine cases, through Kotlin friend compilation — a mechanism with no ABI
   contract at all, where a signature change core is free to make under rule 2 breaks a friend
   module compiled against the previous one, invisibly to every check in this repository. Lockstep
   is what keeps "what was tested" and "what a consumer can resolve" the same set.

   *Enforced by construction.* The convention plugin sets `version` from the single catalog entry
   for every module it is applied to, so a per-module version is not something a build script here
   can express without deleting that line. No task asserts it, because there is nothing yet for a
   task to disagree with.

2. **The tracked API surface is the arbiter of the bump.** Against the previous release: a
   declaration removed from, or incompatibly changed in, any module's `api/<module>.api` is a
   **major**; a declaration added with nothing removed is a **minor**; a release whose tracked
   surface is byte-identical is a **patch**. The judgement is mechanical and is made on an artefact
   that already exists, reviewed in the same commit as the code that moved it
   (`docs/api-surface.md`, `CONTRIBUTING.md`). The alternative — a maintainer deciding per change
   whether something counts as breaking — is the judgement that gets made wrongly under time
   pressure, and it gets made wrongly in the permissive direction, because the version that costs
   nothing to ship is always the smaller one.

   *Enforced in part.* `checkApiSurface` runs in `check` for every module and makes the surface
   change visible and reviewed, but it compares the working tree against the tracked file, not the
   tracked file against the last release's. **#328** is the gate that closes that: the surface diff
   since the last release tag, compared against the bump being proposed, failing when the two
   disagree.

3. **A behaviour change with an unmoved surface is still a major.** The surface is the arbiter of
   *at least* how far the version moves, never of at most. A changed default in
   `StaticProfilePolicy`, a retry budget that shrank, a cache key that now spells a URI differently
   — each is a change a consumer's app can be broken by and none of them removes a declaration.
   Stating this is the honest cost of rule 2, and leaving it unstated would turn a mechanical floor
   into a ceiling nobody agreed to.

   *Not enforced mechanically, and not enforceable.* No tool can tell which behaviour change a
   consumer depended on. What narrows it is that behaviour changes are already visible here: a
   golden trace diff (`./gradlew updateGoldenTraces`) and the QoE floors in
   `superplayer-abr/src/test/qoe-floors.tsv` both make one appear in review, and **#327**'s check
   requires the release to carry a changelog row, which is where the judgement is recorded rather
   than made silently.

4. **A Media3 version bump is not automatically a library major.** ADR-0001 rule 2 keeps every
   `@UnstableApi` Media3 type out of the public API and
   `verifyNoUnstableMedia3InPublicApi` fails the build on a breach, so a Media3 release that
   rearranges `LoadControl`, `TrackSelector` or `AnalyticsListener` moves nothing in any tracked
   `.api` file and is governed by rule 2 like any other internal change. This is the direct dividend
   of that ADR: a library that re-exported Media3's unstable surface would be forced to a major on
   every engine upgrade, which is precisely the compile-time burden rule 2 exists to keep off
   consumers.

   **The single exception is `player.exoPlayer`**, ADR-0001 rule 2's one named breach. It exposes an
   `@UnstableApi` type deliberately, and that ADR already places anything reachable only through it
   outside SuperPlayer's compatibility promise. It is therefore outside this record's too: a Media3
   change that breaks a consumer reaching through the escape hatch moves no version here, because
   the compatibility it carries is Media3's own. Saying so is the point of naming it — the rule a
   reader would otherwise infer, that a public `@UnstableApi` member drags the whole library to a
   major, is not the rule.

   What a Media3 bump does move is the **minor**, never the patch, because the version a consumer's
   build resolves changes with it and a consumer who pins Media3 themselves can be forced off their
   pin. The supported range is `docs/modules.md`'s to restate in the same change, which
   `verifyMedia3SupportedVersion` enforces.

### What this version is not

5. **The build's own versions are not this version.** The Android `minSdk`, the JDK the daemon
   runs, the Kotlin and AGP versions and every dependency in `gradle/libs.versions.toml` move on
   their own schedules. A raise of `minSdk` is the one that reaches a consumer at resolution time
   and moves the minor under rule 4's argument; the rest are internal until they move the tracked
   surface, at which point rule 2 already answers.

6. **The two format versions are not this version, in either direction.**
   `TelemetryEvent.SCHEMA_VERSION` and `SessionTrace.FORMAT_VERSION` version *what a number means*
   to a data pipeline and a trace reader, under ADR-0008 rule 5's own rule — they move on meaning
   and not on shape, which is why `SCHEMA_VERSION` has stood at 2 across four releases' worth of
   added event types. They are read by a warehouse and a bug report, not by a build. A library
   major does not move them and their moving does not decide the library's bump: a changed
   denominator that adds no field and removes no declaration is a `SCHEMA_VERSION` bump and, under
   rule 3, a library major on its own merits — two facts about two audiences, stated separately
   because a pipeline that read the library version as a schema version would silently mis-parse
   every event.

   *Enforced by the shape of the dump.* Both constants appear in the tracked surface as
   `public static final field SCHEMA_VERSION I` and `… FORMAT_VERSION I` — the declaration is
   tracked, the value is not — so the two systems cannot collide mechanically even by accident. The
   meanings behind them are `docs/telemetry-schema.md`'s *Release notes*, which is where a moved
   schema version is argued and where **#330** sends an adopter, rather than here.

### Before 1.0, and what ends it

7. **The library stays on `0.x` while the phases are open, and under `0.x` a minor may break.**
   `PRD.md` Part 4's phases 0–10 are functional and Phase 11 is tuning; a tuning phase whose whole
   purpose is to move the adaptive policy, the preload path and the profiles cannot also promise
   that none of it changes shape. Rather than ship a `1.0.0` whose compatibility promise the roadmap
   would break within a phase, this record says the plain thing: **under `0.x`, a minor release may
   remove or change public declarations, and rule 2's "major" has nowhere to go until the major is
   non-zero.** Rules 2 and 3 still govern the *reporting* — a surface that shrank is still a
   documented, reviewed, changelog'd shrink; it is the number that cannot express it.

   What takes the library to `1.0.0` is all four of: every functional phase closed; Phase 11's exit
   criteria met and reported (`benchmark/phase3/`'s verdict, the feed demo's p50 TTFF target, and
   peak RSS and battery from a device); **#330**'s adopter-facing compatibility document published;
   and rules 2 and 8 held for at least one release cycle without the `0.x` escape being needed.
   Until all four hold, the version stays on `0.x` and nobody is asked to argue it again.

   *Not enforced mechanically, and deliberately not.* A check that refused a `1.0.0` would be a
   check asserting a judgement about a roadmap. What #327 does enforce is that a version moved at
   all and that a changelog row exists for it.

8. **A snapshot is not a release, and nothing published from one is covered.** A version carrying
   the `-SNAPSHOT` suffix means *unreleased*: rules 1 through 7 describe releases, and a snapshot
   makes no claim under any of them, so an artefact resolved from one may change under its own
   coordinates without notice. This is why today's `0.1.0-SNAPSHOT` is not a promise that has been
   kept — it is the absence of a promise, and it is correct that ten phases moved under it. Two
   consequences bind: a release is a version with the suffix removed, and **no release procedure may
   leave a snapshot behind**, neither by publishing one under a release tag nor by tagging a release
   and leaving the catalog on the version just published rather than on the next snapshot.

   *Enforced by **#329***, the release command, which is the only thing that can enforce it because
   it is the only thing that performs the transition. **#327** covers the standing half: the catalog
   version and the changelog agree, whichever of the two states the tree is in.

9. **Deprecate before removing.** A public symbol on its way out is annotated `@Deprecated` with its
   replacement named — `ReplaceWith` where the replacement is expressible — and survives **at least
   one minor release** in that state before it may be removed; the removal itself happens in a
   major. The cycle is the part of the promise that costs an adopter nothing to act on: a warning at
   compile time in a release they chose to take is a migration they can schedule, where a removal is
   a build that stopped working. Under rule 7 this is what a `0.x` still keeps — "a minor may break"
   licenses the version number to be small, not the removal to be a surprise — which is the whole
   reason the two rules are separate.

   *Not enforced mechanically, and honestly so.* `checkApiSurface` compares declarations and their
   signatures; it cannot tell a removal that followed a deprecation cycle from one that did not,
   because the cycle is a fact about two releases and the tracked file describes one tree. Review
   and the changelog row **#327** requires carry it, and **#330** is where it is described to an
   adopter, who is the only party it is for.

## Consequences

**What becomes easier.** The bump stops being a conversation. A release is a diff of tracked `.api`
files and a rule, so the version can be proposed by a tool (#328) and argued only where the tool and
the author disagree. The four tickets that follow this one each cite a rule instead of settling the
same question four ways. An adopter gets an answer to "can I take this" that does not require
reading the commit log.

**What becomes harder.** Lockstep means a one-line fix in `superplayer-tv` re-releases thirteen
artefacts, and the release procedure #329 builds has to be cheap enough that this is not a reason to
batch fixes. Rule 2 also makes the tracked surface load-bearing in a second way: a file regenerated
carelessly — `updateApiSurface` run to make a failure go away rather than to record an intended
change — now mis-states the next version as well as the current API, and the existing rule that the
regeneration and the code land in one reviewed commit is what stands between the two.

**What is honestly unfinished.** Four of the nine rules are not enforced by anything in `check`
today. Rule 3 never will be. Rule 7 deliberately is not. Rules 8 and 9 are #329's and a reviewer's
respectively, and rule 2's enforcement is half-built until #328 lands. This record is written before
its enforcement exists, which is the right order — the gate needs a rule to cite — but a reader
should not mistake "binding" for "checked".

**What this costs the first release.** The library has never been released, so rule 2 has no
previous surface to compare against and the first release is `0.1.0` by declaration rather than by
derivation. #328's gate has to say so rather than fail on a missing baseline.

## Alternatives considered

**Version each module independently.** Rejected. It is the shape a set of genuinely independent
libraries takes, and this is not one: the friend-module seams mean `superplayer-abr` compiled
against one core is not interchangeable with the same source compiled against another, and no check
here would catch the mismatch. It would also multiply the release procedure by thirteen and put the
compatibility matrix — which pairs were ever built together — into a table someone maintains by
hand.

**Let the maintainer judge what is breaking.** Rejected as the thing rule 2 exists to replace. The
judgement is real (rule 3 keeps it), but making it the *primary* definition means the mechanical
artefact this repository already computes gets ignored in favour of an opinion formed at the end of
a change, by the person least able to see it from outside.

**Tie the library's major to Media3's.** Rejected. It would make ADR-0001 rule 2's boundary
pointless: the entire value of wrapping the unstable surface is that an engine upgrade is ours to
absorb. It would also be a claim we cannot keep in the other direction, since a Media3 patch that
changes behaviour we expose through a profile is a library concern whatever Media3 called it.

**Ship `1.0.0` now and use majors freely.** Rejected. It is technically honest semver and it is
practically a lie to a reader, who reads `1.x` as "this API has settled". The phases say it has not.
`0.x` is the version scheme's own word for exactly this state and rule 7 uses it rather than
inventing a softer one.

**Adopt a calendar version (`2026.9.0`).** Rejected. It answers "when" and the question an adopter
is asking is "will my build still compile", which is the question semver's major answers and a date
cannot. It would also throw away rule 2's mechanical derivation, since no diff of `.api` files
produces a date.

**Keep `-SNAPSHOT` permanently and release from tags alone.** Rejected as the status quo dressed as
a policy. It makes every artefact in `mavenLocal` ambiguous — `demo/build.gradle.kts` already notes
that a SNAPSHOT names every build of a release alike — and it gives an adopter no coordinate they
can pin.

## References

- [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html) — the scheme adopted, including
  the `0.x` clause (item 4) and the pre-release suffix rules (item 9) that rules 7 and 8 rely on.
  This record does not restate it; it says what its terms mean *here*.
- [ADR-0001](0001-compose-dont-fork.md) rule 2 — the `@UnstableApi` boundary and its one named
  exception, which rule 4 is a consequence of.
- [ADR-0008](0008-measure-behind-an-engine-agnostic-sink-boundary.md) rule 5 — the meaning-versus-
  shape rule that versions `TelemetryEvent.SCHEMA_VERSION` and, by its #285 addendum,
  `SessionTrace.FORMAT_VERSION`; rule 6 states independence from it rather than restating it.
- [`docs/api-surface.md`](../api-surface.md) — the tracked surface, the two checks over it, and the
  rule that the regeneration lands in the same commit as the change.
- [`docs/modules.md`](../modules.md) — the inward dependency rule and the supported Media3 range,
  which rules 1 and 4 rest on.
- [`docs/telemetry-schema.md`](../telemetry-schema.md) — the release notes a moved schema version is
  argued in.
- [Gradle: declaring versions and ranges](https://docs.gradle.org/current/userguide/dependency_versions.html)
  — the resolution behaviour rules 4 and 5 appeal to when they say a dependency bump reaches a
  consumer.
