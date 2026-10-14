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

package com.superplayer.resilience

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one claim about [TokenRefreshLayer] a player cannot be asked: that the engine's transfer
 * listener still reaches the transport through it.
 *
 * `TransferChain`'s rule for every layer is that a registration is forwarded downstream, and a layer
 * that swallowed one would blind bandwidth estimation with nothing in the logs and playback still
 * working — the failure `superplayer-abr` would find out about last. The reading of it available
 * from outside the facade is a bandwidth meter, and `PlaybackHarness` hands a test no way to install
 * one; core's `SuperPlayerResilienceSeamTest` asserts that the *slot* forwards through a whole
 * player, and this asserts that what this module puts in the slot does.
 *
 * Robolectric rather than a plain JVM test for `Uri`, which every `DataSpec` carries — the reason
 * `ErrorClassifierTest` runs under it too.
 */
@RunWith(AndroidJUnit4::class)
class TokenRefreshLayerTest {

    @Test
    fun everyTransferListenerRegistrationReachesTheTransport() {
        val transport = RecordingTransport()
        val source = TokenRefreshLayer { emptyMap() }.over(transport).createDataSource()
        val listener = NoOpTransferListener()

        source.addTransferListener(listener)

        assertThat(transport.created.single().listeners).containsExactly(listener)
    }

    @Test
    fun aRefusalRepairedIsOneSourceOpenedTwiceAndClosedBetween() {
        // The layer's own accounting, which a transport depends on: the refused ask is closed before
        // the repaired one is made, so a source that counts what it has open is not left holding a
        // transfer that failed (`TokenRefreshLayer.open`).
        val transport = RecordingTransport(refuseFirstWith = FORBIDDEN)
        val source = TokenRefreshLayer { mapOf("Authorization" to "Bearer fresh") }.over(transport).createDataSource()

        source.open(DataSpec(Uri.parse("https://cdn.example/segment-1.ts")))

        val transfer = transport.created.single()
        assertThat(transfer.opens).hasSize(2)
        assertThat(transfer.opens.first()).doesNotContainKey("Authorization")
        assertThat(transfer.opens.last()).containsEntry("Authorization", "Bearer fresh")
        assertThat(transfer.closes).isEqualTo(1)
    }

    /** A transport that records what was asked of it, and can refuse the first ask with a status. */
    private class RecordingTransport(private val refuseFirstWith: Int? = null) : DataSource.Factory {

        val created = mutableListOf<Recorded>()

        override fun createDataSource(): DataSource = Recorded(refuseFirstWith).also { created += it }

        class Recorded(private val refuseFirstWith: Int?) : DataSource {

            val listeners = mutableListOf<TransferListener>()

            /** The headers of each ask, in order. */
            val opens = mutableListOf<Map<String, String>>()
            var closes = 0

            override fun addTransferListener(transferListener: TransferListener) {
                listeners += transferListener
            }

            override fun open(dataSpec: DataSpec): Long {
                opens += dataSpec.httpRequestHeaders
                val status = refuseFirstWith
                if (status != null && opens.size == 1) {
                    throw HttpDataSource.InvalidResponseCodeException(
                        status,
                        "Refused $status for ${dataSpec.uri}",
                        /* cause= */ null,
                        /* headerFields= */ emptyMap(),
                        dataSpec,
                        /* responseBody= */ ByteArray(0),
                    )
                }
                return 0
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

            override fun getUri(): Uri? = null

            override fun close() {
                closes++
            }
        }
    }

    /** A listener that is only ever identity-compared. */
    private class NoOpTransferListener : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytes: Int) = Unit
        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    private companion object {
        /** // ref: RFC 9110 §15.5.4. */
        const val FORBIDDEN = 403
    }
}
