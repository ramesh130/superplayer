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

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.Clock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import java.io.ByteArrayOutputStream

/**
 * Keeps a live HLS playlist moving when the copy reaching the player has stopped, and names the
 * failure when it cannot.
 *
 * ## The defect
 *
 * A CDN cache rule applied to a whole path — `max-age=600` on everything under `/live/` — holds the
 * live media playlist for ten minutes while the segments beside it are never held at all. Every
 * actor conforms: RFC 9111 §5.2.2.1 lets the cache keep the copy, the origin does publish new
 * versions, and the player does reload as RFC 8216 §6.3.4 obliges it to. The composite still breaks
 * RFC 8216 §6.2.1's rule that a new version with a new segment appears within one and a half target
 * durations, because the version that reaches the player is the cached one. Media3 then declares the
 * playlist stuck and ends the session with an unclassified `ERROR_CODE_IO_UNSPECIFIED`, and the
 * ticket reads "live stream freezes after thirty seconds".
 *
 * ## What this does about it, in order
 *
 * 1. **Nothing, while the playlist advances.** It reads each live media playlist as it passes and
 *    remembers how far it has got; no request is changed, so a CDN's offload of a healthy stream is
 *    untouched. That is the case for every stream with a sane cache rule, and it is why this is on
 *    for every player rather than being a choice.
 * 2. **Reloads past the caches, once the playlist is overdue.** A playlist that has gone
 *    [UPDATE_BOUND_TARGET_DURATIONS] target durations without a new segment is late by the
 *    protocol's own rule, so every later reload of it asks for the origin's copy with
 *    `Cache-Control: no-cache` (// spec: RFC 9111 §5.2.1.4). For the rest of the player's life,
 *    not only until it advances: a cache that held one copy for ten minutes will hand the next one
 *    back the same way, and a playlist that advanced only when asked past the cache would stall and
 *    recover on every segment. What that costs is origin requests from the players behind a cache
 *    that was already failing them.
 * 3. **Gives up with a name.** If the playlist is still where it was
 *    [giveUpBoundTargetDurations] target durations on, the load fails with a
 *    [StaleLivePlaylistException] carrying what was observed and what it points at. That bound is
 *    [RELOADS_OF_MARGIN] unchanged reloads short of Media3's own, and the failing load is failed
 *    before Media3 has parsed it, so the engine never gets to judge the playlist stuck and report it
 *    untyped. The engine then
 *    retries the load under its `LoadErrorHandlingPolicy` — a few more seconds, each retry asking
 *    past the caches again — before the session ends with this as its cause. A retry that finds the
 *    playlist moved is a recovery rather than an error: nothing here latches.
 *
 * The request directive is the one fix a client can apply by itself and the one no conforming cache
 * may simply refuse to forward — RFC 9111 makes it a preference rather than an obligation, which is
 * why step 3 exists. A cache-busting query parameter would get past a cache that ignores it, and
 * is not done here: it changes the URL, which breaks a signed one, and whether an origin tolerates
 * it is a fact about a deployment rather than about the protocol.
 *
 * ## What it reads
 *
 * Only HLS media playlists without `EXT-X-ENDLIST`, recognised by the protocol's own naming and
 * media types (// spec: RFC 8216 §4) and by carrying `EXT-X-TARGETDURATION`, which a multivariant
 * playlist does not. Progress is the media sequence number one past the newest segment
 * (// spec: RFC 8216 §4.3.3.2), counting segments a delta update skipped
 * (// spec: draft-pantos-hls-rfc8216bis §4.4.5.2) and, at the same segment, partial segments added
 * since (§4.4.4.9) — so a low-latency stream that advances by parts is not mistaken for a stuck one.
 * A copy *older* than one already seen, which is what a cache hands back after the origin's copy has
 * been fetched round it, is no progress at all.
 *
 * It is one instance per player: the transfer chain is assembled once per engine, and a playlist's
 * history belongs to the session reading it.
 */
internal class LivePlaylistRevalidation(private val clock: Clock) {

