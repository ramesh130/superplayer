package com.superplayer.core

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

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
 * Delegation comes from Media3's own [ForwardingPlayer], not from a hand-written pass-through, so a
 * method added to [Player] in a future Media3 release is forwarded without SuperPlayer changing.
 * `SuperPlayerForwardingTest` pins that with Media3's forwarding-contract assertion.
 *
 * This is deliberately the whole of it for now. Playback profiles, content identity, the player
 * pool, and lifecycle each arrive as their own change; what exists here is the path from public API
 * to a frame on screen, and the boundary that everything else is built inside.
 */
public class SuperPlayer internal constructor(
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
) : ForwardingPlayer(exoPlayer) {

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
