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

package com.superplayer.realtime

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.superplayer.core.RealtimeTrack
import com.superplayer.core.UnsupportedRealtimeCodecException
import java.util.Locale

/**
 * The one table from a codec string to a Media3 `Format`.
 *
 * Two vocabularies reach it, because the two realtime transports this phase is built for name their
 * codecs differently and neither should keep a mapping of its own:
 *
 * - **RFC 6381 codecs strings** — `avc1.640028`, `avc3.42c01e`, `hev1.1.6.L93.B0`, `vp09.00.10.08`,
 *   `av01.0.04M.08`, `mp4a.40.2`, `opus`. This is what a MoQ catalog's `codec` field carries and
 *   what WebCodecs calls a codec string.
 * - **SDP encoding names** — `H264`, `H265`, `VP9`, `AV1`, `opus`, `MPEG4-GENERIC`. This is what a
 *   WHEP answer's `a=rtpmap` lines carry.
 *
 * spec: RFC 6381 §3.3 — the `codecs` parameter, its fourcc-prefixed grammar and the per-codec
 *   registrations it defers to. https://www.rfc-editor.org/rfc/rfc6381#section-3.3
 * spec: RFC 4566 §6 and RFC 4855 §3 — SDP's `rtpmap` encoding names, matched **case-insensitively**,
 *   which is why every token is lowercased before it is looked up.
 *
 * What it answers is a Media3 `Format`, which is `@UnstableApi` vocabulary ADR-0001 rule 2 keeps out
 * of public API, so the table is `internal` and what the public surface carries is the refusal —
 * [UnsupportedRealtimeCodecException], which is core's.
 */
internal object RealtimeFormats {

    /**
     * [codec] as a Media3 `Format`, or [UnsupportedRealtimeCodecException] for a string nothing here
     * maps.
     *
     * Pure: it reads no device, opens no decoder and touches nothing but the string. What it does
     * *not* do is ask whether this device can decode the result — that is `DeviceConstraints`' and
     * the selection gate's, which re-read the profile and level off the `codecs` string this sets
     * (a Media3 `Format` has no field of its own for them); see [profileAndLevelOf] for what that
     * gate can and cannot see.
     *
     * @throws UnsupportedRealtimeCodecException if [codec] is neither a mapped RFC 6381 string nor a
     *   mapped SDP encoding name, or is a mapped one whose parameters are malformed.
     */
    fun formatFor(codec: String, codedSize: RealtimeTrack.CodedSize?): Format {
        val resolved = resolve(codec) ?: throw UnsupportedRealtimeCodecException(codec)

        val builder = Format.Builder().setSampleMimeType(resolved.mimeType)

        // Set only when the transport stated a size, and necessarily as a pair — `CodedSize`
        // cannot be half-built, so there is no combination to branch on here. Left alone otherwise:
        // `Format.Builder` already defaults both to `Format.NO_VALUE`, so an unstated size writes
        // nothing rather than writing a placeholder that would read as a claim.
        codedSize?.let { builder.setWidth(it.width).setHeight(it.height) }
        if (resolved.token !in SDP_ENCODING_NAMES) {
            // `Format.codecs` is RFC 6381's field, so only an RFC 6381 string goes in it. An SDP
            // encoding name is left out rather than written there under a spelling nothing reads:
            // SDP states a profile in `a=fmtp` parameters, which this seam does not carry, so there
            // is genuinely nothing to say and saying it wrongly would be worse than silence.
            builder.setCodecs(codec)
        }
        if (resolved.token in CARRIES_PROFILE_AND_LEVEL && profileAndLevelOf(codec) == null) {
            // The extraction read as a refusal: on Android a level the device's decoder does not
            // reach is refused rather than degraded, `DeviceConstraints` reads the decoder table for
            // exactly that, and the selection gate asks for the pair through
            // `MediaCodecUtil.getCodecProfileAndLevel`. A string of a mapped family whose parameters
            // do not parse would reach that gate as an unknown, which refuses nothing — the right
            // direction for a constraint and the wrong one for bytes that arrived mislabelled.
            throw UnsupportedRealtimeCodecException(codec)
        }
        return builder.build()
    }

