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

/// The HTTP/1.1 the origin speaks, as values: a request read off a connection's bytes, and a
/// response written back. Nothing here touches a socket, so the two halves of the protocol that
/// are easy to get wrong — framing several requests on one connection and answering a byte range —
/// are pure functions of their input.
///
/// The least HTTP/1.1 a media player needs and no more: `GET` and `HEAD`, persistent connections,
/// and single byte ranges — and `POST` with a `Content-Length` body, for the one party that sends
/// one, a FairPlay key request (``TestContent/licenceServer()``). No chunked request bodies, no
/// multipart ranges (which a server may decline by ignoring the header), no conditional requests.
enum HttpExchange {

    /// One request head, parsed.
    struct Request: Equatable {
        let method: String
        let path: String
        let query: String?
        /// Field names lowercased; a repeated field keeps its last value.
        let headers: [String: String]
        /// Whether the connection stays open after the response.
        let keepAlive: Bool
        /// Whether it came as HTTP/1.0, whose persistence the response has to state (RFC 9112
        /// Appendix C.2.2).
        var isHttp10 = false
        /// The request's body, as many bytes as its `Content-Length` declared; empty for a request
        /// that declared none, which is every request a player sends but a key request.
        var body: [UInt8] = []
    }

    /// What reading the front of a connection's buffer found.
    enum Parsed: Equatable {
        /// A whole request, and how many bytes of the buffer it took, body included.
        case request(Request, consumed: Int)
        /// Not enough bytes yet for a whole request.
        case incomplete
        /// Bytes that are not an HTTP/1.x request; the connection is answered 400 and closed.
        case malformed
    }

    /// How large a request head may grow before it is refused, since the buffer is held in memory
    /// until a head ends: sixteen kilobytes, four times what a player's request with every header it
    /// sends comes to, which is how a client that never ends its head is told from a slow one.
    static let maximumHeadBytes = 16 * 1024

    private static let headTerminator = Data("\r\n\r\n".utf8)

