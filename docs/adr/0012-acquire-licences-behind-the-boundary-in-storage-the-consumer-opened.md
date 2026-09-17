# ADR-0012: Acquire licences behind the boundary, in storage the consumer opened, and never downgrade a client unasked

- **Status:** Accepted
- **Date:** 2026-09-16
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** [ADR-0006](0006-own-the-platform-rules-and-hand-back-the-state.md) rule 2, extended
  for an offline licence store exactly as
  [ADR-0010](0010-cache-in-consumer-chosen-storage-and-preload-on-the-one-chain.md) rule 5 extended
  it for the content cache, and recorded at that rule.
  [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md) is
  *applied* rather than changed: its standing obligation says the DRM error mapping lands in
  `ErrorClassifier`, and rules 5 to 7 below are that obligation discharged, including the one
  sentence in `FailureClass.Drm.Unsupported` that rule 1 here makes false.
  [ADR-0009](0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md) rule 2's
  deferral table names the secure-decoder limit as this phase's, and rule 11 collects it.
- **Refined by:**
  [ADR-0013](0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md)
  rule 4, which adds a second friend shape beside this ADR's rule 4, and rule 13, which runs a
  download's licence exchange over a store's chain rather than a player's and makes releasing its
  licence part of its removal (rule 9's addendum and rule 10); each recorded at the rule it refines.
  [ADR-0014](0014-match-the-display-and-watch-it-change-behind-one-output-slot.md)
  rule 3, an eighth friend of rule 4's shape, and rules 5 and 13, which place rule 12's
  display-capability change, the rest of secure surface discipline and the standing obligation's
  Phase 8 half; recorded at rules 4 and 12 and at the obligation.
- **Summary:** Decides Phase 6's shape before any of its code lands (#201). Protection is declared
  once per player, on the builder, and is fixed for the player's lifetime; the credential a licence
  request carries is the app's, through the one `HeaderProvider` the library has. Every Media3 type
  DRM needs is `@UnstableApi`, so the Media3-facing half — an `ExoMediaDrm`, a `DrmSessionManager`
  and the provider handed to the media source factory — is internal to `superplayer-drm`, reaching
  the engine through one new slot in core's seam as core's **sixth** Kotlin friend. A failure
  acquires its meaning where every other failure does, `superplayer-resilience`'s `ErrorClassifier`,
  which the module feeds by raising a public core exception carrying the evidence rather than by
  keeping a taxonomy of its own. An offline licence is state that outlives the player that acquired
  it, so it lives in a directory the consumer named and SuperPlayer opens no storage of its own. A
  lower security level is requested only where the licence server has said it may be, never on the
  client's own initiative, and that decision is a mechanism beside the fallback ladder rather than a
  seventh rung of it. A player built without `setDrm` allocates nothing for DRM, and a test counts
  it.

## Context

Phase 6 (`PRD.md` §3.2 and Part 4, #201) ships `superplayer-drm`: provisioning handling, a
security-level ladder, key rotation and renewal, offline licences, secure surface discipline, and an
error mapping in which no `ERROR_CODE_DRM_UNSPECIFIED` reaches the UI. The squeeze that produced
ADR-0009 rule 7, ADR-0010 rules 3, 6 and 8 and ADR-0011 rule 13 is here again and no worse. What is
new is two properties no earlier phase's subject had, and each of them decides something at the
public surface.

**A licence outlives the player that acquired it.** Everything the library has persisted so far is
either nothing (ADR-0006 rule 2) or bytes the consumer asked it to fetch, in a directory the
consumer named (ADR-0010 rule 1). An offline licence is neither: it is a small, expiring, device-bound
credential that has to survive the process that obtained it, or downloads do not play on a plane.
`OfflineLicenseHelper` acquires and releases one and hands back an opaque key-set id; where that id
and its expiry live is the library's to decide or the app's to own, and nothing in Media3 decides it.
`PRD.md` §3.2 adds the requirement that makes the storage question unavoidable: the
playback-versus-licence expiry split is handled explicitly, so that a downloaded asset with a dead
licence produces a *specific* message rather than "playback error" — which means something has to
have read the expiry before the player was asked to play.

**A security-level downgrade is a decision a client must not take alone.** `MediaDrm` reports a
`securityLevel` property, provisioning can fail on it, and a secure surface can fail to allocate;
the remedy that always works is to ask for an L3 licence and play on a non-secure path.
`PRD.md` §3.2 names both ways this is got wrong in the field — failing hard, and downgrading
silently — and states the rule between them: policy is server-driven, and the client never
unilaterally downgrades protected content. That is not a shape ADR-0011's ladder can carry. A rung is
a remedy the client applies to a failure it has classified, on its own authority, and every rung
below 6 already is. `FailureClass.Drm.*` sets its ceilings accordingly: `Provisioning` and
`LicenceAcquisition` reach rung 1 and nothing above it, `Unsupported` reaches rung 4. A downgrade fits
none of those and would be a seventh rung whose precondition is a *permission* rather than a
classification.

**`FailureClass.Drm.Unsupported` contains an argument that the public surface decides.** Its KDoc
sets the ceiling at rung 4 on the grounds that *another source may be protected differently*. That
sentence is true only if protection travels per source. `PRD.md` §2.2 reserves
`SuperPlayer.Builder.setDrm(WidevineConfig(licenseUrl, headerProvider = tokenProvider))` — builder-level,
beside `setProfile`. `MediaRequest.sources` is a `List<Uri>` and its KDoc says what the list is for:
a change of *protocol* for one piece of content, the canonical case being DASH falling back to HLS.
Both cannot stand as written, and everything else in the phase reads the answer: what the module is
handed, whether a session manager can be built once per player, whether rung 4 is offered a DRM
failure at all.

**Every Media3 type DRM needs is `@UnstableApi`.** `ExoMediaDrm`, `DrmSessionManager`,
`DrmSessionManagerProvider`, `DefaultDrmSessionManager`, `MediaItem.DrmConfiguration`,
`DrmSession.DrmSessionException`, `OfflineLicenseHelper` — all of them, and
`docs/api-surface.md` fails the build on any of them in a public signature. The seam this phase needs
already exists in shape: `TransferChain` assembles the `MediaSource.Factory` for every protocol, and
its stamping factory already replays `setDrmSessionManagerProvider` per item — nothing upstream has
ever set a provider, so the line is a reserved one rather than a live one.

**A DRM failure has to be classifiable by a consumer who has resilience but not this module, and by
one who has this module but not resilience.** ADR-0011 rule 1 makes `ErrorClassifier` the single
place a failure acquires a meaning and its rule 6 says in as many words that no licence plumbing is
resilience's; its Consequences section leaves a standing obligation that the `PRD.md` §3.2 mapping
lands in that classifier rather than in a second one. Rule 4 of the same ADR is the pattern for
getting evidence there: core *detects* and raises a typed public exception carrying the fields, and
the classifier *maps* it by reading them rather than re-deriving anything. `DrmSessionException`
wraps a cause whose useful content is a vendor error code and, from API 31, a
`MediaDrm.ErrorCodes` value, and — where the failure arrives as a `MediaDrmResetException` rather
than a `MediaDrmStateException` — the platform's own statement that the subsystem merely needs
re-opening; facts that answer "provisioning or licence,
retry or not" and that are lost by the time a `PlaybackException` with
`ERROR_CODE_DRM_UNSPECIFIED` reaches anyone.

**Pay nothing is a promise the project has now made four times** — ADR-0008 rule 2, ADR-0009 rule 7,
ADR-0010 rule 13, ADR-0011 rule 14 — and each is asserted by counting rather than by inspection.
Phase 6 adds one more optional module and inherits it, with one wrinkle the others did not have: a
`DrmSessionManagerProvider` that is *set* and answers `DrmSessionManager.DRM_UNSUPPORTED` is not the
same thing as one that was never set, and only the second is nothing.

**The friend count reaches six.** `KotlinFriendModules.kt`'s KDoc argues testkit and abr and says
the count is worth watching; ADR-0011's Consequences names that KDoc as the place a sixth would have
to be argued. This ADR is that argument, and the phase's build change carries it.

## Decision

**Protection is declared once per player, on the builder, and is fixed for the player's lifetime;
the credential it carries is the app's, through the one `HeaderProvider` the library has, which
moves to `superplayer-core` so that neither optional module has to depend on the other. The
Media3-facing half is internal to `superplayer-drm` and reaches the engine through one new slot in
core's seam, filled by an extension interface on the configuration object the consumer already
passes, as `EnginePolicyExtension` and `EngineResilienceExtension` are; `superplayer-drm` is core's
sixth Kotlin friend. The module keeps no taxonomy: it raises a public core exception carrying the
evidence, and `ErrorClassifier` maps it, which is ADR-0011 rule 4's pattern and rule 1's
requirement. An offline licence lives in a directory the consumer named, and SuperPlayer opens no
storage of its own. A lower security level is requested only where the licence server has permitted
it, and that is a mechanism beside the fallback ladder rather than a rung of it. A player built
without `setDrm` allocates nothing for DRM, and a test counts it.**

Thirteen rules follow, and they are binding.

### The public surface

1. **Protection is per player, declared on the builder and fixed for the player's lifetime.**
   `SuperPlayer.Builder.setDrm(…)` takes a public SuperPlayer type naming the key system, the
   licence URL and the credential — `PRD.md` §2.2's `WidevineConfig` — and there is no per-request,
   per-source or per-item protection. `MediaRequest.sources` stays a `List<Uri>`: the list means
   *this content, somewhere else*, and a request whose entries needed different licence servers
   would be two pieces of content under one identity, which is the defect `MediaRequest` exists to
   prevent. The decision is ADR-0005 rule 3's test applied to a credential rather than to a number:
   a licence server and an entitlement token are the app's, they do not vary with the content the
   app hands over, and a setter that let them vary per source would put a security decision in the
   hands of whatever code assembled a playlist.

   The consequence for the *fallback* case is that a second source is a second **container and
   protection-scheme mapping** for the same licence policy — DASH `cenc` or `cbcs` PSSH against HLS
   SAMPLE-AES with its own key format — and not a second entitlement. Rule 7 rewrites the sentence
   in `FailureClass.Drm.Unsupported` that this rule makes false.

2. **The credential is the app's, through one `HeaderProvider`, and that type lives in
   `superplayer-core`.** `PRD.md` §2.2 hands `setDrm` the same `tokenProvider` object the
   token-refresh layer takes, and that is right: a licence request bearing an entitlement token and
   a segment request bearing a signed one are the same problem, and an app that had to write the
   credential twice would eventually write it twice differently. The interface names no Media3 type
   and nothing of resilience's, so the declaration moves to `com.superplayer.core` and
   `superplayer-resilience` keeps the name a consumer already imports resolvable as a public
   `typealias` to it — one commit, both modules' tracked API files, no consumer edit. What that
   move must **not** be is the other repair, making one
   optional module depend on another: a consumer who wants Widevine and not the fallback ladder does
   not acquire the ladder, and `docs/modules.md`'s additive rule is the reason.

   When a licence request is refreshed is correctness and not the app's, exactly as ADR-0011 rule 12
   decided for a transfer: a licence request refused for an expired token is re-made once with what
   the provider returns, and a provider that returns what was just refused, or throws, declines and
   the refusal escalates unrepaired. The bound is progress, and it is the same bound.

3. **No Media3 type appears in a public signature, so the Media3-facing half is internal to
   `superplayer-drm` and reaches the engine through one slot in core's seam.** `EngineConfiguration`
   gains a **DRM slot**: the `DrmSessionManagerProvider` the `MediaSource.Factory` `TransferChain`
   assembles is given for every protocol it builds a source for, at the line its stamping factory
   already replays and that nothing has ever filled. One slot and not two: the session manager, the
   `ExoMediaDrm` under it, the licence `MediaDrmCallback` and the security level it asks for are one
   object graph built once per player, and a second slot would let an implementation fill one and
   not the other. The object the consumer hands `setDrm` also implements a core-internal extension
   interface through which it fills that slot — the shape ADR-0009 rule 7 gave `setPolicy` and
   ADR-0011 rule 13 gave `setResilience` — and the extension runs before the test configurator, so a
   test's engine configuration still wins.

4. **`superplayer-drm` is core's sixth Kotlin friend, and the argument is made in
   `KotlinFriendModules.kt`'s KDoc rather than only in a build file.** The argument is the one made
   for the fifth and it has not weakened: a friend path is a compiler flag and not a Gradle
   dependency, the dependency it does not create is the one `docs/modules.md` forbids, and what the
   module reaches is one core-internal extension interface on the object the consumer already
   passes. The count itself is the thing worth watching, and this ADR states the ceiling implied by
   it: a friend path is right for a later *phase of this library*, filling a slot core declared, and
   for nothing else. A module that wants friendship for any other reason wants a public API instead.

   *Refined by
   [ADR-0013](0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md)
   rule 4: a seventh friend, `superplayer-offline`, is built from core's seam and from what other
   friends filled into it, filling no slot. The ceiling now admits exactly those two shapes.*

   *Refined by [ADR-0014](0014-match-the-display-and-watch-it-change-behind-one-output-slot.md)
   rule 3: an eighth friend, `superplayer-tv`, fills one slot core
   declared, `EngineConfiguration.videoOutput`. It is this rule's shape, so no shape is added.*

### One taxonomy, still

5. **`superplayer-drm` classifies nothing.** ADR-0011 rule 1 stands unchanged: a failure acquires a
   meaning in `ErrorClassifier` and nowhere else, and a `when` over a `MediaDrm` error code in
   `superplayer-drm` would be the second taxonomy that rule exists to forbid. The module's job at
   failure time is to make the evidence survive, and rule 6 is how.

6. **The module raises a public core exception carrying the evidence, and the classifier maps it by
   reading its fields.** This is ADR-0011 rule 4's pattern, taken again for the same reason and with
   the same test: the consumer who most needs a typed DRM failure is the one whose device is unusual,
   and by the time a `DrmSessionException` has become a `PlaybackException` with
   `ERROR_CODE_DRM_UNSPECIFIED` there is nothing left in it to classify. So the licence and
   provisioning paths raise a public `superplayer-core` exception — the peer of
   `StaleLivePlaylistException` — naming which operation failed (provisioning, licence acquisition,
   licence renewal, key release), the vendor error code, and, where the platform reports them, the
   `MediaDrm.ErrorCodes` value and whether the platform raised a `MediaDrmResetException`.
   `ErrorClassifier` reads those fields and re-derives nothing; it does not consult `MediaDrm`, ask
   for a security level, or branch on a vendor string.

   The exception is a **core** type rather than a `superplayer-drm` one because `superplayer-resilience`
   is an earlier phase and may not depend on a later one, and because a consumer with DRM and no
   resilience should still get a `cause` that says what happened rather than a code that says
   nothing. `ERROR_CODE_DRM_UNSPECIFIED` reaching a consumer unaccompanied is the defect `PRD.md`
   §3.2 names, and this rule is what closes it.

7. **The `Drm` branch gains the leaves the mapping needs, in `superplayer-resilience`, and
   `Drm.Unsupported`'s stated reason is rewritten.** ADR-0011 rule 2 requires a class the corpus
   surfaces to *get* a class rather than have the nearest one stretched, and its rule 1's KDoc
   already tells this phase to add a leaf in a change that says why. Two things this rule fixes now,
   because later tickets read them:

   **The rewritten reason.** Rule 1 makes "another source may be protected differently" false. The
   ceiling of rung 4 survives on a different and narrower argument: a second source is a second
   container and scheme mapping for the same licence policy, so a refusal that is about *this
   manifest's* protection data — a PSSH box the device cannot parse, a scheme the container declares
   and the device does not implement — can genuinely be absent from the next source. That is the
   sentence `FailureClass.Drm.Unsupported`'s KDoc must carry.

   **What the rewritten reason excludes.** A refusal about the *device* rather than about the
   content — a revoked device, an operation the licence disallows, a key system the device does not
   have at all — is identical at every source, and rung 4 spends a manifest download to meet it
   again. Those belong in a leaf of their own with a ceiling of rung 6, and the error-mapping ticket
   adds it under ADR-0011 rule 2 rather than widening `Unsupported` to cover both. A leaf whose
   remedy is a licence re-acquisition keeps `LicenceAcquisition`'s rung-1 ceiling; nothing here moves
   `Provisioning`.

### Where an offline licence lives

8. **The consumer opens the offline licence store, naming the directory, and SuperPlayer never
   creates one it was not handed.** An offline licence is state that outlives the player that
   acquired it, which is exactly the condition ADR-0006 rule 2 governs, and the answer is ADR-0010
   rule 1's: the app names a directory, the library opens nothing else, and a consumer who never
   asks for offline playback has no store at all. Everything the store writes — the key-set ids, the
   expiries, its index — lands inside that directory, so uninstalling the app or deleting the
   directory takes the licences with it and leaves nothing behind (ADR-0010 rule 2's requirement,
   taken again).

   ADR-0006 rule 2 is refined and not superseded, and its wording stands: the list — no preferences,
   no database, no file — means no such thing chosen *on a consumer's behalf*. A licence store under
   this rule is storage the consumer opened, holding credentials they asked the library to obtain,
   and the privacy argument the rule rests on does not reach a directory the app named. ADR-0006
   carries a pointer to this ADR at rule 2 beside ADR-0010's, so a reader of that document alone is
   not misled.

9. **The licence expiry and the playback expiry are two facts, and the library reports both before
   playback rather than after it fails.** `PRD.md` §3.2's requirement is that a downloaded asset with
   a dead licence produces a specific message and not "playback error", and that is only possible if
   the remaining duration is read before the engine is asked to play. So the store answers what it
   holds for a `contentId` — whether a licence exists, what its playback duration and its licence
   duration have left — as a public, Media3-free value a consumer can act on, and a failure to open
   a session against an expired licence maps to the leaf rule 7 names rather than to a generic one.

   **Addendum (#210): the store performs its verbs over a player's protection, and reaches it back
   through the slot rule 3 opened.** Acquiring, renewing and releasing are each a round trip to the
   licence server, and rule 2 already decided what that round trip travels: the app's credential
   through the one `HeaderProvider`, `RetryPolicy.licence`'s budget, the `LoadKind.LICENCE` stamp —
   every one of them a property of a *player*. A store with an HTTP stack of its own would be a
   second way to reach the same licence server, answering to none of them. So `store.over(player)`
   is the shape, and the protection is recovered from `SuperPlayer`'s internal `licenceSessions`,
   which is the contents of rule 3's own slot handed back. That is the friend-path shape rule 4
   permits rather than a new one — the module reads its own object out of the slot it filled, nothing
   becomes configurable, and no helper of core's is reached — and the alternative, a registry keyed
   by player kept inside the module, would record the same fact twice and leak on every player
   nobody released. **Reading what the store holds needs none of it**: `licenceFor` answers from the
   directory with no player, no device and no network, which is what makes the paragraph above usable
   from a list screen rather than only from a player.

   *Refined by
   [ADR-0013](0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md)
   rule 13, for downloads only: a download store's exchange runs over the chain `TransferChain`
   assembles for it, which carries the credential, the licence budget and the stamp this addendum
   requires, so it is the same chain reached without a player rather than a second HTTP stack.*

   A stored licence that has *already* expired is refused at session composition rather than silently
   re-acquired. Media3's own answer to a restored licence at or near expiry is a licence request to
   the server, which on a player a consumer deliberately built to play offline is a round trip that
   was not to happen and, with no network, reaches them as a licence that failed to arrive instead of
   the licence that died — which is the "playback error" this rule exists to prevent. The refusal
   carries core's public `OfflineLicenceExpiredException` under `ERROR_CODE_DRM_LICENSE_EXPIRED`, so
   it is the leaf above rather than a new one, and rule 5 is untouched.

10. **Renewal and release are the consumer's to call, and the library schedules nothing.** Renewing
    before `getLicenseDurationRemainingSec()` reaches zero is `PRD.md` §3.2's requirement and the
    library provides the operation; *when* to run it is a background-work decision — a scheduler, a
    wake-up, a network condition — and a library that made it would be choosing a `WorkManager`
    configuration on a consumer's behalf in the same way ADR-0006 rule 2 forbids choosing storage.
    Release on delete is the counterpart and the same shape: a consumer deleting a download releases
    its keys through one call, and a store that leaked them would hold a device-bound credential for
    content the user has removed.

    *Refined by
    [ADR-0013](0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md)
    rule 13, for downloads only: renewal stays the consumer's, and so
    does releasing a licence acquired directly; releasing the licence of an item a download store
    holds is part of that item's removal, because the consumer's call was the removal.*

### The security-level ladder is not a rung

11. **A lower security level is requested only where the licence server has permitted it, and never
    on the client's own initiative.** The permission is a fact the server states — in its licence
    response, or in the configuration the app was given for it — and in its absence the client fails
    the session rather than downgrading it. This is `PRD.md` §3.2's rule written as a rule, and both
    failure modes it names are ruled out by it: failing hard is what happens when no permission was
    given, and it is correct; downgrading silently is what happens when the client decides for
    itself, and it is forbidden.

    **It is a mechanism beside the fallback ladder, not a seventh rung of it.** Every rung in
    ADR-0011 rule 7 is a remedy the client applies on its own authority once a failure is classified,
    and the rung order is fixed for all content precisely because no content wants it varied. A
    downgrade's precondition is a permission rather than a classification, its effect is on what the
    session *is* rather than on where the bytes come from, and a viewer watching at L3 what they were
    entitled to watch at L1 has had a different thing delivered rather than the same thing rescued.
    So it lives in the session-opening path of `superplayer-drm`, `FailureClass.Drm.*` keeps the
    ceilings ADR-0011 gave it, and `FallbackLadder` gains nothing. A session that opened at a reduced
    level says so in telemetry, because a support engineer reading a session needs to know which
    thing was delivered.

    *Addendum (#225).* "The session-opening path" was where the mechanism could run, not where the
    rule holds, and one of the three ways `PRD.md` §3.2 says L1 becomes unusable is invisible from
    there: a device the provisioning service will not certify declares a secure decoder, reports
    `L1`, and is refused from inside `DefaultDrmSessionManager` once the graph is fixed. Such a
    player now **builds its session graph again** at the permitted level and is re-prepared at the
    position playback had reached. Every clause above survives it unchanged: the permission is still
    the set the operator published and an empty one is still a refusal, so nothing is lowered that
    the configuration did not name; the level is still written down for telemetry, now settled
    mid-session rather than before it, which `SuperPlayer.deliveredSecurityLevel` and `SessionEnded`
    report at the end either way; and it is still not a rung — no `FallbackRung`, no
    `FailureClass` leaf and no ceiling moves, and `PlayerStateRungs` is untouched. What is new is a
    second slot in core's seam, `EngineConfiguration.protectionRepair`, which core *interrogates* at
    failure time exactly as ADR-0011 rule 13's addendum has it interrogate the two player-state
    rungs, and which is offered the failure **before** them: a request's sources are one piece of
    content in more than one place, so a refusal at the level asked for is a refusal at every source.
    Which failures are offered is the *classifier's*, as a fourth question on `FailureClass`
    (`lowerSecurityLevelMayHelp`, true for the two leaves a refused keybox reaches and false for
    every other failure in the library) carried out on `SuperPlayerError`: the module keeps no
    taxonomy (rule 5) and core matches on no class name, so ADR-0011 rule 1's "a failure acquires a
    meaning in exactly one place" holds for this mechanism as it does for the ladder. A ceiling is
    not that answer and cannot be — `Drm.Unsupported` reaches rung 4 and `Drm.Provisioning` reaches
    rung 1, while both may be cured by a level — which is the same reason this is not a rung. The bound is core's too and is one attempt per player
    (`SuperPlayer.MAX_PROTECTION_REOPENS`): Widevine has a single level below `L1`, so a second
    attempt would ask for the level already in force.

    **Addendum (#226).** The third way — a secure surface that will not allocate — reached a player
    on this same path, and closing it added no mechanism at all: the only production change is a
    classifier leaf. What made that possible is that the fourth question turned out not to be the
    `Drm` branch's. A secure surface that cannot be backed is the *device* failing to produce an
    output path that keys already issued at `L1` oblige it to use, so it is
    `FailureClass.Device.SecureDecoderInit`, it buckets `DECODER` as it always did, and rule 3's
    one-to-one table does not move — but `lowerSecurityLevelMayHelp` is true for it, because `L3`
    decodes in ordinary memory and needs no protected surface. Told from an ordinary decoder-init
    failure by two fields of Media3's own `DecoderInitializationException`: a `codecInfo` at all,
    which Media3 attaches only where a decoder was selected and its initialisation threw, and that
    `codecInfo` being the secure one. Both halves of that narrowing earn their keep — the flag on
    `Device.DecoderInit` wholesale would have lowered a security level for every codec that would not
    start, and reading the exception's `secureDecoderRequired` instead would have swept in the case
    where no secure decoder was *found*, which another variant or another source may well supply and
    for which rung 4 is the cheaper remedy. Its ceiling is rung 6 for `Drm.DowngradeRefused`'s reason
    in the same words: every rung below is a different place to get bytes that need the same surface
    — an argument that holds precisely because the not-found case was left out of the leaf. And its
    `userMessageKey` is
    the seventh rather than an eighth — the content may not be shown on this device — because that is
    the sentence, not "this device cannot play it". Where the claim is verified and where it is not is
    argued in `SecureSurfaceDowngradeTest` and named in `docs/testing.md`; the origin of such a
    failure is reachable on no device this repository tests on, and that is said rather than faked.
    Nothing about rule 12's line moves: this is a surface that failed to *allocate*, and secure
    surface discipline remains correctness under ADR-0006 rule 1.

12. **The secure-decoder reading is a device constraint, read once, in core's one place.** ADR-0009
    rule 2's deferral table names the secure-decoder instance limit as this phase's, and rule 2's own
    test decides its shape: it does not change under a playing session, so it is a *constraint* and
    not a *condition*, and it is read through core's `DeviceConstraints` alongside the profile, level
    and instance limits Phase 3 already reads there — once, before the platform's codec list is
    first consulted, and never observed. What reads it is track selection, refusing a variant the
    secure decoder cannot sustain, which is `PRD.md` §3.1's own note that F4's frame drops on
    low-end devices are usually that variant rather than the cost of DRM. Secure surface discipline —
    a `SurfaceView` on a secure path, `FLAG_SECURE`, and behaviour on a display-capability change —
    is correctness under ADR-0006 rule 1 and not a profile's to vary.

    *Refined by [ADR-0014](0014-match-the-display-and-watch-it-change-behind-one-output-slot.md)
    rule 13.* The `TextureView` refusal is core's, on the facade of a player
    built with `setDrm`. `FLAG_SECURE` is the app's, set by the demo. A display-capability change
    under a playing protected session is re-selected by `superplayer-tv` (ADR-0014 rule 5), and what
    the new output may show stays the CDM's to enforce.

### Pay nothing

13. **A player, a pool or a session built without `setDrm` allocates nothing for DRM, and a test
    counts it.** The DRM slot is empty, no `DrmSessionManagerProvider` is set on any media source
    factory, no `ExoMediaDrm` is instantiated, no store is opened and no class from the module is
    loaded. A provider that is set and answers `DRM_UNSUPPORTED` is not nothing and does not satisfy
    this rule — the distinction matters because it is the easy accident here, and it is visible in a
    golden trace of a core-only session, which must not change when Phase 6 lands whatever the diff
    says. It is asserted the way ADR-0010 rule 13 and ADR-0011 rule 14 are: a core test builds a
    player with a profile alone and counts what was set, the same counts are taken with the module
    attached so the counter is shown to see what it counts.

## Consequences

**Easier.** Every Phase 6 issue after this one (#203–#214) has a written answer to the question it
would otherwise settle at a call site: what the module is handed, where the session manager comes
from, who names a failure, where a licence lives, and what may be downgraded. The one collision the
phase opened with — builder-level protection against a leaf whose reason assumed per-source
protection — is decided in one place, with the replacement sentence written out, rather than
discovered by whoever edits the KDoc first. ADR-0011's standing obligation is discharged without a
second classifier, and `RetryPolicy.licence` — declared in Phase 5, budgeted per profile, and dead
because Media3 routes a licence load through the DRM session manager's own
`LoadErrorHandlingPolicy` — becomes reachable through rule 3's slot rather than staying a number
nothing reads.

**Harder.** Rule 2 moves a type between modules. `HeaderProvider` is public API under
`docs/api-surface.md`, so the move is a tracked API change in both modules in one commit, and it is
the kind of change that is cheap now and expensive in a year — which is the argument for doing it in
the phase that discovers the second caller rather than after shipping a third.

Rule 8 is the expensive one, for the reason ADR-0010 rule 5 named and one more. A store has a
threading model, a clean-up policy and a migration story, and those are `superplayer-drm`'s to own
and to state. The extra cost here is that what it holds is *device-bound and expiring*: a key-set id
copied to another device is worthless, a restored backup can present the store with ids the DRM
subsystem no longer knows, and a factory reset invalidates every one of them at once. The store has
to treat an unknown id as an absent licence rather than as an error, and rule 9's query is where that
is visible.

Rule 13's distinction — set-and-unsupported is not the same as never set — is a promise that is
easier to break than the previous four, because a `DrmSessionManagerProvider` is the natural thing to
set unconditionally and let answer `DRM_UNSUPPORTED`. The test counts the set, not the answer.

Rule 11 costs a capability the field will ask for. A device whose L1 provisioning fails and whose
server has permitted nothing plays nothing — still true after #225, which changed only what happens
where the server *has* permitted something — and someone will propose a flag. The flag is the silent
downgrade under another name, and the place to change this decision is a superseding ADR arguing
that the client may decide, which is a harder argument than it looks.

**A standing obligation.** Phase 7's downloads read this store and this expiry split rather than
opening a second one, and its resilience reads ADR-0011's taxonomy with rule 7's leaves in it.
Phase 8's TV work owns the display-capability change rule 12 names as correctness — an HDMI hotplug
under a playing secure session is a condition in ADR-0009 rule 2's sense, and it arrives there.
*The Phase 7 half is discharged by
[ADR-0013](0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md)
rules 1, 9 and 13.* *The Phase 8 half is discharged by
[ADR-0014](0014-match-the-display-and-watch-it-change-behind-one-output-slot.md) rules 5 and 13.*

## Alternatives considered

**Per-source protection: retype `MediaRequest.sources` so each entry carries its own configuration.**
Rejected under rule 1. It would keep `FailureClass.Drm.Unsupported`'s existing sentence true at the
price of making a licence server and an entitlement token a property of a URL, which is the coupling
`MediaRequest` exists to break; two entries with different licence servers are two pieces of content
under one `contentId`, and the cache key, the measurement session and the CMCD `sid` all say they
are one. The narrower fact the rejected shape was reaching for — that a second source may declare a
different scheme mapping — is preserved by rule 7's rewritten reason, without moving protection into
the request.

**A second, DRM-specific error taxonomy in `superplayer-drm`, since the module has the richest
evidence.** Rejected under ADR-0011 rule 1, which rejected the same shape for telemetry. The evidence
being richest here is exactly why rule 6 carries it out in a typed exception: a vocabulary is not the
way to move facts between modules.

**Let `ErrorClassifier` read `MediaDrm` itself and keep the module out of the error path.** Rejected.
It would put `MediaDrm` and vendor error strings into `superplayer-resilience`, which a consumer
carries for the fallback ladder with no DRM anywhere, and it would re-derive at classification time
facts the session already had — ADR-0011 rule 4 rejected exactly that for the stale-playlist
detection, and two derivations disagree eventually.

**Make the security-level ladder rung 7.** Rejected under rule 11. The ladder's rungs are remedies
the client applies on its own authority, and adding one whose precondition is a server's permission
would mean a `FallbackLadder` that sometimes may not climb for a reason no classification can see.
It would also make the downgrade order-dependent — tried after a variant exclusion and before a next
source — for content where the right answer is to downgrade first or not at all.

**A builder flag, `setAllowSecurityDowngrade(true)`, so an app can opt in.** Rejected under rule 11,
and it is the rejection most likely to be re-litigated. The flag is the app asserting a permission
that is the licence server's to give, one build-time boolean away from the silent downgrade `PRD.md`
§3.2 names; an app that genuinely has the permission has a server that can say so, and an app whose
server cannot say so has not decided this question at all.

*Addendum (#223).* This rejection is narrower than it first reads, and #208 read it too widely —
withdrawing the wire format it built in consequence. What is rejected is a **boolean the app
asserts**: true of every server it ever talks to, and carrying no statement from any of them. It is
not a rejection of configuration as a channel, which rule 11 names in its own sentence — "in its
licence response, **or in the configuration the app was given for it**". `WidevineConfig`'s
`permittedSecurityLevels` is the second: a set of levels *this* server's operator published, named
per server, carrying as much of the app's own opinion as the licence address beside it does, which is
none. The test that separates them is whether an app could answer it without asking anyone — `true`
it can, a list of levels its licence provider will issue at it cannot. Both keep rule 11's default,
since an app that states nothing permits nothing.

**Persist offline licences in a directory the library picks, since they are small and opaque.**
Rejected under ADR-0006 rule 2 and rule 8. Size is not the argument the rule rests on; a
device-bound credential describing what a person has downloaded is precisely the data the rule is
about, and "it is only a few bytes" has been the first step of every library that ended up owning a
database.

**Keep `HeaderProvider` in `superplayer-resilience` and have `superplayer-drm` depend on it.**
Rejected under `docs/modules.md`'s additive rule. A consumer wanting Widevine would acquire the
fallback ladder, its classifier and its taxonomy, and the pay-nothing promise of ADR-0011 rule 14
would become untestable for them.

**A `DrmSessionManagerProvider` set unconditionally, answering `DRM_UNSUPPORTED` without the
module.** Rejected under rule 13. It is one line simpler in core and it makes the pay-nothing claim
false: a provider is an object, it is consulted per item, and a golden trace of a core-only session
would change.

**`ServiceLoader` or reflection to find the module.** Rejected for ADR-0009's and ADR-0011's reasons,
which hold unchanged: behaviour that changes with a dependency and no line of code, and a pay-nothing
claim a classpath scan would make false.

## References

- `MediaDrm` — `getPropertyString("securityLevel")`, provisioning, and key-set ids:
  https://developer.android.com/reference/android/media/MediaDrm
- `MediaDrm.ErrorCodes` — the platform's own DRM error vocabulary, reachable from API 31 through
  `MediaDrmStateException.getErrorCode()`, which rule 6 carries out:
  https://developer.android.com/reference/android/media/MediaDrm.ErrorCodes
- `MediaDrmResetException` — the platform saying the subsystem needs re-opening rather than that the
  request was wrong, the other half of rule 6's evidence:
  https://developer.android.com/reference/android/media/MediaDrmResetException
- `DrmSession.DrmSessionException` — what a failed session hands the engine:
  https://developer.android.com/reference/androidx/media3/exoplayer/drm/DrmSession.DrmSessionException
- `DrmSessionManagerProvider` and `DefaultDrmSessionManager` — the object a `MediaSource.Factory` is
  given, and `DRM_UNSUPPORTED`, which rule 13 distinguishes from an unset provider:
  https://developer.android.com/reference/androidx/media3/exoplayer/drm/DrmSessionManagerProvider
- `OfflineLicenseHelper` — `downloadLicense`, `renewLicense`, `releaseLicense` and
  `getLicenseDurationRemainingSec`, whose two durations are rule 9's split:
  https://developer.android.com/reference/androidx/media3/exoplayer/drm/OfflineLicenseHelper
- `PlaybackException.ERROR_CODE_DRM_UNSPECIFIED` — the code `PRD.md` §3.2 says must not reach a
  viewer alone: https://developer.android.com/reference/androidx/media3/common/PlaybackException
- Widevine security levels L1 and L3, and the server's role in stating what a client may play:
  https://developers.google.com/widevine/drm/overview
- ISO/IEC 23001-7 Common Encryption — the `cenc` and `cbcs` protection schemes and the PSSH box rule
  7's rewritten reason turns on: https://www.iso.org/standard/68042.html
- RFC 8216, HTTP Live Streaming — §4.3.2.4 `EXT-X-KEY` and the SAMPLE-AES key format, the other half
  of that sentence: https://www.rfc-editor.org/rfc/rfc8216
- `FLAG_SECURE` and secure surfaces — why a secure path is a `SurfaceView`:
  https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE
- [ADR-0001](0001-compose-dont-fork.md) rule 2,
  [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md) rule 3,
  [ADR-0006](0006-own-the-platform-rules-and-hand-back-the-state.md) rules 1 and 2,
  [ADR-0009](0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md) rules 2 and 7,
  [ADR-0010](0010-cache-in-consumer-chosen-storage-and-preload-on-the-one-chain.md) rules 1, 2, 5 and
  13, and [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md)
  rules 1, 2, 4, 6, 7, 12, 13 and 14; `KotlinFriendModules.kt`'s KDoc; `TransferChain`'s KDoc;
  `docs/modules.md`'s additive and phase rules; `docs/api-surface.md`.
- `PRD.md` §2.2, §3.1, §3.2 and Parts 4 and 6; #201 — the phase this decides for; #203–#214 — the
  issues that cite these rules.
