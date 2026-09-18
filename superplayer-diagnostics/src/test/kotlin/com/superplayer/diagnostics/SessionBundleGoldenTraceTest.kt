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

package com.superplayer.diagnostics

import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superplayer.core.MediaRequest
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.telemetry.QoeCollector
import com.superplayer.telemetry.SessionTraceRecorder
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.GoldenFile
import com.superplayer.testkit.NetworkProfile
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import com.superplayer.testkit.ThroughputTrace
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * The golden that pins the bundle's format: one whole session, exported as one artifact and held to
 * the committed file under `src/test/golden`.
 *
 * It is `superplayer-telemetry`'s `GoldenTraceTest` in this module and by the same contract —
 * `./gradlew updateGoldenTraces` is the one command that regenerates it, which is why this class is
 * named for the filter that task applies, and `docs/testing.md`, *Golden traces*, says what a diff
 * here means. What it adds to that test is the layer this module owns: a bundle's header lines, and
 * the `capability` kind the seventh redaction rule admits.
 *
 * The device is **stated** rather than taken as Robolectric finds it, and that is the point of the
 * golden as much as the session is: a capability snapshot read from a device nobody described would
 * pin a line of `unknown`s, and the thing worth catching in review is a field of the snapshot
 * changing meaning. Every value below is therefore chosen to be a readable one, and the platform API
 * level in the golden is the one `src/test/resources/robolectric.properties` pins.
 */
@RunWith(AndroidJUnit4::class)
class SessionBundleGoldenTraceTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun hlsOnDemand() {
        stateTheDevice()

        GoldenFile.check("hls-vod-bundle.trace", play(TestContent.hls()))
    }

    @Test
    fun hlsOnDemandOverThreeG() {
        stateTheDevice()

        // A shaped network, because the two layers the bundle adds to the timeline are only visible
        // over one: a transfer that takes no time reports no duration worth reading and gives the
        // engine's meter nothing to estimate from, so the `bandwidth` kind is absent from a session
        // on an infinite link. This golden is where both are pinned.
        GoldenFile.check(
            "hls-vod-three-g-bundle.trace",
            play(TestContent.hls(), network = NetworkProfile.THREE_G.trace),
        )
    }

    /** The device every case here is read against; see the class KDoc for why it is stated at all. */
    private fun stateTheDevice() {
        DeviceStatement.declareVideoDecoder(H264, DECODER_INSTANCES, HIGH_PROFILE to LEVEL_4)
        DeviceStatement.declareSecureVideoDecoder(HEVC, SECURE_DECODER_INSTANCES, HIGH_PROFILE to LEVEL_4)
        DeviceStatement.declareAppHeap(HEAP_MB)
        // A panel that answered, so the golden pins a populated `hdr=` field rather than the
        // `unknown` an undeclared display prints — the two are different facts and the format says so.
        DeviceStatement.declareDisplayHdrTypes(HDR10, HLG)
    }

    private fun play(content: TestContent, network: ThroughputTrace? = null): String {
        val recorder = SessionTraceRecorder()
        // A sink of the test's own beside the recorder, because a golden has to be taken once the
        // delivery queue has drained and `SessionEnded` is the event that says it has (ADR-0008 rule
        // 4). `GoldenTraceTest` waits through the collector's own idle signal, which is internal to
        // the telemetry module; from outside it, the last event is the signal.
        val delivered = Collections.synchronizedList(mutableListOf<TelemetryEvent>())
        val player = harness.buildPlayer(
            content = content,
            telemetry = QoeCollector(TelemetrySink.composite(recorder, TelemetrySink { delivered += it })),
            network = network,
        )
        recorder.attach(player)
        player.setMediaRequest(MediaRequest.Builder(CONTENT).addSource(content.sourceUri).build())
        harness.playToReady(player)
        harness.advanceUntil(player, "the end of the content", END_BOUND_MS) {
            it.playbackState == Player.STATE_ENDED
        }
        harness.release(player)
        runMainLooperUntil { synchronized(delivered) { delivered.any { it is TelemetryEvent.SessionEnded } } }
        return SessionBundle.Builder(ApplicationProvider.getApplicationContext())
            .setTrace(recorder.trace())
            .setPlayer(player)
            .build()
            .format()
    }

    private companion object {
        const val CONTENT = "series/expanse/s01e01"
        const val END_BOUND_MS = 120_000L

        const val H264 = "video/avc"
        const val HEVC = "video/hevc"

        /** `AVCProfileHigh` and `AVCLevel4`, as `MediaCodecInfo.CodecProfileLevel` numbers them. */
        const val HIGH_PROFILE = 8
        const val LEVEL_4 = 2048

        /** `Display.HdrCapabilities.HDR_TYPE_HDR10` and `HDR_TYPE_HLG`, as the platform numbers them. */
        const val HDR10 = 2
        const val HLG = 3

        const val DECODER_INSTANCES = 6
        const val SECURE_DECODER_INSTANCES = 1
        const val HEAP_MB = 192
    }
}
