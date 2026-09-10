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

package com.superplayer.testkit

import android.net.Uri
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.FakeDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The fault injector, driven directly rather than through a player.
 *
 * What is pinned here is the two halves the issue calls the design problem: that a fault is
 * addressed by *what is being fetched* rather than by a URL — so one script means one thing under
 * HLS and under DASH — and that every injected fault lands on the harness's clock rather than on the
 * wall clock, which is what makes "fails at segment 3" fail at segment 3 on every machine.
 *
 * Playback is not involved on purpose. The claim under test is a claim about the transfer, and a
 * player between the assertion and the thing asserted on would only add ways for the test to be
 * wrong. `FaultInjectionPlaybackTest` is where the same injector is exercised under a real load.
 */
@RunWith(AndroidJUnit4::class)
class FaultInjectionTest {

    @Test
    fun withNothingArmedTheWrapperDeliversTheUpstreamBytesUnchanged() {
        val source = sourceOver(FaultScript.NONE)

        val delivered = readFully(source, HLS_SEGMENTS[0])

        assertThat(delivered).isEqualTo(bodyOf(HLS_SEGMENTS[0]))
    }

    @Test
    fun transferListenerReportingSurvivesTheWrapperEndToEnd() {
        var started = 0
        var ended = 0
        var bytes = 0L
        val listener = object : TransferListener {
            override fun onTransferInitializing(s: DataSource, d: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(s: DataSource, d: DataSpec, isNetwork: Boolean) {
                started++
            }

            override fun onBytesTransferred(s: DataSource, d: DataSpec, isNetwork: Boolean, count: Int) {
                bytes += count
            }

            override fun onTransferEnd(s: DataSource, d: DataSpec, isNetwork: Boolean) {
                ended++
            }
        }
        val source = sourceOver(FaultScript.NONE)
        source.addTransferListener(listener)

        readFully(source, HLS_SEGMENTS[0])

        // Measurement is a propagated `TransferListener` (`PRD.md` §2.4): a wrapper that swallowed
        // these would blind the bandwidth meter, and every ABR test built on this harness would pass
        // while measuring nothing.
        assertThat(started).isEqualTo(1)
        assertThat(ended).isEqualTo(1)
        assertThat(bytes).isEqualTo(bodyOf(HLS_SEGMENTS[0]).size.toLong())
    }

    @Test
    fun theSameScriptAddressesTheSameResourceUnderHlsAndDash() {
        // The URL sequences are not this test's to invent: they are what Media3's own HLS playlist
        // parser and DASH manifest parser produce from a playlist and an MPD. A test that wrote the
        // URLs itself would be checking that the classifier agrees with the test's idea of what a
        // segment is called, which is the one thing it cannot be allowed to prove.
        val hls = addressesOf(HLS_SESSION).map { it.toString() }
        val dash = addressesOf(DASH_SESSION).map { it.toString() }

        // From the initialization segment on, the two are equal: initialization segments are
        // initialization segments and media segments are numbered from zero in the order they were
        // asked for, whether the protocol spells them `.aac` or `.m4s`. That equality is the point
        // of addressing a fault by kind and index — one script, either protocol, one meaning.
        val playable = listOf("INITIALIZATION#0", "MEDIA_SEGMENT#0", "MEDIA_SEGMENT#1", "MEDIA_SEGMENT#2", "MEDIA_SEGMENT#3")
        assertThat(hls.dropWhile { it.startsWith("MANIFEST") }).isEqualTo(playable)
        assertThat(dash.dropWhile { it.startsWith("MANIFEST") }).isEqualTo(playable)

        // What does differ is how many manifests each protocol fetches, and that is a real
        // difference rather than one the addressing should hide: HLS declares its renditions in a
        // multivariant playlist and then fetches a media playlist per rendition, while DASH declares
        // everything in the one MPD — so "fail the second manifest" is a sentence that only means
        // something under HLS.
        assertThat(hls.takeWhile { it.startsWith("MANIFEST") }).containsExactly("MANIFEST#0", "MANIFEST#1").inOrder()
        assertThat(dash.takeWhile { it.startsWith("MANIFEST") }).containsExactly("MANIFEST#0")
    }

    @Test
    fun theProtocolsProduceTheUrlsTheRestOfThisFileNames() {
        // The other tests here address resources by the constants below, and this is what keeps
        // those honest: the segment URLs a real playlist parse yields are those constants. Without
        // it, a heuristic that had drifted from what a packager actually emits would still pass
        // every test in this file.
        assertThat(HLS_SESSION.filter { it.endsWith(SEGMENT_SUFFIX) }).isEqualTo(HLS_SEGMENTS)
        assertThat(HLS_SESSION.filter { it.endsWith(".m3u8") }).isEqualTo(HLS_MANIFESTS)
    }

    @Test
    fun anExpiredTokenFailsTheChosenSegmentAndEveryOneAfterIt() {
        listOf(HLS_SESSION, DASH_SESSION).forEach { session ->
            val factory = factoryOver(FaultScript.Builder().expireTokenAtSegment(2).build())

            // The requests first: `requested` is a snapshot, and one taken before the session ran
            // would be empty.
            val statuses = session.map { url -> statusOf(factory, url) }
            val outcomes = factory.addresses.requested.zip(statuses)

            // Stated as the sequence rather than as one failed request: a token that has expired
            // stays expired, so a ladder that retried the one segment would still be stuck — which
            // is the case `superplayer-resilience` exists for. Identical under both protocols, and
            // said in segment indices, because that is the only vocabulary in which the two
            // sessions are the same sentence.
            assertThat(outcomes.filter { it.first.kind == ResourceKind.MEDIA_SEGMENT }.map { it.second })
                .containsExactly(DELIVERED, DELIVERED, 403, 403).inOrder()
            // And nothing that was not a media segment was touched, whether the protocol fetched one
            // manifest or two.
            assertThat(outcomes.filterNot { it.first.kind == ResourceKind.MEDIA_SEGMENT }.map { it.second })
                .containsNoneOf(403, 404)
        }
    }

    @Test
    fun aRetryOfAnExpiredSegmentUnderAFreshTokenIsStillThatSegment() {
        val factory = factoryOver(FaultScript.Builder().expireTokenAtSegment(1).build())
        val segment = HLS_SEGMENTS[1]

        assertThat(statusOf(factory, HLS_SEGMENTS[0])).isEqualTo(DELIVERED)
        assertThat(statusOf(factory, segment)).isEqualTo(403)
        // The same segment behind a new signature. A test harness that keyed on the whole URL would
        // call this a new resource, slide every later index by one, and quietly stop failing the
        // segment the script named.
        assertThat(statusOf(factory, "$segment?token=refreshed")).isEqualTo(403)
        assertThat(factory.addresses.requested).hasSize(2)
    }

    @Test
    fun eachHttpStatusSurfacesAsTheResponseCodeARealStackWouldThrow() {
        listOf(FaultScript.HTTP_FORBIDDEN, FaultScript.HTTP_NOT_FOUND, FaultScript.HTTP_SERVER_ERROR)
            .forEach { status ->
                val script = FaultScript.Builder()
                    .failWithHttpStatus(status, ResourceKind.MEDIA_SEGMENT, index = 0)
                    .build()

                val failure = assertThrows(HttpDataSource.InvalidResponseCodeException::class.java) {
                    readFully(sourceOver(script), HLS_SEGMENTS[0])
                }

                assertThat(failure.responseCode).isEqualTo(status)
            }
    }

    @Test
    fun aManifestFaultLeavesTheSegmentsAlone() {
        val script = FaultScript.Builder()
            .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, ResourceKind.MANIFEST)
            .build()
        val factory = factoryOver(script)

        // Every manifest, because no index was named — and nothing else, because a kind was.
        assertThat(HLS_MANIFESTS.map { statusOf(factory, it) }).containsExactly(500, 500)
        assertThat(HLS_SEGMENTS.map { statusOf(factory, it) })
            .containsExactly(DELIVERED, DELIVERED, DELIVERED, DELIVERED)
    }

