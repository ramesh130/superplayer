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

/// What SuperPlayer measured, as one closed vocabulary in the product every consumer takes — the
/// Kotlin `TelemetryEvent`'s sealed hierarchy as an enum with a payload per case (ADR-0008 rule 5,
/// `docs/ios-plan.md` §3.2).
///
/// **The definitions are `docs/telemetry-schema.md`, not this file**, on both platforms. Each case
/// below says what it carries; what a number *means* — its boundary, its denominator, what it
/// excludes — is written once, per metric, in that document, and `TelemetrySchemaDocumentTests`
/// holds the names here and the names there equal in both directions. The payload types carry the
/// document's event names exactly, and their fields the document's field names, so that one
/// pipeline can read both platforms' events under one schema.
///
/// Versioned by ``schemaVersion``, which tracks a metric's *meaning* and not the shape of the type
/// carrying it: a case or an optional field added leaves it alone, a changed definition moves it.
/// The value is the Kotlin `SCHEMA_VERSION`'s, and moves with it — one schema, one number.
///
/// An enum is closed, so a `switch` over it can be exhaustive — and a sink that takes the compiler
/// up on that stops compiling when a later version adds a case. A `default` branch is the
/// forward-compatible spelling, as `else` is in Kotlin.
///
/// ## The clocks
///
/// Two, and every event carries both, for the reasons the document's *Two clocks* gives.
/// `timestampMs` is a wall clock, epoch milliseconds, for joining to the app's other events; it can
/// step backwards. `monotonicTimeMs` is the clock every *duration* here is measured on: on Apple
/// platforms `CLOCK_MONOTONIC`, which, like Android's `elapsedRealtime()`, counts on through sleep
/// and is moved by no clock correction — not `CLOCK_UPTIME_RAW`, which stops while the device
/// sleeps and would shorten a duration that spanned it.
///
/// ref: `clock_gettime(3)` on Darwin: `CLOCK_MONOTONIC` "will continue to increment while the
/// system is asleep"
public enum TelemetryEvent: Sendable, Hashable {

    /// The current meaning-version of this vocabulary: the Kotlin `TelemetryEvent.SCHEMA_VERSION`.
    /// `docs/telemetry-schema.md`'s *Versioning* says what moves it, and its release notes what did.
    public static let schemaVersion = 2

    case sessionStarted(SessionStarted)
    case decisionChanged(DecisionChanged)
    case sessionEnded(SessionEnded)
    case firstFrameRendered(FirstFrameRendered)
    case rebufferStarted(RebufferStarted)
    case rebufferEnded(RebufferEnded)
    case startupFailed(StartupFailed)
    case midStreamFailed(MidStreamFailed)
    case trackSwitched(TrackSwitched)
    case seekRequested(SeekRequested)
    case seekCompleted(SeekCompleted)
    case licenceAcquisitionEnded(LicenceAcquisitionEnded)
    case liveLatencySampled(LiveLatencySampled)
    case playbackStateSampled(PlaybackStateSampled)
    case videoFramesDropped(VideoFramesDropped)
    case outputObscuredChanged(OutputObscuredChanged)
    case displayModeChanged(DisplayModeChanged)

    /// The session this event belongs to; the same value on every event between its start and end.
    public var sessionId: String { header.sessionId }

    /// The ``MediaRequest/contentId`` the session is of — the app's own identifier, never a URL.
    public var contentId: String { header.contentId }

    /// When this event happened, in wall-clock epoch milliseconds — for joining, not for measuring.
    public var timestampMs: Int64 { header.timestampMs }

    /// When this event happened on `CLOCK_MONOTONIC`, in milliseconds: the clock every duration is
    /// measured on and the one that orders events within a session.
    public var monotonicTimeMs: Int64 { header.monotonicTimeMs }

    /// The meaning-version of the metrics this event carries: ``schemaVersion``.
    public var schemaVersion: Int { Self.schemaVersion }

