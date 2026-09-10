# The tracked public API surface

Every published module's consumer-facing API is checked into this repository, as
`<module>/api/<module>.api`, and validated on every build. Widening the surface is therefore an
explicit, reviewed edit rather than something noticed after a release.

Eleven of the twelve modules are still placeholders, so their files are empty. That is deliberate:
an empty tracked file is a claim that the module publishes nothing, and it will fail the moment that
stops being true. A missing file would be an omission instead.

## The commands

```bash
./gradlew check                                # includes checkApiSurface for every module
./gradlew updateApiSurface                     # regenerate every module's tracked surface
./gradlew :superplayer-core:updateApiSurface   # regenerate one
```

`updateApiSurface` is the deliberate step. Nothing regenerates the tracked file implicitly, because
the diff *is* the review: a change to the public API and the corresponding change to
`api/<module>.api` belong in the same commit, and `CONTRIBUTING.md` requires it.

When `checkApiSurface` fails it names the added and removed declarations, the file to regenerate,
and the command that does it. Reading the failure should be enough; this document is for the reader
who wants to know why the machinery is shaped the way it is.

## What the checks are

Two tasks run per module, both wired into `check`, so local and CI cannot disagree.

**`checkApiSurface`** compares the surface the module currently builds against the tracked file. It
reads the *release* variant's own compiled classes, which is what a consumer resolves — never the
debug variant, and never the Kotlin compile output alone.

**`verifyNoUnstableMedia3InPublicApi`** enforces [ADR-0001](adr/0001-compose-dont-fork.md) rule 2:
no `@UnstableApi` Media3 type in public API. The tracked file alone would only make such a leak
*visible*, as a diff somebody has to notice. This makes it a failure.

Whether a Media3 type is annotated is read off the pinned Media3 on the module's own compile
classpath, not from a list maintained by hand — ADR-0001 is explicit that per-class status must come
from the pinned version, because annotations move between releases.

## Explicit API mode, one step earlier

The checks above are detection: they report that the surface changed, once it has. Kotlin's
[explicit API mode](https://kotlinlang.org/docs/whatsnew14.html#explicit-api-mode-for-library-authors),
switched on for every module in `superplayer.android.library.gradle.kts`, works before that.

The two halves divide like this. **Explicit API mode makes publicness deliberate**: in Kotlin a
declaration is public by default, so widening the API is what happens when an author writes nothing
at all, and `internal` has to be typed while `public` does not. Under explicit API mode a main-source
declaration compiles only if someone stated its visibility, which is what lets the tracked diff mean
something sharper — a widening is now always something a person chose, so a surprising one is a real
question rather than possibly an oversight. **The tracked surface makes a change to it visible**:
explicit API mode has no opinion about *which* declarations should be public, only that the answer
was typed, and it cannot tell you that today's deliberate `public` differs from yesterday's.

The mode's second requirement — an explicit return type on every public declaration — serves
ADR-0001 rule 2 rather than review. An inferred public signature is one the compiler chose from the
body, so a refactor can change what is published with nothing in the source diff to show it. A
signature that can only change where someone wrote a type is a signature `api/<module>.api` can be
trusted to track.

It is a library-authoring switch and applies where library code is: main source sets of the modules
using the convention plugin. Test source sets are exempt by Kotlin's own rule, and `demo/` — a
consumer, in a separate build — does not apply the plugin and is unaffected.

## Two things the unstable check permits, and why

**The engine escape hatch.** `ExoPlayer` is `@UnstableApi` and is public API, deliberately. It is the
one exception ADR-0001 rule 2 names, and it is a set of exactly one in
`ADR_0001_UNSTABLE_EXCEPTIONS`. A second hand-written entry is a decision that belongs in a
superseding ADR, and a `build-logic` test fails if one appears.

**What Media3's own `Player` contract requires.** `Player` is stable, but
`Player.getSurfaceSize()` returns `androidx.media3.common.util.Size`, which is `@UnstableApi`. Every
implementation of `Player` declares that member, with the type Media3 chose, including Media3's own.
Rule 2 is written about instability SuperPlayer *chose* to take on; refusing this one would mean
refusing to implement `Player`, which is the entire premise of the facade.

So the check permits the *members* `Player` declares — matched by name and descriptor against the
pinned Media3, not listed by hand. Permission belongs to the member, not to the type: a
SuperPlayer-invented `getLastKnownSurfaceSize(): Size` is still a leak, because that one is a choice.

The carve-out is narrow in the other direction too. It does not extend to `media3-exoplayer`, where
SuperPlayer's subject matter lives and where rule 2 does its real work — a public method returning
`LoadControl` or `TrackSelector` fails, which is the case the rule was written for.

Both halves fail closed. If `Player` cannot be read off the compile classpath the check refuses to
report success at all, because `@UnstableApi` is detected by *finding* the annotation: a classpath
that resolves to nothing would make every type look stable and turn the check into a green no-op.

## Why the tooling is wired by hand

The signatures come from `binary-compatibility-validator`, used **as a library rather than as its
Gradle plugin**. Both it and the Kotlin Gradle Plugin's own ABI validation attach themselves to the
`kotlin-android` plugin id, and AGP 9's built-in Kotlin support never applies that id — it applies
`KotlinBaseApiPlugin` instead. Applied the ordinary way, each registers no tasks at all and reports
nothing, which is the worst possible failure mode for a check: silence.

So `build-logic` drives the signature loader directly and takes the class files from AGP's release
variant through `ScopedArtifacts`. If a future AGP or KGP release wires this up properly, the
convention plugin is the one place to revisit.

The generated format is unchanged from `binary-compatibility-validator`'s own, so the files are the
ones any Kotlin developer already knows how to read, and switching back to the plugin later would
not churn them.
