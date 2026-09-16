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
 * superplayer-drm — Widevine sessions, provisioning, offline licences, and the security-level ladder
 *
 * Phase 6 (ADR-0012). The public surface is two types — [com.superplayer.drm.Drm] and
 * [com.superplayer.drm.WidevineConfig] — and everything else is internal, because every Media3 type
 * DRM needs carries `@UnstableApi`. What reaches the engine is a `DrmSessionManagerProvider` in the
 * one DRM slot on core's engine configuration, filled as core's sixth Kotlin friend; a player built
 * without `SuperPlayer.Builder.setDrm` loads no class of this module at all.
 */
package com.superplayer.drm
