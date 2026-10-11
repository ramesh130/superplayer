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
import os

/// A ``TelemetrySink`` that writes every event to the unified log, one line each — the Kotlin
/// `LogcatSink`, under subsystem `com.superplayer` and category `SuperPlayerQoE`, the Kotlin tag.
///
/// ```swift
/// configuration.telemetry = { QoeCollector(sink: .composite(analytics, OSLogSink())) }
/// ```
///
/// ## One line, and why that is the whole design
///
/// The Kotlin sink's argument, unchanged: this is read during an incident, in Console or through
/// `log stream --predicate 'category == "SuperPlayerQoE"'`, and the only property that matters is
/// that a person finds what they need by searching. So every event is one line of `key=value`
/// pairs, the discriminator first, in the Kotlin sink's grammar and with its names:
///
/// ```text
/// evt=rebuffer_ended v=2 sid=8F21… cid="urn:content:12345" t=1757500000000 mono=942310 durationMs=1840 seekInduced=false
/// ```
///
/// Enumerated values are spelt as the Kotlin constants are — `USER_INTENT`, `VIDEO_ON_DEMAND` —
/// rather than as the Swift cases, so one search finds one event on both platforms.
///
/// ## Level and privacy
///
/// `.default` for every event but the two failures, which are `.error`. The unified log has no
/// warning level — `Logger.warning` *is* `.error` — so this is the Kotlin `WARN` and not a
/// promotion: enough to filter to the failures, and `.default` rather than `.info` for the rest
/// because `.info` is kept in memory only and an incident is read after the fact.
///
/// Every field is logged `.public` except a failure's `message`, which is `.private` and is
/// therefore the line's last field, where the Kotlin line has `classification` and `positionMs`
/// after it: an engine's message can carry a URL and the URL a token, and the unified log redacts
/// a private value on a device not attached to a debugger. The grammar is the same; the order of
/// one line's last fields is not.
///
/// ## Cost
///
/// A line is formatted for every event whether or not anything is reading the log — a sink for a
/// debug build, and one to think about before a release one. A value type holding nothing, as the
/// Kotlin `object` is.
public struct OSLogSink: TelemetrySink {

    /// The Kotlin `LogcatSink.TAG`, and core's own telemetry category: a repeated literal rather
    /// than a shared constant, for the reason `TelemetryLog` gives.
    private static let logger = Logger(subsystem: "com.superplayer", category: "SuperPlayerQoE")

    public init() {}

    public func onEvent(_ event: TelemetryEvent) {
        let line = Self.line(for: event)
        if let message = line.privateMessage {
            Self.logger.log(
                level: line.level,
                "\(line.text, privacy: .public) message=\(message, privacy: .private)")
        } else {
            Self.logger.log(level: line.level, "\(line.text, privacy: .public)")
        }
    }

    /// One formatted event: the public text, a failure's message where it has one, and the level.
    struct Line: Equatable {
        let level: OSLogType
        let text: String
        let privateMessage: String?

        /// The whole line as a reader with the private value revealed sees it.
        var whole: String { privateMessage.map { "\(text) message=\($0)" } ?? text }
    }

    /// The one place the line format lives. A `switch` with no `default`, for the Kotlin `when`'s
    /// reason: this sink ships with the vocabulary, so a case added later fails to compile here
    /// rather than logging a name and nothing else, and the level is decided in the same branch as
    /// the text.
    static func line(for event: TelemetryEvent) -> Line {
        let common =
            "v=\(event.schemaVersion) sid=\(event.sessionId) cid=\(quote(event.contentId)) "
            + "t=\(event.timestampMs) mono=\(event.monotonicTimeMs)"
        switch event {
        case .sessionStarted(let e):
            // The decision's fields arrive with its halves (#415); the Kotlin line's buffer and
            // bitrate fields have nothing to print until then.
            return info("evt=session_started \(common) profile=\(e.profile.kotlinName)")
        case .decisionChanged(let e):
            return info("evt=decision_changed \(common) trigger=\(e.trigger.kotlinName)")
        case .sessionEnded(let e):
            return info("evt=session_ended \(common) dropped=\(e.droppedEventCount)")
        case .firstFrameRendered(let e):
            return info(
                "evt=first_frame \(common) ttffMs=\(e.timeToFirstFrameMs) "
                    + "from=\(e.startBoundary.kotlinName)")
        case .rebufferStarted(let e):
            return info("evt=rebuffer_started \(common) seekInduced=\(e.seekInduced)")
        case .rebufferEnded(let e):
            return info(
                "evt=rebuffer_ended \(common) durationMs=\(e.durationMs) seekInduced=\(e.seekInduced)"
            )
        case .startupFailed(let e):
            return failure("evt=startup_failed \(common)", e.failure, after: "")
        case .midStreamFailed(let e):
            return failure(
                "evt=midstream_failed \(common)", e.failure, after: " positionMs=\(e.positionMs)")
        case .trackSwitched(let e):
            return info(
                "evt=track_switched \(common) direction=\(e.direction.kotlinName) "
                    + "fromBps=\(orNull(e.fromBitrateBps)) toBps=\(e.toBitrateBps)")
        case .seekRequested(let e):
            return info(
                "evt=seek_requested \(common) fromMs=\(e.fromPositionMs) toMs=\(e.toPositionMs)")
        case .seekCompleted(let e):
            return info(
                "evt=seek_completed \(common) toMs=\(e.toPositionMs) latencyMs=\(e.seekLatencyMs)")
        case .liveLatencySampled(let e):
            return info(
                "evt=live_latency \(common) latencyMs=\(e.liveLatencyMs) "
                    + "targetMs=\(orNull(e.targetLiveLatencyMs))")
        case .playbackStateSampled(let e):
            return info(
                "evt=state_sample \(common) intervalMs=\(e.samplingIntervalMs) "
                    + "bitrateBps=\(orNull(e.videoBitrateBps)) bufferedMs=\(e.bufferedDurationMs) "
                    + "playing=\(e.playing) estimateBps=\(orNull(e.throughputEstimateBps))")
        case .licenceAcquisitionEnded(let e):
            // Not an error even when refused, for the Kotlin reason: what a refusal means arrives
            // on the failure event, and logging one fact twice at that level shows two problems.
            return info(
                "evt=licence_acquisition \(common) durationMs=\(e.durationMs) "
                    + "outcome=\(e.outcome.kotlinName) securityLevel=\(orNull(e.securityLevel))")
        case .videoFramesDropped(let e):
            return info(
                "evt=video_frames \(common) dropped=\(e.droppedFrames) "
                    + "repeated=\(e.repeatedFrames) elapsedPlayingMs=\(e.elapsedPlayingMs)")
        }
    }

