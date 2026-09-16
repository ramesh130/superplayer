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

package com.superplayer.resilience

import com.superplayer.core.FailureCategory

/**
 * The rungs of the fallback ladder, in the order they are climbed (ADR-0011 rule 7).
 *
 * Declaration order *is* the ladder order, so a rung compares against another with the ordering an
 * enum already has and no second table says which is higher. A rung is reached only when the one
 * below it has failed, and the order is fixed: no content wants its variant excluded before its host
 * is tried.
 *
 * A type rather than a number because [FailureClass.rungCeiling] is read by everything that acts on
 * a failure, and "3" at such a call site says nothing about which rung 3 is.
 */
public enum class FallbackRung {

    /** Rung 1 — the same URL again, after a backoff. Worth nothing unless the failure is transient. */
    RETRY_SAME_URL,

    /** Rung 2 — another CDN host or DASH `BaseURL`, where the content names one. */
    NEXT_HOST,

    /** Rung 3 — the failing variant excluded, playback continuing at another bitrate. */
    EXCLUDE_VARIANT,

    /** Rung 4 — the next entry of `MediaRequest.sources`, re-adopted at the position reached. */
    NEXT_SOURCE,

    /** Rung 5 — the decoder recreated, the player re-prepared at the position it held. */
    RECREATE_DECODER,

    /** Rung 6 — a typed, actionable error, which every failure reaches if nothing below it worked. */
    TYPED_ERROR,
}

/**
 * What a failure *is*, in SuperPlayer's own vocabulary: the taxonomy `ErrorClassifier` assigns and
 * the only one in the library (ADR-0011 rule 1, `PRD.md` §3.3).
 *
 * Public, sealed, and free of Media3 types. The evidence a class is derived from is Media3's — an
 * error code, an HTTP status, the kind of load that failed — and all of it stays behind
 * [ErrorClassifier.classify]; what leaves this module is the conclusion. A consumer reads a class off
 * the typed error a failed session ends with, a data pipeline groups by [stableName], and the rest of
 * Phase 5 reads [retryable] and [rungCeiling] instead of branching on an error code of its own.
 *
 * Each class answers the three questions rule 1 requires of it, and nothing else:
 *
 * - [retryable] — whether rung 1, the same bytes again, can change the outcome. It is the whole of
 *   what a class says about rung 1: a failure that is not retryable is not retried, however much
 *   budget the decision in force allows.
 * - [rungCeiling] — the highest rung the ladder may climb for this class. It is a *ceiling*, not an
 *   itinerary: which rungs below it are worth attempting is the ladder's to decide (#181), which is
 *   how a [Device.DecoderTransient] reaches rung 5 without trying a host. A ceiling of
 *   [FallbackRung.TYPED_ERROR] means no remedy is attempted at all.
 * - [stableName] — the string a log line, a bug report and a warehouse row all carry. Written out
 *   rather than derived from the class name, so that renaming a Kotlin type is not a silent schema
 *   change, and so that it survives an engine that renumbers its codes.
 *
 * [category] is rule 3's one-to-one table: telemetry's coarse [FailureCategory] derived from the
 * class rather than kept as a second taxonomy. Two of that enum's buckets have no row here, and both
 * absences are deliberate — `RENDERER`, because the renderer band's failures are failures of the
 * device's output pipeline whose remedies are the decoder rungs, and `UNKNOWN`, because rule 2
 * leaves nothing unclassified to put in it.
 *
 * [userMessageKey] is the other derived column, and it is `PRD.md` §3.2's rather than rule 3's: what
 * a viewer is told. It is here for the reason [category] is — a mapping from a class kept anywhere
 * else is the second table rule 1 forbids — and it is deliberately coarser than [stableName],
 * because the right sentence to put on a screen is the same for several of these classes and there
 * are fewer useful things to say to a viewer than there are failures.
 */
