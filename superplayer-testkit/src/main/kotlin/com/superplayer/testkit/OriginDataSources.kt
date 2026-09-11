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

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.Clock
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import kotlin.math.min

/**
 * An origin that keeps publishing: what it serves is a function of how long it has been running on
 * the harness's clock.
 *
 * Media3's `FakeDataSet` serves bytes fixed when the test began, which is every stream here except
 * a live HLS one. A live media playlist is *defined* by changing — RFC 8216 §6.2.1 has the server
 * publish a new version as each segment appears — so a live stream served from fixed bytes is a live
 * stream that has stopped, which is `HostileManifests.hlsCachedLivePlaylist` rather than a healthy
 * stream. [publication] says what exists after a given number of milliseconds; this serves it.
 *
 * Paced on the same fake clock the engine runs on, so a segment is published when the test advances
 * time past it and not before. A resource that is not published — one that has slid out of a live
 * window — is a 404, which is what a real origin answers.
 */
internal class LiveOriginDataSource(
    private val publication: (elapsedMs: Long) -> Map<String, ByteArray>,
    private val elapsedMs: () -> Long,
) : BaseDataSource(/* isNetwork= */ true) {

    private var uri: Uri? = null
    private var bytes: ByteArray? = null
    private var readPosition = 0
    private var bytesRemaining = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val data = publication(elapsedMs())[dataSpec.uri.toString()]
            ?: throw HttpDataSource.InvalidResponseCodeException(
                FaultScript.HTTP_NOT_FOUND,
                "Not published at the origin: ${dataSpec.uri}",
                /* cause= */ null,
                /* headerFields= */ emptyMap(),
                dataSpec,
                /* responseBody= */ ByteArray(0),
            )
        if (dataSpec.position > data.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        readPosition = dataSpec.position.toInt()
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            data.size - readPosition
        } else {
            min(dataSpec.length, (data.size - readPosition).toLong()).toInt()
        }
        bytes = data
        transferStarted(dataSpec)
        return bytesRemaining.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT
        val read = min(length, bytesRemaining)
        System.arraycopy(checkNotNull(bytes), readPosition, buffer, offset, read)
        readPosition += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (bytes != null) {
            bytes = null
            transferEnded()
        }
        uri = null
    }

    /** One origin per player, publishing from the moment it is built. */
    class Factory(
        private val publication: (elapsedMs: Long) -> Map<String, ByteArray>,
        private val clock: Clock,
    ) : DataSource.Factory {

        private val startedAtMs = clock.elapsedRealtime()

        override fun createDataSource(): DataSource =
            LiveOriginDataSource(publication) { clock.elapsedRealtime() - startedAtMs }
    }
}

/**
 * Serves the response headers a stream declares, on top of whatever serves its bytes.
 *
 * `FakeDataSource` reports no headers at all, and `HostileManifests.hlsCachedLivePlaylist`'s defect
 * is one: a live playlist served `Cache-Control: public, max-age=600`. Declared on the stream and
 * served by nothing, it could only be read by a test; served here, it is read by the player, which
 * is where the difference between "the stream stopped" and "a cache is holding the stream" is made.
 * Transparent for every URI the map does not name, and for the listener, which is the upstream's.
 */
internal class HeaderServingDataSource(
    private val upstream: DataSource,
    private val headers: Map<String, Map<String, String>>,
) : DataSource {

    private var served: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        val declared = headers[dataSpec.uri.toString()].orEmpty().mapValues { (_, value) -> listOf(value) }
        served = upstream.responseHeaders + declared
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = served

    override fun close() {
        served = emptyMap()
        upstream.close()
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val headers: Map<String, Map<String, String>>,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = HeaderServingDataSource(upstream.createDataSource(), headers)
    }
}
