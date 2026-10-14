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

/// Where offline licences are kept: a store the consumer opened in a directory they named (ADR-0012
/// rule 8) — the Kotlin `LicenceStore`.
///
/// **A consumer has no reason to implement one**, and one it wrote holds nothing a download store
/// can read: what a download store asks of it is the `package` ``StoredLicences``. What makes one is `SuperPlayerDRM`'s
/// `OfflineLicences.store(directory:)`; what takes one is `SuperPlayerOffline`'s download store, in
/// its `DownloadProtection` (#425). It is core's for ADR-0013 rule 1's reason: the download product depends on core
/// alone, so an app that downloads only clear content carries nothing that knows what FairPlay is,
/// and a type both products can name has to be core's. The Kotlin type is an abstract class with an
/// internal constructor; here it is a public marker over the `package` protocol ``StoredLicences``,
/// ``PlaybackDrm``'s shape over ``ContentProtecting`` — a Swift class open to `SuperPlayerDRM`'s
/// subclass would be open to a consumer's too.
public protocol LicenceStore: AnyObject, Sendable {}

/// The half of a ``LicenceStore`` a download store is built from (ADR-0013 rule 4) — the Kotlin
/// `StoredLicences`, `package` where that is `internal`, because a persisted key is nothing a consumer
/// can use.
///
/// A reading and the owed-release half the download licences brought (#425), as the Kotlin
/// interface's arrived with #245. **The writing half is not here, and that is FairPlay's**: a
/// Widevine licence is a key-set id the device keeps, which the Kotlin store is handed to write,
/// while a FairPlay persistable key is bytes only the key session that made them may answer a
/// request with — so writing one is part of ``DownloadLicensing``'s `acquire`, implemented by the
/// product that owns both that session and this store's index. What a download store does itself is
/// the bookkeeping a removal needs at once.
package protocol StoredLicences: LicenceStore {

    /// The licence held for `contentId` and not owed a release, or nil where there is none.
    func standing(of contentId: String) throws -> LicenceStanding?

    /// Marks `contentId`'s licence as owed a release, so no player is given it from now on, and
    /// answers whether the store held one — the Kotlin `awaitRelease`. Persisted, so a release a
    /// process did not finish is one the next process still owes; synchronous, because a removal
    /// must leave no moment in which the download is gone and its licence still playable.
    func awaitRelease(_ contentId: String) throws -> Bool

    /// Every content id whose licence is owed a release — marked by ``awaitRelease(_:)``, or begun
    /// and refused — the Kotlin `awaitingRelease`, by content id rather than by key set because a
    /// FairPlay licence is held per content id.
    func awaitingRelease() throws -> Set<String>
}

/// What core asks a ``PlaybackDrm`` for when a download store is configured with it: one store's
/// licence exchanges — the Kotlin `DownloadDrmExtension`, which hands Media3's `OfflineLicenseHelper`
/// a session graph where this hands over the exchanges themselves, because on Apple platforms the
/// exchange is the library's own (`docs/ios-plan.md` §3.6).
///
/// `package`, ``ContentProtecting``'s shape: `SuperPlayerDRM`'s value conforms, so the download
/// product names no FairPlay type and depends on core alone (ADR-0013 rule 1).
package protocol DownloadProtecting: PlaybackDrm {

    /// The exchanges of one download store, writing into `licences` — or nil where `licences` is not
    /// a store this protection can write (a consumer's own ``LicenceStore``), in which case protected
    /// content downloads with no licence, as on a store configured with none. Asked once, the first
    /// time a store needs it, on the main actor.
    ///
    /// - Parameters:
    ///   - budget: the store's ``RetryPolicy/licence`` now, re-read at every ask, as a player's
    ///     ``ProtectionHost/licenceBudget`` is.
    ///   - headerProvider: the store's credential, from its resilience, or nil.
    @MainActor func downloadLicences(
        over licences: any StoredLicences,
        budget: @escaping @MainActor @Sendable () -> RetryBudget,
        headerProvider: (any HeaderProvider)?
    ) -> (any DownloadLicensing)?
}

