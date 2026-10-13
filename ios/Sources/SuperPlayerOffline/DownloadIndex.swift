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
import SQLite3

/// The store's own index: one SQLite file beside the packages, inside the directory the consumer
/// named (`docs/ios-plan.md` §3.7) — the Kotlin download index, which is a table of the cache's
/// database there and is a file of its own here because iOS has no cache for it to be a table of.
///
/// **SQLite is the system's `libsqlite3`**, as `SuperPlayerDRM`'s `OfflineLicenceIndex` is, for its
/// reasons: no package dependency, and every file it writes inside the directory —
/// `PRAGMA temp_store = MEMORY` keeps SQLite's temporary files out of the system's temporary
/// directory, and the rollback journal is `<file>-journal` beside the file. The handful of helpers
/// below are that index's, written again rather than shared, because the two products may not depend
/// on each other (ADR-0013 rule 1) and a SQLite helper is nothing core should publish even as
/// `package`.
///
/// ref: https://www.sqlite.org/tempfiles.html — §2.1 the rollback journal; §4 `temp_store`
///
/// A row is one download's plan and where it stands: what the store needs to re-attach a task in a
/// new process, start one again that the system no longer holds, and answer a player with its
/// package. The package's location is stored **relative to the directory**, because an app's
/// container path changes between installs and launches and an absolute path into it goes stale.
@MainActor
final class DownloadIndex {

    /// A row as the store reads it back.
    struct Row {
        let plan: DownloadPlan
        /// Its place in the order of first enqueue, which the store reads back as its own.
        let ordinal: Int64
        let state: DownloadState
        let bytes: Int64
        let percent: Double?
        let packagePath: String?
    }

    private let file: URL
    private var database: OpaquePointer?

    init(directory: URL) {
        file = directory.appendingPathComponent(Self.fileName)
    }

    /// Every row, in the order its content was first enqueued.
    func rows() throws -> [Row] {
        try query(
            "SELECT content_id, source, title, variant_bps, audio, subtitles, state, bytes, "
                + "percent, package, ordinal FROM \(Self.table) ORDER BY ordinal", []
        ) { row -> Row? in
            // A row this build cannot read — a source that is no URL, a state it does not know — is
            // left out rather than guessed at: guessing "queued" would start it again.
            guard let source = URL(string: Self.text(row, 1)),
                let state = DownloadState(rawValue: Self.text(row, 6))
            else { return nil }
            return Row(
                plan: DownloadPlan(
                    contentId: Self.text(row, 0), source: source,
                    title: Self.text(row, 2),
                    variantPeakBitRateBps: Self.isNull(row, 3)
                        ? nil : sqlite3_column_double(row, 3),
                    audioLanguages: Self.list(Self.text(row, 4)),
                    subtitleLanguages: Self.list(Self.text(row, 5))),
                ordinal: sqlite3_column_int64(row, 10),
                state: state,
                bytes: sqlite3_column_int64(row, 7),
                percent: Self.isNull(row, 8) ? nil : sqlite3_column_double(row, 8),
                packagePath: Self.isNull(row, 9) ? nil : Self.text(row, 9))
        }.compactMap { $0 }
    }

    /// The highest place in the order any row holds, or zero.
    func maxOrdinal() throws -> Int64 {
        try query("SELECT COALESCE(MAX(ordinal), 0) FROM \(Self.table)", []) {
            sqlite3_column_int64($0, 0)
        }.first ?? 0
    }

    /// Writes `plan` as a queued download at `ordinal`, which the store gave its content when it was
    /// first enqueued: given rather than taken here, because plans are written as their manifest
    /// reads finish, in either order (#425), and one enqueued again after a failure that wrote no row
    /// keeps its first ordinal (#507). The store's is the only ordinal; the index derives none.
    func insert(_ plan: DownloadPlan, ordinal: Int64) throws {
        try run(
            "INSERT OR REPLACE INTO \(Self.table) (content_id, ordinal, source, title, variant_bps, "
                + "audio, subtitles, state, bytes, percent, package) VALUES (?, ?, "
                + "?, ?, ?, ?, ?, ?, 0, NULL, NULL)",
            [
                .text(plan.contentId), .integer(ordinal),
                .text(plan.source.absoluteString),
                .text(plan.title), plan.variantPeakBitRateBps.map(Value.real) ?? .null,
                .text(plan.audioLanguages.joined(separator: "\n")),
                .text(plan.subtitleLanguages.joined(separator: "\n")),
                .text(DownloadState.queued.rawValue),
            ])
    }