    private static func info(_ text: String) -> Line {
        Line(level: .default, text: text, privateMessage: nil)
    }

    /// A failure as fields: the category, the engine's code quoted (an engine string is no more
    /// this sink's to trust than a message is), the classification only where there is one, then
    /// `after`, and the message last and private.
    private static func failure(_ head: String, _ failure: PlaybackFailure, after: String) -> Line {
        var text = "\(head) category=\(failure.category.kotlinName) code=\(quote(failure.code))"
        if let classification = failure.classification {
            text += " classification=\(classification)"
        }
        return Line(level: .error, text: text + after, privateMessage: quote(failure.message))
    }

    /// Quotes a value someone else supplied: a content id is the app's string and a message the
    /// engine's, and either can carry a space or a newline, which would end the one line this format
    /// promises.
    static func quote(_ value: String?) -> String {
        guard let value else { return "null" }
        let escaped = value.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: "\\n")
            .replacingOccurrences(of: "\r", with: "\\r")
        return "\"\(escaped)\""
    }

    /// An optional as the Kotlin line prints one: the value, or `null`.
    private static func orNull<Value>(_ value: Value?) -> String {
        value.map { "\($0)" } ?? "null"
    }
}

// The Kotlin constant each case is, so a line greps the same on both platforms — internal rather
// than private, because the session trace prints the same words for the same reason.

extension PlaybackProfile {
    var kotlinName: String {
        switch self {
        case .videoOnDemand: "VIDEO_ON_DEMAND"
        case .liveLinear: "LIVE_LINEAR"
        case .shortForm: "SHORT_FORM"
        case .tvLeanback: "TV_LEANBACK"
        case .dataSaver: "DATA_SAVER"
        }
    }
}

extension DecisionTrigger {
    var kotlinName: String {
        switch self {
        case .transportChanged: "TRANSPORT_CHANGED"
        case .streamTypeChanged: "STREAM_TYPE_CHANGED"
        case .rebufferEnded: "REBUFFER_ENDED"
        case .playbackSpeedChanged: "PLAYBACK_SPEED_CHANGED"
        case .throughputChanged: "THROUGHPUT_CHANGED"
        }
    }
}

extension TtffStartBoundary {
    var kotlinName: String {
        switch self {
        case .userIntent: "USER_INTENT"
        case .contentAdopted: "CONTENT_ADOPTED"
        }
    }
}

extension TrackSwitchDirection {
    var kotlinName: String {
        switch self {
        case .initial: "INITIAL"
        case .up: "UP"
        case .down: "DOWN"
        }
    }
}

extension LicenceOutcome {
    var kotlinName: String {
        switch self {
        case .acquiredFromServer: "ACQUIRED_FROM_SERVER"
        case .servedFromOfflineStore: "SERVED_FROM_OFFLINE_STORE"
        case .refused: "REFUSED"
        }
    }
}

extension FailureCategory {
    var kotlinName: String {
        switch self {
        case .network: "NETWORK"
        case .source: "SOURCE"
        case .decoder: "DECODER"
        case .drm: "DRM"
        case .renderer: "RENDERER"
        case .unknown: "UNKNOWN"
        case .storage: "STORAGE"
        }
    }
}
