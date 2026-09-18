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

package com.superplayer.diagnostics

import java.util.Locale
import kotlin.math.roundToInt

/*
 * How a `Finding.magnitude` spells a number.
 *
 * A magnitude is words a report prints rather than a value anything branches on, so every rule file writes
 * its numbers through these rather than each formatting its own: a report whose rows spell a rate two ways
 * reads as two reports. Their own file, so that a rule about a clock or a segment spread adds one here
 * rather than editing a file about ladders.
 */

/** [value] to one decimal place, with a trailing `.0` dropped: `48`, `1.4`, `20`. */
internal fun decimal(value: Double): String = String.format(Locale.US, "%.1f", value).removeSuffix(".0")

/** [bitsPerSecond] as the unit a ladder is discussed in. */
internal fun kilobits(bitsPerSecond: Int): String = "${(bitsPerSecond / 1_000.0).roundToInt()} kbps"

/** [durationUs] as seconds, the unit a segment, a window and a clock skew are all discussed in. */
internal fun seconds(durationUs: Long): String = "${decimal(durationUs / 1_000_000.0)} s"
