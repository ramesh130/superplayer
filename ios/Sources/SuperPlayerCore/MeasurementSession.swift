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

import Foundation

/// The session one player is measuring, and the one place its id is decided — the Kotlin
/// `MeasurementSession`.
///
/// There are two readers of a session id and they never meet: the collector stamps it on every
/// ``TelemetryEvent``, and CMCD sends it to the CDN as CTA-5004's `sid`. The join between a CDN's
/// log and an app's warehouse is an equality on this string, so one place decides it (ADR-0008 rule
/// 6), and it is core, because only core knows which item change carried a ``MediaRequest``.
///
/// **Where the id comes from is the caller's argument**, not this type's. Today the facade passes
/// ``newSessionId()``. On iOS 18 the SDK fills CMCD's `sid` itself, from the asset's
/// `httpSessionIdentifier`, and cannot be told what to put there (`docs/ios-cmcd-finding.md`), so
/// #446 passes that identifier instead — one argument changed at the one call site where the asset
/// exists, and nothing here or in any collector moves. An id minted by this type would have been
/// the one thing #446 had to unpick.
///
/// Held only by a player with a reader for an id — today, one configured with a collector — so a
/// player without one mints nothing (ADR-0008 rule 2). The Kotlin `idFor(contentId)`, what CMCD
/// asks, arrives with #446, its first reader.
struct MeasurementSession {

    /// The open session: its content and its id.
    struct Open: Equatable {
        let contentId: String
        let sessionId: String
    }

    /// The session currently open, or nil between sessions.
    private(set) var current: Open?

    /// Opens a session for `contentId` under `sessionId`, replacing whatever was open; the facade
    /// ends the previous one with its collector first.
    mutating func open(contentId: String, sessionId: String) {
        current = Open(contentId: contentId, sessionId: sessionId)
    }

    /// Closes whatever is open and returns it, or nil when nothing was — so a release after a
    /// recycle signals nothing twice.
    mutating func close() -> Open? {
        defer { current = nil }
        return current
    }

    /// A new session id.
    ///
    /// spec: CTA-5004 §3.1 caps `sid` at 64 characters and asks for a GUID; a UUID's canonical
    /// 36-character form satisfies both. Foundation's `uuidString` is uppercase, which is also the
    /// form the asset's `httpSessionIdentifier` takes (`docs/telemetry-schema.md`, *On iOS and
    /// tvOS*), so the source #446 swaps in changes no id's shape.
    static func newSessionId() -> String {
        UUID().uuidString
    }
}