    /// Reads the request at the front of `buffer`.
    ///
    /// spec: RFC 9112 §2.1 — a message is a start line, header fields each ending CRLF, an empty
    /// line, and a body whose length §6.3 decides; a request with neither `Content-Length` nor
    /// `Transfer-Encoding` has none, and a `Transfer-Encoding` request is refused here as malformed
    /// because nothing that plays media sends one.
    static func parse(_ buffer: Data) -> Parsed {
        guard let end = buffer.range(of: headTerminator) else {
            return buffer.count > maximumHeadBytes ? .malformed : .incomplete
        }
        guard let head = String(data: buffer[buffer.startIndex..<end.lowerBound], encoding: .utf8)
        else { return .malformed }
        var lines = head.components(separatedBy: "\r\n")
        // spec: RFC 9112 §3 — the request line is method, target and version, space-separated.
        let requestLine = lines.removeFirst().split(
            separator: " ", omittingEmptySubsequences: false)
        guard requestLine.count == 3, requestLine[2].hasPrefix("HTTP/1."),
            requestLine[1].hasPrefix("/")
        else { return .malformed }

        var headers: [String: String] = [:]
        for line in lines {
            // spec: RFC 9112 §5 — a field line is a name, a colon and a value, the value's
            // surrounding whitespace not part of it.
            guard let colon = line.firstIndex(of: ":") else { return .malformed }
            let name = line[..<colon].lowercased()
            headers[name] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        if headers["transfer-encoding"] != nil { return .malformed }
        let bodyBytes = headers["content-length"].map { Int($0) } ?? 0
        guard let bodyBytes, bodyBytes >= 0 else { return .malformed }
        let consumed = end.upperBound - buffer.startIndex + bodyBytes
        guard buffer.count >= consumed else { return .incomplete }

        let target = requestLine[1]
        let questionMark = target.firstIndex(of: "?")
        let connection = headers["connection"]?.lowercased() ?? ""
        // spec: RFC 9112 §9.3 — HTTP/1.1 is persistent unless `close` is sent; HTTP/1.0 is not,
        // unless `keep-alive` is.
        let keepAlive =
            requestLine[2] == "HTTP/1.1"
            ? !connection.contains("close") : connection.contains("keep-alive")
        return .request(
            Request(
                method: String(requestLine[0]),
                path: String(target[..<(questionMark ?? target.endIndex)]),
                query: questionMark.map { String(target[target.index(after: $0)...]) },
                headers: headers,
                keepAlive: keepAlive, isHttp10: requestLine[2] == "HTTP/1.0",
                body: Array(buffer[end.upperBound..<(buffer.startIndex + consumed)])),
            consumed: consumed)
    }

    /// One response, status line to body.
    struct Response: Equatable {
        var status: Int
        var headers: [(String, String)]
        var body: [UInt8]

        static func == (lhs: Response, rhs: Response) -> Bool {
            lhs.status == rhs.status && lhs.body == rhs.body
                && lhs.headers.map { "\($0): \($1)" } == rhs.headers.map { "\($0): \($1)" }
        }

        /// The response's bytes as they go on the wire. `HEAD` takes the headers alone, with the
        /// `Content-Length` a `GET` would have carried (RFC 9110 §9.3.2).
        func serialized(forHead: Bool) -> Data {
            var head = "HTTP/1.1 \(status) \(Self.reason(status))\r\n"
            for (name, value) in headers { head += "\(name): \(value)\r\n" }
            head += "\r\n"
            var bytes = Data(head.utf8)
            if !forHead { bytes.append(contentsOf: body) }
            return bytes
        }

        private static func reason(_ status: Int) -> String {
            switch status {
            case 200: "OK"
            case 206: "Partial Content"
            case 400: "Bad Request"
            case 403: "Forbidden"
            case 404: "Not Found"
            case 405: "Method Not Allowed"
            case 416: "Range Not Satisfiable"
            case 500: "Internal Server Error"
            case 502: "Bad Gateway"
            case 503: "Service Unavailable"
            default: "Status \(status)"
            }
        }
    }

    /// The answer to `request` when `body` is what is published at its path, or nil when nothing is.
    static func respond(to request: Request, body: [UInt8]?) -> Response {
        frame(resolve(request, body: body), for: request)
    }

    /// ``respond(to:body:)`` before framing: the status, the content headers and the body, with no
    /// `Content-Length` or `Connection` yet — the form a `FaultScript` acts on, since a fault
    /// replaces or stores a response and the framing belongs to the request it finally answers.
    static func resolve(_ request: Request, body: [UInt8]?) -> Response {
        if request.method != "GET" && request.method != "HEAD" {
            // spec: RFC 9110 §15.5.6 — a 405 lists the methods the resource does support.
            return Response(status: 405, headers: [("Allow", "GET, HEAD")], body: [])
        }
        guard let body else { return Response(status: 404, headers: [], body: []) }
        var response = ranged(body, request.headers["range"])
        response.headers.insert(("Content-Type", contentType(of: request.path)), at: 0)
        // spec: RFC 9110 §14.3 — the resource supports byte ranges, so a player may ask for one.
        response.headers.append(("Accept-Ranges", "bytes"))
        return response
    }

    /// The answer a `POST` gets from content that computes one (``TestContent/licenceServer()``):
    /// `body`, opaque bytes, before framing — or the 405 a `GET`-only resource gives where the content
    /// answers no `POST` at that path.
    static func answered(_ request: Request, body: [UInt8]?) -> Response {
        guard let body else { return resolve(request, body: nil) }
        // ref: RFC 2046 §4.5.1 — `application/octet-stream`, uninterpreted binary data, which a key
        // request and a licence each are to everything but the two parties to them.
        return Response(
            status: 200, headers: [("Content-Type", "application/octet-stream")], body: body)
    }

    /// A refusal with `status` and no body, before framing — what an injected failure answers.
    static func refusal(_ status: Int) -> Response {
        Response(status: status, headers: [], body: [])
    }

    /// `response` framed for `request`: its `Content-Length`, and the `Connection` field the
    /// request's persistence asks for.
    static func frame(_ response: Response, for request: Request) -> Response {
        var response = response
        response.headers.append(("Content-Length", String(response.body.count)))
        if !request.keepAlive {
            response.headers.append(("Connection", "close"))
        } else if request.isHttp10 {
            response.headers.append(("Connection", "keep-alive"))
        }
        return response
    }

    /// A 400 for bytes that were not a request, after which the connection is closed.
    static let badRequest = Response(
        status: 400, headers: [("Content-Length", "0"), ("Connection", "close")], body: [])

    /// The `first` of `bytes=first-…`, or zero for no range, a suffix range or anything this origin
    /// would answer whole — the position a read starts at, as Media3's `DataSpec.position` is, which
    /// is what a fault script keys a resource on.
    static func rangeStart(_ range: String?) -> Int {
        guard let range, range.hasPrefix("bytes="), !range.contains(",") else { return 0 }
        let spec = range.dropFirst("bytes=".count).trimmingCharacters(in: .whitespaces)
        guard let dash = spec.firstIndex(of: "-") else { return 0 }
        return Int(spec[..<dash]) ?? 0
    }

    /// `body` whole, or the one byte range `range` asks for.
    ///
    /// spec: RFC 9110 §14.1.2 — `bytes=first-last`, `bytes=first-` and the suffix form
    /// `bytes=-length`, a `last` past the end meaning the end; §14.4 — a satisfied range is a 206 with
    /// `Content-Range: bytes first-last/complete`; §15.5.17 — a range wholly past the end is a 416
    /// with `Content-Range: bytes */complete`. §14.2 lets a server ignore a `Range` it does not
    /// understand or will not serve, and this one ignores a malformed field, another unit and a set
    /// of several ranges by answering the whole body, which a client must accept.
    static func ranged(_ body: [UInt8], _ range: String?) -> Response {
        let whole = Response(status: 200, headers: [], body: body)
        guard let range, range.hasPrefix("bytes="), !range.contains(",") else { return whole }
        let spec = range.dropFirst("bytes=".count).trimmingCharacters(in: .whitespaces)
        guard let dash = spec.firstIndex(of: "-") else { return whole }
        let firstText = spec[..<dash]
        let lastText = spec[spec.index(after: dash)...]
        let count = body.count

        let first: Int
        let last: Int
        if firstText.isEmpty {
            guard let suffix = Int(lastText), suffix >= 0 else { return whole }
            guard suffix > 0, count > 0 else { return unsatisfiable(count) }
            first = max(0, count - suffix)
            last = count - 1
        } else {
            guard let start = Int(firstText), start >= 0 else { return whole }
            // An open end runs to the last byte, which for a start past it is still a range of
            // the right form — and so a 416 rather than a field ignored.
            let end = lastText.isEmpty ? max(start, count - 1) : Int(lastText)
            guard let end, end >= start else { return whole }
            guard start < count else { return unsatisfiable(count) }
            first = start
            last = min(end, count - 1)
        }
        return Response(
            status: 206, headers: [("Content-Range", "bytes \(first)-\(last)/\(count)")],
            body: Array(body[first...last]))
    }

    private static func unsatisfiable(_ count: Int) -> Response {
        Response(status: 416, headers: [("Content-Range", "bytes */\(count)")], body: [])
    }

    /// spec: RFC 8216 §4 — a playlist is `application/vnd.apple.mpegurl`; §3 — packed audio is its
    /// elementary stream's own type, `audio/aac` here, an MPEG-2 transport stream `video/mp2t` and a
    /// fragmented MP4 segment `video/mp4`. Served as a real origin serves them, so that what
    /// `AVPlayer` makes of a type is never a property of the harness.
    static func contentType(of path: String) -> String {
        let name = path.lowercased()
        if name.hasSuffix(".m3u8") { return "application/vnd.apple.mpegurl" }
        if name.hasSuffix(".aac") { return "audio/aac" }
        if name.hasSuffix(".ts") { return "video/mp2t" }
        // spec: RFC 8216 §3.5 — a WebVTT segment; W3C WebVTT §8.1 registers `text/vtt`.
        if name.hasSuffix(".vtt") { return "text/vtt" }
        if name.hasSuffix(".mp4") || name.hasSuffix(".m4s") { return "video/mp4" }
        return "application/octet-stream"
    }
}
