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
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
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
internal class ManifestExamination(private val chain: DataSource.Factory) {

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
        is Fetched.Read -> worstPerPathology(examine(fetched.playlist))
    }

    /** The rules that apply to whichever kind of document [source] turned out to be. */
    private fun examine(playlist: Any): List<Finding> = when (playlist) {
        is HlsMultivariantPlaylist -> examineHls(playlist)

        // A request whose source is a media playlist rather than a multivariant one: legal HLS, and the
        // shape a single-rendition live stream is usually published in.
        is HlsMediaPlaylist -> HlsPathologies.inMediaPlaylist(playlist)

        else -> emptyList()
    }

    /**
     * A multivariant playlist and **every media playlist it names**, variants and audio renditions alike.
     *
     * More fetches than a player makes, deliberately. A player reads the playlist of the rendition it is
     * playing; a doctor is asked once, off the playback path, about the whole stream, and a defect in a rung
     * nobody started on is a defect every viewer whose link climbs to it will meet. It stays within
     * ADR-0015 rule 7 because all of it is manifests: no segment is fetched, so a preflight over a ten-rung
     * ladder costs eleven playlists and no media.
     *
     * A media playlist that cannot be fetched or read is its own finding and does not stop the others: the
     * useful report on a stream with one broken rendition names the broken rendition *and* what else is
     * wrong.
     */
    private fun examineHls(multivariant: HlsMultivariantPlaylist): List<Finding> {
        val findings = HlsPathologies.inMultivariant(multivariant).toMutableList()
        val renditionPlaylists = mutableMapOf<Uri, HlsMediaPlaylist>()
        val named = multivariant.variants.map { it.url } + multivariant.audios.mapNotNull { it.url }
        named.distinct().forEach { url ->
            when (val fetched = fetch(url)) {
                is Fetched.Refused -> findings += fetched.finding

                is Fetched.Read -> {
                    val media = fetched.playlist as? HlsMediaPlaylist
                    if (media == null) {
                        // A multivariant playlist where a media playlist belongs: it parsed, so it is not
                        // unreachable, and it is not the document the tag said it would be.
                        findings += Finding(Pathology.MANIFEST_UNREADABLE, FindingSeverity.BLOCKING, magnitude = null)
                    } else {
                        renditionPlaylists[url] = media
                        findings += HlsPathologies.inMediaPlaylist(media)
                    }
                }
            }
        }
        multivariant.audios.forEach { rendition ->
            findings += listOfNotNull(HlsPathologies.inAudioGroup(rendition, renditionPlaylists[rendition.url]))
        }
        return findings
    }

    /** One finding per pathology, at the highest severity any rendition showed it at. */
    private fun worstPerPathology(findings: List<Finding>): List<Finding> = findings
        .groupBy { it.pathology }
        .map { (_, sameDefect) -> sameDefect.maxBy { it.severity.ordinal } }

    /** [source]'s bytes, parsed — or the finding that says why they are not. */
    private fun fetch(source: Uri): Fetched = try {
        // Media3's own fetch-and-parse, used rather than reimplemented for rule 6's reason: it opens
        // the chain, reads to the end and closes, which is what a player's loader does with a manifest.
        Fetched.Read(ParsingLoadable.load(chain.createDataSource(), parserFor(source), source, C.DATA_TYPE_MANIFEST))
    } catch (rejected: ParserException) {
        // Before the refusal below, because Media3's parse failure *is* an `IOException`: what arrived
        // and what did not are two different reports, and telling them apart is the first thing a
        // support engineer does.
        Fetched.Refused(Finding(Pathology.MANIFEST_UNREADABLE, FindingSeverity.BLOCKING, magnitude = null))
    } catch (refused: IOException) {
        Fetched.Refused(unreachable(refused))
    }

    /** What one fetch came back with: a document to read rules over, or the finding that replaces it. */
    private sealed interface Fetched {

        class Read(val playlist: Any) : Fetched

        class Refused(val finding: Finding) : Fetched
    }

    /**
     * The parser the engine would run over [source]'s bytes, or one that reads and discards them.
     *
     * The reading is the player's own: `DefaultMediaSourceFactory` infers a source's type this way, so a
     * doctor examines whatever that player would have built.
     *
     * **The manifest is fetched whatever the protocol**, even where no rule reads it yet, because whether
     * it can be fetched at all is a finding of its own and rule 7's bullet is unconditional: a DASH
     * manifest no player of this app can reach must not come back looking like a healthy one. What the
     * discarding parser skips is the *reading*, which is #288's to add for DASH; it still pulls the whole
     * body, so a truncated or refused transfer is met here exactly as a player would meet it.
     */
    private fun parserFor(source: Uri): ParsingLoadable.Parser<Any> =
        when (Util.inferContentType(source)) {
            C.CONTENT_TYPE_HLS -> ParsingLoadable.Parser { uri, stream -> HlsPlaylistParser().parse(uri, stream) }
            else -> ParsingLoadable.Parser { _, stream -> stream.readBytes().size }
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
}
