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

/// Runs a ``FrameSource`` against the obligations ADR-0018 puts on one, and says which it broke —
/// the Kotlin `FrameSourceConformance` (#347), ported check for check (`docs/ios-plan.md` §3.10).
///
/// ```swift
/// func testOurTransportSatisfiesSuperPlayersContract() throws {
///     try FrameSourceConformance(
///         sources: MoqTransport(relay: relay),
///         url: URL(string: "moq://relay.example/studio-a")!
///     ).verifyAll()
/// }
/// ```
///
/// ## Why this is a test you run rather than a paragraph you read
///
/// ``FrameSource``'s obligations are on code this library cannot see, and **every one of them fails
/// silently** — that sentence is in the seam's own documentation and it is the whole argument for
/// this type. A transport that hands over its parameter sets in the wrong shape does not throw: it
/// produces a display layer that accepts every buffer and shows nothing. One that frames its samples
/// as Annex-B under a fourcc that promised length prefixes produces a structurally plausible stream
/// of noise, because a start code read as a length declares a one-byte unit and the picture data
/// behind it becomes the next "length". And neither QUIC nor WebRTC runs under `ios/check`, so for a
/// transport over either this suite is not one check among several. It is the only one there is.
///
/// ## What you supply, and what this supplies
///
/// **You supply the transport and the stream.** A ``FrameSource``'s obligations are claims about what
/// it delivers **to a sink**, so what this supplies is the sink: a recorder that subscribes, keeps
/// everything that arrives in the order it arrived, and then cancels. **There is no socket here** —
/// unlike every other test helper in this product, which serves the loopback origin ADR-0021 rule 2
/// widens `docs/testing.md`'s carve-out for, this one has no wire to stand on either end of.
///
/// Point it at a stream your transport can really play, live or recorded, publishing the tracks you
/// ship. A few frames of each track is enough — nothing below waits for more than sixteen. A URL
/// that never reaches ``FrameSink/onTracks(_:)`` is refused under obligation 2, because from here a
/// transport that declared nothing and a stream that is not there are the same thing; a URL that
/// declares its tracks and then delivers **no frames** passes every check vacuously, since each one
/// reads the frames that arrived.
///
/// ## What a failure is
///
/// A ``FrameSourceConformanceError``, thrown — a plain `Error`, so **no test framework is named**:
/// `SuperPlayerTestkit` links no XCTest, and a consumer on XCTest, Swift Testing or a runner of their
/// own runs the same checks. The Kotlin suite makes the same choice by throwing an `AssertionError`
/// rather than calling JUnit; a thrown `Error` is this platform's equivalent, and every framework
/// renders it as the test's failure. Each message names the obligation, says **what this source
/// did** and says **what the rule requires**, in that order, because its reader is not the author of
/// this code and has no copy of this repository.
///
/// ## The checks, and how their count maps
///
/// Eleven, as on Android, and the same eleven: one each for obligations 1, 3, 4, 8 and 9, two for
/// obligation 2 and two for obligation 7, one for the codec string ``RealtimeTrack/codec`` requires
/// and one for the finality of a terminal callback. The seam has **ten** obligations where the
/// Kotlin suite's documentation counted nine: the tenth, the coded size (#353), is unenforceable for
/// obligation 5's reason — no suite can know a stream's true resolution — so it adds a sentence to
/// the list below and no check. ``Check`` lists them, in the order ``verifyAll()`` runs them.
///
/// ## What this cannot check, said plainly
///
/// - **Obligations 5 and 10** — the precision a transport states, and a video track's coded size —
///   are unenforceable by construction: nothing here can tell a transport that truncated to
///   milliseconds from a publisher that emitted them, or a size that is wrong from one that is right.
/// - **Obligation 6** is reached only as far as the framing walk goes. A fragmented-MP4 sample
///   handed over whole is usually caught by ``Check/samplesAreFramedAsTheFourccRequires``, because
///   its box headers do not walk as NAL units, but a container this suite has never seen may not be.
/// - **A configuration record that parses and belongs to a different stream** is invisible.
/// - **Obligation 8 is read only where memory is visible.** A `bytesNoCopy` payload of fourteen bytes
///   or fewer is copied into the `Data` itself, so it shares nothing to find; and two payloads are
///   compared by where each *begins*, so a ring buffer handing out frames at different offsets of
///   one allocation is caught only by the second reading, a payload written after delivery.
/// - **A track declared and never delivered**, and the **shared epoch** across tracks obligation 4
///   asks for wherever a publisher has one. `RealtimePlayer` bounds both at playback, in media time
///   against the leading track, ending the stream on ``RealtimeTrackStalledError``; bounding them
///   here would be deciding how long a publisher may take to start.
/// - **A throw out of `subscribe`**, which the Kotlin suite reports under a rule of its own, cannot
///   happen here: ``FrameSource/subscribe(_:)`` does not throw, so a transport's failure has
///   ``FrameSink/onError(_:)`` as its only route and the type checker has already enforced it.
///
/// And one limit of the **sink** rather than of the seam: obligation 1 is checked by widening the
/// window and watching for two deliveries in flight at once, so a transport with two genuinely
/// concurrent deliverers is caught, while one that delivers from several threads with a
/// happens-before between them passes — which is what the obligation permits.
///
/// ## Threads
///
/// Every check blocks its caller for the bounds below — a second or so for most, two for
/// cancellation. ``FrameSource/subscribe(_:)`` is called on a thread of the suite's own, because a
/// transport is entitled to deliver its whole script inside it, and ``FrameSource/cancel()`` on the
/// caller's. A transport that needs the main thread to deliver must not be verified *from* it. The
/// subscribing thread is waited for only as long as the settle, never joined: a `subscribe` that
/// never returns leaves its thread behind, delivering into a sink nobody reads, once per check.
public struct FrameSourceConformance: Sendable {

