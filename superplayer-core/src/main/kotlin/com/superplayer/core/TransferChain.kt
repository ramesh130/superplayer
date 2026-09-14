/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.core

import android.content.Context
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.util.ReleasableExecutor
import com.google.common.base.Supplier

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
 *   window depth     core, today: LiveWindowDepthCheck. Reads each DASH manifest the engine receives
 *                    and fails one whose live window no playhead can sit inside. Outermost for
 *                    revalidation's reason: what it judges is what reached the engine.
 *   revalidation     core, today: LivePlaylistRevalidation. Reads each live HLS playlist the engine
 *                    receives and, once one is overdue by RFC 8216's own bound, asks past the
 *                    caches for it. Outermost, because what it judges is what reached the engine,
 *                    whichever layer below answered; a local cache that held a live playlist would
 *                    be one more stale copy to it, and has to honour the same request directive.
 *   cache            superplayer-cache: a content-keyed CacheDataSource. Outermost of the layers
 *                    that answer a read, so a hit is answered without any layer below it running at all — which is the point of
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
 * That is no longer a promise: CMCD is emitted today, at that insertion point and at no other, from
 * the [CmcdMode] the profile or the consumer chose. `CmcdBinding.kt` is what turns the mode into
 * Media3's `CmcdConfiguration`, and it is the only file that names one.
 *
 * ## What is assembled today
 *
 * The transport, which is deliberately the same thing `ExoPlayer.Builder` would have installed by
 * itself: `DefaultDataSource.Factory(context)` is defined as `DefaultDataSource.Factory(context,
 * DefaultHttpDataSource.Factory())`, and `DefaultMediaSourceFactory(context)` as
 * `DefaultMediaSourceFactory(DefaultDataSource.Factory(context))` over a `DefaultExtractorsFactory`,
 * which is what the builder's default supplier constructs. The HTTP factory is named rather than
 * left implicit because it is the line ADR-0004 will replace, and a line that is not written down is
 * a line that has to be found first.
 *
 * Above it, one layer of SuperPlayer's own: [LivePlaylistRevalidation]. It changes no request of a
 * playlist that advances on time, so for healthy content the chain still behaves exactly as Media3's
 * default does; what it changes is the stream a cache has frozen, which Media3 alone ends with an
 * unclassified error. It is core's rather than `superplayer-resilience`'s for the reason CMCD is:
 * the defect is in the transfer, the fix has to be in the chain, and a consumer with only
 * `superplayer-core` is the one most likely to be behind a misconfigured CDN. `superplayer-resilience`'s
 * classifier, when it arrives, reads the [StaleLivePlaylistException] this raises rather than
 * re-deriving it.
 *
 * Above that, a second: [LiveWindowDepthCheck], which ends a live DASH stream whose
 * `@timeShiftBufferDepth` is no deeper than a segment takes to become available, with a
 * [LiveWindowTooShortException] — issue #67, where Media3 alone plays such a stream outside its
 * window and reports a negative position. It changes no request and no byte of any manifest it lets
 * through, and it is core's for the same reason. CMCD above all of it is configuration on the media
 * source factory rather than a link in the chain.
 *
 * A test's fake data source arrives as [mediaSourceFactory]'s `transport`, through the engine
 * configurator's [EngineConfiguration], and takes the HTTP stack's place and no other: the layers
 * above it are composed exactly as they are over a consumer's network, so a test plays through the
 * same chain a consumer's player does rather than through none of it.
 */
internal object TransferChain {

    /**
     * The media source factory a player built for [context] loads through, and the chain beneath it.
     *
     * Called once per engine, from `SuperPlayer.Builder.build()`, because `ExoPlayer` has no runtime
     * media-source-factory setter — only `setMediaSource` and `setMediaSources`. The whole loading
     * path is fixed when the engine is constructed, which is also why `player.exoPlayer` cannot
     * substitute for this seam.
     *
     * [cmcdMode] and [measurementSession] are the CMCD half: the mode this player emits under, and
     * the holder the `sid` is read from at prepare time. A disabled mode configures nothing at all.
     *
     * [transport] is what sits at the bottom of the chain in place of the HTTP stack — a test's fake
     * data source, and nothing in production, where it is null. [loadExecutor] is where the loads
     * over it run, for the same reason and with the same default: `EngineConfiguration` says why a
     * harness has to own that thread.
     */
    fun mediaSourceFactory(
        context: Context,
        cmcdMode: CmcdMode,
        measurementSession: MeasurementSession,
        transport: DataSource.Factory? = null,
        loadExecutor: Supplier<ReleasableExecutor>? = null,
    ): MediaSource.Factory =
        DefaultMediaSourceFactory(dataSourceChain(context, transport))
            .apply {
                cmcdMode.toCmcdConfigurationFactory(measurementSession)
                    ?.let(::setCmcdConfigurationFactory)
                loadExecutor?.let(::setDownloadExecutor)
            }

    /**
     * The chain itself — see the composition order above for what wraps what — over [transport], or
     * over the HTTP stack when there is none. One call is one player's chain: the layers hold
     * per-session state.
     */
    private fun dataSourceChain(context: Context, transport: DataSource.Factory?): DataSource.Factory =
        LiveWindowDepthCheck.over(
            LivePlaylistRevalidation(Clock.DEFAULT)
                .over(transport ?: DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())),
        )
}
