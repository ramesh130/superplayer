# ADR-0018: Push encoded frames through one `FrameSource` into Media3's own sample queues

- **Status:** Accepted
- **Date:** 2026-09-19
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None

## Context

Two realtime transports are in scope for phases 13–15: Media over QUIC (MoQ) and WebRTC over WHEP.
Both are sub-second, push-based and live-only, and neither resembles the pull-based, seekable,
`DataSource`-fed pipeline every phase before this one was built on.

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

Phase 12's spikes settled the rest, and three findings contradicted the framing they were written
against. They are recorded here because each one removed work that had been planned.

- **MoQ's Kotlin bindings hand over encoded frames with container framing already stripped.** The
  UniFFI layer wraps the track in a container consumer before the frame is exposed, and the payload
  is byte-identical across all three `MoqContainer` variants for a given codec. No `moof` ever
  reaches a consumer, so the planned fragmented-MP4 demux does not exist as work.
- **Whether codec-specific data is present is decided by the codec's fourcc, not by the container.**
  `avc1`/`hvc1` carry an out-of-band `avcC`/`hvcC` record; `avc3`/`hev1` carry none and put parameter
  sets in-band as Annex-B before every keyframe. Both branches are real; they are keyed to a
  different field than was assumed.
- **WHEP signalling does not fit the HTTP seam this library already has**, and the obstruction is the
  verb rather than the credential. WHEP is a `POST` carrying an SDP body, answered `201` with a
  `Location`, torn down by `DELETE`; `HttpTransport` models a `GET` with an optional byte range. The
  credential side fits well — a CDN's default playback needs no auth, and its signed mode puts a
  token in the URL path.

WebRTC's own central question — whether encoded frames are reachable through the Android
`VideoDecoderFactory` seam at all — is answered only on paper and is unverified on a device. MoQ has
no equivalent open question. That asymmetry is why `PRD.md` Part 4 schedules MoQ first.

## Decision

Realtime transports deliver **encoded** frames through one SuperPlayer interface, and Media3 decodes
them exactly as it decodes everything else. The rules follow.

**Rule 1 — Encoded samples reach Media3's own renderers; nothing in this library decodes.** A
`MediaSource`/`MediaPeriod` pair internal to `superplayer-realtime` writes frames into Media3
`SampleQueue`s, and Media3's `MediaCodec` renderers do the rest. No `Renderer` is written here, and
`EngineConfiguration.renderersFactory` is not filled by a realtime transport. Writing one would
rebuild surface handling, tunneling, frame-rate matching (ADR-0014), secure decoders, `AudioTrack`,
audio focus and dropped-frame telemetry, none of which is improved by being rewritten. *Enforced by
nothing mechanical* — a renderer added later would be visible in review, and #343 pins the shape with
a test.

**Rule 2 — A transport implements `FrameSource`, which names no Media3 type.** Subscribe, receive
encoded frames carrying a timestamp, a codec, a keyframe flag and optional codec-specific data,
cancel. This is ADR-0016's `HttpTransport` shape taken again, for its reason and for one more: it is
the only boundary at which either transport is testable. Neither QUIC nor WebRTC runs under `check`,
and Robolectric cannot load an Android `.so` on the JVM, so the fake stands here and everything above
it is ordinary Robolectric work. *Enforced by* `verifyNoUnstableMedia3InPublicApi` and the tracked
API surface.

**Rule 3 — Container framing is the transport's to strip, and the seam carries codec bitstream
only.** A transport that receives containerised frames unwraps them before the seam. MoQ's bindings
already do this, which is why no fragmented-MP4 parsing appears anywhere in this design and why a
container discriminator must never reach a `MediaCodec` decision — where one exists it identifies
what to ask the transport for, and nothing else.

**Rule 4 — Codec-specific data is keyed on the codec's fourcc, never on the container.** `avc1` and
`hvc1` carry an out-of-band record, which is converted to Annex-B and becomes `csd-0`/`csd-1`;
`avc3` and `hev1` set no codec-specific data at all, because the stream is self-describing. The NAL
length field size is **read out of the record** rather than assumed to be four bytes. *Enforced by*
#345's tests over literal byte arrays.

