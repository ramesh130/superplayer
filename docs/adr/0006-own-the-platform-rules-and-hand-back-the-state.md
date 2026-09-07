# ADR-0006: Turn Android's lifecycle rules on by default, and hand back the state that outlives a player

- **Status:** Accepted
- **Date:** 2026-09-07
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None

## Context

Four behaviours separate a media app that is correct on Android from one that merely plays video:
it requests and respects audio focus, it pauses when the headphones are unplugged, it keeps the
device awake only while playback is actually running, and it does not restart the video when the
device is rotated. `PRD.md` §3.7 lists them together as "lifecycle correctness"; F7 is the
feed-and-grid case that makes them acute.

They are not one problem. Three of them are already implemented inside Media3 and are simply **off
by default**:

| Behaviour | What switches it on |
| --- | --- |
| Audio focus | `ExoPlayer.Builder.setAudioAttributes(attributes, handleAudioFocus = true)` |
| Becoming noisy | `ExoPlayer.Builder.setHandleAudioBecomingNoisy(true)` |
| Wake and Wi-Fi locks | `ExoPlayer.Builder.setWakeMode(C.WAKE_MODE_NETWORK)` |

Each defaults to the inert value, so an integration that does not know to ask gets a player that
talks over phone calls, plays out loud on the bus, and stops when the screen sleeps. Nothing warns
anybody: the defect only appears on a real device, in a situation a test rarely reproduces.

The fourth is different in kind. A configuration change destroys the Activity that owns the player,
and no setting on the engine can help, because the engine has no opinion about Activities. Something
has to carry the state across, and the choice of *what* carries it is a choice about whose
responsibility persistence is.

There is a further question underneath the first three, which is whether they are policy. The
project already has a boundary for policy — `PlaybackPolicy` (ADR-0005) — and a rule that a policy
constant appearing at a Media3 call site is a bug. Audio focus, becoming-noisy and wake locks are
constants at Media3 call sites, in a builder, which is exactly the shape ADR-0005 forbids.

## Decision

**SuperPlayer switches on Media3's own audio focus, becoming-noisy and wake-lock handling for every
player it builds, in one internal binding (`LifecycleBinding.kt`), and does not make any of it
configurable by profile. State that must outlive a player travels as a `PlaybackSnapshot` that the
consumer stores, and SuperPlayer persists nothing itself.**

Three rules follow.

1. **Lifecycle behaviour is correctness, not policy, and is therefore not decided behind
   `PlaybackPolicy`.** ADR-0005's boundary exists because buffering and track selection have
   different right answers for different content; these have one right answer for all of it. A
   `PlaybackProfile` may not vary them. A consumer who needs something else says so through
   Media3's own `Player` API — `setAudioAttributes(attributes, handleAudioFocus)` is on the facade
   like every other `Player` call — rather than through a SuperPlayer knob.
2. **SuperPlayer chooses no storage on a consumer's behalf.** `SuperPlayer.saveSnapshot()` returns a
   value, `PlaybackSnapshot.toBundle()` renders it in the form `onSaveInstanceState`,
   `rememberSaveable` and a `ViewModel` all already take, and where that `Bundle` lives is the
   app's decision. No `SharedPreferences`, no database, no file, no `Context`-scoped singleton.
3. **A snapshot restores as far as it can and never throws.** It is read on the way back into an
   app, where an exception is a crash a user sees and a missing field costs a resumed position.

## Consequences

A player built with `SuperPlayer.Builder(context).build()` is a correct Android media citizen with
no further call, which is the difference this library exists to make. The three behaviours are
verified in `SuperPlayerLifecycleTest` against platform state — the focus request the `AudioManager`
received, the `PowerManager` lock that is held — rather than against SuperPlayer's own bookkeeping,
so the tests fail if a Media3 upgrade changes what the switches do.

The wake-lock rule inherited from Media3 deserves stating plainly, because it is narrower than
"while playing" sounds: the locks are held while the player is buffering **or** ready and
`playWhenReady` is true, and released when it is paused, idle or ended. Buffering with intent to
play holds them deliberately — a rebuffer needs the CPU and the radio in order to end, and releasing
the locks there would let the device suspend inside a stall it would never leave.

This is a departure from issue #10's wording, which asked for locks held "not while paused or
buffering", and it was **put to the issue owner and agreed** rather than decided here.