    @Test
    fun aDnsFailureArrivesAsAnUnknownHostWithoutAnySocket() {
        val script = FaultScript.Builder().failDnsResolution().build()

        val failure = assertThrows(HttpDataSource.HttpDataSourceException::class.java) {
            readFully(sourceOver(script), HLS_MANIFESTS[0])
        }

        // The shape a real resolver failure reaches Media3 in, synthesized: `docs/testing.md` bars
        // the network from these tests, and an injector that needed a real resolver to inject a
        // resolver failure would have missed the point.
        assertThat(failure).hasCauseThat().isInstanceOf(UnknownHostException::class.java)
    }

    @Test
    fun aTruncatedResponseEndsTheBodyEarlyWhileTheDeclaredLengthStands() {
        val script = FaultScript.Builder()
            .truncateAfterBytes(TRUNCATE_AT, ResourceKind.MEDIA_SEGMENT, index = 0)
            .build()
        val source = sourceOver(script)

        val declaredLength = source.open(DataSpec(Uri.parse(HLS_SEGMENTS[0])))
        val delivered = try {
            DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }

        // The request succeeded and the length was promised; the promise was broken. That is a
        // different failure from a status code, which is why it is injected separately — the layer
        // above sees a truncated container rather than a failed fetch.
        assertThat(declaredLength).isEqualTo(bodyOf(HLS_SEGMENTS[0]).size.toLong())
        assertThat(delivered.size.toLong()).isEqualTo(TRUNCATE_AT)
        // And a reader that insists on the promised length is told exactly how short it fell, which
        // is how a truncated container is told apart from an empty one further up.
        val second = sourceOver(script)
        second.open(DataSpec(Uri.parse(HLS_SEGMENTS[0])))
        val short = assertThrows(IllegalStateException::class.java) {
            DataSourceUtil.readExactly(second, bodyOf(HLS_SEGMENTS[0]).size)
        }
        assertThat(short).hasMessageThat().contains("$TRUNCATE_AT < $BODY_BYTES")
        second.close()
    }

