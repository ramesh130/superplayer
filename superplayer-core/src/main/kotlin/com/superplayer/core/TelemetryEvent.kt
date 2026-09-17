/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.core

/**
 * What SuperPlayer measured, as one sealed hierarchy rooted in the module every consumer takes.
 *
 * One vocabulary rather than one per module, versioned by [SCHEMA_VERSION] (ADR-0008 rule 5). The
 * version tracks a metric's *meaning*, not the shape of the class carrying it: adding a field, or
 * adding an event type, leaves it alone; changing what an existing number counts — a different
 * rebuffer denominator, a different start boundary for time-to-first-frame — is what raises it, so
 * that a consumer comparing two releases' numbers can tell whether they are comparable.
 *
 * Every event names the session it belongs to and the content that session is of, so that events
 * from concurrently playing players — a feed holds several — are separable in a pipeline that
 * receives them interleaved.
 *
 * **The definitions are `docs/telemetry-schema.md`, not this file.** Each event below says what it
 * carries; what the number *means* — the boundary it is measured from, the denominator it is a rate
 * over, what it excludes and why — is written down once, per metric, with its CTA-2066 citation, in
 * that document. The KDoc here says enough to use the type and points at the document for the rest,
 * because two prose definitions of one metric is F8 arriving inside this library.
 *
 * ## The clocks
 *
 * Two, deliberately, and every event carries both.
 *
 * [timestampMs] is a wall clock — `System.currentTimeMillis()` — because the consumer of these
 * events is a pipeline that has to line them up against events from elsewhere in the app, and only
 * a wall clock does that. It can step backwards when the device's clock is corrected.
 *
 * [monotonicTimeMs] is `android.os.SystemClock.elapsedRealtime()`, which cannot: it counts
 * milliseconds since boot, including deep sleep, and no clock correction moves it. It is what orders
 * two events within a session and it is the clock every *duration* in this vocabulary is measured on
 * — a time to first frame or a rebuffer length derived from wall-clock readings would be wrong by
 * whatever the clock did in between.
 */
public sealed class TelemetryEvent {

    /** The session this event belongs to; the same value for every event between start and end. */
    public abstract val sessionId: String

    /** The [MediaRequest.contentId] the session is of — the app's own identifier, never a URL. */
    public abstract val contentId: String

    /**
     * When this event happened, as a wall-clock epoch millisecond. See the class KDoc on the clocks:
     * this one is for joining to the app's other events, and not for measuring an interval.
     */
    public abstract val timestampMs: Long

    /**
     * When this event happened, as `SystemClock.elapsedRealtime()` — milliseconds since boot,
     * deep sleep included. The clock every duration here is measured on, and the one that orders
     * events within a session. Comparable only to other readings from the same boot of the same
     * device.
     */
    public abstract val monotonicTimeMs: Long

    /** The meaning-version of the metrics this event carries — see the class KDoc. */
    public val schemaVersion: Int get() = SCHEMA_VERSION

    /**
     * A player took content on and a measurement session opened for it.
     *
     * Emitted from the one place both `setMediaRequest` and a session controller's resolved content
     * pass through, so content started from a car head unit opens a session exactly like content
     * started from the app.
     */
    public data class SessionStarted(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** The profile the player was built with — what kind of playback this session measures. */
        public val profile: PlaybackProfile,
        /**
         * The buffering and track-selection policy this player was actually built with
         * ([SuperPlayer.playbackDecision]).
         *
         * Here because a QoE number is uninterpretable without it: a rebuffer ratio measured under
         * `DATA_SAVER`'s buffer sizes and one measured under `LIVE_LINEAR`'s are two different
         * measurements, and a pipeline that cannot tell them apart will average them. Carrying the
         * decision rather than only the profile is what keeps that true when a later phase's
         * adaptive [PlaybackPolicy] makes the profile stop predicting it.
         */
        public val decision: PlaybackDecision,
    ) : TelemetryEvent()

    /**
     * The decision in force changed after the session started: the policy was consulted again on
     * one of the named [DecisionTrigger]s and answered differently.
     *
     * A pipeline reconstructs the decision a session was running at any moment from
     * [SessionStarted.decision] and the sequence of these — which is what makes a QoE number
     * measured under an adaptive policy interpretable at all. Only emitted on a player whose engine
     * can honour a changed decision whole; a player consulted once emits none (ADR-0009 rule 5).
     *
     * An addition of shape rather than of meaning, so not a [SCHEMA_VERSION] bump (ADR-0008
     * rule 5); `docs/telemetry-schema.md` defines it.
     */
    public data class DecisionChanged(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** The decision now in force ([SuperPlayer.playbackDecision]). */
        public val decision: PlaybackDecision,
        /** Which named trigger produced the re-consultation. */
        public val trigger: DecisionTrigger,
    ) : TelemetryEvent()

