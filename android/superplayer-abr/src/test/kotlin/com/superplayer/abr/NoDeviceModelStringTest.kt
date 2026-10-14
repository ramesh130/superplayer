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

package com.superplayer.abr

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Capabilities are *queried*, never allowlisted (`PRD.md` Part 7, principle 9): there is no
 * device-model string anywhere in this module, because a table keyed on one is wrong on the next
 * device it has not met. A plain file walk, so the rule binds every file this module will ever
 * have and not only the ones a reviewer thought to look at.
 */
class NoDeviceModelStringTest {

    @Test
    fun noSourceFileNamesADeviceModel() {
        val sources = File("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        check(sources.isNotEmpty()) { "No sources found under ${File("src/main").absolutePath}" }
        for (source in sources) {
            for ((number, line) in source.readLines().withIndex()) {
                for (identifier in DEVICE_IDENTIFIERS) {
                    assertWithMessage("${source.name}:${number + 1} names $identifier").that(line).doesNotContain(identifier)
                }
            }
        }
    }

    private companion object {
        val DEVICE_IDENTIFIERS = listOf(
            "Build.MODEL",
            "Build.MANUFACTURER",
            "Build.DEVICE",
            "Build.PRODUCT",
            "Build.BRAND",
            "Build.HARDWARE",
            "Build.BOARD",
        )
    }
}
