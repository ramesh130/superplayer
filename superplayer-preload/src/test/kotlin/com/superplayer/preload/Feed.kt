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

package com.superplayer.preload

import android.net.Uri
import com.superplayer.core.MediaRequest
import com.superplayer.testkit.NetworkRequest
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent

/**
 * A feed of [rows] rows, each the same synthetic HLS stream of [segments] segments served from a host
 * of its own, so which row a request was for is its host.
 */
internal class Feed(rows: Int, segments: Int) {
    val hosts: List<String> = (0 until rows).map { "row$it.feed.test" }
    val content: TestContent
    val requests: List<MediaRequest>

    init {
        var served = TestContent.hls(segments)
        val uris = hosts.map { host ->
            served = served.servedFrom(host)
            served.sourceUri
        }
        content = served
        requests = uris.mapIndexed { row, uri -> MediaRequest.Builder(contentId(row)).addSource(uri).build() }
    }

    fun contentId(row: Int): String = "feed:row$row"

    fun resuming(row: Int): MediaRequest = MediaRequest.Builder(contentId(row))
        .addSource(requests[row].sources.first())
        .setStartPosition(MediaRequest.StartPosition.ResumeFromLastKnown)
        .build()

    fun rowOf(request: NetworkRequest): Int? = hosts.indexOf(Uri.parse(request.uri).host).takeIf { it >= 0 }

    /** Each row's first media segment request, in the order they were made. */
    fun firstSegmentOrder(requests: List<NetworkRequest>): List<Int> =
        requests.filter { it.kind == ResourceKind.MEDIA_SEGMENT }.mapNotNull { rowOf(it) }.distinct()

    fun manifestsFor(row: Int, requests: List<NetworkRequest>): Int =
        requests.count { it.kind == ResourceKind.MANIFEST && rowOf(it) == row }
}
