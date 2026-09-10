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
 * superplayer-telemetry — QoE collector (CTA-2066), CMCD emitter, pluggable sinks
 *
 * Phase 2. What leaves the library is a `com.superplayer.core.TelemetryEvent` written to a
 * `com.superplayer.core.TelemetrySink`, both of which are core's; what lives here is the collector
 * that derives them — `QoeCollector` — and every Media3 analytics type it reads. ADR-0008 draws
 * that line and `docs/modules.md` enforces the direction of it.
 *
 * `QoeCollector` today emits the session boundary only. The CTA-2066 QoE metrics and the CMCD
 * emitter this module is named for arrive in issues #35 to #38.
 */
package com.superplayer.telemetry
