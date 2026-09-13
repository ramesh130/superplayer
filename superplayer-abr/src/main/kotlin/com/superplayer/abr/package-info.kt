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
 * superplayer-abr — AdaptiveLoadControl, NetworkAwareTrackSelection, BandwidthOracle
 *
 * Phase 3: the adaptive policy behind the boundary ADR-0005 establishes and ADR-0009 extends.
 * [com.superplayer.abr.BandwidthOracle] is the throughput estimator the other two read from; the
 * load control and the track selection are the next two issues of #96.
 */
package com.superplayer.abr
