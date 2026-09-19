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

package com.superplayer.realtime

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.FrameSink
import com.superplayer.core.FrameSource
import com.superplayer.core.MediaRequest
import com.superplayer.core.RealtimeStreamNotSeekableException
import com.superplayer.core.SuperPlayer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.LooperMode
import java.io.IOException

/**
 * Phase 13's tracer bullet: a frame handed in by a transport plays, with no device and no network
 * (ADR-0018 rules 1, 2, 5 and 12, issue #343).
 *
 * ## Why this does not go through `PlaybackHarness`
 *
 * Every other optional slot is driven through the harness, and this one is not, for a reason that is
 * about the harness rather than about this module: the harness builds a player *around a transport*
 * — it composes a fake `DataSource` into the chain's slot and counts what leaves it — and a realtime
 * item opens no `DataSource` at all (ADR-0018 rule 6). There is nothing for the harness's central
 * mechanism to stand in for here, so what it would contribute is its clock and its renderers, both
 * of which are Media3's own fakes reached directly below.
 *
 * What is used instead is the seam `docs/testing.md` already sanctions for exactly this:
 * `SuperPlayer.Builder.setEngineConfigurator`, which is `internal` and reachable because this module
 * is core's tenth Kotlin friend (ADR-0018 rule 11). **Every assertion below is on the public
 * `Player` API**, which is the rule that actually binds — nothing here reads past the facade.
 *
 * The other half of the reason is #343's own acceptance criterion that no existing module's tracked
 * API surface moves. Adding a realtime parameter to `PlaybackHarness.buildPlayer` would move
 * `superplayer-testkit`'s, and the harness's realtime half is properly #347's, which is the ticket
 * that writes `FrameSourceConformance` and knows what a transport implementer needs from it.
 *
 * ## What the frames are
 *
 * Synthetic H.264: Annex-B start codes and NAL headers of the right shape, carrying no picture data.
 * **Nothing here decodes them** — the renderer is Media3's `FakeRenderer`, which reads samples and
 * advances — so the bytes prove the path rather than the picture. That is the honest limit of any
 * test under `check`, and it is why [FrameSource]'s obligations 3, 6 and 7 are carried by a
 * conformance suite (#347) instead of by this file.
 */
