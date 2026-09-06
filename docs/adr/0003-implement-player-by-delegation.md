# ADR-0003: Implement `Player` by delegation, never extend a Media3 base class

- **Status:** Accepted
- **Date:** 2026-09-06
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None

## Context

[ADR-0001](0001-compose-dont-fork.md) rule 2 says no `@UnstableApi` Media3 type may appear in
SuperPlayer's public API — "not as a parameter, a return type, a supertype, a type argument, or a
public field" — with exactly one named exception, the `exoPlayer` escape hatch. The ADR also says
enforcement is mechanical rather than remembered: the tracked public API signature files are what
holds the rule.

When that enforcement was actually built, it immediately reported a violation that had been present
since the facade was written. `SuperPlayer` extended Media3's `ForwardingPlayer`, and
`ForwardingPlayer` is itself `@UnstableApi`.

The cost was not theoretical. `SuperPlayer` declared none of `Player`'s methods itself, so every
call a consumer made resolved to a declaration inside an annotated class, and Android Lint demanded
an opt-in at the call site. Re-enabling `UnsafeOptInUsageError` in the demo application — five
calls, on a `SuperPlayer`-typed reference — produced five errors:

```
MainActivity.kt:41  superPlayer.setMediaItem(MediaItem.fromUri(HLS_TEST_STREAM))
MainActivity.kt:42  superPlayer.playWhenReady = true            (getter and setter)
MainActivity.kt:43  superPlayer.prepare()
MainActivity.kt:49  player?.release()
```

That is precisely the burden rule 2 exists to keep off consumers, and the demo's own lint
configuration had been suppressing it, with a comment asserting that rule 2 was holding.

Choosing a different Media3 base class does not help. In Media3 1.11.0 every concrete `Player` base
in `media3-common` is annotated: `ForwardingPlayer`, `ForwardingSimpleBasePlayer`, `SimpleBasePlayer`
and `BasePlayer`. Thirty-four of the sixty top-level classes in `media3-common` are. The `Player`
interface itself is stable, and is the only stable supertype Media3 offers.

## Decision

**SuperPlayer's facade implements Media3's `Player` interface by Kotlin interface delegation. It
does not extend `ForwardingPlayer`, `SimpleBasePlayer`, `BasePlayer`, or any other Media3 class.**

Three rules follow, and they are binding:

1. **No Media3 type is a public supertype.** The only Media3 supertype a SuperPlayer public type may
   declare is a stable interface — in practice `Player`. A Media3 *class* is never extended by a
   public SuperPlayer type, whatever its annotation status, because a class brings its whole member
   set into our API surface along with whatever markers those members carry.
2. **The delegate is Media3's, and it is private.** Delegation targets Media3's own
   `ForwardingPlayer` rather than the `ExoPlayer` directly, so the engine keeps whatever forwarding
   behaviour Media3 defines and we do not hand-write a pass-through. The delegate is not reachable
   from public API; the `exoPlayer` escape hatch is the only way past the facade.
3. **Java `default` members are never left to delegation, and a contract test proves it.** Kotlin's
   interface delegation does not override a Java `default` method: the generated class simply does
   not declare it, so the member silently runs Media3's own default implementation instead of
   reaching the engine. Nothing warns about this, which is what makes it dangerous.

   It bites in two places. `Player` has exactly one `default` member in Media3 1.11.0,
   `getAudioSessionId()`, which the facade overrides explicitly. `Player.Listener` is *entirely*
   `default` — 37 callbacks, no abstract members — so a listener wrapper written with delegation
   declares nothing at all and drops every callback it does not name. Every wrapper of a Media3
   interface is therefore covered by Media3's own forwarding-contract assertion, which drives every
   member through the wrapper and verifies the target received it. That test, not a reviewer's
   memory, is what catches the next `default` member Media3 adds.

## Consequences

**Easier.** Rule 2 holds where it is measured — from the consumer's side. The demo application now
builds with `UnsafeOptInUsageError` enabled and needs no opt-in, and that is a permanent check
rather than a claim in a comment. The tracked API surface also became honest: delegation generates a
real override for each of `Player`'s 122 members, so `api/superplayer-core.api` lists what a consumer
can actually call instead of hiding it behind an inherited supertype.

**Harder.** The facade is stricter than the interface it implements. Kotlin's generated overrides
assert Media3's non-null contract, and Media3's own forwarding-contract harness passes `null` for
arguments it cannot construct — `PlaybackParameters` rejects the zero speed it is offered — so one
method is excluded from the bulk assertion and covered by a test of its own. Listener registration
is no longer inherited either. `ForwardingPlayer` rewrites the player reported by
`Player.Listener.onEvents` to itself, and while the facade *was* the `ForwardingPlayer` that was the
right answer for free; now the delegate is a hidden object the consumer has no name for, so the
facade wraps listeners and makes that correction itself.

**Ongoing cost.** Rule 3 above is a standing obligation on Media3 upgrades. It is discharged by a
test rather than by vigilance, which is the only reason it is acceptable.

**One piece of reflection.** The listener wrapper forwards through a `java.lang.reflect.Proxy`
rather than 37 hand-written methods, because those 37 methods would be a re-creation of Media3's own
`ForwardingPlayer.ForwardingListener` — ADR-0001 rule 1's "class copied out to change three lines" —
and would silently drop any callback Media3 adds. The reflection sits on the player-event callback
path, which is UI-rate, not per-sample or per-chunk.

**Constraint on future work.** Any later facade — a `Player` wrapper for a pooled or preloaded
engine, say — takes the same shape. A proposal to extend a Media3 class has to argue against this
record.

## Alternatives considered

**Keep extending `ForwardingPlayer` and record a second rule 2 exception.** Rejected. An exception
is defensible when its cost is confined to whoever reaches for it, which is what makes the
`exoPlayer` escape hatch acceptable. This one is unavoidable and universal: it charges every
consumer an opt-in on every call, for a type they never asked for. There is no honest version of
that argument.

**Extend `ForwardingSimpleBasePlayer` instead.** Rejected, and it does not address the problem:
it is `@UnstableApi` too. It is also a heavier base, meant for players that intercept and rewrite
state through a `State` object, which this facade does not do.

**Hand-write the forwarding methods, in Kotlin or in Java.** Rejected. It is a re-implementation of
`ForwardingPlayer` — the thing ADR-0001 exists to avoid — and it would drift from `Player` on every
Media3 release. `CONTRIBUTING.md` rule 2 also bars mirroring another SDK's implementation shape, and
copying Media3's would be the clearest possible case.

**Turn off Kotlin's null assertions (`-Xno-param-assertions`, `-Xno-call-assertions`) so the
contract harness passes unmodified.** Rejected. It would weaken every future public API in the
library to accommodate a test double that violates the interface's own contract. The narrow
exclusion, with its own test, costs one method's worth of coverage instead.

## References

- Media3 `@UnstableApi` policy:
  https://developer.android.com/reference/androidx/media3/common/util/UnstableApi
- Kotlin delegation: https://kotlinlang.org/docs/delegation.html
- androidx `RequiresOptIn` and the `UnsafeOptInUsageError` lint check:
  https://developer.android.com/jetpack/androidx/versions/stable-channel#experimental-apis
- [ADR-0001](0001-compose-dont-fork.md), rule 2 and its named exception.
