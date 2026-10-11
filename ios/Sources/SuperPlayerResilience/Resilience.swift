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

import SuperPlayerCore

/// `SuperPlayerResilience`'s entry point — the Kotlin `Resilience`, whose `standard(headers:)` is
/// what `setResilience` takes.
///
/// ```swift
/// var configuration = SuperPlayer.Configuration()
/// configuration.resilience = Resilience.standard(headers: Tokens())
/// let player = SuperPlayer(configuration: configuration)
/// player.errors.sink { error in … }   // a SuperPlayerError naming its FailureClass
/// ```
public enum Resilience {

    /// The resilience every profile shares (ADR-0011 rule 12): a refused credential repaired from
    /// `headers` where one is given (``HeaderProvider``), the next of a request's sources opened at
    /// the position reached (rung 4), the item rebuilt there after the media services reset (rung 5,
    /// at most ``SuperPlayer``'s two times at one position), and every failure nothing repaired
    /// ended on a ``SuperPlayerError`` naming its ``FailureClass`` (rung 6). A failure a rung
    /// repaired reaches no consumer (rule 10).
    ///
    /// Rungs 1 to 3 are AVFoundation's own, and nothing configures them (ADR-0020 rule 6), so
    /// `RetryPolicy.manifest` and `.segment` are ignored here: the engine retries a refused request
    /// on its own schedule before the item fails, and the ladder above begins where it gives up.
    ///
    /// - Parameter headers: the app's credential, asked only on a 401 or a 403; nil — the default —
    ///   for a player that sends no credential of its own and meets such a refusal as any other.
    public static func standard(headers: (any HeaderProvider)? = nil) -> any PlaybackResilience {
        StandardResilience(headers: headers)
    }
}

/// What ``Resilience/standard(headers:)`` makes: core's `package` ``FailureClassifying`` filled with
/// this product's ladder, one per player — the Kotlin internal `StandardResilience`.
struct StandardResilience: FailureClassifying {

    let headers: (any HeaderProvider)?

    @MainActor
    func playerStateRungs() -> any PlayerStateRungs {
        PlayerStateLadder(provider: headers)
    }
}
