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
import SwiftUI
import os

/// What a developer watches while the thing is playing in front of them: `PRD.md` §3.6's on-device
/// debug HUD — the Kotlin `DebugHud`, as a SwiftUI overlay the app places over its own surface.
///
/// ```swift
/// // Once per player, beside the app's own sink.
/// let hudTelemetry = DebugHudTelemetry()
/// var configuration = SuperPlayer.Configuration()
/// configuration.telemetry = { QoeCollector(sink: .composite(appSink, hudTelemetry)) }
/// let player = SuperPlayer(configuration: configuration)
///
/// ZStack(alignment: .topLeading) {
///     VideoPlayer(player: player.avPlayer)
///     DebugHud(player: player, telemetry: hudTelemetry)   // nothing at all in a release build
/// }
/// ```
///
/// Six rows: where the numbers come from, the rendition playing, the buffer ahead of the playhead,
/// the frames dropped, **the throughput estimate beside the rung that was selected and the policy's
/// ceiling**, and the failures the session has seen.
///
/// ## The line worth the HUD
///
/// The `estimate` row, for the Kotlin reason: a stall with a healthy estimate and a low rung is
/// selection — a ceiling, a device refusal — and a stall with a collapsed estimate is the link, and
/// a vocabulary that reports only what was selected cannot tell the two apart. The row puts the
/// estimate, the rung and the ceiling in force on one line at one instant.
///
/// ## What it reads, and what it does not
///
/// Only public API (ADR-0015 rule 11): the `TelemetryEvent` vocabulary through
/// ``DebugHudTelemetry``, `SuperPlayer.playbackDecision` for the ceiling, and the `AVPlayer`'s own
/// state — the current time, the loaded ranges, the access log's `indicatedBitrate` and the item's
/// error. **It opens no seam**, and it registers no observer of its own: a second reader of
/// AVFoundation's logs beside `QoeCollector` would be the drift ADR-0008 rule 2 puts one collector
/// in one product to prevent. The rung is read from the player rather than from telemetry, which is
/// rule 11's own sourcing and what makes the row worth anything on a stock player.
///
/// ## It takes a stock `AVPlayer` too, and degrades rather than refusing
///
/// The Kotlin HUD takes any Media3 `Player`, the supertype a stock `ExoPlayer` and a `SuperPlayer`
/// share. Here the two share no type — the facade owns an `AVPlayer` and subclasses nothing
/// (ADR-0020 rule 2) — so there are two initialisers, and the public signature names `AVPlayer`
/// for that reason alone: it is the stock player. A stock player answers the rows it can — the
/// rendition, the buffer, the position, its own error — and reads `unavailable` on the rest, while a
/// ``SuperPlayer`` with a collector attached answers all six. Nothing here throws, and nothing is
/// invented to fill a row.
///
/// ## Debug builds only
///
/// The overlay renders **nothing** unless ``isDebugHudAvailable()`` — which argues the mechanism and
/// says plainly what it does not guarantee.
public struct DebugHud: View {

    private let source: HudSource
    private let telemetry: DebugHudTelemetry?

    /// A HUD over `player`, reading `telemetry` — the sink attached to the player's collector, or
    /// nil on a player configured without one, where the rows only telemetry answers read
    /// `unavailable`.
    public init(player: SuperPlayer, telemetry: DebugHudTelemetry? = nil) {
        self.init(source: .superPlayer(player), telemetry: telemetry)
    }

    /// A HUD over a stock `AVPlayer`, which answers the rendition, the buffer, the position and its
    /// own error.
    public init(player: AVPlayer, telemetry: DebugHudTelemetry? = nil) {
        self.init(source: .stock(player), telemetry: telemetry)
    }

    package init(source: HudSource, telemetry: DebugHudTelemetry?) {
        self.source = source
        self.telemetry = telemetry
    }

    public var body: some View {
        #if DEBUG
            if isDebugHudAvailable() {
                overlay
            }
        #else
            EmptyView()
        #endif
    }

    /// The rows, re-read on a timer rather than on events, and the timer is the whole refresh: three
    /// of the six rows move with no event — the position and the buffer advance between callbacks —
    /// and a HUD shown while paused must not sit a refresh behind the last seek. One reading of both
    /// halves on the main actor is the only spelling in which every row is the same instant.
    package var overlay: some View {
        TimelineView(.periodic(from: .now, by: Self.refreshInterval)) { _ in
            HudRows(facts: HudFacts.read(source, telemetry: telemetry))
        }
    }

