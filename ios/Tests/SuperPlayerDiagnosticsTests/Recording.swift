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
import SuperPlayerCore
import SuperPlayerTelemetry
import SuperPlayerTestkit
import XCTest

/// `ios/Tests/Fixtures/golden/`, beside the other fixtures — the telemetry tests' directory, so one
/// `ios/check --update-golden` writes both products' goldens.
let goldens = GoldenFile(
    directory: URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Fixtures/golden"))

/// Whether an `AVPlayerItem` has posted that it played to its end, readable from the main actor.
@MainActor
final class EndObserver {
    private(set) var hasEnded = false
    // Written once, in `init`, and read once more in `deinit`, which is not on the main actor.
    nonisolated(unsafe) private var token: NSObjectProtocol?

    init(_ item: AVPlayerItem) {
        token = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification, object: item, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.hasEnded = true } }
    }

    deinit { token.map(NotificationCenter.default.removeObserver) }
}

/// What a subscription to ``SuperPlayer/errors`` heard, on the main actor.
@MainActor
final class ErrorRecord {
    var errors: [any Error] = []
}

extension PlaybackHarness {

    /// The recorder's trace once `SessionEnded` has reached it and, on iOS 18, once every resource
    /// `origin` served has a finished `load` line — the telemetry tests' wait, for their reason: the
    /// metric stream delivers after a request ends and telemetry delivery is asynchronous, so both
    /// are waited for rather than assumed.
    @MainActor
    func settledTrace(
        _ recorder: SessionTraceRecorder, origin: LoopbackOrigin, bound: Duration
    ) async throws -> SessionTrace {
        try await until("the session's end reached the trace", timeout: bound) {
            recorder.trace().lines.contains { $0.contains(" telemetry SessionEnded ") }
        }
        if #available(iOS 18, tvOS 18, *) {
            let resources = Set(origin.networkRequests().map(\.path)).count
            try await until("every request reached the trace", timeout: bound) {
                recorder.trace().lines.filter {
                    $0.contains(" load completed ") || $0.contains(" load error ")
                }.count >= resources
            }
        }
        return recorder.trace()
    }
}