    /// One check, named as the Kotlin suite names its method without the `verify` prefix.
    ///
    /// An enumeration rather than a method per check, because Swift has no reflection over methods:
    /// the Kotlin register reads the checks off the class, so a check added there is one the register
    /// is missing, and here ``verify(_:)``'s exhaustive `switch` and `CaseIterable` do that work — a
    /// check added is a case, a case is a compile error until the switch runs it, and the register
    /// test compares itself to `allCases`. `FaultSweepTests` holds `FaultScript.Kind` the same way.
    public enum Check: String, CaseIterable, Sendable {
        /// **Obligation 1** — every call on the sink is ordered against every other.
        case deliveriesDoNotOverlap
        /// **Obligation 2** — ``FrameSink/onTracks(_:)`` is called once, before the first frame,
        /// and is never empty.
        case tracksAreDeclaredOnceBeforeTheFirstFrame
        /// **Obligation 2** — a frame's ``EncodedFrame/trackIndex`` names a declared track.
        case everyFrameNamesADeclaredTrack
        /// **Obligation 3** — the first frame of each track is a keyframe.
        case eachTrackStartsWithAKeyframe
        /// **Obligation 4** — timestamps never decrease within a track.
        case timestampsAreMonotonicPerTrack
        /// **The codec string** — every declared codec is one SuperPlayer maps.
        case codecStringsAreMapped
        /// **Obligation 7, first half** — the codec configuration follows the fourcc.
        case codecConfigurationFollowsTheFourcc
        /// **Obligation 7, second half** — the samples are framed as the same fourcc requires.
        case samplesAreFramedAsTheFourccRequires
        /// **Obligation 8** — a payload is handed over once and its memory is not touched again.
        case payloadsAreNeitherReusedNorTouchedAgain
        /// **The finality of ``FrameSink/onEnded()`` and ``FrameSink/onError(_:)``.**
        case terminationIsFinal
        /// **Obligation 9** — ``FrameSource/cancel()`` stops delivery before it returns.
        case cancellationStopsDelivery
    }

    private let sources: any FrameSourceFactory
    private let url: URL

    /// A suite over `sources`, which opens one source per check exactly as a player opens one per
    /// playback, subscribing to `url`, whose scheme is the one the transport answers.
    public init(sources: any FrameSourceFactory, url: URL) {
        self.sources = sources
        self.url = url
    }

    /// Every check, in ``Check``'s order, stopping at the first one broken.
    ///
    /// Stopping rather than collecting, for the Kotlin suite's reason: a transport that fails one has
    /// a defect to fix before the rest of the report means anything, and several of these
    /// obligations describe the same delivery from different angles — a source that declares its
    /// tracks late usually also looks like one whose frames name no declared track. Each check
    /// subscribes afresh: ``FrameSourceFactory/open(_:)`` is called once per check and the source it
    /// answers is cancelled before the next, which is the lifecycle a player gives one.
    public func verifyAll() throws(FrameSourceConformanceError) {
        for check in Check.allCases { try verify(check) }
    }

    /// One check, on a subscription of its own.
    public func verify(_ check: Check) throws(FrameSourceConformanceError) {
        switch check {
        case .deliveriesDoNotOverlap: try verifyDeliveriesDoNotOverlap()
        case .tracksAreDeclaredOnceBeforeTheFirstFrame:
            try verifyTracksAreDeclaredOnceBeforeTheFirstFrame()
        case .everyFrameNamesADeclaredTrack: try verifyEveryFrameNamesADeclaredTrack()
        case .eachTrackStartsWithAKeyframe: try verifyEachTrackStartsWithAKeyframe()
        case .timestampsAreMonotonicPerTrack: try verifyTimestampsAreMonotonicPerTrack()
        case .codecStringsAreMapped: try verifyCodecStringsAreMapped()
        case .codecConfigurationFollowsTheFourcc: try verifyCodecConfigurationFollowsTheFourcc()
        case .samplesAreFramedAsTheFourccRequires: try verifySamplesAreFramedAsTheFourccRequires()
        case .payloadsAreNeitherReusedNorTouchedAgain:
            try verifyPayloadsAreNeitherReusedNorTouchedAgain()
        case .terminationIsFinal: try verifyTerminationIsFinal()
        case .cancellationStopsDelivery: try verifyCancellationStopsDelivery()
        }
    }

    // MARK: - The checks

    /// **Obligation 1.** The sink lingers ``Bounds/overlapProbeLinger`` inside each callback, which
    /// is a fair imitation of the real one rather than an unfair burden — `RealtimeEngine` wraps and
    /// enqueues each frame under one lock and takes time doing it — and two deliveries in flight
    /// during that window are two concurrent writers.
    /// ref: ADR-0018 rule 2 — the seam is subscribe, receive, cancel, and the receiving side
    /// enqueues in the order it is called.
    private func verifyDeliveriesDoNotOverlap() throws(FrameSourceConformanceError) {
        let observed = observe(linger: Bounds.overlapProbeLinger)
        if observed.overlapped {
            throw refuse(
                Rule.obligation1,
                did: "had two deliveries in flight on the sink at the same moment; "
                    + "\(observed.deliveringThreads) different threads delivered during this "
                    + "subscription",
                requires: "one delivery at a time. SuperPlayer enqueues what arrives in the order "
                    + "it is called, so two concurrent deliveries reorder frames, and a display "
                    + "layer handed a dependent frame before its reference decodes corruption "
                    + "whose cause names nothing of yours. Delivering from several threads is "
                    + "allowed, but only with a happens-before between them")
        }
    }

    /// **Obligation 2.**
    /// ref: ADR-0018 rule 2's #346 addendum — a player is prepared once, with the whole set of
    /// tracks, so a second declaration has nothing it could mean and a track declared later has no
    /// index it could have been sent under.
    private func verifyTracksAreDeclaredOnceBeforeTheFirstFrame()
        throws(FrameSourceConformanceError)
    {
        let observed = observe()
        if observed.trackDeclarations == 0 {
            throw refuse(
                Rule.obligation2,
                did: "delivered \(observed.frames.count) frame(s) and never called onTracks, "
                    + "within \(Bounds.milliseconds(Bounds.tracks)) ms of being subscribed",
                requires: "onTracks called before the first frame. Until it arrives no format "
                    + "description exists, so nothing is enqueued and the player waits for ever, "
                    + "with no error to report and nothing in a bug report to read")
        }
        if observed.framesBeforeTracks > 0 {
            throw refuse(
                Rule.obligation2,
                did: "delivered \(observed.framesBeforeTracks) frame(s) before it called onTracks",
                requires: "onTracks first. A frame names its track by position in that list, so "
                    + "a frame that arrives before the list has no track it could belong to")
        }
        if observed.trackDeclarations > 1 {
            throw refuse(
                Rule.obligation2,
                did: "called onTracks \(observed.trackDeclarations) times",
                requires: "exactly one declaration per subscription. The player describes every "
                    + "track once, from the one list, so a second call would arrive after it had "
                    + "already decided what it was playing and there would be nowhere to put what "
                    + "it carried. A publisher that may add a track later subscribes again")
        }
        if observed.tracks.isEmpty {
            throw refuse(
                Rule.obligation2,
                did: "declared an empty track list",
                requires: "at least one track. An empty list declares nothing to play and is "
                    + "refused; audio alone is a legitimate subscription and is one element of "
                    + "the same list")
        }
    }

