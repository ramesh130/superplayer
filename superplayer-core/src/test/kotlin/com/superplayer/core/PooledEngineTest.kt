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
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Core's side of ADR-0010 rules 7, 8, 9 and 13: what a [PlayerPool] does when something is attached
 * to it, driven with a hand-written [PoolAttachment] because `superplayer-preload` is a later module
 * and core's seam has to hold on its own. The coordinator's own tests drive the real thing.
 */
@RunWith(AndroidJUnit4::class)
class PooledEngineTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    /** Pools built here, released after each test so no shared thread outlives the test that started it. */
    private val pools = mutableListOf<PlayerPool>()

    /** A device that affords more than one player, so a pool of two is a pool of two (`PlayerPoolTest` says why). */
    @Before
    fun declareTheDeviceThisRunsOn() {
        TestDevice.declareCapableDevice()
    }

    @After
    fun releasePools() {
        pools.forEach { it.release() }
    }

    private fun buildPool(maxSize: Int, telemetry: TelemetryCollector? = null, attachment: PoolAttachment? = null): PlayerPool =
        harness.buildPool(maxSize = maxSize, telemetry = telemetry, attachment = attachment).also { pools += it }

    /** Rule 8: an attachment serves every player a pool builds, so it comes before the first. */
    @Test
    fun anAttachmentAfterThePoolsFirstPlayerIsRefusedNamingTheRule() {
        val pool = buildPool(maxSize = 2)
        checkNotNull(pool.acquire())

        val refused = assertThrows(IllegalStateException::class.java) { pool.attach(RecordingAttachment()) }
        assertThat(refused).hasMessageThat().contains("ADR-0010 rule 8")
    }

    /**
     * Rule 13, counted, with rule 9 as the counter's proof: the players of a pool with nothing
     * attached each run on a playback thread of their own, and the players of a pool with an
     * attachment share one, whose components the attachment is handed once. The shared thread ends
     * once the pool's last player has released.
     *
     * Read through `exoPlayer.playbackLooper`, the engine's own answer to which thread it plays on:
     * which thread is shared is the claim, and no other surface states it.
     */
    @Test
    fun onlyAnAttachedPoolRunsItsPlayersOnOneSharedThreadAndItEndsWithThePool() {
        val plain = buildPool(maxSize = 2)
        val plainLoopers = listOf(checkNotNull(plain.acquire()), checkNotNull(plain.acquire())).map { it.exoPlayer.playbackLooper }
        assertThat(plainLoopers.toSet()).hasSize(2)

        val attachment = RecordingAttachment()
        val attached = buildPool(maxSize = 2, attachment = attachment)
        val sharedLoopers = listOf(checkNotNull(attached.acquire()), checkNotNull(attached.acquire())).map { it.exoPlayer.playbackLooper }
        assertThat(sharedLoopers.toSet()).hasSize(1)
        assertThat(attachment.assembled).hasSize(1)

        attached.release()
        val sharedThread = sharedLoopers.first().thread
        runMainLooperUntil({ !sharedThread.isAlive }, THREAD_EXIT_BOUND_MS, Clock.DEFAULT)
        assertThat(attachment.releasing).isEqualTo(1)
    }

    /**
     * Rule 7: a source the attachment holds for the adopted item plays, found by the item a pooled
     * player's adoption builds, and the session that opens claims the id minted for it before it was
     * created — the id its prefetched requests carried. Content the attachment holds nothing for is
     * played cold under an id of its own.
     */
    @Test
    fun aWarmSourcePlaysUnderTheSessionIdMintedForItAndOtherContentLoadsCold() {
        val events = mutableListOf<TelemetryEvent>()
        val attachment = RecordingAttachment()
        val pool = buildPool(maxSize = 1, telemetry = RecordingTelemetry { events += it }, attachment = attachment)
        val player = checkNotNull(pool.acquire())
        val components = attachment.assembled.single()

        val warm = hlsRequest("episode:warm")
        val minted = components.sessionIds.mint(warm.contentId)
        val warmItem = components.itemOf(warm)
        attachment.held[warmItem] = components.mediaSourceFactory.createMediaSource(warmItem)

        player.setMediaRequest(warm)
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        assertThat(attachment.handedOut).containsExactly(warmItem)
        val warmSession = events.filterIsInstance<TelemetryEvent.SessionStarted>().single()
        assertThat(warmSession.sessionId).isEqualTo(minted)
        assertThat(components.sessionIds.idFor(warm.contentId)).isNull()

        player.setMediaRequest(hlsRequest("episode:cold"))
        assertThat(attachment.handedOut).containsExactly(warmItem)
        val coldSession = events.filterIsInstance<TelemetryEvent.SessionStarted>().last()
        assertThat(coldSession.contentId).isEqualTo("episode:cold")
        assertThat(coldSession.sessionId).isNotEqualTo(minted)
    }

    /**
     * The attachment hears of a recycle once the player has let its item go, so a source it releases
     * then is never one the player is still playing.
     */
    @Test
    fun anAttachmentHearsOfARecycleOnceThePlayerHasLetItsItemGo() {
        val attachment = RecordingAttachment()
        val pool = buildPool(maxSize = 1, attachment = attachment)
        val player = checkNotNull(pool.acquire())
        player.setMediaRequest(hlsRequest("episode:recycled"))

        pool.recycle(player)

        assertThat(attachment.recycledPlayers).containsExactly(player)
        assertThat(attachment.itemsHeldAtRecycle).containsExactly(null)
    }

    private fun hlsRequest(contentId: String): MediaRequest =
        MediaRequest.Builder(contentId).addSource(SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI).build()

    private class RecordingAttachment : PoolAttachment {
        val assembled = mutableListOf<SharedComponents>()
        val held = mutableMapOf<MediaItem, MediaSource>()
        val handedOut = mutableListOf<MediaItem>()
        val recycledPlayers = mutableListOf<SuperPlayer>()
        val itemsHeldAtRecycle = mutableListOf<MediaItem?>()
        var releasing = 0

        override fun onEngineAssembled(components: SharedComponents) {
            assembled += components
        }

        override fun sourceFor(player: SuperPlayer, item: MediaItem): WarmStart? =
            held.remove(item)?.also { handedOut += item }?.let { WarmStart.Source(it) }

        override fun preferredIdle(idle: List<SuperPlayer>): SuperPlayer? = null

        override fun onAcquired(player: SuperPlayer) = Unit

        override fun onRecycled(player: SuperPlayer) {
            recycledPlayers += player
            itemsHeldAtRecycle += player.currentMediaItem
        }

        override fun onPoolReleasing() {
            releasing++
        }
    }

    private companion object {
        const val THREAD_EXIT_BOUND_MS = 5_000L
    }
}
