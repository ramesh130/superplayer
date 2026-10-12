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
import SuperPlayerDiagnostics
import SuperPlayerTestMedia
import SuperPlayerTestkit

/// How every test here asks a doctor about content: served from a loopback origin of its own
/// (ADR-0021 rule 2) and examined through the public API as a consumer would — the Kotlin tests'
/// `findings(entry)`, with the harness's origin where theirs had `diagnosticEnvironment`.
extension PlaybackHarness {

    /// What a doctor configured with `configuration` says about `entry`, served with the headers
    /// the entry declares, as `HostileManifestCorpusTests` serves it.
    func report(
        of entry: HostileStream, configuration: MediaSourceDoctor.Configuration = .init()
    ) async throws -> DiagnosticReport {
        let origin = try await serve(
            .hostile(entry),
            faults: FaultScript.Builder().addDeclaredResponseHeaders(of: entry).build())
        return await MediaSourceDoctor(configuration: configuration)
            .examine(MediaRequest(contentId: entry.id, sources: [origin.sourceURL]))
    }

    /// What a doctor configured with nothing says about `content`, asked about under `contentId`.
    func report(of content: TestContent, contentId: String = "film/healthy") async throws
        -> DiagnosticReport
    {
        let origin = try await serve(content)
        return await MediaSourceDoctor()
            .examine(MediaRequest(contentId: contentId, sources: [origin.sourceURL]))
    }
}

/// The current time as the corpus takes it, for its token entries: what a CDN would mint now.
let nowEpochSeconds = Int64(Date().timeIntervalSince1970)
