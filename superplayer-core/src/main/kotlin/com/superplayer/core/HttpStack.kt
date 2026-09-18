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

import androidx.media3.datasource.DataSource

/**
 * Which HTTP client the bytes below `MediaSource` travel over.
 *
 * Handed to `SuperPlayer.Builder.setHttpStack`, and resolved at the one place any chain names a
 * transport (`TransferChain`'s `resolveTransport`, ADR-0016 rule 1). An app that names none keeps
 * exactly the stack that shipped before it (rule 14).
 *
 * The constructor is `internal` for [ContentCache]'s reason, and it is the reason ADR-0016 rule 11
 * gives: **the set of things core can build is core's, and a consumer can hold one without being
 * able to forge one.** What a stack has to hand the chain is a Media3 `DataSource.Factory`, which
 * carries `@UnstableApi` and which ADR-0001 rule 2 keeps out of public API — so the only way to make
 * one is a factory on this type, and the only thing a consumer supplies is an [HttpTransport], which
 * names no Media3 type at all.
 *
 * That is also why this is a shell with an internal member rather than an interface a consumer
 * implements: an interface would put the adaptation — the range, the status, the measurement
 * bookkeeping — on the far side of the boundary, where each adopter would have to get it right
 * once (ADR-0016 rules 8 and 9).
 */
public abstract class HttpStack internal constructor(
    /** What the bottom of a chain is built from; see `TransferChain`. */
    internal val factory: DataSource.Factory,
) {

    public companion object {

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
 * The one [HttpStack] there is today: a consumer's [HttpTransport], adapted.
 *
 * Private rather than an object a caller could name, because `of` is the whole of the vocabulary —
 * a second way to reach the same thing is a second thing to keep working.
 */
private class ConsumerTransportStack(transport: HttpTransport) : HttpStack(HttpTransportDataSource.Factory(transport))
