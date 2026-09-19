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

package com.superplayer.moq

import com.superplayer.core.RealtimeTrack
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.moq.MoqContainer
import java.io.IOException

/**
 * A MoQ catalog becomes the tracks the Phase 13 seam accepts.
 *
 * This is where Phase 14's "**both codec-description shapes are covered by a test**" criterion
 * (`PRD.md` Part 4) is discharged, and it can be discharged honestly because the subject is a pure
 * mapping: no QUIC, no session and no frames, so nothing here is skipped on a host without the
 * native library the way `MoqFfiLinkageTest` is. A `MoqCatalog` is a plain record on the Kotlin
 * side of the FFI, constructed here and read here.
 *
 * `DeclaredCatalogs` says which fields were observed against a real MoQ pipeline and which were
 * written for these tests, which is the criterion "every fixture states whether it was observed or
 * synthesized".
 *
 * **What the refusals here deliberately do not do.** A codec string nothing maps and a record that
 * contradicts its own fourcc are both refused by `RealtimeMediaPeriod.onTracks`, which calls
 * `RealtimeFormats` and `CodecConfigurationRecords` — both `internal` to `superplayer-realtime`,
 * which this module is not a friend of (ADR-0018 rule 11), so neither the refusal nor the table
 * behind it is reachable from here to assert. That is the seam working rather than a gap: what is
 * asserted below is the half this module owns, which is that such a track reaches the seam at all
 * instead of being quietly dropped. `RealtimePlaybackTest` owns the other half.
 */
class MoqCatalogTracksTest {

    /**
     * The out-of-band shape, on #340's observed `avc1` string and `avcC` bytes: a
     * `CodecConfiguration.Record` whose bytes are the catalog's, unmodified.
     *
     * `assertSame` on the array is the strong form of "exactly as received" (obligation 7): not
     * merely equal content but the same array, so no copy, reorder or unwrap can have happened on
     * the way.
     */
    @Test
    fun anAvc1RenditionCarriesItsRecordToTheSeamExactlyAsReceived() {
        val description = DeclaredCatalogs.OBSERVED_AVCC_PREFIX
        val catalog = DeclaredCatalogs.videoOnly(
            codec = DeclaredCatalogs.OBSERVED_AVC1_CODEC,
            description = description,
        )

        val tracks = MoqCatalogTracks.declaredTracksOf(catalog)

        assertEquals("one declared track", 1, tracks.size)
        assertEquals(
            "the codec string, verbatim",
            DeclaredCatalogs.OBSERVED_AVC1_CODEC,
            tracks.single().track.codec,
        )
        val configuration = tracks.single().track.codecConfiguration
        assertTrue(
            "an out-of-band record, not $configuration",
            configuration is RealtimeTrack.CodecConfiguration.Record,
        )
        assertSame(
            "the record the catalog declared, not a copy of it",
            description,
            (configuration as RealtimeTrack.CodecConfiguration.Record).bytes,
        )
        assertArrayEquals(
            "the observed avcC prefix, unmodified",
            DeclaredCatalogs.OBSERVED_AVCC_PREFIX,
            configuration.bytes,
        )
    }

    /**
     * The in-band shape, on #340's observed `avc3` string and its observed *absence* of a
     * `description`: `CodecConfiguration.InBand` and no record.
     *
     * Asserted rather than assumed, which the acceptance criterion asks for in those words: the
     * seam defaults to `InBand`, so a mapping that read the description field wrongly — or never
     * read it — would produce the same answer here by accident. The `avc1` test above is what
     * turns this one into evidence, because between them only a mapping that actually branches on
     * the field passes both.
     */
    @Test
    fun anAvc3RenditionCarriesNoRecordAtAll() {
        val catalog = DeclaredCatalogs.videoOnly(
            codec = DeclaredCatalogs.OBSERVED_AVC3_CODEC,
            description = null,
        )

        val tracks = MoqCatalogTracks.declaredTracksOf(catalog)

        assertEquals(
            "the codec string, verbatim",
            DeclaredCatalogs.OBSERVED_AVC3_CODEC,
            tracks.single().track.codec,
        )
        assertSame(
            "parameter sets in band, so no record",
            RealtimeTrack.CodecConfiguration.InBand,
            tracks.single().track.codecConfiguration,
        )
    }

