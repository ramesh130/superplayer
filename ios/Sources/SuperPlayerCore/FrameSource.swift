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

/// A realtime transport, as SuperPlayer sees it: something that can be subscribed to and will then
/// push **encoded** frames until it is cancelled — the Kotlin `FrameSource`, ported whole
/// (ADR-0020 rule 11, `docs/ios-plan.md` §3.10).
///
/// This is the whole seam (ADR-0018 rule 2). A transport implements it over MoQ, over WebRTC, or
/// over whatever else pushes media; `SuperPlayerRealtime`'s `RealtimePlayer` wraps what arrives in
/// `CMSampleBuffer`s and enqueues them to an `AVSampleBufferDisplayLayer` and an
/// `AVSampleBufferAudioRenderer` under one `AVSampleBufferRenderSynchronizer`, and those decode it.
/// **Nothing in this library decodes** (rule 1).
///
/// It names no AVFoundation or CoreMedia type, for the Kotlin seam's reason — it names no Media3
/// type — and for one more that holds here too: it is the only boundary at which a realtime
/// transport is testable at all. Neither QUIC nor WebRTC runs under `ios/check`, so a fake stands
/// here and everything above it is ordinary simulator work.
///
/// It is the core product's rather than the realtime product's for the Kotlin #356 reason: a
/// conformance suite belongs in `SuperPlayerTestkit` (#433), which is phase 2 and may name nothing
/// later than core, so an interface a consumer implements has to be core's for its suite to reach
/// it.
///
/// ## The obligations, and what getting each one wrong costs
///
/// Written out because **every one of them fails silently**. A transport that breaks one does not
/// throw: it produces a layer that accepts every buffer and shows nothing, or a player whose clock
/// never starts, with no error anywhere. The Kotlin KDoc carries the same list; the costs are
/// restated in this platform's terms where the mechanism underneath differs.
///
/// 1. **Deliver on one thread, or with a happens-before between deliveries.** Every call on the
///    ``FrameSink`` a subscription was given must be ordered against every other. *Cost:* the player
///    enqueues in the order it is called, and a layer handed two frames in the wrong order decodes a
///    dependent frame before its reference — corruption whose cause names nothing of yours.
/// 2. **Call ``FrameSink/onTracks(_:)`` before the first frame, exactly once, with every track the
///    subscription will deliver.** A frame names its track by ``EncodedFrame/trackIndex``, a position
///    in that list, so a track declared later has no index it could have been sent under and a
///    second call has nothing it could mean. *Cost:* no format description exists, so nothing is
///    enqueued and the player waits for ever, with no error to report.
/// 3. **Make the first frame of each track a keyframe.** *Cost:* the decoder is fed a dependent
///    frame with no reference, and renders either nothing or visible corruption — the classic "it
///    connects and the picture is garbage" report.
/// 4. **Timestamps are microseconds on one monotonically non-decreasing timeline per track, and
///    ideally on one epoch across tracks.** They are *yours*: SuperPlayer subtracts the first frame's
///    timestamp and treats the rest as offsets from it, so an epoch is not required, but a reset or a
///    step backwards within a track is not tolerated (ADR-0018 rule 9). *Cost:* a frame timed before
///    the synchronizer's clock is late on arrival and the layer drops it silently; a backwards step
///    strands the clock ahead of every frame that follows.
/// 5. **The precision you have is the precision you state.** Where a transport cannot fill
///    microseconds exactly, say so in *your* documentation rather than implying a precision you do
///    not have. *Cost:* nobody can tell your rounding from a fault in this library. **This one is
///    unenforceable**, which is exactly why it is written down.
/// 6. **``EncodedFrame/payload`` is codec bitstream and nothing else.** Container framing is yours to
///    strip before the seam (ADR-0018 rule 3); no fragmented-MP4 parsing exists on this side.
///    *Cost:* the layer accepts the buffer and shows nothing, which is the hardest of all these to
///    attribute.
/// 7. **``RealtimeTrack/codecConfiguration`` follows the codec's fourcc, never the container**
///    (ADR-0018 rule 4). `avc1` and `hvc1` carry an out-of-band configuration record and hand it over
///    as ``RealtimeTrack/CodecConfiguration/record(_:)``, **exactly as received**; `avc3` and `hev1`
///    carry their parameter sets in band before every keyframe and hand over
///    ``RealtimeTrack/CodecConfiguration/inBand``, the default. *Cost:* a decoder configured from
///    parameter sets that were about to arrive again in band, or from none with none coming — the
///    silent nothing of 6 in both directions.
///
///    The same fourcc decides how that track's **samples** are framed, the half nothing can check: a
///    track carrying a record sends its payload as **length-prefixed** NAL units, behind the length
///    field width the record declared, and a self-describing track sends Annex-B start codes.
///    **The conversion runs the other way from Android's** (`docs/ios-plan.md` §3.10): CoreMedia's
///    native shape is the record and length-prefixed samples, so a record passes through as the
///    format description's atom and in-band parameter sets are what need converting (#432). *Cost:*
///    a start code taken for a length declares a one-byte unit and the picture data behind it
///    becomes the next "length", so a decoder is fed a structurally plausible stream of noise and
///    reports nothing.
/// 8. **Hand ``EncodedFrame/payload`` over once, and do not reuse memory a payload still points
///    at.** `Data` is a value, so mutating your own copy after ``FrameSink/onFrame(_:)`` returns
///    cannot reach this side's — the Kotlin obligation's first half is met by the type here. What is
///    left is a `Data` made with `bytesNoCopy` over a buffer the transport recycles, which shares
///    the memory a value would have copied. *Cost:* a torn frame, and a corruption that moves when
///    timing moves — the least reproducible failure in this list.
/// 9. **``FrameSource/cancel()`` stops delivery, and no callback arrives after it returns.** *Cost:*
///    on this side a late callback is dropped rather than enqueued, so what it costs is the
///    transport's: work done for a sink nobody reads, on a thread that outlived its player.
/// 10. **State a video track's coded size if you know it** (``RealtimeTrack/codedSize``). A publisher
///    declares the resolution in its catalog or session description, ahead of any frame. *Cost:* a
///    video format description is made with the size the track states, and one made with none
///    describes a zero-sized picture, so the layer sizes and the app lays out around nothing until a
///    frame decodes — and on Android the same omission fails the decoder outright. **Like 5, this
///    one is unenforceable**: no suite can know your stream's true resolution.
///
/// ## What a realtime stream does not get
///
/// Stated rather than discovered (ADR-0018 rule 6, ADR-0020 rule 11): no content cache, no CMCD, no
/// downloads, no bandwidth estimate and none of the fallback ladder's load-error rungs, because on
/// both platforms those are keyed to the transfer path and this is not one.
public protocol FrameSource: AnyObject, Sendable {

