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

package com.superplayer.testkit

import androidx.media3.datasource.DataSource
import com.superplayer.core.DiagnosticEnvironment

/**
 * The [DiagnosticEnvironment] a [PlaybackHarness] hands a doctor: the transport a harness-built player
 * would load the same content through.
 *
 * [injector] is kept for the harness's own question — what the doctor fetched — exactly as a player's and
 * a download's are. There is no wait of its own: a doctor's fetch runs on the thread that asked for it, so
 * the test thread is what a held load holds, and a test that injects a delay moves the clock itself.
 */
internal class HarnessDiagnosticEnvironment(
    override val transport: DataSource.Factory,
    val injector: FaultInjectingDataSource.Factory,
) : DiagnosticEnvironment()
