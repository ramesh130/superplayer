# ADR-0014: Match the display, watch it change, and reach the engine through one output slot

- **Status:** Accepted
- **Date:** 2026-09-17
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** [ADR-0009](0009-observe-conditions-re-apply-decisions-and-remember-per-transport.md)
  rules 1, 2 and 4: the display stops being a constraint read once and becomes a seventh observation
  with a sixth trigger (rules 9 to 11 below), recorded as addenda at those rules.
  [ADR-0005](0005-decide-playback-policy-behind-an-engine-agnostic-boundary.md) rule 2, extended
  by a sixth half of `PlaybackDecision` (rule 7 below) and recorded there as an addendum.
  [ADR-0012](0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md) rule 12
  and its standing obligation, whose display-capability half this ADR places (rules 5 and 13), and
  rule 4's friend ceiling, which gains an eighth instance of its first shape and no new shape
  (rule 3). `PRD.md` §3.8 and Part 4's phase 8 row are *narrowed*: they name Leanback beside Compose
  for TV, and rule 12 drops it. Nothing is superseded, and each refinement is recorded at the rule it
  refines.
- **Summary:** Decides Phase 8's shape before any of its code lands (#264, #265).
  `superplayer-tv` depends on `superplayer-core` alone and reaches the engine as core's **eighth**
  Kotlin friend, through one new slot, `EngineConfiguration.videoOutput`, filled by the object a
  consumer hands to a new `setOutput` call. Matching the display's refresh rate to the content, and
  re-selecting when the display or the audio output changes underneath playback, are correctness,
  on for every player built with the module. Tunneling is policy, a sixth half of `PlaybackDecision`,
  and the `TV_LEANBACK` profile's numbers are policy in core's table. The display becomes a live
  observation, `PlaybackConditions.display`, with a sixth trigger, `DISPLAY_CHANGED`, while decoders
  stay a constraint read once. The surface is Compose for TV only. #213's TextureView refusal is
  core's, its `FLAG_SECURE` is the demo's, and its display-change half is this module's. A player
  built without the module registers and allocates nothing for it, and a test counts it.

## Context

