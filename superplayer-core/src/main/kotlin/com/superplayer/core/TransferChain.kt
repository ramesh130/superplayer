package com.superplayer.core

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource

/**
 * The one place the loading path below `MediaSource` is assembled.
 *
 * Everything an engine fetches — manifests, playlists, initialisation and media segments, DRM
 * licence requests — travels a chain of `DataSource.Factory` wrappers, and four later phases each
 * want a piece of it, with a fifth (`superplayer-offline`) sharing the cache layer's key policy.
 * Composing it here rather than letting each phase reach for `ExoPlayer.Builder` is what keeps the
 * order a decision instead of an accident of which module was written last. `PRD.md` §2.4 is the
 * requirement; ADR-0002 is why getting it wrong is expensive.
 *
 * Nothing here is visible to a consumer. `DataSource`, `DataSource.Factory`, `DefaultDataSource` and
 * `DefaultHttpDataSource` all carry Media3's `@UnstableApi`, so ADR-0001 rule 2 and
 * `verifyNoUnstableMedia3InPublicApi` keep every one of them behind the facade — which is why this
 * type is `internal` and deals in Media3 types rather than SuperPlayer ones.
 *
 * ## The composition order
 *
 * Written outermost-first, as the engine sees it. A read enters at the top and falls through until
 * something can answer it:
 *
 * ```
 *   cache            superplayer-cache: a content-keyed CacheDataSource. Outermost, so a hit is
 *                    answered without any layer below it running at all — which is the point of
 *                    a cache, and also what makes its position the one that must not drift.
 *                    superplayer-offline shares this layer and its key policy (PRD.md §3.5).
 *   header refresh   superplayer-resilience: the HeaderProvider re-invoked on 401/403, closest to
 *                    the transport so that a token refresh and the retry it triggers are a single
 *                    transfer to everything above.
 *   transport        DefaultDataSource over DefaultHttpDataSource — file:, asset:, content:,
 *                    rawresource: and data: locally, HTTP and HTTPS remotely. Which HTTP stack
 *                    sits here is a public question ADR-0004 leaves open; it plugs in at this
 *                    line and nowhere else.
 * ```
 *
 * Two of the four phases are missing from that list, and their absence is the useful part of this
 * comment rather than an omission.
 *
 * ## Measurement is not a layer
 *
 * `superplayer-abr`'s `BandwidthOracle` reads transfers, but it does not wrap anything: measurement
 * arrives as a `TransferListener` the engine hands the media source at prepare time, which
 * propagates *down* the chain through each `DataSource.addTransferListener`. Two consequences, and
 * both are traps:
 *
 * **Every layer added above must forward that registration downstream.** A wrapper that accepts the
 * listener and does not pass it on silently blinds bandwidth estimation — playback still works, the
 * estimate sits at its default, and nothing appears in the logs. ADR-0004 rule 5 states this for
 * HTTP stacks; it binds every layer here for the same reason.
 *
 * **Chain position alone does not keep cache hits out of the estimate.** It is tempting to assume
 * that putting measurement "below" the cache does it, and it does not:
 * `CacheDataSource.addTransferListener` forwards to its cache-read source *and* its upstream
 * (checked against `media3-datasource` 1.11.0), so a hit served from disk is reported like any other
 * transfer. What excludes it is the `isNetwork` flag each source carries — `DefaultBandwidthMeter`
 * drops any transfer reporting `false`, and the cache-read source is a file source. So a
 * `BandwidthOracle` that samples every transfer it is handed, rather than honouring that flag, tells
 * ABR the network runs at disk speed and selects a bitrate the connection cannot carry. That is
 * exactly the corruption ADR-0002 rejected the loopback proxy for, arriving through a different
 * door. `PRD.md` §3.1 states the requirement — cache-hit reads are not throughput samples — and this
 * is the mechanism that has to satisfy it.
 *
 * ## CMCD is not a layer either, which is why this owns the `MediaSource.Factory` too
 *
 * CTA-5004 keys are attached to the `DataSpec` by the DASH and HLS chunk sources, configured through
 * `DefaultMediaSourceFactory.setCmcdConfigurationFactory` — there is no `DataSource.Factory` wrapper
 * that can emit them. Had this object returned only the chain and left the `MediaSource.Factory` to
 * its caller, `superplayer-telemetry` would have had to reach the engine builder to insert itself,
 * which is precisely the thing the seam exists to prevent. So [mediaSourceFactory] is the seam and
 * the chain underneath it is private to this file.
 *
 * ## What is assembled today
 *
 * Only the transport, and deliberately the same thing `ExoPlayer.Builder` would have installed by
 * itself: `DefaultDataSource.Factory(context)` is defined as `DefaultDataSource.Factory(context,
 * DefaultHttpDataSource.Factory())`, and `DefaultMediaSourceFactory(context)` as
 * `DefaultMediaSourceFactory(DefaultDataSource.Factory(context))` over a `DefaultExtractorsFactory`,
 * which is what the builder's default supplier constructs. Routing through here changes what a
 * consumer gets in no way at all. The HTTP factory is named rather than left implicit because it is
 * the line ADR-0004 will replace, and a line that is not written down is a line that has to be found
 * first.
 */
internal object TransferChain {

    /**
     * The media source factory a player built for [context] loads through, and the chain beneath it.
     *
     * Called once per engine, from `SuperPlayer.Builder.build()`, because `ExoPlayer` has no runtime
     * media-source-factory setter — only `setMediaSource` and `setMediaSources`. The whole loading
     * path is fixed when the engine is constructed, which is also why `player.exoPlayer` cannot
     * substitute for this seam.
     */
    fun mediaSourceFactory(context: Context): MediaSource.Factory =
        DefaultMediaSourceFactory(dataSourceChain(context))

    /** The chain itself — see the composition order above for what will wrap what. */
    private fun dataSourceChain(context: Context): DataSource.Factory =
        DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())
}
