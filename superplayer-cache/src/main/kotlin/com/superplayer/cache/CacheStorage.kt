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
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
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

    private val index = DirectoryDatabaseProvider(File(directory, INDEX_FILE_NAME))

    /**
     * Media3's cache. Least-recently-used eviction within [maxBytes]; the budget's derivation and a
     * region eviction may not touch are #157's.
     */
    val cache: SimpleCache = SimpleCache(File(directory, MEDIA_DIRECTORY_NAME), LeastRecentlyUsedCacheEvictor(maxBytes), index)

    private var released = false

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