@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class RealtimePlaybackTest {

    private val players = mutableListOf<SuperPlayer>()

    @After
    fun releasePlayers() {
        players.forEach { it.release() }
    }

    @Test
    fun `a frame handed in by a transport plays to STATE_READY with a position that advances`() {
        val source = ScriptedFrameSource(frames = 300)
        val player = buildPlayer(source)

        player.setMediaRequest(realtimeRequest())
        player.prepare()
        player.play()

        TestPlayerRunHelper.runUntilPlaybackState(player.exoPlayer, Player.STATE_READY)
        assertThat(source.subscribed).isTrue()

        // A position that advances, and not merely a state that was reached: a player can be READY on
        // a stalled queue, and the whole point of the slice is that frames flow.
        val atReady = player.currentPosition
        TestPlayerRunHelper.playUntilPosition(player.exoPlayer, /* mediaItemIndex = */ 0, /* positionMs = */ 2_000)
        val afterPlaying = player.currentPosition
        assertThat(afterPlaying).isGreaterThan(atReady)
        // Advancement rather than an equality with the 2 s asked for: `playUntilPosition` pauses on
        // the last sample boundary at or before its target, so a 30 fps stream lands on 1990 ms and
        // never on 2000. What is being asserted is that a second of media really played.
        assertThat(afterPlaying - atReady).isAtLeast(1_000)
    }

    @Test
    fun `a realtime timeline is live and unseekable`() {
        val player = buildPlayer(ScriptedFrameSource(frames = 300))

        player.setMediaRequest(realtimeRequest())
        player.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(player.exoPlayer, Player.STATE_READY)

        assertThat(player.isCurrentMediaItemSeekable).isFalse()
        assertThat(player.isCurrentMediaItemDynamic).isTrue()
        assertThat(player.duration).isEqualTo(C.TIME_UNSET)
    }

    @Test
    fun `a realtime source refuses StartPosition At rather than coercing it to the live edge`() {
        val player = buildPlayer(ScriptedFrameSource(frames = 1))

        val refusal = runCatching {
            player.setMediaRequest(realtimeRequest(MediaRequest.StartPosition.At(12_000)))
        }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(RealtimeStreamNotSeekableException::class.java)
        assertThat((refusal as RealtimeStreamNotSeekableException).scheme).isEqualTo(REALTIME_SCHEME)
        assertThat(refusal.startPosition).isEqualTo("At")
        // A refused adoption leaves the player exactly as it was, which is why the refusal is raised
        // before any of adoption's bookkeeping runs.
        assertThat(player.currentMediaItem).isNull()
    }

    @Test
    fun `a realtime source refuses StartPosition ResumeFromLastKnown`() {
        val player = buildPlayer(ScriptedFrameSource(frames = 1))

        val refusal = runCatching {
            player.setMediaRequest(realtimeRequest(MediaRequest.StartPosition.ResumeFromLastKnown))
        }.exceptionOrNull()

        assertThat(refusal).isInstanceOf(RealtimeStreamNotSeekableException::class.java)
        assertThat((refusal as RealtimeStreamNotSeekableException).startPosition).isEqualTo("ResumeFromLastKnown")
    }

    /**
     * The control that keeps the refusal from being "refuse every seek".
     *
     * A player with a realtime slot still adopts an ordinary URI at any start position, because the
     * discriminator is the scheme and nothing else (ADR-0018 rule 12). Without this, a refusal that
     * fired on every request would pass the two tests above.
     */
    @Test
    fun `a non-realtime source on the same player is adopted at a named position`() {
        val player = buildPlayer(ScriptedFrameSource(frames = 1))

        player.setMediaRequest(
            MediaRequest.Builder("vod")
                .addSource("https://example.test/stream.m3u8")
                .setStartPosition(MediaRequest.StartPosition.At(12_000))
                .build(),
        )

        assertThat(player.currentMediaItem).isNotNull()
    }

    /**
     * The control that keeps the dispatch from being "everything is realtime".
     *
     * The transport is never subscribed for an https URI on a player whose realtime slot is filled.
     */
    @Test
    fun `a non-realtime scheme never reaches the transport`() {
        val source = ScriptedFrameSource(frames = 300)
        val player = buildPlayer(source)

        player.setMediaRequest(
            MediaRequest.Builder("vod").addSource("https://example.test/stream.m3u8").build(),
        )
        player.prepare()
        TestPlayerRunHelper.advance(player.exoPlayer).untilPendingCommandsAreFullyHandled()

        assertThat(source.subscribed).isFalse()
    }

    /**
     * A transport that fails reaches the consumer where errors already arrive, rather than leaving
     * the player waiting for a frame that is never coming.
     *
     * The cause is the transport's own throwable, because the reader of it is a human with a bug
     * report — which is what [FrameSink.onError]'s KDoc asks a transport to make worth reading.
     */
    @Test
    fun `a transport that fails ends the session with its own cause`() {
        val player = buildPlayer(FailingFrameSource(IOException("the relay closed the subscription")))

        player.setMediaRequest(realtimeRequest())
        player.prepare()
        val error: Throwable = TestPlayerRunHelper.runUntilError(player.exoPlayer)

        assertThat(generateSequence(error) { it.cause }.map { it.message }.toList())
            .contains("the relay closed the subscription")
    }

    /**
     * A publisher that stops leaves the player at the edge with nothing further to load.
     *
     * Asserted as `isLoading`, because that is the whole of what [FrameSink.onEnded] can be observed
     * to do through the public API: a realtime stream never reaches `STATE_ENDED`, for the reason
     * `RealtimeMediaPeriod.getBufferedPositionUs` sets out.
     */
    @Test
    fun `a publisher that stops leaves the player loading nothing further`() {
        val player = buildPlayer(ScriptedFrameSource(frames = 60, endsAfterScript = true))

        player.setMediaRequest(realtimeRequest())
        player.prepare()
        TestPlayerRunHelper.runUntilPlaybackState(player.exoPlayer, Player.STATE_READY)
        TestPlayerRunHelper.runUntilIsLoading(player.exoPlayer, false)

        assertThat(player.isLoading).isFalse()
    }

    private fun realtimeRequest(
        startPosition: MediaRequest.StartPosition = MediaRequest.StartPosition.Beginning,
    ) = MediaRequest.Builder("studio-a")
        .addSource("$REALTIME_SCHEME://relay.test/studio-a")
        .setStartPosition(startPosition)
        .build()

    private fun buildPlayer(source: FrameSource): SuperPlayer {
        val player = SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setRealtime(Realtime.transport(REALTIME_SCHEME) { source })
            .setEngineConfigurator { configuration ->
                // Media3's own fakes, for `docs/testing.md`'s reason: a real `MediaCodecVideoRenderer`
                // has no codec to open on the JVM, and a real clock would make every assertion below
                // a race against the host's speed.
                configuration.engine
                    .setClock(FakeClock(SystemClock.elapsedRealtime(), /* isAutoAdvancing = */ true))
                    .setRenderersFactory(
                        RenderersFactory { _, _, _, _, _ -> arrayOf(FakeRenderer(C.TRACK_TYPE_VIDEO)) },
                    )
            }
            .build()
        players += player
        return player
    }

    private companion object {
        /**
         * Deliberately not `moq` or `whep`: this module knows no protocol, and a test naming one
         * would read as though it did. What is being proven is that *a* scheme reaches *a* transport.
         */
        const val REALTIME_SCHEME = "superplayer-test-realtime"
    }
}
