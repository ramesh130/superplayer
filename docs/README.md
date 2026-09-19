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
| [`testing.md`](testing.md) | How this library is tested: Robolectric, Media3's own fakes, no device and no network, and what each stand-in cannot show |
| [`api-surface.md`](api-surface.md) | How the public API of every published module is tracked and validated |
| [`releasing.md`](releasing.md) | Cutting a release with `./gradlew release`: the order, the six refusals, where the artefacts go and where they do not, and how to undo one |
| [`throughput-traces.md`](throughput-traces.md) | The network-trace format the test harness replays, and how a public dataset comes in |

The repository root carries six more: `CONTEXT.md` is the vocabulary the code, the ADRs and the
issues share; `CONTRIBUTING.md` is the clean-room discipline and the citation rule every non-obvious
algorithm is held to; `PRD.md` is the problem inventory and the phase table the issues are cut from;
and `CLAUDE.md` is the orientation for someone — or something — about to change the library, with
`THIRD_PARTY.md` beside them as the dependency register. `CHANGELOG.md` is the last: what changed
between two versions, in the shape
[`adr/0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md`](adr/0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
decides, held to the version catalog by `./gradlew verifyVersion`.

## Measurement outside `check`

`benchmark/README.md` is the fixed comparison matrix and its report; `devicelab/README.md` is a run
on a real device, and `devicelab/leak/README.md` the leak hunt. Neither is part of
`./gradlew check`, and each says why.

## For agents

[`agents/domain.md`](agents/domain.md) and [`agents/issue-tracker.md`](agents/issue-tracker.md).
