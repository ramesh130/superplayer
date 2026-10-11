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

/// The SuperPlayer demo for iOS and tvOS: the app a consumer reads to see how little playing through
/// SuperPlayer asks of them, and the one a person runs to see that it still plays real HLS over a
/// real CDN — which no test in this repository can show, since every one plays from the loopback
/// origin (ADR-0021).
///
/// The iOS twin of the Android `demo/`, and resolved the same way for the same reason: an Xcode
/// project that takes the package **by path** (`../..`, the repository root where `Package.swift`
/// lives), so the demo compiles against the products as an adopter's app does — through their
/// public API, never anything `package`-scoped — and an adopter's first compile failure happens here.
///
/// The root is a list of ``DemoScreen``s. A screen can also be opened directly, which is what a run
/// from the command line does, with the launch argument `-SPDemoScreen <case>` — the iOS form of the
/// Android demo's `--es com.superplayer.demo.extra.SCREEN`:
///
/// ```sh
/// xcrun simctl launch booted com.superplayer.demo -SPDemoScreen frameOutput
/// ```
@main
struct DemoApp: App {

    var body: some Scene {
        WindowGroup {
            DemoRoot(opened: DemoScreen.fromLaunchArguments())
        }
    }
}

/// The list of screens, with the one named at launch already pushed.
struct DemoRoot: View {

    @State private var path: [DemoScreen]

    init(opened: DemoScreen?) {
        _path = State(initialValue: opened.map { [$0] } ?? [])
    }

    var body: some View {
        NavigationStack(path: $path) {
            List(DemoScreen.allCases) { screen in
                NavigationLink(value: screen) {
                    VStack(alignment: .leading) {
                        Text(screen.title)
                        Text(screen.summary).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("SuperPlayer")
            .navigationDestination(for: DemoScreen.self) { $0.destination }
        }
    }
}
