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
import os

/// Where measurement leaves the library: one call, one event, the consumer's own pipeline
/// underneath — the Kotlin `TelemetrySink`, and ADR-0008 rule 1's reason for naming no engine
/// type: a sink is an app's data contract, and binding it to AVFoundation's vocabulary would make an
/// engine change a schema migration.
///
/// ## What a sink must tolerate
///
/// **Gaps.** Delivery is at-most-once, bounded and lossy under pressure (ADR-0008 rule 3); the count
/// of what was lost arrives on ``TelemetryEvent/SessionEnded/droppedEventCount``. **New cases**,
/// which ``TelemetryEvent``'s own doc comment says how to switch over.
///
/// ## Which thread this is called on, and why the compiler says so
///
/// **Never the main actor and never a thread the engine needs** (ADR-0008 rule 4): a sink is called
/// on SuperPlayer's own delivery queue — one serial queue for the process, so sixty pooled players
/// do not mean sixty threads — and calls are serialized, so an implementation needs no locking of
/// its own. A sink that blocks costs events, its own and other sessions', and never playback.
///
/// On Android that is a sentence in KDoc. Here it is three facts the compiler holds a consumer to
/// (ADR-0020 rule 5, `docs/ios-plan.md` §2's language row):
///
/// - The protocol is `Sendable`, so a sink can be handed to the delivery queue at all, and an
///   event is a `Sendable` value, so what crosses carries no reference back into the player.
/// - ``onEvent(_:)`` is a *nonisolated* requirement. A main-actor type cannot satisfy it — its
///   conformance "crosses into main actor-isolated code" and does not compile — and an isolated
///   conformance (`: @MainActor TelemetrySink`) is refused outright for a protocol that inherits
///   `Sendable`. So no sink can be one that runs where the engine's callbacks land.
/// - ``SuperPlayer`` is `@MainActor`, so a sink that reaches for the player from ``onEvent(_:)``
///   writes an `await` it cannot spell in a synchronous method — the Kotlin "do not touch the player
///   from here", which there is a wrong-thread violation at run time and here does not build.
///
/// What no compiler can say is how long a sink takes: one that never returns stops delivery for
/// every player in the process, so hand long work to your own queue.
public protocol TelemetrySink: Sendable {

    /// Hands `event` to whatever this sink writes to. An error thrown here costs this sink this
    /// event and nothing else: the delivery queue and ``composite(_:)`` both contain it.
    ///
    /// `throws` because an analytics SDK's own API usually does, and a sink that had to swallow
    /// every error itself would swallow it silently; contained here, it is logged. A *trap* —
    /// a force unwrap, an index out of range — is not an error and cannot be contained by anything
    /// in-process; Swift has no `catch (Throwable)`, which is the one place this is weaker than the
    /// Kotlin composite, and why a sink should throw rather than trap.
    func onEvent(_ event: TelemetryEvent) throws
}

extension TelemetrySink where Self == CompositeTelemetrySink {

    /// One sink that fans every event out to all of `sinks`, in the order they were given — the
    /// Kotlin `TelemetrySink.composite(...)`, spelled `.composite(...)` wherever a
    /// ``TelemetrySink`` is expected, since Swift reaches a static member through the protocol only
    /// by leading dot:
    ///
    /// ```swift
    /// let sink: any TelemetrySink = .composite(analytics, logSink)
    /// ```
    ///
    /// **A child that throws costs nobody else their event**: each child is called in its own `do`,
    /// the rest still receive the event, nothing propagates back to the shared delivery queue, and
    /// the failure is logged, because a sink that silently stops receiving is the defect nobody
    /// notices for a fortnight.
    public static func composite(_ sinks: any TelemetrySink...) -> CompositeTelemetrySink {
        CompositeTelemetrySink(children: sinks)
    }
}

/// What ``TelemetrySink/composite(_:)`` returns. A named type only because Swift's static member
/// lookup through a protocol needs one; build it through that factory.
public struct CompositeTelemetrySink: TelemetrySink {

    /// Copied at construction, for the Kotlin composite's reason: a membership that could change
    /// under it would drop events with no trace. A variadic argument is already a copy.
    private let children: [any TelemetrySink]

    fileprivate init(children: [any TelemetrySink]) {
        self.children = children
    }

    public func onEvent(_ event: TelemetryEvent) {
        for child in children {
            do {
                try child.onEvent(event)
            } catch {
                TelemetryLog.logger.warning(
                    "Telemetry sink \(String(describing: type(of: child)), privacy: .public) threw; other sinks unaffected: \(String(describing: error), privacy: .private)"
                )
            }
        }
    }
}

