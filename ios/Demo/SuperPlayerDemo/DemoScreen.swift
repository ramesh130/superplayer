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

    /// `tvLeanback` through `TvOutput` on `AVPlayerViewController`'s system controls — what a tvOS
    /// app opens on.
    case tv

    /// The playback screen with the debug HUD over it: what each row reads, live.
    case debugHud
    #if canImport(SuperPlayerMoQ)
        /// A MoQ broadcast through a `RealtimePlayer` and `MoqFrameSource` — iOS alone, because the
        /// MoQ product's native library carries no tvOS slice.
        case moq
    #endif

    var id: String { rawValue }

    var title: String {
        switch self {
        case .playback: "Playback"
        case .frameOutput: "Frame output"
        case .feed: "Feed"
        case .tv: "Television"
        case .debugHud: "Debug HUD"
        #if canImport(SuperPlayerMoQ)
            case .moq: "MoQ"
        #endif
        }
    }

    var summary: String {
        switch self {
        case .playback: "Apple's HLS example on the stock VideoPlayer"
        case .frameOutput: "The same stream through a pass-through frame output"
        case .feed: "200 rows over the pool and the preload coordinator"
        case .tv: "tvLeanback and the display's criteria on the system controls"
        case .debugHud: "The same stream under the six-row debug overlay"
        #if canImport(SuperPlayerMoQ)
            case .moq: "A live broadcast over Media over QUIC on a RealtimePlayer"
        #endif
        }
    }

    @MainActor @ViewBuilder
    var destination: some View {
        switch self {
        case .playback: PlaybackScreen(stream: .bipbopAdvanced)
        case .frameOutput: FrameOutputScreen(stream: .bipbopAdvanced)
        case .feed: FeedScreen()
        case .tv: TvScreen(stream: .bipbopAdvanced)
        case .debugHud: DebugHudScreen(stream: .bipbopAdvanced)
        #if canImport(SuperPlayerMoQ)
            case .moq: MoqScreen()
        #endif
        }
    }

    /// The screen `-SPDemoScreen` names, or nil to open on the list. A launch argument of that form
    /// lands in `UserDefaults`' argument domain, so reading the key is reading the argument.
    ///
    /// On tvOS with no argument it is ``tv``: a television opens on its own screen, as the Android
    /// APK opens its TV screen on a television, and the list stays one Menu press away.
    static func fromLaunchArguments(_ defaults: UserDefaults = .standard) -> DemoScreen? {
        if let named = defaults.string(forKey: "SPDemoScreen") {
            return DemoScreen(rawValue: named)
        }
        #if os(tvOS)
            return .tv
        #else
            return nil
        #endif
    }
}
