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

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.testmedia.SyntheticHlsStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.MalformedURLException

/**
 * The two stacks core builds itself, and the refusal (#313, ADR-0016 rules 11 and 12).
 *
 * ## What can be shown offline about `HttpStack.httpEngine`, and what cannot
 *
 * **It cannot be played here, and nothing in this class pretends otherwise.**
 * `android.net.http.HttpEngine` is a platform class with no Robolectric shadow: its own
 * `Builder.build()` goes looking for the Cronet implementation the *system* ships, finds none in a
 * JVM, and raises `Expected Cronet version number …, actual version number null`. There is no
 * arrangement of `DeviceStatement` or of the harness that changes that, and faking one would mean
 * writing a stand-in HTTP engine of our own and then asserting that our stand-in works — which is
 * the shape `docs/testing.md` refuses wherever it refuses a fake, and which `SecureSurfaceDowngradeTest`
 * had to say about a secure buffer queue for the same reason. So the issue's *"plays HLS and DASH on
 * a stated API 34+ device"* is **#316's**, on a real device, and `docs/testing.md`'s
 * *The platform's HTTP engine* records the gap.
 *
 * What is left is worth having and is the whole of the risk this ticket carries, because the risk is
 * a **silent substitution** rather than a slow one:
 *
 *  - below the floor, the refusal is raised, typed, and raised *by `build()`*;
 *  - above it, the refusal is **not** raised and the platform's engine is genuinely asked for — which
 *    is visible precisely because the platform declines. A stack that had been quietly swapped for
 *    `DefaultHttpDataSource` would have built a player and said nothing.
 *
 * ## Stating an API level
 *
 * This class is the repository's first user of `@Config(sdk = [...])`, which
 * `superplayer-core/src/test/resources/robolectric.properties` has always pointed at for exactly
 * this case. It is deliberately not a `DeviceStatement`: that type states what a device *can do* —
 * decoders, display, heap, protection — and the platform SDK level is not a capability the library
 * reads through any of those, it is `Build.VERSION.SDK_INT`, which Robolectric's own annotation
 * already sets. The module-wide pin is 35, which is above the floor, so only the refusing arm says
 * anything at all.
 */
@RunWith(AndroidJUnit4::class)
class HttpStackSelectionTest {

    /** Robolectric has no real codecs; the renderer pipeline runs against shadow ones. */
    @get:Rule
    val shadowMediaCodecConfig: ShadowMediaCodecConfig =
        ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @get:Rule
    val harness: SuperPlayerHarness = SuperPlayerHarness()

    /** Where the one `file:` stream of the default-stack count is written, and what removes it. */
    @get:Rule
    val streamDirectory: TemporaryFolder = TemporaryFolder()

    /**
     * ADR-0016 rule 12: below API [HttpStack.HTTP_ENGINE_MIN_API_LEVEL] the selection is **reported**.
     *
     * Typed, and with the two numbers a reader of a crash report needs — what was asked for and what
     * this device is — rather than a string to parse.
     */
    @Test
    @Config(sdk = [33])
    fun theHttpEngineStackIsRefusedBelowTheApiLevelItNeeds() {
        val refusal = refusalFromBuildingWithTheHttpEngineStack()

        assertThat(refusal).isInstanceOf(HttpStackUnsupportedException::class.java)
        refusal as HttpStackUnsupportedException
        assertThat(refusal.stack).isEqualTo("HttpStack.httpEngine()")
        assertThat(refusal.requiredApiLevel).isEqualTo(34)
        assertThat(refusal.deviceApiLevel).isEqualTo(33)
    }

    /**
     * The sharper half of the same criterion, and the one a later change is most likely to erode:
     * the refusal is **`build()`'s**, not the first load's.
     *
     * Nothing is prepared and nothing is played here on purpose. The claim is that no player comes
     * back at all — so a change that moved the check into the chain's first `createDataSource`, or
     * into the first segment, fails this method by *succeeding*: `build()` would return a player,
     * `built` would be non-null, and the assertion below would read a player where it demands none.
     * That is the ADR-0004 instinct written as a test, and it is why the try wraps `build()` alone.
     */
    @Test
    @Config(sdk = [33])
    fun theRefusalIsBuildsAndNotTheFirstLoads() {
        var built: SuperPlayer? = null
        val refusal = runCatching {
            built = builderWithTheHttpEngineStack().build()
        }.exceptionOrNull()

        // Released rather than leaked, for the case where this assertion is the one that fails.
        built?.release()

        assertThat(built).isNull()
        assertThat(refusal).isInstanceOf(HttpStackUnsupportedException::class.java)
    }

