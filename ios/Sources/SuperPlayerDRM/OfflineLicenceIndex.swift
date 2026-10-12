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

/// The one file an ``OfflineLicenceStore`` writes, inside the directory the consumer named — the
/// Kotlin `OfflineLicenceIndex`, whose doc comment carries the argument for every choice below that
/// is not iOS's own.
///
/// **SQLite is the system's `libsqlite3`**, through the SDK's `SQLite3` module: no package
/// dependency and no `THIRD_PARTY.md` row, since the library ships with every Apple OS. The module
/// links it by itself, and the target names it again in `Package.swift`
/// (`linkerSettings: [.linkedLibrary("sqlite3")]`) so the link is written down where a reader of the
/// manifest looks. Core Data and SwiftData were not chosen: both choose a store's location and
/// companion files by their own rules, and what this file has to promise is the opposite.
///
/// **Everything it writes lands inside the directory** (ADR-0012 rule 8): the database file and the
/// rollback journal SQLite keeps beside it, which is `<file>-journal` in the same directory. SQLite's
/// one other habit is temporary files for large sorts and temporary tables, made in the *system's*
/// temporary directory; `PRAGMA temp_store = MEMORY` keeps those in memory, which costs nothing for an
/// index of a few rows.
///
/// ref: https://www.sqlite.org/tempfiles.html — §2.1 the rollback journal, beside the database;
/// §2.7 and §4 the temporary files and `temp_store`
///
/// What is in a row is the device's persistable keys and two deadlines. Where Android keeps a
/// *key-set id* — a handle into the device's own protected storage — FairPlay hands the app the key
/// itself, encrypted to this device (`persistableContentKey(fromKeyVendorResponse:options:)`), and
/// leaves storing it to the app; so the bytes are here, and they are useless on any other device for
/// the Kotlin handle's reason. The deadlines are absolute wall-clock milliseconds, with the Kotlin
/// type's stated cost: a device clock moved forward moves every reading with it, and the device
/// enforces the licence's own expiry whatever this file says.
final class OfflineLicenceIndex: @unchecked Sendable {
    // @unchecked: the connection is read and written under `lock` only, and `file` is fixed.

    private let file: URL
    private let lock = NSLock()
    private var database: OpaquePointer?

    init(directory: URL) throws {
        // Created rather than required, as the Kotlin store's directory is: a consumer naming a
        // directory inside their own storage should not also have to have made it.
        var isDirectory: ObjCBool = false
        if FileManager.default.fileExists(atPath: directory.path, isDirectory: &isDirectory) {
            guard isDirectory.boolValue else {
                throw OfflineLicenceStoreError.notADirectory(directory)
            }
        } else {
            try FileManager.default.createDirectory(
                at: directory, withIntermediateDirectories: true)
        }
        file = directory.appendingPathComponent(Self.fileName)
    }

    deinit {
        if let database { sqlite3_close_v2(database) }
    }

    /// Writes the keys a store's acquisition or renewal answered with, replacing whatever was held
    /// for `contentId`, and returns what it wrote.
    func write(
        _ contentId: String, keys: [String: Data], licenceDuration: Duration,
        playbackDuration: Duration
    ) throws -> OfflineLicence {
        let now = Self.nowMs()
        let licenceExpiresAtMs = Self.deadline(now, licenceDuration)
        let playbackExpiresAtMs = Self.deadline(now, playbackDuration)
        try locked { database in
            try execute(database, "BEGIN IMMEDIATE")
            do {
                try run(
                    database, "DELETE FROM \(Self.keysTable) WHERE content_id = ?",
                    [.text(contentId)])
                for (identifier, key) in keys {
                    try run(
                        database, "INSERT INTO \(Self.keysTable) VALUES (?, ?, ?)",
                        [.text(contentId), .text(identifier), .blob(key)])
                }
                try run(
                    database, "INSERT OR REPLACE INTO \(Self.licencesTable) VALUES (?, ?, ?)",
                    [
                        .text(contentId), .integer(licenceExpiresAtMs),
                        .integer(playbackExpiresAtMs),
                    ])
                try execute(database, "COMMIT")
            } catch {
                try? execute(database, "ROLLBACK")
                throw error
            }
        }
        return OfflineLicence(
            contentId: contentId, licenceExpiresAtMs: licenceExpiresAtMs,
            playbackExpiresAtMs: playbackExpiresAtMs, keys: keys)
    }

