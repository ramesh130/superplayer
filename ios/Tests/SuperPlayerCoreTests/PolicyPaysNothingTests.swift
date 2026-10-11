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

import XCTest

// `@testable` for the observed register's count, the internal seam `ObservedSignalRegisterTests`
// argues.
@testable import SuperPlayerCore

/// ADR-0009 rule 7's "pays nothing", counted rather than assumed (#415): a player under its
/// profile's static policy registers none of the policy's signals, whether the policy was left to
/// the default or handed back explicitly; a player given a policy of its own registers each of them
/// once — the player's for its life, the item's on whatever item it plays — and still once after
/// any number of replacements; and a released player none. The Kotlin `SuperPlayerPolicyTest` counts
/// the same thing as engine listeners.
///
/// The count is taken twice, as ADR-0020 rule 13 asks, so the counter is shown to see what it
/// counts. No item here loads — the URL names a port nothing listens on — because a registration is
/// made when an item is set, not when it plays.
@MainActor
final class PolicyPaysNothingTests: XCTestCase {

    /// The discard port on the loopback interface, which nothing here listens on.
    ///
    /// ref: RFC 863 (Discard Protocol)
    private let nowhere = URL(string: "http://127.0.0.1:9/never.m3u8")!

    private func request(_ contentId: String) -> MediaRequest {
        MediaRequest(contentId: contentId, sources: [nowhere])
    }

    /// The policy's signals in `scopes`, each once.
    private func policySignalsOnce(_ scopes: Set<ObservedSignal.Scope>) -> [ObservedSignal: Int] {
        Dictionary(
            uniqueKeysWithValues: ObservedSignal.allCases.filter { scopes.contains($0.scope) }
                .map { ($0, 1) })
    }

    /// The live registrations of the policy's two scopes on `player`.
    private func policyObservations(of player: SuperPlayer) -> [ObservedSignal: Int] {
        player.liveObservations.filter {
            [.reapplicationPlayer, .reapplicationItem].contains($0.key.scope)
        }
    }

    func testAStaticPlayerRegistersNothingOfThePolicys() {
        for policy in [nil, PlaybackProfile.dataSaver.policy] as [(any PlaybackPolicy)?] {
            var configuration = SuperPlayer.Configuration(profile: .dataSaver)
            configuration.policy = policy
            let player = SuperPlayer(configuration: configuration)

            player.setMediaRequest(request("a"))
            player.setMediaURL(nowhere)

            XCTAssertEqual(policyObservations(of: player), [:], "\(String(describing: policy))")
        }
    }

    /// The control: a policy of the consumer's own is observed for, so the count above is not one
    /// that reads nothing whatever the player was given.
    func testAPolicyPlayerRegistersEachOfThePolicysSignalsOnce() {
        let player = SuperPlayer(configuration: configured(with: FixedPolicy()))
        XCTAssertEqual(policyObservations(of: player), policySignalsOnce([.reapplicationPlayer]))

        for contentId in ["a", "b", "a"] { player.setMediaRequest(request(contentId)) }
        XCTAssertEqual(
            policyObservations(of: player),
            policySignalsOnce([.reapplicationPlayer, .reapplicationItem]))

        // Whatever the item carries: a bare URL's item is observed for the policy too.
        player.setMediaURL(nowhere)
        XCTAssertEqual(
            policyObservations(of: player),
            policySignalsOnce([.reapplicationPlayer, .reapplicationItem]))
    }

    func testARecycledPolicyPlayerKeepsOnlyThePlayersSignals() {
        let player = SuperPlayer(configuration: configured(with: FixedPolicy()))
        player.setMediaRequest(request("a"))

        player.resetForReuse()

        XCTAssertEqual(policyObservations(of: player), policySignalsOnce([.reapplicationPlayer]))
    }

    func testAReleasedPolicyPlayerObservesNothing() {
        let player = SuperPlayer(configuration: configured(with: FixedPolicy()))
        player.setMediaRequest(request("a"))

        player.release()

        XCTAssertEqual(player.liveObservations, [:])
    }

    /// What the player plays under: the static player its profile's row, a policy player its
    /// policy's first answer — on the item and in `playbackDecision` alike.
    func testTheDecisionInForceIsThePolicysFirstAnswer() {
        let profiled = SuperPlayer(configuration: .init(profile: .shortForm))
        XCTAssertEqual(
            profiled.playbackDecision, PlaybackProfile.shortForm.policy.decide(PlaybackConditions())
        )

        let player = SuperPlayer(configuration: configured(with: FixedPolicy()))
        player.setMediaRequest(request("a"))
        XCTAssertEqual(player.playbackDecision, FixedPolicy.decision)
        XCTAssertEqual(player.avPlayer.currentItem?.preferredForwardBufferDuration, 7)
        XCTAssertEqual(player.avPlayer.automaticallyWaitsToMinimizeStalling, false)
    }

    private func configured(with policy: any PlaybackPolicy) -> SuperPlayer.Configuration {
        var configuration = SuperPlayer.Configuration()
        configuration.policy = policy
        return configuration
    }
}

/// A policy whose answer never moves, written by hand: re-consulted all the same, since nothing but
/// the static policy is known not to change.
private struct FixedPolicy: PlaybackPolicy {
    static let decision = PlaybackDecision(
        buffer: BufferPolicy(maxBufferMs: 7_000, waitsToMinimizeStalling: false))

    func decide(_ conditions: PlaybackConditions) -> PlaybackDecision { Self.decision }
}
