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
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.ContentIdentity
import com.superplayer.core.LoadKind
import com.superplayer.core.RequestStamp
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * A [ContentKeyedCache]'s eviction order, pinned region and budget changes, driven below the player:
 * whole entries of one known size written into and read from the cache directly, so every assertion is
 * about bytes rather than about how a stream happened to be segmented. `ContentKeyedCachePlaybackTest`
 * shows the same rules holding for what a player stores.
 */
@RunWith(AndroidJUnit4::class)
class CacheEvictionTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val directory: File by lazy { folder.newFolder() }
    private val opened = mutableListOf<ContentKeyedCache>()

    @After
    fun releaseCaches() {
        opened.forEach { it.release() }
    }

    @Test
    fun fillingPastTheBudgetEvictsTheLeastRecentlyUsedContent() {
        val cache = open(entries = 3)

        cache.write("a", "b", "c", "d")

        assertThat(cache.contentHeld()).containsExactly("b", "c", "d")
    }

    @Test
    fun contentReadAgainCountsAsUsed() {
        val cache = open(entries = 3)
        cache.write("a", "b", "c")

        cache.read("a")
        cache.write("d")

        assertThat(cache.contentHeld()).containsExactly("a", "c", "d")
    }

    @Test
    fun pinnedContentSurvivesAnEvictionPassThatWouldOtherwiseRemoveIt() {
        val cache = open(entries = 3)
        cache.write("a")
        cache.pin("a")

        cache.write("b", "c", "d", "e")

        assertThat(cache.contentHeld()).containsExactly("a", "d", "e")
        assertThat(cache.heldBytes()).isEqualTo(cache.maxBytes)
    }

    /** Pinned bytes count against the budget, and past it the pins are kept rather than the budget. */
    @Test
    fun pinnedContentBeyondTheBudgetIsKeptWholeAndNothingUnpinnedIsKeptBesideIt() {
        val cache = open(entries = 2)
        cache.write("a", "b")
        cache.pin("a")
        cache.pin("b")
        // Pinned before it is stored, which a download will do.
        cache.pin("c")

        cache.write("c", "d")

        assertThat(cache.contentHeld()).containsExactly("a", "b", "c")
        assertThat(cache.heldBytes()).isEqualTo(3 * ENTRY_BYTES)
    }

    @Test
    fun unpinnedContentIsEvictableAgain() {
        val cache = open(entries = 2)
        cache.write("a")
        cache.pin("a")
        cache.write("b")

        cache.unpin("a")
        cache.write("c")

        assertThat(cache.isPinned("a")).isFalse()
        assertThat(cache.contentHeld()).containsExactly("b", "c")
    }

    @Test
    fun reopeningWithASmallerBudgetEvictsDownToItInOrderOfUseAndKeepsTheRestReadable() {
        open(entries = 4).apply {
            write("a", "b", "c")
            read("a")
            release()
        }

        val smaller = open(entries = 2)
        assertThat(smaller.contentHeld()).containsExactly("c", "a")
        smaller.release()

        val larger = open(entries = 4)
        assertThat(larger.contentHeld()).containsExactly("c", "a")
        larger.read("a")
        larger.read("c")
    }

    @Test
    fun reopeningWithALargerBudgetKeepsEverything() {
        open(entries = 2).apply {
            write("a", "b")
            release()
        }

        val larger = open(entries = 4)
        larger.write("c", "d")

        assertThat(larger.contentHeld()).containsExactly("a", "b", "c", "d")
    }

    @Test
    fun aPinOutlivesItsCacheAndAChangeOfBudget() {
        open(entries = 4).apply {
            write("a", "b", "c")
            pin("b")
            release()
        }

        val smaller = open(entries = 1)

        assertThat(smaller.isPinned("b")).isTrue()
        assertThat(smaller.contentHeld()).containsExactly("b")
    }

    @Test
    fun aReleasedCacheRefusesAPin() {
        val cache = open(entries = 1)
        cache.release()

        assertThrows(IllegalStateException::class.java) { cache.pin("a") }
    }

    private fun open(entries: Int): ContentKeyedCache =
        CachePolicy.contentKeyed(directory, entries * ENTRY_BYTES).also { opened += it }

    /** Stores one whole entry of [ENTRY_BYTES] under each content id, in order. */
    private fun ContentKeyedCache.write(vararg contentIds: String) {
        val cache = storage.cache
        for (contentId in contentIds) {
            val key = keyOf(contentId)
            val hole = cache.startReadWrite(key, 0, ENTRY_BYTES)
            check(!hole.isCached) { "$contentId is already stored" }
            val file = cache.startFile(key, 0, ENTRY_BYTES)
            file.writeBytes(ByteArray(ENTRY_BYTES.toInt()))
            cache.commitFile(file, ENTRY_BYTES)
            cache.releaseHoleSpan(hole)
            tick()
        }
    }

    /** Reads [contentId]'s entry as a request answered from the cache would, which touches it. */
    private fun ContentKeyedCache.read(contentId: String) {
        assertWithMessage("$contentId is stored").that(storage.cache.startReadWrite(keyOf(contentId), 0, ENTRY_BYTES).isCached).isTrue()
        tick()
    }

    private fun ContentKeyedCache.contentHeld(): List<String?> = keys().map(ContentKeys::contentIdOf)

    private fun keyOf(contentId: String): String = ContentKeys.buildCacheKey(
        DataSpec.Builder()
            .setUri(Uri.parse("https://cdn.example/$contentId/seg.m4s"))
            .setCustomData(RequestStamp(ContentIdentity(contentId), LoadKind.MEDIA))
            .build(),
    )

    /**
     * A span's use is stamped in wall-clock milliseconds, which Robolectric does not move; two uses in
     * one millisecond would be ordered by key rather than by time, and pass or fail by the alphabet.
     */
    private fun tick() = Thread.sleep(2)

    private companion object {
        const val ENTRY_BYTES = 1_000L
    }
}
