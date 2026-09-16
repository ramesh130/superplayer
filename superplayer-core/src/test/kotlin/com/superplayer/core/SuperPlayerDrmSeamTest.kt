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

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The slot ADR-0012 rule 3 adds to core's engine seam, driven by a `PlaybackDrm` hand-written here
 * rather than `superplayer-drm`'s.
 *
 * No licence is acquired here and none is asserted — that is `superplayer-drm`'s
 * `WidevinePlaybackTest`, over a protected stream and a licence server. What is asserted is the seam:
 * that a protection which is also core's extension fills the slot, that what it puts there is invoked
 * with the licence transport and the stated device and its answer reaches the media source factory,
 * and — the claim this file exists for — that a player built **without** `setDrm` fills nothing,
 * invokes nothing and sets no provider at all.
 *
 * Rule 13's own emphasis is that the easy accident here is not an absent slot but a *present* one
 * answering `DRM_UNSUPPORTED`: a provider is an object, it is consulted per item, and a core-only
 * session that acquired one would differ from the session Phase 5 shipped while every test still
 * passed. So the counter below counts the **set**, never the answer.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerDrmSeamTest {

    /** Robolectric has no real codecs; the renderer pipeline runs against shadow ones. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    /**
     * ADR-0012 rule 13, counted: a player built without `setDrm` leaves the DRM slot empty, so
     * nothing is ever asked for a session provider and no `ExoMediaDrm` is reached for. The same
     * count on a player *with* a protection is non-empty and the slot's contents *were* invoked, so
     * the counter is shown to see what it counts.
     *
     * The rule's other half — that a core-only session is unchanged by this phase — is held by the
     * golden traces in `superplayer-telemetry`, exactly as ADR-0010 rule 13's and ADR-0011 rule 14's
     * halves are.
     */
    @Test
    fun aPlayerBuiltWithoutDrmFillsNoSlotAndIsAskedForNoSession() {
        var withoutSlot: EngineConfiguration? = null
        val without = harness.buildPlayer(alsoConfigure = { withoutSlot = it })
        playUntilReady(without)

        assertThat(withoutSlot?.drm).isNull()

        val sessions = RecordingSessions()
        var withSlot: EngineConfiguration? = null
        val with = harness.buildPlayer(
            drm = TestDrm(sessions),
            alsoConfigure = {
                withSlot = it
                it.exoMediaDrm = STATED_DEVICE
            },
        )
        playUntilReady(with)

        assertThat(withSlot?.drm).isNotNull()
        // Invoked, and invoked with both of the things only core can hand it: a transport to carry a
        // licence over, and the device a test stated. A slot filled but never called would be a
        // provider that never reached a media source factory.
        assertThat(sessions.licenceTransports()).hasSize(1)
        assertThat(sessions.statedDevices()).containsExactly(STATED_DEVICE)
    }

    /**
     * The extension runs *before* the test configurator, which is why the device is a slot read at
     * chain-composition time rather than an argument the extension could have read for itself: when
     * `superplayer-drm` is asked to fill its slot, a test has not yet said what device this is.
     *
     * Asserted rather than assumed, because the whole Robolectric DRM story depends on it — there is
     * no `ShadowMediaDrm`, so a session opened against the platform's own `MediaDrm` cannot be opened
     * at all.
     */
    @Test
    fun theStatedDeviceIsWhateverTheTestConfiguratorSaidAfterTheExtensionRan() {
        val sessions = RecordingSessions()
        val player = harness.buildPlayer(
            drm = TestDrm(sessions),
            alsoConfigure = { it.exoMediaDrm = STATED_DEVICE },
        )
        playUntilReady(player)

        assertThat(sessions.statedDevices()).containsExactly(STATED_DEVICE)
    }

    private fun playUntilReady(player: SuperPlayer) {
        player.setMediaItem(MediaItem.fromUri(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI))
        player.prepare()
        TestPlayerRunHelper.playUntilPosition(player.exoPlayer, /* mediaItemIndex= */ 0, /* positionMs= */ 0)
        TestPlayerRunHelper.runUntilPlaybackState(player.exoPlayer, Player.STATE_READY)
    }

    /** A protection that fills the slot and acquires nothing: the seam, with no Widevine behind it. */
    private class TestDrm(private val sessions: RecordingSessions) : EngineDrmExtension {
        override fun configureEngine(configuration: EngineConfiguration) {
            configuration.drm = sessions
        }
    }

    /**
     * What the slot was handed, and a provider that opens no session.
     *
     * `DRM_UNSUPPORTED` is exactly what rule 13 says is *not* nothing, and it is the right answer
     * here for the same reason: this test's content declares no protection, so an item asking for a
     * session would be the test's own bug rather than the seam's.
     */
    private class RecordingSessions : LicenceSessions {
        private val transports = mutableListOf<DataSource.Factory>()
        private val devices = mutableListOf<ExoMediaDrm.Provider?>()

        override fun over(
            licenceTransport: DataSource.Factory,
            mediaDrm: ExoMediaDrm.Provider?,
        ): DrmSessionManagerProvider {
            transports += licenceTransport
            devices += mediaDrm
            return DrmSessionManagerProvider { DrmSessionManager.DRM_UNSUPPORTED }
        }

        fun licenceTransports(): List<DataSource.Factory> = transports.toList()

        fun statedDevices(): List<ExoMediaDrm.Provider?> = devices.toList()
    }

    private companion object {

        /**
         * A device that is never opened against: what is asserted is that this exact object reached
         * the slot, and nothing in this file plays protected content.
         */
        val STATED_DEVICE = ExoMediaDrm.Provider { error("No session is opened in this test") }
    }
}