    /// The licence held for `contentId`, or nil — nil too for one owed a release or whose release
    /// has begun, unless `awaitingRelease` asks for it: its keys are gone from the device or going,
    /// and a licence that read as playable then is the defect the Kotlin `contentIdsAwaitingRelease`
    /// exists to prevent.
    func read(_ contentId: String, awaitingRelease: Bool = false) throws -> OfflineLicence? {
        try locked { database in
            if !awaitingRelease,
                try !query(
                    database,
                    "SELECT 1 FROM \(Self.releasesTable) WHERE content_id = ? UNION "
                        + "SELECT 1 FROM \(Self.owedTable) WHERE content_id = ?",
                    [.text(contentId), .text(contentId)], { _ in () }
                ).isEmpty
            {
                return nil
            }
            let deadlines = try query(
                database,
                "SELECT licence_expires_at_ms, playback_expires_at_ms FROM \(Self.licencesTable) "
                    + "WHERE content_id = ?", [.text(contentId)]
            ) { row in (sqlite3_column_int64(row, 0), sqlite3_column_int64(row, 1)) }
            guard let (licenceExpiresAtMs, playbackExpiresAtMs) = deadlines.first else {
                return nil
            }
            let keys = try query(
                database,
                "SELECT key_identifier, persistable_key FROM \(Self.keysTable) WHERE content_id = ?",
                [.text(contentId)]
            ) { row in (Self.text(row, 0), Self.blob(row, 1)) }
            return OfflineLicence(
                contentId: contentId, licenceExpiresAtMs: licenceExpiresAtMs,
                playbackExpiresAtMs: playbackExpiresAtMs,
                keys: Dictionary(keys, uniquingKeysWith: { first, _ in first }))
        }
    }

    /// Every content id a licence is held for and not owed a release or being released.
    func contentIds() throws -> Set<String> {
        try locked { database in
            Set(
                try query(
                    database,
                    "SELECT content_id FROM \(Self.licencesTable) WHERE content_id NOT IN "
                        + "(SELECT content_id FROM \(Self.releasesTable) UNION "
                        + "SELECT content_id FROM \(Self.owedTable))", []
                ) { row in Self.text(row, 0) })
        }
    }

    /// Every content id owed a release, or whose release has begun, that has not yet reached the
    /// server.
    func awaitingRelease() throws -> Set<String> {
        try locked { database in
            Set(
                try query(
                    database,
                    "SELECT content_id FROM \(Self.releasesTable) UNION "
                        + "SELECT content_id FROM \(Self.owedTable)", []
                ) { row in Self.text(row, 0) })
        }
    }

    /// Marks `contentId`'s licence as owed a release before any key is deleted — a download store's
    /// removal (#425) — and answers whether there was a licence to owe.
    func owe(_ contentId: String) throws -> Bool {
        try locked { database in
            guard
                try !query(
                    database, "SELECT 1 FROM \(Self.licencesTable) WHERE content_id = ?",
                    [.text(contentId)], { _ in () }
                ).isEmpty
            else { return false }
            try run(
                database, "INSERT OR IGNORE INTO \(Self.owedTable) VALUES (?)",
                [.text(contentId)])
            return true
        }
    }

    /// The device's proof that `keyIdentifier`'s key for `contentId` was deleted, if it was.
    func proofOfDeletion(_ contentId: String, _ keyIdentifier: String) throws -> Data? {
        try locked { database in
            try query(
                database,
                "SELECT proof FROM \(Self.releasesTable) WHERE content_id = ? AND key_identifier = ?",
                [.text(contentId), .text(keyIdentifier)]
            ) { row in Self.blob(row, 0) }.first
        }
    }

