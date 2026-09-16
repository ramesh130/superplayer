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

package com.superplayer.testmedia

import java.io.ByteArrayOutputStream

/**
 * The protection data a Widevine stream declares: one key id, and the `pssh` box that carries it.
 *
 * ## What is protected here, and what is not
 *
 * The **declaration** is real and the **bytes are not encrypted**. What a protected stream has to do
 * for a test of licence acquisition is make the player ask for a licence before it will hand a sample
 * to a renderer, and that follows entirely from the manifest: a representation that declares content
 * protection produces a format carrying `DrmInitData`, and a format carrying `DrmInitData` is one no
 * sample is read from until a session holds keys. Encrypting the samples as well would add a
 * decryption step that nothing in a Robolectric test can perform — there is no `MediaCrypto`, and
 * Media3's fake renderers decode nothing — so it would turn a stream that exercises the licence round
 * trip into one that cannot play at all.
 *
 * So `SyntheticHlsStream.protectedResources` and `SyntheticDashStream.protectedResources` serve the
 * same media as their unprotected forms, under manifests that declare this protection. The
 * distinction is stated here rather than left for a reader to infer from a stream that plays.
 *
 * ## The system id
 *
 * ref: DASH-IF, *Content Protection Identifiers* — Widevine's DRM system id is
 * `edef8ba9-79d6-4ace-a3c8-27dcd51d21ed`: https://dashif.org/identifiers/content_protection/
 * It is the identifier both protocols name — DASH in a `ContentProtection@schemeIdUri` of
 * `urn:uuid:…` (// spec: ISO/IEC 23009-1 §5.8.4.1) and HLS in an `EXT-X-KEY`'s `KEYFORMAT`
 * (// spec: RFC 8216 §4.3.2.4) — which is what makes one `pssh` box serve both streams.
 */
public object WidevineProtection {

    /** The Widevine DRM system id, in the `8-4-4-4-12` form both protocols write it in. */
    public const val SYSTEM_ID: String = "edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

    /** `urn:uuid:` and the system id: DASH's `schemeIdUri`, and HLS's `KEYFORMAT`. */
    public const val SYSTEM_ID_URN: String = "urn:uuid:$SYSTEM_ID"

    /**
     * The one key this content is declared to be encrypted under.
     *
     * Sixteen bytes, because a Common Encryption `KID` is a UUID (// spec: ISO/IEC 23001-7 §8.2).
     * The value is arbitrary: nothing decrypts anything, so what matters is that every stream names
     * the same one, which is what makes one licence server serve both protocols.
     *
     * Private, and a function rather than a field, because a public `ByteArray` is a mutable object
     * every caller shares: one test that wrote into it would change what every other stream declares.
     * What leaves this object is [pssh], which builds a new array each time.
     */
    private fun keyId(): ByteArray = ByteArray(KEY_ID_BYTES) { index -> (index + 1).toByte() }

    /**
     * The `pssh` box both protocols carry, version 1, naming [KEY_ID] and carrying no system data.
     *
     * spec: ISO/IEC 23001-7 §8.1 — `ProtectionSystemSpecificHeaderBox`: a `FullBox('pssh')` whose
     * body is the 16-byte `SystemID`, then, at version 1, a `KID_count` and that many 16-byte `KID`s,
     * then a `DataSize` and that many bytes of system-specific data.
     *
     * Version 1 rather than version 0 because version 1 is the form that states the key id in the
     * box itself, which is the whole of what this box has to say: a real Widevine box's data field is
     * a protocol-buffer message no public specification describes, and inventing one would be
     * pretending to a fidelity this has no way to reach. `DataSize` is therefore zero, which §8.1
     * permits, and the key id is where a reader — and Media3's own `PsshAtomUtil` — finds it.
     */
    @JvmStatic
    public fun pssh(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(systemIdBytes())
        body.writeInt32(1) // KID_count
        body.write(keyId())
        body.writeInt32(0) // DataSize
        val payload = body.toByteArray()

        val box = ByteArrayOutputStream()
        box.writeInt32(BOX_HEADER_BYTES + FULL_BOX_VERSION_AND_FLAGS_BYTES + payload.size)
        box.write("pssh".toByteArray(Charsets.US_ASCII))
        box.write(1) // version
        box.write(0) // flags, all three bytes
        box.write(0)
        box.write(0)
        box.write(payload)
        return box.toByteArray()
    }

    /**
     * [pssh] in the base64 both manifests carry it as: DASH inside a `cenc:pssh` element
     * (// spec: ISO/IEC 23001-7 §11.2) and HLS inside an `EXT-X-KEY` `URI` of
     * `data:text/plain;base64,…` (// spec: RFC 2397 for the URL scheme).
     *
     * `java.util.Base64` needs API 26 and this module's floor is lower, and `android.util.Base64` is
     * an Android type this module may not name — it depends on nothing, which is what keeps it below
     * every module that plays these streams — so the encoder is the twenty lines below.
     *
     * spec: RFC 4648 §4 — the standard alphabet, padded with `=` to a multiple of four characters.
     */
    @JvmStatic
    public fun psshBase64(): String {
        val bytes = pssh()
        val out = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            val remaining = bytes.size - index
            val block = (bytes[index].toInt() and 0xFF shl 16) or
                (if (remaining > 1) bytes[index + 1].toInt() and 0xFF shl 8 else 0) or
                (if (remaining > 2) bytes[index + 2].toInt() and 0xFF else 0)
            out.append(BASE64_ALPHABET[block ushr 18 and 0x3F])
            out.append(BASE64_ALPHABET[block ushr 12 and 0x3F])
            out.append(if (remaining > 1) BASE64_ALPHABET[block ushr 6 and 0x3F] else '=')
            out.append(if (remaining > 2) BASE64_ALPHABET[block and 0x3F] else '=')
            index += 3
        }
        return out.toString()
    }

    /** The system id as the sixteen big-endian bytes a `pssh` box carries it as. */
    private fun systemIdBytes(): ByteArray {
        val hex = SYSTEM_ID.replace("-", "")
        return ByteArray(KEY_ID_BYTES) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write(value ushr 24 and 0xFF)
        write(value ushr 16 and 0xFF)
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }

    /** spec: ISO/IEC 23001-7 §8.2 — a `KID` is sixteen bytes, as a `SystemID` is. */
    private const val KEY_ID_BYTES = 16

    /** spec: ISO/IEC 14496-12 §4.2 — a 32-bit size and a four-character type, then a version and flags. */
    private const val BOX_HEADER_BYTES = 8
    private const val FULL_BOX_VERSION_AND_FLAGS_BYTES = 4

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}
