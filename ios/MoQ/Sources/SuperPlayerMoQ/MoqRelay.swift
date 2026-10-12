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
import MoqFFI

/// MoQ as the frame pump needs it: connect, read a catalog, subscribe to a track, pull frames — the
/// Kotlin `MoqRelay`, and the seam's reasons are that file's.
///
/// ## Why there is a seam here at all
///
/// ``MoqFrameSource`` is this product's substance and **nothing under `ios/check` can run it
/// against a relay**: ADR-0021 widens the no-network rule only as far as a loopback HTTP origin,
/// and no QUIC session is opened anywhere under the check. So the pump is written against this
/// protocol and ``UniffiMoqRelay`` is the one implementation that touches the bindings; a scripted
/// fake of the same three types is what `MoqFrameSourceConformanceTests` drives `FrameSourceConformance`
/// over. A green conformance run proves **this bridge** honours `FrameSource`'s obligations and
/// proves **nothing whatever about MoQ's bindings, about QUIC, or about a relay** — that is the
/// i9 device ticket's.
///
/// ## Where the seam falls
///
/// Exactly where a call stops being a decision of ours and starts being a round trip: the three
/// verbs below are the three the bindings answer, in the shape they answer them. The catalog crosses
/// as the bindings' `MoqCatalog` and a frame as their `MoqMediaFrame`, both plain Swift values on
/// this side of the FFI; what the seam removes is the handles, the `async` boundary and the
/// `MoqSubscription` the bindings want. Every method is **blocking**: the bindings' are `async`, and
/// ``UniffiMoqRelay`` turns that into a blocking call on a thread of the pump's own, so the fake is a
/// fake of a transport rather than of an executor.
protocol MoqRelay: Sendable {

    /// Connects to the relay `url` names and requests the broadcast it names, blocking until both
    /// have happened. A failure throws, and what it becomes is ``MoqFrameSource``'s: it reaches the
    /// consumer through `FrameSink.onError(_:)`, never as a throw on a thread nobody owns.
    func connect(_ url: URL) throws -> any MoqBroadcastSession
}

/// One connected session and the broadcast it holds open, closed in that order — the Kotlin
/// `MoqBroadcastSession`. One object because their lifetimes are one, and the object a
/// subscription's statistics are polled from.
protocol MoqBroadcastSession: AnyObject, Sendable {

    /// The broadcast's catalog, blocking until the publisher has sent one. Read **once**, at
    /// subscription, because `FrameSink.onTracks(_:)` is called exactly once (obligation 2); a
    /// catalog republished mid-broadcast is ADR-0018 rule 7's deferred selection arriving.
    func catalog() throws -> MoqCatalog

    /// Opens the subscription for one rendition: `trackName` is the catalog's own key for it and
    /// `container` what that rendition declared its frames are wrapped in, both handed over exactly
    /// as ``MoqCatalogTracks`` read them.
    func subscribe(trackName: String, container: MoqContainer) throws -> any MoqTrackStream

    /// This session's statistics as of now — the one place the seam does **not** carry a binding
    /// type across unchanged, for the Kotlin reason: the reading is the only thing in this product a
    /// consumer can name, and the bindings' `MoqConnectionStats` is not a type this product's public
    /// surface carries. Never nil — MoQ always answers *a* snapshot, with its fields individually
    /// absent — and a session that cannot be read throws, which costs the polling and never the
    /// subscription. Answered without blocking: the bindings' `stats()` is not `async`.
    func statistics() throws -> MoqSessionStatistics

    /// Ends the broadcast subscription and then the session. Safe to call more than once.
    func close()
}

/// One rendition's frames, pulled one at a time — the Kotlin `MoqTrackStream`.
protocol MoqTrackStream: AnyObject, Sendable {

    /// The next frame, blocking until one arrives, or **nil** once the track has ended of its own
    /// accord. A failed track throws, which is a different thing from ending and reaches the
    /// consumer as `onError(_:)` rather than `onEnded()`.
    func next() throws -> MoqMediaFrame?

    /// Ends this track's subscription, and **unblocks a ``next()`` in flight on another thread**.
    ///
    /// That second half is why this is written down. `FrameSource.cancel()` must return only once no
    /// further callback can be made (obligation 9), ``MoqFrameSource`` makes that true by joining
    /// its pump threads, and a pump parked in a `next` nothing interrupts would never be joinable —
    /// a cancellation that looked correct would hang the main actor instead.
    func close()
}
