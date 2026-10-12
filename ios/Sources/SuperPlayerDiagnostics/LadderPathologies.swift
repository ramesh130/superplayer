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

/// What a ladder of declared variants can get wrong — the HLS readings of the Kotlin
/// `LadderPathologies`, whose KDoc argues why the three are one judgement written once.
///
/// On Android the file is shared by both protocols over Media3 `Format`s; here there is one
/// protocol, so it reads the lint's ``Variant``s directly. It stays a file of its own for the
/// Kotlin reason all the same: **the thresholds are the Kotlin ones, ported with their sources
/// rather than re-derived** (ADR-0015 rule 6's "a threshold copied is a bug", which ADR-0020 rule 8
/// keeps binding across the platforms by stating each number once, in
/// `docs/media-source-doctor.md`, and scoring both doctors against one corpus). A reader comparing
/// the two files finds the same constant beside the same citation.
enum LadderPathologies {

    /// A ladder with any variant that declares no `CODECS`, as one finding — one rather than one
    /// per variant, because the defect is the manifest's.
    ///
    /// ``FindingSeverity/degraded``: a player that cannot tell from the manifest whether it can
    /// decode a variant has to fetch a segment of it to find out. No magnitude: the attribute is
    /// present or it is not.
    static func missingCodecs(_ rungs: [Variant]) -> Finding? {
        guard rungs.contains(where: { $0.codecs == nil }) else { return nil }
        return Finding(pathology: .hlsMissingCodecs, severity: .degraded, magnitude: nil)
    }

    /// The widest step between two adjacent rungs of the declared ladder, where it is wider than a
    /// ladder built to the published guidance ever is.
    ///
    /// Read off the *distinct* declared `BANDWIDTH`s sorted, because two variants at one rate — a
    /// redundant stream on a second host, say — are one rung and not a step of nothing. What a step
    /// costs, which is what the severities grade: a client just short of the upper rung can sustain
    /// only the lower one, so it uses about `1 / step` of its link.
    static func gap(_ rungs: [Variant]) -> Finding? {
        let rates = declaredRates(rungs)
        guard rates.count >= 2 else { return nil }
        // The widest step and the two rungs that make it, so the magnitude names the gap rather
        // than the ladder's endpoints — on a ladder of five those are two different pairs.
        let steps = zip(rates, rates.dropFirst())
        guard
            let widest = steps.max(by: { Double($0.1) / Double($0.0) < Double($1.1) / Double($1.0) }
            )
        else { return nil }
        let step = Double(widest.1) / Double(widest.0)
        guard let severity = severityOfStep(step) else { return nil }
        return Finding(
            pathology: .hlsLadderGap, severity: severity,
            magnitude: "adjacent rungs \(Magnitudes.decimal(step))× apart, "
                + "\(Magnitudes.kilobits(widest.0)) to \(Magnitudes.kilobits(widest.1))")
    }

    /// A rung whose declared peak rate is past what the format it declares can carry.
    ///
    /// **Read from declarations and never from delivery** (ADR-0015 rule 7): the doctor downloads
    /// no segment. So the rule is narrow on purpose and applies only to a rung it can bound — one
    /// whose `CODECS` names AAC and no video format and which declares no `RESOLUTION`. A rung
    /// carrying video is not bounded at all, because a video codec's rate depends on what neither
    /// manifest need declare, and a guess would be the false positive rule 12 punishes. Graded on how
    /// far past the ceiling the declaration sits, and never blocking: the lower rungs still play.
    static func overstatedBitrate(_ rungs: [Variant]) -> Finding? {
        guard let worst = rungs.compactMap(overstatement).max(by: { $0.factor < $1.factor })
        else { return nil }
        return Finding(
            pathology: .hlsOverstatedBitrate,
            severity: worst.factor > overstatementNoAuthoringExplains ? .degraded : .advisory,
            magnitude: "declares \(Magnitudes.kilobits(worst.declared)) where the format it names "
                + "carries at most \(Magnitudes.kilobits(worst.ceiling))")
    }

