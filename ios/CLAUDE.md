# superPlayer for iOS and tvOS

The iOS and tvOS half of SuperPlayer: one Swift package, Swift 6 with strict concurrency, iOS 17 and
tvOS 17 as the floor. `docs/ios-plan.md` is the roadmap and §2's table is the decisions taken up
front; the ADRs bind both platforms, and the root `CLAUDE.md` is still the whole of the repository's
orientation — this file adds only what is different under `ios/`.

## Orientation

| Path | What it is |
| --- | --- |
| `Package.swift` (repository root) | the manifest — at the root rather than here because SwiftPM resolves a `.package(url:)` dependency by reading the manifest at the root (`docs/ios-plan.md` §2); every target names its path under `ios/` |
| `ios/Sources/<Product>/` | one library product per Android module that ports, named `SuperPlayer<Thing>` |
| `ios/Tests/<Product>Tests/` | its XCTest target, run on an iOS simulator |
| `ios/api/<Product>.baseline` | the tracked public API, the `<module>/api/<module>.api` analog (`docs/api-surface.md`, *The iOS products*) |
| `ios/scripts/api-surface.swift` | reduces the digester's JSON to that baseline |
| `ios/check` | the whole definition of passing |
| `ios/.swift-format` | the format's configuration: four-space indent and 100 columns, as the Kotlin half |

**Every product is empty until its phase.** i0 (#397) is the scaffold alone: twelve products, a
placeholder test each, baselines recording no API. `SuperPlayerMoQ` is not declared yet — its binary
target is i9's — and there is no `SuperPlayerCache`, since its content is the offline store's.

**Dependencies point inward** under `docs/modules.md`'s phase rule, and the phase a product takes is
the Android module's it ports: `docs/modules.md`'s *iOS products* table is the source, and
`./gradlew verifyModulePhaseRule` reads `Package.swift` as text against it, on Linux. So a target
keeps the documented `.target(name:dependencies:path:)` shape and is never assembled from a
variable, and a new product arrives with its row in that table in the same change. Kotlin friend
modules have no analog: a seam a later product reaches is `package`-scoped, not `internal`.

## Commands

```bash
ios/check                     # swift-format lint, simulator tests, tvOS build, API diff
ios/check --update-api        # the same, rewriting ios/api/*.baseline; commit the diff
SP_SIMULATOR=mine ios/check   # test on a simulator of that name instead (created if absent)
xcrun swift-format format --in-place --recursive --configuration ios/.swift-format ios Package.swift
```

`ios/check` is a script rather than a Gradle task (ADR-0021), and the macOS CI job runs it and
nothing else, path-filtered to `ios/**`, `Package.swift` and the shared documents. The Linux job's
`./gradlew check` covers the two iOS readings that are text, because a check that can be a Gradle
task belongs in `check`: the phase rule above, and the licence header below.

Things worth knowing before the first run:

- **The simulator is the script's, by name** — `superplayer-check` unless `SP_SIMULATOR` says
  otherwise — created on first use on the newest iOS runtime, as the first iPhone that runtime
  supports, and shut down afterwards if the run booted it. Two contributors, or two worktrees, give
  it different names and never share a booted device. Delete one with `xcrun simctl delete <name>`.
- **DerivedData lives in `.build/DerivedData`**, not Xcode's shared folder, so two checkouts never
  build into one another; `SP_DERIVED_DATA` moves it. A clean run is `rm -rf .build`.
- **A quiet `xcodebuild` exit is not evidence.** The script reads the result bundle with
  `xcrun xcresulttool get test-results summary` and fails unless it says `Passed` with at least one
  test per product; `.build/ios-check/test.xcresult` is the bundle to open when it does not.
- **tvOS is built, not tested**: a tvOS simulator runtime is not on every host, and no product holds
  tvOS-only code until i7, which is when a tvOS test run earns its place.
- **Every `.swift` file opens with `config/license-header.txt` as `//` lines** (in `Package.swift`,
  after the tools-version line). `./gradlew verifyLicenseHeader` derives the expected text from
  that file rather than keeping a copy, and checks it on Linux, since swift-format has no header
  rule; `ios/check` does not, so run the Gradle task too before pushing a new Swift file.
- **The `[MT] IDESchemeActionDeviceRecord ... invalidDigitCount` lines** an Xcode 27 `xcodebuild`
  prints against an iOS 27 runtime are Xcode's own noise and fail nothing.
