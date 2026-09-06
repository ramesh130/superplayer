package com.superplayer.core

import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.base.Function
import com.google.common.collect.ImmutableSet
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify

/**
 * Pins the facade against Media3's own definition of what forwarding a [Player] means.
 *
 * The assertion is Media3's, not ours: it reflects over every public overridable method on [Player],
 * calls each one on a [SuperPlayer], and verifies the wrapped player received it. A method Media3
 * adds to [Player] in a future release is therefore covered here the moment the catalog is bumped,
 * without anyone remembering to extend a hand-written list.
 */
@RunWith(AndroidJUnit4::class)
class SuperPlayerForwardingTest {

    @Test
    fun forwardsEveryPlayerMethodToTheWrappedEngine() {
        TestUtil.assertForwardingClassForwardsAllMethodsExcept(
            Player::class.java,
            Function { player -> SuperPlayer(player!!.asExoPlayer()) },
            NOT_PLAIN_PASS_THROUGHS,
        )
    }

    /**
     * Covers what [forwardsEveryPlayerMethodToTheWrappedEngine] has to leave out.
     *
     * Media3's assertion passes `null` for an argument it cannot construct, and `PlaybackParameters`
     * rejects the zero it is offered, so the bulk assertion never reaches the engine for this one
     * method. Given a value the type actually accepts, forwarding is ordinary — which is what this
     * asserts, so the exclusion above costs no coverage.
     */
    @Test
    fun forwardsPlaybackParametersToTheWrappedEngine() {
        val engine = mock(Player::class.java)
        val player = SuperPlayer(engine.asExoPlayer())
        val parameters = PlaybackParameters(/* speed= */ 2f)

        player.playbackParameters = parameters

        verify(engine).playbackParameters = parameters
    }

    /**
     * The listener wrapper forwards every callback, not just the one it rewrites.
     *
     * This is the assertion that makes the wrapper's reflective forwarding safe to rely on, and it
     * is the same Media3 harness used for [Player] above — every callback on the interface, driven
     * through the wrapper, verified to reach the listener underneath. `Player.Listener` is 37 Java
     * `default` methods and nothing else, so a wrapper that quietly forwarded none of them would
     * still compile and still pass every other test in this file.
     */
    @Test
    fun theListenerWrapperForwardsEveryCallback() {
        TestUtil.assertForwardingClassForwardsAllMethodsExcept(
            Player.Listener::class.java,
            Function { listener -> listener!!.reportingSourceAs(mock(Player::class.java)) },
            // Verified by the test below instead: this is the one callback the wrapper exists to
            // change, so it does not reach the listener with the arguments it was given.
            ImmutableSet.of("onEvents"),
        )
    }

    /**
     * The wrapper is its own object, not a mirror of the listener it wraps.
     *
     * A dynamic proxy routes `equals`, `hashCode` and `toString` through its handler too. Forwarded
     * blindly to the wrapped listener, `wrapper.equals(wrapper)` asks the *listener* whether it
     * equals the wrapper and gets false — a listener that cannot be found in any hash-based
     * collection, including ones Media3 or a consumer might keep.
     */
    @Test
    fun theListenerWrapperIsEqualToItself() {
        val listener = object : Player.Listener {}

        val wrapper = listener.reportingSourceAs(mock(Player::class.java))

        assertThat(wrapper).isEqualTo(wrapper)
        assertThat(wrapper).isNotEqualTo(listener)
        assertThat(hashSetOf(wrapper)).contains(wrapper)
    }

