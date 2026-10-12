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
import SuperPlayerTV

/// A display stated by the test, at the one package-scoped protocol the TV output reaches the
/// platform through (ADR-0021 rule 5): what the system's would have been asked is recorded instead,
/// and its mode is moved by hand. The same stand-in on iOS and on tvOS, because what it stands in
/// for — `AVDisplayManager` — is a tvOS type and a simulator's display switches no mode either way.
///
/// Each asset's criteria are named for its URL's last path component, so a request reads
/// `prefer a.m3u8`; a withdrawal reads `withdraw`.
@MainActor
final class StandInDisplay: DisplayManaging {

    var isDisplayCriteriaMatchingEnabled = true

    var isDisplayModeSwitchInProgress = false

    /// What the display was asked, in order.
    private(set) var requests: [String] = []

    /// The assets, by last path component, that state no criteria.
    var statesNoCriteria: Set<String> = []

    /// Whether an answer waits for ``answer(_:)`` rather than arriving at once.
    var holdsAnswers = false

    private var held: [String: CheckedContinuation<Void, Never>] = [:]

    private struct Registration {
        let signal: DisplaySignal
        weak var observation: DisplayObservation?
        let handler: @MainActor @Sendable () -> Void
    }

    private var registrations: [Registration] = []

    /// The assets whose answer is being held, by last path component.
    var waiting: Set<String> { Set(held.keys) }

    /// The registrations a binding still holds — released ones are gone with the binding's hold.
    var liveObservations: [DisplaySignal: Int] {
        registrations.filter { $0.observation != nil }.reduce(into: [:]) { counts, registration in
            counts[registration.signal, default: 0] += 1
        }
    }

    func preferredCriteria(of asset: AVAsset) async -> DisplayCriteria? {
        let name = (asset as? AVURLAsset)?.url.lastPathComponent ?? "an asset with no URL"
        if holdsAnswers {
            await withCheckedContinuation { held[name] = $0 }
        }
        return statesNoCriteria.contains(name) ? nil : DisplayCriteria(name)
    }

    func setPreferredCriteria(_ criteria: DisplayCriteria?) {
        requests.append(criteria.map { "prefer \($0.value)" } ?? "withdraw")
    }

    func observe(_ signal: DisplaySignal, _ handler: @escaping @MainActor @Sendable () -> Void)
        -> DisplayObservation
    {
        let observation = DisplayObservation(signal: signal)
        registrations.append(
            Registration(signal: signal, observation: observation, handler: handler))
        return observation
    }

    /// Lets the held answer for `name` arrive.
    func answer(_ name: String) {
        held.removeValue(forKey: name)?.resume()
    }

    /// Posts `signal` to every live registration of it, as the display's notification would.
    func post(_ signal: DisplaySignal) {
        for registration in registrations
        where registration.signal == signal && registration.observation != nil {
            registration.handler()
        }
    }
}
