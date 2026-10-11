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

// Detecting a live playlist that stopped advancing, and naming it with a `StaleLivePlaylistError` —
// the half of the Kotlin `LivePlaylistRevalidation` that ports (`docs/ios-plan.md` §3.1). The other
// half, the reload past the caches, does not: `AVPlayer` fetches its own playlists, and nothing asks
// it to send a request header (ADR-0020 rule 6). So this reads and never acts on a request.
//
// ## Why core watches at all
//
// Because AVFoundation does not fail. A playlist that stops changing leaves an `AVPlayer` waiting at
// the live edge with no error and no end: its error log gains a -12888 per reload ("unchanged for
// longer than 1.5 × target duration") and nothing surfaces it (`HostileManifestCorpusTests`' frozen
// live row, #412). A session that neither plays nor fails is the Kotlin `FaultSweepTest`'s
// `STALLED`, the outcome no taxonomy rescues — so on Apple platforms the defect has to be *detected*
// before it can be named, where Android's engine at least fails the load.
//
// **Only on a player configured with resilience** (ADR-0011 rule 14). On Android this is core's and
// on for everyone, because there the session ends either way and the layer only types an ending that
// would come untyped. Here detecting it *ends* a session AVFoundation would have gone on waiting in,
// and ending a session with an error is what rung 6 is — which is the resilience product's delivery
// on this platform, and why a player without one keeps AVFoundation's behaviour exactly.
// `ResiliencePaysNothingTests` counts the registrations.
//
// ## What "unchanged" is read from
//
// The Kotlin layer parses each playlist body for its media sequence. `AVPlayer` hands over no body,
// so the progress read is the **live edge** — the end of the item's last `seekableTimeRanges` — which
// moves exactly when a reload brings a new segment, and is AVFoundation's own parse of the playlist
// rather than a second one. It is the item's edge rather than one playlist's, so an audio rendition
// whose playlist froze while the video's advanced is not judged — the events name a playlist and
// carry no body to say it was unchanged. On iOS and tvOS 18 the answers it is judged at are the
// `AVMetricHLSPlaylistRequestEvent`s for the media playlist, which also carry the response headers
// the likely cause is read from; below 18 the judge is AVFoundation's own -12888, which says
// "unchanged" in so many words and nothing else. Both registrations are in `ObservedSignals.swift`.

/// One answered playlist request, reduced to what the judgement reads.
struct PlaylistAnswer: Equatable {
    /// The playlist requested — AVFoundation's resource URL, since the event's own `url` reads nil
    /// on the iOS 27 runtime (observed, #421).
    var url: URL?
    var isMultivariant: Bool
    /// When the response finished arriving.
    var answeredAt: Date
    /// Whether the response was a 2xx — anything else is a failure the classifier is for.
    var succeeded: Bool
    var cacheControl: String?
    var age: String?
}

/// The judgement itself, over values, so every bound is tested without playing anything
/// (`StaleLivePlaylistJudgementTests`). One per item: a playlist's history belongs to the item
/// following it.
struct StalePlaylistJudgement {

    /// How many target durations a live playlist may go without a new segment before it is late —
    /// the Kotlin `UPDATE_BOUND_TARGET_DURATIONS`.
    ///
    /// spec: RFC 8216 §6.2.1 — a new version with at least one new segment within one and a half
    /// target durations of the previous one. Read here only for the likely cause: a cache lifetime
    /// past it is a copy held longer than a live playlist may stand still.
    static let updateBoundTargetDurations = 1.5

    /// How many target durations without a new segment before the session ends typed — the Kotlin
    /// `giveUpBoundTargetDurations`, 2.5, kept as the number rather than derived as it is there.
    ///
    /// The Kotlin bound is Media3's stuck-playlist coefficient (3.5) less two unchanged reloads of
    /// margin, so the typed failure lands before the engine's untyped one. AVFoundation has no
    /// untyped ending to beat — it waits — so the derivation has nothing to derive from, and the
    /// number is kept so that ``StaleLivePlaylistError/unchangedForMs`` means the same on both
    /// platforms: a frozen stream ends at the same point of its life on either. Still well past
    /// RFC 8216 §6.2.1's one and a half, so no conforming stream is ended by it.
    static let giveUpBoundTargetDurations = 2.5

    /// How many answers must arrive with the edge unmoved before any of them is judged: the port of
    /// the Kotlin `STALE_HISTORY_TARGET_DURATIONS` guard, in the one form AVFoundation allows.
    ///
    /// The Kotlin guard starts a history afresh after a gap in loads, so a player that stopped and
    /// came back is not judged against how long it was away. A gap cannot be read that way here,
    /// because AVFoundation backs its reloads of an unchanged playlist off — four target durations
    /// apart were observed (#421) — and a gap bound would forgive exactly the playlist it is for. So
    /// the first answer after any gap is never the one judged: it may carry the segments the gap
    /// missed, and the live edge reads them a moment later; the second answer sees that edge.
    static let answersBeforeJudging = 2

