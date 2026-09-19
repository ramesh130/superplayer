# Contributing to SuperPlayer

SuperPlayer is a production playback layer built on top of AndroidX Media3. It is a policy,
resilience, observability, and lifecycle library — not a fork of Media3 and not a new player.

Everything in the **Clean-room rules** section below is binding. A contribution that violates one of
those rules is not accepted, regardless of its technical merit.

## License

SuperPlayer is licensed under the **Apache License, Version 2.0**, matching Media3. By contributing,
you agree that your contribution is licensed under those terms and that you have the right to submit
it.

### Dependency license policy

Third-party dependencies are recorded, with their license, in `THIRD_PARTY.md` at the repo root.
That file is created by the first change that adds a dependency.

**Accepted:** permissive licenses without a field-of-use restriction — Apache-2.0, MIT, BSD-2/3-
Clause, ISC, and equivalents.

**Not accepted in any published artifact:**

- Copyleft licenses — GPL, LGPL, AGPL, SSPL, and derivatives.
- Any license carrying a field-of-use restriction, a non-commercial clause, or an "ethical" or
  behavioral use restriction.

**Not accepted anywhere, including test-only and build-time dependencies:**

- Strong copyleft — GPL, AGPL, SSPL — whose reach is not limited to the licensed files.
- Any license carrying a field-of-use, non-commercial, or behavioral use restriction.
- Any dependency whose license cannot be identified.

**Weak, file-scoped copyleft (EPL, MPL, LGPL) is accepted for test-only and build-time
dependencies**, which are never distributed in a published artifact and whose copyleft reaches only
modifications to the dependency's own files. This is what allows JUnit, which is EPL-1.0, and it is
the narrowest carve-out that does. It does not extend to anything that ships to a consumer.

**#364's amendment, and it widens that carve-out by exactly one case.** The rule's reason is *never
distributed in a published artifact*, and it named two kinds of dependency that satisfy it. A third
does: a dependency of a module the build carries and **does not publish**, declared as such in
`settings.gradle.kts`'s `unpublishedModules` list (ADR-0017 rule 1, `build-logic`'s
`ModulePublication`). Such a module has no coordinate at any version, so nothing of it reaches an
adopter's APK, which is the same fact the original two rest on rather than a new allowance.

This is written down because something relies on it: `superplayer-moq` links MoQ's UniFFI bindings,
whose `uniffi_core` is MPL-2.0 and *is* linked into the native library
(`third-party/moq/CRATES.md`). The amendment is deliberately not a general loosening — it does not
admit copyleft into a published module, and it does not survive the module being published. **#369,
which would publish `superplayer-moq`, has to answer this first**, and the row it would have to
clear is the one above rather than this one.

Adding a dependency means adding its entry to `THIRD_PARTY.md` in the same change, creating the
file if it does not exist yet.

## Clean-room rules

SuperPlayer must be defensibly independent of any commercial player SDK. These rules are how that
independence is maintained, and they are also, separately, good engineering practice.

### 1. Do not consult proprietary material

While contributing, do not consult, reference, quote, paraphrase, or work from:

- Source code of a prior or current employer, or of any commercial player SDK.
- Internal documentation, design notes, architecture diagrams, or specifications.
- Ticket text, incident write-ups, post-mortems, or code review discussions.
- Benchmark data, QoE measurements, or performance numbers from a proprietary platform.
- Anything behind a customer login or under NDA.

This applies to material you personally have legitimate access to. Access is not permission.

### 2. Do not mirror a commercial SDK's API shape

Do not reproduce another player SDK's public API design: its class decomposition, its naming
scheme, its configuration-object hierarchy, its event names, or its callback structure. An API
surface can carry copyright and trade-dress arguments, and a recognizable clone of one invites them.

### 3. API idiom follows Media3

Where an API design question arises, the answer is Media3's own convention: `Builder` construction,
`Factory` interfaces, `Listener` callbacks, `@UnstableApi` discipline for surfaces that are not yet
committed to, and `Player`-compatible types wherever a `Player`-shaped concept exists. This is
idiomatic for the ecosystem and it is independently derived from Apache-2.0 code the project already
builds on.

