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

package com.superplayer.abr

import com.superplayer.core.CellularGeneration
import com.superplayer.core.NetworkTransport

/**
 * The throughput a transport starts from when nothing recent has been measured on it in this
 * process — ADR-0009 rule 9's table, one value per transport and per cellular generation.
 *
 * SuperPlayer's own rather than a copy of Media3's per-country table, and every value is a **cold
 * default rather than a prediction**: its one job is to choose a first rendition that will not
 * stall, and the first sample replaces it within one segment. Guessing low costs one segment of
 * quality; guessing high costs a rebuffer at the start of the session, which is the failure F1
 * names and the trade `PRD.md` §3.1 makes explicitly. So each value sits well below the published
 * median for its transport — roughly a fifth, rounded to a number a ladder has a rung near — and
 * the fraction is a judgment rather than a measurement. The measurement that would revise it is
 * the benchmark's cold-start cells (`benchmark/README.md`), and rule 9 names that as the trigger
 * for the consumer-stored memory too.
 *
 * The published medians cited are global figures from indices that are updated monthly, so the
 * numbers in the citations are the ones read when this table was written; the `// ref:` is to the
 * index, which is where a reader checks them against today's.
 */
internal object ColdDefaults {

    /** The cold default for [transport], in bits per second. */
    fun bpsFor(transport: NetworkTransport): Long = when (transport) {
        NetworkTransport.Wifi -> WIFI_BPS

        NetworkTransport.Ethernet -> ETHERNET_BPS

        is NetworkTransport.Cellular -> when (transport.generation) {
            CellularGeneration.NR -> CELLULAR_NR_BPS
            CellularGeneration.LTE -> CELLULAR_LTE_BPS
            CellularGeneration.OLDER -> CELLULAR_OLDER_BPS
            null -> CELLULAR_UNKNOWN_GENERATION_BPS
        }

        NetworkTransport.Unknown -> UNKNOWN_BPS
    }

    /**
     * 5 Mbit/s. WiFi is a share of a fixed line whose global median download is well over
     * 90 Mbit/s, but the share is unknowable — a congested café and a home fibre line are both
     * WiFi — and a fifth of the *mobile* median is the conservative reading of "some WiFi".
     * ref: Ookla, *Speedtest Global Index*, median fixed broadband and mobile download speeds:
     * https://www.speedtest.net/global-index
     */
    const val WIFI_BPS: Long = 5_000_000L

    /**
     * 10 Mbit/s. A wired link is the one transport with no radio between the device and the
     * line, so it starts from twice WiFi's value and still far below the fixed-line median above.
     * ref: Ookla, *Speedtest Global Index*, median fixed broadband download:
     * https://www.speedtest.net/global-index
     */
    const val ETHERNET_BPS: Long = 10_000_000L

    /**
     * 8 Mbit/s. 5G's measured download experience is several times LTE's in every market
     * Opensignal reports, and a fifth of a global median in the tens of Mbit/s is this.
     * ref: Opensignal, *5G Global Mobile Network Experience* reports, 5G download speed:
     * https://www.opensignal.com/reports
     */
    const val CELLULAR_NR_BPS: Long = 8_000_000L

    /**
     * 4 Mbit/s. A fifth of a global 4G download experience that Opensignal reports in the low
     * tens of Mbit/s. ref: Opensignal, *Mobile Network Experience* reports, 4G download speed:
     * https://www.opensignal.com/reports
     */
    const val CELLULAR_LTE_BPS: Long = 4_000_000L

    /**
     * 400 kbit/s. 3G download experience is reported in the low single Mbit/s and 2G is far below
     * it; the two are one value here because a policy treats both as "slow", and the bottom rung
     * of a ladder is the only rendition either can carry. ref: Opensignal, *Mobile Network
     * Experience* reports, 3G download speed: https://www.opensignal.com/reports
     */
    const val CELLULAR_OLDER_BPS: Long = 400_000L

    /**
     * LTE's value. The generation is unobserved when the app lacks `READ_PHONE_STATE`, which is
     * most apps; 4G and 5G together are the large majority of subscriptions worldwide, so a
     * cellular network of unknown generation is far more likely LTE-or-better than older, and LTE's
     * default is the conservative one of the two. ref: Ericsson, *Mobility Report*, subscriptions by
     * technology: https://www.ericsson.com/en/reports-and-papers/mobility-report
     */
    const val CELLULAR_UNKNOWN_GENERATION_BPS: Long = CELLULAR_LTE_BPS

    /**
     * 1 Mbit/s. A transport SuperPlayer does not classify — a VPN over an unknown carrier,
     * Bluetooth tethering, USB — or no network at all. Nothing is known, so the value is the one
     * every ladder has a rung at or below: the bottom rung of the HLS authoring specification's
     * recommended ladders is under 1 Mbit/s. ref: Apple, *HLS Authoring Specification for Apple
     * Devices*, video encoding requirements — the lowest recommended tiers:
     * https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices
     */
    const val UNKNOWN_BPS: Long = 1_000_000L
}