/// Where the telemetry path writes about itself.
///
/// The category is the Kotlin tag, `SuperPlayerQoE`, deliberately: a person debugging telemetry
/// filters on one name, and a sink that threw is the most interesting thing that name carries. The
/// delivery queue in `SuperPlayerTelemetry` and its `OSLogSink` (#409) write under the same
/// subsystem and category — a repeated literal rather than a shared constant, for the Kotlin
/// `TAG`'s reason and one more: the digester tracks a `package` declaration as API, and a log name
/// is not API.
///
/// ref: https://developer.apple.com/documentation/os/logger — a subsystem in reverse-DNS form, a
/// category naming the part of it
private enum TelemetryLog {
    static let logger = Logger(subsystem: "com.superplayer", category: "SuperPlayerQoE")
}

/// The thing ``SuperPlayer/Configuration/telemetry`` makes: something that watches one player and
/// writes to a ``TelemetrySink`` — the Kotlin `TelemetryCollector`.
///
/// Core declares it; `SuperPlayerTelemetry`'s `QoeCollector` (#409) implements it, which is where
/// every AVFoundation observation and every metric definition lives (ADR-0008 rule 2).
///
/// **A collector rather than a bare sink**, for Android's first reason: with collector-as-sink a
/// configuration handed a sink compiles and measures nothing, because the object that knows how to
/// derive events is the one the consumer forgot to wrap. Here that does not compile.
///
/// **Main-actor isolated**, because everything it is told comes from the facade, which is, and
/// everything it observes — key-value observation of the player and its item, their notifications —
/// is delivered there. What it derives leaves through a delivery queue, never by calling a sink
/// from here (ADR-0008 rule 4).
///
/// **Core signals the session edges** rather than letting a collector infer them, because only core
/// knows which item change carried a ``MediaRequest``: ``startSession(contentId:sessionId:)`` when
/// a player takes content on, ``endSession()`` when that content is replaced or the player is
/// released or recycled. Core always ends an open session before it starts the next, so a collector
/// never has to close one implicitly — a departure from the Kotlin facade, which leaves that to the
/// collector, made because here the facade sees every replacement it makes.
@MainActor
public protocol TelemetryCollector {

    /// Binds this collector to `player` for its lifetime: register observations here. Called once,
    /// from ``SuperPlayer/init(configuration:)``, before the player is handed back. Core passes the
    /// player rather than asking for something to register, for the Kotlin reason: what there is to
    /// observe is the player's, and core naming it in a signature here would be core deciding what
    /// telemetry observes.
    func attach(to player: SuperPlayer)

    /// A measurement session opens for `contentId`, under `sessionId`: stamp it on every event of
    /// the session. The id arrives rather than being minted here because CMCD sends the same string
    /// to the CDN (ADR-0008 rule 6) and only core can be the one place both read it from. Opaque, at
    /// most 64 characters; carry it, never parse it.
    func startSession(contentId: String, sessionId: String)

    /// The open session closes. A no-op when none is open, so a release after a recycle never
    /// produces two ends.
    func endSession()

    /// The consumer declared intent to play, at `monotonicTimeMs` on `CLOCK_MONOTONIC` — the Kotlin
    /// `declareIntent(monotonicTimeMs)`.
    ///
    /// Core forwards ``SuperPlayer/declarePlaybackIntent()`` here and does nothing else with it: the
    /// declaration is the start boundary of time to first frame, and what a boundary means is the
    /// schema's business rather than the facade's. Hold it for the next session that starts; a
    /// session that opens without one measures from its own start and says so through
    /// ``TtffStartBoundary/contentAdopted``. Usually called before
    /// ``startSession(contentId:sessionId:)`` — the app declares at the tap and loads once its
    /// catalogue answers — and a declaration after the session opened belongs to the next one
    /// (`docs/telemetry-schema.md`, *Time to first frame*).
    func declareIntent(monotonicTimeMs: Int64)

    /// The decision in force changed to `decision`, because of `trigger` — the Kotlin
    /// `decisionChanged(decision, trigger)`. Emit it as a ``TelemetryEvent/DecisionChanged`` under
    /// the open session; with none open there is nothing to stamp it on, and the next
    /// ``TelemetryEvent/SessionStarted`` carries the decision then in force, so nothing is lost.
    ///
    /// Core signals it rather than letting a collector infer it, for
    /// ``startSession(contentId:sessionId:)``'s reason: only core knows the trigger, and the trigger
    /// is what makes a decision series legible (ADR-0009 rule 6). Never called on a player under its
    /// profile's static policy.
    func decisionChanged(_ decision: PlaybackDecision, trigger: DecisionTrigger)

    /// The player is being released: unregister everything ``attach(to:)`` registered.
    func detach()

