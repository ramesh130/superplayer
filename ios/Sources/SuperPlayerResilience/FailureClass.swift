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

/// The rungs of the fallback ladder, in the order they are climbed (ADR-0011 rule 7) — the Kotlin
/// `FallbackRung`, case for case.
///
/// Declaration order *is* the ladder order, and `Comparable` reads it, so no second table says which
/// rung is higher. All six are declared although rungs 1 to 3 are not performed on Apple platforms
/// (ADR-0020 rule 6: Apple retries inside the engine and nothing configures it), because a
/// ``FailureClass/rungCeiling`` is a fact about the failure and not about this platform's ladder —
/// a class whose ceiling is rung 1 says "only asking again could help" on both platforms, and on
/// this one AVFoundation is what asks.
public enum FallbackRung: Int, Sendable, Hashable, CaseIterable, Comparable {

    /// Rung 1 — the same URL again, after a backoff. AVFoundation's own on Apple platforms.
    case retrySameUrl = 1

    /// Rung 2 — another host. Content steering's on Apple platforms, which is the server's.
    case nextHost

    /// Rung 3 — the failing variant excluded. AVFoundation's own on Apple platforms.
    case excludeVariant

    /// Rung 4 — the next entry of `MediaRequest.sources`, re-adopted at the position reached.
    case nextSource

    /// Rung 5 — the player rebuilt at the position it held, the Kotlin recreated decoder.
    case recreateDecoder

    /// Rung 6 — a typed, actionable error, which every failure reaches if nothing below it worked.
    case typedError

    /// The Kotlin enum constant's name — `"RETRY_SAME_URL"` and the rest — which is what
    /// `SuperPlayerError.rungsTried` carries on both platforms, so a warehouse reads one vocabulary.
    public var stableName: String {
        switch self {
        case .retrySameUrl: "RETRY_SAME_URL"
        case .nextHost: "NEXT_HOST"
        case .excludeVariant: "EXCLUDE_VARIANT"
        case .nextSource: "NEXT_SOURCE"
        case .recreateDecoder: "RECREATE_DECODER"
        case .typedError: "TYPED_ERROR"
        }
    }

    public static func < (lhs: FallbackRung, rhs: FallbackRung) -> Bool {
        lhs.rawValue < rhs.rawValue
    }
}

/// What a failure *is*, in SuperPlayer's own vocabulary: the taxonomy ``ErrorClassifier`` assigns
/// and the only one in the library (ADR-0011 rule 1, `PRD.md` §3.3) — the Kotlin sealed
/// `FailureClass`, leaf for leaf, with the same stable names, the same answers and the same message
/// keys, so a failure on either platform lands in the same warehouse row.
///
/// The Kotlin sealed class hierarchy is a nested enum here, which is Swift's closed hierarchy: a
/// `switch` over it is exhaustive, so a leaf added later is a compile error at every place that
/// acts on one rather than a silent fall-through. `.transient(.cdnEdge)` is the Kotlin
/// `FailureClass.Transient.CdnEdge`.
///
/// Public, and naming no AVFoundation type: the evidence a class is derived from is AVFoundation's —
/// an error's domain and code, the error log's entry — and all of it stays behind
/// ``ErrorClassifier/classify(_:loggedFailure:playlistURL:)``; what leaves is the conclusion.
///
/// Each class answers the questions ADR-0011 rule 1 requires of it, and the Kotlin KDoc argues each
/// answer; they are ported unchanged rather than re-argued, because the answers are about the
/// failure and not the engine:
///
/// - ``isRetryable`` — whether the same bytes again can change the outcome (the Kotlin `retryable`,
///   named as a Swift Boolean property reads).
/// - ``rungCeiling`` — the highest rung the ladder may climb for this class: a ceiling, never an
///   itinerary.
/// - ``stableName`` — the string a log line, a bug report and a warehouse row all carry, written out
///   rather than derived from a type name.
/// - ``lowerSecurityLevelMayHelp`` — the Kotlin #225 question, carried for parity: a FairPlay device
///   has one security level, so nothing on Apple platforms acts on it, but a leaf that answers it
///   differently on the two platforms would be two taxonomies.
///
/// ``category`` is rule 3's one-to-one table into telemetry's coarse ``FailureCategory``, and
/// ``userMessageKey`` `PRD.md` §3.2's column, both derived here and nowhere else.
///
/// **Which leaves are reachable on Apple platforms** is the classifier's to say, and its doc comment
/// lists them; every leaf is declared whether or not this platform's evidence reaches it, because
/// the taxonomy is shared and a leaf missing here would be a class a dashboard cannot group across
/// platforms.
public enum FailureClass: Sendable, Hashable, CustomStringConvertible {

