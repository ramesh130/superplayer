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

/// A realtime stream was asked to start somewhere other than at its live edge, and the request was
/// refused rather than coerced (ADR-0018 rule 5) — the Kotlin `RealtimeStreamNotSeekableException`,
/// under Swift's `Error` suffix as ``CmcdModeUnsupportedError`` is.
///
/// Thrown by `RealtimePlayer.setMediaRequest(_:)` for a ``MediaRequest`` whose
/// ``MediaRequest/StartPosition`` is ``MediaRequest/StartPosition/at(positionMs:)`` or
/// ``MediaRequest/StartPosition/resumeFromLastKnown``, **before any of adoption's bookkeeping runs**,
/// so a refused request leaves the player exactly as it was.
///
/// **Why refused rather than coerced:** a realtime timeline is live and unseekable — nothing was
/// received before the subscription began, so there is no past to seek into. Quietly starting at the
/// edge instead would be a resume that *looks like it worked*: the app asked for 12:04 and got live,
/// with nothing anywhere saying so.
///
/// **What to do instead:** play the source at ``MediaRequest/StartPosition/beginning``, which on a
/// live stream is its edge. Content that must resume where a viewer left it needs a source with a
/// past, on a `SuperPlayer`.
///
/// It carries no failure class, for the Kotlin reason: every class answers which rung of the
/// fallback ladder may be climbed, and there is no rung for "this stream has no past".
public struct RealtimeStreamNotSeekableError: Error, Sendable, Hashable, CustomStringConvertible {

    /// The realtime scheme the refused source named, without the `:`.
    public let scheme: String

    /// The start position that was asked for, as the case a consumer wrote — `at`,
    /// `resumeFromLastKnown`.
    public let startPosition: String

    package init(scheme: String, startPosition: String) {
        self.scheme = scheme
        self.startPosition = startPosition
    }

    public var description: String {
        "A \(scheme): stream is live and unseekable, so StartPosition.\(startPosition) cannot be "
            + "honoured. SuperPlayer refuses it rather than starting at the live edge, because a "
            + "coerced resume is indistinguishable from one that worked. Use "
            + "StartPosition.beginning, or play a source with a past on a SuperPlayer."
    }
}

/// A realtime transport delivered a track whose codec string SuperPlayer cannot map to a decoder —
/// the Kotlin `UnsupportedRealtimeCodecException`.
///
/// Raised when ``RealtimeTrack/codec`` is neither an RFC 6381 codecs string for a codec this library
/// maps nor an SDP encoding name for one, or is one of a mapped family whose parameters do not parse.
///
/// **Why a refusal rather than a guess:** elsewhere in this repository an unknown refuses nothing —
/// that rule is about **constraints**. This is an **assertion about bytes**: a codec nobody mapped
/// quietly treated as one we do know would configure a decoder cleanly and then render nothing, or
/// corruption, with the stated cause naming the wrong codec. A refusal that says which string
/// arrived is the only version of this a bug report can act on, which is why the string is a
/// property and is in the description.
///
/// It reaches the consumer as `RealtimePlayer`'s failure, the delivery that went wrong — the track
/// arrived over a transport already subscribed — where ``RealtimeStreamNotSeekableError`` is thrown
/// at the call, refusing a configuration before any frame is asked for.
///
/// **What to do instead:** publish H.264, H.265, VP9, AV1, AAC or Opus, under its RFC 6381 string.
public struct UnsupportedRealtimeCodecError: Error, Sendable, Hashable, CustomStringConvertible {

    /// The codec string the transport delivered, verbatim, so a bug report carries the thing itself.
    public let codec: String

    package init(codec: String) {
        self.codec = codec
    }

    public var description: String {
        "No decoder mapping for realtime codec \"\(codec)\". SuperPlayer maps H.264 (avc1, avc3, "
            + "H264), H.265 (hvc1, hev1, H265), VP9 (vp09, VP9), AV1 (av01, AV1), AAC (mp4a.40.*, "
            + "mp4a.67, MPEG4-GENERIC) and Opus (opus), as RFC 6381 codecs strings or SDP encoding "
            + "names. It refuses an unrecognised string rather than guessing, because a codec "
            + "decoded as the wrong one fails silently."
    }
}

/// A realtime transport delivered bytes that are not the shape its own codec string says they are —
/// the Kotlin `MalformedRealtimeBitstreamException`.
///
/// ``UnsupportedRealtimeCodecError``'s argument one layer in: that one refuses a codec nobody
/// mapped, this one refuses bytes that *are* a mapped codec's and do not hold together. Reading a
/// record up to the point it stops making sense configures a decoder that then renders nothing, or
/// corruption, indistinguishable from a working stream until a viewer looks at it. ``reason`` says
/// which case it was, because that tells a transport author whether the bug is in the publisher or
/// in the adapter.
///
/// The tracer (#431) raises it for the audio cases a format description cannot be made from: an AAC
/// track with no AudioSpecificConfig — CoreMedia describes audio by its sampling rate and channels
/// before the first packet, AAC states them only there, and a guessed rate plays at the wrong speed
/// — and a configuration record that ends before the fields it must carry. The video cases the
/// Kotlin type covers — a record that does not parse, a record on a self-describing fourcc, a length
/// field running past a frame — arrive with #432's conversion, mirrored.
public struct MalformedRealtimeBitstreamError: Error, Sendable, Hashable, CustomStringConvertible {

    /// The codec string the track was declared as, verbatim.
    public let codec: String

    /// What was wrong, in one clause, naming the offset or the count that did not add up.
    public let reason: String

    package init(codec: String, reason: String) {
        self.codec = codec
        self.reason = reason
    }

    public var description: String {
        "Malformed realtime bitstream for codec \"\(codec)\": \(reason)."
    }
}