    /// How often the HUD re-reads everything: twice a second — the Kotlin `HUD_REFRESH_MS`, for its
    /// reason. Fast enough that the buffer and the position look live, slow enough to be irrelevant
    /// beside the frames decoded next to it, and deliberately not the collector's ten-second sample.
    static let refreshInterval: TimeInterval = 0.5
}

/// The six rows of one reading.
private struct HudRows: View {

    let facts: HudFacts

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(facts.rows(), id: \.label) { row in
                HStack(alignment: .firstTextBaseline, spacing: 0) {
                    Text(row.label)
                        .foregroundStyle(Self.labelColour)
                        .frame(width: Self.labelWidth, alignment: .leading)
                    Text(row.value).foregroundStyle(Self.valueColour)
                }
                // Each row names itself and carries its reading, so a screen reader announces one
                // row rather than a wall of figures.
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(row.label)
                .accessibilityValue(row.value)
            }
        }
        .font(.system(size: Self.fontSize, design: .monospaced))
        .padding(Self.padding)
        .background(Self.background)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(HudFacts.description)
    }

    // Layout, each the Kotlin value for its reason. Where the overlay sits is the app's, through
    // the modifiers it applies; what is fixed is only what makes the rows readable over video.

    /// Near-opaque black rather than a tint: the HUD lies over content of unknown brightness. Not
    /// fully opaque, so frames can be seen arriving underneath — the Kotlin `0xD0000000`.
    private static let background = Color.black.opacity(208.0 / 255.0)

    /// A mid grey label beside a white value: the eye reads down the values column — the Kotlin
    /// `0xFF9E9E9E` and white.
    private static let labelColour = Color(red: 0x9E / 255.0, green: 0x9E / 255.0, blue: 0x9E / 255.0)
    private static let valueColour = Color.white

    /// Eight points, half a step of the 16-point grid Apple's layout guides use and the Kotlin 8dp,
    /// so the text does not touch the background's edge.
    private static let padding: CGFloat = 8

    /// Wide enough for the longest label, `estimate`, plus a space, so every value starts in one
    /// column — the Kotlin 72dp.
    private static let labelWidth: CGFloat = 72

    /// Eleven points, monospaced: small because it covers the content, and monospaced so a number
    /// that changes width does not shift the row — the Kotlin 11sp.
    private static let fontSize: CGFloat = 11
}

/// Whether ``DebugHud`` shows anything in this build of this app.
///
/// **The mechanism, and why it differs from Android's.** Two readings, both of which must say yes.
///
/// 1. **`#if DEBUG`, at compile time.** SwiftPM compiles a package from source in the app's own
///    build configuration, so this library's `DEBUG` *is* the app's — which is exactly what the
///    Kotlin argument found `BuildConfig.DEBUG` is not, since a published AAR is always a release
///    build. In a Release build the overlay's body is `EmptyView` and this answers false, so the
///    rendering code is not in the binary at all — a stronger guarantee than `FLAG_DEBUGGABLE`
///    gives, where R8 keeps a composable the app calls.
/// 2. **The `get-task-allow` entitlement, at run time** (`docs/ios-plan.md` §3.9): the entitlement a
///    development signature carries so a debugger may attach, read from the provisioning profile
///    embedded in the app. A distribution signature — App Store, TestFlight, ad hoc, enterprise —
///    lacks it or sets it false, so a Debug-configuration build signed for distribution still
///    shows nothing. On the simulator there is no profile and no distribution, so it is taken as
///    present there.
///
/// Neither is a flag a consumer sets or a builder call: no switch is present in a release binary,
/// which is ADR-0015 rule 11's requirement.
///
/// **What it does not guarantee, said plainly.**
/// - **The types are still in a release binary.** `DebugHud` and ``DebugHudTelemetry`` exist in
///   every configuration, because a public API that changed with the build configuration would
///   break the app's Release build. An app that attaches the sink in Release keeps paying for its
///   reading — a lock and a value per event — so the sink's composition belongs under the app's own
///   `#if DEBUG` too.
/// - **A library built ahead of time never shows it.** An `xcframework` of this package is compiled
///   once, in Release, so `DEBUG` is false whatever the app's configuration; the guard holds for the
///   way the package is distributed (`docs/ios-plan.md` §2), and says so rather than assuming.
/// - **A development-signed build shows it**, Debug or not of the app's own code, on any device it
///   was provisioned for: the entitlement says how the build was signed, and a tester handed a
///   development build is holding a debuggable app.
/// - **It is not a security control.** A re-signed binary carries whatever entitlements its signer
///   chose. Nothing on the HUD is a secret: every number is one the app's own telemetry carries.
///
/// Public so a consumer's own debug affordance can ask the question the HUD asks.
///
/// ref: Apple TN3125, *Inside Code Signing: Provisioning Profiles* — the profile is a CMS-signed
///   property list whose `Entitlements` dictionary carries `get-task-allow`
public func isDebugHudAvailable() -> Bool {
    #if DEBUG
        let compiledForDebug = true
    #else
        let compiledForDebug = false
    #endif
    #if targetEnvironment(simulator)
        let simulator = true
    #else
        let simulator = false
    #endif
    let profile = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision")
        .flatMap { try? Data(contentsOf: $0) }
    return debugHudAvailability(
        compiledForDebug: compiledForDebug, simulator: simulator, provisioningProfile: profile)
}

