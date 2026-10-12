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

import SuperPlayerCore

extension FairPlayDrm: DownloadProtecting {

    /// A download store's exchanges over a key session of the store's own — the Kotlin
    /// `DownloadDrmExtension.downloadSessions` (#425). Nil for a ``LicenceStore`` that is not an
    /// ``OfflineLicenceStore``: no other store has an index this product can write a key into.
    ///
    /// The key session is the one a player of this protection would be given, made by the same
    /// factory, and nothing is ever a recipient of it: a download asks for its keys by identifier,
    /// persistable, which is the route Apple documents for a key with no asset loading.
    @MainActor
    func downloadLicences(
        over licences: any StoredLicences,
        budget: @escaping @MainActor @Sendable () -> RetryBudget,
        headerProvider: (any HeaderProvider)?
    ) -> (any DownloadLicensing)? {
        guard let store = licences as? OfflineLicenceStore else { return nil }
        let host = DownloadLicenceHost(budget: budget, headerProvider: headerProvider)
        let protection = FairPlayProtection(
            keySession: keySession(),
            exchange: LicenceExchange(config: config, provider: headerProvider), host: host)
        return FairPlayDownloadLicences(
            host: host, protection: protection,
            exchange: OfflineLicenceExchange(index: store.index, protection: protection))
    }
}

/// One download store's FairPlay exchanges: ``OfflineLicenceExchange``'s acquisition and release,
/// over a protection no player owns.
@MainActor
final class FairPlayDownloadLicences: DownloadLicensing {

    /// Held here because ``FairPlayProtection`` holds its host weakly, a player owning its own.
    private let host: DownloadLicenceHost
    private let protection: FairPlayProtection
    private let exchange: OfflineLicenceExchange

    init(
        host: DownloadLicenceHost, protection: FairPlayProtection, exchange: OfflineLicenceExchange
    ) {
        self.host = host
        self.protection = protection
        self.exchange = exchange
    }

    func acquire(
        _ contentId: String, keyIdentifiers: [String], licenceDuration: Duration,
        playbackDuration: Duration
    ) async throws {
        _ = try await exchange.persist(
            contentId, keyIdentifiers, licenceDuration: licenceDuration,
            playbackDuration: playbackDuration)
    }

    func release(_ contentId: String, tellingServer: Bool) async throws {
        try await exchange.release(contentId, tellingServer: tellingServer)
    }

    func close() {
        protection.release()
    }
}

/// The ``ProtectionHost`` a download store's protection answers to: the store's budget and
/// credential, and nobody to tell of an acquisition or a failure — a download's licence is not one
/// a viewing waited for, as the Kotlin `OfflineLicenseHelper` reports to an event dispatcher of its
/// own, and a refusal reaches the store as the error its `acquire` throws rather than through a
/// ladder.
@MainActor
final class DownloadLicenceHost: ProtectionHost {

    private let budget: @MainActor @Sendable () -> RetryBudget
    let headerProvider: (any HeaderProvider)?

    init(
        budget: @escaping @MainActor @Sendable () -> RetryBudget,
        headerProvider: (any HeaderProvider)?
    ) {
        self.budget = budget
        self.headerProvider = headerProvider
    }

    var licenceBudget: RetryBudget { budget() }

    func licenceAcquisitionEnded(durationMs: Int64, outcome: LicenceOutcome) {}

    func licenceFailed(_ error: any Error) {}
}
