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

/// What is wrong with this stream, in the words a support engineer needs, before a player is built
/// — the Kotlin `MediaSourceDoctor`, configured by a value rather than built (ADR-0020 rule 5).
///
/// ```swift
/// let doctor = MediaSourceDoctor(configuration: .init(headers: tokens))
/// let report = await doctor.examine(request)   // the same MediaRequest a player adopts
/// for finding in report.findings {
///     log(finding.pathology.id, finding.severity, finding.specCitation, finding.cause)
/// }
/// ```
///
/// A variant that declares no `CODECS` comes back as a ``Finding`` naming the defect, its severity,
/// the clause it departs from and the misconfiguration that produces it — rather than as a player
/// that behaves oddly ten minutes later.
///
/// ## What it reads, and how
///
/// It fetches the multivariant playlist and every media playlist it names, and **downloads no
/// segment**: the pathologies are declarations, and fetching media to check one would make a
/// preflight cost what a start costs (ADR-0015 rule 7). It reads them with **a lint of its own
/// written to RFC 8216**, because AVFoundation exposes no playlist parser — ADR-0020 rule 8's
/// departure from ADR-0015 rule 6, bounded by the lint deciding nothing about playback. A finding
/// on this platform therefore reads a document as RFC 8216 describes it rather than as AVFoundation
/// does. HLS is the only protocol read, because it is the only one `AVPlayer` plays: an MPD comes
/// back ``Pathology/manifestUnreadable``, which is what a player of it would meet too.
///
/// A playlist that cannot be fetched or read is a finding — ``Pathology/manifestUnreachable`` or
/// ``Pathology/manifestUnreadable`` — and never a throw, so `examine` is not `throws` at all.
///
/// ## Lifetime
///
/// A doctor holds no thread, registers nothing with the platform and keeps no connection between
/// calls: each examination opens a session and finishes it, so there is nothing to release, and one
/// may be made per question or kept for the life of a screen. What it keeps is the credential its
/// provider last returned, so that a doctor asked twice pays one refusal rather than two.
public final class MediaSourceDoctor: Sendable {

    /// How a doctor fetches — what the players it answers for are configured with, because its
    /// answer is meant to be theirs (ADR-0015 rule 7).
    public struct Configuration: Sendable {

        /// The provider a player of this content is configured with through
        /// `Resilience.standard(headers:)`, so the credential the app mints, and the refresh a 401
        /// or 403 triggers, are the ones those players carry. Nil fetches with no credential, which
        /// is what a player configured with none meets. Taken as the provider rather than as the
        /// resilience, because this product depends on core alone of the two (ADR-0015 rule 1) and
        /// the provider is core's.
        public var headers: (any HeaderProvider)?

        public init(headers: (any HeaderProvider)? = nil) {
            self.headers = headers
        }
    }

    private let credentials: Credentials

    /// Which optional layers every examination travels, read off the configuration once.
    private let chain: Set<ChainLayer>

    public init(configuration: Configuration = Configuration()) {
        credentials = Credentials(provider: configuration.headers)
        chain = configuration.headers == nil ? [] : [.headerRefresh]
    }

    /// Fetches `request`'s playlists and answers what is wrong with them.
    ///
    /// The first entry of `MediaRequest.sources` is examined, because it is the one a player plays;
    /// the rest are what rung 4 would reach for, and a stream nothing has fallen back to has not
    /// been asked to serve anything.
    ///
    /// Called from any context, and it suspends for as long as the fetches take. Cancelling the task
    /// that awaits it ends the fetching, and what could not be fetched then reads as unreachable —
    /// so **a report from a cancelled task is not evidence about the stream**, and a caller that
    /// cancelled discards it rather than logging it against a CDN.
    public func examine(_ request: MediaRequest) async -> DiagnosticReport {
        let session = URLSession(configuration: Self.sessionConfiguration)
        defer { session.finishTasksAndInvalidate() }
        let findings = await ManifestExamination(session: session, credentials: credentials)
            .examine(request.sources[0])
        return DiagnosticReport(contentId: request.contentId, findings: findings, chain: chain)
    }

    /// Ephemeral, with no local cache: a doctor reads what a player would be served now, and a
    /// playlist this process cached — a live one above all — is not that. A CDN's cache is left in
    /// the path, because it is what a player meets too.
    private static var sessionConfiguration: URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        return configuration
    }
}
