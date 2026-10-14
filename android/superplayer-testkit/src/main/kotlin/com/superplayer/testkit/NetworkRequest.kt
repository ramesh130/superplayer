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

/**
 * One request that left a player's loading chain for the network, as the harness's origin saw it.
 *
 * What a cache test counts: a read a local cache answered never becomes one of these, because the
 * harness's transport sits where the HTTP stack does, below every layer SuperPlayer composes. So a
 * replay whose segments come from the cache is a replay with no [ResourceKind.MEDIA_SEGMENT] here.
 *
 * Plain strings rather than a `DataSpec`, for the reason `docs/testing.md` gives about this module's
 * public API naming no Media3 type.
 */
public class NetworkRequest internal constructor(
    /** The whole URI requested, host and query included. */
    public val uri: String,
    /** What kind of resource it names, by the harness's own reading of the URI. */
    public val kind: ResourceKind,
    /** The request headers the chain set — CMCD's among them, when it is sent as headers. */
    public val headers: Map<String, String>,
) {

    override fun toString(): String = "$kind $uri"
}
