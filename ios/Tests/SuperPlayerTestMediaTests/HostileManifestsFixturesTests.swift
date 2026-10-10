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

import Foundation
import SuperPlayerTestMedia
import XCTest

/// The Swift half of ADR-0021 rule 6 for the hostile corpus (#412): `HostileManifests` produces
/// exactly the entries committed under `ios/Tests/Fixtures/hostile/`, which `superplayer-testmedia`'s
/// `HostileManifestsFixturesTest` holds the Kotlin generator to — every HLS entry at every severity,
/// its files byte for byte, its fields as the index states them, and its declared headers.
///
/// Which entries exist is read from the directory's `index.tsv`, which the Kotlin test writes, so an
/// entry Kotlin has and Swift lacks fails ``testTheSwiftCorpusHasEveryIndexedEntryAndNoOther`` naming
/// it, rather than going unnoticed because nobody added it to a second list.
final class HostileManifestsFixturesTests: XCTestCase {

    func testTheSwiftCorpusHasEveryIndexedEntryAndNoOther() throws {
        let rows = try HostileFixtures.index()
        XCTAssertFalse(rows.isEmpty, "index.tsv lists no entries")

        XCTAssertEqual(
            HostileFixtures.unmatched(rows: rows, entries: try HostileFixtures.swiftCorpus(rows)),
            [])
    }

    func testEveryEntryIsTheCommittedFilesByteForByte() throws {
        let rows = try HostileFixtures.index()
        for entry in try HostileFixtures.swiftCorpus(rows) {
            let directory = HostileFixtures.directory(of: entry)
            XCTAssertEqual(
                try Fixtures.differences(
                    expected: HostileFixtures.relativeFiles(of: entry),
                    directory: HostileFixtures.root.appending(path: directory)),
                [],
                "\(entry): the Swift generator is not the committed fixture; the Kotlin generator is "
                    + "the reference, so the Swift port follows it")
        }
    }

    func testEveryEntryCarriesTheFieldsTheIndexStates() throws {
        let rows = try HostileFixtures.index()
        let entries = try HostileFixtures.swiftCorpus(rows)
        for row in rows {
            guard let entry = entries.first(where: { HostileFixtures.key(of: $0) == row.key })
            else { continue }  // Named by the test above, which is the one about absence.
            XCTAssertEqual(HostileFixtures.row(of: entry, mintedAt: row.nowEpochSeconds), row)
        }
    }

    func testEveryEntryDeclaresTheCommittedHeaders() throws {
        let rows = try HostileFixtures.index()
        let declared = try HostileFixtures.swiftCorpus(rows).flatMap(HostileFixtures.headerRows)

        XCTAssertEqual(declared.sorted(), try HostileFixtures.headers().sorted())
    }

    func testEveryEntryDirectoryIsIndexed() throws {
        let indexed = Set(try HostileFixtures.index().map { "\($0.id)/\($0.severity)" })
        let ids = try FileManager.default.contentsOfDirectory(atPath: HostileFixtures.root.path)
            .filter { !$0.contains(".") }
        let present = try ids.flatMap { id in
            try FileManager.default.contentsOfDirectory(
                atPath: HostileFixtures.root.appending(path: id).path
            )
            .filter { $0 != ".DS_Store" }.map { "\(id)/\($0)" }
        }

        XCTAssertEqual(Set(present), indexed)
    }

    // The controls: a comparison that passed everything would make the tests above say nothing.

    func testAnEntryMissingFromTheSwiftCorpusIsNamed() throws {
        let rows = try HostileFixtures.index()
        let entries = try HostileFixtures.swiftCorpus(rows).filter {
            !($0.id == "hls-token-expiring-in-window" && $0.severity == .borderline)
        }

        XCTAssertEqual(
            HostileFixtures.unmatched(rows: rows, entries: entries),
            [
                "hls-token-expiring-in-window (borderline) is in the Kotlin corpus and not the Swift one"
            ])
    }

    func testAnEntryTheKotlinCorpusLacksIsNamed() throws {
        let rows = try HostileFixtures.index().filter { $0.id != "hls-missing-codecs" }
        let entries = try HostileFixtures.swiftCorpus(rows)

        XCTAssertEqual(
            HostileFixtures.unmatched(rows: rows, entries: entries),
            ["hls-missing-codecs (severe) is in the Swift corpus and not the Kotlin one"])
    }

    func testOneByteChangedInOneEntryIsNamed() throws {
        let rows = try HostileFixtures.index()
        let entry = try XCTUnwrap(
            try HostileFixtures.swiftCorpus(rows).first {
                $0.id == "hls-inconsistent-segment-durations" && $0.severity == .severe
            })
        var files = HostileFixtures.relativeFiles(of: entry)
        files["segment1.aac"]![files["segment1.aac"]!.count - 1] &+= 1

        XCTAssertEqual(
            try Fixtures.differences(
                expected: files,
                directory: HostileFixtures.root.appending(
                    path: HostileFixtures.directory(of: entry))),
            ["segment1.aac differs"])
    }

    func testAFieldThatDiffersIsSeen() throws {
        let row = try XCTUnwrap(try HostileFixtures.index().first { $0.id == "hls-ladder-gap" })
        let entry = HostileManifests.hlsLadderGap(.borderline)

        XCTAssertNotEqual(HostileFixtures.row(of: entry, mintedAt: row.nowEpochSeconds), row)
    }
}