    /// **Obligation 2**, the half a single-track transport passes by default and a two-track one has
    /// to get right: the index is a position in the declared list, not an identifier of the
    /// transport's own.
    /// ref: ADR-0018 rule 2's #346 addendum — a frame naming a track nobody declared is reported
    /// rather than dropped.
    private func verifyEveryFrameNamesADeclaredTrack() throws(FrameSourceConformanceError) {
        let observed = observe()
        let declared = try observed.tracksOrRefuse(Rule.obligation2)
        guard
            let stray = observed.frames.first(where: { !declared.indices.contains($0.trackIndex) })
        else { return }
        // An empty declaration is refused by its own check rather than here, so this renders the
        // range only where there is one to render.
        let range =
            declared.isEmpty
            ? " and so has no index a frame could name" : " — indices 0..\(declared.count - 1)"
        throw refuse(
            Rule.obligation2,
            did: "delivered a frame at \(stray.timestampUs) us naming trackIndex "
                + "\(stray.trackIndex), where onTracks declared \(declared.count) track(s)\(range)",
            requires: "a trackIndex that is a position in the list onTracks declared. It is an "
                + "index into that list and not an identifier of your own; a single-track "
                + "subscription leaves it at its default of 0")
    }

    /// **Obligation 3.**
    /// spec: ITU-T H.264 §7.4.1.2.4 and ITU-T H.265 §8.1 — a decoder starts at a random access
    /// point and what precedes one has references it does not hold.
    private func verifyEachTrackStartsWithAKeyframe() throws(FrameSourceConformanceError) {
        let observed = observe()
        let declared = try observed.tracksOrRefuse(Rule.obligation3)
        var started = Set<Int>()
        for frame in observed.frames where started.insert(frame.trackIndex).inserted {
            guard !frame.keyFrame else { continue }
            throw refuse(
                Rule.obligation3,
                did: "delivered a frame with keyFrame = false first on track \(frame.trackIndex) "
                    + "(\(declared.codec(at: frame.trackIndex))), at \(frame.timestampUs) us",
                requires: "a keyframe first on every track. A decoder fed a dependent frame with "
                    + "no reference renders either nothing or visible corruption, which is the "
                    + "classic \"it connects and the picture is garbage\" report. A transport "
                    + "that joins a stream mid-GOP drops frames until the first keyframe rather "
                    + "than forwarding them")
        }
    }

    /// **Obligation 4**, per track and not across tracks: the seam promises monotonicity within a
    /// track and nothing about two tracks sharing an origin, which `RealtimeEngine` reconciles rather
    /// than assumes.
    /// spec: RFC 3550 §5.1 — the initial RTP timestamp is random and per synchronization source.
    /// ref: ADR-0018 rule 9 and its #346 addendum.
    private func verifyTimestampsAreMonotonicPerTrack() throws(FrameSourceConformanceError) {
        let observed = observe()
        let declared = try observed.tracksOrRefuse(Rule.obligation4)
        var latest: [Int: Int64] = [:]
        for frame in observed.frames {
            if let previous = latest[frame.trackIndex], frame.timestampUs < previous {
                throw refuse(
                    Rule.obligation4,
                    did: "delivered a frame at \(frame.timestampUs) us on track "
                        + "\(frame.trackIndex) (\(declared.codec(at: frame.trackIndex))) after one "
                        + "at \(previous) us on the same track — a step of "
                        + "\(previous - frame.timestampUs) us backwards",
                    requires: "timestamps that never decrease within a track. SuperPlayer "
                        + "subtracts the first frame delivered on any track and treats the rest as "
                        + "offsets from it, so the epoch is yours — but a frame timed before the "
                        + "synchronizer's clock is late on arrival and dropped silently, and a "
                        + "backwards step strands the clock ahead of every frame that follows. Two "
                        + "tracks need not share an epoch; each must keep its own in order")
            }
            latest[frame.trackIndex] = frame.timestampUs
        }
    }

    /// **The codec string.** The families are listed here rather than read from the table that maps
    /// them, for a module boundary: `RealtimeFormats` is `SuperPlayerRealtime`'s and internal, while
    /// this product is phase 2 and may name nothing later than core. What is duplicated is a list of
    /// names and not a parser; `ScriptedFrameSourceConformanceTests` holds the two to each other.
    /// spec: RFC 6381 §3.3 — the `codecs` parameter's fourcc-prefixed grammar; an `mp4a` string's
    /// second element is an MP4 Registration Authority object type, which is why `mp4a.69` is MP3.
    /// spec: RFC 4566 §6 and RFC 4855 §3 — SDP's `a=rtpmap` encoding names, matched
    /// case-insensitively.
    private func verifyCodecStringsAreMapped() throws(FrameSourceConformanceError) {
        let observed = observe()
        let declared = try observed.tracksOrRefuse(Rule.codec)
        for (index, track) in declared.enumerated() where !Codecs.isMapped(track.codec) {
            throw refuse(
                Rule.codec,
                did: "declared track \(index) as \"\(track.codec)\", which is neither an RFC 6381 "
                    + "codecs string nor an SDP encoding name for a codec SuperPlayer maps",
                requires: "one of H.264 (avc1, avc3, H264), H.265 (hvc1, hev1, H265), VP9 (vp09, "
                    + "VP9), AV1 (av01, AV1), AAC (mp4a.40.*, mp4a.67.*, MPEG4-GENERIC) or Opus "
                    + "(opus). SuperPlayer refuses an unrecognised string rather than guessing, "
                    + "because a codec decoded as the wrong one fails silently. Prefer the RFC "
                    + "6381 string where you have one: it states a profile and a level")
            // The Kotlin message goes on to say a profile is what lets a rendition this device cannot
            // decode be refused; `RealtimePlayer` selects nothing (ADR-0018 rule 7), so here it
            // stops at what the string states.
        }
    }

