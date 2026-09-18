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

package com.superplayer.diagnostics

import android.content.Context
import com.superplayer.core.ContentCache
import com.superplayer.core.DiagnosticEnvironment
import com.superplayer.core.MediaRequest
import com.superplayer.core.PlaybackResilience
import com.superplayer.core.SuperPlayerError
import com.superplayer.core.TransferChain

/**
 * What is wrong with this stream, in the words a support engineer needs, before a player is built.
 *
 * ```kotlin
 * val doctor = MediaSourceDoctor.Builder(context).setCache(cache).setResilience(resilience).build()
 * val report = doctor.examine(request)      // the same MediaRequest a player adopts
 * report.findings.forEach { log(it.pathology.id, it.severity, it.specCitation, it.cause) }
 * ```
 *
 * A variant that declares no `CODECS` comes back as a [Finding] naming the defect, its severity, the
 * clause it departs from and the misconfiguration that produces it — rather than as a player that behaves
 * oddly ten minutes later.
 *
 * The same call, handed what a session ended on, is the *postmortem* — the report a support engineer reads
 * after a failure is the report the app could have read before it (ADR-0015 rule 8). See [examine].
 *
 * ## What it fetches, and what it fetches over
 *
 * It reads manifests and playlists, and **downloads no segment**: the pathologies are declarations, and
 * fetching media to check one would make a preflight cost what a start costs (ADR-0015 rule 7).
 *
 * The fetch travels the chain a *player* of that request would load through — `TransferChain`'s, with the
 * [ContentCache] and the [PlaybackResilience] this doctor was built with in it — and never an HTTP stack of
 * its own. That is ADR-0002's argument arriving through a different door: the defects a doctor is most
 * often asked about, an expired token above all, are invisible to a stack that does not carry the app's
 * credential. So a doctor is built with what the players it is answering for are built with, and the report
 * says which of those layers it travelled ([DiagnosticReport.chain]).
 *
 * It is measured by nothing: the chain it composes reports to no bandwidth meter and emits no CMCD, because
 * a doctor's fetch is not a viewing.
 *
 * ## Threading and lifetime
 *
 * [examine] **blocks the calling thread** for as long as the fetch takes, so it is called off the main
 * thread, exactly as any other network read is. It is the whole of the object's activity: a doctor holds no
 * thread, registers nothing with the platform and keeps nothing open between calls, which is why there is
 * nothing to release and why one may be constructed per question or kept for the life of a screen.
 *
 * One doctor may be asked from several threads at once, and holds one chain across them, as the players
 * sharing that same cache and that same credential do.
 */
public class MediaSourceDoctor private constructor(
    context: Context,
    cache: ContentCache?,
    resilience: PlaybackResilience?,
    environment: DiagnosticEnvironment?,
) {

    /**
     * The chain every examination fetches over, composed once: the layer a credential is refreshed by
     * holds the state of what it has already refreshed, so a doctor asked twice pays one refusal rather
     * than two, exactly as a download store's does.
     */
    private val chain = TransferChain.diagnosticChain(context, environment, cache, resilience)

    /**
     * Which optional layers that chain really carries, read off the composition rather than off which
     * setters were called: a `PlaybackResilience` a consumer wrote themselves contributes no layer, and a
     * report that said otherwise would be the bug in the doctor the field exists to prevent.
     */
    private val layers: Set<ChainLayer> = buildSet {
        if (chain.cacheComposed) add(ChainLayer.CONTENT_CACHE)
        if (chain.headerRefreshComposed) add(ChainLayer.HEADER_REFRESH)
    }

    /**
     * Fetches [request]'s manifest over this doctor's chain and answers what is wrong with it.
     *
     * The first entry of [MediaRequest.sources] is examined, because it is the one a player plays; the rest
     * are what rung 4 of the fallback ladder would reach for, and a stream nothing has fallen back to has
     * not been asked to serve anything.
     *
     * A manifest the chain refuses is a [Pathology.MANIFEST_UNREACHABLE] finding rather than an exception
     * (ADR-0015 rule 7): "the doctor threw" is the least useful thing a support ticket can say.
     *
     * ## The postmortem of a session that failed
     *
     * [classification] is what a *failed* session ended on, and passing it is the whole of the difference
     * between a preflight and a postmortem — one call, one report type (ADR-0015 rule 8). A support ticket
     * reading "live stream freezes after 30 s" then comes back as a named defect with a citation beside the
     * class of failure, rather than as the class alone:
     *
     * ```kotlin
     * override fun onPlayerError(error: PlaybackException) {
     *     // null on a player built without `superplayer-resilience` (ADR-0011 rule 14), which answers a
     *     // report of findings and no classification — the existing behaviour, surfacing here.
     *     val report = doctor.examine(request, player.classify(error))
     * }
     * ```
     *
     * The value is **read and never re-derived** (ADR-0015 rule 5): this module depends on no classifier
     * and holds no taxonomy, so the only classification a report can carry is the one `ErrorClassifier`
     * already made (ADR-0011 rule 1). It is carried onto [DiagnosticReport.classification] and read by
     * nothing here — in particular the findings are the same findings the same request answers as a
     * preflight, because what failed narrows, widens and reorders nothing about what the manifest says.
     */
    @JvmOverloads
    public fun examine(request: MediaRequest, classification: SuperPlayerError? = null): DiagnosticReport {
        val source = request.sources.first()
        val findings = ManifestExamination(
            chain.forContent(request.contentId),
            chain.forSegmentsOf(request.contentId),
        ).examine(source)
        return DiagnosticReport(request.contentId, findings, layers, classification)
    }

    /**
     * Builds a [MediaSourceDoctor]. Media3's construction idiom — see ADR-0001 and CONTRIBUTING rule 3.
     *
     * A doctor is built with what the players it answers for are built with, because rule 7 makes its
     * answer that player's answer. A doctor given neither is the doctor of a player given neither, and is
     * the right one for an app that has neither.
     */
    public class Builder(private val context: Context) {

        private var cache: ContentCache? = null
        private var resilience: PlaybackResilience? = null
        private var environment: DiagnosticEnvironment? = null

        /** The cache the players of this content are built with, so a manifest on disk is read from disk. */
        public fun setCache(cache: ContentCache): Builder = apply { this.cache = cache }

        /**
         * The resilience the players of this content are built with, so the credential the app mints and
         * the refresh a 401 or 403 triggers are the ones those players carry.
         */
        public fun setResilience(resilience: PlaybackResilience): Builder = apply { this.resilience = resilience }

        /**
         * Where this doctor's fetch travels, when that is not the device's own network: a
         * `PlaybackHarness.diagnosticEnvironment`, and nothing else can make one.
         *
         * Internal, as the download store's twin is: a consumer never has a [DiagnosticEnvironment], so the
         * only code that can call this is this module's own tests.
         */
        internal fun setEnvironment(environment: DiagnosticEnvironment): Builder =
            apply { this.environment = environment }

        public fun build(): MediaSourceDoctor =
            MediaSourceDoctor(context.applicationContext, cache, resilience, environment)
    }
}
