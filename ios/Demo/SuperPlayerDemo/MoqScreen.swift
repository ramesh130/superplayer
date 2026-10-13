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

// iOS alone: SuperPlayerMoQ's XCFramework carries no tvOS slice, so the project links the product
// on iOS only (its package product dependency is platform-filtered) and this file compiles to
// nothing on tvOS (third-party/moq/README.md, *The Swift bindings*).
#if canImport(SuperPlayerMoQ)

    import AVKit
    import SuperPlayerCore
    import SuperPlayerMoQ
    import SuperPlayerRealtime
    import SwiftUI
    import os

    /// A MoQ broadcast through a `RealtimePlayer` on the library's `PlayerView` — the Android
    /// `MoqScreen`, and the lifecycle a consumer copies:
    ///
    /// 1. **Register the transport**, once per player:
    ///    `Realtime.transport(scheme: MoqFrameSource.scheme) { MoqFrameSource(url: $0) }`. A factory
    ///    rather than an instance, because one source is opened per subscription, keyed on the URL's
    ///    **scheme** (ADR-0018 rule 12).
    /// 2. **Adopt the broadcast as content** with `setMediaRequest`, the `moq://` URL as its source and
    ///    the default `.beginning` start, which is the live edge because no other position exists;
    ///    `.at` and `.resumeFromLastKnown` are refused typed (rule 5).
    /// 3. **Play**.
    /// 4. **Release with the screen.** `release()` is what ends the QUIC session: the source's
    ///    `cancel()` closes the streams and the session and joins the pumps and the poller.
    ///
    /// **Two players, not one** — the one place the iOS port's "one player" claim splits, because an
    /// `AVPlayer` cannot take pushed frames (ADR-0020 rule 11). **A realtime stream never ends** on
    /// its own, so leaving the screen is the whole of teardown, and there is no scrub bar to offer.
    /// The overlay is the transport's own reading, `MoqFrameSource.statistics()`, which deliberately
    /// reaches no telemetry sink.
    ///
    /// **Picture in Picture** (#461) is the button beside it: the player's display layer handed to
    /// `AVPictureInPictureController` through `PlayerLayerView.makePictureInPictureContentSource()`,
    /// which for a `RealtimePlayer` answers for a live timeline with no ends. It needs the app's
    /// `audio` background mode, which the demo's `Info.plist` declares.
    ///
    /// **The broadcast is somebody else's.** ``MoqScreen/defaultBroadcast`` is on a public relay, and
    /// MoQ's refusal is `unroutable` for every reason at once, so a failure is shown verbatim rather
    /// than interpreted, and `-SPMoqBroadcast <moq://…>` names another one without a rebuild.
    struct MoqScreen: View {

        /// MoQ's own demonstration broadcast, Big Buck Bunny under the relay's `demo` namespace — the
        /// one that played on an iPhone when #438 ran (`docs/testing.md`, *The first real MoQ session
        /// on an iPhone*). Chosen over the Android demo's `anon/catshark.hang`, which the relay no
        /// longer announced that day: `anon` is whatever its visitors left running, while `demo` is
        /// the relay operator's own. That makes it likelier to last and no more than that, so a run
        /// that finds it gone is a finding about the relay, and `-SPMoqBroadcast` is the way round it.
        static let defaultBroadcast = "moq://cdn.moq.dev/demo/bbb.hang"

        @State private var session: MoqDemoSession?

        var body: some View {
            ZStack(alignment: .topLeading) {
                if let session {
                    HostedLayerView(view: session.view)
                } else {
                    Color.black
                }
                // Read once a second, the cadence the source polls its session at. The reading line
                // rides the overlay as its accessibility value, which is how the device smoke's UI
                // test reads it: nothing else in the app is readable from another process.
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    HStack {
                        Text(session?.status ?? "connecting")
                            .font(.footnote.monospacedDigit())
                            .padding(8)
                            .background(.black.opacity(0.6))
                            .foregroundStyle(.white)
                            .accessibilityIdentifier(MoqDemoSession.readingIdentifier)
                            .accessibilityValue(session?.reading ?? "")
                        Button("Picture in Picture") { session?.togglePictureInPicture() }
                            .font(.footnote)
                            .padding(8)
                            .background(.black.opacity(0.6))
                            .foregroundStyle(.white)
                            .accessibilityIdentifier(MoqDemoSession.pictureInPictureIdentifier)
                    }
                }
                .padding()
            }
            .background(.black)
            .navigationTitle(DemoScreen.moq.title)
            .onAppear {
                if session == nil { session = MoqDemoSession(broadcast: Self.broadcast()) }
            }
            .onDisappear {
                session?.release()
                session = nil
            }
        }

        /// `-SPMoqBroadcast`, or the default — the Android extra `MOQ_BROADCAST`'s twin.
        private static func broadcast(_ defaults: UserDefaults = .standard) -> URL {
            URL(string: defaults.string(forKey: "SPMoqBroadcast") ?? defaultBroadcast)
                ?? URL(string: defaultBroadcast)!
        }
    }

    /// One player over one broadcast, and the source it opened, kept so the overlay can read the
    /// transport's statistics: a `RealtimePlayer` does not hand its source out, so the factory hands
    /// a copy here as it opens one.
    ///
    /// **It logs a reading every ``readingIntervalSeconds``**, in `DemoPlayback`'s shape —
    /// `content=demo:moq-live position=<ms> rendering=<bool> received=<bytes> rtt=<ms>` — because a
    /// screenshot cannot tell a rendered frame from a stalled one, and a position that advances while
    /// the display layer is rendering and the session's byte count rises can. The same line is the
    /// overlay's accessibility value, which `SuperPlayerDemoUITests`' `MoqLiveSessionSmokeTests`
    /// reads on a phone (`devicelab/lab run moq-smoke --platform ios`, #438).
    @MainActor
    final class MoqDemoSession {

        /// The overlay's accessibility identifier, which the UI test finds it by.
        static let readingIdentifier = "moq.reading"

        /// The Picture in Picture button's, likewise.
        static let pictureInPictureIdentifier = "moq.pip"

        /// `DemoPlayback`'s interval and its reason: two lines within seconds of the first frame, and
        /// a run of minutes still a screenful.
        static let readingIntervalSeconds: Double = 2

        static let contentId = "demo:moq-live"

        let player: RealtimePlayer

        /// The library's view of the player, owned here rather than made by SwiftUI so its Picture
        /// in Picture content source has one view to be made from.
        let view: PlayerLayerView

        private var pictureInPicture: AVPictureInPictureController?
        private let pictureInPictureEvents = PictureInPictureEvents()
        private let opened: OSAllocatedUnfairLock<MoqFrameSource?>
        private let log = Logger(subsystem: "com.superplayer.demo", category: "moq")
        private var logging: Task<Void, Never>?

        init(broadcast: URL) {
            let opened = OSAllocatedUnfairLock<MoqFrameSource?>(initialState: nil)
            self.opened = opened
            // Step 1: the one line that is about realtime at all.
            player = RealtimePlayer(
                configuration: .init(
                    sources: Realtime.transport(scheme: MoqFrameSource.scheme) { url in
                        let source = MoqFrameSource(url: url)
                        opened.withLock { $0 = source }
                        return source
                    }))
            view = PlayerLayerView(player: player)
            // Made with the view rather than at the tap: the system makes a controller's start
            // possible a moment after it is made, and a start asked before then is ignored (seen on
            // an iPhone, #461), so the button must find one already made.
            if let source = view.makePictureInPictureContentSource() {
                let controller = AVPictureInPictureController(contentSource: source)
                // A live stream has nothing to skip through: without this the window draws its
                // skip buttons whatever the playback delegate says of the timeline (seen on an
                // iPhone, #461), and the controller is the app's, so the app says it.
                // ref: https://developer.apple.com/documentation/avkit/avpictureinpicturecontroller/requireslinearplayback
                controller.requiresLinearPlayback = true
                controller.delegate = pictureInPictureEvents
                pictureInPicture = controller
            }
            do {
                // Step 2, and step 3.
                try player.setMediaRequest(
                    MediaRequest(contentId: Self.contentId, sources: [broadcast]))
                player.play()
                log.info("subscribed to \(broadcast.absoluteString, privacy: .public)")
            } catch {
                log.error("refused \(broadcast.absoluteString, privacy: .public): \(error)")
            }
            logging = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(Self.readingIntervalSeconds))
                    guard let self else { return }
                    self.log.log("\(self.reading, privacy: .public)")
                }
            }
        }

        /// The line logged and read: the player's clock, whether the display layer is presenting,
        /// and the session's received bytes and round trip — `-` where the session reported none —
        /// with the failure verbatim at the end once there is one.
        var reading: String {
            let seconds = player.currentTime.seconds
            let positionMs = seconds.isFinite ? Int64(seconds * 1_000) : 0
            let statistics = opened.withLock { $0 }?.statistics()
            let received = statistics?.bytesReceived.map(String.init) ?? "-"
            let rtt = statistics?.roundTripTimeUs.map { String($0 / 1_000) } ?? "-"
            let failure = player.failure.map { " failed=\($0)" } ?? ""
            return
                "content=\(Self.contentId) position=\(positionMs) rendering=\(player.isRendering)"
                + " received=\(received) rtt=\(rtt) pip=\(pictureInPictureState)\(failure)"
        }

        /// Where Picture in Picture is: `active`, `possible`, `impossible` before the system allows
        /// it, or `failed:<error>` if starting it was refused — the last event the controller's
        /// delegate heard, if any, else the controller's own reading.
        var pictureInPictureState: String {
            if let event = pictureInPictureEvents.last { return event }
            guard let pictureInPicture else { return "none" }
            if pictureInPicture.isPictureInPictureActive { return "active" }
            return pictureInPicture.isPictureInPicturePossible ? "possible" : "impossible"
        }

        /// Starts Picture in Picture over the player's display layer, or stops it if it runs.
        func togglePictureInPicture() {
            guard let pictureInPicture else { return }
            if pictureInPicture.isPictureInPictureActive {
                pictureInPicture.stopPictureInPicture()
            } else {
                pictureInPictureEvents.last = nil
                pictureInPicture.startPictureInPicture()
            }
            log.log("picture in picture asked: \(self.pictureInPictureState, privacy: .public)")
        }

        /// What a person reads over the picture: the failure verbatim if there was one, else the
        /// session's round trip and receive rate, each "—" where the session reported none.
        var status: String {
            if let failure = player.failure { return "failed: \(failure)" }
            guard let reading = opened.withLock({ $0 })?.statistics() else { return "connecting" }
            let rtt = reading.roundTripTimeUs.map { "\($0 / 1_000) ms" } ?? "—"
            let rate = reading.receiveRateBps.map { "\($0 / 1_000) kbit/s" } ?? "—"
            return "rtt \(rtt) · receiving \(rate)"
        }

        /// Step 4.
        func release() {
            logging?.cancel()
            pictureInPicture?.stopPictureInPicture()
            pictureInPicture = nil
            player.release()
        }
    }

    /// What Picture in Picture's controller last reported, as the reading's `pip=` word.
    @MainActor
    final class PictureInPictureEvents: NSObject, AVPictureInPictureControllerDelegate {
        var last: String?

        nonisolated func pictureInPictureControllerDidStartPictureInPicture(
            _ controller: AVPictureInPictureController
        ) {
            MainActor.assumeIsolated { last = "active" }
        }

        nonisolated func pictureInPictureControllerDidStopPictureInPicture(
            _ controller: AVPictureInPictureController
        ) {
            MainActor.assumeIsolated { last = "stopped" }
        }

        nonisolated func pictureInPictureController(
            _ controller: AVPictureInPictureController,
            failedToStartPictureInPictureWithError error: any Error
        ) {
            let text = "failed:\((error as NSError).domain):\((error as NSError).code)"
            MainActor.assumeIsolated { last = text }
        }
    }

    /// A view the session made, hosted as it is: SwiftUI neither makes nor replaces it, so the
    /// Picture in Picture content source made from it stays the one on screen.
    struct HostedLayerView: UIViewRepresentable {
        let view: PlayerLayerView
        func makeUIView(context: Context) -> PlayerLayerView { view }
        func updateUIView(_ view: PlayerLayerView, context: Context) {}
    }

#endif
