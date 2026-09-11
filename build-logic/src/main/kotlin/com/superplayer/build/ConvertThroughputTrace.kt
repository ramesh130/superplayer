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

package com.superplayer.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option
import java.io.File

/**
 * Converts a public throughput dataset's file into SuperPlayer's trace format.
 *
 * ```
 * ./gradlew convertThroughputTrace --from=bandwidth-log --transport=CELLULAR \
 *     --input=/path/to/report.log --output=/path/to/report.trace
 * ```
 *
 * `docs/throughput-traces.md` is the format's specification and names the datasets each source
 * format comes from, with what their terms ask. The conversion is a plain function per format, so
 * it is unit-tested without a Gradle build, and its output for a checked-in fixture is the file
 * `superplayer-testkit`'s own tests read — which is what holds this writer and that reader to one
 * format across two builds.
 *
 * Untracked: a person runs it by hand, on a file that is usually outside the repository, and the
 * output is theirs to check in or not. No dataset is vendored here.
 */
@UntrackedTask(because = "A one-off conversion of a file outside the build, run by hand")
abstract class ConvertThroughputTrace : DefaultTask() {

    @get:Input
    @get:Option(option = "from", description = "The source format: bandwidth-log or mahimahi.")
    abstract val from: Property<String>

    @get:Input
    @get:Option(option = "input", description = "The dataset file to convert.")
    abstract val input: Property<String>

    @get:Input
    @get:Option(option = "output", description = "Where to write the trace.")
    abstract val output: Property<String>

    @get:Input
    @get:Option(option = "transport", description = "What the dataset was measured over: WIFI, CELLULAR, ETHERNET or UNKNOWN.")
    abstract val transport: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "bin-ms", description = "mahimahi only: the length of one stretch, in ms (default 1000).")
    abstract val binMs: Property<String>

    /** What a relative `--input` or `--output` is relative to: the root project's directory. */
    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @TaskAction
    fun convert() {
        val source = resolve(input.get())
        if (!source.isFile) throw GradleException("No such file: $source")
        val text = source.readText()
        val trace = try {
            when (from.get()) {
                "bandwidth-log" -> convertBandwidthLog(text, transport.get(), source.name)

                "mahimahi" -> convertMahimahi(
                    text,
                    transport.get(),
                    source.name,
                    binMs.orNull?.let { it.toLongOrNull() ?: throw IllegalArgumentException("--bin-ms is not a number: $it") }
                        ?: DEFAULT_MAHIMAHI_BIN_MS,
                )

                else -> throw IllegalArgumentException("--from is bandwidth-log or mahimahi, not ${from.get()}")
            }
        } catch (e: IllegalArgumentException) {
            throw GradleException("Could not convert $source: ${e.message}", e)
        }
        val target = resolve(output.get())
        target.parentFile?.mkdirs()
        target.writeText(trace)
        logger.lifecycle("Wrote $target")
    }

    private fun resolve(path: String): File =
        File(path).let { if (it.isAbsolute) it else projectRoot.get().asFile.resolve(path) }
}

/** One mahimahi stretch unless told otherwise: the one-second granularity the bandwidth logs have. */
internal const val DEFAULT_MAHIMAHI_BIN_MS = 1_000L

/**
 * A bandwidth log — the six-column format two public datasets share — as a trace.
 *
 * The HSDPA dataset (// ref: Riiser et al., "Commute Path Bandwidth Traces from 3G Networks:
 * Analysis and Applications", MMSys 2013) and the Ghent 4G/LTE logs (// ref: van der Hooft et al.,
 * "HTTP/2-Based Adaptive Streaming of HEVC Video over 4G/LTE Networks", IEEE Communications Letters
 * 20(11), 2016) both write one line per sample: a wall-clock timestamp, a monotonic millisecond
 * timestamp, latitude, longitude, *bytes received since the previous sample*, and *milliseconds
 * since the previous sample*. Only the last two are read. The first is in seconds in one dataset and
 * milliseconds in the other, and the coordinates are where a person was, which a trace has no use
 * for and should not carry.
 *
 * Each sample becomes a stretch of its own elapsed time at the rate its bytes imply, rounded down
 * to a whole bit per second — so a stretch may deliver up to a bit per second less than its sample
 * did. A sample with no elapsed time has no rate: its bytes are carried into the next sample rather
 * than dropped, which is the difference between a rounding error and a lost sample.
 */
