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
import SuperPlayerCore

/// One player's display criteria matching: ADR-0014 rule 4 read for tvOS (`docs/ios-plan.md` §3.8)
/// — the Kotlin `FrameRateMatching`, smaller, because the platform does the half Android's binding
/// does by hand.
///
/// **What it asks.** Each item the player makes current is asked for its own
/// `preferredDisplayCriteria`, and the display is asked for those, so 24 fps film on a 60 Hz panel
/// is shown at a matching rate, and HDR10 or Dolby Vision content in its own dynamic range. The
/// Kotlin binding decides whether a request is worth making — a display already at a multiple of the
/// rate, a display with no matching mode — because `Surface.setFrameRate` with
/// `CHANGE_FRAME_RATE_ALWAYS` acts on any request; on tvOS the asset states the criteria and the
/// system decides the mode, honouring the viewer's *Match Content* setting, so every item asks and
/// none is second-guessed here.
///
/// **When it withdraws.** At the item's end, so the display goes back to a mode suited to the
/// interface the viewer returns to — and asks again if the viewer plays it again from there; when a
/// pool recycles the player; when the player is released; and when an item states no criteria.
/// **Not between two items**: a replacement asks for the next item's criteria in place, because a
/// withdrawal there would cost the panel two switches — and two blanks — for one change of content.
///
/// **Only what it asked for is withdrawn.** The display is one per process and so are its criteria,
/// while a pool or a screen may hold several players; a player whose item ended after another
/// player asked for its own must not put the display back under the one still playing. So a
/// withdrawal is made only by the binding whose request is the one in force (``owners``).
///
/// **An answer that arrives late is dropped.** The criteria load asynchronously; an answer for an
/// item no longer current, or after its end, or after release, asks nothing — a slow asset never
/// overrides the content that replaced it.
///
/// **The display's mode is reported and never acted on** (`docs/ios-plan.md` §3.8): the reading as
/// each item is asked for, then each switch beginning and ending and each change of the viewer's
/// settings, reach the player's collector as `DisplayModeChanged`. Android's display watch re-selects
/// on a display change; on Apple platforms the system re-selects, so nothing here does.
@MainActor
final class DisplayCriteriaMatching: VideoOutputBinding {

    /// Which binding's request is in force on each display: the one allowed to withdraw it.
    /// Process-wide, because the display is. The binding is held weakly, so one gone without a
    /// release — and an identity later reused — can never pass for the owner.
    private static var owners: [ObjectIdentifier: Owner] = [:]

    private struct Owner {
        weak var binding: DisplayCriteriaMatching?
    }

    private let displays: any DisplayManaging
    private weak var host: (any VideoOutputHost)?

    private var displayObservations: [DisplayObservation] = []
    private var itemObservations: [DisplayObservation] = []

    /// The item the player is showing, if any.
    private var current: AVPlayerItem?

    /// Counts the asks, so an answer can tell whether it is still the latest one.
    private var generation = 0

    /// Whether the current item's criteria were withdrawn at its end, which a time jump then
    /// re-asks.
    private var withdrawnAtEnd = false

    private var isReleased = false

    init(displays: any DisplayManaging, host: any VideoOutputHost) {
        self.displays = displays
        self.host = host
        displayObservations = DisplaySignal.allCases.filter { $0.scope == .display }.map {
            signal in
            displays.observe(signal) { [weak self] in self?.report() }
        }
    }

    func itemChanged(to item: AVPlayerItem?) {
        guard !isReleased else { return }
        itemObservations = []
        current = item
        guard let item else {
            generation += 1
            withdraw()
            return
        }
        itemObservations = [
            DisplayObservation.register(.itemPlayedToEnd, postedBy: item) {
                [weak self, weak item] in
                guard let self, let item else { return }
                self.itemEnded(item)
            },
            DisplayObservation.register(.itemTimeJumped, postedBy: item) { [weak self, weak item] in
                guard let self, let item else { return }
                self.itemTimeJumped(item)
            },
        ]
        ask(for: item)
    }

    func release() {
        guard !isReleased else { return }
        isReleased = true
        generation += 1
        itemObservations = []
        displayObservations = []
        current = nil
        withdraw()
    }

    /// The registrations live on this binding, per signal — what the register's test counts.
    var liveObservations: [DisplaySignal: Int] {
        (displayObservations + itemObservations).reduce(into: [:]) { counts, observation in
            counts[observation.signal, default: 0] += 1
        }
    }

    private func ask(for item: AVPlayerItem) {
        generation += 1
        withdrawnAtEnd = false
        let asked = generation
        let asset = item.asset
        Task { [weak self, displays] in
            let criteria = await displays.preferredCriteria(of: asset)
            guard let self, asked == self.generation, !self.isReleased else { return }
            if let criteria {
                displays.setPreferredCriteria(criteria)
                Self.owners[ObjectIdentifier(displays)] = Owner(binding: self)
            } else {
                self.withdraw()
            }
            self.report()
        }
    }

    private func itemEnded(_ item: AVPlayerItem) {
        guard item === current, !withdrawnAtEnd else { return }
        // An answer still loading for this item is now late.
        generation += 1
        withdrawnAtEnd = true
        withdraw()
    }

    private func itemTimeJumped(_ item: AVPlayerItem) {
        // Any other jump is a seek within content whose criteria are already in force.
        guard item === current, withdrawnAtEnd else { return }
        ask(for: item)
    }

    /// Puts the display back to a mode suited to everything, if the request in force is this
    /// binding's; nothing otherwise.
    private func withdraw() {
        let display = ObjectIdentifier(displays)
        guard Self.owners[display]?.binding === self else { return }
        Self.owners[display] = nil
        displays.setPreferredCriteria(nil)
    }

    private func report() {
        host?.displayModeChanged(
            switchInProgress: displays.isDisplayModeSwitchInProgress,
            criteriaMatchingEnabled: displays.isDisplayCriteriaMatchingEnabled)
    }
}
