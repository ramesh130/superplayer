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

    init(keySession: any FairPlayKeySession, exchange: LicenceExchange, host: any ProtectionHost) {
        self.keySession = keySession
        self.exchange = exchange
        self.host = host
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
        keySession.addRecipient(asset)
    }

    func release() {
        guard !isReleased else { return }
        isReleased = true
        keySession.invalidate()
        for task in inFlight.values { task.cancel() }
        inFlight = [:]
        Task { [exchange] in await exchange.cancel() }
    }

    private func handle(_ request: FairPlayKeyRequest) {
        guard !isReleased else { return request.fail(CancellationError()) }
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
