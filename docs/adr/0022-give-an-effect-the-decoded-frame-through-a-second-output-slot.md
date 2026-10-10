# ADR-0022: Give an effect the decoded frame through a second output slot, and keep Picture in Picture

- **Status:** Accepted
- **Date:** 2026-10-10
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** [ADR-0014](0014-match-the-display-and-watch-it-change-behind-one-output-slot.md)
  rule 3, which gave core one video output slot and argued against a second one *for audio*. This
  record adds a second slot for a different fact and records the amendment as an addendum at that
  rule (rule 3 below says why the audio argument does not transfer). ADR-0014 rule 14's "pays
  nothing" is untouched and is the reason the slot is second rather than shared.
- **Relies on**, without changing:
  [ADR-0012](0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md) rule 1,
  protection fixed per player, which rule 6 below leans on; and
  [ADR-0020](0020-compose-on-avfoundations-avplayer-and-bring-no-other-engine.md) rule 1, whose "no
  second engine" is why the iOS half pulls frames from `AVPlayer` rather than decoding them.
- **Summary:** Decides `docs/ios-plan.md` §3.11. A consumer reaches the decoded frame of a playing
  video through one SuperPlayer type, `FrameOutput`, fixed at build time and naming no engine type
  beyond the platform's own frame. On Android it is laid on Media3's `setVideoEffects` by a new
  module that is core's eleventh Kotlin friend, through a **second** slot,
  `EngineConfiguration.frameOutput`, rather than by sharing ADR-0014's `videoOutput`: a filled slot
  is a whole signal, and sharing one would turn a TV player's frame-rate strategy and display watch
  on for every effects player, and couple the TV module to effects. On iOS the frame is pulled from
  an `AVPlayerItemVideoOutput` and the processed frame is enqueued to an
  **`AVSampleBufferDisplayLayer`**, not drawn to a bare Metal layer, because that keeps Picture in
  Picture and is the layer the realtime path already renders through. Protected content and
  realtime are out of reach on both platforms, and a player that can play either reports the output
  off with a typed reason — never a playback failure. A player built without a frame output pays
  nothing, and a test counts it.

## Context

