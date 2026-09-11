plugins {
    id("superplayer.android.library")
}

// Synthetic HLS and DASH streams: the known-good media every other module's tests play
//
// Phase 1. Dependency direction: see docs/modules.md. This module may depend on
// modules from its own or an earlier phase only — never on a later one.
//
// It depends on nothing at all, and that is the point rather than an accident. The generators are
// reached from `superplayer-core`'s *tests* and from `superplayer-testkit`'s *main* source set, and
// core cannot depend on testkit (later phase, and a cycle besides), so the one home both can see has
// to sit below both. Naming no dependency — not even Media3's test utilities — is what keeps it
// there: a module with no edges cannot break the phase rule and cannot be a cycle.
//
// What it emits is bytes and URIs, so nothing here needs `FakeDataSet`. Wiring a stream into one is
// a line at each call site, and it is deliberately the caller's: `FakeDataSet` is `@UnstableApi`, so
// an `addTo(FakeDataSet)` in this module's *public* API would fail
// `verifyNoUnstableMedia3InPublicApi` (ADR-0001 rule 2) — the same rule that keeps Media3 out of
// `superplayer-testkit`'s signatures.
dependencies {
}