    /// How many of AVFoundation's own "unchanged" entries end the session below iOS 18.
    ///
    /// AVFoundation logs its -12888 once per unchanged reload past one and a half target durations,
    /// and RFC 8216 §6.3.4 spaces unchanged reloads at least half a target duration apart, so the
    /// third entry is at least 1.5 + 0.5 + 0.5 = 2.5 target durations in: the same bound as
    /// ``giveUpBoundTargetDurations``, reached by AVFoundation's count where no target is readable.
    static let unchangedEntriesBeforeGivingUp = 3

    private var longestSegmentSeconds: Double?
    private var liveEdgeSeconds: Double?
    private var edgeAdvancedAt: Date?
    private var answersSinceAdvance = 0
    private var unchangedEntries = 0
    /// Whether any answer since the edge last moved said a cache holds the copy — sticky, as the
    /// Kotlin `cacheEvidence` is.
    private var cacheEvidence = false

    /// The target duration, in milliseconds, as the longest segment read so far says it is at least,
    /// or nil before any segment.
    ///
    /// `AVPlayer` reports no `EXT-X-TARGETDURATION`, so it is read from what the tag constrains.
    /// spec: RFC 8216 §4.3.3.1 — every `EXTINF` duration, rounded to the nearest integer, is at most
    /// the target duration, so the longest one rounded is the smallest value the tag may hold; and
    /// never less than the segment itself, so a sub-second segment does not make a bound of zero.
    /// The reading's one gap: a playlist declaring a target far above its segments' lengths is
    /// judged against its segments, which on a stream that publishes them in bursts would end it
    /// early.
    var targetDurationMs: Int64? {
        longestSegmentSeconds.map { Int64((max($0.rounded(), $0) * 1_000).rounded()) }
    }

    mutating func segmentAnswered(durationSeconds: Double) {
        guard durationSeconds.isFinite, durationSeconds > 0 else { return }
        longestSegmentSeconds = max(longestSegmentSeconds ?? 0, durationSeconds)
    }

    /// The live edge read `seconds`, at `now`: a later edge than any before is a new segment, and the
    /// history starts over from it.
    mutating func liveEdgeRead(_ seconds: Double, at now: Date) {
        guard seconds.isFinite else { return }
        if let liveEdgeSeconds, seconds <= liveEdgeSeconds { return }
        liveEdgeSeconds = seconds
        edgeAdvancedAt = now
        answersSinceAdvance = 0
        unchangedEntries = 0
        cacheEvidence = false
    }

    /// Judges one answered playlist request of an item that is `isLive`, answering the error to end
    /// the session on, or nil. iOS and tvOS 18 and later.
    mutating func playlistAnswered(_ answer: PlaylistAnswer, isLive: Bool)
        -> StaleLivePlaylistError?
    {
        // A multivariant playlist carries no segments, and a failed answer is the classifier's.
        guard isLive, !answer.isMultivariant, answer.succeeded, let url = answer.url,
            let edgeAdvancedAt, let target = targetDurationMs
        else { return nil }
        answersSinceAdvance += 1
        cacheEvidence =
            cacheEvidence
            || Self.cachedPastTheUpdateBoundSeconds(answer.cacheControl, targetDurationMs: target)
                != nil
            || (answer.age.flatMap { Int64($0.trimmingCharacters(in: .whitespaces)) }).map {
                Double($0 * 1_000) > Self.updateBoundTargetDurations * Double(target)
            } == true
        let unchangedForMs = Int64(
            (answer.answeredAt.timeIntervalSince(edgeAdvancedAt) * 1_000).rounded())
        guard answersSinceAdvance >= Self.answersBeforeJudging,
            Double(unchangedForMs) > Self.giveUpBoundTargetDurations * Double(target)
        else { return nil }
        return StaleLivePlaylistError(
            playlistURL: url, targetDurationMs: target, unchangedForMs: unchangedForMs,
            servedCacheControl: answer.cacheControl,
            likelyCause: cacheEvidence ? .intermediaryCache : .origin)
    }

    /// Counts AVFoundation's own "playlist unchanged" entry for `playlist` on an item that is
    /// `isLive`, answering the error to end the session on, or nil. Below iOS and tvOS 18.
    mutating func unchangedLogged(playlist: URL?, isLive: Bool) -> StaleLivePlaylistError? {
        guard isLive, let playlist else { return nil }
        unchangedEntries += 1
        guard unchangedEntries >= Self.unchangedEntriesBeforeGivingUp else { return nil }
        return StaleLivePlaylistError(
            playlistURL: playlist, targetDurationMs: nil, unchangedForMs: nil,
            servedCacheControl: nil, likelyCause: nil)
    }

