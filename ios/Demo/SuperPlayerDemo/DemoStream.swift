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

/// The public test streams the demo plays, and the only place one is named — the Android demo's
/// `DemoStream` less its DASH entry, since `AVPlayer` plays no DASH (ADR-0020's *does not port*).
///
/// A stream is an identity, a location and what the lock screen shows, because that is all
/// SuperPlayer needs to play one. The content id is what the stream *is* rather than where it is
/// served from, in a namespace of the demo's own and deliberately not derived from the URL: a second
/// CDN host for the same title has to be the same content id, which is the point of a `MediaRequest`
/// carrying one. It is the Android demo's id for the same stream, so a resume position and a
/// telemetry row name one piece of content on both platforms.
enum DemoStream: String, CaseIterable {

    /// Apple's public "Advanced stream (HEVC/H.264, fMP4)" HLS example — the reference stream the HLS
    /// specification's own authors publish, multi-bitrate with alternate audio and subtitle
    /// renditions, and the Android demo's HLS entry.
    ///
    /// ref: https://developer.apple.com/streaming/examples/
    case bipbopAdvanced = "demo:bipbop-advanced"

    var contentId: String { rawValue }

    var url: URL {
        switch self {
        case .bipbopAdvanced:
            URL(
                string: "https://devstreaming-cdn.apple.com/videos/streaming/examples/"
                    + "img_bipbop_adv_example_fmp4/master.m3u8")!
        }
    }

    /// The request for this stream, with everything the lock screen and Control Centre show. One
    /// function for every screen and for the catalog, because two descriptions of one stream — one
    /// for the app, one for Now Playing — are how the lock screen comes to disagree with the app.
    func request(startPosition: MediaRequest.StartPosition = .beginning) -> MediaRequest {
        MediaRequest(
            contentId: contentId,
            sources: [url],
            startPosition: startPosition,
            title: "Bip Bop",
            subtitle: "Apple's advanced HLS example")
    }
}

/// The demo's catalog, as a `PlaybackSession` asks it: content named from outside the app — a
/// remote command, a CarPlay row — arrives as a content id, and this turns it back into a request
/// rather than reading it as a URL (ADR-0007 rule 3).
struct DemoCatalog: MediaRequestResolver {

    func resolve(contentId: String) -> MediaRequest? {
        DemoStream(rawValue: contentId)?.request(startPosition: .resumeFromLastKnown)
    }
}
