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

#if os(tvOS)
    import AVFoundation
    import AVKit
    import UIKit

    /// The system's ``DisplayManaging``: the `AVDisplayManager` of the app's key window, on tvOS.
    ///
    /// **Read at every call, never held.** A display manager belongs to a window (`UIWindow`'s
    /// `avDisplayManager`), and a configuration is a value made before any window exists, so the
    /// window is found when the display is asked: the foreground-active scene's key window, else the
    /// first scene's first window. A tvOS app shows one window on one display, which is why the
    /// first is the one; an app with none yet asks nothing, and the next item asks again.
    ///
    /// **The notifications are heard from any object**, because the manager that posts them is the
    /// window's and the window is found late; a tvOS device drives one display, so there is no
    /// second manager to mistake for it.
    ///
    /// Nothing under `ios/check` runs this type — `xctest` has no `UIApplication` to find a window
    /// through, and the simulator's display switches no mode — so the binding is tested over a
    /// stand-in and this is a device's (`docs/testing.md`, *A television on tvOS*).
    @MainActor
    final class SystemDisplayManager: DisplayManaging {

        /// One per process: there is one display, and ownership of its criteria is decided per
        /// manager (`DisplayCriteriaMatching`), so every player must reach the same one.
        static let shared = SystemDisplayManager()

        private init() {}

        private var manager: AVDisplayManager? {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
            return (scene?.keyWindow ?? scene?.windows.first)?.avDisplayManager
        }

        var isDisplayCriteriaMatchingEnabled: Bool {
            manager?.isDisplayCriteriaMatchingEnabled ?? false
        }

        var isDisplayModeSwitchInProgress: Bool {
            manager?.isDisplayModeSwitchInProgress ?? false
        }

        // ref: https://developer.apple.com/documentation/avfoundation/avpartialasyncproperty/preferreddisplaycriteria
        func preferredCriteria(of asset: AVAsset) async -> DisplayCriteria? {
            guard let criteria = try? await asset.load(.preferredDisplayCriteria) else {
                return nil
            }
            return DisplayCriteria(criteria)
        }

        func setPreferredCriteria(_ criteria: DisplayCriteria?) {
            manager?.preferredDisplayCriteria = criteria?.value as? AVDisplayCriteria
        }

        /// Through the register's one registering function, which names the notification.
        func observe(
            _ signal: DisplaySignal, _ handler: @escaping @MainActor @Sendable () -> Void
        ) -> DisplayObservation {
            precondition(signal.scope == .display, "\(signal) is registered on an item")
            return DisplayObservation.register(signal, postedBy: nil, handler)
        }
    }
#endif
