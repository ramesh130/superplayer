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
import androidx.media3.common.C
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.DashSegmentIndex
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Refuses a live DASH manifest whose window no playhead can sit inside, naming it with
 * [LiveWindowTooShortException] — issue #67.
 *
 * [LiveWindowTooShortException]'s KDoc carries the arithmetic: a segment is fetchable only once it
 * is complete, less its `@availabilityTimeOffset`, so a `@timeShiftBufferDepth` no deeper than that
 * lag puts every playable position before the window's start. Media3 plays such a stream anyway,
 * outside the window, and reports negative positions; this ends it with a name instead.
 *
 * Not a policy, and so not behind `PlaybackPolicy` (ADR-0005): nothing here is a number to tune. The
 * one comparison is the protocol's own availability rule applied to what the manifest says, and the
 * outcome is a failure rather than a configuration — which is `superplayer-resilience`'s to classify
 * and core's to raise. It is a link in the chain because the chain is the one place a manifest can be
 * refused before the engine parses it without forking the engine's DASH source (ADR-0001), and it is
 * core's because the consumer most likely to meet a misconfigured packager has only `superplayer-core`.
 *
 * ## What it reads
 *
 * Only whole responses that are DASH manifests by the protocol's own naming and media type
 * (// spec: ISO/IEC 23009-1 Annex C — `application/dash+xml`, conventionally `.mpd`), and of those
 * only the ones that mention `timeShiftBufferDepth` at all — a manifest without it promises an
 * unlimited window and cannot be too short, which keeps every static MPD and most live ones from
 * being parsed twice. The rest is parsed with Media3's own `DashManifestParser`, the parser the
 * engine is about to run over the same bytes, so the two can never disagree about what the manifest
 * says.
 *
 * Stateless, unlike [LivePlaylistRevalidation]: each manifest carries its whole window, so one
 * version is judged on its own and a later version that deepens the window plays.
 */
internal object LiveWindowDepthCheck {

    /** [upstream], with every live DASH manifest it serves judged on the way past. */
    fun over(upstream: DataSource.Factory): DataSource.Factory =
        DataSource.Factory { CheckingDataSource(upstream.createDataSource()) }

    /** The failure one complete manifest response calls for, or null when it is playable or not judged. */
    private fun judge(uri: Uri, body: ByteArray): LiveWindowTooShortException? {
        if (!String(body, Charsets.UTF_8).contains(TIME_SHIFT_BUFFER_DEPTH)) return null
        // A document the engine's parser rejects is the engine's to report, with its own error.
        val manifest = runCatching { DashManifestParser().parse(uri, ByteArrayInputStream(body)) }.getOrNull()
            ?: return null
        return tooShort(uri, manifest)
    }

    /**
     * Whether [manifest]'s window is one no playhead fits inside, as the failure that says so by how much —
     * or null where it holds one, or where nothing here can tell.
     *
     * **This is the judgement itself, split from the fetch above so that it can be asked a second time from
     * a second place.** ADR-0015 rule 3's third seam is this function: `superplayer-diagnostics` reports the
     * same defect before a player exists, and rule 6 makes a copy of the comparison a bug — the rule is not
     * a constant but `depthUs > lag.us` over the longest availability lag any addressed segment declares, so
     * a second definition is one nothing would keep in step. A caller that has already parsed with Media3's
     * own parser passes the manifest; this file's own load parses and calls the same way.
     *
     * The `LiveWindowTooShortException` is the carrier rather than a value of its own, because it already
     * holds exactly the three numbers the comparison was made from, and it is what a *player* of this
     * manifest ends on. A caller that only wants to describe the defect reads those numbers and throws
     * nothing.
     */
    fun tooShort(uri: Uri, manifest: DashManifest): LiveWindowTooShortException? {
        if (!manifest.dynamic || manifest.timeShiftBufferDepthMs == C.TIME_UNSET) return null

        val lag = longestAvailabilityLag(manifest) ?: return null
        val depthUs = Util.msToUs(manifest.timeShiftBufferDepthMs)
        if (depthUs > lag.us) return null
        return LiveWindowTooShortException(
            manifestUri = uri.toString(),
            timeShiftBufferDepthMs = manifest.timeShiftBufferDepthMs,
            segmentDurationMs = Util.usToMs(lag.segmentDurationUs),
            availabilityTimeOffsetMs = Util.usToMs(lag.availabilityTimeOffsetUs),
        )
    }

    /** How long after a segment's start it becomes fetchable: its duration less its availability offset. */
    private class AvailabilityLag(val segmentDurationUs: Long, val availabilityTimeOffsetUs: Long) {
        val us: Long get() = segmentDurationUs - availabilityTimeOffsetUs
    }

    /**
     * The longest [AvailabilityLag] of any segment the manifest addresses, or null when none is known.
     *
     * The longest because a player plays every track it selects at once: one rendition whose segments
     * cannot be reached inside the window holds the whole presentation outside it.
     */
    private fun longestAvailabilityLag(manifest: DashManifest): AvailabilityLag? {
        // A dynamic MPD must carry @publishTime (// spec: ISO/IEC 23009-1 §5.3.1.2); one that does not
        // gives no "now" to read availability at, and an index read at an unset time is noise.
        if (manifest.publishTimeMs == C.TIME_UNSET) return null
        val nowUnixTimeUs = Util.msToUs(manifest.publishTimeMs)
        return (0 until manifest.periodCount).asSequence()
            .flatMap { periodIndex ->
                val period = manifest.getPeriod(periodIndex)
                val periodDurationUs = manifest.getPeriodDurationUs(periodIndex)
                period.adaptationSets.asSequence()
                    .flatMap { it.representations.asSequence() }
                    .mapNotNull { it.index }
                    .mapNotNull { lagOf(it, periodDurationUs, nowUnixTimeUs) }
            }
            .maxByOrNull { it.us }
    }

    /**
     * One segment index's [AvailabilityLag], or null when its availability offset cannot be read.
     *
     * The offset is not a public property of Media3's parsed index; what is public is the time the
     * next segment becomes available, which Media3 computes as that segment's end, in the period's own
     * time, less the offset (checked against `media3-exoplayer-dash` 1.11.0's `MultiSegmentBase`). So
     * the offset is recovered from it at whichever segment is next at the manifest's own publish time —
     * the offset is the same for every segment of one index, so which segment does not matter. An
     * index that answers "unknown" there — a `SegmentTimeline`, for which Media3 does not compute it —
     * is not judged: an offset nobody can read may be a low-latency one, and refusing a stream on a
     * guess is worse than letting Media3 play it as it did before.
     *
     * The duration is that same next segment's: every index this can judge is a `SegmentTemplate` with
     * a constant `@duration` (// spec: ISO/IEC 23009-1 §5.3.9.4), whose segments are all alike but for
     * a last one a period's end may cut short — which the next segment at publish time is not.
     */
    private fun lagOf(
        index: DashSegmentIndex,
        periodDurationUs: Long,
        nowUnixTimeUs: Long,
    ): AvailabilityLag? {
        val nextAvailableUs = index.getNextSegmentAvailableTimeUs(periodDurationUs, nowUnixTimeUs)
        if (nextAvailableUs == C.TIME_UNSET) return null
        val next = index.getFirstAvailableSegmentNum(periodDurationUs, nowUnixTimeUs) +
            index.getAvailableSegmentCount(periodDurationUs, nowUnixTimeUs)
        val nextDurationUs = index.getDurationUs(next, periodDurationUs)
        if (nextDurationUs == C.TIME_UNSET || nextDurationUs <= 0) return null
        // An offset at or past the whole segment is "available before it starts" — `INF`, in a
        // manifest's own vocabulary — and no window is too short for that.
        val availabilityTimeOffsetUs = index.getTimeUs(next) + nextDurationUs - nextAvailableUs
        if (availabilityTimeOffsetUs < 0 || availabilityTimeOffsetUs >= nextDurationUs) return null
        return AvailabilityLag(nextDurationUs, availabilityTimeOffsetUs)
    }

    /**
     * The wrapper each load goes through: reads a manifest response whole as it is opened, hands it to
     * [judge], and then serves the same bytes; every other response, header and callback goes straight
     * through. The transfer listener is registered on the upstream, which is `TransferChain`'s rule for
     * every layer.
     *
     * Whole at open rather than on the way past, as [LivePlaylistRevalidation] reads a playlist, because
     * Media3's manifest parser stops reading at the closing `</MPD>` and never asks for the end of input
     * — so a layer waiting for it never judges anything — and the engine closes a manifest load quietly,
     * so a failure raised on close is swallowed. Open is the last moment a failure still reaches the
     * engine as the load's own error. A consequence for any listener: a manifest's bytes are transferred
     * during `open` rather than `read` — harmless today, because Media3 registers no transfer listener
     * on a manifest load.
     */
    private class CheckingDataSource(private val upstream: DataSource) : DataSource {

        /** The manifest bytes already read from the upstream, served before anything else. */
        private var prefix = ByteArray(0)
        private var served = 0

        /** Whether [prefix] is the whole response, so that serving it is the end of input. */
        private var prefixIsWhole = false

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val length = upstream.open(dataSpec)
            prefix = ByteArray(0)
            served = 0
            prefixIsWhole = false
            // A whole response only: a range of a manifest is not a version of it.
            val whole = dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()
            if (whole && isManifest(dataSpec.uri, upstream.responseHeaders)) {
                readPrefix()
                if (prefixIsWhole) judge(dataSpec.uri, prefix)?.let { throw it }
            }
            return length
        }

        /** Reads the upstream to its end, or to [MAX_MANIFEST_BYTES] — past which it is not judged. */
        private fun readPrefix() {
            val body = ByteArrayOutputStream()
            val chunk = ByteArray(READ_CHUNK_BYTES)
            while (body.size() <= MAX_MANIFEST_BYTES) {
                val read = upstream.read(chunk, 0, chunk.size)
                if (read == C.RESULT_END_OF_INPUT) {
                    prefixIsWhole = true
                    break
                }
                body.write(chunk, 0, read)
            }
            prefix = body.toByteArray()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (served < prefix.size) {
                val count = minOf(length, prefix.size - served)
                System.arraycopy(prefix, served, buffer, offset, count)
                served += count
                return count
            }
            return if (prefixIsWhole) C.RESULT_END_OF_INPUT else upstream.read(buffer, offset, length)
        }

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() {
            prefix = ByteArray(0)
            upstream.close()
        }
    }

    /** // spec: ISO/IEC 23009-1 Annex C — the MPD media type; `.mpd` is the naming every packager uses. */
    private fun isManifest(uri: Uri, headers: Map<String, List<String>>): Boolean {
        if (uri.path.orEmpty().lowercase().endsWith(".mpd")) return true
        val contentType = headers.entries.firstOrNull { it.key.equals(CONTENT_TYPE, ignoreCase = true) }
            ?.value?.firstOrNull()
        return contentType?.substringBefore(';')?.trim()?.equals(DASH_MEDIA_TYPE, ignoreCase = true) == true
    }

    private const val TIME_SHIFT_BUFFER_DEPTH = "timeShiftBufferDepth"
    private const val CONTENT_TYPE = "Content-Type"
    private const val DASH_MEDIA_TYPE = "application/dash+xml"

    /**
     * The largest manifest read. A multi-period live MPD with a `SegmentTimeline` per representation
     * reaches hundreds of kilobytes in the field; eight megabytes is well past that, and small enough
     * that a mislabelled media file is abandoned before it costs a player real memory.
     */
    private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024

    /** One upstream read while a manifest is taken whole: the size Media3's own input streams read in. */
    private const val READ_CHUNK_BYTES = 8 * 1024
}
