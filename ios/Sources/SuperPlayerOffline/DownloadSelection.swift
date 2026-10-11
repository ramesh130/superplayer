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

/// What a download takes: one rendition under the decision's ceiling and the languages a viewer
/// asked for that the content carries — the Kotlin `DownloadSelection`, ADR-0013 rule 12 and its
/// #241 addendum, as pure functions over what the manifest declared.
///
/// Pure, so the rule is stated once and the store only reads the manifest into it; the store hands
/// what it decides to the download task as an `AVAssetDownloadConfiguration`'s variant qualifier
/// and media selections (`SystemAssetDownloads`).
enum DownloadSelection {

    /// A variant as the multivariant playlist declares it: its peak bitrate (`BANDWIDTH`) and its
    /// height, either absent where the playlist says nothing.
    struct Variant: Equatable {
        let peakBitRateBps: Double?
        let heightPx: Int?
    }

    /// The one variant to download: the highest-bitrate one under `policy`'s ceilings, and the
    /// lowest where none is under them — the Kotlin selection's answer, which is Media3's
    /// `DefaultTrackSelector` with `exceedVideoConstraintsIfNecessary` on, its default: a ceiling
    /// nothing fits still downloads something, the least that breaks it. Nil for no variants at all,
    /// a media playlist handed over directly, where there is nothing to choose.
    ///
    /// A ceiling the playlist gives no figure for refuses nothing: an undeclared height is under any
    /// height ceiling, the Kotlin "unknown refuses nothing". **The display is not a ceiling**, for
    /// the #241 addendum's reason — a height cap on the shorter edge would put every rung of a
    /// vertical clip over it — and the device's decoders are not read here: `AVAssetDownloadTask`
    /// fetches what the qualifier names, and which variants the device can play is AVFoundation's
    /// judgement when a player opens the package. That is the one part of the Kotlin rule this does
    /// not port, and a device is where it shows.
    static func variant(among variants: [Variant], under policy: DownloadSelectionPolicy)
        -> Variant?
    {
        let eligible = variants.filter { variant in
            let underBitrate = policy.maxVideoBitrateBps.map { ceiling in
                variant.peakBitRateBps.map { $0 <= Double(ceiling) } ?? true
            }
            let underHeight = policy.maxVideoHeightPx.map { ceiling in
                variant.heightPx.map { $0 <= ceiling } ?? true
            }
            return underBitrate ?? true && underHeight ?? true
        }
        let rate = { (variant: Variant) in variant.peakBitRateBps ?? 0 }
        guard !eligible.isEmpty else { return variants.min { rate($0) < rate($1) } }
        return eligible.max { rate($0) < rate($1) }
    }

    /// The tags of `offered` that `declared` asks for, in the order declared and each once — a
    /// declared language the content lacks is skipped, never refused, because a language is a
    /// preference and a download in the languages there are is what the viewer still wants (the
    /// #241 addendum). Filtered here first for the Kotlin reason: handed an uncarried language, the
    /// selection would take another in its place.
    static func carried(_ declared: [String], offered: [String]) -> [String] {
        var chosen: [String] = []
        for language in declared {
            for tag in offered where matches(language, tag) && !chosen.contains(tag) {
                chosen.append(tag)
            }
        }
        return chosen
    }

    /// Whether a viewer's `language` names the content's `tag`: equal ignoring case and the `_` a
    /// locale identifier writes for BCP 47's `-`, or one a subtag-wise prefix of the other — `en`
    /// names `en-US` and the reverse, the Kotlin rule, bounded at a subtag so `e` names nothing.
    ///
    /// spec: RFC 5646 §2.1 — a tag is subtags separated by `-`, compared case-insensitively.
    static func matches(_ language: String, _ tag: String) -> Bool {
        let viewer = normalized(language)
        let content = normalized(tag)
        guard !viewer.isEmpty, !content.isEmpty else { return false }
        return viewer == content || content.hasPrefix(viewer + "-")
            || viewer.hasPrefix(content + "-")
    }

    private static func normalized(_ tag: String) -> String {
        tag.lowercased().replacingOccurrences(of: "_", with: "-")
    }
}
