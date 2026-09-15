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
 * superplayer-preload — PreloadCoordinator: first-segment prefetch in scroll order, on the pool's engine
 *
 * A [PreloadCoordinator] is attached to a `PlayerPool` and builds Media3's preload manager over the
 * components the pool's players share, so a prefetched row loads through the chain core composes and
 * plays through `setMediaRequest` like any other (ADR-0010). Decoder warm-up, the memory guard and the
 * data-saver rule arrive in later changes to this module.
 */
package com.superplayer.preload
