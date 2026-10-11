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

/// One download as the store last reported it — the Kotlin `DownloadItem`. A value: a new one is
/// made on every change, so one a listener kept is what was true when it was handed over.
public struct DownloadItem: Sendable, Equatable, CustomStringConvertible {

    /// The content this is a download of: a ``MediaRequest/contentId``.
    public let contentId: String

    /// Where the download stands.
    public let state: DownloadState

    /// Bytes on disk so far, as the download task counts them.
    public let bytesDownloaded: Int64

    /// How much of the content is on disk, from 0 to 100, or nil until the download has said how
    /// much there is — the Kotlin field, measured here in media time rather than bytes: an
    /// `AVAssetDownloadTask` reports the time ranges it has loaded against the duration it expects,
    /// and never a byte total (ref: Apple, `AVAssetDownloadDelegate`
    /// `urlSession(_:assetDownloadTask:didLoad:totalTimeRangesLoaded:timeRangeExpectedToLoad:)`).
    public let percentDownloaded: Double?

    /// The class the download failed on, where the store was configured with a resilience to name
    /// it (``Downloads/Configuration/resilience``) — nil otherwise, nil for a ``refusal``, and nil
    /// for a manifest that could not be read or a failure in an earlier process, as on Android. Only
    /// when ``state`` is ``DownloadState/failed``.
    public let failure: SuperPlayerError?

    /// Why the store would not download this content, where it refused — only beside
    /// ``DownloadState/failed``, and with ``failure`` nil, because a refusal is not a failure
    /// (ADR-0013 rule 8's #251 addendum).
    public let refusal: DownloadRefusal?

    package init(
        contentId: String, state: DownloadState, bytesDownloaded: Int64 = 0,
        percentDownloaded: Double? = nil, failure: SuperPlayerError? = nil,
        refusal: DownloadRefusal? = nil
    ) {
        self.contentId = contentId
        self.state = state
        self.bytesDownloaded = bytesDownloaded
        self.percentDownloaded = percentDownloaded
        self.failure = failure
        self.refusal = refusal
    }

    /// Equal where every field is, the failure by its class: a `SuperPlayerError` carries the
    /// engine's `NSError`, which compares by identity, and two reports of one failure name one class.
    public static func == (lhs: DownloadItem, rhs: DownloadItem) -> Bool {
        lhs.contentId == rhs.contentId && lhs.state == rhs.state
            && lhs.bytesDownloaded == rhs.bytesDownloaded
            && lhs.percentDownloaded == rhs.percentDownloaded
            && lhs.failure?.causeClass == rhs.failure?.causeClass && lhs.refusal == rhs.refusal
    }

    public var description: String {
        let percent = percentDownloaded.map { "\(Int($0))%" } ?? "?%"
        let why = failure.map { " \($0.causeClass)" } ?? refusal.map { " \($0)" } ?? ""
        return "\(contentId) \(state) \(percent) \(bytesDownloaded)B\(why)"
    }
}

/// Where a download stands — the Kotlin `DownloadState`, less `STOPPED`.
///
/// **No `stopped` on Apple platforms.** Android stops an item and names the condition holding it,
/// because its scheduler is the module's own and can see each one. Here the background session *is*
/// the scheduler (`docs/ios-plan.md` §3.7): a task waiting for an unmetered network, for Low Data
/// Mode to lift or for the system's judgement of power is held by the system, which reports nothing
/// of why. So a held item reads as it was — ``queued`` before its first byte, ``downloading`` after —
/// and the Kotlin `DownloadStopReason` has nothing to carry.
public enum DownloadState: String, Sendable, Hashable, CaseIterable {
    /// Enqueued: its manifest being read, or its task waiting for the system to run it.
    case queued
    /// Fetching — announced by the task's first report of progress, never before it, as the Kotlin
    /// store announces it by the downloader's first report.
    case downloading
    /// Everything is on disk, and a player configured with the store plays it with no network.
    case completed
    /// Ended without completing: ``DownloadItem/failure`` or ``DownloadItem/refusal`` says why.
    case failed
    /// Being removed: its package is being deleted, after which the store forgets it.
    case removing
}

/// Why the store refused to download something — the Kotlin `DownloadRefusal`.
public enum DownloadRefusal: String, Sendable, Hashable, CaseIterable {
    /// The content is live — its duration is indefinite — so there is no end to download and a
    /// copy would be stale by definition (ADR-0013 rule 8). Enqueueing it again reads the manifest
    /// again and is refused again.
    case liveContent
}

/// Hears a store's downloads change — the Kotlin `DownloadsListener`. Called on the main actor, the
/// store's, in the order the changes were made.
@MainActor
public protocol DownloadsListener: AnyObject {

    /// `item` changed: each state it enters, and while downloading each whole percent it passes.
    func downloadChanged(_ item: DownloadItem)

    /// The download of `contentId` was removed: its package is deleted and the store forgets it.
    func downloadRemoved(_ contentId: String)
}
