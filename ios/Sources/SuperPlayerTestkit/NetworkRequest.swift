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

/// What kind of resource a request names, by the origin's own reading of its path.
///
/// The port of `superplayer-testkit`'s `ResourceKind`, read by the same rule as its
/// `FaultInjectingDataSource.kindOf`: a name ending `.m3u8` or `.mpd` is a manifest, a name
/// containing `init` an initialization segment, and anything else media. A rule over the *name*
/// rather than over what the origin happens to serve, so a request for a resource that is not there
/// still has a kind — which is what a fault addressed at a kind (#410) needs.
///
/// Android's fourth kind, `LICENCE`, is not here: a FairPlay key request is the app's own exchange
/// with its key server through `AVContentKeySession`, not a load the origin serves, and it arrives
/// with i6.
public enum ResourceKind: Sendable, Hashable, CaseIterable {

    /// A multivariant or media playlist (HLS) or an MPD.
    case manifest

    /// An initialization segment: HLS's `EXT-X-MAP` target.
    case initialization

    /// A segment carrying media samples.
    case mediaSegment

    /// The kind `path` names, by its last component, ignoring case.
    static func of(path: String) -> ResourceKind {
        let name = (path.split(separator: "/").last.map(String.init) ?? path).lowercased()
        if name.hasSuffix(".m3u8") || name.hasSuffix(".mpd") { return .manifest }
        if name.contains("init") { return .initialization }
        return .mediaSegment
    }
}

/// One request a ``LoopbackOrigin`` received, as it arrived on the wire — the port of
/// `superplayer-testkit`'s `NetworkRequest`, and what a test counts instead of reaching into the
/// player.
///
/// Recorded before the origin decides how to answer, so a request for a resource that is not
/// served, or one a fault refuses (#410), is counted like any other: it left the player.
public struct NetworkRequest: Sendable, Hashable, CustomStringConvertible {

    /// The request method, `GET` or `HEAD` from `AVPlayer`.
    public let method: String

    /// The path of the request target, query excluded, with its leading `/`.
    public let path: String

    /// The query of the request target without its `?`, or nil when it had none.
    public let query: String?

    /// What kind of resource ``path`` names.
    public let kind: ResourceKind

    /// The request's header fields, names lowercased: a field name is case-insensitive (RFC 9110
    /// §5.1), so a test reads `range` whatever spelling the client sent. A repeated field keeps its
    /// last value.
    public let headers: [String: String]

    /// The ordinal, from zero, of the TCP connection the request arrived on, in the order the origin
    /// accepted them: two requests with one ordinal shared a kept-alive connection.
    public let connection: Int

    public var description: String { "\(kind) \(method) \(path)" }
}
