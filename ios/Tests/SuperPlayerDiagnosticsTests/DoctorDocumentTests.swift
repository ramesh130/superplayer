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
import SuperPlayerDiagnostics
import XCTest

/// `docs/media-source-doctor.md` against the Swift vocabulary — the Kotlin `DoctorDocumentTest`'s
/// rule, run over the same file (#430, ADR-0021 rule 6's shared-document rule).
///
/// The doctor's manual is one document for both platforms, so each defect's section carries a
/// **platform line** — `- **Platforms:** Android`, `iOS and tvOS`, or `Android, iOS and tvOS` —
/// and each platform's test reads the sections that name it. This one holds every ``Pathology`` the
/// iOS doctor knows to a section of its own, in the enum's order, carrying the citation the code
/// carries; `superplayer-diagnostics`' `DoctorDocumentTest` holds the Kotlin enum to the sections
/// naming Android. A section naming a platform whose doctor has no such defect fails that
/// platform's test as an unexpected id, so the line cannot claim more than the code does, and a
/// section with no line, or a line outside the three readings, fails both.
///
/// The citation is read **per section**, for the Kotlin reason: several pathologies cite one clause
/// (RFC 8216 §4.3.4.2 four times), so a document-wide search would let a section lose its own and
/// pass on a sibling's. Nothing about prose is asserted — what a section *says* is review's.
///
/// The document is read from the source tree by `#filePath`, as `TelemetrySchemaDocumentTests`
/// reads its own: a simulator test process reads the host's file system, and a copy bundled as a
/// resource would be a second place for the sections to go stale.
final class DoctorDocumentTests: XCTestCase {

    func testEveryPathologyHasItsOwnSectionInTheDoctorsDocument() throws {
        // The enum is the authority, and the order is asserted too: the document groups its
        // sections by protocol because the enums do, and a defect appended to one group belongs in
        // that group's prose rather than at the end of the file.
        let documented = try DoctorDocument.sections().filter(\.namesApplePlatforms).map(\.id)
        XCTAssertEqual(
            documented, Pathology.allCases.map(\.id),
            "Every Pathology the iOS doctor knows needs a `#### `<id>`` section in "
                + "\(DoctorDocument.name) whose platform line names iOS and tvOS, saying what it "
                + "means, what causes it, what to change and its citation — in the enum's own order. "
                + "A section naming iOS for a defect the iOS doctor cannot name is wrong too.")
    }

    func testEveryPathologysOwnSectionCarriesTheCitationTheCodeCarries() throws {
        // One citation in two places, and neither may drift: a document citing a clause the code
        // does not is a reader sent to the wrong paragraph of the wrong specification.
        let sections = Dictionary(
            try DoctorDocument.sections().map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        for pathology in Pathology.allCases {
            guard let section = sections[pathology.id] else {
                XCTFail("\(pathology.id) has no section in \(DoctorDocument.name)")
                continue
            }
            XCTAssertTrue(
                section.body.contains(pathology.specCitation),
                "\(pathology.id)'s own section in \(DoctorDocument.name) must carry its "
                    + "`specCitation`, verbatim: \"\(pathology.specCitation)\". A sibling section "
                    + "citing the same clause does not count — several pathologies share one.")
        }
    }

    func testEverySectionNamesItsPlatformsInOneOfTheThreeReadings() throws {
        let sections = try DoctorDocument.sections()
        // A guard on the parser rather than a second authority on the count: a document it read no
        // section of would pass every assertion above vacuously.
        XCTAssertGreaterThan(sections.count, Pathology.allCases.count)
        for section in sections {
            XCTAssertNotNil(
                section.platforms,
                "\(section.id)'s section in \(DoctorDocument.name) needs exactly one line reading "
                    + "`- **Platforms:** ` and then one of: "
                    + DoctorDocument.platformReadings.joined(separator: "; ")
                    + " — found \(section.platformLines.map { "\"\($0)\"" })")
        }
    }

