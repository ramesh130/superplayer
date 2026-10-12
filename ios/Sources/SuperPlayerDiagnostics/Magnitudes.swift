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

/// How a ``Finding/magnitude`` spells a number — the Kotlin `Magnitudes.kt`, spelled the same, so
/// that a report from either platform reads the same magnitude the same way and a support engineer
/// holding both sees one fact rather than two.
enum Magnitudes {

    /// `value` to one decimal place, with a trailing `.0` dropped: `48`, `1.4`, `20`.
    static func decimal(_ value: Double) -> String {
        let text = String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), value)
        return text.hasSuffix(".0") ? String(text.dropLast(2)) : text
    }

    /// `bitsPerSecond` as the unit a ladder is discussed in, rounded half up as the Kotlin
    /// `roundToInt` rounds a positive value.
    static func kilobits(_ bitsPerSecond: Int) -> String {
        "\(Int((Double(bitsPerSecond) / 1_000).rounded(.toNearestOrAwayFromZero))) kbps"
    }

    /// `seconds` as the unit a segment and a window are discussed in.
    static func seconds(_ seconds: Double) -> String { "\(decimal(seconds)) s" }
}
