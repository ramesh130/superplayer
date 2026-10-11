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
import SuperPlayerResilience
import XCTest

/// `ErrorClassifier` over `NSError`s per domain and code, each built in the shape AVFoundation hands
/// one over — the Kotlin `ErrorClassifierTest`'s role. The shapes of the HTTP refusals are the ones
/// #419 forced at the loopback origin and recorded: a Foundation error with the
/// `CoreMediaErrorDomain` code naming the status under it, and the error log's entry beside it —
/// naming the request, naming nothing, or absent. `TypedErrorPlaybackTests` is the playing half,
/// which holds those shapes to what the runtime really reports.
final class ErrorClassifierTests: XCTestCase {

    private let playlist = URL(string: "http://127.0.0.1:8080/master.m3u8")!
    private let mediaPlaylist = URL(string: "http://127.0.0.1:8080/media.m3u8")!
    private let segment = URL(string: "http://127.0.0.1:8080/segment1.aac")!

    /// What an item reports for a refused request: `outer` over the CoreMedia code.
    private func refusal(_ coreMediaCode: Int, outer: (String, Int)) -> NSError {
        let cause = NSError(domain: "CoreMediaErrorDomain", code: coreMediaCode)
        return NSError(
            domain: outer.0, code: outer.1, userInfo: [NSUnderlyingErrorKey: cause])
    }

    private func logged(_ uri: URL?, _ code: Int) -> LoggedFailure {
        LoggedFailure(uri: uri, errorStatusCode: code, errorDomain: "CoreMediaErrorDomain")
    }

    private func classify(_ error: NSError, _ log: LoggedFailure?) -> FailureClass {
        ErrorClassifier.classify(error, loggedFailure: log, playlistURL: playlist)
    }

    private let notFound = (NSURLErrorDomain, NSURLErrorFileDoesNotExist)

    // MARK: The failed load — the acceptance's pair, and its controls

    /// A segment refused 404 is logged with no URI (observed): an edge that lost the object.
    func testASegment404IsTheCdnEdge() {
        XCTAssertEqual(
            classify(refusal(-12938, outer: notFound), logged(nil, -12938)),
            .transient(.cdnEdge))
    }

    /// The same error with the media playlist named in the log is a refused playlist, which says
    /// nothing narrower than the network — the control that keeps the pair from being "every 404".
    func testAMediaPlaylist404IsTheNetwork() {
        XCTAssertEqual(
            classify(refusal(-12938, outer: notFound), logged(mediaPlaylist, -12938)),
            .transient(.network))
    }

    /// The multivariant playlist refused leaves no log entry at all (observed).
    func testAMultivariantPlaylist404IsTheNetwork() {
        XCTAssertEqual(classify(refusal(-12938, outer: notFound), nil), .transient(.network))
    }

    /// The asset's own URL is a playlist whatever its path says.
    func testTheAssetsOwnURLIsAPlaylistWhateverItsPath() {
        let asset = URL(string: "http://127.0.0.1:8080/stream?id=7")!
        XCTAssertEqual(
            ErrorClassifier.classify(
                refusal(-12938, outer: notFound), loggedFailure: logged(asset, -12938),
                playlistURL: asset),
            .transient(.network))
    }

    func testASegmentRefused401Or403IsTheCdnEdge() {
        XCTAssertEqual(
            classify(
                refusal(-16840, outer: (NSURLErrorDomain, NSURLErrorUserAuthenticationRequired)),
                logged(segment, -16840)),
            .transient(.cdnEdge))
        XCTAssertEqual(
            classify(
                refusal(-12660, outer: (NSURLErrorDomain, NSURLErrorNoPermissionsToReadFile)),
                logged(segment, -12660)),
            .transient(.cdnEdge))
    }

    /// The object shorter than the range asked for: what AVFoundation reports under its own
    /// `serverIncorrectlyConfigured`, which alone would read as the network.
    func testASegmentRefused416IsASegmentGap() {
        let error = refusal(
            -12939,
            outer: (AVFoundationErrorDomain, AVError.Code.serverIncorrectlyConfigured.rawValue))
        XCTAssertEqual(classify(error, logged(segment, -12939)), .content(.segmentGap))
        XCTAssertEqual(classify(error, logged(mediaPlaylist, -12939)), .transient(.network))
    }

    /// A 5xx and a 410 are the CDN unwell, not this object wrong: no edge class.
    func testASegmentRefused500Or410IsTheNetwork() {
        let unavailable = (NSURLErrorDomain, NSURLErrorResourceUnavailable)
        XCTAssertEqual(
            classify(refusal(-16847, outer: unavailable), logged(nil, -16847)),
            .transient(.network))
        XCTAssertEqual(
            classify(refusal(-12668, outer: unavailable), logged(segment, -12668)),
            .transient(.network))
    }

