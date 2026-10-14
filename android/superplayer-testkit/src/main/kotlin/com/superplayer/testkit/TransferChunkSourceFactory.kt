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

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.test.utils.FakeAdaptiveDataSet
import androidx.media3.test.utils.FakeChunkSource
import androidx.media3.test.utils.FakeDataSource

/**
 * Media3's own adaptive chunk source, loading through whatever the harness puts in front of it — the
 * fault injector, and under a [ThroughputTrace] the shaper over that.
 *
 * The composition `docs/testing.md` asks for: whatever the harness installed stays installed and the
 * wrappers go in front of it. `FakeChunkSource.Factory` builds its own `DataSource` rather than
 * taking one, and it takes a concrete `FakeDataSource.Factory` that no wrapper can be — so the seam
 * is its one overridable method rather than the factory it holds, and [wrap] is the stack of
 * wrappers as one function.
 *
 * The [TransferListener] is registered on the **upstream** source, which is where the bytes actually
 * move. A wrapper that intercepted the registration and re-raised the callbacks itself could report
 * a byte count the transfer never made; this arrangement cannot.
 */
internal class TransferChunkSourceFactory(
    dataSets: FakeAdaptiveDataSet.Factory,
    dataSources: FakeDataSource.Factory,
    private val wrap: (DataSource) -> DataSource,
) : FakeChunkSource.Factory(dataSets, dataSources) {

    override fun createChunkSource(
        trackSelection: ExoTrackSelection,
        durationUs: Long,
        transferListener: TransferListener?,
    ): FakeChunkSource {
        // The superclass's own fields and the superclass's own steps, with one line added. Held
        // twice they could drift apart, and the copy that drifted would be the one this file forgot
        // to update on the next Media3 upgrade.
        val dataSet = dataSetFactory.createDataSet(trackSelection.trackGroup, durationUs)
        val source = dataSourceFactory.setFakeDataSet(dataSet).createDataSource()
        transferListener?.let { source.addTransferListener(it) }
        return FakeChunkSource(trackSelection, wrap(source), dataSet)
    }
}
