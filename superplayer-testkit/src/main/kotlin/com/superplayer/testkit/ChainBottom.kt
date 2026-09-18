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

package com.superplayer.testkit

/**
 * How the bytes reach a player this harness builds: through the transport slot, or through an
 * `HttpTransport` a consumer wrote.
 *
 * The same origin either way — the same synthetic stream, the same [FaultScript], the same
 * [ThroughputTrace], the same clock. What changes is only the *last* step of the journey, and that
 * is the whole point of the choice existing: a test that runs its body over both entries is asking
 * whether the library behaves identically over a transport we did not write, which is ADR-0016
 * rule 8's claim and is the one thing that cannot be asserted by reading the adapter.
 *
 * It is a choice on `PlaybackHarness.buildPlayer` rather than a second harness because everything
 * above the bytes has to be the same or the comparison is not one.
 */
public enum class ChainBottom {

    /**
     * The origin as Media3's own `DataSource`, in `EngineConfiguration`'s transport slot.
     *
     * What every test in this repository used before #310, and the default: a fault, a shaped link
     * and a live origin are all expressed as `DataSource` behaviour, and this is where they are
     * installed (`docs/testing.md`, *The layers above the fakes*).
     */
    HARNESS_TRANSPORT_SLOT,

    /**
     * The same origin behind an `HttpTransport`, chosen through `SuperPlayer.Builder.setHttpStack`
     * and adapted by core.
     *
     * The transport slot is deliberately left **empty**, because a slot that is filled wins over a
     * stack (ADR-0016 rule 3) and a player whose stack never resolved anything would pass every
     * test here for the wrong reason.
     *
     * What the stand-in transport does with a refusal is the point of it: the origin raises a
     * Media3 `InvalidResponseCodeException`, and the transport *reports the number* on an
     * [com.superplayer.core.HttpResponse] exactly as a consumer's HTTP client does, so the typed
     * failure the rest of the library reads can only have been built by core's adapter.
     *
     * Two things it cannot be asked for. Content Media3's fakes synthesize from a timeline
     * (`TestContent.video`, `TestContent.videoLadder`) has no transport to stand in for at all, so
     * it is refused rather than quietly played over the slot. And a fault *below* the response — a
     * name that does not resolve, a handshake that fails — arrives as the `IOException` a client
     * would raise rather than as the error code the injector chose, because a status is the only
     * thing an `HttpTransport` can report (rule 4); a test comparing the two bottoms compares
     * statuses.
     */
    CONSUMERS_HTTP_TRANSPORT,
}
