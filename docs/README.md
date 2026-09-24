# SuperPlayer documentation

Every document under `docs/`, and which reader each is for; the repository root's own four are
listed below. Nothing here is a summary of another document: each is the authority on its own subject, and where two touch, the more specific
one says so.

## If you are reading a stream or a session

| Document | For |
| --- | --- |
| [`media-source-doctor.md`](media-source-doctor.md) | Someone whose stream will not play: every defect `MediaSourceDoctor` can name, what it means, what produces it, what to change, and its specification citation |
| [`session-bundle.md`](session-bundle.md) | The session bundle **format**: every line, every field, and what is redacted |
| [`reading-a-session-bundle.md`](reading-a-session-bundle.md) | A support or CDN engineer handed a bundle: which questions it settles, what the timings are measured between, and what to ask for where it is silent |
| [`telemetry-schema.md`](telemetry-schema.md) | A data pipeline: what every metric means, with its CTA-2066 citation and every departure from it stated |

## If you are building an app on it

| Document | For |
| --- | --- |
| [`compatibility.md`](compatibility.md) | Someone deciding what they may depend on: the lockstep rule, what a major, minor and patch each mean here, the pre-1.0 position, every module's stability, the Media3 relationship and its one exception, the two format versions that are not this one, the deprecation policy, and what a release does not promise |
| [`http-transport.md`](http-transport.md) | Someone putting their app's own HTTP client under the player: the worked adapter, the five obligations with what each costs to get wrong, what the library already does above the transport, and the conformance suite to run against your implementation |

## If you are changing the library

| Document | For |
| --- | --- |
| [`adr/README.md`](adr/README.md) | The architecture decision records, each decision in a line — **why the library is shaped as it is**, and the rules a change is held to |
| [`modules.md`](modules.md) | The module table, the phases, and the rule that dependencies point inward |
| [`realtime-path.md`](realtime-path.md) | One table: when a `moq://` URI plays, which layer is MoQ's, which is this library's and which is Media3's — with what the path deliberately does not reach |
| [`testing.md`](testing.md) | How this library is tested: Robolectric, Media3's own fakes, no device and no network, and what each stand-in cannot show |
| [`api-surface.md`](api-surface.md) | How the public API of every published module is tracked and validated |
| [`releasing.md`](releasing.md) | Cutting a release with `./gradlew release`: the order, the six refusals, where the artefacts go and where they do not, and how to undo one |
| [`throughput-traces.md`](throughput-traces.md) | The network-trace format the test harness replays, and how a public dataset comes in |

The repository root carries seven more. [`README.md`](../README.md) is the front door and the only
one written for somebody who has not decided to be here yet: what the library is, what it does not
yet promise, and which of these documents to open next. The rest are for a reader already inside.
`CONTEXT.md` is the vocabulary the code, the ADRs and the
issues share; `CONTRIBUTING.md` is the clean-room discipline and the citation rule every non-obvious
algorithm is held to; `PRD.md` is the problem inventory and the phase table the issues are cut from;
and `CLAUDE.md` is the orientation for someone — or something — about to change the library, with
`THIRD_PARTY.md` beside them as the dependency register. `CHANGELOG.md` is the last: what changed
between two versions, in the shape
[`adr/0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md`](adr/0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
decides, held to the version catalog by `./gradlew verifyVersion`.

## Measurement outside `check`

`benchmark/README.md` is the fixed comparison matrix and its report; `devicelab/README.md` is a run
on a real device, `devicelab/leak/README.md` the leak hunt, `devicelab/startup/README.md` the cold-start
measurement, `devicelab/jank/README.md` the scrolled and tapped feed, and `devicelab/plants/README.md` the known regressions a run can be built with. Neither
benchmark nor devicelab is part of `./gradlew check`, and each says why.

## Built outside the build

`third-party/moq/README.md` is the one dependency this repository builds rather than resolves: MoQ's
Kotlin bindings, compiled on one machine with the crate's codec features off, and committed as a
Maven repository that `superplayer-moq` alone reads. It carries the rebuild recipe, the stated
limits — one ABI, reproducible by nobody else — and `CRATES.md`, the licence of every crate in that
build. Nothing in `./gradlew check` rebuilds it.

Read `CRATES.md` and `THIRD_PARTY.md` before drawing a conclusion about that build's licences. The
codec subtraction removed every `symphonia` and `openh264` symbol, and it did **not** empty the
graph of copyleft: UniFFI is MPL-2.0 and `uniffi_core` is linked. That is admitted by
`CONTRIBUTING.md`'s #364 amendment and only because the module is **not published** — which is why
it is not, and what #369 has to answer.

## For agents

[`agents/domain.md`](agents/domain.md) and [`agents/issue-tracker.md`](agents/issue-tracker.md).
