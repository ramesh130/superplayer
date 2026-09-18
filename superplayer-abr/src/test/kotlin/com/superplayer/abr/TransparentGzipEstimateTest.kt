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

package com.superplayer.abr

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.test.utils.FakeClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.HttpRequest
import com.superplayer.core.HttpResponse
import com.superplayer.core.HttpStack
import com.superplayer.core.HttpTransport
import com.superplayer.core.NetworkTransport
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * What a transparently-decompressing HTTP client costs the bandwidth estimate — ADR-0016 rule 7's
 * failure, as a number rather than as a warning.
 *
 * The rule says a consumer's transport must not negotiate a content coding of its own, and core
 * sends `Accept-Encoding: identity` on every request so that the commonest clients cannot. Nothing
 * fails when a client overrides that and gunzips anyway: the bytes still arrive, the stream still
 * plays, and the only thing that moves is the number ABR decides a rendition against. This class is
 * that number, measured on both sides of the same link.
 *
 * ## Why it lives here and not beside the rest of the contract
 *
 * `superplayer-core`'s `TransportContractTest` holds rule 6, rule 10 and the request half of rule 7,
 * because each of those is visible in the exchange itself. This one is not: it is a claim about the
 * estimate, and the estimate is [OracleBandwidthMeter]'s. Asserting it in core would mean asserting
 * over byte counts and calling the consequence obvious, which is exactly the half-done version this
 * test exists to avoid.
 *
 * ## The experiment
 *
 * One link, one payload, one duration, two clients. The link moves [PLAIN_BYTES] of compressible
 * content as [TRANSFER_MS] of traffic. In the correct arm the origin honoured `identity`, so the
 * wire carried the resource itself and the meter counts what the wire carried. In the broken arm the
 * client asked for `gzip` on its own initiative, the origin obliged, the wire carried the *deflated*
 * resource — fewer bytes over the same [TRANSFER_MS] — and the client handed core the inflated one.
 *
 * The meter therefore reads the *same* number in both arms while the links are nothing alike, and
 * that is the whole defect: the broken link is the slower of the two and is described by the faster
 * one's estimate.
 *
 * Which assertion does the work is worth saying, because two of them look stronger than they are.
 * The **measured** one is `honest.estimateBps == honest.trueLinkBps`: nothing here is mocked between
 * the transport and the reading, so the number can only be right if the adapter reported the
 * transfer, the meter counted its bytes, and a sample was really taken — a run in which none was
 * would read `ColdDefaults`' Wi-Fi figure and fail. What is **declared** rather than measured is
 * `bytesOnTheWire`: no deflated byte travels anywhere in this process, and it could not, because the
 * defect is precisely that the deflated bytes are the ones core never sees. So the last two
 * assertions restate a fact the fixture asserts of itself, and they earn their place by naming which
 * of the two identical estimates is a description of its own link.
 *
 * Nothing in this repository can go further than that. A link that really carried fewer bytes in the
 * same time needs a shaped network under a decompressing client, and `docs/throughput-traces.md`'s
 * replay paces what the *chain* transfers, which is the inflated side of exactly this boundary.
 *
 * Each arm gets its own [EstimateMemory] rather than sharing the process's (ADR-0009 rule 8), which
 * is what keeps one arm's samples out of the other's window and needs no forgetting between them.
 */
@RunWith(AndroidJUnit4::class)
class TransparentGzipEstimateTest {

    private val plain = compressiblePayload()
    private val deflated = gzipped(plain)

    @Test
    fun aClientThatGunzipsOnItsOwnInitiativeOverstatesTheLink() {
        val honest = measure(GzipNegotiatingTransport(plain, deflated, negotiatesGzip = false))
        val gunzipping = measure(GzipNegotiatingTransport(plain, deflated, negotiatesGzip = true))

        // The disobedience, first: the chain asked for identity and this client asked for gzip
        // anyway, which is what puts the two arms on different links.
        assertThat(honest.askedFor).isEqualTo("identity")
        assertThat(gunzipping.askedFor).isEqualTo("gzip")
        assertThat(gunzipping.bytesOnTheWire).isLessThan(honest.bytesOnTheWire)

        // The estimate is the same on both, because both handed core the same inflated bytes over
        // the same time. One of the two is a description of the link it was measured on.
        assertThat(honest.meterWasToldBytes).isEqualTo(plain.size.toLong())
        assertThat(gunzipping.meterWasToldBytes).isEqualTo(plain.size.toLong())
        assertThat(gunzipping.estimateBps).isEqualTo(honest.estimateBps)

        // The honest arm's estimate *is* its link, and this is the one line that could only pass
        // over a real sample: a run that took none reads `ColdDefaults`' Wi-Fi figure instead. The
        // gunzipping arm's is not its link, and the gap is the compression ratio — a ladder chosen
        // for a link carrying that many more bits per second than this one has, which arrives at a
        // viewer as a rebuffer with nothing in any log.
        assertThat(honest.estimateBps).isEqualTo(honest.trueLinkBps)
        assertThat(gunzipping.estimateBps).isGreaterThan(gunzipping.trueLinkBps)
        assertThat(gunzipping.estimateBps / gunzipping.trueLinkBps)
            .isAtLeast(MINIMUM_DEMONSTRATED_OVERSTATEMENT)
    }

