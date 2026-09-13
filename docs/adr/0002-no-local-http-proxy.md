# ADR-0002: No local HTTP proxy for startup latency

- **Status:** Accepted
- **Date:** 2026-09-05
- **Deciders:** SuperPlayer maintainers
- **Supersedes:** None
- **Summary:** Don't run a local HTTP proxy or rewrite manifests to fake a fast start — it corrupts
  bandwidth estimation and breaks ABR, DRM license binding, CMCD reporting, and CDN token auth.
  Pursue startup latency instead through Media3's own supported surfaces: caching, preload/decoder
  warming, and tuned adaptive-selection startup parameters.

## Context

A recurring technique for cutting time-to-first-frame on Android is to run a loopback HTTP server
inside the app, point the player at `http://127.0.0.1:<port>/…`, and have that server rewrite HLS
or DASH manifests so that segments already held in a local prefetch cache resolve to local URLs.
The player then "downloads" the first segments from localhost and renders a frame almost
immediately. It works, and reports of sub-100ms starts achieved this way are credible.

The technique has a documented second act. Because localhost round-trip time is effectively zero,
the player's bandwidth estimator — which measures throughput from the transfers it performs —
concludes that the available bandwidth is enormous, and adaptive bitrate selection immediately
requests the highest-quality track. The moment playback moves past the prefetched segments onto
the real network, that choice is catastrophically wrong. The usual remedy is to reimplement ABR
inside the proxy, where it can see request timings but cannot see what actually drives a good
decision.

This decision is recorded now, before any code exists that could drift toward the proxy design.
Startup latency will become urgent later; the position is far cheaper to hold as a written record
than to re-argue under that pressure.

## Decision

**SuperPlayer does not run a local HTTP proxy and does not rewrite manifests. The startup-latency
goals that motivate the proxy design are pursued at Media3's supported extension points instead.**

The failures this avoids, each of which the proxy architecture causes directly:

1. **Corrupted bandwidth estimation.** Transfers served from localhost complete at memory speed and
   enter the bandwidth meter's samples as genuine network throughput. The estimate no longer
   describes the network the player is about to use.
2. **Blinded ABR.** Once the estimate is corrupted, adaptive selection must be moved out of the
   player and into the proxy. There it is blind to the state that actually governs the decision:
   current buffer occupancy, the track currently selected and the cost of switching away from
   it, decoder and display capability, and the renderer's own view of playback health. A bitrate
   decision made without buffer level is a guess.
3. **Broken DRM license binding.** A Widevine license is issued against a request context — the
   content identity asserted in the license request, the authentication token carried with it, and
   the key IDs the initialization data names. Rewriting manifests rewrites the content the player
   believes it is playing, so the identity and key IDs the player derives from the rewritten
   manifest can no longer be relied on to match what the license was issued for. The proxy must
   then reproduce that context faithfully, in the one part of the stack where a subtle mismatch is
   hardest to diagnose and where the failure mode is a black screen rather than an error.
4. **Broken client-side reporting to the CDN.** Common Media Client Data (CTA-5004) works by having
   the player attach its own state — bitrate, buffer length, measured throughput, deadline, startup
   flag — to the requests the CDN receives. When the CDN receives requests from a proxy, the fields
   describe the proxy, and the CDN loses the client visibility the mechanism exists to provide.
   Server-side QoE analysis and CDN-side steering both degrade.
5. **Broken CDN token authentication.** Signed URLs and token-authenticated delivery bind a token
   to a URL, a path prefix, a client IP, or an expiry. Once the manifest is rewritten, keeping
   those tokens valid becomes the proxy's job: it must carry each signed URL through unmodified,
   re-sign on the client where the signing key must not be, or refresh tokens that expire while
   prefetched content sits in the cache. Each is a way to get it wrong, and the usual outcome is
   either authentication failures or a token scheme weakened until it stops failing.

The proxy also carries costs unrelated to correctness: an extra process or thread and a listening
socket to keep alive across the app lifecycle, TLS termination inside the app, and a new network
surface that must pass security review.

**Where the goals are pursued instead.** The objectives — prefetching the first segments of likely-
next content, warming decoders before playback starts, and starting at a low-bitrate track then moving
up quickly — are all reachable through supported Media3 surfaces, and are scheduled for the
preload and ABR phases:

- Cache hits are served without network I/O by `CacheDataSource`, which requires no manifest
  rewriting because the cache sits below the data-source layer where the player already expects it.
- Off-screen items are held prepared, and decoders warmed, through Media3's preload manager and
  preloading media source, bounded by the device's reported concurrent decoder limits rather than a
  constant.
- Cache-served reads are excluded from bandwidth throughput samples, so the estimate keeps
  describing the network. This is the specific defect the proxy design creates and then works
  around.
- Low-quality starts that escalate quickly are configured through adaptive track selection's
  existing startup and quality-increase parameters, tuned per playback profile, rather than
  hand-rolled as a two-stage switch.

## Consequences

**Easier.** Bandwidth estimation stays truthful, so ABR stays inside the track selector where
buffer occupancy and codec capability are visible. DRM, CMCD reporting, and token-authenticated
delivery keep working with no special cases. There is no listening socket, no in-app TLS
termination, and no additional attack surface to justify in review.

**Harder.** Startup latency must be earned through prefetch, cache warming, and selection tuning,
which is more incremental work than pointing the player at a local server. Some of it is bounded by
what Media3's preload surfaces support, and per ADR-0001 the response to a missing capability is an
upstream contribution or a documented limitation, not a workaround at a different layer.

**Constraint on future work.** A future proposal for a loopback proxy, a manifest rewriter, or any
interposition between the player and the origin must argue against this record and must say how it
addresses each of the five failures above. Startup latency being urgent is not by itself an
argument.

**Measurement obligation.** Because the alternative path is incremental, the startup work is only
credible with numbers. Time-to-first-frame is measured by the project's own harness against public
test streams under shaped network conditions, and improvements are reported against that baseline.

## Alternatives considered

**Loopback proxy with manifest rewriting, ABR left in the player.** Rejected. This is the
architecture in its naive form, and it is unshippable: the corrupted bandwidth estimate makes the
player request its highest-bitrate track immediately.

**Loopback proxy with ABR reimplemented in the proxy.** Rejected. It resolves the estimation problem
by moving the decision to the one place that cannot see buffer occupancy, track-switch cost, or
decoder capability — and it leaves the DRM, CMCD, and token-authentication failures entirely
unaddressed.

**Proxy used only for non-DRM, non-token-authenticated content.** Rejected. It splits the playback
path in two, so the fast path is the one least exercised by production content and the two paths
drift. The bandwidth-estimation defect is present on both.

## References

- CTA-5004, Common Media Client Data (CMCD): https://cdn.cta.tech/cta/media/media/resources/standards/pdfs/cta-5004-final.pdf
- CTA-2066, Streaming Quality of Experience Events, Properties and Metrics
- RFC 8216, HTTP Live Streaming: https://www.rfc-editor.org/rfc/rfc8216
- ISO/IEC 23009-1, Dynamic adaptive streaming over HTTP (DASH)
- Widevine DRM public integration documentation: https://developers.google.com/widevine/drm/overview
- AndroidX Media3 preloading and caching documentation: https://developer.android.com/media/media3
