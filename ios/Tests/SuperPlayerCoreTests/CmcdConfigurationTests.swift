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

import AVFoundation
import XCTest

// `@testable` for `StaticCmcdPolicy` and `CmcdBinding`, both internal: the table and the gate are
// read directly because what they decide on a runtime below 18 cannot be played on any simulator a
// supported Xcode ships, so the below-18 branch is reached here by argument rather than by runtime.
@testable import SuperPlayerCore

/// What a configuration accepts and refuses for CMCD (#446), and what the binding does with each
/// mode — the half of the CMCD claim that needs no playback. `CmcdPlaybackTests`, in
/// `SuperPlayerTelemetryTests`, is the half on the wire.
@MainActor
final class CmcdConfigurationTests: XCTestCase {

    /// The query parameter is refused typed, naming the mode, and leaves the configuration as it
    /// was (ADR-0020 rule 9's first shape).
    func testAQueryParameterIsRefusedTypedAndNamesTheMode() {
        var configuration = SuperPlayer.Configuration()

        XCTAssertThrowsError(try configuration.setCmcdMode(.queryParameter)) { error in
            XCTAssertEqual(error as? CmcdModeUnsupportedError, .init(mode: .queryParameter))
            XCTAssertTrue("\(error)".contains("queryParameter"), "\(error)")
        }
        XCTAssertNil(configuration.cmcdMode, "a refused mode is not recorded")
    }

    /// The control: the two modes AVFoundation has a mechanism for are accepted, so the refusal
    /// above is of one mode and not of the setter.
    func testHeadersAndDisabledAreAccepted() throws {
        var configuration = SuperPlayer.Configuration()

        try configuration.setCmcdMode(.disabled)
        XCTAssertEqual(configuration.cmcdMode, .disabled)
        try configuration.setCmcdMode(.requestHeaders)
        XCTAssertEqual(configuration.cmcdMode, .requestHeaders)
    }

    /// Every profile emits headers when nothing was chosen — the Kotlin `StaticCmcdPolicy`'s rows.
    func testEveryProfileDefaultsToRequestHeaders() {
        for profile in PlaybackProfile.allCases {
            XCTAssertEqual(
                StaticCmcdPolicy.defaultMode(for: profile), .requestHeaders, "\(profile)")
        }
    }

    /// The binding sets the switch for headers on a platform that has it, and leaves it alone for
    /// ``CmcdMode/disabled`` and — the below-18 branch — where the platform has none. Reading the
    /// switch needs 18 itself, so this skips on 17; the branch it covers is the absence of a call,
    /// which is what a below-18 runtime would run.
    func testTheSwitchIsSetOnlyForHeadersOnAPlatformThatHasIt() throws {
        guard #available(iOS 18, tvOS 18, *) else {
            throw XCTSkip("sendsCommonMediaClientDataAsHTTPHeaders is read from iOS 18 and tvOS 18")
        }
        let url = try XCTUnwrap(URL(string: "http://127.0.0.1:9/master.m3u8"))
        func switchAfter(_ mode: CmcdMode, platformSendsCmcd: Bool) -> Bool {
            let asset = AVURLAsset(url: url)
            CmcdBinding.apply(mode, to: asset, platformSendsCmcd: platformSendsCmcd)
            return asset.resourceLoader.sendsCommonMediaClientDataAsHTTPHeaders
        }

        XCTAssertTrue(switchAfter(.requestHeaders, platformSendsCmcd: true))
        XCTAssertFalse(switchAfter(.disabled, platformSendsCmcd: true))
        XCTAssertFalse(switchAfter(.requestHeaders, platformSendsCmcd: false), "below 18")
        XCTAssertTrue(CmcdBinding.platformSendsCmcd, "this runtime is 18 or later")
    }

    /// The session id is the asset's own identifier, and two assets of one URL have two — the fact
    /// a fresh asset per adoption rests on.
    func testTheSessionIdIsTheAssetsAndEachAssetHasItsOwn() throws {
        let url = try XCTUnwrap(URL(string: "http://127.0.0.1:9/master.m3u8"))
        let first = AVURLAsset(url: url)
        let second = AVURLAsset(url: url)

        XCTAssertEqual(CmcdBinding.sessionId(of: first), first.httpSessionIdentifier.uuidString)
        XCTAssertNotEqual(CmcdBinding.sessionId(of: first), CmcdBinding.sessionId(of: second))
    }
}
