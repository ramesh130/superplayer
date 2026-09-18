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

package com.superplayer.diagnostics

import android.net.Uri
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import com.superplayer.core.LivePlaylistRevalidation

/**
 * What a stream's *delivery* can get wrong: the defects that are in no document and that no parse can see.
 *
 * A `Cache-Control` the playlist and its segments disagree about, a token scoped to the manifest and not to
 * the media, a CORS configuration that refuses the request a player has to make — each is a fact about a
 * transfer rather than about a manifest, so each is readable only because ADR-0015 rule 7 makes the doctor
 * fetch over the chain a player of this request would load through. A doctor with an HTTP stack of its own
 * would carry a different credential and meet a different edge, and would report a healthy stream.
 *
 * ## What it reads, and what it costs
 *
 * The response headers of the manifests the examination was fetching anyway, and — for the one rule that
 * needs two parties before it can name the wrong one — the headers of a *single* segment, read without
 * reading any of its bytes. That probe is rule 7's #289 addendum, and this file is where the decision to
 * spend it is made: the supplier arrives as a lambda, and a rule calls it only once it already has a defect
 * to attribute. So a stream whose playlist is cached correctly costs exactly what it cost before.
 *
 * ## HLS only, for now
 *
 * Nothing here is protocol-specific — a token and an allowed origin are read from a URI and a header, never
 * from a playlist — but a [Pathology] is named for the corpus entry it is scored against (ADR-0015 rule 12),
 * and the corpus's three delivery entries are HLS. A DASH delivery entry would carry its own ids, and these
 * rules would be lifted rather than copied. A DASH stream with a CORS misconfiguration is therefore a gap
 * this file names rather than covers.
 */
internal object DeliveryPathologies {

    /**
     * What the delivery of one media playlist got wrong, given the headers it arrived with.
     *
     * [source] is the URI the app handed the doctor — the one a signing step is applied to — and
     * [playlistUri] the one this playlist was actually read from, which is where its segments resolve
     * against. [segmentHeaders] answers the headers of a segment, or null where that transfer was refused;
     * it is called at most once, and only by a rule that already has something to attribute.
     */
    fun inDelivery(
        source: Uri,
        playlistUri: Uri,
        playlist: HlsMediaPlaylist,
        playlistHeaders: Map<String, List<String>>,
        segmentHeaders: (Uri) -> Map<String, List<String>>?,
    ): List<Finding> = listOfNotNull(
        cachedLivePlaylist(playlistUri, playlist, playlistHeaders, segmentHeaders),
        tokenScopedToManifest(source, playlist),
        tokenExpiringInWindow(source, playlist),
    )

    /**
     * A live media playlist a shared cache may hold past the point at which it is late, and the segments
     * that say the opposite.
     *
     * **The comparison is core's** — `LivePlaylistRevalidation` already decides whether a served
     * `Cache-Control` outlives RFC 8216 §6.2.1's update bound, and ADR-0015 rule 6 makes a copy of it a bug
     * rather than a duplication to tidy up later. This rule asks it, and what it adds is the second party:
     * a player meets this defect as a playlist that stopped advancing, while a doctor can say *which of the
     * two responses is misconfigured*, which is the whole of the fix.
     *
     * **The playlist is the wrong one, always.** A media segment is immutable once published, so caching it
     * for a long time is correct and caching it for none is merely wasteful; a live media playlist is the one
     * document in the stream that changes, so a lifetime longer than its own update bound is what turns a
     * live stream into a frozen one. The magnitude names both, because a support engineer changes a cache
     * rule and needs to know which path it is on.
     *
     * [FindingSeverity.BLOCKING]: the client reloads a playlist that cannot change, and playback ends —
     * on a player with `superplayer-core` the session fails with a `StaleLivePlaylistException` naming this
     * very header (issue #66), which is the same defect met from the other end.
     *
     * On-demand playlists are passed over: an `EXT-X-ENDLIST` says the document will not change again, so
     * there is no bound for a lifetime to outlive and a long `max-age` on one is correct.
     */
    private fun cachedLivePlaylist(
        playlistUri: Uri,
        playlist: HlsMediaPlaylist,
        playlistHeaders: Map<String, List<String>>,
        segmentHeaders: (Uri) -> Map<String, List<String>>?,
    ): Finding? {
        if (playlist.hasEndTag) return null
        val heldSeconds = LivePlaylistRevalidation.cachedPastTheUpdateBoundSeconds(
            playlistHeaders.header(CACHE_CONTROL),
            playlist.targetDurationUs / MICROS_PER_MILLI,
        ) ?: return null
        val segment = playlist.segments.firstOrNull()?.let { resolve(playlistUri, it.url) }
        val served = segment?.let(segmentHeaders)?.header(CACHE_CONTROL)
        val held = "the playlist, not its segments: held for ${seconds(heldSeconds * MICROS_PER_SECOND)} " +
            "against a ${seconds(playlist.targetDurationUs)} target duration"
        return Finding(
            Pathology.HLS_CACHED_LIVE_PLAYLIST,
            FindingSeverity.BLOCKING,
            magnitude = if (served == null) held else "$held, while its segments are served \"$served\"",
        )
    }

