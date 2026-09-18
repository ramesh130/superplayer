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
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.IOException

/**
 * One manifest, fetched over [chain] and read for the defects the doctor can name.
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

    /** What is wrong with the manifest at [source], or an empty list where nothing this doctor knows is. */
    fun examine(source: Uri): List<Finding> {
        val parsed = try {
            // Media3's own fetch-and-parse, used rather than reimplemented for rule 6's reason: it opens
            // the chain, reads to the end and closes, which is what a player's loader does with a manifest.
            ParsingLoadable.load(chain.createDataSource(), parserFor(source), source, C.DATA_TYPE_MANIFEST)
        } catch (rejected: ParserException) {
            // Before the refusal below, because Media3's parse failure *is* an `IOException`: what arrived
            // and what did not are two different reports, and telling them apart is the first thing a
            // support engineer does.
            return listOf(Finding(Pathology.MANIFEST_UNREADABLE, FindingSeverity.BLOCKING, magnitude = null))
        } catch (refused: IOException) {
            return listOf(unreachable(refused))
        }
        return (parsed as? HlsMultivariantPlaylist)?.let(::examineHlsMultivariant).orEmpty()
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
     * discarding parser skips is the *reading*, which is #287's and #288's to add; it still pulls the whole
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

    /**
     * A playlist with any variant that declares no `CODECS`, as one finding.
     *
     * One finding rather than one per variant: the defect is of the playlist — a packager or a template
     * that does not write the attribute at all — so a report with a line per rung would be one fact printed
     * four times.
     *
     * spec: RFC 8216 §4.3.4.2 — "Every EXT-X-STREAM-INF tag SHOULD include a CODECS attribute", and its
     * value "MUST be all of the parameters of the format" (RFC 6381). A SHOULD, so a playlist without one
     * is valid and a parser reports the absence as no codec string at all rather than as an error — which
     * is why this is read off the parsed variant rather than by re-reading the text.
     *
     * [FindingSeverity.DEGRADED] rather than advisory: the absence costs something measurable on every
     * start. A player that cannot tell from the playlist whether it can decode a rendition has to fetch a
     * segment of it to find out, so a capability check becomes a download and a rendition it cannot decode
     * becomes a stall instead of a rung it never chose. It is not blocking, because content whose single
     * rung the device can decode plays perfectly.
     *
     * No magnitude, for the reason the corpus entry has none: the attribute is present or it is not, and
     * there is no milder absence. How *many* variants are missing it is a count of the playlist rather
     * than a reading of how far the defect is pushed, and a report that printed one in the magnitude
     * column would be saying a binary defect came in degrees.
     */
    private fun examineHlsMultivariant(playlist: HlsMultivariantPlaylist): List<Finding> {
        if (playlist.variants.none { it.format.codecs == null }) return emptyList()
        return listOf(Finding(Pathology.HLS_MISSING_CODECS, FindingSeverity.DEGRADED, magnitude = null))
    }
}
