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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scores [HttpTransportConformance] itself, from both sides.
 *
 * ADR-0016 rule 14's conformance test is a claim about code this repository cannot see, so the two
 * ways it could be worthless are the two things asserted here.
 *
 * **It could pass everything.** A check nothing has ever failed is a check that does not work, so
 * there is one deliberately wrong transport *per obligation* — [PlatformClientTransport.Defect] —
 * and each is driven against the check that is supposed to catch it. Per obligation and not in
 * aggregate: five checks with four proofs is the easy way to half-do this, and it is the way that
 * looks finished.
 *
 * **It could describe something we do not ship.** So the suite is also run against
 * `HttpStack.default()` — through [DefaultStackTransport], the same fifty lines an adopter writes —
 * which must pass every one of the five. A contract the shipped stack cannot keep is a contract to
 * change rather than one to hold an adopter to.
 *
 * The control between the two is [PlatformClientTransport] with no defect: a transport written over
 * the platform's own client, correct, which passes. Without it, a failing check could be one that
 * fails for everything.
 */
@RunWith(AndroidJUnit4::class)
class HttpTransportConformanceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aCorrectlyWrittenTransportSatisfiesEveryObligation() {
        HttpTransportConformance(PlatformClientTransport()).verifyAll()
    }

    @Test
    fun theStackThatShipsSatisfiesEveryObligation() {
        HttpTransportConformance(DefaultStackTransport(context)).verifyAll()
    }

    @Test
    fun aTransportThatDropsTheRangeIsRefusedByRuleFive() {
        val refusal = refusalFrom(PlatformClientTransport.Defect.IGNORES_RANGE) {
            it.verifyByteRangeIsHonoured()
        }

        assertThat(refusal).contains("ADR-0016 rule 5")
        // What it did, and not merely that it failed: the reader of this message has no copy of
        // this repository and needs to be told what their client sent.
        assertThat(refusal).contains("no Range header at all")
    }

    @Test
    fun aTransportThatReportsTheRequestedUriIsRefusedByRuleSix() {
        val refusal = refusalFrom(PlatformClientTransport.Defect.REPORTS_THE_REQUESTED_URI) {
            it.verifyRedirectedUriIsReported()
        }

        assertThat(refusal).contains("ADR-0016 rule 6")
        assertThat(refusal).contains(ConformanceOrigin.REDIRECT_PATH)
        assertThat(refusal).contains(ConformanceOrigin.RESOURCE_PATH)
    }

    @Test
    fun aTransportThatNegotiatesGzipIsRefusedByRuleSeven() {
        val refusal = refusalFrom(PlatformClientTransport.Defect.NEGOTIATES_GZIP) {
            it.verifyNoContentCodingIsAdded()
        }

        assertThat(refusal).contains("ADR-0016 rule 7")
        assertThat(refusal).contains("Accept-Encoding: gzip")
    }

    @Test
    fun aTransportThatRaisesOnARefusalIsRefusedByRuleEight() {
        val refusal = refusalFrom(PlatformClientTransport.Defect.RAISES_ON_A_REFUSAL) {
            it.verifyStatusIsReportedRatherThanRaised()
        }

        assertThat(refusal).contains("ADR-0016 rule 8")
        assertThat(refusal).contains("raised java.io.IOException")
    }

    @Test
    fun aTransportWhoseCloseAbandonsNothingIsRefusedByRuleTen() {
        val refusal = refusalFrom(PlatformClientTransport.Defect.BLOCKS_PAST_CLOSE) {
            it.verifyCancelledRequestReturns()
        }

        assertThat(refusal).contains("ADR-0016 rule 10")
        assertThat(refusal).contains("left a read blocked")
    }

    /**
     * `verifyAll` stops at the first obligation broken, so one defect is enough to state that the
     * whole-suite entry point a consumer actually calls reaches the same conclusion the single
     * check does — which is the only thing tying the five together.
     */
    @Test
    fun theWholeSuiteRefusesATransportThatBreaksOneObligation() {
        val refusal = refusalFrom(PlatformClientTransport.Defect.IGNORES_RANGE) { it.verifyAll() }

        assertThat(refusal).contains("ADR-0016 rule 5")
    }

    private fun refusalFrom(
        defect: PlatformClientTransport.Defect,
        check: (HttpTransportConformance) -> Unit,
    ): String {
        val conformance = HttpTransportConformance(PlatformClientTransport(defect))
        val refusal = try {
            check(conformance)
            null
        } catch (refused: HttpTransportConformanceException) {
            refused
        }
        val message = checkNotNull(refusal) { "$defect was not refused by the check meant to catch it" }
            .message
            .orEmpty()

        // The shape every message keeps, because the whole point of the exercise is that it is read
        // by someone who is not the author of this code.
        assertThat(message).contains("What this transport did:")
        assertThat(message).contains("What the rule requires:")
        return message
    }
}
