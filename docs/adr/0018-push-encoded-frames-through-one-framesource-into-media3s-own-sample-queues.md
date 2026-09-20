# ADR-0018: Push encoded frames through one `FrameSource` into Media3's own sample queues

- **Status:** Accepted
- **Date:** 2026-09-19
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Refines:** None — this record changes no earlier rule's scope. It **relies on** ADR-0016 rule 13
  staying at four entry points (rule 10 below) and on ADR-0013 rule 8 already refusing live content,
  and it weakens no rule of either.

## Context

Two realtime transports are in scope for phases 13–15 of [`PRD.md`](../../PRD.md) Part 4: Media over
QUIC (MoQ) and WebRTC over WHEP. Both are sub-second, push-based and live-only, and neither resembles
the pull-based, seekable, `DataSource`-fed pipeline every phase before this one was built on.

The framing the work started from was that WebRTC's `PeerConnection` model does not map onto
`MediaSource → SampleStream → Extractor`, and that the answer is therefore a custom `Renderer` that
owns transport and decode and pushes decoded frames at the `Surface` the renderer machinery provides.

**That framing is half right, and the half that is wrong is the expensive half.** ExoPlayer will not
run a `Renderer` that is not attached to a `MediaPeriod`: `Renderer.enable()` is handed a
`SampleStream`, renderers are enabled only for tracks selected from a `MediaPeriod`'s
`TrackGroupArray`, and `STATE_READY` is computed from `MediaPeriod.getBufferedPositionUs()`. A custom
`MediaSource`/`MediaPeriod` pair is needed **whichever way the decision goes**. The only real fork is
what flows through the `SampleStream`: encoded samples that Media3's own `MediaCodec` renderers
decode, or nothing, with a renderer of ours decoding out of band.

Media3's own precedent for the push-based case is the RTSP module: RTP packets arrive pushed, are
depacketised into `SampleQueue`s, and surface as an ordinary `MediaSource` under a live, unseekable
timeline. Nothing about being push-based requires leaving the sample pipeline.

### What the spikes found

Phase 12's spikes are issues #337–#341, and their findings are recorded in full on those issues.
Four contradicted the framing they were written against, and each removed or added work.

- **MoQ's Kotlin bindings hand over encoded frames with container framing already stripped** (#340).
  The UniFFI layer wraps the track in a container consumer before the frame is exposed, and the
  payload was observed byte-identical across all three `MoqContainer` variants for a given codec. The
  planned fragmented-MP4 demux therefore does not exist as work. **The observation was taken at the
  library's API boundary rather than off the wire, and covered H.264 only.**
