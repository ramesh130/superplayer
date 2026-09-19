# Cutting a release

For whoever is about to cut one, including the first time.

A release of SuperPlayer is one version for all thirteen published modules, moved together
([ADR-0017](adr/0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
rule 1). Cutting one is six things that have to happen in one order, and there is exactly one
supported way to do it:

```bash
./gradlew release --release-version=<x.y.z>
```

The option is `--release-version` rather than `--version` because Gradle parses `--version` as its
own built-in flag — the one that prints Gradle's version — before any task sees the command line.

There is no second way, and that is deliberate. Five of the six steps done by hand still leaves a
release: one whose record of the released API surface was never taken, which silently disarms
`verifyVersionBump` for the whole of the next cycle, so the next version is judged against nothing
and passes whatever it says.

## Before you start

- Be on the branch the release is cut from, with **nothing uncommitted** — not even an untracked
  scratch file. The command refuses a dirty tree.
- Write what changed under `CHANGELOG.md`'s `## [Unreleased]` heading. The command refuses an empty
  one, because that section is the only record of a behaviour change that moved no declaration
  (ADR-0017 rule 3), and the version it becomes is a number that cannot express one. **Read it
  before the first cut**: that section moves *whole* into the dated heading, and today it opens
  "**Nothing has been released.** The catalog reads `0.1.0-SNAPSHOT`…", which is true while it sits
  under `Unreleased` and is nonsense the moment it is dated. The command moves what it is given and
  writes no release notes of its own.
- Know the version. It is derived rather than chosen: a declaration removed from or incompatibly
  changed in any `<module>/api/<module>.api` since the last release is a major — a **minor** while
  the library is `0.x` (rule 7) — one added is a minor, and an unmoved surface is a patch (rule 2).
  If you are wrong the command tells you the smallest version that describes the change, in
  `verifyVersionBump`'s own words.
- If a public symbol was **removed** since the last release, check it was deprecated first: ADR-0017
  rule 9 has one survive at least one minor release annotated `@Deprecated` with its replacement
  named. No tool can see this — `checkApiSurface` compares declarations and a deprecation cycle is a
  fact about two releases — so it is a reviewer's, and the changelog row is where it is recorded.
- Optionally run `./gradlew check` first. The release command depends on it, so it runs anyway; a
  check that has already passed is up to date and the release run's is quick. Doing it first is how
  you find out about a failing test before you have thought about a version number.

## What the command does, in order

1. **Runs the full `check`** — the root project's and every module's, which is what a plain
   `./gradlew check` means. This is a task dependency, not a claim: Gradle runs them before the
   release action starts, so a red tree stops the release where it stands. It is neither trusting
   your word nor starting a second build.
2. **Refuses, or plans.** The tree is clean, the version parses, it is a legal successor of the last
   release, it is no smaller than the surface diff requires, the `Unreleased` section is not empty,
   the tag is free. Every refusal happens **before anything is written**.
3. **Writes.** `gradle/libs.versions.toml` drops its `-SNAPSHOT`; `CHANGELOG.md`'s `Unreleased`
   section becomes `## [<x.y.z>] - <today>` with a fresh empty `Unreleased` above it; `api/released/`
   is re-recorded from the surfaces tracked today, under that version.
4. **Commits** those three as `Release <x.y.z>`.
5. **Publishes** the thirteen modules at that version — see
   [where the artefacts go](#where-the-artefacts-go-and-where-they-do-not).
6. **Tags** the release commit `v<x.y.z>`, annotated.
7. **Opens the next snapshot**: the catalog moves to the next *patch* `-SNAPSHOT` and that is a
   second commit, `Open <x.y.(z+1)>-SNAPSHOT`. ADR-0017 rule 8 requires it — a tree left on the
   version just published would say every later build of it is that release.

The next snapshot is a patch because what the *next* release will be is derived from a surface diff
that has not happened yet. Opening a minor would be the command guessing, and a guess in the catalog
reads exactly like a decision.

**Nothing is pushed.** Two commits and one tag exist locally and go no further; the command cannot
reach a remote. Pushing them is yours, when you are satisfied:

```bash
git push && git push origin v<x.y.z>
```

## Why the command writes to git

The alternative — write the files, print the `git` commands, let a human run them — is the one that
cannot surprise anyone, and it was rejected for a reason particular to rule 8. The released version
has to exist **as a commit** for a tag to name it, and the tree has to **end** on the next snapshot.
One final working tree cannot be both. A command that only printed would hand back a tree in the
wrong state and ask its reader to reconstruct the intermediate one by hand, which is the half-done
cut the command exists to prevent.

What it writes is bounded and local: two commits, one tag, no remote, and a failure part-way through
prints exactly what to run to undo it.

## What it refuses, and what to do

| Refusal | What it means | What to do |
| --- | --- | --- |
| The working tree is not clean | A release is cut from a tree that is exactly what gets tagged and published | Commit or discard the paths it lists |
| `--release-version=…` is not `MAJOR.MINOR.PATCH` | The grammar ADR-0017 admits, optionally with `-SNAPSHOT` | Pass the version being released, e.g. `--release-version=1.2.0` |
| `--release-version=…` names a snapshot | A snapshot is not a release (rule 8) | Pass the release itself; the command opens the next snapshot on its own |
| `--release-version=…` does not succeed the last release | A successor is one of the three versions semver reaches in one step; a skipped version is a release an adopter reads as one they missed | Pick one of the three it names |
| The version is smaller than the surface diff requires | `verifyVersionBump`'s judgement, asked about your version before anything was written (rule 2) | Cut the version it names, or work out why a declaration moved that should not have |
| The `Unreleased` section is empty | The release would ship with nothing said about it | Write what changed under that heading |
| The tag already exists | That version has been cut before | Pick the next version — or, if the tag was cut wrongly and nothing has been shared, `git tag -d v<x.y.z>` |
| A check failed | Reported by `check` itself, before the release action runs at all | Fix it; nothing of the release has happened |

A refusal at any of these leaves the tree untouched.

## Where the artefacts go, and where they do not

`publishToMavenLocal` is the whole of publishing in this repository. A cut release lands in the
**local Maven repository on the machine that cut it** — `~/.m2/repository/com/superplayer/` — and
**nowhere else**.

**It is not resolvable by anyone else.** There is no publish `repositories { }` block in this
repository, no credentials and no release workflow, and publishing to a remote repository is
deliberately out of scope. `demo/` and `benchmark/` resolve the library from `mavenLocal()` after a
prior publish, and they are the only consumers a cut release has today. A version that has been cut
is a version this repository can *state* — a tag, a changelog row, a recorded API surface — not one
an adopter can add to their build.

## Undoing a release cut wrongly

Nothing has been pushed, so this is local surgery and it is complete:

```bash
git tag -d v<x.y.z>
git reset --hard <the commit before the release commit>   # git log --oneline names it
```

The artefacts already in the local Maven repository stay there until they are overwritten by the
next cut of the same version, or deleted:
`rm -rf ~/.m2/repository/com/superplayer`. They are one machine's, which is the one thing that makes
this recoverable at all — and is the same fact as the section above.

If a release has already been **pushed**, it has not: the tag is the release, and deleting a pushed
tag is a promise broken for anyone who resolved it. Cut the next version instead, with a changelog
row saying what the previous one got wrong.

## What the command does not do

- It does not push, and cannot.
- It does not decide the version. ADR-0017 rule 2 derives it from the tracked API surface, and rule
  3 is the half no tool can check: a behaviour change that moves no declaration is still a major.
  The command holds the floor, never the ceiling.
- It does not write release notes. What is under `Unreleased` when you run it is what the release
  says, moved whole.
- It does not take the library to `1.0.0` on its own authority. ADR-0017 rule 7 lists the four
  things that do.

## What reads what this writes

- `./gradlew verifyVersion` holds `gradle/libs.versions.toml` and `CHANGELOG.md` to each other, in
  both states — the released one and the snapshot the cut leaves behind.
- `./gradlew verifyVersionBump` compares `api/released/` against the tracked surfaces and refuses a
  version too small for the difference. Re-recording that directory is step 3, which is what arms it
  for the next cycle. [`api/released/README.md`](../api/released/README.md) describes it.
- [`api-surface.md`](api-surface.md) is the tracked surface itself and the two checks over it.
