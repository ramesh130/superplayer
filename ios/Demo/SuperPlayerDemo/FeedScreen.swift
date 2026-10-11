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
import SuperPlayerCore
import SuperPlayerPreload
import SuperPlayerTelemetry
import SwiftUI
import os

/// A long short-form feed, built the way a consumer's should be: one `PlayerPool`, a
/// `PreloadCoordinator` attached to it, and a player only on the row being watched — the Android
/// demo's `FeedScreen`, whose KDoc this follows section by section.
///
/// The header says what each piece is doing while the viewer scrolls — players in use and made
/// against the device's bound, and players holding a row warm ahead of the scroll — and every row
/// that has played says how long its first frame took, from the moment it became the row being
/// watched.
///
/// ## The lifecycle to copy
///
/// ``FeedPlayback`` is the part of this file a consumer's feed should look like, and its order
/// matters:
///
/// 1. **Make the pool** with a collector per player: `PlayerPool.Configuration.player.telemetry` is
///    a factory the pool calls once per player, because a collector measures one. The pool's
///    players are the only ones the feed uses, so they are the ones measured.
/// 2. **Attach the coordinator before the pool makes its first player** (ADR-0010 rule 8). A
///    pooled player asks the attachment for a warm start as it adopts content, and a player made
///    before it was attached would never have asked.
/// 3. **Hand the coordinator the feed** (`setItems`) as soon as it is known, as the same
///    `MediaRequest`s the rows will play: a row is warm only for the content it was prefetched as.
/// 4. **Release in reverse**: the coordinator, then the pool.
///
/// The Android lifecycle opens a content-keyed cache first and releases it last. iOS has no step
/// for it: no streaming cache exists over `AVPlayer` (`docs/ios-plan.md` §3.4), so a prefetch is a
/// load and nothing is written, and the header has no cache count to show.
///
/// ## One row plays, and the rest show their label
///
/// Every other row is the stand-in for an item's artwork. What those rows would have spent — a
/// playlist, a segment, a decoder — the coordinator spends instead, and only ahead of where the
/// feed is going, within what the pool and the process's memory allow. That is also why one row
/// plays: pooled players share the app's one audio session (`PlayerPool`'s doc comment), so a
/// second playing row would mix with or interrupt the first.
///
/// When a row becomes the one being watched, ``FeedPlayback/watch(row:)`` does four things in an
/// order that is not incidental. It tells the coordinator where the feed is *before* asking for a
/// player, so the pool can hand it the player holding that row warm. It declares playback intent
/// before setting content, so time to first frame is measured from the row becoming current rather
/// than from adoption. It sets content through `setMediaRequest`, the only path a warm item is
/// handed over on. And it plays.
///
/// ## Where the recycling actually happens
///
/// In ``FeedPlayback/watch(row:)``, and not in the rows. On Android a row's own effect acquires and
/// recycles, and Compose disposes the old row's effect before running the new one's, so the player
/// is back in the pool before the next row asks. SwiftUI promises no order between two views'
/// change handlers, and on a device that affords one player the wrong order is a row that finds
/// the pool empty. So the hand-off is one function: recycle the row left, then acquire for the row
/// reached.
///
/// One thing the iOS hand-off does that the Android row does not: it **removes the row's periodic
/// time observer** before recycling (`stopPlaying`). A recycle removes every registration the facade made, but an
/// observer added to `avPlayer` is the consumer's and not the facade's to find
/// (`SuperPlayer.resetForReuse`'s doc comment), so a row that left it would leak one closure per
/// row scrolled past — which is what the leak hunt (`ios/hunt-leaks`) would then name.
///
/// ## What the numbers can and cannot show
///
/// A row's first frame is a `TelemetryEvent.firstFrameRendered`, delivered to the collector's sink
/// on telemetry's own queue (ADR-0008 rule 4) and handed to the main actor here. A row that shows
/// "from adoption" had no declared intent, and its number is not comparable with the others.
///
/// The header is sampled twice a second rather than observed: a pool and a coordinator are playback
/// types, not UI ones, and publish no change for SwiftUI to watch.
///
/// ## A tap pauses the row being watched
///
/// A tap anywhere on the feed pauses the watched row and a second tap plays it again — the whole
/// list is the target, because a thumb lands in the middle and the watched row is the one at the
/// top. The pause belongs to the row rather than to a player: a row scrolled back to finds itself
/// paused, on whichever player it gets.
///
/// ## How long it is, and the scripted scroll
///
/// ``FeedItem/defaultCount`` rows, because `PRD.md`'s Phase 4 exit criterion is memory that stays
/// flat over a 200-item scroll, and a feed shorter than that cannot be scrolled to show it.
/// `-SPFeedRows <n>` overrides it, and `-SPFeedScroll <passes>` makes the screen scroll itself —
/// ``FeedScroll`` says why the leak hunt drives it from inside rather than with touches.
struct FeedScreen: View {

