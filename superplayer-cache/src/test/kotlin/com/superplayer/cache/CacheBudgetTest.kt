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

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * [CachePolicy.deviceAware] against a declared device: the free space the platform reports for the
 * directory's volume, and whether the device says it is low on RAM.
 */
@RunWith(AndroidJUnit4::class)
class CacheBudgetTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory: File by lazy { folder.newFolder() }

    @After
    fun forgetDeclaredVolumes() {
        ShadowStatFs.reset()
    }

    @Test
    fun theBudgetIsATenthOfTheFreeSpace() {
        declareFree(4 * GIB)

        assertThat(CachePolicy.deviceAware(context, directory)).isEqualTo(4 * GIB / 10)
    }

    @Test
    fun aLargeVolumeIsHeldToTheCeiling() {
        declareFree(100 * GIB)

        assertThat(CachePolicy.deviceAware(context, directory)).isEqualTo(2 * GIB)
    }

    @Test
    fun aLowRamDeviceIsHeldToALowerCeiling() {
        declareFree(4 * GIB)
        val ordinary = CachePolicy.deviceAware(context, directory)
        declareLowRam()

        assertThat(CachePolicy.deviceAware(context, directory)).isEqualTo(128 * MIB)
        assertThat(ordinary).isGreaterThan(128 * MIB)

        // Below its ceiling a low-RAM device is still sized by its free space.
        declareFree(800 * MIB)
        assertThat(CachePolicy.deviceAware(context, directory)).isEqualTo(80 * MIB)
    }

    @Test
    fun theBudgetIsNeverBelowTheFloor() {
        declareFree(100 * MIB)
        assertThat(CachePolicy.deviceAware(context, directory)).isEqualTo(32 * MIB)

        declareFree(0)
        assertThat(CachePolicy.deviceAware(context, directory)).isEqualTo(32 * MIB)

        declareLowRam()
        assertThat(CachePolicy.deviceAware(context, directory)).isEqualTo(32 * MIB)
    }

    @Test
    fun aDirectoryNotYetCreatedIsMeasuredOnItsVolumeAndNotCreated() {
        declareFree(4 * GIB)
        val notYetCreated = File(directory, "media/cache")

        assertThat(CachePolicy.deviceAware(context, notYetCreated)).isEqualTo(4 * GIB / 10)
        assertThat(File(directory, "media").exists()).isFalse()
    }

    private fun declareFree(bytes: Long) {
        val blocks = (bytes / ShadowStatFs.BLOCK_SIZE).toInt()
        ShadowStatFs.registerStats(directory, blocks, blocks, blocks)
    }

    private fun declareLowRam() {
        shadowOf(context.getSystemService(ActivityManager::class.java)).setIsLowRamDevice(true)
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val GIB = 1024 * MIB
    }
}
