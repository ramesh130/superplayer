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

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.upstream.CmcdConfiguration

/**
 * The one place a [CmcdMode] becomes Media3 configuration — `EngineBinding.kt` for CTA-5004.
 *
 * Nothing here builds a header or a query string. Media3 owns the wire format: the DASH and HLS chunk
 * sources collect the keys as they issue each request and attach them to the `DataSpec`, and all this
 * file supplies is *which* mode, *which* session and *which* content. That division is deliberate —
 * a hand-built `CMCD-Request` header would be a second implementation of a spec the engine already
 * implements, and ADR-0001 rule 1 rules that out.
 *
 * ## What Media3 sends, and where each key comes from
 *
 * Every key CTA-5004 v1 defines and Media3 knows how to fill is allowed through, because the values
 * are ones the player already believes and a CDN that is not interested in one simply ignores it. The
 * six this issue is accountable for:
 *
 * ```
 *   br   spec: CTA-5004 §3.1 encoded bitrate — the kbps of the object being requested, from the
 *        Format the chunk source selected. Reads as ABR's decision, seen from the CDN's side.
 *   bl   spec: CTA-5004 §3.1 buffer length — buffered media ahead of the play head in ms, rounded
 *        to 100ms. The number that says whether this request is comfortable or desperate.
 *   mtp  spec: CTA-5004 §3.1 measured throughput — the engine's own bandwidth estimate in kbps.
 *        Phase 3 changes what produces this estimate; CMCD carries the new number untouched, which
 *        is the argument for CMCD being measurement rather than policy.
 *   dl   spec: CTA-5004 §3.1 deadline — ms until the first sample of this object must be available,
 *        which is `bl` divided by the playback rate. A CDN can read it as a per-request SLA.
 *   su   spec: CTA-5004 §3.1 startup — present, valueless, when the object is needed urgently:
 *        startup, a seek, or recovery from an empty buffer. The key that separates "slow" from
 *        "slow at the moment it mattered".
 *   sid  spec: CTA-5004 §3.1 session id — see below; this is the join.
 * ```
 *
 * ## `sid` is the telemetry session id, exactly
 *
 * Not correlated, not derived — the same string, because [MeasurementSession] mints it once and both
 * seams read it from there. So a CDN access log and the app's QoE warehouse join on equality:
 *
 * ```sql
 * SELECT * FROM cdn_log c JOIN qoe_events q ON c.cmcd_sid = q.session_id
 * ```
 *
 * `docs/telemetry-schema.md` states it as a promise, and this file and [MeasurementSession] are what
 * keep it true. A request with no `sid` — content set through Media3's own `setMediaItem`, which has
 * no identity — is a request that could not have been joined anyway; see [MeasurementSession.idFor].
 *
 * ## Why the per-profile table is not [PlaybackPolicy]'s
 *
 * ADR-0005 puts all buffering and track-selection policy behind `PlaybackPolicy`, and CMCD is
 * neither: it changes no decision the player makes, it only moves data outward. Routing it through a
 * boundary whose whole vocabulary is buffers and track ceilings would say something false about what
 * it is. The profile still gets a say, because the trade CMCD makes is a per-use-case one — see
 * [StaticCmcdPolicy].
 */

