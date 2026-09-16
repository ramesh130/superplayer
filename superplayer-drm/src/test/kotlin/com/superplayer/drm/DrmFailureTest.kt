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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.FailureCategory
import com.superplayer.core.MediaRequest
import com.superplayer.core.SuperPlayer
import com.superplayer.core.SuperPlayerError
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
import org.junit.runner.RunWith
import java.io.File

/**
 * `PRD.md` §3.2's last bullet through a real player: **no `ERROR_CODE_DRM_UNSPECIFIED` reaches the
 * UI** (#206). A protection failure nothing rescued ends the session on a named class, a message key
 * an app can write a sentence for, and an `isRetryable` that is true of the failure rather than of
 * the band it happened to land in.
 *
 * Nothing here is new machinery. The error travels the path rung 6 already built (ADR-0011 rule 10):
 * a `SuperPlayerError` as the `cause` of the `PlaybackException` the consumer already receives, with
 * the engine's own exception under it. What #206 changed is upstream of all of that and in one file
 * — which is the point of the last test in this class, and of `ErrorClassifierTest` being where the
 * mapping itself is asserted, code by code. This class asserts only that the classification arrives,
 * through a player built the way a consumer builds one.
 *
 * Two forcing shapes, because a protection failure has two quite different origins: a licence server
 * that will not issue, which is a transfer over the one chain, and a device that cannot be
 * provisioned, which never becomes a transfer at all. A classifier that read only the transport
 * would name the first and miss the second.
 *
 * Neither of them *forces* #206, and that is stated rather than left to be assumed: both shapes
 * carry a `PlaybackException` whose DRM code Media3 had already narrowed, so both classified the same
 * way before this change as after it. What they are is the regression guard for the path — the class
 * reaching a consumer at all — while the codes #206 actually re-routed are the ones no fake device
 * here can raise, and they are asserted where the mapping is.
 *
 * What is not forced here is the leaf `PRD.md` names by title — a downloaded asset whose licence has
 * expired. `FakeExoMediaDrm` has no expiry to reach and this module has no offline store yet (that
 * is ADR-0012's other half), so `Drm.LicenceExpired` is asserted in `ErrorClassifierTest` off the
 * code Media3 assigns a `KeysExpiredException`, and this file says so rather than leaving the gap to
 * be inferred.
 */
@RunWith(AndroidJUnit4::class)
class DrmFailureTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    @Test
    fun aLicenceNoServerWillIssueEndsTheSessionOnANamedProtectionFailure() {
        val player = playUntilItFails(
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, kind = ResourceKind.LICENCE)
                .build(),
        )

        val typed = typedErrorOf(player)
        // The class, and not the band: a consumer switching on this string is told which
        // conversation to have, which "a DRM error happened" never was.
        assertThat(typed.causeClass).isEqualTo(FailureClass.Drm.LicenceAcquisition.stableName)
        assertThat(typed.userMessageKey).isEqualTo(FailureClass.DRM_MESSAGE_KEY)
        assertThat(typed.category).isEqualTo(FailureCategory.DRM)
        // A licence that never arrived may arrive on the next ask, which is the one thing that
        // separates this leaf from `Drm.LicenceExpired`.
        assertThat(typed.isRetryable).isTrue()
        // And the engine's own account is underneath rather than replaced: what Media3 raised and
        // what SuperPlayer made of it are two facts, and a bug report wants both.
        assertThat(typed.cause).isNotNull()
        assertThat(player.playerError?.errorCodeName).isNotNull()
    }

    @Test
    fun aDeviceTheProvisioningServiceRefusesIsNamedAsThatAndNotAsALicenceFailure() {
        // Never a transfer: the device asks to be provisioned and is turned down, so nothing the
        // licence transport can see happened. Media3 says which round trip it dispatched and the
        // classification reads that, which is why the two shapes come out differently here.
        DeviceStatement.declareWidevineProvisioningFailure()
        val player = playUntilItFails()

        assertThat(typedErrorOf(player).causeClass).isEqualTo(FailureClass.Drm.Provisioning.stableName)
    }

    @Test
    fun aPlayerBuiltWithNoResilienceStillFailsAndSimplyNamesNothing() {
        // ADR-0011 rule 14 for this ticket's half: the classification is `superplayer-resilience`'s,
        // and a consumer who took `superplayer-drm` alone pays nothing for it and gets nothing from
        // it. The session still ends, and it ends exactly as it did before Phase 5.
        val content = TestContent.protectedDash()
        val player = harness.buildPlayer(
            content = content,
            faults = FaultScript.Builder()
                .failWithHttpStatus(FaultScript.HTTP_SERVER_ERROR, kind = ResourceKind.LICENCE)
                .build(),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        harness.playToFailure(player)

        assertThat(player.playerError).isNotNull()
        assertThat(player.playerError?.cause).isNotInstanceOf(SuperPlayerError::class.java)
        assertThat(player.classify(player.playerError!!)).isNull()
    }

    @Test
    fun thisModuleKeepsNoTaxonomyOfItsOwn() {
        // ADR-0012's "the module classifies nothing and instead raises the evidence", checked against
        // the source tree rather than trusted — the same mechanism `FallbackRungCoverageTest` and
        // `superplayer-abr`'s `NoDeviceModelStringTest` use, and for the same reason: some facts
        // about a repository are facts about its files. A second `when` over an error code is the
        // thing ADR-0011 rule 1 forbids and the thing that would quietly reappear here first.
        val forbidden = listOf("ERROR_CODE_DRM", "FailureClass", "FailureCategory", "errorCode")
        File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.forEach { source ->
            val text = source.readText()
            forbidden.forEach { token ->
                assertWithMessage("${source.path} names $token").that(text).doesNotContain(token)
            }
        }
    }

    /** A player built as a consumer builds one, played until the protection failure ends it. */
    private fun playUntilItFails(faults: FaultScript = FaultScript.NONE): SuperPlayer {
        val content = TestContent.protectedDash()
        val player = harness.buildPlayer(
            content = content,
            faults = faults,
            // The classification and the typed error are both this module's — `setResilience` beside
            // `setDrm` is what a consumer who wants either writes, and #205 made it the pairing that
            // puts a licence on the one chain in the first place.
            resilience = Resilience.standard(),
            drm = Drm.widevine(WidevineConfig(FakeLicenceServer.LICENCE_URI)),
        )
        player.setMediaRequest(MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build())
        harness.playToFailure(player)
        return player
    }

    /** The typed error rung 6 delivered, where a consumer already looks for a cause. */
    private fun typedErrorOf(player: SuperPlayer): SuperPlayerError {
        val error = player.playerError
        assertThat(error).isNotNull()
        assertThat(error?.cause).isInstanceOf(SuperPlayerError::class.java)
        return error?.cause as SuperPlayerError
    }

    private companion object {
        const val CONTENT_ID = "series/expanse/s01e04"
    }
}