    /**
     * The playlists this player is following, least recently loaded first and bounded at
     * [MAX_TRACKED_PLAYLISTS]. Bounded because a pooled player lives through many items: a feed of
     * live rows would otherwise accumulate one entry per channel it ever showed. A playlist evicted
     * and reloaded starts afresh, which costs at most one late detection.
     */
    private val playlists = object : LinkedHashMap<String, Tracked>(16, 0.75f, /* accessOrder= */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tracked>?): Boolean =
            size > MAX_TRACKED_PLAYLISTS
    }

    /** [upstream], with every playlist it serves read on the way past. */
    fun over(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { RevalidatingDataSource(upstream.createDataSource()) }

    @Synchronized
    private fun isBypassingCaches(key: String): Boolean = playlists[key]?.bypassingCaches == true

    /**
     * Takes in one complete playlist response and returns the failure to raise, if it is time to.
     *
     * The clock is Media3's own default clock, the one `DefaultHlsPlaylistTracker` measures a stuck
     * playlist against, so that the two bounds are compared on the same time rather than on two.
     */
    @Synchronized
    private fun onPlaylistLoaded(
        uri: Uri,
        key: String,
        body: ByteArray,
        headers: Map<String, List<String>>,
        bypassedCaches: Boolean,
    ): StaleLivePlaylistException? {
        val playlist = MediaPlaylistSummary.of(body)
        if (playlist == null || playlist.ended) {
            // Not a media playlist, or one that has ended and so cannot be late.
            playlists.remove(key)
            return null
        }
        val nowMs = clock.elapsedRealtime()
        // A history nobody has added to for longer than [STALE_HISTORY_TARGET_DURATIONS] is not this
        // engine following a live playlist reloads it at least once a target duration, so a gap that
        // long is a stop and a later prepare, or a pooled player's next item. Judged against it, a
        // channel replayed after its origin restarted the media sequence would look minutes late on
        // its first load, and fail where Media3's fresh tracker plays it — so it starts afresh,
        // keeping only whether this playlist needed asking past a cache.
        val tracked = playlists[key]?.takeIf {
            nowMs - it.loadedAtMs <= STALE_HISTORY_TARGET_DURATIONS * playlist.targetDurationMs
        }
        if (tracked == null || playlist.progress > tracked.progress) {
            playlists[key] = Tracked(playlist.progress, advancedAtMs = nowMs).apply {
                loadedAtMs = nowMs
                bypassingCaches = playlists[key]?.bypassingCaches == true
            }
            return null
        }
        tracked.loadedAtMs = nowMs

        val cacheControl = headers.header(CACHE_CONTROL)
        tracked.cacheEvidence = tracked.cacheEvidence ||
            outlivesTheUpdateBound(cacheControl, playlist.targetDurationMs) ||
            (bypassedCaches && (headers.header(AGE)?.trim()?.toLongOrNull() ?: 0L) > 0L)
        if (bypassedCaches) tracked.unansweredBypasses++

        val unchangedForMs = nowMs - tracked.advancedAtMs
        if (unchangedForMs > UPDATE_BOUND_TARGET_DURATIONS * playlist.targetDurationMs) {
            tracked.bypassingCaches = true
        }
        if (unchangedForMs <= giveUpBoundTargetDurations * playlist.targetDurationMs) return null
        return StaleLivePlaylistException(
            playlistUri = uri.toString(),
            targetDurationMs = playlist.targetDurationMs,
            unchangedForMs = unchangedForMs,
            cacheBypassingReloads = tracked.unansweredBypasses,
            servedCacheControl = cacheControl,
            likelyCause = if (tracked.cacheEvidence) {
                StaleLivePlaylistException.LikelyCause.INTERMEDIARY_CACHE
            } else {
                StaleLivePlaylistException.LikelyCause.ORIGIN
            },
        )
    }

    /** One playlist's history: how far it has got, since when, and what it has been served with. */
    private class Tracked(val progress: Progress, val advancedAtMs: Long) {
        var loadedAtMs = advancedAtMs
        var bypassingCaches = false
        var unansweredBypasses = 0
        var cacheEvidence = false
    }

    /**
     * The wrapper each load goes through: asks past the caches when the playlist's history says to,
     * and hands a complete playlist response back to be read.
     *
     * Every other byte, header and callback is the upstream's, and the transfer listener is
     * registered *on* the upstream rather than held here — `TransferChain`'s rule for every layer,
     * because measurement is a propagated listener and a layer that swallowed it would blind
     * bandwidth estimation without a symptom.
     */
    private inner class RevalidatingDataSource(private val upstream: DataSource) : DataSource {

        private var uri: Uri? = null
        private var key: String? = null
        private var bypassedCaches = false
        private var body: ByteArrayOutputStream? = null

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val key = keyOf(dataSpec.uri)
            bypassedCaches = isBypassingCaches(key)
            val request = if (bypassedCaches) dataSpec.withAdditionalHeaders(NO_CACHE) else dataSpec
            val length = upstream.open(request)
            this.uri = dataSpec.uri
            this.key = key
            // A whole response only: a range of a playlist is not a version of it.
            val whole = dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()
            body = if (whole && isPlaylist(dataSpec.uri, upstream.responseHeaders)) ByteArrayOutputStream() else null
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = upstream.read(buffer, offset, length)
            val body = body ?: return read
            when {
                read == C.RESULT_END_OF_INPUT -> {
                    this.body = null
                    onPlaylistLoaded(
                        checkNotNull(uri),
                        checkNotNull(key),
                        body.toByteArray(),
                        upstream.responseHeaders,
                        bypassedCaches,
                    )?.let { throw it }
                }

                // Past any live playlist a real deployment serves: stop reading it rather than
                // hold an unbounded copy of something that is not one.
                body.size() + read > MAX_PLAYLIST_BYTES -> this.body = null

                else -> body.write(buffer, offset, read)
            }
            return read
        }

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() {
            body = null
            upstream.close()
        }
    }

    /**
     * How far a playlist has got: the sequence number one past its newest segment, then the
     * partial segments published since. Compared in that order, so a new segment always counts and
     * a new part counts only at the same segment.
     */
    private data class Progress(val nextSequenceNumber: Long, val trailingParts: Int) : Comparable<Progress> {
        override fun compareTo(other: Progress): Int =
            compareValuesBy(this, other, Progress::nextSequenceNumber, Progress::trailingParts)
    }

    /** The three facts about a media playlist this reads, or null for anything that is not one. */
    private class MediaPlaylistSummary(val targetDurationMs: Long, val progress: Progress, val ended: Boolean) {

        companion object {

            fun of(body: ByteArray): MediaPlaylistSummary? {
                // spec: RFC 8216 §4.1 — UTF-8, lines ended by LF or CR LF, and the first line is
                // EXTM3U.
                val lines = String(body, Charsets.UTF_8).lineSequence().map(String::trim).iterator()
                if (!lines.hasNext() || lines.next() != "#EXTM3U") return null
                var targetDurationMs: Long? = null
                var mediaSequence = 0L
                var segments = 0L
                var trailingParts = 0
                var ended = false
                for (line in lines) {
                    when {
                        // spec: RFC 8216 §4.3.3.1 — a decimal-integer number of seconds. Read as a
                        // decimal all the same, because packagers write `6.0` and Media3 accepts it.
                        line.startsWith(TARGET_DURATION) -> targetDurationMs = line.substringAfter(':')
                            .toDoubleOrNull()?.let { (it * MILLIS_PER_SECOND).toLong() }

                        // spec: RFC 8216 §4.3.3.2 — absent means zero.
                        line.startsWith(MEDIA_SEQUENCE) ->
                            mediaSequence = line.substringAfter(':').toLongOrNull() ?: 0L

                        line.startsWith(SEGMENT) -> {
                            segments++
                            trailingParts = 0
                        }

                        line.startsWith(PART) -> trailingParts++

                        line.startsWith(SKIP) -> segments += SKIPPED_SEGMENTS.find(line)
                            ?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                        line == END_LIST -> ended = true
                    }
                }
                return targetDurationMs?.let {
                    MediaPlaylistSummary(it, Progress(mediaSequence + segments, trailingParts), ended)
                }
            }
        }
    }

    companion object {

        /**
         * How many target durations a live playlist may go without a new segment before it is late.
         *
         * spec: RFC 8216 §6.2.1 — "the server MUST make a new version of the Playlist file available
         * that contains at least one new Media Segment" no later than one and a half times the target
         * duration after the previous one. Past this the copy the player holds is out of spec, so
         * asking past the caches is justified by the protocol rather than by a tuning choice.
         */
        const val UPDATE_BOUND_TARGET_DURATIONS: Double = 1.5

        /**
         * How far a reload of an unchanged playlist follows the one before, in target durations.
         *
         * spec: RFC 8216 §6.3.4 — when a reload brings no change, the client waits half the target
         * duration before the next.
         */
        private const val UNCHANGED_RELOAD_TARGET_DURATIONS: Double = 0.5

        /**
         * How many unchanged reloads of margin to leave before Media3's own bound.
         *
         * One was not enough. This layer can only judge a playlist when a reload of it *arrives*, so
         * one reload of margin is exactly one chance to fail the load first — and a host that delays
         * that single reload lets Media3 reach its own bound and end the session untyped instead.
         * That is not only a test's problem: the same starvation on a device costs a consumer the
         * named cause and the retries that go with it. Two reloads are two chances, still inside the
         * engine's bound (issue #91, where one commit recorded both outcomes on two runs of CI).
         */
        private const val RELOADS_OF_MARGIN: Double = 2.0

        /**
         * How many target durations without a new segment before a load fails typed:
         * [RELOADS_OF_MARGIN] unchanged reloads before Media3 would declare the playlist stuck and
         * end the session untyped.
         *
         * ref: Media3 1.11.0 `DefaultHlsPlaylistTracker`, which raises `PlaylistStuckException` once
         * a playlist has not changed for more than this coefficient times its target duration. Read
         * from Media3 rather than copied, so an engine that moves its bound moves this one with it.
         *
         * Still well past RFC 8216 §6.2.1's one and a half target durations, so no conforming stream
         * is failed by this: what the margin changes is how much of the engine's own slack this layer
         * leaves itself, not what counts as late.
         */
        val giveUpBoundTargetDurations: Double =
            DefaultHlsPlaylistTracker.DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT -
                RELOADS_OF_MARGIN * UNCHANGED_RELOAD_TARGET_DURATIONS

        /**
         * How long a playlist's history stays this session's, in target durations.
         *
         * Deliberately *not* [giveUpBoundTargetDurations], which it was derived from until the margin
         * above moved: the two answer different questions. This one asks whether a gap in loads means
         * a stop and a later prepare — an engine following a live playlist reloads it at least once a
         * target duration, so any window comfortably over one answers it — while the give-up bound
         * asks how much of Media3's slack to leave. Sharing one number made this move whenever that
         * did, which is a silent change to what counts as a new session.
         */
        private const val STALE_HISTORY_TARGET_DURATIONS: Double = 3.0

        init {
            // The order the bounds have to keep: ask past the caches once the protocol calls the
            // playlist late, give up while the engine still has slack, and never after it has none.
            require(
                UPDATE_BOUND_TARGET_DURATIONS < giveUpBoundTargetDurations &&
                    giveUpBoundTargetDurations <
                    DefaultHlsPlaylistTracker.DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT,
            ) {
                "Bounds out of order: late at $UPDATE_BOUND_TARGET_DURATIONS, giving up at " +
                    "$giveUpBoundTargetDurations, engine at " +
                    "${DefaultHlsPlaylistTracker.DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT}"
            }
        }

        /**
         * How many playlists one player follows at once. A session plays a multivariant stream's
         * renditions and its audio and subtitle groups, which is a handful; a pooled player adds one
         * per item it has played. Sixty-four is room for both with no search worth measuring.
         */
        private const val MAX_TRACKED_PLAYLISTS = 64

        /** Four MiB: a four-hour DVR window of two-second segments is well under one. */
        private const val MAX_PLAYLIST_BYTES = 4 * 1024 * 1024

        private const val MILLIS_PER_SECOND = 1_000L

        private const val CACHE_CONTROL = "Cache-Control"
        private const val AGE = "Age"
        private val NO_CACHE = mapOf(CACHE_CONTROL to "no-cache")

        private const val TARGET_DURATION = "#EXT-X-TARGETDURATION:"
        private const val MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
        private const val SEGMENT = "#EXTINF:"
        private const val PART = "#EXT-X-PART:"
        private const val SKIP = "#EXT-X-SKIP:"
        private const val END_LIST = "#EXT-X-ENDLIST"
        private val SKIPPED_SEGMENTS = Regex("SKIPPED-SEGMENTS=(\\d+)")

        /**
         * spec: RFC 8216 §4 — a playlist's name ends `.m3u8` or `.m3u`, or it is served as
         * `application/vnd.apple.mpegurl` or `audio/mpegurl`. Either is enough.
         *
         * ref: `application/x-mpegurl` is not in the RFC, and is what a great many servers send for a
         * playlist all the same — it is the type Media3's own `MimeTypes.APPLICATION_M3U8` names.
         */
        private val PLAYLIST_MEDIA_TYPES =
            setOf("application/vnd.apple.mpegurl", "audio/mpegurl", "application/x-mpegurl")

        private fun isPlaylist(uri: Uri, headers: Map<String, List<String>>): Boolean {
            val path = uri.path.orEmpty().lowercase()
            if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return true
            val mediaType = headers.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            return mediaType in PLAYLIST_MEDIA_TYPES
        }

        /**
         * One playlist, whatever delivery directives its request carries.
         *
         * spec: draft-pantos-hls-rfc8216bis §6.2.5.1 — a low-latency client adds query parameters
         * named with the reserved `_HLS_` prefix (`_HLS_msn`, `_HLS_part`, `_HLS_skip`) to ask for a
         * particular version, so they change on every reload of the same playlist. Only those are
         * dropped: any other parameter may be what distinguishes one rendition, or one signed URL,
         * from another, and two playlists must not be compared as one.
         */
        private fun keyOf(uri: Uri): String {
            val key = uri.buildUpon().clearQuery().fragment(null)
            uri.queryParameterNames.filterNot { it.startsWith(DELIVERY_DIRECTIVE_PREFIX) }.forEach { name ->
                uri.getQueryParameters(name).forEach { key.appendQueryParameter(name, it) }
            }
            return key.build().toString()
        }

        private const val DELIVERY_DIRECTIVE_PREFIX = "_HLS_"

        /**
         * Whether [cacheControl] lets a shared cache keep a playlist past the point it is late.
         *
         * spec: RFC 9111 §5.2.2.10 — `s-maxage` overrides `max-age` for a shared cache, which is
         * what a CDN is; §5.2.2.4 and §5.2.2.5 — `no-cache` and `no-store` mean nothing is served
         * from a store unvalidated, whatever the lifetime says.
         */
        private fun outlivesTheUpdateBound(cacheControl: String?, targetDurationMs: Long): Boolean {
            val directives = cacheControl?.split(',')?.map { it.trim().lowercase() } ?: return false
            if (directives.any { it == "no-cache" || it == "no-store" }) return false
            fun seconds(name: String) =
                directives.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.trim('"')?.toLongOrNull()
            val lifetimeSeconds = seconds("s-maxage") ?: seconds("max-age") ?: return false
            return lifetimeSeconds * MILLIS_PER_SECOND > UPDATE_BOUND_TARGET_DURATIONS * targetDurationMs
        }

        /** A header by name, case-insensitively as HTTP names are, with its values joined. */
        private fun Map<String, List<String>>.header(name: String): String? =
            entries.firstOrNull { name.equals(it.key, ignoreCase = true) }?.value?.joinToString(", ")
    }
}
