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
import Foundation

/// Content held on the device that a player opens instead of the network: what
/// ``SuperPlayer/Configuration/offlineContent`` takes, and what `SuperPlayerOffline`'s download
/// store is — the iOS reading of the Kotlin player built over the `ContentCache` its downloads
/// write into (ADR-0013 rule 8).
///
/// **Why a seam of core's and not a cache.** No streaming cache exists over `AVPlayer`
/// (`docs/ios-plan.md` §3.4): pinned content is a downloaded `.movpkg`, and a player of it opens
/// the package itself rather than reading segments through a layer. So what a player needs from a
/// download store is one answer per adoption — the asset to play for this content, or none — and
/// a type both products can name has to be core's, for ``LicenceStore``'s reason: the download
/// product depends on core alone (ADR-0013 rule 1).
///
/// **A consumer has no reason to implement one**: the answer core asks is the `package`
/// ``OfflineAssets``, and one written outside the package is never asked. A public marker over a
/// `package` protocol, ``PlaybackDrm``'s shape.
public protocol OfflineContent: AnyObject, Sendable {}

/// The half of ``OfflineContent`` core asks at adoption — `package`, because what it answers is an
/// `AVURLAsset`, which is AVFoundation's and nothing a consumer hands a player.
package protocol OfflineAssets: OfflineContent {

    /// The asset `contentId` plays from on this device, or nil to play from its sources as any
    /// other request does: the downloaded package once a download completed, and the download
    /// task's own asset while one is in progress — which AVFoundation plays from what the task has
    /// written and fetches the rest of (ref: Apple, "Using AVFoundation to play and persist HTTP
    /// Live Streams", *Play offline assets*). Asked on the main actor, once per adoption.
    @MainActor func offlineAsset(for contentId: String) -> AVURLAsset?
}
