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

package com.superplayer.testmedia

import com.superplayer.testmedia.HostileStream.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Kotlin half of ADR-0021 rule 6 for the hostile corpus (#412): the HLS entries of
 * [HostileManifests.graded] — every pathology at every severity it has — are committed under
 * `ios/Tests/Fixtures/hostile/`, and this test fails `check` when they are not what the Kotlin
 * generator produces now. The Swift half is `HostileManifestsFixturesTests` in
 * `SuperPlayerTestMediaTests`, which holds the Swift port to the same files and to the same index.
 *
 * Only HLS, because DASH is not ported (ADR-0020); a DASH entry here would be a fixture nothing on
 * the other side reads.
 *
 * Three kinds of file, because an entry is more than its bytes ([HostileStream]'s KDoc says the
 * fields are the deliverable):
 *
 * - `<id>/<severity>/<name>` — the entry's files, under the directory [HostileStream.sourceUri]
 *   names with its `fake://superplayer.test/hostile/` prefix taken off. A token entry's names carry
 *   their query, `?expires=…&signature=corpus`, because that is the address the stream is published
 *   at and the address is its defect.
 * - `index.tsv` — one row per entry, in [HostileManifests.graded]'s order: the id, the severity,
 *   the validity, the instant its tokens were minted at, its duration, its source, its magnitude
 *   (`-` where it has none), its citation and its cause. The Swift test reads which entries exist
 *   from here, so an entry Kotlin has and Swift has not fails there naming it.
 * - `headers.tsv` — [HostileStream.declaredResponseHeaders], one header per row, keyed by the file
 *   it is declared on: the form both platforms read, since a header is no file of its own.
 */
class HostileManifestsFixturesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun theCommittedFixturesAreWhatTheGeneratorProducesNow() {
        IosFixtures.checkOrUpdate(expectedFixtures(), DIRECTORY_PROPERTY, "ios/Tests/Fixtures/hostile/")
    }

    // The controls: a comparison that passed everything would make the test above say nothing.

    @Test
    fun oneByteChangedInOneEntryIsNamed() {
        val directory = temporary.newFolder()
        val expected = expectedFixtures()
        IosFixtures.write(expected, directory)
        val playlist = File(directory, "hls-ladder-gap/borderline/master.m3u8")
        playlist.writeText(playlist.readText().replace("BANDWIDTH=512000", "BANDWIDTH=512001"))

        assertEquals(listOf("hls-ladder-gap/borderline/master.m3u8 differs"), IosFixtures.differences(expected, directory))
    }

    @Test
    fun anEntryMissingFromTheDirectoryIsNamedFileByFile() {
        val directory = temporary.newFolder()
        val expected = expectedFixtures()
        IosFixtures.write(expected, directory)
        File(directory, "hls-missing-codecs").deleteRecursively()

        val differences = IosFixtures.differences(expected, directory)
        assertTrue(differences.toString(), differences.isNotEmpty())
        assertTrue(differences.toString(), differences.all { it.startsWith("hls-missing-codecs/severe/") && it.endsWith(" is missing") })
    }

    @Test
    fun theIndexListsEveryHlsEntryAtEverySeverityAndNoDashOne() {
        val rows = index().map { it.split('\t') }
        val hls = HostileManifests.graded(FIXTURE_EPOCH_SECONDS).filter { it.protocol == HostileStream.Protocol.HLS }

        assertEquals(hls.map { "${it.id}/${it.severity.name.lowercase()}" }, rows.map { "${it[0]}/${it[1]}" })
        assertTrue(rows.none { it[0].startsWith("dash-") })
        // Four graded pathologies at three levels and seven binary ones, as `HostileManifestCorpusTest` pins them.
        assertEquals(19, rows.size)
    }

    @Test
    fun aRowCarriesTheFieldsAReportPrints() {
        val row = index().single { it.startsWith("hls-overstated-bitrate\tborderline\t") }

        assertEquals(
            "hls-overstated-bitrate\tborderline\tVALID_BUT_HOSTILE\t$FIXTURE_EPOCH_SECONDS\t8000\tmaster.m3u8\t" +
                "declares 1.43× its real bitrate\tRFC 8216 §4.3.4.2\t" +
                HostileManifests.hlsOverstatedBitrate(Severity.BORDERLINE).cause,
            row,
        )
        assertTrue(index().single { it.startsWith("hls-missing-codecs\t") }.split('\t')[6] == NO_MAGNITUDE)
    }

    @Test
    fun theTokensAreMintedAtTheFixtureInstantRatherThanAtNow() {
        // What makes the token entries fixtures at all: `expires` is in the bytes, and a corpus
        // minted at the wall clock would differ every second.
        val expected = expectedFixtures().keys

        assertTrue(expected.toString(), "hls-token-scoped-to-manifest/severe/master.m3u8?expires=${FIXTURE_EPOCH_SECONDS + 86_400}&signature=corpus" in expected)
        assertTrue(expected.toString(), "hls-token-expiring-in-window/severe/segment0.aac?expires=${FIXTURE_EPOCH_SECONDS + 4}&signature=corpus" in expected)
    }

    @Test
    fun theDeclaredHeadersAreWrittenOnePerRowAgainstTheFileTheyAreDeclaredOn() {
        val headers = String(expectedFixtures().getValue(HEADERS_FILE), Charsets.UTF_8)

        assertTrue(headers, headers.contains("hls-cached-live-playlist\tsevere\tmedia.m3u8\tCache-Control\tpublic, max-age=600\n"))
        assertTrue(headers, headers.contains("hls-cached-live-playlist\tsevere\tsegment3.aac\tCache-Control\tno-store\n"))
        assertTrue(headers, headers.contains("hls-cors-refuses-credentials\tsevere\tmaster.m3u8\tAccess-Control-Allow-Origin\t*\n"))
        // The multivariant playlist of the cache entry is declared nothing, so it has no row.
        assertTrue(headers, !headers.contains("hls-cached-live-playlist\tsevere\tmaster.m3u8\t"))
    }

    private fun index(): List<String> = String(expectedFixtures().getValue(INDEX_FILE), Charsets.UTF_8)
        .lines()
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    private companion object {
        const val DIRECTORY_PROPERTY = "superplayer.hostileFixtures.dir"
        const val INDEX_FILE = "index.tsv"
        const val HEADERS_FILE = "headers.tsv"

        /** What a magnitude-less row carries in its magnitude column: the fields are tab-separated. */
        const val NO_MAGNITUDE = "-"

        /**
         * The instant every token in the fixtures is minted at, in seconds since the epoch: a fixed
         * one, because a token's `expires` is part of the bytes. Its value is arbitrary — 2027-01-15 —
         * and a later one would serve as well; what matters is that it never moves.
         */
        const val FIXTURE_EPOCH_SECONDS = 1_800_000_000L

        /** `fake://superplayer.test/hostile/`, which a fixture path does not carry. */
        const val CORPUS_PREFIX = "fake://superplayer.test/hostile/"

        /** Every committed file, as its path under the fixtures directory to its bytes. */
        fun expectedFixtures(): Map<String, ByteArray> = buildMap {
            val index = StringBuilder(
                "# id\tseverity\tvalidity\tnowEpochSeconds\tdurationMs\tsource\tmagnitude\tspec\tcause\n",
            )
            val headers = StringBuilder("# id\tseverity\tfile\tname\tvalue\n")
            HostileManifests.graded(FIXTURE_EPOCH_SECONDS)
                .filter { it.protocol == HostileStream.Protocol.HLS }
                .forEach { stream ->
                    val severity = stream.severity.name.lowercase()
                    val directory = "${stream.id}/$severity/"
                    val fields = listOf(
                        stream.id,
                        severity,
                        stream.validity.name,
                        FIXTURE_EPOCH_SECONDS.toString(),
                        stream.durationMs.toString(),
                        stream.sourceUri.removePrefix(CORPUS_PREFIX + directory),
                        stream.magnitude ?: NO_MAGNITUDE,
                        stream.spec,
                        stream.cause,
                    )
                    // A tab or a line break inside a field would make a second column or a second row.
                    check(fields.none { '\t' in it || '\n' in it }) { "${stream.id}: a field cannot be written as TSV" }
                    index.append(fields.joinToString("\t")).append('\n')
                    stream.resources().forEach { (uri, bytes) -> put(uri.removePrefix(CORPUS_PREFIX), bytes) }
                    stream.declaredResponseHeaders.toSortedMap().forEach { (uri, declared) ->
                        val file = uri.removePrefix(CORPUS_PREFIX + directory)
                        declared.toSortedMap().forEach { (name, value) ->
                            headers.append("${stream.id}\t$severity\t$file\t$name\t$value\n")
                        }
                    }
                }
            put(INDEX_FILE, index.toString().toByteArray(Charsets.UTF_8))
            put(HEADERS_FILE, headers.toString().toByteArray(Charsets.UTF_8))
        }
    }
}
