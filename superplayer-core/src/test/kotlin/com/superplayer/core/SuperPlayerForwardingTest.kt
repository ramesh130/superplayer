package com.superplayer.core

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.base.Function
import com.google.common.collect.ImmutableSet
import java.lang.reflect.Proxy
import org.junit.Test
import org.junit.runner.RunWith

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
            LISTENER_REGISTRATION,
        )
    }

    private companion object {
        /**
         * The two methods [androidx.media3.common.ForwardingPlayer] deliberately does not forward
         * verbatim: it wraps the listener so that callbacks report the facade as their source rather
         * than the engine underneath. A consumer that added a listener to a [SuperPlayer] and got
         * events attributed to some inner player would be the bug; this is the fix for it, so the
         * exclusion is the correct behaviour rather than a gap.
         */
        val LISTENER_REGISTRATION: ImmutableSet<String> =
            ImmutableSet.of("addListener", "removeListener")
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
            Player::class.java
                .getMethod(method.name, *method.parameterTypes)
                .invoke(this, *(args ?: emptyArray()))
        } as ExoPlayer
}