/// The guard as a function of its three readings, so each answer is a test's to state.
package func debugHudAvailability(
    compiledForDebug: Bool, simulator: Bool, provisioningProfile: Data?
) -> Bool {
    guard compiledForDebug else { return false }
    if simulator { return true }
    guard let profile = provisioningProfile,
        let entitlements = provisionedEntitlements(profile)
    else { return false }
    return entitlements["get-task-allow"] as? Bool == true
}

/// The `Entitlements` dictionary of a provisioning profile: the XML property list inside its CMS
/// envelope, found by its own delimiters rather than by verifying the signature — the reading needs
/// what the profile says, and the system verified it at install.
///
/// ref: Apple TN3125, *Inside Code Signing: Provisioning Profiles*
private func provisionedEntitlements(_ profile: Data) -> [String: Any]? {
    guard let start = profile.range(of: Data("<?xml".utf8)),
        let end = profile.range(of: Data("</plist>".utf8), in: start.lowerBound..<profile.endIndex)
    else { return nil }
    let plist = profile[start.lowerBound..<end.upperBound]
    let root = try? PropertyListSerialization.propertyList(from: plist, format: nil)
    return (root as? [String: Any])?["Entitlements"] as? [String: Any]
}

/// The telemetry half of ``DebugHud``: a `TelemetrySink` the app attaches beside its own, keeping
/// the last reading of each metric the HUD shows and the session's most recent failures — the Kotlin
/// `DebugHudTelemetry`.
///
/// A sink rather than a collector, and that is the point: everything the HUD shows is already in
/// the vocabulary a consumer's pipeline receives, so the HUD adds a *reader* of the one collector
/// rather than a second derivation. One per player, as a collector is.
///
/// **Thread-safe**: a sink is called on the delivery queue (ADR-0008 rule 4) and the HUD reads on
/// the main actor, so the reading is swapped under a lock and handed out as a value. Nothing here
/// touches the player.
///
/// **It keeps a reading, not a history** — the last estimate, the session's dropped-frame total and
/// the last few failures. A session's whole stream is ``SessionBundle``'s, and the two are
/// deliberately different artifacts: one is watched live and one is exported.
public final class DebugHudTelemetry: TelemetrySink, Sendable {

    private let state = OSAllocatedUnfairLock(initialState: HudTelemetryReading())

    public init() {}

    public func onEvent(_ event: TelemetryEvent) {
        state.withLock { $0 = $0.after(event) }
    }

    package func reading() -> HudTelemetryReading {
        state.withLock { $0 }
    }
}

