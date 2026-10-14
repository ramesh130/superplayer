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

import android.os.StatFs
import android.system.ErrnoException
import android.system.OsConstants
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import com.superplayer.core.StorageFullException
import java.io.File
import java.io.IOException

/**
 * A download's cache writes, measured against the free space of the volume [directory] is on, so a disk that
 * cannot hold the next write fails it as core's [StorageFullException] rather than as an anonymous I/O failure
 * (ADR-0013 rule 9, #244).
 *
 * **Why measure at all**, when a full disk fails the write by itself: the failure a full disk raises is an
 * `IOException` indistinguishable, to a downloader, from a connection that dropped, and a store waits a lost
 * network out. Asking the platform first is the fact the engine does not report, and it carries the evidence.
 * The platform's own `ENOSPC` is still translated to the same exception, because another writer can take the
 * space between the reading and the write.
 *
 * **When it measures**: at the first write of each request, and again only once the writes since the last
 * reading would pass what that reading allowed. A segment costs one reading, not one per buffer.
 *
 * **A volume the platform does not describe refuses nothing**, the direction `DeviceConstraints` takes for a
 * codec table it cannot read: a reading of no blocks, or a path `StatFs` will not take, is a platform that
 * says nothing, and a download refused on no evidence would fail on every such platform with no way for the
 * viewer to mend it. That is where this departs from `CacheBudget.availableBytesAt`, which reads the same
 * nothing as nothing free, because a suggested budget has a floor to fall back on and a write has none.
 *
 * Only a download's writes come through here. A streaming player's cache writes are Media3's own, and a full
 * disk there costs a cached span rather than the playback, which Media3 already decides.
 */
internal class DiskMeasuredSink(private val delegate: DataSink, private val directory: File) : DataSink {

    /** The bytes this request may still write before the volume is read again. */
    private var allowance = 0L

    override fun open(dataSpec: DataSpec) {
        allowance = 0
        delegate.open(dataSpec)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length > allowance) {
            val available = availableBytes()
            if (length > available) throw StorageFullException(length.toLong(), available)
            allowance = available
        }
        allowance -= length
        translatingNoSpace(length.toLong()) { delegate.write(buffer, offset, length) }
    }

    override fun close() {
        // Closing is where the delegate flushes its buffer, so a write can meet the disk here too. The bytes it
        // flushes were counted as written when they were handed over, so none are reported as still to write.
        translatingNoSpace(bytesToWrite = 0) { delegate.close() }
    }

    private inline fun translatingNoSpace(bytesToWrite: Long, write: () -> Unit) {
        try {
            write()
        } catch (e: IOException) {
            if (!isNoSpace(e)) throw e
            // None available: the platform has just refused the write for want of space, whatever it read before.
            throw StorageFullException(bytesToWrite, bytesAvailable = 0).apply { initCause(e) }
        }
    }

    /** What the platform says this app may write to [directory]'s volume, or [Long.MAX_VALUE] where it says nothing. */
    private fun availableBytes(): Long = try {
        val stats = StatFs(directory.path)
        if (stats.blockCountLong == 0L) Long.MAX_VALUE else stats.availableBytes
    } catch (_: IllegalArgumentException) {
        Long.MAX_VALUE
    }

    private companion object {

        // Far past the depth Media3 wraps a write failure to: `CacheDataSink`'s own exception around the
        // stream's, around the platform's.
        const val CAUSE_DEPTH = 8

        // ref: https://developer.android.com/reference/android/system/OsConstants#ENOSPC
        fun isNoSpace(error: Throwable): Boolean = generateSequence(error) { it.cause }
            .take(CAUSE_DEPTH)
            .any { it is ErrnoException && it.errno == OsConstants.ENOSPC }
    }
}
