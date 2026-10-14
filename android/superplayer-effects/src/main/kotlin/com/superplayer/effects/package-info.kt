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
 * superplayer-effects — the decoded frame, handed to an effect through a second output slot (ADR-0022)
 *
 * `VideoEffects.frameOutput(output)` is what `SuperPlayer.Builder.setFrameOutput` and
 * `PlayerPool.Builder.setFrameOutput` take. On a player built with it, every decoded frame of the video
 * passes through the consumer's `FrameOutput` before it is shown, on Media3's own effect pipeline. A
 * player built with protection or for realtime has the output off and says why.
 */
package com.superplayer.effects
