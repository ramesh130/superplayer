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
import os

/// The key session a FairPlay player's items are recipients of — **the one `package` protocol this
/// product stands a platform call behind** (ADR-0021 rule 5), because the simulator has no FairPlay:
/// an `AVContentKeySession` there never composes a key request, so everything around one — the
/// certificate, the `POST`, the budget, the credential repair, the telemetry — could otherwise be
/// tested only on a device.
///
/// ``SystemFairPlayKeySession`` is the one implementation that touches AVFoundation, and nothing
/// under `check` runs a line of it; `SuperPlayerDRMTests`' stand-in hands over requests a test made.
/// What a device must show is listed in `docs/testing.md`'s *FairPlay on iOS*.
@MainActor
package protocol FairPlayKeySession: AnyObject {

    /// Delivers every key request from now on to `handler`, on whatever thread the session's
    /// delegate runs on. Called once, as the player is made, before any recipient is added.
    func start(_ handler: @escaping @Sendable (FairPlayKeyRequest) -> Void)

    /// `asset`'s keys are this session's to answer for — `AVContentKeySession.addContentKeyRecipient`,
    /// which has to precede the asset loading anything for a key request to reach the session.
    func addRecipient(_ asset: AVURLAsset)

    /// The player is released: requests that arrive from now on are refused at once, so nothing is
    /// left waiting on an answer nobody will send.
    func invalidate()

    /// Every key request from now on is delivered **persistable**: the player plays from a stored
    /// licence (`Drm.fairPlay(_:licence:)`, #423), and a stored key answers only a persistable
    /// request. Called once, as the player is made, before any recipient is added.
    func persistEveryKey()

    /// Asks the session for `keyIdentifier`'s key now, with no asset loading, and delivers the
    /// request persistable — what the offline store's acquisition and renewal are made of — or calls
    /// `refused` where the device will not make it persistable, so no request will be delivered.
    func requestPersistableKey(
        _ keyIdentifier: String, refused: @escaping @Sendable (any Error) -> Void)

    /// Deletes the persistable key `key` from the device and answers the proof of its deletion: the
    /// bytes a key server is sent to hear of a release.
    func invalidatePersistableKey(_ key: Data) async throws -> Data
}

/// One key request, as the stand-in and the real session both hand it over: what it names and the
/// three things that can be done with it. Closures rather than a second protocol, so that the seam is
/// one protocol and a test makes a request by writing three functions.
package struct FairPlayKeyRequest: Sendable {

    /// The key's identifier as the playlist declared it: an `EXT-X-KEY`'s `skd://` URI.
    package let keyIdentifier: String

    /// Whether the session asked to renew a key it already holds — AVFoundation's
    /// `didProvideRenewingContentKeyRequest` — rather than for a key.
    package let isRenewal: Bool

    /// Whether this is an `AVPersistableContentKeyRequest`: one ``persistableKey`` can turn a licence
    /// into a key the device keeps, and one a stored key answers.
    package let isPersistable: Bool

    /// Composes the key request — the SPC — from the application certificate and the content
    /// identifier, or throws where the device's protection stack will not.
    package let makeServerPlaybackContext:
        @Sendable (_ certificate: Data, _ contentIdentifier: Data) async throws -> Data

    /// Hands the session the licence — the CKC — the server issued for this request.
    package let respond: @Sendable (_ licence: Data) -> Void

    /// Tells the session no licence is coming for this request, and why.
    package let fail: @Sendable (_ error: any Error) -> Void

    /// Turns the licence the server issued for a persistable request into the key the device keeps
    /// — `persistableContentKey(fromKeyVendorResponse:options:)` — or throws where the request is not
    /// persistable or the device will not. A persistable request is then answered with that key,
    /// through ``respond``.
    package let persistableKey: @Sendable (_ licence: Data) throws -> Data

    package init(
        keyIdentifier: String, isRenewal: Bool, isPersistable: Bool = false,
        makeServerPlaybackContext: @escaping @Sendable (Data, Data) async throws -> Data,
        respond: @escaping @Sendable (Data) -> Void, fail: @escaping @Sendable (any Error) -> Void,
        persistableKey: @escaping @Sendable (Data) throws -> Data = { _ in throw NotPersistable() }
    ) {
        self.keyIdentifier = keyIdentifier
        self.isRenewal = isRenewal
        self.isPersistable = isPersistable
        self.makeServerPlaybackContext = makeServerPlaybackContext
        self.respond = respond
        self.fail = fail
        self.persistableKey = persistableKey
    }

    /// What ``persistableKey`` throws on a request that is not persistable.
    package struct NotPersistable: Error { package init() {} }
}

