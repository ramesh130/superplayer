/*
 * Copyright 2026 The SuperPlayer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.superplayer.moq

import android.net.Uri
import uniffi.moq.MoqCatalog
import uniffi.moq.MoqContainer
import uniffi.moq.MoqMediaFrame

/**
 * MoQ as the frame pump needs it: connect, read a catalog, subscribe to a track, pull frames.
 *
 * ## Why there is a seam here at all
 *
 * `MoqFrameSource` is this module's substance and **nothing under `check` can run it against a
 * relay**. `docs/testing.md` bars the network, #347 deliberately did not widen testkit's one
 * loopback carve-out, and Robolectric cannot load an Android `.so` on the JVM in any case. So the
 * pump is written against this interface and [UniffiMoqRelay] is the one implementation that
 * touches the bindings; a scripted fake of the same three types is what
 * `MoqFrameSourceConformanceTest` drives `FrameSourceConformance` over.
 *
 * **What that proves and what it does not**, said here because it is the one thing a reader of this
 * module could get wrong: a green conformance run proves that *this bridge* honours all nine of
 * `FrameSource`'s obligations, and it proves **nothing whatever about MoQ's bindings, about QUIC,
 * or about a relay**. The first real session is #367's, and it needs a device.
 *
 * ## Where the seam falls, and why there
 *
 * Exactly at the boundary where a call stops being a decision of ours and starts being a round trip:
 * the three verbs below are the three the bindings answer, in the shape the bindings answer them.
 * Nothing is simplified on the way past — the catalog crosses as `uniffi.moq.MoqCatalog` and a frame
 * as `uniffi.moq.MoqMediaFrame`, because both are plain Kotlin records on this side of the FFI
 * (which is what `MoqCatalogTracksTest` already relies on) and a parallel pair of data classes would
 * be a second vocabulary to keep in step for no gain. What the seam *does* remove is everything that
 * needs the native library: the handles, the `suspend` boundary and the `MoqSubscription` the
 * bindings want.
 *
 * Every method here is **blocking**. The bindings' are `suspend`, and [UniffiMoqRelay] is where that
 * is turned into a blocking call on a thread of the pump's own — deliberately on the adapter's side
 * of the seam, so the fake is a fake of a transport rather than a fake of a coroutine.
 */
internal fun interface MoqRelay {

    /**
     * Connects to the relay [uri] names and requests the broadcast it names, blocking until both
     * have happened.
     *
     * @throws java.io.IOException if the connection or the broadcast request fails. What a failure
     *   becomes is [MoqFrameSource]'s: it reaches the consumer through `FrameSink.onError`, never
     *   as a throw on a thread nobody owns.
     */
    fun connect(uri: Uri): MoqBroadcastSession
}

/**
 * One connected session and the broadcast it holds open.
 *
 * The two are one object because their lifetimes are one: a broadcast subscription outliving its
 * QUIC session is not a state this module has any use for, and [close] ends both in that order.
 *
 * **This is the object #368 polls.** MoQ exports its statistics as a snapshot taken from the session
 * — round trip time, estimated rates, byte and packet counters — and has no stream of them, so a
 * cadence is this library's to impose on something it holds. What holds one is this type, for the
 * lifetime of one subscription, which is the same lifetime a measurement session has.
 */
internal interface MoqBroadcastSession : AutoCloseable {

    /**
     * The broadcast's catalog, blocking until the publisher has sent one.
     *
     * Read **once**, at subscription: `FrameSink.onTracks` is called exactly once with every track
     * a subscription will deliver (obligation 2), so a catalog republished mid-broadcast has
     * nowhere to go and is not read. A publisher that changes its ladder mid-broadcast is ADR-0018
     * rule 7's deferred adaptive selection arriving, and the phase that takes it amends that record.
     */
    fun catalog(): MoqCatalog

    /**
     * Opens the subscription for one rendition: [trackName] is the catalog's own key for it and
     * [container] what that rendition declared its frames are wrapped in, both handed over exactly
     * as `MoqCatalogTracks` read them.
     */
    fun subscribe(trackName: String, container: MoqContainer): MoqTrackStream

    /** Ends the broadcast subscription and then the session. Safe to call more than once. */
    override fun close()
}

/** One rendition's frames, pulled one at a time. */
internal interface MoqTrackStream : AutoCloseable {

    /**
     * The next frame, blocking until one arrives, or **null** once the track has ended of its own
     * accord — the publisher stopped, the broadcast finished.
     *
     * @throws java.io.IOException if the track failed, which is a different thing from ending and
     *   reaches the consumer as `FrameSink.onError` rather than `onEnded`.
     */
    fun next(): MoqMediaFrame?

    /**
     * Ends this track's subscription, and **unblocks a [next] in flight on another thread**.
     *
     * That second half is the whole reason this method is written down rather than left to
     * `AutoCloseable`'s KDoc. `FrameSource.cancel` must return only once no further callback can be
     * made (obligation 9), [MoqFrameSource] makes that true by joining its pump threads, and a pump
     * thread parked in a `next` nothing interrupts would never be joinable — so a cancellation that
     * looked correct would hang the playback thread instead. A stream whose close does not unblock
     * its reader breaks obligation 9 through this module, silently, which is why the fake honours
     * it and why [UniffiMoqRelay] cancels the consumer before closing it.
     */
    override fun close()
}