    /**
     * The reason `addListener` and `removeListener` are excluded above: they are not pass-throughs.
     *
     * [Player.Listener.onEvents] hands the listener the player the events came from. A consumer that
     * registered against a [SuperPlayer] must get the [SuperPlayer] back — an inner delegate they
     * have no name for would be the bug.
     */
    @Test
    fun listenerEventsReportTheFacadeRatherThanTheDelegateAsTheirSource() {
        val engine = mock(Player::class.java)
        val player = SuperPlayer(engine.asExoPlayer())
        var reportedSource: Player? = null
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                reportedSource = player
            }
        }

        player.addListener(listener)

        val registered = ArgumentCaptor.forClass(Player.Listener::class.java)
        verify(engine).addListener(registered.capture())
        registered.value.onEvents(engine, Player.Events(FlagSet.Builder().build()))

        assertThat(reportedSource).isSameInstanceAs(player)
    }

    /** Removing a listener has to reach the engine with the same wrapper that was registered. */
    @Test
    fun removingAListenerRemovesTheWrapperThatWasRegistered() {
        val engine = mock(Player::class.java)
        val player = SuperPlayer(engine.asExoPlayer())
        val listener = object : Player.Listener {}

        player.addListener(listener)
        player.removeListener(listener)

        val registered = ArgumentCaptor.forClass(Player.Listener::class.java)
        verify(engine).addListener(registered.capture())
        val removed = ArgumentCaptor.forClass(Player.Listener::class.java)
        verify(engine).removeListener(removed.capture())

        assertThat(removed.value).isSameInstanceAs(registered.value)
    }

    private companion object {
        /**
         * What the bulk assertion cannot cover, each covered by a test of its own above.
         *
         * `addListener` and `removeListener` are excluded because the facade deliberately does not
         * forward them verbatim: it wraps the listener so callbacks report the facade as their
         * source. `setPlaybackParameters` is excluded because Media3's harness cannot build a
         * `PlaybackParameters` — its constructor rejects a zero speed — and passes null instead,
         * which [Player]'s own contract does not permit.
         */
        val NOT_PLAIN_PASS_THROUGHS: ImmutableSet<String> =
            ImmutableSet.of("addListener", "removeListener", "setPlaybackParameters")
    }

    /**
     * The assertion mocks the interface it is given, so it hands us a [Player] while [SuperPlayer]
     * takes an [ExoPlayer]. This adapts one to the other without weakening the facade's constructor
     * to `Player`, which would put a type in production code that exists only for a test.
     *
     * Every call reaching the proxy arrives through [SuperPlayer]'s `Player`-typed delegate, so the
     * method is always resolvable on [Player] — which it has to be re-resolved to, because
     * [ExoPlayer] narrows the return type of several of them and a proxy reports the most derived
     * declaration, which a `Player` mock is not an instance of.
     */
    private fun Player.asExoPlayer(): ExoPlayer =
        Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, args ->
            val forwarded = Player::class.java
                .getMethod(method.name, *method.parameterTypes)
                .invoke(this, *(args ?: emptyArray()))

            forwarded ?: playerContractDefaultFor(method.returnType)
        } as ExoPlayer

    /**
     * Supplies a non-null value where [Player]'s contract promises one and the mock does not.
     *
     * Media3 declares [Player]'s getters non-null, and [SuperPlayer] implements the interface by
     * Kotlin delegation, so its generated overrides assert that. A Mockito mock returns null for
     * every reference type regardless of what the interface promises, which makes the mock — not the
     * facade — the party breaking the contract. Media3's `ForwardingPlayer` never noticed because
     * Java inserts no such assertion; the facade is simply stricter than the double.
     *
     * The value itself is irrelevant: the assertion under test verifies that the call *reached* the
     * mock and never inspects what came back. Each of these types publishes an empty or default
     * constant of its own type, which is what is found here, so no per-type table has to be kept in
     * step with [Player].
     */
    private fun playerContractDefaultFor(returnType: Class<*>): Any? = when {
        returnType.isPrimitive -> null
        // A Looper has no such constant, and under Robolectric the main one is a real Looper.
        returnType == Looper::class.java -> Looper.getMainLooper()
        else -> returnType.fields
            .firstOrNull { Modifier.isStatic(it.modifiers) && returnType.isAssignableFrom(it.type) }
            ?.get(null)
    }
}
