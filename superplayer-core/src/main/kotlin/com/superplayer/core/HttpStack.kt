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

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpEngineDataSource
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Which HTTP client the bytes below `MediaSource` travel over.
 *
 * Handed to `SuperPlayer.Builder.setHttpStack`, and resolved at the one place any chain names a
 * transport (`TransferChain`'s `resolveTransport`, ADR-0016 rule 1). An app that names none keeps
 * exactly the stack that shipped before it (rule 14) — which is [default], resolved through this
 * same type rather than through a second line, so that "the default is what `default()` is" is true
 * by construction and not by two copies staying in step.
 *
 * The constructor is `internal` for [ContentCache]'s reason, and it is the reason ADR-0016 rule 11
 * gives: **the set of things core can build is core's, and a consumer can hold one without being
 * able to forge one.** What a stack has to hand the chain is a Media3 `DataSource.Factory`, which
 * carries `@UnstableApi` and which ADR-0001 rule 2 keeps out of public API — so the only way to make
 * one is a factory on this type, and the only thing a consumer supplies is an [HttpTransport], which
 * names no Media3 type at all.
 *
 * That is also why this is a shell with internal members rather than an interface a consumer
 * implements: an interface would put the adaptation — the range, the status, the measurement
 * bookkeeping — on the far side of the boundary, where each adopter would have to get it right
 * once (ADR-0016 rules 8 and 9).
 *
 * ## Why the factory is asked for rather than held
 *
 * #309 held it as a constructor property, which was right while every stack could be built the
 * moment the consumer named it. [httpEngine] cannot: the platform object it loads over needs a
 * `Context`, and on a device below [HTTP_ENGINE_MIN_API_LEVEL] it cannot be built at all. So the
 * chain *asks* a stack for its factory at the one point that has a context and is inside `build()`,
 * and a stack that cannot answer refuses there (rule 12) instead of at the first segment.
 */
public abstract class HttpStack internal constructor() {

    /**
     * The HTTP half of the bottom of a chain, for a player, pool, store or doctor built for
     * [context] — `http:` and `https:` and nothing else, because the other five schemes stay
     * `DefaultDataSource`'s whatever a consumer selects (`TransferChain.resolveTransport`).
     *
     * Called once per chain. An implementation that holds a process-heavy object builds it once and
     * answers with it, rather than once per call.
     */
    internal abstract fun httpFactory(context: Context): DataSource.Factory

    /**
     * Raises [HttpStackUnsupportedException] where this device cannot honour this selection.
     *
     * Empty here because two of the three stacks ask the device for nothing: an [HttpTransport] is
     * the consumer's own code and [default] is the platform's `HttpURLConnection`, which has been
     * there since before `minSdk`. The chain asks this **before** it consults a test's transport
     * slot, so that whether a selection can be honoured stays a fact about the device and the
     * selection rather than one a harness could hide by replacing the network.
     *
     * The one way past it is `EngineConfiguration.mediaSourceFactory`, which replaces the whole
     * loading path and therefore never reaches `TransferChain` at all. That is a test's seam and no
     * consumer's, and a caller who has supplied their own loading path has supplied their own
     * transport with it, so there is nothing left for a stack to say.
     */
    internal open fun refuseUnlessHonourable() {}

    public companion object {

        /**
         * The Android release `android.net.http.HttpEngine` arrived in, and therefore the floor
         * [httpEngine] refuses below.
         *
         * // ref: `android.net.http.HttpEngine` is platform public API added in Android 14
         * (API 34, `UPSIDE_DOWN_CAKE`). There is no support-library backport of it — an app cannot
         * carry it the way it carries an AndroidX class — which is why a device below the floor is
         * refused rather than shimmed.
         *
         * The floor is stated as an API level and not as an SDK-extension version, although the
         * class also reaches some Android 12 and 13 devices through the Connectivity mainline
         * module: ADR-0016 rule 12 and #313 name API 34, so the narrower promise is the one kept,
         * and the reason is argued where the refusal is raised.
         */
        public const val HTTP_ENGINE_MIN_API_LEVEL: Int = Build.VERSION_CODES.UPSIDE_DOWN_CAKE

        /**
         * What ships when nothing is said: Media3's `DefaultHttpDataSource` over the platform's
         * `HttpURLConnection`.
         *
         * Offered as a name because an implicit default is one a consumer cannot be explicit about,
         * and "which stack am I on" is a question a bug report has to be able to answer. A player
         * built with this is indistinguishable from one built with no stack at all — not merely
         * equivalent to it, the same object graph, because the unstated path resolves through here.
         *
         * **Java calls this `HttpStack.defaultStack()`.** `default` is a reserved word in Java, so
         * `HttpStack.default()` does not parse there and a `@JvmStatic` with no alias would be a
         * public method Java could not invoke at all. The Kotlin name is ADR-0016 rule 11's and is
         * kept; the alias is the whole of the accommodation, and it is here rather than in a release
         * note because the two names are one method and a reader of either has to find the other.
         */
        @JvmStatic
        @JvmName("defaultStack")
        public fun default(): HttpStack = DefaultStack

        /**
         * Loads over the **platform's** HTTP engine — `android.net.http.HttpEngine`, which is
         * Cronet shipped and updated by the system rather than by this app.
         *
         * It is the one alternative transport core can offer without a consumer supplying anything
         * and without this repository taking a dependency: Media3's `HttpEngineDataSource` is in
         * `media3-datasource`, which core already resolves, so there is no artifact to add and no
         * licence question to answer (ADR-0016 rule 11). What it buys is HTTP/3 and QUIC on devices
         * whose CDN speaks them.
         *
         * **It needs API [HTTP_ENGINE_MIN_API_LEVEL], and below that it refuses**: `build()` raises
         * [HttpStackUnsupportedException] naming the stack and the level, rather than quietly
         * substituting [default] (rule 12). An app that supports older devices selects it behind its
         * own `Build.VERSION.SDK_INT` check and passes [default] otherwise.
         *
         * Hold **one** and pass it to every entry point that composes a chain. Today that is
         * `SuperPlayer.Builder` alone; rule 13 names four, and the pool's, the store's and the
         * doctor's arrive with #314. Each call here is a separate stack and therefore a separate
         * `HttpEngine`, and the platform's engine is a process-sized object meant to be shared: an
         * app that calls this once per screen gets one of them per screen, with a set of connections
         * and a cache each.
         *
         * Nothing shuts the engine down, deliberately. `HttpEngine.shutdown()` exists, and there is
         * no honest moment to call it from here: an [HttpStack] has no lifetime of its own — it is a
         * value a consumer holds and hands to however many builders they like — and core would have
         * to reference-count the chains built from it to know when the last one had finished, which
         * it does for no other stack and which a consumer's own [HttpTransport] is likewise trusted
         * to outlive. So the engine lives for the process, which is what the platform's own object
         * is shaped for, and an app that wants it gone releases the process.
         */
        @JvmStatic
        public fun httpEngine(): HttpStack = HttpEngineStack()

        /**
         * Loads over [transport]: an HTTP client the consumer wrote, over whatever stack their app
         * already ships.
         *
         * ```kotlin
         * val player = SuperPlayer.Builder(context)
         *     .setHttpStack(HttpStack.of(OkHttpTransport(app.okHttpClient)))
         *     .build()
         * ```
         *
         * Everything above the bytes is adapted behind this boundary and is not the consumer's to
         * get right: the Media3 `DataSource`, the transfer reporting a bandwidth estimate is
         * derived from, and the typed failure the fallback ladder and the classifier both read.
         * What [transport] owes in exchange is in [HttpTransport]'s KDoc, obligation by obligation,
         * each with what getting it wrong costs.
         */
        @JvmStatic
        public fun of(transport: HttpTransport): HttpStack = ConsumerTransportStack(transport)
    }
}

/**
 * The stack that was always there, named.
 *
 * An `object` rather than a new instance per call because it holds nothing: `DefaultHttpDataSource`
 * builds its connection per data source, so there is no state for two of these to disagree about.
 * The factory is new per chain, which is what Media3's own default supplier does and what keeps a
 * chain's default request properties its own.
 */
private object DefaultStack : HttpStack() {

    override fun httpFactory(context: Context): DataSource.Factory = DefaultHttpDataSource.Factory()
}

/**
 * The platform's engine, built here rather than by the consumer, because building it is the part
 * that names a Media3 type and an `@RequiresApi` class.
 *
 * The engine is built **once per stack, on the first chain that asks**, and not in [HttpStack.httpEngine]:
 * a consumer selects a stack wherever it is convenient and builds a player wherever the screen is,
 * and the refusal is owed to `build()` (ADR-0016 rule 12) rather than to whichever of the two came
 * first. Failing to build it is not caught and turned into anything: an engine the platform declines
 * to make on a device that is new enough is a fault of that device, and hiding it under our own
 * exception would say API level when the reason was something else.
 */
private class HttpEngineStack : HttpStack() {

    /**
     * The one engine, and the one thread pool its callbacks run on.
     *
     * A field rather than a constructor argument, because the constructor has no `Context`. It is
     * only ever written from [httpFactory], which the chain reaches only after
     * [refuseUnlessHonourable] has let it through, so the `@RequiresApi` body is unreachable below
     * the floor.
     */
    private var engine: HttpEngine? = null

    override fun refuseUnlessHonourable() {
        if (Build.VERSION.SDK_INT < HTTP_ENGINE_MIN_API_LEVEL) {
            throw HttpStackUnsupportedException(
                stack = "HttpStack.httpEngine()",
                requiredApiLevel = HTTP_ENGINE_MIN_API_LEVEL,
                deviceApiLevel = Build.VERSION.SDK_INT,
            )
        }
    }

    // Media3 marks `HttpEngineDataSource.Factory` `@RequiresExtension(S, 7)` rather than
    // `@RequiresApi(34)`, because `android.net.http.HttpEngine` reaches Android 12 and 13 devices
    // through the Connectivity mainline module as well. The gate above is strictly stronger than
    // that annotation — the class is *platform* public API from API 34, so every device past the
    // floor carries at least extension 7 — and lint has no way to draw that inference from an
    // `SDK_INT` comparison, which is the whole of what this suppression covers.
    //
    // Gating on the extension version instead would reach further, onto devices with a recent
    // module below API 34. It is deliberately not done here: ADR-0016 rule 12 and #313 name API 34
    // as the floor, and widening it is a change to what a consumer is promised rather than a lint
    // accommodation. It would also make the refusal harder to read, since "your device is API 33"
    // explains itself and "your Connectivity module is older than extension 7" does not.
    // Synchronized because the four entry points rule 13 names are four `build()` calls a consumer
    // may make from wherever they like, and two racing here would each build an engine while only
    // one of them was kept — a process-sized object and its connections leaked for the life of the
    // app. It is contended once per stack at most.
    @Synchronized
    @SuppressLint("NewApi")
    @RequiresApi(HTTP_ENGINE_MIN_API_LEVEL)
    override fun httpFactory(context: Context): DataSource.Factory {
        // No second API check here: [refuseUnlessHonourable] is the one place it is made, and the
        // chain asks it first and unconditionally. A guard repeated here would read as belt and
        // braces and would in fact be worse than none — it would keep passing after the call the
        // consumer's `build()` actually depends on had been deleted, which is the mutation a test
        // has to be able to see.
        val engine = engine ?: HttpEngine.Builder(context.applicationContext).build().also { engine = it }
        return HttpEngineDataSource.Factory(engine, CALLBACK_EXECUTOR)
    }

    private companion object {

        /**
         * Where `HttpEngine` runs the callbacks that feed a transfer.
         *
         * It cannot be the caller's thread: `HttpEngineDataSource` blocks the loader thread that
         * called `open` or `read` and is woken by a callback, so a direct executor would wait for
         * itself. It has to be able to grow, because a player, its downloads and a doctor can all
         * have a transfer in flight at once and each holds one of these threads for the length of
         * its read.
         *
         * One pool for the process rather than one per stack: the threads are idle between
         * callbacks, the pool retires them after its own timeout, and an app that ends up with two
         * of these stacks should not thereby get two sets of threads. Daemon threads, so the pool
         * never keeps a JVM alive — the same reason `TelemetryDelivery`'s one thread is a daemon.
         */
        val CALLBACK_EXECUTOR: Executor =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "SuperPlayer:HttpEngine").apply { isDaemon = true }
            }
    }
}

/**
 * A consumer's [HttpTransport], adapted.
 *
 * Private rather than an object a caller could name, because `of` is the whole of the vocabulary —
 * a second way to reach the same thing is a second thing to keep working.
 */
private class ConsumerTransportStack(transport: HttpTransport) : HttpStack() {

    /**
     * One factory for every chain this stack is passed to, because the transport it wraps is one
     * object the consumer handed over and two factories over it would be two names for it.
     */
    private val factory = HttpTransportDataSource.Factory(transport)

    override fun httpFactory(context: Context): DataSource.Factory = factory
}