    let rowCount: Int
    let scroll: FeedScroll?

    @State private var feed: FeedPlayback?
    /// The row at the top of the list: the feed snaps nothing, so this is the row being watched,
    /// and a viewer scrolling sees the playing row follow them down — Android's
    /// `firstVisibleItemIndex`.
    @State private var topRow: Int?

    init(
        rowCount: Int = FeedItem.countFromLaunchArguments(),
        scroll: FeedScroll? = FeedScroll.fromLaunchArguments()
    ) {
        self.rowCount = rowCount
        self.scroll = scroll
    }

    var body: some View {
        VStack(spacing: 0) {
            if let feed {
                FeedHeader(feed: feed)
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(feed.items) { item in
                            FeedRow(item: item, feed: feed).id(item.index)
                        }
                    }
                    .scrollTargetLayout()
                }
                .scrollPosition(id: $topRow, anchor: .top)
                .onTapGesture { feed.togglePause() }
                .accessibilityAction(named: "Pause or play the row being watched") {
                    feed.togglePause()
                }
                .onChange(of: topRow, initial: true) { _, row in feed.watch(row: row ?? 0) }
                .task(id: scroll) {
                    guard let scroll else { return }
                    await scroll.run(rows: feed.items.count) { row in topRow = row }
                }
            } else {
                Color.black
            }
        }
        .navigationTitle(DemoScreen.feed.title)
        // Guarded, as the other screens are: `onAppear` can fire again without an `onDisappear`
        // between, and a second feed would orphan the first's pool unreleased.
        .onAppear {
            if feed == nil { feed = FeedPlayback(items: FeedItem.all(count: rowCount)) }
        }
        .onDisappear {
            feed?.release()
            feed = nil
        }
    }
}

/// The pool and the coordinator a feed plays through, made and released in the order
/// ``FeedScreen``'s doc comment gives, and the one place a row is handed a player.
@MainActor @Observable
final class FeedPlayback {

    /// How often the watched row's position is logged — the Android feed's one line a second.
    static let positionLogIntervalSeconds: Double = 1

    nonisolated private static let logger = Logger(
        subsystem: "com.superplayer.demo", category: "Feed")

    let items: [FeedItem]
    @ObservationIgnored let pool: PlayerPool
    @ObservationIgnored let preload: PreloadCoordinator

    /// The row being watched and the player it was handed, or nil if the pool had none to give.
    private(set) var playing: Playing?
    /// Per content id, the last time to first frame telemetry reported for it.
    private(set) var firstFrames: [String: FirstFrame] = [:]
    /// The row the viewer paused, if any. At most one: a tap pauses the watched row and plays any
    /// other.
    private(set) var pausedRow: Int?
    /// The row last asked for, played or not, so a tap knows which row it pauses.
    private(set) var watchedRow: Int?

    @ObservationIgnored private var positionObserver: Any?
    @ObservationIgnored private var isReleased = false

