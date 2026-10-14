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

import android.media.MediaCodecList
import android.os.Build
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.LicenceOutcome
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryEvent
import com.superplayer.core.TelemetrySink
import com.superplayer.drm.Drm
import com.superplayer.drm.WidevineConfig
import com.superplayer.resilience.CredentialRefusal
import com.superplayer.resilience.HeaderProvider
import com.superplayer.resilience.Resilience
import com.superplayer.telemetry.QoeCollector
import com.superplayer.telemetry.SessionTraceRecorder
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.WidevineProtection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The bundle's redaction, asserted against a session that really carried every forbidden thing.
 *
 * **That is the whole design of this test and the reason it is long.** A redaction test over a
 * session that never had the material proves nothing at all: it passes identically against a correct
 * implementation and against one that prints a request's whole URL, because there was no URL to
 * print. So one session here is driven through, in order, signed URLs on a CDN host of its own, a
 * credential minted into an `Authorization` header and repaired inside the transfer that met a
 * refusal, a Widevine device and a real licence round trip, a CMCD session id sent on every request,
 * and finally a fatal failure whose exception message names the signed URL it could not fetch — and
 * every one of those is asserted *present* before anything is asserted absent. The order matters:
 * each `assertThat(... had it ...)` below is what makes the matching `doesNotContain` mean something.
 *
 * What it holds the bundle to is the seven rules `SessionTraceRecorder`'s KDoc states — six a trace
 * obeys and the seventh ADR-0015 rule 10 added for the capability snapshot this artifact carries.
 * The snapshot's own half is asserted from both sides: what the device can *do* is in the bundle, and
 * what the device *is* — every `Build` string, every decoder component name — is not, on a device
 * whose codec list this test states so that there are real component names to leak.
 *
 * `superplayer-drm`'s `LicenceTelemetryTest` is this test's model for the protection half, and it
 * asserts the same rule 6 over the trace alone; a phase 6 module under a phase 9 one, tests only, is
 * the allowed direction and is why the licence half is reachable here at all.
 */
