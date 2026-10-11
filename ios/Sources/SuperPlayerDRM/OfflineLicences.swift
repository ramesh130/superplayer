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
import SuperPlayerCore

/// Where licences for offline playback are kept: a directory the consumer opened and named — the
/// Kotlin `OfflineLicences` (#423).
///
/// An offline licence is state that outlives the player that acquired it, which is exactly what
/// ADR-0006 rule 2 forbids the library to choose a home for. So the app names the directory, the
/// library writes nothing outside it, and a consumer who never asks for offline playback has no
/// store at all (ADR-0012 rule 8).
///
/// ```swift
/// let store = try OfflineLicences.store(directory: licencesDirectory)
///
/// // Once, online: a player that has loaded the content is what composes the requests.
/// let player = SuperPlayer(configuration: configuration)   // drm = Drm.fairPlay(config)
/// player.setMediaRequest(request)
/// try await store.over(player).acquire(
///     request.contentId,
///     licenceDuration: .seconds(30 * 86_400), playbackDuration: .seconds(48 * 3_600))
///
/// // Later, with no network: read first, then play with what was read.
/// switch try store.licence(for: contentId) {
/// case nil: promptToDownload()
/// case let licence? where licence.isExpired: promptToGoOnlineAndRefresh()   // PRD.md §3.2
/// case let licence?: configuration.drm = Drm.fairPlay(config, licence: licence)
/// }
/// ```
public enum OfflineLicences {

    /// Opens the store in `directory`, creating it if it is not there.
    ///
    /// Everything the store writes lands inside `directory` and nothing outside it, so deleting the
    /// directory — or the app — takes the licences with it (ADR-0012 rule 8). One store per
    /// directory: the index is an ordinary database file, and two stores over one directory are two
    /// writers of it. Nothing is written until the store is first read or written.
    ///
    /// - Throws: ``OfflineLicenceStoreError/notADirectory(_:)`` where `directory` is a file, or the
    ///   file system's error where it cannot be made.
    public static func store(directory: URL) throws -> OfflineLicenceStore {
        OfflineLicenceStore(index: try OfflineLicenceIndex(directory: directory))
    }
}

/// What ``OfflineLicences/store(directory:)`` opened: the licences this device holds, by
/// `contentId` — the Kotlin `OfflineLicenceStore`.
///
/// **Reading is free and writing is not.** ``licence(for:)`` and ``contentIds()`` answer from the
/// index alone — no device, no player, no network — which is what makes ADR-0012 rule 9's "before
/// playback rather than after it fails" reachable from a list screen. The verbs that change
/// something are ``OfflineLicenceExchange``'s, because each is a round trip to the licence server and
/// that server is reached over a *player's* exchange (ADR-0012 rule 2).
///
/// It is core's ``LicenceStore``, so the download store (#424, #425) can take it while depending on
/// core alone, as the Kotlin `OfflineLicenceStore` extends core's `LicenceStore` (ADR-0013 rule 1).
public final class OfflineLicenceStore: LicenceStore, StoredLicences {

    private let index: OfflineLicenceIndex

    init(index: OfflineLicenceIndex) {
        self.index = index
    }

    /// The licence this store holds for `contentId`, or nil where it holds none — the Kotlin
    /// `licenceFor`.
    ///
    /// Both durations, as ADR-0012 rule 9 requires, counted down from the deadlines written when the
    /// licence was acquired or last renewed. A consumer reads this *before* configuring a player:
    /// that is the difference between `PRD.md` §3.2's "go online and refresh this download", which a
    /// viewer can act on, and "playback error", which they cannot.
    public func licence(for contentId: String) throws -> OfflineLicence? {
        try index.read(contentId)
    }

    /// Every content id this store holds a licence for, expired ones included and ones being
    /// released not.
    public func contentIds() throws -> Set<String> {
        try index.contentIds()
    }

    /// Every content id whose release began and has not reached the server — a release the server
    /// refused, say — the Kotlin `contentIdsAwaitingRelease`. Their keys are already gone from the
    /// device, so none is answered by ``licence(for:)``; ``OfflineLicenceExchange/release(_:)`` on
    /// one tells the server again with the proof the device gave the first time.
    public func contentIdsAwaitingRelease() throws -> Set<String> {
        try index.awaitingRelease()
    }

