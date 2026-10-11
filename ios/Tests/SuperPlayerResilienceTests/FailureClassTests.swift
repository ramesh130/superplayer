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

import SuperPlayerCore
import SuperPlayerResilience
import XCTest

/// The taxonomy held to its Kotlin twin, row for row: every leaf, its stable name, its three
/// answers, its category and its message key — the Kotlin `FailureClass.kt`'s values written out,
/// because the two platforms write one warehouse's rows and a leaf answering differently here would
/// be a second taxonomy (ADR-0011 rule 1).
final class FailureClassTests: XCTestCase {

    private typealias Row = (
        name: String, retryable: Bool, ceiling: FallbackRung, category: FailureCategory,
        key: String, lower: Bool
    )

    /// The Kotlin `FailureClass.kt`, in its declaration order.
    private let kotlin: [FailureClass: Row] = [
        .transient(.network): (
            "Transient.Network", true, .nextSource, .network, "superplayer_error_network", false
        ),
        .transient(.cdnEdge): (
            "Transient.CdnEdge", true, .nextSource, .network, "superplayer_error_network", false
        ),
        .content(.manifestInvalid): (
            "Content.ManifestInvalid", false, .nextSource, .source,
            "superplayer_error_content_unavailable", false
        ),
        .content(.segmentGap): (
            "Content.SegmentGap", false, .nextSource, .source,
            "superplayer_error_content_unavailable", false
        ),
        .device(.decoderInit): (
            "Device.DecoderInit", false, .nextSource, .decoder, "superplayer_error_device", false
        ),
        .device(.decoderTransient): (
            "Device.DecoderTransient", false, .recreateDecoder, .decoder,
            "superplayer_error_device", false
        ),
        .device(.secureDecoderInit): (
            "Device.SecureDecoderInit", false, .typedError, .decoder,
            "superplayer_error_protection_unavailable", true
        ),
        .drm(.provisioning): (
            "Drm.Provisioning", true, .retrySameUrl, .drm, "superplayer_error_protected_content",
            true
        ),
        .drm(.licenceAcquisition): (
            "Drm.LicenceAcquisition", true, .retrySameUrl, .drm,
            "superplayer_error_protected_content", false
        ),
        .drm(.licenceExpired): (
            "Drm.LicenceExpired", false, .typedError, .drm, "superplayer_error_licence_expired",
            false
        ),
        .drm(.systemError): (
            "Drm.SystemError", false, .typedError, .drm, "superplayer_error_protected_content",
            false
        ),
        .drm(.unsupported): (
            "Drm.Unsupported", false, .nextSource, .drm, "superplayer_error_protected_content", true
        ),
        .drm(.downgradeRefused): (
            "Drm.DowngradeRefused", false, .typedError, .drm,
            "superplayer_error_protection_unavailable", false
        ),
        .storage(.full): (
            "Storage.Full", false, .typedError, .storage, "superplayer_error_storage_full", false
        ),
        .fatal(.unsupported): (
            "Fatal.Unsupported", false, .typedError, .source, "superplayer_error_unsupported", false
        ),
    ]

    func testEveryLeafIsTheKotlinLeaf() {
        XCTAssertEqual(FailureClass.allCases.count, kotlin.count)
        XCTAssertEqual(Set(FailureClass.allCases), Set(kotlin.keys))
        for failure in FailureClass.allCases {
            let row = kotlin[failure]!
            XCTAssertEqual(failure.stableName, row.name)
            XCTAssertEqual(failure.description, row.name)
            XCTAssertEqual(failure.isRetryable, row.retryable, row.name)
            XCTAssertEqual(failure.rungCeiling, row.ceiling, row.name)
            XCTAssertEqual(failure.category, row.category, row.name)
            XCTAssertEqual(failure.userMessageKey, row.key, row.name)
            XCTAssertEqual(failure.lowerSecurityLevelMayHelp, row.lower, row.name)
        }
    }

    /// Rule 3's two deliberate absences: no class buckets `renderer` or `unknown`.
    func testNoClassBucketsRendererOrUnknown() {
        let categories = Set(FailureClass.allCases.map(\.category))
        XCTAssertFalse(categories.contains(.renderer))
        XCTAssertFalse(categories.contains(.unknown))
    }

    /// The ladder's order is the declaration order, and the names are the Kotlin constants', which
    /// is what `SuperPlayerError.rungsTried` carries.
    func testTheRungsAreInLadderOrderUnderTheKotlinNames() {
        XCTAssertEqual(FallbackRung.allCases, FallbackRung.allCases.sorted())
        XCTAssertEqual(
            FallbackRung.allCases.map(\.stableName),
            [
                "RETRY_SAME_URL", "NEXT_HOST", "EXCLUDE_VARIANT", "NEXT_SOURCE", "RECREATE_DECODER",
                "TYPED_ERROR",
            ])
    }
}
