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

package com.superplayer.drm

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.BufferPolicy
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackConditions
import com.superplayer.core.PlaybackDecision
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.RetryBudget
import com.superplayer.core.RetryPolicy
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TrackSelectionPolicy
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A live stream re-keys under a running session, and the renewal costs the viewer nothing (#209).
 *
 * ## What rotates, and who says so
 *
 * The packager changes the key a live stream is encrypted under without ending anything: the session
 * stays open, the playlist keeps advancing, and what the *device* notices is that the keys it holds
 * no longer decrypt. It says so with `MediaDrm.EVENT_KEY_REQUIRED`, which Media3 routes to the owning
 * session (`DefaultDrmSessionManager.MediaDrmHandler`, then `DefaultDrmSession.onMediaDrmEvent`),
 * and the session composes a fresh key request on the licence transport it already has.
 *
 * So the lever is the device's and not the licence server's — `DeviceStatement.signalKeyRotation` —
 * and that is the only honest place for it: a server that changed the key it would issue is invisible
 * to a client until the device complains. `WidevineStatement.signalKeyRotation` carries the reasoning
 * and the reference.
 *
 * ## What this file owns, and what it does not
 *
 * Media3 raises and routes the event; nothing here adds a listener or a callback for it. What #209
 * owns is the pair of facts a consumer would notice if they were wrong: **a rotation in flight is not
 * a failure** — it costs a licence request and neither a stall nor an ended session — and **a renewal
 * that fails ends in the class #206 gave it** rather than in an unnamed DRM code. The second needs
 * no new classification code at all and that is the point: `ErrorClassifier` already maps Media3's
 * whole DRM band, and this asserts that a renewal arrives inside it rather than beside it.
 *
 * ## The finding this file records, and where the second half of #209's fourth criterion lives
 *
 * A renewal that *fails* does not end the session and does not reach the consumer at all, and that
 * is Media3's deliberate rule rather than a gap here: `DefaultDrmSession.onError` sets the session to
 * `STATE_ERROR` only `if (state != STATE_OPENED_WITH_KEYS)`, so a session already holding working
 * keys reports the failure to `DrmSessionEventListener` and keeps playing on what it holds. It is the
 * right rule — a rotation that could not be fetched has cost the viewer nothing until the keys in
 * force actually stop working — and it is exactly the sort of thing a test that assumed otherwise
 * would have hidden, so [aRenewalThatFailsLeavesThePlayingSessionOnTheKeysItHolds] states it.
 *
 * What that leaves is the classification, and it needs no code and no test of its own here. The two
 * failures a renewal can end in are Media3's `ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED` and its
 * `ERROR_CODE_DRM_LICENSE_EXPIRED`, and #206 gave each a named leaf — `Drm.LicenceAcquisition` and
 * `Drm.LicenceExpired` — with neither falling through to `Drm.SystemError`, which is what "a generic
 * licence failure" would have been. `ErrorClassifierTest` asserts that mapping over the codes and
 * `DrmFailureTest` drives the first of them through a real player; repeating either here would be a
 * second copy of an assertion rather than a second assertion. The path from a *keyed* session to
 * `Drm.LicenceExpired` runs through real sample decryption, which `docs/testing.md`'s *A Widevine
 * device and a licence server* explains this harness cannot do, and pretending at it from a stream
 * whose samples are in the clear would be worse than naming the limit.
 */
@RunWith(AndroidJUnit4::class)
class KeyRotationTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aKeyRotationOnALiveStreamIsRenewedWithoutAStallOrASessionEnd() {
        val player = play()
        val licencesBeforeRotation = licenceRequests(player)
        val stateBeforeRotation = player.playbackState

        DeviceStatement.signalKeyRotation()
        harness.advanceTimeInStepsMs(player, PLAYED_AFTER_ROTATION_MS)

        // The renewal happened: the session asked for a licence again, on the transport it already
        // had. Counted rather than described, for the reason every claim in this module is counted —
        // a session graph can be exactly right and still ask for nothing.
        assertWithMessage("a licence request for the renewal")
            .that(licenceRequests(player))
            .isGreaterThan(licencesBeforeRotation)
        // And it cost nothing. No error reached the consumer, the session did not end — the player is
        // still on the item it was on — and the state it was in is the state it is in, which is what
        // "without a stall" means to anyone watching: a rebuffer would have moved it to BUFFERING.
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(stateBeforeRotation)
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.mediaItemCount).isEqualTo(1)
    }

    @Test
    fun aRenewalThatFailsLeavesThePlayingSessionOnTheKeysItHolds() {
        // The licence budget is nothing, so the refusal below is spent in one ask and nothing here is
        // about retries. The refusal arrives only *after* the first licence has been issued
        // (`afterAttempts`), so what fails is the renewal and not the acquisition — which is the
        // whole distinction, and the reason the count below is checked before the rotation is
        // signalled rather than only after.
        val player = play(
            faults = FaultScript.Builder()
                .failWithHttpStatus(
                    FaultScript.HTTP_FORBIDDEN,
                    kind = ResourceKind.LICENCE,
                    afterAttempts = 1,
                )
                .build(),
            licenceBudget = NO_RETRIES,
        )
        assertWithMessage("the first licence was issued before the refusal begins")
            .that(licenceRequests(player))
            .isEqualTo(1)
        val stateBeforeRotation = player.playbackState

        DeviceStatement.signalKeyRotation()
        harness.advanceTimeInStepsMs(player, PLAYED_AFTER_ROTATION_MS)

        // The renewal was attempted and refused: a second licence request left the chain and met the
        // fault. Asserted first, so that what follows is "the refusal did not end the session" rather
        // than "no renewal was ever tried".
        assertWithMessage("the renewal was asked for and refused")
            .that(licenceRequests(player))
            .isEqualTo(2)
        // And the session is still the one it was, on the keys it already holds. Media3's own rule,
        // cited in the class KDoc; the consumer sees no error because nothing has yet stopped
        // working. A library that turned this into a `SuperPlayerError` would be ending a viewing
        // that was still perfectly playable.
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(stateBeforeRotation)
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

    /** A player over a live protected stream, built as a consumer builds one and playing. */
    private fun play(
        faults: FaultScript = FaultScript.NONE,
        licenceBudget: RetryBudget? = null,
    ): SuperPlayer {
        val content = TestContent.protectedLiveHls()
        val player = harness.buildPlayer(
            content = content,
            faults = faults,
            policy = licenceBudget?.let(::licenceBudgetOf),
            // Set whenever a budget is, because the budget is `superplayer-resilience`'s to answer
            // with: a player without it keeps Media3's own handling for a licence exactly as it keeps
            // it for a segment (ADR-0011 rule 14).
            resilience = licenceBudget?.let { Resilience.standard() },
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        harness.playToReady(player)
        // Far enough into the window that the session is established and the origin has published
        // past the segment the player joined on: a rotation signalled before either would be a
        // rotation of a session that had not finished opening.
        harness.advanceTimeInStepsMs(player, PLAYED_BEFORE_ROTATION_MS)
        return player
    }

    /**
     * A policy that decides a workable buffer and the one budget these tests are about.
     *
     * Hand-written rather than a profile row, for `LicenceLoadTest`'s reason: the profile rows are
     * argued in `StaticProfilePolicy` and what a test here needs is one specific number. It is not an
     * engine extension, so it is consulted once at construction.
     */
    private fun licenceBudgetOf(licence: RetryBudget): PlaybackPolicy = object : PlaybackPolicy {
        override fun decide(conditions: PlaybackConditions): PlaybackDecision = PlaybackDecision(
            buffer = BufferPolicy(
                minBufferMs = 10_000,
                maxBufferMs = 20_000,
                bufferForPlaybackMs = 1_000,
                bufferForPlaybackAfterRebufferMs = 2_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false,
            ),
            trackSelection = TrackSelectionPolicy(
                maxVideoBitrateBps = TrackSelectionPolicy.UNLIMITED,
                maxVideoHeightPx = TrackSelectionPolicy.UNLIMITED,
            ),
            retry = RetryPolicy(licence = licence),
        )
    }

    /** How many times a licence was asked for, repeats included — which is what a renewal is. */
    private fun licenceRequests(player: SuperPlayer): Int =
        harness.networkRequests(player).count { it.kind == ResourceKind.LICENCE }

    private companion object {
        const val CONTENT_ID = "live/channel-4"

        /** Long enough that the live window has slid at least once under the open session. */
        const val PLAYED_BEFORE_ROTATION_MS = 8_000L

        /** Long enough that a renewal, and any stall it caused, would both have happened. */
        const val PLAYED_AFTER_ROTATION_MS = 8_000L

        /** A budget that allows nothing, so what ends a session is the refusal and not the count. */
        val NO_RETRIES = RetryBudget(maxRetries = 0, initialBackoffMs = 0, maxBackoffMs = 0)
    }
}