    /**
     * The record shape again for a codec that is not H.264, on a **synthesized** `hvcC`.
     *
     * It is here because this module's rule is that the shape follows what arrived and not what a
     * fourcc table here says — there is no such table — so a second codec carrying a record is the
     * control that the `avc1` case above was not an `avc1` special case.
     */
    @Test
    fun anHvc1RenditionCarriesItsRecordTheSameWay() {
        val catalog = DeclaredCatalogs.videoOnly(
            codec = DeclaredCatalogs.SYNTHESIZED_HVC1_CODEC,
            description = DeclaredCatalogs.SYNTHESIZED_HVCC,
        )

        val configuration = MoqCatalogTracks.declaredTracksOf(catalog).single().track.codecConfiguration

        assertArrayEquals(
            "the synthesized hvcC, unmodified",
            DeclaredCatalogs.SYNTHESIZED_HVCC,
            (configuration as RealtimeTrack.CodecConfiguration.Record).bytes,
        )
    }

    /**
     * A catalog listing audio beside video maps to both, in one list, video first.
     *
     * The order is the promise `EncodedFrame.trackIndex` rests on and the one #366's frame pump
     * will rely on, so it is asserted as an order rather than as a set.
     */
    @Test
    fun audioBesideVideoMapsToOneListWithVideoFirst() {
        val catalog = DeclaredCatalogs.catalog(
            video = mapOf(
                "video" to DeclaredCatalogs.video(
                    codec = DeclaredCatalogs.OBSERVED_AVC1_CODEC,
                    description = DeclaredCatalogs.OBSERVED_AVCC_PREFIX,
                ),
            ),
            audio = mapOf(
                "audio" to DeclaredCatalogs.audio(codec = DeclaredCatalogs.SYNTHESIZED_AAC_CODEC, description = null),
            ),
        )

        val tracks = MoqCatalogTracks.declaredTracksOf(catalog)

        assertEquals(
            "video at index 0 and audio at index 1",
            listOf(DeclaredCatalogs.OBSERVED_AVC1_CODEC, DeclaredCatalogs.SYNTHESIZED_AAC_CODEC),
            tracks.map { it.track.codec },
        )
        assertEquals("the catalog's own track names", listOf("video", "audio"), tracks.map { it.trackName })
    }

    /**
     * Audio alone is one element at index 0, not an audio track at index 1 with a hole before it.
     *
     * A legitimate MoQ configuration (#346 widened the seam for it), and the control that "video
     * first" is an ordering of what is there rather than a fixed pair of slots.
     */
    @Test
    fun audioAloneIsTheOnlyTrackAndIsIndexedFirst() {
        val catalog = DeclaredCatalogs.audioOnly(codec = DeclaredCatalogs.SYNTHESIZED_OPUS_CODEC, description = null)

        val tracks = MoqCatalogTracks.declaredTracksOf(catalog)

        assertEquals("one declared track", 1, tracks.size)
        assertEquals(DeclaredCatalogs.SYNTHESIZED_OPUS_CODEC, tracks.single().track.codec)
    }

    /**
     * A codec string nothing maps survives into the list, verbatim.
     *
     * This is the criterion "refused **by name** rather than dropped from the list", and the point
     * of it is where the refusal happens: `RealtimeMediaPeriod.onTracks` refuses the whole
     * subscription with `UnsupportedRealtimeCodecException` naming the string, and one track
     * dropped here instead would have a partially understood catalog play as a silently narrower
     * stream. VP8 is the fixture because it is a codec a publisher plausibly sends and the seam's
     * table plausibly does not carry, rather than a string invented to fail.
     */
    @Test
    fun aCodecStringNothingMapsSurvivesIntoTheListVerbatim() {
        val catalog = DeclaredCatalogs.catalog(
            video = mapOf("video" to DeclaredCatalogs.video(codec = DeclaredCatalogs.SYNTHESIZED_UNMAPPED_CODEC, description = null)),
            audio = mapOf("audio" to DeclaredCatalogs.audio(codec = DeclaredCatalogs.SYNTHESIZED_AAC_CODEC, description = null)),
        )

        val tracks = MoqCatalogTracks.declaredTracksOf(catalog)

        assertEquals(
            "the unmappable track is still declared, beside the one that maps",
            listOf(DeclaredCatalogs.SYNTHESIZED_UNMAPPED_CODEC, DeclaredCatalogs.SYNTHESIZED_AAC_CODEC),
            tracks.map { it.track.codec },
        )
    }