**Rule 5 — A realtime stream is live and unseekable, and says so rather than pretending.**
`StartPosition.At` and `ResumeFromLastKnown` are refused at adoption with a typed exception naming
the reason, in the idiom of `HttpStackUnsupportedException` and `LiveContentUnsupportedException`,
never coerced to `Beginning`. A silent coercion is a resume that looks like it worked.

**Rule 6 — What this path does not reach is stated, not discovered.** The content cache, CMCD,
downloads, the fallback ladder's load-error rungs and bandwidth estimation are all keyed to
`DataSource`, and a realtime transport is not one. **ADR-0016's four chains do not become five** — a realtime
transport is not a fifth chain a consumer's `HttpStack` carries, and that ADR's promise is unchanged
rather than quietly weakened. What *does* survive is `MediaRequest.sources` and ADR-0011's rung 4, so
a realtime source falling back to an HLS source is expressible with no new mechanism, and that is the
documented way to have a fallback at all.

**Rule 6a — adaptive selection is *deferred*, not excluded, and the difference is deliberate.** An
earlier draft of rule 6 said `superplayer-abr` has nothing to select because the server decides the
quality. That is true of WHEP and **false of MoQ**, whose catalog lists *renditions* per media kind,
lets a transcoder publish a ladder, and lets a publisher flag a rendition stalled so that players
prefer another — which is a client-side ladder in all but name. No phase wires it: selection here
would be a subscription change at the transport rather than a track choice inside Media3, so it
reaches `PlaybackPolicy` through a seam that does not exist yet. The rule is therefore that a
realtime transport **exposes no selection to `superplayer-abr` today**, and that a phase adding one
amends this record rather than quietly widening it. What is *not* claimed is that the protocol
cannot bear it.

**Rule 7 — Telemetry keeps what it can derive and declares the rest missing.** Playback state
transitions, dropped frames, video size and decoder events all survive, so rebuffer and startup
metrics work unchanged. `onLoadStarted` and `onLoadCompleted` do not exist on this path, so there are
**no bandwidth samples and `BandwidthOracle` observes nothing**. A transport may report its own
throughput through the seam where it has one, and MoQ's session statistics are reachable for exactly
this. No metric's *meaning* changes, so `TelemetryEvent.SCHEMA_VERSION` does not move (ADR-0008
rule 5). An upstream frame the transport skipped and a frame the renderer dropped are different
events and are never summed.

**Rule 8 — A timestamp is the transport's, and its precision is the transport's limit.** The seam
carries the finest unit it can, and a transport that cannot fill it exactly says so in its own
documentation rather than implying precision it does not have. This is not hypothetical: WebRTC's
Android Java seam exposes no RTP timestamp and truncates it to whole milliseconds, which no amount of
work on this side recovers.

**Rule 9 — Signalling is not the media path, and does not travel `HttpTransport`.** ADR-0016 rule
13's list of entry points that take a stack **does not gain a fifth entry**. Widening `HttpTransport`
to carry a `POST` with a body and a `DELETE` would be an ABI break under ADR-0017 and would falsify
`HttpTransportConformance` for every consumer who already implements it, to serve one protocol. A
transport that needs signalling owns it. Whether a narrower signalling seam is worth having is
phase 15's to decide, in the change that needs it.

**Rule 10 — `superplayer-realtime` is a module of its own, with one consumer at first.** That is
ordinarily speculative generality. It is admitted here because this repository's own precedent is to
fix module boundaries before code arrives — `docs/modules.md` opens by saying every module exists
even where it carries no code, so that boundaries are enforceable from the start — and because the
seam is required for testability by MoQ alone, independently of whether WHEP ever ships.

**Rule 11 — A dependency carrying prebuilt native code is pinned exactly and states its ABIs.** A
version range on a native artifact means two builds naming one version can ship different machine
code. The ABIs it covers are recorded where the module is declared, because an absent ABI is a device
that cannot run the library at all rather than one that runs it slowly.

## Consequences

**Easier.** Everything Media3 already does keeps working, because none of it is re-implemented: the
TV path, secure decoders, tunneling, audio focus and the decoder-side half of telemetry all apply to
a realtime stream with no new code. A second transport is cheap, which is the whole reason phase 13
precedes phases 14 and 15. And the seam makes both transports testable under `check` without a
device, which nothing else about them permits.

