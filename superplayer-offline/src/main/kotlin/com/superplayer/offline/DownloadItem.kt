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

package com.superplayer.offline

import com.superplayer.core.SuperPlayerError

/**
 * One download as a store answers it: the content it is for, where it has got to, and how much of it
 * is on disk. A value — a store hands out a new one on every change rather than updating one in place —
 * so two readings can be compared to see whether anything moved.
 */
public class DownloadItem internal constructor(
    /** The `MediaRequest.contentId` the download was enqueued under, and the id a player plays it by. */
    public val contentId: String,
    /** Where the download has got to. */
    public val state: DownloadState,
    /** Bytes of the content written to the cache so far. */
    public val bytesDownloaded: Long,
    /**
     * How much of the content is on disk, from 0 to 100, or null while that is not yet known — before
     * the manifest has said how much there is to fetch. For segmented content with no declared lengths
     * it is the share of segments fetched rather than of bytes, which is Media3's own measure.
     */
    public val percentDownloaded: Float?,
    /** What holds a [DownloadState.STOPPED] item, and null in every other state. */
    public val stopReason: DownloadStopReason? = null,
    /**
     * What a [DownloadState.FAILED] item failed with, as `ErrorClassifier` names it, and null in every other
     * state. Null on a failed item too where the store was built without a resilience, which has nobody to
     * ask (ADR-0013 rule 14), where the manifest could not be read, and where the failure happened in an
     * earlier process, whose exception did not survive it.
     */
    public val failure: SuperPlayerError? = null,
    /**
     * The offline licence a protected download holds, read from the licence store the store was built with,
     * and null for content that declares no protection and on a store built without one (ADR-0013 rule 13).
     */
    public val licence: DownloadLicence? = null,
) {

    override fun equals(other: Any?): Boolean = other is DownloadItem &&
        contentId == other.contentId &&
        state == other.state &&
        bytesDownloaded == other.bytesDownloaded &&
        percentDownloaded == other.percentDownloaded &&
        stopReason == other.stopReason &&
        failure == other.failure &&
        licence == other.licence

    override fun hashCode(): Int = listOf(contentId, state, bytesDownloaded, percentDownloaded, stopReason, failure, licence).hashCode()

    override fun toString(): String = "DownloadItem($contentId, $state, $bytesDownloaded bytes, $percentDownloaded%" +
        (stopReason?.let { ", stopped: $it" } ?: "") + (failure?.let { ", failed: ${it.causeClass}" } ?: "") +
        (licence?.let { ", licence: $it" } ?: "") + ")"
}

/**
 * What a protected download's licence allows, readable before any player is built — the difference between
 * `PRD.md` §3.2's "go online and refresh this download", which a viewer can act on, and "playback error",
 * which they cannot (ADR-0012 rule 9).
 *
 * How long each of the licence's two durations has left is the licence store's to answer, per content id:
 * this carries what follows from them, which is what a list of downloads shows.
 */
public class DownloadLicence internal constructor(
    /**
     * Whether either duration has run out, so a player of the download would fail rather than play. A
     * completed download with an expired licence is still completed: its bytes are all there.
     */
    public val isExpired: Boolean,
    /**
     * Whether the licence should be renewed now, on the licence store's threshold. Reported and never acted
     * on: nothing renews a licence on its own, since when to run background work is the consumer's
     * (ADR-0012 rule 10), and the renewal is the licence store's verb.
     */
    public val renewalDue: Boolean,
    /**
     * An expired licence as the typed error playing it would end with, `Drm.LicenceExpired` with its own
     * message key, so the words a viewer is shown can be chosen before they press play. Null for a licence
     * that has not expired, and where the store was built without a resilience, which has nobody to ask
     * (ADR-0013 rule 14).
     */
    public val expiry: SuperPlayerError?,
) {

    override fun equals(other: Any?): Boolean = other is DownloadLicence &&
        isExpired == other.isExpired &&
        renewalDue == other.renewalDue &&
        expiry == other.expiry

    override fun hashCode(): Int = listOf(isExpired, renewalDue, expiry).hashCode()

    override fun toString(): String = if (isExpired) {
        "expired"
    } else if (renewalDue) {
        "renewal due"
    } else {
        "valid"
    }
}

/**
 * What holds a stopped download. Where several hold at once the item names one: a condition before a lost
 * network, since a download the network came back to would still wait for it, and among the conditions the
 * first in the order declared here.
 */
public enum class DownloadStopReason {

    /**
     * The network went away while it downloaded: every request failed to reach its origin. It keeps what it
     * has, tries again on a widening, jittered wait, and resumes from the bytes it holds once a request gets
     * through (ADR-0013 rule 9). Only a store built with a resilience tells a lost network from a failure.
     */
    NETWORK_LOST,

    /**
     * No unmetered network is connected, and the store accepts no other ([Downloads.meteredNetworksAllowed]).
     * Unmetered is the platform's reading, not Wi-Fi: an unlimited Ethernet link counts and a phone's hotspot
     * does not (ADR-0013 rule 10).
     */
    NO_UNMETERED_NETWORK,

    /** No network is connected, on a store that accepts a metered one. */
    NO_NETWORK,

    /** The network is metered and Data Saver restricts this app's background data, on a store that accepts a metered network. */
    DATA_SAVER,

    /** The battery is low. Not a consumer's to relax (rule 10). */
    BATTERY_LOW,

    /** The device's storage is low. Not a consumer's to relax (rule 10). */
    STORAGE_LOW,
}

/**
 * Where a download has got to.
 *
 * What holds a [STOPPED] item and why a [FAILED] one failed are carried beside the state, as
 * [DownloadItem.stopReason] and [DownloadItem.failure].
 */
public enum class DownloadState {

    /** Enqueued and waiting its turn. One waiting for a condition it downloads under is [STOPPED] instead, naming it. */
    QUEUED,

    /** Fetching now. */
    DOWNLOADING,

    /** Held, keeping what it has, until what holds it lets go: its [DownloadItem.stopReason]. */
    STOPPED,

    /** Everything is on disk, and a player built over the cache plays it with no network. */
    COMPLETED,

    /** Ended without completing. */
    FAILED,

    /** Being removed: its bytes are being deleted, after which the store forgets it. */
    REMOVING,
}

/**
 * Told about downloads as they change, on the thread the store was built on.
 */
public interface DownloadsListener {

    /**
     * [item] changed: its state, or its progress by at least a whole percent. A download is first reported
     * [DownloadState.DOWNLOADING] with its first new bytes, whose progress counts what the cache already held,
     * so a download that is started and stopped again before any arrive is never reported downloading. Consecutive calls for one
     * content id never carry an equal item, and a download's progress is reported in the order it was
     * made.
     */
    public fun onDownloadChanged(item: DownloadItem)

    /** The download of [contentId] was removed: its bytes are deleted and its pin is gone. */
    public fun onDownloadRemoved(contentId: String)
}
