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

/// Which ``CmcdMode`` each ``PlaybackProfile`` emits under when a configuration does not choose one
/// — the Kotlin `StaticCmcdPolicy`, row for row, and every reason is that file's.
///
/// All five profiles are CDN-facing and all five default to ``CmcdMode/requestHeaders``, so the
/// table is uniform; it exists anyway because the reasoning is per profile, and the one profile
/// with a case against — ``PlaybackProfile/dataSaver`` — is the one most likely to be argued about.
/// Not `PlaybackPolicy`'s, for the Kotlin reason: CMCD changes no decision the player makes, it only
/// moves data outward, and a boundary whose vocabulary is buffers and ceilings would say something
/// false about it (ADR-0005).
///
/// ```
///   videoOnDemand  requestHeaders  The case CMCD was designed for: whether a rebuffer at minute
///                                  twelve was the network, the client or the edge is unanswerable
///                                  without the CDN's half.
///   liveLinear     requestHeaders  Where it is worth most: a live incident happens to every viewer
///                                  at once and cannot be reproduced, so the data has to be in the
///                                  CDN's log already.
///   shortForm      requestHeaders  Many short requests, so the relative overhead is highest here —
///                                  still far below one segment's noise, and `su` separates "the CDN
///                                  is slow" from "slow on the request the viewer waits for".
///   tvLeanback     requestHeaders  The longest sessions, on a shared home line, on a device nobody
///                                  opens a log on: `bl` on a stall says whether the cushion ran dry
///                                  or was never filled.
///   dataSaver      requestHeaders  The judgement call, and it goes the same way: the headers are
///                                  some 150 bytes on a request for hundreds of kilobytes, and this is
///                                  the population whose problems are hardest to reproduce and most
///                                  often blamed on the CDN. `setCmcdMode(.disabled)` disagrees.
/// ```
///
/// On iOS the row is the *asked-for* mode; whether the SDK can send it is the platform's (iOS 18 and
/// tvOS 18), which `CmcdBinding.swift` decides and this table does not.
enum StaticCmcdPolicy {

    /// The mode `profile` emits under when ``SuperPlayer/Configuration/setCmcdMode(_:)`` was not
    /// called.
    static func defaultMode(for profile: PlaybackProfile) -> CmcdMode {
        switch profile {
        case .videoOnDemand, .liveLinear, .shortForm, .tvLeanback, .dataSaver:
            .requestHeaders
        }
    }
}
