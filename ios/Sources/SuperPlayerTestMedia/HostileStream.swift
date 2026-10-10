// Copyright 2026 The SuperPlayer Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

/// One pathological stream from ``HostileManifests``: a known-good stream with one thing wrong, and
/// everything a reader needs in order to know *what* is wrong and why a real CDN serves it.
///
/// The port of `superplayer-testmedia`'s `HostileStream`, whose KDoc argues each field; the fields
/// are the deliverable rather than the bytes, because what makes a corpus useful is that each entry
/// says which clause it stretches, what misconfiguration produces it, and whether it is still a
/// legal document — and the doctor (`docs/ios-plan.md`'s i8) is graded against exactly those.
///
/// Three departures from the Kotlin type, each because of what serves it here:
///
/// - **No `protocol`.** Every entry is HLS, because DASH is not ported (ADR-0020), and a protocol is
///   a fact about which parser reads an entry — on iOS there is one. ``Validity``'s `healthy` is
///   ported though no entry carries it, because it is a label of the vocabulary a doctor grades in,
///   not a fact about this platform.
/// - **Paths, not `fake://` URIs.** ``files`` and ``declaredResponseHeaders`` are keyed by path
///   relative to wherever the corpus is served — `hostile/<id>/<severity>/<name>`, the Kotlin URI
///   with its `fake://superplayer.test/` host taken off — because an iOS test serves them from a
///   loopback origin (ADR-0021 rule 2), as ``SyntheticHlsStream`` explains for its own `files`.
/// - **A name may carry a query.** The token entries are published at a signed address, so their
///   paths end `?expires=…&signature=corpus`: the address is the defect. An origin that serves by
///   path alone strips the query to find the file, as a CDN does before checking the signature.
public struct HostileStream: Sendable, Hashable, CustomStringConvertible {

    /// Stable kebab-case name, protocol first: what a test, a report, or a doctor rule names.
    public let id: String

    /// Whether it is a legal document that behaves badly, or genuinely broken. See ``Validity``.
    public let validity: Validity

    /// How far its pathology is pushed: whether a doctor must flag it, may, or must not.
    public let severity: Severity

    /// The value this entry's pathology is generated at, in words a report can print — "top rung 4×
    /// the one below it" — or nil for a binary pathology. The argument for the value is at the
    /// value, in `HostileManifests.swift` and the Kotlin source it ports.
    public let magnitude: String?

    /// The clause this stretches or violates, as a citation a reader can look up: the same citation
    /// the `// spec:` comment at the entry carries, as data.
    public let spec: String

    /// Which misconfiguration, encoder, or packager produces this in the field, in plain language.
    /// It belongs to the pathology rather than to the level, so it reads the same at every severity.
    public let cause: String

    /// What a player is pointed at: the multivariant playlist's path, query included.
    public let sourcePath: String

    /// How much media the stream carries, for a test that needs a playback budget.
    public let durationMs: Int64

    /// The response headers this stream is *supposed* to be served with, keyed by path. Empty for
    /// every entry but the delivery ones (#289), whose defect is in a header rather than in a
    /// document; `SuperPlayerTestkit` serves them on top of the bytes.
    public let declaredResponseHeaders: [String: [String: String]]

    /// Everything the player will ask for, keyed by path, and nothing else.
    public let files: [String: [UInt8]]

    /// Whether the document is legal — the Kotlin `Validity`, whose KDoc argues the distinction.
    /// `healthy` is for a baseline a test plays beside the corpus; no entry here carries it, and it
    /// is ported so that the vocabulary is the Kotlin one whole.
    public enum Validity: Sendable, Hashable, CaseIterable {
        case healthy
        case validButHostile
        case malformed
    }

    /// How far a pathology is pushed — the Kotlin `Severity`, whose KDoc argues each level. A binary
    /// pathology exists at ``severe`` only. Ordered mildest first, as ``HostileManifests/graded(nowEpochSeconds:)``
    /// lists the levels.
    public enum Severity: String, Sendable, Hashable, CaseIterable {
        /// Within what real content does: a doctor must not flag it.
        case benign
        /// Where a reasonable threshold could fall either way.
        case borderline
        /// Unmistakable, and what ``HostileManifests/all(nowEpochSeconds:)`` carries.
        case severe
    }

    /// The id and the severity, so a failure names the entry and which of its levels it was — the
    /// Kotlin `toString`.
    public var description: String { "\(id) (\(severity.rawValue))" }
}
