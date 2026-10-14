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

package com.superplayer.cache

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.ContentIdentity
import com.superplayer.core.LoadKind
import com.superplayer.core.RequestStamp
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keying rule on its own, request by request. The playback tests show the rule doing its job
 * through a player; these pin the cases a synthetic stream has no way to produce — a rendition in a
 * second path, a signed query, an id that looks like a path.
 */
@RunWith(AndroidJUnit4::class)
class ContentKeysTest {

    @Test
    fun theSameRenditionFromTwoHostsWithDifferentSignaturesIsOneKey() {
        assertThat(keyOf("episode:1", "https://cdn-a.example/vod/720p/seg3.m4s?token=abc"))
            .isEqualTo(keyOf("episode:1", "https://cdn-b.example/vod/720p/seg3.m4s?token=xyz"))
    }

    @Test
    fun aDifferentRenditionOrSegmentIsADifferentKey() {
        val segment = keyOf("episode:1", "https://cdn.example/vod/720p/seg3.m4s")
        assertThat(keyOf("episode:1", "https://cdn.example/vod/1080p/seg3.m4s")).isNotEqualTo(segment)
        assertThat(keyOf("episode:1", "https://cdn.example/vod/720p/seg4.m4s")).isNotEqualTo(segment)
    }

    @Test
    fun aDifferentContentIdAtAnIdenticalUrlIsADifferentKey() {
        val url = "https://cdn.example/vod/720p/seg3.m4s"
        assertThat(keyOf("episode:1", url)).isNotEqualTo(keyOf("episode:2", url))
    }

    @Test
    fun anIdAndPathCannotBeReadAsAnotherIdAndPath() {
        assertThat(keyOf("a/b", "https://cdn.example/c")).isNotEqualTo(keyOf("a", "https://cdn.example/b/c"))
    }

    @Test
    fun aDashRepresentationsOwnKeyDoesNotBringTheHostBack() {
        val fromA = DataSpec.Builder().setUri("https://cdn-a.example/v/seg.m4s").setKey("https://cdn-a.example/v/seg.m4s_1")
            .setCustomData(RequestStamp(ContentIdentity("episode:1"), LoadKind.MEDIA)).build()
        val fromB = DataSpec.Builder().setUri("https://cdn-b.example/v/seg.m4s").setKey("https://cdn-b.example/v/seg.m4s_1")
            .setCustomData(RequestStamp(ContentIdentity("episode:1"), LoadKind.MEDIA)).build()

        assertThat(ContentKeys.buildCacheKey(fromA)).isEqualTo(ContentKeys.buildCacheKey(fromB))
    }

    @Test
    fun contentWithNoIdentityIsKeyedByItsUrlAndNeverAsARequest() {
        val url = "https://cdn.example/vod/720p/seg3.m4s"
        val unidentified = ContentKeys.buildCacheKey(
            DataSpec.Builder().setUri(url).setCustomData(RequestStamp(null, LoadKind.MEDIA)).build(),
        )

        assertThat(unidentified).isEqualTo("url:$url")
        assertThat(unidentified).isNotEqualTo(keyOf("episode:1", url))
        // An id chosen to look like the URL family still lands in the content family.
        assertThat(keyOf("url:", url)).startsWith("content:")
        assertThat(
            ContentKeys.buildCacheKey(DataSpec.Builder().setUri("https://cdn-b.example/vod/720p/seg3.m4s").build()),
        ).isNotEqualTo(unidentified)
    }

    /** How a pin finds its entries: the id is read back whole, however much of it looks like a key. */
    @Test
    fun aKeyNamesTheContentItWasBuiltFromAndAUrlKeyNamesNone() {
        for (id in listOf("episode:1", "12:34", "a/b", "", "url:x")) {
            assertThat(ContentKeys.contentIdOf(keyOf(id, "https://cdn.example/vod/720p/seg3.m4s"))).isEqualTo(id)
        }
        assertThat(ContentKeys.contentIdOf("url:https://cdn.example/vod/720p/seg3.m4s")).isNull()
        assertThat(ContentKeys.contentIdOf("content:99:short")).isNull()
        assertThat(ContentKeys.contentIdOf("content:no-length")).isNull()
        assertThat(ContentKeys.contentIdOf("content:-3:x")).isNull()
    }

    private fun keyOf(contentId: String, uri: String): String = ContentKeys.buildCacheKey(
        DataSpec.Builder().setUri(Uri.parse(uri)).setCustomData(RequestStamp(ContentIdentity(contentId), LoadKind.MEDIA)).build(),
    )
}
