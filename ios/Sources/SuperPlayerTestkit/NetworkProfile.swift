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

/// The six network profiles `PRD.md` Part 6 names, each as a ``ThroughputTrace``, and ``ethernet``,
/// the seventh — the port of `superplayer-testkit`'s `NetworkProfile`, with the same constants and
/// the same public sources (`docs/ios-plan.md` §3.2).
///
/// **Every number is the Kotlin one and carries its source here too**, per the clean-room rule in
/// `CONTRIBUTING.md`; `docs/throughput-traces.md`'s *The seven profiles* is the summary. Round-trip
/// time is 0 everywhere except ``highLatency``, so that latency is the one variable that profile
/// changes, and ``ethernet`` is ``stableWifi``'s rate, so that the transport is the one variable it
/// changes — a variable that on iOS is reported rather than switched (``NetworkTransport``). Each
/// profile's trace is written out under `ios/Tests/Fixtures/throughput-traces/` by the Kotlin side
/// and compared with this one's, so the two cannot drift apart.
public enum NetworkProfile: String, Sendable, Hashable, CaseIterable {

    /// Stable WiFi at 20 Mbit/s: enough for the top rung of any ladder, and never varying.
    case stableWifi = "STABLE_WIFI"

    /// Congested WiFi averaging 3 Mbit/s and swinging ±50% from one second to the next.
    case congestedWifi = "CONGESTED_WIFI"

    /// LTE at 5 Mbit/s that drops out for two seconds, every ``lteDropoutPeriodMs``.
    case lteWithDropouts = "LTE_WITH_DROPOUTS"

    /// 3G at 1 Mbit/s: the bottom of the ladder and little else.
    case threeG = "THREE_G"

    /// Stable WiFi until ``handoverAtMs``, then LTE for good — a transport change, not only a rate
    /// change, and one that holds rather than loops back.
    case wifiToCellularHandover = "WIFI_TO_CELLULAR_HANDOVER"

    /// A 600 ms round trip, at ``stableWifi``'s bandwidth so that latency is the only thing that
    /// differs. Every request waits one round trip before its first byte.
    case highLatency = "HIGH_LATENCY"

    /// A wired link at ``stableWifi``'s bandwidth, so that the transport is the only thing that
    /// differs: a television on Ethernet, which `PRD.md` §3.8 says should not behave like a phone on
    /// WiFi.
    case ethernet = "ETHERNET"

    /// When ``wifiToCellularHandover`` leaves WiFi.
    ///
    /// ref: derivation (CONTRIBUTING.md rule 4), not a claim about a network: `PRD.md` Part 6 asks
    /// for the handover *mid-playback*, and ten seconds is five 2-second segments in — past startup,
    /// and early enough that a minute of content has most of its loads still ahead.
    public static let handoverAtMs: Int64 = 10_000

    /// How often ``lteWithDropouts`` drops out.
    ///
    /// ref: derivation (CONTRIBUTING.md rule 4), not a claim about a network: twenty seconds is ten
    /// 2-second segments between dropouts, so each one's effect on a session is separable from the
    /// last one's.
    public static let lteDropoutPeriodMs: Int64 = 20_000

    /// The profile as a trace, built the way the Kotlin constant is.
    public var trace: ThroughputTrace {
        switch self {
        case .stableWifi:
            return Self.built(
                ThroughputTrace.Builder()
                    .add(durationMs: oneSecondMs, bandwidthBps: stableWifiBps, transport: .wifi))
        case .congestedWifi:
            return Self.congestedWifiTrace
        case .lteWithDropouts:
            return Self.built(
                ThroughputTrace.Builder()
                    .add(
                        durationMs: Self.lteDropoutPeriodMs - lteDropoutMs, bandwidthBps: lteBps,
                        transport: .cellular
                    )
                    .add(durationMs: lteDropoutMs, bandwidthBps: 0, transport: .cellular))
        case .threeG:
            return Self.built(
                ThroughputTrace.Builder()
                    .add(durationMs: oneSecondMs, bandwidthBps: threeGBps, transport: .cellular))
        case .wifiToCellularHandover:
            return Self.built(
                ThroughputTrace.Builder()
                    .add(
                        durationMs: Self.handoverAtMs, bandwidthBps: stableWifiBps,
                        transport: .wifi
                    )
                    // No outage between the two, for the Kotlin reason: how long one lasts is the
                    // HTTP stack's, and a number here would be a guess about a decision not made.
                    .add(durationMs: oneSecondMs, bandwidthBps: lteBps, transport: .cellular)
                    .holdAtEnd())
        case .highLatency:
            return Self.built(
                ThroughputTrace.Builder()
                    // A geostationary satellite link is what a 600 ms round trip is in the field,
                    // reached over the home WiFi the device is connected to.
                    .add(
                        durationMs: oneSecondMs, bandwidthBps: stableWifiBps, transport: .wifi,
                        rttMs: highLatencyRttMs))
        case .ethernet:
            return Self.built(
                ThroughputTrace.Builder()
                    // ref: derivation (CONTRIBUTING.md rule 4), the Kotlin ETHERNET's: a cable has
                    // no radio hop to fade or contend, so one constant stretch, at STABLE_WIFI's rate
                    // so that only the transport differs.
                    .add(durationMs: oneSecondMs, bandwidthBps: stableWifiBps, transport: .ethernet)
            )
        }
    }

