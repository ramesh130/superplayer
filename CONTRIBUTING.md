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

Two ADRs constrain essentially every change, and both are worth reading before your first
contribution. They are the source of truth for the rules they carry; this file does not restate
them.

- **[ADR-0001](docs/adr/0001-compose-dont-fork.md) — Compose on Media3, do not fork it.**
- **[ADR-0002](docs/adr/0002-no-local-http-proxy.md) — No local HTTP proxy.**

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

`.github/workflows/ci.yml` runs on every push and every pull request: it builds every module,
runs the unit tests, runs Android Lint and the repo-wide Media3 version verification, publishes to
the local Maven repository, and builds the demo against those published artifacts. Its result
appears as a status check on the pull request.

Run the same thing before pushing:

```
./gradlew assemble check
./gradlew publishToMavenLocal
(cd demo && ./gradlew assembleDebug)
```

`check` is the complete definition of the library's checks — lint, the repo verification, the
module tests, and the `build-logic` test suite (an included build, so a plain `./gradlew test`
misses it). A new check that can be a Gradle task belongs in `check`, not only in the workflow, so
local and CI cannot disagree about what passing means.

The last two commands are separate because the demo is a separate Gradle build, deliberately: it
resolves SuperPlayer from published coordinates rather than as a source dependency, so no root
task can reach it.

## Pull requests

- CI must pass: build, tests, lint, and the public API compatibility check. Each of these applies
  from the change that introduces it; the scaffold lands them early precisely so they are never
  optional afterwards.
- A change to the public API surface must include the corresponding update to the tracked API
  signature files, as an explicit, reviewable edit.
- Tests assert externally observable behavior through the public API. Do not assert on private
  state or on which internal method was called. `docs/testing.md` describes the seam this repository
  tests through, and it applies to every change, not only to playback code.
- Keep the change and its documentation in the same pull request: new dependency and
  `THIRD_PARTY.md`, new decision and its ADR, new public API and its KDoc.