internal fun convertBandwidthLog(text: String, transport: String, sourceName: String): String {
    requireTransport(transport)
    val rows = mutableListOf<String>()
    var carriedBytes = 0L
    text.lines().forEachIndexed { index, raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEachIndexed
        val fields = line.split(WHITESPACE)
        fun fail(why: String): Nothing = throw IllegalArgumentException("line ${index + 1}: $why: \"$raw\"")
        if (fields.size != BANDWIDTH_LOG_COLUMNS) fail("a bandwidth log line has $BANDWIDTH_LOG_COLUMNS columns")
        val bytes = fields[4].toLongOrNull()?.takeIf { it >= 0 } ?: fail("column 5, bytes received, is not a count")
        val elapsedMs = fields[5].toLongOrNull()?.takeIf { it >= 0 } ?: fail("column 6, elapsed ms, is not a count")
        if (elapsedMs == 0L) {
            carriedBytes += bytes
            return@forEachIndexed
        }
        rows += "$elapsedMs ${(bytes + carriedBytes) * BITS_PER_BYTE * MILLIS_PER_SECOND / elapsedMs} $transport"
        carriedBytes = 0
    }
    require(rows.isNotEmpty()) { "the log has no samples with elapsed time" }
    return trace(rows, "bandwidth-log", sourceName)
}

/**
 * A Mahimahi packet-delivery trace as a trace, one stretch per [binMs].
 *
 * // ref: `mm-link(1)`, Mahimahi (Netravali et al., USENIX ATC 2015): each line is a millisecond
 * timestamp at which one MTU-sized packet — 1500 bytes — can be delivered, several lines with the
 * same timestamp are several packets, and the link wraps to the start of the trace at its end. The
 * trace's period is therefore its last timestamp. The Pensieve traces, and the cellular traces
 * Mahimahi itself ships, are published in this format.
 *
 * A packet at time `t` counts toward the bin `(k × binMs, (k + 1) × binMs]` holding it — delivered
 * *by* the end of that bin — and the last bin ends at the period, so it may be shorter than the
 * others. Its rate is its packets' bits over its own length, rounded down to a whole bit per second.
 */
internal fun convertMahimahi(text: String, transport: String, sourceName: String, binMs: Long): String {
    requireTransport(transport)
    require(binMs > 0) { "a bin is at least 1 ms, not $binMs" }
    val timestamps = text.lines().mapIndexedNotNull { index, raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@mapIndexedNotNull null
        line.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("line ${index + 1}: a mahimahi line is one millisecond timestamp: \"$raw\"")
    }
    require(timestamps.isNotEmpty()) { "the trace has no delivery opportunities" }
    timestamps.zipWithNext().forEachIndexed { index, (earlier, later) ->
        require(later >= earlier) { "line ${index + 2}: timestamps go backwards, $later after $earlier" }
    }
    val periodMs = timestamps.last()
    require(periodMs > 0) { "the trace's period, its last timestamp, is 0 ms" }
    val bins = LongArray(((periodMs + binMs - 1) / binMs).toInt())
    timestamps.forEach { t -> bins[if (t == 0L) 0 else ((t - 1) / binMs).toInt()]++ }
    val rows = bins.mapIndexed { k, packets ->
        val lengthMs = minOf(binMs, periodMs - k * binMs)
        "$lengthMs ${packets * MAHIMAHI_PACKET_BYTES * BITS_PER_BYTE * MILLIS_PER_SECOND / lengthMs} $transport"
    }
    return trace(rows, "mahimahi", sourceName)
}

private fun trace(rows: List<String>, format: String, sourceName: String): String = buildString {
    appendLine(TRACE_HEADER)
    appendLine("# Converted from $sourceName ($format) by ./gradlew convertThroughputTrace.")
    appendLine("# Where the source dataset comes from, and what its terms ask: docs/throughput-traces.md.")
    // Every dataset this reads is recorded and loops, as mahimahi's own replay does.
    appendLine("at-end loop")
    rows.forEach { appendLine(it) }
}

private fun requireTransport(transport: String) {
    require(transport in TRANSPORTS) { "--transport is one of ${TRANSPORTS.joinToString()}, not $transport" }
}

/** The format `superplayer-testkit`'s `ThroughputTrace.parse` reads; the two change together. */
internal const val TRACE_HEADER = "superplayer-throughput-trace 1"

/**
 * `superplayer-testkit`'s `NetworkTransport` names, kept in step by hand: this build cannot see that
 * one. The drift fails safe — a transport added there and not here is refused by the converter, and
 * one here that the reader does not know is refused by the reader, each naming it.
 */
private val TRANSPORTS = listOf("WIFI", "CELLULAR", "ETHERNET", "UNKNOWN")

private const val BANDWIDTH_LOG_COLUMNS = 6
private const val MAHIMAHI_PACKET_BYTES = 1_500L
private const val BITS_PER_BYTE = 8L
private const val MILLIS_PER_SECOND = 1_000L
private val WHITESPACE = Regex("""\s+""")