    /// **Obligation 7, first half.** Every codec but the four named here has its record passed
    /// through unchanged, so nothing is required of it.
    /// spec: ISO/IEC 14496-15 §5.3.3.1 — the `avc1` sample entry carries an
    /// `AVCDecoderConfigurationRecord` while `avc3` carries its parameter sets in the samples;
    /// §8.3.3.1 says the same of `hvc1` and `hev1`.
    /// ref: ADR-0018 rule 4 and its #345 addendum.
    private func verifyCodecConfigurationFollowsTheFourcc() throws(FrameSourceConformanceError) {
        let observed = observe()
        let declared = try observed.tracksOrRefuse(Rule.obligation7)
        for (index, track) in declared.enumerated() {
            let fourcc = Codecs.fourcc(of: track.codec)
            switch track.codecConfiguration {
            case .inBand where Codecs.recordFourccs.contains(fourcc):
                throw refuse(
                    Rule.obligation7,
                    did: "declared track \(index) as \"\(track.codec)\" and handed over "
                        + "CodecConfiguration.inBand",
                    requires: "a CodecConfiguration.record for a `\(fourcc)` track, which carries "
                        + "its parameter sets out of band. A decoder configured from none, with "
                        + "none coming in the bitstream, renders nothing and reports nothing")
            case .record(let bytes) where Codecs.inBandFourccs.contains(fourcc):
                throw refuse(
                    Rule.obligation7,
                    did: "declared track \(index) as \"\(track.codec)\" and handed over a "
                        + "CodecConfiguration.record of \(bytes.count) bytes",
                    requires: "CodecConfiguration.inBand for a `\(fourcc)` track, which is "
                        + "self-describing: its parameter sets arrive in the bitstream before "
                        + "every keyframe. A decoder configured from a record whose parameter sets "
                        + "were about to arrive again in band is the silent failure ADR-0018 rule "
                        + "4 exists to prevent")
            case .record(let bytes):
                try verifyRecordIsARecord(index: index, track: track, bytes: bytes)
            case .inBand:
                break
            }
        }
    }

    /// **Obligation 7, second half** — the half nothing but a suite like this can check, and worse to
    /// get wrong than the first, because both directions read as valid bytes. Every frame of every
    /// H.264 or H.265 track is walked; VP9, AV1, AAC and Opus samples have no NAL framing to check.
    /// spec: ISO/IEC 14496-15 §5.3.3.1.2 — a sample of a track whose sample entry carries an
    /// `avcC` is a run of NAL units each prefixed by its length, `lengthSizeMinusOne + 1` bytes wide;
    /// §8.3.3.1.2 says the same of `hvcC`, whose `lengthSizeMinusOne` sits at a different offset.
    /// spec: ITU-T H.264 Annex B — the start code prefix `00 00 01`, optionally preceded by a
    /// further zero byte.
    private func verifySamplesAreFramedAsTheFourccRequires() throws(FrameSourceConformanceError) {
        let observed = observe()
        let declared = try observed.tracksOrRefuse(Rule.obligation7)
        // The length field width is a fact about the *track*, read once from its record: a refusal
        // from it names the track rather than whichever frame happened to be walked first.
        var lengthSizes: [Int?] = []
        for track in declared {
            let fourcc = Codecs.fourcc(of: track.codec)
            if case .record(let bytes) = track.codecConfiguration,
                Codecs.recordFourccs.contains(fourcc)
            {
                lengthSizes.append(try nalLengthSize(fourcc: fourcc, record: bytes, track: track))
            } else {
                lengthSizes.append(nil)
            }
        }
        for frame in observed.frames where declared.indices.contains(frame.trackIndex) {
            let track = declared[frame.trackIndex]
            if let lengthSize = lengthSizes[frame.trackIndex] {
                try verifyLengthPrefixed(frame, track: track, lengthSize: lengthSize)
            } else if track.codecConfiguration == .inBand,
                Codecs.inBandFourccs.contains(Codecs.fourcc(of: track.codec))
            {
                try verifyAnnexB(frame, track: track)
            }
        }
    }

    /// **Obligation 8**, in this platform's terms. `Data` is a value, so the Kotlin obligation's
    /// ordinary failure — a pooled array refilled after it was handed over — cannot happen through
    /// the type: what is left is a `Data` made with `bytesNoCopy` over memory the transport goes on
    /// writing, which shares that memory where a value would have copied it.
    ///
    /// So both halves are read off memory rather than off identity. **Two frames over the same
    /// memory whose bytes differed when each arrived** is a buffer rewritten between deliveries —
    /// a value could not have changed, so sharing alone (one `Data` sent twice) is refused by
    /// nothing. **A payload whose bytes differ from the copy taken when it arrived** is memory
    /// written after ``FrameSink/onFrame(_:)`` returned. Both are read before the source is
    /// cancelled, because a transport is entitled to free its buffers once it has been.
    private func verifyPayloadsAreNeitherReusedNorTouchedAgain() throws(FrameSourceConformanceError)
    {
        let observed = observe()
        if let (earlier, later) = observed.sharedMemory {
            throw refuse(
                Rule.obligation8,
                did: "handed over the same \(later.asDelivered.count)-byte memory for the frame at "
                    + "\(earlier.timestampUs) us and the one at \(later.timestampUs) us, rewritten "
                    + "in between",
                requires: "a payload per frame. A Data made with bytesNoCopy over a buffer you "
                    + "recycle shares that buffer with every frame already handed over, so "
                    + "refilling it for the next frame tears one already queued — a corruption "
                    + "that moves when timing moves, and the least reproducible failure in this "
                    + "list")
        }
        if let touched = observed.touchedAfterDelivery {
            throw refuse(
                Rule.obligation8,
                did: "changed the \(touched.asDelivered.count)-byte payload of the frame at "
                    + "\(touched.timestampUs) us after onFrame had returned",
                requires: "a payload's memory left alone once it has been handed over. A Data made "
                    + "with bytesNoCopy is that memory itself, so writing into it afterwards "
                    + "rewrites a frame the player has already accepted")
        }
    }

    /// **The finality of a terminal callback.** A source that has done neither is deliberately not
    /// refused: a realtime stream has no end to reach (ADR-0018 rule 5), and a publisher going quiet
    /// is an ordinary live edge rather than a failure.
    private func verifyTerminationIsFinal() throws(FrameSourceConformanceError) {
        let observed = observe()
        guard let terminal = observed.terminal else { return }
        if observed.terminalCallbacks > 1 {
            throw refuse(
                Rule.termination,
                did: "called \(terminal) after it had already ended the subscription "
                    + "(\(observed.terminalCallbacks) terminal callbacks in all)",
                requires: "one terminal callback. onEnded and onError each say the subscription is "
                    + "over, and the difference between them is what a viewer is shown — a stream "
                    + "that ended of its own accord shows what is enqueued and stops, while one "
                    + "that failed reports the cause. A second call has nothing left to say")
        }
        if observed.framesAfterTerminal > 0 {
            throw refuse(
                Rule.termination,
                did: "delivered \(observed.framesAfterTerminal) frame(s) after it had called "
                    + terminal,
                requires: "no delivery after the subscription has ended. A frame handed over "
                    + "afterwards reaches a stream that has already stopped and is lost without a "
                    + "word")
        }
    }

