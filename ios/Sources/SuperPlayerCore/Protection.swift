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

/// The protection a player is configured with: `SuperPlayerDRM`'s `Drm.fairPlay(_:)`, which
/// ``SuperPlayer/Configuration/drm`` takes — the Kotlin `PlaybackDrm`.
///
/// Public and empty, ``PlaybackResilience``'s shape and the Kotlin marker's: what core asks of it is
/// the `package` protocol ``ContentProtecting``, which the DRM product's value also conforms to, so
/// no AVFoundation type reaches a public signature and a consumer has nothing to implement. A value
/// a consumer wrote conforming to this alone acquires nothing — and is still a declaration that the
/// player's content is protected, which is what turns its frame output off (ADR-0012 rule 13: a
/// provider that is set and answers nothing is not nothing).
public protocol PlaybackDrm: Sendable {}

/// What core asks a ``PlaybackDrm`` for, once per player: the key session that player's items are
/// recipients of — the Kotlin `WidevineDrm` filling the DRM slot, and the seam a Kotlin friend module
/// reaches as `internal`, which on Apple platforms is `package` (`ios/CLAUDE.md`).
///
/// One per player for ADR-0012 rule 1's reason and `docs/ios-plan.md` §3.6's shape: protection is
/// declared once per player and fixed for its lifetime, and an `AVContentKeySession` per player
/// with each item a recipient is what the platform offers for exactly that.
package protocol ContentProtecting: PlaybackDrm {

    /// A fresh session for the player `host` is, made as that player is made, on the main actor.
    /// The session holds `host` weakly: the player owns its session.
    @MainActor func protectionSession(host: any ProtectionHost) -> any ProtectionSession
}

/// One player's protection, as core drives it: every asset the player plays is handed over before
/// the item on it is made current, and the session ends with the player.
@MainActor
package protocol ProtectionSession: AnyObject {

    /// `asset` will play on this player: its keys are this session's to answer for. Called once per
    /// asset, before the item on it is the player's, so that no key request can precede it.
    func addRecipient(_ asset: AVURLAsset)

    /// The player is released: nothing more is asked, and nothing in flight is answered.
    func release()
}

/// What a ``ProtectionSession`` asks of the player it serves — core's half of the exchange, so
/// that `SuperPlayerDRM` reads no decision, holds no collector and climbs no ladder of its own.
@MainActor
package protocol ProtectionHost: AnyObject {

    /// The licence budget in force now, re-read at every ask — the Kotlin `DecisionInForce` window
    /// onto ``SuperPlayer/playbackDecision``, so a re-consulted decision governs the next retry.
    var licenceBudget: RetryBudget { get }

    /// The app's credential, from the resilience the player was configured with, or nil
    /// (``FailureClassifying/headerProvider``).
    var headerProvider: (any HeaderProvider)? { get }

    /// One acquisition ended — a span `docs/telemetry-schema.md`'s *Licence acquisition* defines —
    /// for the collector, if the player has one (``TelemetryCollector/licenceAcquisitionEnded(durationMs:outcome:)``).
    func licenceAcquisitionEnded(durationMs: Int64, outcome: LicenceOutcome)

    /// The exchange for a key could not produce one, with everything the budget allowed spent: the
    /// current item's failure, offered to the ladder as an item's own failure is.
    func licenceFailed(_ error: LicenceExchangeError)
}

/// A FairPlay key request this library made and could not complete — the evidence a licence failure
/// carries to `SuperPlayerResilience`'s classifier, which is where it acquires a meaning (ADR-0012
/// rule 5: the DRM product classifies nothing; it raises a core error carrying the evidence).
///
/// It exists on iOS where Android has none because the exchange is ours here (`docs/ios-plan.md`
/// §3.6): Media3 raises its own DRM error codes for a licence it failed to load, while on Apple
/// platforms the `POST` is this library's and so is the error that reports it. It reaches a consumer
/// as the ``SuperPlayerError/underlyingError`` of the typed error the session ended on.
public struct LicenceExchangeError: Error, Sendable, CustomStringConvertible, CustomNSError {

    /// Which step of the exchange failed.
    public enum Stage: Int, Sendable, Hashable {
        /// Fetching the application certificate the key server publishes.
        case certificate = 1
        /// The device composing the key request — the SPC — from the certificate. No server was
        /// asked; the protection stack refused.
        case keyRequest = 2
        /// The licence server's answer to the key request.
        case licence = 3
    }

    /// Which step failed.
    public let stage: Stage

    /// The HTTP status of the last answer, or nil where none arrived — a transport failure, or the
    /// ``Stage/keyRequest`` step, which asks no server.
    public let status: Int?

    /// How many requests the step made, the first and every retry the licence budget allowed; a
    /// credential repair is a request too. Zero for ``Stage/keyRequest``.
    public let attempts: Int

    /// What the platform said, where it said something: the transport's error, or the protection
    /// stack's.
    public let underlyingError: NSError?

    package init(stage: Stage, status: Int?, attempts: Int, underlyingError: NSError?) {
        self.stage = stage
        self.status = status
        self.attempts = attempts
        self.underlyingError = underlyingError
    }

    public var description: String {
        let answer = status.map { "HTTP \($0)" } ?? "no answer"
        return "licence exchange failed at \(stage) after \(attempts) request(s): \(answer)"
    }

    /// The domain this error bridges to `NSError` under, with ``Stage``'s raw value as the code —
    /// which is how the classifier reads it, beside AVFoundation's own domains.
    public static let errorDomain = "SuperPlayerLicenceErrorDomain"

    public var errorCode: Int { stage.rawValue }

    public var errorUserInfo: [String: Any] {
        var info: [String: Any] = [NSLocalizedDescriptionKey: description]
        if let underlyingError { info[NSUnderlyingErrorKey] = underlyingError }
        return info
    }
}

extension SuperPlayer: ProtectionHost {

    package var licenceBudget: RetryBudget { playbackDecision.retry.licence }

    package var headerProvider: (any HeaderProvider)? {
        (configuration.resilience as? any FailureClassifying)?.headerProvider
    }

    package func licenceAcquisitionEnded(durationMs: Int64, outcome: LicenceOutcome) {
        guard !isReleased else { return }
        collector?.licenceAcquisitionEnded(durationMs: durationMs, outcome: outcome)
    }

    /// Offered to the ladder as the current item's own failure, once per item, on a player configured
    /// with resilience — and on no other, whose failures stay AVFoundation's (ADR-0011 rule 14): the
    /// session has already handed the key request the error, so the item fails on its own there.
    ///
    /// The current item, because an `AVContentKeySession` names a key and never the asset that asked
    /// for it: a licence still in flight when its content was replaced fails the content that
    /// replaced it. A device question as much as a design one, listed in `docs/testing.md`'s
    /// *FairPlay on iOS*. With no current item — content released or replaced by a bare `nil` —
    /// there is nothing to fail and the error is dropped; the key request was still told.
    package func licenceFailed(_ error: LicenceExchangeError) {
        guard !isReleased, let item = avPlayer.currentItem, !failureDelivered, let rungs else {
            return
        }
        failureDelivered = true
        climb(
            FailureEvidence(
                error: error as NSError, loggedFailure: nil,
                playlistURL: (item.asset as? AVURLAsset)?.url),
            of: item, rungs, askingForCredential: false)
    }
}