    /// The bytes did not arrive, and asking again may be all it takes. Both leaves reach rung 4.
    case transient(Transient)

    /// The bytes arrived and are not what they were described as. Never retryable; rung 4.
    case content(Content)

    /// This device could not play it, which is a different question from whether the content is.
    case device(Device)

    /// Protection, not delivery. FairPlay's on Apple platforms: `LicenceAcquisition` and
    /// `SystemError` are reached from #422's exchange, and `ErrorClassifier` says which wait.
    case drm(Drm)

    /// The device's storage, not the content, the network or the decoder.
    case storage(Storage)

    /// The engine has named the content unsupported, and nothing the ladder does changes that.
    case fatal(Fatal)

    /// The Kotlin `FailureClass.Transient`'s leaves.
    public enum Transient: Sendable, Hashable, CaseIterable {
        /// A connection, a timeout, a DNS failure, a status with nothing more specific behind it.
        case network
        /// A segment the edge refused or no longer holds while the playlist is fine — the classic
        /// expired token or edge miss (`PRD.md` §3.3).
        case cdnEdge
    }

    /// The Kotlin `FailureClass.Content`'s leaves.
    public enum Content: Sendable, Hashable, CaseIterable {
        /// The playlist cannot be acted on: malformed, or describing what no player can sit inside.
        case manifestInvalid
        /// The media the playlist promised is not there as promised.
        case segmentGap
    }

    /// The Kotlin `FailureClass.Device`'s leaves.
    public enum Device: Sendable, Hashable, CaseIterable {
        /// No decoder or output could be had for this content; another variant or source may fit.
        case decoderInit
        /// A decoder or output that was working stopped: rung 5 is the remedy and the ceiling.
        case decoderTransient
        /// A secure decoder was chosen and would not start (#226). Android's evidence for it — a
        /// `DecoderInitializationException` whose codec is the secure one — has no AVFoundation
        /// counterpart, so no iOS failure reaches this leaf; it is declared for the shared taxonomy.
        case secureDecoderInit
    }

    /// The Kotlin `FailureClass.Drm`'s leaves.
    public enum Drm: Sendable, Hashable, CaseIterable {
        /// The device could not be provisioned. The certificate is Apple's on this platform
        /// (ADR-0020 rule 6), so nothing here reaches it.
        case provisioning
        /// A licence could not be acquired: the round trip did not produce one.
        case licenceAcquisition
        /// A licence that was issued has run out.
        case licenceExpired
        /// The device's protection stack failed, with no entitlement implicated.
        case systemError
        /// The scheme, the device or the operation is refused.
        case unsupported
        /// The device cannot honour the protection level, and the server permitted no lower one.
        case downgradeRefused
    }

    /// The Kotlin `FailureClass.Storage`'s leaf.
    public enum Storage: Sendable, Hashable, CaseIterable {
        /// The volume could not hold the next bytes a download was to write (#244).
        case full
    }

    /// The Kotlin `FailureClass.Fatal`'s leaf.
    public enum Fatal: Sendable, Hashable, CaseIterable {
        /// A container, a playlist or a format the engine says it does not support.
        case unsupported
    }

    /// Every class, in the Kotlin declaration order — what a test walks to hold each row to its
    /// Kotlin twin, built from the leaves' own `allCases` so a leaf added is a leaf listed.
    public static var allCases: [FailureClass] {
        Transient.allCases.map(FailureClass.transient) + Content.allCases.map(FailureClass.content)
            + Device.allCases.map(FailureClass.device) + Drm.allCases.map(FailureClass.drm)
            + Storage.allCases.map(FailureClass.storage) + Fatal.allCases.map(FailureClass.fatal)
    }