/**
 * Which mode each [PlaybackProfile] emits under when a consumer does not choose one.
 *
 * `PRD.md` §3.1 makes CMCD non-optional in the CDN-facing profiles. All four are CDN-facing, and all
 * four default to [CmcdMode.REQUEST_HEADERS] — so the table is uniform today, and it exists anyway
 * because the *reasoning* is per-profile and the one profile with a real case against is the one most
 * likely to be argued about later.
 *
 * ```
 *   VIDEO_ON_DEMAND  REQUEST_HEADERS  The case CMCD was designed for. Long sessions, many segments,
 *                                     and the diagnosis that matters — was the rebuffer at minute
 *                                     twelve the network, the client or the edge — is unanswerable
 *                                     without the CDN's half of it.
 *   LIVE_LINEAR      REQUEST_HEADERS  Where it is worth most. A live incident is happening to every
 *                                     viewer at once and cannot be reproduced afterwards, so the
 *                                     data has to already be in the CDN's log when the call starts.
 *   SHORT_FORM       REQUEST_HEADERS  Many short requests rather than few long ones, so the relative
 *                                     overhead is at its highest here — still far below the noise of
 *                                     a single segment, and `su` on a feed is the difference between
 *                                     "the CDN is slow" and "the CDN is slow on the request the
 *                                     viewer is waiting for".
 *   DATA_SAVER       REQUEST_HEADERS  The judgement call, and it goes the same way. See below.
 * ```
 *
 * ## Why [PlaybackProfile.DATA_SAVER] emits too
 *
 * CMCD is not free on a metered connection, and that is the honest case against. The size of it: the
 * four headers come to roughly 150 bytes on a request whose response is a segment of hundreds of
 * kilobytes — under a tenth of a percent, and paid on the request rather than the response, which is
 * the cheaper direction on an asymmetric link.
 *
 * Against that, DATA_SAVER is the profile whose sessions get complained about. It is chosen when the
 * viewer is on cellular or has asked to spend less, which is exactly the population whose playback
 * problems are hardest to reproduce and most often blamed on the CDN. Blinding the CDN there to save
 * a tenth of a percent would trade the diagnosis for a rounding error. A consumer who disagrees for
 * their own deployment says so with `SuperPlayer.Builder.setCmcdMode(CmcdMode.DISABLED)`, which is
 * why that value exists.
 */
internal object StaticCmcdPolicy {

    /** The mode [profile] emits under when `SuperPlayer.Builder.setCmcdMode` was not called. */
    fun defaultModeFor(profile: PlaybackProfile): CmcdMode = when (profile) {
        PlaybackProfile.VIDEO_ON_DEMAND,
        PlaybackProfile.LIVE_LINEAR,
        PlaybackProfile.SHORT_FORM,
        PlaybackProfile.DATA_SAVER,
        -> CmcdMode.REQUEST_HEADERS
    }
}

/**
 * The CMCD configuration factory the media source factory is given, or null for [CmcdMode.DISABLED].
 *
 * Null rather than a configuration that emits nothing, so that a disabled player hands Media3 nothing
 * to call: `DefaultMediaSourceFactory` skips the whole path when it has no factory, which is what
 * makes "disabled" cost nothing rather than cost a callback per media source.
 *
 * Media3 calls the returned factory once per media source — that is, once per item the player takes
 * on — which is why the session id is read from [session] at that moment rather than captured here.
 */
internal fun CmcdMode.toCmcdConfigurationFactory(
    session: MeasurementSession,
): CmcdConfiguration.Factory? {
    val transmissionMode = when (this) {
        CmcdMode.DISABLED -> return null
        CmcdMode.REQUEST_HEADERS -> CmcdConfiguration.MODE_REQUEST_HEADER
        CmcdMode.QUERY_PARAMETERS -> CmcdConfiguration.MODE_QUERY_PARAMETER
    }

    return CmcdConfiguration.Factory { mediaItem ->
        CmcdConfiguration(
            /* sessionId= */ session.idFor(mediaItem.mediaId),
            /* contentId= */ mediaItem.cmcdContentId(),
            // Media3's own defaults: every key it knows how to fill is allowed, and no requested
            // maximum throughput is declared — `rtp` is a hint to the CDN about what to reserve, and
            // SuperPlayer has no number for it that the engine's own estimate does not already say
            // better through `mtp`.
            /* requestConfig= */ object : CmcdConfiguration.RequestConfig {},
            transmissionMode,
        )
    }
}

/**
 * This item's `cid`: the app's own content id, or null when there is not one worth sending.
 *
 * Null in two cases, and neither is a defect. An item set through Media3's `setMediaItem` carries
 * [MediaItem.DEFAULT_MEDIA_ID], which every such item shares and which would therefore group
 * unrelated content together in the CDN's log. And an id longer than CTA-5004's 64-character
 * ceiling is dropped rather than truncated: a truncated id joins to nothing on the app's side and
 * can collide with another title's prefix, which is worse than an absent key — and Media3's
 * `CmcdConfiguration` rejects it outright with an `IllegalArgumentException`, so sending it is not
 * an option regardless. A consumer whose ids are that long and who wants `cid` shortens them at the
 * source, where the mapping back is theirs to keep.
 */
private fun MediaItem.cmcdContentId(): String? = mediaId
    .takeIf { it != MediaItem.DEFAULT_MEDIA_ID }
    // spec: CTA-5004 §3.1 caps `cid` at 64 characters.
    ?.takeIf { it.length <= CmcdConfiguration.MAX_ID_LENGTH }
