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
 * The player was built to play from a licence already on the device, and that licence has run out —
 * so nothing was played rather than a licence being fetched behind the app's back (ADR-0012 rule 9,
 * issue #210).
 *
 * Raised by `superplayer-drm` and carried out of it as evidence, which is the shape
 * [SecurityDowngradeRefusedException] has and the reason this type is core's: the module that found
 * the fact keeps no taxonomy of its own (ADR-0012 rule 5). Its error code says exactly what happened
 * — a licence expired — so `superplayer-resilience` names it `FailureClass.Drm.LicenceExpired` off
 * the band without reading these fields, and they are here for the bug report rather than for the
 * classification.
 *
 * **It should be rare, because the fact was readable first.** `OfflineLicenceStore.licenceFor`
 * answers both durations with no device, no player and no network, which is what lets an app tell a
 * viewer to go online and refresh a download instead of showing them a playback error. This is what
 * happens when a player is built with an expired licence anyway.
 */
public class OfflineLicenceExpiredException internal constructor(

    /** The content whose stored licence has expired: a [MediaRequest.contentId]. */
    public val contentId: String,

    /**
     * Milliseconds of viewing window that were left when the player was built, which is zero whenever
     * the window is what ran out.
     *
     * The two durations are reported separately because they mean different things — an unwatched
     * rental that expired and a viewing window that closed are different things to tell a viewer —
     * and a message that could not tell them apart is the one `PRD.md` §3.2 is complaining about.
     */
    public val playbackDurationRemainingMs: Long,

    /** Milliseconds of entitlement that were left, zero whenever the entitlement is what ran out. */
    public val licenceDurationRemainingMs: Long,
) : IOException(
    "The offline licence for $contentId has expired: ${licenceDurationRemainingMs}ms of licence and " +
        "${playbackDurationRemainingMs}ms of playback remaining",
)