    init(items: [FeedItem]) {
        self.items = items
        var configuration = PlayerPool.Configuration()
        // `shortForm` is already the pool's default profile; named anyway, because what a feed is —
        // short items, none watched to the end — is the reason, and it is the profile that decides
        // prerolled players ahead of the scroll.
        configuration.player.profile = .shortForm
        // Through a relay holding the feed weakly, because the pool holds every player, every
        // player its collector, every collector this sink — and a sink holding the feed strongly
        // would hold the pool that holds it. A relay rather than `[weak self]`, which Swift does
        // not allow before every stored property is set, and the pool is one of them.
        let relay = FirstFrameRelay()
        let sink = FirstFrameSink { contentId, frame in
            relay.feed?.firstFrameRendered(contentId: contentId, frame: frame)
        }
        // A collector per player, because a collector measures one; `OSLogSink` beside it puts
        // every event in the log, which is where the leak hunt counts the rows that reached a frame.
        configuration.player.telemetry = { QoeCollector(sink: .composite(sink, OSLogSink())) }
        pool = PlayerPool(configuration: configuration)

        // Immediately, before any row has asked the pool for a player (step 2).
        preload = PreloadCoordinator(pool: pool)
        preload.setItems(items.map(\.request))
        relay.feed = self
    }

    /// Hands the row at `row` a player, after giving back the one the previous row held — the
    /// whole of a row's player lifecycle (``FeedScreen``, *Where the recycling actually happens*).
    func watch(row: Int) {
        // The same row again is nothing to do. A row that found the pool at its bound is not asked
        // again until the feed moves: the row before it is recycled first, so with one row playing
        // that needs a pool that made a player and lent it elsewhere, which this screen never does.
        guard !isReleased, items.indices.contains(row), row != watchedRow else { return }
        let previous = watchedRow
        watchedRow = row
        stopPlaying()

        // Where the feed is, first: the pool then prefers the player holding this row warm. The
        // velocity is a row a step in the direction moved, which is what tells the coordinator which
        // way is ahead; a settled feed reports zero on its next move.
        let velocity = previous.map { Double((row - $0).signum()) } ?? 0
        preload.setScrollPosition(index: row, velocity: velocity)
        guard let player = pool.acquire() else {
            Self.logger.log("row=\(row, privacy: .public) no player: the pool is at its bound")
            return
        }
        let item = items[row]
        // Muted: a feed that makes noise as it is scrolled is not a feed. The recycle puts the
        // volume back, so the next row is not silently muted.
        player.avPlayer.isMuted = true
        // The viewer's arrival is the start of time to first frame, so it is declared before the
        // content is: a player that adopts content before intent measures from adoption instead.
        player.declarePlaybackIntent()
        // The one path a warm item is handed over on: a prefetched row keeps what was prepared.
        player.setMediaRequest(item.request)
        if pausedRow != row { player.avPlayer.play() }
        playing = Playing(row: row, player: player, hasFirstFrame: false)

        // The watched row's playhead, one greppable line a second. A pooled player publishes no
        // session, so this is what shows it advancing where a screenshot cannot tell a frame from a
        // stall. Removed in `stopPlaying`, since a recycle leaves it (the doc comment above).
        let contentId = item.contentId
        // A timescale of 600, ``DemoPlayback``'s and for its reason: a whole multiple of 24, 25 and
        // 30, so the interval is exact at every common frame rate.
        positionObserver = player.avPlayer.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: Self.positionLogIntervalSeconds, preferredTimescale: 600),
            queue: .main
        ) { time in
            Self.logger.log(
                "row=\(row, privacy: .public) contentId=\(contentId, privacy: .public) positionMs=\(Int64(time.seconds * 1000), privacy: .public)"
            )
        }
    }

    /// Pauses the watched row, or plays it again if it was the one paused.
    func togglePause() {
        guard let watchedRow else { return }
        pausedRow = pausedRow == watchedRow ? nil : watchedRow
        guard let player = playing?.player else { return }
        if pausedRow == watchedRow { player.avPlayer.pause() } else { player.avPlayer.play() }
    }

    /// Releases the coordinator and then the pool (step 4), after giving back the player out.
    func release() {
        guard !isReleased else { return }
        stopPlaying()
        isReleased = true
        preload.release()
        pool.release()
    }

    /// The view's side first, then the pool's: the row stops being shown and its observer goes,
    /// and the recycle then clears the player's own state, which is what stops the next row from
    /// seeing this item's last frame.
    private func stopPlaying() {
        guard let playing else { return }
        if let positionObserver {
            playing.player.avPlayer.removeTimeObserver(positionObserver)
            self.positionObserver = nil
        }
        self.playing = nil
        pool.recycle(playing.player)
    }

    fileprivate func firstFrameRendered(contentId: String, frame: FirstFrame) {
        firstFrames[contentId] = frame
        if let playing, items[playing.row].contentId == contentId {
            self.playing?.hasFirstFrame = true
        }
    }

    /// The row being watched and its player.
    struct Playing {
        let row: Int
        let player: SuperPlayer
        /// Whether the row's first frame has been reported, which is when its label comes off —
        /// artwork until there is a picture.
        var hasFirstFrame: Bool
    }
}

