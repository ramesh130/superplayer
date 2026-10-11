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

import AVFoundation
import SuperPlayerCore

/// One player's FairPlay protection: its key session, every asset it plays a recipient, and each key
/// request answered through the player's ``LicenceExchange`` — core's ``ProtectionSession``, made by
/// ``FairPlayDrm`` once per player.
///
/// ## What is measured, and what a rotation is
///
/// A request is an **acquisition** — one ``TelemetryEvent/LicenceAcquisitionEnded``, opened as the
/// session asks and closed as the licence is handed over or refused — when the content playing holds
/// no key yet. Anything after the first key of the content is a **rotation**, and so is a request to
/// renew a key already held: each is one licence request at the server, counted there, and emits no
/// event, for the schema's reason — a rotation renews inside a session already open and is not a
/// second licence the viewing waited for (`docs/telemetry-schema.md`, *Licence acquisition*). Two
/// keys asked for before either is answered — content whose audio and video are keyed apart, at
/// start-up — are two acquisitions, as two Kotlin DRM sessions are; a key asked for after the first
/// was answered is read as a rotation, because `AVContentKeySession` says no more than an identifier
/// and nothing tells a later separately licensed key from a rotated one. Which
/// content is playing moves with every asset handed over, a rung's re-adoption included, because a
/// new asset asks for its keys again and that is a licence the viewer waits for.
///
/// ## What a failure does
///
/// The request is told — so the item fails on a device as AVFoundation fails it — and the player is
/// told, which offers it to the ladder as the item's own failure (``ProtectionHost/licenceFailed(_:)``).
/// A failed **renewal** is not offered: the key it would have renewed is still in force, which is
/// the Kotlin `KeyRotationTest`'s finding about `DefaultDrmSession` kept on purpose.
///
/// ## The offline store's half (#423)
///
/// A player configured with an ``OfflineLicence`` answers every key from it and asks nobody, its
/// session delivering every request persistable; one acquisition answered so is measured as
/// `servedFromOfflineStore`. And on any FairPlay player the store's verbs ask the key session for a
/// *persistable* request by identifier, which is told apart from the content's own requests by the
/// verb waiting on it and answered through the same exchange. One window is left open on purpose:
/// a content request for an identifier the store is asking for at that moment is answered by the
/// store's exchange and measured by nobody, because the two requests carry nothing else to tell them
/// apart; it costs one acquisition span, never a key.
@MainActor
final class FairPlayProtection: ProtectionSession {

    private let keySession: any FairPlayKeySession
    private let exchange: LicenceExchange

    /// The player this protects. Weak: the player owns its protection.
    private weak var host: (any ProtectionHost)?

    /// The assets already handed to the key session, so one handed over twice — a warm item adopted,
    /// say — is added once. Weak, because the key session holds what it needs.
    private var recipients: [WeakAsset] = []

    /// Whether the content playing now has been given a key: what tells an acquisition from a
    /// rotation.
    private var contentKeyed = false

    /// Which content a request was asked for: moved on with every asset handed over, so a licence
    /// for the content before it, answered late, does not mark the new content keyed and hide its
    /// acquisition.
    private var contentGeneration = 0

    /// The exchanges in flight, cancelled when the player is released.
    private var inFlight: [Int: Task<Void, Never>] = [:]
    private var nextRequest = 0

    private var isReleased = false

    /// The stored licence this player plays from, or nil for one that asks the server (#423).
    private let offline: OfflineLicence?

    /// The keys the content playing now has asked for, in the order it asked: what the offline
    /// store's acquisition persists, since only the content declares which keys it is encrypted
    /// under (the Kotlin `protectedFormat`).
    private(set) var contentKeyIdentifiers: [String] = []

    /// The offline store's verbs waiting on the persistable request they asked the key session for,
    /// by key identifier.
    private var awaitingPersistable: [String: [CheckedContinuation<Data, any Error>]] = [:]

    init(
        keySession: any FairPlayKeySession, exchange: LicenceExchange, host: any ProtectionHost,
        offline: OfflineLicence? = nil
    ) {
        self.keySession = keySession
        self.exchange = exchange
        self.host = host
        self.offline = offline
        if offline != nil { keySession.persistEveryKey() }
        keySession.start { [weak self] request in
            Task { @MainActor in
                guard let self else { return request.fail(CancellationError()) }
                self.handle(request)
            }
        }
    }