    /**
     * One resource fetched over [transport] through core's adapter, with the oracle's own meter
     * registered as the engine registers it.
     *
     * The clock moves [TRANSFER_MS] between the transfer starting and ending, which is the "so many
     * bytes over so many milliseconds" every other reading in this module is worked from.
     */
    private fun measure(transport: GzipNegotiatingTransport): Reading {
        val clock = FakeClock(/* initialTimeMs= */ 0, /* isAutoAdvancing= */ false)
        val meter = OracleBandwidthMeter(clock, EstimateMemory(), NetworkTransport.Wifi)
        val source: DataSource = HttpStack.of(transport)
            // The chain asks a stack for its factory rather than holding one, because the platform's
            // stack needs a Context and an API level (HttpStack.httpFactory, #313).
            .httpFactory(ApplicationProvider.getApplicationContext())
            .createDataSource()
        source.addTransferListener(meter)

        source.open(DataSpec.Builder().setUri(Uri.parse(URI)).build())
        val buffer = ByteArray(READ_BYTES)
        while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) {
            // Read out, so that what the meter counts is everything the client handed over.
        }
        clock.advanceTime(TRANSFER_MS)
        source.close()

        return Reading(
            askedFor = transport.askedFor,
            bytesOnTheWire = transport.bytesOnTheWire,
            meterWasToldBytes = meter.countedBytes,
            estimateBps = meter.currentEstimate().meanBps,
            trueLinkBps = transport.bytesOnTheWire * BITS_PER_BYTE * MILLIS_PER_SECOND / TRANSFER_MS,
        )
    }

    /** One arm of the experiment: what the link did, and what the estimate made of it. */
    private class Reading(
        val askedFor: String,
        val bytesOnTheWire: Long,
        val meterWasToldBytes: Long,
        val estimateBps: Long,
        val trueLinkBps: Long,
    )

    /**
     * A consumer's transport over a client that does or does not negotiate compression for itself.
     *
     * With [negotiatesGzip] it does what an HTTP client configured for transparent compression does,
     * in order: it overrides the `Accept-Encoding` it was handed, the origin answers the deflated
     * representation, and the client inflates it and hands the caller the original bytes with no
     * `Content-Length` and no `Content-Encoding` left to say what happened
     * (// spec: RFC 9110 §8.4.1 — a coding removed by the recipient is removed from the field too).
     * That last part is not incidental: it is why core cannot detect this and has to ask instead.
     */
    private class GzipNegotiatingTransport(
        private val plain: ByteArray,
        private val deflated: ByteArray,
        private val negotiatesGzip: Boolean,
    ) : HttpTransport {

        /** The coding that went out on the wire, which is the chain's unless the client overrode it. */
        var askedFor: String = ""
            private set

        /** What the link actually carried, which is the whole point of the measurement. */
        var bytesOnTheWire: Long = 0
            private set

        override fun open(request: HttpRequest): HttpResponse {
            askedFor = if (negotiatesGzip) GZIP else request.headers.getValue(ACCEPT_ENCODING)
            bytesOnTheWire = (if (negotiatesGzip) deflated else plain).size.toLong()
            return HttpResponse(
                status = OK,
                headers = if (negotiatesGzip) {
                    emptyMap()
                } else {
                    mapOf(CONTENT_LENGTH to listOf(plain.size.toString()))
                },
                body = ByteArrayInputStream(plain),
            )
        }

        private companion object {
            const val OK = 200
            const val GZIP = "gzip"
            const val ACCEPT_ENCODING = "Accept-Encoding"
            const val CONTENT_LENGTH = "Content-Length"
        }
    }

    private companion object {

        const val URI = "https://superplayer.test/segment0.aac"

        /**
         * Big enough that one fetch is a sample on its own —
         * [OracleBandwidthMeter.MIN_SAMPLE_BYTES] is 16 KiB — with room to spare, so that the
         * reading under test is the estimate rather than the threshold.
         */
        const val PLAIN_BYTES = 256 * 1_024

        /**
         * How compressible the payload is, as the number of distinct byte values it draws from.
         * Four values is two bits of entropy per byte, so it deflates to roughly a quarter — the
         * order of magnitude a manifest or a poorly packaged segment really does, rather than the
         * thousandfold a repeating pattern would give and which would make the test's margin
         * meaningless.
         */
        const val ALPHABET = 4

        /** Fixed, because a payload that varied run to run would make the ratio vary with it. */
        const val SEED = 311

        /** Long enough to clear [OracleBandwidthMeter.MIN_SAMPLE_ELAPSED_MS], and round. */
        const val TRANSFER_MS = 100L

        /** The bits-per-second arithmetic the meter does, restated so the expectation is worked. */
        const val BITS_PER_BYTE = 8L
        const val MILLIS_PER_SECOND = 1_000L

        const val READ_BYTES = 4 * 1_024

        /**
         * The floor the demonstration is held to: the overstatement has to be at least a doubling,
         * not merely non-zero. A ratio this large is not a claim about real content — it is a claim
         * that the test is measuring the effect and not rounding, and a payload that stopped
         * compressing would fail here rather than passing quietly with a ratio of one.
         */
        const val MINIMUM_DEMONSTRATED_OVERSTATEMENT = 2L

        /** Deterministic bytes over a small alphabet: compressible, and the same every run. */
        fun compressiblePayload(): ByteArray {
            val random = Random(SEED)
            return ByteArray(PLAIN_BYTES) { random.nextInt(ALPHABET).toByte() }
        }

        fun gzipped(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }
    }
}