/// The weak reference ``FeedPlayback``'s sink reaches it through.
@MainActor
private final class FirstFrameRelay: Sendable {
    weak var feed: FeedPlayback?
}

/// The one event this screen shows, taken off telemetry's queue and handed to the main actor.
private struct FirstFrameSink: TelemetrySink {

    let deliver: @MainActor @Sendable (String, FirstFrame) -> Void

    init(_ deliver: @escaping @MainActor @Sendable (String, FirstFrame) -> Void) {
        self.deliver = deliver
    }

    func onEvent(_ event: TelemetryEvent) {
        guard case .firstFrameRendered(let e) = event else { return }
        let frame = FirstFrame(
            ms: e.timeToFirstFrameMs, fromIntent: e.startBoundary == .userIntent)
        let contentId = e.contentId
        // A task per event, so two events are not promised to arrive in order; a row shows the last
        // to arrive, which for one row's one first frame per watch is the only one.
        Task { @MainActor in deliver(contentId, frame) }
    }
}

/// A row's time to first frame, and whether it was measured from declared intent.
struct FirstFrame: Equatable, Sendable {
    let ms: Int64
    let fromIntent: Bool
}

/// What the header says, sampled off the pool and the coordinator twice a second.
///
/// One reading rather than four, so that the numbers cannot be refreshed apart and show a moment
/// that never happened — "3 in use, 2 made".
private struct FeedHeader: View {

    /// Twice a second, as the Android header: often enough to follow a scroll, and a pool is not
    /// UI state that could be observed instead.
    static let refreshSeconds: Double = 0.5

    let feed: FeedPlayback

    var body: some View {
        TimelineView(.periodic(from: .now, by: Self.refreshSeconds)) { _ in
            Text(
                "players in use \(feed.pool.inUseCount) · made \(feed.pool.size) of \(feed.pool.maxSize) · warm \(feed.preload.warmDecoderCount)"
            )
            .font(.footnote.monospacedDigit())
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
        }
    }
}

/// One row: the player if it is the row being watched and the pool had one to give, and its label
/// otherwise, with the row's last time to first frame under either.
///
/// A watched row with no player is not an error path: a pool hands out at most what the device
/// can afford, and a caller has to have an answer for it. Here the answer is the label, saying so.
private struct FeedRow: View {

    let item: FeedItem
    let feed: FeedPlayback

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ZStack {
                Color(white: 0.25)
                if let playing = feed.playing, playing.row == item.index {
                    // The facade's own view, handed the pooled player with no adapter.
                    PlayerView(player: playing.player)
                    if !playing.hasFirstFrame { Text(item.label) }
                } else if feed.watchedRow == item.index {
                    Text("\(item.label) — no player free")
                } else {
                    Text(item.label)
                }
            }
            .foregroundStyle(.white)
            .aspectRatio(16 / 9, contentMode: .fit)
            .padding(4)
            if let frame = feed.firstFrames[item.contentId] {
                Text(
                    frame.fromIntent
                        ? "first frame \(frame.ms) ms"
                        : "first frame \(frame.ms) ms (from adoption)"
                )
                .font(.caption.monospacedDigit())
                .padding(.horizontal, 8)
            }
        }
    }
}