- **Whether codec-specific data is present is decided by the codec's fourcc, not by the container**
  (#340). `avc1` carries an out-of-band `avcC` record; `avc3` carries none and puts parameter sets
  in-band before every keyframe. Both branches are real; they are keyed to a different field than was
  assumed. **The `hvc1`/`hev1` half of this is derived from source and was not observed.**
- **WHEP signalling does not fit the HTTP seam this library already has** (#337), and the obstruction
  is the verb rather than the credential. WHEP is a `POST` carrying an SDP body, answered `201` with
  a `Location`, torn down by `DELETE`; `HttpTransport` models a `GET` with an optional byte range.
  The credential side fits well — a CDN's default playback may need no auth, and its signed mode puts
  a token in the URL path. #337 also found that the CDN examined implements `draft-murillo-whep-01`
  rather than the IETF working-group draft, so "implements WHEP" is not one behaviour.
- **MoQ exposes connection statistics and does not expose loss statistics** (#339). RTT, estimated
  rates and byte and packet counters are reachable from Kotlin as a polled snapshot; the container
  consumer's group-skip count exists in the Rust core and is not exported across the FFI boundary at
  all, and a transport-level stale-drop count is instrumented nowhere.

WebRTC's own central question — whether encoded frames are reachable through the Android
`VideoDecoderFactory` seam — is **answered on paper only and unverified on a device** (#338): the
receive path judges a decode by its return code with no watchdog on decoded output, which suggests
viability, and there is documented prior art for a pass-through decoder elsewhere in the WebRTC
ecosystem. MoQ has no equivalent open question. That asymmetry is why `PRD.md` Part 4 schedules MoQ
first, and why this record decides WHEP's shape no further than it must.

## Decision

Realtime transports deliver **encoded** frames through one SuperPlayer interface, and Media3 decodes
them exactly as it decodes everything else. Every rule says how it is enforced; the rules that
nothing enforces say so, and are summarised at the end rather than left to be counted.

**Rule 1 — Encoded samples reach Media3's own renderers; nothing in this library decodes.** A
`MediaSource`/`MediaPeriod` pair internal to `superplayer-realtime` writes frames into Media3
`SampleQueue`s, and Media3's `MediaCodec` renderers do the rest. No `Renderer` is written here, and
`EngineConfiguration.renderersFactory` is not filled by a realtime transport. Writing one would
rebuild surface handling, tunneling, frame-rate matching (ADR-0014), secure decoders, `AudioTrack`,
audio focus and dropped-frame telemetry, none of which is improved by being rewritten. *Enforced by
nothing mechanical; #343 pins the shape with a test, and a renderer added later is visible in review.*

**Rule 2 — A transport implements `FrameSource`, which names no Media3 type.** Subscribe, receive
encoded frames carrying a timestamp, a codec, a keyframe flag and the codec configuration the
fourcc says it carries (rule 4's #345 addendum), cancel. This is ADR-0016's `HttpTransport` shape taken again, for its reason and for one more: it is
the only boundary at which either transport is testable. Neither QUIC nor WebRTC runs under `check`,
and Robolectric cannot load an Android `.so` on the JVM, so the fake stands here and everything above
it is ordinary Robolectric work. *Enforced by `verifyNoUnstableMedia3InPublicApi` and the tracked API
surface, and by `FrameSourceConformance` (#347) for the obligations a signature cannot carry.*

*Addendum (2026-09-19, #356).* The seam is **`superplayer-core`'s**, not `superplayer-realtime`'s.
This rule named the shape without naming the module, and the sentence above settles it in the
direction the enforcement clause already required: `FrameSourceConformance` is to be public API of
`superplayer-testkit`, which is phase 2 and may name nothing later than core, while
`superplayer-realtime` is phase 13 — so a suite over a seam living in the realtime module is
unexpressible under `verifyModulePhaseRule`, and an exception to that rule would be the wrong price
for a package name. The precedent is the one this rule is already built on: `HttpTransport` is
core's and `HttpTransportConformance` is testkit's, for exactly this reason
([`docs/modules.md`](../modules.md)). So `FrameSource`, `FrameSink`, `RealtimeTrack`, `EncodedFrame`
and `FrameSourceFactory` are `com.superplayer.core`, beside the other two public types this phase
already put there (`RealtimeSources`, `RealtimeStreamNotSeekableException`), and they qualify for
core by the same test `HttpTransport` passes: they name no Media3 type at all. What stays in
`superplayer-realtime` is what cannot leave — `Realtime`, `RealtimeMediaSource` and
`RealtimeMediaPeriod`, which name Media3 `@UnstableApi` vocabulary and are why the module exists.
Nothing about the seam's shape, its nine obligations or any behaviour moves with it, and no build
script gains a dependency: testkit already declares `api(project(":superplayer-core"))`. *Enforced
by the tracked API surface of both modules, which is where a type moving back would show.*

*Addendum (2026-09-19, #346).* The seam carries **as many tracks as the publisher declared**, not
one. `FrameSink.onTrack` becomes `FrameSink.onTracks(List<RealtimeTrack>)` and `EncodedFrame` gains
a `trackIndex` naming a position in that list, defaulted to zero so a single-track transport is
unchanged in everything but the declaration. The method was *widened* rather than joined by a second
one, which this rule's own shape decides: preparation completes exactly once, with the whole
`TrackGroupArray` a player then selects from, so a track declared in a second call would arrive after
the player had already chosen what it was playing and there would be nowhere to put it. Audio alone
is a legitimate WHEP configuration and is one element of the same list, so nothing about this path
requires a video track. An empty list is refused, and a frame naming a track nobody declared is
reported rather than dropped. *Enforced by the tracked API surface for the shape, and by
`RealtimePlaybackTest` for the behaviour; `FrameSourceConformance` (#347) is where a transport is
held to the declaration.*

*Addendum (2026-09-20, #353).* A `RealtimeTrack` also carries the **coded size** of a video track,
as a `RealtimeTrack.CodedSize?` defaulting to null, and `FrameSource`'s obligation register gains a
**tenth** entry for it — unenforceable, like obligation 5, because no suite can know a stream's true
resolution, and written down for the same reason. This is a declaration and not
a policy: it is the publisher's own statement of what its samples are, `MediaCodec.configure`
**refuses a video format without it**, and no part of this design can derive it — rule 1 means
nothing here decodes, and rule 3 means the container that would otherwise carry it was stripped
before the seam. The cost of leaving it off was a failure of exactly the kind rule 4's KDoc
register exists to prevent, and it was found on a device rather than by any check: a session
connected, subscribed, delivered frames, and then died at the first keyframe with an
`IllegalArgumentException: Invalid size(s), width=-1, height=-1` and a `DecoderInitializationException`
naming the device's decoder — a message pointing at the one component that was not at fault. It is
the **coded** size rather than the display size, because that is what a decoder is configured with
and any aspect correction happens after decoding; a transport that genuinely does not know says
**null** rather than a zeroed size, since an absent size may still configure from an in-band
stream's own parameter sets while a zero is a positive claim about the picture. That distinction is
structural rather than documented: a `CodedSize` is two positive dimensions or it does not exist, so
half a size is unrepresentable and a nonsensical one is refused where it is built — which is where
a value narrowed from an unsigned field a transport never checked is caught, rather than at
`MediaCodec`. *Enforced by the
tracked API surface for the shape, by `CodedSize`'s own constructor for the values, by
`RealtimeFormatsTest` for the translation, and by `MoqCatalogTracksTest` for a transport that reads
it out of a catalog and for the refusal when what it read cannot be used.*

**Rule 3 — Container framing is the transport's to strip, and the seam carries codec bitstream
only.** A transport that receives containerised frames unwraps them before the seam. MoQ's bindings
already do this, which is why no fragmented-MP4 parsing appears anywhere in this design, and a
container discriminator must never reach a `MediaCodec` decision — where one exists it identifies
what to ask the transport for, and nothing else. *Enforced by `FrameSourceConformance` (#347), which
is where a transport handing over containerised bytes is caught.*

**Rule 4 — Codec-specific data is keyed on the codec's fourcc, never on the container.** `avc1` and
`hvc1` carry an out-of-band record, which is converted to Annex-B and becomes `csd-0`/`csd-1`;
`avc3` and `hev1` set no codec-specific data at all, because the stream is self-describing. The NAL
length field size is **read out of the record** rather than assumed to be four bytes. The HEVC half
of this rule is derived from source rather than observed (#340), and the first transport to carry
HEVC is expected to confirm it rather than to assume this rule already did. *Enforced by #345's tests
over literal byte arrays, using #340's observed bytes as fixtures.*

> **#345 addendum — the conversion is SuperPlayer's, and the seam names the shape.** The rule as
> first written left *who* converts unsaid, and the seam answered it by taking Annex-B units: the
> transport converted. That is now reversed. `RealtimeTrack` carries a `CodecConfiguration` of
> `InBand` or `Record(bytes)` — the record **exactly as received** — in place of a
> `codecSpecificData: List<ByteArray>`, so a transport hands over what its publisher sent and needs
> to know nothing about Annex-B. The reason is the one this record keeps returning to: every value
> of a byte list type-checked, so an `avcC` handed over where units were expected compiled and
> rendered nothing, while a *named* shape makes the contradiction a statement and lets a fourcc that
> disagrees with it be refused by name. That refusal is the new public
> `MalformedRealtimeBitstreamException`, an `IOException` for `UnsupportedRealtimeCodecException`'s
> reason and not a fourth exception shape.
>
> Two things the rule did not say and now does. The length field size read out of the record is
> **used**, on that track's samples, because a track that carries a record sends its samples
> length-prefixed and a size read but not applied is a number nobody uses; the record's own
> parameter-set lengths are 16-bit by its grammar, which is a different field. And every codec that
> is neither H.264 nor H.265 has its record **passed through unchanged**, which is the honest
> default rather than a claim that it is right for each of them — #346 owns getting that right per
> codec, with something observed behind it.

**Rule 5 — A realtime stream is live and unseekable, and says so rather than pretending.**
`StartPosition.At` and `ResumeFromLastKnown` are refused at adoption with a typed exception naming
the reason, in the idiom of `HttpStackUnsupportedException` and `LiveContentUnsupportedException`,
never coerced to `Beginning`. A silent coercion is a resume that looks like it worked. *Enforced by a
test in #343 asserting the refusal, not by a build rule.*

**Rule 6 — What this path does not reach is stated, not discovered.** The content cache, CMCD,
downloads, the fallback ladder's load-error rungs and bandwidth estimation are all keyed to
`DataSource`, and a realtime transport is not one. **ADR-0016's four chains do not become five** — a
realtime transport is not a fifth chain a consumer's `HttpStack` carries, and that ADR's promise is
unchanged rather than quietly weakened. Live content was already refused by downloads at enqueue
(ADR-0013 rule 8), so that row is existing behaviour rather than a new hole. What *does* survive is
`MediaRequest.sources` and ADR-0011's rung 4, so a realtime source falling back to an HLS source is
expressible with no new mechanism, and that is the documented way to have a fallback at all.
*Enforced by nothing mechanical — it is a statement about absence, and the adopter-facing half is
`docs/compatibility.md`'s, in the phase that ships a transport.*

**Rule 7 — Adaptive selection is *deferred*, not excluded, and the difference is deliberate.** An
earlier draft said `superplayer-abr` has nothing to select because the server decides the quality.
That is true of WHEP and **false of MoQ**, whose catalog is documented as listing *renditions* per
media kind, letting a transcoder publish a ladder, and letting a publisher flag a rendition stalled
so that players prefer another — a client-side ladder in all but name. No phase wires it: selection
here would be a subscription change at the transport rather than a track choice inside Media3, so it
reaches `PlaybackPolicy` through a seam that does not exist yet. The rule is therefore that a
realtime transport **exposes no selection to `superplayer-abr` today**, and that a phase adding one
amends this record rather than quietly widening it. What is *not* claimed is that the protocol cannot
bear it. *Enforced by nothing; it is a scope statement, and the amendment requirement is the control.*

**Rule 8 — Telemetry keeps what it can derive and declares the rest missing.** Playback state
transitions, dropped frames, video size and decoder events all survive, so rebuffer and startup
metrics work unchanged. `onLoadStarted` and `onLoadCompleted` do not exist on this path, so there are
**no bandwidth samples and `BandwidthOracle` observes nothing**. A transport may report its own
throughput through the seam where it has one — MoQ's connection statistics are reachable for exactly
this (#339) — but the **loss** half is not: MoQ's group-skip count stops at the FFI boundary and a
stale-drop count is instrumented nowhere, so a realtime session reports no upstream loss today and
says so rather than reporting zero. No metric's *meaning* changes, so `TelemetryEvent.SCHEMA_VERSION`
does not move (ADR-0008 rule 5). An upstream frame the transport skipped and a frame the renderer
dropped are different events and are never summed. *Enforced by ADR-0008 rule 5's existing review
discipline for the version; the rest is a statement about what is absent.*

**Rule 9 — A timestamp is the transport's, and its precision is the transport's limit.** The seam
carries the finest unit it can, and a transport that cannot fill it exactly says so in its own
documentation rather than implying precision it does not have. This is not hypothetical: WebRTC's
Android Java seam was found to expose no RTP timestamp and to truncate it to whole milliseconds
(#338, on paper), which no amount of work on this side recovers. *Enforced by
`FrameSourceConformance` (#347) for ordering and monotonicity; **precision itself is unenforceable**
and is a documentation obligation on the transport.*

*Addendum (2026-09-19, #346).* Monotonicity is promised **per track**; a **shared epoch across
tracks is not**, and this rule now says what SuperPlayer does about it rather than leaving it to be
discovered. One anchor for the whole period — the first frame delivered on any track — and every
track rebased by it, so a skew the publisher meant to send survives; rebasing each track on its own
first frame was rejected because it puts both first frames at zero and flattens lip sync to nothing,
silently. A track whose first frame lands more than a stated bound from that anchor is taken to be on
an epoch of its own and anchored at the point the period had reached when it arrived: two origins are
not hypothetical, since an RTP stream's initial timestamp is random per SSRC (RFC 3550 §5.1) and two
tracks of one session routinely start hours apart on the wire, and honouring such a difference either
strands a track before the read position where its frames are discarded or hours past it where the
buffer wedges. That recovery buys playback and not lip sync, which the seam's KDoc says in as many
words.

Two tracks also make **silence** a failure mode one track did not have, so a track that falls far
enough behind the others ends the session with the public `RealtimeTrackStalledException` rather than
buffering for ever — with a longer bound for a track that has never delivered, which is still
connecting, than for one that delivered and stopped. Both bounds are measured in **media time against
the furthest-ahead track and never against a clock**, which is what keeps all of a publisher's tracks
going quiet together an ordinary live edge (rule 5) rather than a failure, and what makes the bounds
assertable under `check` at all. A period's buffered position is correspondingly the **most
conservative** of its queues, since that is what `STATE_READY` is computed from; a track that has yet
to deliver is left out of that minimum, which is the late-start allowance the first bound closes.
*Enforced by `RealtimePlaybackTest`, which forces the reconciliation in both directions and each bound
in turn.*

**Rule 10 — Signalling is not the media path, and does not travel `HttpTransport`.** ADR-0016 rule
13's list of entry points that take a stack **does not gain a fifth entry**. Widening `HttpTransport`
to carry a `POST` with a body and a `DELETE` would change an interface that **consumers implement**,
so every existing implementation would stop compiling and every consumer's run of
`HttpTransportConformance` would be measuring a different contract — and ADR-0017 rule 2's arbiter
would score it a *minor*, because the tracked surface only gains declarations. That gap between what
the surface shows and what actually breaks is ADR-0017 rule 3's case, and it is the reason this is
refused rather than versioned. A transport that needs signalling owns it. Whether a narrower
signalling seam is worth having is phase 15's to decide, in the change that needs it. *Enforced by
ADR-0016 rule 13's own enumeration, which names four entry points and would have to be edited.*

**Rule 11 — `superplayer-realtime` is a module of its own, and core's tenth Kotlin friend.** One
consumer at first is ordinarily speculative generality; it is admitted here because this
repository's precedent is to fix module boundaries before code arrives — `docs/modules.md` opens by
saying every module exists even where it carries no code — and because the seam is required for
testability by MoQ alone, independently of whether WHEP ever ships. It is a **friend** of core
because it fills a `MediaSource.Factory` into `TransferChain`, which is core-internal and Media3
`@UnstableApi` vocabulary; it is the **tenth**, the first since ADR-0015 rule 3 took the ninth, and
`docs/modules.md`'s note that phase 10 added no tenth friendship is superseded by this rule rather
than contradicted. **`superplayer-moq` and `superplayer-whep` are not friends of core and must not
become ones**: they depend on `superplayer-realtime` and implement its public `FrameSource`, which is
the whole point of rule 2 and the test that the seam is real. *Enforced by
`verifyModulePhaseRule` for the dependency direction; the friend count itself is declared per module
by `declareKotlinFriendModule(...)` and is reviewed rather than counted by a task.*

*Addendum (2026-09-19, #356).* Rule 2's addendum moved the seam into core, so the two transport
modules implement **core's** `FrameSource` rather than this module's. Neither the friendship nor the
prohibition moves with it: `superplayer-realtime` is still core's tenth friend, because it is still
the module that fills a `MediaSource.Factory` into `TransferChain`, and `superplayer-moq` and
`superplayer-whep` are still not friends — they now name core for the seam they implement and this
module for `Realtime.transport` alone, which makes the prohibition easier to keep rather than
weaker.

**Rule 12 — A realtime URI is dispatched in `TransferChain`, on its scheme.** `TransferChain` is the
one place a `MediaSource.Factory` is chosen, which that file's KDoc already requires, and a realtime
URI reaches its factory there beside the HLS and DASH branches rather than through a second
composition point. The discriminator is the **URI scheme**, not a MIME type on the `MediaItem`:
Media3's content-type inference has no value for either protocol, a scheme is what a consumer
actually writes, and a MIME type would have to be supplied by the same consumer with no way to check
it. A `MediaItem` carrying a realtime scheme and a contradictory MIME type resolves on the scheme.
*Enforced by nothing mechanical; #343 covers the dispatch with a test, and `TransferChain`'s existing
KDoc is what keeps a second composition point out.*

**Rule 13 — A dependency carrying prebuilt native code is pinned exactly, states its ABIs, and has
its vendored licences identified.** A version range on a native artifact means two builds naming one
version can ship different machine code, and #341 found exactly that shape published upstream. The
ABIs it covers are recorded where the module is declared, because an absent ABI is a device that
cannot run the library rather than one that runs it slowly. **The licence obligation is the
load-bearing half**: `CONTRIBUTING.md` bars a dependency whose licence cannot be identified, and a
prebuilt binary's vendored tree is inside the artifact whether or not it is in the POM. #341 found
one weakly-copyleft codec compiled into the shipped library and a C++ runtime that will collide at
APK merge if another dependency ships it; the first is an open question a phase must close and the
second is a packaging fact its module carries. *Enforced by `THIRD_PARTY.md`'s existing
same-change rule and by review; **no task inspects a `.so`**, and that is the gap this rule names
rather than closes.*

### What nothing enforces

Rules 1, 5, 6, 7, 8, 9's precision half, 11's count, 12 and 13's licence half are not checked by
anything in `check`, and most never can be: they are statements about absence, about a transport's
own honesty, or about a binary's contents. Rules 2, 3, 4 and 9's ordering half are carried by
`FrameSourceConformance` and by tests, which is why that suite is a deliverable rather than a nicety.

## Consequences

**Easier.** Everything Media3 already does keeps working, because none of it is re-implemented: the
TV path, secure decoders, tunneling, audio focus and the decoder-side half of telemetry all apply to
a realtime stream with no new code. A second transport is cheap, which is the whole reason phase 13
precedes phases 14 and 15. And the seam makes both transports testable under `check` without a
device, which nothing else about them permits.

**Harder, and honestly.** Rule 6 is a real hole: an adopter who has built on the cache, on CMCD, on
downloads or on the fallback ladder gets none of them here, and the only mitigation offered is a
non-realtime source to fall back to. Rule 8 leaves the library with no throughput estimate of its own
on this path and no upstream loss signal at all, so an adaptive policy cannot be built over it
without either a transport that volunteers the numbers or an upstream contribution that exports
them. Rule 10 means WHEP signalling is written twice if a third protocol ever needs the same shape.

**The maintenance burden is the frame contract.** A seam that carries bytes and timestamps has
obligations that fail silently — a wrong length-field size produces a decoder that configures
cleanly and renders nothing. That is why rule 2's interface is paired with a conformance suite
(#347) rather than with KDoc alone, on the same argument ADR-0016 made for `HttpTransport`.

**One claim in an existing document weakens.** ADR-0016 says a consumer's client carries all four
chains. That stays true as written, and rule 6 keeps it true by refusing to call a realtime transport
a chain. A reader who expected "my HTTP client carries everything" will still be surprised, and
answering them is **the phase that ships the first transport**: `docs/compatibility.md` says nothing
about realtime today, and the ticket that adds a transport adds its row, exactly as ADR-0017's rules
named #327 to #330 rather than assuming a document already covered them.

## Alternatives considered

**A custom `Renderer` that owns decode (the original framing).** Rejected for MoQ outright, since its
bindings hand over encoded frames and there is nothing to intercept. Rejected as the default for
WebRTC because a `MediaSource`/`MediaPeriod` is required either way, so the renderer is pure addition
— and that addition costs the whole of ADR-0014's work, secure decoders and the audio path. It
remains the fallback if phase 15's device spike shows encoded frames are unreachable, and that
contingency is `PRD.md` Part 4's, not a rule here.

**WebRTC's insertable streams, and libwebrtc's own encoded-frame sink.** Both rejected on #338's
evidence rather than on preference: `FrameDecryptor`/`FrameEncryptor` are exposed to Android only as
native pointer handles with no byte access from Java, and libwebrtc's `RecordableEncodedFrame` sink
exists natively with no Android Java binding. Either would have been a cleaner route to encoded
frames than a pass-through decoder, and neither is reachable without patching the artifact — which is
why the pass-through decoder is what phase 15's device spike tests.

**A loopback HTTP server feeding Media3's fragmented-MP4 extractor.** The obvious way to reuse
Media3's demuxer for containerised frames, and barred by ADR-0002 before it was even attractive. It
is moot as well as barred: rule 3 records that no container framing survives to this side.

**Widening `HttpTransport` to carry WHEP signalling.** Rejected in rule 10. The credential side fitted
well enough to be tempting, which is precisely why the verb problem is written down — the next person
to notice how neatly the auth fits should find the reason it still does not work.

**Folding the seam into `superplayer-core`.** Rejected: a consumer of neither transport would carry
it, which several records here count as a real cost, and core depends on Media3 alone by design.

**One module per transport with no shared seam.** Genuinely attractive while WHEP is deferred, and
rejected on rule 11's grounds. Extracting a seam later is a minor release under ADR-0017; a module
coordinate published early and withdrawn is a breaking change.

**Dispatching a realtime URI on a MIME type rather than a scheme.** Rejected in rule 12. Media3's own
`MediaItem` carries a MIME type and it would have cost nothing to read, but it is supplied by the
same consumer who wrote the URI and nothing can check it against the transport that answers.

**Media3's own RTSP module as the vehicle.** Not rejected so much as inapplicable — it is cited as
the precedent for the shape in rule 1, but it is bound to RTP and SDP and neither transport speaks
them.

## References

- RFC 6381 — the `codecs` parameter and its fourcc-prefixed grammar, which is what rule 4 branches on.
- ISO/IEC 14496-15 §5.3.3.1.2 — the `AVCDecoderConfigurationRecord`, including the
  `lengthSizeMinusOne` field rule 4 requires be read rather than assumed. The
  `HEVCDecoderConfigurationRecord` is the same standard's HEVC clause rather than this subsection;
  rule 4 covers both and only the AVC half was observed (#340).
- ISO/IEC 14496-15 — the `avc1` versus `avc3` sample entry distinction, which is the standard's own
  mechanism for whether parameter sets may appear in the elementary stream, and therefore the source
  of rule 4's second branch.
- `draft-ietf-moq-transport`, and `moq-lite` as the forwards-compatible subset the implementation
  examined in #340 targets. The rendition catalog rule 7 relies on is that project's `hang` layer,
  documented at `doc.moq.dev`.
- `draft-ietf-wish-whep` and `draft-murillo-whep-01` — the two WHEP drafts. #337 found the CDN it
  examined implementing the latter, so a deployment implementing one is not automatically correct
  against the other, and phase 15 has to name which it targets.
- Issues #337–#341 carry the spike findings this record is written from, including their caveats.
- [ADR-0001](0001-compose-dont-fork.md) rule 2, [ADR-0002](0002-no-local-http-proxy.md),
  [ADR-0008](0008-measure-behind-an-engine-agnostic-sink-boundary.md) rule 5,
  [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md) rule 5,
  [ADR-0013](0013-download-into-the-cache-the-consumer-opened-on-the-one-chain.md) rule 8,
  [ADR-0014](0014-match-the-display-and-watch-it-change-behind-one-output-slot.md),
  [ADR-0015](0015-name-a-pathology-over-the-players-own-chain-and-redact-the-bundle-by-construction.md)
  rule 3 for the ninth friendship this record's tenth follows,
  [ADR-0016](0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md)
  rule 13, [ADR-0017](0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
  rules 2 and 3.