    /// **Obligation 9.** The bound is about telling *stopped* from *not stopped at all* rather than
    /// about being fast: a transport that hands its shutdown to another thread and returns is the
    /// ordinary way this is broken, which is why the check watches after `cancel` has returned.
    private func verifyCancellationStopsDelivery() throws(FrameSourceConformanceError) {
        let observed = observe(watchAfterCancel: true)
        if observed.callbacksAfterCancel > 0 {
            throw refuse(
                Rule.obligation9,
                did: "made \(observed.callbacksAfterCancel) further callback(s) in the "
                    + "\(Bounds.milliseconds(Bounds.cancellation)) ms after cancel() returned",
                requires: "a cancel() that returns only once no further callback can be made. The "
                    + "player drops a late callback rather than enqueuing it, so what a late one "
                    + "costs is yours: work done for a sink nobody reads, on a thread that outlived "
                    + "its player. A transport that shuts down on a thread of its own joins that "
                    + "thread before returning")
        }
    }

    // MARK: - Obligation 7's readings

    /// The record a track declared, checked as far as being a record of that codec at all.
    private func verifyRecordIsARecord(index: Int, track: RealtimeTrack, bytes: Data)
        throws(FrameSourceConformanceError)
    {
        if Codecs.startCodeLength(bytes) > 0 {
            throw refuse(
                Rule.obligation7,
                did: "handed over a CodecConfiguration.record for track \(index) "
                    + "(\"\(track.codec)\") whose first bytes are an Annex-B start code",
                requires: "the configuration record exactly as your publisher sent it — an "
                    + "AVCDecoderConfigurationRecord for `avc1`, an HEVCDecoderConfigurationRecord "
                    + "for `hvc1` — and not the parameter sets unwrapped out of it. SuperPlayer "
                    + "hands the record to CoreMedia as the format description's atom and reads "
                    + "the NAL length width out of it; a transport that converts first is one that "
                    + "has to know what a decoder wants")
        }
        // spec: ISO/IEC 14496-15 §5.3.3.1.2 — `configurationVersion` is the record's first byte and
        // is 1. Checked for `avc1` alone, as the Kotlin suite does: every codec's record but the two
        // NAL ones passes through untouched, and the HEVC record's own version is read by the
        // length-width walk's bound rather than here.
        let fourcc = Codecs.fourcc(of: track.codec)
        if fourcc == "avc1", bytes.count < Codecs.avccMinimumBytes || bytes.first != 1 {
            throw refuse(
                Rule.obligation7,
                did: "handed over \(bytes.count) bytes as track \(index)'s "
                    + "AVCDecoderConfigurationRecord, beginning "
                    + Codecs.hex(bytes.prefix(Codecs.avccMinimumBytes)),
                requires: "an AVCDecoderConfigurationRecord: at least \(Codecs.avccMinimumBytes) "
                    + "bytes, the first of them the configurationVersion 1. SuperPlayer reads the "
                    + "NAL length width and the parameter sets out of it, and refuses one that "
                    + "does not parse rather than describing a track from half of it")
        }
    }

    /// The NAL length field width this track's record declares, which its samples are framed with.
    /// spec: ISO/IEC 14496-15 §5.3.3.1.2 — `lengthSizeMinusOne` is the low two bits of an `avcC`'s
    /// fifth byte; §8.3.3.1.2 puts it in the low two bits of an `hvcC`'s twenty-second.
    private func nalLengthSize(fourcc: String, record: Data, track: RealtimeTrack)
        throws(FrameSourceConformanceError) -> Int
    {
        let offset = fourcc == "hvc1" ? Codecs.hvccLengthSizeOffset : Codecs.avccLengthSizeOffset
        let bytes = [UInt8](record)
        guard bytes.count > offset else {
            throw refuse(
                Rule.obligation7,
                did: "handed over a \(bytes.count)-byte configuration record for "
                    + "\"\(track.codec)\", which is too short to carry the NAL length field size "
                    + "at byte \(offset)",
                requires: "the whole configuration record. Its length field size is what every "
                    + "sample of this track is framed with, so a record that stops before it "
                    + "leaves nothing able to read a frame")
        }
        return Int(bytes[offset] & 0x03) + 1
    }

    /// One length-prefixed sample, walked unit by unit until it ends exactly.
    private func verifyLengthPrefixed(_ frame: ReceivedFrame, track: RealtimeTrack, lengthSize: Int)
        throws(FrameSourceConformanceError)
    {
        let payload = [UInt8](frame.asDelivered)
        if Codecs.startCodeLength(frame.asDelivered) > 0 {
            throw refuse(
                Rule.obligation7,
                did: "framed the sample at \(frame.timestampUs) us on track \(frame.trackIndex) "
                    + "(\"\(track.codec)\") with an Annex-B start code, although the track "
                    + "carries a configuration record",
                requires: "length-prefixed NAL units on a track that carries a record, each "
                    + "behind a \(lengthSize)-byte length field as the record itself declares. "
                    + "This is the worst of these to get wrong, because both directions read as "
                    + "valid: a start code taken for a length declares a one-byte unit, the "
                    + "picture data behind it becomes the next length, and the decoder is fed a "
                    + "structurally plausible stream of noise and reports nothing")
        }
        var offset = 0
        var units = 0
        while offset < payload.count {
            guard offset + lengthSize <= payload.count else {
                throw refuse(
                    Rule.obligation7,
                    did: "left \(payload.count - offset) byte(s) after \(units) NAL unit(s) in the "
                        + "sample at \(frame.timestampUs) us on track \(frame.trackIndex) — too few "
                        + "for a \(lengthSize)-byte length field",
                    requires: "a sample that is a whole number of length-prefixed NAL units and "
                        + "ends exactly where the last one does")
            }
            let length = payload[offset..<offset + lengthSize].reduce(0) { $0 << 8 | Int($1) }
            guard length > 0, offset + lengthSize + length <= payload.count else {
                throw refuse(
                    Rule.obligation7,
                    did: "declared a NAL unit of \(length) bytes at offset \(offset) of the "
                        + "\(payload.count)-byte sample at \(frame.timestampUs) us on track "
                        + "\(frame.trackIndex), read as a \(lengthSize)-byte length field",
                    requires: "a length field naming a unit that fits inside the sample. A run "
                        + "past the end is what a sample framed some other way looks like when it "
                        + "is read as length-prefixed — container framing left on, or Annex-B "
                        + "start codes under a fourcc that promised lengths")
            }
            offset += lengthSize + length
            units += 1
        }
        if units == 0 {
            throw refuse(
                Rule.obligation7,
                did: "delivered an empty payload for the frame at \(frame.timestampUs) us on "
                    + "track \(frame.trackIndex)",
                requires: "at least one NAL unit per frame. A frame with no bytes is a frame the "
                    + "decoder cannot be given and a sample buffer cannot describe")
        }
    }