    /// Records the device's proof of a deletion before the server is told of it, so a release the
    /// server refused is retried with the same proof rather than by deleting a key already gone.
    func recordProofOfDeletion(_ contentId: String, _ keyIdentifier: String, _ proof: Data) throws {
        try locked { database in
            try run(
                database, "INSERT OR REPLACE INTO \(Self.releasesTable) VALUES (?, ?, ?)",
                [.text(contentId), .text(keyIdentifier), .blob(proof)])
        }
    }

    /// Forgets `contentId`'s licence, its keys, any proof of their deletion and any release owed.
    func remove(_ contentId: String) throws {
        try locked { database in
            try execute(database, "BEGIN IMMEDIATE")
            do {
                for table in [
                    Self.keysTable, Self.licencesTable, Self.releasesTable, Self.owedTable,
                ] {
                    try run(
                        database, "DELETE FROM \(table) WHERE content_id = ?", [.text(contentId)])
                }
                try execute(database, "COMMIT")
            } catch {
                try? execute(database, "ROLLBACK")
                throw error
            }
        }
    }

    /// Closes the connection; the next use opens it again. The directory is not touched.
    func close() {
        lock.withLock {
            if let database { sqlite3_close_v2(database) }
            database = nil
        }
    }

    // MARK: SQLite

    /// Runs `work` on the open connection, opening it — and creating the file and its tables — on
    /// first use, as the Kotlin index does, so a store nobody reads or writes creates no file.
    private func locked<T>(_ work: (OpaquePointer) throws -> T) throws -> T {
        try lock.withLock {
            if let database { return try work(database) }
            var opened: OpaquePointer?
            // NOMUTEX: `lock` serialises every use of this connection, so SQLite's own is not needed.
            let status = sqlite3_open_v2(
                file.path, &opened,
                SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX,
                nil)
            guard status == SQLITE_OK, let opened else {
                let error = Self.failure(opened, status)
                if let opened { sqlite3_close_v2(opened) }
                throw error
            }
            do {
                try execute(opened, "PRAGMA temp_store = MEMORY")
                // The schema's version, so a later one can tell which file it is opening; read by
                // nothing yet, there having been only one.
                try execute(opened, "PRAGMA user_version = \(Self.schemaVersion)")
                try execute(
                    opened,
                    "CREATE TABLE IF NOT EXISTS \(Self.licencesTable) ("
                        + "content_id TEXT PRIMARY KEY NOT NULL, "
                        + "licence_expires_at_ms INTEGER NOT NULL, "
                        + "playback_expires_at_ms INTEGER NOT NULL)")
                // A table of its own rather than a column of that one: content keyed apart — its
                // audio and video under two keys — holds two persistable keys and one licence.
                try execute(
                    opened,
                    "CREATE TABLE IF NOT EXISTS \(Self.keysTable) ("
                        + "content_id TEXT NOT NULL, key_identifier TEXT NOT NULL, "
                        + "persistable_key BLOB NOT NULL, "
                        + "PRIMARY KEY (content_id, key_identifier))")
                // A release under way, by key: the device's proof of each deletion, kept until the
                // server has heard of it — the Kotlin releases table, by key rather than key set.
                try execute(
                    opened,
                    "CREATE TABLE IF NOT EXISTS \(Self.releasesTable) ("
                        + "content_id TEXT NOT NULL, key_identifier TEXT NOT NULL, "
                        + "proof BLOB NOT NULL, PRIMARY KEY (content_id, key_identifier))")
                // A release owed and not yet begun: a download removed (#425), its licence given to
                // no player from that moment though no key is deleted yet. A table of its own, so a
                // file the #423 schema made gains it as it opens, with nothing to migrate.
                try execute(
                    opened,
                    "CREATE TABLE IF NOT EXISTS \(Self.owedTable) ("
                        + "content_id TEXT PRIMARY KEY NOT NULL)")
            } catch {
                sqlite3_close_v2(opened)
                throw error
            }
            database = opened
            return try work(opened)
        }
    }