    /**
     * The profile and level [codec] states, as `MediaCodecInfo.CodecProfileLevel` constants, or null
     * where the string states none or is malformed for its own family.
     *
     * Media3's own reader rather than a second parser: the pair this answers is the pair the
     * selection gate asks for the same way, so the two cannot disagree about what a string says.
     * The repository has no RFC 6381 parser of its own and this deliberately does not add one.
     *
     * **What the gate then does with it is Media3's**, and there is one gap worth stating rather
     * than discovering: Media3 1.11's reader dispatches on `avc1` and `avc2` and not on `avc3`, so
     * although the pair *is* extracted here for an `avc3` string — through [READABLE_SPELLINGS] —
     * the gate re-reading the `Format` will find none, and a level a device's decoder cannot reach
     * goes unrefused for a self-describing H.264 stream. Rewriting the fourcc in `Format.codecs` to
     * close that would be writing a sample entry name the transport did not send into the field
     * that states one, and the honest fix is in Media3's reader.
     */
    fun profileAndLevelOf(codec: String): Pair<Int, Int>? {
        val resolved = resolve(codec) ?: return null
        val readable = READABLE_SPELLINGS[resolved.token]?.let { it + codec.substring(resolved.token.length) } ?: codec
        val probe = Format.Builder().setSampleMimeType(resolved.mimeType).setCodecs(readable).build()
        val pair = MediaCodecUtil.getCodecProfileAndLevel(probe) ?: return null
        return pair.first to pair.second
    }

    /**
     * Every token this table answers for, `mp4a` included.
     *
     * Exposed for one reader, and it is a test: `superplayer-testkit`'s `FrameSourceConformance`
     * tells a transport author which codec families to publish, and cannot read this table — that
     * module is phase 2 and may name nothing later than core, so it keeps a list of the same names
     * (#347). `ScriptedFrameSourceConformanceTest` holds the two together, and holds them to *this*
     * rather than to a third copy, so a family added below and nowhere else fails the build instead
     * of becoming a codec SuperPlayer plays and the conformance suite refuses.
     */
    val mappedTokens: Set<String> get() = MIME_TYPES.keys + "mp4a"

    /** A codec string this table answers for: the token it was looked up under, and what it decodes as. */
    private class Resolved(val token: String, val mimeType: String)

    /**
     * [codec] looked up in the one table, or null for a string nothing here maps.
     *
     * The single place a codec string becomes a token and a MIME type, so that a family added later
     * — or another fourcc that, like `mp4a`, cannot be mapped by its first element alone — is added
     * once rather than in each of the two functions above.
     */
    private fun resolve(codec: String): Resolved? {
        // RFC 6381 §3.3: the codecs string is dot-separated and its first element is the fourcc (a
        // sample entry name). An SDP encoding name carries no dot, so the same take answers both —
        // `MPEG4-GENERIC` included, whose hyphen is part of the name rather than a separator.
        val token = codec.substringBefore('.').lowercase(Locale.ROOT)
        val mimeType = MIME_TYPES[token] ?: aacMimeType(codec, token) ?: return null
        return Resolved(token, mimeType)
    }

    /**
     * `audio/mp4a-latm` for an `mp4a` string whose object type indication is one of AAC's, null for
     * any other `mp4a` string and for any other token.
     *
     * `mp4a` is the one fourcc that cannot be mapped by its first element alone: RFC 6381 §3.3 puts
     * an MP4 Registration Authority object type indication in the second element, and `mp4a.69` and
     * `mp4a.6B` are **MP3**. Mapping the whole of `mp4a` to AAC would be this file's own headline
     * defect — a codec silently decoded as a different one.
     *
     * spec: RFC 6381 §3.3, and the MP4RA object type indications it points at.
     *   https://mp4ra.org/registered-types/object-types — `0x40` MPEG-4 Audio (ISO/IEC 14496-3) and
     *   `0x67` MPEG-2 AAC LC (ISO/IEC 13818-7), which are AAC's two; the audio object type in the
     *   third element then separates AAC-LC from HE-AAC, and is read by the profile extraction.
     */
    private fun aacMimeType(codec: String, token: String): String? {
        if (token != "mp4a") return null
        // Hexadecimal without a prefix, per the registration; an absent or unreadable one is a
        // refusal rather than a default, for the reason the whole file exists.
        val objectType = codec.split('.').getOrNull(1)?.toIntOrNull(16) ?: return null
        return if (objectType == OBJECT_TYPE_MPEG4_AUDIO || objectType == OBJECT_TYPE_MPEG2_AAC_LC) MimeTypes.AUDIO_AAC else null
    }

    /** MP4RA object type indication `0x40`, MPEG-4 Audio — what every realtime publisher sending AAC writes. */
    private const val OBJECT_TYPE_MPEG4_AUDIO = 0x40

    /**
     * MP4RA object type indication `0x67`, MPEG-2 AAC LC. Mapped beside `0x40` because it is the
     * same decoder and the same MIME type in Media3's own table, and because refusing it would be
     * refusing AAC for the spelling rather than for the codec.
     */
    private const val OBJECT_TYPE_MPEG2_AAC_LC = 0x67