    /// The store's three verbs that talk to the licence server, performed over `player`'s protection.
    ///
    /// Over a player rather than on its own, which is a decision: a licence exchange travels with
    /// the player's credential (the one ``HeaderProvider``) and the player's ``RetryPolicy/licence``
    /// budget, both properties of a player, and a store that opened a `URLSession` of its own would
    /// be a second way to reach the same server answering to neither. It is also where FairPlay
    /// itself is: a persistable key is composed by the player's `AVContentKeySession`.
    ///
    /// - Throws: ``OfflineLicenceStoreError/notFairPlay`` where `player` was not configured with
    ///   `Drm.fairPlay`.
    @MainActor
    public func over(_ player: SuperPlayer) throws -> OfflineLicenceExchange {
        guard let protection = player.protection as? FairPlayProtection else {
            throw OfflineLicenceStoreError.notFairPlay
        }
        return OfflineLicenceExchange(index: index, protection: protection)
    }

    /// Closes the index; the next read or write opens it again. The directory is the consumer's and
    /// is not touched.
    public func close() {
        index.close()
    }

    package func standing(of contentId: String) throws -> LicenceStanding? {
        try index.read(contentId).map {
            LicenceStanding(
                playbackDurationRemainingMs: $0.playbackDurationRemainingMs,
                licenceDurationRemainingMs: $0.licenceDurationRemainingMs, isExpired: $0.isExpired,
                renewalDue: $0.renewalDue)
        }
    }
}

/// One licence this device holds, and how long it has left — the two answers ADR-0012 rule 9
/// requires to be readable before playback — the Kotlin `OfflineLicence`.
///
/// **The two durations are two facts and not one**: a *licence* duration — how long the entitlement
/// lives, FairPlay's storage or lease duration — and a *playback* duration, the viewing window that
/// begins when the content is first played, FairPlay's rental duration. An unwatched rental that
/// expired and a viewing window that closed are different things to tell a viewer.
///
/// **Where they come from is iOS's own**, and the one place this type departs from the Kotlin one.
/// Widevine's device reports both (`MediaDrm.queryKeyStatus`); FairPlay reports neither — they are
/// inside the licence the key server issued, which no client API reads — so here they are what the
/// key server's operator granted, stated by the app at ``OfflineLicenceExchange/acquire(_:licenceDuration:playbackDuration:)``
/// as its own backend told it. The device enforces the licence's real terms whatever is stated; a
/// stated term longer than the real one reads as playable and then fails as the device refuses it.
///
/// Counted from absolute deadlines at the moment a property is read, so a licence read an hour ago
/// still answers truthfully now.
public struct OfflineLicence: Sendable, CustomStringConvertible {

    /// The content this licence is for: a ``MediaRequest/contentId``.
    public let contentId: String

    /// When the entitlement runs out, in wall-clock milliseconds since 1970.
    let licenceExpiresAtMs: Int64

    /// When the viewing window closes, in wall-clock milliseconds since 1970.
    let playbackExpiresAtMs: Int64

    /// The persistable keys the device answered with, by the key identifier the content declared.
    /// Internal for the Kotlin `keySetId`'s reason: they are useless anywhere but this device, and
    /// the only thing anyone may do with them is hand them back to it.
    let keys: [String: Data]

    init(
        contentId: String, licenceExpiresAtMs: Int64, playbackExpiresAtMs: Int64,
        keys: [String: Data]
    ) {
        self.contentId = contentId
        self.licenceExpiresAtMs = licenceExpiresAtMs
        self.playbackExpiresAtMs = playbackExpiresAtMs
        self.keys = keys
    }

    /// Milliseconds of viewing window left, or zero where it has run out.
    public var playbackDurationRemainingMs: Int64 {
        max(0, playbackExpiresAtMs - OfflineLicenceIndex.nowMs())
    }

    /// Milliseconds of entitlement left, or zero where it has run out.
    public var licenceDurationRemainingMs: Int64 {
        max(0, licenceExpiresAtMs - OfflineLicenceIndex.nowMs())
    }

