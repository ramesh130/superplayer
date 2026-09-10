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

/**
 * How CMCD (CTA-5004) travels on the requests this player makes — or that it does not travel at all.
 *
 * CMCD is the client telling the CDN what the client believes: the bitrate it asked for, how much it
 * has buffered, the throughput it measured, whether this request is holding up playback. The CDN
 * writes those keys into its own logs, so the app's QoE warehouse and the CDN's access log end up
 * describing the same session. That correlation is the whole point: when a stream is bad and the app
 * and the CDN disagree about whose fault it is, CMCD is what ends the argument — and it has to be in
 * place before the incident rather than added during it.
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setCmcdMode(CmcdMode.QUERY_PARAMETERS) // only if your CDN needs it; see below
 *     .build()
 * ```
 *
 * Left unset, a player emits under the mode its [PlaybackProfile] defaults to, which today is
 * [REQUEST_HEADERS] for every profile — including [PlaybackProfile.DATA_SAVER]. The per-profile
 * table and the reasoning behind it are in `StaticCmcdPolicy`.
 *
 * ## Which one to choose, and why the default is [REQUEST_HEADERS]
 *
 * This is a per-deployment fact rather than a preference, because it depends on what sits in front of
 * the media: some CDNs log one and not the other, and one of the two can break a URL outright.
 *
 * [REQUEST_HEADERS] is the default because its failure mode is the survivable one. A CDN that is not
 * configured to log the `CMCD-*` headers strips them and playback is untouched — the cost is missing
 * data, which is visible in the CDN's own logs as an absence. A CDN that is not configured for the
 * `CMCD` *query parameter* can reject the request outright: a signed URL whose signature covers the
 * query string (CloudFront signed URLs, Akamai token authentication) fails verification once an
 * unexpected parameter is appended, and playback stops with a 403. Choosing the mode that can only
 * lose telemetry over the one that can lose playback is not a close call.
 *
 * [QUERY_PARAMETERS] is nonetheless the right answer for some deployments, which is why it is here:
 * an origin or edge that logs query strings but not custom request headers cannot see CMCD any other
 * way, and a browser-shaped delivery path avoids the CORS preflight that custom headers force.
 * Before switching, check two things with whoever runs the CDN — that the signature scheme, if any,
 * tolerates the added parameter, and that the cache key ignores it. A cache key that includes an
 * unignored `CMCD` parameter turns every request into a miss, because the parameter's value changes
 * on every one.
 *
 * ## What is emitted, and what is not
 *
 * Only CMCD v1, which is what the pinned Media3 implements — v2's keys and its collector-endpoint
 * reporting mode do not exist in the engine, so they are not a SuperPlayer omission. Emission is the
 * work of the DASH and HLS chunk sources, so a progressive `.mp4` carries no CMCD however this is
 * set: there is no adaptive request for the keys to describe.
 */
public enum class CmcdMode {

    /**
     * No CMCD at all: no headers, no query parameter, and no configuration handed to the engine.
     *
     * For a deployment where the keys reach nobody who would read them, or where the operator of the
     * delivery path has asked for the requests to be left exactly as they were. Turning it off does
     * not turn telemetry off — [TelemetrySink] is a separate seam and keeps working — it only means
     * a row in the CDN's log can no longer be joined to a row in the app's warehouse.
     */
    DISABLED,

    /**
     * The keys travel as the four `CMCD-Object`, `CMCD-Request`, `CMCD-Session` and `CMCD-Status`
     * request headers CTA-5004 defines. The default; see the class KDoc for why.
     */
    REQUEST_HEADERS,

    /**
     * The keys travel as a single URL-encoded `CMCD` query parameter, as CTA-5004 allows.
     *
     * Changes the request URL, which is what makes it the mode to check a signing scheme and a cache
     * key against before adopting. See the class KDoc.
     */
    QUERY_PARAMETERS,
}
