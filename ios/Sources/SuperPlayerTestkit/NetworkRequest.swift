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
/// The fourth kind, ``licence``, is Android's `LICENCE`, read by where it is rather than by its name:
/// anything under the licence server's directory (``TestContent/licenceServer()``).
public enum ResourceKind: Sendable, Hashable, CaseIterable {

    /// A multivariant or media playlist (HLS) or an MPD.
    case manifest

    /// An initialization segment: HLS's `EXT-X-MAP` target.
    case initialization

    /// A segment carrying media samples.
    case mediaSegment

    /// A FairPlay key server's: its application certificate, or a key request `POST`ed for a
    /// licence — the Kotlin `LICENCE`, which a fault addresses to refuse a licence without touching
    /// the content, because on both platforms the licence server is a host of its own.
    case licence

    /// The kind `path` names, by its last component, ignoring case — or ``licence`` for anything
    /// under the licence server's directory, whatever its name.
    static func of(path: String) -> ResourceKind {
        if path.hasPrefix("/" + TestContent.licenceDirectory) { return .licence }
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

    /// The request method, `GET` or `HEAD` from `AVPlayer`, and `POST` for a key request.
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

    /// Which of the origin's hosts — its listeners, numbered from zero — the request arrived at.
    /// Zero unless the content was served on several (``TestContent/hls(segmentCount:variantCount:hosts:)``).
    public let host: Int

    /// The request's body: empty for everything a player sends but a key request, whose body is
    /// the SPC the device composed — what a test holds the exchange's `POST` to.
    public let body: [UInt8]

    public var description: String {
        host == 0 ? "\(kind) \(method) \(path)" : "\(kind) \(method) \(path) @host\(host)"
    }
}

/// How a ``LoopbackOrigin`` answered one ``NetworkRequest``: what it decided to send, recorded
/// before the bytes go — so a test can show that a fault happened by reading the origin rather than
/// by inferring it from what the player did.
///
/// No Android counterpart: there the injector throws inside the engine and the engine's own error
/// is the evidence. Here a fault is a server's answer, and the server is the only party a test can
/// read it from.
public struct OriginResponse: Sendable, Hashable, CustomStringConvertible {

    /// The request this answers.
    public let request: NetworkRequest

    /// The status sent, or nil when the connection was dropped with no response at all
    /// (``FaultScript/Builder/resetConnection(kind:index:firstAttempts:host:)``).
    public let status: Int?

    /// The response's header fields, names lowercased, as sent.
    public let headers: [String: String]

    /// The body bytes the origin sends — fewer than `Content-Length` declares when the response is
    /// truncated, and none for a `HEAD`.
    public let bodyBytesSent: Int

    /// The fault kinds that shaped this answer, in the order the script declares them; empty for an
    /// answer the script did not touch. A shared cache appears on the response that filled it and on
    /// every one served from it; ``servedFromCache`` says which.
    public let faults: [FaultScript.Kind]

    /// Whether the answer is a stored copy from the script's intermediary cache rather than what the
    /// origin publishes now.
    public let servedFromCache: Bool

    /// The transport the origin's ``ThroughputTrace`` was on when the request arrived, or nil when
    /// none was given. Reported rather than switched — see ``NetworkTransport``.
    public let transport: NetworkTransport?

    public var description: String {
        let answer = status.map(String.init) ?? "reset"
        return "\(request) -> \(answer)" + (faults.isEmpty ? "" : " \(faults)")
    }
}
