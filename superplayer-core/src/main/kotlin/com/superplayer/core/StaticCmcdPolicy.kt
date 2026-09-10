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
