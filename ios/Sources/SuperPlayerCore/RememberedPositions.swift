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

/// Where each piece of content was left, keyed by ``MediaRequest/contentId`` — what
/// ``MediaRequest/StartPosition/resumeFromLastKnown`` reads.
///
/// The Kotlin facade's `lastKnownPositions`, an access-ordered `LinkedHashMap` that drops its
/// eldest entry past the bound: a read counts as a use, as a write does, so the entry dropped is the
/// least recently *used* and not the least recently written. Swift has no ordered map, so the order
/// is a list beside the dictionary; at the bound of 128 a linear move per use costs nothing a
/// replacement of the current item would notice.
struct RememberedPositions {

    /// The most entries kept; the next one written drops the least recently used.
    let bound: Int

    private var positions: [String: Int64] = [:]

    /// Content ids, least recently used first.
    private var order: [String] = []

    init(bound: Int) {
        precondition(bound > 0, "A bound of \(bound) remembers nothing")
        self.bound = bound
    }

    /// Records `positionMs` for `contentId` as its most recent use, dropping the least recently used
    /// entry if that takes the memory past ``bound``.
    mutating func remember(_ positionMs: Int64, for contentId: String) {
        positions[contentId] = positionMs
        touch(contentId)
        if order.count > bound {
            positions[order.removeFirst()] = nil
        }
    }

    /// The position remembered for `contentId`, counting the read as a use — the access order the
    /// Kotlin map is built with.
    mutating func position(for contentId: String) -> Int64? {
        guard let position = positions[contentId] else { return nil }
        touch(contentId)
        return position
    }

    /// How many content ids are remembered.
    var count: Int { order.count }

    private mutating func touch(_ contentId: String) {
        if let index = order.firstIndex(of: contentId) { order.remove(at: index) }
        order.append(contentId)
    }
}