Phase 8 (`PRD.md` §3.8 and Part 4, #264) ships `superplayer-tv`: display capability negotiation
through `Display.Mode` and `HdrCapabilities`, frame-rate matching through `Surface.setFrameRate`,
tunneled playback where supported, HDMI hotplug and audio-capability change handling, D-pad-first
controls, and an Ethernet-transport profile. Its exit criterion is a TV device and emulator on
which frame-rate matching is verified and HDMI and audio-capability changes are handled. The facts
below decide the shape.

**The module is an empty placeholder, and core's seam has no slot for what it does.**
`EngineConfiguration` has slots for loading, buffering, selection, metering, errors, protection, the
clock and the renderers. It has none for how video frames meet a display, and none for tunneling.
`docs/modules.md` lets `superplayer-tv` (phase 8) depend on every earlier module; it declares core.

**Media3 already asks for a frame rate, but only a seamless one.** `ExoPlayer.Builder` and `ExoPlayer`
take a `videoChangeFrameRateStrategy`, which Media3 1.11 offers two values for:
`C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS`, the default, and
`C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF`. With the default, Media3's video renderer calls
`Surface.setFrameRate` itself, asking the platform for a rate change only where the switch is
seamless. On most televisions an HDMI mode switch is *not* seamless: the panel blanks for a moment
while the sink resynchronises. Media3's default therefore leaves 24 fps content on a 60 Hz mode on
exactly the devices whose viewers complain about judder. Android 12 added a third argument to
`Surface.setFrameRate`, `CHANGE_FRAME_RATE_ALWAYS`, and a user setting that governs it. Android TV's
*Match content frame rate* setting offers *Never*, *Seamless only* and *Always*
(`DisplayManager.getMatchContentFrameRateUserPreference`), and the platform applies it to an app's
request. Two callers of `setFrameRate` on one surface overwrite each other.

**`Surface.setFrameRate` needs a `Surface`, which a consumer hands the facade.** `SuperPlayer`
implements `Player`, and every `setVideoSurface*` call arrives there before it reaches the wrapped
engine, so core is the one place that sees a surface attached and detached. The video format's frame
rate is Media3's `Format.frameRate`, known once a video track is selected and before its first frame
is rendered.

**The display is read once, and ADR-0009 said when that would stop being true.** `DeviceConstraints`
reads the largest display mode's shorter edge and the display's HDR types once, before the codec list
is first consulted. `NetworkAwareTrackSelection` refuses a rung whose short edge exceeds the display's,
and a PQ rung on a display that lists no PQ type. ADR-0009 rule 2 left the display a constraint
because on a phone it does not change under a session, and named an HDMI mode switch, a TV concern,
as the change that would make it a condition in Phase 8. On a TV box it changes at the owner's hand:
an AV receiver in the HDMI chain, a 4K HDR television swapped for a 1080p SDR one, or a soundbar that restarts the link. A player that read the
display at construction keeps offering rungs the new display cannot show.

**Media3 already re-selects on an audio-capability change, on every player.** A `DefaultAudioSink`
built with a context registers an `AudioCapabilitiesReceiver`, which watches HDMI audio plug
broadcasts, routed-device callbacks and the external-surround setting. The sink reports a change to
its renderer, and `DefaultTrackSelector` implements `RendererCapabilities.Listener` and invalidates
the selection on `onRendererCapabilitiesChanged`. Core builds its engines with Media3's own renderers
and its own `DefaultTrackSelector`, with or without abr's selection factory. So an AV receiver
powered on mid-playback is already *seen*. What no test here shows is that the re-selection
survives SuperPlayer's composition, so passthrough formats become selectable without an error or
a stall.

**Tunneling is a trade, not a fix.** `DefaultTrackSelector.Parameters.Builder.setTunnelingEnabled`
asks for it, and Media3 enables it only where the device's decoders and audio sink support it for
the selected formats. A tunneled path hands A/V synchronisation to the platform's hardware. That is
cheaper on a low-end TV SoC and smoother where the vendor's implementation is good. It costs
visibility, because frames the platform releases are not frames Media3 counts. It also needs a
`SurfaceView`, and some vendors' implementations are worse than the path they replace. Which way
that trade falls differs by profile and device.

**Leanback is in maintenance.** `androidx.leanback` receives fixes but no new features. Android's TV
guidance directs new apps to Compose for TV (`androidx.tv:tv-material`). `PRD.md` was written
naming both.

**#213 was handed here in part.** ADR-0012 rule 12 names secure surface discipline, a `SurfaceView`
on a secure path, `FLAG_SECURE`, and behaviour on a display-capability change, as correctness under
ADR-0006 rule 1. Its standing obligation hands "the display-capability change rule 12 names" to
Phase 8. #213's own comment notes that its `TextureView` refusal and the demo's `FLAG_SECURE` do not
depend on Phase 8, if this ADR places them outside `superplayer-tv`.

**What a consumer pays for is a decision, again.** ADR-0009 rule 7, ADR-0010 rule 13, ADR-0011 rule 14,
ADR-0012 rule 13 and ADR-0013 rule 15 each promise that a player built without a module pays nothing
for it, and each counts it. A display listener registered on every phone player, or a frame-rate
strategy changed on every player, would break that promise for a module the app never added.

## Decision

**`superplayer-tv` depends on `superplayer-core` alone. It reaches the engine as core's eighth Kotlin
friend through one new slot, `EngineConfiguration.videoOutput`, filled by the `PlaybackOutput` a
consumer passes to `setOutput`. On every player built with it, the display's refresh rate is matched
to the content, and a change of the display or of the audio output re-selects rather than playing
on regardless. That is correctness, and no profile varies it. Tunneling is a sixth half of
`PlaybackDecision`, and `TV_LEANBACK` is a core profile with a reason per row. The display is a
seventh observation with a sixth trigger, and decoders stay a constraint read once. The only TV
surface is Compose for TV, on a `SurfaceView`. #213's `TextureView` refusal is core's, its
`FLAG_SECURE` is the demo's, and its display-change half is this module's. A player built without
the module registers and allocates nothing for it, and a test counts it.**

Fourteen rules follow, and they are binding.

### Dependencies and the seam

1. **`superplayer-tv` declares `superplayer-core` and no other SuperPlayer module in its main
   sources.** Nothing it does needs another module. Frame-rate matching and display watching are
   engine-facing. The display refusal it re-arms already lives in `superplayer-abr`, and reaches
   abr through core (rule 10). Its controls take a Media3 `Player`, which a SuperPlayer is. An app
   that plays protected content on a TV adds `superplayer-drm` because it already needed it, and
   the TV module learns nothing about Widevine. The module's tests may depend on testkit, testmedia,
   abr, drm, resilience and telemetry, which is the allowed direction. It does not depend on
   `superplayer-ui`: that module is unscheduled, and `docs/modules.md` says nothing may depend on an
   unscheduled module. The Compose and Compose-for-TV libraries it adds each arrive with their
   `THIRD_PARTY.md` row in the change that first uses them (#272).

2. **The public surface is one core type and one factory, and names no unstable Media3 type.**
   - **Core type.** `PlaybackOutput` is a public core interface, the peer of `PlaybackDrm` and
     `PlaybackResilience`.
   - **Builders.** `SuperPlayer.Builder.setOutput(output)` and `PlayerPool.Builder.setOutput(output)`
     take it. Like protection (ADR-0012 rule 1), it is fixed for the player's lifetime.
   - **Factory.** The module's entry point is `TvOutput.standard(context)`, which returns a
     `PlaybackOutput`. One such object may be handed to many builders: it fills the slot *per
     player*, as `Resilience.standard` does.
   - **Controls.** The Compose-for-TV controls (rule 12) are public composables whose parameters are
     a `Player` and SuperPlayer or Compose types. `Player` is stable, and ADR-0001 rule 2 permits it.

   The spelling above is this ADR's. An issue that finds a better spelling amends this rule in the
   change that renames it, rather than drifting from it.

3. **`superplayer-tv` is core's eighth Kotlin friend, and it has the first shape ADR-0012 rule 4
   names: a later phase filling a slot core declared.** A `PlaybackOutput` that is also core's
   internal `EngineOutputExtension` fills one slot, `EngineConfiguration.videoOutput`, with a
   per-player `VideoOutputBinding`. That is a core-internal interface, and core calls it on the
   application thread:
   - `onSurfaceChanged(surface: Surface?)` whenever the facade's video surface is set, replaced or
     cleared. Core resolves a `SurfaceView` or `SurfaceHolder` to its `Surface`.
   - `onVideoFrameRate(framesPerSecond: Float?)` when the selected video format's frame rate becomes
     known or changes. It passes null for a format with no declared rate and for audio-only content.
   - `release()` with the player.

   **The filled slot is the whole signal, as `decisionTarget` is for re-consultation** (ADR-0009
   rule 7). On a player whose `videoOutput` is filled, core does four things, and it does none of
   them otherwise:
   - It builds the engine with `C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF`, so the binding is the only
     caller of `Surface.setFrameRate`.
   - It registers the display watch of rule 5.
   - It lays the output half of rule 7 into the selector's parameters.
   - It resolves `DISPLAY_CHANGED` for rule 11.

   No second slot is added for audio, and rule 6 says why. The argument for the friendship is
   `KotlinFriendModules.kt`'s, unchanged: a compiler flag rather than a Gradle dependency, one
   internal interface on the object the consumer already passes, and no configurability widened.
   This adds an instance and no shape, so the ceiling reads as ADR-0013 rule 4 left it.

### What is correctness and what is policy

4. **Frame-rate matching is correctness on every player built with the module, and no profile varies
   it.** When the binding holds both a surface and a frame rate, it calls
   `Surface.setFrameRate(framesPerSecond, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
   CHANGE_FRAME_RATE_ALWAYS)` on API 31 and later. On API 30 it calls the two-argument form, the only
   one that exists there. It clears the request with a rate of zero when the surface is cleared, the
   rate becomes unknown, or the player is released.
   - **Why `ALWAYS` is not a trade.** The seamless-or-not choice is the viewer's: it is the TV's
     *Match content frame rate* setting, and the platform applies it to the request. Asking only
     for seamless switches, which is Media3's default, would override the viewer's *Always* with the
     library's *Seamless only*. On most TVs that is judder left unfixed.
   - **When the request lands.** It is made on the first video format, which precedes the first
     rendered frame, so a non-seamless switch blanks the panel before content and not during it.
   - **Below API 30.** No `Surface` API exists. The module's surface composable (rule 12) holds a
     window and sets `WindowManager.LayoutParams.preferredDisplayModeId` to a mode whose refresh
     rate is a whole multiple of the content's (#272). A consumer who brings their own surface below
     API 30 gets no matching, and the module's KDoc says so.
   - **Why not every player.** A phone keeps Media3's seamless-only default untouched (rule 14). A
     phone has no such viewer setting, and a non-seamless switch on a phone panel is a flicker nobody
     asked for.

5. **Surviving a display change is correctness on every player built with the module.** The display
   watch is core's. It is a `DisplayManager.DisplayListener` on the default display, registered on
   the player's application looper, and its reading is translated into a `DisplayCapability` in
   `ConditionsBinding.kt`, the one translation file ADR-0009 rule 3 allows. When a reading differs
   from the one in force, core does these, in this order:
   1. It writes the new capability to `DisplayInForce` (rule 10), which the selection gate reads on
      every evaluation.
   2. It rewrites `TrackSelectionParameters`' viewport to the new display's size, but **only where
      the viewport still holds the value Media3 derived at construction**. A viewport the consumer
      set is theirs, which is ADR-0009 rule 5's promise about their parameters.
   3. It invalidates the selection through core's own `DefaultTrackSelector` subclass. Media3's
      `TrackSelector.invalidate` is protected, and exposing it is the subclass's only job. The
      invalidation replaces a rung already buffered on the next selection, not only on the next
      chunk.
   4. It hands the binding its frame rate again, because a new display is asked afresh.
   5. On a re-consulted player, it consults the policy with `DISPLAY_CHANGED` (rule 11).

   **A change of refresh rate alone is not a change.** The frame-rate request of rule 4 causes one,
   and a player that re-selected on its own request would loop. `DisplayCapability` carries no rate
   (rule 9), so equality excludes it by construction. A player built without abr has no display
   refusal, and on it steps 2 and 3 re-run Media3's own viewport-bounded selection. The HDR refusal
   stays where it is, in abr's gate, and rule 10 says why it is not copied into core. #269 builds
   this rule.

6. **Surviving an audio-capability change is correctness on every player, and it is Media3's
   mechanism rather than this module's.** Every player already registers Media3's
   `AudioCapabilitiesReceiver` inside its audio sink, and every selector core builds already
   invalidates on the renderer's report (Context). A module slot would add a second receiver for a
   fact the engine already watches. #270 does two things. It asserts the claim through the harness
   on a player built with the module and on one built without it: an AV receiver powered on
   mid-playback makes a passthrough format selectable, powered off it re-selects PCM, and neither
   raises an error. **If that assertion fails, the fix belongs in core.** A phone on a USB-C dock
   loses its passthrough device just as a TV does, and a fix in this module would repair only one of
   them.

7. **Tunneling is policy: a sixth half of `PlaybackDecision`, `output: OutputPolicy`, applied at
   construction and never re-applied.** `OutputPolicy(tunneling: Boolean)` defaults to
   `OutputPolicy.NONE`, which is tunneling off. A policy written before the half existed therefore
   decides no tunneling, as ADR-0013 rule 12's default did for downloads.
   - **Where it is laid.** `EngineBinding.kt`, the one translation place, lays it into the
     selector's `setTunnelingEnabled` on a player whose `videoOutput` is filled. Everywhere else it
     is decided and ignored, and `SuperPlayer.playbackDecision` still says what was decided.
   - **"Where supported" is Media3's check.** Media3 tunnels only where the device supports it for
     the formats selected, so a `true` on an unsupported device is a request refused, not an error.
   - **Why never re-applied.** Turning tunneling on or off re-enables both renderers, which is a
     visible break in playback. Honouring a changed half on a trigger would create the defect
     rule 5 exists to prevent. So core applies the construction decision's half and no later one,
     and the half's KDoc says a policy that varies it with conditions varies something nothing
     honours. This is the one half ADR-0009 rule 5's "honoured whole" reaches by being fixed. The
     alternative, re-applying it, is recorded below.
   - **Why policy.** The trade in Context has different right answers per profile and device, which
     is ADR-0005 rule 2's test. #271 builds it.

8. **`TV_LEANBACK` is a core `PlaybackProfile`, and its numbers are rows of `StaticProfilePolicy`
   with a reason each.** The profile enum and the static table are core's (ADR-0005 rule 3), and
   choosing the profile is independent of adding the module. A TV app may play short-form under
   `SHORT_FORM` with the module installed. An Ethernet set-top app that renders to its own surface
   may name `TV_LEANBACK` without the module. The Ethernet half, a TV on Ethernet not behaving like a
   phone on WiFi, is `TransportCaps`' per-transport table in `superplayer-abr` and a
   `NetworkProfile` in the harness. Both are policy and measurement, not this module's code. #267
   builds them, and the profile's `output` row is #271's.

### The display as a live condition

9. **The display is a seventh observation, `PlaybackConditions.display: DisplayCapability?`.** It
   amends ADR-0009 rule 1's closed list.
   - **Shape.** `DisplayCapability(shortEdgePx: Int?, hdrTypes: Set<HdrType>?)`. `shortEdgePx` is the
     active mode's shorter edge in physical pixels. `HdrType` is a SuperPlayer enum: `DOLBY_VISION`,
     `HDR10`, `HLG`, `HDR10_PLUS`. Both fields keep the gate's convention: *unknown* is null and
     constrains nothing, while an empty set is a display that answered and supports no HDR.
   - **The active mode, not the largest.** `DeviceConstraints` read the largest mode because nothing
     would change it. A live reading is of what the panel is showing now, and after a hotplug that
     is the only honest reading.
   - **Its reader.** `superplayer-abr`'s `AdaptiveSelectionPolicy` caps `maxVideoHeightPx` at
     `shortEdgePx`. The selection gate already refuses those rungs, so what the cap changes is what
     reaches the buffer policy: branch 5's heap ceiling is sized against the ceiling in force. After
     a hotplug to a lesser display, the ceiling in seconds is sized for the rungs the display can
     show, not for a 2160p rung nothing will play. That is the reader ADR-0005's standing obligation
     demands of every property.
   - **What is left out.** The refresh rate and the mode list are not in it. No policy reads them,
     and rule 4's request lets the platform choose the mode. Rule 5 needs the rate absent.
   - **When it is observed.** Live on a re-consulted player with the module. Once, at construction,
     on a re-consulted player without it. Not at all on a player that is never re-consulted, which is
     ADR-0009 rule 5 unchanged.

10. **`DeviceConstraints` keeps the decoders and gives up the display.** The decoder table, the
    secure decoders and the instance limits are the device's own silicon, and no hotplug changes
    them. They stay a constraint, read once before the codec list is first consulted (ADR-0009
    rule 2, ADR-0012 rule 12).
    - **Where the display fields go.** `displayShortEdgePx` and `displayHdrTypes` move to
      `DisplayInForce`, a core-internal window beside `DecisionInForce` on `EngineConfiguration`.
      `NetworkAwareTrackSelection`'s gate reads it on every evaluation.
    - **Without the module, nothing changes.** Every player fills `DisplayInForce` once at
      construction from the same translation, so the gate refuses exactly what it refuses today.
      With the module, rule 5 writes it again on each change.
    - **Why the HDR refusal stays in abr.** Copying it into core, so that a player without abr
      refused a PQ rung too, would make two copies of one rule. It would also give core a selection
      of its own: Media3 1.11's adaptive factory is final where it builds, which is why abr's
      `NetworkAwareTrackSelection` has a factory of its own, and core would need a second one. A
      TV consumer who wants the HDR refusal takes `AdaptivePolicy`, which is what `TV_LEANBACK`'s
      recipe in the demo does (#273).

11. **`DecisionTrigger.DISPLAY_CHANGED` is the sixth trigger, and it amends ADR-0009 rule 4's closed
    list.** It fires on a re-consulted player with the module, when rule 5 observes a change. As
    for every trigger, a consultation that returns the decision in force emits nothing, and one that
    changes it emits `DecisionChanged` with this trigger. A new value of an existing field is shape,
    not meaning (ADR-0008 rule 5), so `TelemetryEvent.SCHEMA_VERSION` stays **2**.
    `docs/telemetry-schema.md` lists the value, and its release notes say the version did not move,
    in the change that adds it (#269).

### The surface, and #213

12. **The only TV surface is Compose for TV, and its video surface is always a `SurfaceView`.**
    - **Where it lives.** The module's composables live in `superplayer-tv`, over
      `androidx.tv:tv-material`: D-pad focus, a transport bar and seek-scrubbing. Leanback is not
      built against, and `PRD.md` §3.8 and Part 4 are narrowed to say so.
    - **Why always a `SurfaceView`.** A `TextureView` cannot carry a tunneled stream, cannot show a
      secure buffer, and composites through the app's own GPU path instead of reaching the display
      as its own layer. Each of the phase's three claims needs the surface to be its own layer.
    - **Scrubbing.** It uses Media3's scrubbing mode internally where the `Player` is a SuperPlayer.
      Its public face stays `Player`. #272 builds the controls and #273 the demo.

13. **#213's display rule is placed in three parts.**
    - **The `TextureView` refusal is core's, and it does not wait for Phase 8.** A player built with
      `setDrm` throws `IllegalStateException` from `setVideoTextureView`, naming ADR-0012 rule 12. The
      facade is the one place that sees both facts, the surface and `EngineConfiguration`'s
      `protectedPlayback`. A log warning is exactly what a consumer misses, and the black rectangle
      it warns of looks like a playback failure. A `Surface` a consumer built from a
      `SurfaceTexture` themselves is indistinguishable at `setVideoSurface`, and the KDoc says that
      rather than claiming a refusal it cannot make.
    - **`FLAG_SECURE` is the demo's.** It is set on the window of the activity that shows protected
      content, and its KDoc says it is the app's call. The library has no window, and the flag
      blanks every screenshot of that window, the app's own UI included. Deciding that is the
      app's.
    - **A display change during protected playback is this module's.** It is rule 5, which applies
      to a protected player unchanged. What the new output is *permitted* to show is the CDM's to
      enforce, against the licence's output-protection requirements. A refusal the CDM raises
      arrives as a failure `ErrorClassifier` already names, and nothing here lowers anything
      locally (ADR-0012 rule 11). No HDCP level is observed, and the alternatives say why.
    - **Without the module.** A protected player reads its display once, as today, and that is
      stated rather than hidden. What it loses is re-selection, not protection, because output
      protection is enforced below the player either way.

    #213 closes on the first two parts and on #269.

### Pay nothing

14. **A player, a pool or a session built without `setOutput` registers and allocates nothing for
    the module, and a test counts it.** Concretely:
    - The `videoOutput` slot is empty.
    - The engine keeps Media3's default frame-rate strategy.
    - No `DisplayListener` is registered, and `DisplayInForce` is read once.
    - No tunneling parameter is laid.
    - No class from `superplayer-tv` is loaded.
    - A core-only session's golden trace is byte-identical before and after Phase 8.

    The count is `SuperPlayerOutputSeamTest` in core, taken the way ADR-0012 rule 13's is: once with
    a profile alone, and once with a hand-written extension filling the slot, so the counter is shown
    to see what it counts. #268 lands it with the slot, and #269 extends it to the display
    listener.

## Consequences

**Easier.** Every Phase 8 issue after this one (#266–#274) has a written answer to the questions it
would otherwise settle at a call site: which slot, who registers the display listener, what counts
as a display change, where tunneling is decided, where the HDR refusal lives, and which parts of
#213 wait. Two ADR-0009 deferrals are collected in the phase that names them, with the reader each
observation needs. #213 can begin its `TextureView` and `FLAG_SECURE` halves at once.

**Harder.** Rule 4 makes a TV's first frame wait for a mode switch wherever the viewer chose
*Always*. Time to first frame on such a device includes the HDMI resynchronisation, which is
seconds rather than milliseconds on some sinks. The metric's meaning does not change, because the
viewer waited for it. A report comparing a TV arm with a phone arm will still show it, and
`docs/telemetry-schema.md` says so in the change that ships rule 4 (#268).

Rule 5 puts a `DefaultTrackSelector` subclass in core whose only job is to expose `invalidate`.
Every Media3 upgrade must be checked for whether the protected method moved. Rule 5's viewport
comparison is a heuristic too: a consumer who set the viewport to exactly the value Media3 derived
is indistinguishable from one who did not set it, and is overwritten. That consumer asked for the
display's size and gets the display's size.

Rule 7's "applied at construction, never re-applied" is the first half of `PlaybackDecision` that a
re-consulted player decides and ignores. `playbackDecision` can therefore say `tunneling = true` on
a player that is not tunneled, both because the device refused and because a later decision changed
it. The half's KDoc has to say both.

Rule 10 moves two fields out of a type abr reads, which is an internal change in two modules in one
commit (#269). Rule 9 widens a public data class, `PlaybackConditions`, which is a tracked API change.

Rule 12 drops a surface `PRD.md` promised. A consumer with a Leanback app keeps every engine-facing
rule by calling `setOutput` and bringing their own `SurfaceView`. They lose only the controls.

Rule 13's `TextureView` refusal is a behaviour change for any existing protected player on a
`TextureView`. Such a player was showing a black rectangle on a secure path, or a clear picture only
on L3, so the refusal replaces a silent failure with a thrown one. The release notes say so.

## Alternatives considered

**Leanback, beside Compose for TV or instead of it.** Rejected under rule 12. The library is in
maintenance, and Android's TV guidance points new apps at Compose for TV. Two control surfaces would
double the focus, scrubbing and accessibility work, one of them on a library that will not gain the
features the other gets.

**Controls in a separate `superplayer-tv-compose` artifact, or in `superplayer-ui`.** Rejected.
`superplayer-ui` is unscheduled, and nothing may depend on it (`docs/modules.md`). A fourteenth
module holding only composables would split one phase's surface from its own rules. Its only saving
is Compose on the classpath of a TV app that brings its own UI, and R8 removes composables nobody
calls. If that saving turns out to matter, the split is a later module-table change, not a
rewrite.

**Frame-rate matching through Media3's default strategy alone.** Rejected under rule 4. It is
already on, on every player, and it is seamless-only, which leaves judder on exactly the TVs that
need a mode switch and overrides the viewer's own setting.

**Frame-rate matching as a profile's choice.** Rejected under rule 4 and ADR-0006 rule 1. The trade
it would expose, a blank during the switch against judder throughout, belongs to the viewer. The
platform already asks them, in Settings.

**Frame-rate matching on every player, in core, without the module.** Rejected under rule 14. It
changes phone behaviour for apps that never asked. On a phone a non-seamless switch is a flicker,
and there is no viewer setting to consult.

**A second slot for audio capabilities, filled by the module.** Rejected under rule 6. Media3's sink
already registers the receiver on every player, so a module receiver would be a second watcher of
one fact. Where the first watcher is found wanting, the defect is every player's.

**The module registers the display listener and translates the reading itself.** Rejected under
ADR-0009 rule 3. Observation is core's, in one translation file. A module that read `Display.Mode`
would be a second place Android's vocabulary becomes an observation, and the abr gate would be reading
a module's type.

**The display as a constraint re-read by rebuilding the player on a hotplug.** Rejected under
rule 5. It tears down buffer, decoder, DRM session and position for a change the engine can absorb
with a re-selection. It is also the visible break a hotplug should not cause.

**The refresh rate or the full mode list in `DisplayCapability`.** Rejected under rule 9. Nothing
reads them. Including the rate would also make the player's own frame-rate request a display
change, and every match would re-select.

**The HDR refusal copied into core, so a player without abr gets it.** Rejected under rule 10. It
would be two copies of one rule, and it needs a core subclass of Media3's adaptive selection that
duplicates abr's.

**Tunneling as correctness, on wherever supported.** Rejected under rule 7. Vendors' implementations
differ in quality, and it trades away the frame visibility telemetry reports. That is a trade with
different answers per device and profile.

**Tunneling re-applied on every trigger.** Rejected under rule 7. Toggling it re-enables both
renderers, a visible break that a display or network trigger would then cause mid-content.

**HDCP level as an observation, so a policy could select on it.** Rejected under rule 13. Which rung
an output may show under a licence is the licence server's rule, enforced by the CDM. A client that
selected on HDCP would be deciding output protection on its own authority, which is ADR-0012
rule 11's refusal in a new place.

**The `TextureView` refusal in `superplayer-drm`, or as a warning.** Rejected under rule 13. The drm
module has no hook on the facade's surface calls, and adding one would be a slot for a single `if`.
A warning is exactly what a consumer misses.

**`FLAG_SECURE` set by the library.** Rejected under rule 13. The library has no window, and the flag
blanks the app's own UI in screenshots and recordings, which is the app's decision to make.

**Public opaque handles instead of an eighth friend.** Rejected for ADR-0009's reason, repeated by
ADR-0010 rule 3 and ADR-0013. A public type that is empty until a module passes a Media3 object
through it is an `@UnstableApi` leak with a delay.

## References

- `Surface.setFrameRate`, `FRAME_RATE_COMPATIBILITY_FIXED_SOURCE`, `CHANGE_FRAME_RATE_ALWAYS`:
  https://developer.android.com/reference/android/view/Surface#setFrameRate(float,%20int,%20int)
- Frame-rate guidance, including the viewer's *Match content frame rate* setting:
  https://developer.android.com/media/optimize/performance/frame-rate
- `DisplayManager.getMatchContentFrameRateUserPreference` and `DisplayManager.DisplayListener`:
  https://developer.android.com/reference/android/hardware/display/DisplayManager
- `Display.Mode` and `Display.HdrCapabilities`:
  https://developer.android.com/reference/android/view/Display.Mode
  and https://developer.android.com/reference/android/view/Display.HdrCapabilities
- `WindowManager.LayoutParams.preferredDisplayModeId`:
  https://developer.android.com/reference/android/view/WindowManager.LayoutParams#preferredDisplayModeId
- `ExoPlayer.setVideoChangeFrameRateStrategy` and its two strategies:
  https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer#setVideoChangeFrameRateStrategy(int)
- `AudioCapabilitiesReceiver`:
  https://developer.android.com/reference/androidx/media3/exoplayer/audio/AudioCapabilitiesReceiver
- `DefaultTrackSelector.Parameters.Builder.setTunnelingEnabled`:
  https://developer.android.com/reference/androidx/media3/exoplayer/trackselection/DefaultTrackSelector.Parameters.Builder#setTunnelingEnabled(boolean)
- `FLAG_SECURE`:
  https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE
- Compose for TV:
  https://developer.android.com/training/tv/playback/compose
- Leanback's release notes and status:
  https://developer.android.com/jetpack/androidx/releases/leanback
