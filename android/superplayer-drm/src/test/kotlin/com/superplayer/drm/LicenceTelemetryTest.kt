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

import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.LicenceOutcome
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackDrm
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.telemetry.QoeCollector
import com.superplayer.telemetry.SessionTraceRecorder
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.WidevineProtection
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * Licence acquisition as a number a data team can read (#212).
 *
 * The metric is derived in `superplayer-telemetry`'s `QoeCollector`, from Media3's DRM analytics
 * callbacks, and not in this module — ADR-0012 rule 5 says this module classifies nothing, and a
 * second derivation of a metric beside the one that owns the vocabulary is the drift ADR-0011 rule 1
 * and `docs/telemetry-schema.md` both exist to prevent. So the collector is the code under test and
 * this is where it is *driven*, because a real licence round trip needs a protected stream, a stated
 * Widevine device and a licence server, and all three of those are reachable only from here. The
 * dependency direction is the allowed one: phase 6 on phase 2, tests only.
 *
 * All three outcomes are driven here, and the third arrived after the other two: #212 declared
 * `LicenceOutcome.SERVED_FROM_OFFLINE_STORE` with nothing able to reach it, because nothing in this
 * library stored a licence, and #210's offline store is what made it reachable. That order was the
 * point — the value becoming *emitted* is a behaviour change against a vocabulary a pipeline had
 * already been told about, rather than a second change of the schema for one metric — and it is why
 * `SCHEMA_VERSION` did not move for either half.
 */
@RunWith(AndroidJUnit4::class)
class LicenceTelemetryTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /** Where the one test with an offline store keeps it — a directory the test named, as a consumer does. */
    @get:Rule
    val directory: TemporaryFolder = TemporaryFolder()

    /** Thread-safe: a sink is called on the delivery thread, never on the test's (ADR-0008 rule 4). */
    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())

    @Test
    fun aLicenceFetchedFromTheServerIsOneMeasuredSpan() {
        // Both protocols, for the reason `WidevinePlaybackTest` gives: an `EXT-X-KEY` and a
        // `ContentProtection` descriptor are two vocabularies for one fact, and a span derived from
        // the engine's DRM callbacks should not be able to tell them apart.
        listOf(TestContent.protectedHls(), TestContent.protectedDash()).forEach { content ->
            events.clear()
            val player = play(content)

            val acquisitions = licenceAcquisitions(player)
            assertThat(acquisitions).hasSize(1)
            val acquisition = acquisitions.single()
            assertThat(acquisition.outcome).isEqualTo(LicenceOutcome.ACQUIRED_FROM_SERVER)
            assertThat(acquisition.contentId).isEqualTo(CONTENT_ID)
            // A duration and not a sentinel: the span is measured, and the clock it is measured on
            // cannot run backwards. Its *value* is the harness's fake clock and says nothing about a
            // real server, which is why nothing here asserts an upper bound.
            assertThat(acquisition.durationMs).isAtLeast(0L)
            // The session the CDN log joins on, which is the whole of ADR-0008 rule 6: an
            // acquisition that named a session of its own would be unjoinable to the view it
            // delayed.
            assertThat(acquisition.sessionId).isEqualTo(sessionId())
            // No level was negotiated: the stated device honours what it reports, so ADR-0012 rule
            // 11's ladder never engaged. `SecurityLevelTest` is where a non-null value is forced.
            assertThat(acquisition.securityLevel).isNull()
        }
    }

    @Test
    fun aRefusedLicenceIsMeasuredToo() {
        // The half that matters most and is easiest to omit: an acquisition that produced no keys
        // still cost the viewer the wait, and a metric that only counts successes reports a
        // licence-acquisition time that *improves* as an entitlement service fails.
        val player = play(
            TestContent.protectedDash(),
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_FORBIDDEN, kind = ResourceKind.LICENCE)
                .build(),
            toReady = false,
        )

        val acquisition = licenceAcquisitions(player).single()
        assertThat(acquisition.outcome).isEqualTo(LicenceOutcome.REFUSED)
        assertThat(acquisition.durationMs).isAtLeast(0L)
        // Why it was refused is the classifier's answer and arrives on the failure event; this
        // event says only that an attempt happened and what it cost. Both halves are here, which is
        // the join `docs/telemetry-schema.md` tells a pipeline to make.
        val failure = events.filterIsInstance<TelemetryEvent.StartupFailed>().single()
        assertThat(failure.failure.category.name).isEqualTo("DRM")
    }

    @Test
    fun keysRestoredFromTheOfflineStoreAreMeasuredAsServedFromIt() {
        // #212 declared `SERVED_FROM_OFFLINE_STORE` and could not reach it: nothing stored a licence
        // then. #210 does, so this is the value becoming reachable — a behaviour change against a
        // vocabulary a pipeline was already told about, which is exactly why the value was declared
        // early rather than added now. `SCHEMA_VERSION` therefore does not move: no definition
        // changed, and a population a pipeline was told to expect arrived.
        //
        // It is also the distinction the outcome exists for. A deployment that believes its
        // downloads play offline and is in fact re-acquiring every time looks identical in every
        // other metric here, and different in exactly this one.
        val store = OfflineLicences.store(File(directory.root, "licences"))
        val acquiring = play(TestContent.protectedDash())
        val licence = store.over(acquiring).acquire(CONTENT_ID)
        harness.release(acquiring)
        synchronized(events) { events.clear() }

        val offline = harness.buildPlayer(
            content = TestContent.protectedDash(),
            telemetry = QoeCollector(TelemetrySink { events += it }),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI), licence),
        )
        offline.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(TestContent.protectedDash().sourceUri).build())
        harness.playToReady(offline)

        val acquisition = licenceAcquisitions(offline).single()
        assertThat(acquisition.outcome).isEqualTo(LicenceOutcome.SERVED_FROM_OFFLINE_STORE)
        // It is still a *span*, and a short one: a restore is work the device does rather than work
        // a server does, which is the whole of what the value reports.
        assertThat(acquisition.durationMs).isAtLeast(0L)
        store.close()
    }

    @Test
    fun contentThatDeclaresNoProtectionMeasuresNoAcquisitionAtAll() {
        // The control, and the reason this is not "emit always": the event is a fact about a licence
        // exchange, so a session with no exchange has none of them. Everything else about the player
        // — the DRM slot filled, the collector attached — is exactly as it is above.
        val player = play(TestContent.dash(), drm = widevine())

        assertThat(licenceAcquisitions(player)).isEmpty()
        assertThat(events.filterIsInstance<TelemetryEvent.SessionEnded>()).isNotEmpty()
    }

    @Test
    fun aPlayerBuiltWithoutDrmMeasuresNoAcquisitionAtAll() {
        // The other control: a player that never had a DRM slot filled pays nothing for this metric,
        // which is ADR-0012 rule 13 read through the telemetry seam.
        val player = play(TestContent.dash(), drm = null)

        assertThat(licenceAcquisitions(player)).isEmpty()
    }

    @Test
    fun nothingOfTheLicenceReachesASessionTrace() {
        // `SessionTraceRecorder`'s redaction rule 6 — no DRM payload — asserted against a session
        // that really did acquire a licence, so that the new event is inside the artifact a bug
        // report carries rather than beside it. Rule 6 was written before any licence was measured;
        // this is what keeps it true now that one is.
        val recorder = SessionTraceRecorder()
        val player = harness.buildPlayer(
            content = TestContent.protectedDash(),
            // The recorder is a sink beside the test's own, and first, so that by the time this test
            // has seen `SessionEnded` the recorder has seen it too — a composite calls its children
            // in order, and the trace is taken as soon as the wait returns.
            telemetry = QoeCollector(TelemetrySink.composite(recorder, TelemetrySink { events += it })),
            drm = widevine(),
        )
        recorder.attach(player)
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(TestContent.protectedDash().sourceUri).build())
        harness.playToReady(player)
        awaitSessionEnd(player)

        val text = recorder.trace().format()

        // The acquisition is in the trace, so what follows is an assertion about a trace that has
        // something to redact rather than about an empty one.
        assertThat(text).contains("LicenceAcquisitionEnded")
        assertThat(text).contains("outcome=ACQUIRED_FROM_SERVER")
        // And none of the material: not the server, not the address, not the protection system, not
        // the initialization data the manifest declared. `FakeExoMediaDrm`'s key request and
        // response are derived from that last one, so a trace free of it is a trace free of them.
        assertThat(text).doesNotContain(FakeLicenceServer.HOST)
        assertThat(text).doesNotContain(FakeLicenceServer.LICENCE_URI)
        assertThat(text).doesNotContain(WidevineProtection.SYSTEM_ID)
        assertThat(text).doesNotContain(WidevineProtection.psshBase64())
    }

    /** The acquisitions this player reported, once its telemetry has been delivered. */
    private fun licenceAcquisitions(player: SuperPlayer): List<TelemetryEvent.LicenceAcquisitionEnded> {
        awaitSessionEnd(player)
        return synchronized(events) { events.filterIsInstance<TelemetryEvent.LicenceAcquisitionEnded>() }
    }

    /** The one session every event here belongs to. */
    private fun sessionId(): String =
        synchronized(events) { events.filterIsInstance<TelemetryEvent.SessionStarted>().single().sessionId }

    /**
     * Releases [player] and waits for its last event to arrive.
     *
     * Delivery is asynchronous and bounded by design (ADR-0008 rules 3 and 4), and `SessionEnded`
     * is the event that says every earlier one has been handed over. Awaited rather than sampled
     * and never slept on, which is `docs/testing.md`'s *Determinism* rule and the tool it names:
     * `runMainLooperUntil` either sees the condition or times out saying what it wanted.
     */
    private fun awaitSessionEnd(player: SuperPlayer) {
        if (sessionHasEnded()) return
        harness.release(player)
        runMainLooperUntil(::sessionHasEnded)
    }

    /** Externally synchronized, because the delivery thread appends while this reads. */
    private fun sessionHasEnded(): Boolean =
        synchronized(events) { events.any { it is TelemetryEvent.SessionEnded } }

    /** A player built the way a consumer builds one, with a collector attached the same way. */
    private fun play(
        content: TestContent,
        faults: FaultScript = FaultScript.NONE,
        toReady: Boolean = true,
        drm: PlaybackDrm? = widevine(),
    ): SuperPlayer {
        val player = harness.buildPlayer(
            content = content,
            telemetry = QoeCollector(TelemetrySink { events += it }),
            faults = faults,
            drm = drm,
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        if (toReady) harness.playToReady(player) else harness.playToFailure(player)
        return player
    }

    /** What a consumer writes, against the harness's licence server rather than a real one. */
    private fun widevine(): PlaybackDrm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI))

    private companion object {
        const val CONTENT_ID = "series/expanse/s01e02"
    }
}