    /**
     * The refusal is a fact about the device and the selection, so **a harness cannot hide it**.
     *
     * `buildPlayer` fills the engine configurator's transport slot, which ADR-0016 rule 3 makes the
     * winner over any stack a consumer set — so if the check were made where the stack is *resolved*
     * it would never run here, and a test written against the harness would see a player build
     * happily on a device that cannot honour what it asked for. It is made before the slot is
     * consulted instead, which is what this method pins.
     */
    @Test
    @Config(sdk = [33])
    fun aFilledTransportSlotDoesNotExcuseAStackTheDeviceCannotHonour() {
        var built: SuperPlayer? = null
        val refusal = runCatching {
            built = harness.buildPlayer(httpStack = HttpStack.httpEngine())
        }.exceptionOrNull()

        assertThat(built).isNull()
        assertThat(refusal).isInstanceOf(HttpStackUnsupportedException::class.java)
    }

    /**
     * Above the floor the selection is **honoured**, which here is read off what fails.
     *
     * The platform declines — there is no Cronet implementation inside a JVM — and that refusal is
     * the evidence: it can only have been raised by `android.net.http.HttpEngine.Builder.build()`,
     * so the stack was asked for rather than exchanged for `DefaultHttpDataSource` behind the
     * consumer's back. A substitution would have produced a working player and no exception at all,
     * which is the failure mode this test exists to make impossible to introduce quietly.
     *
     * It is also the honest statement of the gap: the assertion stops exactly where the platform
     * does, and #316 is where a device carries it further.
     */
    @Test
    fun aboveTheFloorThePlatformsEngineIsAskedForRatherThanSubstituted() {
        val failure = refusalFromBuildingWithTheHttpEngineStack()

        assertThat(failure).isNotNull()
        assertThat(failure).isNotInstanceOf(HttpStackUnsupportedException::class.java)
        // Robolectric's stub says exactly this, and what it names is the implementation the *system*
        // would have shipped. The assertion is on the word rather than on the type because the type
        // is a bare RuntimeException.
        assertThat(failure).hasMessageThat().contains("Cronet")
    }

    /**
     * ADR-0016 rule 11's first factory, counted the way #309 counted the absence of a stack: a player
     * built with [HttpStack.default] is indistinguishable from one built with no stack at all.
     *
     * Both halves of the resolved factory are named, and neither probe touches a network.
     * `DefaultDataSource` is named by a `file:` URI, which no HTTP stack of any kind serves and which
     * both players play to `STATE_READY`. `DefaultHttpDataSource` is named by the
     * `MalformedURLException` both players end on for the synthetic streams' `fake:` scheme: that
     * exception comes from `java.net.URL`, which Media3's own HTTP factory is the only thing in this
     * chain to call — so a bottom that had resolved to something else would have named an unsupported
     * scheme instead, and a bottom that had resolved to nothing would have failed differently again.
     *
     * The two players are asserted *against each other* rather than against a remembered expectation,
     * because "the same as saying nothing" is the claim, and a change that moved both would otherwise
     * pass.
     */
    @Test
    fun theDefaultStackIsExactlyWhatSayingNothingResolvesTo() {
        val stream = SyntheticHlsStream.writeTo(streamDirectory.root)

        val stated = harness.buildPlayerOnItsOwnTransferChain(httpStack = HttpStack.default())
        val unstated = harness.buildPlayerOnItsOwnTransferChain()

        assertThat(readiedOn(stated, stream.toString())).isTrue()
        assertThat(readiedOn(unstated, stream.toString())).isTrue()

        val statedHttp = harness.buildPlayerOnItsOwnTransferChain(httpStack = HttpStack.default())
        val unstatedHttp = harness.buildPlayerOnItsOwnTransferChain()

        val statedCauses = causeClassesOfPlaying(statedHttp, SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)
        val unstatedCauses = causeClassesOfPlaying(unstatedHttp, SyntheticHlsStream.MULTIVARIANT_PLAYLIST_URI)

        // Each player is driven exactly once: asking the same one twice would be reading a second
        // failure back through a `prepare()` that had to clear the first, which is state this claim
        // has no business depending on.
        assertThat(statedCauses).isEqualTo(unstatedCauses)
        assertThat(statedCauses).contains(MalformedURLException::class.java)
    }

    /** Builds a player over the real chain with the platform's stack, and answers what stopped it. */
    private fun refusalFromBuildingWithTheHttpEngineStack(): Throwable? {
        var built: SuperPlayer? = null
        val failure = runCatching { built = builderWithTheHttpEngineStack().build() }.exceptionOrNull()
        built?.release()
        return failure
    }

    /**
     * A consumer's own call, with nothing of the harness in it: the transport slot a harness fills
     * would win over the stack (rule 3), and the point here is what happens to the stack.
     */
    private fun builderWithTheHttpEngineStack(): SuperPlayer.Builder =
        SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setHttpStack(HttpStack.httpEngine())

    private fun readiedOn(player: SuperPlayer, uri: String): Boolean {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilState(Player.STATE_READY)
        return player.playerError == null
    }

    /** Plays [uri] until it fails, and answers every class on the failure's cause chain. */
    private fun causeClassesOfPlaying(player: SuperPlayer, uri: String): List<Class<*>> {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        TestPlayerRunHelper.advance(player).untilPlayerError()
        return generateSequence(player.playerError as Throwable?) {
            it.cause.takeIf { cause -> cause !== it }
        }.map { it.javaClass }.toList()
    }
}
