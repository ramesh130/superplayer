package com.superplayer.core

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.IdentityHashMap

/**
 * SuperPlayer's entry point: a Media3 [Player] that delegates every call to a wrapped [ExoPlayer].
 *
 * Because it *is* a [Player] rather than something player-shaped, every integration written against
 * Media3 keeps working unchanged — `PlayerView`, `MediaSession`, notification and Android Auto
 * bridges, and the Compose media surfaces all accept it directly:
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context).build()
 * playerView.player = player
 * player.setMediaItem(MediaItem.fromUri(url))
 * player.prepare()
 * player.play()
 * ```
 *
 * ## Why [Player] is implemented by delegation rather than extended
 *
 * The obvious shape for this class is `SuperPlayer : ForwardingPlayer(exoPlayer)`, and that is what
 * it was until the tracked API surface arrived and made the cost visible. [ForwardingPlayer] is an
 * `@UnstableApi` type, so as a *supertype* it put Media3's opt-in marker on every method a consumer
 * could reach: because `SuperPlayer` declared none of them itself, `superPlayer.prepare()` resolved
 * to [ForwardingPlayer.prepare] and Android Lint demanded `@OptIn(UnstableApi::class)` at the call
 * site. The demo app — five calls — produced five errors. That is exactly the burden ADR-0001 rule 2
 * exists to keep off consumers, so the shape had to change rather than the rule.
 *
 * Extending some other Media3 base class does not help: in Media3 1.11.0 `ForwardingPlayer`,
 * `ForwardingSimpleBasePlayer`, `SimpleBasePlayer` and `BasePlayer` are all `@UnstableApi`. The
 * [Player] interface is the only stable supertype Media3 offers, so implementing it by delegation is
 * the only shape that satisfies rule 2.
 *
 * Kotlin's interface delegation generates a forwarding override for every [Player] member, so a
 * method added to [Player] in a future Media3 release is still forwarded without SuperPlayer
 * changing, and those overrides are SuperPlayer's own and carry no opt-in marker. The delegate is
 * Media3's own [ForwardingPlayer] rather than the [ExoPlayer] directly, so the engine keeps whatever
 * forwarding behaviour Media3 defines. `SuperPlayerForwardingTest` pins all of it with Media3's own
 * forwarding-contract assertion.
 *
 * This is deliberately the whole of it for now. Playback profiles, content identity, the player
 * pool, and lifecycle each arrive as their own change; what exists here is the path from public API
 * to a frame on screen, and the boundary that everything else is built inside.
 */
public class SuperPlayer private constructor(
    /**
     * The wrapped engine — the escape hatch, and permanent public API.
     *
     * [ExoPlayer] is an `@UnstableApi` type, so exposing it is a breach of ADR-0001 rule 2. It is
     * the one breach that ADR names and accepts: a playback library whose engine cannot be reached
     * cannot be debugged in production, and a consumer who reaches for this opts in to Media3's
     * instability at their own call site rather than having it forced on their compile classpath.
     *
     * Nothing reachable only through here is inside SuperPlayer's compatibility promise. Prefer the
     * facade's own API; reach past it when the facade is genuinely missing something, and say so in
     * an issue when you do.
     */
    public val exoPlayer: ExoPlayer,
    private val delegate: ForwardingPlayer,
) : Player by delegate {

    internal constructor(exoPlayer: ExoPlayer) : this(exoPlayer, ForwardingPlayer(exoPlayer))

    /**
     * Listeners are wrapped so that callbacks report *this* player as their source.
     *
     * [Player.Listener.onEvents] hands the listener the player the events came from, and a consumer
     * that registered against a [SuperPlayer] must get the [SuperPlayer] back — not the delegate,
     * which is an implementation detail they have no name for. [ForwardingPlayer] does this for its
     * own subclasses; delegation hides the delegate instead, so the correction is made here.
     *
     * Keyed by identity because listener equality is identity: registering the same listener twice
     * must not produce two wrappers, and removing it must remove the one that was added.
     */
    private val wrappedListeners = IdentityHashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val wrapper = synchronized(wrappedListeners) {
            wrappedListeners.getOrPut(listener) { listener.reportingSourceAs(this) }
        }
        delegate.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapper = synchronized(wrappedListeners) { wrappedListeners.remove(listener) }
        delegate.removeListener(wrapper ?: listener)
    }

    /**
     * Releases the engine and drops the listener wrappers with it.
     *
     * Each wrapper holds the consumer's listener, which in an app is usually held by an Activity or
     * a ViewModel. Without this they would outlive playback for as long as anything held the
     * facade — a leak whose size is however many listeners were ever registered.
     */
    override fun release() {
        delegate.release()
        synchronized(wrappedListeners) { wrappedListeners.clear() }
    }

    /**
     * Forwarded by hand because Kotlin's interface delegation does not override a Java `default`
     * method: without this, [Player]'s own default implementation would run against nothing and the
     * engine would never be asked. It is the only `default` member of [Player] in Media3 1.11.0.
     *
     * Nothing here has to remember to revisit this when Media3 adds another one.
     * `SuperPlayerForwardingTest` drives every [Player] method through the facade and asserts the
     * engine received it, so a newly-defaulted method fails there on the next catalog bump.
     */
    override fun getAudioSessionId(): Int = delegate.audioSessionId

    /**
     * Builds a [SuperPlayer]. Media3's own construction idiom (ADR-0001, CONTRIBUTING rule 3), so
     * that later configuration — profiles, telemetry, cache policy — arrives as builder methods
     * rather than as a widening constructor.
     */
    public class Builder(private val context: Context) {

        private var engineConfigurator: ((ExoPlayer.Builder) -> Unit)? = null

        /**
         * The single seam through which tests reach the engine's construction.
         *
         * A test that must run without a device or a network has to substitute Media3's fake clock
         * and fake data source, and both are `@UnstableApi` types that ADR-0001 rule 2 keeps out of
         * public API. Rather than widen the public surface for testing, the seam is `internal`:
         * tests configure the engine here and then drive playback entirely through the public
         * [Player] API, so no assertion reaches past the facade.
         */
        @VisibleForTesting
        internal fun setEngineConfigurator(configurator: (ExoPlayer.Builder) -> Unit): Builder =
            apply { engineConfigurator = configurator }

        public fun build(): SuperPlayer {
            val engineBuilder = ExoPlayer.Builder(context)
            engineConfigurator?.invoke(engineBuilder)
            return SuperPlayer(engineBuilder.build())
        }
    }
}

