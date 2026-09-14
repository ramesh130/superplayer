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

import java.io.IOException

/**
 * A live DASH stream advertises a window too short for any playhead to sit inside.
 *
 * Found as the `cause` of the player's error, as [StaleLivePlaylistException] is and for the same
 * reason: the `PlaybackException` around it carries Media3's own error code, and this type is what
 * makes the failure *named* — what an app branches on to tell a viewer "this channel is misconfigured"
 * rather than "check your connection".
 *
 * ```kotlin
 * when (val cause = player.playerError?.cause) {
 *     is LiveWindowTooShortException -> showChannelUnavailable()
 *     else -> showGenericError()
 * }
 * ```
 *
 * ## Why no offset is playable
 *
 * `@timeShiftBufferDepth` is the window, behind the live edge, in which the manifest guarantees
 * segments are available (// spec: ISO/IEC 23009-1 §5.3.1.2). A segment becomes available only once
 * it is complete, less any `@availabilityTimeOffset` the manifest declares
 * (// spec: ISO/IEC 23009-1 §5.3.9.5.3). So the moment a player can first fetch a segment, that
 * segment's start is already [segmentDurationMs] − [availabilityTimeOffsetMs] behind the edge — and a
 * playhead starting it, with any fetch time at all, is further behind still. When the window is no
 * deeper than that, every position a player could hold lies before the window's start.
 *
 * Media3 does not refuse such a stream. It plays the segments anyway, several seconds behind the
 * window, and reports a position measured from the window's start — a negative one, which jumps on
 * every manifest refresh. Media is heard; the position a seek bar, a resume point or a
 * `PlaybackSnapshot` would be built from is one the stream cannot contain, and the segments are ones
 * the origin has stopped promising, so a CDN that honours its own manifest serves a 404 next. Moving
 * the target live offset changes nothing, because what holds the playhead back is segment
 * availability rather than the offset (issue #67). Failing with a name is the honest outcome.
 *
 * ## When it is raised
 *
 * When a whole manifest of `@type="dynamic"` is received whose window is no deeper than the longest
 * segment's availability lag, before Media3 has parsed it. The engine retries the manifest under its
 * `LoadErrorHandlingPolicy` first, and a retry that finds a deeper window is a recovery rather than an
 * error: nothing here latches. A manifest with no `@timeShiftBufferDepth` promises an unlimited window
 * and is not judged; nor is a segment whose availability offset the parsed manifest does not expose,
 * because an unknown offset may be a low-latency stream that plays inside a short window perfectly well.
 */
public class LiveWindowTooShortException internal constructor(
    /** The manifest that advertised the window, as the engine requested it. */
    public val manifestUri: String,
    /** Its `@timeShiftBufferDepth`. */
    public val timeShiftBufferDepthMs: Long,
    /** The duration of the segment with the longest availability lag — the one the window was judged against. */
    public val segmentDurationMs: Long,
    /** How much earlier than complete that segment is declared available; zero for an ordinary stream. */
    public val availabilityTimeOffsetMs: Long,
) : IOException(
    "The live manifest $manifestUri advertises a timeShiftBufferDepth of $timeShiftBufferDepthMs ms, " +
        "no deeper than its ${segmentDurationMs - availabilityTimeOffsetMs} ms segment availability lag " +
        "($segmentDurationMs ms segments, availabilityTimeOffset $availabilityTimeOffsetMs ms): every " +
        "segment leaves the window before a player can reach it. Likely a packager configured with a " +
        "window in seconds where it meant minutes.",
)