    /// One Annex-B sample, checked as far as the framing its first bytes state.
    private func verifyAnnexB(_ frame: ReceivedFrame, track: RealtimeTrack)
        throws(FrameSourceConformanceError)
    {
        guard Codecs.startCodeLength(frame.asDelivered) == 0 else { return }
        throw refuse(
            Rule.obligation7,
            did: "framed the sample at \(frame.timestampUs) us on track \(frame.trackIndex) "
                + "(\"\(track.codec)\") with no Annex-B start code, beginning "
                + Codecs.hex(frame.asDelivered.prefix(4)),
            requires: "Annex-B start codes on a self-describing track, the same framing its "
                + "in-band parameter sets arrive in. A `\(Codecs.fourcc(of: track.codec))` track "
                + "states that its parameter sets are in the bitstream, and nothing can find them "
                + "in a sample whose units are not delimited")
    }

    // MARK: - The subscription

    /// Subscribes, keeps what arrives, and cancels — one subscription per check, the lifecycle a
    /// player gives a source.
    ///
    /// `subscribe` runs on a thread of its own, because a transport is entitled to deliver its whole
    /// script inside it, and a suite that called it on its own thread would never reach the reading.
    private func observe(linger: TimeInterval = 0, watchAfterCancel: Bool = false) -> Observation {
        let source = sources.open(url)
        let sink = RecordingSink(linger: linger)
        let subscribed = DispatchSemaphore(value: 0)
        let thread = Thread {
            source.subscribe(sink)
            subscribed.signal()
        }
        thread.name = "FrameSourceConformance"
        thread.start()
        // Not refused here when nothing arrives: which obligation an absent declaration breaks
        // depends on the check that is asking, so each check names its own rule.
        if sink.awaitTracks(within: Bounds.tracks) {
            sink.awaitFrames(within: Bounds.frames)
        }
        // The frames waited for are a *lower* bound, so the delivery is given a moment to finish
        // what it was in the middle of — what follows an end is exactly what one check is about.
        // The wait on `subscribe` returning is the deterministic half, for a transport that delivers
        // its whole script inside it; the pause is the best there is for one delivering from a
        // thread of its own, where nothing here can know what it was in the middle of.
        _ = subscribed.wait(timeout: .now() + Bounds.settle)
        Thread.sleep(forTimeInterval: Bounds.settle)
        // Obligation 8's two readings, taken while the source still holds its buffers.
        let memory = sink.memoryReadings()
        source.cancel()
        // Counted from the moment cancel *returned*, which is what the obligation says: a callback
        // made inside cancel is a transport draining what it had, which the rule allows. A callback
        // landing between cancel's return and this reading is missed, which errs toward passing; no
        // point nearer the return exists to read from, since only the transport knows when it is.
        let atCancel = sink.callbacks
        if watchAfterCancel { Thread.sleep(forTimeInterval: Bounds.cancellation) }
        return sink.observation(
            callbacksAfterCancel: watchAfterCancel ? sink.callbacks - atCancel : 0,
            memory: memory)
    }
}

/// An obligation ``FrameSourceConformance`` found broken, naming the rule, what the source did and
/// what the rule requires — the Kotlin `FrameSourceConformanceException`, named `…Error` as this
/// package's errors are.
///
/// A plain `Error` and nothing a test framework declares, so XCTest, Swift Testing and a runner of a
/// consumer's own all report it as the failure it is: the transport under test is the thing that
/// failed. No public initializer, because only the suite says what a transport did.
public struct FrameSourceConformanceError: Error, CustomStringConvertible, LocalizedError {

    /// The rule broken, as the message's first line names it.
    public let rule: String

    /// The whole message: the rule, what this source did, what the rule requires, and where the
    /// obligation is written out.
    public let message: String

    init(rule: String, did: String, requires: String) {
        self.rule = rule
        self.message = """
            This FrameSource does not satisfy \(rule).
              What this source did: \(did).
              What the rule requires: \(requires).
              The obligation is written out in full in FrameSource's documentation.
            """
    }

    public var description: String { message }
    public var errorDescription: String? { message }
}

/// The one message shape, composed in one place.
private func refuse(_ rule: String, did: String, requires: String) -> FrameSourceConformanceError {
    FrameSourceConformanceError(rule: rule, did: did, requires: requires)
}

/// The names a failure gives the rule it found broken — the Kotlin constants' words.
private enum Rule {
    static let obligation1 = "FrameSource obligation 1 (deliveries are ordered against one another)"
    static let obligation2 =
        "FrameSource obligation 2 (every track is declared once, before the first frame)"
    static let obligation3 = "FrameSource obligation 3 (each track's first frame is a keyframe)"
    static let obligation4 =
        "FrameSource obligation 4 (timestamps do not go backwards within a track)"
    static let obligation7 =
        "FrameSource obligation 7 (codec-specific data and sample framing follow the fourcc)"
    static let obligation8 =
        "FrameSource obligation 8 (a payload is handed over once and not touched again)"
    static let obligation9 = "FrameSource obligation 9 (cancel stops delivery before it returns)"
    /// The codec string's own requirement, which ``RealtimeTrack/codec``'s documentation carries.
    static let codec = "the codec string RealtimeTrack.codec requires"
    /// The finality of a terminal callback, which ``FrameSink/onEnded()``'s and
    /// ``FrameSink/onError(_:)``'s documentation carry.
    static let termination = "the finality of FrameSink.onEnded and FrameSink.onError"
}

/// The bounds the checks apply, the Kotlin suite's values and reasons.
private enum Bounds {

