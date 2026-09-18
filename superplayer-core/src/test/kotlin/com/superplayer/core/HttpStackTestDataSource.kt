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
import androidx.test.core.app.ApplicationProvider

/**
 * The adapter's own `DataSource`, for the handful of assertions that are about **one HTTP exchange**
 * rather than about playback — a byte range, a redirect, a cancelled read. Each of them is a
 * documented exception to `docs/testing.md`'s *Why assertions stop at the facade*, argued where it is
 * made rather than here.
 *
 * It exists because the chain *asks* a stack for its factory rather than holding one (#313): only the
 * platform's stack needs a `Context` and an API level, but the question is asked of every stack, so a
 * test asking it has to supply a context too. This is the one line that does, instead of the same
 * line in each of the three classes that need it.
 *
 * `superplayer-abr`'s `TransparentGzipEstimateTest` cannot reach it — a test source set is not
 * published to another module — and asks `httpFactory` directly for that reason.
 */
internal fun HttpStack.testDataSource(): DataSource =
    httpFactory(ApplicationProvider.getApplicationContext()).createDataSource()