    @Test
    fun anInjectedLatencyElapsesOnTheHarnessClockAndNotOnTheWallClock() {
        val clock = FakeClock(0L, /* isAutoAdvancing= */ false)
        val script = FaultScript.Builder()
            .addLatencyMs(LATENCY_MS, ResourceKind.MEDIA_SEGMENT, index = 0)
            .build()
        val injector = factoryOver(script, clock)
        val source = injector.createDataSource()
        val openedAtMs = AtomicLong(NEVER)

        onALoadingThread(clock, injector, stepMs = LATENCY_MS) {
            source.open(DataSpec(Uri.parse(HLS_SEGMENTS[0])))
            openedAtMs.set(clock.elapsedRealtime())
            source.close()
        }

        // Exact, not approximate. The load could not have completed before the clock reached the
        // delay, because the only thing that releases it is the clock reaching the delay — which is
        // what makes a fault reproducible on a loaded CI runner and on an idle laptop alike.
        assertThat(openedAtMs.get()).isEqualTo(LATENCY_MS)
    }

    @Test
    fun aThroughputCapPacesDeliveryAgainstTheHarnessClock() {
        val clock = FakeClock(0L, /* isAutoAdvancing= */ false)
        val body = bodyOf(HLS_SEGMENTS[0])
        val script = FaultScript.Builder().capThroughputBps(CAP_BPS).build()
        val injector = factoryOver(script, clock)
        val source = injector.createDataSource()
        val finishedAtMs = AtomicLong(NEVER)
        val delivered = AtomicReference<ByteArray>()

        onALoadingThread(clock, injector, stepMs = CLOCK_STEP_MS) {
            source.open(DataSpec(Uri.parse(HLS_SEGMENTS[0])))
            delivered.set(DataSourceUtil.readToEnd(source))
            finishedAtMs.set(clock.elapsedRealtime())
            source.close()
        }

        // Every byte arrives; what the cap changes is when. A cap that dropped bytes would be a
        // corruption fault wearing a bandwidth fault's name.
        assertThat(delivered.get()).isEqualTo(body)
        assertThat(finishedAtMs.get()).isAtLeast(body.size * 8L * 1_000 / CAP_BPS)
    }