    /// The control: the reading filters on the line rather than passing every section, and refuses
    /// a section whose line it cannot read rather than counting it on either side.
    func testThePlatformLineDecidesWhichSectionsArePlatformsOwn() {
        let sections = DoctorDocument.sections(
            in: """
                #### `hls-a`

                - **Citation:** RFC 8216 §4
                - **Platforms:** Android, iOS and tvOS

                #### `dash-b`

                - **Platforms:** Android

                #### `hls-c`

                - **Platforms:** iOS and tvOS

                #### `hls-d`

                - **Platforms:** iOS

                ### A heading of another level ends a section
                - **Platforms:** Android
                """)
        XCTAssertEqual(sections.map(\.id), ["hls-a", "dash-b", "hls-c", "hls-d"])
        XCTAssertEqual(sections.filter(\.namesApplePlatforms).map(\.id), ["hls-a", "hls-c"])
        XCTAssertNil(sections[3].platforms, "a reading outside the three is no reading")
        XCTAssertEqual(sections[1].platformLines, ["Android"])
    }
}

/// `docs/media-source-doctor.md`, read from the source tree.
private enum DoctorDocument {

    static let name = "docs/media-source-doctor.md"

    /// Found from this file's own place: `ios/Tests/SuperPlayerDiagnosticsTests/` is three below the
    /// root.
    static let path = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: name)

    /// The three readings a platform line may carry, the Kotlin test's list too. A fixed vocabulary
    /// rather than a parse of a list, so a misspelt platform fails naming itself rather than
    /// silently counting on neither side.
    static let platformReadings = ["Android", "iOS and tvOS", "Android, iOS and tvOS"]

    /// One defect's section: its heading's id, the text up to the next heading of any level, and
    /// what its platform line said.
    struct Section {
        let id: String
        let body: String
        let platformLines: [String]

        /// The section's one platform line, where there is exactly one and it is a known reading.
        var platforms: String? {
            guard platformLines.count == 1, let line = platformLines.first,
                DoctorDocument.platformReadings.contains(line)
            else { return nil }
            return line
        }

        var namesApplePlatforms: Bool { platforms?.hasSuffix("iOS and tvOS") == true }
    }

    static func sections() throws -> [Section] {
        sections(in: try String(contentsOf: path, encoding: .utf8))
    }

    /// The document's defect sections in the order it gives them. A section runs from its own
    /// `#### `<id>`` heading to the next heading of any level, which is what makes the citation
    /// assertion a reading of one section rather than of the file.
    static func sections(in markdown: String) -> [Section] {
        var sections: [Section] = []
        var current: (id: String, lines: [Substring])?
        func close() {
            guard let open = current else { return }
            let platformLines = open.lines.compactMap { line -> String? in
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                guard trimmed.hasPrefix(platformPrefix) else { return nil }
                return String(trimmed.dropFirst(platformPrefix.count))
                    .trimmingCharacters(in: .whitespaces)
            }
            sections.append(
                Section(
                    id: open.id, body: open.lines.joined(separator: "\n"),
                    platformLines: platformLines))
            current = nil
        }
        for line in markdown.split(separator: "\n", omittingEmptySubsequences: false) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if let id = sectionId(trimmed) {
                close()
                current = (id, [])
            } else if trimmed.hasPrefix("#") {
                close()
            } else {
                current?.lines.append(line)
            }
        }
        close()
        return sections
    }

    private static let platformPrefix = "- **Platforms:**"

    /// The id of a fourth-level heading whose whole text is a backticked kebab-case id, as the
    /// Kotlin test's `#### `([a-z0-9-]+)`` reads it.
    private static func sectionId(_ line: String) -> String? {
        let prefix = "#### `"
        guard line.hasPrefix(prefix), line.hasSuffix("`"), line.count > prefix.count + 1 else {
            return nil
        }
        let id = line.dropFirst(prefix.count).dropLast()
        let allowed = Set("abcdefghijklmnopqrstuvwxyz0123456789-")
        return id.allSatisfy(allowed.contains) ? String(id) : nil
    }
}
