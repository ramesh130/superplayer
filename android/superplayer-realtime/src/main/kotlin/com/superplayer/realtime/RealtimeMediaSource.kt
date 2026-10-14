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

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocator
import com.superplayer.core.FrameSource

/**
 * The `MediaSource` a realtime item is played through: a live, unseekable timeline over one
 * [RealtimeMediaPeriod] fed by a [FrameSource].
 *
 * Internal, because every type in its signature is Media3 `@UnstableApi` vocabulary (ADR-0001
 * rule 2). It is reached only from core's one dispatch point, on the URI's scheme (ADR-0018
 * rule 12).
 *
 * **Nothing here loads.** There is no `DataSource`, no `Loader` and no `LoadEventInfo`, which is
 * ADR-0018 rule 6 by construction: `onLoadStarted` and `onLoadCompleted` never fire, so a realtime
 * session contributes no bandwidth sample and `BandwidthOracle` observes nothing (rule 8). Rebuffer
 * and startup metrics survive untouched, because those are derived from playback state transitions
 * rather than from loads.
 */
internal class RealtimeMediaSource(
    private val item: MediaItem,
    private val frameSource: FrameSource,
) : BaseMediaSource() {

    override fun getMediaItem(): MediaItem = item

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        // Published immediately and once: there is nothing to fetch before a realtime timeline can be
        // described, because the description does not depend on anything the transport says. What the
        // transport supplies is the track and the frames, and those arrive at the period.
        //
        // The timeline is the whole of ADR-0018 rule 5's mechanical half:
        //   - duration `TIME_UNSET`, because a live stream has no end to name;
        //   - `isSeekable = false`, so `Player.isCurrentMediaItemSeekable` is false and a seek bar
        //     built on it is inert rather than lying;
        //   - `isDynamic = true`, which is Media3's own word for a timeline that can still change.
        //
        // `useLiveConfiguration` is deliberately **false**. Setting it true engages Media3's live
        // offset targeting and `LivePlaybackSpeedControl`, which adjusts playback speed to chase an
        // edge computed from a manifest's declared window — numbers a push transport does not supply
        // and this phase measures nothing to choose. A speed control fed guesses is worse than none,
        // and turning it on later is additive rather than breaking.
        refreshSourceInfo(
            SinglePeriodTimeline(
                /* durationUs = */ C.TIME_UNSET,
                /* isSeekable = */ false,
                /* isDynamic = */ true,
                /* useLiveConfiguration = */ false,
                /* manifest = */ null,
                /* mediaItem = */ item,
            ),
        )
    }

    override fun releaseSourceInternal() {
        // The period cancels its own subscription; a source released before any period was created
        // has nothing subscribed to cancel.
    }

    override fun maybeThrowSourceInfoRefreshError() {
        // Nothing is fetched to describe this timeline, so there is no refresh that can fail. A
        // transport that cannot connect fails at the period, where the subscription is.
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = RealtimeMediaPeriod(frameSource, allocator)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        (mediaPeriod as RealtimeMediaPeriod).release()
    }
}
