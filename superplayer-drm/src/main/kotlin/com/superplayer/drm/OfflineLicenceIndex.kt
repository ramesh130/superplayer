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

package com.superplayer.drm

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The one file an [OfflineLicenceStore] writes, inside the directory the consumer named.
 *
 * A database in that directory rather than anywhere the platform would put one by default, which is
 * the shape `superplayer-cache`'s `DirectoryDatabaseProvider` already has and for the same reason:
 * ADR-0012 rule 8 requires that everything the store writes land inside [directory], so that deleting
 * it takes the licences with it. Opening by `File` needs no `Context`, which is why
 * [OfflineLicences.store] takes none.
 * ref: https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase#openOrCreateDatabase(java.io.File,%20android.database.sqlite.SQLiteDatabase.CursorFactory)
 *
 * What is in a row is a handle and two deadlines, and deliberately not the keys: a key-set id names
 * keys the *device* holds in its own protected storage and is useless anywhere else.
 *
 * The deadlines are absolute and wall-clock, computed once from what the device reported when the
 * licence was acquired or renewed. That is what makes a reading free — no device, no session, no
 * network, so a list screen can ask about fifty downloads (ADR-0012 rule 9) — and its one cost is
 * stated rather than hidden: a viewer who moves the device clock moves these readings with it. The
 * licence itself is unaffected, because the device enforces its own expiry whatever this file says,
 * so the worst such a move produces is a reading that disagrees with the refusal playback gives.
 */
internal class OfflineLicenceIndex(directory: File) {

    private val file: File

    init {
        // Created rather than required, as `SimpleCache` does for the cache's directory: a consumer
        // naming a directory inside their own storage should not also have to have made it.
        directory.mkdirs()
        require(directory.isDirectory) { "An offline licence store needs a directory: $directory" }
        file = File(directory, INDEX_FILE_NAME)
    }

    private var database: SQLiteDatabase? = null

    @Synchronized
    private fun open(): SQLiteDatabase = database ?: SQLiteDatabase.openOrCreateDatabase(file, null).also {
        it.execSQL(
            "CREATE TABLE IF NOT EXISTS $TABLE (" +
                "$COLUMN_CONTENT_ID TEXT PRIMARY KEY NOT NULL, " +
                "$COLUMN_KEY_SET_ID BLOB NOT NULL, " +
                "$COLUMN_LICENCE_EXPIRES_AT INTEGER NOT NULL, " +
                "$COLUMN_PLAYBACK_EXPIRES_AT INTEGER NOT NULL)",
        )
        database = it
    }

    @Synchronized
    fun write(contentId: String, keySetId: ByteArray, licenceSecondsLeft: Long, playbackSecondsLeft: Long): OfflineLicence {
        val now = System.currentTimeMillis()
        val licenceExpiresAt = deadline(now, licenceSecondsLeft)
        val playbackExpiresAt = deadline(now, playbackSecondsLeft)
        open().execSQL(
            "INSERT OR REPLACE INTO $TABLE VALUES (?, ?, ?, ?)",
            arrayOf(contentId, keySetId, licenceExpiresAt, playbackExpiresAt),
        )
        return licenceOf(contentId, keySetId, licenceExpiresAt, playbackExpiresAt, now)
    }

    @Synchronized
    fun read(contentId: String): OfflineLicence? =
        open().query(TABLE, null, "$COLUMN_CONTENT_ID = ?", arrayOf(contentId), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) return null
            licenceOf(
                contentId,
                cursor.getBlob(cursor.getColumnIndexOrThrow(COLUMN_KEY_SET_ID)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_LICENCE_EXPIRES_AT)),
                cursor.getLong(cursor.getColumnIndexOrThrow(COLUMN_PLAYBACK_EXPIRES_AT)),
                System.currentTimeMillis(),
            )
        }

    @Synchronized
    fun contentIds(): Set<String> =
        open().query(TABLE, arrayOf(COLUMN_CONTENT_ID), null, null, null, null, null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    @Synchronized
    fun remove(contentId: String) {
        open().delete(TABLE, "$COLUMN_CONTENT_ID = ?", arrayOf(contentId))
    }

    @Synchronized
    fun close() {
        database?.close()
        database = null
    }

    private fun licenceOf(
        contentId: String,
        keySetId: ByteArray,
        licenceExpiresAtMs: Long,
        playbackExpiresAtMs: Long,
        nowMs: Long,
    ) = OfflineLicence(
        contentId,
        playbackDurationRemainingMs = (playbackExpiresAtMs - nowMs).coerceAtLeast(0L),
        licenceDurationRemainingMs = (licenceExpiresAtMs - nowMs).coerceAtLeast(0L),
        keySetId = keySetId,
    )

    /**
     * When a duration of [secondsLeft] runs out, counted from [nowMs].
     *
     * Clamped at both ends rather than trusted. A licence with nothing left reports a duration that
     * is zero or negative and becomes a deadline in the past; and Media3 answers `Long.MIN_VALUE + 1`
     * for a property the device did not report at all, which multiplied by a thousand would wrap into
     * a deadline far in the future — a dead licence reading as a live one, which is the one direction
     * this must never round.
     */
    private fun deadline(nowMs: Long, secondsLeft: Long): Long =
        if (secondsLeft <= 0L) nowMs else nowMs + secondsLeft.coerceAtMost(MAX_SECONDS) * 1000L

    private companion object {

        const val INDEX_FILE_NAME = "offline-licences.db"
        const val TABLE = "offline_licences"
        const val COLUMN_CONTENT_ID = "content_id"
        const val COLUMN_KEY_SET_ID = "key_set_id"
        const val COLUMN_LICENCE_EXPIRES_AT = "licence_expires_at_ms"
        const val COLUMN_PLAYBACK_EXPIRES_AT = "playback_expires_at_ms"

        /** A hundred years, which no licence outlives and whose milliseconds fit in a `Long`. */
        const val MAX_SECONDS = 100L * 365 * 24 * 60 * 60
    }
}
