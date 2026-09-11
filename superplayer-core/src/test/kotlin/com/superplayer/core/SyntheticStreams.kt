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

import androidx.media3.test.utils.FakeDataSet
import com.superplayer.testmedia.SyntheticDashStream
import com.superplayer.testmedia.SyntheticHlsStream

/**
 * Serves `superplayer-testmedia`'s synthetic streams from Media3's [FakeDataSet].
 *
 * The streams themselves live in `superplayer-testmedia` so that this module's tests and
 * `superplayer-testkit`'s main source set can both reach one copy — see `docs/testing.md`. That
 * module names no Media3 type at all, which is what lets it sit below both, so a stream arrives here
 * as URI-to-bytes and the three lines that put it in a [FakeDataSet] are the caller's.
 *
 * Extension functions rather than a helper object, so that every call site reads exactly as it did
 * when the generators lived in this package.
 */
internal fun SyntheticHlsStream.addTo(fakeDataSet: FakeDataSet, segmentCount: Int = 1): FakeDataSet {
    resources(segmentCount).forEach { (uri, bytes) -> fakeDataSet.setData(uri, bytes) }
    return fakeDataSet
}

/** The DASH counterpart of [SyntheticHlsStream.addTo], on the same terms. */
internal fun SyntheticDashStream.addTo(fakeDataSet: FakeDataSet, segmentCount: Int = 1): FakeDataSet {
    resources(segmentCount).forEach { (uri, bytes) -> fakeDataSet.setData(uri, bytes) }
    return fakeDataSet
}
