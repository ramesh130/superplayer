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

// tvOS's SDK has no `AVAssetDownloadURLSession`; there `Downloads(configuration:)` refuses typed.
#if !os(tvOS)

    /// The shipped ``AssetDownloading``: one background `AVAssetDownloadURLSession` whose identifier is
    /// the store's (`docs/ios-plan.md` §3.7).
    ///
    /// **None of this runs under `ios/check`** — `AssetDownloading` says why — so every line here is
    /// written against Apple's documented API and is the device ticket's (#436) to exercise: a task per
    /// plan, its rendition named by an `AVAssetVariantQualifier` and its languages by media selections,
    /// its progress from the time ranges it reports, its package moved into the consumer's directory
    /// once the task completes, and the session re-attached by identifier in a new process.
    ///
    /// ref: Apple, "Using AVFoundation to play and persist HTTP Live Streams" (WWDC 2020 session 10655
    /// and the `AVAssetDownloadConfiguration` documentation), and `AVAssetDownloadDelegate`.
    final class SystemAssetDownloads: NSObject, AssetDownloading, AVAssetDownloadDelegate,
        @unchecked Sendable
    {
        // @unchecked: the maps below are read and written under `lock` only; the session is fixed once
        // `init` returns, and the context is a value.

        private let context: DownloaderContext
        private let lock = NSLock()
        private var session: AVAssetDownloadURLSession?
        private var tasks: [String: AVAssetDownloadTask] = [:]
        private var locations: [String: URL] = [:]
        /// The plan each content id was last started on, between `start` and its task's creation —
        /// so a cancel in that window is seen, and a second start supersedes the first.
        private var starting: [String: UUID] = [:]
        private var backgroundCompletion: (@Sendable () -> Void)?

        init(context: DownloaderContext) {
            self.context = context
            super.init()
            let configuration = URLSessionConfiguration.background(
                withIdentifier: context.sessionIdentifier)
            configuration.allowsExpensiveNetworkAccess =
                context.constraints.allowsExpensiveNetworkAccess
            configuration.allowsCellularAccess = context.constraints.allowsCellularAccess
            configuration.allowsConstrainedNetworkAccess =
                context.constraints.allowsConstrainedNetworkAccess
            configuration.isDiscretionary = context.constraints.isDiscretionary
            // So the system relaunches the app in the background for the session's events — what the
            // app's `handleEventsForBackgroundURLSession` hand-off then receives.
            configuration.sessionSendsLaunchEvents = true
            // One delegate queue, serial, so events reach the store in the order the session made them.
            let queue = OperationQueue()
            queue.maxConcurrentOperationCount = 1
            session = AVAssetDownloadURLSession(
                configuration: configuration, assetDownloadDelegate: self, delegateQueue: queue)
        }

        func start(_ plan: DownloadPlan) {
            let token = UUID()
            lock.withLock { starting[plan.contentId] = token }
            Task { await self.begin(plan, token) }
        }

        private func begin(_ plan: DownloadPlan, _ token: UUID) async {
            let asset = AVURLAsset(url: plan.source)
            let configuration = AVAssetDownloadConfiguration(asset: asset, title: plan.title)
            if let rate = plan.variantPeakBitRateBps {
                // The variant the store chose, by the figure it was chosen on; AVFoundation downloads the
                // variant the qualifier admits, which is that one.
                configuration.primaryContentConfiguration.variantQualifiers = [
                    AVAssetVariantQualifier(
                        predicate: NSPredicate(format: "peakBitRate == %f", rate))
                ]
            }
            do {
                let selections = try await Self.mediaSelections(of: asset, for: plan)
                if !selections.isEmpty {
                    configuration.primaryContentConfiguration.mediaSelections = selections
                }
            } catch {
                context.events(.failed(contentId: plan.contentId, error: error))
                return
            }
            let task: AVAssetDownloadTask? = lock.withLock {
                // Cancelled, superseded, or closed while the media selections were read: no task.
                guard let session, starting[plan.contentId] == token else { return nil }
                starting[plan.contentId] = nil
                let task = session.makeAssetDownloadTask(downloadConfiguration: configuration)
                task.taskDescription = plan.contentId
                tasks[plan.contentId] = task
                return task
            }
            task?.resume()
        }

        /// One media selection per language in `plan`, each the asset's preferred selection with that
        /// rendition chosen in its group: a download configuration fetches every selection it is given.
        private static func mediaSelections(of asset: AVURLAsset, for plan: DownloadPlan)
            async throws
            -> [AVMediaSelection]
        {
            let preferred = try await asset.load(.preferredMediaSelection)
            var selections: [AVMediaSelection] = []
            for (characteristic, languages) in [
                (AVMediaCharacteristic.audible, plan.audioLanguages),
                (AVMediaCharacteristic.legible, plan.subtitleLanguages),
            ] where !languages.isEmpty {
                guard let group = try await asset.loadMediaSelectionGroup(for: characteristic)
                else {
                    continue
                }
                for language in languages {
                    guard
                        let option = group.options.first(where: {
                            $0.extendedLanguageTag == language
                        }),
                        let selection = preferred.mutableCopy() as? AVMutableMediaSelection
                    else { continue }
                    selection.select(option, in: group)
                    selections.append(selection)
                }
            }
            return selections
        }

        func cancel(_ contentId: String) {
            let (task, location) = lock.withLock {
                starting[contentId] = nil
                return (
                    tasks.removeValue(forKey: contentId), locations.removeValue(forKey: contentId)
                )
            }
            task?.cancel()
            // What a cancelled task wrote stays where the system put it unless it is deleted. A task
            // re-attached in a new process has told this one no location yet, so what it wrote before
            // the relaunch is the system's to reclaim — a gap #436 is to measure on a device.
            if let location { try? FileManager.default.removeItem(at: location) }
        }

        func inFlight() async -> Set<String> {
            guard let session = lock.withLock({ session }) else { return [] }
            let running = await session.allTasks.compactMap { $0 as? AVAssetDownloadTask }
                .filter { $0.state == .running || $0.state == .suspended }
            lock.withLock {
                for task in running {
                    if let id = task.taskDescription { tasks[id] = task }
                }
            }
            return Set(running.compactMap(\.taskDescription))
        }

        func inProgressAsset(of contentId: String) -> AVURLAsset? {
            lock.withLock { tasks[contentId]?.urlAsset }
        }

        func handleBackgroundEvents(completion: @escaping @Sendable () -> Void) {
            lock.withLock { backgroundCompletion = completion }
        }

        func close() {
            let session = lock.withLock {
                let current = self.session
                self.session = nil
                return current
            }
            session?.finishTasksAndInvalidate()
        }

        // MARK: AVAssetDownloadDelegate

        func urlSession(
            _ session: URLSession, assetDownloadTask: AVAssetDownloadTask,
            willDownloadTo location: URL
        ) {
            guard let id = assetDownloadTask.taskDescription else { return }
            lock.withLock { locations[id] = location }
        }

        func urlSession(
            _ session: URLSession, assetDownloadTask: AVAssetDownloadTask,
            didLoad timeRange: CMTimeRange, totalTimeRangesLoaded loadedTimeRanges: [NSValue],
            timeRangeExpectedToLoad: CMTimeRange
        ) {
            guard let id = assetDownloadTask.taskDescription else { return }
            let expected = timeRangeExpectedToLoad.duration.seconds
            guard expected > 0, expected.isFinite else { return }
            let loaded = loadedTimeRanges.reduce(0.0) { $0 + $1.timeRangeValue.duration.seconds }
            context.events(
                .progress(
                    contentId: id, fraction: min(1, loaded / expected),
                    bytes: assetDownloadTask.countOfBytesReceived))
        }

        func urlSession(
            _ session: URLSession, assetDownloadTask: AVAssetDownloadTask,
            didFinishDownloadingTo location: URL
        ) {
            guard let id = assetDownloadTask.taskDescription else { return }
            lock.withLock { locations[id] = location }
        }

        func urlSession(
            _ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?
        ) {
            guard let id = task.taskDescription else { return }
            let location = lock.withLock {
                tasks.removeValue(forKey: id)
                return locations.removeValue(forKey: id)
            }
            if let error {
                // A cancellation is the store's own, made by ``cancel(_:)``, which reports nothing.
                let failure = error as NSError
                if failure.domain == NSURLErrorDomain, failure.code == NSURLErrorCancelled {
                    return
                }
                context.events(.failed(contentId: id, error: error))
                return
            }
            guard let location else {
                context.events(.failed(contentId: id, error: CocoaError(.fileNoSuchFile)))
                return
            }
            // Into the consumer's directory, as its package (ADR-0013 rule 5 read for iOS): the system
            // chose where the task wrote, and a package may be moved once its task has completed.
            let destination = context.packagesDirectory.appendingPathComponent(
                Self.packageName(of: id))
            do {
                try FileManager.default.createDirectory(
                    at: context.packagesDirectory, withIntermediateDirectories: true)
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                try FileManager.default.moveItem(at: location, to: destination)
                context.events(.finished(contentId: id, package: destination))
            } catch {
                context.events(.failed(contentId: id, error: error))
            }
        }

        func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
            let completion = lock.withLock {
                let held = backgroundCompletion
                backgroundCompletion = nil
                return held
            }
            // The system's own rule: the hand-off's completion handler is called on the main queue.
            if let completion { DispatchQueue.main.async { completion() } }
        }

        /// The package's file name: the content id, percent-encoded so any id is one path component.
        static func packageName(of contentId: String) -> String {
            (contentId.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? contentId)
                + ".movpkg"
        }
    }
#endif