    /**
     * Every token this library maps, lowercase, in both vocabularies. `mp4a` is absent on purpose —
     * see [aacMimeType].
     *
     * The fourccs are the ones the transports actually publish. `avc2` and `avc4` are registered and
     * are deliberately not here: nothing observed emits them, and a fourcc carries ADR-0018 rule 4's
     * codec-specific-data obligation, so admitting one nothing sends would add a branch of that rule
     * that no transport and no test ever exercises.
     */
    private val MIME_TYPES: Map<String, String> = mapOf(
        // H.264. spec: RFC 6381 §3.3 and ISO/IEC 14496-15 Annex A — `avcN.PPCCLL`, six hex digits of
        // profile_idc, constraint flags and level_idc. `avc1` and `avc3` are one entry because they
        // are one codec to a decoder: the sample entry name decides only whether parameter sets are
        // carried out of band, which is ADR-0018 rule 4's subject and not a decoder's.
        "avc1" to MimeTypes.VIDEO_H264,
        "avc3" to MimeTypes.VIDEO_H264,
        // H.265. spec: ISO/IEC 14496-15 Annex E.3 — `hvc1.A.B.C.LXX.…`, the dot-separated general
        // profile space, profile, compatibility flags, tier and level. `hvc1` and `hev1` pair as
        // `avc1` and `avc3` do, for the same reason.
        "hvc1" to MimeTypes.VIDEO_H265,
        "hev1" to MimeTypes.VIDEO_H265,
        // VP9. spec: "VP Codec ISO Media File Format Binding", Codecs Parameter String —
        // `vp09.PP.LL.DD…`, profile, level and bit depth.
        // https://www.webmproject.org/vp9/mp4/#codecs-parameter-string
        "vp09" to MimeTypes.VIDEO_VP9,
        // AV1. spec: "AV1 Codec ISO Media File Format Binding" v1.2.0 §5, Codecs Parameter String —
        // `av01.P.LLT.DD…`, profile, level, tier and bit depth.
        // https://aomediacodec.github.io/av1-isobmff/#codecsparam
        "av01" to MimeTypes.VIDEO_AV1,
        // Opus, in both vocabularies at once: the ISOBMFF binding's codecs string and SDP's encoding
        // name are the same token, and neither carries a profile or a level — Opus has none.
        // spec: "Encapsulation of Opus in ISO Base Media File Format" §4.3 (the `opus` codecs
        //   parameter), and RFC 7587 §7 (the `opus` RTP payload's media type name).
        "opus" to MimeTypes.AUDIO_OPUS,
        // SDP encoding names. Each is the media subtype its RTP payload format registers, and none
        // carries a profile or a level in the name itself — those live in `a=fmtp` parameters, which
        // this seam does not carry.
        "h264" to MimeTypes.VIDEO_H264, // spec: RFC 6184 §8.1 — media type `video/H264`.
        "h265" to MimeTypes.VIDEO_H265, // spec: RFC 7798 §7.1 — media type `video/H265`.
        "vp9" to MimeTypes.VIDEO_VP9, // spec: RFC 9628 §5 — media type `video/VP9`.
        "av1" to MimeTypes.VIDEO_AV1, // spec: "RTP Payload Format For AV1" v1.0 §7 — media type `video/AV1`.
        "mpeg4-generic" to MimeTypes.AUDIO_AAC, // spec: RFC 3640 §4.1 — `audio/mpeg4-generic`, which for realtime audio is AAC.
    )

    /** Which of [MIME_TYPES]' tokens arrived as SDP encoding names; see [formatFor] on why it matters. */
    private val SDP_ENCODING_NAMES: Set<String> = setOf("h264", "h265", "vp9", "av1", "mpeg4-generic")

    /**
     * The tokens whose grammar states a profile and a level, so that a string of that family which
     * does not parse is a refusal rather than a `Format` that says less than the transport did.
     *
     * `opus` is absent because Opus defines neither, and the SDP names are absent because a bare
     * encoding name states neither — in both cases there is nothing to fail to extract.
     */
    private val CARRIES_PROFILE_AND_LEVEL: Set<String> = setOf("avc1", "avc3", "hvc1", "hev1", "vp09", "av01", "mp4a")

    /**
     * Fourccs whose parameters Media3's reader does not dispatch on, mapped to the fourcc it does —
     * for the **probe only**, never for the `Format` the player is given.
     *
     * Media3 1.11 reads `avc1` and `avc2` and not `avc3`, so the identical six hex digits of an
     * `avc3` string would otherwise extract as nothing. The substitution is a lookup rather than a
     * guess: ISO/IEC 14496-15 Annex A gives every `avcN` sample entry the same `PPCCLL` parameters
     * off the same `AVCDecoderConfigurationRecord`, and the entry name says only where parameter
     * sets live. `Format.codecs` keeps the fourcc the transport sent, because that is what ADR-0018
     * rule 4 keys codec-specific data on and what #345 reads.
     */
    private val READABLE_SPELLINGS: Map<String, String> = mapOf("avc3" to "avc1")
}
