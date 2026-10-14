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

package com.superplayer.testkit

/**
 * One effect list the engine handed a player's video renderer: what `ExoPlayer.setVideoEffects`
 * delivers, read where Media3 delivers it, which is as far as anything under Robolectric can see
 * (`docs/testing.md`, *A frame output*).
 *
 * [effectCount] is how many effects the list carried, and nothing names them: Media3's `Effect` is
 * `@UnstableApi`, and which effect a module built is that module's business rather than a test's.
 * [beforeRendererEnabled] is whether the list arrived while the renderer had never been enabled — before
 * the first `prepare()` reached it — which is the first of Media3's limits on the method.
 *
 * A delivery is not a frame processed. Robolectric has no GPU and the harness's renderer is Media3's
 * fake, which runs no effect pipeline, so whether frames passed through an effect is a device's to show.
 */
public data class VideoEffectsDelivery(
    val effectCount: Int,
    val beforeRendererEnabled: Boolean,
)
