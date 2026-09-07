package com.superplayer.core

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * The one place Android's playback lifecycle rules become Media3 configuration.
 *
 * The counterpart of `EngineBinding.kt` for the concerns that are not policy: audio focus, the
 * becoming-noisy broadcast, and the wake locks. Each is a rule of the *platform* rather than a
 * choice about this playback, which is why none of it is decided behind [PlaybackPolicy] and why
 * none of it varies by [PlaybackProfile] — see [withLifecycleCorrectness].
 */

/**
 * Turns on the three lifecycle behaviours every Android media app is required to get right, none of
 * which Media3 enables by default.
 *
 * Every one of them is *implemented* by Media3 already; what is missing from a consumer's app is the
 * three lines that switch them on, and forgetting any one of them is invisible until a phone call
 * arrives or a battery report does. Enabling them here is ADR-0001's argument in miniature —
 * SuperPlayer's contribution is the correct default, not a second implementation of the mechanism.
 *
 * ## Audio focus
 *
 * `handleAudioFocus = true` makes the engine request focus when playback starts, hold it while
 * playing, and abandon it when playback stops, then react to what other apps do with it:
 *
 * | What happens | What the player does |
 * | --- | --- |
 * | Transient loss (a phone call) | pauses, and resumes when focus comes back |
 * | Transient loss that may duck (a navigation prompt) | lowers the volume, and restores it after |
 * | Permanent loss (another media app starts) | stops playing, and does **not** resume |
 *
 * The permanent case is the one that is easy to get wrong in both directions: an app that auto-
 * resumes after a permanent loss talks over whatever took focus from it, and one that treats a
 * transient loss as permanent leaves the user pressing play again after every notification. Media3
 * distinguishes them; the difference is visible through the facade as [Player.getPlayWhenReady] —
 * cleared on a permanent loss, left alone on a transient one, where the pause shows up as
 * [Player.getPlaybackSuppressionReason] instead.
 *
 * Ducking rather than pausing is chosen by the content type in [AudioAttributes], and
 * [AudioAttributes.DEFAULT] — media usage, unspecified content type — ducks. Speech content should
 * pause instead, because a ducked podcast is not a quieter podcast but an unintelligible one; a
 * consumer says so with [Player.setAudioAttributes], which is Media3's own API and reaches the
 * engine through the facade like any other `Player` call. Passing `handleAudioFocus = false` there
 * turns this off again, which is the consumer's decision to make.
 *
 * ref: https://developer.android.com/media/optimize/audio-focus
 *
 * ## Becoming noisy
 *
 * Unplugging headphones routes audio to the speaker, and a player that keeps going announces to a
 * room what someone was listening to privately. The platform broadcasts
 * `AudioManager.ACTION_AUDIO_BECOMING_NOISY` just before the route changes so that an app can pause
 * first; `setHandleAudioBecomingNoisy` registers Media3's receiver for it.
 *
 * ref: https://developer.android.com/media/optimize/audio-focus#becoming-noisy
 *
 * ## Wake locks
 *
 * [C.WAKE_MODE_NETWORK] makes the engine hold a `PowerManager` wake lock — and a `WifiManager`
 * Wi-Fi lock — only while playback is actually running, and release both the moment it is not.
 * Without it, playback with the screen off stops when the device suspends; with a hand-rolled lock
 * held across a whole session, a paused player drains the battery for as long as the app is alive.
 *
 * "Actually running" is Media3's own rule: the locks are held while
 * [Player.getPlayWhenReady] is true and the player is buffering or ready, and released when it is
 * paused, idle or ended. Note that buffering *with intent to play* holds them, which is deliberate
 * and not a slip — a rebuffer needs the CPU and the radio precisely in order to end, and releasing
 * the locks there would suspend the device inside a stall it would then never leave.
 *
 * The network mode rather than [C.WAKE_MODE_LOCAL] because everything SuperPlayer plays is streamed.
 * It costs no permission a consumer does not already have: `media3-exoplayer`'s own manifest
 * declares `WAKE_LOCK`, and Media3 checks for it and degrades to logging a warning if an app has
 * removed it.
 *
 * ref: https://developer.android.com/media/media3/exoplayer/battery-consumption
 */
internal fun ExoPlayer.Builder.withLifecycleCorrectness(): ExoPlayer.Builder =
    setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
