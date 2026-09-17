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

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.util.NotificationUtil
import androidx.media3.exoplayer.offline.DownloadNotificationHelper

/**
 * The service a download outlives its screen in: while any download of the store can run, the process is held
 * in the foreground with a notification, and once none can the service stops (ADR-0013 rule 3).
 *
 * An app subclasses it, names the subclass on the store with `Downloads.Builder.setService`, and overrides
 * [onDownloads] — the process's store, because a service the system starts in a fresh process has no other way
 * to find storage the app opened — and the notification's channel name and icon, because those are the app's
 * words. The store starts the service whenever a download can run, and the scheduled work starts it in a process
 * with no store open, so the app starts it nowhere itself.
 *
 * **What the app declares.** A `<service>` element for the subclass, with `android:foregroundServiceType="dataSync"`
 * and `android:exported="false"`, and the `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` and
 * `POST_NOTIFICATIONS` permissions — the last a runtime permission from API 33, which the app asks for. None of
 * them is in this module's manifest: a foreground service type is a store-policy commitment, and a library does
 * not make one on every consumer's behalf (ADR-0013 rule 3, on ADR-0007 rule 4's argument).
 *
 * **What it does not do.** It downloads nothing: the store runs its downloads whether or not a service holds it,
 * and the service only keeps the process alive around them. It is not sticky, since the scheduled work is what
 * restarts a download whose process died (rule 11), and a sticky restart would be a foreground-service start from
 * the background the platform refuses. And it is not bound: [onBind] answers null, as nothing needs to reach it.
 *
 * A download *held* by a condition — a metered network, a low battery — is not one that can run, so the service
 * stops rather than holding a foreground notification over a download that is waiting; the schedule starts it again
 * once the conditions hold. A `dataSync` foreground service has a daily time budget from API 35, which is a second
 * reason not to spend it waiting.
 *
 * ref: https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync
 */
public abstract class DownloadsService : Service() {

    /**
     * The process's download store, opened over the cache the app opened, on the main thread. Called once, on the
     * service's first start; a fresh process opens the store here, so this is where the app's own lazily opened
     * store belongs rather than in a screen.
     */
    protected abstract fun onDownloads(): Downloads

    /** The string resource naming the notification channel, as the system's notification settings show it. */
    protected abstract val notificationChannelName: Int

    /** The drawable resource of the notification's small icon. */
    protected abstract val notificationSmallIcon: Int

    /** The notification channel's id, created by the service where it does not already exist. */
    protected open val notificationChannelId: String = DEFAULT_NOTIFICATION_CHANNEL_ID

    /** The foreground notification's id, which must not collide with one the app posts itself. */
    protected open val notificationId: Int = DEFAULT_NOTIFICATION_ID

    /**
     * The foreground notification for [downloads], every download the store holds. The default is Media3's
     * progress notification — the overall percentage, and what holds the queue where something does — under
     * [notificationSmallIcon] on [notificationChannelId]. Called on the main thread, at most once a
     * [NOTIFICATION_UPDATE_INTERVAL_MS].
     */
    protected open fun onNotification(downloads: List<DownloadItem>): Notification {
        val store = checkNotNull(store)
        return DownloadNotificationHelper(this, notificationChannelId)
            .buildProgressNotification(this, notificationSmallIcon, null, null, store.currentMediaDownloads(), store.notMetRequirements())
    }

    private val handler = Handler(Looper.getMainLooper())

    private var store: Downloads? = null

    private var updateScheduled = false

    private val onRunningChanged: (Boolean) -> Unit = { running -> if (!running) stop() }

    private val onDownloadsChanged = object : DownloadsListener {
        override fun onDownloadChanged(item: DownloadItem) = scheduleUpdate()

        override fun onDownloadRemoved(contentId: String) = scheduleUpdate()
    }

    private val update = Runnable {
        updateScheduled = false
        val store = store ?: return@Runnable
        if (store.isRunning()) getSystemService(NotificationManager::class.java).notify(notificationId, notification(store))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = store ?: onDownloads().also(::attach)
        // Every start announces itself, including one with nothing to run: the store and the scheduled work start
        // this as a foreground service, and the platform ends an app whose foreground start never announced one.
        NotificationUtil.createNotificationChannel(this, notificationChannelId, notificationChannelName, 0, NotificationUtil.IMPORTANCE_LOW)
        val notification = notification(store)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notificationId, notification)
        }
        if (!store.isRunning()) stop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        store?.let { detach(it) }
        super.onDestroy()
    }

    private fun attach(store: Downloads) {
        this.store = store
        store.addRunningObserver(onRunningChanged)
        store.addListener(onDownloadsChanged)
    }

    private fun detach(store: Downloads) {
        store.removeRunningObserver(onRunningChanged)
        store.removeListener(onDownloadsChanged)
        handler.removeCallbacks(update)
        this.store = null
    }

    private fun notification(store: Downloads): Notification = onNotification(store.downloads())

    /** Coalesces the store's changes, which arrive once per whole percent per download, into one update an interval. */
    private fun scheduleUpdate() {
        if (updateScheduled) return
        updateScheduled = true
        handler.postDelayed(update, NOTIFICATION_UPDATE_INTERVAL_MS)
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    public companion object {

        /** The channel id a subclass that does not choose one posts on. */
        public const val DEFAULT_NOTIFICATION_CHANNEL_ID: String = "com.superplayer.offline.downloads"

        /** The notification id a subclass that does not choose one posts under: arbitrary, and overridable for that reason. */
        public const val DEFAULT_NOTIFICATION_ID: Int = 0x5350

        /**
         * How often the notification is rebuilt while downloads progress: a second, Media3's own `DownloadService`
         * cadence, and well under the rate at which the platform starts dropping a package's notification updates.
         */
        public const val NOTIFICATION_UPDATE_INTERVAL_MS: Long = 1_000L
    }
}
