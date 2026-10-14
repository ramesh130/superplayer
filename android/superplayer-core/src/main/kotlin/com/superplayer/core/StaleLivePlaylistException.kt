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

import java.io.IOException

/**
 * A live playlist stopped advancing, and reloading it past every cache on the way did not start it
 * again.
 *
 * Found as the `cause` of the player's error. The `PlaybackException` around it still carries
 * Media3's `ERROR_CODE_IO_UNSPECIFIED`, because error codes are the engine's to assign; this type is
 * what makes the failure *named* rather than merely coded, and it is what an app branches on to tell
 * a viewer "this stream has stopped updating" rather than "check your connection".
 *
 * ```kotlin
 * when (val cause = player.playerError?.cause) {
 *     is StaleLivePlaylistException -> showStreamStalled(cause.likelyCause)
 *     else -> showGenericError()
 * }
 * ```
 *
 * ## What SuperPlayer tried first
 *
 * A live media playlist has to gain a segment within one and a half target durations of its last
 * (// spec: RFC 8216 §6.2.1). One that has not is not necessarily dead: the usual cause is a cache
 * rule applied to a whole path, so that every intermediary hands back the copy it took first while
 * the origin carries on publishing. So SuperPlayer reloads it with `Cache-Control: no-cache` from
 * then on, and in the common case that is the end of it — the origin's copy arrives, playback
 * continues, and nothing is raised.
 *
 * This is raised only when those reloads bring nothing new either, on the reload before Media3
 * would have judged the playlist stuck — so Media3's untyped `PlaylistStuckException` never fires,
 * and after the engine's own retries of the failed load, the session ends with a name and a likely
 * cause rather than without either. `LivePlaylistRevalidation`
 * holds the bounds and the reasoning for each.
 *
 * Every property is evidence the player actually observed, and none of it is a guess dressed as a
 * fact: [likelyCause] says what that evidence points at, and says so as a likelihood.
 */
public class StaleLivePlaylistException internal constructor(
    /** The media playlist that stopped advancing, as the engine requested it. */
    public val playlistUri: String,
    /** Its `EXT-X-TARGETDURATION`, which every bound this was raised against is a multiple of. */
    public val targetDurationMs: Long,
    /** How long the playlist had gone without a new segment when SuperPlayer gave up on it. */
    public val unchangedForMs: Long,
    /**
     * How many reloads asked every cache on the way to fetch the origin's copy, and still brought
     * nothing new. Zero would mean none was attempted, which the bounds are chosen to make impossible.
     */
    public val cacheBypassingReloads: Int,
    /** The `Cache-Control` the playlist was last served with, or null when it was served with none. */
    public val servedCacheControl: String?,
    /** Which party the evidence points at. */
    public val likelyCause: LikelyCause,
) : IOException(
    describe(playlistUri, targetDurationMs, unchangedForMs, cacheBypassingReloads, servedCacheControl, likelyCause),
) {

    /** Which party the evidence points at, from what the responses themselves said. */
    public enum class LikelyCause {

        /**
         * An intermediary is answering with a copy it holds. Either the playlist was served with a
         * shared-cache lifetime longer than a live playlist may stand still — a `max-age` or
         * `s-maxage` past one and a half target durations — or a reload that asked for the origin's
         * copy was answered with an `Age`, which only a stored response carries
         * (// spec: RFC 9111 §5.1).
         *
         * The stream may well be live at the origin; the copy reaching the player is not. The fix
         * is a cache rule at the CDN, and nothing the app does will help.
         */
        INTERMEDIARY_CACHE,

        /**
         * Nothing served says a cache is involved: the reloads that asked every cache to step aside
         * were answered with the same playlist and no sign of having been stored. That is what an
         * origin that has stopped publishing looks like — a stalled encoder or packager.
         *
         * Not proof. RFC 9111 §5.2.1.4 makes `no-cache` in a request a *preference*, and an
         * intermediary that ignores it and strips `Age` is indistinguishable from this side.
         */
        ORIGIN,
    }

    private companion object {

        fun describe(
            playlistUri: String,
            targetDurationMs: Long,
            unchangedForMs: Long,
            cacheBypassingReloads: Int,
            servedCacheControl: String?,
            likelyCause: LikelyCause,
        ): String {
            val observed = "The live playlist $playlistUri gained no segment for $unchangedForMs ms " +
                "(target duration $targetDurationMs ms), and $cacheBypassingReloads reload(s) with " +
                "Cache-Control: no-cache brought nothing new."
            val served = servedCacheControl?.let { " It was served Cache-Control: $it." } ?: ""
            val likely = when (likelyCause) {
                LikelyCause.INTERMEDIARY_CACHE ->
                    " Likely a stale copy held by an intermediary cache rather than a dead stream."

                LikelyCause.ORIGIN ->
                    " Nothing served points at a cache; likely the origin has stopped publishing."
            }
            return observed + served + likely
        }
    }
}