    /**
     * Runs [body] on a thread that loads, advancing [clock] until it finishes.
     *
     * A loading thread and a test thread are exactly the two the engine has, and an injected delay is
     * released by the second advancing the clock the first is waiting on. Bounded in wall-clock time
     * so a test that never releases its own load fails rather than hangs.
     */
    private fun onALoadingThread(
        clock: FakeClock,
        injector: FaultInjectingDataSource.Factory,
        stepMs: Long,
        body: () -> Unit,
    ) {
        val failure = AtomicReference<Throwable>()
        val thread = Thread { runCatching(body).onFailure(failure::set) }
        thread.start()
        var advancedMs = 0L
        while (thread.isAlive && advancedMs < MAX_CLOCK_ADVANCE_MS) {
            // The clock moves only while a load is actually waiting on it, and then by one step,
            // and then not again until that load has seen it. Advancing on any other schedule would
            // race the load to the clock and answer "how long did the delay take" with whatever
            // reading the pump had reached first — which is a tolerance rather than a measurement.
            if (injector.isWaitingOnTheClock) {
                clock.advanceTime(stepMs)
                advancedMs += stepMs
                waitForTheLoadToSeeIt(injector, thread)
            } else {
                Thread.yield()
            }
        }
        thread.join(JOIN_TIMEOUT_MS)
        failure.get()?.let { throw it }
        check(!thread.isAlive) { "The load never finished within $MAX_CLOCK_ADVANCE_MS ms of fake time" }
    }

    /** Yields until the waiting load has acted on the advance, or long enough to say it needs more. */
    private fun waitForTheLoadToSeeIt(injector: FaultInjectingDataSource.Factory, thread: Thread) {
        val startedAtMs = System.currentTimeMillis()
        while (injector.isWaitingOnTheClock && thread.isAlive &&
            System.currentTimeMillis() - startedAtMs < HANDOVER_MS
        ) {
            Thread.yield()
        }
    }

    private fun addressesOf(session: List<String>): List<ResourceAddress> {
        val factory = factoryOver(FaultScript.NONE)
        session.forEach { url -> statusOf(factory, url) }
        return factory.addresses.requested
    }

    /** [DELIVERED] if the resource was served, or the HTTP status the injector answered with. */
    private fun statusOf(factory: FaultInjectingDataSource.Factory, url: String): Int {
        val source = factory.createDataSource()
        return try {
            readFully(source, url)
            DELIVERED
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            e.responseCode
        }
    }

    private fun readFully(source: DataSource, url: String): ByteArray {
        source.open(DataSpec(Uri.parse(url)))
        return try {
            DataSourceUtil.readToEnd(source)
        } finally {
            source.close()
        }
    }

    private fun sourceOver(script: FaultScript): DataSource = factoryOver(script).createDataSource()

    private fun factoryOver(
        script: FaultScript,
        clock: FakeClock = FakeClock(0L, /* isAutoAdvancing= */ false),
    ): FaultInjectingDataSource.Factory = FaultInjectingDataSource.Factory(
        FakeDataSource.Factory().setFakeDataSet(dataSet()),
        script,
        clock,
    )

    /** Every URL either session fetches, with a body whose length is its own, so a read is checkable. */
    private fun dataSet(): FakeDataSet = FakeDataSet().apply {
        (HLS_SESSION + DASH_SESSION).forEach { url -> setData(url, bodyOf(url)) }
        newDefaultData().appendReadData(bodyOf("default")).endData()
    }

    private fun bodyOf(url: String): ByteArray = ByteArray(BODY_BYTES) { (url.hashCode() + it).toByte() }