    /// Begins delivery to `sink`, which is called as obligations 1 to 8 above require.
    ///
    /// Called once per playback, on the main actor, by the player that opened this source. A
    /// transport that needs to connect does so on a thread of its own and returns; nothing on this
    /// side waits for it, and the player shows nothing until ``FrameSink/onTracks(_:)`` and the first
    /// frames arrive.
    func subscribe(_ sink: any FrameSink)

    /// Ends delivery, releasing whatever ``subscribe(_:)`` opened.
    ///
    /// Called when the player lets the stream go, and **once** per ``subscribe(_:)``, on the main
    /// actor. It must return only once no further callback can be made — obligation 9 — and must be
    /// safe to call while another thread is delivering frames.
    func cancel()
}

/// Where a ``FrameSource`` delivers what it receives.
///
/// Implemented by SuperPlayer and handed to ``FrameSource/subscribe(_:)``; a transport calls it and
/// never implements it. Every method's contract is one of the obligations on ``FrameSource``, which
/// is where the reasons and the costs are. `Sendable`, because a transport calls it from a thread of
/// its own.
public protocol FrameSink: Sendable {

    /// Every track this subscription will deliver. Called once, before the first ``onFrame(_:)``
    /// (obligation 2).
    ///
    /// The order is the one ``EncodedFrame/trackIndex`` names, so a transport chooses it and then
    /// keeps it. An empty list declares nothing to play and is refused.
    func onTracks(_ tracks: [RealtimeTrack])

    /// One encoded frame (obligations 3, 4, 6 and 8).
    func onFrame(_ frame: EncodedFrame)

    /// The stream ended of its own accord — the publisher stopped, the broadcast finished.
    ///
    /// Playback shows what is enqueued and stops. A transport that *failed* calls ``onError(_:)``
    /// instead: the difference is what a viewer is shown, and it is not this library's to guess.
    func onEnded()

    /// The stream failed and no further frame will arrive.
    ///
    /// `error` reaches the consumer as the player's failure, so it is read by a human in a bug
    /// report: name what failed, not that something did.
    func onError(_ error: any Error)
}

/// What is being delivered: the codec, the codec-specific data that codec needs out of band, and a
/// video track's coded size.
public struct RealtimeTrack: Sendable, Hashable {

    /// What is being sent, in either of the two vocabularies a realtime transport has: an RFC 6381
    /// codecs string — `avc1.640028`, `avc3.42E01E`, `hev1.1.6.L93.B0`, `vp09.00.10.08`,
    /// `av01.0.04M.08`, `mp4a.40.2`, `opus` — or an SDP encoding name as an `a=rtpmap` line carries
    /// it, `H264`, `H265`, `VP9`, `AV1`, `MPEG4-GENERIC` or `opus`. Prefer the RFC 6381 string where
    /// you have one: it states a profile and a level, and a bare encoding name states neither.
    /// H.264, H.265, VP9, AV1, AAC and Opus are mapped and **anything else is refused** with
    /// ``UnsupportedRealtimeCodecError`` naming the string, because a codec decoded as the wrong one
    /// fails silently. An RFC 6381 string's **fourcc** prefix is also what decides which
    /// ``CodecConfiguration`` is expected, never the container the frames came in (ADR-0018 rule 4).
    public let codec: String

