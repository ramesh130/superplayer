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

import SuperPlayerOffline
import XCTest

/// What tells the download store a manifest is protected (#425): the FairPlay
/// `EXT-X-SESSION-KEY`s of a multivariant playlist, on string literals alone — each refusal beside
/// the declaration it is told apart from, so the rule is neither "any key line" nor "none".
final class SessionKeysTests: XCTestCase {

    private let fairPlay = "KEYFORMAT=\"com.apple.streamingkeydelivery\",KEYFORMATVERSIONS=\"1\""

    func testFairPlaySessionKeysAreReadInOrderAndOnce() {
        let playlist = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="skd://keys.example/a",\(fairPlay)
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="skd://keys.example/b",\(fairPlay)
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="skd://keys.example/a",\(fairPlay)
            #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2"
            media.m3u8
            """
        XCTAssertEqual(
            SessionKeys.declared(in: playlist), ["skd://keys.example/a", "skd://keys.example/b"])
    }

    /// RFC 8216 §4.2: a quoted string may hold a comma, and the attribute after it is still read.
    func testAQuotedCommaDoesNotEndTheAttribute() {
        let playlist =
            "#EXTM3U\n#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"skd://keys.example/a,b\",\(fairPlay)"
        XCTAssertEqual(SessionKeys.declared(in: playlist), ["skd://keys.example/a,b"])
    }

    /// Another DRM's session key, a `METHOD=NONE` and a media playlist's `EXT-X-KEY` are not this
    /// store's to acquire — the last being the Kotlin trap, recorded rather than worked around.
    func testOtherFormatsNoneAndMediaPlaylistKeysAreNotRead() {
        let playlist = """
            #EXTM3U
            #EXT-X-SESSION-KEY:METHOD=SAMPLE-AES-CTR,URI="data:text/plain;base64,AAAA",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
            #EXT-X-SESSION-KEY:METHOD=NONE,\(fairPlay)
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://keys.example/media",\(fairPlay)
            """
        XCTAssertEqual(SessionKeys.declared(in: playlist), [])
    }
}
