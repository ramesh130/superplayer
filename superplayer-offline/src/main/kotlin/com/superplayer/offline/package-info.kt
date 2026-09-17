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
 */
package com.superplayer.offline