    /// How many frames a check waits for. Small on purpose: every obligation read here is a
    /// property of the *shape* of a delivery rather than of its length, so a longer observation
    /// would buy a longer wait and no more evidence, paid eleven times over against a live publisher.
    static let framesObserved = 16

    /// How long a declaration is waited for. Wide enough that a transport connecting to a real relay
    /// is never the finding, and finite so that one which never answers fails as itself rather than
    /// as a suite that hung.
    static let tracks: TimeInterval = 10

    /// How long ``framesObserved`` frames are waited for, once the tracks have been declared.
    static let frames: TimeInterval = 10

    /// How long a delivery is given to finish what it was in the middle of. Short, because every
    /// check pays it: it covers the gap between the last frame waited for and a callback that was
    /// always going to follow it, not a transport that is still connecting.
    static let settle: TimeInterval = 0.25

    /// How long a callback is watched for after `cancel()` has returned — about telling *stopped*
    /// from *not stopped at all*: a shutdown handed to another thread typically delivers its next
    /// frame well inside it.
    static let cancellation: TimeInterval = 1

    /// How long the overlap probe holds each callback. Long enough that two genuinely concurrent
    /// deliverers are seen inside the window on any machine, short enough that ``framesObserved`` of
    /// them cost well under a second.
    static let overlapProbeLinger: TimeInterval = 0.01

    /// A bound as a message states it.
    static func milliseconds(_ interval: TimeInterval) -> Int { Int(interval * 1000) }
}

/// The codec vocabulary the checks read — a list of names, never a parser (see
/// `verifyCodecStringsAreMapped`).
private enum Codecs {

    /// Fourccs that carry an out-of-band configuration record and frame their samples
    /// length-prefixed.
    static let recordFourccs: Set<String> = ["avc1", "hvc1"]

    /// Fourccs that are self-describing: parameter sets in band, samples framed with Annex-B.
    static let inBandFourccs: Set<String> = ["avc3", "hev1"]

    /// The families SuperPlayer maps, by an RFC 6381 string's first element or an SDP encoding name,
    /// lowercased. `mp4a` is absent deliberately — see ``isMapped(_:)``.
    static let mappedTokens: Set<String> = [
        "avc1", "avc3", "hvc1", "hev1", "vp09", "av01", "opus",
        "h264", "h265", "vp9", "av1", "mpeg4-generic",
    ]

    /// MP4 Registration Authority object types for AAC: `0x40` MPEG-4 Audio and `0x67` MPEG-2 AAC
    /// LC. `0x69` and `0x6B` are MP3, which is why `mp4a` cannot be mapped by its first element.
    /// spec: RFC 6381 §3.3 and https://mp4ra.org/registered-types/object-types.
    static let aacObjectTypes: Set<Int> = [0x40, 0x67]

    /// The shortest `AVCDecoderConfigurationRecord` this suite can read anything out of: the six
    /// bytes before the first parameter set's own length — `configurationVersion`, the three profile
    /// and level bytes, `lengthSizeMinusOne` and `numOfSequenceParameterSets`. Deliberately not the
    /// shortest *valid* record, since nothing here parses the parameter sets.
    /// spec: ISO/IEC 14496-15 §5.3.3.1.2.
    static let avccMinimumBytes = 6

    /// `lengthSizeMinusOne`'s byte in an `AVCDecoderConfigurationRecord`.
    /// spec: ISO/IEC 14496-15 §5.3.3.1.2.
    static let avccLengthSizeOffset = 4

    /// `lengthSizeMinusOne`'s byte in an `HEVCDecoderConfigurationRecord`.
    /// spec: ISO/IEC 14496-15 §8.3.3.1.2.
    static let hvccLengthSizeOffset = 21

    /// An RFC 6381 string's fourcc, or an SDP encoding name, lowercased. // spec: RFC 6381 §3.3.
    static func fourcc(of codec: String) -> String {
        String(codec.prefix { $0 != "." }).lowercased()
    }

    /// Whether SuperPlayer maps `codec` to a decoder.
    static func isMapped(_ codec: String) -> Bool {
        let token = fourcc(of: codec)
        if mappedTokens.contains(token) { return true }
        guard token == "mp4a" else { return false }
        // Hexadecimal without a prefix, per the registration; an absent or unreadable one is not AAC.
        let parts = codec.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count > 1, let objectType = Int(parts[1], radix: 16) else { return false }
        return aacObjectTypes.contains(objectType)
    }

    /// The length of the Annex-B start code `bytes` begins with, or zero.
    /// spec: ITU-T H.264 Annex B — `00 00 01`, optionally preceded by a further zero byte.
    static func startCodeLength(_ bytes: Data) -> Int {
        let head = [UInt8](bytes.prefix(4))
        if head == [0, 0, 0, 1] { return 4 }
        if head.count >= 3, head[0] == 0, head[1] == 0, head[2] == 1 { return 3 }
        return 0
    }

    /// Bytes as a message quotes them, two hex digits each.
    static func hex(_ bytes: Data) -> String {
        bytes.map { String(format: "%02x", $0) }.joined(separator: " ")
    }
}

/// What one subscription delivered, in the order it arrived.
private struct Observation {
    let tracks: [RealtimeTrack]
    let trackDeclarations: Int
    let framesBeforeTracks: Int
    let frames: [ReceivedFrame]
    let overlapped: Bool
    let deliveringThreads: Int
    let terminal: String?
    let terminalCallbacks: Int
    let framesAfterTerminal: Int
    let callbacksAfterCancel: Int
    let sharedMemory: (ReceivedFrame, ReceivedFrame)?
    let touchedAfterDelivery: ReceivedFrame?

    /// The declared tracks, or a refusal under the asking check's own rule — every check but the
    /// first two reads the declaration, so a source that never made one would otherwise pass them
    /// all by having delivered nothing, the vacuous pass a suite like this exists to avoid.
    func tracksOrRefuse(_ rule: String) throws(FrameSourceConformanceError) -> [RealtimeTrack] {
        guard trackDeclarations > 0 else {
            throw refuse(
                rule,
                did: "never called onTracks, within \(Bounds.milliseconds(Bounds.tracks)) ms of "
                    + "being subscribed, so there is nothing to check this against",
                requires: "onTracks called before the first frame, with every track the "
                    + "subscription will deliver (obligation 2). Every other obligation is stated "
                    + "in terms of it")
        }
        return tracks
    }
}

extension Array where Element == RealtimeTrack {
    /// The codec of a declared track, for a message, or a stand-in where the index is not one.
    fileprivate func codec(at index: Int) -> String {
        indices.contains(index) ? self[index].codec : "undeclared"
    }
}

