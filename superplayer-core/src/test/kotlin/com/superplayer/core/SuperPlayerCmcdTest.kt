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

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * What CMCD (CTA-5004) puts on the requests a built player actually issues.
 *
 * Driven over the real transfer chain rather than over `FakeDataSource`, for the reason
 * `SuperPlayerTransferChainTest` gives: the chain is what CMCD is configured *on*, and a test that
 * substitutes a media source factory of its own has replaced the subject. So the stream is a `file:`
 * one the real chain resolves, and the requests are read where every layer of the chain reports
 * them — through the `TransferListener` the engine propagates down it, which is
 * [RecordingBandwidthMeter].
 *
 * That listener is also the reason this can assert on values rather than only on keys: it sees the
 * `DataSpec` the chunk source built, headers and all, at the moment it is opened.
 *
 * ## The one key that cannot be asserted here, and why that is not a gap in the library
 *
 * `mtp` — measured throughput — is the exception. Media3 fills it from
 * `ExoTrackSelection.getLatestBitrateEstimate()`, which only an *adaptive* selection keeps; a fixed
 * selection has no estimate and the key is omitted rather than sent empty. Nothing SuperPlayer
 * configures suppresses it: no key filter is applied at all, so every key Media3 knows how to fill
 * travels.
 *
 * Getting an adaptive selection needs a renderer that reports adaptive support, which under
 * Robolectric means video, and `docs/testing.md` bars the device and the network that a real video
 * stream would need. So `mtp` is verified where the issue's acceptance criteria put it — on the
 * emulator, against the demo's real streams — and asserted here only as far as this harness can
 * honestly reach. A test that faked an estimate to produce the key would be asserting on the fake.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerCmcdTest {

    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val streamDirectory: TemporaryFolder = TemporaryFolder()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    @Test
    fun theKeysThisLibraryIsAccountableForReachTheRequestsThePlayerIssues() {
        val requests = RecordingBandwidthMeter()
        val player = playUntilReady(requests)
        player.play()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_ENDED)

        // Segment requests rather than playlist ones: `br`, `bl` and `su` describe a media object
        // being fetched, and a manifest fetch has no bitrate to report.
        val segments = requests.cmcdKeysOfSegmentRequests()
        val first = segments.first()

        // spec: CTA-5004 §3.1 — encoded bitrate in kbps, which for this stream is the higher of the
        // two bitrates its multivariant playlist declares, because that is the variant selected.
        assertThat(first["br"]?.toInt())
            .isEqualTo(SyntheticHlsStream.HIGHER_DECLARED_BITRATE_BPS / 1_000)
        // spec: CTA-5004 §3.1 — `su` is a valueless key, present when the object is needed urgently.
        // The first segment of a session is the definition of urgent, and the ones after it are not.
        assertThat(first).containsKey("su")
        assertThat(segments.last()).doesNotContainKey("su")
        // spec: CTA-5004 §3.1 — buffer length in ms, rounded to 100ms, and §3.1 `dl`, the deadline
        // in ms until the first sample must be available: `bl` divided by the playback rate, which
        // at rate 1.0 tracks `bl`. Empty at the first request and not at the last, which is the
        // plausibility worth asserting: a `bl` that never moved would be a constant, not a
        // measurement.
        assertThat(first["bl"]?.toInt()).isEqualTo(0)
        assertThat(first["dl"]?.toInt()).isEqualTo(0)
        assertThat(segments.last()["bl"]?.toInt()).isGreaterThan(0)
        assertThat(segments.last()["dl"]?.toInt()).isGreaterThan(0)

        assertThat(player.playerError).isNull()
    }

    @Test
    fun theCmcdSessionIdIsTheTelemetrySessionId() {
        val telemetry = RecordingTelemetry { event -> events += event }
        val requests = RecordingBandwidthMeter()
        playUntilReady(requests, telemetry = telemetry)

        val started = events.filterIsInstance<TelemetryEvent.SessionStarted>().single()

        // The whole value of CMCD: this equality is what joins a row in a CDN's access log to a row
        // in the app's warehouse. Documented as a promise in `docs/telemetry-schema.md`, and this is
        // where the promise is kept.
        assertThat(requests.cmcdKeysOfSegmentRequests().first()["sid"]).isEqualTo(quoted(started.sessionId))
    }

    @Test
    fun theContentIdTravelsAsTheAppsOwnIdentityRatherThanAsAUrl() {
        val requests = RecordingBandwidthMeter()
        playUntilReady(requests)

        // spec: CTA-5004 §3.1 `cid` — and the point of MediaRequest: what a CDN logs is the app's
        // identity for the content, so the same title behind two CDNs is one row.
        assertThat(requests.cmcdKeysOfSegmentRequests().first()["cid"]).isEqualTo(quoted(EPISODE))
    }

    @Test
    fun aPlayerInQueryParameterModePutsTheKeysInTheUrlAndLeavesTheHeadersAlone() {
        val requests = RecordingBandwidthMeter()
        playUntilReady(requests, cmcdMode = CmcdMode.QUERY_PARAMETER)

        val segment = requests.segmentRequest()
        assertThat(segment.httpRequestHeaders.keys).doesNotContain("CMCD-Request")

        // spec: CTA-5004 §3.3 — one URL-encoded `CMCD` query parameter carrying the whole key set.
        val parameter = checkNotNull(segment.uri.getQueryParameter("CMCD")) {
            "No CMCD query parameter on ${segment.uri}"
        }
        assertThat(parameterKeys(parameter)).containsAtLeast("br", "bl", "dl", "su", "sid", "cid")
    }

    @Test
    fun aPlayerWithCmcdDisabledIssuesTheRequestsItWouldHaveIssuedAnyway() {
        val requests = RecordingBandwidthMeter()
        val player = playUntilReady(requests, cmcdMode = CmcdMode.DISABLED)

        val segment = requests.segmentRequest()
        assertThat(segment.httpRequestHeaders.keys.filter { it.startsWith("CMCD") }).isEmpty()
        assertThat(segment.uri.getQueryParameter("CMCD")).isNull()

        // And playback is untouched by the absence, which is the other half of what "disabled" has
        // to mean.
        assertThat(player.playerError).isNull()
    }

    @Test
    fun everyProfileEmitsCmcdIncludingTheOneThatSpendsLessData() {
        PlaybackProfile.entries.forEach { profile ->
            val requests = RecordingBandwidthMeter()
            playUntilReady(requests, profile = profile)

            // `StaticCmcdPolicy`'s table, asserted rather than described: DATA_SAVER is the profile
            // with a case against emitting, and it emits.
            assertThat(requests.cmcdKeysOfSegmentRequests().first()).containsKey("sid")
        }
    }

    @Test
    fun contentSetThroughMediaItemCarriesNoSessionIdToJoinOnRatherThanTheLastOne() {
        val requests = RecordingBandwidthMeter()
        val player = harness.buildPlayerOnItsOwnTransferChain(
            alsoConfigureEngine = { engine -> engine.setBandwidthMeter(requests) },
        )

        // A raw MediaItem has no content identity, so no measurement session opened for it — and a
        // `sid` here would be the *previous* content's, quietly attributing this title's CDN traffic
        // to another one.
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(writeStream()))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        val keys = requests.cmcdKeysOfSegmentRequests().first()
        assertThat(keys).doesNotContainKey("sid")
        assertThat(keys).doesNotContainKey("cid")
        // The keys that describe the request rather than the session still travel: they are true of
        // any request, identified or not.
        assertThat(keys).containsKey("br")
    }

    /** What a [RecordingTelemetry] built in a test writes to, drained by the test that made it. */
    private val events = mutableListOf<TelemetryEvent>()

    /**
     * Builds a player on its own transfer chain, plays the synthetic stream through
     * [MediaRequest] under [EPISODE], and returns once there is something to assert on.
     */
    private fun playUntilReady(
        requests: RecordingBandwidthMeter,
        profile: PlaybackProfile? = null,
        cmcdMode: CmcdMode? = null,
        telemetry: TelemetryCollector? = null,
    ): SuperPlayer {
        val player = harness.buildPlayerOnItsOwnTransferChain(
            profile = profile,
            cmcdMode = cmcdMode,
            telemetry = telemetry,
            alsoConfigureEngine = { engine -> engine.setBandwidthMeter(requests) },
        )

        player.setMediaRequest(
            MediaRequest.Builder(EPISODE).addSource(writeStream()).build(),
        )
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)

        return player
    }

    /**
     * The stream on disk, fresh per player so two players in one test do not share a directory.
     *
     * Two variants rather than the usual one, because `mtp` comes from the track selection's own
     * bandwidth estimate and only an *adaptive* selection keeps one — a stream with a single variant
     * is selected by a fixed selection, which has no estimate to report and therefore sends no `mtp`
     * at all. That is Media3's behaviour rather than SuperPlayer's, and a test that asserted `mtp`
     * over a one-variant stream would be asserting something no real adaptive stream does.
     */
    private fun writeStream(): Uri =
        Uri.parse(SyntheticHlsStream.writeTo(streamDirectory.newFolder(), segmentCount = 3, variantCount = 2))

    private fun parameterKeys(parameter: String): List<String> =
        splitCmcdPairs(parameter).map { it.substringBefore('=') }

    private companion object {
        const val EPISODE = "series-9:episode-4"

        /** spec: CTA-5004 §3.1 — a string-valued key is sent quoted. */
        fun quoted(value: String) = "\"$value\""
    }
}
