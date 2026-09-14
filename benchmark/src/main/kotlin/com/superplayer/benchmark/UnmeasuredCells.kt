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

package com.superplayer.benchmark

/**
 * Cells of the Robolectric matrix the runner does not play, each with the issue that closes it.
 *
 * The one place a skip is stated, so the runner, its completeness assertion and the report cannot
 * disagree about what was left out. A skip is an explicit decision taken before the run rather than
 * a failure caught during it: catching the harness's error would leave an engine spinning under every
 * later cell, and would turn "could not measure" into a startup failure rate, which is a number.
 *
 * Kept apart from [PublicStreams.gaps], which are cells of the content axis nothing could measure yet;
 * these are cells the matrix defines and the harness cannot currently drive.
 */
internal object UnmeasuredCells {

    /** One scenario × arm pair left out across every network profile, and why. */
    data class Skip(val scenario: Scenario, val arm: Arm, val gap: PublicStreams.Gap)

    val entries: List<Skip> = listOf(
        Skip(
            scenario = Scenario.LIVE,
            arm = Arm.ADAPTIVE,
            gap = PublicStreams.Gap(
                cell = "Live content, ${Arm.ADAPTIVE.label}, every network profile",
                why = "Under the harness, the adaptive policy on the synthetic live ladder keeps the " +
                    "engine working without the clock moving, and the session neither starts nor " +
                    "fails — while the static profile and both stock arms play the same window. The " +
                    "adaptive policy is the only arm that lays a live playback-speed range into the " +
                    "item, over a window dated against a wall clock Robolectric does not move; whether " +
                    "that is a harness limitation or a library defect is not yet known. So the adaptive " +
                    "policy's live branch has no number in this report, and the exit criterion is " +
                    "judged on the other three scenarios.",
                closedBy = "issue #144, then re-taking this report.",
            ),
        ),
    )

    fun skips(key: CellKey): Boolean = entries.any { it.scenario == key.scenario && it.arm == key.arm }
}