    /**
     * A manifest fetched from a signed URL whose segments are addressed without one.
     *
     * ref: RFC 3986 §5.3 — a relative reference is resolved against the base URI's path, and the query
     * component is not part of it. A packager that signs the URL the app is handed and names its segments
     * relatively therefore publishes media addressed with no credential at all, which a CDN enforcing the
     * signature refuses (RFC 9110 §15.5.4). Nothing in the playlist is wrong, and the defect exists only
     * in the relation between the URI the doctor was given and the URIs that playlist resolves to — which
     * is why it needs the fetch and not the parse.
     *
     * [FindingSeverity.BLOCKING]: the stream opens and then serves nothing, and no rung of the fallback
     * ladder repairs it — every source of one piece of content is signed by the same service in the same way.
     *
     * The magnitude counts the segments, because "the manifest is signed and its segments are not" is the
     * sentence, and the count is what says it was the whole rendition rather than one stray line.
     */
    private fun tokenScopedToManifest(source: Uri, playlist: HlsMediaPlaylist): Finding? {
        if (expirySecondsOf(source) == null) return null
        val segments = playlist.segments.map { it.url }
        if (segments.isEmpty() || segments.any { Uri.parse(it).getQueryParameter(EXPIRES) != null }) return null
        return Finding(
            Pathology.HLS_TOKEN_SCOPED_TO_MANIFEST,
            FindingSeverity.BLOCKING,
            magnitude = "the manifest is signed and its ${segments.size} segments are not",
        )
    }

    /**
     * A token whose own expiry falls inside the content it was minted for.
     *
     * ref: RFC 3986 §3.4 — a signed URL states its expiry in the clear, as a query parameter, so that an
     * intermediary can refuse an expired request without asking the signer. That is the part of the
     * convention every CDN's scheme shares and the only part read here; nothing is verified and no scheme
     * is reproduced, which is what `CONTRIBUTING.md`'s clean-room rules ask of a convention with no
     * standard behind it.
     *
     * **The window is the content, doubled.** What a token has to cover is a viewing rather than a
     * duration, and a viewing is the content plus whatever the viewer spends paused inside it; the content's
     * own duration is the only part of that the stream states, so it stands for both. Hence: a token with
     * less life left than the content is one no viewer reaches the end with, and a token with less than
     * twice the content is one a viewer who pauses for as long again loses. Derived rather than cited,
     * because a signed URL's lifetime is a CDN's setting and no document speaks to it.
     *
     * Live playlists are passed over, and that is the rule rather than a gap: a live stream has no end to
     * cover, so every finite token expires inside it and reporting that would flag every signed live URL
     * there is.
     */
    private fun tokenExpiringInWindow(source: Uri, playlist: HlsMediaPlaylist): Finding? {
        if (!playlist.hasEndTag) return null
        val expirySeconds = expirySecondsOf(source) ?: return null
        val remainingUs = (expirySeconds - System.currentTimeMillis() / MILLIS_PER_SECOND) * MICROS_PER_SECOND
        val severity = when {
            remainingUs < playlist.durationUs -> FindingSeverity.BLOCKING
            remainingUs < VIEWING_CONTENT_DURATIONS * playlist.durationUs -> FindingSeverity.DEGRADED
            else -> return null
        }
        return Finding(
            Pathology.HLS_TOKEN_EXPIRING_IN_WINDOW,
            severity,
            magnitude = "${seconds(remainingUs)} of token left over ${seconds(playlist.durationUs)} of content",
        )
    }

