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

import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.core.SuperPlayerError
import com.superplayer.core.TelemetryEvent
import com.superplayer.resilience.Resilience
import com.superplayer.telemetry.QoeCollector
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent

/*
 * The apparatus `SecurityLevelTest`, `ProvisioningDowngradeTest` and `SecureSurfaceDowngradeTest`
 * share (#233). What is deliberately *not* here is the one thing those three differ in — the device
 * each states and the failure each forces — which stays in each file, because it is that file's
 * subject.
 *
 * It lives in `superplayer-drm`'s test sources rather than in `superplayer-testkit` because the one
 * helper worth sharing names `Drm.widevine` and `WidevineConfig`, and testkit is a phase 2 module
 * that a phase 6 one may not be reached from (`docs/modules.md`). The other two name only core types
 * and could move, but every copy of either is in this module's tests, and a helper in testkit is
 * public API of a published module (`docs/api-surface.md`) that nothing else would call.
 */

/**
 * A protected player adopting [TestContent.protectedDash], built as a consumer writes it: protection
 * through a licence server whose operator permitted [permits] — empty being a refusal (ADR-0012
 * rule 11) — and the resilience both halves of ADR-0012 rule 2 need. Nothing is prepared; the test
 * says how far it plays.
 */
internal fun PlaybackHarness.play(
    permits: Set<String> = emptySet(),
    telemetry: QoeCollector? = null,
    faults: FaultScript = FaultScript.NONE,
): SuperPlayer {
    val content = TestContent.protectedDash()
    val player = buildPlayer(
        content = content,
        faults = faults,
        telemetry = telemetry,
        drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI, permittedSecurityLevels = permits)),
        resilience = Resilience.standard(),
    )
    player.setMediaRequest(MediaRequest.Builder(DOWNGRADE_CONTENT_ID).addSource(content.sourceUri).build())
    return player
}

/** The typed error rung 6 delivered, where a consumer already looks for a cause. */
internal fun typedErrorOf(player: SuperPlayer): SuperPlayerError {
    val error = player.playerError
    assertThat(error).isNotNull()
    assertThat(error?.cause).isInstanceOf(SuperPlayerError::class.java)
    return error?.cause as SuperPlayerError
}

/**
 * The session's `SessionEnded`, waited for rather than read: delivery is a bounded queue on a thread
 * of its own (ADR-0008 rules 3 and 4), so the event a test wants is the last one to arrive.
 * `TypedErrorPlaybackTest` waits the same way and for the same reason.
 */
internal fun endedEventOf(events: List<TelemetryEvent>): TelemetryEvent.SessionEnded {
    val deadline = System.currentTimeMillis() + DELIVERY_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        val delivered = synchronized(events) { events.toList() }
        delivered.filterIsInstance<TelemetryEvent.SessionEnded>().firstOrNull()?.let { return it }
        Thread.sleep(DELIVERY_POLL_MS)
    }
    throw AssertionError("the session's events did not arrive within $DELIVERY_TIMEOUT_MS ms")
}

// No assertion reads the identity; a request needs one, and one for all three keeps it out of the way.
private const val DOWNGRADE_CONTENT_ID = "film/the-third-man"

// Generous against a delivery thread that normally drains in milliseconds, because a timeout here
// reads as a missing event rather than a slow host.
private const val DELIVERY_TIMEOUT_MS = 5_000L
private const val DELIVERY_POLL_MS = 10L
