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
import SuperPlayerTelemetry

/// One session, as one artifact a viewer's device can produce and a CDN engineer can read: the
/// session trace one layer richer — the Kotlin `SessionBundle` (`PRD.md` §3.6, ADR-0015 rule 9),
/// in **the same format and the same version** (`docs/session-bundle.md` is the format, written for
/// a reader who has never seen this repository; ADR-0020 rule 4 shares it rather than restating it).
///
/// ```swift
/// let recorder = SessionTraceRecorder()
/// var configuration = SuperPlayer.Configuration()
/// configuration.telemetry = { QoeCollector(sink: .composite(appSink, recorder)) }
/// let player = SuperPlayer(configuration: configuration)
/// recorder.attach(to: player)
/// // ... play, fail, release; take the trace once the sink has seen SessionEnded ...
/// let bundle = SessionBundle(trace: recorder.trace())
/// bugReport.attach(bundle.format())
/// ```
///
/// **It is the trace's own grammar, not a second one.** Every line is `+<ms> <kind> <fields>`,
/// ordered and redacted exactly as ``SessionTrace`` is, because two authored formats would be two
/// redaction implementations of which one would be behind. What this type adds is the one kind no
/// recorder of a session can hold, the `capability` snapshot, and the header naming the artifact.
///
/// **The snapshot is the phase's one deliberate exposure, and it obeys ADR-0015 rule 10** — the
/// seventh redaction rule, stated in ``SessionTraceRecorder``'s documentation beside the six a trace
/// obeys: what the device can **do** is admitted, what the device **is** is refused. The boundary is
/// not enforced here. Core reads the snapshot and hands it over whole (its `package`
/// `CapabilitySnapshot`, rule 3's second seam), so a field this bundle could print is a field that
/// type carries, and it carries no model, no identifier and no build string. Its iOS lines differ
/// from the Kotlin ones in their fields, because Apple's platforms answer different questions;
/// `docs/session-bundle.md` lists both, and ADR-0015 rule 10's #429 addendum argues the iOS set.
///
/// **No player is read.** The Kotlin builder takes the player for one field, the security level a
/// protected session's licences were delivered at, and FairPlay negotiates no level
/// (`docs/ios-plan.md` §3.6: `SessionEnded.securityLevel` is nil on Apple's platforms by schema).
/// So an iOS bundle has no `capability protection` line — a line that could only ever say one thing
/// says nothing — and no parameter is taken that nothing would read.
///
/// **Versioning.** ``formatVersion`` is the Kotlin `FORMAT_VERSION`, shared rather than restated,
/// and moves on ADR-0008 rule 5's rule: when a line's *meaning* changes, not when a kind or a field
/// is added. ``SessionTrace/formatVersion`` is printed on its own line, so a reader knows which
/// grammar the trace lines are in.
public struct SessionBundle: Sendable, Hashable, CustomStringConvertible {

    /// The meaning of the lines. Bumps when a line's meaning changes, not when a kind is added.
    public static let formatVersion = 1

    /// The first line of every formatted bundle.
    public static let formatHeader = "superplayer-session-bundle v\(formatVersion)"

    /// Every line of the bundle, in order, without the header: the `capability` kind, then the
    /// trace's.
    public let lines: [String]

    /// Whether each line carries the `+<ms>` column — ``SessionTrace/timed`` of the trace it was
    /// built from.
    public let timed: Bool

    /// The bundle of `trace`, with the device read now.
    @MainActor
    public init(trace: SessionTrace) {
        self.init(trace: trace, capabilities: .current())
    }

    /// The bundle of `trace` on a device a test states, as the Kotlin golden states its device
    /// through `DeviceStatement`: a snapshot read from whichever simulator ran the test would pin
    /// that host's OS version and decoders in a committed file.
    package init(trace: SessionTrace, capabilities: CapabilitySnapshot) {
        // The `+0` column only where the trace it joins carries one, for the Kotlin reason: a
        // capability is a fact about the whole session, and the time the bundle was built would be
        // the one number in the artifact saying when a report was made rather than when something
        // happened.
        let stamped = Self.capabilityLines(capabilities).map {
            trace.timed ? "+0 capability \($0)" : "capability \($0)"
        }
        self.init(lines: stamped + trace.lines, timed: trace.timed)
    }

    private init(lines: [String], timed: Bool) {
        self.lines = lines
        self.timed = timed
    }

    /// The bundle as text: three header lines, then ``lines``, each newline-terminated.
    public func format() -> String {
        let timings = timed ? "timings relative-ms" : "timings omitted"
        return ([Self.formatHeader, SessionTrace.formatHeader, timings] + lines)
            .map { $0 + "\n" }.joined()
    }

    public var description: String { format() }

    /// The reading a golden compares on a real-time engine: the `capability` lines untimed and in
    /// their order, then the trace's lines as ``SessionTrace/structural()`` reads them (ADR-0021
    /// rule 4 and its #411 addendum). A capability carries no reading of time, so it is held equal
    /// whole.
    public func structural() -> SessionBundle {
        let untimed = SessionTrace(lines: lines, timed: timed).withoutTimings().lines
        let capabilities = untimed.filter { $0.hasPrefix("capability ") }
        let trace = SessionTrace(
            lines: untimed.filter { !$0.hasPrefix("capability ") }, timed: false
        ).structural()
        return SessionBundle(lines: capabilities + trace.lines, timed: false)
    }

    /// The `capability` lines in a fixed order: the device, one line per video codec in
    /// `CapabilitySnapshot.videoCodecs`' order, then the display.
    ///
    /// Every value is one ``CapabilitySnapshot`` carries, which is the whole of rule 10's
    /// enforcement: this function cannot print what it was not handed. A reading the platform did
    /// not give prints `unknown`, the Kotlin bundle's word, and never a guess.
    static func capabilityLines(_ snapshot: CapabilitySnapshot) -> [String] {
        ["device platform=\(snapshot.platform) osVersion=\(snapshot.osVersion) "
            + "memoryBudgetMb=\(orUnknown(snapshot.memoryBudgetMb))"]
            + snapshot.videoDecoders.map { "decoder mime=\($0.mimeType) hardware=\($0.hardware)" }
            + [
                "display shortEdgePx=\(orUnknown(snapshot.displayShortEdgePx)) "
                    + "hdrPlayback=\(snapshot.hdrPlaybackEligible)"
            ]
    }

    /// What the platform did not say, as against an answer of none.
    private static func orUnknown(_ value: Int?) -> String {
        value.map(String.init) ?? "unknown"
    }
}