    /**
     * A response whose CORS headers refuse a credentialed request, over every response this examination saw.
     *
     * spec: WHATWG Fetch §3.3.5 — where a request's credentials mode is "include", §4.10's CORS check fails
     * unless `Access-Control-Allow-Origin` is the request's own origin, so a wildcard fails it whatever
     * `Access-Control-Allow-Credentials` says. The pair is a refusal by construction, which is why it can be
     * called wrong without knowing which origin is asking — and it is the only CORS reading here for exactly
     * that reason.
     *
     * **A response that says nothing about CORS is not a misconfiguration.** Most origins serving native
     * players emit no CORS headers at all and are entirely correct; flagging them would be the false positive
     * ADR-0015 rule 12 scores a doctor on. What is reported is an origin that speaks the protocol and
     * contradicts it.
     *
     * [FindingSeverity.BLOCKING], and it is a defect no native player can see: one CDN configuration serves
     * an app and a browser alike, so this is the finding that matters most on a stream that plays perfectly
     * on the device the report was taken from. No magnitude: a configuration refuses the request or it does
     * not.
     */
    fun inResponses(responses: Collection<Map<String, List<String>>>): List<Finding> {
        val refuses = responses.any { headers ->
            headers.header(ALLOW_ORIGIN)?.trim() == ANY_ORIGIN &&
                headers.header(ALLOW_CREDENTIALS)?.trim().equals(ALLOWED, ignoreCase = true)
        }
        return if (refuses) {
            listOf(Finding(Pathology.HLS_CORS_REFUSES_CREDENTIALS, FindingSeverity.BLOCKING, magnitude = null))
        } else {
            emptyList()
        }
    }

    /** [relative] as the playlist at [base] addresses it — Media3's own resolution, which is the player's. */
    private fun resolve(base: Uri, relative: String): Uri =
        Uri.parse(UriUtil.resolve(base.toString(), relative))

    /** The instant a signed [uri] says it stops being accepted, in seconds since the epoch, or null. */
    private fun expirySecondsOf(uri: Uri): Long? =
        runCatching { uri.getQueryParameter(EXPIRES) }.getOrNull()?.toLongOrNull()

    /** A header by name, case-insensitively as HTTP names are, with its values joined. */
    private fun Map<String, List<String>>.header(name: String): String? =
        entries.firstOrNull { name.equals(it.key, ignoreCase = true) }?.value?.joinToString(", ")

    private const val CACHE_CONTROL = "Cache-Control"
    private const val ALLOW_ORIGIN = "Access-Control-Allow-Origin"
    private const val ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials"

    /** spec: WHATWG Fetch §3.2.3 — the wildcard an origin emits when it means to allow everyone. */
    private const val ANY_ORIGIN = "*"

    /** spec: WHATWG Fetch §3.2.4 — `Access-Control-Allow-Credentials` has exactly this one true value. */
    private const val ALLOWED = "true"

    /** The query parameter a signed URL states its own expiry in. See [tokenExpiringInWindow]. */
    private const val EXPIRES = "expires"

    /**
     * How many of the content's own durations a token has to cover before it is not worth reporting.
     *
     * Two: the content, and as long again for the pauses a viewer takes inside it. Argued at
     * [tokenExpiringInWindow], which is the rule that spends it.
     */
    private const val VIEWING_CONTENT_DURATIONS = 2

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MICROS_PER_MILLI = 1_000L
    private const val MICROS_PER_SECOND = 1_000_000L
}
