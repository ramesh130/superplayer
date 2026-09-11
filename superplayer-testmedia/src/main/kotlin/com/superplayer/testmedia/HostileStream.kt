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

/**
 * One pathological stream from [HostileManifests]: a known-good stream with one thing wrong, and
 * everything a reader needs in order to know *what* is wrong and why a real CDN serves it.
 *
 * The fields are the deliverable rather than the bytes. A broken manifest on its own tests the
 * parser; what makes a corpus useful is that each entry says which clause it stretches, what
 * misconfiguration produces it, and whether it is still a legal document — and `MediaSourceDoctor`
 * (`PRD.md` §3.6, Phase 9) is graded against exactly those three.
 *
 * Handed over as URI-to-bytes for the reason `docs/testing.md` gives for the synthetic streams
 * themselves: this module names no Media3 type, so putting a stream into a `FakeDataSet` is a line
 * at the call site rather than a signature here.
 */
public class HostileStream internal constructor(

    /** Stable kebab-case name, protocol first: what a test, a report, or a doctor rule names. */
    public val id: String,

    /** Which protocol's parser reads it. */
    public val protocol: Protocol,

    /** Whether it is a legal document that behaves badly, or genuinely broken. See [Validity]. */
    public val validity: Validity,

    /**
     * The clause this stretches or violates, as a citation a reader can look up — RFC 8216 for HLS,
     * ISO/IEC 23009-1 and DASH-IF IOP for DASH.
     *
     * "Valid but hostile" is a claim about a specification, and without the citation it is an
     * opinion. The same citation appears as a `// spec:` comment at the entry that builds this
     * stream, which is where the argument is made; this field is that citation as data, so a report
     * over the corpus carries it.
     */
    public val spec: String,

    /**
     * Which misconfiguration, encoder, or packager produces this in the field, in plain language.
     *
     * Written now, while the pathology is being constructed, because this is the sentence Phase 9's
     * doctor turns into its user-facing message and it is far harder to reconstruct later.
     */
    public val cause: String,

    /** What a player is pointed at: the multivariant playlist, or the MPD. */
    public val sourceUri: String,

    /** How much media the stream carries, for a test that needs a playback budget. */
    public val durationMs: Long,

    /**
     * The response headers this stream is *supposed* to be served with, keyed by resource URI.
     *
     * Empty for every entry but the cache-control one, and it is data rather than behaviour:
     * Media3's `FakeDataSource` serves bytes and reports no headers, so nothing in this repository
     * applies these today. They are recorded because the pathology they describe — a live media
     * playlist served cacheable while its segments are not — is a manifest-and-transport defect
     * rather than a manifest one, and a corpus that silently dropped it would be claiming coverage
     * it does not have. `docs/testing.md` says so where it describes extending the corpus.
     */
    public val declaredResponseHeaders: Map<String, Map<String, String>>,

    private val files: Map<String, ByteArray>,
) {

    /**
     * Everything the player will ask for, keyed by URI, and nothing else: an unknown URI is a test
     * failure, exactly as it is for [SyntheticHlsStream.resources].
     */
    public fun resources(): Map<String, ByteArray> = files

    /** Which protocol's parser reads this stream. */
    public enum class Protocol { HLS, DASH }

    /**
     * Whether the document is legal.
     *
     * The distinction is the whole point of the corpus. A [VALID_BUT_HOSTILE] stream conforms to its
     * specification and is still handled badly, which is where real playback bugs live; a
     * [MALFORMED] one breaks a MUST, so a strict parser is entitled to reject it and a lenient one
     * is guessing. Both are worth carrying — a packager really does emit both — but they are graded
     * differently, so the corpus labels them rather than leaving a reader to infer it from the
     * outcome. The label is a claim about the document, never about a player: Media3 plays some
     * malformed manifests without complaint, and the corpus's test records that too.
     *
     * [HEALTHY] is for a baseline — a conforming stream a test plays *beside* the corpus, so that a
     * failing row can be told apart from a harness that cannot play the protocol at all. No entry in
     * `HostileManifests.all()` carries it.
     */
    public enum class Validity { HEALTHY, VALID_BUT_HOSTILE, MALFORMED }

    /** The id, so a parameterised test's failure names the pathology rather than an object hash. */
    override fun toString(): String = id
}
