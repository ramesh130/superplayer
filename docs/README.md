# SuperPlayer documentation

Every document in this repository, and which reader each is for. Nothing here is a summary of
another document: each is the authority on its own subject, and where two touch, the more specific
one says so.

## If you are reading a stream or a session

| Document | For |
| --- | --- |
| [`media-source-doctor.md`](media-source-doctor.md) | Someone whose stream will not play: every defect `MediaSourceDoctor` can name, what it means, what produces it, what to change, and its specification citation |
| [`session-bundle.md`](session-bundle.md) | The session bundle **format**: every line, every field, and what is redacted |
| [`reading-a-session-bundle.md`](reading-a-session-bundle.md) | A support or CDN engineer handed a bundle: which questions it settles, what the timings are measured between, and what to ask for where it is silent |
| [`telemetry-schema.md`](telemetry-schema.md) | A data pipeline: what every metric means, with its CTA-2066 citation and every departure from it stated |

## If you are changing the library

| Document | For |
| --- | --- |
| [`adr/README.md`](adr/README.md) | The fifteen architecture decision records, each decision in a line — **why the library is shaped as it is**, and the rules a change is held to |
| [`modules.md`](modules.md) | The module table, the phases, and the rule that dependencies point inward |
| [`testing.md`](testing.md) | How this library is tested: Robolectric, Media3's own fakes, no device and no network, and what each stand-in cannot show |
| [`api-surface.md`](api-surface.md) | How the public API of every published module is tracked and validated |
| [`throughput-traces.md`](throughput-traces.md) | The network-trace format the test harness replays, and how a public dataset comes in |

The repository root carries three more: `CONTEXT.md` is the vocabulary the code, the ADRs and the
issues share; `CONTRIBUTING.md` is the clean-room discipline and the citation rule every non-obvious
algorithm is held to; and `PRD.md` is the problem inventory and the phase table the issues are cut
from.

## Measurement outside `check`

`benchmark/README.md` is the fixed comparison matrix and its report; `devicelab/README.md` is a run
on a real device, and `devicelab/leak/README.md` the leak hunt. Neither is part of
`./gradlew check`, and each says why.

## For agents

[`agents/domain.md`](agents/domain.md) and [`agents/issue-tracker.md`](agents/issue-tracker.md).
