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
 * superplayer-core — SuperPlayer facade, PlaybackSession, config profiles, player pool
 *
 * The one module a consumer depends on, and so far the only one carrying library code. It holds
 * `SuperPlayer` — a Media3 `Player` built by `SuperPlayer.Builder` — along with the types that
 * travel through it: `MediaRequest`, `PlaybackProfile`, `PlaybackSnapshot`, `PlaybackPolicy`,
 * `PlaybackSession`, `PlaybackService` and `PlayerPool`. Everything public here is tracked in
 * `api/superplayer-core.api` and enforced on every build.
 *
 * For what the module is for and how it fits the others, read `CLAUDE.md` and `docs/modules.md`
 * rather than a third copy of them here.
 */
package com.superplayer.core
