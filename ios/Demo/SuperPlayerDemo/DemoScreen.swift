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

import SwiftUI

/// The demo's screens, and the one place a new one is added: a case, its title and summary, and its
/// view in ``destination``. The root lists every case, and `-SPDemoScreen <rawValue>` opens one.
enum DemoScreen: String, CaseIterable, Identifiable, Hashable {

    /// Public HLS through a `SuperPlayer` on SwiftUI's stock `VideoPlayer`, with no adapter.
    case playback

    /// The same stream through a pass-through `FrameOutput` on the library's `PlayerView`.
    case frameOutput

    /// Two hundred rows over a `PlayerPool` and a `PreloadCoordinator`, one row playing at a time.
    case feed

    var id: String { rawValue }

    var title: String {
        switch self {
        case .playback: "Playback"
        case .frameOutput: "Frame output"
        case .feed: "Feed"
        }
    }

    var summary: String {
        switch self {
        case .playback: "Apple's HLS example on the stock VideoPlayer"
        case .frameOutput: "The same stream through a pass-through frame output"
        case .feed: "200 rows over the pool and the preload coordinator"
        }
    }

    @MainActor @ViewBuilder
    var destination: some View {
        switch self {
        case .playback: PlaybackScreen(stream: .bipbopAdvanced)
        case .frameOutput: FrameOutputScreen(stream: .bipbopAdvanced)
        case .feed: FeedScreen()
        }
    }

    /// The screen `-SPDemoScreen` names, or nil to open on the list. A launch argument of that form
    /// lands in `UserDefaults`' argument domain, so reading the key is reading the argument.
    static func fromLaunchArguments(_ defaults: UserDefaults = .standard) -> DemoScreen? {
        defaults.string(forKey: "SPDemoScreen").flatMap(DemoScreen.init(rawValue:))
    }
}
