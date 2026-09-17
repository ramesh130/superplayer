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

/**
 * superplayer-offline — downloads into the `ContentCache` the consumer opened, on the one chain.
 *
 * [Downloads] is the store and [Downloads.Builder] how one is opened over a cache; [DownloadItem] is what
 * it answers per `contentId`, and [DownloadsListener] how a screen hears about changes. A downloaded item
 * plays through `SuperPlayer.setMediaRequest` on a player built with the same cache, and reaches the
 * network for nothing. ADR-0013 is the decisions this module implements.
 *
 * **What the app declares, and what arrives on its own.** The schedule a pending download waits in is
 * `WorkManager`'s, and asks the app to declare nothing: `androidx.work:work-runtime`'s own manifest merges
 * into the app's the `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE`
 * permissions, App Startup's `InitializationProvider` with `WorkManagerInitializer`, the `SystemJobService` and
 * `SystemForegroundService` it runs work in, and the receivers that reschedule work after a reboot or a force
 * stop. Those entries are WorkManager's, listed here so they surprise nobody reading a merged manifest; an app
 * that configures `WorkManager` itself removes the initializer in its own manifest, as WorkManager's
 * documentation says. The service a download runs in outside a screen, its `<service>` element, and the
 * `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS` permissions it needs are the app's to declare and
 * justify (ADR-0013 rule 3, #246); this module declares none of them.
 *
 * ref: https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration
 */
package com.superplayer.offline
