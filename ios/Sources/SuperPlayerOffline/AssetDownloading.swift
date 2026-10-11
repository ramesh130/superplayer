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
import Foundation

/// What runs a download once the store has decided it: the background `AVAssetDownloadURLSession`
/// in a shipped store (`SystemAssetDownloads`), and a stand-in under test — the one `package`
/// protocol this product defines, as `FairPlayKeySession` is `SuperPlayerDRM`'s.
///
/// **Why there is a seam at all: the simulator test host cannot run an `AVAssetDownloadTask`.**
/// Forced by #424 on the iOS 27.1 simulator under Xcode 27: an `AVAssetDownloadURLSession` refuses
/// any configuration but a background one (`NSInvalidArgumentException`, "Can only create an
/// AVAssetDownloadURLSession from a background configuration"), and a task on a background session
/// made inside `xctest` completes at once with `NSURLErrorDomain` -1, "unknown error", having sent
/// the loopback origin no request at all — the background transfer daemon serves an app, and
/// `xctest` is not one. So everything the store decides — the manifest read, the live refusal, the
/// rendition and the languages, the constraints, the index, the retry and its budget, the naming of
/// a failure, what a player opens — runs as shipped under `ios/check`, against the real
/// `AVURLAsset` reading the loopback origin; and the transfer itself is this protocol's. That a task
/// downloads what its configuration names, lands a `.movpkg` that plays offline, and survives the
/// process, is the device ticket's (#436), and `docs/testing.md`'s *Downloads on iOS* says so.
///
/// Every event is delivered through ``DownloaderContext/events``, from any thread, in the order the
/// downloader observed it; the store hops them onto the main actor in that order.
package protocol AssetDownloading: AnyObject, Sendable {

    /// Begins downloading `plan`, or begins it again: a failed task is re-enqueued by starting its
    /// plan once more (`docs/ios-plan.md` §3.7, *Retry budgets*).
    func start(_ plan: DownloadPlan)

    /// Stops `contentId`'s task, if one runs, and deletes what it wrote; no event follows.
    func cancel(_ contentId: String)

    /// The content ids whose tasks the session still holds — on a store opened in a new process,
    /// what an earlier one left running, re-attached rather than started again (ADR-0013 rule 9,
    /// the platform's promise on iOS).
    func inFlight() async -> Set<String>

    /// The asset an in-progress download of `contentId` plays from — its task's own `urlAsset` —
    /// or nil where none is in progress.
    func inProgressAsset(of contentId: String) -> AVURLAsset?

    /// Holds `completion` until the session has delivered every event the system woke the app for,
    /// then calls it on the main queue: the app's `handleEventsForBackgroundURLSession` hand-off.
    func handleBackgroundEvents(completion: @escaping @Sendable () -> Void)

    /// Lets the session go. Tasks the system runs are not cancelled: a store closed is not a
    /// download removed.
    func close()
}

/// One download, decided: what the store hands an ``AssetDownloading`` — the content, the rendition
/// and the languages, as values rather than AVFoundation objects so the decision is what a test reads.
package struct DownloadPlan: Sendable, Hashable {

    /// The content this downloads.
    package let contentId: String

    /// The multivariant playlist it reads: the request's first source.
    package let source: URL

    /// The title the system shows for the download in Settings' storage list.
    package let title: String

    /// The peak bitrate (`BANDWIDTH`) of the one variant to download, or nil where the source
    /// declares no variants and there is nothing to choose.
    package let variantPeakBitRateBps: Double?

    /// The audio renditions to download, by the language tags the content declares them under.
    package let audioLanguages: [String]

    /// The subtitle renditions to download, likewise.
    package let subtitleLanguages: [String]

    package init(
        contentId: String, source: URL, title: String, variantPeakBitRateBps: Double?,
        audioLanguages: [String], subtitleLanguages: [String]
    ) {
        self.contentId = contentId
        self.source = source
        self.title = title
        self.variantPeakBitRateBps = variantPeakBitRateBps
        self.audioLanguages = audioLanguages
        self.subtitleLanguages = subtitleLanguages
    }
}

/// What a download did, as an ``AssetDownloading`` reports it.
package enum DownloadEvent: Sendable {
    /// `fraction` of the content's duration is on disk, and `bytes` in all.
    case progress(contentId: String, fraction: Double, bytes: Int64)
    /// The download finished, and its package is at `package`, inside the store's directory.
    case finished(contentId: String, package: URL)
    /// The task failed with `error`, the session's own.
    case failed(contentId: String, error: any Error)
}

/// What the store hands the downloader it makes: the session's identity, its constraints, where
/// packages land, and where events go.
package struct DownloaderContext: Sendable {

    /// The background session's identifier: the store's (``Downloads/Configuration/sessionIdentifier``).
    package let sessionIdentifier: String

    /// The network and power conditions a task runs under.
    package let constraints: DownloadConstraints

    /// The directory inside the consumer's that finished packages are moved into.
    package let packagesDirectory: URL

    /// Where every event goes, from any thread, in order.
    package let events: @Sendable (DownloadEvent) -> Void

    package init(
        sessionIdentifier: String, constraints: DownloadConstraints, packagesDirectory: URL,
        events: @escaping @Sendable (DownloadEvent) -> Void
    ) {
        self.sessionIdentifier = sessionIdentifier
        self.constraints = constraints
        self.packagesDirectory = packagesDirectory
        self.events = events
    }
}

/// The conditions a download runs under — ADR-0013 rules 10 and 11 read for iOS (`docs/ios-plan.md`
/// §3.7), as the background session's configuration states them.
///
/// **Coarser than Android's, and said so.** Android stops a download on a metered network, on a low
/// battery and on low storage, each named on the item. Here each is a flag the *system* honours on
/// the session, which reports none of them:
///
/// - **The network.** ``allowsExpensiveNetworkAccess`` and ``allowsCellularAccess`` are both false
///   unless ``Downloads/Configuration/meteredNetworksAllowed`` relaxes them: "expensive" is the
///   platform's reading of a metered link — cellular, and a personal hotspot — which is Android's
///   `NET_CAPABILITY_NOT_METERED` in Apple's words; cellular is named too so a device that reads a
///   cellular link as inexpensive still keeps the default. ref: Apple, `URLSessionConfiguration`
///   `allowsExpensiveNetworkAccess`, `allowsCellularAccess`.
/// - **Low Data Mode**, Android's Data Saver: ``allowsConstrainedNetworkAccess`` is always false,
///   relaxed by nothing, as Data Saver still holds a store that allows metered networks. ref: Apple,
///   `allowsConstrainedNetworkAccess`.
/// - **The battery and the system's own conditions**: ``isDiscretionary`` is true, which hands the
///   system the decision of when a transfer runs "for optimal performance" — it waits for power and
///   a good network. That is coarser than Android's battery-not-low, which is a threshold this
///   platform does not let a background session state; storage-not-low is the system's own, and a
///   full disk fails the task (`Storage.Full`). ref: Apple, `URLSessionConfiguration.isDiscretionary`.
package struct DownloadConstraints: Sendable, Hashable {
    package let allowsExpensiveNetworkAccess: Bool
    package let allowsCellularAccess: Bool
    package let allowsConstrainedNetworkAccess: Bool
    package let isDiscretionary: Bool

    /// The constraints a store runs under, metered networks allowed or not.
    package init(meteredNetworksAllowed: Bool) {
        allowsExpensiveNetworkAccess = meteredNetworksAllowed
        allowsCellularAccess = meteredNetworksAllowed
        allowsConstrainedNetworkAccess = false
        isDiscretionary = true
    }
}
