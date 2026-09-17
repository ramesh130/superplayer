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
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
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
 *   header refresh   built, and empty unless the player was given a PlaybackResilience that fills it:
 *                    then that resilience's HeaderRefreshLayer — the HeaderProvider re-invoked on
 *                    401/403 — closest to the transport, so that a token refresh and the retry it
 *                    triggers are a single transfer to everything above (ADR-0011 rule 13).
 *   transport        DefaultDataSource over DefaultHttpDataSource — file:, asset:, content:,
 *                    rawresource: and data: locally, HTTP and HTTPS remotely. Which HTTP stack
 *                    sits here is a public question ADR-0004 leaves open; it plugs in at this
 *                    line and nowhere else.
 * ```
 *
 * Two of the four phases are missing from that list, and their absence is the useful part of this
 * comment rather than an omission.
 *
 * ## A licence takes the bottom half of it, and the DRM slot is not a layer
 *
 * A licence request is composed by the player rather than named by a manifest, and it is answered by
 * a server the *app* nominated rather than by the CDN. So it enters the chain at the header-refresh
 * line and not at the top: everything above that line is about content — a cache that answers reads,
 * a revalidation that judges a playlist, a window check that reads a manifest — and none of it has
 * anything true to say about an entitlement. What is below the line does: a licence request carries
 * the app's credential exactly as a segment request does, and a licence server that answers 401 is
 * the same problem at the same layer (ADR-0012 rule 2).
 *
 * The session manager that composes it reaches this from the DRM slot, which is a provider on the
 * `MediaSource.Factory` rather than a link in the chain — Media3 asks one object per item what
 * session an item needs, at the line [StampingMediaSourceFactory] has always replayed and nothing
 * had ever filled (ADR-0012 rule 3).
 *
 * Two things travel with it and are the whole of #205. Its requests are stamped [LoadKind.LICENCE],
 * so the header-refresh layer repairs a refused entitlement as it repairs a refused segment; and the
 * session manager is handed the player's *own* `LoadErrorHandlingPolicy` rather than being left to
 * build one, so a failed licence load spends [RetryPolicy.licence] and nothing else. Media3 asks a
 * session manager's policy about a licence and a media source factory's about everything else, and a
 * budget only one of the two objects knows about is a budget no load can reach.
 *
 * ## The load-error slot is not a layer either
 *
 * Whether a failed load is retried, after how long, and whether the engine falls back to another
 * location or another track are questions Media3 asks one object per load error — a
 * `LoadErrorHandlingPolicy` on the `MediaSource.Factory`, not a link in the chain, because the
 * questions are about a load rather than about a transfer. So the slot is here for the reason CMCD's
 * is: [mediaSourceFactory] owns the factory as well as the chain, and `superplayer-resilience` fills
 * it through the engine seam rather than by reaching `ExoPlayer.Builder`. Empty, Media3's own
 * `DefaultLoadErrorHandlingPolicy` is in force, which is what ADR-0011 rule 14 promises a player
 * built without the module.
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
 * The same stamp says what kind of load a request is ([LoadKind]), on every item a player with either
 * slot filled plays, identified or not: a cache answers media and never a streamed manifest, because a stored
 * live playlist is exactly the stale copy revalidation exists to get past, and the header-refresh
 * slot below it has to tell a 403 on a segment from a failure of the manifest before it repairs
 * anything (ADR-0011 rule 13). Only the media source knows the kind — HLS asks for a data source per
 * data type, DASH takes a manifest factory beside its chunk factory — so an HLS or DASH item's source
 * is built per protocol with a stamped chain for each kind, rather than by `DefaultMediaSourceFactory`
 * over one chain.
 *
 * The two stamps are not one decision. A *kind* is stamped for whichever slot is filled; an
 * *identity* is a cache's key and is laid onto the item by adoption only on a player that has one, so
 * a request reaching the header-refresh slot on a player built with resilience alone carries its kind
 * and no identity.
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
     *
     * [headerRefresh] and [loadErrors] are resilience's two slots — a layer closest to the transport
     * and the object Media3 asks about a failed load. Null leaves the slot empty and, for
     * [loadErrors], Media3's own policy in force (ADR-0011 rules 13 and 14).
     *
     * [drm] is the DRM slot, and [exoMediaDrm] the device a test stands in for it. Null leaves no
     * provider set on the factory at all, which is what ADR-0012 rule 13 promises a player built
     * without `setDrm` — not a provider that answers `DRM_UNSUPPORTED`. A filled slot is handed
     * [loadErrors] as well, because Media3 asks a session manager's own policy about a licence load;
     * one object rather than two is what makes `RetryPolicy.licence` reachable (#205).
     * [deliveredProtection] is where the slot writes down what it opened, read back through
     * [SuperPlayer.deliveredSecurityLevel]; a player with no slot writes nothing into it.
     */
    fun mediaSourceFactory(
        context: Context,
        cmcdMode: CmcdMode,
        measurementSession: MeasurementSession,
        transport: DataSource.Factory? = null,
        loadExecutor: Supplier<ReleasableExecutor>? = null,
        cache: ContentCache? = null,
        headerRefresh: HeaderRefreshLayer? = null,
        loadErrors: LoadErrorHandlingPolicy? = null,
        drm: LicenceSessions? = null,
        exoMediaDrm: ExoMediaDrm.Provider? = null,
        deliveredProtection: DeliveredProtection = DeliveredProtection(),
    ): MediaSource.Factory {
        val bottom = transport ?: DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())
        // Header refresh first, so it is the innermost wrapper: a request it repairs and re-opens is
        // one transfer to the cache slot and to everything above it.
        val refreshed = headerRefresh?.over(bottom) ?: bottom
        val chain = dataSourceChain(refreshed, cache)
        // With neither slot filled, exactly the factory Phase 3 built: nothing about a request is
        // stamped, because nothing below would read it (ADR-0010 rule 13, ADR-0011 rule 14). A DRM
        // slot is not in that count: what it fills is a provider on the factory rather than a layer
        // in the chain, and the licence transport it is handed carries a stamp of its own whether or
        // not an item's requests do.
        val stamps = cache != null || headerRefresh != null
        val factory = if (stamps) StampingMediaSourceFactory(chain, cache?.downloads) else DefaultMediaSourceFactory(chain)
        return factory.apply {
            cmcdMode.toCmcdConfigurationFactory(measurementSession)
                ?.let(::setCmcdConfigurationFactory)
            loadExecutor?.let(::setDownloadExecutor)
            loadErrors?.let(::setLoadErrorHandlingPolicy)
            // Over `refreshed` rather than over `chain`: a licence travels the credential-bearing
            // part of the chain and not the content-bearing part. See [LicenceSessions].
            //
            // Stamped whenever there is a slot to fill rather than under `stamps`: the kind is the
            // one thing that identifies a request no item's factory opened, and it is what lets the
            // header-refresh layer repair a refused entitlement. The identity is null because a
            // licence belongs to no item — it is the player's, like the protection that asked for it
            // (ADR-0012 rule 1) — and because nothing keys a cache by it: the licence transport is
            // below the cache slot, so no licence reaches a cache to be keyed at all.
            //
            // `loadErrors` is the same instance the factory above was given, which is what routes a
            // failed licence load to `RetryPolicy.licence` instead of to Media3's own defaults (#205).
            drm?.let {
                setDrmSessionManagerProvider(
                    it.over(
                        LicenceContext(
                            transport = refreshed.stampedWith(identity = null, kind = LoadKind.LICENCE),
                            mediaDrm = exoMediaDrm,
                            loadErrors = loadErrors,
                            // Read here rather than by the module, and read only where there is a slot
                            // to read it for: a player without `setDrm` walks no codec list on this
                            // account (ADR-0012 rules 12 and 13).
                            device = deviceConstraintsOf(context),
                            delivered = deliveredProtection,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * The chain a download loads through: the transport — [environment]'s under a test, the platform's
     * HTTP stack otherwise — with [headerRefresh] over it where the store's resilience has one.
     *
     * ADR-0013 rule 6: a download travels the one chain, less what is a *playback's*. No CMCD, because
     * a download is not a playback session and has no `sid` to join; no bandwidth meter, because nothing
     * here registers one; and neither live layer, because a live stream is refused at enqueue (rule 8).
     * The cache is not composed here either: Media3's downloader writes through a `CacheDataSource`
     * it is handed per content id, over this chain as its upstream (rule 5). The header-refresh layer rule 6
     * also names is composed innermost, as on a player, so a refused 401 or 403 it repairs is one transfer to
     * the cache writer and to the downloader's retry count above it (rule 14, #254); it sits under the stamp,
     * which is how it sees a request's kind as a player's layer does.
     *
     * Every request is stamped with its [LoadKind], as a player with resilience stamps it, because that is
     * what lets `ErrorClassifier` tell a segment the origin has lost from a transfer that failed. A download
     * has no media source to stamp by kind, so the kind is read off the request instead: Media3's segment
     * downloader asks for every manifest — the multivariant playlist, each media playlist, the MPD — as a
     * compressible request and for no segment that way (// ref: `SegmentDownloader.getCompressibleDataSpec`).
     */
    fun downloadChain(
        context: Context,
        environment: DownloadEnvironment? = null,
        headerRefresh: HeaderRefreshLayer? = null,
    ): DataSource.Factory {
        val transport = environment?.transport ?: DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())
        val refreshed = headerRefresh?.over(transport) ?: transport
        return DataSource.Factory { DownloadStampingDataSource(refreshed.createDataSource()) }
    }

    /**
     * The chain a download's licence exchange travels: [downloadChain]'s transport, every request stamped
     * [LoadKind.LICENCE] as a player's licence requests are (ADR-0013 rule 13).
     *
     * Beside [downloadChain] rather than through it, because that chain reads a request's kind off Media3's
     * segment downloader, which composes no licence request. The header-refresh layer is not composed here:
     * #254 built it for a download's own requests, and a licence exchange's credential, like its
     * `RetryPolicy.licence` budget, is not yet a download's.
     */
    fun downloadLicenceChain(
        context: Context,
        environment: DownloadEnvironment? = null,
    ): DataSource.Factory {
        val transport = environment?.transport ?: DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())
        return transport.stampedWith(identity = null, kind = LoadKind.LICENCE)
    }

    /**
     * The chain itself — see the composition order above for what wraps what — over [refreshed],
     * which is the transport with the header-refresh slot already composed onto it, and with
     * [cache]'s layer in the cache slot when there is one. One call is one player's chain: the
     * layers hold per-session state.
     */
    private fun dataSourceChain(
        refreshed: DataSource.Factory,
        cache: ContentCache?,
    ): DataSource.Factory =
        LiveWindowDepthCheck.over(
            LivePlaylistRevalidation(Clock.DEFAULT)
                .over(cache?.layer?.over(refreshed) ?: refreshed),
        )

    /**
     * Media3's own media source factory, built per item over [chain] stamped with that item's
     * [ContentIdentity] and with each request's [LoadKind] — the way content identity reaches the
     * cache slot's key, and the way both slots below learn what a request is for.
     *
     * Per item because `DefaultMediaSourceFactory` takes its data source factory once, and the HLS and
     * DASH sources it builds open requests with no word of which item they are for. One factory per
     * item is the price of a key that is right when a preload manager loads several items at once
     * through one factory; the chain beneath the stamp is still built once per player, so its layers'
     * state is shared exactly as before. Every setting the engine or a caller makes on this factory is
     * recorded and replayed onto each per-item one, in order.
     *
     * Every item's requests are stamped with their [LoadKind], so a cache can keep manifests out of
     * itself and a header-refresh layer can tell which load a failed credential belonged to; an item
     * with no identity — one set through `setMediaItem`, or any item on a player with resilience and
     * no cache — is stamped with the kind and no identity, and a cache keys it by its URL.
     *
     * An identified HLS or DASH item whose content [downloads] holds a download of is narrowed to the
     * tracks that download holds, as Media3's stream keys on the item, so its player selects the
     * rendition and the languages on disk rather than one it would fetch (ADR-0013 rule 12). Here rather
     * than at adoption because every path that builds a source — a player's, a pool's, a preload
     * manager's — builds it through this one factory.
     */
    private class StampingMediaSourceFactory(
        private val chain: DataSource.Factory,
        private val downloads: CacheDownloads?,
    ) : MediaSource.Factory {

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
            val packaging = packagingOf(mediaItem)
            val factory: MediaSource.Factory = when (packaging) {
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
            val adaptive = packaging == Packaging.HLS || packaging == Packaging.DASH
            return factory.createMediaSource(if (adaptive) narrowedToDownload(mediaItem, identity) else mediaItem)
        }

        /** [item], narrowed to the tracks its content's download holds, or [item] itself when there is none. */
        private fun narrowedToDownload(item: MediaItem, identity: ContentIdentity?): MediaItem {
            val local = item.localConfiguration
            if (downloads == null || identity == null || local == null || local.streamKeys.isNotEmpty()) return item
            val tracks = downloads.downloadedTracks(identity.contentId, local.uri)
            return if (tracks.isEmpty()) item else item.buildUpon().setStreamKeys(tracks).build()
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

/** Stamps each request a download opens with its [LoadKind], read off the request as [TransferChain.downloadChain] says. */
private class DownloadStampingDataSource(private val upstream: DataSource) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.customData != null) return upstream.open(dataSpec)
        val kind = if (dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP)) LoadKind.MANIFEST else LoadKind.MEDIA
        return upstream.open(dataSpec.buildUpon().setCustomData(RequestStamp(identity = null, kind)).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    // A Java default method, which Kotlin delegation would not forward: written out by hand.
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        upstream.close()
    }
}
