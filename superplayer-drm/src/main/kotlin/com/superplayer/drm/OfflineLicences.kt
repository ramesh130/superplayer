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

package com.superplayer.drm

import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import com.superplayer.core.SuperPlayer
import java.io.File

/**
 * Where licences for offline playback are kept: a directory the consumer opened and named.
 *
 * The peer of `CachePolicy.contentKeyed`, and here for the same reason ADR-0010 rule 1 put that
 * there — an offline licence is state that outlives the player that acquired it, which is exactly
 * what ADR-0006 rule 2 forbids the library to choose a home for. So the app names the directory, the
 * library opens nothing else, and a consumer who never asks for offline playback has no store at all
 * (ADR-0012 rule 8).
 *
 * ```kotlin
 * val store = OfflineLicences.store(File(context.filesDir, "licences"))
 *
 * // Once, while online: a player that has loaded the content is what composes the request.
 * val player = SuperPlayer.Builder(context).setDrm(Drm.widevine(config)).build()
 * player.setMediaRequest(request)
 * store.over(player).acquire(request.contentId)
 *
 * // Later, with no network: read first, then play with what was read.
 * val licence = store.licenceFor(contentId)
 * when {
 *     licence == null -> promptToDownload()
 *     licence.isExpired -> promptToGoOnlineAndRefresh()   // PRD.md §3.2's specific message
 *     else -> SuperPlayer.Builder(context).setDrm(Drm.widevine(config, licence)).build()
 * }
 * ```
 */
public object OfflineLicences {

    /**
     * Opens the store in [directory], creating it if it is not there.
     *
     * Everything the store writes lands inside [directory] and nothing outside it, so deleting the
     * directory — or uninstalling the app — takes the licences with it and leaves nothing behind
     * (ADR-0012 rule 8, taking ADR-0010 rule 2's requirement again). One store per directory: the
     * index is an ordinary database file, and two stores over one directory are two writers of it.
     *
     * The keys themselves are never here. What is stored is the *key-set id* the device answered
     * with, which is a handle into the device's own protected storage and is useless on any other
     * device — so a directory that leaks leaks no entitlement.
     */
    @JvmStatic
    public fun store(directory: File): OfflineLicenceStore = OfflineLicenceStore(OfflineLicenceIndex(directory))
}

/**
 * What [OfflineLicences.store] opened: the licences this device holds, by `contentId`.
 *
 * A public type with an internal constructor, as `ContentKeyedCache` is, so that a consumer can name
 * one without any of the Media3 machinery behind it appearing in a signature — nothing on this type,
 * or on [OfflineLicence] or [OfflineLicenceExchange], names a Media3 class (ADR-0001 rule 2).
 *
 * **Reading is free and writing is not.** [licenceFor] and [contentIds] answer from the index alone:
 * no device, no player, no network, which is what makes ADR-0012 rule 9's "before playback rather
 * than after it fails" reachable from a list screen. The four verbs that change something are
 * [OfflineLicenceExchange]'s, because each of them is a round trip to the licence server and that
 * server is reached over a *player's* transport (ADR-0012 rule 2).
 */
public class OfflineLicenceStore internal constructor(private val index: OfflineLicenceIndex) {

    /**
     * The licence this store holds for [contentId], or null where it holds none.
     *
     * Both expiries, as ADR-0012 rule 9 requires, and both counted from what the device reported when
     * the licence was acquired or last renewed. A consumer reads this *before* building a player:
     * that is the difference between `PRD.md` §3.2's "go online and refresh this download", which a
     * viewer can act on, and "playback error", which they cannot.
     */
    public fun licenceFor(contentId: String): OfflineLicence? = index.read(contentId)

    /** Every content id this store holds a licence for, expired ones included. */
    public fun contentIds(): Set<String> = index.contentIds()

    /**
     * The store's four verbs, performed over [player]'s protection.
     *
     * Over a player rather than on its own, and that is a decision rather than an inconvenience: a
     * licence exchange travels the chain ADR-0012 rule 2 gave it — the app's credential through the
     * one `HeaderProvider`, the licence budget of `RetryPolicy.licence`, the `LoadKind.LICENCE` stamp
     * — and all three of those are properties of a player. A store that opened an HTTP stack of its
     * own would be a second way to reach the same licence server, answering to none of them.
     *
     * [player] must have been built with [Drm.widevine]; an [acquire][OfflineLicenceExchange.acquire]
     * also needs it to have loaded the content, since only the content declares the protection data a
     * licence is for.
     */
    public fun over(player: SuperPlayer): OfflineLicenceExchange {
        val protection = player.licenceSessions as? PlayerProtection
        checkNotNull(protection) {
            "This player has no Widevine protection: build it with setDrm(Drm.widevine(...)) first"
        }
        return OfflineLicenceExchange(index, protection)
    }