    /**
     * The session closed: the player was released, recycled into a [PlayerPool], or given different
     * content through [SuperPlayer.setMediaRequest] or [SuperPlayer.restoreSnapshot].
     *
     * Those are the ways content changes *with an identity attached*, and they are therefore the
     * only ways a session can end other than by the player going away. A consumer that moves the
     * player on with Media3's own `setMediaItem` instead leaves the session open, and what follows
     * is reported under the previous content's id — the same gap `SuperPlayer.saveSnapshot`
     * documents from the other side, and for the same reason: a raw `MediaItem` carries no
     * [MediaRequest.contentId] for a session to be of. Mixing the two APIs on one player is what
     * produces it; a player driven through [SuperPlayer.setMediaRequest] throughout cannot.
     *
     * This is also how an exit is observed at all. The library sees a `release`, a recycle or a
     * content change; it never sees a back press, and a library that claimed to would be guessing
     * about its consumer's navigation. Exit-before-video-start is therefore derived rather than
     * reported: a session that ended with no [FirstFrameRendered] in it is one the viewer left
     * before video started. `docs/telemetry-schema.md` states that derivation.
     *
     * The session's terminal event, and the one ADR-0008 rule 3 says may never be dropped, because
     * it is what carries [droppedEventCount].
     */
    public data class SessionEnded(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /**
         * How many events of this session the delivery path discarded under pressure.
         *
         * Non-zero means this session's event stream is incomplete, and a session that says so
         * should be **excluded** from aggregates rather than averaged in: a rebuffer ratio computed
         * from a partially-dropped stream is a plausible wrong number that nobody audits. That is
         * the whole reason rule 3 requires the count and requires this event to be undroppable —
         * a declaration of what was lost is worthless if pressure can lose it.
         *
         * Counted at delivery rather than at the moment the session ended, so a drop caused by
         * memory pressure after the last event was queued still lands here.
         */
        public val droppedEventCount: Int = 0,

        /**
         * The `securityLevel` this session's protected content was actually delivered at — `"L1"`,
         * `"L3"` — or null on a session that negotiated none, which is every unprotected session and
         * every protected one on a device that could honour the level it reports.
         *
         * ADR-0012 rule 11: a session that opened at a *reduced* level had a different thing
         * delivered from the one it was entitled to, and a support engineer reading a session needs
         * to know which. Carried on this event rather than on [SessionStarted] because it is not
         * settled when a session starts — the permission is asked of the licence server and the first
         * session opens after the answer — and carried at all rather than left to be inferred,
         * because nothing else in a session's stream says it.
         *
         * Read from [SuperPlayer.deliveredSecurityLevel], which is the one place it is decided. An
         * addition of shape rather than of meaning, so not a [SCHEMA_VERSION] bump (ADR-0008 rule 5);
         * `docs/telemetry-schema.md` defines it.
         */
        public val securityLevel: String? = null,
    ) : TelemetryEvent()

    /**
     * The first video frame of this session reached the display.
     *
     * CTA-2066's video start-up time, and the metric whose start boundary is the reason
     * [SuperPlayer.declarePlaybackIntent] exists. [startBoundary] says which boundary this
     * measurement actually used, so a pipeline never has to assume: an app that declares intent and
     * one that does not are both measurable, and their numbers are not silently mixed.
     */
    public data class FirstFrameRendered(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** Milliseconds from [startBoundary] to the first rendered frame, on the monotonic clock. */
        public val timeToFirstFrameMs: Long,
        /** Where the measurement started — see [TtffStartBoundary]. */
        public val startBoundary: TtffStartBoundary,
    ) : TelemetryEvent()

    /**
     * Playback stalled for data after it had started — or, when [seekInduced], stalled because a
     * seek landed somewhere unbuffered.
     *
     * The two are one event with a flag rather than two events, because the boundary between them is
     * a judgement made at collection time and a pipeline that receives them separately cannot revise
     * it. `docs/telemetry-schema.md` defines exactly what makes a stall seek-induced, and rebuffer
     * ratio excludes those.
     */
    public data class RebufferStarted(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** True when this stall is attributed to a seek, and therefore out of rebuffer ratio. */
        public val seekInduced: Boolean,
    ) : TelemetryEvent()