    func addRecipient(_ asset: AVURLAsset) {
        guard !isReleased else { return }
        recipients.removeAll { $0.asset == nil }
        guard !recipients.contains(where: { $0.asset === asset }) else { return }
        recipients.append(WeakAsset(asset: asset))
        contentKeyed = false
        contentGeneration += 1
        contentKeyIdentifiers = []
        keySession.addRecipient(asset)
    }

    func release() {
        guard !isReleased else { return }
        isReleased = true
        keySession.invalidate()
        for task in inFlight.values { task.cancel() }
        inFlight = [:]
        let waiting = awaitingPersistable.values.joined()
        awaitingPersistable = [:]
        for continuation in waiting { continuation.resume(throwing: CancellationError()) }
        Task { [exchange] in await exchange.cancel() }
    }

    // MARK: The offline store's half (#423)

    /// A persistable key for `keyIdentifier`, acquired from the configured server over this player's
    /// exchange — its budget, its credential — and handed back once the key session has been
    /// answered with it. Throws the ``LicenceExchangeError`` the exchange ended on — at
    /// ``LicenceExchangeError/Stage/keyRequest`` where the device would not make the request
    /// persistable — or `CancellationError` once the player is released.
    ///
    /// Two verbs asking for one identifier at once share one request: the key session is asked only
    /// by the first, so no second persistable request arrives with nobody waiting on it to be read as
    /// the content's own.
    func persistableKey(for keyIdentifier: String) async throws -> Data {
        guard !isReleased else { throw CancellationError() }
        return try await withCheckedThrowingContinuation { continuation in
            let first = awaitingPersistable[keyIdentifier] == nil
            awaitingPersistable[keyIdentifier, default: []].append(continuation)
            guard first else { return }
            keySession.requestPersistableKey(keyIdentifier) { [weak self] error in
                Task { @MainActor in self?.persistableRequestRefused(keyIdentifier, error) }
            }
        }
    }

    /// Deletes `key` from the device and answers the device's proof of the deletion. Throws a
    /// ``LicenceExchangeError`` at ``LicenceExchangeError/Stage/keyRequest`` where the device would
    /// not.
    func invalidate(key: Data) async throws -> Data {
        guard !isReleased else { throw CancellationError() }
        do {
            return try await keySession.invalidatePersistableKey(key)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw LicenceExchangeError(
                stage: .keyRequest, status: nil, attempts: 0, underlyingError: error as NSError)
        }
    }

    /// Tells the configured server of a deletion, `proof` `POST`ed on the licence budget. Throws the
    /// ``LicenceExchangeError`` the server's answer ended on.
    func tellServer(proofOfDeletion proof: Data) async throws {
        guard !isReleased else { throw CancellationError() }
        let budget: @MainActor @Sendable () -> RetryBudget? = { [weak self] in
            self?.host?.licenceBudget
        }
        if case .failure(let error) = try await exchange.release(proof: proof, budget: budget) {
            throw error
        }
    }

    /// The device would not turn the request for `keyIdentifier` persistable, so none is coming:
    /// every verb waiting on it is told, rather than left waiting for the player's release.
    private func persistableRequestRefused(_ keyIdentifier: String, _ error: any Error) {
        let waiting = awaitingPersistable.removeValue(forKey: keyIdentifier) ?? []
        let refusal = LicenceExchangeError(
            stage: .keyRequest, status: nil, attempts: 0, underlyingError: error as NSError)
        for continuation in waiting { continuation.resume(throwing: refusal) }
    }

    /// Answers a persistable request the store asked for: the licence from the server, made into
    /// the key the device keeps, which answers the request and every verb waiting on it. Nothing is
    /// measured — the store's exchange is not a licence the viewing waited for, as the Kotlin
    /// `OfflineLicenseHelper` reports to an event dispatcher of its own — and nothing is offered to
    /// the ladder, because no playback asked.
    private func persist(_ request: FairPlayKeyRequest) {
        let waiting = awaitingPersistable.removeValue(forKey: request.keyIdentifier) ?? []
        let id = nextRequest
        nextRequest += 1
        inFlight[id] = Task { [exchange, weak self] in
            let budget: @MainActor @Sendable () -> RetryBudget? = { [weak self] in
                self?.host?.licenceBudget
            }
            let result: Result<Data, any Error>
            // `try?` loses nothing: the exchange throws only `CancellationError`, and every failure
            // with evidence arrives as its `.failure`.
            switch try? await exchange.licence(for: request, budget: budget) {
            case nil: result = .failure(CancellationError())
            case .failure(let error): result = .failure(error)
            case .success(let licence):
                do {
                    let key = try request.persistableKey(licence)
                    request.respond(key)
                    result = .success(key)
                } catch {
                    result = .failure(
                        LicenceExchangeError(
                            stage: .keyRequest, status: nil, attempts: 0,
                            underlyingError: error as NSError))
                }
            }
            if case .failure(let error) = result { request.fail(error) }
            self?.inFlight[id] = nil
            for continuation in waiting { continuation.resume(with: result) }
        }
    }