/**
 * Returns a [Player.Listener] that forwards every callback to this one, substituting [source] for
 * the player [Player.Listener.onEvents] reports.
 *
 * `onEvents` is the only [Player.Listener] callback that carries a player.
 *
 * ## Why this forwards reflectively
 *
 * Every one of `Player.Listener`'s 37 callbacks is a Java `default` method, and Kotlin's interface
 * delegation does not override those — `Player.Listener by listener` compiles to a class that
 * declares nothing, so all but the overridden callback would fall through to Media3's empty
 * defaults and never reach the consumer at all. It is the same gap that makes
 * [SuperPlayer.getAudioSessionId] a hand-written override, and here it would be silent.
 *
 * The two alternatives are worse. Writing all 37 forwarding methods out is a re-creation of Media3's
 * own `ForwardingPlayer.ForwardingListener`, which is what ADR-0001 rule 1 calls "a class copied out
 * to change three lines"; it would also drop any callback Media3 adds later, invisibly. A proxy
 * forwards whatever the interface has, including what it grows.
 *
 * The cost is reflection on the callback path, which carries player events — UI-rate at most, not
 * per-sample or per-chunk. `SuperPlayerForwardingTest` pins the result against Media3's own
 * forwarding-contract assertion, so "the proxy forwards everything" is checked rather than asserted.
 */
internal fun Player.Listener.reportingSourceAs(source: Player): Player.Listener {
    val listener = this
    return Proxy.newProxyInstance(
        Player.Listener::class.java.classLoader,
        arrayOf(Player.Listener::class.java),
    ) { proxy, method, args ->
        val arguments = args ?: emptyArray()

        when {
            // A proxy routes Object's methods through the handler too. Forwarding those to the
            // wrapped listener would make the wrapper unequal to itself, which breaks any consumer
            // or Media3 code that stores a listener in a hash-based collection. The wrapper is its
            // own object and answers as one.
            method.isObjectMethod() -> when (method.name) {
                EQUALS -> proxy === arguments.firstOrNull()
                HASH_CODE -> System.identityHashCode(proxy)
                else -> "SuperPlayer listener reporting $source, forwarding to $listener"
            }

            else -> {
                val forwarded =
                    if (method.name == ON_EVENTS && arguments.isNotEmpty()) {
                        arrayOf(source, *arguments.copyOfRange(1, arguments.size))
                    } else {
                        arguments
                    }

                try {
                    method.invoke(listener, *forwarded)
                } catch (e: InvocationTargetException) {
                    // A listener that throws must surface its own exception, not a reflection wrapper.
                    throw e.cause ?: e
                }
            }
        }
    } as Player.Listener
}

private fun Method.isObjectMethod(): Boolean = when (name) {
    EQUALS -> parameterTypes.size == 1 && parameterTypes[0] == Any::class.java
    HASH_CODE, TO_STRING -> parameterTypes.isEmpty()
    else -> false
}

private const val ON_EVENTS = "onEvents"
private const val EQUALS = "equals"
private const val HASH_CODE = "hashCode"
private const val TO_STRING = "toString"
