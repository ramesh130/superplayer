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

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.IOException

/**
 * One stream's manifests, fetched over [chain] and read for the defects the doctor can name.
 *
 * This class owns the *fetching* and [HlsPathologies] owns the *judgement*, so that a rule can be read
 * against the clause it cites without reading a transport, and so that what travels the chain is decided in
 * one place (ADR-0015 rule 7).
 *
 * Internal because every type it deals in is Media3's and every one of them `@UnstableApi`: ADR-0015
 * rule 2 keeps all of it behind [MediaSourceDoctor]'s public surface, which names no Media3 type at all.
 *
 * **The parsers are the ones the engine would run** (rule 6). Media3's `HlsPlaylistParser` is what the
 * player is about to run over these very bytes, so the doctor and the player can never disagree about what
 * the manifest *says* before they disagree about what it means; a doctor with a parser of its own would be
 * diagnosing a document the player never saw.
 */
internal class ManifestExamination(
    private val chain: DataSource.Factory,
    private val segmentChain: DataSource.Factory,
) {

    /** The headers each manifest arrived with, which is all [DeliveryPathologies] has to read. */
    private val responses = mutableMapOf<Uri, Map<String, List<String>>>()

    /** Every media playlist this examination managed to read, keyed by the URI it read it from. */
    private val mediaPlaylists = mutableMapOf<Uri, HlsMediaPlaylist>()

    /**
     * What is wrong with the manifest at [source], or an empty list where nothing this doctor knows is.
     *
     * At most one finding per [Pathology]: a defect is a fact about the stream, and a ladder examined
     * through four renditions is one ladder. Where several renditions carry the same defect the one
     * reported is the worst of them, so a report is read as "this is how bad this is" rather than as a
     * count of where it was seen.
     */
    fun examine(source: Uri): List<Finding> = when (val fetched = fetch(source)) {
        is Fetched.Refused -> listOf(fetched.finding)
        is Fetched.Read -> worstPerPathology(rulesOver(fetched.manifest) + delivery(source, fetched.manifest))
    }

    /**
     * What the *delivery* of this stream got wrong, read after the documents rather than beside them.
     *
     * After, because the rules need what the fetching produced: the headers every response arrived with,
     * and — for a stream whose source is a multivariant playlist — the media playlists it named, which are
     * where the segments a token has to cover are declared. A media playlist reached directly is its own,
     * and is the shape a single-rendition live stream is usually published in.
     *
     * **HLS only**, and the branch is the whole of that limit. Nothing the rules read is protocol-specific —
     * a token is in a URI and an allowed origin in a header — but a [Pathology] is named for the corpus
     * entry it is scored against (ADR-0015 rule 12) and all three delivery entries are HLS, so reporting one
     * of them over an MPD would be reporting an id nothing in the register can match.
     * [DeliveryPathologies] says the same thing from the other side, and a DASH delivery entry is what would
     * lift the rules rather than copy them.
     */
    private fun delivery(source: Uri, manifest: ParsedManifest): List<Finding> {
        val playlists = when (manifest) {
            is ParsedManifest.Media -> mapOf(source to manifest.playlist)
            is ParsedManifest.Multivariant -> mediaPlaylists
            is ParsedManifest.Dash, ParsedManifest.Opaque -> return emptyList()
        }
        return DeliveryPathologies.inResponses(responses.values) +
            playlists.flatMap { (uri, playlist) ->
                DeliveryPathologies.inDelivery(source, uri, playlist, responses[uri].orEmpty(), ::probeSegment)
            }
    }

    /** The rules that apply to whichever kind of document [manifest] turned out to be. */
    private fun rulesOver(manifest: ParsedManifest): List<Finding> = when (manifest) {
        is ParsedManifest.Multivariant -> examineHls(manifest.playlist)

        // A request whose source is a media playlist rather than a multivariant one: legal HLS, and the
        // shape a single-rendition live stream is usually published in.
        is ParsedManifest.Media -> HlsPathologies.inMediaPlaylist(manifest.playlist)

        is ParsedManifest.Dash -> DashPathologies.inManifest(manifest.uri, manifest.manifest)

        ParsedManifest.Opaque -> emptyList()
    }

    /**
     * A multivariant playlist and **every media playlist it names**, variants and audio renditions alike.
     *
     * More fetches than a player makes, deliberately. A player reads the playlist of the rendition it is
     * playing; a doctor is asked once, off the playback path, about the whole stream, and a defect in a rung
     * nobody started on is a defect every viewer whose link climbs to it will meet. It stays within
     * ADR-0015 rule 7 because all of it is manifests: no segment is fetched, so a preflight over a ten-rung
     * ladder costs eleven playlists and no media — bar the one headers-only probe [probeSegment] explains,
     * which is a request and not a download.
     *
     * A media playlist that cannot be fetched or read is its own finding and does not stop the others: the
     * useful report on a stream with one broken rendition names the broken rendition *and* what else is
     * wrong.
     */
    private fun examineHls(multivariant: HlsMultivariantPlaylist): List<Finding> {
        val findings = HlsPathologies.inMultivariant(multivariant).toMutableList()
        val named = multivariant.variants.map { it.url } + multivariant.audios.mapNotNull { it.url }
        named.distinct().forEach { url ->
            when (val fetched = fetch(url)) {
                is Fetched.Refused -> findings += fetched.finding

                is Fetched.Read -> when (val manifest = fetched.manifest) {
                    is ParsedManifest.Media -> {
                        mediaPlaylists[url] = manifest.playlist
                        findings += HlsPathologies.inMediaPlaylist(manifest.playlist)
                    }

                    // A multivariant playlist, or something else entirely, where a media playlist belongs:
                    // it parsed, so it is not unreachable, and it is not the document the tag said it was.
                    else -> findings += unreadable()
                }
            }
        }
        return findings + HlsPathologies.inAudioGroups(multivariant, mediaPlaylists)
    }

    /** One finding per pathology, at the highest severity any rendition showed it at. */
    private fun worstPerPathology(findings: List<Finding>): List<Finding> = findings
        .groupBy { it.pathology }
        .map { (_, sameDefect) -> sameDefect.maxBy { it.severity } }

    /** [source]'s bytes, parsed — or the finding that says why they are not. */
    private fun fetch(source: Uri): Fetched = try {
        // Media3's own fetch-and-parse, used rather than reimplemented for rule 6's reason: it opens
        // the chain, reads to the end and closes, which is what a player's loader does with a manifest.
        // The recorder above it keeps what the response arrived with, because a delivery defect is a fact
        // about the transfer and the headers are gone by the time the load has closed the source.
        val recorder = HeaderRecordingDataSource(chain.createDataSource(), responses::put)
        Fetched.Read(ParsingLoadable.load(recorder, parserFor(source), source, C.DATA_TYPE_MANIFEST))
    } catch (rejected: ParserException) {
        // Before the refusal below, because Media3's parse failure *is* an `IOException`: what arrived
        // and what did not are two different reports, and telling them apart is the first thing a
        // support engineer does.
        Fetched.Refused(unreadable())
    } catch (refused: IOException) {
        Fetched.Refused(unreachable(refused))
    }

    /**
     * Bytes that arrived and are not the document they were asked for.
     *
     * Blocking, and no magnitude: there is nothing to play and nothing to grade. What is *not* carried is
     * the parser's own message, which may quote the line it choked on and therefore a URL and its token
     * (`SessionTraceRecorder`'s rule 1).
     */
    private fun unreadable(): Finding =
        Finding(Pathology.MANIFEST_UNREADABLE, FindingSeverity.BLOCKING, magnitude = null)

    /**
     * The headers one segment is served with, read without reading a byte of it — or null where the
     * transfer was refused, which is a fact this rule reports nothing about.
     *
     * **This is the one request a doctor opens that is not a manifest**, and ADR-0015 rule 7's #289
     * addendum is what admits it: a `Cache-Control` disagreement is a disagreement between two responses,
     * so naming which of them is wrong needs the second one. What is asked for is [PROBE_BYTES] — enough
     * that the request is answered, and a range a CDN serves from its edge without touching an origin — and
     * even that is never read: the source is opened for its headers and closed. So the cost is one round
     * trip rather than a segment, which is what keeps a preflight from costing what a start costs. One
     * media playlist can spend one probe, so a ten-rung ladder all of whose playlists are misconfigured
     * costs ten headers and still no media.
     *
     * It travels [segmentChain], the same chain stamped as media, because the stamp is what tells a refused
     * segment from a refused manifest everywhere else in this library.
     */
    private fun probeSegment(segment: Uri): Map<String, List<String>>? {
        val source = segmentChain.createDataSource()
        return try {
            source.open(DataSpec.Builder().setUri(segment).setLength(PROBE_BYTES).build())
            source.responseHeaders
        } catch (refused: IOException) {
            null
        } finally {
            // A close after a refused open is Media3's own contract, and one that throws has nothing left
            // to say: the answer is already the headers or their absence.
            runCatching { source.close() }
        }
    }

    /** What one fetch came back with: a document to read rules over, or the finding that replaces it. */
    private sealed interface Fetched {

        class Read(val manifest: ParsedManifest) : Fetched

        class Refused(val finding: Finding) : Fetched
    }

    /**
     * A document this doctor has parsed, as the kind of document it turned out to be.
     *
     * Named rather than left as the parser's own type, so that what a manifest *is* is decided once, at the
     * parse, and every reader afterwards branches on a closed set. A protocol whose reading a later issue
     * adds (#288's DASH) becomes one more case here and a case in [rulesOver], rather than another cast at
     * every place a parsed document is held.
     */
    private sealed interface ParsedManifest {

        class Multivariant(val playlist: HlsMultivariantPlaylist) : ParsedManifest

        class Media(val playlist: HlsMediaPlaylist) : ParsedManifest

        /**
         * An MPD, with the URI it was read from: core's live-window judgement is asked over the parsed
         * manifest and names the document it judged, so the two travel together rather than the rules
         * reaching back for a source the examination has moved past.
         */
        class Dash(val uri: Uri, val manifest: DashManifest) : ParsedManifest

        /** Fetched whole and read by no rule yet: what the protocols this doctor has no rules for parse to. */
        data object Opaque : ParsedManifest
    }

    /**
     * The parser the engine would run over [source]'s bytes, or one that reads and discards them.
     *
     * The reading is the player's own: `DefaultMediaSourceFactory` infers a source's type this way, so a
     * doctor examines whatever that player would have built.
     *
     * **The manifest is fetched whatever the protocol**, even where no rule reads it yet, because whether
     * it can be fetched at all is a finding of its own and rule 7's bullet is unconditional: a manifest no
     * player of this app can reach must not come back looking like a healthy one. What the discarding parser
     * skips is the *reading*, which the two protocols with rules no longer need; it still pulls the whole
     * body, so a truncated or refused transfer is met here exactly as a player would meet it.
     */
    private fun parserFor(source: Uri): ParsingLoadable.Parser<ParsedManifest> =
        when (Util.inferContentType(source)) {
            C.CONTENT_TYPE_HLS -> ParsingLoadable.Parser { uri, stream ->
                when (val playlist = HlsPlaylistParser().parse(uri, stream)) {
                    is HlsMultivariantPlaylist -> ParsedManifest.Multivariant(playlist)
                    is HlsMediaPlaylist -> ParsedManifest.Media(playlist)
                    else -> ParsedManifest.Opaque
                }
            }

            C.CONTENT_TYPE_DASH -> ParsingLoadable.Parser { uri, stream ->
                ParsedManifest.Dash(uri, DashManifestParser().parse(uri, stream))
            }

            else -> ParsingLoadable.Parser { _, stream ->
                stream.readBytes()
                ParsedManifest.Opaque
            }
        }

    /**
     * A manifest the chain would not deliver, as the finding rule 7 asks for.
     *
     * Blocking, and the one severity that needs no argument: there is nothing to play. The magnitude is the
     * status where a server answered with one, because "refused with 403" and "no host answered" send a
     * support engineer to two different teams, and the status is the only part of a refusal that is safe to
     * print — a message may carry a URL and the token in its query (`SessionTraceRecorder`'s rule 1).
     */
    private fun unreachable(refused: IOException): Finding = Finding(
        Pathology.MANIFEST_UNREACHABLE,
        FindingSeverity.BLOCKING,
        magnitude = (refused as? InvalidResponseCodeException)?.let { "HTTP ${it.responseCode}" },
    )

    /**
     * [upstream], with the headers of every response it opens handed to [onResponse] as it opens it.
     *
     * A wrapper rather than a read after the load, because a source reports the headers of the transfer it
     * currently holds: `ParsingLoadable.load` closes the source it was given, and a closed source has none.
     * Transparent in every other respect, the transfer listener included, which is the upstream's.
     */
    private class HeaderRecordingDataSource(
        private val upstream: DataSource,
        private val onResponse: (Uri, Map<String, List<String>>) -> Unit,
    ) : DataSource {

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val length = upstream.open(dataSpec)
            onResponse(dataSpec.uri, upstream.responseHeaders)
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() = upstream.close()
    }

    private companion object {

        /**
         * How much of a segment [probeSegment] asks for: one byte.
         *
         * A request rather than a download. An open with no length reads as "send me all of it" to every
         * cache and origin between here and the media, and a doctor that opened one and closed it would be
         * paying a segment's bandwidth for a header. One byte is the smallest range that is still a request
         * an origin answers, and its response carries the same `Cache-Control` the whole segment's does,
         * because a caching directive is a property of the resource rather than of the range.
         */
        const val PROBE_BYTES = 1L
    }
}