### 4. Cite a public source for every non-obvious algorithm

Any algorithm, constant, heuristic, or protocol behavior that a reader would not derive
immediately carries a `// spec:` or `// ref:` comment naming a **public** source: a specification,
a standard, public vendor documentation, or a named paper. Examples of acceptable sources:

- RFC 8216 (HTTP Live Streaming)
- ISO/IEC 23009-1 (DASH)
- CTA-5004 (Common Media Client Data)
- CTA-2066 (Streaming QoE events, properties and metrics)
- DASH-IF Interoperability Points
- Public Widevine integration documentation
- Named academic work — BOLA, MPC/FastMPC, Pensieve, and comparable published results

"It is what everyone does" is not a citation. If no public source can be named, the code needs a
comment explaining the derivation from first principles or from measurement taken by this project's
own harness.

This requirement is also the reason the codebase is readable: it makes the provenance of anything
clever traceable, which is what a reviewer needs in order to check it.

### 5. General skill is portable; specific implementations are not

Knowing that a class of device fails DRM provisioning in a particular way is experience, and you may
use it. A particular employer's workaround for that failure is their implementation, and you may
not. When the line is unclear, derive the solution from public sources and measurement, and cite
what you derived it from.

### 6. Benchmarks are our own

Performance and QoE numbers published in this repository are produced by this project's own
benchmark harness against public test streams. Numbers attributed to, or carried over from, a
proprietary platform are not published here.

## Architecture decisions

Decisions that constrain later work are recorded as ADRs in `docs/adr/`, numbered sequentially and
dated, using the format in `docs/adr/0000-template.md`.

Five ADRs constrain essentially every change, and all five are worth reading before your first
contribution. They are the source of truth for the rules they carry; this file does not restate
them.

- **[ADR-0001](docs/adr/0001-compose-dont-fork.md) — Compose on Media3, do not fork it.**
- **[ADR-0002](docs/adr/0002-no-local-http-proxy.md) — No local HTTP proxy.**
- **[ADR-0003](docs/adr/0003-implement-player-by-delegation.md) — Implement `Player` by delegation,
  never extend a Media3 base class.**
- **[ADR-0005](docs/adr/0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md) — Decide
  playback policy behind an engine-agnostic boundary, and ship a static one.**
- **[ADR-0006](docs/adr/0006-own-the-platform-rules-and-hand-back-the-state.md) — Turn Android's
  lifecycle rules on by default, and hand back the state that outlives a player.** Read it with
  ADR-0005: it is where the line between policy and platform correctness is drawn.

One more is recorded but not yet binding, because the work it governs has not started:

- **[ADR-0004](docs/adr/0004-select-the-http-stack-through-a-superplayer-type.md) — Select the HTTP
  stack through a SuperPlayer-owned type.** *Proposed.* Read it before adding any public API that
  touches Media3's `DataSource` layer; it carries open questions rather than settled rules.

If a change contradicts an ADR, say so explicitly in the pull request and argue the case. Do not
work around an ADR silently. If the argument wins, the outcome is a new ADR that supersedes the old
one, not an undocumented exception.

Adding a decision: copy `docs/adr/0000-template.md` to the next free number, fill it in, and link it
from the pull request that implements it.

## Domain vocabulary

Once `CONTEXT.md` exists at the repo root, the terms it defines are the project's vocabulary: use
them in code, tests, ADRs, issues, and commit messages rather than drifting to synonyms. Until then,
follow Media3's own terminology for engine concepts. `CONTEXT.md` is written lazily, as terms are
actually settled, rather than up front.

## Continuous integration

`.github/workflows/ci.yml` runs on demand, not on every push or pull request. It is started
once a phase is complete, from the Actions tab or with `gh workflow run ci.yml --ref <branch>`,
to stay inside the GitHub Actions minutes budget. Until then, the local commands below are the
only check a change gets. The workflow builds every module,
runs the unit tests, runs Android Lint, the Kotlin format and license headers, and the repo-wide
Media3 version verification, publishes to the local Maven repository, and builds the demo against
those published artifacts. A run started on a pull request's branch shows its result as a
status check on that pull request.