    /// Records where `contentId` stands.
    func update(
        _ contentId: String, state: DownloadState, bytes: Int64, percent: Double?,
        packagePath: String? = nil
    ) throws {
        try run(
            "UPDATE \(Self.table) SET state = ?, bytes = ?, percent = ?, "
                + "package = COALESCE(?, package) WHERE content_id = ?",
            [
                .text(state.rawValue), .integer(bytes), percent.map(Value.real) ?? .null,
                packagePath.map(Value.text) ?? .null, .text(contentId),
            ])
    }

    /// Forgets `contentId`.
    func remove(_ contentId: String) throws {
        try run("DELETE FROM \(Self.table) WHERE content_id = ?", [.text(contentId)])
    }

    /// Closes the connection. The file stays.
    func close() {
        if let database { sqlite3_close_v2(database) }
        database = nil
    }

    // MARK: SQLite

    private enum Value {
        case text(String)
        case integer(Int64)
        case real(Double)
        case null
    }

    /// The open connection, opened — and the file and its table created — on first use.
    private func connection() throws -> OpaquePointer {
        if let database { return database }
        var opened: OpaquePointer?
        let status = sqlite3_open_v2(
            file.path, &opened, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX,
            nil)
        guard status == SQLITE_OK, let opened else {
            let error = Self.failure(opened, status)
            if let opened { sqlite3_close_v2(opened) }
            throw error
        }
        do {
            for statement in [
                "PRAGMA temp_store = MEMORY",
                "CREATE TABLE IF NOT EXISTS \(Self.table) ("
                    + "content_id TEXT PRIMARY KEY NOT NULL, ordinal INTEGER NOT NULL, "
                    + "source TEXT NOT NULL, title TEXT NOT NULL, variant_bps REAL, "
                    + "audio TEXT NOT NULL, subtitles TEXT NOT NULL, state TEXT NOT NULL, "
                    + "bytes INTEGER NOT NULL, percent REAL, package TEXT)",
            ] {
                let executed = sqlite3_exec(opened, statement, nil, nil, nil)
                guard executed == SQLITE_OK else { throw Self.failure(opened, executed) }
            }
            // The schema's version, so a later one can tell which file it is opening — stamped on a
            // new file only, so an older build never stamps a newer file as its own.
            database = opened
            let version = try query("PRAGMA user_version", []) { sqlite3_column_int64($0, 0) }
            if version.first == 0 { try run("PRAGMA user_version = \(Self.schemaVersion)", []) }
        } catch {
            database = nil
            sqlite3_close_v2(opened)
            throw error
        }
        database = opened
        return opened
    }

    private func run(_ sql: String, _ values: [Value]) throws {
        _ = try query(sql, values) { _ in () }
    }

    private func query<Row>(_ sql: String, _ values: [Value], _ read: (OpaquePointer) -> Row)
        throws -> [Row]
    {
        let database = try connection()
        var statement: OpaquePointer?
        let prepared = sqlite3_prepare_v2(database, sql, -1, &statement, nil)
        guard prepared == SQLITE_OK, let statement else { throw Self.failure(database, prepared) }
        defer { sqlite3_finalize(statement) }
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            let bound: Int32
            switch value {
            case .text(let text):
                bound = sqlite3_bind_text(statement, index, text, -1, Self.transient)
            case .integer(let integer): bound = sqlite3_bind_int64(statement, index, integer)
            case .real(let real): bound = sqlite3_bind_double(statement, index, real)
            case .null: bound = sqlite3_bind_null(statement, index)
            }
            guard bound == SQLITE_OK else { throw Self.failure(database, bound) }
        }
        var rows: [Row] = []
        while true {
            let stepped = sqlite3_step(statement)
            if stepped == SQLITE_DONE { return rows }
            guard stepped == SQLITE_ROW else { throw Self.failure(database, stepped) }
            rows.append(read(statement))
        }
    }

    private static func text(_ row: OpaquePointer, _ column: Int32) -> String {
        sqlite3_column_text(row, column).map { String(cString: $0) } ?? ""
    }

    private static func isNull(_ row: OpaquePointer, _ column: Int32) -> Bool {
        sqlite3_column_type(row, column) == SQLITE_NULL
    }

    private static func list(_ joined: String) -> [String] {
        joined.isEmpty ? [] : joined.components(separatedBy: "\n")
    }

    private static func failure(_ database: OpaquePointer?, _ status: Int32) -> DownloadsError {
        let message = database.flatMap { sqlite3_errmsg($0) }.map { String(cString: $0) }
        return .database(
            code: Int(status), message: message ?? String(cString: sqlite3_errstr(status)))
    }

    /// ref: https://www.sqlite.org/c3ref/c_static.html — `SQLITE_TRANSIENT`, which Swift does not
    /// import: SQLite copies a bound string before the call returns.
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    /// The index's file name inside the consumer's directory.
    static let fileName = "downloads.db"
    private static let table = "downloads"
    private static let schemaVersion = 1
}
