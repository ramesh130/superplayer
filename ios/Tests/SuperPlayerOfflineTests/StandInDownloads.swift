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
import SuperPlayerOffline
import os

/// The download task stood in at `AssetDownloading`, the one seam the store has, because the
/// simulator test host cannot run an `AVAssetDownloadTask` (that protocol's doc comment records how
/// it fails). `FairPlayKeySession`'s stand-in is the precedent.
///
/// It does what a task configured with the plan is documented to do, over the loopback origin: it
/// fetches the multivariant playlist, the one variant's media playlist and segments the plan names
/// by `BANDWIDTH`, and each audio and subtitle rendition the plan names by language — so a count at
/// the origin is a count of what the plan asked for — reporting progress per segment in media time.
/// What it lands is not a `.movpkg`, which only the system can write: it is the variant's segments
/// concatenated into one ADTS file under the packages directory, which `AVPlayer` plays from a
/// `file:` URL (observed, #424), so "a player of a downloaded content id opens what was downloaded,
/// with no request" is asserted on a real player. That the real package plays is #436's.
final class StandInDownloads: AssetDownloading, @unchecked Sendable {
    // @unchecked: every field below is read and written under `state` only.

    let context: DownloaderContext
    private let state = OSAllocatedUnfairLock(initialState: State())
    private let session = URLSession(configuration: .ephemeral)

    private struct State {
        var started: [DownloadPlan] = []
        var running: [String: Task<Void, Never>] = [:]
        var tokens: [String: UUID] = [:]
        var heldBySession: Set<String> = []
        var diskFull: Set<String> = []
        var taskAssets: [String: AVURLAsset] = [:]
        var backgroundCompletion: (@Sendable () -> Void)?
        var released = false
    }

    init(context: DownloaderContext, heldBySession: Set<String> = []) {
        self.context = context
        state.withLock { $0.heldBySession = heldBySession }
    }

    /// Every plan the store started, in order — a re-enqueue is a second entry.
    var started: [DownloadPlan] { state.withLock { $0.started } }

    /// Whether the store let the session go.
    var released: Bool { state.withLock { $0.released } }

    /// Makes `contentId`'s task fail as the system fails one on a full disk:
    /// `AVError.diskFull`, after its first segment.
    func failWithDiskFull(_ contentId: String) {
        state.withLock { _ = $0.diskFull.insert(contentId) }
    }

    /// Delivers the background session's "finished events", as the system does once it has.
    func finishBackgroundEvents() {
        let completion = state.withLock { state in
            defer { state.backgroundCompletion = nil }
            return state.backgroundCompletion
        }
        if let completion { DispatchQueue.main.async { completion() } }
    }

    // MARK: AssetDownloading

    func start(_ plan: DownloadPlan) {
        let token = UUID()
        state.withLock { state in
            state.started.append(plan)
            state.tokens[plan.contentId] = token
            state.running[plan.contentId] = Task { await self.run(plan, token) }
        }
    }

    func cancel(_ contentId: String) {
        state.withLock { $0.running.removeValue(forKey: contentId) }?.cancel()
    }

    func inFlight() async -> Set<String> { state.withLock { $0.heldBySession } }

    /// A task's own `urlAsset`: one asset of the plan's source per task, which plays from what the
    /// task has written and fetches the rest — kept, so a test can tell it is the one a player opened.
    func inProgressAsset(of contentId: String) -> AVURLAsset? {
        state.withLock { state in
            guard state.running[contentId] != nil,
                let plan = state.started.last(where: { $0.contentId == contentId })
            else { return nil }
            if let asset = state.taskAssets[contentId] { return asset }
            let asset = AVURLAsset(url: plan.source)
            state.taskAssets[contentId] = asset
            return asset
        }
    }

    func handleBackgroundEvents(completion: @escaping @Sendable () -> Void) {
        state.withLock { $0.backgroundCompletion = completion }
    }

    func close() {
        state.withLock { $0.released = true }
    }

    // MARK: The transfer

