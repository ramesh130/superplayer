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

/// How CMCD (CTA-5004) travels on the requests this player makes — or that it does not travel at
/// all. The port of `superplayer-core`'s `CmcdMode`, the identical enum, and its reasons are that
/// type's.
///
/// CMCD is the client telling the CDN what the client believes — the buffer it has, the throughput
/// it measured, whether this request is holding up playback — so that the CDN's access log and the
/// app's QoE warehouse describe the same session, joined on `sid` (`docs/telemetry-schema.md`,
/// *Joining to the CDN's log*). That correlation has to be in place before an incident rather than
/// added during one, which is why it is on by default.
///
/// ```swift
/// var configuration = SuperPlayer.Configuration()
/// try configuration.setCmcdMode(.disabled)   // only if nobody reads the keys
/// ```
///
/// Left unset, a player emits under the mode its ``PlaybackProfile`` defaults to — ``requestHeaders``
/// for every profile, `StaticCmcdPolicy`'s table.
///
/// **What differs on iOS and tvOS** is what AVFoundation offers, because AVFoundation is what sends
/// the keys (ADR-0020 rule 7's #413 addendum, `docs/ios-cmcd-finding.md`): one switch on the asset,
/// headers only, from iOS 18 and tvOS 18. So ``queryParameter`` has no mechanism and is refused at
/// configuration rather than quietly sent as headers; below 18 nothing is sent and nothing is
/// refused, because CMCD is not a call a consumer makes (rule 9's measurement shape). The keys are
/// the SDK's choice — v1, no `cid`, no per-key control — and the finding lists the ones observed.
public enum CmcdMode: CaseIterable, Sendable, Hashable {

    /// No CMCD at all: the asset's switch is left at its default, off.
    ///
    /// For a deployment where the keys reach nobody who would read them. Turning it off does not
    /// turn telemetry off — ``TelemetrySink`` is a separate seam — and it does not remove the
    /// session id from the wire: AVFoundation sends `X-Playback-Session-Id` on every request
    /// whatever this says, and that header is the telemetry `sessionId` too.
    case disabled

    /// The keys travel as the `CMCD-*` request headers CTA-5004 §3.2 defines. The default: a CDN not
    /// configured to log them strips them and playback is untouched, so the failure mode is missing
    /// data rather than a refused request (the Kotlin `CmcdMode`'s argument).
    case requestHeaders

    /// The keys travel as one `CMCD` query parameter — **refused on iOS and tvOS**, with
    /// ``CmcdModeUnsupportedError``, by ``SuperPlayer/Configuration/setCmcdMode(_:)``.
    ///
    /// AVFoundation offers headers alone (`AVAssetResourceLoader.h` states no query mode), and
    /// appending a parameter by hand would mean intercepting every request, which is the in-process
    /// proxy ADR-0020 rule 6 forbids. Declared anyway so the shared vocabulary is one vocabulary and
    /// a configuration ported from Android fails by name rather than failing to compile on a
    /// missing case a reader then has to look up.
    case queryParameter
}

/// A ``CmcdMode`` this platform has no mechanism for, refused at configuration — ADR-0020 rule 9's
/// first shape, which the Kotlin `HttpStackUnsupportedException` is the model of: the call is
/// refused typed and names what was asked, never degraded silently. A query parameter quietly sent
/// as headers would be the silent degradation: the CDN that logs query strings alone, the reason a
/// consumer asks for this mode, would see nothing and say nothing.
///
/// It names no version, unlike rule 9's other refusals, because there is none to name: no release
/// of AVFoundation this package supports offers the mode.
public struct CmcdModeUnsupportedError: Error, Sendable, Hashable, CustomStringConvertible {

    /// The mode that was asked for.
    public let mode: CmcdMode

    public var description: String {
        "CmcdMode.\(mode) is not supported on iOS or tvOS: AVFoundation sends CMCD as request "
            + "headers only (sendsCommonMediaClientDataAsHTTPHeaders, iOS 18 and tvOS 18). Use "
            + "CmcdMode.requestHeaders or CmcdMode.disabled."
    }
}
