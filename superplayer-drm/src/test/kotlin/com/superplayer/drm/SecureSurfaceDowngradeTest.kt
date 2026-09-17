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

import android.media.MediaFormat
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.TelemetryEvent
import com.superplayer.resilience.FailureClass
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.SecurityLevel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The third of `PRD.md` §3.2's three ways L1 becomes unusable: **the secure surface will not
 * allocate** (#226).
 *
 * ## Where this claim is verified, and where it is not
 *
 * The ticket owed an answer before it owed code, so here it is, in the file that has to be honest
 * about it.
 *
 * The claim splits in two, and the split is the seam #226 found rather than one invented to make a
 * test possible. What the security-level re-open needs is **a signal that the protected path is
 * unavailable** — not a protected path. That signal is two fields on one real Media3 object,
 * `MediaCodecRenderer.DecoderInitializationException`: a `codecInfo` at all, which is the shape
 * Media3 raises only where a decoder was selected and its initialisation threw, and that `codecInfo`
 * being the `.secure` one. `ErrorClassifier` reads them, and everything after it is production code.
 *
 * - **Forced here, under `./gradlew check`:** that a *selected secure decoder* failing to start
 *   classifies as `FailureClass.Device.SecureDecoderInit`, that a player whose licence server's
 *   operator permitted `L3` re-opens its session graph there and plays, that one whose operator
 *   permitted nothing ends typed with nothing delivered, that a device already at `L3` has nothing to
 *   fall to and ends there, and that an *ordinary* decoder failure — the identical code, the
 *   identical exception, a decoder that is not secure — lowers nothing. Every one of those runs
 *   through a real `SuperPlayer`, a real `Drm.widevine`, a real `Resilience.standard()` and the real
 *   classifier. The narrower control that a secure decoder Media3 could not *find* keeps its rungs
 *   rather than conceding a level is `ErrorClassifierTest`'s, where the two exception shapes can be
 *   put side by side.
 * - **Not forced anywhere, and named:** that a real device whose protected buffer queue will not
 *   allocate produces that exception with that `codecInfo`. Robolectric has no `MediaCrypto` and no
 *   protected buffer queue, and the harness's renderers are Media3 fakes rather than
 *   `MediaCodecRenderer`, so nothing under `check` can allocate a secure surface or fail to. What
 *   stands in is `PlaybackHarness.failDecoderInitialization`, which raises the real exception from
 *   the harness's renderer; the origin — `MediaCodecVideoRenderer` asking
 *   `PlaceholderSurface.newInstance(context, codecInfo.secure)` inside `initCodec`, that call failing
 *   a `checkState` on a device that cannot back a protected surface, and the catch in
 *   `maybeInitCodecWithFallback` turning it into this exception — is established from Media3 1.11's
 *   bytecode and cited in `ErrorClassifier.theSecureDecoderWouldNotStart`, not asserted.
 * - **Not `devicelab`, and deliberately.** Two reasons, and the first is enough on its own:
 *   `devicelab` measures and never asserts (`devicelab/README.md`), so a scenario there could report
 *   this failure but not fail on it. The second is that no run can put a device *into* the failing
 *   state — a secure surface that will not allocate is a property of a particular handset under
 *   particular load, not a condition a scenario provokes — and the device `devicelab` boots by
 *   default is an emulator, on which a protected session is not obtainable at all.
 *   `docs/testing.md`'s *What is not covered here* is where that is said.
 *
 * This is the shape [DrmFailureTest] and `SuperPlayerDecoderRecreationTest` already use — the half
 * that can be forced is forced, and the half that cannot is named rather than faked — and
 * `docs/testing.md`'s *A Widevine device and a licence server* carries the same boundary from the
 * other side.
 *
 * ## What is not this ticket
 *
 * Secure surface *discipline* — a `SurfaceView` rather than a `TextureView`, `FLAG_SECURE`, a
 * display-capability change — is correctness under ADR-0006 rule 1 and #213's, on the far side of
 * the line ADR-0012 rule 12 draws. Nothing here touches a `Surface`.
 */
@RunWith(AndroidJUnit4::class)
class SecureSurfaceDowngradeTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aSecureSurfaceThatWillNotAllocateFallsToTheLevelTheOperatorPermitted() {
        aCapableL1Device()

        val player = harness.buildDowngradePlayer(permits = setOf(WidevineConfig.SECURITY_LEVEL_L3))
        harness.playToReady(player)

        // The session was protected, so the decoder selected for it was a secure one, and the
        // protected output path it needs could not be allocated. `L3` decodes in ordinary memory and
        // needs no protected surface at all.
        harness.failDecoderInitialization(player, secureDecoderRequired = true)
        harness.advanceUntil(player, "the session re-opened and played") {
            it.playbackState == Player.STATE_READY
        }

        // One viewing rescued is one session rather than a session plus an error (ADR-0011 rule 10),
        // which is the same account #225's re-open gives — this reaches it through a different
        // classification and not a different mechanism.
        assertThat(player.playerError).isNull()
        // The fault is armed once, so a bare re-prepare would also have got the player back to
        // `READY`: the assertion that says the *downgrade* is what rescued it is this one, because
        // nothing but `reopenAtLowerLevel` writes a delivered level down.
        assertThat(player.deliveredSecurityLevel).isEqualTo(WidevineConfig.SECURITY_LEVEL_L3)
    }

    @Test
    fun theLevelItFellToIsWhatTelemetryReports() {
        aCapableL1Device()

        val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
        val player = harness.buildDowngradePlayer(
            permits = setOf(WidevineConfig.SECURITY_LEVEL_L3),
            telemetry = QoeCollector { events += it },
        )
        harness.playToReady(player)
        harness.failDecoderInitialization(player, secureDecoderRequired = true)
        harness.advanceUntil(player, "the session re-opened and played") {
            it.playbackState == Player.STATE_READY
        }
        player.release()

        // ADR-0012 rule 11's last sentence: a session that opened at a reduced level says so. No new
        // event and no new field — `SessionEnded` is read at the end, which is after the re-open.
        assertThat(endedEventOf(events).securityLevel).isEqualTo(WidevineConfig.SECURITY_LEVEL_L3)
    }

    @Test
    fun withNothingPermittedItEndsTypedRatherThanDowngradingSilently() {
        // The control ADR-0012 rule 11 exists for: an app told nothing permits nothing, and failing
        // hard is what that has to mean. The identical device and the identical failure.
        aCapableL1Device()

        val player = harness.buildDowngradePlayer()
        harness.playToReady(player)
        harness.failDecoderInitialization(player, secureDecoderRequired = true)
        harness.advanceUntil(player, "an error") { it.playerError != null }

        val typed = typedErrorOf(player)
        assertThat(typed.causeClass).isEqualTo(FailureClass.Device.SecureDecoderInit.stableName)
        assertThat(typed.isRetryable).isFalse()
        // Not "this device cannot play it": the viewer has been refused a programme on this device,
        // which is a different sentence and the reason the leaf carries the seventh message key.
        assertThat(typed.userMessageKey).isEqualTo(FailureClass.PROTECTION_UNAVAILABLE_MESSAGE_KEY)
        // Nothing was delivered, rather than something weaker.
        assertThat(player.deliveredSecurityLevel).isNull()
    }

    @Test
    fun anOrdinaryDecoderFailureLowersNothingEvenWhereTheLowerLevelIsPermitted() {
        // The control that keeps this from being "downgrade on any decoder failure", and the one the
        // ticket asks for by name. Same device, same permission, same error code, same exception
        // class — and the renderer saying the path that failed was not the protected one.
        aCapableL1Device()

        val player = harness.buildDowngradePlayer(permits = setOf(WidevineConfig.SECURITY_LEVEL_L3))
        harness.playToReady(player)
        harness.failDecoderInitialization(player, secureDecoderRequired = false)
        harness.advanceUntil(player, "an error") { it.playerError != null }

        assertThat(typedErrorOf(player).causeClass).isEqualTo(FailureClass.Device.DecoderInit.stableName)
        assertThat(player.deliveredSecurityLevel).isNull()
    }

    @Test
    fun aDeviceAlreadyAtTheLowestLevelHasNothingToFallToAndEndsThere() {
        // The middle case: the permission is published and the failure is the right one, and there is
        // still nothing to do — an `L3` device is already below the level a downgrade would ask for.
        // `SecurityLevelLadder.levelToFallTo` answers null and the repair slot is never filled, which
        // is the half of the decision `superplayer-drm` owns rather than the classifier.
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)
        DeviceStatement.declareWidevine(SecurityLevel.L3)

        val player = harness.buildDowngradePlayer(permits = setOf(WidevineConfig.SECURITY_LEVEL_L3))
        harness.playToReady(player)
        harness.failDecoderInitialization(player, secureDecoderRequired = true)
        harness.advanceUntil(player, "an error") { it.playerError != null }

        assertThat(typedErrorOf(player).causeClass).isEqualTo(FailureClass.Device.SecureDecoderInit.stableName)
        // Nothing was lowered, because there was no lower level to be lowered to.
        assertThat(player.deliveredSecurityLevel).isNull()
    }

    /**
     * A device with nothing visibly wrong with it: Widevine at `L1`, a secure decoder declared, and
     * a provisioning service that certifies it.
     *
     * Everything #208 and #225 look at is in order here, which is what makes this the third case
     * rather than either of theirs: the level is settled before the session opens and the graph is
     * built without complaint, and the only thing that fails is the decoder the keys oblige.
     */
    private fun aCapableL1Device() {
        DeviceStatement.declareSecureVideoDecoder(MediaFormat.MIMETYPE_VIDEO_AVC, maxSupportedInstances = 1)
        DeviceStatement.declareWidevine(SecurityLevel.L1)
    }
}