    /// Only CoreMedia's codes are read as statuses: another domain's code in the HTTP range — a
    /// Cocoa file-write error is 512 — is not a refused segment.
    func testACodeInTheHttpRangeFromAnotherDomainIsNoStatus() {
        XCTAssertEqual(
            classify(
                NSError(domain: NSCocoaErrorDomain, code: NSFileWriteUnknownError),
                LoggedFailure(uri: segment, errorStatusCode: 403, errorDomain: NSCocoaErrorDomain)),
            .transient(.network))
    }

    // MARK: The codes

    func testAPlaylistAVFoundationWouldNotParseIsInvalid() {
        let error = refusal(
            -12642, outer: (AVFoundationErrorDomain, AVError.Code.unknown.rawValue))
        XCTAssertEqual(classify(error, nil), .content(.manifestInvalid))
    }

    func testALivePlaylistThatStoppedChangingIsTheEdge() {
        XCTAssertEqual(
            classify(
                NSError(domain: AVFoundationErrorDomain, code: AVError.Code.unknown.rawValue),
                logged(mediaPlaylist, -12888)),
            .transient(.cdnEdge))
    }

    func testEachAVFoundationRow() {
        let rows: [(AVError.Code, FailureClass)] = [
            (.fileFormatNotRecognized, .fatal(.unsupported)),
            (.formatUnsupported, .fatal(.unsupported)),
            (.failedToParse, .content(.segmentGap)),
            (.noLongerPlayable, .content(.segmentGap)),
            (.decoderNotFound, .device(.decoderInit)),
            (.decoderTemporarilyUnavailable, .device(.decoderTransient)),
            (.decodeFailed, .device(.decoderTransient)),
            (.mediaServicesWereReset, .device(.decoderTransient)),
            (.videoCompositorFailed, .device(.decoderTransient)),
            (.contentIsProtected, .drm(.unsupported)),
            (.contentIsNotAuthorized, .drm(.unsupported)),
            (.applicationIsNotAuthorized, .drm(.unsupported)),
            (.diskFull, .storage(.full)),
            (.contentIsUnavailable, .transient(.network)),
        ]
        for (code, expected) in rows {
            let error = NSError(domain: AVFoundationErrorDomain, code: code.rawValue)
            XCTAssertEqual(ErrorClassifier.classify(error), expected, "\(code)")
        }
    }

    func testTheFoundationAndPosixRows() {
        XCTAssertEqual(
            ErrorClassifier.classify(
                NSError(domain: NSURLErrorDomain, code: NSURLErrorUnsupportedURL)),
            .fatal(.unsupported))
        for code in [
            NSURLErrorTimedOut, NSURLErrorCannotFindHost, NSURLErrorCannotConnectToHost,
            NSURLErrorNetworkConnectionLost, NSURLErrorNotConnectedToInternet,
        ] {
            XCTAssertEqual(
                ErrorClassifier.classify(NSError(domain: NSURLErrorDomain, code: code)),
                .transient(.network), "\(code)")
        }
        XCTAssertEqual(
            ErrorClassifier.classify(NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))),
            .storage(.full))
    }

    /// The cause is the informed error: a decoder failure wrapped in a generic Foundation error
    /// is still the decoder's.
    func testTheCauseDecidesOverItsWrapper() {
        let cause = NSError(
            domain: AVFoundationErrorDomain, code: AVError.Code.decoderNotFound.rawValue)
        let wrapped = NSError(
            domain: NSURLErrorDomain, code: NSURLErrorUnknown,
            userInfo: [NSUnderlyingErrorKey: cause])
        XCTAssertEqual(ErrorClassifier.classify(wrapped), .device(.decoderInit))
    }

    // MARK: Totality

    /// Rule 2: no unknown class, and `Fatal.Unsupported` never the fall-through.
    func testAnythingAtAllIsClassifiedAndNeverAsFatal() {
        struct Unrelated: Error {}
        for error: any Error in [
            Unrelated(), NSError(domain: "com.example", code: 7),
            NSError(domain: AVFoundationErrorDomain, code: -1),
            NSError(domain: "CoreMediaErrorDomain", code: -1),
        ] {
            XCTAssertEqual(ErrorClassifier.classify(error), .transient(.network), "\(error)")
        }
    }

    /// A chain that loops is walked once and classified, not followed forever.
    func testAChainThatLoopsIsClassified() {
        let looping = LoopingError()
        XCTAssertEqual(ErrorClassifier.classify(looping), .transient(.network))
    }
}

/// An `NSError` whose underlying error is itself.
private final class LoopingError: NSError, @unchecked Sendable {
    init() { super.init(domain: "com.example.loop", code: 1, userInfo: nil) }
    required init?(coder: NSCoder) { nil }
    override var userInfo: [String: Any] { [NSUnderlyingErrorKey: self] }
}