    /// The name this class is known by outside the process — the Kotlin `stableName`, string for
    /// string, because it is a warehouse's grouping key and both platforms write the same rows.
    public var stableName: String {
        switch self {
        case .transient(.network): "Transient.Network"
        case .transient(.cdnEdge): "Transient.CdnEdge"
        case .content(.manifestInvalid): "Content.ManifestInvalid"
        case .content(.segmentGap): "Content.SegmentGap"
        case .device(.decoderInit): "Device.DecoderInit"
        case .device(.decoderTransient): "Device.DecoderTransient"
        case .device(.secureDecoderInit): "Device.SecureDecoderInit"
        case .drm(.provisioning): "Drm.Provisioning"
        case .drm(.licenceAcquisition): "Drm.LicenceAcquisition"
        case .drm(.licenceExpired): "Drm.LicenceExpired"
        case .drm(.systemError): "Drm.SystemError"
        case .drm(.unsupported): "Drm.Unsupported"
        case .drm(.downgradeRefused): "Drm.DowngradeRefused"
        case .storage(.full): "Storage.Full"
        case .fatal(.unsupported): "Fatal.Unsupported"
        }
    }

    public var description: String { stableName }

    /// Whether retrying the same bytes can change the outcome. Transient failures, and the two
    /// protection round trips a later attempt commonly succeeds at; nothing else.
    public var isRetryable: Bool {
        switch self {
        case .transient: true
        case .drm(.provisioning), .drm(.licenceAcquisition): true
        case .content, .device, .drm, .storage, .fatal: false
        }
    }

    /// The highest rung the ladder may climb for this class — the Kotlin ceilings, each argued at
    /// its Kotlin leaf.
    public var rungCeiling: FallbackRung {
        switch self {
        case .transient, .content: .nextSource
        case .device(.decoderInit): .nextSource
        case .device(.decoderTransient): .recreateDecoder
        case .device(.secureDecoderInit): .typedError
        case .drm(.provisioning), .drm(.licenceAcquisition): .retrySameUrl
        case .drm(.unsupported): .nextSource
        case .drm(.licenceExpired), .drm(.systemError), .drm(.downgradeRefused): .typedError
        case .storage, .fatal: .typedError
        }
    }

    /// The coarse bucket telemetry reports, derived here and nowhere else (ADR-0011 rule 3). No row
    /// reaches ``FailureCategory/renderer`` or ``FailureCategory/unknown``, for the Kotlin reasons:
    /// the output pipeline's failures are the decoder rungs' to remedy, and nothing is unclassified.
    public var category: FailureCategory {
        switch self {
        case .transient: .network
        case .content, .fatal: .source
        case .device: .decoder
        case .drm: .drm
        case .storage: .storage
        }
    }

    /// The key an app looks a viewer-facing string up by, carried out on ``SuperPlayerError``
    /// (`PRD.md` §3.2) — a key and never a message, since the library has no locale.
    public var userMessageKey: String {
        switch self {
        case .transient: Self.networkMessageKey
        case .content: Self.contentMessageKey
        case .device(.secureDecoderInit), .drm(.downgradeRefused):
            Self.protectionUnavailableMessageKey
        case .device: Self.deviceMessageKey
        case .drm(.licenceExpired): Self.licenceExpiredMessageKey
        case .drm: Self.drmMessageKey
        case .storage: Self.storageFullMessageKey
        case .fatal: Self.unsupportedMessageKey
        }
    }

    /// Whether a licence issued at a lower security level could plausibly succeed where this
    /// failed (ADR-0012 rule 11's #225 addendum): the Kotlin three leaves, and no others.
    public var lowerSecurityLevelMayHelp: Bool {
        switch self {
        case .drm(.provisioning), .drm(.unsupported), .device(.secureDecoderInit): true
        default: false
        }
    }

    /// The bytes did not arrive: something to try again, and possibly the viewer's own link.
    public static let networkMessageKey = "superplayer_error_network"

    /// The bytes arrived and are wrong: this programme is broken, and retrying will not mend it.
    public static let contentMessageKey = "superplayer_error_content_unavailable"

    /// This device could not play it, which is a different sentence from the content being bad.
    public static let deviceMessageKey = "superplayer_error_device"

    /// Protection, not delivery: the sentence an app words around its own entitlements.
    public static let drmMessageKey = "superplayer_error_protected_content"

    /// The entitlement ran out rather than failed — the one protection failure with a remedy a
    /// viewer can be offered (`PRD.md` §3.2, #206).
    public static let licenceExpiredMessageKey = "superplayer_error_licence_expired"

    /// The content may not be shown on this device, and asking again will not change that (#208).
    public static let protectionUnavailableMessageKey = "superplayer_error_protection_unavailable"

    /// The device has no room for this download: the viewer's own storage is the remedy (#244).
    public static let storageFullMessageKey = "superplayer_error_storage_full"

    /// The engine has named the content unplayable here: the one message that offers no remedy.
    public static let unsupportedMessageKey = "superplayer_error_unsupported"
}
