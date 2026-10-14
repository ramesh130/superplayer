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
import java.io.File

/**
 * The budget [CachePolicy.deviceAware] suggests, as arithmetic over two readings, so that every
 * constant is argued where it is chosen and the whole of it is testable without a device.
 *
 * The rates below are for orientation only: a 3 Mbit/s rendition is a common 720p rung, and 1.5 Mbit/s
 * a common data-saver one.
 */
internal object CacheBudget {

    private const val MEBIBYTE = 1024L * 1024

    /**
     * A tenth of what is free on the directory's volume. The platform reports storage low at a few
     * percent of a volume (5%, and never more than 500 MiB), and under that pressure clears apps' cache
     * directories — this cache's among them when it lives under `cacheDir`. A cache that took most of
     * what was free would drive the device there and be deleted for it; a tenth leaves the rest to the
     * user and to the app, and on a volume with tens of gigabytes free still reaches the ceiling.
     * ref: https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/os/storage/StorageManager.java
     * — `DEFAULT_THRESHOLD_PERCENTAGE` and `DEFAULT_THRESHOLD_MAX_BYTES`.
     */
    const val FREE_SPACE_DIVISOR = 10L

    /**
     * Never less than 32 MiB, however little is free: about a minute and a half at 3 Mbit/s, enough
     * that a seek back or a replay of the last scene is answered locally. Below it a cache holds too
     * little to save a request worth saving and still costs a directory, an index and eviction work.
     * The floor is a limit on what the cache may keep rather than an allocation, so it takes nothing
     * from a volume that has less than this free; the cache simply cannot fill it.
     */
    const val FLOOR_BYTES = 32 * MEBIBYTE

    /**
     * Never more than 2 GiB: about an hour and a half at 3 Mbit/s, a feature film replayable without a
     * request. Past that a streaming cache has stopped saving requests and started keeping content,
     * which is what a pin — and Phase 7's downloads behind it — is for.
     */
    const val CEILING_BYTES = 2048 * MEBIBYTE

    /**
     * Never more than 128 MiB on a device that declares itself low on RAM: about twelve minutes at
     * 1.5 Mbit/s. `isLowRamDevice` is the platform's flag for an entry-tier device, whose storage is
     * entry-tier with it, and it costs memory as well as disk here — `SimpleCache` keeps its index in
     * the heap, an object per stored span, so every entry is paid for on the device with least of it.
     * `DeviceCapacity.kt` treats the flag as an instruction rather than a hint, and so does this.
     * ref: https://developer.android.com/reference/android/app/ActivityManager#isLowRamDevice()
     */
    const val LOW_RAM_CEILING_BYTES = 128 * MEBIBYTE

    fun suggest(availableBytes: Long, isLowRamDevice: Boolean): Long {
        val ceiling = if (isLowRamDevice) LOW_RAM_CEILING_BYTES else CEILING_BYTES
        return (availableBytes.coerceAtLeast(0) / FREE_SPACE_DIVISOR).coerceIn(FLOOR_BYTES, ceiling)
    }

    /**
     * The bytes free to this app on the volume [directory] is, or will be, on.
     *
     * A directory the consumer has not created yet is measured at its nearest existing parent, which is
     * on the same volume, rather than created here: nothing is written before the cache is opened. A
     * volume the platform will not describe reads as nothing free, which the floor then answers.
     */
    fun availableBytesAt(directory: File): Long {
        val existing = generateSequence(directory.absoluteFile) { it.parentFile }.firstOrNull { it.exists() } ?: return 0
        return try {
            StatFs(existing.path).availableBytes
        } catch (_: IllegalArgumentException) {
            0
        }
    }
}
