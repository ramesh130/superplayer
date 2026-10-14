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

package com.superplayer.drm

import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import com.superplayer.testmedia.WidevineProtection
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A player that holds more than one protected item asks for a licence per **licence policy** — not
 * once for everything it will ever play (#209).
 *
 * ## Why this is a defect repaired rather than an optimisation added
 *
 * `DefaultDrmSessionManager` has two modes and only one of them looks at the content. With
 * `multiSession` false — Media3's default, and what this module left it at until this change — the
 * manager keeps a single `noMultiSessionDrmSession` and hands it to **every** format whatever its
 * `DrmInitData`; the list of open sessions is searched for one whose scheme datas are equal only when
 * the flag is true. So the cheap-looking default was never "one licence where the policy is shared".
 * It was one licence for everything a prepared manager is asked about, two assets a licence server
 * issued separate licences for included — which is the correctness bug the issue names, and the
 * reason the fix is a flag rather than a cache.
 *
 * SuperPlayer has to choose where Media3 does not. Media3's own `DefaultDrmSessionManagerProvider`
 * reads the flag off `MediaItem.DrmConfiguration`, so a stock player answers the question per item;
 * ADR-0012 rule 1 declares protection once **per player**, which means one session manager spans
 * every item the player holds and the false default spans them all with one session.
 *
 * ## What "shares a licence policy" is taken to mean here
 *
 * The initialization data the content declares, compared whole — Media3's own rule, and the
 * conservative one. Two streams whose `pssh` names the same key id need the same keys and are one
 * licence to whichever server issued either; two streams naming different key ids were licensed
 * apart, and there is no reading of a manifest under which one session may serve both. Anything
 * finer than the key is invisible to a client: a server that draws a per-asset line behind identical
 * key ids has not told the player, and a player that drew it anyway would be guessing.
 *
 * The security level #208 introduced is deliberately **not** part of the identity, and that is an
 * argument rather than an omission: a level is negotiated once while the player's chain is composed
 * and is then set on the device for the player's lifetime, so every session of one manager is
 * delivered at the same level and the level can separate nothing. Were a level ever negotiated per
 * item it would have to join the key here — two items delivered at different levels are not one
 * policy — which is why the fact is written down rather than left to be rediscovered.
 *
 * ## Where the question can be put to a player, and where it cannot
 *
 * To a **playlist**: `Player.setMediaItems` is part of the facade, so two items can be held at once,
 * and a manager asked about both while prepared is the shape every claim above is about.
 *
 * Not to two successive `setMediaRequest` calls, and [aReplacementIsANewSessionGraphAndNotAReuse]
 * records why in the one place a reader will look for it. That is a *replacement*: the outgoing
 * `MediaSource` releases the manager, its prepare count reaches zero, and Media3 then tears down
 * every session it holds whatever `multiSession` and `sessionKeepaliveMs` say — keepalive holds a
 * session across a gap in a manager's *references*, not across the manager's own lifetime. Holding
 * on through such a gap would mean deferring the manager's release with nothing to bound the
 * deferral, and the thing left holding on would be a `MediaDrm`: a scarce device resource traded for
 * one small request. That is a worse bargain than the request, so it is not made.
 *
 * ## How it is asserted
 *
 * By counting `ResourceKind.LICENCE` requests at the transport, because the issue asks for exactly
 * that: a claim about sessions is a claim about traffic, and a session graph can be described
 * correctly and still ask twice. The pair of playlist tests is the whole assertion — one licence
 * where the policy is shared *and* two where it is not — because a manager that always reuses and a
 * manager that never does each pass one of them. Only the second moves with this change; the first
 * held before it and is here to keep holding.
 */
@RunWith(AndroidJUnit4::class)
class SessionReuseTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aPlaylistOfItemsSharingALicencePolicyAcquiresOneLicence() {
        // Two genuinely different assets — a DASH stream and an HLS one, different manifests,
        // different segments, different addresses — declaring the same key. The protocols state that
        // key in entirely different vocabularies (a `ContentProtection` descriptor against an
        // `EXT-X-KEY`) and Media3's two parsers produce the same `DrmInitData` from them, which is
        // what makes this a test of the *policy* rather than of two copies of one file.
        val first = TestContent.protectedDash()
        val second = TestContent.protectedHls()
        val player = playlistOf(first.alsoServing(second), first.sourceUri, second.sourceUri)

        assertThat(player.playerError).isNull()
        assertWithMessage("licences for two items under one key").that(licenceRequests(player)).isEqualTo(1)
    }

    @Test
    fun itemsThatDoNotShareAPolicyDoNotShareASession() {
        // The control, and the half that makes the test above mean anything. The same stream twice in
        // every respect a player can see except the one that decides the question: the second names a
        // different key id, so a licence server issued it its own licence and a session holding the
        // first item's keys can decrypt nothing of it.
        val first = TestContent.protectedDash()
        val second = TestContent.protectedDash(key = WidevineProtection.ContentKey.SECOND)
        val player = playlistOf(first.alsoServing(second), first.sourceUri, second.sourceUri)

        assertThat(player.playerError).isNull()
        assertWithMessage("licences for two items under different keys").that(licenceRequests(player)).isEqualTo(2)
    }

    @Test
    fun oneItemAsksOnceHoweverLongItPlays() {
        // The floor under both counts above, and the thing that would quietly turn either of them
        // into a tautology: a session re-acquired per segment, or per playlist reload, would make
        // "two" arrive without a second item at all. Asserted on the item that plays furthest.
        val content = TestContent.protectedDash(segmentCount = SEGMENTS)
        val player = harness.buildPlayer(content = content, drm = widevine())
        player.setMediaRequest(MediaRequest.Builder(FIRST_ID).addSource(content.sourceUri).build())
        harness.playToReady(player)
        harness.advanceTimeInStepsMs(player, PLAYED_MS)

        assertThat(player.playerError).isNull()
        assertThat(licenceRequests(player)).isEqualTo(1)
    }

    @Test
    fun aReplacementIsANewSessionGraphAndNotAReuse() {
        // Recorded rather than desired, and the class KDoc argues the bargain. `setMediaRequest`
        // replaces the item, so the outgoing `MediaSource` takes the session manager's prepare count
        // to zero; Media3 releases every session it holds at that point and acquires a fresh
        // `ExoMediaDrm` for the next item. The same content under the same key therefore pays a
        // second licence — which is what a reader who expects this file to say "one licence per
        // playlist, always" needs to find written down rather than discover.
        val content = TestContent.protectedDash()
        val player = harness.buildPlayer(content = content, drm = widevine())
        player.setMediaRequest(MediaRequest.Builder(FIRST_ID).addSource(content.sourceUri).build())
        harness.playToReady(player)
        player.setMediaRequest(MediaRequest.Builder(SECOND_ID).addSource(content.sourceUri).build())
        harness.playToReady(player)

        assertThat(player.playerError).isNull()
        assertThat(licenceRequests(player)).isEqualTo(2)
    }

    /**
     * A player holding both [uris] at once, played far enough that each has opened its session.
     *
     * `setMediaItems` rather than `setMediaRequest`, because a playlist is what this file is about
     * and `setMediaRequest` is a replacement. It is the facade's own API either way — `SuperPlayer`
     * *is* a `Player` (ADR-0003) — and it carries no `MediaRequest` identity, which nothing here
     * needs: what decides a session is the protection the manifest declared.
     */
    private fun playlistOf(content: TestContent, vararg uris: String): SuperPlayer {
        val player = harness.buildPlayer(content = content, drm = widevine())
        player.setMediaItems(uris.map { MediaItem.fromUri(it) })
        harness.playToReady(player)
        // Past the end of the first item and well into the second: a session is acquired when a
        // renderer reads the item's format, so a playlist stopped at item one would count one
        // licence whatever the manager decided.
        harness.advanceTimeInStepsMs(player, PLAYED_MS)
        return player
    }

    /** What a consumer writes, against the harness's licence server rather than a real one. */
    private fun widevine() = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI))

    /** How many times a licence was asked for, repeats included — which is what a second session is. */
    private fun licenceRequests(player: SuperPlayer): Int =
        harness.networkRequests(player).count { it.kind == ResourceKind.LICENCE }

    private companion object {
        const val FIRST_ID = "series/expanse/s01e01"
        const val SECOND_ID = "series/expanse/s01e02"

        /** Enough segments that a session outlives more than one of them. */
        const val SEGMENTS = 4

        /** Past the end of a single-segment item, so a two-item playlist reaches its second. */
        const val PLAYED_MS = 20_000L
    }
}