Run the same thing before pushing:

```
./gradlew assemble check
./gradlew publishToMavenLocal
(cd demo && ./gradlew assembleDebug lintDebug spotlessCheck)
```

`check` is the complete definition of the library's checks — lint, the repo verification, the
tracked API surface, the Kotlin format and Apache-2.0 file headers, the module tests, and the
`build-logic` test suite (an included build, so a plain `./gradlew test` misses it). A new check
that can be a Gradle task belongs in `check`, not only in the workflow, so local and CI cannot
disagree about what passing means.

Dependabot (`.github/dependabot.yml`) opens weekly pull requests for the Gradle dependencies of all
three builds and for the actions the workflow pins. Every one of those is reviewed like any other
change, and nothing is merged automatically. A Media3 bump arrives as the `media3` group. A minor or
major one also needs the supported Media3 version in `docs/modules.md` changed by hand, and `check`
fails through `verifyMedia3SupportedVersion` until it is. The comments in that file say which
catalog entries Dependabot cannot update, and why.

## Formatting and license headers

Every `.kt` file in this repository carries the Apache-2.0 boilerplate header, and every one is
formatted by ktlint. Both are applied by Spotless, and both are verified by `check`:

```
./gradlew spotlessApply
```

That one command covers all three Gradle builds. Spotless is configured by path rather than by
project, so the root build reaches `demo/` and `build-logic/` as well. The demo applies Spotless a
second time over its own sources so that `(cd demo && ./gradlew ...)` enforces the same thing with
the root build not running — hence the `spotlessCheck` in the command list above.

The rules are in `.editorconfig`, which editors read too. A handful of ktlint rules are switched off
there, each with a note saying which piece of this codebase it would have rewritten and why that
piece is deliberate; where a rule and the existing house style disagree, the house style wins.

The header text is `config/license-header.txt`. It is not free-form: `check` runs
`verifyLicenseHeader`, which compares it against the boilerplate in `LICENSE`'s own appendix, so the
header and the license cannot drift apart. Changing one means changing the other.

The demo is linted as well as built, and that is not incidental: it is the only place
`UnsafeOptInUsageError` is enabled, so it is where ADR-0001 rule 2 is proved from a consumer's side
rather than asserted. See `docs/api-surface.md`.

The last two commands are separate because the demo is a separate Gradle build, deliberately: it
resolves SuperPlayer from published coordinates rather than as a source dependency, so no root
task can reach it.

## The public API surface

Every published module's public API is checked into the repository as `<module>/api/<module>.api`,
and `check` fails when what the module builds no longer matches it. Regenerating it is a deliberate
step, never automatic:

```
./gradlew updateApiSurface                     # every module
./gradlew :superplayer-core:updateApiSurface   # one module
```

Commit the regenerated file in the same change as the API edit that caused it. The diff is the
review: it is how a widened surface gets agreed to rather than discovered after a release.

`check` also fails if an `@UnstableApi` Media3 type reaches public API, which is ADR-0001 rule 2
enforced mechanically rather than remembered. `docs/api-surface.md` explains both checks, the two
things the second one permits, and why the tooling is wired the way it is.

## Pull requests

- CI must pass: build, tests, lint, and the public API surface check. Each of these applies
  from the change that introduces it; the scaffold lands them early precisely so they are never
  optional afterwards.
- A change to the public API surface must include the corresponding update to the tracked API
  signature files, as an explicit, reviewable edit.
- Tests assert externally observable behavior through the public API. Do not assert on private
  state or on which internal method was called. `docs/testing.md` describes the seam this repository
  tests through, and it applies to every change, not only to playback code.
- Keep the change and its documentation in the same pull request: new dependency and
  `THIRD_PARTY.md`, new decision and its ADR, new public API and its KDoc.