    /// Whether either duration has run out, so that playing this would fail rather than play.
    /// Either and not both, for the Kotlin reason: a closed viewing window stops playback exactly as
    /// a dead entitlement does, and "can I play this now" wants one answer.
    public var isExpired: Bool {
        playbackDurationRemainingMs <= 0 || licenceDurationRemainingMs <= 0
    }

    /// Whether this licence should be renewed now — `PRD.md` §3.2's "before the remaining duration
    /// reaches zero", as a reading rather than as a timer.
    ///
    /// **The library schedules nothing** (ADR-0012 rule 10): when to run background work is a
    /// `BGTaskScheduler` request chosen on the consumer's behalf in the same way ADR-0006 rule 2
    /// forbids choosing storage. So the store reports what is due and the consumer's own job calls
    /// ``OfflineLicenceExchange/renew(_:licenceDuration:playbackDuration:)``.
    ///
    /// It reads the *licence* duration and not the playback one, for the Kotlin reason: renewing
    /// asks for the entitlement again, and a viewing window that has begun is not a thing a renewal
    /// resets. The threshold is ``renewalDueWithinMs``.
    public var renewalDue: Bool {
        licenceDurationRemainingMs <= Self.renewalDueWithinMs
    }

    /// Twenty-four hours — the Kotlin `RENEWAL_DUE_WITHIN_MS`, for its reason, which is the
    /// consumer's schedule rather than the licence's: a job that runs once a day is the coarsest
    /// thing an app plausibly uses for this (on iOS a `BGAppRefreshTask` the system may run less
    /// often still), and a threshold shorter than the gap between two of its runs would let a
    /// licence die between them.
    public static let renewalDueWithinMs: Int64 = 24 * 60 * 60 * 1000

    /// Nothing about the keys, for the reason `docs/telemetry-schema.md` redacts a licence load.
    public var description: String {
        "OfflineLicence(\(contentId), playbackLeftMs=\(playbackDurationRemainingMs), "
            + "licenceLeftMs=\(licenceDurationRemainingMs))"
    }
}

/// The verbs of `PRD.md` Part 4's offline lifecycle that talk to the licence server, over one
/// player's protection — the Kotlin `OfflineLicenceExchange`. The fourth verb, playing offline, is
/// `Drm.fairPlay(_:licence:)`, a configuration rather than a call.
///
/// Each is `async` and suspends on its round trips rather than blocking, so unlike the Kotlin verbs
/// they may be awaited from the main actor. None of them is playback: the requests they make are
/// the key session's persistable ones, measured by no collector and offered to no ladder, and the
/// content the player is playing is untouched. All of them work on a player configured with an
/// *expired* stored licence too, whose playback was refused: renewing the dead download on the
/// player already in hand is the likeliest next thing an app does.
@MainActor
public final class OfflineLicenceExchange {

    private let index: OfflineLicenceIndex
    private let protection: FairPlayProtection

    init(index: OfflineLicenceIndex, protection: FairPlayProtection) {
        self.index = index
        self.protection = protection
    }

    /// Acquires a persistable key for every key the content this player has loaded asked for, and
    /// stores them under `contentId` with the two durations the key server granted.
    ///
    /// The keys come from the content: only it declares which keys it is encrypted under, and
    /// nothing the store could have been told stands in for that — so the player must have loaded
    /// the content and its session asked for a key first. Each key is asked for again, persistable,
    /// through `processContentKeyRequest(withIdentifier:)` and the player's exchange: a download is a
    /// second exchange with the server and not a copy of the streaming keys, which cannot be stored.
    ///
    /// - Parameters:
    ///   - licenceDuration: how long the entitlement lives, as the key server's operator granted it.
    ///   - playbackDuration: the viewing window once playback begins, likewise.
    /// - Throws: ``OfflineLicenceStoreError/noKeyRequested`` before the content asked for any key;
    ///   core's ``LicenceExchangeError`` where the server or the device did not produce one, in which
    ///   case nothing is stored; ``OfflineLicenceStoreError/database(code:message:)``.
    public func acquire(
        _ contentId: String, licenceDuration: Duration, playbackDuration: Duration
    ) async throws -> OfflineLicence {
        let identifiers = protection.contentKeyIdentifiers
        guard !identifiers.isEmpty else { throw OfflineLicenceStoreError.noKeyRequested }
        return try await persist(
            contentId, identifiers, licenceDuration: licenceDuration,
            playbackDuration: playbackDuration)
    }

