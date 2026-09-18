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

package com.superplayer.diagnostics

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import com.superplayer.core.MediaRequest
import com.superplayer.testkit.PlaybackHarness
import com.superplayer.testkit.TestContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * ADR-0015 rule 13: an app that opens no doctor registers and allocates nothing for one, and a test counts
 * it rather than asserting it by inspection. This is the half #286 lands; the HUD's half is #294's.
 *
 * What could be paid is registration with the platform and traffic on the network, so each is counted with
 * no doctor and again with one — and the counter is shown to see what it counts, which is the shape
 * `SuperPlayerOutputSeamTest` and `DownloadsPayNothingTest` take. A player really does register a receiver
 * when it starts playing, and a doctor really does fetch when it is asked; what neither count moves for is
 * a doctor that exists.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticsPayNothingTest {

    @get:Rule
    val harness: PlaybackHarness = PlaybackHarness()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aDoctorRegistersNothingWithThePlatformWhileAPlayerDoes() {
        val content = TestContent.hls()
        val quiet = registrations()

        val doctor = MediaSourceDoctor.Builder(context)
            .setEnvironment(harness.diagnosticEnvironment(content))
            .build()
        doctor.examine(requestFor(content))

        assertWithMessage("a doctor built, asked, and dropped").that(registrations()).isEqualTo(quiet)

        // The same counter, against a player that plays: what it counts is real, so the reading above is a
        // doctor registering nothing rather than a counter that sees nothing.
        val player = harness.buildPlayer(content = content)
        player.setMediaRequest(requestFor(content))
        harness.playToReady(player)
        assertWithMessage("a player that is playing").that(registrations()).isNotEqualTo(quiet)
        harness.release(player)
    }

    @Test
    fun nothingLeavesForTheNetworkUntilADoctorIsAsked() {
        val content = TestContent.hls()
        val environment = harness.diagnosticEnvironment(content)

        val doctor = MediaSourceDoctor.Builder(context).setEnvironment(environment).build()
        assertWithMessage("building a doctor composes no chain and opens nothing")
            .that(harness.networkRequests(environment)).isEmpty()

        doctor.examine(requestFor(content))

        assertWithMessage("and asking one fetches exactly the manifest it was asked about")
            .that(harness.networkRequests(environment)).hasSize(1)
    }

    @Test
    fun theModuleDeclaresNoComponentAndNoPermission() {
        // Nothing here is a component, a permission or a provider: a doctor is an object a consumer makes,
        // asks and drops. So the module publishes no manifest of its own and there is nothing for an app's
        // merged manifest to carry — which is what makes "adding the module to the classpath costs nothing"
        // true before any code of it runs, and is the reading `DownloadsPayNothingTest` takes of a module
        // that does need one.
        assertWithMessage("this module's own manifest").that(File("src/main/AndroidManifest.xml").exists()).isFalse()
    }

    private fun requestFor(content: TestContent): MediaRequest =
        MediaRequest.Builder(CONTENT_ID).addSource(content.sourceUri).build()

    /** Broadcast receivers and network callbacks registered with the platform, as a pair. */
    private fun registrations(): Pair<Int, Int> = Pair(
        shadowOf(context as Application).registeredReceivers.size,
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbacks.size,
    )

    private companion object {

        const val CONTENT_ID = "film/pay-nothing"
    }
}