/// What ``DebugHudTelemetry`` has heard — the Kotlin `HudTelemetryReading`, with its reasons.
///
/// It does **not** carry the selected rung, although the vocabulary reports it: that number is the
/// player's own and is read from it, and a second copy here would be a reading that could disagree
/// with the player on the one row where the comparison is the point.
package struct HudTelemetryReading: Sendable, Hashable {

    package var throughputEstimateBps: Int?
    package var droppedFrames = 0
    /// Newest first, at most ``maxRecentFailures``.
    package var failures: [String] = []

    package init() {}

    /// How many failures the HUD keeps and shows: four — the Kotlin `MAX_RECENT_FAILURES`. An
    /// overlay lies over the content a developer is also watching, and four lines of failure is
    /// about what can be read at a glance; a session that failed more often has a problem the first
    /// four describe. It bounds the *row*, which is why ``HudFacts`` applies it again after adding
    /// the delivered error.
    package static let maxRecentFailures = 4

    func after(_ event: TelemetryEvent) -> HudTelemetryReading {
        var next = self
        switch event {
        case .sessionStarted:
            // A new session starts from nothing: a reading carried over would attribute the last
            // content's estimate and dropped frames to this one, the common case on a pooled player.
            return HudTelemetryReading()
        case .playbackStateSampled(let sample):
            // A sample's own nil does not blank the row: the field is nil before the access log has
            // an entry, and a row that emptied and refilled would read as a fault in the HUD.
            next.throughputEstimateBps = sample.throughputEstimateBps ?? throughputEstimateBps
        case .videoFramesDropped(let dropped):
            // Reported per interval, so the HUD shows the session's running total — the quantity a
            // developer asks about.
            next.droppedFrames += dropped.droppedFrames
        case .startupFailed(let failed):
            next.failures = Self.bounded([Self.describe(failed.failure)] + failures)
        case .midStreamFailed(let failed):
            next.failures = Self.bounded([Self.describe(failed.failure)] + failures)
        default:
            break
        }
        return next
    }

    /// One failure in one line: the classification where the player had a classifier, the engine's
    /// own code either way — two facts, because they are two (ADR-0011 rule 3) — and the bucket
    /// where the engine gave no code, since an empty line says nothing.
    static func describe(_ failure: PlaybackFailure) -> String {
        let line = [failure.classification, failure.code].compactMap { $0 }.joined(separator: " ")
        return line.isEmpty ? "\(failure.category)" : line
    }

    static func bounded(_ failures: [String]) -> [String] {
        Array(failures.prefix(maxRecentFailures))
    }
}

/// The player a HUD reads: the facade, or a stock `AVPlayer`.
package enum HudSource {
    case superPlayer(SuperPlayer)
    case stock(AVPlayer)
}