    /// The four fields every payload carries, read through one switch so that a case added below
    /// is a compile error here rather than a property that answers for some cases and not others.
    private var header: EventHeader {
        switch self {
        case .sessionStarted(let event): event.header
        case .decisionChanged(let event): event.header
        case .sessionEnded(let event): event.header
        case .firstFrameRendered(let event): event.header
        case .rebufferStarted(let event): event.header
        case .rebufferEnded(let event): event.header
        case .startupFailed(let event): event.header
        case .midStreamFailed(let event): event.header
        case .trackSwitched(let event): event.header
        case .seekRequested(let event): event.header
        case .seekCompleted(let event): event.header
        case .licenceAcquisitionEnded(let event): event.header
        case .liveLatencySampled(let event): event.header
        case .playbackStateSampled(let event): event.header
        case .videoFramesDropped(let event): event.header
        case .outputObscuredChanged(let event): event.header
        case .displayModeChanged(let event): event.header
        }
    }

    /// A player took content on and a measurement session opened for it.
    public struct SessionStarted: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// The profile the player was configured with — what kind of playback this session measures.
        public let profile: PlaybackProfile
        /// The decision the player was running at the start; the document says why a QoE number is
        /// uninterpretable without it.
        public let decision: PlaybackDecision

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            profile: PlaybackProfile, decision: PlaybackDecision
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.profile = profile
            self.decision = decision
        }
    }

    /// The decision in force changed after the session started, on a named ``DecisionTrigger``.
    public struct DecisionChanged: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// The decision now in force.
        public let decision: PlaybackDecision
        /// Which named trigger produced the re-consultation.
        public let trigger: DecisionTrigger

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            decision: PlaybackDecision, trigger: DecisionTrigger
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.decision = decision
            self.trigger = trigger
        }
    }

    /// The session closed: the player was released or recycled, or given other content.
    ///
    /// The session's terminal event, and the one ADR-0008 rule 3 says may never be dropped, because
    /// it is what carries ``droppedEventCount``.
    public struct SessionEnded: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// How many of this session's events the delivery path discarded under pressure. Non-zero
        /// means the stream is incomplete and the session belongs out of aggregates. Stamped as the
        /// event leaves the delivery queue, so a drop counted after the session ended still lands.
        public let droppedEventCount: Int
        /// The security level the session's protected content was delivered at, or nil where none
        /// was negotiated.
        public let securityLevel: String?

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            droppedEventCount: Int = 0, securityLevel: String? = nil
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.droppedEventCount = droppedEventCount
            self.securityLevel = securityLevel
        }
    }

    /// The first video frame of this session reached the display.
    public struct FirstFrameRendered: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// Milliseconds from ``startBoundary`` to the first frame, on the monotonic clock.
        public let timeToFirstFrameMs: Int64
        /// Where the measurement started.
        public let startBoundary: TtffStartBoundary

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            timeToFirstFrameMs: Int64, startBoundary: TtffStartBoundary
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.timeToFirstFrameMs = timeToFirstFrameMs
            self.startBoundary = startBoundary
        }
    }

    /// Playback stalled for data after it started — or, when ``seekInduced``, because a seek
    /// landed somewhere unbuffered. One event with a flag, for the Kotlin event's reason.
    public struct RebufferStarted: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// True when this stall is attributed to a seek, and therefore out of rebuffer ratio.
        public let seekInduced: Bool

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            seekInduced: Bool
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.seekInduced = seekInduced
        }
    }

    /// Playback resumed from the stall ``RebufferStarted`` opened.
    public struct RebufferEnded: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// How long the stall lasted, on the monotonic clock.
        public let durationMs: Int64
        /// The matching ``RebufferStarted``'s flag, repeated so either event stands alone.
        public let seekInduced: Bool

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            durationMs: Int64, seekInduced: Bool
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.durationMs = durationMs
            self.seekInduced = seekInduced
        }
    }

    /// Playback failed before the first frame — the session produced no video at all.
    public struct StartupFailed: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// What went wrong, in this library's vocabulary rather than the engine's.
        public let failure: PlaybackFailure

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            failure: PlaybackFailure
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.failure = failure
        }
    }

    /// Playback failed after the first frame had rendered.
    public struct MidStreamFailed: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// What went wrong, in this library's vocabulary rather than the engine's.
        public let failure: PlaybackFailure
        /// How far into the content playback had reached, in media milliseconds.
        public let positionMs: Int64

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            failure: PlaybackFailure, positionMs: Int64
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.failure = failure
            self.positionMs = positionMs
        }
    }

    /// The video rendition being played changed, or was first chosen. Bitrates are the
    /// rendition's declared peak, which is what a playlist states.
    public struct TrackSwitched: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// The bitrate played before this switch, or nil for the initial choice.
        public let fromBitrateBps: Int?
        /// The bitrate now played.
        public let toBitrateBps: Int
        /// Up, down, or the initial selection.
        public let direction: TrackSwitchDirection

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            fromBitrateBps: Int?, toBitrateBps: Int, direction: TrackSwitchDirection
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.fromBitrateBps = fromBitrateBps
            self.toBitrateBps = toBitrateBps
            self.direction = direction
        }
    }

    /// A seek was asked for; paired with ``SeekCompleted``.
    public struct SeekRequested: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// Where playback was, in media milliseconds, when the seek was issued.
        public let fromPositionMs: Int64
        /// Where the seek asked to go, in media milliseconds.
        public let toPositionMs: Int64

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            fromPositionMs: Int64, toPositionMs: Int64
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.fromPositionMs = fromPositionMs
            self.toPositionMs = toPositionMs
        }
    }

    /// Playback resumed at the seek target.
    public struct SeekCompleted: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// Where playback actually resumed, in media milliseconds.
        public let toPositionMs: Int64
        /// Milliseconds from the matching ``SeekRequested``, on the monotonic clock.
        public let seekLatencyMs: Int64

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            toPositionMs: Int64, seekLatencyMs: Int64
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.toPositionMs = toPositionMs
            self.seekLatencyMs = seekLatencyMs
        }
    }

    /// One licence acquisition finished: how long it took and how it ended. SuperPlayer's own
    /// metric — CTA-2066 has none, which the document says.
    public struct LicenceAcquisitionEnded: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// Milliseconds from a key request being opened to keys held or refused, on the monotonic
        /// clock.
        public let durationMs: Int64
        /// Where the keys came from, or that they never arrived.
        public let outcome: LicenceOutcome
        /// The security level in force when this acquisition ended, or nil where none was
        /// negotiated.
        public let securityLevel: String?

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            durationMs: Int64, outcome: LicenceOutcome, securityLevel: String? = nil
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.durationMs = durationMs
            self.outcome = outcome
            self.securityLevel = securityLevel
        }
    }

    /// How far behind the live edge this session is, sampled; live content only.
    public struct LiveLatencySampled: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// Milliseconds between the playback position and the live edge at the sample instant.
        public let liveLatencyMs: Int64
        /// The latency the stream or the profile asked for, or nil when neither stated one.
        public let targetLiveLatencyMs: Int64?

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            liveLatencyMs: Int64, targetLiveLatencyMs: Int64?
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.liveLatencyMs = liveLatencyMs
            self.targetLiveLatencyMs = targetLiveLatencyMs
        }
    }

    /// A periodic sample of what playback is doing: the rendition, the buffer, whether anything
    /// moves, and the estimate beside the rendition.
    public struct PlaybackStateSampled: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// The nominal gap between samples, in milliseconds, and the weight of this one.
        public let samplingIntervalMs: Int64
        /// The declared peak bitrate of the video rendition playing, or nil when none is.
        public let videoBitrateBps: Int?
        /// Media milliseconds buffered ahead of the playback position.
        public let bufferedDurationMs: Int64
        /// Whether the position was advancing at the sample instant.
        public let playing: Bool
        /// The engine's throughput estimate, in bits per second, or nil where it has reported none.
        /// On iOS that is the access log's `observedBitrate`, Apple's smoothed figure rather than a
        /// sample (`docs/ios-plan.md` §3.2), which the document says beside the metric.
        public let throughputEstimateBps: Int?

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            samplingIntervalMs: Int64, videoBitrateBps: Int?, bufferedDurationMs: Int64,
            playing: Bool, throughputEstimateBps: Int? = nil
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.samplingIntervalMs = samplingIntervalMs
            self.videoBitrateBps = videoBitrateBps
            self.bufferedDurationMs = bufferedDurationMs
            self.playing = playing
            self.throughputEstimateBps = throughputEstimateBps
        }
    }

    /// Video frames dropped or repeated over an interval of playing time. **Video frames, not UI
    /// frames** — the document's section on why is the one this metric is most often read wrong
    /// without.
    public struct VideoFramesDropped: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// Video frames discarded without being presented, over this interval.
        public let droppedFrames: Int
        /// Video frames presented twice because no new one was ready, over this interval.
        public let repeatedFrames: Int
        /// The playing time this interval covers, in milliseconds on the monotonic clock.
        public let elapsedPlayingMs: Int64

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            droppedFrames: Int, repeatedFrames: Int, elapsedPlayingMs: Int64
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.droppedFrames = droppedFrames
            self.repeatedFrames = repeatedFrames
            self.elapsedPlayingMs = elapsedPlayingMs
        }
    }

    /// The picture began, or stopped, being obscured because an external display lacks the output
    /// protection the content requires — HDCP on a cable or an AirPlay receiver. **Reported and never
    /// acted on**: whether protected content may be shown on an output is the licence's policy, which
    /// the platform enforces, so the client's part is to say so (ADR-0012 rule 11). Emitted on a player
    /// configured with ``SuperPlayer/Configuration/drm`` alone, from
    /// `AVPlayer.isOutputObscuredDueToInsufficientExternalProtection`; the Kotlin twin is declared and
    /// never emitted, since Android has no such reading.
    public struct OutputObscuredChanged: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// Whether the output is obscured from this moment on.
        public let obscured: Bool

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            obscured: Bool
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.obscured = obscured
        }
    }

    /// The display's mode reading on a television: whether a display mode switch is in progress —
    /// the panel may be blank while the link resynchronises — and whether the viewer's *Match
    /// Content* settings allow a switch at all. The first of a session is the reading as the display
    /// was asked for the content's criteria; each later one a switch beginning or ending, or the
    /// settings changing. **Reported and never acted on**: on Apple platforms the system re-selects
    /// for a display change itself (`docs/ios-plan.md` §3.8). Emitted on a player configured with
    /// `SuperPlayerTV`'s output on tvOS alone, from `AVDisplayManager`; the Kotlin twin is declared
    /// and never emitted, since Android's display change re-selects through SuperPlayer's own gate
    /// and appears as `TrackSwitched` (ADR-0014 rule 5).
    public struct DisplayModeChanged: Sendable, Hashable {
        public let sessionId: String
        public let contentId: String
        public let timestampMs: Int64
        public let monotonicTimeMs: Int64
        /// Whether a display mode switch is in progress from this moment on.
        public let switchInProgress: Bool
        /// Whether the viewer's settings allow the display to switch modes to match content.
        public let criteriaMatchingEnabled: Bool

        public init(
            sessionId: String, contentId: String, timestampMs: Int64, monotonicTimeMs: Int64,
            switchInProgress: Bool, criteriaMatchingEnabled: Bool
        ) {
            self.sessionId = sessionId
            self.contentId = contentId
            self.timestampMs = timestampMs
            self.monotonicTimeMs = monotonicTimeMs
            self.switchInProgress = switchInProgress
            self.criteriaMatchingEnabled = criteriaMatchingEnabled
        }
    }
}

