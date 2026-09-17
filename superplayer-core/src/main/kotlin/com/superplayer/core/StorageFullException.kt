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
 * A download could not write its next bytes, because the volume the consumer's cache directory is on
 * cannot hold them (ADR-0013 rule 9, issue #244).
 *
 * Raised by the cache's download half and carried out as evidence, which is the shape
 * [StaleLivePlaylistException] has and the reason this type is core's: the module that found the fact
 * keeps no taxonomy of its own (ADR-0011 rule 1), and `superplayer-resilience`'s `ErrorClassifier`
 * names it `FailureClass.Storage.Full` off its type. The fields are for a bug report rather than for
 * the classification.
 *
 * It fails the one download that met it and nothing else: every other item in the store carries on,
 * and what the failed one had written stays in the cache for a later enqueue to continue from.
 */
public class StorageFullException internal constructor(

    /** The bytes the download was about to write. */
    public val bytesToWrite: Long,

    /** The bytes the platform said this app could still write to the volume, when it was asked. */
    public val bytesAvailable: Long,
) : IOException("The download's storage is full: $bytesToWrite bytes to write and $bytesAvailable available")