    /** Playback resumed from the stall [RebufferStarted] opened. */
    public data class RebufferEnded(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** How long the stall lasted, on the monotonic clock. */
        public val durationMs: Long,
        /** The flag the matching [RebufferStarted] carried, repeated so either event stands alone. */
        public val seekInduced: Boolean,
    ) : TelemetryEvent()

    /**
     * Playback failed before the first frame — the session produced no video at all.
     *
     * Split from [MidStreamFailed] because CTA-2066 counts them separately and they mean different
     * things to a viewer: nothing played, versus something played and then stopped.
     */
    public data class StartupFailed(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** What went wrong, in this library's own vocabulary rather than the engine's. */
        public val failure: PlaybackFailure,
    ) : TelemetryEvent()

    /** Playback failed after the first frame had rendered. See [StartupFailed] for the split. */
    public data class MidStreamFailed(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** What went wrong, in this library's own vocabulary rather than the engine's. */
        public val failure: PlaybackFailure,
        /** How far into the content playback had reached, in media milliseconds. */
        public val positionMs: Long,
    ) : TelemetryEvent()

    /**
     * The video rendition being played changed — an ABR upshift or downshift, or the initial choice.
     *
     * Bitrates are the rendition's declared peak bitrate, which is what a manifest states and what
     * every comparable metric is computed from; it is not a measurement of what was transferred.
     */
    public data class TrackSwitched(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** The bitrate being played before this switch, or null when this is the initial choice. */
        public val fromBitrateBps: Int?,
        /** The bitrate now being played. */
        public val toBitrateBps: Int,
        /** Up, down, or the initial selection — see [TrackSwitchDirection]. */
        public val direction: TrackSwitchDirection,
    ) : TelemetryEvent()

    /** A seek was asked for. Paired with [SeekCompleted], which is what makes seek latency a number. */
    public data class SeekRequested(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** Where playback was, in media milliseconds, when the seek was issued. */
        public val fromPositionMs: Long,
        /** Where the seek asked to go, in media milliseconds. */
        public val toPositionMs: Long,
    ) : TelemetryEvent()

    /** Playback resumed at the seek target. */
    public data class SeekCompleted(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** Where playback actually resumed, in media milliseconds. */
        public val toPositionMs: Long,
        /** Milliseconds from the matching [SeekRequested], on the monotonic clock. */
        public val seekLatencyMs: Long,
    ) : TelemetryEvent()

    /**
     * How far behind the live edge this session is, sampled.
     *
     * `LIVE_LINEAR`'s metric, and emitted only for live content: a sample of zero from on-demand
     * content would be a number a dashboard would happily average.
     */
    public data class LiveLatencySampled(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** Milliseconds between the playback position and the live edge at the sample instant. */
        public val liveLatencyMs: Long,
        /** The latency the stream or the profile asked for, or null when neither stated one. */
        public val targetLiveLatencyMs: Long?,
    ) : TelemetryEvent()

    /**
     * A periodic sample of what playback is doing right now: the rendition, the buffer, and whether
     * anything is actually moving.
     *
     * A sample rather than a change notification, because bitrate distribution and buffer health are
     * time-weighted quantities and a pipeline cannot weight what it did not receive at a known
     * cadence. [samplingIntervalMs] is that cadence, carried on every sample so the weight is in the
     * event rather than in a consumer's assumption about the library's configuration.
     */
    public data class PlaybackStateSampled(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** The nominal gap between samples, in milliseconds, and the weight of this one. */
        public val samplingIntervalMs: Long,
        /** The declared peak bitrate of the video rendition playing, or null when none is. */
        public val videoBitrateBps: Int?,
        /** Media milliseconds buffered ahead of the playback position. */
        public val bufferedDurationMs: Long,
        /** Whether the position was advancing at the sample instant. */
        public val playing: Boolean,
    ) : TelemetryEvent()

