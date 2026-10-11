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
import SuperPlayerCore

/// Where a realtime transport is handed to a player — the Kotlin `Realtime`.
///
/// ```swift
/// let player = RealtimePlayer(
///     configuration: .init(sources: Realtime.transport(scheme: "moq") { MoqFrameSource($0) }))
/// try player.setMediaRequest(
///     MediaRequest(contentId: "studio-a", sources: [URL(string: "moq://relay.example/studio-a")!]))
/// player.play()
/// ```
///
/// What this returns is ``RealtimePlayer/Configuration/sources`` and nothing else. On Android it is
/// `SuperPlayer.Builder.setRealtime`'s argument, composed into the one player's loading path; here a
/// realtime stream has a player of its own (ADR-0020 rule 11), and the scheme is what that player
/// dispatches on.
public enum Realtime {

    /// The transport answering URLs with `scheme`, opening one ``FrameSource`` per playback through
    /// `open`.
    ///
    /// `scheme` is matched case-insensitively against the URL's own scheme and carries no `:`. It is
    /// **the only discriminator** (ADR-0018 rule 12): nothing about a request but its first source's
    /// scheme decides that this transport answers it. A blank scheme, or one carrying a `:`, is a
    /// programming error and traps, as the Kotlin `require` throws.
    ///
    /// Choosing the scheme is the transport's, not this library's. A protocol with a registered
    /// scheme uses it; one without should pick something an app author will not confuse with
    /// `https`.
    public static func transport(scheme: String, factory: any FrameSourceFactory) -> RealtimeSources
    {
        precondition(
            !scheme.trimmingCharacters(in: .whitespaces).isEmpty && !scheme.contains(":"),
            "A realtime scheme is a bare scheme without the ':' — \"moq\", not \"moq://\". "
                + "Got: \"\(scheme)\"")
        return RealtimeSources(schemes: [scheme.lowercased()], factory: factory)
    }

    /// ``transport(scheme:factory:)`` with the factory written as a closure, the Kotlin trailing
    /// lambda's shape.
    public static func transport(
        scheme: String, _ open: @escaping @Sendable (URL) -> any FrameSource
    ) -> RealtimeSources {
        transport(scheme: scheme, factory: ClosureFactory(body: open))
    }
}

/// The realtime transports a ``RealtimePlayer`` can reach, and the URL schemes that reach them — the
/// Kotlin `RealtimeSources`.
///
/// Made by ``Realtime/transport(scheme:factory:)`` and nothing else, so its initializer is not
/// public. On Android the type is core's, because the one player's builder takes it; here the player
/// that takes it is this product's, so the type is too.
public struct RealtimeSources: Sendable {

    /// The URL schemes these transports answer, lowercase and without the `:` — a set rather than one
    /// string because a product may carry more than one protocol.
    public let schemes: Set<String>

    let factory: any FrameSourceFactory

    /// Whether `scheme` is one of ``schemes``. Case-insensitive, because a URL's scheme is the
    /// consumer's spelling.
    func answers(_ scheme: String?) -> Bool {
        scheme.map { schemes.contains($0.lowercased()) } ?? false
    }
}

/// A closure as a ``FrameSourceFactory``.
private struct ClosureFactory: FrameSourceFactory {
    let body: @Sendable (URL) -> any FrameSource
    func open(_ url: URL) -> any FrameSource { body(url) }
}
