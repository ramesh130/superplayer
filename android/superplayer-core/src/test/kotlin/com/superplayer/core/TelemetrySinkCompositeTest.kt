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

package com.superplayer.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `TelemetrySink.composite` — the fan-out `PRD.md` §2.2 spells, and the isolation that makes it safe
 * to put an app's own analytics adapter next to a sink that ships with the library.
 *
 * Robolectric because the composite reports a throwing child to logcat, and `android.util.Log` needs
 * a runtime. Nothing here asserts on the log line: what a consumer is promised is that the *other*
 * sinks still got the event and that playback was not taken down, and both of those are observable
 * without reading logcat.
 */
@RunWith(AndroidJUnit4::class)
class TelemetrySinkCompositeTest {

    @Test
    fun everyChildReceivesEveryEventInTheOrderTheyWereGiven() {
        val order = mutableListOf<String>()
        val composite = TelemetrySink.composite(
            TelemetrySink { order += "first" },
            TelemetrySink { order += "second" },
            TelemetrySink { order += "third" },
        )

        composite.onEvent(sessionStarted())

        assertThat(order).containsExactly("first", "second", "third").inOrder()
    }

    @Test
    fun aChildThatThrowsDoesNotStarveItsSiblings() {
        val received = mutableListOf<String>()
        val composite = TelemetrySink.composite(
            TelemetrySink { received += "before" },
            TelemetrySink { error("this sink's analytics SDK is having a bad day") },
            TelemetrySink { received += "after" },
        )

        composite.onEvent(sessionStarted())

        // The sink declared *after* the failing one is the assertion that matters: a composite that
        // let the throw escape would deliver to it never, and the app whose analytics adapter was
        // registered second would lose everything because of a bug in somebody else's.
        assertThat(received).containsExactly("before", "after").inOrder()
    }

    @Test
    fun aChildThatThrowsDoesNotReachTheCaller() {
        val composite = TelemetrySink.composite(TelemetrySink { error("boom") })

        // The caller today is a `setMediaRequest` or a `release` on the application thread. An
        // escaping exception there is a crash in playback caused by a telemetry sink, which is the
        // one thing ADR-0008's boundary exists to make impossible.
        composite.onEvent(sessionStarted())
    }

    @Test
    fun aChildThatThrowsAnErrorRatherThanAnExceptionIsContainedToo() {
        val received = mutableListOf<String>()
        val composite = TelemetrySink.composite(
            // The realistic shape: a sink whose optional analytics dependency was stripped by R8.
            TelemetrySink { throw NoClassDefFoundError("com/example/analytics/Client") },
            TelemetrySink { received += "after" },
        )

        composite.onEvent(sessionStarted())

        assertThat(received).containsExactly("after")
    }

    @Test
    fun aCompositeOfNothingIsUsableAndSilent() {
        // Not a curiosity: an app that builds its sink list from a feature flag can arrive here, and
        // the alternative to this working is a crash in a release build that never ran with the flag
        // off.
        TelemetrySink.composite().onEvent(sessionStarted())
    }

    @Test
    fun theChildrenAreFixedWhenTheCompositeIsBuilt() {
        val received = mutableListOf<String>()
        val children = arrayOf(TelemetrySink { received += "original" })
        val composite = TelemetrySink.composite(*children)

        children[0] = TelemetrySink { received += "swapped" }
        composite.onEvent(sessionStarted())

        assertThat(received).containsExactly("original")
    }

    private fun sessionStarted(): TelemetryEvent =
        TelemetryEvent.SessionStarted(
            sessionId = "session-0",
            contentId = "urn:content:12345",
            timestampMs = 1_700_000_000_000L,
            monotonicTimeMs = 42_000L,
            profile = PlaybackProfile.VIDEO_ON_DEMAND,
            decision = StaticProfilePolicy(PlaybackProfile.VIDEO_ON_DEMAND).decide(PlaybackConditions()),
        )
}
