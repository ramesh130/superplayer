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

import java.util.UUID

/**
 * The id one player's current measurement session is known by, in the one place both of its readers
 * can see it.
 *
 * There are two consumers of a session id and they never meet. `superplayer-telemetry`'s collector
 * stamps it on every [TelemetryEvent], so it is the key a warehouse groups a view by. The CMCD seam
 * sends it to the CDN as CTA-5004's `sid`, so it is the key a CDN access log groups a view by. Those
 * two logs are worth having separately and worth *joining* far more, and the join is an equality on
 * this string — which only works if one place mints it. ADR-0008 rule 8 requires the shared id;
 * `docs/telemetry-schema.md` states the join as a promise a data engineer can rely on.
 *
 * Core is that place, for the same reason core signals the session boundaries at all: only core knows
 * which item change carried a [MediaRequest] and which was a recycle. A collector cannot mint it,
 * because a player built without telemetry still emits CMCD and would then have no id; the CMCD seam
 * cannot mint it, because it is handed a `MediaItem` at prepare time and knows nothing about
 * sessions.
 *
 * ## Why the id is keyed by content
 *
 * [idFor] answers only for the content the open session is actually about. The alternative — a bare
 * "current session id" — is wrong in a way that would be invisible: a consumer who mixes
 * `setMediaRequest` with Media3's own `setMediaItem` on one player would have the raw item's requests
 * stamped with the *previous* content's `sid`, quietly attributing one title's CDN traffic to
 * another. An item with no identity gets no `sid`, which is the same answer [PlaybackSnapshot] gives
 * for the same reason.
 *
 * ## Threading
 *
 * [open] and [close] are called on the application thread, from the facade; [idFor] is called on a
 * playback thread, when a media source is created. Hence `@Volatile` on the one field and an
 * immutable value inside it: the reader either sees the whole of the previous session or the whole
 * of the next one, never half of either.
 */
internal class MeasurementSession(
    /**
     * Whether an id is worth minting at all — true when this player has a collector, a CMCD mode
     * other than [CmcdMode.DISABLED], or both.
     *
     * A player with neither has no reader for the id, and minting one anyway would be a cost paid
     * for a feature that was not asked for, which is the accounting ADR-0008 rule 2 applies to every
     * other telemetry field on the facade. [open] returns null for such a player, and the facade has
     * nothing to hand anyone.
     */
    private val enabled: Boolean,
) {

    /** The content id and session id of the session currently open, or null when none is. */
    @Volatile
    private var open: Open? = null

    /**
     * Opens a session for [contentId] and returns its id, or null when this player has no reader for
     * one. Replaces whatever was open, exactly as `TelemetryCollector.startSession` does.
     */
    fun open(contentId: String): String? {
        if (!enabled) return null
        // spec: CTA-5004 §3.1 caps `sid` at 64 characters and asks for a GUID; a UUID's canonical
        // 36-character form satisfies both, and Media3's CmcdConfiguration rejects anything longer.
        val id = UUID.randomUUID().toString()
        open = Open(contentId, id)
        return id
    }

    /** Closes whatever is open. A no-op when nothing is, so release after recycle is harmless. */
    fun close() {
        open = null
    }

    /**
     * The open session's id if it is the session for [contentId], and null otherwise — for content
     * this player cannot name, for a player between sessions, and for one with no reader for an id.
     */
    fun idFor(contentId: String?): String? = open?.takeIf { it.contentId == contentId }?.id

    private class Open(val contentId: String, val id: String)
}