    /**
     * A catalog declaring neither kind is refused, rather than answered with an empty list.
     *
     * The control on every test above: a mapping that returned an empty list for a catalog it did
     * not understand would pass all of them and leave a player buffering for ever on a subscription
     * with nothing in it.
     */
    @Test
    fun aCatalogDeclaringNeitherKindIsRefused() {
        val refusal = assertThrows(IOException::class.java) {
            MoqCatalogTracks.declaredTracksOf(DeclaredCatalogs.catalog())
        }

        assertTrue(
            "a message naming what was empty, was: ${refusal.message}",
            refusal.message.orEmpty().contains("no video track and no audio track"),
        )
    }

    /**
     * A ladder is subscribed to one rendition per kind, and the choice is the catalog's declaration
     * order rather than any judgement of this module's.
     *
     * ADR-0018 rule 7 defers selection and a phase adding it amends that record; until then the
     * assertion is that nothing here prefers. The second rendition is the better one *and* the
     * first is flagged `stalled` — the flag a publisher raises precisely so that a selecting player
     * moves off it — so a mapping that had grown any preference at all would take the second and
     * fail here rather than quietly become a selector.
     *
     * What it cannot show is the other half of "first": the maps here are Kotlin's own, built on
     * this side of the FFI, so this exercises the mapping's `entries.firstOrNull()` and never the
     * generated converter whose insertion order `MoqCatalogTracks`' `// ref:` reads off the
     * bindings rather than asserting.
     */
    @Test
    fun aLadderIsTakenAtItsFirstDeclaredRenditionAndNothingPrefers() {
        val catalog = DeclaredCatalogs.catalog(
            video = mapOf(
                "video-low" to DeclaredCatalogs.video(
                    codec = DeclaredCatalogs.OBSERVED_AVC3_CODEC,
                    description = null,
                    stalled = true,
                ),
                "video-high" to DeclaredCatalogs.video(
                    codec = DeclaredCatalogs.OBSERVED_AVC1_CODEC,
                    description = DeclaredCatalogs.OBSERVED_AVCC_PREFIX,
                ),
            ),
        )

        val tracks = MoqCatalogTracks.declaredTracksOf(catalog)

        assertEquals("one rendition per media kind", 1, tracks.size)
        assertEquals("the first rendition the catalog declared", "video-low", tracks.single().trackName)
    }

    /**
     * The track name and the container reach the subscription, which is what #366 opens one with.
     *
     * The container is carried and is deliberately *not* read for anything else: the bindings strip
     * container framing below the FFI boundary, so it must not enter a decoding decision (#340's
     * second correction, ADR-0018 rule 3). Carrying it beside the seam's own track rather than
     * inside it is what keeps it out of one.
     */
    @Test
    fun theTrackNameAndContainerAreCarriedForTheSubscription() {
        val catalog = DeclaredCatalogs.videoOnly(
            trackName = "video/1",
            codec = DeclaredCatalogs.OBSERVED_AVC3_CODEC,
            description = null,
            container = MoqContainer.Legacy,
        )

        val declared = MoqCatalogTracks.declaredTracksOf(catalog).single()

        assertEquals("the catalog's own key, which subscribeMedia takes", "video/1", declared.trackName)
        assertSame("the container the publisher declared", MoqContainer.Legacy, declared.container)
    }
}