An on-device ML effects library is being built as a separate project — person segmentation,
auto-reframe and the like. It targets stock Media3 first, and SuperPlayer through an adapter.
SuperPlayer ships no effects; what it needs is a place where an effect can attach, on both
platforms, with one rule for what is out of reach. No Android phase gives an effect a decoded frame
today, so this is not a port. It is decided now because the iOS answer decides **how the iOS core
renders**, and that is fixed when the core is first built (i1, #400 and #405); deciding later would
make the frame path a retrofit of the facade's rendering.

**Android.** Media3 has the seam. `ExoPlayer.setVideoEffects(List<Effect>)` runs a GL effect
pipeline between the decoder and the surface, implemented in the `media3-effect` artifact, which
core does not depend on. Both the method and `Effect` are `@UnstableApi` in Media3 1.11.0, so under
ADR-0001 rule 2 neither may appear on SuperPlayer's public surface, and an adopter reaches the method
through `player.exoPlayer` today. Its documentation sets four limits that bind any design here:

- it must be called before `prepare()` to set the pipeline up;
- it works only with the default `MediaCodecVideoRenderer`;
- it rejects effects that change frame timestamps;
- it does not work with DRM-protected content.

**iOS.** Apple's documentation for `AVPlayerItem.videoComposition` says a video composition is not
supported for media served with HTTP Live Streaming, and a custom `AVVideoCompositing` compositor
arrives only through a video composition. What remains for streamed content is the pull path: an
`AVPlayerItemVideoOutput` added to the item, a display link asking it for the pixel buffer due at
each host time, and a layer the processed frame is shown on in place of `AVPlayerLayer`. Developers
report that the output yields no pixel buffer under FairPlay (Apple Developer Forums thread 726854);
that is a report rather than documented behaviour, and i6's device run verifies it. The realtime
path has no decoded frame to tap, because `AVSampleBufferDisplayLayer` decodes internally.

**Two questions were left open by the plan**, and this record exists to close them.

*Which slot.* ADR-0014 gives core one `EngineConfiguration.videoOutput` slot, which `TvOutput` fills
and which is fixed for the player's lifetime. A frame output could share that slot — one
`PlaybackOutput` composing display matching and effects — or take a second one. ADR-0014 rule 3
states two things about its slot: **the filled slot is the whole signal**, so that filling it makes
core switch Media3's frame-rate strategy off, register the display watch, lay the tunneling half and
allow re-selection on an audio-capability change; and **no second slot is added for audio**, because
Media3's audio output already watches the capabilities and a module receiver would be a second
watcher of one fact.

*Which layer on iOS.* Processed frames can go to an `AVSampleBufferDisplayLayer`, which
`AVPictureInPictureController` accepts as a content source (iOS 15 and later), or be drawn to a
`CAMetalLayer`, which is simpler and which Picture in Picture cannot take.

## Decision

**A frame output is one SuperPlayer type, `FrameOutput`, passed at build time and fixed for the
player's lifetime. On Android it fills a second core slot, `EngineConfiguration.frameOutput`, from a
new module that lays it on `setVideoEffects`. On iOS it is core's own pull path into an
`AVSampleBufferDisplayLayer`. Protected content and realtime are not reached, and a player that can
play either reports the output off with a typed reason.**

Ten rules follow, and they are binding. No code exists when this record is accepted: #406 builds
the Android half and #405 the iOS half, each exactly as decided here, and each rule names what will
enforce it.

### The public type

1. **One SuperPlayer type, `FrameOutput`, and no engine effect API on the surface.** A consumer
   implements `FrameOutput`: it is handed one decoded frame and its presentation time, and it
   returns the frame to display at that same time. The frame is the platform's own picture type,
   because that is what an ML effect operates on and wrapping it would copy or hide it — on Android a
   GL texture with its size, carried by a SuperPlayer value type; on iOS a `CVPixelBuffer`, which is
   a stable CoreVideo type and the case ADR-0020 rule 3 admits. Neither `Effect` nor
   `AVVideoCompositing` crosses the boundary (ADR-0001 rule 2). What is shared is the vocabulary —
   the name, the one method, its timing contract, the typed reasons of rule 6 — and not an effects
   API: no effect written for one platform runs on the other, and none is meant to. *Enforced by
   `verifyNoUnstableMedia3InPublicApi` and the tracked surface on Android, and the digester on iOS.*

2. **The output is fixed at build time; what it does with a frame is its own affair.** On Android
   `SuperPlayer.Builder.setFrameOutput(output)` and `PlayerPool.Builder.setFrameOutput(output)`; on
   iOS `Configuration.frameOutput`. Android needs this because Media3 requires the pipeline before
   `prepare()`; iOS takes it for parity and because the display path (rule 5) is chosen when the
   player is made. **The effect list is not SuperPlayer's to change during playback** — SuperPlayer
   installs one adapter over the consumer's `FrameOutput`, and an effect that switches its own
   processing on or off, or changes its model, does so inside `process` without the engine
   reconfiguring. That gives the "effects may change during playback" the plan asked for without
   depending on Media3 re-laying a pipeline mid-item. **A frame's time cannot be changed**: the
   method returns a frame and not a time, so Media3's third limit is met by the signature rather
   than by a refusal. *Enforced by the signature, and by the tracked surfaces.*

### The slot (Android)

3. **A second slot, `EngineConfiguration.frameOutput`, beside ADR-0014's `videoOutput`; the two are
   not shared.** The decision is a second slot, and ADR-0014 rule 3 carries the addendum. Three
   reasons, in order of weight:
   - **A filled slot is the whole signal, and the two signals are different.** Filling `videoOutput`
     switches off Media3's frame-rate strategy, registers a `DisplayListener`, lays tunneling and
     allows audio re-selection (ADR-0014 rule 3). A phone app adding segmentation wants none of it,
     and sharing the slot would mean either giving it all four — an effects player paying for TV
     correctness, against ADR-0014 rule 14 read in reverse — or splitting the signal *inside* one
     slot, which is a second slot with one name.
   - **Tunneling and an effect pipeline cannot both hold.** A tunneled player hands decoded frames
     to the display without passing through a surface the app's GL can read, so the composed
     object would have to arbitrate between ADR-0014 rule 7's tunneling half and every effect.
     Separate slots make the conflict a statement core can make once (rule 4) rather than a branch
     inside the TV module.
   - **The TV module stays ignorant of effects, and the effects module of displays.** Each depends
     on core alone and fills its own slot, so neither needs a release when the other changes.

   **ADR-0014's "no second slot" argument does not transfer.** It refused a second slot *for audio*
   because a module receiver would be a second watcher of a fact Media3 already watches. A frame
   output watches nothing Media3 watches; it is a different fact with a different consumer. What
   ADR-0014 rule 3 actually protects — one slot per fact, the filled slot as the whole signal — is
   kept, twice.

   The filler is a new module, depending on core alone, that is **core's eleventh Kotlin friend** of
   the first shape ADR-0012 rule 4 names, a later module filling a slot core declared: it carries
   the `media3-effect` dependency, so a player without effects carries neither the module nor the
   artifact, and it lays the consumer's `FrameOutput` on `setVideoEffects` as one Media3 `GlEffect`
   before `prepare()`. Its name, its public factory and its phase row in `docs/modules.md` are
   #406's to fix in the change that adds it, under ADR-0014 rule 2's spelling clause: an issue that
   finds a better spelling amends this rule in the same change. *Enforced by
   `KotlinFriendModules.kt`'s declaration and review for the friendship, and by a seam test
   counting both slots (rule 9).*

4. **A player built with both outputs is built, and tunneling yields to the frame output.** A
   tunneled video path bypasses the effect pipeline, so on a player whose `frameOutput` is filled,
   core does not lay ADR-0014 rule 7's tunneling half even where the decision asks for it, and
   `playbackDecision` keeps saying what was asked, as it already may (ADR-0014 rule 7's own caveat).
   Frame-rate matching and the display watch are unaffected: they act on the surface the pipeline
   renders to. Media3's second limit is met the same way: core never combines a frame output with a
   renderers factory other than Media3's default in a consumer's player, and the harness's fake
   renderers are the test seam's, which can show the list reached the engine and not that a frame
   was processed. *Enforced by #406's test that a player with both slots filled has no tunneling
   parameter laid; the renderer half by review alone, since a test cannot show the absence of a
   consumer path to replace the renderers factory.*

### The display path (iOS)

5. **Processed frames are enqueued to an `AVSampleBufferDisplayLayer`, not drawn to a bare Metal
   layer.** On a player configured with a frame output, core adds an `AVPlayerItemVideoOutput` to
   each item, a display link asks it for the pixel buffer due at each host time, the consumer's
   `FrameOutput` processes it, and core wraps the result in a `CMSampleBuffer` stamped with the
   item time and enqueues it to an `AVSampleBufferDisplayLayer` whose control timebase follows the
   item's, flushing on a seek. The library's player view hosts that layer in place of
   `AVPlayerLayer`. The reasons:
   - **Picture in Picture survives.** `AVPictureInPictureController` takes a sample-buffer display
     layer as a content source, with a playback delegate core implements over the `AVPlayer`. A
     Metal layer is not a content source, so a frame output would cost every effects app Picture in
     Picture.
   - **It is the layer the realtime path already renders through** (ADR-0020 rule 11), so the iOS
     core has one non-`AVPlayerLayer` display path and not two, and the one player view renders
     either player through it.
   - **Display timing stays the system's.** The layer presents each buffer at its timestamp against
     the timebase, so core never schedules a present itself.

   **What the sample-buffer layer loses**, stated because the choice costs something: one
   `CMSampleBuffer` wrap per frame (a reference to the same pixel buffer, not a copy); an effect must
   write its result into a `CVPixelBuffer` — through a Metal texture cache if it renders with Metal —
   rather than draw straight into a drawable; and what a drawable allows that a buffer does not, such
   as rendering at the display's resolution rather than the content's. **What a Metal layer would
   have lost** is Picture in Picture, the shared layer with realtime, and the system's presentation
   timing. **What both lose** is `AVPlayerViewController`: it renders the player's frames itself and
   would show the unprocessed picture, so on a player built with a frame output the library's own
   view is the recipe, and its documentation says so. *Enforced by #405's tests that the layer is
   a sample-buffer layer and that a Picture in Picture content source can be made from it; that
   Picture in Picture actually runs is a device claim, under #435.*

### What is out of reach

6. **Protected content and realtime are not reached, and a player that can play either reports the
   output off with a typed reason — never a playback failure.** Media3 does not run its effect
   pipeline over protected content, and on iOS the decrypted frames of FairPlay content are exactly
   what the protection exists to keep from app code, whatever the forum report turns out to say. So
   on both platforms, **a player built with protection has its frame output off for its lifetime**:
   on Android one built with `setDrm`, on iOS one configured with `drm`. Protection is fixed per
   player (ADR-0012 rule 1) and the output is fixed per player (rule 2), so the two facts meet when
   the player is made, which is where the answer is given: the player builds, plays every item
   exactly as it would without a frame output, and reports the output's state as off with the
   reason `PROTECTED_CONTENT`, readable at build and on each item adopted. The same holds for
   realtime with the reason `REALTIME`: on iOS `RealtimePlayer` takes no frame output at all, since
   no decoded frame exists; on Android a player built with `setRealtime` has its output off, because
   whether Media3's pipeline runs over the sample queues ADR-0018 writes is unexamined and an output
   that silently applied or silently did not would be the worse answer. The cost is stated: a
   player built for protected content gets no effect on its *clear* items either. Per-item switching
   was rejected below, and an app that wants effects on clear content in a protected catalogue builds
   a second player — which `PlayerPool` already makes cheap. **The reason is never delivered as an
   error**: not through `onPlayerError`, not as a `SuperPlayerError`, and not as a `FailureClass`,
   because nothing failed and ADR-0011's taxonomy is for sessions that ended. *Enforced by #405's and
   #406's tests that a protected item plays to the end with the output off and the reason
   reported.*

7. **When the output is unused, the display path is unchanged.** Without a frame output, the Android
   engine is built exactly as today and the iOS player view hosts an `AVPlayerLayer` exactly as a
   stock app would; `AVPlayerViewController`, Picture in Picture from an `AVPlayerLayer`, and every
   integration ADR-0020 lists keep working. The sample-buffer path exists only on a player built
   with a frame output. *Enforced by rule 9's count.*

8. **The first effect is not this repository's.** i1 builds the seam, the display path behind it,
   and one pass-through output in the demo that proves frames arrive on time (#407). The first real
   effect belongs to the separate effects project, and no effect ships in a SuperPlayer artifact. A
   pass-through that drops no frame `AVPlayerLayer` would have shown is the iOS exit claim, and it is
   a device count (#435), because the simulator's display timing proves nothing about a phone's.
   *Enforced by review; a shipped effect would be a public type the digester shows.*

### Pay nothing

9. **A player built without a frame output registers and allocates nothing for it, and a test counts
   it.** On Android: the `frameOutput` slot is empty, `setVideoEffects` is never called, no class of
   the new module is loaded, and `media3-effect` is not on the classpath of an app that did not add
   the module. On iOS: no `AVPlayerItemVideoOutput`, no display link, no sample-buffer layer. The
   count is taken the ADR-0014 rule 14 way — once with a profile alone and once with a hand-written
   stand-in filling the slot — so the counter is shown to see what it counts, and the Android count
   includes `videoOutput` so that filling one slot is shown not to fill the other. *Enforced by
   #406's and #405's seam tests.*

10. **What this record does not decide is said, not left.** Whether an effect may change resolution,
    how an effect's own latency is budgeted against the frame interval, and whether a frame output
    ever reaches realtime on Android are open, and a phase that needs one amends this record. The
    telemetry vocabulary gains nothing here: a frame an effect was late with is a dropped frame the
    collector already counts, and `SCHEMA_VERSION` does not move. *Not enforceable; listed so a
    reader does not mistake silence for a decision.*

### What nothing enforces

Rule 10 is enforced by nothing and cannot be, and rule 8 by review alone. Rule 3's friendship is declared and reviewed
rather than counted, as every friendship is. Rule 5's Picture in Picture claim and rule 8's frame
count need a device. Rules 1, 2, 4, 6, 7 and 9 are carried by the tracked surfaces and by #405's and
#406's tests once they land.

## Consequences

**Easier.** An effects library has one SuperPlayer type to adapt to on each platform, fixed in one
place, and the Android half stops being "reach through `player.exoPlayer` and opt in to
`@UnstableApi`". The iOS core is built once on a display path that already serves realtime and
Picture in Picture, so neither has to be retrofitted.

**Harder, and honestly.** A second slot is a second thing core's engine construction reads and a
second friend to account for, which is the price of keeping two signals whole. The sample-buffer path
puts timebase management, flushing on seek and a Picture in Picture playback delegate into iOS core,
code a Metal layer would not have needed in the first release. `AVPlayerViewController` is lost on
any player with a frame output, on both choices. And rule 6 refuses effects to clear items on a
protected player, which a consumer with a mixed catalogue will notice.

**ADR-0014's slot count is now two.** Anyone adding a third should read rule 3's argument first: a
new slot is justified by a new signal, never by a new feature that could ride an existing one.

## Alternatives considered

**Share ADR-0014's `videoOutput` slot, one `PlaybackOutput` composing display matching and
effects.** It keeps ADR-0014's single slot and its "no second slot" sentence literally. Rejected
under rule 3: the filled slot would no longer be the whole signal, an effects player would pay for
TV correctness or the composed object would hide a second slot inside the first, tunneling and
effects would be arbitrated inside the TV module, and the TV module would learn about effects.

**A bare Metal layer on iOS.** Simpler: draw the processed texture to a `CAMetalLayer` and present
it. Rejected under rule 5: it loses Picture in Picture, it is a second display path beside the
realtime one, and core would schedule every present itself.

**Per-item switching for protected content.** Keep the output on for clear items of a protected
player and off for protected ones. Rejected under rule 6: on iOS it swaps the displayed layer
between items, which shows; on Android it needs Media3 to re-lay or bypass its pipeline mid-playlist,
which is unverified; and a second player is already cheap.

**A custom `AVVideoCompositing` compositor.** Not supported for HLS, which is every streamed item
this library plays, and with a reported regression on composition-backed items under the Xcode 27
betas (Apple Developer Forums thread 837179).

**Decoding with VideoToolbox on the HLS path.** A second engine, which ADR-0020 rule 1 rejects, and
it forfeits FairPlay and the system's HLS stack.

**Exposing Media3's `Effect` directly.** ADR-0001 rule 2 forbids it.

**Putting the effect pipeline in core on Android.** No new module and no eleventh friend. Rejected
under rule 9: core would carry `media3-effect` into every app, which pays for effects it never uses.

**Deferring the decision past i1.** The iOS core would build on `AVPlayerLayer` alone, and the frame
path would arrive later as a retrofit of the facade's rendering — the change this record exists to
avoid.

## References

- Media3 1.11.0 API reference: `ExoPlayer.setVideoEffects`, `Effect`, `GlEffect`, the
  `media3-effect` artifact — the four limits of *Context*.
- Apple Developer Documentation: `AVPlayerItem.videoComposition`, `AVVideoCompositing`,
  `AVPlayerItemVideoOutput`, `CADisplayLink`, `AVSampleBufferDisplayLayer`,
  `AVPictureInPictureController.ContentSource(sampleBufferDisplayLayer:playbackDelegate:)`,
  `AVPictureInPictureSampleBufferPlaybackDelegate`, `CMSampleBufferCreateReadyWithImageBuffer`,
  `CVMetalTextureCache`, `CAMetalLayer`.
- Apple Developer Forums threads 726854 (no pixel buffer under FairPlay, reported) and 837179 (the
  compositor regression, reported). Both are reports, not documentation, and are cited as such.
- [`docs/ios-plan.md`](../ios-plan.md) §3.11.
- [ADR-0001](0001-compose-dont-fork.md) rule 2,
  [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md),
  [ADR-0012](0012-acquire-licences-behind-the-boundary-in-storage-the-consumer-opened.md) rules 1 and 4,
  [ADR-0014](0014-match-the-display-and-watch-it-change-behind-one-output-slot.md) rules 2, 3, 7 and 14,
  [ADR-0018](0018-push-encoded-frames-through-one-framesource-into-media3s-own-sample-queues.md),
  [ADR-0020](0020-compose-on-avfoundations-avplayer-and-bring-no-other-engine.md) rules 1, 3 and 11.
