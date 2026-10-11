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

/// `SuperPlayerResilience`'s entry point — the Kotlin `Resilience`, whose `standard(headers:)` is
/// what `setResilience` takes.
///
/// ```swift
/// var configuration = SuperPlayer.Configuration()
/// configuration.resilience = Resilience.standard()
/// let player = SuperPlayer(configuration: configuration)
/// player.errors.sink { error in … }   // a SuperPlayerError naming its FailureClass
/// ```
public enum Resilience {

    /// The resilience every profile shares: today, failures named by ``ErrorClassifier`` and ended
    /// on a ``SuperPlayerError`` at rung 6 (#419). The Kotlin `standard` takes a `HeaderProvider`;
    /// that argument arrives with #420, which repairs a refused credential by re-adoption, and the
    /// rungs below the typed error with the tickets that build them.
    public static func standard() -> any PlaybackResilience {
        StandardResilience()
    }
}

/// What ``Resilience/standard()`` makes: core's `package` ``FailureClassifying`` filled with this
/// product's classifier, the Kotlin internal `StandardResilience`.
struct StandardResilience: FailureClassifying {

    func typedError(
        for error: NSError, loggedFailure: AVPlayerItemErrorLogEvent?, playlistURL: URL?,
        positionMs: Int64?
    ) -> SuperPlayerError {
        let failure = ErrorClassifier.classify(
            error, loggedFailure: loggedFailure.map(LoggedFailure.init), playlistURL: playlistURL)
        return SuperPlayerError(
            causeClass: failure.stableName, userMessageKey: failure.userMessageKey,
            isRetryable: failure.isRetryable, category: failure.category,
            lowerSecurityLevelMayHelp: failure.lowerSecurityLevelMayHelp,
            // No rung below the typed error is performed yet, so none was tried: the class's
            // ceiling bounds a climb nothing on this platform makes until rungs 4 and 5 are built.
            rungsTried: [], positionMs: positionMs, underlyingError: error)
    }
}
