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
 * superplayer-diagnostics — MediaSourceDoctor, session trace bundle, on-device debug HUD
 *
 * Phase 9, and so far the doctor alone: [com.superplayer.diagnostics.MediaSourceDoctor] takes the same
 * `MediaRequest` a player adopts and answers a [com.superplayer.diagnostics.DiagnosticReport] of
 * [com.superplayer.diagnostics.Finding]s — what is wrong with the stream, before a player is built.
 *
 * ADR-0015 decides the module's shape, and three of its rules are the ones to read before changing
 * anything here: a **pathology is not a failure**, so nothing in this module classifies one (rule 4); the
 * fetch travels the chain a *player* of that request would load through and the parsers that player would
 * run (rules 6 and 7); and the public surface names **no** Media3 type at all, which is stronger than
 * ADR-0001 rule 2 and is asserted rather than assumed (rule 2).
 */
package com.superplayer.diagnostics
