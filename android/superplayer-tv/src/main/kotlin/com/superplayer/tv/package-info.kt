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

/**
 * superplayer-tv — CTV behind one output slot (ADR-0014)
 *
 * `TvOutput.standard(context)` is what `SuperPlayer.Builder.setOutput` and
 * `PlayerPool.Builder.setOutput` take. On a player built with it, the display's refresh rate is
 * matched to the content's declared frame rate, a display or audio-output change re-selects, and a
 * decision asking for tunneling is laid on the selector. `TvPlaybackControls` is the D-pad controls in
 * Compose for TV, for any Media3 `Player`: focus through every control, and seek-scrubbing that seeks once.
 */
package com.superplayer.tv
