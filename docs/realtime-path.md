# What owns what on the realtime path

One table, for the question that is otherwise answered by reading three modules and an ADR: when a
`moq://` URI plays, **which layer is MoQ's, which is this library's, and which is Media3's**.

It is a map rather than a decision.
[`adr/0018-push-encoded-frames-through-one-framesource-into-media3s-own-sample-queues.md`](adr/0018-push-encoded-frames-through-one-framesource-into-media3s-own-sample-queues.md)
is what binds, and its rule numbers are cited per row; where this table and that record ever read
differently, **the record is right and this file is stale**.

The `moq.dev` column is the Rust implementation reached through the `moq-ffi` UniFFI bindings
(`third-party/moq/README.md` is how that artifact is built). The rows below the seam are shared with
every other realtime transport, so Phase 15's WHEP changes the first four rows and none of the rest
— which is the point of putting the seam where ADR-0018 rule 2 puts it.

| Concern | moq.dev (Rust, via `moq-ffi` UniFFI) | SuperPlayer | Media3 |
| --- | --- | --- | --- |
| UDP / QUIC / WebTransport | **all of it** — handshake, streams, congestion control | — | — |
| MoQ session control (SETUP, ANNOUNCE, SUBSCRIBE) | **all of it** — `MoqClient`, `MoqSession`, `MoqOriginConsumer` | `UniffiMoqRelay` — `suspend` → blocking, the address split, and `requestBroadcastWhenRoutable` | — |
| Signalling | the catalog **is** the signalling; no SDP, no offer/answer | does not travel `HttpTransport` (rule 10) | — |
| HTTP stack and the `DataSource` chain | — | **not reached at all** — no cache, no CMCD, no downloads, no load-error rungs, no bandwidth estimation (rule 6); ADR-0016's four chains do not become five | — |
| URI dispatch | — | `TransferChain`, on the **scheme** and never a MIME type (rule 12) | — |
| Catalog | delivered **typed** as `MoqCatalog`; no JSON is parsed on this side | `MoqCatalogTracks` → `RealtimeTrack`, video first then audio, one rendition per kind (rule 7) | — |
| Container framing (`.hang`) | **stripped below the FFI boundary** (rule 3) | — | — |
| Codec string | — | `RealtimeFormats`, RFC 6381 fourccs and SDP encoding names in one table, `internal` to `superplayer-realtime` | `Format.codecs`; profile and level extracted by Media3's own reader |
| Codec-specific data | the record is handed over **exactly as received** | `CodecConfigurationRecords`, keyed on the **fourcc** and never the container (rule 4): `avc1`/`hvc1` parse to Annex-B, `avc3`/`hev1` configure with nothing | consumed as `Format.initializationData` |
| Frame delivery seam | a blocking `next()` per track | `FrameSource`/`FrameSink`/`EncodedFrame`, **`superplayer-core`'s** and naming no Media3 type (rule 2); `MoqFrameSource` is one connect thread plus one pump per track | — |
| Sample queues and timeline | — | `RealtimeMediaSource`/`RealtimeMediaPeriod` — one epoch shared across tracks, one NAL length size per track | `SampleQueue`, `SinglePeriodTimeline`: live, unseekable, unknown duration |
| Track selection | — | eligibility is **deferred, not excluded** (rule 7); a phase that adds it amends the record | selects from the `TrackGroupArray` preparation published |
| **Decoding** | — | **nothing** (rule 1) | `MediaCodec`, through Media3's own renderers |
| **Rendering** | — | nothing | video to the `Surface`, audio to `AudioTrack` |
| Seeking | — | `StartPosition.At` and `ResumeFromLastKnown` **refused typed**, never coerced to the live edge (rule 5) | — |
| Bandwidth estimation | RTT and send/receive rate estimates in `stats()` | polled once a second into `MoqSessionStatistics`; **reaches no `TelemetrySink`** | `BandwidthOracle` observes nothing here, by construction |
| Upstream loss | **not exported** — the group-skip count stops at the FFI boundary, a stale-drop count is instrumented nowhere | `MoqUpstreamMediaLoss.NOT_INSTRUMENTED`, because a `Long` cannot say "nobody counted this" (rule 8) | — |
| Failure handling | `MoqException.Protocol` and its siblings | wrapped as an `IOException` and reported through `FrameSink.onError`; ladder rungs 1–3 are unreachable and **rung 4 survives** | surfaces to the consumer as a `PlaybackException` |
| Telemetry | — | rebuffer and startup metrics kept, bandwidth samples lost, no upstream loss; `SCHEMA_VERSION` unmoved at **2** | `AnalyticsListener` feeds `QoeCollector` |
| Lifecycle, audio focus, session, TV output | — | unchanged — the realtime path touches none of it | unchanged |

## What the table does not say

**Which rows are verified, and how.** Everything from the frame-delivery seam downward is exercised
under `./gradlew check` against fakes; everything above it is exercised by **one instrumented test
on a device** and by nothing else. `testing.md`'s *The first real MoQ session* is the carve-out and
says what that run proves, and `superplayer-moq`'s own `FrameSourceConformance` run proves the
bridge while proving nothing about MoQ.

**That the address split is specified.** It is not. No public document states where a single
`moq://` URI divides into a session URL and a broadcast name; the namespace-belongs-to-the-session
row above was settled empirically against one relay, and `UniffiMoqRelay` says so where it is
implemented.