    /**
     * One licence acquisition finished, and this is how long it took and how it ended.
     *
     * Protected playback's own start-up cost. A DRM session cannot deliver a frame until it holds
     * keys, so a protected session that is slow to start is often slow *here* — and with no event of
     * its own that interval is indistinguishable, in the rest of this vocabulary, from a slow
     * manifest or a slow first segment.
     *
     * SuperPlayer's own metric: CTA-2066 has no licence-acquisition measurement, which
     * `docs/telemetry-schema.md` states rather than implying a citation the standard does not carry.
     *
     * One event per acquisition rather than a started/ended pair, and a session can carry several —
     * content declaring two licence policies opens two sessions (ADR-0012 rule 1's addendum), and a
     * key rotation renews inside the one it has. A session reused for content whose initialization
     * data matched acquires nothing and reports nothing, which is the point of counting acquisitions
     * rather than sessions.
     */
    public data class LicenceAcquisitionEnded(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /**
         * Milliseconds from the moment a DRM session without keys was opened to the moment it held
         * them or was refused, on the monotonic clock.
         */
        public val durationMs: Long,
        /** Where the keys came from, or that they never arrived — see [LicenceOutcome]. */
        public val outcome: LicenceOutcome,
        /**
         * The `securityLevel` in force when this acquisition ended — `"L1"`, `"L3"` — or null where
         * no level was negotiated, which is every acquisition on a device that can honour the level
         * it reports.
         *
         * Read from [SuperPlayer.deliveredSecurityLevel] at the moment the acquisition ended, rather
         * than once per session: ADR-0012 rule 11's ladder settles a level after the first refusal,
         * so the level a session's *first* acquisition ran at and the level a later one ran at need
         * not be the same number. The same field on [SessionEnded] is the level the session finished
         * on; this one is the level this licence was fetched under.
         */
        public val securityLevel: String? = null,
    ) : TelemetryEvent()

    /**
     * Video frames the renderer dropped or repeated over an interval of playing time.
     *
     * **These are video frames, not UI frames**, and the two are close to independent pipelines.
     * `docs/telemetry-schema.md` has a section on why that distinction is the one this metric is
     * most often read wrong through; the short form is that this number says nothing about whether
     * the app's own drawing was smooth, and a library reporting that would be measuring its
     * consumer's code.
     *
     * Carried as a count plus the interval it accumulated over, so the rate is derivable and the
     * count is the count — see the document for the denominator and why it is that one.
     */
    public data class VideoFramesDropped(
        override val sessionId: String,
        override val contentId: String,
        override val timestampMs: Long,
        override val monotonicTimeMs: Long,
        /** Video frames the renderer discarded without presenting them, over this interval. */
        public val droppedFrames: Int,
        /** Video frames presented twice because no new one was ready, over this interval. */
        public val repeatedFrames: Int,
        /** The playing time this interval covers, in milliseconds on the monotonic clock. */
        public val elapsedPlayingMs: Long,
    ) : TelemetryEvent()

    public companion object {
        /**
         * The current meaning-version of this vocabulary. See the class KDoc for what moves it, and
         * `docs/telemetry-schema.md` for the list of changes that do and do not.
         */
        public const val SCHEMA_VERSION: Int = 2
    }
}

/**
 * Where [TelemetryEvent.FirstFrameRendered] started counting from.
 *
 * The boundary is the whole argument of the metric. `PRD.md` §3.4 puts it at *user intent* rather
 * than at `prepare()`, because the seconds a viewer experiences begin at their tap and a library
 * that starts its stopwatch when it is handed a URL has excluded whatever the app spent getting
 * there — a catalogue call, a licence check, a navigation transition. That interval is real, and it
 * is exactly the interval an app most wants to see.
 *
 * The library cannot observe a tap, so intent is declared: [SuperPlayer.declarePlaybackIntent].
 */
public enum class TtffStartBoundary {

    /**
     * Measured from [SuperPlayer.declarePlaybackIntent] — the boundary CTA-2066 and CMCD v2's `msd`
     * both describe, and the one to compare against another app's number.
     */
    USER_INTENT,

    /**
     * Measured from the moment the player took the content on — `setMediaRequest`, or a session
     * controller's resolved content — because no intent was declared for this session.
     *
     * A real measurement of a narrower interval, and never mixed with [USER_INTENT] silently: it
     * omits whatever the app did between the tap and the request, so it reads lower.
     */
    CONTENT_ADOPTED,
}

/** Which way an ABR switch went, as [TelemetryEvent.TrackSwitched] reports it. */
public enum class TrackSwitchDirection {

    /** The first rendition of the session; there is nothing to have switched from. */
    INITIAL,

    /** To a higher bitrate than the one playing. */
    UP,

    /** To a lower bitrate than the one playing. */
    DOWN,
}

/**
 * How the acquisition [TelemetryEvent.LicenceAcquisitionEnded] measured ended.
 *
 * Three outcomes and not two, because *where the keys came from* is the question a protected
 * catalogue is read for: an offline licence that is being honoured costs no round trip, and a
 * deployment that believes its downloads play offline and is in fact re-acquiring every time looks
 * identical in every other metric here.
 */
public enum class LicenceOutcome {

    /** Keys arrived from the licence server, over the network, in [durationMs][TelemetryEvent.LicenceAcquisitionEnded.durationMs]. */
    ACQUIRED_FROM_SERVER,

