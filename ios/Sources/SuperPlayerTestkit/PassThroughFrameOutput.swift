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

import CoreMedia
import CoreVideo
import Foundation
import SuperPlayerCore
import os

/// A frame output that changes nothing and remembers what it was handed: each frame's presentation
/// time, in the order it arrived, and whether it arrived on the main thread.
///
/// The pass-through ADR-0022 rule 8 puts outside the library: it is testkit's, so it ships in no
/// artifact an app plays with, and it is what a test configures to see that frames reach an output
/// at all and in order (`FrameOutputPlaybackTests`). The demo's pass-through (#407) is the demo's
/// own, for the same reason. Returning the frame it was handed is the whole of passing through
/// (``FrameOutput``).
public final class PassThroughFrameOutput: FrameOutput {

    private let record = OSAllocatedUnfairLock(
        initialState: (times: [CMTime](), onMainThread: 0))

    public init() {}

    public func process(_ frame: CVPixelBuffer, presentationTime: CMTime) -> CVPixelBuffer {
        let onMainThread = Thread.isMainThread
        record.withLock { record in
            record.times.append(presentationTime)
            if onMainThread { record.onMainThread += 1 }
        }
        return frame
    }

    /// The presentation time of every frame handed over so far, in the order it was handed over.
    public var presentationTimes: [CMTime] { record.withLock { $0.times } }

    /// How many of those frames were handed over on the main thread — none, if the output runs
    /// where ``FrameOutput`` says it does.
    public var framesOnMainThread: Int { record.withLock { $0.onMainThread } }
}