    private enum Value {
        case text(String)
        case integer(Int64)
        case blob(Data)
    }

    private func execute(_ database: OpaquePointer, _ sql: String) throws {
        let status = sqlite3_exec(database, sql, nil, nil, nil)
        guard status == SQLITE_OK else { throw Self.failure(database, status) }
    }

    private func run(_ database: OpaquePointer, _ sql: String, _ values: [Value]) throws {
        _ = try query(database, sql, values) { _ in () }
    }

    private func query<Row>(
        _ database: OpaquePointer, _ sql: String, _ values: [Value],
        _ read: (OpaquePointer) -> Row
    ) throws -> [Row] {
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
            case .integer(let integer):
                bound = sqlite3_bind_int64(statement, index, integer)
            case .blob(let data):
                bound = data.withUnsafeBytes { bytes in
                    // A zero-length blob still has to bind as a blob rather than as NULL, which a
                    // nil base address would bind it as; `zeroblob` is SQLite's own for that.
                    guard let base = bytes.baseAddress else {
                        return sqlite3_bind_zeroblob(statement, index, 0)
                    }
                    return sqlite3_bind_blob(
                        statement, index, base, Int32(bytes.count), Self.transient)
                }
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

    private static func blob(_ row: OpaquePointer, _ column: Int32) -> Data {
        let count = Int(sqlite3_column_bytes(row, column))
        guard let bytes = sqlite3_column_blob(row, column), count > 0 else { return Data() }
        return Data(bytes: bytes, count: count)
    }

    private static func failure(_ database: OpaquePointer?, _ status: Int32)
        -> OfflineLicenceStoreError
    {
        let message = database.flatMap { sqlite3_errmsg($0) }.map { String(cString: $0) }
        return .database(
            code: Int(status), message: message ?? String(cString: sqlite3_errstr(status)))
    }

    /// ref: https://www.sqlite.org/c3ref/c_static.html — `SQLITE_TRANSIENT`, SQLite copying a bound
    /// value before the call returns, which a Swift string's or `Data`'s temporary buffer needs. The
    /// C macro is a cast of -1 that Swift does not import.
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    // MARK: Deadlines

    /// Wall-clock milliseconds since 1970, the clock every deadline here is on.
    static func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }

    /// When `duration` runs out, counted from `nowMs` — the Kotlin `deadline`: zero or less is a
    /// deadline now, which has run out; anything else is clamped at ``maxDurationMs`` first, so that
    /// no implausibly large answer overflows into a deadline in the past, the one direction this must
    /// never round.
    static func deadline(_ nowMs: Int64, _ duration: Duration) -> Int64 {
        let (seconds, attoseconds) = duration.components
        guard seconds > 0 || (seconds == 0 && attoseconds > 0) else { return nowMs }
        guard seconds < maxDurationMs / 1000 else { return nowMs + maxDurationMs }
        return nowMs + seconds * 1000 + attoseconds / 1_000_000_000_000_000
    }

    /// A hundred years, which no licence outlives and whose milliseconds fit in an `Int64` — the
    /// Kotlin `MAX_SECONDS`, in milliseconds.
    static let maxDurationMs: Int64 = 100 * 365 * 24 * 60 * 60 * 1000

    /// The Kotlin `INDEX_FILE_NAME`, so one name means the store's file on both platforms.
    static let fileName = "offline-licences.db"
    private static let licencesTable = "offline_licences"
    private static let keysTable = "offline_licence_keys"
    private static let releasesTable = "offline_licence_releases"
    private static let owedTable = "offline_licence_owed"
    private static let schemaVersion = 1
}
