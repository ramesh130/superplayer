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

    /// Composes the key request — the SPC — from the application certificate and the content
    /// identifier, or throws where the device's protection stack will not.
    package let makeServerPlaybackContext:
        @Sendable (_ certificate: Data, _ contentIdentifier: Data) async throws -> Data

    /// Hands the session the licence — the CKC — the server issued for this request.
    package let respond: @Sendable (_ licence: Data) -> Void

    /// Tells the session no licence is coming for this request, and why.
    package let fail: @Sendable (_ error: any Error) -> Void

    package init(
        keyIdentifier: String, isRenewal: Bool,
        makeServerPlaybackContext: @escaping @Sendable (Data, Data) async throws -> Data,
        respond: @escaping @Sendable (Data) -> Void, fail: @escaping @Sendable (any Error) -> Void
    ) {
        self.keyIdentifier = keyIdentifier
        self.isRenewal = isRenewal
        self.makeServerPlaybackContext = makeServerPlaybackContext
        self.respond = respond
        self.fail = fail
    }
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

    func contentKeySession(
        _ session: AVContentKeySession, didProvide keyRequest: AVContentKeyRequest
    ) {
        deliver(keyRequest, isRenewal: false)
    }

    func contentKeySession(
        _ session: AVContentKeySession,
        didProvideRenewingContentKeyRequest keyRequest: AVContentKeyRequest
    ) {
        deliver(keyRequest, isRenewal: true)
    }

    private func deliver(_ keyRequest: AVContentKeyRequest, isRenewal: Bool) {
        let request = Box(keyRequest)
        guard let handler = handler.withLock({ $0 }) else {
            return keyRequest.processContentKeyResponseError(CancellationError())
        }
        // ref: AVContentKeyRequest.h — for FairPlay Streaming the identifier is the URI of the
        // `EXT-X-KEY` that asked, an `skd://` string.
        let identifier =
            (keyRequest.identifier as? String) ?? (keyRequest.identifier as? URL)?.absoluteString
            ?? ""
        handler(
            FairPlayKeyRequest(
                keyIdentifier: identifier, isRenewal: isRenewal,
                makeServerPlaybackContext: { certificate, contentIdentifier in
                    try await request.serverPlaybackContext(certificate, contentIdentifier)
                },
                respond: { licence in
                    request.value.processContentKeyResponse(
                        AVContentKeyResponse(fairPlayStreamingKeyResponseData: licence))
                },
                fail: { error in request.value.processContentKeyResponseError(error) }))
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
