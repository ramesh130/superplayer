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

package com.superplayer.drm

import com.superplayer.core.PlaybackDrm

/**
 * This module's entry point: what `SuperPlayer.Builder.setDrm` is handed.
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setProfile(PlaybackProfile.VIDEO_ON_DEMAND)
 *     .setDrm(Drm.widevine(WidevineConfig(licenceUri = "https://licence.example/widevine")))
 *     .build()
 * ```
 *
 * A factory rather than a constructor for the reason `Resilience.standard` and
 * `AdaptivePolicy.forProfile` are ones: what it returns is a [PlaybackDrm] — a core type naming no
 * Media3 class — while what it actually builds is core's internal `EngineDrmExtension`, which is how
 * a `DrmSessionManager`, the `ExoMediaDrm` under it and the licence callback reach the engine without
 * any of them appearing in a signature a consumer can see (ADR-0012 rule 3, ADR-0001 rule 2).
 *
 * The one argument is a [WidevineConfig], and what it carries is what only the app knows: which
 * licence server answers for this content. Everything else this module does — when a session is
 * opened, when it is released, what is asked of the device — is correctness rather than a caller's to
 * vary, on the terms ADR-0011 rule 12 set for the same question in Phase 5.
 */
public object Drm {

    /**
     * Widevine, against the licence server [config] names.
     *
     * One object may be handed to many players: nothing returned here holds a player's state, and
     * everything with a lifetime — the session manager, the device handle, the callback — is built
     * per player, as the engine is.
     */
    @JvmStatic
    public fun widevine(config: WidevineConfig): PlaybackDrm = WidevineDrm(config)
}