    /// Asks the key server for fresh persistable keys for `contentId` and replaces the stored ones.
    ///
    /// No content is loaded for this and none is needed: the key identifiers are the stored
    /// licence's, which is why it is a background job's operation rather than a player's.
    ///
    /// - Throws: ``OfflineLicenceStoreError/noLicenceHeld(contentId:)``, or what
    ///   ``acquire(_:licenceDuration:playbackDuration:)`` throws; a failed renewal leaves the stored
    ///   licence as it was.
    public func renew(
        _ contentId: String, licenceDuration: Duration, playbackDuration: Duration
    ) async throws -> OfflineLicence {
        let stored = try held(contentId)
        return try await persist(
            contentId, stored.keys.keys.sorted(), licenceDuration: licenceDuration,
            playbackDuration: playbackDuration)
    }

    /// Releases `contentId`'s licence — at the device and the server, then here — so a deleted
    /// download leaves no entitlement behind.
    ///
    /// The server is told first and the index cleared second, for the Kotlin reason: a release the
    /// server never heard of leaves a licence counted against the viewer's device allowance, which is
    /// the defect this verb exists to prevent. FairPlay adds an order of its own: the server hears of
    /// a deletion only through the device's proof of it, so each key is deleted from the device first
    /// (`invalidatePersistableContentKey`) and its proof **recorded** before it is `POST`ed. A refusal
    /// then propagates with the licence in ``OfflineLicenceStore/contentIdsAwaitingRelease()`` and
    /// no longer in ``OfflineLicenceStore/licence(for:)`` — its keys are gone — and calling this again
    /// re-sends the recorded proof rather than deleting a key twice.
    ///
    /// - Throws: ``OfflineLicenceStoreError/noLicenceHeld(contentId:)``, core's
    ///   ``LicenceExchangeError``, or ``OfflineLicenceStoreError/database(code:message:)``.
    public func release(_ contentId: String) async throws {
        let stored = try held(contentId, awaitingRelease: true)
        for identifier in stored.keys.keys.sorted() {
            let proof: Data
            if let recorded = try index.proofOfDeletion(contentId, identifier) {
                proof = recorded
            } else if let key = stored.keys[identifier] {
                proof = try await protection.invalidate(key: key)
                try index.recordProofOfDeletion(contentId, identifier, proof)
            } else {
                continue
            }
            try await protection.tellServer(proofOfDeletion: proof)
        }
        try index.remove(contentId)
    }

    private func held(_ contentId: String, awaitingRelease: Bool = false) throws -> OfflineLicence {
        guard let stored = try index.read(contentId, awaitingRelease: awaitingRelease) else {
            throw OfflineLicenceStoreError.noLicenceHeld(contentId: contentId)
        }
        return stored
    }

    /// One persistable key per identifier, in order, then all of them written at once — so a
    /// failure part-way stores nothing rather than half a licence.
    private func persist(
        _ contentId: String, _ identifiers: [String], licenceDuration: Duration,
        playbackDuration: Duration
    ) async throws -> OfflineLicence {
        var keys: [String: Data] = [:]
        for identifier in identifiers {
            keys[identifier] = try await protection.persistableKey(for: identifier)
        }
        return try index.write(
            contentId, keys: keys, licenceDuration: licenceDuration,
            playbackDuration: playbackDuration)
    }
}

/// Why the offline store could not do what it was asked — the Kotlin store's `IllegalStateException`
/// and `IllegalArgumentException` cases, each named. A licence server's refusal is core's
/// ``LicenceExchangeError`` and not one of these.
public enum OfflineLicenceStoreError: Error, Sendable, Equatable {
    /// The directory named is a file.
    case notADirectory(URL)
    /// The player was not configured with `Drm.fairPlay`, so it has no key session to ask.
    case notFairPlay
    /// The player's content has asked for no key yet: set the content and let it load first.
    case noKeyRequested
    /// The store holds no licence for this content.
    case noLicenceHeld(contentId: String)
    /// SQLite refused, with its result code and message.
    case database(code: Int, message: String)
}