**Harder, and honestly.** Rule 6 is a real hole: an adopter who has built on the cache, on CMCD, on
downloads or on the fallback ladder gets none of them here, and the only mitigation offered is a
non-realtime source to fall back to. Rule 7 leaves the library with no throughput estimate of its own
on this path, so an adaptive policy cannot be built over it without a transport that volunteers the
number. Rule 9 means WHEP signalling is written twice if a third protocol ever needs the same shape.

**The maintenance burden is the frame contract.** A seam that carries bytes and timestamps has
obligations that fail silently — a wrong length-field size produces a decoder that configures
cleanly and renders nothing. That is why rule 2's interface is paired with a conformance suite
(#347) rather than with KDoc alone, on the same argument ADR-0016 made for `HttpTransport`.

**One claim in an existing document weakens.** ADR-0016 says a consumer's client carries all four
chains. That stays true as written, and rule 6 keeps it true by refusing to call a realtime transport
a chain. A reader who expected "my HTTP client carries everything" will still be surprised, and
`docs/compatibility.md` is where that is their answer.

## Alternatives considered

**A custom `Renderer` that owns decode (the original framing).** Rejected for MoQ outright, since its
bindings hand over encoded frames and there is nothing to intercept. Rejected as the default for
WebRTC because a `MediaSource`/`MediaPeriod` is required either way, so the renderer is pure addition
— and that addition costs the whole of ADR-0014's work, secure decoders and the audio path. It
remains the fallback if phase 15's device spike shows encoded frames are unreachable, and that
contingency is `PRD.md` Part 4's, not a rule here.

**A loopback HTTP server feeding Media3's fragmented-MP4 extractor.** The obvious way to reuse
Media3's demuxer for containerised frames, and barred by ADR-0002 before it was even attractive. It
is moot as well as barred: rule 3 records that no container framing survives to this side.

**Widening `HttpTransport` to carry WHEP signalling.** Rejected in rule 9. The credential side fitted
well enough to be tempting, which is precisely why the verb problem is written down — the next person
to notice how neatly the auth fits should find the reason it still does not work.

**Folding the seam into `superplayer-core`.** Rejected: a consumer of neither transport would carry
it, which several records here count as a real cost, and core depends on Media3 alone by design.

**One module per transport with no shared seam.** Genuinely attractive while WHEP is deferred, and
rejected on rule 10's grounds. Extracting a seam later is a minor release under ADR-0017; a module
coordinate published early and withdrawn is a breaking change.

**Media3's own RTSP module as the vehicle.** Not rejected so much as inapplicable — it is cited as
the precedent for the shape in rule 1, but it is bound to RTP and SDP and neither transport speaks
them.

## References

- RFC 6381 — the `codecs` parameter, which is the grammar rule 4's fourcc is read from.
- ISO/IEC 14496-15 §5.3.3.1.2 — the `avcC`/`hvcC` record layout, including the length-field size that
  rule 4 requires be read rather than assumed.
- RFC 6184 — H.264 over RTP, for the Annex-B in-band parameter-set convention rule 4's second branch
  relies on.
- `draft-ietf-moq-transport`, and `moq-lite` as the forwards-compatible subset MoQ's implementation
  targets.
- `draft-ietf-wish-whep` and `draft-murillo-whep-01` — the two WHEP drafts, which differ in ways
  rule 9's phase will have to name; a deployment implementing one is not automatically correct
  against the other.
- [ADR-0001](0001-compose-dont-fork.md) rule 2, [ADR-0002](0002-no-local-http-proxy.md),
  [ADR-0008](0008-measure-behind-an-engine-agnostic-sink-boundary.md) rule 5,
  [ADR-0011](0011-classify-every-failure-once-and-keep-the-rungs-behind-the-boundary.md) rule 5,
  [ADR-0014](0014-match-the-display-and-watch-it-change-behind-one-output-slot.md),
  [ADR-0016](0016-take-the-consumers-transport-through-one-superplayer-interface-and-adapt-it-to-media3.md)
  rule 13, [ADR-0017](0017-version-the-library-by-semver-in-lockstep-with-the-tracked-api-surface-as-the-arbiter.md)
  rules 2 and 9.