    /**
     * Keys were restored from a licence already stored on the device — no licence request was made.
     *
     * Reachable since #210, which opened `superplayer-drm`'s offline licence store; #212 declared it
     * before there was anything to emit it, so that #210 was a behaviour change against a vocabulary
     * a pipeline had already been told about rather than a second change of this schema for one
     * metric. `SCHEMA_VERSION` moved for neither half: no definition changed, and a population a
     * pipeline was told to expect arrived. `docs/telemetry-schema.md` records both.
     *
     * It is the value a protected catalogue is read for. A deployment that believes its downloads
     * play offline and is in fact re-acquiring a licence every time is identical in every other
     * metric here and different in exactly this one.
     */
    SERVED_FROM_OFFLINE_STORE,

    /**
     * No keys: the licence server refused, the request never arrived, or the device's protection
     * stack failed the session.
     *
     * *Which* of those is not this field — a refusal that ends playback is reported as a failure
     * with `PlaybackFailure.classification` naming the `FailureClass.Drm` leaf, and a second
     * taxonomy here would be the drift ADR-0011 rule 1 exists to prevent. What this value adds is
     * that a licence was asked for at all, and how long the attempt cost, which a failure event does
     * not say.
     */
    REFUSED,
}

/**
 * What failed, in SuperPlayer's own vocabulary.
 *
 * Three fields and three questions, and they stay three: which bucket a failure rate is grouped by,
 * which failure it was in the engine's own words, and what a human reading a log sees.
 *
 * [classification] is the fourth and is the one answer that is not core's own: it is the name
 * `superplayer-resilience`'s `ErrorClassifier` gave the failure, carried rather than derived
 * (ADR-0011 rule 3). A player built without that module has nobody to ask and reports null, which is
 * every player before Phase 5 and the reason the field is nullable rather than defaulted to a word
 * that would be a guess.
 */
public data class PlaybackFailure(
    /**
     * The bucket a failure rate is grouped by.
     *
     * Six values and no more, which ADR-0011 rule 3 fixes: a dashboard wants a handful of slices, and
     * the taxonomy fine enough to act on automatically is [classification]'s. Where a classifier is
     * attached this is *derived from the class* by the one-to-one table that classifier owns, and
     * where none is it is derived from Media3's error-code band as it always was — so the same
     * failure can land in a different bucket on a player with resilience than on one without, and the
     * class is the one that is right. `docs/telemetry-schema.md` carries that as a release note
     * against `TelemetryEvent.SCHEMA_VERSION` 2.
     */
    public val category: FailureCategory,
    /**
     * A stable identifier for the specific failure *the engine* reported, or null when it offered
     * none — `errorCodeName`, unchanged by Phase 5.
     *
     * A string rather than an integer because it must survive an engine that renumbers, and because
     * it is a grouping key in a pipeline rather than something to branch on. It stays the engine's
     * word rather than becoming the classifier's: the two are different facts, one says which code
     * Media3 raised and the other what SuperPlayer made of it, and a pipeline that has to find a
     * failure in a logcat needs the first.
     */
    public val code: String?,
    /** Whatever the engine said, for a human reading a log. Never parsed, never a grouping key. */
    public val message: String?,
    /**
     * What `superplayer-resilience` classified the failure as — its `FailureClass.stableName`, the
     * same string the typed error a consumer was handed carries — or null on a player built without
     * that module (ADR-0011 rules 3 and 14).
     *
     * This is the field to group and alert on: `"Transient.CdnEdge"` is a conversation with a CDN and
     * `"Device.DecoderTransient"` is a conversation with a device fleet, where [code] would report
     * both of those as whatever code the engine happened to assign. Null means *unclassified*, and it
     * means it in the honest way: nothing was there to classify it.
     *
     * A string rather than an enum for [code]'s reason and one more: the taxonomy belongs to a module
     * core does not depend on (ADR-0011 rule 1), and a copy of it here would be the second answer to
     * one question that rule exists to prevent.
     */
    public val classification: String? = null,
)

/** The buckets [PlaybackFailure] sorts a failure into. */
public enum class FailureCategory {

    /** The bytes did not arrive: connectivity, timeout, an HTTP status, a DNS failure. */
    NETWORK,

    /** The bytes arrived and were not playable: a malformed manifest, an unparsable container. */
    SOURCE,

    /** No decoder could be initialised, or one failed while decoding. */
    DECODER,

    /** Licence acquisition, provisioning, or key rotation failed. */
    DRM,

    /** The renderer or the output surface failed with playable content and a working decoder. */
    RENDERER,

    /** Everything else, including a failure the engine reported without a usable cause. */
    UNKNOWN,
}