/// The four fields every payload carries, gathered for ``TelemetryEvent``'s accessors.
private struct EventHeader {
    let sessionId: String
    let contentId: String
    let timestampMs: Int64
    let monotonicTimeMs: Int64
}

/// Every payload has the four fields under the same names; this is how the enum reads them.
private protocol HasEventHeader {
    var sessionId: String { get }
    var contentId: String { get }
    var timestampMs: Int64 { get }
    var monotonicTimeMs: Int64 { get }
}

extension HasEventHeader {
    fileprivate var header: EventHeader {
        EventHeader(
            sessionId: sessionId, contentId: contentId, timestampMs: timestampMs,
            monotonicTimeMs: monotonicTimeMs)
    }
}

extension TelemetryEvent.SessionStarted: HasEventHeader {}
extension TelemetryEvent.DecisionChanged: HasEventHeader {}
extension TelemetryEvent.SessionEnded: HasEventHeader {}
extension TelemetryEvent.FirstFrameRendered: HasEventHeader {}
extension TelemetryEvent.RebufferStarted: HasEventHeader {}
extension TelemetryEvent.RebufferEnded: HasEventHeader {}
extension TelemetryEvent.StartupFailed: HasEventHeader {}
extension TelemetryEvent.MidStreamFailed: HasEventHeader {}
extension TelemetryEvent.TrackSwitched: HasEventHeader {}
extension TelemetryEvent.SeekRequested: HasEventHeader {}
extension TelemetryEvent.SeekCompleted: HasEventHeader {}
extension TelemetryEvent.LicenceAcquisitionEnded: HasEventHeader {}
extension TelemetryEvent.LiveLatencySampled: HasEventHeader {}
extension TelemetryEvent.PlaybackStateSampled: HasEventHeader {}
extension TelemetryEvent.VideoFramesDropped: HasEventHeader {}
extension TelemetryEvent.OutputObscuredChanged: HasEventHeader {}
extension TelemetryEvent.DisplayModeChanged: HasEventHeader {}

