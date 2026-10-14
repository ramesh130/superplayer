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
import com.superplayer.core.EncodedFrame
import com.superplayer.core.FrameSink
import com.superplayer.core.MediaRequest
import com.superplayer.core.RealtimeTrack
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What an app that does not play MoQ pays for this module — nothing — and the two structural claims
 * ADR-0018 makes about it, which nothing else in `check` reads (#366).
 *
 * `DownloadsPayNothingTest` and `DiagnosticsPayNothingTest` are the shape, and the half that makes
 * them worth writing is taken here too: each counter is **shown to move**, so a zero is evidence
 * rather than a check that could never fire.
 *
 * What this module could cost is narrower than an optional module's usually is, and that narrowness
 * is itself the claim. It declares no Android component and asks for no permission, so there is
 * nothing for an app's merged manifest to carry. What it *does* cost, once a broadcast is
 * subscribed, is **threads and a QUIC session** — which is why the threads are what is counted here
 * rather than platform registrations: `MoqFrameSource` registers nothing with the platform at all,
 * and a counter that can never move is not a check.
 *
 * The last two tests are rules 2 and 11 — no Media3 type in any signature, and no Kotlin friendship
 * with core — each read off the repository rather than asserted by inspection, and each with the
 * control that makes the reading a reading.
 */
@RunWith(RobolectricTestRunner::class)
class MoqPayNothingTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    /**
     * A player that never touches this module starts none of its threads and opens no session, and
     * both counters move the moment a broadcast really is subscribed.
     */
    @Test
    fun aPlayerThatPlaysNoBroadcastStartsNoneOfThisTransportsThreads() {
        val relay = ScriptedMoqRelay(
            DeclaredCatalogs.videoOnly(codec = DeclaredCatalogs.OBSERVED_AVC3_CODEC, description = null),
        )

        val content = TestContent.hls()
        val player = harness.buildPlayer(content = content)
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        harness.playToReady(player)
        assertEquals("a player that is playing ordinary content", 0, transportThreads())
        assertEquals("and no session was opened for it", 0, relay.sessionsOpened.get())
        harness.release(player)

        // The same two counters against a subscription that really happens: what they count is real,
        // so the readings above are this module costing nothing rather than counters that see nothing.
        val source = MoqFrameSource(Uri.parse(BROADCAST), relay)
        val delivered = CountDownLatch(1)
        source.subscribe(FirstFrame(delivered))
        // Asserted rather than discarded, so a run that never delivers fails naming what it waited
        // for instead of failing on the thread count below (`docs/testing.md`, *Determinism*).
        assertTrue(
            "waited for the subscription to deliver something",
            delivered.await(WAIT_BOUND_MS, TimeUnit.MILLISECONDS),
        )
        assertTrue("a subscribed broadcast runs on threads of its own", transportThreads() > 0)
        assertEquals("and holds one session", 1, relay.sessionsOpened.get())

        // And the threads go with the subscription, which is the other half of obligation 9: `cancel`
        // joins them, so there is no window in which one is still running once it has returned.
        source.cancel()
        assertEquals("cancelled, the threads are gone", 0, transportThreads())
    }

    /**
     * Nothing here is a component, a permission or a provider, so this module publishes no manifest
     * and an app's merged one carries nothing of it. That is what makes "adding the module to the
     * classpath costs nothing" true before a line of it runs.
     */
    @Test
    fun theModuleDeclaresNoComponentAndNoPermission() {
        assertFalse("this module's own manifest", File("src/main/AndroidManifest.xml").exists())
    }

    /**
     * ADR-0018 rule 2: **no signature in this module names a Media3 type**, which is the test that
     * the Phase 13 seam is real — what a transport implements is core's `FrameSource`, and that
     * interface names none either.
     *
     * Read off this module's own source tree, in `DrmFailureTest`'s shape, because nothing
     * mechanical covers it: `verifyNoUnstableMedia3InPublicApi` and the tracked API surface are
     * where this rule is normally enforced, and neither looks here — the module is unpublished
     * (ADR-0017 rule 1's #364 addendum) so it has no `api/` file at all. Meanwhile
     * `api(project(":superplayer-core"))` puts every Media3 type on the compile classpath, so the
     * next signature that named one would fail nothing whatever.
     *
     * The second half is the control, and it is the same reading over `superplayer-realtime` —
     * which exists *because* its half cannot avoid naming Media3's `MediaSource` vocabulary. A
     * reading that found nothing there would be a reading that finds nothing anywhere.
     */
    @Test
    fun noSourceFileInThisModuleNamesAMedia3Type() {
        assertEquals(
            "files in superplayer-moq naming a Media3 type",
            emptyList<String>(),
            sourcesNamingMedia3(File("src/main/kotlin")),
        )
        assertTrue(
            "and superplayer-realtime's do, which is what makes the reading above a reading",
            sourcesNamingMedia3(File("../superplayer-realtime/src/main/kotlin")).isNotEmpty(),
        )
    }

    /**
     * ADR-0018 rule 11: `superplayer-moq` is **not** a Kotlin friend of core, and that is the test
     * that the Phase 13 seam is real rather than decorative — what this module implements is core's
     * *public* `FrameSource`, and a transport that needed an internal seam would be evidence the
     * seam was drawn in the wrong place.
     *
     * Read off the build configuration, because that is where friendship is declared: it is a
     * compiler flag `declareKotlinFriendModule` adds and not a Gradle dependency, so nothing about
     * it is visible at runtime and nothing else could be asserted. The second half is the control —
     * `superplayer-realtime` really does declare it — without which this would be a grep that
     * matches nothing wherever the helper were renamed.
     */
    @Test
    fun thisModuleIsNotAKotlinFriendOfCore() {
        assertFalse(
            "superplayer-moq declares no Kotlin friendship",
            File("build.gradle.kts").readText().contains(FRIEND_DECLARATION),
        )
        assertTrue(
            "and the phase 13 module it sits on does, which is what makes the reading above a reading",
            File("../superplayer-realtime/build.gradle.kts").readText().contains(FRIEND_DECLARATION),
        )
    }

    /**
     * The Kotlin sources under [root] that name a Media3 type, by path.
     *
     * The token is the **package**, `androidx.media3`, and not the word "Media3": every file in this
     * module discusses Media3 in prose, and a reading that counted that would refuse the KDoc
     * explaining why there is none.
     */
    private fun sourcesNamingMedia3(root: File): List<String> = root.walkTopDown()
        .filter { it.extension == "kt" }
        .filter { it.readText().contains(MEDIA3_PACKAGE) }
        .map { it.path }
        .toList()

    /** Live threads this transport started, told by the names [MoqFrameSource] gives them. */
    private fun transportThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name.startsWith(THREAD_PREFIX) }

    /** Counts down once anything at all has been delivered, so the test waits no longer than it must. */
    private class FirstFrame(private val delivered: CountDownLatch) : FrameSink {

        override fun onTracks(tracks: List<RealtimeTrack>) = Unit

        override fun onFrame(frame: EncodedFrame) = delivered.countDown()

        override fun onEnded() = delivered.countDown()

        override fun onError(cause: Throwable) = delivered.countDown()
    }

    private companion object {

        const val CONTENT_ID = "film/pay-nothing"

        const val BROADCAST = "${MoqFrameSource.SCHEME}://relay.example/studio-a"

        /** The prefix every thread `MoqFrameSource` starts is named with. */
        const val THREAD_PREFIX = "moq-"

        /** What friendship looks like in a module's build file. */
        const val FRIEND_DECLARATION = "declareKotlinFriendModule"

        /** What naming a Media3 type looks like in a source file: its package, not the word. */
        const val MEDIA3_PACKAGE = "androidx.media3"

        /** How long a first frame is waited for before the test gives up and fails as itself. */
        const val WAIT_BOUND_MS = 10_000L
    }
}