    /// What the decoder needs before the first frame, in the shape the codec's fourcc says it comes
    /// in: ``CodecConfiguration/record(_:)`` for `avc1` and `hvc1`, and ``CodecConfiguration/inBand``
    /// — the default — for `avc3` and `hev1`. See obligation 7 on ``FrameSource``.
    public let codecConfiguration: CodecConfiguration

    /// The coded size of a video track, or nil where the transport did not state one — the honest
    /// answer for an audio track, and the default, because a transport that says nothing must not be
    /// read as claiming a size (obligation 10).
    public let codedSize: CodedSize?

    public init(
        codec: String, codecConfiguration: CodecConfiguration = .inBand, codedSize: CodedSize? = nil
    ) {
        self.codec = codec
        self.codecConfiguration = codecConfiguration
        self.codedSize = codedSize
    }

    /// The dimensions of a video track's samples, in pixels — the Kotlin `CodedSize`, for its reason:
    /// half a size is not a state that should be expressible, so the pair is constructed or it is
    /// absent.
    ///
    /// The values come from a **remote publisher's** declaration, and this repository's rule for
    /// that is to refuse what does not hold together rather than read past it (ADR-0018 rule 4's
    /// #345 addendum), so a zero or a negative dimension makes no size: the initializer is failable
    /// where the Kotlin constructor throws, because a value a transport read off the wire is input to
    /// check rather than a programming error to trap. It is the **coded** size, not the display size.
    public struct CodedSize: Sendable, Hashable, CustomStringConvertible {

        /// The coded width in pixels. Positive.
        public let width: Int

        /// The coded height in pixels. Positive.
        public let height: Int

        /// A size of `width` by `height`, or nil unless both are positive.
        public init?(width: Int, height: Int) {
            guard width > 0, height > 0 else { return nil }
            self.width = width
            self.height = height
        }

        public var description: String { "\(width)x\(height)" }
    }

    /// Where the parameter sets a decoder is configured with come from, which the codec's **fourcc**
    /// decides and the container never does (ADR-0018 rule 4).
    ///
    /// A named shape rather than a list of bytes, for the Kotlin #345 reason: every value of a byte
    /// list type-checks, so a record handed over where units were expected compiled and rendered
    /// nothing, while a named shape lets a fourcc that contradicts it be refused by name.
    public enum CodecConfiguration: Sendable, Hashable {

        /// The stream is self-describing: parameter sets arrive in band before every keyframe, and
        /// nothing is configured out of band. What `avc3` and `hev1` hand over.
        case inBand

        /// The codec's out-of-band configuration record, **exactly as the transport received it**:
        /// an `AVCDecoderConfigurationRecord` (`avcC`) for `avc1`, an
        /// `HEVCDecoderConfigurationRecord` (`hvcC`) for `hvc1`, and for any other codec whatever
        /// its binding calls its configuration record. Not converted, unwrapped or reordered first.
        /// A track that carries one sends its samples **length-prefixed** (obligation 7).
        case record(Data)
    }
}

/// One encoded frame.
public struct EncodedFrame: Sendable, Hashable {

    /// The frame's presentation time in microseconds, on the transport's own monotonically
    /// non-decreasing timeline (obligations 4 and 5). SuperPlayer subtracts the first frame's value,
    /// so the epoch is yours and only the differences are read.
    public let timestampUs: Int64

    /// The codec bitstream, with no container framing (obligation 6), handed over once
    /// (obligation 8).
    public let payload: Data

    /// Whether this frame can be decoded without reference to an earlier one. The first frame of
    /// each track must be one (obligation 3).
    public let keyFrame: Bool

    /// Which of the tracks handed to ``FrameSink/onTracks(_:)`` this frame belongs to, by position in
    /// that list. Zero, the default, is the only value a single-track subscription has. A frame
    /// naming a track that was never declared is refused rather than dropped, because a silently
    /// discarded track is exactly the failure the obligations exist to prevent.
    public let trackIndex: Int

    public init(timestampUs: Int64, payload: Data, keyFrame: Bool, trackIndex: Int = 0) {
        self.timestampUs = timestampUs
        self.payload = payload
        self.keyFrame = keyFrame
        self.trackIndex = trackIndex
    }
}

/// Opens the ``FrameSource`` for a URL whose scheme this transport answers.
///
/// One source per playback: ``FrameSource/subscribe(_:)`` is called on what this returns, and
/// ``FrameSource/cancel()`` when the player is done with it. Returning the same instance for two
/// URLs would have one stream's cancellation end the other's delivery, so a transport that pools
/// connections pools those rather than this.
public protocol FrameSourceFactory: Sendable {

    /// The source for `url`, whose scheme is one this transport was registered under. Called on the
    /// main actor, by the player adopting `url`.
    func open(_ url: URL) -> any FrameSource
}
