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

package com.superplayer.core

import androidx.media3.datasource.DataSource

/**
 * What a doctor's fetch travels over, when that is not the device's own network.
 *
 * **A consumer never has one.** What produces one is `superplayer-testkit`'s
 * `PlaybackHarness.diagnosticEnvironment`. What takes one is `superplayer-diagnostics`'s
 * `MediaSourceDoctor.Builder`, through a setter internal to that module, so the only code able to hand one
 * to a doctor is that module's own tests; a doctor built without one fetches over the platform's HTTP
 * stack, as a player built without the engine configurator does.
 *
 * It is public for [DownloadEnvironment]'s reason and takes its shape (ADR-0010 rule 3): the harness is
 * phase 2 and `superplayer-diagnostics` phase 9, so neither can name the other's types, and a type both
 * can name has to be core's. Its member is internal because `DataSource.Factory` is Media3
 * `@UnstableApi` vocabulary (ADR-0001 rule 2), and its constructor is internal so that only a friend of
 * core can make one.
 *
 * It carries a transport and nothing else, which is the difference from [DownloadEnvironment]. A doctor
 * builds no engine, selects no track and acquires no licence, so it has no renderers to choose against
 * and no `MediaDrm` to acquire on; and it runs its one fetch on the thread that asked for it
 * (`MediaSourceDoctor.examine`), so there is no load executor for a harness to own.
 */
public abstract class DiagnosticEnvironment internal constructor() {

    /**
     * What sits at the bottom of a doctor's chain in place of the HTTP stack: the harness's origin and
     * fault injector, the same composition a harness-built player of the same content loads through.
     *
     * **Null where the harness put that same origin behind an `HttpTransport` instead**, for
     * [DownloadEnvironment.transport]'s reason: this slot wins over a consumer's stack (ADR-0016 rule 3),
     * so a doctor handed both would resolve its bottom here and a test of
     * `MediaSourceDoctor.Builder.setHttpStack` would pass without the stack having carried a byte (#314).
     */
    internal abstract val transport: DataSource.Factory?
}
