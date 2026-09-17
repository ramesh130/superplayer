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

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
 * What exists here is the path from public API to a frame on screen, the content identity that
 * travels along it ([MediaRequest]), the policy that shapes it ([PlaybackProfile]), and the state
 * that outlives one player ([PlaybackSnapshot]). A screen that needs many of these at once — a feed,
 * a grid — builds them through [PlayerPool] rather than one per item, because decoder instances are
 * a device resource with a limit that a scroll will find.
 *
 * Android's own lifecycle rules — audio focus, the becoming-noisy broadcast, the wake locks — are on
 * by default and need no call at all; `LifecycleBinding.kt` says what is switched on and why.
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
    /**
     * The profile this player was built with — what kind of playback this is.
     *
     * Read-only: a profile is chosen at construction and does not change. It names the policy the
     * player was built for, and a policy is built *for* a profile rather than observing one. An app
     * that offers the choice at runtime — a data-saver switch in settings, say — builds a new
     * player, which is what the demo does.
     */
    public val profile: PlaybackProfile,
    /** The decision the policy made at construction; what [playbackDecision] starts as. */
    initialDecision: PlaybackDecision,
    private val delegate: ForwardingPlayer,
    /**
     * The track selection parameters this player was built with — what [resetForReuse] puts back.
     *
     * Passed in rather than read off [delegate] here, and that is not a stylistic choice: they are
     * the engine's own device-derived defaults with the profile's ceilings laid over them, so
     * [Builder] is the only place the combination exists, and re-deriving them would be
     * re-implementing Media3's defaults — the thing ADR-0001 rule 1 rules out.
     *
     * Null for the one caller that has no build to capture from: Media3's forwarding-contract
     * harness, which constructs this class over a mock. Reading the property in a constructor
     * initialiser was the first shape of this, and that harness counts every call the facade makes
     * to the engine — so it failed there, correctly. A reset with nothing to put back leaves the
     * parameters alone, which for a mock is the only meaningful answer anyway.
     *
     * Deliberately without a default value, and deliberately not reached from [Builder]. Either one
     * makes Kotlin emit a synthetic constructor carrying every parameter of this one — including the
     * [ForwardingPlayer] delegate, which is an `@UnstableApi` type — and `checkApiSurface` fails the
     * build for exactly that. It did, both ways round. [Builder] goes through the internal
     * constructor below, whose parameters are all types SuperPlayer may publish.
     */
    private val builtWithTrackSelectionParameters: TrackSelectionParameters?,
    /**
     * The collector this player was built with, or null if none was — and the whole of what a player
     * without telemetry pays for the feature (ADR-0008 rule 2). Every call to it below is
     * null-conditional, so no listener is registered and nothing is allocated.
     *
     * Deliberately without a default value, for the same reason
     * [builtWithTrackSelectionParameters] has none: the synthetic constructor Kotlin emits for a
     * private constructor's defaults carries every parameter of this one, including the
     * `@UnstableApi` [ForwardingPlayer], and `checkApiSurface` fails on it.
     */
    private val telemetry: TelemetryCollector?,
    /**
     * The application context, held only to register [telemetryMemoryPressure] and null whenever
     * [telemetry] is — a player without telemetry registers nothing and therefore needs nothing to
     * register it against, which is the same ADR-0008 rule 2 accounting every other telemetry field
     * here is subject to.
     *
     * The application context rather than whatever the consumer passed to [Builder]: a
     * `ComponentCallbacks2` registered on an `Activity` outlives it only as a leak, and a player is
     * routinely held past the Activity that built it.
     *
     * Deliberately without a default value, for the reason [builtWithTrackSelectionParameters] has
     * none.
     */
    private val applicationContext: Context?,
    /**
     * The id this player's current measurement session is known by — the one string the telemetry
     * events and the CMCD `sid` are both stamped with.
     *
     * Held here because the facade is where a session begins and ends, and read from the transfer
     * chain, which was given the same object when the engine was built. See [MeasurementSession] for
     * why one place mints it and what a player with neither telemetry nor CMCD does instead.
     *
     * Deliberately without a default value, for the reason [builtWithTrackSelectionParameters] has
     * none.
     */
    private val measurementSession: MeasurementSession,
    /**
     * The trigger loop that consults the policy again and re-targets the engine, or null on a
     * player whose engine was built with nothing that could honour a changed decision — which is
     * every player built without `superplayer-abr`'s policy, and is the whole of what such a player
     * pays (ADR-0009 rule 7). Every use below is null-conditional.
     *
     * Deliberately without a default value, for the reason [builtWithTrackSelectionParameters] has
     * none.
     */
    private val reapplication: DecisionReapplication?,
    /**
     * Whether items this player adopts carry their [ContentIdentity], which is true exactly when the
     * engine was built with a [ContentCache] whose key needs it. False lays nothing on an item, so a
     * player without a cache builds the items it always did (ADR-0010 rule 13).
     *
     * Deliberately without a default value, for the reason [builtWithTrackSelectionParameters] has
     * none.
     */
    private val identifiesContent: Boolean,
    /**
     * The engine this player shares with the rest of its [PlayerPool], or null for a player that has
     * none — every player not built by a pool with a coordinator attached or an extension policy set.
     * It is where [setMediaRequest] asks for a warm source (ADR-0010 rule 7); every use is
     * null-conditional, which is what a player without one pays.
     *
     * Deliberately without a default value, for the reason [builtWithTrackSelectionParameters] has
     * none.
     */
    private val pooled: PooledEngine?,
    /**
     * Who this player asks whether a surfaced failure may climb to rung 4, or null on a player built
     * without `superplayer-resilience` — which asks nobody, registers no listener for it and opens
     * the first source and nothing else (ADR-0011 rule 14).
     *
     * Deliberately without a default value, for the reason [builtWithTrackSelectionParameters] has
     * none.
     */
    private val playerStateRungs: PlayerStateRungs?,
    /**
     * What the DRM slot wrote down about the session graph it opened, and an empty holder on every
     * player that opened none.
     *
     * Deliberately without a default value, for the reason [builtWithTrackSelectionParameters] has
     * none.
     */
    private val deliveredProtection: DeliveredProtection,
    /**
     * Who core asks whether a protection failure can be repaired by re-opening the session graph at
     * a permitted lower level, and null on every player without one (ADR-0012 rule 11's #225
     * addendum).
     *
     * Deliberately without a default value, for the reason [builtWithTrackSelectionParameters] has
     * none.
     */
    private val protectionRepair: ProtectionRepair?,
) : Player by delegate {

    /**
     * The `securityLevel` this player's protected sessions were actually opened at — `"L1"`, `"L3"`,
     * or null on a player that has opened none.
     *
     * ADR-0012 rule 11's last sentence, read as a property rather than reported as an event: a
     * session that opened at a *reduced* level had a different thing delivered than the one it was
     * entitled to, and a support engineer reading a session needs to know which. `QoeCollector` reads
     * it onto `TelemetryEvent.SessionEnded.securityLevel`, where it is settled; an app that shows a
     * quality badge can read it directly.
     *
     * Null on every player built without `SuperPlayer.Builder.setDrm`, and also on one whose device
     * could honour the level it reports — the ordinary case, where no negotiation happens and there
     * is nothing to say.
     *
     * **It can change during a session**, and a reader that caches it is reading a guess. A device
     * the provisioning service refuses at the level it reports is found out after its session graph
     * was composed, and the graph is then built again at the permitted level (ADR-0012 rule 11's
     * #225 addendum) — so this is null until that happens and the lower level afterwards.
     * `QoeCollector` reads it at the end of the session, which is the moment it is settled.
     */
    public val deliveredSecurityLevel: String?
        get() = deliveredProtection.securityLevel

    /**
     * The decision currently in force: how this player is buffering and what it is selecting
     * tracks under.
     *
     * Reported rather than merely applied, because half of it is otherwise invisible. The selection
     * half is observable as Media3's own [Player.getTrackSelectionParameters] on a player built
     * without retargetable components; the buffer half is handed to a `LoadControl` and Media3
     * offers no way to read it back, so without this a consumer — or a test — has no way to see
     * what their policy actually asked for.
     *
     * **It can change.** On a player whose engine can honour a changed decision whole — one built
     * with `superplayer-abr`'s policy — the policy is consulted again on the [DecisionTrigger]s and
     * this reports its latest answer; each change is also a `TelemetryEvent.DecisionChanged`, and
     * `SessionStarted.decision` is the value this had when the session began. On every other player
     * it is the decision made at construction, for the player's lifetime (ADR-0009 rules 5 and 6).
     * Written on the application thread; readable from any.
     */
    @Volatile
    public var playbackDecision: PlaybackDecision = initialDecision
        private set

    /**
     * The platform's memory-pressure signal, forwarded to the collector.
     *
     * Registered here rather than reached for by the collector because a context is something the
     * facade has and a collector attached to a built player does not — the same reason core signals
     * the session boundaries. What to do about pressure is still the collector's decision; see
     * [TelemetryCollector.onMemoryPressure].
     *
     * Null when no collector was supplied, so a player built without telemetry allocates nothing
     * and registers nothing (ADR-0008 rule 2).
     */
    private val telemetryMemoryPressure: ComponentCallbacks2? =
        if (telemetry == null) {
            null
        } else {
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    // TRIM_MEMORY_UI_HIDDEN says the app went to the background and says nothing
                    // about memory; a player still playing there is the ordinary background-audio
                    // case. Everything at RUNNING_LOW or above is the platform actually asking.
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
                        level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                    ) {
                        telemetry.onMemoryPressure()
                    }
                }

                // Required by the interface and deprecated since API 34 in favour of the levels
                // above, which the platform sends first. Forwarded anyway: on an API 24 device it
                // is the only signal that arrives, and minSdk is 24.
                @Deprecated("Superseded by onTrimMemory levels; still delivered below API 34.")
                override fun onLowMemory() {
                    telemetry.onMemoryPressure()
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit
            }
        }

    init {
        telemetryMemoryPressure?.let { applicationContext?.registerComponentCallbacks(it) }
        // Wired here rather than in the builder so the loop never holds a half-built facade: it is
        // started, and can first call back, only once `build()` has this object in hand.
        reapplication?.onDecisionChanged = { decision, trigger ->
            val previous = playbackDecision
            playbackDecision = decision
            // One pool, one decision: what a coordinator reads on its next invalidation.
            pooled?.components?.decision = decision
            // The live half travels on the media item, so a changed one is laid into the item that
            // is playing — replaced in place, which Media3's own sources do without re-preparing
            // when nothing but the live configuration differs. The other halves went to the target.
            if (decision.liveLatency != previous.liveLatency) {
                delegate.currentMediaItem?.let { current ->
                    val updated = current.withLiveLatency(decision.liveLatency)
                    if (updated != current) delegate.replaceMediaItem(delegate.currentMediaItemIndex, updated)
                }
            }
            telemetry?.decisionChanged(decision, trigger)
        }
    }

    /**
     * Wraps an engine that has already been built.
     *
     * The profile and its decision are passed in rather than derived, because a decision is applied
     * to an engine as it is constructed and this constructor is downstream of that. They default to
     * what [Builder] would have produced with no profile set, for the one caller that has no engine
     * construction of its own to speak of: Media3's forwarding-contract harness, which hands this
     * class a mock.
     */
    internal constructor(
        exoPlayer: ExoPlayer,
        profile: PlaybackProfile = PlaybackProfile.VIDEO_ON_DEMAND,
        playbackDecision: PlaybackDecision =
            PlaybackPolicy.forProfile(profile).decide(PlaybackConditions()),
        builtWithTrackSelectionParameters: TrackSelectionParameters? = null,
        telemetry: TelemetryCollector? = null,
        applicationContext: Context? = null,
        // Deliberately not [Builder]'s rule, which also enables the holder for CMCD. This
        // constructor wraps an engine somebody else built, so nothing here composed a transfer
        // chain and no CMCD seam can be reading the id — a collector is the only possible reader,
        // and `enabled` says exactly that. [Builder] passes its own and never falls through to this.
        measurementSession: MeasurementSession = MeasurementSession(enabled = telemetry != null),
        // Null for an engine somebody else built, which has nothing of core's to re-target.
        reapplication: DecisionReapplication? = null,
        // False for an engine somebody else built: no chain of core's has a cache slot to key.
        identifiesContent: Boolean = false,
        // Null for an engine somebody else built: no pool built it.
        pooled: PooledEngine? = null,
        // Null for an engine somebody else built: no resilience filled the slot, so nothing is asked.
        playerStateRungs: PlayerStateRungs? = null,
        // Empty for an engine somebody else built: no slot of core's opened a session on it.
        deliveredProtection: DeliveredProtection = DeliveredProtection(),
        // Null for the same reason: no protection of core's composed a graph, so there is none to
        // compose again.
        protectionRepair: ProtectionRepair? = null,
    ) : this(
        exoPlayer,
        profile,
        playbackDecision,
        ForwardingPlayer(exoPlayer),
        builtWithTrackSelectionParameters,
        telemetry,
        applicationContext,
        measurementSession,
        reapplication,
        identifiesContent,
        pooled,
        playerStateRungs,
        deliveredProtection,
        protectionRepair,
    )

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

    /**
     * Where each piece of content was left, keyed by [MediaRequest.contentId] — what
     * [MediaRequest.StartPosition.ResumeFromLastKnown] reads.
     *
     * In memory and for the life of this player only. Persisting it would mean choosing a storage
     * mechanism on a consumer's behalf; what crosses a configuration change is a [PlaybackSnapshot],
     * which hands the consumer a `Bundle` and lets them decide where it lives.
     *
     * Bounded, and least-recently-used first out. A feed UI can move through thousands of items in a
     * session, and an unbounded map of ids a consumer chose the length of is a slow leak in exactly
     * the kind of app the player pool exists for. [MAX_REMEMBERED_POSITIONS] is generous next to any
     * plausible back-stack of things a viewer might return to, and cheap: a few kilobytes at worst.
     * Eviction is documented on [MediaRequest.StartPosition.ResumeFromLastKnown], because it is a
     * limit of that promise rather than an implementation detail a consumer can ignore.
     *
     * Not synchronized, unlike [wrappedListeners]: this is only touched from [setMediaRequest],
     * which is a [Player] method call and therefore already on the application looper.
     */
    private val lastKnownPositions =
        object : LinkedHashMap<String, Long>(0, 0.75f, /* accessOrder= */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
                size > MAX_REMEMBERED_POSITIONS
        }

    /**
     * The last [MediaRequest] this player was given, which is the only description of what is
     * playing that a [PlaybackSnapshot] can be made from.
     *
     * It can go stale: [Player.setMediaItem] and the rest of the playlist API are forwarded straight
     * to the engine by delegation, so a consumer mixing them with [setMediaRequest] moves the player
     * on without passing through here. [saveSnapshot] therefore checks this against what is actually
     * loaded rather than trusting it — see there.
     */
    private var currentRequest: MediaRequest? = null

    /**
     * Which entry of [currentRequest]'s sources is open — 0 until rung 4 moves it, and back to 0
     * whenever content is adopted, restored or reset.
     *
     * Held rather than derived, because the item carries a URI and a URI does not say which candidate
     * it was: two entries of one request may differ only in a signed query string, and a session that
     * re-derived its index would fall back to the source it had just left.
     *
     * Touched on the application thread only — [adopt] is reached from a [Player] call or a session
     * callback, and [openNextSource] from a player error, which Media3 delivers on the application
     * looper.
     */
    private var sourceIndex: Int = FIRST_SOURCE

    /**
     * The failure the rungs core performs were last asked about, and the answer, so that the question
     * is put to [playerStateRungs] exactly once per failure.
     *
     * Three callers ask, in this order and in the same dispatch: a collector, because telemetry
     * reports what the classifier said ([classify]); the listener wrappers, because a failure core
     * takes on is one the consumer is not told about ([withholdsFromConsumer]); and the engine
     * listener registered below, which performs the rung. The answer has to be the same for all of
     * them, and it cannot be recomputed — by the time the second asks, the rung has been performed
     * and its preconditions are gone.
     *
     * [failureTypedError] is decided in the same breath and for the same reason, and it is the reason
     * the decision is taken eagerly rather than on first need: it carries the position playback had
     * reached, which a rung 4 or a rung 5 performed in the meantime would have moved (ADR-0011
     * rule 10). [failureDelivered] is what a consumer's listener is handed for a failure that reached
     * rung 6 — built once, so the listener and [getPlayerError] hand over one object.
     */
    private var failureDecidedOn: PlaybackException? = null
    private var failureRepair: FailureRepair = FailureRepair.NONE
    private var failureTypedError: SuperPlayerError? = null
    private var failureDelivered: PlaybackException? = null

    /**
     * How many decoders this player has recreated (rung 5) without playback getting any further, and
     * the position the last recreation was performed at — the bound that keeps a rung from becoming
     * a loop, and the memory that lets it start over when the rung worked.
     *
     * Counted against the *position* rather than against the player: a device that loses its decoder
     * at an HDMI event twice in one long viewing has had two remedies work, while a decoder that will
     * not come back fails again where it failed before, having played nothing in between
     * ([mayRecreateDecoder]).
     *
     * Touched on the application thread only, like [sourceIndex] and for the same reason.
     */
    private var decoderRecreations: Int = 0
    private var recreatedAtPositionMs: Long = C.TIME_UNSET

    /**
     * How many times this player has re-opened its protection at a permitted lower level, bounded by
     * [MAX_PROTECTION_REOPENS] — which says why this one is never started over.
     *
     * Touched on the application thread only, like the two above and for the same reason.
     */
    private var protectionReopens: Int = 0

    // Where a climb out of the top of `superplayer-resilience`'s ladder reaches the player: rungs 4
    // and 5 are performed here, on a player that has somebody to ask and on no other (ADR-0011
    // rules 5 and 14).
    //
    // On the engine rather than through `addListener`, so it is not wrapped, and registered in the
    // constructor so that it is ahead of every consumer's: Media3 dispatches an event to its
    // listeners in registration order, and a consumer's wrapper asks this player what to withhold.
    init {
        playerStateRungs?.let {
            delegate.addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        when (repairFor(error)) {
                            FailureRepair.REOPEN_PROTECTION -> reopenProtection()
                            FailureRepair.NEXT_SOURCE -> openNextSource()
                            FailureRepair.RECREATE_DECODER -> recreateDecoder()
                            FailureRepair.NONE -> Unit
                        }
                    }
                },
            )
        }
    }

    /**
     * Says that a viewer just asked for playback — the start boundary of time to first frame.
     *
     * Call it at the tap, before whatever the app does to turn that tap into a [MediaRequest]: the
     * catalogue lookup, the entitlement check, the navigation transition. That interval is part of
     * what the viewer waited through, and `PRD.md` §3.4 puts the metric's boundary at intent rather
     * than at `prepare()` precisely so that it is counted. The library cannot see a tap, which is
     * why this is declared rather than observed, and it is the whole reason this method exists.
     *
     * Cheap and safe to call with no telemetry attached: it reads the monotonic clock and hands the
     * reading to the collector, and a player built without one does neither. Safe to call before the
     * content is known, which is the point of it — there is no [MediaRequest] yet at the tap.
     *
     * A session that never gets one is measured from the moment the player took the content on and
     * reports [TtffStartBoundary.CONTENT_ADOPTED], so the two are never silently averaged together.
     */
    public fun declarePlaybackIntent() {
        // ref: CTA-2066 measures video start-up time from the viewer's request; CMCD v2's `msd`
        // (media start delay) uses the same boundary, and agreeing with both is what makes this
        // number comparable outside this app. `elapsedRealtime` rather than the wall clock because
        // the reading is one end of a duration — see `TelemetryEvent` on the two clocks.
        telemetry?.declareIntent(SystemClock.elapsedRealtime())
    }

    /**
     * Plays what [request] describes: the first of its sources, starting where its
     * [MediaRequest.StartPosition] says, identified by its [MediaRequest.contentId].
     *
     * The counterpart of [Player.setMediaItem], and the same contract: it replaces the current item
     * and [prepare] still has to be called. What it adds is the identity and the start position,
     * neither of which a `MediaItem` can express — see [MediaRequest] for why that matters.
     *
     * The outgoing content's position is remembered first, so a later
     * [MediaRequest.StartPosition.ResumeFromLastKnown] for it returns here.
     *
     * On a player from a [PlayerPool] with a `PreloadCoordinator` attached, content the coordinator
     * has prefetched starts from what it prefetched. Nothing else differs: the identity, the start
     * position and the measurement session are the ones this call produces on any player (ADR-0010
     * rule 7).
     */
    public fun setMediaRequest(request: MediaRequest) {
        val adopted = adopt(request)
        // Asked after adoption, so a warm source is played under exactly the session and start
        // position a cold one would have been.
        when (val warm = pooled?.sourceFor(this, adopted.mediaItem)) {
            is WarmStart.Source -> exoPlayer.setMediaSource(warm.source, adopted.startPositionMs)

            // Already prepared on this item with its decoders initialised: setting the source again
            // would tear exactly that down. Only a start position other than where it was held moves it.
            WarmStart.Prepared ->
                if (adopted.startPositionMs != C.TIME_UNSET && adopted.startPositionMs != delegate.currentPosition) {
                    delegate.seekTo(adopted.startPositionMs)
                }

            null -> delegate.setMediaItem(adopted.mediaItem, adopted.startPositionMs)
        }
    }

    /**
     * Prepares [source] on this player while it is idle in its pool, without adopting anything: a
     * decoder held warm for a row the feed has not reached (ADR-0010 rule 10's warm decoder).
     *
     * No request, no session, no position and no `playWhenReady`: nothing plays and nothing is measured
     * until the feed calls [setMediaRequest] for that row on this player, which then keeps what was
     * prepared ([WarmStart.Prepared]). That is how rule 7's "only through `setMediaRequest`" still holds
     * for what a viewer sees and a sink receives. A pooled player is idle without a surface — the pool
     * detached it — so a warm decoder draws nothing either.
     */
    internal fun holdWarm(source: MediaSource) {
        exoPlayer.setMediaSource(source)
        exoPlayer.prepare()
    }

    /**
     * Takes [request] on as what this player is playing, and works out where it starts — everything
     * [setMediaRequest] does except handing the result to the engine.
     *
     * Split out because there are two callers and only one of them may call `setMediaItem`. The
     * other is `PlaybackSession`, which resolves a *controller's* media id into a request and then
     * has to give Media3 the item back rather than loading it: a session callback that loaded the
     * item itself would race the load Media3 performs on return from that callback.
     *
     * Both callers need the identical bookkeeping, and that is the point of it being here rather
     * than duplicated: the outgoing content's position is remembered, and the incoming request
     * becomes the one a [PlaybackSnapshot] would name — so content started from a notification, a
     * car head unit or a watch resumes exactly like content started from the app.
     */
    internal fun adopt(request: MediaRequest): AdoptedRequest {
        rememberPositionOfCurrentContent()
        currentRequest = request
        // Content taken on afresh starts at its first source, whatever the outgoing content had
        // fallen back to. A request that is given to a player a second time gets the whole ladder
        // again, which is the honest answer: the CDN that failed a minute ago may be well now, and
        // nothing here remembers a failure across an adoption.
        sourceIndex = FIRST_SOURCE
        // The same answer for rung 5's bound, and for the same reason: what it counts is decoders
        // recreated for content this player is no longer playing.
        forgetRungsClimbed()
        // The one place a measurement session can begin, for the same reason this method exists at
        // all: both callers arrive here, so content resolved from a notification or a car head unit
        // opens a session exactly as content set from the app does. A collector that already has one
        // open closes it first — core signals the edge, the collector keeps the bookkeeping.
        //
        openMeasurementSession(request.contentId)
        // The decision's live half rides on the item; EngineBinding.kt says why it goes here.
        return AdoptedRequest(
            itemOf(request).withLiveLatency(playbackDecision.liveLatency),
            request.resolvedStartPositionMs(),
        )
    }

    /**
     * [request] as this player's engine item: carrying its [ContentIdentity] when the engine has a
     * cache to key by it, and not otherwise. Every path that turns a request into an item for this
     * player goes through here, so an item a controller added and an item the app set are keyed alike.
     */
    internal fun itemOf(request: MediaRequest): MediaItem = itemOf(request, FIRST_SOURCE)

    /** [itemOf], opening a named candidate: [FIRST_SOURCE] for everything but rung 4's re-adoption. */
    private fun itemOf(request: MediaRequest, source: Int): MediaItem =
        request.toMediaItem(identified = identifiesContent, source = source)

    /**
     * Rung 4 of ADR-0011's ladder: the next entry of the current request's sources, opened at the
     * instant playback had reached, under the identity the session already has (rule 5).
     *
     * Called only from the failure listener, which is registered only where a resilience filled the
     * slot, and only for a failure [repairFor] has already routed to this rung.
     * What is decided *here* is the half the ladder cannot see — whether there is a next source, and
     * whether this player is still playing the content that named it.
     *
     * Three things this deliberately does not do:
     *
     * - **It does not open a measurement session.** A fallback that rescued a session is a fact about
     *   that session (ADR-0011 rule 10), so the telemetry session and the CMCD `sid` are the ones the
     *   session started with — which is also why this is not routed through [adopt], whose whole job
     *   is the bookkeeping of taking on *different* content.
     * - **It does not re-read the request's [MediaRequest.StartPosition].** Rule 9 says core carries
     *   the held position across the operation explicitly, and a `Beginning` read again is exactly
     *   the restart-from-zero §3.3 calls worse than the error.
     * - **It does not seek afterwards.** The position goes into `setMediaItem`, so the new source is
     *   prepared at it rather than prepared and then moved, and a seek on an unprepared timeline
     *   would be a second position for the same fallback to disagree about.
     */
    private fun openNextSource(): Boolean {
        val request = currentRequest ?: return false
        if (!hasNextSource(request)) return false

        // Read before anything replaces the item, which is what makes it the position playback
        // reached rather than the position the next item happens to report.
        val positionMs = positionOfCurrentContent()
        sourceIndex++
        // Updated as `ResumeFromLastKnown` would have it, which rule 9 asks for in as many words. The
        // ordinary path would reach the same number later — [adopt] remembers the outgoing content's
        // position whenever this player moves on — so what this line adds is that the map is right at
        // the instant of the switch rather than only at the next adoption.
        lastKnownPositions[request.contentId] = positionMs
        delegate.setMediaItem(itemOf(request, sourceIndex).withLiveLatency(playbackDecision.liveLatency), positionMs)
        delegate.prepare()
        return true
    }

    /**
     * Whether [request] has a candidate left after the one open, on a player still playing it.
     *
     * The second half is the one worth stating: a consumer who called [Player.setMediaItem] after
     * [setMediaRequest] has moved the player on without passing through here, so [currentRequest]
     * describes something that is no longer loaded — the same staleness [saveSnapshot] guards
     * against, guarded the same way rather than by trusting the bookkeeping.
     */
    private fun hasNextSource(request: MediaRequest): Boolean =
        sourceIndex + 1 in request.sources.indices &&
            delegate.currentMediaItem?.mediaId == request.contentId

    /**
     * Rung 5 of ADR-0011's ladder: the decoder recreated, which in Media3 is this player prepared
     * again where it stands (rule 5).
     *
     * Called only from the failure listener, and only for a failure [repairFor] has already routed
     * here — which is a failure rung 4 declined, so the order rule 7 fixes is imposed by the one
     * place both rungs are decided rather than by either rung.
     *
     * **Nothing is replaced and nothing is re-adopted, and that is the whole of rule 9 here.** Media3
     * keeps the playlist and the position of a player that failed — it goes to `STATE_IDLE` holding
     * both — and `prepare()` retries from exactly there (// ref: `Player.prepare()`, "if the player
     * has failed, calling this method will retry the playback"). So the position is carried by not
     * being touched, the way it is for the rungs Media3 performs inside a load, and a seek added
     * around this would be SuperPlayer inventing a second position for the operation to disagree
     * about. What the position *is* still goes into the remembered-position map before the
     * re-prepare, so `ResumeFromLastKnown` agrees at the instant of the recreation rather than only
     * at the next adoption — which is the half of rule 9 that rung 4 performs in the same words.
     *
     * The identity, the measurement session and the CMCD `sid` are untouched for the reason rung 4
     * leaves them untouched (ADR-0011 rule 10): one viewing rescued is one session. Here that needs
     * no code at all, because the item is the item that was already playing.
     */
    private fun recreateDecoder(): Boolean {
        if (delegate.currentMediaItem == null) return false

        // Read and recorded before the re-prepare, so both are the position playback had reached
        // rather than whatever the prepared player first reports.
        recreatedAtPositionMs = positionOfCurrentContent()
        decoderRecreations++
        rememberPositionOfCurrentContent()
        delegate.prepare()
        return true
    }

    /**
     * Core's half of the protection repair: whether *this* failure, on *this* player, is one a
     * permitted lower level could be the remedy for (ADR-0012 rule 11's #225 addendum).
     *
     * Two halves, both core's, and neither is a classification of its own. The first is
     * [SuperPlayerError.lowerSecurityLevelMayHelp], which is the one classifier's own answer to
     * exactly this question and is why core matches on no class name here: a licence that expired
     * and a protection stack that faulted are not failures of the level asked for, and telling them
     * from a device the provisioning service or the scheme refused is what the taxonomy is for
     * (ADR-0011 rule 1). A player with no resilience has no typed error and therefore no repair,
     * which is the same "pays nothing" every rung has.
     *
     * The second is the bound, [MAX_PROTECTION_REOPENS], which says why it is one and why it never
     * starts over. Whether there is a permitted level left to ask for is the protection's half and
     * is not visible from here.
     */
    private fun mayReopenProtection(): Boolean {
        // Nothing prepared is nothing to re-prepare, exactly as for rung 5: a failure before there
        // was an item is not a session that opened at the wrong level.
        if (delegate.currentMediaItem == null) return false
        if (failureTypedError?.lowerSecurityLevelMayHelp != true) return false
        return protectionReopens < MAX_PROTECTION_REOPENS
    }

    /**
     * The protection opened again at the level the licence server's operator permitted, and this
     * player prepared on it at the instant playback had reached.
     *
     * Called only from the failure listener, and only for a failure [repairFor] has already routed
     * here. The graph is `superplayer-drm`'s to rebuild and the player is core's to re-prepare,
     * which is the same split every rung has and the reason [ProtectionRepair] is two methods.
     *
     * **The item is re-set rather than merely re-prepared**, and that is the one mechanism worth
     * knowing: `ExoPlayer` asks the media source factory for a source when an item is set, and the
     * factory asks the `DrmSessionManagerProvider` for a manager as it builds one — so a graph
     * rebuilt behind the provider reaches the renderer only through a source built after it.
     * `prepare()` alone retries the source that already holds the refused manager (// ref:
     * `Player.prepare()` retries *the current* media item).
     *
     * The same item object, so the identity, the CMCD `sid`, the measurement session and the cache
     * key are the ones the viewing started with (ADR-0011 rule 10 for the same reason rung 4 keeps
     * them): one viewing rescued is one session. The position goes into `setMediaItem` rather than
     * into a seek afterwards, and into the remembered-position map before it, which is rung 4's
     * rule 9 in the same words.
     */
    private fun reopenProtection(): Boolean {
        val item = delegate.currentMediaItem ?: return false

        // Read before anything replaces the item, for the reason rung 4 reads it there.
        val positionMs = positionOfCurrentContent()
        protectionReopens++
        rememberPositionOfCurrentContent()
        protectionRepair?.reopenAtLowerLevel()
        delegate.setMediaItem(item, positionMs)
        delegate.prepare()
        return true
    }

    /**
     * Core's half of rung 5: whether recreating a decoder on this player could be a remedy rather
     * than the start of a loop.
     *
     * The bound is here rather than in the ladder because what it counts is the player's own history
     * — how many decoders this player has already recreated without playback advancing — and none of
     * that is visible from a classification. It is correctness rather than policy (ADR-0011 rule 12):
     * every profile wants a decoder back and none wants a player re-preparing itself for ever.
     *
     * The counter starts over wherever the viewer got further, which is what makes the bound a bound
     * on *futile* recreations: a long viewing that loses its decoder to a surface change, recovers,
     * plays for an hour and loses it again has had a remedy work twice, while a decoder that will not
     * come back fails again at the position it failed at, having played nothing in between.
     */
    private fun mayRecreateDecoder(): Boolean {
        // Nothing prepared is nothing to re-prepare: a failure before there was an item is not a
        // decoder that stopped working.
        if (delegate.currentMediaItem == null) return false
        if (recreatedAtPositionMs != C.TIME_UNSET && positionOfCurrentContent() > recreatedAtPositionMs) {
            decoderRecreations = 0
        }
        return decoderRecreations < MAX_DECODER_RECREATIONS
    }

    /**
     * Drops what has been climbed for content that is no longer the content that failed: what
     * [mayRecreateDecoder] counts, and the ladder's own record of the rungs it tried
     * ([SuperPlayerError.rungsTried]).
     *
     * Both halves in one place because they are one fact told twice — a player given different
     * content gets the whole ladder again, since the CDN that failed a minute ago may be well now —
     * and a caller that remembered one half and forgot the other would report a new failure with an
     * old history.
     */
    private fun forgetRungsClimbed() {
        decoderRecreations = 0
        recreatedAtPositionMs = C.TIME_UNSET
        playerStateRungs?.forgetClimb()
    }

    /**
     * Which rung, if any, this player performs for [error] — the one place rungs 4 and 5 are routed,
     * and therefore the one place ADR-0011 rule 7's order between them is imposed.
     *
     * **The protection's repair is offered the failure before either of them** (ADR-0012 rule 11's
     * #225 addendum), and that is also an order rather than a preference: a session refused at the
     * level it asked for is refused at every source, because a request's sources are one piece of
     * content in more than one place and protection is the player's (ADR-0012 rule 1). Rung 4 would
     * download a second manifest to meet the same refusal, and rung 5 would build a decoder nothing
     * has keys for. It is not a rung, gains no `FallbackRung` and moves no ceiling — what makes it
     * reachable is a permission rather than a classification ([ProtectionRepair]).
     *
     * Rung 4 is offered the failure first and rung 5 only where it declined, which is the order and
     * not a preference. It costs a `Device.DecoderTransient` nothing, because the ladder refuses that
     * class the next source in as many words — a decoder that stopped is not a property of the source,
     * and a second manifest fetched before the decoder is recreated is a startup a viewer waits
     * through for nothing.
     *
     * Each rung is a pair of halves: the ladder's, which is what the class permits, and core's, which
     * is whether this player can perform it at all — a next source to open, a decoder recreation that
     * is not the third at one position. Both are asked here, so the memo below is an answer to "which
     * rung will be performed" rather than to "which rung would be allowed".
     */
    private fun repairFor(error: PlaybackException?): FailureRepair {
        val rungs = playerStateRungs ?: return FailureRepair.NONE
        if (error == null) return failureRepair
        if (error !== failureDecidedOn) {
            failureDecidedOn = error
            failureDelivered = null
            // Before any rung is routed, let alone performed: the position on it is where the viewer
            // had got to, and a rung that re-prepares or replaces the item moves it.
            failureTypedError = rungs.typedErrorFor(error, positionOfCurrentContent())
            failureRepair = when {
                mayReopenProtection() && protectionRepair?.mayReopenAtLowerLevel() == true ->
                    FailureRepair.REOPEN_PROTECTION

                currentRequest?.let { hasNextSource(it) } == true && rungs.opensNextSource(error) ->
                    FailureRepair.NEXT_SOURCE

                mayRecreateDecoder() && rungs.recreatesDecoder(error) -> FailureRepair.RECREATE_DECODER

                else -> FailureRepair.NONE
            }
        }
        return failureRepair
    }

    /**
     * Whether [error] is one this player takes on itself — at rung 4, at rung 5, or by re-opening
     * its protection at a permitted lower level — and therefore one the consumer is never told
     * about.
     *
     * A rescued session that also reported an error would be two contradictory accounts of one
     * viewing: ADR-0011 rule 10 makes the typed error rung 6 — what a consumer is handed once nothing
     * below it worked — so an error a rung below repairs is not an error the `Player` API delivers.
     * That rule's addendum carries the argument, and **this is the one deliberate exception to
     * ADR-0003 rule 3's "a wrapper forwards every callback"**, recorded at that rule too and bounded
     * where the wrapper reads it ([reportingSourceAs]). The protection's re-open is inside that one
     * exception rather than beside it: the argument is about a session core rescued rather than
     * about which mechanism rescued it, and a viewer whose stream came back at the permitted level
     * has had no error to handle either.
     *
     * The listener wrappers ask this before forwarding, which is the only place the callback can be
     * withheld: Media3 hands one event to every listener in one pass, so a decision taken after the
     * pass began would come too late for the listeners already visited.
     *
     * Memoized against the failure, and null means "the error being cleared": the `prepare` either
     * rung performs clears it immediately, and a consumer who never saw it must not see it go away
     * either.
     */
    private fun withholdsFromConsumer(error: PlaybackException?): Boolean =
        repairFor(error) != FailureRepair.NONE

    /**
     * What [error] is, in SuperPlayer's own vocabulary, or null on a player built without resilience
     * — which has nobody to ask and therefore classifies nothing (ADR-0011 rule 14).
     *
     * The same object a consumer is handed as the `cause` of a failure nothing rescued, answered for
     * any failure this player has surfaced rather than only for those: what a failure *is* does not
     * depend on whether a rung repaired it, and a collector reporting one that was repaired reports
     * the same class (ADR-0011 rule 3).
     *
     * This is the door `superplayer-telemetry` reads the classification through. A collector watches
     * Media3's analytics rather than the facade — that is what a collector is for — so the exception
     * it is handed is the engine's, with none of this on it; asking the player is what makes
     * `PlaybackFailure.classification` and the consumer's typed error one answer rather than two. It
     * is public for the same reason `exoPlayer` is: `superplayer-telemetry` is not a friend of core
     * and reaches it as a consumer does (ADR-0008), and an app that keeps its own error reporting has
     * exactly the same question to ask.
     *
     * Call it on the application thread, as every `Player` method is called.
     */
    public fun classify(error: PlaybackException): SuperPlayerError? {
        repairFor(error)
        return failureTypedError
    }

    /**
     * What a consumer's listener is handed for [error], which for a failure that reached rung 6 is a
     * `PlaybackException` carrying the typed error as its `cause` (ADR-0011 rule 10).
     *
     * The engine's own exception cannot carry it — a cause is fixed when an exception is built, and
     * the engine builds this one — so the delivery is a `PlaybackException` of core's with the
     * engine's code and message, the typed error as its cause, and the engine's exception under
     * *that*. Nothing is lost and nothing is invented: a consumer switching on `errorCode` goes on
     * working, and one walking the chain still reaches Media3's own exception and its stack.
     *
     * Only for a failure that reached rung 6, because only then is there a typed error to deliver: a
     * failure a rung repaired is withheld entirely ([withholdsFromConsumer]), and one on a player
     * with no resilience is Media3's unchanged.
     */
    private fun deliveredToConsumer(error: PlaybackException?): PlaybackException? {
        if (error == null) return null
        if (repairFor(error) != FailureRepair.NONE) return error
        val typed = failureTypedError ?: return error
        return failureDelivered
            ?: PlaybackException(error.message, typed, error.errorCode).also { failureDelivered = it }
    }

    /** Drops the memo, so nothing of a finished session's failure is held or answered for. */
    private fun forgetWithheldFailure() {
        failureDecidedOn = null
        failureRepair = FailureRepair.NONE
        failureTypedError = null
        failureDelivered = null
    }

    /**
     * Opens a measurement session for [contentId] and tells the collector, if there is one.
     *
     * The id is minted here rather than by the collector because CMCD's `sid` is the same string,
     * and the transfer chain reads it from the same holder — see [MeasurementSession]. A player with
     * no reader for an id has no id to mint, and therefore no session to signal either.
     *
     * One function for the two callers that open a session — [adopt] and [restoreSnapshot] — because
     * a second copy that minted without signalling, or signalled without minting, would break the
     * join silently and in only one of the two paths.
     */
    private fun openMeasurementSession(contentId: String) {
        measurementSession.open(contentId)?.let { sessionId ->
            telemetry?.startSession(contentId, sessionId)
        }
    }

    /**
     * What this player is doing, as a value that outlives it — the answer to a configuration change.
     *
     * Take it while the player is still alive, before [release], and pour it into the player that
     * replaces it with [restoreSnapshot]. [PlaybackSnapshot] documents what travels and what does
     * not, and carries the `onSaveInstanceState` shape of it.
     *
     * The snapshot names what is playing only if this player can name it: an item loaded through
     * [Player.setMediaItem] rather than [setMediaRequest] has no content identity, and one loaded
     * through [setMediaRequest] and then replaced by a raw `MediaItem` would leave the remembered
     * request describing something that is no longer loaded. Both are ruled out by checking the
     * request against the engine's current item rather than by trusting the bookkeeping.
     */
    public fun saveSnapshot(): PlaybackSnapshot {
        val request = currentRequest?.takeIf { it.contentId == delegate.currentMediaItem?.mediaId }
        val positionMs =
            if (request == null) C.TIME_UNSET else positionOfCurrentContent()

        return PlaybackSnapshot(
            request = request,
            positionMs = positionMs,
            playWhenReady = delegate.playWhenReady,
            // The current content's own position is folded in, so that the memory a restored player
            // starts with is complete: without it, restoring and then switching away and back would
            // resume the one thing the viewer was actually watching from the beginning.
            rememberedPositions = buildMap {
                putAll(lastKnownPositions)
                if (request != null) put(request.contentId, positionMs)
            },
        )
    }

    /**
     * Puts this player back where [snapshot] left off: the content, the position within it, the
     * intent to play, and the resume positions the saved player had accumulated.
     *
     * Like [setMediaRequest], this loads rather than prepares — call [prepare] afterwards, which is
     * also where an Activity being recreated would call it anyway. Intended for a freshly built
     * player; restoring into one that is already playing replaces what it is playing.
     *
     * A snapshot with no [PlaybackSnapshot.request] restores the positions and the intent and leaves
     * the current content alone, which is the honest answer for a player whose content had no
     * identity to save. See [PlaybackSnapshot].
     */
    public fun restoreSnapshot(snapshot: PlaybackSnapshot) {
        // Ahead of the media item, so that the restored memory is in place before anything can read
        // it, and least-recently-used order is inherited from the order they were saved in.
        lastKnownPositions.putAll(snapshot.rememberedPositions)

        snapshot.request?.let { request ->
            currentRequest = request
            // At the first source, not at whichever one the saved player had fallen back to: a
            // snapshot carries the request and the position and no rung, because the ladder is about
            // what is failing *now* and a restored player is a new engine on a new network.
            sourceIndex = FIRST_SOURCE
            // And on new decoders: nothing of what the saved player recreated is this player's.
            forgetRungsClimbed()
            // A restored player measures under a session of its own rather than continuing the saved
            // one: the player that took the snapshot ended its session when it was released, and
            // without this the whole of what a viewer watches after a rotation would go unmeasured.
            // Deliberately not routed through `adopt`, which would remember a position for content
            // this player never played.
            openMeasurementSession(request.contentId)
            delegate.setMediaItem(itemOf(request).withLiveLatency(playbackDecision.liveLatency), snapshot.positionMs)
        }
        delegate.playWhenReady = snapshot.playWhenReady
    }

    /**
     * Records where the currently-playing content has got to, if it is content this player can name.
     *
     * An item set through [Player.setMediaItem] rather than [setMediaRequest] carries Media3's
     * default media id, which says nothing about identity and is shared by every such item — so it
     * is skipped rather than remembered under a key that would collide with the next one.
     *
     * Content that has ended is recorded at the beginning: see
     * [MediaRequest.StartPosition.ResumeFromLastKnown].
     */
    private fun rememberPositionOfCurrentContent() {
        val contentId = delegate.currentMediaItem?.mediaId ?: return
        if (contentId == MediaItem.DEFAULT_MEDIA_ID) return

        lastKnownPositions[contentId] = positionOfCurrentContent()
    }

    /**
     * Where the currently-playing content has got to, as a resume position.
     *
     * Content that has ended reports the beginning: see
     * [MediaRequest.StartPosition.ResumeFromLastKnown] for why resuming to the end is nobody's
     * intent.
     */
    private fun positionOfCurrentContent(): Long =
        if (delegate.playbackState == Player.STATE_ENDED) 0L else delegate.contentPosition

    /**
     * The start position in the form [Player.setMediaItem] takes: milliseconds, or [C.TIME_UNSET]
     * for "the content's own default position", which is the start of on-demand content and the live
     * edge of a live stream.
     */
    private fun MediaRequest.resolvedStartPositionMs(): Long = when (val position = startPosition) {
        is MediaRequest.StartPosition.Beginning -> C.TIME_UNSET

        is MediaRequest.StartPosition.At -> position.positionMs

        is MediaRequest.StartPosition.ResumeFromLastKnown ->
            lastKnownPositions[contentId] ?: C.TIME_UNSET
    }

    override fun addListener(listener: Player.Listener) {
        val wrapper = synchronized(wrappedListeners) {
            wrappedListeners.getOrPut(listener) {
                listener.reportingSourceAs(this, ::withholdsFromConsumer, ::deliveredToConsumer)
            }
        }
        delegate.addListener(wrapper)
    }

    override fun removeListener(listener: Player.Listener) {
        val wrapper = synchronized(wrappedListeners) { wrappedListeners.remove(listener) }
        delegate.removeListener(wrapper ?: listener)
    }

    /**
     * Returns this player to the state a freshly built one is in, so that [PlayerPool] can hand it
     * to a different item without anything of the previous one coming along.
     *
     * Internal because it is the pool's, not a consumer's: a consumer holding a player they built
     * themselves has no second item to hand it to, and one holding a *pooled* player must go through
     * [PlayerPool.recycle] so the pool's own bookkeeping stays true.
     *
     * The surface goes first. Media3 renders into whatever surface it holds until told otherwise, so
     * a player that cleared its content before its surface has a window — small, but reliably
     * visible on a fast scroll — in which it can put the outgoing item's last decoded frame into a
     * view that now belongs to a different item. That flash is the tell of a hand-rolled pool, and
     * the ordering here is the whole fix.
     *
     * The listeners go too, and that is the part with teeth. A wrapper holds the consumer's
     * listener, which in a feed is a view holder; leaving them attached would keep every view holder
     * the pool has ever served reachable from a player that outlives all of them, and would keep
     * calling them about content they no longer show. It is the same reasoning as [release], one
     * item's lifetime rather than the player's.
     *
     * What deliberately does *not* survive is the remembered-position map. Keeping it would look
     * like a feature — scroll away from an item, scroll back, resume — and it would work only when
     * the item happened to land on the same pooled player, which a viewer experiences as resume that
     * works sometimes. A feed that wants resume across recycling holds the positions itself, or a
     * [PlaybackSnapshot] per item, both of which are right every time.
     */
    internal fun resetForReuse() {
        // Before the content, so nothing decoded for the outgoing item can reach the outgoing view.
        delegate.clearVideoSurface()

        delegate.stop()
        delegate.clearMediaItems()

        synchronized(wrappedListeners) {
            wrappedListeners.values.forEach(delegate::removeListener)
            wrappedListeners.clear()
        }

        delegate.playWhenReady = false
        delegate.repeatMode = Player.REPEAT_MODE_OFF
        delegate.shuffleModeEnabled = false
        delegate.playbackParameters = PlaybackParameters.DEFAULT
        delegate.volume = 1f
        // Whatever the previous holder overrode — a forced audio language, a resolution cap for one
        // item — goes back to what the profile decided when this player was built.
        builtWithTrackSelectionParameters?.let { delegate.trackSelectionParameters = it }
        // And the platform rule, which is the one that must not be inherited. A row that turned
        // focus handling off because it was the silent one in a grid, or that declared its content
        // speech so it would pause rather than duck, has said something about *that item*. Leaving
        // it in place would hand the next item a player that plays audio while requesting no focus —
        // no ducking for a navigation prompt, no pause for a phone call — which is precisely the
        // defect ADR-0006 rule 1 exists to prevent, arriving by the back door. `LifecycleBinding.kt`
        // owns both halves of the value, so this cannot restore something the builder never applied.
        delegate.setAudioAttributes(LIFECYCLE_AUDIO_ATTRIBUTES, HANDLES_AUDIO_FOCUS)

        lastKnownPositions.clear()
        currentRequest = null
        // With the content, because the rung belongs to the content that was failing: a recycled
        // player handed a new row must open that row's first source.
        sourceIndex = FIRST_SOURCE
        forgetWithheldFailure()
        forgetRungsClimbed()
        // The session ends with the item, not with the player. A pooled player that kept one session
        // open across a scroll would report one forty-minute view of nine different things, which is
        // the defect this line exists to prevent. The collector stays attached: the next
        // `setMediaRequest` opens a fresh session on the same player.
        measurementSession.close()
        telemetry?.endSession()
    }

    /**
     * Releases the engine and drops the listener wrappers with it.
     *
     * Each wrapper holds the consumer's listener, which in an app is usually held by an Activity or
     * a ViewModel. Without this they would outlive playback for as long as anything held the
     * facade — a leak whose size is however many listeners were ever registered.
     */
    override fun release() {
        // Before the engine goes, so the session's terminal event is emitted while there is still
        // something to unregister from and `detach` comes off a live engine.
        measurementSession.close()
        telemetry?.endSession()
        telemetry?.detach()
        // The application context outlives every player it built, so a callback left registered
        // here is a leak of this player and everything it holds — for the life of the process.
        telemetryMemoryPressure?.let { applicationContext?.unregisterComponentCallbacks(it) }
        // Before the engine goes, for the same reason: the connectivity callback is registered
        // against the process, and would otherwise hold this player for as long as it lived.
        reapplication?.stop()

        delegate.release()
        synchronized(wrappedListeners) { wrappedListeners.clear() }
        lastKnownPositions.clear()
        currentRequest = null
        sourceIndex = FIRST_SOURCE
        // The memo holds a `PlaybackException`, which holds its cause: dropped with everything else
        // this player was keeping alive.
        forgetWithheldFailure()
        forgetRungsClimbed()
    }

    /**
     * A request this player has taken on, in the two parts Media3 loads content from.
     *
     * [startPositionMs] is [C.TIME_UNSET] for "the content's own default position", which is the
     * start of on-demand content and the live edge of a live stream — the form `setMediaItem` and
     * Media3's session callbacks both take.
     */
    internal class AdoptedRequest(val mediaItem: MediaItem, val startPositionMs: Long)

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
     * The engine's error, as rung 6 delivers it: the same object this player's listeners were handed
     * (ADR-0011 rule 10).
     *
     * Overridden for consistency and for nothing else. ADR-0011 rule 10's addendum says this property
     * is not special-cased for *withholding* — the `prepare` a rung performs clears it, so a consumer
     * reading it sees what their listener was told — and the same sentence is why the substitution
     * does belong here: a consumer who reads the property instead of registering a listener must not
     * get a different account of one failure. There is no substitution to make on a player built
     * without resilience, which is every player before Phase 5.
     */
    override fun getPlayerError(): PlaybackException? = deliveredToConsumer(delegate.playerError)

    /**
     * Builds a [SuperPlayer]. Media3's own construction idiom (ADR-0001, CONTRIBUTING rule 3), so
     * that later configuration — profiles, telemetry, cache policy — arrives as builder methods
     * rather than as a widening constructor.
     */
    public companion object {
        /**
         * How many pieces of content one player remembers a position for.
         *
         * Public because it bounds what [MediaRequest.StartPosition.ResumeFromLastKnown] promises,
         * and a consumer sizing a feed against it should be able to read the number rather than
         * guess it.
         */
        public const val MAX_REMEMBERED_POSITIONS: Int = 128

        /**
         * How many decoders rung 5 recreates at one position before the ladder gives up on it: two.
         *
         * The bound ADR-0011 rule 7 implies and the issue asks for in as many words — a decoder that
         * fails to come back must not loop — and it is a count of *futile* attempts, since
         * [mayRecreateDecoder] starts over wherever playback advanced.
         *
         * Two rather than one, because the second attempt has a different cause to answer: the first
         * covers the ordinary case this rung exists for, a decoder lost to a surface change or an
         * HDMI event and available again immediately (// ref: `MediaCodec.CodecException.isTransient`
         * — the platform saying the resource was momentarily unavailable), while the second covers a
         * device where that moment had not passed when the first re-prepare asked — a feed holding
         * every instance the device has is the case a `PlayerPool` makes ordinary. Two rather than
         * more, because a third failure at a position that has played nothing in between is a decoder
         * that is not coming back, and a viewer is better served by rung 6's typed error than by a
         * player re-preparing itself behind a spinner. Internal rather than public: unlike
         * [MAX_REMEMBERED_POSITIONS] it bounds no promise a consumer writes code against, and a
         * consumer who hits it sees the error rather than the count.
         */
        internal const val MAX_DECODER_RECREATIONS: Int = 2

        /**
         * How many times one player re-opens its protection at a permitted lower level: once.
         *
         * The bound ADR-0012 rule 11's #225 addendum asks for, here rather than in
         * `superplayer-drm` for the reason [MAX_DECODER_RECREATIONS] is here rather than in the
         * ladder: what it bounds is an operation on this player's own state, and a repair free to
         * repeat is a loop rather than a remedy — a refusal the lower level does not cure fails
         * again the moment the new graph asks.
         *
         * One rather than two, and the asymmetry with [MAX_DECODER_RECREATIONS] is the whole
         * argument: a decoder may come back on the second ask because what stopped it was a moment
         * passing, while a licence server's operator publishes one set of permitted levels and
         * asking again cannot enlarge it. Widevine has exactly one level below `L1`, so a second
         * attempt would be an attempt at the level already in force. Counted for the player's
         * lifetime and never started over, unlike the decoder count, because a lowered level is a
         * property of the session graph rather than of the content playing through it: new content
         * on this player is still protected by the graph the first content's failure rebuilt.
         */
        internal const val MAX_PROTECTION_REOPENS: Int = 1
    }

    /**
     * Which of the two rungs core performs (ADR-0011 rule 5) a surfaced failure was routed to, if
     * either.
     *
     * A named answer rather than a boolean because two callers read it for different purposes in one
     * dispatch — the listener wrappers ask only whether the failure is withheld, while the engine
     * listener has to perform the right remedy — and a pair of booleans would let the two disagree.
     */
    private enum class FailureRepair { NONE, REOPEN_PROTECTION, NEXT_SOURCE, RECREATE_DECODER }

    public class Builder(private val context: Context) {

        private var engineConfigurator: ((EngineConfiguration) -> Unit)? = null
        private var profile: PlaybackProfile = PlaybackProfile.VIDEO_ON_DEMAND
        private var policy: PlaybackPolicy? = null
        private var telemetry: TelemetryCollector? = null
        private var cache: ContentCache? = null
        private var resilience: PlaybackResilience? = null
        private var drm: PlaybackDrm? = null
        private var pooledEngine: PooledEngine? = null

        /**
         * The CMCD mode a consumer chose, or null for "whatever the profile defaults to".
         *
         * Null rather than eagerly resolved through `StaticCmcdPolicy`, because [setProfile] may be
         * called after [setCmcdMode] and a default captured at the wrong moment would silently be
         * the previous profile's.
         */
        private var cmcdMode: CmcdMode? = null

        /**
         * Chooses the kind of playback this player is for.
         *
         * Defaults to [PlaybackProfile.VIDEO_ON_DEMAND]. What the profile decides, and why each
         * profile decides it differently, is [PlaybackProfile] and [PlaybackPolicy]'s subject; the
         * result is readable afterwards as [SuperPlayer.playbackDecision]. The profile names the
         * policy the player runs unless [setPolicy] supplies one; either way the consumer names a
         * profile or a policy and never a number (ADR-0005 rule 3, as ADR-0009 rewords it).
         */
        public fun setProfile(profile: PlaybackProfile): Builder = apply { this.profile = profile }

        /**
         * Supplies the [PlaybackPolicy] this player decides with, in place of the profile's own
         * static one. Any implementation: `superplayer-abr`'s adaptive policy for a profile, or a
         * consumer's own.
         *
         * ```kotlin
         * val player = SuperPlayer.Builder(context)
         *     .setProfile(PlaybackProfile.LIVE_LINEAR)
         *     .setPolicy(myPolicy)
         *     .build()
         * ```
         *
         * **How often it is consulted depends on what the engine was built with**, and this is a
         * documented limit rather than a surprise (ADR-0009 rule 5). A policy that brings engine
         * components able to honour a changed decision — `superplayer-abr`'s does — is consulted at
         * construction and again on every [DecisionTrigger], with [PlaybackConditions] core has
         * observed. Any other policy, a consumer's hand-written adaptive one included, is consulted
         * **once**, at construction, with empty conditions: Media3's own `DefaultLoadControl` cannot
         * take a new buffer target after it is built, and a decision half in force is worse than one
         * honestly documented as construction-time. Such a policy behaves as a static one, and
         * [SuperPlayer.playbackDecision] reports its one answer for the player's lifetime.
         *
         * The profile is still set separately, because a policy is built *for* one and reads it
         * from its own constructor; [setProfile] is what the telemetry and the CMCD defaults read.
         */
        public fun setPolicy(policy: PlaybackPolicy): Builder = apply { this.policy = policy }

        /**
         * Measures this player, and writes what it measures to the collector's own sink.
         *
         * The collector is `superplayer-telemetry`'s — `QoeCollector(sink)` — and this is the only
         * call that turns measurement on. Leave it unset and the player registers no analytics
         * listener and allocates nothing for telemetry, which is ADR-0008 rule 2 and is asserted
         * rather than asserted-about.
         *
         * ```kotlin
         * val player = SuperPlayer.Builder(context)
         *     .setTelemetry(QoeCollector(sink = { event -> analytics.record(event) }))
         *     .build()
         * ```
         *
         * One collector per player: it is attached to the player this builder builds, for that
         * player's lifetime. See [TelemetryCollector] for why the type here is a collector rather
         * than a bare [TelemetrySink].
         */
        public fun setTelemetry(collector: TelemetryCollector): Builder =
            apply { telemetry = collector }

        /**
         * Chooses how CMCD (CTA-5004) travels on this player's requests, overriding the profile's
         * default.
         *
         * Unlike [setTelemetry], this turns nothing *on*: every profile emits CMCD already, because
         * the CDN's half of a diagnosis is worth having before the incident rather than after it.
         * What this call is for is the two facts SuperPlayer cannot know — that a deployment's CDN
         * logs query strings rather than headers, or that it wants the requests left alone
         * altogether.
         *
         * ```kotlin
         * val player = SuperPlayer.Builder(context)
         *     .setCmcdMode(CmcdMode.QUERY_PARAMETER)
         *     .build()
         * ```
         *
         * [CmcdMode] carries the trade-off between the two modes, including the one that can break
         * a signed URL, and `StaticCmcdPolicy` carries the per-profile defaults this replaces. Fixed
         * for the player's lifetime, like the profile: it is applied to the media source factory as
         * the engine is built.
         */
        public fun setCmcdMode(mode: CmcdMode): Builder = apply { cmcdMode = mode }

        /**
         * Loads this player's media through [cache]: reads it can answer are answered from the
         * consumer's storage, and what is fetched is written there.
         *
         * The cache is `superplayer-cache`'s, opened by the consumer in a directory they named and
         * within a budget they chose, and released by them; this call opens nothing and a player
         * built without it has no cache at all (ADR-0010 rules 1 and 13). The type is a
         * [ContentCache] rather than a directory or a size for the reason [setTelemetry] takes a
         * collector: a configuration value passed on its own would compile and cache nothing.
         *
         * Content is keyed by [MediaRequest.contentId], so what [setMediaRequest] plays is found again
         * whichever of its sources it came from. Content set through `setMediaItem` has no identity
         * and is keyed by its URL. Fixed for the player's lifetime: the cache is composed into the
         * loading path as the engine is built.
         */
        public fun setCache(cache: ContentCache): Builder = apply { this.cache = cache }

        /**
         * Gives this player [resilience]: the retry, the fallback ladder and the token refresh it
         * survives a failure with.
         *
         * The object is `superplayer-resilience`'s, and this call is the only thing that turns any
         * of it on. Leave it unset and the player keeps Media3's own load-error handling, an empty
         * header-refresh slot in its transfer chain and no class of the module loaded — which is
         * ADR-0011 rule 14, and is counted rather than asserted about.
         *
         * ```kotlin
         * val player = SuperPlayer.Builder(context)
         *     .setResilience(myResilience)
         *     .build()
         * ```
         *
         * What it decides is behind the type: which rungs a failure may reach, how long to back off
         * and with what jitter, when a credential is refreshed. What it does *not* decide is the
         * retry budgets, which are policy and arrive through [setPolicy] with the rest of the
         * decision (ADR-0011 rule 11). Fixed for the player's lifetime, like the cache: both slots
         * are filled as the engine is built.
         */
        public fun setResilience(resilience: PlaybackResilience): Builder =
            apply { this.resilience = resilience }

        /**
         * Plays content protected by [drm]: the licence server, the key system and everything
         * `superplayer-drm` does to open a session against them.
         *
         * ```kotlin
         * val player = SuperPlayer.Builder(context)
         *     .setDrm(Drm.widevine(WidevineConfig(licenceUri = "https://licence.example/widevine")))
         *     .build()
         * ```
         *
         * **Declared once, and fixed for the player's lifetime** (ADR-0012 rule 1). Protection is not
         * a property of a [MediaRequest] or of one of its sources — a request's sources are one piece
         * of content in more than one place, and a licence server that varied between them would put
         * a security decision in whatever code assembled the list. A player that plays protected and
         * unprotected content in turn asks for a licence only for the items that declare protection;
         * a player that must change licence server is a second player.
         *
         * Leave it unset and nothing of DRM exists on this player: no provider on the media source
         * factory, no `ExoMediaDrm` instantiated, no class of the module loaded — which is ADR-0012
         * rule 13, and is counted rather than asserted about.
         */
        public fun setDrm(drm: PlaybackDrm): Builder = apply { this.drm = drm }

        /**
         * The single seam through which tests reach the engine's construction.
         *
         * A test that must run without a device or a network has to substitute Media3's fake clock
         * and fake data source, and both are `@UnstableApi` types that ADR-0001 rule 2 keeps out of
         * public API. Rather than widen the public surface for testing, the seam is `internal`:
         * tests configure the engine here and then drive playback entirely through the public
         * [Player] API, so no assertion reaches past the facade. [EngineConfiguration] says what
         * may be changed, and why a fake data source goes in its transport slot rather than
         * replacing the chain above it.
         */
        @VisibleForTesting
        internal fun setEngineConfigurator(configurator: (EngineConfiguration) -> Unit): Builder =
            apply { engineConfigurator = configurator }

        /**
         * The engine of the [PlayerPool] this player is built for, or null for a player of its own.
         * Set by the pool's player factory, and by a test's that stands in for it; see [PooledEngine].
         */
        internal fun setPooledEngine(engine: PooledEngine?): Builder = apply { pooledEngine = engine }

        public fun build(): SuperPlayer {
            val policy = policy ?: PlaybackPolicy.forProfile(profile)

            // The CMCD half, resolved once the profile is final. The holder is created here rather
            // than inside the player because both ends of the join need the same object: the
            // transfer chain reads the session id from it, and the facade writes it.
            val cmcd = cmcdMode ?: StaticCmcdPolicy.defaultModeFor(profile)
            val pooled = pooledEngine
            val measurementSession = MeasurementSession(
                enabled = telemetry != null || cmcd != CmcdMode.DISABLED,
                preminted = pooled?.sessionIds,
            )

            val engineBuilder = ExoPlayer.Builder(context)
                // Audio focus, becoming-noisy and the wake locks: platform rules rather than
                // policy, which is why they are not a profile's to decide. See LifecycleBinding.kt.
                .withLifecycleCorrectness()
            val configuration = EngineConfiguration(engineBuilder)
            // A policy that brings its own engine components fills the slots first; the test seam
            // runs after it so a test's engine configuration wins over the policy's exactly as it
            // wins over the profile's: the clock, the renderers, a meter a test reads through. A
            // policy that is not an extension takes the path below untouched — nothing is looked
            // up and nothing is registered, which is what a consumer without abr pays.
            //
            // A pooled player after the first takes the components the first one assembled instead,
            // so an extension configures one engine per pool (ADR-0010 rule 9; PooledEngine.kt).
            val shared = pooled?.components
            if (shared == null) {
                (policy as? EnginePolicyExtension)?.configureEngine(configuration)
            } else {
                (policy as? EnginePolicyExtension)?.onComponentsShared()
                configuration.loadControl = shared.loadControl
                configuration.bandwidthMeter = shared.bandwidthMeter
                configuration.trackSelectionFactory = shared.trackSelectionFactory
                configuration.decisionTarget = shared.decisionTarget
            }
            // Resilience fills its two slots on every engine rather than once per pool: what it
            // installs is a chain layer and a load-error policy, and a chain is built per player
            // (ADR-0011 rule 13). A resilience that is not an extension fills nothing, and one never
            // set looks the same from here — which is what a consumer without the module pays.
            (resilience as? EngineResilienceExtension)?.configureEngine(configuration)
            // And protection, on the same terms and for the same reason: what it fills is a provider
            // on the media source factory, and a factory is built per player (ADR-0012 rule 3). A
            // `PlaybackDrm` that is not an extension fills nothing, and one never set looks the same
            // from here — which is the whole of what a consumer without the module pays.
            (drm as? EngineDrmExtension)?.configureEngine(configuration)
            engineConfigurator?.invoke(configuration)
            configuration.clock?.let(engineBuilder::setClock)
            configuration.renderersFactory?.let(engineBuilder::setRenderersFactory)
            // One playback thread for a pool whose players share components: a shared load control
            // pins itself to one thread, and a coordinator's preload manager prepares on it too.
            pooled?.let { engineBuilder.setPlaybackLooper(it.playbackLooper()) }

            // The policy boundary, consulted at its one construction-time call site. Nothing about
            // buffering or track selection is decided below this line. With a target the loop that
            // will consult it again reads the conditions core can already observe — the transport,
            // the heap — and hands the answer to the target; without one the conditions are empty,
            // because nothing has been observed and nothing will be (ADR-0009 rule 5).
            val reapplication = configuration.decisionTarget?.let { target ->
                DecisionReapplication(
                    context.applicationContext,
                    policy,
                    target,
                    configuration.bandwidthMeter as? ThroughputSource,
                )
            }
            val decision = reapplication?.decideInitially() ?: policy.decide(PlaybackConditions())
            // And the window an extension was handed before any of this happened is pointed at
            // where the answer now lives: the reapplication's current decision on a player that has
            // one, and this one answer for the player's lifetime on every other (ADR-0011 rule 11).
            configuration.decisionInForce.fedBy { reapplication?.decision ?: decision }

            // The buffer half becomes a `DefaultLoadControl` only when nothing retargetable was
            // installed in its place; the target already holds the decision otherwise.
            val loadControl = configuration.loadControl ?: decision.buffer.toLoadControl()
            engineBuilder.setLoadControl(loadControl)
            configuration.bandwidthMeter?.let(engineBuilder::setBandwidthMeter)
            // Under Media3's own selector, so the device-derived defaults are built upon, as they
            // are for the parameters below. A decided pace with no factory installed to own it
            // becomes Media3's own adaptive factory, fixed as the load control above is.
            val trackSelectionFactory = configuration.trackSelectionFactory ?: decision.trackSelection.pace?.toTrackSelectionFactory()
            trackSelectionFactory?.let { factory ->
                engineBuilder.setTrackSelector(DefaultTrackSelector(context, factory))
            }
            // The loading path, composed in one place rather than defaulted by Media3; TransferChain
            // says what wraps what, and where cache, measurement, CMCD and header refresh each go.
            // Installed after the seam rather than before it, so that a test's fake data source
            // stands in for the HTTP stack *under* SuperPlayer's layers instead of replacing them.
            // Only content with no transport at all replaces the whole path.
            // Built here rather than inside the chain so that the facade can read it back: it is the
            // one fact about a player that the DRM slot knows and core does not (ADR-0012 rule 11).
            // A player whose loading path a test replaced wholesale fills no slot and so writes
            // nothing into it, which is the same answer a player without `setDrm` gives.
            val deliveredProtection = DeliveredProtection()
            val mediaSourceFactory = configuration.mediaSourceFactory
                ?: TransferChain.mediaSourceFactory(
                    context,
                    cmcd,
                    measurementSession,
                    configuration.transport,
                    configuration.loadExecutor,
                    cache,
                    configuration.headerRefresh,
                    configuration.loadErrors,
                    configuration.drm,
                    configuration.exoMediaDrm,
                    deliveredProtection,
                )
            engineBuilder.setMediaSourceFactory(mediaSourceFactory)

            val engine = engineBuilder.build()
            // The selection half, laid into the parameters only where no target owns it. Built upon
            // rather than replaced, so the engine's own device-derived defaults survive the
            // ceilings; a consumer setting their own parameters afterwards overrides this, and no
            // trigger ever puts it back — EngineBinding.kt says why a target gets no ceiling here.
            if (reapplication == null) {
                engine.trackSelectionParameters =
                    decision.trackSelection.applyTo(engine.trackSelectionParameters)
            }

            val player = SuperPlayer(
                engine,
                profile,
                decision,
                // Captured after the profile has been applied, so that a pooled player's reset
                // restores what this player was built with rather than the engine's own default.
                builtWithTrackSelectionParameters = engine.trackSelectionParameters,
                telemetry = telemetry,
                // Only when there is a collector to signal; see the field.
                applicationContext = telemetry?.let { context.applicationContext },
                measurementSession = measurementSession,
                reapplication = reapplication,
                // Only a chain core composed around the cache reads the identity; a test that
                // replaced the whole loading path has no slot to key.
                identifiesContent = cache != null && configuration.mediaSourceFactory == null,
                pooled = pooled,
                // Whatever the resilience put in the slot, and null on every other player — which is
                // the whole of what a player without the module pays for rung 4 (ADR-0011 rule 14).
                playerStateRungs = configuration.playerStateRungs,
                deliveredProtection = deliveredProtection,
                // And whatever the protection put in the slot that faces the other way, which is
                // null on every player whose protection has no permitted level to fall to — and on
                // every player without `setDrm` at all (ADR-0012 rule 13).
                protectionRepair = configuration.protectionRepair,
            )

            // The first pooled player's components become the pool's. The factory handed on is the
            // instance this engine loads through, so a source built from it runs through this chain,
            // cache slot included, under the identity adoption stamps (ADR-0010 rule 6).
            if (pooled != null && shared == null) {
                pooled.share(
                    SharedComponents(
                        applicationContext = context.applicationContext,
                        mediaSourceFactory = mediaSourceFactory,
                        loadControl = loadControl,
                        bandwidthMeter = configuration.bandwidthMeter,
                        trackSelectionFactory = trackSelectionFactory,
                        decisionTarget = configuration.decisionTarget,
                        renderersFactory = configuration.renderersFactory,
                        clock = configuration.clock,
                        playbackLooper = pooled.playbackLooper(),
                        identifiesContent = cache != null && configuration.mediaSourceFactory == null,
                        sessionIds = pooled.sessionIds,
                        initialDecision = decision,
                    ),
                )
            }

            // After construction rather than inside it: a collector registers against the built
            // player, and handing `this` out of a constructor to something that will call back into
            // it is how a half-built object escapes. Only reached when a collector was supplied,
            // which is what makes ADR-0008 rule 2's "pays nothing" true of every other player.
            telemetry?.attach(player)
            // Likewise, and after the collector, so that the first change a trigger produces finds
            // a collector already attached to report it to.
            reapplication?.start(engine)

            return player
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
 *
 * ## The one callback it may swallow — the single exception to ADR-0003 rule 3
 *
 * That rule says a wrapper of a Media3 interface forwards every member, and the contract test above
 * is what proves it. There is one deliberate exception, argued in ADR-0003 rule 3's addendum and
 * decided by ADR-0011 rule 10: a failure that a rung of the fallback ladder *repairs* is not
 * delivered to the consumer, because rule 10 reserves the delivered error for the top of that ladder
 * and a rescued session that also reported a failure would be two accounts of one viewing.
 *
 * [withheld] is asked about a failure before it is forwarded, and about the null that clears one —
 * both forms, or the consumer would be told about the failure in the one form they cannot act on.
 * See [SuperPlayer.withholdsFromConsumer] for when it answers true and why the answer is memoized
 * rather than recomputed.
 *
 * ## The one callback it may substitute — which is not an exception to anything
 *
 * [delivered] maps a failure that *is* forwarded to the exception the consumer should receive, which
 * for a failure that reached rung 6 is one carrying the typed error as its cause (ADR-0011 rule 10,
 * [SuperPlayer.deliveredToConsumer]). It changes what is delivered and never whether: every callback
 * the rule above forwards is still forwarded, with the same arity and in the same order, which is why
 * this is not a second deviation from ADR-0003 rule 3 to argue. It defaults to the identity, so a
 * player with no resilience — and the forwarding-contract harness — hands over exactly what Media3
 * raised.
 *
 * Three bounds keep the rule above true everywhere else, and are worth checking against any
 * temptation to add a second exception. It is these two callbacks and no others; it is a failure the
 * player is taking on itself and never one that reaches the consumer's rung; and it defaults to
 * withholding nothing, which is what a player with no resilience behaves as and what the
 * forwarding-contract harness wraps with — so that harness still drives all 37 callbacks through
 * this proxy, unchanged, and still catches the next `default` member Media3 adds.
 */
internal fun Player.Listener.reportingSourceAs(
    source: Player,
    withheld: (PlaybackException?) -> Boolean = { false },
    delivered: (PlaybackException?) -> PlaybackException? = { it },
): Player.Listener {
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

            // Both halves of one failure, or neither: `onPlayerError` carries it and
            // `onPlayerErrorChanged` announces both its arrival and its clearing, and a consumer told
            // only that an error went away would be told about a failure in the one form they cannot
            // act on.
            method.name in ERROR_CALLBACKS && arguments.size == 1 -> {
                val failure = arguments[0] as PlaybackException?
                if (withheld(failure)) null else forward(listener, method, arrayOf(delivered(failure)))
            }

            else -> forward(
                listener,
                method,
                if (method.name == ON_EVENTS && arguments.isNotEmpty()) {
                    arrayOf(source, *arguments.copyOfRange(1, arguments.size))
                } else {
                    arguments
                },
            )
        }
    } as Player.Listener
}

/** Hands one callback on, with the reflection wrapper taken off whatever the listener threw. */
private fun forward(listener: Player.Listener, method: Method, arguments: Array<out Any?>): Any? =
    try {
        method.invoke(listener, *arguments)
    } catch (e: InvocationTargetException) {
        // A listener that throws must surface its own exception, not a reflection wrapper.
        throw e.cause ?: e
    }

private fun Method.isObjectMethod(): Boolean = when (name) {
    EQUALS -> parameterTypes.size == 1 && parameterTypes[0] == Any::class.java
    HASH_CODE, TO_STRING -> parameterTypes.isEmpty()
    else -> false
}

private const val ON_EVENTS = "onEvents"

/** The two callbacks that carry a failure, and the only ones a wrapper may withhold. */
private val ERROR_CALLBACKS = setOf("onPlayerError", "onPlayerErrorChanged")
private const val EQUALS = "equals"
private const val HASH_CODE = "hashCode"
private const val TO_STRING = "toString"
