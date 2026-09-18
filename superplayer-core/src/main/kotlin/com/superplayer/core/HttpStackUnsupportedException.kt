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

/**
 * The [HttpStack] a consumer selected cannot be honoured on this device, so the player, pool, store
 * or doctor was not built (ADR-0016 rule 12, ADR-0004 rule 4 unchanged, issue #313).
 *
 * Today there is exactly one way to meet it: [HttpStack.httpEngine] on a device below
 * [HttpStack.HTTP_ENGINE_MIN_API_LEVEL], where the platform class it loads over does not exist.
 *
 * ## Why this is raised rather than substituted
 *
 * Falling back to the default stack would be the friendlier-looking answer and is refused for the
 * reason ADR-0004 gave: **it makes a bandwidth-estimate anomaly impossible to explain afterwards.**
 * A consumer who asked for HTTP/3 and quietly got `DefaultHttpDataSource` has nothing in the numbers
 * to discover that from — the sessions are simply slower on some devices than on others, and no
 * telemetry field says which stack carried them.
 *
 * ## Why at `build()` rather than at the first segment
 *
 * The same instinct that made ADR-0004 reject the `compileOnly` alternative, where an unsatisfiable
 * selection would have surfaced as a `NoClassDefFoundError` somewhere inside playback. A refusal at
 * construction is a line in a crash report next to the call that asked for it; a refusal at the
 * first load is a failed session on a device the developer does not own.
 *
 * ## Why it is not an `IOException` and carries no `FailureClass`
 *
 * The five public exceptions core raised before this one are all `IOException`s, because all five
 * describe a *load* that went wrong. This one describes a *configuration* that cannot be honoured,
 * before any byte is asked for, and the difference is mechanical as well as conceptual: Kotlin emits
 * no `throws` clause, so an `IOException` raised out of `build()` is one a Java consumer cannot name
 * in a `catch` at all — javac rejects the clause as unreachable. `UnsupportedOperationException` is
 * the parent because that is what this is, in `java.lang`'s own words: the operation asked for is
 * not supported here.
 *
 * It is deliberately **not** classifiable. `superplayer-resilience`'s `ErrorClassifier` is total over
 * the failures a *player* surfaces, and every answer it gives is ultimately about which rung of the
 * fallback ladder may be climbed (ADR-0011 rules 1 and 5). There is no rung for "this device is
 * API 33", no player to deliver it on, and no session for it to end: giving it a `FailureClass`
 * would put a build-time configuration refusal into a taxonomy whose whole purpose is choosing what
 * to retry.
 */
public class HttpStackUnsupportedException internal constructor(

    /** The stack that was asked for, spelled as the factory call that made it — `HttpStack.httpEngine()`. */
    public val stack: String,

    /** The API level that stack needs, and the reason it needs it, is on the factory that names it. */
    public val requiredApiLevel: Int,

    /** What this device reported — `Build.VERSION.SDK_INT`, read once where the refusal is raised. */
    public val deviceApiLevel: Int,
) : UnsupportedOperationException(
    "$stack needs API $requiredApiLevel and this device is API $deviceApiLevel. " +
        "SuperPlayer does not substitute another stack for one that was asked for: " +
        "select it only where Build.VERSION.SDK_INT >= $requiredApiLevel, or pass HttpStack.default().",
)