    /** Closes the index. The directory is the consumer's and is not touched. */
    public fun close() {
        index.close()
    }
}

/**
 * One licence this device holds, and how long it has left — the two answers ADR-0012 rule 9 requires
 * to be readable before playback.
 *
 * **The two durations are two facts and not one**, which is why they are two fields. A Widevine
 * licence carries a *licence* duration — how long the entitlement itself lives — and a *playback*
 * duration, the viewing window that begins when the download is first played; a rental that has been
 * started and a rental that has expired unwatched are different things to tell a viewer, and a client
 * that reported only the smaller could not tell them apart.
 *
 * ref: `MediaDrm.queryKeyStatus` reports them as `LicenseDurationRemaining` and
 * `PlaybackDurationRemaining`, which Media3 reads in `WidevineUtil.getLicenseDurationRemainingSec`.
 *
 * Read when the licence was acquired or last renewed and counted down from there, so this is
 * answerable with no device and no network — which is the whole point of its being readable before
 * playback.
 */
public class OfflineLicence internal constructor(

    /** The content this licence is for: a [com.superplayer.core.MediaRequest]'s `contentId`. */
    public val contentId: String,

    /** Milliseconds of viewing window left, or zero where it has run out. */
    public val playbackDurationRemainingMs: Long,

    /** Milliseconds of entitlement left, or zero where it has run out. */
    public val licenceDurationRemainingMs: Long,

    /**
     * The device's handle on the keys, which is what is actually persisted.
     *
     * Internal because it is nothing a consumer can use: it names keys in the device's own protected
     * storage, is meaningless on any other device, and the only thing anyone may do with it is hand
     * it back to the device this licence was acquired on.
     */
    internal val keySetId: ByteArray,
) {

    /**
     * Whether either duration has run out, so that playing this would fail rather than play.
     *
     * Either and not both: a viewing window that has closed stops playback exactly as a dead
     * entitlement does, and a consumer asking "can I play this now" wants one answer. Which of the
     * two ran out is still on the two fields above, for a message that says why.
     */
    public val isExpired: Boolean
        get() = playbackDurationRemainingMs <= 0L || licenceDurationRemainingMs <= 0L

    /**
     * Whether this licence should be renewed now — `PRD.md` §3.2's "before the remaining duration
     * reaches zero", as a reading rather than as a timer.
     *
     * **The library schedules nothing** (ADR-0012 rule 10): when to run background work is a
     * `WorkManager` configuration that would be chosen on the consumer's behalf in the same way
     * ADR-0006 rule 2 forbids choosing storage. So the store reports what is due and the consumer's
     * own job calls [OfflineLicenceExchange.renew].
     *
     * The threshold is [RENEWAL_DUE_WITHIN_MS] and its reason is the consumer's schedule rather than
     * the licence's: a job that runs once a day is the coarsest thing an app plausibly uses for this,
     * and a threshold shorter than the gap between two of its runs would let a licence die between
     * them. It is also far above the sixty seconds at which Media3 re-requests a restored licence
     * during playback, so a licence this calls due still has time to be renewed deliberately, online,
     * instead of becoming a network round trip in the middle of an offline view.
     */
    public val renewalDue: Boolean
        get() = licenceDurationRemainingMs <= RENEWAL_DUE_WITHIN_MS

    /** Nothing about the keys, for the reason `docs/telemetry-schema.md` redacts a licence load. */
    override fun toString(): String =
        "OfflineLicence($contentId, playbackLeftMs=$playbackDurationRemainingMs, licenceLeftMs=$licenceDurationRemainingMs)"

    public companion object {

        /** Twenty-four hours. [renewalDue] carries the argument. */
        public const val RENEWAL_DUE_WITHIN_MS: Long = 24L * 60 * 60 * 1000
    }
}

/**
 * The four verbs of `PRD.md` Part 4's offline lifecycle, over one player's protection: acquire, play
 * offline, renew, release.
 *
 * Three of them are here; the fourth is playing, and it is `Drm.widevine(config, licence)` rather
 * than a call, because playing from a stored licence is a property of the session graph a player is
 * built with.
 *
 * **Every one of these blocks on a licence round trip**, as Media3's own `OfflineLicenseHelper` does,
 * and none of them may be called on the main thread of an app. They are also not playback: each opens
 * a session graph of its own so that the sessions a player is holding are untouched.
 */