/// One frame as it was delivered, kept with a copy of the bytes it held at that moment.
private struct ReceivedFrame {
    let trackIndex: Int
    let timestampUs: Int64
    let keyFrame: Bool

    /// The payload itself, which shares a transport's memory where it was made with `bytesNoCopy`
    /// — held only while the source is, and dropped by ``detached()`` before the observation leaves
    /// the sink, because a transport may free that memory once it has been cancelled.
    private(set) var payload: Data?

    /// What it held on arrival, copied out of whatever memory it pointed at.
    let asDelivered: Data

    init(_ frame: EncodedFrame) {
        trackIndex = frame.trackIndex
        timestampUs = frame.timestampUs
        keyFrame = frame.keyFrame
        payload = frame.payload
        // Through an array, not `Data(frame.payload)`, which may keep the same storage.
        asDelivered = Data([UInt8](frame.payload))
    }

    /// This frame without the payload that may point at a transport's memory.
    func detached() -> ReceivedFrame {
        var copy = self
        copy.payload = nil
        return copy
    }
}

/// The sink a check subscribes: it keeps everything, refuses nothing, and never touches what it was
/// given.
///
/// `@unchecked Sendable`: every `var` is read and written under `condition`, and the in-flight count
/// under `flight`, which is a lock of its own so that taking it does not serialize exactly the
/// concurrency obligation 1 is about.
private final class RecordingSink: FrameSink, @unchecked Sendable {

    private let linger: TimeInterval
    private let condition = NSCondition()
    private let flight = NSLock()
    private var inFlight = 0

    private var tracks: [RealtimeTrack] = []
    private var trackDeclarations = 0
    private var framesBeforeTracks = 0
    private var frames: [ReceivedFrame] = []
    private var threads = Set<UInt32>()
    private var overlapped = false
    private var terminal: String?
    private var terminalCallbacks = 0
    private var framesAfterTerminal = 0
    private var _callbacks = 0

    init(linger: TimeInterval) { self.linger = linger }

    /// Every callback made so far, which the cancellation check counts across `cancel()`.
    var callbacks: Int {
        condition.lock()
        defer { condition.unlock() }
        return _callbacks
    }

    func onTracks(_ tracks: [RealtimeTrack]) {
        record {
            trackDeclarations += 1
            // The first declaration is the one every check reads: a second is a violation in itself
            // and must not overwrite what the frames were sent against.
            if trackDeclarations == 1 { self.tracks = tracks }
        }
    }

    func onFrame(_ frame: EncodedFrame) {
        record {
            if trackDeclarations == 0 { framesBeforeTracks += 1 }
            if terminal != nil { framesAfterTerminal += 1 }
            frames.append(ReceivedFrame(frame))
        }
    }

    func onEnded() { record { end("onEnded") } }

    func onError(_ error: any Error) { record { end("onError") } }

    private func end(_ which: String) {
        terminalCallbacks += 1
        if terminal == nil { terminal = which }
    }

    /// Whether a declaration — or an end, after which none will come — arrived within `bound`.
    func awaitTracks(within bound: TimeInterval) -> Bool {
        wait(within: bound) { trackDeclarations > 0 || terminal != nil }
    }

    func awaitFrames(within bound: TimeInterval) {
        _ = wait(within: bound) { frames.count >= Bounds.framesObserved || terminal != nil }
    }

    private func wait(within bound: TimeInterval, until done: () -> Bool) -> Bool {
        let deadline = Date(timeIntervalSinceNow: bound)
        condition.lock()
        defer { condition.unlock() }
        while !done() {
            if !condition.wait(until: deadline) { return done() }
        }
        return true
    }

    /// Obligation 8's two readings over what has arrived (see the check for why each is what it is).
    func memoryReadings() -> (
        shared: (ReceivedFrame, ReceivedFrame)?, touched: ReceivedFrame?
    ) {
        condition.lock()
        let received = frames
        condition.unlock()
        var shared: (ReceivedFrame, ReceivedFrame)?
        search: for later in received.indices {
            for earlier in 0..<later
            where received[earlier].asDelivered != received[later].asDelivered
                && sameMemory(received[earlier].payload, received[later].payload)
            {
                shared = (received[earlier], received[later])
                break search
            }
        }
        let touched = received.first { $0.payload != $0.asDelivered }
        return (shared.map { ($0.0.detached(), $0.1.detached()) }, touched?.detached())
    }

    /// Whether two payloads begin at one address. Read with both buffers open at once, because a
    /// small `Data` stores its bytes inline and lends a temporary copy: two such copies alive
    /// together cannot share an address, while one read after the other could reuse it.
    private func sameMemory(_ first: Data?, _ second: Data?) -> Bool {
        guard let first, let second else { return false }
        return first.withUnsafeBytes { a in
            second.withUnsafeBytes { b in
                a.baseAddress != nil && a.baseAddress == b.baseAddress
            }
        }
    }

    func observation(
        callbacksAfterCancel: Int,
        memory: (shared: (ReceivedFrame, ReceivedFrame)?, touched: ReceivedFrame?)
    ) -> Observation {
        condition.lock()
        defer { condition.unlock() }
        return Observation(
            tracks: tracks, trackDeclarations: trackDeclarations,
            framesBeforeTracks: framesBeforeTracks, frames: frames.map { $0.detached() },
            overlapped: overlapped,
            deliveringThreads: threads.count, terminal: terminal,
            terminalCallbacks: terminalCallbacks, framesAfterTerminal: framesAfterTerminal,
            callbacksAfterCancel: callbacksAfterCancel, sharedMemory: memory.shared,
            touchedAfterDelivery: memory.touched)
    }

    /// One callback, recorded. The in-flight count is raised and the linger taken **outside**
    /// `condition`, because that lock is this recorder's own and taking it first would serialize the
    /// very concurrency obligation 1 is about and hide it.
    private func record(_ body: () -> Void) {
        let concurrent = flight.withLock {
            inFlight += 1
            return inFlight > 1
        }
        defer { flight.withLock { inFlight -= 1 } }
        if linger > 0 { Thread.sleep(forTimeInterval: linger) }
        let stillConcurrent = flight.withLock { inFlight > 1 }
        condition.lock()
        if concurrent || stillConcurrent { overlapped = true }
        threads.insert(pthread_mach_thread_np(pthread_self()))
        _callbacks += 1
        body()
        condition.broadcast()
        condition.unlock()
    }
}
