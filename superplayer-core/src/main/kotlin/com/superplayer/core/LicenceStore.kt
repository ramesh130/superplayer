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

/**
 * Where a download store keeps the offline licences of what it downloads: a store the consumer opened
 * in a directory they named (ADR-0012 rule 8).
 *
 * **A consumer never implements one.** What makes one is `superplayer-drm`'s `OfflineLicences.store`;
 * what takes one is `superplayer-offline`'s `Downloads.Builder.setDrm`. It is core's for ADR-0013 rule
 * 1's reason, and the shape is [ContentCache]'s (ADR-0010 rule 3): the download module depends on core
 * alone, so an app that downloads only clear content carries nothing that knows what Widevine is, and a
 * type both modules can name has to be core's. Its members are internal, and its constructor is too, so
 * only a friend of core can make one.
 *
 * What it holds per content id is a handle on keys the device keeps and the deadlines of both of a
 * licence's durations, and the one fact a download adds: that the download was removed and its licence
 * is waiting to be released at the server (ADR-0013 rule 13). A licence in that state is given to no
 * player.
 */
public abstract class LicenceStore internal constructor(
    /** What a download store reads and writes licences through; see [StoredLicences]. */
    internal val licences: StoredLicences,
)

/**
 * The half of a [LicenceStore] a download store is built from (ADR-0013 rule 4). `superplayer-drm` fills it
 * over its own index; internal because a key-set id is nothing a consumer can use.
 */
internal interface StoredLicences {

    /** The licence held for [contentId] and not waiting to be released, or null where there is none. */
    fun standingOf(contentId: String): LicenceStanding?

    /**
     * Writes the licence the device answered an acquisition with, as the licence for [contentId], with
     * the durations the device reported for it straight afterwards. A licence it replaces is not dropped but
     * owed a release, since the server still counts it; answers whether there was one.
     */
    fun write(contentId: String, keySetId: ByteArray, licenceSecondsLeft: Long, playbackSecondsLeft: Long): Boolean

    /**
     * Marks [contentId]'s licence as waiting to be released, and answers whether the store held one.
     * Persisted, so a release a process did not finish is one the next process still owes.
     */
    fun awaitRelease(contentId: String): Boolean

    /**
     * The key-set id of every licence waiting to be released. By key-set id rather than content id, because
     * one content id can owe several: removed, downloaded again and removed again before a network came back.
     */
    fun awaitingRelease(): List<ByteArray>

    /** Forgets the owed release of [keySetId], which the server has been told of. A licence in force is untouched. */
    fun released(keySetId: ByteArray)
}

/**
 * A stored licence as a download store reads it: both durations left and what follows from them, decided
 * by the store that holds the licence so that the two modules reading one licence cannot disagree about
 * when it is dead or due.
 */
internal class LicenceStanding(
    val playbackDurationRemainingMs: Long,
    val licenceDurationRemainingMs: Long,
    val isExpired: Boolean,
    val renewalDue: Boolean,
)