/// The real key session: an `AVContentKeySession` for FairPlay Streaming whose delegate turns each
/// request it is given into a ``FairPlayKeyRequest``.
///
/// **Nothing under `check` runs it** (ADR-0021 rule 5): on the simulator a FairPlay item never
/// reaches a key request. It is written against Apple's documented delegate contract, and i6's
/// device run is what plays protected content through it.
///
/// ref: https://developer.apple.com/documentation/avfoundation/avcontentkeysession
/// ref: https://developer.apple.com/documentation/avfoundation/avcontentkeysessiondelegate
///
/// The conformance to ``FairPlayKeySession`` is in an extension rather than on the declaration, so
/// that the protocol's main-actor isolation is not inferred onto the class and its delegate methods,
/// which AVFoundation calls on the delegate queue.
final class SystemFairPlayKeySession: NSObject, AVContentKeySessionDelegate, @unchecked Sendable {
    // @unchecked: the session and the delegate queue are fixed at construction, and the one mutable
    // value, the handler, is behind the lock.

    private let session = AVContentKeySession(keySystem: .fairPlayStreaming)

    /// The delegate's own serial queue: AVFoundation calls the delegate there, never on the main
    /// thread, and a key request is answered from wherever its exchange finished.
    private let delegateQueue = DispatchQueue(label: "SuperPlayerDRM.ContentKeySession")

    private let handler = OSAllocatedUnfairLock<(@Sendable (FairPlayKeyRequest) -> Void)?>(
        initialState: nil)

    /// Which requests are turned persistable before they are delivered: every one, on a player that
    /// plays from a stored licence, or the identifiers the offline store asked for.
    private let persisting = OSAllocatedUnfairLock(initialState: Persisting())

    private struct Persisting {
        var everyKey = false
        /// The identifiers asked for, each with whom to tell if it cannot be made persistable.
        var identifiers: [String: @Sendable (any Error) -> Void] = [:]
    }

    override init() {
        super.init()
        session.setDelegate(self, queue: delegateQueue)
    }

    func start(_ handler: @escaping @Sendable (FairPlayKeyRequest) -> Void) {
        self.handler.withLock { $0 = handler }
    }

    func addRecipient(_ asset: AVURLAsset) {
        session.addContentKeyRecipient(asset)
    }

    func invalidate() {
        handler.withLock { $0 = nil }
    }

    func persistEveryKey() {
        persisting.withLock { $0.everyKey = true }
    }

    /// ref: AVContentKeySession.h — `processContentKeyRequestWithIdentifier:initializationData:options:`
    /// is how an app obtains a key with no asset loading, Apple's route to a key for a download; the
    /// request it produces is turned persistable as it arrives.
    ///
    /// Renewal takes this route too, and not `renewExpiringResponseData(for:)`, which renews through
    /// the `AVContentKeyRequest` a *playing* session was handed: renewing a stored licence is a
    /// background job's operation (ADR-0012 rule 10) and holds no such request, while a fresh
    /// persistable key for the same identifier is exactly what replaces the stored one.
    ///
    /// tvOS has no persistable keys — `AVPersistableContentKeyRequest` and its methods are not in its
    /// SDK — so there every request is refused at once, typed, and the offline store's verbs fail
    /// rather than wait.
    func requestPersistableKey(
        _ keyIdentifier: String, refused: @escaping @Sendable (any Error) -> Void
    ) {
        #if os(tvOS)
            refused(PersistableKeysUnavailable())
        #else
            persisting.withLock { $0.identifiers[keyIdentifier] = refused }
            session.processContentKeyRequest(
                withIdentifier: keyIdentifier, initializationData: nil, options: nil)
        #endif
    }

    /// What a persistable-key operation fails with on tvOS, whose SDK has none.
    struct PersistableKeysUnavailable: Error {}

    /// ref: AVContentKeySession.h — `invalidatePersistableContentKey:options:completionHandler:`
    /// deletes the key and answers a secure proof of the deletion, for the key server.
    func invalidatePersistableKey(_ key: Data) async throws -> Data {
        #if os(tvOS)
            throw PersistableKeysUnavailable()
        #else
            try await invalidate(key)
        #endif
    }

    #if !os(tvOS)
        private func invalidate(_ key: Data) async throws -> Data {
            try await withCheckedThrowingContinuation { continuation in
                // Version 1, for the reason `serverPlaybackContext` names it.
                session.invalidatePersistableContentKey(key, options: [.protocolVersions: [1]]) {
                    proof, error in
                    if let proof {
                        continuation.resume(returning: proof)
                    } else {
                        continuation.resume(throwing: error ?? NoProofOfDeletion())
                    }
                }
            }
        }
    #endif

    /// The session answered an invalidation with neither a proof nor an error.
    private struct NoProofOfDeletion: Error {}

