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
import SuperPlayerTestMedia
import SuperPlayerTestkit

/// What the tests playing `.videoHls()` over a shaped link share, written once.
enum VideoPlayback {

    /// What `.videoHls()` costs a second to fetch: one of its media segments over the two seconds
    /// it plays for, read off the generator so a link stated as a fraction of it stays one.
    static let streamBitrateBps: Int64 = {
        let segment = SyntheticVideoHlsStream.files(segmentCount: 2)[
            SyntheticVideoHlsStream.segmentName(1)]!
        return Int64(segment.count) * 8 * 1_000 / SyntheticVideoHlsStream.segmentDurationMs
    }()

    /// The path `.videoHls()` serves its first media segment at.
    static let firstSegmentPath = "/video/\(SyntheticVideoHlsStream.segmentName(0))"

    /// How many times `origin` was asked for `path`.
    static func attempts(at path: String, _ origin: LoopbackOrigin) -> Int {
        origin.networkRequests().filter { $0.path == path }.count
    }
}

/// A lifecycle platform that does nothing: a video item holds the idle timer and `xctest` has no
/// `UIApplication`, and the audio session is left alone.
final class QuietPlatform: LifecyclePlatform {
    func activateAudioSession() {}
    func deactivateAudioSession() {}
    func setIdleTimerDisabled(_ disabled: Bool) {}
}