/// A row's content: a label and an identity, over the public streams the demo already has.
///
/// The items cycle through ``DemoStream`` rather than naming two hundred URLs, because what the
/// screen is about is how rows start, not what is in them — the Android `FeedItem`, which cycles
/// through that demo's streams the same way. Each row still gets a content id of its own, because
/// the coordinator and telemetry key on it: a feed whose rows were all one content id would be one
/// row prefetched two hundred times over.
struct FeedItem: Identifiable, Sendable {

    /// The PRD's 200-item scroll (Phase 4's exit criterion), which is also comfortably past the
    /// pool's bound.
    static let defaultCount = 200

    let index: Int
    let label: String
    let contentId: String
    /// Made once, so the coordinator is handed the same request the row plays.
    let request: MediaRequest

    var id: Int { index }

    static func all(count: Int) -> [FeedItem] {
        (0..<count).map { index in
            let stream = DemoStream.allCases[index % DemoStream.allCases.count]
            let contentId = "demo:feed-\(index)"
            return FeedItem(
                index: index,
                label: "#\(index + 1) · \(stream.title)",
                contentId: contentId,
                request: MediaRequest(
                    contentId: contentId,
                    sources: [stream.url],
                    title: "#\(index + 1) · \(stream.title)"))
        }
    }

    /// `-SPFeedRows <n>`, or ``defaultCount``. The leak hunt passes the count it scrolls, so the
    /// rows it counts and the rows on screen are one number by construction.
    static func countFromLaunchArguments(_ defaults: UserDefaults = .standard) -> Int {
        let count = defaults.integer(forKey: "SPFeedRows")
        return count > 0 ? count : defaultCount
    }
}

/// The feed scrolling itself: down to the last row and back to the first, a row at a time, for a
/// number of passes — what the leak hunt records (`ios/hunt-leaks`), and the Android hunt's
/// `LEAK_FEED_PASSES` scenario.
///
/// **Driven from inside the app, because a simulator takes no scripted touch**: `simctl` has no
/// input command, which is what `adb shell input swipe` is to the Android hunt, and a UI-test
/// target to drive one would be a second target and a test runner in the process being measured.
/// What a step changes is the list's scroll position — the same binding a finger moves — so a
/// scripted step and a swipe reach ``FeedPlayback/watch(row:)`` by one path.
///
/// Every step and the end are logged under the category `FeedScroll`, which is how the hunt knows
/// the scroll is over.
struct FeedScroll: Hashable, Sendable {

    /// A row a second by default, the Android feed's position-log cadence: long enough for a warm
    /// row to start and be recycled having played, short enough that 200 rows down and back are
    /// minutes rather than an hour. `-SPFeedDwellMs` overrides it.
    static let defaultDwellMs = 1_000

    private static let logger = Logger(
        subsystem: "com.superplayer.demo", category: "FeedScroll")

    let passes: Int
    let dwellMs: Int

    /// `-SPFeedScroll <passes>`, or nil to scroll only under a finger.
    static func fromLaunchArguments(_ defaults: UserDefaults = .standard) -> FeedScroll? {
        let passes = defaults.integer(forKey: "SPFeedScroll")
        guard passes > 0 else { return nil }
        let dwell = defaults.integer(forKey: "SPFeedDwellMs")
        return FeedScroll(passes: passes, dwellMs: dwell > 0 ? dwell : defaultDwellMs)
    }

    /// Moves the top row through `rows` rows and back, `passes` times, waiting ``dwellMs`` on each.
    @MainActor
    func run(rows: Int, moveTo: @MainActor (Int) -> Void) async {
        guard rows > 1 else { return }
        Self.logger.log(
            "scroll started passes=\(passes, privacy: .public) rows=\(rows, privacy: .public)")
        for pass in 1...passes {
            let down = Array(1..<rows)
            let up = Array((0..<(rows - 1)).reversed())
            for row in down + up {
                do {
                    try await Task.sleep(for: .milliseconds(dwellMs))
                } catch {
                    return  // the screen went away mid-scroll
                }
                moveTo(row)
            }
            Self.logger.log("scroll pass=\(pass, privacy: .public) ended at row=0")
        }
        Self.logger.log(
            "scroll finished passes=\(passes, privacy: .public) rows=\(rows, privacy: .public)")
    }
}
