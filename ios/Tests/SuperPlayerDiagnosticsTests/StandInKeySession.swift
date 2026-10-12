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

/// What stands where `AVContentKeySession` would, at `SuperPlayerDRM`'s one `package` protocol
/// (ADR-0021 rule 5) — the DRM tests' `StandInKeySession`, cut to what a bundle's redaction test
/// asks of it: one key request, composed as the identifier's bytes behind `spc:`, and its answer.
/// The simulator has no FairPlay (`docs/testing.md`, *FairPlay on iOS*), and a test target cannot
/// reach another's sources, so the few lines are written again rather than published.
@MainActor
final class StandInKeySession: FairPlayKeySession {

    private var handler: (@Sendable (FairPlayKeyRequest) -> Void)?

    /// Whether the player has started the session, which it does as it adopts its first item.
    var isStarted: Bool { handler != nil }

    func start(_ handler: @escaping @Sendable (FairPlayKeyRequest) -> Void) {
        self.handler = handler
    }

    func addRecipient(_ asset: AVURLAsset) {}

    func invalidate() { handler = nil }

    func persistEveryKey() {}

    func requestPersistableKey(
        _ keyIdentifier: String, refused: @escaping @Sendable (any Error) -> Void
    ) {
        refused(FairPlayKeyRequest.NotPersistable())
    }

    func invalidatePersistableKey(_ key: Data) async throws -> Data {
        throw FairPlayKeyRequest.NotPersistable()
    }

    /// The key request a device composes for `keyIdentifier`: `spc:` and the identifier.
    nonisolated static func serverPlaybackContext(for keyIdentifier: String) -> Data {
        Data("spc:\(keyIdentifier)".utf8)
    }

    /// Asks for `keyIdentifier`'s key as the session's delegate would be asked, returning the record
    /// of how it is answered.
    func request(_ keyIdentifier: String) -> KeyRequestRecord {
        let record = KeyRequestRecord()
        handler?(
            FairPlayKeyRequest(
                keyIdentifier: keyIdentifier, isRenewal: false,
                makeServerPlaybackContext: { _, _ in Self.serverPlaybackContext(for: keyIdentifier) },
                respond: { licence in record.answer(.licence(licence)) },
                fail: { error in record.answer(.failed(String(describing: error))) }))
        return record
    }
}

/// How one key request was answered, readable from any thread.
final class KeyRequestRecord: Sendable {

    enum Answer: Equatable, Sendable {
        case licence(Data)
        case failed(String)
    }

    private let state = OSAllocatedUnfairLock(initialState: [Answer]())

    var answers: [Answer] { state.withLock { $0 } }

    func answer(_ answer: Answer) {
        state.withLock { $0.append(answer) }
    }
}
