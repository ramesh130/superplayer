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

// The rungs of ADR-0011's ladder that exist on Apple platforms, decided here and performed by core
// (`PlayerStateRungs.swift`): rung 4, rung 5, rung 6's typed error, and the credential repair that
// is asked before any of them. Rungs 1 to 3 are AVFoundation's own and nothing configures them
// (ADR-0020 rule 6), which is why `RetryPolicy.manifest` and `.segment` are ignored on this platform.

extension FailureClass {

    /// Whether the ladder may attempt `rung` for this class — ``rungCeiling`` read, in the one
    /// function every rung reads it through, as the Kotlin `mayClimb` is. A ceiling caps the climb
    /// and does not route it: which rung below it answers is each rung's own decision.
    func mayClimb(_ rung: FallbackRung) -> Bool { rung <= rungCeiling }
}

/// Rung 4: the next entry of `MediaRequest.sources`, at the position playback had reached — the
/// Kotlin `NextSource`, its two refusals ported unchanged.
///
/// 1. **A ceiling below this rung**: ``FailureClass/Fatal/unsupported`` and the protection leaves
///    that end at the typed error reach rung 6 without a second source being fetched to fail the
///    same way.
/// 2. **``FailureClass/Device/decoderTransient``**: its ceiling is *above* this rung, which is why
///    the refusal has to be written — a decoder that was working and stopped, or media services
///    reset beneath it, is not a property of the source, and its remedy is rung 5; opening a second
///    source first would cost a viewer a whole start-up for nothing.
///
/// No budget and no backoff, for the Kotlin reason: each source is asked for once, and the list is
/// what bounds the climb — which only core knows the length of.
enum NextSource {
    static func takesOn(_ failure: FailureClass) -> Bool {
        failure != .device(.decoderTransient) && failure.mayClimb(.nextSource)
    }
}

/// Rung 5: the player rebuilt where it stood — the Kotlin `RecreateDecoder`, and on Apple platforms
/// the remedy for a media services reset, which ``ErrorClassifier`` names
/// ``FailureClass/Device/decoderTransient`` with every other decoder that stopped
/// (`docs/ios-plan.md` §3.5: "exactly the case").
///
/// One class and nothing else: ``FailureClass/Device/decoderInit`` is refused because no decoder was
/// there to lose, and everything else — a refused segment, a malformed playlist — is not a fault of
/// this device's decoding, so an item rebuilt against the same broken source is a loop with a
/// start-up in it. How many rebuilds are worth attempting is core's count at the position the
/// player is stuck at (`SuperPlayer.maxDecoderRecreations`), not visible from a class.
enum RecreateDecoder {
    static func takesOn(_ failure: FailureClass) -> Bool {
        failure == .device(.decoderTransient) && failure.mayClimb(.recreateDecoder)
    }
}

/// Rung 6: the typed, actionable error a session ends on when nothing below it worked — every field
/// read from the one classification (ADR-0011 rule 1), the position core's and the rungs the
/// player's climb record.
enum TypedError {
    static func of(
        _ failure: FailureClass, error: NSError, positionMs: Int64?, rungsTried: [String]
    ) -> SuperPlayerError {
        SuperPlayerError(
            causeClass: failure.stableName, userMessageKey: failure.userMessageKey,
            isRetryable: failure.isRetryable, category: failure.category,
            lowerSecurityLevelMayHelp: failure.lowerSecurityLevelMayHelp,
            rungsTried: rungsTried, positionMs: positionMs, underlyingError: error)
    }
}

/// The rungs core performs and the error it delivers, as the one object core asks, one per player —
/// the Kotlin `PlayerStateLadder` with its `ClimbRecord` inside, and the Kotlin `TokenRefreshLayer`'s
/// state beside them: the headers the app's provider last returned.
///
/// One per player for the Kotlin reason: the climb record is a player's own history, and a record
/// shared across a feed would report one row's rungs on another row's error. A rung is recorded on
/// `true` and not on being asked: core asks only once its own half holds, so a `true` is a rung
/// performed and a `false` a rung declined.
@MainActor
final class PlayerStateLadder: PlayerStateRungs {

    private let provider: (any HeaderProvider)?

    /// The rungs taken on for the current content, in ladder order.
    private var climbed: Set<FallbackRung> = []

    /// The requests a refreshed credential was handed over for, for the current content: each is
    /// repaired once — the Kotlin "asked once more" — so a fresh credential refused again by the
    /// request it was fetched for climbs on rather than loops, while a token that expires again on
    /// a later segment is a new refusal and is repaired again.
    private var refreshedFor: Set<URL> = []

    private(set) var requestHeaders: [String: String] = [:]

    init(provider: (any HeaderProvider)?) {
        self.provider = provider
    }

    func refusesCredential(_ evidence: FailureEvidence) -> Bool {
        guard provider != nil, refusedStatus(evidence) != nil, let uri = refusedURI(evidence)
        else { return false }
        return !refreshedFor.contains(uri)
    }

    func refreshCredential(_ evidence: FailureEvidence) async -> Bool {
        guard let provider, let status = refusedStatus(evidence), let uri = refusedURI(evidence),
            !refreshedFor.contains(uri)
        else { return false }
        // Recorded before the provider is asked, so the question is put once whatever it answers.
        refreshedFor.insert(uri)
        let sent = requestHeaders
        let refusal = CredentialRefusal(uri: uri, status: status, sentHeaders: sent)
        // Throwing declines, and the refusal that reaches the consumer is the CDN's: the provider's
        // own failure is not what the session failed on, as the Kotlin contract says.
        guard let fresh = try? await provider.refreshedHeaders(for: refusal), fresh != sent else {
            return false
        }
        requestHeaders = fresh
        return true
    }

    func opensNextSource(_ evidence: FailureEvidence) -> Bool {
        record(.nextSource, NextSource.takesOn(classify(evidence)))
    }

    func recreatesPlayer(_ evidence: FailureEvidence) -> Bool {
        record(.recreateDecoder, RecreateDecoder.takesOn(classify(evidence)))
    }

    func typedError(for evidence: FailureEvidence, positionMs: Int64?) -> SuperPlayerError {
        TypedError.of(
            classify(evidence), error: evidence.error, positionMs: positionMs,
            rungsTried: climbed.sorted().map(\.stableName))
    }

    /// The headers stay: a token is the player's, and the next content is served by the same CDN
    /// under the same credential — the Kotlin layer keeps what its provider returned the same way.
    func forgetClimb() {
        climbed = []
        refreshedFor = []
    }

    private func record(_ rung: FallbackRung, _ taken: Bool) -> Bool {
        if taken { climbed.insert(rung) }
        return taken
    }

    private func classify(_ evidence: FailureEvidence) -> FailureClass {
        ErrorClassifier.classify(
            evidence.error, loggedFailure: evidence.loggedFailure.map(LoggedFailure.init),
            playlistURL: evidence.playlistURL)
    }

    /// The request the error log names, or the multivariant playlist, which it names for nothing.
    private func refusedURI(_ evidence: FailureEvidence) -> URL? {
        evidence.loggedFailure?.uri.flatMap(URL.init(string:)) ?? evidence.playlistURL
    }

    private func refusedStatus(_ evidence: FailureEvidence) -> Int? {
        ErrorClassifier.refusedCredentialStatus(
            evidence.error, loggedFailure: evidence.loggedFailure.map(LoggedFailure.init))
    }
}
