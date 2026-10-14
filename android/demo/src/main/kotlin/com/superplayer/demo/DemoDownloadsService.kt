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

package com.superplayer.demo

import com.superplayer.offline.Downloads
import com.superplayer.offline.DownloadsService

/**
 * The demo's download service: what keeps a download going once the viewer has left [DownloadsScreen].
 *
 * The whole of what a consuming app writes for it. [onDownloads] answers the process's store — opened here
 * when the scheduled work starts this service in a process that has no screen, after a reboot say, which is
 * why the store is opened by [DemoDownloads] and not by the screen — and the channel name and icon are the
 * app's words. Nothing here starts downloading, builds a notification, calls `startForeground`, or decides when
 * to stop: the store starts the service whenever a download can run, and the service stops once none can.
 *
 * The manifest entry and the `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS` permissions are in the
 * demo's own manifest rather than the library's, which is ADR-0013 rule 3.
 */
class DemoDownloadsService : DownloadsService() {

    override fun onDownloads(): Downloads = DemoDownloads.open(this).store

    override val notificationChannelName: Int = R.string.downloads_channel_name

    // The platform's own download glyph: the demo has no drawables of its own, and this is the icon a viewer
    // already reads as "downloading".
    override val notificationSmallIcon: Int = android.R.drawable.stat_sys_download
}