/// `ios/Tests/Fixtures/hostile/`, the two tables in it, and the shapes the tests compare.
enum HostileFixtures {

    static let root = Fixtures.directory.deletingLastPathComponent().appending(path: "hostile")

    /// One row of `index.tsv`, field for field as the Kotlin test writes it.
    struct Row: Equatable, CustomStringConvertible {
        let id: String
        let severity: String
        let validity: String
        let nowEpochSeconds: Int64
        let durationMs: Int64
        let source: String
        let magnitude: String
        let spec: String
        let cause: String

        var key: String { "\(id) (\(severity))" }
        var description: String { key }
    }

    /// What a magnitude-less row carries in its magnitude column.
    static let noMagnitude = "-"

    /// The rows of `index.tsv`, `#` lines a comment.
    static func index() throws -> [Row] {
        try table("index.tsv", columns: 9).map { fields in
            guard let epoch = Int64(fields[3]), let duration = Int64(fields[4]) else {
                throw Fixtures.FixtureError("index.tsv: a malformed row, \(fields)")
            }
            return Row(
                id: fields[0], severity: fields[1], validity: fields[2], nowEpochSeconds: epoch,
                durationMs: duration, source: fields[5], magnitude: fields[6], spec: fields[7],
                cause: fields[8])
        }
    }

    /// The rows of `headers.tsv`: id, severity, file, name and value, tab-joined.
    static func headers() throws -> [String] {
        try table("headers.tsv", columns: 5).map { $0.joined(separator: "\t") }
    }

    /// The Swift corpus, minted at the instant the index says the Kotlin one was. One instant for
    /// the whole index, which the Kotlin test writes and this reads rather than assumes.
    static func swiftCorpus(_ rows: [Row]) throws -> [HostileStream] {
        let instants = Set(rows.map(\.nowEpochSeconds))
        guard instants.count == 1, let instant = instants.first else {
            throw Fixtures.FixtureError("index.tsv: one minting instant expected, \(instants)")
        }
        return HostileManifests.graded(nowEpochSeconds: instant)
    }

    /// Every entry one side has and the other lacks, named from the Kotlin side, which is the
    /// reference: an entry missing here is one the port has yet to follow.
    static func unmatched(rows: [Row], entries: [HostileStream]) -> [String] {
        let indexed = rows.map(\.key)
        let ported = entries.map(key(of:))
        return indexed.filter { !ported.contains($0) }.map {
            "\($0) is in the Kotlin corpus and not the Swift one"
        }
            + ported.filter { !indexed.contains($0) }.map {
                "\($0) is in the Swift corpus and not the Kotlin one"
            }
    }

    static func key(of entry: HostileStream) -> String { entry.description }

    /// `<id>/<severity>`: where an entry's files are committed.
    static func directory(of entry: HostileStream) -> String {
        "\(entry.id)/\(entry.severity.rawValue)"
    }

    /// An entry's files by name within its own directory, as the fixtures hold them.
    static func relativeFiles(of entry: HostileStream) -> [String: [UInt8]] {
        let prefix = "hostile/\(directory(of: entry))/"
        return entry.files.reduce(into: [:]) { files, file in
            files[String(file.key.dropFirst(prefix.count))] = file.value
        }
    }

    /// The row the Kotlin test would write for `entry`.
    static func row(of entry: HostileStream, mintedAt instant: Int64) -> Row {
        Row(
            id: entry.id, severity: entry.severity.rawValue,
            validity: kotlinName(of: entry.validity), nowEpochSeconds: instant,
            durationMs: entry.durationMs,
            source: String(entry.sourcePath.dropFirst("hostile/\(directory(of: entry))/".count)),
            magnitude: entry.magnitude ?? noMagnitude, spec: entry.spec, cause: entry.cause)
    }

    /// `entry`'s declared headers as `headers.tsv` rows.
    static func headerRows(of entry: HostileStream) -> [String] {
        let prefix = "hostile/\(directory(of: entry))/"
        return entry.declaredResponseHeaders.flatMap { path, declared in
            declared.map { name, value in
                [
                    entry.id, entry.severity.rawValue, String(path.dropFirst(prefix.count)), name,
                    value,
                ]
                .joined(separator: "\t")
            }
        }
    }

    /// The Kotlin enum constant's name, which is what the index carries.
    private static func kotlinName(of validity: HostileStream.Validity) -> String {
        switch validity {
        case .healthy: "HEALTHY"
        case .validButHostile: "VALID_BUT_HOSTILE"
        case .malformed: "MALFORMED"
        }
    }

    private static func table(_ name: String, columns: Int) throws -> [[String]] {
        let text = try String(contentsOf: root.appending(path: name), encoding: .utf8)
        return try text.split(separator: "\n").filter { !$0.hasPrefix("#") }.map { line in
            let fields = line.split(separator: "\t", omittingEmptySubsequences: false)
                .map(String.init)
            guard fields.count == columns else {
                throw Fixtures.FixtureError("\(name): a malformed row, \"\(line)\"")
            }
            return fields
        }
    }
}