    /// Every profile is a constant trace that builds; one that did not would be this file wrong.
    private static func built(_ builder: ThroughputTrace.Builder) -> ThroughputTrace {
        do { return try builder.build() } catch { preconditionFailure("\(error)") }
    }

    /// Congested WiFi as pairs of seconds, each pair `mean + d` and `mean − d` in a random order —
    /// antithetic pairs, so the mean is *exactly* the 3 Mbit/s `PRD.md` names.
    ///
    /// The draws are `java.util.Random`'s with the Kotlin seed, through ``JavaRandom``: the same
    /// sequence the JVM produces, which is what lets the Kotlin and Swift traces be one fixture.
    private static let congestedWifiTrace: ThroughputTrace = {
        var random = JavaRandom(seed: congestedWifiSeed)
        var builder = ThroughputTrace.Builder()
        for _ in 0..<congestedWifiPairs {
            let deviation = Int64(
                random.nextDouble() * Double(congestedWifiMeanBps) * congestedWifiSwing)
            let high = congestedWifiMeanBps + deviation
            let low = congestedWifiMeanBps - deviation
            let (first, second) = random.nextBoolean() ? (high, low) : (low, high)
            builder = builder.add(
                durationMs: congestedWifiSampleMs, bandwidthBps: first, transport: .wifi)
            builder = builder.add(
                durationMs: congestedWifiSampleMs, bandwidthBps: second, transport: .wifi)
        }
        return built(builder)
    }()
}

// ref: derivation (CONTRIBUTING.md rule 4). The length a constant-rate profile's one stretch is
// written at; a looping trace at one rate delivers identically at any stretch length.
private let oneSecondMs: Int64 = 1_000

// ref: Netflix Help Center, "Internet connection speed recommendations" (help.netflix.com/node/306):
// 15 Mbit/s or more for 4K/UHD. 20 clears it with room left over, so a stable WiFi link is one on
// which the network is never the reason a rung was not chosen. The exact 20 is `PRD.md` Part 6's.
private let stableWifiBps: Int64 = 20_000_000

// ref: the same Netflix recommendations: 3 Mbit/s or more for HD (720p), 5 for full HD (1080p). A
// mean of 3 sits on the HD threshold, and ±50% swings it from 1.5 to 4.5. `PRD.md` Part 6 names the
// mean and the swing.
private let congestedWifiMeanBps: Int64 = 3_000_000
private let congestedWifiSwing = 0.5

// ref: HSDPA dataset — Riiser et al., "Commute Path Bandwidth Traces from 3G Networks: Analysis and
// Applications", MMSys 2013 — and the van der Hooft et al. 4G/LTE logs (IEEE Communications Letters
// 20(11), 2016) both log one sample a second.
private let congestedWifiSampleMs: Int64 = 1_000

// ref: derivation (CONTRIBUTING.md rule 4). Thirty pairs is a minute before the trace loops.
private let congestedWifiPairs = 30

// Any fixed seed: what matters is that it is fixed, and that it is the Kotlin one.
private let congestedWifiSeed: Int64 = 2066

// ref: Netflix's 5 Mbit/s for full HD (above), and under the 6 Mbit/s average Mao et al., "Neural
// Adaptive Video Streaming with Pensieve", SIGCOMM 2017 §5.1, restricts its evaluation traces to.
private let lteBps: Int64 = 5_000_000

// ref: 3GPP TS 36.331 (E-UTRA RRC), timer T310, at most ms2000: the longest interruption LTE sits
// through without declaring radio link failure.
private let lteDropoutMs: Int64 = 2_000

// ref: Riiser et al. (above), 3G throughput on commutes, and Pensieve §5.1's band: 1 Mbit/s is near
// its bottom. `PRD.md` Part 6 names the 1.
private let threeGBps: Int64 = 1_000_000

// ref: RFC 2488 §2: a geostationary round trip is at least 558 ms; 600 is that floor plus the
// terrestrial part, and `PRD.md` Part 6's number.
private let highLatencyRttMs: Int64 = 600

/// `java.util.Random`, the three calls ``NetworkProfile/congestedWifi`` makes, so the Swift profile
/// draws the JVM's sequence.
///
/// ref: Java SE API, `java.util.Random` — "particular algorithms are specified for the class
/// Random": a 48-bit linear congruential generator with multiplier `0x5DEECE66D` and addend `0xB`,
/// seeded by `(seed ^ 0x5DEECE66D) & ((1 << 48) - 1)`; `next(bits)` returns the top `bits` of the
/// new state as an `int`; `nextDouble()` is `((long) next(26) << 27) + next(27)` times `0x1.0p-53`;
/// `nextBoolean()` is `next(1) != 0`.
struct JavaRandom {
    private static let multiplier: Int64 = 0x5_DEEC_E66D
    private static let addend: Int64 = 0xB
    private static let mask: Int64 = (1 << 48) - 1

    private var state: Int64

    init(seed: Int64) { state = (seed ^ Self.multiplier) & Self.mask }

    private mutating func next(_ bits: Int) -> Int32 {
        state = (state &* Self.multiplier &+ Self.addend) & Self.mask
        // `(int)(seed >>> (48 - bits))`: an unsigned shift of a value already below 2^48, then
        // the low 32 bits as a signed int.
        return Int32(truncatingIfNeeded: state >> Int64(48 - bits))
    }

    mutating func nextDouble() -> Double {
        let high = Int64(next(26)) << 27
        let low = Int64(next(27))
        return Double(high + low) * 0x1.0p-53
    }

    mutating func nextBoolean() -> Bool { next(1) != 0 }
}