    /// The distinct declared peak rates of `rungs`, ascending: the ladder as a manifest states it.
    static func declaredRates(_ rungs: [Variant]) -> [Int] {
        Array(Set(rungs.compactMap(\.bandwidthBps).filter { $0 > 0 })).sorted()
    }

    /// How grave a step of `step` between two rates is, or nil where it is a step a ladder built to
    /// the published guidance has.
    static func severityOfStep(_ step: Double) -> FindingSeverity? {
        if step <= ladderStepWithinGuidance { return nil }
        return step > ladderStepCostingMostOfTheLink ? .degraded : .advisory
    }

    /// The AAC object type `codecs` names — `mp4a.40.<n>` (RFC 6381 §3.3) — or nil where it names
    /// no AAC, or more than one audio format.
    static func aacObjectType(_ codecs: String) -> Int? {
        let parts = codecs.trimmingCharacters(in: .whitespaces).split(
            separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0] == "mp4a", parts[1] == "40" else { return nil }
        return Int(parts[2])
    }

    /// What one rung declares and what it could deliver, where the second is knowable and smaller.
    private struct Overstatement {
        let declared: Int
        let ceiling: Int
        var factor: Double { Double(declared) / Double(ceiling) }
    }

    private static func overstatement(_ rung: Variant) -> Overstatement? {
        guard let declared = rung.bandwidthBps, let codecs = rung.codecs else { return nil }
        guard !Codecs.hasVideo(codecs), !rung.declaresResolution else { return nil }
        guard let audio = Codecs.audio(codecs), aacObjectType(audio) != nil else { return nil }
        let ceiling = maxAacBitsPerSecondPerChannel * assumedAudioChannels
        return declared > ceiling ? Overstatement(declared: declared, ceiling: ceiling) : nil
    }

    // ref: Apple Technical Note TN2224, "Best Practices for Creating and Deploying HTTP Live
    // Streaming Media for Apple Devices" — "Adjacent bit rates should be a factor of 1.5 to 2
    // apart". The HLS Authoring Specification has since superseded the note and states no spacing,
    // so this remains the one published number for a ladder's step. Two is its upper end, and the
    // comparison is strict: a ladder built exactly to the guidance is not a gap. The Kotlin
    // `LADDER_STEP_WITHIN_GUIDANCE`.
    static let ladderStepWithinGuidance = 2.0

    // Derived, because no document grades a gap once it is one: eight is two whole rungs missing
    // from a ladder built at the guidance's ceiling (2 × 2 × 2), past which a client between the
    // two rungs is held to under an eighth of what it could sustain. The Kotlin
    // `LADDER_STEP_COSTING_MOST_OF_THE_LINK`, whose comment carries the whole derivation.
    static let ladderStepCostingMostOfTheLink = 8.0

    // spec: ISO/IEC 14496-3 — AAC's bit reservoir bounds one raw data block to 6144 bits per
    // channel, and a block carries 1024 samples, so a channel cannot exceed 6144 × sampleRate / 1024
    // bits per second: 576 kbps at 96 kHz, the highest sampling frequency AAC's index admits. The
    // highest deliberately, so that conforming content is under it twice over. The Kotlin
    // `MAX_AAC_BITS_PER_SECOND_PER_CHANNEL`.
    static let maxAacBitsPerSecondPerChannel = 576_000

    // Derived: the ceiling already assumes AAC's highest rate, so a rung just past it was rounded
    // up from an authoring the manifest does not declare, while twice past it is past every such
    // reading at once. The Kotlin `OVERSTATEMENT_NO_AUTHORING_EXPLAINS`.
    static let overstatementNoAuthoringExplains = 2.0

    // spec: RFC 8216 §4.3.4.2 gives EXT-X-STREAM-INF no way to state a channel count at all, so a
    // variant's is always unknown and stereo is assumed — assuming more would raise the ceiling and
    // let a real overstatement through. The Kotlin `ASSUMED_AUDIO_CHANNELS`, which Media3's variant
    // formats never override either.
    static let assumedAudioChannels = 2
}