    /// Answers a key request from the stored licence and asks nobody — the Kotlin `MODE_PLAYBACK`,
    /// whose sessions restore keys rather than ask for them. An expired licence is refused with
    /// core's ``OfflineLicenceExpiredError``, read at the moment the key is asked for; a key the
    /// licence does not hold — a licence stored for other content — is refused as a licence that did
    /// not arrive, with no request made (`attempts` zero), since asking the server is what this
    /// player was configured not to do. A refusal is not measured: no acquisition was attempted.
    private func answerFromStore(_ request: FairPlayKeyRequest, _ licence: OfflineLicence) {
        let refusal: (any Error)?
        if licence.isExpired {
            refusal = OfflineLicenceExpiredError(
                contentId: licence.contentId,
                playbackDurationRemainingMs: licence.playbackDurationRemainingMs,
                licenceDurationRemainingMs: licence.licenceDurationRemainingMs)
        } else if let key = licence.keys[request.keyIdentifier] {
            let isAcquisition = !request.isRenewal && !contentKeyed
            request.respond(key)
            if !request.isRenewal { contentKeyed = true }
            if isAcquisition {
                host?.licenceAcquisitionEnded(durationMs: 0, outcome: .servedFromOfflineStore)
            }
            refusal = nil
        } else {
            refusal = LicenceExchangeError(
                stage: .licence, status: nil, attempts: 0, underlyingError: nil)
        }
        guard let refusal else { return }
        request.fail(refusal)
        if !request.isRenewal { host?.licenceFailed(refusal) }
    }

    // MARK: Playback

    private func handle(_ request: FairPlayKeyRequest) {
        guard !isReleased else { return request.fail(CancellationError()) }
        if request.isPersistable, awaitingPersistable[request.keyIdentifier] != nil {
            return persist(request)
        }
        if !request.isRenewal, !contentKeyIdentifiers.contains(request.keyIdentifier) {
            contentKeyIdentifiers.append(request.keyIdentifier)
        }
        if let offline { return answerFromStore(request, offline) }
        let isAcquisition = !request.isRenewal && !contentKeyed
        let generation = contentGeneration
        let startedMs = Self.now()
        let id = nextRequest
        nextRequest += 1
        inFlight[id] = Task { [exchange, weak self] in
            let budget: @MainActor @Sendable () -> RetryBudget? = { [weak self] in
                self?.host?.licenceBudget
            }
            let outcome = try? await exchange.licence(for: request, budget: budget)
            self?.finish(
                request, id: id, outcome: outcome, acquisition: isAcquisition,
                generation: generation, startedMs: startedMs)
        }
    }

    /// Answers `request` with `outcome` — nil for an exchange cancelled with the player — and tells
    /// the player what it measures and what it failed on, the span before the failure so a
    /// `LicenceAcquisitionEnded` precedes the session's end.
    private func finish(
        _ request: FairPlayKeyRequest, id: Int, outcome: Result<Data, LicenceExchangeError>?,
        acquisition: Bool, generation: Int, startedMs: Int64
    ) {
        inFlight[id] = nil
        guard !isReleased, let outcome else { return request.fail(CancellationError()) }
        let durationMs = Self.now() - startedMs
        switch outcome {
        case .success(let licence):
            request.respond(licence)
            if !request.isRenewal, generation == contentGeneration { contentKeyed = true }
            if acquisition {
                host?.licenceAcquisitionEnded(durationMs: durationMs, outcome: .acquiredFromServer)
            }
        case .failure(let error):
            request.fail(error)
            if acquisition {
                host?.licenceAcquisitionEnded(durationMs: durationMs, outcome: .refused)
            }
            if !request.isRenewal { host?.licenceFailed(error) }
        }
    }

    /// `CLOCK_MONOTONIC` in milliseconds, the clock every duration in the schema is on.
    private static func now() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000)
    }

    private struct WeakAsset {
        weak var asset: AVURLAsset?
    }
}
