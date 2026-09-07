package com.superplayer.core

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * The one place the `DataSource.Factory` an engine loads through is assembled.
 *
 * Everything below `MediaSource` — manifests, playlists, initialisation segments, media segments,
 * DRM licence requests — reaches the network through one chain of `DataSource.Factory` wrappers, and
 * four later phases each want a layer of it. Composing it here rather than letting each phase reach
 * for `ExoPlayer.Builder` is what keeps the order a decision instead of an accident of which module
 * was written last. `PRD.md` §2.4 is the requirement; ADR-0002 is why the order matters.
 *
 * Nothing here is visible to a consumer. `DataSource`, `DataSource.Factory`, `DefaultDataSource` and
 * `DefaultHttpDataSource` all carry Media3's `@UnstableApi`, so ADR-0001 rule 2 and
 * `verifyNoUnstableMedia3InPublicApi` keep every one of them behind the facade — which is why this
 * type is `internal` and returns a Media3 type rather than a SuperPlayer one.
 *
 * ## The composition order
 *
 * Written outermost-first, as the engine sees it. A read enters at the top and falls through until
 * something can answer it:
 *
 * ```
 *   cache            superplayer-cache: a content-keyed CacheDataSource. Outermost, so a hit
 *                    short-circuits everything below it — including measurement.
 *   CMCD             superplayer-telemetry: CTA-5004 keys on the requests that actually leave the
 *                    device, which is why it sits below the cache rather than above it.
 *   measurement      superplayer-abr: the BandwidthOracle's TransferListener. Below the cache on
 *                    purpose — see below.
 *   header refresh   superplayer-resilience: the HeaderProvider re-invoked on 401/403, closest to
 *                    the transport so that a token refresh and its retry are one transfer to
 *                    everything above.
 *   transport        DefaultDataSource over DefaultHttpDataSource — file:, asset:, content:,
 *                    rawresource: and data: locally, HTTP and HTTPS remotely. Which HTTP stack
 *                    sits here is a public question ADR-0004 leaves open; it plugs in at this
 *                    line and nowhere else.
 * ```
 *
 * **Measurement goes inside the cache, not outside it.** A cache hit is served from disk in
 * microseconds; counted as a throughput sample it tells `DefaultBandwidthMeter` the network is
 * faster than any network is, and phase 3's ABR then selects a bitrate the connection cannot carry.
 * That is the same corruption ADR-0002 rejected the loopback proxy for — measurement describing
 * something other than the network — arriving through a different door, so the answer is the same
 * one. `PRD.md` §3.1 states it as a requirement of `BandwidthOracle`: cache-hit reads are not
 * throughput samples.
 *
 * **Every layer added here must forward `TransferListener` registration downstream.** Measurement is
 * not a wrapper in the chain; it is a listener the engine hands to the media source at prepare time,
 * which propagates down through each `DataSource` that implements
 * `DataSource.addTransferListener`. A wrapper that accepts the registration and does not pass it on
 * silently blinds bandwidth estimation: playback still works, the estimate stays at its default, and
 * nothing appears in the logs. There is no test that will catch this for you by accident, so a layer
 * arrives with one of its own — the rule is written into ADR-0004 rule 5 for the same reason.
 *
 * ## What is assembled today
 *
 * Only the transport, and deliberately the same one `ExoPlayer.Builder` would have installed by
 * itself: `DefaultDataSource.Factory(context)` is defined as `DefaultDataSource.Factory(context,
 * DefaultHttpDataSource.Factory())`, and `DefaultMediaSourceFactory(context)` is defined as
 * `DefaultMediaSourceFactory(DefaultDataSource.Factory(context))`, so routing through here changes
 * what a consumer gets in no way at all. The HTTP factory is named rather than left implicit
 * because it is the line ADR-0004 will replace, and a line that is not written down is a line that
 * has to be found first.
 */
internal object TransferChain {

    /**
     * The chain a player built for [context] loads through.
     *
     * Called once per engine, from `SuperPlayer.Builder.build()`, because `ExoPlayer` has no runtime
     * media-source-factory setter — only `setMediaSource` and `setMediaSources`. The chain is fixed
     * when the engine is constructed, which is also why `player.exoPlayer` cannot substitute for
     * this seam.
     */
    fun assemble(context: Context): DataSource.Factory =
        DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())
}
