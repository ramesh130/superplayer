# ADR-0003: Implement `Player` by delegation, never extend a Media3 base class

- **Status:** Accepted
- **Date:** 2026-09-06
- **Amended:** 2026-09-16 (#181), by an addendum at rule 3 recording the one callback a listener
  wrapper may withhold, and why [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md)
  rule 10 requires it.
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Summary:** The facade implements Media3's `Player` interface by Kotlin interface delegation
  rather than extending a Media3 base class such as `ForwardingPlayer` — every concrete `Player`
  base Media3 ships is `@UnstableApi`, so extending one forces every consumer to opt in on every
  call. A contract test guards against delegation silently skipping Java `default` members.

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

   *Addendum (2026-09-16, #181, ADR-0011 rule 10).* There is now exactly one deliberate exception to
   "forwards every callback", and it is named here so that the next reader of the contract test finds
   the argument rather than a puzzle. A `SuperPlayer` built with `superplayer-resilience` performs
   rungs of ADR-0011's fallback ladder from the player-error path, and a failure such a rung
   *repairs* is withheld from the consumer's listeners in both the forms Media3 delivers one —
   `onPlayerError` and `onPlayerErrorChanged`, including the null that clears it. ADR-0011 rule 10
   reserves the delivered error for the top of that ladder, so a session that was rescued reporting a
   failure would be two contradictory accounts of one viewing; that rule's addendum carries the whole
   argument. The exception is bounded in three ways, each of which keeps this rule's own claim true:
   it is those two callbacks and no others, it is a failure the player is taking on itself and never
   one that reaches the consumer's rung, and the wrapper withholds nothing at all unless a player
   gives it something to ask — so Media3's forwarding-contract assertion still drives all 37
   callbacks through the wrapper and still catches the next `default` member, unchanged. A wrapper
   that ever wants a second exception argues it here.

   *Addendum (#225, ADR-0012 rule 11).* Here is that argument, and the exception is the same
   exception rather than a second one — what widened is which repairs it covers, not what may be
   withheld. A `SuperPlayer` whose protection was refused at the security level it asked for, and
   whose licence server's operator published a lower one, opens its session graph again at that
   level and re-prepares. That is not a rung of ADR-0011's ladder and ADR-0012 rule 11 is explicit
   that it must not become one, so the previous addendum's "performs rungs of the fallback ladder"
   no longer describes every case and is corrected here to: **a failure the player repairs itself**.
   Every bound above survives unchanged and the bounds are what the rule rests on — the same two
   callbacks and no others, a failure the player has taken on rather than one the consumer must act
   on, and nothing withheld at all on a player that gives the wrapper nothing to ask. The reason is
   also unchanged: a viewer whose stream came back, at a level the operator permitted, has had a
   session rescued and not a session that failed, and reporting both would be the two contradictory
   accounts rule 10 exists to prevent. A third kind of repair argues itself here as this one did.

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