/// One download store's licence exchanges: a protected download's persistable keys acquired before
/// its first media byte, and released once it is removed (ADR-0013 rule 13) — the round trips of the
/// Kotlin `DownloadLicences`.
@MainActor
package protocol DownloadLicensing: AnyObject {

    /// Acquires a persistable key for each of `keyIdentifiers` and stores them all as `contentId`'s
    /// licence, with the two durations the key server's operator grants a download — or stores
    /// nothing. Throws core's ``LicenceExchangeError`` where the server or the device would not
    /// produce a key.
    func acquire(
        _ contentId: String, keyIdentifiers: [String], licenceDuration: Duration,
        playbackDuration: Duration
    ) async throws

    /// Releases `contentId`'s licence: each key deleted from the device and the proof of its
    /// deletion recorded, which needs no network — then, where `tellingServer`, each proof sent to
    /// the key server and the licence forgotten. Without it the licence stays owed, and a later call
    /// sends the proofs already recorded rather than deleting anything twice. Throws what the device
    /// or the server ended on.
    func release(_ contentId: String, tellingServer: Bool) async throws

    /// Ends the exchanges: nothing more is asked, and nothing in flight is answered.
    func close()
}

/// A stored licence as a download store reads it — the Kotlin `LicenceStanding`: both durations left
/// and what follows from them, decided by the store that holds the licence so that two products
/// reading one licence cannot disagree about when it is dead or due.
package struct LicenceStanding: Sendable, Hashable {
    package let playbackDurationRemainingMs: Int64
    package let licenceDurationRemainingMs: Int64
    package let isExpired: Bool
    package let renewalDue: Bool

    package init(
        playbackDurationRemainingMs: Int64, licenceDurationRemainingMs: Int64, isExpired: Bool,
        renewalDue: Bool
    ) {
        self.playbackDurationRemainingMs = playbackDurationRemainingMs
        self.licenceDurationRemainingMs = licenceDurationRemainingMs
        self.isExpired = isExpired
        self.renewalDue = renewalDue
    }
}

/// The player was configured to play from a licence already on the device, and that licence has run
/// out — so nothing was played rather than a licence being fetched behind the app's back (ADR-0012
/// rule 9) — the Kotlin `OfflineLicenceExpiredException`.
///
/// Raised by `SuperPlayerDRM` and carried out of it as evidence, the shape ``LicenceExchangeError``
/// has and the reason this type is core's: the product that found the fact keeps no taxonomy of its
/// own (ADR-0012 rule 5). Its domain says exactly what happened, so `SuperPlayerResilience` names it
/// `FailureClass.Drm.licenceExpired` without reading these fields, which are here for the bug report
/// rather than for the classification. It reaches a consumer as the
/// ``SuperPlayerError/underlyingError`` of the typed error the session ended on.
///
/// **It should be rare, because the fact was readable first**: `OfflineLicenceStore.licence(for:)`
/// answers both durations with no device, no player and no network, which is what lets an app tell a
/// viewer to go online and refresh a download instead of showing them a playback error. This is what
/// happens when a player is configured with an expired licence anyway.
public struct OfflineLicenceExpiredError: Error, Sendable, CustomStringConvertible, CustomNSError {

    /// The content whose stored licence has expired: a ``MediaRequest/contentId``.
    public let contentId: String

    /// Milliseconds of viewing window that were left, zero whenever the window is what ran out. Two
    /// fields rather than one for the Kotlin reason: an unwatched rental that expired and a viewing
    /// window that closed are different things to tell a viewer.
    public let playbackDurationRemainingMs: Int64

    /// Milliseconds of entitlement that were left, zero whenever the entitlement is what ran out.
    public let licenceDurationRemainingMs: Int64

    package init(
        contentId: String, playbackDurationRemainingMs: Int64, licenceDurationRemainingMs: Int64
    ) {
        self.contentId = contentId
        self.playbackDurationRemainingMs = playbackDurationRemainingMs
        self.licenceDurationRemainingMs = licenceDurationRemainingMs
    }

    public var description: String {
        "the offline licence for \(contentId) has expired: \(licenceDurationRemainingMs)ms of licence "
            + "and \(playbackDurationRemainingMs)ms of playback remaining"
    }

    /// The domain this error bridges to `NSError` under, which is how the classifier reads it.
    public static let errorDomain = "SuperPlayerOfflineLicenceErrorDomain"

    public var errorCode: Int { 1 }

    public var errorUserInfo: [String: Any] { [NSLocalizedDescriptionKey: description] }
}