@RunWith(AndroidJUnit4::class)
class SessionBundleRedactionTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /** Thread-safe: a sink is called on the delivery thread, never on the test's (ADR-0008 rule 4). */
    private val events = Collections.synchronizedList(mutableListOf<TelemetryEvent>())

    @Test
    fun nothingOfASessionThatCarriedEverythingReachesItsBundle() {
        // A device with decoders of its own, so the snapshot has something true to say and the codec
        // list has component names a leak could show. Stated before the first player is built, as
        // `DeviceStatement` requires.
        DeviceStatement.declareVideoDecoder(H264, DECODER_INSTANCES, HIGH_PROFILE to LEVEL_4)
        DeviceStatement.declareSecureVideoDecoder(HEVC, SECURE_DECODER_INSTANCES, HIGH_PROFILE to LEVEL_4)
        DeviceStatement.declareTunnelingVideoDecoder(AV1)
        DeviceStatement.declareAppHeap(HEAP_MB)
        DeviceStatement.declareWidevine()

        val content = TestContent.protectedHls().servedFrom(CDN_HOST)
        val provider = MintingProvider()
        val recorder = SessionTraceRecorder()
        val player = harness.buildPlayer(
            content = content,
            // The recorder first in the composite, so that by the time this test has seen
            // `SessionEnded` the recorder has too — `LicenceTelemetryTest`'s reason.
            telemetry = QoeCollector(TelemetrySink.composite(recorder, TelemetrySink { events += it })),
            faults = FaultScript.Builder()
                // Three faults, and one session: a credential the CDN refuses once, so a refresh has
                // to repair it; an edge that answers 502 once, so the bundle carries a load error
                // with an HTTP status on it (`PRD.md` §3.6 asks for the status, and only a refusal
                // Media3's analytics report one for can put it there); and then a name that will not
                // resolve, so the session ends on an exception whose message is the host
                // (`FaultInjectingDataSource` synthesizes the `UnknownHostException` a real resolver
                // failure arrives in).
                .expireTokenAtSegment(FAULTED_SEGMENT, refreshable = true)
                .failWithHttpStatus(
                    FaultScript.HTTP_SERVER_ERROR,
                    kind = ResourceKind.MEDIA_SEGMENT,
                    index = REFUSED_SEGMENT,
                    firstAttempts = 1,
                )
                .failDnsResolution(kind = ResourceKind.MEDIA_SEGMENT, index = FATAL_SEGMENT)
                .build(),
            resilience = Resilience.standard(headers = provider),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)),
        )
        recorder.attach(player)
        player.setMediaRequest(
            MediaRequest.Builder(CONTENT_ID)
                .addSource(content.sourceUri)
                // Consumer material with a query and a token in it, which an app really does carry:
                // artwork behind the same signing as the media. Nothing of a request but its content
                // id is a fact a trace records, and this is what makes that assertable.
                .setArtworkUri(SIGNED_ARTWORK_URI)
                .build(),
        )
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        // The material, half of it read off the wire: the requests this session really made.
        val requests = harness.networkRequests(player)
        val hostedRequests = requests.filter { it.uri.contains(CDN_HOST) }
        assertThat(hostedRequests).isNotEmpty()
        val playedUri = hostedRequests.first().uri
        // A credential was minted and sent, which only a refusal repaired inside its own transfer can
        // have caused (`TokenRefreshPlaybackTest` is where that mechanism is the subject).
        assertThat(provider.calls()).isEqualTo(1)
        val credential = provider.minted(1)
        assertThat(requests.any { it.headers[AUTHORIZATION] == credential }).isTrue()
        // And the session id was on the wire, as CMCD's `sid` on every request (ADR-0008 rule 6).
        val sessionId = sessionId()
        assertThat(requests.any { request -> request.headers.values.any { it.contains(sessionId) } }).isTrue()
        // The signed artwork URL is really held by the session rather than only written above: it is
        // on the item the player is playing, which is where a `MediaRequest`'s artwork travels.
        assertThat(player.currentMediaItem?.mediaMetadata?.artworkUri?.toString()).isEqualTo(SIGNED_ARTWORK_URI)

        // The fatal half of the same session: the name stops resolving, every rung is spent, and the
        // session ends on a failure whose exception message names the host it could not resolve.
        harness.advanceUntil(player, "a failure", FAILURE_BOUND_MS) { it.playerError != null }
        val failureText = messagesOf(player.playerError)
        assertThat(failureText).contains(CDN_HOST)

        awaitSessionEnd(player)
        val licence = synchronized(events) { events.filterIsInstance<TelemetryEvent.LicenceAcquisitionEnded>() }
        assertThat(licence.map { it.outcome }).contains(LicenceOutcome.ACQUIRED_FROM_SERVER)

        val bundle = SessionBundle.Builder(ApplicationProvider.getApplicationContext())
            .setTrace(recorder.trace())
            .setPlayer(player)
            .build()
        val text = bundle.format()

        // One artifact, naming itself and both format versions (ADR-0015 rule 9).
        assertThat(text.lines().first()).isEqualTo("superplayer-session-bundle v${SessionBundle.FORMAT_VERSION}")

        // It is a bundle of the session that had all of that, not of an empty one: the licence, the
        // repaired transfer's retry, the loads and the failure are all in it.
        assertThat(text).contains("telemetry LicenceAcquisitionEnded")
        assertThat(text).contains("outcome=ACQUIRED_FROM_SERVER")
        assertThat(text).contains("load completed media")
        assertThat(text).contains("durationMs=")
        // The status the 502 refusal put on a load line, which is the half of "load events with their
        // timings and HTTP status" that only a refusal can demonstrate.
        assertThat(text).contains("http=${FaultScript.HTTP_SERVER_ERROR}")
        assertThat(text).containsMatch("telemetry (StartupFailed|MidStreamFailed)")

        // Rule 1: no URL, host, path, query or token. Not the CDN, not the address a segment was
        // fetched from, not the signed one that failed, and not a query at all.
        assertThat(text).doesNotContain(CDN_HOST)
        assertThat(text).doesNotContain(playedUri)
        assertThat(text).doesNotContain(SIGNED_ARTWORK_URI)
        assertThat(text).doesNotContain(SIGNING_TOKEN)
        assertThat(text).doesNotContain("?")
        assertThat(text).doesNotContain("https://")

        // Rule 2: no request or response header — neither the name the credential travelled under nor
        // its value, and none of CMCD's keys, which are headers on this chain.
        assertThat(text).doesNotContain(AUTHORIZATION)
        assertThat(text).doesNotContain(credential)
        assertThat(text).doesNotContain(CMCD_HEADER_PREFIX)

        // Rule 3: no exception message. The failure is in the bundle as a code and a category, and the
        // message that named the URL is not — asserted line by line, because the message is long and
        // `doesNotContain` over the whole of it would pass on a bundle that printed half.
        failureText.lines().filter { it.isNotBlank() }.forEach { assertThat(text).doesNotContain(it) }

        // Rule 4: no session id and no wall clock. The clock half is asserted as the *shape* of one
        // rather than as one reading of the host's: a leak from a different millisecond than this
        // assertion's would evade an equality, and every time in the format is declared relative.
        assertThat(text).doesNotContain(sessionId)
        assertThat(text.lines()).contains("timings relative-ms")
        assertThat(WALL_CLOCK_SHAPED.containsMatchIn(text)).isFalse()

        // Rule 5 and rule 7's refusals: nothing of what the device *is*. Every `Build` string ADR-0015
        // rule 10 names, and every decoder component name — the field rule 10 calls the one a snapshot
        // is most tempted by, on a device that declares real ones.
        val buildStrings = buildStrings()
        assertThat(buildStrings).isNotEmpty()
        buildStrings.forEach { assertThat(text).doesNotContain(it) }
        val componentNames = videoDecoderComponentNames()
        assertThat(componentNames).isNotEmpty()
        componentNames.forEach { assertThat(text).doesNotContain(it) }

        // Rule 6: no DRM payload. Not the licence server, not its address, not the protection system,
        // and not the initialization data the manifest declared — from which `FakeExoMediaDrm`'s key
        // request and response are both derived, so a bundle free of it is free of them.
        assertThat(text).doesNotContain(FakeLicenceServer.HOST)
        assertThat(text).doesNotContain(FakeLicenceServer.LICENCE_URI)
        assertThat(text).doesNotContain(WidevineProtection.SYSTEM_ID)
        assertThat(text).doesNotContain(WidevineProtection.psshBase64())

        // Rule 7's admissions, the other side of the same rule: what the device can *do* is here, and
        // it is the snapshot core handed over rather than a second reading of the platform.
        assertThat(text).contains("capability device apiLevel=${Build.VERSION.SDK_INT} lowRam=false heapBudgetMb=$HEAP_MB")
        assertThat(text).contains(
            "capability decoder mime=$H264 secure=false instances=$DECODER_INSTANCES " +
                "profile=$HIGH_PROFILE level=$LEVEL_4 tunneling=false",
        )
        assertThat(text).contains(
            "capability decoder mime=$HEVC secure=true instances=$SECURE_DECODER_INSTANCES " +
                "profile=$HIGH_PROFILE level=$LEVEL_4 tunneling=false",
        )
        // The tunneling decoder, whose instance limit the test does not state: Robolectric's codec
        // builder answers its own default for one, so the line is matched on the fields this test
        // stated rather than on that number.
        assertThat(text).containsMatch(
            "capability decoder mime=$AV1 secure=false instances=[0-9]+ profile=unknown level=unknown tunneling=true",
        )
        assertThat(text).contains("capability display shortEdgePx=")
        // The session negotiated no level: the stated device honours what it reports, so ADR-0012
        // rule 11's ladder never engaged. What matters here is that the field is a word and not a
        // device identifier whichever way it reads.
        assertThat(text).contains("capability protection deliveredSecurityLevel=not-negotiated")
    }

    @Test
    fun theProtectionLineCarriesTheLevelASessionWasDeliveredAt() {
        // Rule 10's protection field, exercised rather than assumed. Every other case here reads
        // `not-negotiated`, because a device that honours what it reports negotiates nothing — so
        // this states the device the ladder exists for (`superplayer-drm`'s `SecurityLevelTest`): a
        // Widevine implementation reporting `L1` beside a codec table with an ordinary decoder and no
        // secure one, against a server whose operator has published `L3` as permitted.
        DeviceStatement.declareVideoDecoder(H264)
        DeviceStatement.declareWidevine()

        val content = TestContent.protectedDash()
        val recorder = SessionTraceRecorder()
        val player = harness.buildPlayer(
            content = content,
            telemetry = QoeCollector(TelemetrySink.composite(recorder, TelemetrySink { events += it })),
            drm = Drm.widevine(
                WidevineConfig(FakeLicenceServer.LICENCE_URI, setOf(WidevineConfig.SECURITY_LEVEL_L3)),
            ),
        )
        recorder.attach(player)
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        harness.playToReady(player)

        // The level really was lowered, so the line below is a reading and not a default.
        assertThat(player.deliveredSecurityLevel).isEqualTo(WidevineConfig.SECURITY_LEVEL_L3)

        val text = SessionBundle.Builder(ApplicationProvider.getApplicationContext())
            .setTrace(recorder.trace())
            .setPlayer(player)
            .build()
            .format()

        assertThat(text).contains(
            "capability protection deliveredSecurityLevel=${WidevineConfig.SECURITY_LEVEL_L3}",
        )
        // A level is what the device can *do*; what it *is* stays out, on this session as on the other.
        buildStrings().forEach { assertThat(text).doesNotContain(it) }
    }

    /** The one session id every event of the first session belongs to. */
    private fun sessionId(): String =
        synchronized(events) { events.filterIsInstance<TelemetryEvent.SessionStarted>().first().sessionId }

    /**
     * Releases [player] and waits for its last event to arrive, `LicenceTelemetryTest`'s way: the
     * delivery queue is asynchronous by design and `SessionEnded` is what says everything before it
     * has been handed over.
     */
    private fun awaitSessionEnd(player: SuperPlayer) {
        harness.release(player)
        runMainLooperUntil(::sessionHasEnded)
    }

    /** Externally synchronized, because the delivery thread appends while this reads. */
    private fun sessionHasEnded(): Boolean =
        synchronized(events) { events.any { it is TelemetryEvent.SessionEnded } }

    /** Every message in [throwable]'s cause chain, which is where Media3 puts a failing URL. */
    private fun messagesOf(throwable: Throwable?): String = buildString {
        var cause: Throwable? = throwable
        while (cause != null) {
            cause.message?.let { append(it).append('\n') }
            cause = cause.cause
        }
    }

    /** The `Build` strings ADR-0015 rule 10 refuses by name, blank ones left out as unassertable. */
    private fun buildStrings(): List<String> = listOfNotNull(
        Build.MANUFACTURER,
        Build.MODEL,
        Build.DEVICE,
        Build.PRODUCT,
        Build.BOARD,
        Build.FINGERPRINT,
        Build.ID,
    ).filter { it.isNotBlank() }.distinct()

    /** What the platform's codec list calls this device's video decoders. */
    private fun videoDecoderComponentNames(): List<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.lowercase().startsWith("video/") } }
            .map { it.name }
            .distinct()

    /** A provider that mints a token every time it is asked, and remembers what it minted. */
    private class MintingProvider : HeaderProvider {

        private val minted = CopyOnWriteArrayList<String>()

        override fun refreshedHeaders(refusal: CredentialRefusal): Map<String, String> {
            val token = "Bearer minted-secret-${minted.size + 1}"
            minted += token
            return mapOf(AUTHORIZATION to token)
        }

        fun calls(): Int = minted.size

        /** The [ordinal]th token this minted, counting from one. */
        fun minted(ordinal: Int): String = minted[ordinal - 1]
    }

    private companion object {
        const val CONTENT_ID = "series/expanse/s01e01"

        /** The CDN this session's content is served from, so every URL has a host worth leaking. */
        const val CDN_HOST = "edge-42.cdn.example"

        /** A signed URL an app carries beside the media: a host, a path, a query and a token. */
        const val SIGNING_TOKEN = "hmac-a1b2c3d4e5"
        const val SIGNED_ARTWORK_URI = "https://$CDN_HOST/art/s01e01.jpg?token=$SIGNING_TOKEN&expires=2000000000"

        const val AUTHORIZATION = "Authorization"

        /** CTA-5004's request header prefix: `CMCD-Object`, `CMCD-Request`, `CMCD-Session`, `CMCD-Status`. */
        const val CMCD_HEADER_PREFIX = "CMCD-"

        const val H264 = "video/avc"
        const val HEVC = "video/hevc"
        const val AV1 = "video/av01"

        /** `AVCProfileHigh` and `AVCLevel4`, as `MediaCodecInfo.CodecProfileLevel` numbers them. */
        const val HIGH_PROFILE = 8
        const val LEVEL_4 = 2048

        const val DECODER_INSTANCES = 6
        const val SECURE_DECODER_INSTANCES = 1
        const val HEAP_MB = 192

        /** The segment whose credential the CDN refuses once, so a refresh has to repair it. */
        const val FAULTED_SEGMENT = 1

        /** The segment the edge refuses once with a 502, so a load line carries an HTTP status. */
        const val REFUSED_SEGMENT = 2

        /** The segment whose host stops resolving, and which nothing can repair. */
        const val FATAL_SEGMENT = 3
        const val PLAYED_MS = 4_000L
        const val FAILURE_BOUND_MS = 120_000L

        /**
         * A wall clock in milliseconds, which is thirteen digits for every date this library will see.
         * Nothing in the format prints one, and a line that did would be the drift rule 4 forbids.
         */
        val WALL_CLOCK_SHAPED = Regex("\\b1[0-9]{12}\\b")
    }
}
