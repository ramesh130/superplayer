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

/// The Swift half of ADR-0021 rule 6: `SyntheticHlsStream` produces exactly the files committed
/// under `ios/Tests/Fixtures/synthetic/`, which `superplayer-testmedia`'s `SyntheticHlsFixturesTest`
/// holds the Kotlin generator to. Byte for byte, file for file, so the two platforms play one
/// corpus and neither needs the other's toolchain to know it.
///
/// Which fixtures exist, and with what arguments, is read from the directory's `index.tsv`, which
/// the Kotlin test writes — one table, not two. The fixtures are read from the source tree by
/// `#filePath` rather than copied in as bundle resources: a simulator test process reads the host's
/// file system, the directory is shared with the targets that will serve it later, and a resource
/// copy would be a second place for the bytes to go stale.
final class SyntheticHlsFixturesTests: XCTestCase {

    func testTheSwiftGeneratorProducesTheCommittedFixtures() throws {
        let fixtures = try Fixtures.index()
        XCTAssertFalse(fixtures.isEmpty, "index.tsv lists no fixtures")

        for fixture in fixtures {
            let generated = try fixture.generate()
            let differences = try Fixtures.differences(
                expected: generated, directory: Fixtures.directory.appending(path: fixture.name))
            XCTAssertEqual(
                differences, [],
                "\(fixture.name): the Swift generator is not the committed fixture; the Kotlin "
                    + "generator is the reference, so the Swift port follows it")
        }
    }

    func testEveryFixtureDirectoryIsListedInTheIndex() throws {
        let listed = Set(try Fixtures.index().map(\.name))
        let present = try FileManager.default.contentsOfDirectory(
            at: Fixtures.directory, includingPropertiesForKeys: [.isDirectoryKey]
        )
        .filter { try $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true }
        .map(\.lastPathComponent)

        XCTAssertEqual(Set(present), listed)
    }

    // The controls: a comparison that passed everything would make the test above say nothing.

    func testOneByteChangedInOneSegmentIsNamed() throws {
        let fixture = try XCTUnwrap(try Fixtures.index().first { $0.name == "hls-vod" })
        var generated = try fixture.generate()
        let segment = SyntheticHlsStream.segmentName(1)
        generated[segment]![generated[segment]!.count - 1] &+= 1

        XCTAssertEqual(
            try Fixtures.differences(
                expected: generated, directory: Fixtures.directory.appending(path: fixture.name)),
            ["segment1.aac differs"])
    }

    func testAMissingFileAndAnExtraFileAreBothNamed() throws {
        let fixture = try XCTUnwrap(try Fixtures.index().first { $0.name == "hls-ladder" })
        var generated = try fixture.generate()
        generated[SyntheticHlsStream.secondMediaPlaylistName] = nil
        generated["segment9.aac"] = [0]

        XCTAssertEqual(
            try Fixtures.differences(
                expected: generated, directory: Fixtures.directory.appending(path: fixture.name)),
            ["media-high.m3u8 is not generated", "segment9.aac is missing"])
    }
}

/// The committed fixtures and the one table that says what they are.
enum Fixtures {
    /// `ios/Tests/Fixtures/synthetic/`, found from this file's own place in the source tree.
    static let directory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: "Fixtures/synthetic")

    struct Fixture {
        let name: String
        let generator: String
        let segmentCount: Int
        let variantCount: Int

        /// The generator the row names, with the row's arguments. A generator this side does not
        /// know is a failure rather than a skip, so a Kotlin fixture cannot arrive unported.
        func generate() throws -> [String: [UInt8]] {
            switch generator {
            case "SyntheticHlsStream.files":
                return SyntheticHlsStream.files(
                    segmentCount: segmentCount, variantCount: variantCount)
            default:
                throw FixtureError("\(name): no Swift port of the generator \(generator)")
            }
        }
    }

    struct FixtureError: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }

    /// `index.tsv`: one fixture per line, tab-separated, `#` lines a comment.
    static func index() throws -> [Fixture] {
        let text = try String(
            contentsOf: directory.appending(path: "index.tsv"), encoding: .utf8)
        return try text.split(separator: "\n").filter { !$0.hasPrefix("#") }.map { line in
            let fields = line.split(separator: "\t").map(String.init)
            guard fields.count == 4, let segments = Int(fields[2]), let variants = Int(fields[3])
            else { throw FixtureError("index.tsv: a malformed row, \"\(line)\"") }
            return Fixture(
                name: fields[0], generator: fields[1], segmentCount: segments,
                variantCount: variants)
        }
    }

    /// What differs between `expected` and the files in `directory`, one line per file, in name
    /// order. The wording reads from the generator's side: what it does not produce is "not
    /// generated", what it produces and nobody committed is "missing".
    static func differences(expected: [String: [UInt8]], directory: URL) throws -> [String] {
        let committed = try FileManager.default.contentsOfDirectory(atPath: directory.path)
            .filter { $0 != ".DS_Store" }
        let names = Set(expected.keys).union(committed).sorted()
        return try names.compactMap { name in
            guard let want = expected[name] else { return "\(name) is not generated" }
            guard committed.contains(name) else { return "\(name) is missing" }
            let have = try Data(contentsOf: directory.appending(path: name))
            return Array(have) == want ? nil : "\(name) differs"
        }
    }
}
