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

/// Rung 6 of ADR-0011's ladder: what a failure *was*, once every rung below it has been exhausted —
/// the Kotlin `SuperPlayerError`, field for field but one.
///
/// A consumer is handed one on ``SuperPlayer/errors`` and reads the last one off
/// ``SuperPlayer/playerError``: the facade's error publisher, which is where a failure of the facade
/// arrives on Apple platforms (`docs/ios-plan.md` §3.5's rung 6 row). On Android it is the `cause` of
/// the `PlaybackException` a `Player.Listener` is already handed, because that facade *is* a
/// `Player`; this one owns an `AVPlayer` (ADR-0020 rule 2), whose own error surface — the item's
/// `error` — cannot carry anything of SuperPlayer's, so the facade has one of its own. AVFoundation's
/// own error is ``underlyingError``, and it is what this error bridges to `NSError` with under
/// `NSUnderlyingErrorKey`, so nothing AVFoundation said is lost.
///
/// **The shape** is `PRD.md` §3.2's — a cause class, a user-facing string key, and whether it is
/// retryable — with ADR-0011 rule 10's additions: the rungs tried and the position reached. The
/// Kotlin `likelyCause` has no counterpart: it is read off core's `StaleLivePlaylistException`, which
/// core's transfer chain raises, and the chain does not port (ADR-0020 rule 6).
///
/// Every value is decided by `SuperPlayerResilience`'s classifier and its taxonomy, the one place a
/// failure acquires a meaning (ADR-0011 rule 1). This type carries that conclusion across the product
/// boundary as strings and adds nothing to it, which is why its initializer is `package`: a consumer
/// receives one, and nothing outside the library mints one. A player configured without
/// ``SuperPlayer/Configuration/resilience`` has nobody to ask and never produces one (ADR-0011 rule
/// 14): its errors are AVFoundation's, unchanged, on the item.
public struct SuperPlayerError: Error, Sendable, CustomStringConvertible, CustomNSError {

    /// What the failure is, as the classifier's stable name — `"Transient.CdnEdge"`,
    /// `"Content.ManifestInvalid"` and the rest. A string rather than a type of core's for the Kotlin
    /// reason: the taxonomy is `SuperPlayerResilience`'s, and a copy of it here would be the second
    /// answer to one question.
    public let causeClass: String

    /// The key an app looks a viewer-facing string up by — a resource name, not a message.
    public let userMessageKey: String

    /// Whether asking for the same bytes again could change the outcome: the difference between a
    /// failure worth offering a retry button for and one where the button is a lie.
    public let isRetryable: Bool

    /// The coarse bucket telemetry reports this failure in, derived from the class by the
    /// classifier's one-to-one table (ADR-0011 rule 3) rather than a second time here.
    public let category: FailureCategory

    /// Whether a licence at a lower security level could plausibly have succeeded (ADR-0012 rule
    /// 11's #225 addendum). Carried for the shared shape; FairPlay has one level, so on Apple
    /// platforms nothing acts on it.
    public let lowerSecurityLevelMayHelp: Bool

    /// The rungs the ladder climbed for this failure, in order, by the Kotlin `FallbackRung` names —
    /// `"NEXT_SOURCE"`, `"RECREATE_DECODER"`. Empty is a fact, not an absence: a class whose ceiling
    /// is the typed error is offered no rung, and no rung is performed on Apple platforms until rungs
    /// 4 and 5 are built.
    public let rungsTried: [String]

    /// Where playback had reached when the failure surfaced, in milliseconds, or nil when the item
    /// had no position to report — the Kotlin `C.TIME_UNSET`.
    public let positionMs: Int64?

    /// AVFoundation's own error, kept underneath so nothing the engine said is lost: the item's
    /// `error`, whose own `NSUnderlyingErrorKey` is usually the `CoreMediaErrorDomain` code naming
    /// the HTTP status.
    public let underlyingError: NSError

    package init(
        causeClass: String, userMessageKey: String, isRetryable: Bool, category: FailureCategory,
        lowerSecurityLevelMayHelp: Bool, rungsTried: [String], positionMs: Int64?,
        underlyingError: NSError
    ) {
        self.causeClass = causeClass
        self.userMessageKey = userMessageKey
        self.isRetryable = isRetryable
        self.category = category
        self.lowerSecurityLevelMayHelp = lowerSecurityLevelMayHelp
        self.rungsTried = rungsTried
        self.positionMs = positionMs
        self.underlyingError = underlyingError
    }

    /// The Kotlin `describe`: built from the fields rather than taken from the engine's message,
    /// which stays on ``underlyingError``.
    public var description: String {
        let position = positionMs.map { "\($0)ms" } ?? "no position"
        let rungs =
            rungsTried.isEmpty
            ? "no rung attempted" : "rungs tried: \(rungsTried.joined(separator: ", "))"
        return "\(causeClass) at \(position) (\(rungs))"
    }

    /// The domain this error bridges to `NSError` under.
    public static let errorDomain = "SuperPlayerErrorDomain"

    /// One code, because the class is ``causeClass`` and an integer per class would be a third
    /// spelling of the taxonomy.
    public var errorCode: Int { 1 }

    /// AVFoundation's error under `NSUnderlyingErrorKey`, so a consumer walking an `NSError` chain —
    /// a crash reporter, an analytics SDK — reaches it as it would on a stock player.
    public var errorUserInfo: [String: Any] {
        [NSUnderlyingErrorKey: underlyingError, NSLocalizedDescriptionKey: description]
    }
}

/// The resilience a player is configured with: `SuperPlayerResilience`'s `Resilience.standard()`,
/// which ``SuperPlayer/Configuration/resilience`` takes — the Kotlin `PlaybackResilience`.
///
/// Public and empty, the Kotlin marker's shape: what core asks of it is the `package` protocol
/// ``FailureClassifying``, which the resilience product's value also conforms to, so no AVFoundation
/// type reaches a public signature and a consumer has nothing to implement. A value a consumer wrote
/// conforming to this alone classifies nothing, as a hand-written Kotlin `PlaybackResilience` fills
/// no slot.
public protocol PlaybackResilience: Sendable {}

/// What core asks a ``PlaybackResilience`` when an item fails: the typed error for it — the Kotlin
/// `EngineResilienceExtension`'s classification half, and the seam a Kotlin friend module reaches
/// as `internal`, which on Apple platforms is `package` (`ios/CLAUDE.md`).
///
/// Core hands over the evidence AVFoundation leaves and decides nothing from it: the item's error,
/// the error log's last entry — the one place AVFoundation names *which* request failed — the
/// asset's own URL, the multivariant playlist, and where playback had reached.
package protocol FailureClassifying: PlaybackResilience {
    func typedError(
        for error: NSError, loggedFailure: AVPlayerItemErrorLogEvent?, playlistURL: URL?,
        positionMs: Int64?
    ) -> SuperPlayerError
}