    func contentKeySession(
        _ session: AVContentKeySession, didProvide keyRequest: AVContentKeyRequest
    ) {
        let identifier = Self.identifier(of: keyRequest)
        let (everyKey, refused) = persisting.withLock { ($0.everyKey, $0.identifiers[identifier]) }
        #if os(tvOS)
            _ = (everyKey, refused)
            deliver(keyRequest, isRenewal: false)
        #else
            guard everyKey || refused != nil else { return deliver(keyRequest, isRenewal: false) }
            // ref: AVContentKeyRequest.h — the session answers this with a second delegate call carrying
            // an `AVPersistableContentKeyRequest`, and that is the one delivered.
            do {
                try keyRequest.respondByRequestingPersistableContentKeyRequestAndReturnError()
            } catch {
                keyRequest.processContentKeyResponseError(error)
                persisting.withLock { _ = $0.identifiers.removeValue(forKey: identifier) }
                refused?(error)
            }
        #endif
    }

    #if !os(tvOS)
        func contentKeySession(
            _ session: AVContentKeySession, didProvide keyRequest: AVPersistableContentKeyRequest
        ) {
            persisting.withLock {
                _ = $0.identifiers.removeValue(forKey: Self.identifier(of: keyRequest))
            }
            deliver(keyRequest, persistable: PersistableBox(keyRequest))
        }
    #endif

    func contentKeySession(
        _ session: AVContentKeySession,
        didProvideRenewingContentKeyRequest keyRequest: AVContentKeyRequest
    ) {
        deliver(keyRequest, isRenewal: true)
    }

    private func deliver(
        _ keyRequest: AVContentKeyRequest, isRenewal: Bool = false,
        persistable persistableRequest: PersistableBox? = nil
    ) {
        let request = Box(keyRequest)
        guard let handler = handler.withLock({ $0 }) else {
            return keyRequest.processContentKeyResponseError(CancellationError())
        }
        handler(
            FairPlayKeyRequest(
                keyIdentifier: Self.identifier(of: keyRequest), isRenewal: isRenewal,
                isPersistable: persistableRequest != nil,
                makeServerPlaybackContext: { certificate, contentIdentifier in
                    try await request.serverPlaybackContext(certificate, contentIdentifier)
                },
                // ref: AVContentKeyResponse.h — a persistable request is answered with its
                // persistable key through the same FairPlay response a licence is.
                respond: { licence in
                    request.value.processContentKeyResponse(
                        AVContentKeyResponse(fairPlayStreamingKeyResponseData: licence))
                },
                fail: { error in request.value.processContentKeyResponseError(error) },
                persistableKey: { licence in
                    guard let persistableRequest else { throw FairPlayKeyRequest.NotPersistable() }
                    return try persistableRequest.persistableKey(licence)
                }))
    }

    /// ref: AVContentKeyRequest.h — for FairPlay Streaming the identifier is the URI of the
    /// `EXT-X-KEY` that asked, an `skd://` string, or the identifier the app asked with.
    private static func identifier(of keyRequest: AVContentKeyRequest) -> String {
        (keyRequest.identifier as? String) ?? (keyRequest.identifier as? URL)?.absoluteString ?? ""
    }

    /// An `AVPersistableContentKeyRequest` carried into a closure, `@unchecked` for ``Box``'s
    /// reason; on tvOS, whose SDK has none, a type nothing makes.
    private struct PersistableBox: @unchecked Sendable {
        #if os(tvOS)
            func persistableKey(_ licence: Data) throws -> Data {
                throw PersistableKeysUnavailable()
            }
        #else
            let value: AVPersistableContentKeyRequest
            init(_ value: AVPersistableContentKeyRequest) { self.value = value }
            func persistableKey(_ licence: Data) throws -> Data {
                try value.persistableContentKey(fromKeyVendorResponse: licence, options: nil)
            }
        #endif
    }

    /// An `AVContentKeyRequest` carried across the closures above. `@unchecked` because the SDK does
    /// not mark the class `Sendable`, while its own contract is that a request is answered from any
    /// thread, once — which is what the exchange does.
    private struct Box: @unchecked Sendable {
        let value: AVContentKeyRequest
        init(_ value: AVContentKeyRequest) { self.value = value }

        func serverPlaybackContext(_ certificate: Data, _ contentIdentifier: Data) async throws
            -> Data
        {
            try await withCheckedThrowingContinuation { continuation in
                value.makeStreamingContentKeyRequestData(
                    forApp: certificate, contentIdentifier: contentIdentifier,
                    // ref: AVContentKeyRequest.h — version 1 is the FairPlay Streaming protocol
                    // every key server speaks; naming it is what the documentation asks of a client.
                    options: [AVContentKeyRequestProtocolVersionsKey: [1]]
                ) { spc, error in
                    if let spc {
                        continuation.resume(returning: spc)
                    } else {
                        continuation.resume(throwing: error ?? CancellationError())
                    }
                }
            }
        }
    }
}

extension SystemFairPlayKeySession: FairPlayKeySession {}