/// Where ``TelemetryEvent/FirstFrameRendered`` started counting from — the Kotlin
/// `TtffStartBoundary`. The boundary is the whole argument of the metric, and the document's *Time
/// to first frame* is that argument.
public enum TtffStartBoundary: Sendable, Hashable {
    /// Measured from the consumer's declared intent to play — the boundary CTA-2066 describes.
    case userIntent
    /// Measured from the player taking the content on, because no intent was declared. A narrower
    /// interval, never mixed with ``userIntent`` silently.
    case contentAdopted
}

/// Which way a rendition switch went, as ``TelemetryEvent/TrackSwitched`` reports it.
public enum TrackSwitchDirection: Sendable, Hashable {
    /// The first rendition of the session; there is nothing to have switched from.
    case initial
    /// To a higher bitrate than the one playing.
    case up
    /// To a lower bitrate than the one playing.
    case down
}

/// How the acquisition ``TelemetryEvent/LicenceAcquisitionEnded`` measured ended — three outcomes,
/// because where the keys came from is the question a protected catalogue is read for.
public enum LicenceOutcome: Sendable, Hashable {
    /// Keys arrived from the licence server, over the network.
    case acquiredFromServer
    /// Keys were restored from a licence already stored on the device; no request was made.
    case servedFromOfflineStore
    /// No keys: refused, never arrived, or the device's protection stack failed the request.
    case refused
}

