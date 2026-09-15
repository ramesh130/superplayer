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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsDataSourceFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Supplier
import java.util.concurrent.CopyOnWriteArrayList

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
 *                    and fails one whose live window no playhead can sit inside. Outermost, above
 *                    revalidation and for its reason: what it judges is what reached the engine. It
 *                    changes no request, so its order against revalidation is otherwise free.
 *   revalidation     core, today: LivePlaylistRevalidation. Reads each live HLS playlist the engine
 *                    receives and, once one is overdue by RFC 8216's own bound, asks past the
 *                    caches for it. Outermost of the layers that change a request, because what it
 *                    judges is what reached the engine,
 *                    whichever layer below answered; a local cache that held a live playlist would
 *                    be one more stale copy to it, and has to honour the same request directive.
 *   cache            built, and empty unless the player was given a ContentCache: then that cache's
 *                    CacheLayer, which superplayer-cache fills with a content-keyed CacheDataSource.
 *                    Outermost of the layers that answer a read, so a hit is answered without any
 *                    layer below it running at all — which is the point of a cache, and also what
 *                    makes its position the one that must not drift. superplayer-offline shares this
 *                    layer and its key policy (PRD.md §3.5).
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
 * ## The cache slot, and how content identity reaches its key
 *
 * The slot is built. A player given a [ContentCache] through `SuperPlayer.Builder.setCache` has that
 * cache's [CacheLayer] composed into it, below revalidation and above the transport; a player given
 * none has nothing there, and its chain and media source factory are exactly the ones Phase 3 built
 * (ADR-0010 rule 13). Nothing in core opens a cache — `superplayer-cache` does, in storage the
 * consumer named (ADR-0010 rules 1 and 2).
 *
 * A cache keys by content, not URL (ADR-0010 rule 4), and a layer in a chain shared by every item
 * cannot tell which item a request is for. So on a player with a cache, an item adopted from a
 * [MediaRequest] carries a [ContentIdentity], and [mediaSourceFactory] builds each item's source
 * over the chain with that identity stamped onto every request it opens — above every layer, so the
 * slot sees it whichever layer passed the request on. An item set through `setMediaItem` has none,
 * and its requests reach the slot with none. [ContentIdentity] says why it travels with the request
 * rather than in a holder beside the chain.
 *
 * The same stamp says what kind of load a request is ([LoadKind]), on every item a cached player
 * plays, identified or not: a cache answers media and never a manifest, because a stored live
 * playlist is exactly the stale copy revalidation exists to get past. Only the media source knows the
 * kind — HLS asks for a data source per data type, DASH takes a manifest factory beside its chunk
 * factory — so an HLS or DASH item's source is built per protocol with a stamped chain for each kind,
 * rather than by `DefaultMediaSourceFactory` over one chain.
 *
 * Preload builds its sources from the same factory, handed to it as a pool's `SharedComponents`
 * (`PooledEngine.kt`), so a source warmed ahead of the viewport loads through this chain under the
 * same identity (ADR-0010 rule 6).
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
     *
     * [cache] fills the cache slot and turns on content identity; null leaves both off.
     */
    fun mediaSourceFactory(
        context: Context,
        cmcdMode: CmcdMode,
        measurementSession: MeasurementSession,
        transport: DataSource.Factory? = null,
        loadExecutor: Supplier<ReleasableExecutor>? = null,
        cache: ContentCache? = null,
    ): MediaSource.Factory {
        val chain = dataSourceChain(context, transport, cache)
        // Without a cache, exactly the factory Phase 3 built: nothing about a request is stamped,
        // because nothing below would read it (ADR-0010 rule 13).
        val factory = if (cache == null) DefaultMediaSourceFactory(chain) else ContentKeyedMediaSourceFactory(chain)
        return factory.apply {
            cmcdMode.toCmcdConfigurationFactory(measurementSession)
                ?.let(::setCmcdConfigurationFactory)
            loadExecutor?.let(::setDownloadExecutor)
        }
    }

    /**
     * The chain itself — see the composition order above for what wraps what — over [transport], or
     * over the HTTP stack when there is none, with [cache]'s layer in the cache slot when there is
     * one. One call is one player's chain: the layers hold per-session state.
     */
    private fun dataSourceChain(
        context: Context,
        transport: DataSource.Factory?,
        cache: ContentCache?,
    ): DataSource.Factory {
        val bottom = transport ?: DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())
        return LiveWindowDepthCheck.over(
            LivePlaylistRevalidation(Clock.DEFAULT)
                .over(cache?.layer?.over(bottom) ?: bottom),
        )
    }

    /**
     * Media3's own media source factory, built per item over [chain] stamped with that item's
     * [ContentIdentity] — the way content identity reaches the cache slot's key.
     *
     * Per item because `DefaultMediaSourceFactory` takes its data source factory once, and the HLS and
     * DASH sources it builds open requests with no word of which item they are for. One factory per
     * item is the price of a key that is right when a preload manager loads several items at once
     * through one factory; the chain beneath the stamp is still built once per player, so its layers'
     * state is shared exactly as before. Every setting the engine or a caller makes on this factory is
     * recorded and replayed onto each per-item one, in order.
     *
     * Every item's requests are stamped with their [LoadKind], so the slot can keep manifests out of a
     * cache; an item with no identity — one set through `setMediaItem` — is stamped with the kind and
     * no identity, and a cache keys it by its URL.
     */
    private class ContentKeyedMediaSourceFactory(private val chain: DataSource.Factory) : MediaSource.Factory {

        /** Settings replayed onto each per-item factory; written as the engine is built, read on loads. */
        private val settings = CopyOnWriteArrayList<(MediaSource.Factory) -> Unit>()

        private fun record(setting: (MediaSource.Factory) -> Unit): MediaSource.Factory =
            apply { settings += setting }

        override fun setCmcdConfigurationFactory(factory: CmcdConfiguration.Factory): MediaSource.Factory =
            record { it.setCmcdConfigurationFactory(factory) }

        override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory =
            record { it.setDrmSessionManagerProvider(provider) }

        override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory =
            record { it.setLoadErrorHandlingPolicy(policy) }

        override fun experimentalParseSubtitlesDuringExtraction(parse: Boolean): MediaSource.Factory =
            record { it.experimentalParseSubtitlesDuringExtraction(parse) }

        override fun setSubtitleParserFactory(factory: SubtitleParser.Factory): MediaSource.Factory =
            record { it.setSubtitleParserFactory(factory) }

        override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags: Int): MediaSource.Factory =
            record { it.experimentalSetCodecsToParseWithinGopSampleDependencies(codecFlags) }

        override fun setDownloadExecutor(supplier: Supplier<ReleasableExecutor>): MediaSource.Factory =
            record { it.setDownloadExecutor(supplier) }

        // Fixed by which Media3 source modules are on the classpath, so asked once rather than per call.
        private val typesOnClasspath: IntArray by lazy { DefaultMediaSourceFactory(chain).supportedTypes }

        override fun getSupportedTypes(): IntArray = typesOnClasspath.copyOf()

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val identity = ContentIdentity.of(mediaItem)
            val factory: MediaSource.Factory = when (packagingOf(mediaItem)) {
                Packaging.HLS -> {
                    val manifests = chain.stampedWith(identity, LoadKind.MANIFEST)
                    val media = chain.stampedWith(identity, LoadKind.MEDIA)
                    val other = chain.stampedWith(identity, LoadKind.UNCLASSIFIED)
                    HlsMediaSource.Factory(
                        HlsDataSourceFactory { dataType ->
                            when (dataType) {
                                C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION -> media

                                C.DATA_TYPE_MANIFEST, C.DATA_TYPE_STEERING_MANIFEST -> manifests

                                // An EXT-X-KEY's key (DATA_TYPE_DRM), and anything Media3 adds later.
                                else -> other
                            }.createDataSource()
                        },
                    )
                }

                // The MPD and its UTCTiming source load through the manifest factory, and every
                // initialization, index and media segment through the chunk source's.
                Packaging.DASH -> DashMediaSource.Factory(
                    DefaultDashChunkSource.Factory(chain.stampedWith(identity, LoadKind.MEDIA)),
                    chain.stampedWith(identity, LoadKind.MANIFEST),
                )

                Packaging.PROGRESSIVE -> DefaultMediaSourceFactory(chain.stampedWith(identity, LoadKind.MEDIA))

                Packaging.UNCLASSIFIED -> DefaultMediaSourceFactory(chain.stampedWith(identity, LoadKind.UNCLASSIFIED))
            }
            settings.forEach { it(factory) }
            return factory.createMediaSource(mediaItem)
        }

        /**
         * How [item] is packaged, which decides how its requests can be told apart.
         *
         * Media3's own inference, so an item is read as HLS or DASH exactly when
         * `DefaultMediaSourceFactory` would read it so. An HLS or DASH item that asks for what only
         * `DefaultMediaSourceFactory` adds around a source — side-loaded subtitles, clipping, ads — is
         * built by it, and its requests cannot be classified: they go uncached rather than risk a
         * manifest being answered from storage. Items adopted from a [MediaRequest] carry none of those.
         * A progressive file has no manifest, so every request it makes is media. Smooth Streaming and
         * RTSP, if a consumer adds them, have manifests this does not build by kind.
         */
        private fun packagingOf(item: MediaItem): Packaging {
            val local = item.localConfiguration ?: return Packaging.UNCLASSIFIED
            val packaging = when (Util.inferContentTypeForUriAndMimeType(local.uri, local.mimeType)) {
                C.CONTENT_TYPE_HLS -> Packaging.HLS
                C.CONTENT_TYPE_DASH -> Packaging.DASH
                C.CONTENT_TYPE_OTHER -> return Packaging.PROGRESSIVE
                else -> return Packaging.UNCLASSIFIED
            }
            val decorated = local.subtitleConfigurations.isNotEmpty() ||
                item.clippingConfiguration != MediaItem.ClippingConfiguration.UNSET ||
                local.adsConfiguration != null
            return if (decorated) Packaging.UNCLASSIFIED else packaging
        }
    }

    private enum class Packaging { HLS, DASH, PROGRESSIVE, UNCLASSIFIED }
}