public sealed class FailureClass(
    /** The name this class is known by outside the process. */
    public val stableName: String,
    /** Whether retrying the same bytes can change the outcome. */
    public val retryable: Boolean,
    /** The highest rung the ladder may climb for this class. */
    public val rungCeiling: FallbackRung,
    /** The coarse bucket telemetry reports, derived here and nowhere else (ADR-0011 rule 3). */
    public val category: FailureCategory,
    /**
     * The key an app looks a viewer-facing string up by, carried out on
     * [com.superplayer.core.SuperPlayerError] (`PRD.md` §3.2).
     *
     * A key and never a message: the library has no locale and no tone of voice, and a string it
     * invented would be the one thing an app cannot translate. Named for what a viewer is being told
     * rather than for the class, because that is what an app switches on.
     */
    public val userMessageKey: String,
) {

    final override fun toString(): String = stableName

    /**
     * The bytes did not arrive, and asking again may be all it takes.
     *
     * Both leaves reach rung 4: a transfer that failed may succeed against another host, another
     * variant is another object at the same edge, and another source is the last thing left to try.
     * Neither reaches rung 5, because a decoder is not implicated in bytes that never got to one.
     */
    public sealed class Transient(
        stableName: String,
        rungCeiling: FallbackRung,
    ) : FailureClass(stableName, retryable = true, rungCeiling, FailureCategory.NETWORK, NETWORK_MESSAGE_KEY) {

        /** A connection, a timeout, a DNS failure, a status with nothing more specific behind it. */
        public object Network : Transient("Transient.Network", FallbackRung.NEXT_SOURCE)

        /**
         * A segment the edge refused or no longer holds while the manifest is fine — the classic
         * expired token or edge miss (`PRD.md` §3.3), and the frozen copy an intermediary is serving
         * when core's [com.superplayer.core.StaleLivePlaylistException] names one.
         *
         * Retryable, because the retry is not the same request: the header-refresh layer re-invokes
         * the request's provider first, so an expired token is a repaired request rather than the
         * same 403 again (ADR-0011 rule 12).
         */
        public object CdnEdge : Transient("Transient.CdnEdge", FallbackRung.NEXT_SOURCE)
    }

    /**
     * The bytes arrived and are not what they were described as.
     *
     * Never retryable: the same bytes parse the same way, and a budget spent on them is a budget the
     * rungs above do not get. Both leaves reach rung 4, because a description or a publication
     * defect at one host or in one source can be absent from another.
     */
    public sealed class Content(
        stableName: String,
    ) : FailureClass(stableName, retryable = false, FallbackRung.NEXT_SOURCE, FailureCategory.SOURCE, CONTENT_MESSAGE_KEY) {

        /**
         * The manifest or playlist cannot be acted on: malformed, or well-formed and describing
         * something no player can sit inside — which is what core's
         * [com.superplayer.core.LiveWindowTooShortException] names.
         */
        public object ManifestInvalid : Content("Content.ManifestInvalid")

        /**
         * The media the manifest promised is not there as promised: a segment missing, shorter than
         * its description, unplayable where playable media should be, or never published at all —
         * which is what a live playlist frozen at the origin leaves behind.
         */
        public object SegmentGap : Content("Content.SegmentGap")
    }

    /**
     * This device could not play it, which is a different question from whether the content is
     * playable.
     *
     * Neither leaf is retryable — nothing about the transfer failed — and the two differ in exactly
     * the thing the ladder needs: whether recreating the decoder can help.
     */
    public sealed class Device(
        stableName: String,
        rungCeiling: FallbackRung,
    ) : FailureClass(stableName, retryable = false, rungCeiling, FailureCategory.DECODER, DEVICE_MESSAGE_KEY) {

        /**
         * No decoder or output could be initialised for this rung: none exists, none could be
         * queried, or the format is past what the device lists. A recreate of a decoder that was
         * never there changes nothing; another variant or another source may be within reach, so
         * the ceiling is rung 4.
         */
        public object DecoderInit : Device("Device.DecoderInit", FallbackRung.NEXT_SOURCE)

        /**
         * A decoder or output that was working stopped, or one could not be had for a moment — the
         * surface went away, an HDMI event landed, every instance on the device was held. Rung 5 is
         * the remedy and the ceiling; the ladder reaches it without trying a host, because no host
         * has anything to do with it.
         */
        public object DecoderTransient : Device("Device.DecoderTransient", FallbackRung.RECREATE_DECODER)
    }

    /**
     * Protection, not delivery.
     *
     * The branch exists so Phase 6 has somewhere to land (ADR-0011 rule 6) and no DRM is plumbed
     * here. Its leaves are not placeholders, though: Media3's DRM band is delivered to players today
     * and rule 2 leaves nothing unclassified, so each leaf is a code the engine can already raise.
     * Phase 6 adds a leaf in a change that says why, rather than stretching one of these.
     */
    public sealed class Drm(
        stableName: String,
        retryable: Boolean,
        rungCeiling: FallbackRung,
    ) : FailureClass(stableName, retryable, rungCeiling, FailureCategory.DRM, DRM_MESSAGE_KEY) {

        /**
         * The device could not be provisioned. A server-side operation between the device and the
         * provisioning service that a later attempt commonly succeeds at, and that no other host,
         * variant or source has any bearing on — so rung 1 is the ceiling as well as the remedy.
         */
        public object Provisioning : Drm("Drm.Provisioning", retryable = true, FallbackRung.RETRY_SAME_URL)

        /** A licence could not be acquired or has expired; re-acquiring is the remedy, and the only one. */
        public object LicenceAcquisition :
            Drm("Drm.LicenceAcquisition", retryable = true, FallbackRung.RETRY_SAME_URL)

        /**
         * The scheme, the device or the operation is refused: an unsupported key system, a revoked
         * device, a disallowed operation, content whose protection data is wrong. Another source may
         * be protected differently, so the ceiling is rung 4 rather than the error at once.
         */
        public object Unsupported : Drm("Drm.Unsupported", retryable = false, FallbackRung.NEXT_SOURCE)
    }

    /**
     * The engine has named the content unsupported, and nothing the ladder does changes that.
     *
     * Reserved for exactly that — a code that says *unsupported* — and never a bucket for a failure
     * the mapping did not foresee (ADR-0011 rule 2). A ceiling of rung 6 is what "goes to rung 6 at
     * once" means: no remedy is attempted, and the consumer gets a typed error naming this class.
     */
    public sealed class Fatal(
        stableName: String,
    ) : FailureClass(stableName, retryable = false, FallbackRung.TYPED_ERROR, FailureCategory.SOURCE, UNSUPPORTED_MESSAGE_KEY) {

        /** A container, a manifest or a format the engine says it does not support. */
        public object Unsupported : Fatal("Fatal.Unsupported")
    }

    /**
     * The five things there are to say to a viewer, which is fewer than there are classes and is the
     * point of [userMessageKey] being its own column.
     *
     * Named for the *message* rather than for the branch that carries it — `superplayer_error_` and
     * then what the viewer is being told — because an app writing its strings file reads these names
     * and nothing else of this taxonomy. A class added later takes an existing key unless it deserves
     * a sentence none of these says, which is a decision to argue in the change that adds it.
     */
    public companion object {

        /** The bytes did not arrive: something to try again, and possibly the viewer's own link. */
        public const val NETWORK_MESSAGE_KEY: String = "superplayer_error_network"

        /** The bytes arrived and are wrong: this programme is broken, and retrying will not mend it. */
        public const val CONTENT_MESSAGE_KEY: String = "superplayer_error_content_unavailable"

        /** This device could not play it, which is a different sentence from the content being bad. */
        public const val DEVICE_MESSAGE_KEY: String = "superplayer_error_device"

        /** Protection, not delivery: the sentence an app words around its own entitlements. */
        public const val DRM_MESSAGE_KEY: String = "superplayer_error_protected_content"

        /** The engine has named the content unplayable here: the one message that offers no remedy. */
        public const val UNSUPPORTED_MESSAGE_KEY: String = "superplayer_error_unsupported"
    }
}
