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

/// **The one place a ``CmcdMode`` becomes AVFoundation configuration** — the Kotlin
/// `CmcdBinding.kt`, for CTA-5004.
///
/// Nothing here builds a header, as nothing there does: the SDK owns the wire format and fills the
/// keys as it issues each request, and all this file supplies is whether it should. A hand-built
/// `CMCD-*` header would be a second implementation of a spec the engine already implements, and
/// reaching every request to add one would be ADR-0020 rule 6's in-process proxy.
///
/// **Where the session id comes from is the other direction from Android's.** There core mints the
/// id and hands it to Media3's CMCD configuration; here the SDK fills `sid` from the asset's own
/// `httpSessionIdentifier` and cannot be told otherwise, so core reads ``sessionId(of:)`` off the
/// asset it built and hands *that* to the measurement session (`SessionEdges.swift`). There is
/// still one string and one place it comes from, so the two seams still cannot drift. Two
/// consequences, both the facade's to keep:
///
/// - **One asset per adoption.** Copies of an asset vend an equal identifier
///   (`AVAsset.h`, on `httpSessionIdentifier`), so an asset reused across two adoptions would put
///   two sessions under one `sid`. ``SuperPlayer/setMediaRequest(_:)`` builds a fresh asset every
///   time, the same content's included.
/// - **Only content with an identity is sent CMCD.** An item from ``SuperPlayer/setMediaURL(_:)``
///   opens no session, so its `sid` would join to nothing; it is built with the switch off. The
///   Kotlin binding sends such an item's keys without a `sid`, which AVFoundation cannot do.
///
/// ref: `docs/ios-cmcd-finding.md`, the evidence for both directions, pinned by `CmcdFindingTests`
enum CmcdBinding {

    /// Sets the asset's switch where `mode` asks for headers and `sdkHasSwitch` says the SDK has
    /// it — before the asset is any item's, since the switch is read as the first
    /// request is issued.
    ///
    /// `sdkHasSwitch` is the gate on iOS 18 and tvOS 18, passed rather than read here so the
    /// below-18 branch — which no simulator on a supported Xcode can run — is reachable by a test
    /// on a later runtime. Below 18 the switch is simply not set: nothing is refused, because CMCD
    /// is not a call a consumer makes (ADR-0020 rule 9's measurement shape, the finding's *What
    /// that means for the join*).
    ///
    /// ``CmcdMode/queryParameter`` cannot arrive: ``SuperPlayer/Configuration/setCmcdMode(_:)``
    /// refuses it, and the profile table never answers it. A debug build asserts, so a mode added
    /// to that table by mistake is seen; a release build sends no CMCD rather than trapping, so the
    /// mistake costs the CDN's half and not playback.
    ///
    /// On the main actor because the facade, its only caller, builds every asset there.
    @MainActor
    static func apply(
        _ mode: CmcdMode, to asset: AVURLAsset, sdkHasSwitch: Bool = platformSendsCmcd
    ) {
        assert(mode != .queryParameter, "CmcdMode.queryParameter is refused at configuration")
        guard mode == .requestHeaders, sdkHasSwitch else { return }
        if #available(iOS 18, tvOS 18, *) {
            asset.resourceLoader.sendsCommonMediaClientDataAsHTTPHeaders = true
        }
    }

    /// Whether this runtime's AVFoundation has the switch.
    ///
    /// ref: AVFoundation's `AVAssetResourceLoader.h`, `sendsCommonMediaClientDataAsHTTPHeaders`,
    /// `API_AVAILABLE(ios(18.0), tvos(18.0))`
    static var platformSendsCmcd: Bool {
        if #available(iOS 18, tvOS 18, *) { true } else { false }
    }

    /// The session id `asset`'s requests carry — CMCD's `sid` where the switch is set, and the
    /// `X-Playback-Session-Id` header on every request whether it is or not — which is the
    /// telemetry `sessionId` for the session that plays it.
    ///
    /// spec: CTA-5004 §3.1 caps `sid` at 64 characters and asks for a GUID; a UUID's canonical
    /// 36-character form satisfies both. Uppercase, as Foundation writes it and as the SDK sends it.
    /// ref: AVFoundation's `AVAsset.h`, `httpSessionIdentifier`, `API_AVAILABLE(ios(16.0), tvos(16.0))`
    static func sessionId(of asset: AVURLAsset) -> String {
        asset.httpSessionIdentifier.uuidString
    }
}
