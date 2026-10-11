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

/// The bytes #340 observed, read from the one file both platforms read —
/// `ios/Tests/Fixtures/realtime/observed-bytes.tsv`, which the Kotlin `ObservedBytes` reads too —
/// so the two ports cannot drift into disagreeing about what was seen (`docs/testing.md`, *A
/// realtime transport on iOS*).
///
/// That spike's **caveat 2** binds it: what it saw was **H.264 only**. Each row says whether it was
/// observed or synthesized, and the properties below say how the whole record is assembled, as the
/// Kotlin object does.
enum ObservedBytes {

    /// `ios/Tests/Fixtures/realtime/`, found from this file's own place in the source tree, as
    /// `SyntheticHlsFixturesTests` finds its directory.
    static let directory = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: "Fixtures/realtime")

    /// #340's `description` dump, verbatim: the first 24 bytes of the 40-byte `avcC` it observed.
    static func avccDump() throws -> Data { try row("AVCC_DUMP") }

    /// #340's `avc3` keyframe payload, verbatim: a start code, the 24-byte SPS, the next start code.
    static func avc3Keyframe() throws -> Data { try row("AVC3_KEYFRAME") }

    /// The SPS, read off ``avc3Keyframe()``: the 24 bytes between its two start codes.
    static func sps() throws -> Data { try avc3Keyframe().subdata(in: 4..<28) }

    /// The whole record, as the Kotlin `AVCC` builds it: ``avccDump()``'s eight-byte header, the
    /// SPS's full 24 bytes, then the synthesized PPS — the one part that was not seen.
    static func avcc() throws -> Data {
        let pps = try row("SYNTHESIZED_PPS")
        return try avccDump().prefix(8) + sps() + Data([1, 0, UInt8(pps.count)]) + pps
    }

    /// The row `name` of the file, as bytes.
    static func row(_ name: String) throws -> Data {
        let text = try String(
            contentsOf: directory.appending(path: "observed-bytes.tsv"), encoding: .utf8)
        let rows = text.split(separator: "\n").filter { !$0.hasPrefix("#") }
            .map { $0.split(separator: "\t").map(String.init) }
            .filter { $0.first == name }
        guard rows.count == 1, rows[0].count == 3 else {
            throw FixtureError("observed-bytes.tsv has no single row named \(name)")
        }
        // Every token must parse: a typo dropped here would shorten the bytes on this side only,
        // which is the drift one shared file exists to prevent — the Kotlin `toInt(16)` throws too.
        return Data(
            try rows[0][2].split(separator: " ").map { token in
                guard let byte = UInt8(token, radix: 16) else {
                    throw FixtureError("observed-bytes.tsv: \(name) has a token \"\(token)\"")
                }
                return byte
            })
    }

    struct FixtureError: Error, CustomStringConvertible {
        let description: String
        init(_ description: String) { self.description = description }
    }
}
