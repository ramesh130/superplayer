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

/// The protection a protected download acquires its offline licence under, and where the licence
/// is kept — the Kotlin `Downloads.Builder.setDrm(drm, licences)`, plus the two durations FairPlay
/// does not report (ADR-0013 rule 13, #425).
///
/// ```swift
/// configuration.protection = DownloadProtection(
///     drm: Drm.fairPlay(fairPlayConfig),                  // the one this content's players use
///     licences: try OfflineLicences.store(directory: licencesDirectory),
///     licenceDuration: .seconds(30 * 86_400), playbackDuration: .seconds(48 * 3_600))
///
/// // A player of the download, its licence read before the player is made:
/// if let licence = try licences.licence(for: contentId), !licence.isExpired {
///     player.drm = Drm.fairPlay(fairPlayConfig, licence: licence)
/// }
/// ```
///
/// **Both values are core types**, so `SuperPlayerOffline` depends on core alone and an app that
/// downloads only clear content carries nothing that knows what FairPlay is (ADR-0013 rule 1): `drm`
/// is `SuperPlayerDRM`'s `Drm.fairPlay(_:)` and `licences` its `OfflineLicences.store(directory:)`.
/// A value of a consumer's own for either acquires nothing, and protected content then downloads
/// with no licence — as it does on a store configured with no protection at all.
///
/// **The durations are stated rather than read**, for `OfflineLicence`'s reason: FairPlay reports
/// neither to the client — they are inside the licence the key server issued — so they are what the
/// key server's operator grants a download, as the app's own backend told it, and they are what
/// ``DownloadLicence`` and `OfflineLicenceStore.licence(for:)` count down. One pair per store,
/// because a service's download policy is one policy; content granted terms of its own is renewed
/// through `store.over(player).renew(_:licenceDuration:playbackDuration:)`.
public struct DownloadProtection: Sendable {

    /// The protection the content is encrypted under: `Drm.fairPlay(_:)`, naming the servers.
    public var drm: any PlaybackDrm

    /// Where a download's licence is written and read: an `OfflineLicenceStore`.
    public var licences: any LicenceStore

    /// How long a download's entitlement lives, as the key server's operator grants it.
    public var licenceDuration: Duration

    /// The viewing window once a download is first played, likewise.
    public var playbackDuration: Duration

    public init(
        drm: any PlaybackDrm, licences: any LicenceStore, licenceDuration: Duration,
        playbackDuration: Duration
    ) {
        self.drm = drm
        self.licences = licences
        self.licenceDuration = licenceDuration
        self.playbackDuration = playbackDuration
    }
}

/// The FairPlay keys a multivariant playlist declares — what tells the store a download is
/// protected, and which keys its licence is for.
///
/// **Only `EXT-X-SESSION-KEY` is read**, the Kotlin store's trap restated for iOS: a licence is
/// acquired for the protection the manifest read sees, and the read is of the multivariant playlist
/// alone, so content that declares its key only in each media playlist's `EXT-X-KEY` downloads with
/// no licence and cannot play offline. Declaring it in the multivariant playlist is what that tag is
/// for.
///
/// spec: RFC 8216 §4.3.4.5 — `EXT-X-SESSION-KEY` lets a client preload the keys of media playlists
/// from the multivariant playlist, with `EXT-X-KEY`'s attributes; its `METHOD` must not be `NONE`.
/// ref: Apple, "HLS Authoring Specification for Apple Devices" and "FairPlay Streaming" — a FairPlay
/// key's `KEYFORMAT` is `com.apple.streamingkeydelivery`, and its `URI` (an `skd://` URI) is the
/// identifier a key request names.
package enum SessionKeys {

    /// FairPlay Streaming's `KEYFORMAT`: a session key in any other format — another DRM's, in a
    /// multi-DRM playlist — is not this store's to acquire.
    static let fairPlayKeyFormat = "com.apple.streamingkeydelivery"

    /// The `URI` of every FairPlay `EXT-X-SESSION-KEY` in `playlist`, in the order declared, each
    /// once.
    package static func declared(in playlist: String) -> [String] {
        var identifiers: [String] = []
        for line in playlist.split(whereSeparator: \.isNewline) {
            let prefix = "#EXT-X-SESSION-KEY:"
            guard line.hasPrefix(prefix) else { continue }
            let attributes = attributeList(line.dropFirst(prefix.count))
            guard attributes["METHOD"].map({ $0 != "NONE" }) ?? false,
                attributes["KEYFORMAT"] == fairPlayKeyFormat, let uri = attributes["URI"],
                !identifiers.contains(uri)
            else { continue }
            identifiers.append(uri)
        }
        return identifiers
    }

    /// An attribute list's names and values, quotes taken off a quoted string.
    ///
    /// spec: RFC 8216 §4.2 — `AttributeName=AttributeValue` pairs separated by commas, where a
    /// quoted-string value may itself contain a comma, so splitting on every comma is wrong.
    static func attributeList(_ text: Substring) -> [String: String] {
        var attributes: [String: String] = [:]
        var name = ""
        var value = ""
        var inName = true
        var quoted = false
        func finish() {
            if !name.isEmpty { attributes[name] = value }
            name = ""
            value = ""
            inName = true
        }
        for character in text {
            if inName {
                if character == "=" { inName = false } else { name.append(character) }
            } else if character == "\"" {
                quoted.toggle()
            } else if character == ",", !quoted {
                finish()
            } else {
                value.append(character)
            }
        }
        finish()
        return attributes
    }
}
