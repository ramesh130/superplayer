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

/// `SuperPlayerDRM`'s entry point: what ``SuperPlayer/Configuration/drm`` is handed — the Kotlin
/// `Drm`, with FairPlay where it has Widevine.
///
/// ```swift
/// var configuration = SuperPlayer.Configuration()
/// configuration.drm = Drm.fairPlay(
///     FairPlayConfig(certificateURL: certificate, licenceURL: keyServer))
/// configuration.resilience = Resilience.standard(headers: Tokens())
/// let player = SuperPlayer(configuration: configuration)
/// ```
///
/// A factory rather than a constructor for the reason `Resilience.standard(headers:)` is one: what it
/// returns is a ``PlaybackDrm`` — a core type naming no AVFoundation class — while what it builds is
/// core's `package` `ContentProtecting`, which is how an `AVContentKeySession` per player reaches the
/// facade without appearing in a signature a consumer can see (ADR-0012 rule 3's shape; there is no
/// engine slot on iOS, `docs/ios-plan.md` §3.6).
///
/// Everything this product does besides naming the servers — when the certificate is fetched, how
/// the key request is composed, how often a refused licence is asked for again and how long between —
/// is correctness or the policy's ``RetryPolicy/licence``, and not a caller's to vary here.
public enum Drm {

    /// FairPlay Streaming, against the servers `config` names.
    ///
    /// One value may be handed to many players: nothing returned here holds a player's state, and the
    /// key session, the fetched certificate and the credential a refusal refreshed are made per
    /// player, as the player is.
    public static func fairPlay(_ config: FairPlayConfig) -> any PlaybackDrm {
        FairPlayDrm(config: config, keySession: { SystemFairPlayKeySession() })
    }

    /// FairPlay whose key session is `keySession`'s — the one seam `check` stands a key session in
    /// at, because the simulator has no FairPlay (ADR-0021 rule 5, `docs/testing.md`'s *FairPlay on
    /// iOS*). Called once per player.
    package static func fairPlay(
        _ config: FairPlayConfig,
        keySession: @escaping @MainActor @Sendable () -> any FairPlayKeySession
    ) -> any PlaybackDrm {
        FairPlayDrm(config: config, keySession: keySession)
    }
}

/// What a player needs in order to acquire a FairPlay licence: the two addresses the app's key
/// server publishes — the Kotlin `WidevineConfig`, with the certificate FairPlay adds.
///
/// **The servers are named by configuration only**, for the Kotlin `forceDefaultLicenseUri = true`
/// reason (`WidevineDrm.kt`): a licence server a *playlist* can name is one whoever served the
/// playlist can name, and this type's whole claim is that the server is the app's (ADR-0012 rule 1).
/// The `skd://` URI an `EXT-X-KEY` carries identifies the key and is sent to the server inside the
/// request; it is never fetched and never read as an address.
///
/// **It is the player's and not the request's** (ADR-0012 rule 1): ``SuperPlayer/Configuration/drm``
/// is fixed for a player's lifetime, and ``MediaRequest/sources`` stays one piece of content in more
/// than one place, never a second entitlement.
///
/// **The credential is not here**, for the Kotlin reason (ADR-0012 rule 2): it is the one
/// ``HeaderProvider`` a player is configured with through `Resilience.standard(headers:)`, which a
/// licence refused 401 or 403 asks once — the same provider that repairs a refused segment.
///
/// There is no security-level permission, because FairPlay has no levels to permit
/// (`docs/ios-plan.md` §3.6): what it has instead is an output-protection reading, reported to
/// telemetry and never acted on.
public struct FairPlayConfig: Sendable, Hashable {

    /// Where the application certificate is fetched from, with a `GET`, once per player.
    ///
    /// spec: FairPlay Streaming composes a key request from the content key's identifier and the
    /// application certificate the key server's operator issued; the certificate is public, and is
    /// conventionally published at an address of its own.
    /// ref: https://developer.apple.com/documentation/avfoundation/avcontentkeyrequest/makestreamingcontentkeyrequestdata(forapp:contentidentifier:options:completionhandler:)
    public let certificateURL: URL

    /// Where a licence is asked for: the URL the key request — the SPC — is `POST`ed to, its body the
    /// request's bytes as the device composed them and the response's body the licence — the CKC —
    /// as the server issued it, with `Content-Type: application/octet-stream` both ways. A server that
    /// wraps either in a format of its own is answered by a proxy the app runs, not by a hook here.
    public let licenceURL: URL

    public init(certificateURL: URL, licenceURL: URL) {
        self.certificateURL = certificateURL
        self.licenceURL = licenceURL
    }
}

/// What ``Drm/fairPlay(_:)`` makes: core's `package` ``ContentProtecting`` filled with a FairPlay
/// session per player — the Kotlin internal `WidevineDrm`.
struct FairPlayDrm: ContentProtecting {

    let config: FairPlayConfig
    let keySession: @MainActor @Sendable () -> any FairPlayKeySession

    @MainActor
    func protectionSession(host: any ProtectionHost) -> any ProtectionSession {
        FairPlayProtection(
            keySession: keySession(),
            exchange: LicenceExchange(config: config, provider: host.headerProvider), host: host)
    }
}
