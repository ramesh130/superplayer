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

import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * The files behind one cache, every one of them inside the consumer's [directory] (ADR-0010 rule 2).
 *
 * Media goes into a subdirectory rather than [directory] itself, because `SimpleCache` treats its
 * folder as its own: at initialization it reads every file there as a span of its own naming, and one
 * it does not recognise is not something to share a folder with. The index sits beside that
 * subdirectory, still inside [directory].
 */
internal class CacheStorage(directory: File, maxBytes: Long) {

    /** The index database, which Media3's cache index, the pins and a download store's index share. */
    val index = DirectoryDatabaseProvider(File(directory, INDEX_FILE_NAME))

    private val pinned = PinnedContent(index)

    /** The folder the cache writes media into, whose volume a download's writes are measured against. */
    val mediaDirectory = File(directory, MEDIA_DIRECTORY_NAME)

    /** Media3's cache, evicting the least recently used unpinned entries past [maxBytes]. */
    val cache: SimpleCache = SimpleCache(mediaDirectory, PinAwareLruEvictor(maxBytes, ::isPinnedKey), index)

    private var released = false

    @Synchronized
    fun pin(contentId: String) {
        checkOpen()
        // Under the cache's own lock, which every eviction pass holds, so no pass that began before the
        // pin can still remove the content once `pin` has returned. The order — cache, then pins, then
        // the database — is the evictor's own.
        synchronized(cache) { pinned.add(contentId) }
    }

    @Synchronized
    fun unpin(contentId: String) {
        checkOpen()
        pinned.remove(contentId)
    }

    @Synchronized
    fun isPinned(contentId: String): Boolean {
        checkOpen()
        return contentId in pinned
    }

    // Not synchronized: the evictor asks under the cache's lock, and a pin never waits on that lock.
    private fun isPinnedKey(key: String): Boolean = ContentKeys.contentIdOf(key)?.let { it in pinned } == true

    // A released cache has closed its database, which the provider would otherwise quietly reopen.
    private fun checkOpen() = check(!released) { "The cache has been released" }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        try {
            cache.release()
        } finally {
            index.close()
        }
    }

    private companion object {
        const val MEDIA_DIRECTORY_NAME = "media"
        const val INDEX_FILE_NAME = "index.db"
    }
}

/**
 * Media3's cache index, kept in a database file SuperPlayer did not choose the place of.
 *
 * Media3's `StandaloneDatabaseProvider` opens its database in the application's database directory
 * under a name Media3 picked, which would be a file outside the directory the consumer named — the
 * storage ADR-0006 rule 2 forbids choosing on their behalf (ADR-0010 rule 2). `DatabaseProvider` is
 * an interface, so this implements it over a file inside that directory. Opening by `File` needs no
 * `Context`, which is why [CachePolicy.contentKeyed] takes none.
 * ref: https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase#openOrCreateDatabase(java.io.File,%20android.database.sqlite.SQLiteDatabase.CursorFactory)
 *
 * Opened lazily, on the first call Media3's index makes, which is on the cache's own initialization
 * thread rather than the consumer's.
 */
internal class DirectoryDatabaseProvider(private val file: File) : DatabaseProvider {

    private var database: SQLiteDatabase? = null

    override fun getWritableDatabase(): SQLiteDatabase = open()

    // Media3's contract allows the readable and writable database to be one object, as they are here.
    override fun getReadableDatabase(): SQLiteDatabase = open()

    @Synchronized
    private fun open(): SQLiteDatabase = database ?: SQLiteDatabase.openOrCreateDatabase(file, null).also { database = it }

    @Synchronized
    fun close() {
        database?.close()
        database = null
    }
}