    private companion object {

        /**
         * One HLS session and one DASH session, as the sequence of URLs each protocol produces.
         *
         * Derived by handing a playlist and an MPD to Media3's own parsers and asking what they
         * point at, in the order a player fetches them: the multivariant playlist, the media
         * playlist it names, the `EXT-X-MAP` target (// spec: RFC 8216 §4.3.2.5) and then the
         * segments; the MPD, its `<Initialization>` target (// spec: ISO/IEC 23009-1 §5.3.9.2) and
         * then the segments.
         */
        // Lazily, because these are derived from the manifests declared further down this object and
        // a companion initializes in declaration order.
        val HLS_SESSION: List<String> by lazy { hlsSession() }
        val DASH_SESSION: List<String> by lazy { dashSession() }

        val HLS_MANIFESTS = listOf(
            "https://cdn.test/hls/master.m3u8",
            "https://cdn.test/hls/media.m3u8",
        )
        const val SEGMENT_SUFFIX = ".aac"
        val HLS_SEGMENTS = (0..3).map { "https://cdn.test/hls/seg$it$SEGMENT_SUFFIX" }

        private fun hlsSession(): List<String> {
            val multivariantUri = Uri.parse("https://cdn.test/hls/master.m3u8")
            val multivariant = HlsPlaylistParser()
                .parse(multivariantUri, MULTIVARIANT_PLAYLIST.byteInputStream()) as HlsMultivariantPlaylist
            val mediaPlaylistUri = multivariant.variants.single().url
            val media = HlsPlaylistParser()
                .parse(mediaPlaylistUri, MEDIA_PLAYLIST.byteInputStream()) as HlsMediaPlaylist
            val initialization = media.segments.first().initializationSegment!!
            return listOf(multivariantUri.toString(), mediaPlaylistUri.toString()) +
                resolve(media.baseUri, initialization.url) +
                media.segments.map { resolve(media.baseUri, it.url) }
        }

        private fun dashSession(): List<String> {
            val manifestUri = Uri.parse("https://cdn.test/dash/manifest.mpd")
            val manifest = DashManifestParser().parse(manifestUri, MPD.byteInputStream())
            val representation = manifest.getPeriod(0).adaptationSets.single().representations.single()
            val baseUrl = representation.baseUrls.first().url
            val segments = representation as Representation.MultiSegmentRepresentation
            val first = segments.getFirstSegmentNum()
            val count = segments.getSegmentCount(manifest.getPeriodDurationUs(0))
            return listOf(manifestUri.toString()) +
                representation.getInitializationUri()!!.resolveUriString(baseUrl) +
                (first until first + count).map { segments.getSegmentUrl(it).resolveUriString(baseUrl) }
        }

        private fun resolve(baseUri: String, url: String): String =
            UriUtil.resolveToUri(baseUri, url).toString()

        private val MULTIVARIANT_PLAYLIST = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
            media.m3u8
        """.trimIndent()

        private val MEDIA_PLAYLIST = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-VERSION:6
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:2.0,
            seg0.aac
            #EXTINF:2.0,
            seg1.aac
            #EXTINF:2.0,
            seg2.aac
            #EXTINF:2.0,
            seg3.aac
            #EXT-X-ENDLIST
        """.trimIndent()

        private val MPD = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static"
                 mediaPresentationDuration="PT8.0S" minBufferTime="PT2.0S"
                 profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
              <Period start="PT0S" duration="PT8.0S">
                <AdaptationSet mimeType="audio/mp4" codecs="mp4a.40.2">
                  <Representation id="0" bandwidth="128000">
                    <SegmentList duration="2" timescale="1">
                      <Initialization sourceURL="init.mp4"/>
                      <SegmentURL media="segment0.m4s"/>
                      <SegmentURL media="segment1.m4s"/>
                      <SegmentURL media="segment2.m4s"/>
                      <SegmentURL media="segment3.m4s"/>
                    </SegmentList>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()

        /** Not an HTTP status: what [statusOf] reports when the resource was served. */
        const val DELIVERED = 200

        const val BODY_BYTES = 512
        const val TRUNCATE_AT = 100L
        const val LATENCY_MS = 250L

        /** A cap slow enough that a 512-byte body takes whole milliseconds to deliver. */
        const val CAP_BPS = 4_096L

        const val NEVER = -1L
        const val CLOCK_STEP_MS = 100L

        /** Real milliseconds a paced load gets to notice an advance before the pump makes another. */
        const val HANDOVER_MS = 200L
        const val MAX_CLOCK_ADVANCE_MS = 60_000L
        const val JOIN_TIMEOUT_MS = 10_000L
    }
}