public class OfflineLicenceExchange internal constructor(
    private val index: OfflineLicenceIndex,
    private val protection: PlayerProtection,
) {

    /**
     * Downloads a licence for the content this player has loaded and persists it under [contentId].
     *
     * The protection data comes from the format the player's engine actually asked for a session over
     * — only the content declares which keys it is encrypted under, and nothing the store could have
     * been told stands in for it. So the player must have loaded the content: prepare it, or play it,
     * before calling this.
     *
     * ref: Media3's `OfflineLicenseHelper.downloadLicense(Format)` performs the exchange —
     * `DefaultDrmSessionManager` in `MODE_DOWNLOAD`, which asks the device for a *persistable*
     * licence (`MediaDrm.KEY_TYPE_OFFLINE`) rather than a streaming one, and answers with the
     * key-set id.
     */
    public fun acquire(contentId: String): OfflineLicence {
        val format = protection.protectedFormat
        checkNotNull(format) {
            "This player has asked for no protected session yet: set the content and prepare it before acquiring"
        }
        return exchange { helper -> persist(contentId, helper.downloadLicense(format), helper) }
    }

    /**
     * Asks the licence server for a fresh licence for [contentId] and replaces the stored one.
     *
     * No content is loaded for this and none is needed: a renewal is composed from the key-set id
     * alone, which is why it is a background job's operation rather than a player's.
     *
     * ref: `OfflineLicenseHelper.renewLicense(byte[])`.
     */
    public fun renew(contentId: String): OfflineLicence {
        val stored = held(contentId)
        return exchange { helper -> persist(contentId, helper.renewLicense(stored.keySetId), helper) }
    }

    /**
     * Releases [contentId]'s licence — at the server, then here — so a deleted download leaves no
     * entitlement behind.
     *
     * The server is told first and the index cleared second, deliberately: a release the server never
     * heard about leaves a licence counted against the viewer's device allowance, which is the defect
     * this verb exists to prevent, while an index that still names keys the device has dropped is
     * repaired by the next read finding nothing. A refusal therefore propagates rather than being
     * swallowed, and the entry stays for the consumer to try again.
     *
     * ref: `OfflineLicenseHelper.releaseLicense(byte[])`, which is `MODE_RELEASE` — a round trip whose
     * whole purpose is to tell the server the licence is done with.
     */
    public fun release(contentId: String) {
        val stored = held(contentId)
        exchange { helper -> helper.releaseLicense(stored.keySetId) }
        index.remove(contentId)
    }

    private fun held(contentId: String): OfflineLicence =
        checkNotNull(index.read(contentId)) { "This store holds no licence for $contentId" }

    /**
     * Runs [work] against a helper over a session graph of this player's, and releases it afterwards
     * however it ends.
     *
     * The helper owns a thread of its own while it exists (Media3 gives it a `HandlerThread`), so one
     * per verb rather than one per exchange: a store that kept one alive between calls would hold a
     * `MediaDrm` and a thread for as long as the app did.
     */
    private fun <T> exchange(work: (OfflineLicenseHelper) -> T): T {
        val helper = OfflineLicenseHelper(protection.exchangeSessions(), DrmSessionEventListener.EventDispatcher())
        return try {
            work(helper)
        } finally {
            helper.release()
        }
    }

    /**
     * Writes what the device answered with, expiries and all, and returns what it wrote.
     *
     * The durations are read straight after the exchange and stored as deadlines, so that every later
     * reading is arithmetic rather than another round trip through the device — which is what lets
     * [OfflineLicenceStore.licenceFor] answer on a list screen with nothing open.
     */
    private fun persist(contentId: String, keySetId: ByteArray, helper: OfflineLicenseHelper): OfflineLicence {
        // ref: `OfflineLicenseHelper.getLicenseDurationRemainingSec` answers the pair Widevine
        // reports — the licence duration first, the playback duration second — which is the split
        // ADR-0012 rule 9 requires be kept apart rather than reduced to their minimum.
        val remaining = helper.getLicenseDurationRemainingSec(keySetId)
        return index.write(
            contentId,
            keySetId,
            licenceSecondsLeft = remaining.first,
            playbackSecondsLeft = remaining.second,
        )
    }
}