The buffering half is argued rather than pinned by a test, and that is a known gap with a known
cause. Asserting it needs the player held in `STATE_BUFFERING` long enough to read a lock that is
taken asynchronously on the playback thread — but Media3's `WakeLockManager` arms a **1000 ms
`UNREACTIVE_WAKELOCK_HANDLER_RELEASE_DELAY_MS` safety net on every release**, posted to a handler
built on the injected `Clock`, which force-releases the lock if its own thread has not answered in
time. Under the auto-advancing `FakeClock` this seam runs on, a stall long enough to assert in is a
stall long enough for fake time to race a thousand milliseconds ahead of real thread scheduling, so
the net fires and drops a lock that was legitimately acquired. The lock is `setReferenceCounted(false)`,
so one stray release is final.

An attempt that blocked the loader inside the first media segment was written, passed locally
eighteen times including under heavy CPU contention, and failed on CI — first by sampling the lock
rather than awaiting it, then, once that was fixed, by timing out on the force-release above. It was
removed rather than retried: a test that cannot be made to fail locally cannot be fixed locally
either. Pinning this needs a clock that does not fast-forward past Media3's own timers, which is a
change to the seam rather than to a test, and it is not worth that on its own. The rule itself is
Media3's and Media3 tests it; what this project owns is the decision to switch it on, which
`aWakeLockIsHeldWhilePlayingAndReleasedWhenPaused` covers for the pause half.

What becomes harder: a consumer who wants focus handling off has to know to pass
`handleAudioFocus = false` themselves, and a consumer with an unusual wake-lock need has to reach
`player.exoPlayer`. Both are deliberate — the default is the one almost everyone wants, and the
escape hatch already exists — but neither is discoverable from SuperPlayer's own API.

Rule 2's cost is one call each way in a consumer's lifecycle methods. The alternative would have
been zero calls and a library that writes to storage nobody asked it to write to. It also leaves a
real gap: **process death is not covered by anything SuperPlayer does automatically.** A snapshot
that crossed process death works exactly as one that crossed a rotation, but only if the app put it
somewhere that survives — which is the app's call, and is stated rather than hidden.

`PlaybackSnapshot` becomes public API and therefore a compatibility commitment. Its bundle format is
versionless: a field added later is absent from an old bundle and restores as its default, which
rule 3 already requires. Anything the player pool (F7) or process-death handling later needs to
carry is added to this type rather than to a second one.

## Alternatives considered

**Put lifecycle behaviour behind `PlaybackPolicy`.** Rejected: it would widen `PlaybackDecision`,
which is public API, to carry values that no profile has a different answer for. ADR-0005's boundary
is for decisions that vary with the content; treating a platform rule as one of those would make the
policy interface mean two different things.

**Make the behaviours opt-in, matching Media3's defaults.** Rejected: it reproduces the defect the
library exists to remove. A default that is wrong for almost every app is not a neutral choice, and
"Media3 does it this way" is a reason to keep the *mechanism*, not the default.

**Use `C.WAKE_MODE_LOCAL` rather than `C.WAKE_MODE_NETWORK`.** Rejected: everything SuperPlayer
plays is streamed, so releasing the Wi-Fi lock while holding the CPU awake would leave the case that
actually stalls. It costs no permission a consumer does not already have — `media3-exoplayer`'s own
manifest declares `WAKE_LOCK`, and Media3 checks for it and logs rather than throwing if an app has
removed it.

**Have SuperPlayer persist resume positions itself.** Rejected under rule 2. Choosing storage for a
consumer means choosing a threading model, a migration story, a clean-up policy and a place where
personal data lives, all on behalf of an app that never asked. Persisting position data silently
would be a privacy decision made by a library.

**Solve the configuration change by keeping the player alive across it.** A player retained in a
`ViewModel` survives a rotation with no state transfer at all, and for a pure rotation it is a better
answer. It is not an alternative SuperPlayer can *implement*, though: retaining the player is the
app's architectural choice, it needs a lifecycle dependency the library does not have, and it does
nothing for process death. The snapshot is the lower layer, and it is compatible with a retained
player rather than a competitor to it.

**Make the snapshot `Parcelable`.** Rejected: it binds the class's field order to a binary format
that adding a field breaks, for a saving that is unmeasurable at this frequency. `toBundle` /
`fromBundle` is also Media3's own convention for a type that crosses a process boundary —
`MediaItem`, `Tracks` and `PlaybackException` all carry the pair — which CONTRIBUTING rule 3 makes
the tie-breaker.

## References

- Managing audio focus — https://developer.android.com/media/optimize/audio-focus
- Reacting to `ACTION_AUDIO_BECOMING_NOISY` —
  https://developer.android.com/media/optimize/audio-focus#becoming-noisy
- ExoPlayer battery consumption and wake locks —
  https://developer.android.com/media/media3/exoplayer/battery-consumption
- Saving UI state on Android —
  https://developer.android.com/topic/libraries/architecture/saving-states