/// Every reading the HUD shows, taken at one instant on the main actor — the Kotlin `HudFacts`.
package struct HudFacts: Sendable, Hashable {

    package var superPlayer: Bool
    package var telemetryAttached: Bool
    package var bufferedDurationMs: Int64
    package var positionMs: Int64
    package var selectedBitrateBps: Int?
    package var throughputEstimateBps: Int?
    package var ceiling: Ceiling
    package var droppedFrames: Int?
    package var failures: [String]

    /// The policy's ceiling as the HUD can know it.
    package enum Ceiling: Sendable, Hashable {
        /// A stock player has no policy.
        case unavailable
        /// The decision caps nothing — a decision, and not a missing reading.
        case unlimited
        case bitsPerSecond(Int)
    }

    /// What the overlay is, for a screen reader and for a test that has to find it.
    package static let description = "SuperPlayer debug HUD"

    /// Read by a developer, never localised: a debug overlay's audience is whoever built the build.
    package static let unavailable = "unavailable"
    package static let none = "none"

    /// The rows, in the order they are shown, each a label and its value.
    package func rows() -> [Row] {
        [
            Row("source", source),
            Row("bitrate", Self.rate(selectedBitrateBps)),
            Row("buffer", "\(Self.seconds(bufferedDurationMs)) ahead of \(Self.seconds(positionMs))"),
            Row("dropped", droppedFrames.map(String.init) ?? Self.unavailable),
            Row("estimate", estimate),
            Row("failures", failures.isEmpty ? Self.none : failures.joined(separator: ", ")),
        ]
    }

    /// One row: the label a developer sees, and its reading.
    package struct Row: Sendable, Hashable {
        package let label: String
        package let value: String

        init(_ label: String, _ value: String) {
            self.label = label
            self.value = value
        }
    }

    private var source: String {
        (superPlayer ? "SuperPlayer" : "AVPlayer")
            + (telemetryAttached ? ", telemetry attached" : ", no telemetry")
    }

    /// The estimate, the rung it was spent on, and the ceiling the policy allowed — in the order a
    /// reader compares them. The rung repeats the `bitrate` row on purpose: that row is a reading,
    /// this one the relation, which cannot be read unless the numbers are adjacent. Absent parts are
    /// named rather than dropped, so the row's shape does not change with what is available.
    private var estimate: String {
        let ceilingText =
            switch ceiling {
            case .unavailable: Self.unavailable
            case .unlimited: "unlimited"
            case .bitsPerSecond(let bps): Self.rate(bps)
            }
        return "\(Self.rate(throughputEstimateBps)) estimated, \(Self.rate(selectedBitrateBps)) "
            + "selected, \(ceilingText) ceiling"
    }

    /// Kilobits a second, which is how every rendition ladder in use is named.
    private static func rate(_ bps: Int?) -> String {
        bps.map { "\($0 / 1_000) kbps" } ?? unavailable
    }

    /// Seconds to one decimal, the precision a buffer is reasoned about in.
    private static func seconds(_ ms: Int64) -> String {
        String(format: "%.1fs", Double(ms) / 1_000)
    }

    /// One reading of both halves. The player is read here and nowhere else in this file, on the
    /// main actor, which is the only place a `SuperPlayer` may be read.
    @MainActor
    package static func read(_ source: HudSource, telemetry: DebugHudTelemetry?) -> HudFacts {
        let reading = telemetry?.reading()
        let player: AVPlayer
        let superPlayer: SuperPlayer?
        switch source {
        case .superPlayer(let facade):
            player = facade.avPlayer
            superPlayer = facade
        case .stock(let stock):
            player = stock
            superPlayer = nil
        }
        let item = player.currentItem
        let position = max(0, milliseconds(player.currentTime()))
        // The engine's own fatal error, first and never merged into a classified line beside it:
        // on a stock player it is the only failure there is, and on a SuperPlayer it is the one
        // rung 6 delivered. Two vocabularies, neither expressed in the other.
        let delivered: (any Error)? =
            if let superPlayer { superPlayer.playerError } else { item?.error ?? player.error }
        let deliveredLine = delivered.map { "delivered \(code(of: $0))" }
        return HudFacts(
            superPlayer: superPlayer != nil,
            telemetryAttached: telemetry != nil,
            bufferedDurationMs: item.map { bufferedAhead(of: position, in: $0) } ?? 0,
            positionMs: position,
            selectedBitrateBps: item.flatMap(selectedBitrateBps),
            throughputEstimateBps: reading?.throughputEstimateBps,
            ceiling: superPlayer.map {
                $0.playbackDecision.trackSelection.maxVideoBitrateBps.map(Ceiling.bitsPerSecond)
                    ?? .unlimited
            } ?? .unavailable,
            droppedFrames: reading?.droppedFrames,
            failures: HudTelemetryReading.bounded(
                [deliveredLine].compactMap { $0 } + (reading?.failures ?? [])))
    }

    /// The variant playing, as its declared `BANDWIDTH` — the access log's `indicatedBitrate`, the
    /// quantity `QoeCollector` reports a switch in, so the two numbers a reader may see side by side
    /// are one quantity. Nil before the log has an entry, and for a log that states none.
    ///
    /// ref: https://developer.apple.com/documentation/avfoundation/avplayeritemaccesslogevent/indicatedbitrate
    @MainActor
    private static func selectedBitrateBps(_ item: AVPlayerItem) -> Int? {
        guard let indicated = item.accessLog()?.events.last?.indicatedBitrate, indicated > 0 else {
            return nil
        }
        return Int(indicated)
    }

    /// How far the loaded range holding the playhead runs past it; zero where none holds it.
    @MainActor
    private static func bufferedAhead(of positionMs: Int64, in item: AVPlayerItem) -> Int64 {
        for value in item.loadedTimeRanges {
            let range = value.timeRangeValue
            let start = milliseconds(range.start)
            let end = milliseconds(range.end)
            if start <= positionMs, positionMs <= end { return end - positionMs }
        }
        return 0
    }

    private static func milliseconds(_ time: CMTime) -> Int64 {
        time.isNumeric ? Int64((time.seconds * 1_000).rounded()) : 0
    }

    /// The engine's code for a delivered error: the domain and code of the error AVFoundation
    /// raised, under a ``SuperPlayerError`` where the facade delivered one — never a message.
    private static func code(of error: any Error) -> String {
        let engine = (error as? SuperPlayerError)?.underlyingError ?? error as NSError
        return "\(engine.domain):\(engine.code)"
    }
}
