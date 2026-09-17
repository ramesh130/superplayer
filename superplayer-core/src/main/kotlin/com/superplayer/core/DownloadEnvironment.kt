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

import androidx.media3.datasource.DataSource
import java.util.concurrent.Executor

/**
 * Where a download's loads run and what they travel over, when that is not the device's own network.
 *
 * **A consumer never has one.** What produces one is `superplayer-testkit`'s
 * `PlaybackHarness.downloadEnvironment`. What is to take one is `superplayer-offline`'s download store
 * (#240), through a setter internal to that module, so the only code able to hand one to a store will
 * be that module's own tests; a store built without one fetches over the platform's HTTP stack, as a
 * player built without the engine configurator does. Until #240 the harness's own tests drive Media3's
 * download manager over it directly.
 *
 * It is public for one reason, and the shape is `ContentCache`'s (ADR-0010 rule 3): the harness is
 * phase 2 and `superplayer-offline` phase 7, so neither can name the other's types, and a type both can
 * name has to be core's. Its members are internal because they are Media3's `@UnstableApi`
 * vocabulary (ADR-0001 rule 2), and its constructor is internal so that only a friend of core can make
 * one. `docs/testing.md`'s *Downloads* section is the argument for why this is the player seam's twin
 * rather than a second seam.
 */
public abstract class DownloadEnvironment internal constructor() {

    /**
     * What sits at the bottom of a download's chain in place of the HTTP stack: the harness's origin,
     * fault injector and shaper, the same composition a harness-built player loads through.
     */
    internal abstract val transport: DataSource.Factory

    /**
     * Where a download's segment loads run: handed to Media3's downloader factory in place of the
     * direct executor it uses by default, so the harness owns the threads and can tell when a load
     * has finished rather than only when its transfer closed (`HarnessLoadThreads`' argument).
     */
    internal abstract val loadExecutor: Executor
}
