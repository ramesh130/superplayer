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

package com.superplayer.abr

import android.content.Context
import android.media.MediaFormat
import android.net.ConnectivityManager
import androidx.media3.common.util.Clock
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.PlaybackProfile
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.PlaybackHarness
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeoutException

/**
 * ADR-0010 rule 9 for `superplayer-abr`: one adaptive policy serves one pool — one engine, one
 * oracle, one decision — and goes when the pool does.
 */
@RunWith(AndroidJUnit4::class)
class AdaptivePolicyPoolTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * A pool built with an adaptive policy builds as many players as it is asked for — a second
     * `build()` with one policy object was refused before a pool could share it — and they decide
     * alike. Every connectivity callback the policy's engine registered is gone once the pool is
     * released, which is the oracle released with the last engine rather than with the first.
     */
    @Test
    fun oneAdaptivePolicyServesAWholePoolAndIsReleasedWithIt() {
        // Before any player, because the platform caches its codec list on first read.
        DeviceStatement.declareVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC)
        DeviceStatement.declareAppHeap(megabytes = 2048)
        val callbacksBefore = networkCallbackCount()

        val pool = harness.buildPool(
            maxSize = 2,
            profile = PlaybackProfile.SHORT_FORM,
            policy = AdaptivePolicy.forProfile(context, PlaybackProfile.SHORT_FORM),
        )
        val first = checkNotNull(pool.acquire())
        val second = checkNotNull(pool.acquire())

        assertThat(second.playbackDecision).isEqualTo(first.playbackDecision)
        assertThat(networkCallbackCount()).isGreaterThan(callbacksBefore)

        pool.release()

        // An engine releases its load control on its own playback thread, which may finish after
        // `release()` has returned under the harness's clock; the oracle goes when the last one does.
        try {
            runMainLooperUntil({ networkCallbackCount() == callbacksBefore }, RELEASE_BOUND_MS, Clock.DEFAULT)
        } catch (_: TimeoutException) {
            // Reported below, naming what is still registered.
        }
        assertWithMessage("callbacks still registered: ${networkCallbacks().map { it.javaClass.name }}")
            .that(networkCallbackCount()).isEqualTo(callbacksBefore)
    }

    private fun networkCallbacks(): Set<ConnectivityManager.NetworkCallback> =
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks

    private fun networkCallbackCount(): Int = networkCallbacks().size

    private companion object {
        /** Real milliseconds a released engine's playback thread is given to finish. */
        const val RELEASE_BOUND_MS = 5_000L
    }
}
