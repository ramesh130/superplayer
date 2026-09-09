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
 * A [PlaybackService] for `SuperPlayerServiceTest` to drive, and the smallest possible one.
 *
 * A service is constructed by the system rather than by the test, so there is no constructor to hand
 * a player to and the factory has to be static. That is a compromise this file makes on the test's
 * behalf and not a shape any app should copy — a real service builds its own player, as
 * `DemoPlaybackService` does, and needs no hook at all.
 */
class TestPlaybackService : PlaybackService() {

    override fun onCreatePlayer(): SuperPlayer =
        checkNotNull(playerFactory) { "No player factory was set for TestPlaybackService" }.invoke()

    // `catalog`, not `contentResolver`: a Service *is* a Context, and `getContentResolver()` is
    // already on it. The clash is silent and resolves in the platform's favour.
    override fun onResolveContent(contentId: String): MediaRequest? =
        catalog?.resolve(contentId)

    companion object {
        /** Set by the test before the service is created; cleared after. */
        var playerFactory: (() -> SuperPlayer)? = null

        /** The catalog this service answers with, or null to decline every id. */
        var catalog: MediaRequestResolver? = null
    }
}
