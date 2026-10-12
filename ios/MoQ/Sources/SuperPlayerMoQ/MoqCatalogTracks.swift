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
import MoqFFI
import SuperPlayerCore

/// One track this product will subscribe to, as the catalog declared it — the Kotlin
/// `MoqDeclaredTrack`: the ``RealtimeTrack`` the seam is handed, and the two things MoQ needs to open
/// the subscription behind it, which the seam has no room for and no business knowing.
struct MoqDeclaredTrack: Sendable, Equatable {

    /// The catalog's key for this rendition, which is what `subscribeMedia(name:…)` takes.
    let trackName: String

    /// What the publisher declared this track's frames are wrapped in. Handed straight back to
    /// `subscribeMedia`, which is the **only** thing it is for: the bindings strip the framing below
    /// the FFI boundary, so it must not enter any decoding decision (#340, ADR-0018 rules 3 and 4).
    let container: MoqContainer

    /// What the realtime seam accepts: the codec string verbatim, the configuration's shape, and the
    /// coded size where one was declared.
    let track: RealtimeTrack
}

/// What a publisher *declared* becomes what the realtime seam *accepts*: a MoQ catalog becomes a
/// list of tracks — the Kotlin `MoqCatalogTracks`, whose reasons hold here and are not all repeated.
///
/// A pure mapping — no session, no QUIC, no frames — and so the one part of this product the check
/// can test honestly, which is where "both codec-description shapes are covered by a test" is
/// discharged on this platform as on Android.
///
/// **The catalog arrives typed, so nothing here parses**: the bindings decode the wire format below
/// the FFI boundary and hand over a `MoqCatalog` of two maps, track name to rendition, per kind.
///
/// // spec: the catalog's shape — renditions keyed by track name, per media kind — is `moq-lite`'s
/// // `hang` layer, documented at doc.moq.dev; the bindings' `MoqCatalog` mirrors it field for field.
///
/// **It maps no codec strings and re-derives no fourcc rule.** `RealtimeFormats` and
/// `CodecConfigurationRecords` are `SuperPlayerRealtime`'s and internal, and this product does not
/// depend on that one at all, so they are unreachable by construction. A codec string nothing maps is
/// passed through **verbatim and kept in the list**, and the realtime engine refuses the whole
/// subscription by name; a `description` contradicting its own fourcc is handed over as received and
/// refused there too. Dropping or correcting either here would play a partially understood catalog
/// as a silently narrower stream.
///
/// ## One rendition per kind, chosen in no way — and on this platform, in name order
///
/// ADR-0018 rule 7 **defers** adaptive selection, so one rendition is taken per kind and none is
/// preferred; `MoqVideo.stalled` is read by nothing, for that reason. The Kotlin port takes the
/// *first* rendition each map declares. **That ordering does not exist here**, and the departure is
/// forced rather than chosen: the bindings carry each map as a Swift `Dictionary`, whose iteration
/// order is unspecified and changes from one process to the next, so "first" would be a different
/// rendition on every launch. This takes the rendition whose **track name sorts first** instead —
/// still a choice of nothing, but the same nothing every time, which is what a bug report needs.
///
/// // ref: `rs/moq-ffi/src/media.rs` `convert_catalog` at `moq-dev/moq@4e419f9` collects each kind's
/// // renditions into a Rust `HashMap` before either binding is reached, so the publisher's
/// // declaration order is already gone on the Rust side of the boundary, for both platforms.
enum MoqCatalogTracks {

    /// The tracks `catalog` declares, in the order ``SuperPlayerCore/EncodedFrame/trackIndex`` will
    /// name them: **video first, then audio** — a promise, because an index is a position in this
    /// list. Throws where the catalog declares neither kind, or declares a video size no decoder could
    /// be configured with; both are plain ``MoqTransportError``s, since the realtime path's typed
    /// refusals are about *bytes* and there are none to name here.
    static func declaredTracks(of catalog: MoqCatalog) throws -> [MoqDeclaredTrack] {
        var declared: [MoqDeclaredTrack] = []
        if let (trackName, video) = catalog.video.min(by: { $0.key < $1.key }) {
            declared.append(
                MoqDeclaredTrack(
                    trackName: trackName,
                    container: video.container,
                    track: RealtimeTrack(
                        codec: video.codec,
                        codecConfiguration: configuration(of: video.description),
                        // The **coded** size, which is what a decoder is configured with;
                        // `displayAspect` is presentation and read by nothing (ADR-0018 rule 1).
                        // Carried because leaving it out costs a picture on Android (#353), and
                        // because the seam's obligation 10 asks for it where it is known.
                        codedSize: try codedSize(of: video.coded, trackName: trackName))))
        }
        if let (trackName, audio) = catalog.audio.min(by: { $0.key < $1.key }) {
            declared.append(
                MoqDeclaredTrack(
                    trackName: trackName,
                    container: audio.container,
                    track: RealtimeTrack(
                        codec: audio.codec,
                        codecConfiguration: configuration(of: audio.description))))
        }
        guard !declared.isEmpty else {
            throw MoqTransportError(
                "The MoQ catalog declares no video track and no audio track, so there is nothing to "
                    + "subscribe to.")
        }
        return declared
    }

    /// The shape the publisher declared its codec configuration in, which is `description`'s
    /// **presence** and nothing else: present is a ``RealtimeTrack/CodecConfiguration/record(_:)`` of
    /// the bytes exactly as received — an empty one included, since what an empty record means is a
    /// judgement about bytes this product makes none of — and absent is
    /// ``RealtimeTrack/CodecConfiguration/inBand``. The fourcc correspondence is ADR-0018 rule 4's,
    /// which `SuperPlayerRealtime` owns and checks; this reports what arrived.
    private static func configuration(of description: Data?) -> RealtimeTrack.CodecConfiguration {
        description.map { .record($0) } ?? .inBand
    }

    /// `declared` as the seam's own size, or nil where none was declared.
    ///
    /// The bindings carry the dimensions as `UInt32`, and what is refused is what no decoder could be
    /// configured with: a zero, and anything past `Int32.max`. The second is the Kotlin refusal for a
    /// different reason — there a `UInt` wraps to a negative `Int`; here an `Int` holds it whole, but
    /// the realtime engine describes the track in CoreMedia's `CMVideoDimensions`, whose fields are
    /// `Int32`, so the number would arrive there truncated into a claim about another picture.
    /// Either way the failure arrives as a statement about the catalog, with the track named.
    private static func codedSize(
        of declared: MoqDimensions?, trackName: String
    ) throws -> RealtimeTrack.CodedSize? {
        guard let declared else { return nil }
        guard declared.width <= Int32.max, declared.height <= Int32.max,
            let size = RealtimeTrack.CodedSize(
                width: Int(declared.width), height: Int(declared.height))
        else {
            throw MoqTransportError(
                "The MoQ catalog declares track \"\(trackName)\" as "
                    + "\(declared.width)x\(declared.height), which is not a size a decoder can be "
                    + "configured with. A publisher that does not know its resolution declares none."
            )
        }
        return size
    }
}

/// A catalog this product cannot subscribe from, or a transport failure on the way to one. Untyped
/// on purpose, as the Kotlin `IOException`s and core's own delivery errors are: a consumer has
/// nothing to branch on, and the message names what the publisher's author needs.
struct MoqTransportError: Error, CustomStringConvertible, LocalizedError {
    let description: String
    init(_ description: String) { self.description = description }
    var errorDescription: String? { description }
}
