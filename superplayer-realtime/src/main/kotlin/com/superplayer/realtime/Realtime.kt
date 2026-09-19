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

package com.superplayer.realtime

import com.superplayer.core.RealtimeMediaSources
import com.superplayer.core.RealtimeSources
import com.superplayer.core.SuperPlayer

/**
 * Where a realtime transport is handed to a player.
 *
 * ```kotlin
 * val player = SuperPlayer.Builder(context)
 *     .setRealtime(Realtime.transport(scheme = "moq") { uri -> MoqFrameSource(relay, uri) })
 *     .build()
 *
 * player.setMediaRequest(
 *     MediaRequest(contentId = "studio-a", sources = listOf(Uri.parse("moq://relay.example/studio-a"))),
 * )
 * ```
 *
 * What this returns is [SuperPlayer.Builder.setRealtime]'s argument and nothing else: the dispatch is
 * composed into the loading path as the engine is built, and a player built without the call has no
 * realtime path at all (ADR-0018 rule 12).
 */
public object Realtime {

    /**
     * The transport answering URIs with [scheme], opening one [FrameSource] per playback through
     * [sources].
     *
     * [scheme] is matched case-insensitively against the URI's own scheme and carries no `:`. It is
     * **the only discriminator** (ADR-0018 rule 12): a `MediaItem` whose URI carries this scheme
     * resolves here whatever MIME type it also declares, because a MIME type is supplied by the same
     * consumer who wrote the URI and nothing can check it against the transport that answers.
     *
     * Choosing the scheme is the transport's, not this library's. A protocol with a registered
     * scheme uses it; one without should pick something an app author will not confuse with `https`.
     */
    public fun transport(scheme: String, sources: FrameSourceFactory): RealtimeSources {
        require(scheme.isNotBlank() && !scheme.contains(':')) {
            "A realtime scheme is a bare scheme without the ':' — \"moq\", not \"moq://\". Got: \"$scheme\""
        }
        return RealtimeTransport(setOf(scheme.lowercase()), sources)
    }
}

/**
 * Core's [RealtimeSources], filled with this module's half.
 *
 * Private, and reached only as the abstract core type: a consumer names [Realtime.transport]'s
 * return type and never this, which is what keeps the Media3 `MediaSource` the base class carries
 * out of anybody's public API (ADR-0001 rule 2).
 */
private class RealtimeTransport(
    schemes: Set<String>,
    sources: FrameSourceFactory,
) : RealtimeSources(
    schemes,
    RealtimeMediaSources { item ->
        // The URI is present because the dispatch read its scheme to get here.
        val uri = checkNotNull(item.localConfiguration).uri
        RealtimeMediaSource(item, sources.open(uri))
    },
)
