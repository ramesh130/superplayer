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

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.superplayer.core.DeliveredProtection
import com.superplayer.core.DownloadDrmExtension
import com.superplayer.core.DownloadEnvironment
import com.superplayer.core.HeaderRefreshLayer
import com.superplayer.core.HttpStack
import com.superplayer.core.LicenceContext
import com.superplayer.core.LicenceStanding
import com.superplayer.core.LicenceStore
import com.superplayer.core.TransferChain
import com.superplayer.core.deviceConstraintsOf
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A store's offline licences: acquired for a protected download after its manifest and before its first
 * media byte, and released at the server once the download is removed (ADR-0013 rule 13).
 *
 * The round trips run on one thread of their own, one at a time, because each blocks for as long as the
 * licence server takes to answer — Media3's `OfflineLicenseHelper` waits on its own — and a store is used
 * from the main thread. The thread exists only once a protected download has needed it, so a store built
 * with protection that downloads nothing starts nothing (rule 15). Every callback is made on that thread.
 */
internal class DownloadLicences(
    private val context: Context,
    private val drm: DownloadDrmExtension,
    store: LicenceStore,
    private val environment: DownloadEnvironment?,
    private val headerRefresh: HeaderRefreshLayer?,
    private val loadErrors: LoadErrorHandlingPolicy?,
    private val httpStack: HttpStack?,
) {

    private val store = store.licences

    // One chain for every exchange, as a download has one chain for its bytes (rule 6), composed when first asked,
    // with the store's header-refresh layer under the stamp where its resilience has a provider (rule 14) and over
    // the store's own HTTP stack, which is the same one its segments travel (ADR-0016 rule 13).
    private val transport by lazy { TransferChain.downloadLicenceChain(context, environment, headerRefresh, httpStack) }

    // Read once, when first asked: a store that downloads nothing protected walks no codec list.
    private val device by lazy { deviceConstraintsOf() }

    private var executor: ExecutorService? = null

    /**
     * A session graph of the store's protection, for a download's manifest read to be given — so that the
     * device's own renderers see protected formats as ones it can decrypt — or for one exchange. Throws the
     * refusal [DownloadDrmExtension.downloadSessions] names.
     */
    fun sessions(): DefaultDrmSessionManager = drm.downloadSessions(
        LicenceContext(
            transport = transport,
            mediaDrm = environment?.mediaDrm,
            // The store's `RetryPolicy.licence` where it has a resilience, as a player's DRM slot is handed the player's
            // own policy (#205, #260); Media3's own handling for a licence load where it has none.
            loadErrors = loadErrors,
            device = device,
            delivered = DeliveredProtection(),
        ),
    )

    /**
     * The first protected format [helper] has selected, whose protection data a licence is requested for, or
     * null where the download selected nothing protected. Only the content declares which keys it is
     * encrypted under, so this is read from the manifest rather than from anything the store was told.
     */
    fun protectedFormatOf(helper: DownloadHelper): Format? {
        for (period in 0 until helper.periodCount) {
            for (renderer in 0 until helper.getMappedTrackInfo(period).rendererCount) {
                for (selection in helper.getTrackSelections(period, renderer)) {
                    for (index in 0 until selection.length()) {
                        selection.getFormat(index).takeIf { it.drmInitData != null }?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** The licence held for [contentId] and not waiting to be released, read from the licence store. */
    fun standingOf(contentId: String): LicenceStanding? = store.standingOf(contentId)

    /**
     * Acquires a licence for [format] and stores it as [contentId]'s, then calls [done] with what the
     * acquisition failed with or null, and with whether the licence it stored replaced one now owed a release.
     *
     * ref: `OfflineLicenseHelper.downloadLicense` asks the device for a persistable licence
     * (`MODE_DOWNLOAD`) and answers its key-set id; `getLicenseDurationRemainingSec` answers the licence
     * duration first and the playback duration second, which the store keeps apart (ADR-0012 rule 9).
     */
    fun acquire(contentId: String, format: Format, done: (failure: Throwable?, replaced: Boolean) -> Unit) {
        onLicenceThread {
            try {
                val replaced = exchange { helper ->
                    val keySetId = helper.downloadLicense(format)
                    val remaining = helper.getLicenseDurationRemainingSec(keySetId)
                    store.write(contentId, keySetId, licenceSecondsLeft = remaining.first, playbackSecondsLeft = remaining.second)
                }
                done(null, replaced)
            } catch (e: Exception) {
                done(e, false)
            }
        }
    }

    /**
     * Marks [contentId]'s licence as owed a release, so no player is given it from now on, and answers
     * whether there was one. On the calling thread, because a removal must not leave a moment in which the
     * download is gone and its licence still playable.
     */
    fun owe(contentId: String): Boolean = store.awaitRelease(contentId)

    /** Whether any licence is owed a release, read from the licence store on the calling thread. */
    fun owesAny(): Boolean = store.awaitingRelease().isNotEmpty()

    /**
     * Releases every licence owed a release, at the server and then in the store, and calls [done] with
     * whether any is still owed. One that fails stays owed for a later pass: a release is a round trip, and
     * one the server never heard about leaves the licence counted against the viewer's device.
     *
     * ref: `OfflineLicenseHelper.releaseLicense` is `MODE_RELEASE`, the round trip whose whole purpose is to
     * tell the server the licence is done with.
     */
    fun releaseOwed(done: (stillOwed: Boolean) -> Unit) {
        onLicenceThread {
            var stillOwed = false
            store.awaitingRelease().forEach { keySetId ->
                try {
                    exchange { helper -> helper.releaseLicense(keySetId) }
                    store.released(keySetId)
                } catch (_: Exception) {
                    stillOwed = true
                }
            }
            done(stillOwed)
        }
    }

    /** Lets the thread go once what it is doing is done. Idempotent. */
    fun shutDown() {
        executor?.shutdown()
        shutDown = true
    }

    private var shutDown = false

    /** Runs [work] on the licence thread, starting it if this is its first; nothing, once shut down. */
    private fun onLicenceThread(work: () -> Unit) {
        if (shutDown) return
        val running = executor ?: Executors.newSingleThreadExecutor { Thread(it, "SuperPlayer download licences").apply { isDaemon = true } }
        executor = running
        running.execute(work)
    }

    /** Runs [work] against a helper over a session graph of its own, released however it ends. */
    private fun <T> exchange(work: (OfflineLicenseHelper) -> T): T {
        val helper = OfflineLicenseHelper(sessions(), DrmSessionEventListener.EventDispatcher())
        return try {
            work(helper)
        } finally {
            helper.release()
        }
    }
}
