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

package com.superplayer.testkit

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.test.utils.FakeAdaptiveDataSet
import androidx.media3.test.utils.FakeAdaptiveMediaSource
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeChunkSource
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeDataSet
import androidx.media3.test.utils.FakeDataSource
import androidx.media3.test.utils.FakeMediaPeriod
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import com.superplayer.core.BufferPolicy
import com.superplayer.core.PlaybackPolicy
import com.superplayer.core.PlaybackProfile
import com.superplayer.core.PlayerPool
import com.superplayer.core.SuperPlayer
import com.superplayer.core.TelemetryCollector
import org.junit.rules.ExternalResource
import java.util.IdentityHashMap
import java.util.Random
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Deterministic playback for a module that is not `superplayer-core`.
 *
 * ```kotlin
 * @get:Rule val harness = PlaybackHarness()
 *
 * @Test fun something() {
 *     val player = harness.buildPlayer(TestContent.videoLadder(), telemetry = collector)
 *     player.setMediaRequest(MediaRequest.Builder("series/expanse/s01e01").addSource(uri).build())
 *     harness.playToReady(player)
 * }
 * ```
 *
 * ## Why this module owns it
 *
 * `superplayer-core` has a harness of its own, and it cannot be shared: substituting a fake clock
 * and fake renderers means reaching `SuperPlayer.Builder`'s one `internal` configurator
 * (`docs/testing.md`), and Kotlin `internal` is per compilation. This module compiles as a *friend*
 * of core rather than as a consumer of it — see `KotlinFriendModules.kt` in `build-logic` for why
 * that is the honest description and not a widening of the seam — which is what lets one harness
 * serve every phase-2-and-later module.
 *
 * ## The clocks, which is the part that decides whether a measurement test is exact
 *
 * Two clocks are in play and they are advanced **together**, by [advanceTimeMs], because a
 * measurement is a difference and a difference between two clocks is not one.
 *
 * - Media3's [FakeClock] drives the engine: the playback thread's waits, and the `realtimeMs` on
 *   every analytics callback.
 * - Robolectric's `SystemClock` is what `SuperPlayer.declarePlaybackIntent` reads and what a
 *   collector reads, because `SystemClock.elapsedRealtime()` is the clock
 *   `docs/telemetry-schema.md` defines every duration on.
 *
 * The [FakeClock] is therefore created **at the current `SystemClock` reading and with
 * auto-advancing off**, so the two start equal and stay equal — and [advanceTimeMs] moves them only
 * once the engine has nothing left to do at the current time, with nothing run between the two
 * moves, so that no callback is ever stamped on one clock for work the engine did on the other
 * (issue #110). That is the opposite of core's
 * harness, which auto-advances so that a two-second stream costs no wall-clock time — a trade that
 * is right when nothing is being measured and wrong here, because an auto-advancing clock moves by
 * an amount no assertion can name. A test using this harness advances time in the amounts it is
 * about, and every duration it then asserts on is exact.
 */
public class PlaybackHarness : ExternalResource() {

    /**
     * The engine's clock, shared by every player this harness builds.
     *
     * One rather than one per player, unlike core's harness: these tests measure, and two players
     * measured against two clocks could not be compared — which is exactly what a pooled-player test
     * needs to do.
     */
    private val clock = FakeClock(SystemClock.elapsedRealtime(), /* isAutoAdvancing= */ false)

    /**
     * Every player built here, newest last, so [after] can release them and [rendererFor] find one.
     *
     * Keyed by Media3's `Player` rather than by [SuperPlayer], because this harness builds two kinds:
     * a SuperPlayer ([buildPlayer]) and a stock `ExoPlayer` ([buildStockPlayer]). `PRD.md` §6's three
     * arms are driven by the same methods, over one clock, so a number measured on one is comparable
     * with a number measured on another — which is the whole claim a benchmark makes.
     */
    private val renderers = IdentityHashMap<Player, ControllableVideoRenderer>()
    private val engines = IdentityHashMap<Player, ExoPlayer>()

    /**
     * The off-screen video outputs handed to the players, held so [after] can release them.
     *
     * A video renderer with nowhere to draw renders nothing and reports no first frame, so a harness
     * whose whole purpose is measuring time to first frame has to give every player an output. It is
     * a [SurfaceTexture]-backed [Surface] rather than a real view: nothing looks at the pixels, and
     * what the renderer needs is somewhere legal to put them.
     */
    private val outputs = mutableListOf<Pair<Surface, SurfaceTexture>>()

    /**
     * The fault injector installed under each player, so [requestedResources] can report what the
     * session actually fetched and under which address.
     */
    private val injectors = IdentityHashMap<Player, FaultInjectingDataSource.Factory>()

    /** How each player's loads wait on the clock, so [advanceTimeMs] can let them catch up with it. */
    private val waits = IdentityHashMap<Player, HarnessClockWait>()

    /** The transport half of each player's trace, replayed into the platform as [advanceTimeMs] moves the clock. */
    private val transportReplays = IdentityHashMap<Player, TransportReplay>()

    /**
     * A player as a consumer builds one, over synthetic content and the fakes above.
     *
     * [content] describes what to play; [profile] is left unset by default so a test asserting on
     * the library's own default asserts on the library's rather than on this file's.
     *
     * [network], when set, replays a [ThroughputTrace] under every transfer the player makes, from
     * the moment this returns — a [NetworkProfile]'s, or one read from a converted dataset. It
     * composes with [faults] rather than replacing them: the shaper sits in front of the injector,
     * so a shaped network with a 403 at segment 5 is one player, and the 403 arrives after the
     * trace's round trip as a real one would. The trace's *transport* is replayed too, into the
     * platform's connectivity service, so a handover in a trace is a change of network the library
     * observes and not only a change of rate ([TransportReplay]).
     *
     * [policy], when set, is what `SuperPlayer.Builder.setPolicy` takes: a module's own
     * `PlaybackPolicy`, which is how `superplayer-abr`'s tests put its engine components under a
     * player this harness builds. A policy that is also core's extension fills the engine's slots
     * before this harness configures the clock and the transport, exactly as it does for a consumer.
     */
    public fun buildPlayer(
        content: TestContent = TestContent.video(),
        profile: PlaybackProfile? = null,
        telemetry: TelemetryCollector? = null,
        faults: FaultScript = FaultScript.NONE,
        network: ThroughputTrace? = null,
        policy: PlaybackPolicy? = null,
    ): SuperPlayer {
        var built: ControllableVideoRenderer? = null
        val transport = composeTransport(content, faults, network)
        val transfers = transport.transfers
        val player = SuperPlayer.Builder(ApplicationProvider.getApplicationContext())
            .apply { profile?.let { setProfile(it) } }
            .apply { telemetry?.let { setTelemetry(it) } }
            .apply { policy?.let { setPolicy(it) } }
            .setEngineConfigurator { configuration ->
                configuration.engine.setClock(clock)
                configuration.engine.setRenderersFactory(renderersFactory { built = it })
                // A real protocol stream is not described by a timeline at all: the manifest says
                // what the content is, Media3's own parser reads it, and its own extractor demuxes
                // the segments. So the injector — and the shaper, under a trace — goes where the
                // HTTP stack goes, beneath core's own layers exactly as a consumer's network is:
                // every fetch passes through a fault script and a trace, and nothing SuperPlayer
                // does to a transfer is skipped. Described content has no transport to stand in
                // for, so it replaces the loading path whole.
                if (content.protocol == TestContent.Protocol.DESCRIBED) {
                    configuration.mediaSourceFactory = describedMediaSourceFactory(content, transfers)
                } else {
                    configuration.transport = transfers.factory
                    configuration.loadExecutor = transport.loadThreads
                }
            }
            .build()
        register(player, player.exoPlayer, checkNotNull(built) { "The engine built no renderers" }, transport)
        return player
    }

    /**
     * A stock `ExoPlayer` with no SuperPlayer anywhere in it, over the same content, the same clock
     * and the same shaped transport [buildPlayer] uses.
     *
     * `PRD.md` §6's arms (a) and (b): with no [stockBufferPolicy] this is
     * `ExoPlayer.Builder(context).build()` and nothing else, and with one it is that plus the buffer
     * configuration the policy describes. This is the *comparison* half of the benchmark, and
     * everything about it that could differ from arm (c) other than the player itself has been made
     * not to: one [clock], one fake origin, one [ShapingDataSource] over one [ThroughputTrace], one
     * [FaultInjectingDataSource], one renderer implementation, one video output. What is left
     * different is the thing being measured.
     *
     * **What the arms *are* is `benchmark/`'s, not this module's.** A [BufferPolicy] arrives here
     * rather than a named tuning because the same two arms are also run on a device against public
     * streams, by an app that cannot load this harness at all; one value translated in two places is
     * what stops a device result and a Robolectric result describing different arms under one name.
     * `BufferPolicy` is `superplayer-core`'s own engine-agnostic type, so it carries the numbers
     * without putting a `LoadControl` in this module's public API (ADR-0001 rule 2).
     *
     * `ExoPlayer` in the return type is the one `@UnstableApi` exception ADR-0001 rule 2 names —
     * the same escape hatch `SuperPlayer.exoPlayer` is, and for the same reason: there is no stable
     * Media3 type that can be built and configured, and a benchmark of the engine has to name the
     * engine. It is `docs/api-surface.md`'s `ADR_0001_UNSTABLE_EXCEPTIONS` set of exactly one, not a
     * second entry.
     *
     * Note what this does **not** do: it installs no `TransferChain`, so a stock player gets none of
     * core's own layers — no live-playlist revalidation, no CMCD. That is not an omission, it is
     * arm (a). A stock ExoPlayer is what an app that wrote nothing has, and measuring it with
     * SuperPlayer's layers underneath would be measuring neither arm.
     */
    public fun buildStockPlayer(
        content: TestContent = TestContent.video(),
        stockBufferPolicy: BufferPolicy? = null,
        faults: FaultScript = FaultScript.NONE,
        network: ThroughputTrace? = null,
    ): ExoPlayer {
        var built: ControllableVideoRenderer? = null
        val transport = composeTransport(content, faults, network)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val player = ExoPlayer.Builder(context)
            .setClock(clock)
            .setRenderersFactory(renderersFactory { built = it })
            .apply { loadControlFor(stockBufferPolicy)?.let { setLoadControl(it) } }
            .setMediaSourceFactory(
                // The same split [buildPlayer] makes, for the same reason: described content has no
                // transport to stand in for and replaces the loading path whole, while a real
                // protocol stream loads through a `DataSource` chain that the shaper and the
                // injector sit at the bottom of.
                if (content.protocol == TestContent.Protocol.DESCRIBED) {
                    describedMediaSourceFactory(content, transport.transfers)
                } else {
                    DefaultMediaSourceFactory(context)
                        .setDataSourceFactory(transport.transfers.factory)
                        .setDownloadExecutor(transport.loadThreads)
                },
            )
            .build()
        register(player, player, checkNotNull(built) { "The engine built no renderers" }, transport)
        return player
    }

    /**
     * [policy] as Media3's `LoadControl`, or null for the arm that configures nothing.
     *
     * The translation, not the numbers. `superplayer-core`'s `EngineBinding` is the counterpart of
     * this for arm (c), and `benchmark/` holds the third: the same value, turned into a real
     * `LoadControl` wherever one is needed, rather than the numbers written out three times.
     */
    private fun loadControlFor(policy: BufferPolicy?): LoadControl? {
        // Null rather than `DefaultLoadControl.Builder().build()`, which would be the same object by
        // a longer route: arm (a) is `ExoPlayer.Builder(context).build()` *and nothing else*, and
        // building one anyway would quietly make the control arm a configured player that happens to
        // agree today.
        if (policy == null) return null
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                policy.minBufferMs,
                policy.maxBufferMs,
                policy.bufferForPlaybackMs,
                policy.bufferForPlaybackAfterRebufferMs,
            )
            .setBackBuffer(policy.backBufferMs, policy.retainBackBufferFromKeyframe)
            // Not a field of `BufferPolicy`, because no SuperPlayer profile needs it (`EngineBinding`),
            // and set here because a caller describing a deep buffer means it: without this the
            // engine caps the durations at its memory target, and an arm that asked for 120 s would
            // silently be measured with rather less.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    /**
     * Records a freshly built player against the transport it was built over, and gives it somewhere
     * to draw.
     *
     * One place, so that a player of either kind is released by [after], found by [rendererFor], and
     * waited on by [awaitLoads] on identical terms — the alternative being two registration paths
     * that drift, which in a benchmark would show up as an unexplained difference between arms.
     */
    private fun register(player: Player, engine: ExoPlayer, renderer: ControllableVideoRenderer, transport: Transport) {
        renderers[player] = renderer
        engines[player] = engine
        injectors[player] = transport.injector
        waits[player] = transport.wait
        transport.transportReplay?.let { transportReplays[player] = it }
        attachVideoOutput(player)
        ignoreTheViewport(player)
    }

    /**
     * Stops the *display size* deciding which renditions a ladder has.
     *
     * `DefaultTrackSelector` constrains selection to the viewport by default, which is right in an
     * app — decoding 1080p into a 360p view is bytes and decoder time spent on pixels nobody sees.
     * It is wrong here twice over. The output is an off-screen `SurfaceTexture` that nobody looks at,
     * so there is no viewport to speak of; and the one Robolectric reports is a small default that
     * has nothing to do with the device a measurement is about.
     *
     * Left alone it silently truncates the ladder: with rungs at 360p through 1080p and a small
     * reported display, every rung above the bottom is filtered out before the bandwidth estimate is
     * consulted at all, so a 20 Mbit/s link and a 1 Mbit/s link both play 365 kbit/s and no arm ever
     * switches. That is the same class of defect as the bandwidth meter seeing no samples
     * ([networkOrigin]) — an instrument reading zero because it is unplugged, in a shape that looks
     * like a finding.
     *
     * Applied by `buildUpon` rather than by building fresh parameters, which matters: a SuperPlayer
     * profile's own ceilings — `DATA_SAVER`'s 480p and 800 kbit/s — are already on this player, and
     * replacing the parameters wholesale would erase the very policy arm (c) exists to measure. This
     * removes one constraint and leaves the rest, for every arm equally.
     */
    private fun ignoreTheViewport(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearViewportSizeConstraints()
            .build()
    }

    /**
     * The fake origin, the fault injector and the network shaper, composed once for a player of
     * either kind.
     *
     * Lifted out of [buildPlayer] when [buildStockPlayer] arrived, and that is the point rather than
     * tidiness: `PRD.md` §6's arms are only comparable if the bytes reach them the same way, and one
     * function is the only version of that claim a reader can check.
     */
    private fun composeTransport(
        content: TestContent,
        faults: FaultScript,
        network: ThroughputTrace?,
    ): Transport {
        // One wait for both wrappers, so "is a load held by the clock" has one answer.
        val wait = HarnessClockWait(clock)
        // The injector's own upstream carries the synthetic stream, because that is the one path
        // where the injector makes its own sources: real HLS and real DASH load through this
        // factory. The adaptive path below builds its sources inside Media3's `FakeChunkSource` and
        // reaches the injector by `wrap`, so the data set handed over here is empty and unused there.
        // Content an origin keeps publishing is served by one that advances with the clock, and a
        // stream that declares its response headers has them served on top of its bytes.
        val origin = content.publication?.let { LiveOriginDataSource.Factory(it, clock) }
            ?: networkOrigin().setFakeDataSet(fakeDataSetFor(content))
        val injector = FaultInjectingDataSource.Factory(
            if (content.responseHeaders.isEmpty()) origin else HeaderServingDataSource.Factory(origin, content.responseHeaders),
            faults,
            clock,
            wait,
            // Only the outermost wrapper counts a transfer, and under a trace that is the shaper:
            // one load registered twice would be a load the wait could never see catch up, because
            // the one thread carrying it can only ever be waiting for one deadline.
            countsTransfers = network == null,
        )
        val shaper = network?.let { ShapingDataSource.Factory(injector, it, clock, wait) }
        // The network the trace starts on is the device's from before the player is built, so the
        // transport the library reads at construction is the trace's rather than Robolectric's.
        val transportReplay = network?.let { TransportReplay(it, clock.elapsedRealtime()).apply { replayAt(clock.elapsedRealtime()) } }
        return Transport(
            transfers = Transfers(
                factory = shaper ?: injector,
                wrap = { source -> injector.wrap(source).let { shaper?.wrap(it) ?: it } },
                loadsThroughADataSource = !faults.isEmpty() || network != null,
            ),
            injector = injector,
            wait = wait,
            loadThreads = HarnessLoadThreads(wait),
            transportReplay = transportReplay,
        )
    }

    /**
     * What [player] has fetched so far, in the order it was first asked for, as the addresses a
     * [FaultScript] speaks in.
     *
     * Internal: [ResourceAddress] is the injector's own vocabulary, and this module's public API
     * stays the one a test writes fault plans in. The harness's own tests are what read it.
     */
    internal fun requestedResources(player: Player): List<ResourceAddress> =
        checkNotNull(injectors[player]) { "This harness did not build that player" }.addresses.requested

    /**
     * How many of [player]'s requests so far asked every cache on the way to step aside with
     * `Cache-Control: no-cache`. Internal for [requestedResources]'s reason.
     */
    internal fun cacheBypassingRequests(player: Player): Int =
        checkNotNull(injectors[player]) { "This harness did not build that player" }.cacheBypassingRequests

    /**
     * A [PlayerPool] whose players are this harness's, so a pooled player is a player like any
     * other test's — same clock, same fakes, released at the end of the test whether the pool was
     * or not.
     *
     * [telemetry] is a factory rather than a collector because a collector measures one player
     * (ADR-0008), so a pool of two needs two.
     */
    public fun buildPool(
        maxSize: Int? = null,
        profile: PlaybackProfile? = null,
        content: TestContent = TestContent.video(),
        telemetry: () -> TelemetryCollector? = { null },
    ): PlayerPool = PlayerPool.Builder(ApplicationProvider.getApplicationContext())
        .apply {
            maxSize?.let { setMaxSize(it) }
            profile?.let { setProfile(it) }
        }
        .setPlayerFactory { buildPlayer(content, profile ?: PlaybackProfile.SHORT_FORM, telemetry()) }
        .build()

    /**
     * Moves both clocks forward by [millis] and lets the player act on it.
     *
     * The one way time passes in a test using this harness. See the class KDoc for why the two
     * clocks move together and why neither moves on its own.
     */
    public fun advanceTimeMs(player: Player, millis: Long) {
        require(millis >= 0) { "Time does not go backwards" }
        // Quiet before either clock moves, as well as after: a load that finished between the last
        // settle and this call would otherwise be heard by the engine at the new time on some runs
        // and at the old time on others.
        //
        // *Either* clock, and both only now. This call once moved `SystemClock` first and quiesced
        // second, so work the engine finished at the old time — Media3's fake renderer presents its
        // first frame in the same instant it is enabled — reached the collector stamped with the new
        // one on a quiet machine and with the old one on a starved runner, whichever thread the
        // scheduler happened to run. A time to first frame read 50 ms or 0 ms by that accident
        // (issue #110). The two clocks are now never apart while anything can observe them: nothing
        // runs on any looper between the two lines below.
        quiesce(player)
        // And the engine before the loads. Moving the clock makes the engine's timed work due and a
        // load's delay run out in the same instant, and left to the scheduler the loading thread
        // writes the new moment's samples while that work reads the buffer: a player became ready a
        // step earlier on a starved runner than anywhere else (issue #120). So loads stay at the old
        // moment until the engine has done everything it will at the new one, and are released after.
        // Every player's loads are held because every player shares the clock, but only [player] is
        // settled in between; another player's engine hears its loads at this time, as it always did.
        waits.values.forEach { it.holdLoads() }
        try {
            SystemClock.setCurrentTimeMillis(SystemClock.uptimeMillis() + millis)
            clock.advanceTime(millis)
            quiesce(player)
        } finally {
            waits.values.forEach { it.releaseLoads() }
        }
        quiesce(player)
        // The transport last, once the loads have caught up with the new moment. A transfer open
        // across a handover reads, on release, every byte that arrived on the old network during
        // the step, and a meter hears a byte when it is read; a network changed before that heard
        // those bytes as the new one's and timed them from the change — a WiFi-rate sample recorded
        // as cellular, whenever a segment happened to be in flight (issue #125).
        transportReplays[player]?.let { replay ->
            if (replay.replayAt(clock.elapsedRealtime())) quiesce(player)
        }
    }

    /**
     * Settles the player until the engine has nothing left to do at this moment.
     *
     * [awaitLoads] waits for the loading threads; [drainLoopers] lets the playback and application
     * threads act on what they delivered, which may be to start the next load — on a loading
     * thread again — or to post the engine one more message about it. So the three are asked in
     * turn until a whole round passes in which no transfer opened or closed, no load task was
     * submitted or finished, and both loopers report nothing due. Everything the engine can do
     * with the loads of this moment has then been done, whichever thread was scheduled first, and
     * the clock may move on. What this buys is that a session traces identically run after run —
     * `SessionTraceRecorderTest` in `superplayer-telemetry` is where that is held — where before it
     * a load's completion, and the next load it triggers, reached the engine one step late on some
     * runs and not others.
     *
     * [quietAt] is the activity count the last round ended on, so that a load started between two
     * calls — by `prepare()`, say — counts as something to settle rather than as the baseline.
     */
    private fun quiesce(player: Player) {
        val startedAtMs = System.currentTimeMillis()
        var seen = quietAt
        while (true) {
            awaitLoads()
            drainLoopers(player)
            awaitLoads()
            val now = activitySoFar()
            if (now == seen && playbackThreadIsIdle(player) && Looper.getMainLooper().queue.isIdle) {
                quietAt = now
                return
            }
            seen = now
            check(System.currentTimeMillis() - startedAtMs < CATCH_UP_WALL_CLOCK_MS) {
                "The engine kept working for $CATCH_UP_WALL_CLOCK_MS ms of real time without the clock moving"
            }
        }
    }

    private var quietAt = 0

    private fun activitySoFar(): Int = waits.values.sumOf { it.activitySoFar }

    /**
     * Lets every load in flight act on the time that has passed, before the clock moves again.
     *
     * Within one step the engine acts first and the loads follow ([HarnessClockWait.holdLoads] says
     * why), so a load trails the engine by that one step and never by more; this is what bounds it.
     * A load runs on a loading thread and wakes once per read, so without this the test thread could
     * run the clock ahead of it whenever that thread was short of CPU — on a busy CI runner, say.
     * Under a [ThroughputTrace] the engine would then drain a buffer the trace had filled, reporting
     * a stall the trace never described; under none, a live playlist reloaded on schedule would look
     * minutes late to a tracker reading the clock rather than the thread, and Media3 would declare a
     * healthy stream stuck (issue #91). Waiting until every open transfer is waiting for a moment
     * still to come, or has closed, narrows both gaps.
     *
     * It is bounded in real time and fails at the bound, saying so, as every other wait here does:
     * carrying on would hand the engine the very stall this exists to prevent, and the test would
     * fail later for a reason it could not name. A player whose loads are all in memory opens no
     * transfer at all, so for it this returns at once.
     */
    private fun awaitLoads() {
        val startedAtMs = System.currentTimeMillis()
        while (waits.values.any { !it.transfersHaveCaughtUp }) {
            check(System.currentTimeMillis() - startedAtMs < CATCH_UP_WALL_CLOCK_MS) {
                "A load did not catch up with the harness clock within " +
                    "$CATCH_UP_WALL_CLOCK_MS ms of real time"
            }
            Thread.yield()
        }
    }

    /**
     * Moves both clocks forward by [millis] in load-sized steps, rather than in one jump.
     *
     * The counterpart of [advanceTimeMs] for a test whose subject is what the *transfer* does. A load
     * is asynchronous — the engine asks for a chunk, a loading thread fetches it, the result arrives
     * on a later pass — so a single long advance gives the engine one pass and reaches the second
     * chunk of a session however far it jumped. A fault addressed at the fourth segment would then
     * never fire, and the test would pass for the wrong reason.
     *
     * Time still moves by exactly [millis] in total, so a duration measured across it is still exact.
     */
    public fun advanceTimeInStepsMs(player: Player, millis: Long) {
        require(millis >= 0) { "Time does not go backwards" }
        var advanced = 0L
        while (advanced < millis) {
            val step = minOf(LOAD_STEP_MS, millis - advanced)
            advanceTimeMs(player, step)
            advanced += step
        }
    }

    /**
     * Gives [player] somewhere to draw, and takes responsibility for releasing it.
     *
     * Called for every player this harness builds, and **again after a pool recycle**:
     * `SuperPlayer.resetForReuse` detaches the surface first of all, so that a stale frame cannot
     * appear in a recycled view. A reused player therefore has nowhere to render and reports no
     * first frame until something gives it an output — which in an app is the next row binding a
     * `PlayerView`, and here is this.
     */
    public fun attachVideoOutput(player: Player) {
        val texture = SurfaceTexture(/* texName= */ 0)
        val surface = Surface(texture)
        outputs += surface to texture
        player.setVideoSurface(surface)
    }

    /** The reading every duration in `docs/telemetry-schema.md` is measured on, right now. */
    public fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    /**
     * Lets the engine act on everything [player] has been told so far, until it has nothing left to
     * do at the current time.
     *
     * Media3 masks a pending command's effects until the playback thread has seen it, so an
     * assertion made without this is asserting on the state that was standing before the command.
     * And the playback thread's first reaction to a command is rarely its last: a `prepare()` has
     * the source prepared, then the period, then the renderers enabled, each a message the engine
     * posts to itself behind the one it is handling. This returns once that chain has run out —
     * the same quiet [advanceTimeMs] requires before it moves the clocks — so that what a test reads
     * next is the state of *this* moment rather than of whichever message the scheduler had reached
     * (issue #110). A wait that stops at the first acknowledgement is [drainLoopers], and is private
     * because a test has no use for the state between two of the engine's own messages.
     */
    public fun settle(player: Player) {
        quiesce(player)
    }

    /**
     * Runs both loopers once: the playback thread through its pending commands, then the application
     * looper dry.
     *
     * One round of [quiesce], not a settle on its own. The probe this sends comes back after the
     * commands pending *now*; a message the engine posts to itself while handling one of them lands
     * behind the probe and is not waited for, which is why [quiesce] asks again until a round
     * changes nothing.
     */
    private fun drainLoopers(player: Player) {
        TestPlayerRunHelper.advance(player)
            .untilPendingCommandsAreFullyHandled(clock, player.applicationLooper)
        // The helper returns when its own probe has come back, and a callback the engine posted to
        // the application looper a moment earlier may still be sitting behind it: Robolectric's main
        // looper runs only when asked. Run it dry, so a listener hears at the time the engine spoke.
        val mainQueue = Looper.getMainLooper().queue
        RobolectricUtil.runMainLooperUntil { mainQueue.isIdle }
    }

    /**
     * Whether the playback thread has nothing due, answered by the playback thread itself.
     *
     * A message the engine posts to itself while handling a loader's completion lands *behind* the
     * probe [drainLoopers] sent, so the probe coming back does not mean the engine has finished reacting.
     * Asking the queue from another thread would not do either: a message being handled is not in
     * the queue. So the question is posted, and answered from inside the looper, where "nothing in
     * the queue" and "nothing running" are the same fact. A released player's looper has quit and
     * declines the post, which is as idle as it gets.
     */
    private fun playbackThreadIsIdle(player: Player): Boolean {
        val engine = engines[player] ?: return true
        val idle = CompletableFuture<Boolean>()
        val posted = Handler(engine.playbackLooper).post { idle.complete(Looper.myQueue().isIdle) }
        if (!posted) return true
        return try {
            idle.get(CATCH_UP_WALL_CLOCK_MS, TimeUnit.MILLISECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            throw IllegalStateException(
                "The playback thread did not answer whether it was idle within $CATCH_UP_WALL_CLOCK_MS ms of real time",
                timeout,
            )
        }
    }

    /**
     * Prepares, plays, and returns once the player is ready — the ordinary start of a session.
     *
     * Waits by *advancing time*, not by idling the looper. Media3's own
     * `TestPlayerRunHelper.untilState` idles and waits, which resolves nothing under a clock that
     * does not advance on its own: a source that needs to load a chunk before it can be ready is
     * waiting for a timer that will never fire, and the wait times out. Content that is ready with
     * no time passing — Media3's non-adaptive fake — costs no advance at all, which is what keeps a
     * time-to-first-frame measured through this method exact.
     */
    public fun playToReady(player: Player) {
        player.prepare()
        player.play()
        advanceUntil(player, "ready") { it.playbackState == Player.STATE_READY }
    }

    /**
     * Prepares, plays, and returns once the player has failed.
     *
     * The counterpart of [playToReady] for a fault armed with [failRendering] before playback
     * started: nothing renders, so there is no ready state to wait for, and the thing being waited
     * on is the error.
     *
     * It is also what a [FaultScript] armed at a transfer wants, and for a second reason: how many
     * loads a session makes before it reaches the faulted one is the protocol's business — HLS reads
     * a multivariant playlist and then a media playlist before its first segment, DASH an MPD and an
     * initialization segment — so a test that advanced a fixed span instead would be picking a
     * number that made the slower protocol pass.
     */
    public fun playToFailure(player: Player) {
        player.prepare()
        player.play()
        advanceUntil(player, "an error") { it.playerError != null }
    }

    /**
     * Advances time in [WAIT_STEP_MS] steps until [condition] holds, or fails saying what it wanted.
     *
     * The general form of [playToReady], for a test whose subject arrives at a moment no state name
     * describes — a segment past the live window, the end of the content. Public because the
     * alternative a test reaches for otherwise is a fixed span, and a fixed span measures the host:
     * loads run on real threads, so how much playback time a session needs to get anywhere depends
     * on how busy the machine is, and a span chosen on a quiet one fails on a loaded runner (issue
     * #91). [wanted] names the thing being waited for, in the failure.
     *
     * Bounded by [boundMs] of *playback* time, because a test that hangs is worse than one that
     * fails: the message names the state that never arrived, which is the same promise
     * `TestPlayerRunHelper`'s own waits make. A bound belongs to what is being waited for — the
     * content's own length, a buffer's drain — so a test that has one passes it.
     */
    @JvmOverloads
    public fun advanceUntil(
        player: Player,
        wanted: String,
        boundMs: Long = MAX_WAIT_MS,
        condition: (Player) -> Boolean,
    ) {
        settle(player)
        var waited = 0L
        while (!condition(player) && waited < boundMs) {
            advanceTimeMs(player, WAIT_STEP_MS)
            waited += WAIT_STEP_MS
        }
        check(condition(player)) {
            "Waited $boundMs ms of playback time for $wanted; " +
                "state=${player.playbackState} error=${player.playerError}"
        }
    }

    /**
     * Stops the video renderer being ready, which is what the engine reports as buffering.
     *
     * The engine notices on its next render pass, and a render pass happens when time moves — so
     * this advances the clock by [RENDER_PASS_MS] and no more. That millisecond is part of every
     * stall's measured duration, which is why it is a named constant rather than an arbitrary nudge:
     * a test asserting on a rebuffer's length adds it once at each end and gets an exact number.
     */
    public fun stallRendering(player: Player) {
        rendererFor(player).stall()
        advanceTimeMs(player, RENDER_PASS_MS)
    }

    /** Ends the stall [stallRendering] began, on the same terms. */
    public fun resumeRendering(player: Player) {
        rendererFor(player).resume()
        advanceTimeMs(player, RENDER_PASS_MS)
    }

    /** Fails the video renderer on its next pass — a real `ExoPlaybackException` of renderer type. */
    public fun failRendering(player: Player) {
        rendererFor(player).fail()
        advanceTimeMs(player, RENDER_PASS_MS)
    }

    /** Raises the renderer's dropped-frame callback with [count] frames over [elapsedMs]. */
    public fun reportDroppedFrames(player: Player, count: Int, elapsedMs: Long) {
        rendererFor(player).reportDroppedFrames(count, elapsedMs)
        settle(player)
    }

    /**
     * Releases [player] and forgets it.
     *
     * Optional for a test, which builds a handful and lets [after] release them, and **not optional
     * for a benchmark**, which builds one per run and can build well over a thousand in a single
     * `@Test`. Two things go wrong without it, and the second is worse than the first.
     *
     * The cost is quadratic: [advanceTimeMs] asks every registered player's [HarnessClockWait]
     * whether its transfers have caught up, so a matrix of a thousand players pays a thousand-entry
     * scan on each of hundreds of advances per run. It also keeps every `Surface`, every renderer
     * and every released `ExoPlayer` reachable until the test method ends.
     *
     * The correctness half: a player that was released while a transfer was open leaves a wait that
     * can never catch up, and [advanceTimeMs] fails on it — so a cell fails because of a player
     * released twenty cells earlier, with a message about a load that has nothing to do with it.
     * Handing the player back is what removes that.
     */
    public fun release(player: Player) {
        player.release()
        renderers.remove(player)
        engines.remove(player)
        injectors.remove(player)
        waits.remove(player)
        transportReplays.remove(player)
    }

    /**
     * The device every player here is built on, unless a test states otherwise first.
     *
     * A television-sized display, because a selector that reads the display would otherwise refuse
     * every rung above Robolectric's small default before a bandwidth estimate was consulted — the
     * same defect [ignoreTheViewport] closes for Media3's own viewport constraint, arriving through
     * SuperPlayer's door. A test about the display gate narrows it with [DeviceStatement.declareDisplay]
     * before it builds its player.
     */
    override fun before() {
        DeviceStatement.declareDisplay(DeviceStatement.DEFAULT_DISPLAY_WIDTH_PX, DeviceStatement.DEFAULT_DISPLAY_HEIGHT_PX)
    }

    /** Releases every player this harness built and still holds, newest first. */
    override fun after() {
        renderers.keys.toList().asReversed().forEach { it.release() }
        renderers.clear()
        engines.clear()
        injectors.clear()
        waits.clear()
        transportReplays.clear()
        // After the players, which are what were drawing into them.
        outputs.forEach { (surface, texture) ->
            surface.release()
            texture.release()
        }
        outputs.clear()
    }

    private fun rendererFor(player: Player): ControllableVideoRenderer =
        checkNotNull(renderers[player]) { "This harness did not build that player" }

    /**
     * Media3's fake renderers rather than real ones: a real `MediaCodecVideoRenderer` under
     * Robolectric needs a shadow codec and reports a first frame only if the shadow cooperates,
     * which makes the most important metric in the schema depend on a shadow's behaviour rather than
     * on the library's. [ControllableVideoRenderer] reports the same callbacks and can be made to
     * stall and to fail.
     */
    private fun renderersFactory(onVideoRenderer: (ControllableVideoRenderer) -> Unit) =
        RenderersFactory { eventHandler, videoListener, audioListener, _, _ ->
            val handler = clock.createHandler(eventHandler.looper, /* callback= */ null)
            val video = ControllableVideoRenderer(handler, videoListener)
            onVideoRenderer(video)
            arrayOf<Renderer>(video, FakeAudioRenderer(handler, audioListener))
        }

    /**
     * The content [content] describes, as something the engine can load.
     *
     * A ladder becomes a [FakeAdaptiveMediaSource] over a [FakeChunkSource], which is Media3's own
     * adaptive machinery and therefore switches renditions the way the real one does; anything else
     * becomes a [FakeMediaSource] with a single video track, because a source with nothing to choose
     * between should not go through a chunk source that exists to choose.
     */
    private fun describedMediaSourceFactory(content: TestContent, transfers: Transfers): MediaSource.Factory {
        val formats = content.rungs.mapIndexed { index, rung -> videoFormat(index, rung) }
        val timeline = FakeTimeline(
            FakeTimeline.TimelineWindowDefinition.Builder()
                .setDurationUs(content.durationMs * 1_000)
                // Zero, against Media3's own non-zero default. The default puts the window at an
                // offset inside its period, so the renderer's stream positions start ~123 s in
                // while the synthesized samples start at zero — every sample is then already in the
                // past, the renderer consumes the lot on its first pass and reports itself ended,
                // and an ended renderer's readiness is never consulted again.
                .setWindowPositionInFirstPeriodUs(0)
                .setSeekable(true)
                .setLive(content.live)
                .setDynamic(content.live)
                // A live window has to say when it started in wall-clock terms, or the player has
                // nothing to measure a live offset against and reports `TIME_UNSET` — which would
                // make a test of live-edge latency pass by finding no latency at all. Placed so the
                // edge is now: the window ends at the current wall clock.
                .apply {
                    if (content.live) {
                        setWindowStartTimeUs(System.currentTimeMillis() * 1_000 - content.durationMs * 1_000)
                    }
                }
                .build(),
        )
        return object : MediaSource.Factory {
            override fun createMediaSource(mediaItem: androidx.media3.common.MediaItem): MediaSource =
                // A ladder needs the adaptive source to have anything to switch between; a fault
                // script or a trace needs it because it is the only path here that loads through a
                // `DataSource` at all — Media3's non-adaptive fake synthesizes its samples in
                // memory, so there is no transfer for an injector or a shaper to sit in front of.
                if (formats.size > 1 || transfers.loadsThroughADataSource) {
                    FakeAdaptiveMediaSource(
                        timeline,
                        TrackGroupArray(TrackGroup(*formats.toTypedArray())),
                        TransferChunkSourceFactory(
                            // A fixed seed: the chunk sizes this generates are what a bandwidth
                            // estimate is built from, and a test whose ABR decisions moved between
                            // runs would be a test of the random number generator.
                            FakeAdaptiveDataSet.Factory(CHUNK_DURATION_US, /* bitratePercentStdDev= */ 0.0, Random(0)),
                            // The adaptive path is where [networkOrigin] matters most: it is the
                            // one with renditions to choose between.
                            networkOrigin(),
                            transfers.wrap,
                        ),
                    )
                } else {
                    // Samples across the whole window, not Media3's default single sample. A source
                    // that hands the renderer one frame and then end-of-stream leaves it
                    // permanently `isEnded`, and an ended renderer's readiness is not consulted —
                    // so every injected stall would be invisible and every buffering assertion
                    // would pass for the wrong reason.
                    FakeMediaSource(
                        timeline,
                        DrmSessionManager.DRM_UNSUPPORTED,
                        FakeMediaPeriod.TrackDataFactory.samplesWithRateDurationAndKeyframeInterval(
                            /* initialSampleTimeUs= */ 0,
                            /* sampleRate= */ FRAME_RATE,
                            /* durationUs= */ content.durationMs * 1_000,
                            /* keyFrameInterval= */ KEYFRAME_INTERVAL_FRAMES,
                        ),
                        formats.single(),
                    )
                }

            override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

            override fun setDrmSessionManagerProvider(
                provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
            ): MediaSource.Factory = this

            override fun setLoadErrorHandlingPolicy(
                policy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
            ): MediaSource.Factory = this
        }
    }

    /**
     * A fake origin that declares itself a **network** source, which is not the default and is
     * load-bearing.
     *
     * Media3's `FakeDataSource` defaults to `isNetwork = false`, which is right for what that class
     * is usually for — standing in for something already on the device. Here it is the only thing
     * below the shaper and the injector, so it stands in for an origin at the far end of a network,
     * and the flag decides something much larger than it looks.
     *
     * `DefaultBandwidthMeter` **ignores every transfer whose source is not a network one** — the same
     * mechanism that keeps cache hits out of the estimate (`PRD.md` §2.4, ADR-0002). With the
     * default, the engine's bandwidth meter receives no samples at all in this harness, its estimate
     * stays pinned at Media3's initial 1 Mbit/s for the whole session, and `AdaptiveTrackSelection`
     * picks the highest rung fitting inside 70% of that — the bottom of any realistic ladder — and
     * never moves off it, whatever the [ThroughputTrace] underneath is doing.
     *
     * The failure mode is silent, and it is the one `CLAUDE.md` warns about: a layer that drops the
     * measurement blinds ABR without failing anything. Everything still plays. A benchmark of it
     * reports the same bitrate on a 1 Mbit/s link and on a 20 Mbit/s one, which reads as a finding
     * rather than as an unplugged instrument — which is how this was found.
     *
     * The timing the meter then sees is the shaper's, and that is correct: the wrappers forward
     * `addTransferListener` down to this source rather than re-raising the callbacks themselves
     * (see [TransferChunkSourceFactory]), so a byte is reported when the origin actually hands it
     * over, which is when the shaper asked for it, paced by the trace.
     */
    private fun networkOrigin(): FakeDataSource.Factory = FakeDataSource.Factory().setIsNetwork(true)

    /**
     * The bytes [content] is served from, or an empty set for content Media3 synthesizes.
     *
     * `superplayer-testmedia` hands a stream over as URI-to-bytes and names no Media3 type — see
     * `docs/testing.md` for why the one home both this module and `superplayer-core`'s tests can
     * reach has to be below both — so putting one into a [FakeDataSet] is this line, here.
     */
    private fun fakeDataSetFor(content: TestContent): FakeDataSet = FakeDataSet().apply {
        content.resources.forEach { (uri, bytes) -> setData(uri, bytes) }
    }

    /**
     * Everything [composeTransport] built for one player: the [Transfers] the engine loads through,
     * and the objects the harness keeps hold of afterwards — the injector, which records what was
     * requested; the wait, which is how [advanceTimeMs] knows a load has caught up with the clock;
     * and under a trace its transport replay, which [advanceTimeMs] drives.
     */
    private class Transport(
        val transfers: Transfers,
        val injector: FaultInjectingDataSource.Factory,
        val wait: HarnessClockWait,
        val loadThreads: HarnessLoadThreads,
        /** The trace's transport, replayed into the platform; null when no trace is replayed. */
        val transportReplay: TransportReplay?,
    )

    /**
     * How a player's transfers are made: [factory] where Media3 makes its own sources, [wrap] where
     * `FakeChunkSource` hands over one it built, and whether anything is in the path at all.
     */
    private class Transfers(
        val factory: androidx.media3.datasource.DataSource.Factory,
        val wrap: (androidx.media3.datasource.DataSource) -> androidx.media3.datasource.DataSource,
        val loadsThroughADataSource: Boolean,
    )

    /** A rung as a Media3 `Format`; what the rung says is `TestContent.Rung`'s subject, not this one's. */
    private fun videoFormat(index: Int, rung: TestContent.Rung): Format = Format.Builder()
        .setId("video-$index")
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setCodecs(rung.codecs)
        .setPeakBitrate(rung.bitrateBps)
        .setAverageBitrate(rung.bitrateBps)
        .setWidth(rung.heightPx * WIDTH_NUMERATOR / HEIGHT_DENOMINATOR)
        .setHeight(rung.heightPx)
        .setFrameRate(FRAME_RATE)
        .build()

    public companion object {
        /**
         * How far time has to move for the engine to take another render pass.
         *
         * `ExoPlayerImplInternal` schedules its work loop every 10 ms while playback is active, so
         * advancing the clock by less than that changes nothing at all — a fault injected and then
         * "settled" over 1 ms is a fault the engine never sees, which is a test that passes for the
         * wrong reason.
         *
         * Named because it is measurable: a stall injected and released through this harness lasts
         * whatever the test advanced plus two of these, and a test that could not name the overhead
         * would have to assert on a range instead of a value.
         */
        public const val RENDER_PASS_MS: Long = 10L

        /**
         * How far time moves per step while [playToReady] waits — a few render passes, so a wait is
         * not five hundred of them.
         *
         * Public because it is measurable: content that is not ready instantly costs whole steps of
         * playback time, and they land inside any duration measured across the start of playback. A
         * test asserting on a time to first frame names this rather than rounding it away.
         */
        public const val WAIT_STEP_MS: Long = 50L

        /** Playback time, not wall-clock: generous, and only ever reached when something is wrong. */
        private const val MAX_WAIT_MS = 30_000L

        /** Real milliseconds [awaitLoads] gives loads to catch up with one advance. */
        private const val CATCH_UP_WALL_CLOCK_MS = 10_000L

        /**
         * One step of [advanceTimeInStepsMs]: a few render passes and a fraction of a chunk.
         *
         * Short enough that segments load in the order playback needs them, long enough that a
         * minute of playback is not six thousand advances.
         */
        private const val LOAD_STEP_MS = 250L

        /** Two seconds, the segment duration both the HLS and DASH interoperability profiles use. */
        private val CHUNK_DURATION_US = 2_000_000L

        /** 16:9, as every rung of a modern ladder is: width is height × 16 ÷ 9. */
        private const val WIDTH_NUMERATOR = 16
        private const val HEIGHT_DENOMINATOR = 9

        private val FRAME_RATE = 30f

        /** One keyframe a second at [FRAME_RATE], which is what a real encoder ladder uses. */
        private val KEYFRAME_INTERVAL_FRAMES = 30
    }
}
