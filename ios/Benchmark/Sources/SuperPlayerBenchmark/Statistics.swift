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

/// A sample of runs, summarised — and never summarised to one number. The Kotlin `Distribution`.
///
/// `PRD.md` §6: **report variance, and not only means.** Enforced by the type rather than by whoever
/// writes the table: there is no way to get a mean out of this without the spread that came with
/// it, and the report prints the two together because it has no other option.
struct Distribution: Sendable, Equatable {
    let count: Int
    let mean: Double
    /// The sample standard deviation; ``of(_:)`` says why the sample one.
    let standardDeviation: Double
    let min: Double
    let p50: Double
    let p95: Double
    let max: Double

    /// The values formatted for a report cell: the mean, its spread, and the tail.
    func summary(_ format: (Double) -> String) -> String {
        "\(format(mean)) ± \(format(standardDeviation)) (p50 \(format(p50)), p95 \(format(p95)), n=\(count))"
    }

    /// Summarises `values`, or nil when there are none — never a distribution of zeroes, since a
    /// cell where every run failed to start has no time to first frame, and a zero would be the
    /// best-looking number in the column.
    static func of(_ values: [Double]) -> Distribution? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        let mean = sorted.reduce(0, +) / Double(sorted.count)
        // The *sample* standard deviation (n−1): these runs are a sample of the runs this
        // configuration could produce, which is the premise of reporting a spread at all. With one
        // run there is no spread to estimate, and zero is the honest answer.
        // ref: NIST/SEMATECH e-Handbook of Statistical Methods §1.3.5.6, sample standard deviation.
        // https://www.itl.nist.gov/div898/handbook/
        let variance =
            sorted.count < 2
            ? 0 : sorted.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(sorted.count - 1)
        return Distribution(
            count: sorted.count, mean: mean, standardDeviation: variance.squareRoot(),
            min: sorted.first!, p50: percentile(sorted, 50), p95: percentile(sorted, 95),
            max: sorted.last!)
    }

    /// The `percentile`th percentile of an already sorted `sorted`, by the nearest-rank method.
    ///
    /// ref: NIST/SEMATECH e-Handbook of Statistical Methods §7.2.5.2 — the value at ordinal
    /// `ceil(p/100 × n)`. Nearest rank always returns a value that occurred, so a p95 is something
    /// a reader can find in the raw traces; and two harnesses quoting "p95" under different
    /// definitions are quoting different numbers, which is `PRD.md` F8 arriving inside a benchmark.
    static func percentile(_ sorted: [Double], _ percentile: Double) -> Double {
        precondition(!sorted.isEmpty, "No values to take a percentile of")
        let rank = Int((percentile / 100 * Double(sorted.count)).rounded(.up))
        return sorted[Swift.min(Swift.max(rank, 1), sorted.count) - 1]
    }
}
