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
import Foundation
import SuperPlayerDRM
import os

/// What stands where `AVContentKeySession` would — the one `package` protocol, ``FairPlayKeySession``
/// (ADR-0021 rule 5), because the simulator has no FairPlay: no item there ever reaches a key
/// request, so a test makes one itself with ``request(_:renewal:composes:)``, as AVFoundation would
/// on a device, and reads how the exchange answered it.
///
/// The key request it composes is the identifier's bytes behind a prefix, so a test can tell which
/// request a `POST` carried and which licence answered it. Nothing here is FairPlay, which is the
/// point: what a device must show is `docs/testing.md`'s *FairPlay on iOS*.
@MainActor
final class StandInKeySession: FairPlayKeySession {

    /// Every asset handed over, in order.
    private(set) var recipients: [AVURLAsset] = []

    /// Whether the player let go of the session.
    private(set) var isInvalidated = false

    private var handler: (@Sendable (FairPlayKeyRequest) -> Void)?

    func start(_ handler: @escaping @Sendable (FairPlayKeyRequest) -> Void) {
        self.handler = handler
    }

    func addRecipient(_ asset: AVURLAsset) {
        recipients.append(asset)
    }

    func invalidate() {
        isInvalidated = true
        handler = nil
    }

    /// The key request a device composes for `keyIdentifier`: `spc:` and the identifier.
    nonisolated static func serverPlaybackContext(for keyIdentifier: String) -> Data {
        Data("spc:\(keyIdentifier)".utf8)
    }

    /// Asks for `keyIdentifier`'s key — or to renew it — as the session's delegate would be asked,
    /// returning the record of how it is answered. `composes: false` is a device whose protection
    /// stack refuses to compose the key request.
    func request(_ keyIdentifier: String, renewal: Bool = false, composes: Bool = true)
        -> KeyRequestRecord
    {
        let record = KeyRequestRecord()
        guard let handler else {
            record.answer(.failed("the session was never started, or was invalidated"))
            return record
        }
        handler(
            FairPlayKeyRequest(
                keyIdentifier: keyIdentifier, isRenewal: renewal,
                makeServerPlaybackContext: { certificate, contentIdentifier in
                    record.composed(certificate: certificate, contentIdentifier: contentIdentifier)
                    guard composes else { throw ProtectionStackRefused() }
                    return Self.serverPlaybackContext(for: keyIdentifier)
                },
                respond: { licence in record.answer(.licence(licence)) },
                fail: { error in record.answer(.failed(String(describing: error))) }))
        return record
    }

    /// What a device's protection stack throws when it will not compose a key request.
    struct ProtectionStackRefused: Error {}
}

/// How one key request was composed and answered, readable from any thread.
final class KeyRequestRecord: Sendable {

    enum Answer: Equatable {
        case licence(Data)
        case failed(String)
    }

    private struct State {
        var certificate: Data?
        var contentIdentifier: Data?
        var answers: [Answer] = []
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    /// The certificate the request was composed with, once it was.
    var certificate: Data? { state.withLock { $0.certificate } }

    /// The content identifier the request was composed with, once it was.
    var contentIdentifier: Data? { state.withLock { $0.contentIdentifier } }

    /// Every answer given, which a correct exchange makes exactly one of.
    var answers: [Answer] { state.withLock { $0.answers } }

    func composed(certificate: Data, contentIdentifier: Data) {
        state.withLock {
            $0.certificate = certificate
            $0.contentIdentifier = contentIdentifier
        }
    }

    func answer(_ answer: Answer) {
        state.withLock { $0.answers.append(answer) }
    }
}