    /// A rung repaired `failure` by re-adopting the open session's content on a new item — the next
    /// source, the item rebuilt after a media services reset, or a refreshed credential
    /// (`PlayerStateRungs.swift`, ADR-0011 rule 10). **The session continues**: no edge is
    /// signalled, because one viewing rescued is one session, and the player's current item is now
    /// the one to observe.
    ///
    /// `failure` is what was repaired, classified, so its class can be reported as any failure's is
    /// (ADR-0011 rule 3) though the consumer was never handed it. A requirement of its own rather
    /// than an edge, because Android needs none — its facade re-adopts on the one `ExoPlayer` the
    /// collector already listens to — while here every item is observed by registration. A default
    /// that does nothing is provided, so a collector that observes no item is unchanged.
    func contentReopened(after failure: SuperPlayerError)

    /// Core ended the open session on `failure` although its item never failed — a live playlist
    /// that stopped advancing, which AVFoundation waits on rather than failing
    /// (`StaleLivePlaylistDetection.swift`, #421). Reported as any failure is (ADR-0011 rules 3 and
    /// 10): the item's own failure is what a collector otherwise hears, and here there is none to
    /// hear. A default that does nothing is provided, so a collector that observes no item is
    /// unchanged.
    func sessionFailed(_ failure: SuperPlayerError)

    /// A FairPlay licence acquisition ended after `durationMs` on `CLOCK_MONOTONIC`, with `outcome` —
    /// one ``TelemetryEvent/LicenceAcquisitionEnded`` under the open session, whose definition is
    /// `docs/telemetry-schema.md`'s *Licence acquisition*.
    ///
    /// Core signals it, as it signals ``contentReopened(after:)``, because the exchange is this
    /// library's own (`docs/ios-plan.md` §3.6): on Android the collector derives the span from
    /// Media3's DRM analytics callbacks, while here no AVFoundation interface a collector could
    /// observe reports a licence at all — the key session's delegate is `SuperPlayerDRM`'s. A key
    /// rotation is not signalled, for the schema's reason. A default that does nothing is provided,
    /// so a collector written before it is unchanged.
    func licenceAcquisitionEnded(durationMs: Int64, outcome: LicenceOutcome)

    /// The display's mode reading, on a player configured with `SuperPlayerTV`'s output: whether a
    /// display mode switch is in progress — the panel may be blank — and whether the viewer's
    /// *Match Content* settings allow one at all. One ``TelemetryEvent/DisplayModeChanged`` under the
    /// open session where the reading differs from the last this session reported, so the first
    /// reading of a session is always reported and a change heard twice is one event.
    ///
    /// Core signals it because the reading is the output binding's (`PlaybackOutput.swift`): a tvOS
    /// display manager belongs to a window, which a collector attached to a player cannot see. It is
    /// **reported and never acted on** — the system re-selects for a display change on Apple
    /// platforms (`docs/ios-plan.md` §3.8) — and Android, where a display change re-selects through
    /// SuperPlayer's own gate, emits none. A default that does nothing is provided, so a collector
    /// written before it is unchanged.
    func displayModeChanged(switchInProgress: Bool, criteriaMatchingEnabled: Bool)
}

extension TelemetryCollector {

    /// Does nothing: a collector that registers nothing on an item has nothing to move.
    public func contentReopened(after failure: SuperPlayerError) {}

    /// Does nothing: a collector that hears no failure has nothing to report.
    public func sessionFailed(_ failure: SuperPlayerError) {}

    /// Does nothing: a collector that measures no licence reports none.
    public func licenceAcquisitionEnded(durationMs: Int64, outcome: LicenceOutcome) {}

    /// Does nothing: a collector that reports no display has nothing to say about one.
    public func displayModeChanged(switchInProgress: Bool, criteriaMatchingEnabled: Bool) {}
}

extension SuperPlayer {

    /// Says that a viewer just asked for playback — the start boundary of time to first frame, the
    /// Kotlin `declarePlaybackIntent()`.
    ///
    /// Call it at the tap, before whatever the app does to turn that tap into a ``MediaRequest``:
    /// the catalogue lookup, the entitlement check, the navigation transition. That interval is part
    /// of what the viewer waited through, and `PRD.md` §3.4 puts the metric's boundary at intent
    /// rather than at item creation precisely so that it is counted. The library cannot see a tap,
    /// which is why this is declared rather than observed.
    ///
    /// Cheap and safe with no telemetry configured: it reads the monotonic clock and hands the
    /// reading to the collector, and a player configured without one does neither. Safe before the
    /// content is known, which is the point of it. A session that never gets one is measured from
    /// the moment the player took the content on and reports
    /// ``TtffStartBoundary/contentAdopted``, so the two are never silently averaged together.
    public func declarePlaybackIntent() {
        guard !isReleased, let collector else { return }
        // ref: CTA-2066 measures video start-up time from the viewer's request; CMCD v2's `msd`
        // uses the same boundary. `CLOCK_MONOTONIC` because the reading is one end of a duration —
        // ``TelemetryEvent``'s *The clocks* — and the collector reads the other end on it too.
        collector.declareIntent(
            monotonicTimeMs: Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000))
    }
}