/// What failed, in SuperPlayer's own vocabulary — the Kotlin `PlaybackFailure`, field for field.
public struct PlaybackFailure: Sendable, Hashable {
    /// The bucket a failure rate is grouped by.
    public let category: FailureCategory
    /// A stable identifier for the failure *the engine* reported, or nil when it offered none. On
    /// iOS the error's domain and code, which the collector ticket's derivation names.
    public let code: String?
    /// Whatever the engine said, for a human reading a log. Never parsed, never a grouping key.
    public let message: String?
    /// What the resilience product classified the failure as, or nil on a player configured
    /// without it — nil meaning, honestly, that nothing was there to classify it.
    public let classification: String?

    public init(
        category: FailureCategory, code: String?, message: String?, classification: String? = nil
    ) {
        self.category = category
        self.code = code
        self.message = message
        self.classification = classification
    }
}

/// The buckets ``PlaybackFailure`` sorts a failure into — the Kotlin `FailureCategory`'s seven, in
/// its order, ``storage`` last because it was added last (#244).
public enum FailureCategory: Sendable, Hashable {
    /// The bytes did not arrive: connectivity, timeout, an HTTP status, a DNS failure.
    case network
    /// The bytes arrived and were not playable: a malformed playlist, an unparsable container.
    case source
    /// No decoder could be initialised, or one failed while decoding.
    case decoder
    /// Licence acquisition, provisioning, or key rotation failed.
    case drm
    /// The renderer or the output surface failed with playable content and a working decoder.
    case renderer
    /// Everything else, including a failure reported without a usable cause.
    case unknown
    /// The device's storage could not hold what was being written. Reached by no playback session.
    case storage
}
