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
) : Player by delegate {

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
    internal fun itemOf(request: MediaRequest): MediaItem = request.toMediaItem(identified = identifiesContent)

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
            wrappedListeners.getOrPut(listener) { listener.reportingSourceAs(this) }
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
    }

    public class Builder(private val context: Context) {

        private var engineConfigurator: ((EngineConfiguration) -> Unit)? = null
        private var profile: PlaybackProfile = PlaybackProfile.VIDEO_ON_DEMAND
        private var policy: PlaybackPolicy? = null
        private var telemetry: TelemetryCollector? = null
        private var cache: ContentCache? = null
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
            val mediaSourceFactory = configuration.mediaSourceFactory
                ?: TransferChain.mediaSourceFactory(
                    context,
                    cmcd,
                    measurementSession,
                    configuration.transport,
                    configuration.loadExecutor,
                    cache,
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
