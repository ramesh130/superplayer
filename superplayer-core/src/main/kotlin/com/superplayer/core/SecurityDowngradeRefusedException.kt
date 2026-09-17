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

import java.io.IOException

/**
 * The session could not be opened at a level this device can honour, and the licence server did not
 * permit the lower one — so nothing was delivered rather than something weaker (ADR-0012 rule 11).
 *
 * Raised by `superplayer-drm` and carried out of it as evidence rather than as a classification,
 * which is ADR-0012 rule 6's shape and the reason this type is core's: `superplayer-resilience`'s
 * `ErrorClassifier` reads the fields below and names the failure, and the module that found them
 * keeps no taxonomy of its own (ADR-0012 rule 5).
 *
 * A consumer sees it as the cause of the `PlaybackException` `onPlayerError` carries, under the
 * `SuperPlayerError` rung 6 builds where the fallback ladder is attached. It is **not** a licence
 * that could not be fetched: no licence was ever asked for, because there was no level to ask at.
 */
public class SecurityDowngradeRefusedException internal constructor(

    /** What the device reports for `securityLevel`, and cannot honour — `L1` in the case that occurs. */
    public val deviceSecurityLevel: String,

    /** The level the session would have had to open at, and the one that was not permitted. */
    public val refusedLevel: String,

    /**
     * The levels the licence server's operator did permit, for a bug report — `WidevineConfig`'s
     * `permittedSecurityLevels`, which is empty whenever an app configured none.
     *
     * Empty is the commonest reason a downgrade is refused and is not an error on anyone's part: it
     * is an app that was never told its licence server would issue at a lower level, which is most
     * apps. A non-empty set here that does not contain [refusedLevel] is the rarer and more
     * interesting case — a server that issues at some lower level, but not the one this device needs.
     */
    public val permittedLevels: Set<String>,
) : IOException(
    "This device reports $deviceSecurityLevel but cannot honour it, and the licence server is not " +
        "configured to permit $refusedLevel (it permits ${permittedLevels.ifEmpty { "nothing" }})",
)
