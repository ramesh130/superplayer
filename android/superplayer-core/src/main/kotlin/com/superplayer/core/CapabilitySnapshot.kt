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
 * What this device can **do**, as a value: the capabilities this library itself branches on, read
 * once and handed over whole.
 *
 * This is ADR-0015 rule 3's second seam and the only way `superplayer-diagnostics` sees a device.
 * The shape is the rule's, and the reason for it is worth restating where it is built: the module is
 * handed *the snapshot* rather than the readers, so a field that is not here is a field a bundle
 * cannot print by accident. Adding one is a decision taken against rule 10 in the change that adds
 * it, not a line in another module.
 *
 * **What it records, and why each field is admitted** (ADR-0015 rule 10's readership test — a
 * capability is admitted because some code in this library reads it to decide something):
 *
 * - the video decoder table, which the selection gate refuses rungs against ([DeviceConstraints])
 *   and the pool bounds itself on ([concurrentPlayerCapacityOf]);
 * - the display, which the same gate re-reads per evaluation ([DisplayCapability]);
 * - the app's heap budget and whether the platform calls the device low-RAM, which bound the pool,
 *   cap `maxBufferMs` and gate the preload window;
 * - the platform API level, which this library branches on in a dozen places — the frame-rate call
 *   ADR-0014 rule 4 makes is one.
 *
 * **What it does not record, and cannot be made to** (rule 10's refusals): any `Build` string, the
 * decoder *component* name, any DRM identifier, the display's id or name, anything of the network.
 * Two capabilities rule 10 would permit are also absent, and their absence is the readership test
 * applied rather than an omission:
 *
 * - **the audio output's declared encodings.** Nothing in this library reads them: Media3's own
 *   `AudioCapabilitiesReceiver` does, inside the engine, which is what ADR-0014 rule 6 leaves to
 *   Media3. Admitting them would mean writing a platform reader for a bundle alone, which is the
 *   shape rule 10's test refuses. When something here branches on them, they come in with it.
 * - **the Widevine security level.** It is not core's to read — `superplayer-drm` asks the device —
 *   and rule 3 keeps the protection half off the seam list for that reason: what a bundle reports is
 *   [SuperPlayer.deliveredSecurityLevel], which is public and which the bundle reads as
 *   `QoeCollector` does.
 */
internal class CapabilitySnapshot(
    /** `Build.VERSION.SDK_INT`: the platform API level, and nothing else of the build. */
    val apiLevel: Int,

    /** Whether the manufacturer declared this device memory-constrained ([isLowRamDeviceOf]). */
    val lowRamDevice: Boolean,

    /**
     * The heap this app is allowed, in megabytes, or null where the platform does not say.
     *
     * Megabytes rather than bytes because that is the unit the platform reports
     * (`ActivityManager.getMemoryClass`) and the unit a reader of a bug report thinks in; the byte
     * value [heapBudgetBytesOf] answers is the same number.
     */
    val heapBudgetMb: Long?,

    /** The video decoders the platform declares, ascending by MIME type so a bundle is stable. */
    val videoDecoders: List<VideoDecoderCapability>,

    /** The display's active-mode reading, exactly as the selection gate is handed it. */
    val display: DisplayCapability,
) {

    /**
     * One video MIME type's decoding capability, pooled across the decoders that declare it exactly
     * as [DecoderTable] pools them — because "can this device play this rung" is a question about the
     * device and not about which of its components answers.
     *
     * The component name is deliberately not a field. It names a vendor and usually a chipset, and a
     * vendor beside an API level beside a display size is a device model spelled differently, which
     * is ADR-0015 rule 10's worked example of what this snapshot refuses.
     */
    class VideoDecoderCapability(
        /** The MIME type, lowercased, as the platform's codec list declares it. */
        val mimeType: String,

        /**
         * Whether a decoder for it declares `FEATURE_SecurePlayback`, or null where the device
         * declared no video decoder at all — the unknown every reading of this table keeps apart from
         * "none", for [DeviceConstraints]'s reason.
         */
        val secure: Boolean?,

        /** The largest concurrent-instance limit declared for it, or null where none was positive. */
        val maxInstances: Int?,

        /**
         * The highest profile declared for it at the highest level declared *at that profile*, or
         * null where none was declared.
         *
         * One value rather than two numbers, because the pair is the fact: a device declaring High at
         * level 4.0 and Baseline at 5.1 reaches High 4.0, and a snapshot carrying the highest of each
         * separately could report "High" beside "5.1" — a combination it does not have. The profile
         * decides first, because a profile is the feature set and a level is a bound within it.
         */
        val highestProfileLevel: DeviceConstraints.ProfileLevel?,

        /** Whether a decoder for it declares `FEATURE_TunneledPlayback` (ADR-0014 rule 7's other half). */
        val tunneling: Boolean,
    )
}

// `capabilitySnapshotOf`, the one function that builds this, is in `DeviceCapacity.kt` beside the
// decoder walk it reads. It is there rather than here so that the walk, its table and the tallies
// stay **private to that file**: this module's ninth Kotlin friend can see anything `internal`, and
// a reader it could reach directly is a reader rule 3's second seam did not hand it.
