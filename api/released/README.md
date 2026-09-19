# The last release's API surface

**Nothing in this directory is hand-edited.** `./gradlew recordReleasedApiSurface` writes it, and
the only thing that runs that command is the release command (issue #329), as one step of cutting a
release.
Editing a file here by hand changes what the next version is allowed to be, silently.

## What is here

Once a release has been cut, this directory holds:

- `<module>.api` for every published module — that module's public API surface **as it was
  released**, byte for byte the `<module>/api/<module>.api` that was tracked at the time; and
- `version.txt`, the single version those surfaces were released at
  ([ADR-0017](../../docs/adr/0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
  rule 1 releases all thirteen modules at one number).

**Today it holds neither, and that is correct**: the library has never been released. ADR-0017's
*What this costs the first release* says the first version is a declaration rather than a
derivation, so `verifyVersionBump` reads an absent record as "there is no baseline" and passes.
The absence is the state — not thirteen empty files, which would read as thirteen modules that
published nothing and would make every declaration in the repository an addition. A record with one
half present and the other missing is a corrupted record and fails, because the command writes both
in one step.

## What reads it

`./gradlew verifyVersionBump`, in `check`. It compares these surfaces against the
`<module>/api/<module>.api` files tracked today and fails when `gradle/libs.versions.toml`'s
`superplayer` version is too small for the difference — a declaration removed or incompatibly
changed being a major, one added a minor, an unmoved surface a patch (ADR-0017 rule 2; rule 7 is
why a removal under `0.x` needs only a minor).

It is **not** `checkApiSurface`, which asks whether the tracked file still matches the code. This
asks whether the version matches the difference between two tracked files, and reads no compiled
class at all. See [`docs/api-surface.md`](../../docs/api-surface.md).