    /// How long a shared cache may keep a playlist that is already late, in seconds — or nil where
    /// `cacheControl` keeps it no longer than the update bound allows. The Kotlin
    /// `cachedPastTheUpdateBoundSeconds`, the judgement ADR-0015 rule 3's fourth seam names, ported
    /// with its rule, and internal until the doctor on this platform is built and has to ask it rather
    /// than restate it (rule 6) — which is when it becomes `package`.
    ///
    /// spec: RFC 9111 §5.2.2.10 — `s-maxage` overrides `max-age` for a shared cache, which is what a
    /// CDN is; §5.2.2.4 and §5.2.2.5 — `no-cache` and `no-store` mean nothing is served from a store
    /// unvalidated, whatever the lifetime says.
    static func cachedPastTheUpdateBoundSeconds(
        _ cacheControl: String?, targetDurationMs: Int64
    ) -> Int64? {
        guard let cacheControl else { return nil }
        let directives = cacheControl.split(separator: ",").map {
            $0.trimmingCharacters(in: .whitespaces).lowercased()
        }
        if directives.contains(where: { $0 == "no-cache" || $0 == "no-store" }) { return nil }
        func seconds(_ name: String) -> Int64? {
            directives.first { $0.hasPrefix("\(name)=") }
                .flatMap { Int64($0.dropFirst(name.count + 1).trimmingCharacters(in: ["\""])) }
        }
        guard let lifetime = seconds("s-maxage") ?? seconds("max-age"),
            Double(lifetime * 1_000) > updateBoundTargetDurations * Double(targetDurationMs)
        else { return nil }
        return lifetime
    }
}

/// The registrations that feed one item's ``StalePlaylistJudgement``, and the one place its verdict
/// leaves: `onStale`, once.
///
/// What the facade holds is the returned observations and nothing else — the judgement lives in the
/// closures they hold, so dropping them is the whole of stopping, as every registration in the
/// observed register is.
@MainActor
enum StaleLivePlaylistDetection {

    /// The `CoreMediaErrorDomain` code AVFoundation logs for a playlist unchanged past one and a half
    /// target durations — observed, #412 and #421, with the message "Playlist File unchanged for
    /// longer than 1.5 * target duration". Read only to *detect*: what the failure is called is the
    /// classifier's (ADR-0011 rule 1), which reads the typed error this raises.
    static let playlistUnchangedCode = -12888

    static func watch(
        _ item: AVPlayerItem,
        onStale: @escaping @MainActor @Sendable (StaleLivePlaylistError) -> Void
    ) -> [Observation] {
        let judge = Judge(onStale: onStale)
        var observations = [
            Observation.register(.resilienceItemLiveEdge, on: item) { [weak item] in
                guard let end = item?.seekableTimeRanges.last?.timeRangeValue.end, end.isNumeric
                else { return }
                judge.judgement.liveEdgeRead(end.seconds, at: Date())
            }
        ]
        if #available(iOS 18, tvOS 18, *) {
            observations.append(
                Observation.registerRequestMetrics(on: item) { [weak item] event in
                    guard let item else { return }
                    if let segment = event as? AVMetricHLSMediaSegmentRequestEvent,
                        !segment.isMapSegment
                    {
                        judge.judgement.segmentAnswered(durationSeconds: segment.segmentDuration)
                    } else if let playlist = event as? AVMetricHLSPlaylistRequestEvent,
                        let answer = answer(of: playlist)
                    {
                        judge.report(
                            judge.judgement.playlistAnswered(answer, isLive: isLive(item)))
                    }
                })
        } else {
            observations.append(
                Observation.register(.resilienceItemErrorLogEntry, on: item) { [weak item] in
                    guard let item, let entry = item.errorLog()?.events.last,
                        entry.errorDomain == "CoreMediaErrorDomain",
                        entry.errorStatusCode == playlistUnchangedCode
                    else { return }
                    judge.report(
                        judge.judgement.unchangedLogged(
                            playlist: entry.uri.flatMap(URL.init(string:)), isLive: isLive(item)))
                })
        }
        return observations
    }

    /// Live content is what has a live edge to stop: an item whose duration is indefinite. Anything
    /// else — on-demand content, an ended event — is never judged.
    private static func isLive(_ item: AVPlayerItem) -> Bool { item.duration.isIndefinite }

    @available(iOS 18, tvOS 18, *)
    private static func answer(of event: AVMetricHLSPlaylistRequestEvent) -> PlaylistAnswer? {
        guard let request = event.mediaResourceRequestEvent else { return nil }
        let response =
            request.networkTransactionMetrics?.transactionMetrics.last?.response as? HTTPURLResponse
        return PlaylistAnswer(
            url: event.url ?? request.url, isMultivariant: event.isMultivariantPlaylist,
            answeredAt: request.responseEndTime,
            succeeded: request.errorEvent == nil
                && (response.map { (200..<300).contains($0.statusCode) } ?? false),
            cacheControl: response?.value(forHTTPHeaderField: "Cache-Control"),
            age: response?.value(forHTTPHeaderField: "Age"))
    }

    /// One item's judgement, shared by its registrations' closures, and the verdict delivered once.
    @MainActor
    private final class Judge {
        var judgement = StalePlaylistJudgement()
        private var onStale: (@MainActor @Sendable (StaleLivePlaylistError) -> Void)?

        init(onStale: @escaping @MainActor @Sendable (StaleLivePlaylistError) -> Void) {
            self.onStale = onStale
        }

        func report(_ stale: StaleLivePlaylistError?) {
            guard let stale, let onStale else { return }
            self.onStale = nil
            onStale(stale)
        }
    }
}
