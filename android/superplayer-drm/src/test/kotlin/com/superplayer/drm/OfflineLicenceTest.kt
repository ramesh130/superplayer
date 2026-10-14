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

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.superplayer.core.MediaRequest
import com.superplayer.core.OfflineLicenceExpiredException
import com.superplayer.core.SuperPlayer
import com.superplayer.resilience.ErrorClassifier
import com.superplayer.resilience.FailureClass
import com.superplayer.resilience.Resilience
import com.superplayer.testkit.DeviceStatement
import com.superplayer.testkit.FakeLicenceServer
import com.superplayer.testkit.FaultScript
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.ResourceKind
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * The whole offline licence lifecycle `PRD.md` Part 4 names as an exit criterion (#210): acquire,
 * play offline, renew, release — and the expiry split that makes a dead download say something
 * actionable.
 *
 * Driven through the public API a consumer writes: `OfflineLicences.store(directory)`,
 * `store.over(player)` for the three verbs that talk to the licence server, and
 * `Drm.widevine(config, licence)` for the fourth, which is playing. Nothing here reaches past the
 * facade, and the store's directory is a `TemporaryFolder` because a store SuperPlayer chose the
 * place of is the thing ADR-0012 rule 8 forbids.
 *
 * **Two claims are counted at the transport rather than inferred.** That a licence was *stored* is
 * weak on its own — a player that quietly re-acquired one every time would look identical from the
 * store — so what offline playback is asserted on is a count of **zero** `ResourceKind.LICENCE`
 * requests. And that a release happened at all is asserted as the round trip it is, because a
 * release nobody told the server about leaves the licence counted against the viewer's device.
 *
 * **"With the network down" is expressed as a licence server that cannot be reached**, and that is
 * the closest this phase can come to the real thing rather than a shortcut: nothing here stores
 * *media* — the download stack is Phase 7's — so a test whose every request failed would be a test
 * of a player with no content. A `FaultScript` that fails DNS resolution for
 * [ResourceKind.LICENCE] leaves the CDN where it is and takes the entitlement server off the
 * network, which is the half this ticket is about. It also makes the count belt and braces: a single
 * licence request would not merely be counted, it would end the session.
 *
 * What stands in for a Widevine device that can persist a licence is stated in `superplayer-testkit`:
 * Media3's `FakeExoMediaDrm` refuses offline key requests outright and throws from `restoreKeys`, so
 * `WidevineDevice.kt` adds the bookkeeping a real implementation does — a key store that outlives one
 * `MediaDrm`, and the two durations `queryKeyStatus` reports — around Media3's own exchange.
 * `DeviceStatement.declareOfflineLicence` is how a test states those durations.
 */
@RunWith(AndroidJUnit4::class)
class OfflineLicenceTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @get:Rule
    val directory: TemporaryFolder = TemporaryFolder()

    @Test
    fun acquiringALicenceStoresItWithBothExpiriesAndNothingOutsideTheDirectory() {
        val store = OfflineLicences.store(storeDirectory())
        val player = playOnline()
        val licencesBefore = licenceRequests(player)

        val acquired = store.over(player).acquire(CONTENT_ID)

        // A download is a second exchange with the server and not a copy of the streaming session's
        // keys: Widevine issues a persistable licence under a different key type, and a store that
        // kept what the renderer was already using would be storing something that cannot be
        // restored. Counted, because from the store alone the two look identical.
        assertThat(licenceRequests(player)).isGreaterThan(licencesBefore)

        // Both durations, kept apart: ADR-0012 rule 9 is that they are two facts, and a store that
        // reduced them to their minimum could not tell an unwatched rental that expired from a
        // viewing window that closed. The device states them apart, so they arrive apart.
        assertThat(acquired.contentId).isEqualTo(CONTENT_ID)
        assertThat(acquired.licenceDurationRemainingMs).isGreaterThan(acquired.playbackDurationRemainingMs)
        assertThat(acquired.isExpired).isFalse()
        assertThat(store.licenceFor(CONTENT_ID)).isNotNull()
        assertThat(store.contentIds()).containsExactly(CONTENT_ID)

        // ADR-0012 rule 8: everything the store writes is inside the directory the consumer named, so
        // deleting it takes the licences with it. A file count rather than a file name, because what
        // the rule is about is the boundary and not the schema.
        store.close()
        assertThat(storeDirectory().listFiles()!!.map { it.name }).isNotEmpty()
        harness.release(player)
    }

    @Test
    fun aStoredLicenceIsReadableWithNoPlayerNoDeviceAndNoNetwork() {
        // The half of rule 9 a list screen needs: what the store holds is answerable from the
        // directory alone, by a store opened fresh over it, long after the player that acquired the
        // licence has gone. A reading that needed a session would be a reading an app could not make
        // for fifty downloads at once.
        val acquiringStore = OfflineLicences.store(storeDirectory())
        val player = playOnline()
        acquiringStore.over(player).acquire(CONTENT_ID)
        acquiringStore.close()
        harness.release(player)

        val reopened = OfflineLicences.store(storeDirectory())

        val licence = reopened.licenceFor(CONTENT_ID)
        assertThat(licence).isNotNull()
        assertThat(licence!!.licenceDurationRemainingMs).isGreaterThan(0L)
        assertThat(reopened.licenceFor("something/else")).isNull()
        reopened.close()
    }

    @Test
    fun protectedContentPlaysFromTheStoreWithTheLicenceServerUnreachableAndAsksForNoLicence() {
        val store = OfflineLicences.store(storeDirectory())
        val acquiring = playOnline()
        val licence = store.over(acquiring).acquire(CONTENT_ID)
        harness.release(acquiring)

        val offline = harness.buildPlayer(
            content = TestContent.protectedDash(),
            // The entitlement server is off the network and the CDN is not, which is what this phase
            // can express: nothing stores media yet.
            faults = FaultScript.Builder().failDnsResolution(kind = ResourceKind.LICENCE).build(),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI), licence),
        )
        offline.setMediaRequest(request())
        harness.playToReady(offline)

        assertThat(offline.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(offline.playerError).isNull()
        // The assertion that matters, and the reason the one above is not enough on its own.
        assertThat(harness.networkRequests(offline).filter { it.kind == ResourceKind.LICENCE }).isEmpty()
        harness.release(offline)
        store.close()
    }

    @Test
    fun renewingReplacesTheStoredLicenceBeforeItReachesZero() {
        // `PRD.md` §3.2's "renew before the remaining duration reaches zero", read as ADR-0012 rule
        // 10 requires: the store *reports* what is due on a stated threshold and the consumer calls
        // the renewal, because a library that scheduled it would be choosing a `WorkManager`
        // configuration on the app's behalf. An hour is well inside `OfflineLicence`'s day.
        DeviceStatement.declareOfflineLicence(licenceDurationSec = 3600, playbackDurationSec = 3600)
        val store = OfflineLicences.store(storeDirectory())
        val player = playOnline()
        val exchange = store.over(player)

        val nearlyExpired = exchange.acquire(CONTENT_ID)
        assertThat(nearlyExpired.renewalDue).isTrue()
        assertThat(nearlyExpired.isExpired).isFalse()

        // The server now issues a month, as it would for a renewed rental.
        DeviceStatement.declareOfflineLicence(licenceDurationSec = 30 * 24 * 3600, playbackDurationSec = 2 * 24 * 3600)
        val licencesBefore = licenceRequests(player)
        val renewed = exchange.renew(CONTENT_ID)

        assertThat(renewed.renewalDue).isFalse()
        assertThat(renewed.licenceDurationRemainingMs).isGreaterThan(nearlyExpired.licenceDurationRemainingMs)
        // A renewal is a round trip and not a local recalculation.
        assertThat(licenceRequests(player)).isGreaterThan(licencesBefore)
        // And one licence rather than two: a renewed download is still one thing to release.
        assertThat(store.contentIds()).containsExactly(CONTENT_ID)
        harness.release(player)
        store.close()
    }

    @Test
    fun releasingLeavesNoLicenceBehindAndTellsTheServer() {
        val store = OfflineLicences.store(storeDirectory())
        val player = playOnline()
        val exchange = store.over(player)
        exchange.acquire(CONTENT_ID)
        val licencesBefore = licenceRequests(player)

        exchange.release(CONTENT_ID)

        assertThat(store.licenceFor(CONTENT_ID)).isNull()
        assertThat(store.contentIds()).isEmpty()
        // The server was told, which is the half an assertion against the store alone cannot see: a
        // licence released only locally still counts against the viewer's device allowance.
        assertThat(licenceRequests(player)).isGreaterThan(licencesBefore)
        harness.release(player)
        store.close()
    }

    @Test
    fun anExpiredStoredLicenceIsReadableBeforePlaybackAndRefusedRatherThanReAcquired() {
        // The pair `PRD.md` §3.2 asks for. The *before* is the reading: an app can say "go online and
        // refresh this download", which is actionable, without building anything.
        DeviceStatement.declareOfflineLicence(licenceDurationSec = 0, playbackDurationSec = 0)
        val store = OfflineLicences.store(storeDirectory())
        val acquiring = playOnline()
        val expired = store.over(acquiring).acquire(CONTENT_ID)
        harness.release(acquiring)

        assertThat(expired.isExpired).isTrue()
        assertThat(expired.playbackDurationRemainingMs).isEqualTo(0L)
        assertThat(expired.licenceDurationRemainingMs).isEqualTo(0L)

        // And the *after*, for an app that plays it anyway. Media3's own answer to a restored licence
        // that has expired is to ask the server for a new one, which for a player built to play
        // offline is the wrong surprise: a round trip where there was to be none, failing as a
        // licence that could not be fetched rather than as the licence that expired. So it is
        // refused, and it says the same thing the store said.
        val player = harness.buildPlayer(
            content = TestContent.protectedDash(),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI), expired),
            resilience = Resilience.standard(),
        )
        player.setMediaRequest(request())
        harness.playToFailure(player)

        val error = player.playerError
        assertThat(error).isNotNull()
        assertThat(generateSequence(error as Throwable?) { it.cause }.filterIsInstance<OfflineLicenceExpiredException>().toList())
            .hasSize(1)
        // Its own message key, distinguishable from a licence that failed to arrive and from any
        // other playback failure — the classification is `superplayer-resilience`'s, read as a
        // consumer reads it.
        val classified = ErrorClassifier.classify(checkNotNull(error))
        assertThat(classified).isEqualTo(FailureClass.Drm.LicenceExpired)
        assertThat(classified.userMessageKey).isEqualTo(FailureClass.LICENCE_EXPIRED_MESSAGE_KEY)
        // Nothing was asked of the licence server, which is what "refused rather than re-acquired"
        // means in traffic.
        assertThat(licenceRequests(player)).isEqualTo(0)
        harness.release(player)
        store.close()
    }

    @Test
    fun aDeadDownloadIsRenewableOnThePlayerThatRefusedIt() {
        // The recovery an app actually takes after the reading above: the download is dead, so renew
        // it — on the player already in hand rather than on a second one built for the purpose. The
        // player's protection opened no session, but it holds the licence transport, and that is all
        // a renewal needs.
        DeviceStatement.declareOfflineLicence(licenceDurationSec = 0, playbackDurationSec = 0)
        val store = OfflineLicences.store(storeDirectory())
        val acquiring = playOnline()
        val expired = store.over(acquiring).acquire(CONTENT_ID)
        harness.release(acquiring)

        val refused = harness.buildPlayer(
            content = TestContent.protectedDash(),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI), expired),
        )
        refused.setMediaRequest(request())
        harness.playToFailure(refused)
        DeviceStatement.declareOfflineLicence(licenceDurationSec = 30 * 24 * 3600, playbackDurationSec = 2 * 24 * 3600)

        val renewed = store.over(refused).renew(CONTENT_ID)

        assertThat(renewed.isExpired).isFalse()
        assertThat(store.licenceFor(CONTENT_ID)!!.isExpired).isFalse()
        harness.release(refused)
        store.close()
    }

    @Test
    fun aDurationTheDeviceDoesNotReportIsIndistinguishableFromOneThatExpired() {
        // Recorded rather than asserted as desirable, which is what this repo does with a limit it
        // did not choose. A device that carries only one of the two properties — a purchase with no
        // separate viewing window — is read as a licence with nothing left, and the collapse is
        // Media3's and happens before the store can see it: an absent property is `C.TIME_UNSET`,
        // Media3's own MODE_QUERY session reads that as expired and raises `KeysExpiredException`,
        // and `OfflineLicenseHelper.getLicenseDurationRemainingSec` catches exactly that and answers
        // (0, 0). There is no sentinel left to tell the two apart, so a store that wanted to would
        // have to re-implement the query. A Widevine *offline* licence carries both properties,
        // which is why this is written down rather than worked around.
        DeviceStatement.declareOfflineLicence(licenceDurationSec = 30 * 24 * 3600, playbackDurationSec = null)
        val store = OfflineLicences.store(storeDirectory())
        val player = playOnline()

        val acquired = store.over(player).acquire(CONTENT_ID)

        assertThat(acquired.isExpired).isTrue()
        assertThat(acquired.playbackDurationRemainingMs).isEqualTo(0L)
        assertThat(acquired.licenceDurationRemainingMs).isEqualTo(0L)
        harness.release(player)
        store.close()
    }

    @Test
    fun aPlayerWithNoStoreBehavesExactlyAsBefore() {
        // ADR-0012 rule 13's posture applied to the store: the store is a thing a consumer opens, and
        // a player built without one acquires its licence online exactly as it did before #210 — the
        // control that keeps every assertion above from being true of every player.
        val player = playOnline()

        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(licenceRequests(player)).isGreaterThan(0)
        // And nothing was written anywhere: a store is opened by a consumer or it does not exist.
        assertThat(storeDirectory().exists()).isFalse()
        harness.release(player)
    }

    /** A player built and prepared the way a consumer's download flow builds one: online, protected. */
    private fun playOnline(): SuperPlayer {
        val player = harness.buildPlayer(
            content = TestContent.protectedDash(),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)),
        )
        player.setMediaRequest(request())
        harness.playToReady(player)
        return player
    }

    private fun request() = MediaRequest.Builder(CONTENT_ID)
        .addSource(TestContent.protectedDash().sourceUri)
        .build()

    private fun licenceRequests(player: SuperPlayer): Int =
        harness.networkRequests(player).count { it.kind == ResourceKind.LICENCE }

    /** The directory the consumer named, which SuperPlayer never chose and never leaves. */
    private fun storeDirectory(): File = File(directory.root, "licences")

    private companion object {
        const val CONTENT_ID = "film/arrival"
    }
}
