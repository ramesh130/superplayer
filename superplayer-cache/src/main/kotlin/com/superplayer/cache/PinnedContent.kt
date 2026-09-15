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

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.DatabaseProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * The content ids pinned in one cache, in a table of the cache's own index database.
 *
 * Kept there so a pin lives exactly as long as the directory does — across the process's death and a
 * reopening under another budget — and nowhere outside it (ADR-0010 rule 2). The table is this module's,
 * beside Media3's, and named so it cannot be taken for one of them.
 *
 * Read from the database once, on first use, and answered from memory after: the evictor asks on every
 * pass, under the cache's lock, and must not wait on the disk each time. The first use is usually the
 * evictor's own, on the cache's initialization thread.
 */
internal class PinnedContent(private val database: DatabaseProvider) {

    @Volatile
    private var ids: MutableSet<String>? = null

    operator fun contains(contentId: String): Boolean = contentId in loaded()

    @Synchronized
    fun add(contentId: String) {
        val pinned = loaded()
        val row = ContentValues().apply { put(COLUMN, contentId) }
        database.writableDatabase.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_IGNORE)
        pinned += contentId
    }

    @Synchronized
    fun remove(contentId: String) {
        val pinned = loaded()
        database.writableDatabase.delete(TABLE, "$COLUMN = ?", arrayOf(contentId))
        pinned -= contentId
    }

    private fun loaded(): MutableSet<String> = ids ?: synchronized(this) { ids ?: read().also { ids = it } }

    private fun read(): MutableSet<String> {
        val db = database.writableDatabase
        db.execSQL("CREATE TABLE IF NOT EXISTS $TABLE ($COLUMN TEXT PRIMARY KEY NOT NULL)")
        val pinned = ConcurrentHashMap.newKeySet<String>()
        db.query(TABLE, arrayOf(COLUMN), null, null, null, null, null).use { rows ->
            while (rows.moveToNext()) pinned += rows.getString(0)
        }
        return pinned
    }

    private companion object {
        const val TABLE = "SuperPlayerPinnedContent"
        const val COLUMN = "content_id"
    }
}