    private func run(_ plan: DownloadPlan, _ token: UUID) async {
        do {
            let multivariant = try await text(plan.source)
            let playlist =
                try Self.variantURI(in: multivariant, bandwidth: plan.variantPeakBitRateBps)
                .map { URL(string: $0, relativeTo: plan.source)!.absoluteURL } ?? plan.source
            var renditions = [(playlist, true)]
            for (type, languages) in [
                ("AUDIO", plan.audioLanguages), ("SUBTITLES", plan.subtitleLanguages),
            ] {
                for language in languages {
                    if let uri = Self.renditionURI(in: multivariant, type: type, language: language)
                    {
                        renditions.append(
                            (URL(string: uri, relativeTo: plan.source)!.absoluteURL, false))
                    }
                }
            }
            var segments: [(URL, Bool)] = []
            for (url, isVariant) in renditions {
                let media = try await text(url)
                segments += media.split(separator: "\n").map(String.init)
                    .filter { !$0.isEmpty && !$0.hasPrefix("#") }
                    .map { (URL(string: $0, relativeTo: url)!.absoluteURL, isVariant) }
            }
            var written = Data()
            var bytes: Int64 = 0
            for (done, (url, isVariant)) in segments.enumerated() {
                try Task.checkCancellation()
                if done == 1, state.withLock({ $0.diskFull.contains(plan.contentId) }) {
                    throw AVError(.diskFull)
                }
                let data = try await fetch(url)
                bytes += Int64(data.count)
                if isVariant { written.append(data) }
                context.events(
                    .progress(
                        contentId: plan.contentId,
                        fraction: Double(done + 1) / Double(segments.count), bytes: bytes))
            }
            try Task.checkCancellation()
            try FileManager.default.createDirectory(
                at: context.packagesDirectory, withIntermediateDirectories: true)
            let package = context.packagesDirectory.appendingPathComponent(
                "\(plan.contentId).aac")
            try written.write(to: package)
            context.events(.finished(contentId: plan.contentId, package: package))
        } catch is CancellationError {
            return
        } catch {
            if Task.isCancelled { return }
            context.events(.failed(contentId: plan.contentId, error: error))
        }
        // Only this run's own entry: a retry started before this line ran is the current one.
        state.withLock { state in
            guard state.tokens[plan.contentId] == token else { return }
            state.running[plan.contentId] = nil
            state.tokens[plan.contentId] = nil
        }
    }

    private func text(_ url: URL) async throws -> String {
        String(decoding: try await fetch(url), as: UTF8.self)
    }

    /// `url`'s body, or the error a refused response is to AVFoundation: a `CoreMediaErrorDomain`
    /// code naming the status where `ErrorClassifier` knows one, as #419 observed them.
    private func fetch(_ url: URL) async throws -> Data {
        let (data, response) = try await session.data(from: url)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 200
        guard (200..<300).contains(status) else {
            let codes = [401: -16840, 403: -12660, 404: -12938, 410: -12668, 500: -16847]
            throw NSError(
                domain: "CoreMediaErrorDomain", code: codes[status] ?? -12938,
                userInfo: [NSLocalizedDescriptionKey: "HTTP \(status)"])
        }
        return data
    }

    /// The URI after the `EXT-X-STREAM-INF` whose `BANDWIDTH` is `bandwidth`, or nil for a media
    /// playlist or no bandwidth asked for.
    private static func variantURI(in playlist: String, bandwidth: Double?) throws -> String? {
        guard let bandwidth else { return nil }
        let lines = playlist.split(separator: "\n").map(String.init)
        for (index, line) in lines.enumerated()
        where line.hasPrefix("#EXT-X-STREAM-INF:")
            && attribute("BANDWIDTH", in: line) == String(Int(bandwidth))
        {
            return lines[index + 1]
        }
        throw URLError(.resourceUnavailable)
    }

    private static func renditionURI(in playlist: String, type: String, language: String)
        -> String?
    {
        playlist.split(separator: "\n").map(String.init).first { line in
            line.hasPrefix("#EXT-X-MEDIA:") && attribute("TYPE", in: line) == type
                && attribute("LANGUAGE", in: line) == language
        }.flatMap { attribute("URI", in: $0) }
    }

    private static func attribute(_ name: String, in line: String) -> String? {
        let body = line.drop { $0 != ":" }.dropFirst()
        for pair in body.split(separator: ",") {
            let parts = pair.split(separator: "=", maxSplits: 1)
            if parts.count == 2, parts[0] == name {
                return parts[1].trimmingCharacters(in: CharacterSet(charactersIn: "\""))
            }
        }
        return nil
    }
}
