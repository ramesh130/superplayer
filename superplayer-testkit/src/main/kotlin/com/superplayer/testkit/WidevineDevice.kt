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

import android.media.DeniedByServerException
import androidx.media3.common.DrmInitData
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.WidevineUtil
import androidx.media3.test.utils.FakeExoMediaDrm

/**
 * How well a device protects the content it decodes, in Widevine's own two-rung vocabulary.
 *
 * ref: Android's `MediaDrm.PROPERTY_SECURITY_LEVEL` is the string `securityLevel`, and a Widevine
 * implementation answers it with `L1` or `L3`:
 * https://developer.android.com/reference/android/media/MediaDrm#PROPERTY_SECURITY_LEVEL
 *
 * The distinction is not a shade of the same thing, which is ADR-0012 rule 11's whole argument for
 * keeping a security-level downgrade out of the fallback ladder: at [L1] the keys and the decode both
 * sit in the device's trusted execution environment and a licence server may allow HD, while at [L3]
 * they do not and the same server usually will not. A viewer moved from one to the other has had a
 * different thing delivered rather than the same thing rescued.
 */
public enum class SecurityLevel {

    /** Keys and decoding inside the trusted execution environment; a secure decoder is required. */
    L1,

    /** Keys handled in software; the content plays on an ordinary decoder, usually capped. */
    L3,
}

/**
 * What a test has said about this device's Widevine implementation, read by [PlaybackHarness] when it
 * builds a player for protected content.
 *
 * A statement the harness reads rather than a shadow the platform answers, and that is forced rather
 * than chosen: **Robolectric 4.16 ships no `ShadowMediaDrm`**, so `MediaDrm` cannot be instantiated
 * under `check` at all and there is no platform object for a shadow to state anything about. What
 * stands in for the device is Media3's `FakeExoMediaDrm`, which is the object a
 * `DrmSessionManager` actually talks to, and this is what configures one — which means a test states
 * its device in the same place and at the same moment as it states a display or a decoder
 * ([DeviceStatement]), and nothing about that difference shows up in the test.
 */
internal class WidevineStatement(
    val securityLevel: SecurityLevel,
    val maxConcurrentSessions: Int,
    val provisioningRequired: Boolean,
    val provisioningFails: Boolean,
) {

    /**
     * Every implementation this statement has handed out, so that a test can make the *device* do
     * something to a session that is already open.
     *
     * Held here and nowhere else because there is nowhere else: a real provider hands out a new
     * implementation per acquisition and keeps no register of them, and neither does
     * [PlaybackHarness] — the slot it fills is a provider, and what the provider returns goes
     * straight into a session manager. Everything a device does *before* a session exists is stated
     * as a field of this class; a key rotation is the one thing it does *after*, so a handle is the
     * only way to say it. Cleared with the statement itself ([DeviceStatement.forgetWidevine]), so
     * one test's device is not the next one's here either.
     */
    private val handedOut = mutableListOf<FakeExoMediaDrm>()

    /** The device as an `ExoMediaDrm`, built fresh per session as a real provider's would be. */
    fun exoMediaDrm(): ExoMediaDrm {
        val fake = FakeExoMediaDrm.Builder()
            .setMaxConcurrentSessions(maxConcurrentSessions)
            .setProvisionsRequired(if (provisioningRequired || provisioningFails) 1 else 0)
            .build()
        handedOut += fake
        return StatedExoMediaDrm(fake, this)
    }

    /**
     * The device telling every session it holds open that its keys must be renewed.
     *
     * ref: `MediaDrm.EVENT_KEY_REQUIRED` is the event a Widevine implementation raises when the
     * keys a session holds need replacing — which on a live stream is a key rotation, since the
     * packager changes the key under a running session rather than ending it:
     * https://developer.android.com/reference/android/media/MediaDrm#EVENT_KEY_REQUIRED
     *
     * The *device* raises it and not the licence server, which is why it is stated here rather than
     * on [FakeLicenceServer]: the server changes the key it will issue, the device notices that the
     * keys in force no longer decrypt, and what a player sees is this event. A harness that made a
     * rotation a property of the server would be describing the half no client can observe.
     *
     * `EVENT_KEY_EXPIRED` is deliberately not offered beside it. Media3 routes only
     * `EVENT_KEY_REQUIRED` to a session at all (`DefaultDrmSession.onMediaDrmEvent`), so a lever for
     * the other would be a lever for nothing, and a test using it would be asserting that Media3
     * ignores an event rather than that SuperPlayer renews a licence.
     */
    fun signalKeyRotation() {
        handedOut.forEach { device ->
            // Every open session of every implementation: the predicate is the *device's* way of
            // addressing a rotation at one session id, and a test here has no session id to name —
            // it knows the content it asked for and not the bytes a session was opened under.
            device.triggerEvent({ true }, ExoMediaDrm.EVENT_KEY_REQUIRED, /* extra= */ 0, /* data= */ ByteArray(0))
        }
    }

    /**
     * How long a licence this device persists has left, in seconds — the two halves
     * `MediaDrm` reports separately and ADR-0012 rule 9 requires the library to keep apart.
     *
     * Stated on the *device* rather than on [FakeLicenceServer] because that is where a real client
     * reads them: the licence policy is the server's, but what a player and an offline licence store
     * can see is `queryKeyStatus`, and everything that acts on an expiry acts on those two numbers
     * ([DeviceStatement.declareOfflineLicence] for what the defaults are and why).
     */
    var offlineLicenceDurationSec: Long = DEFAULT_OFFLINE_LICENCE_DURATION_SEC
        private set

    var offlinePlaybackDurationSec: Long = DEFAULT_OFFLINE_PLAYBACK_DURATION_SEC
        private set

    fun declareOfflineLicenceDurations(licenceDurationSec: Long, playbackDurationSec: Long) {
        offlineLicenceDurationSec = licenceDurationSec
        offlinePlaybackDurationSec = playbackDurationSec
    }

    /**
     * This device's persistent key store: what a licence *downloaded* for offline use leaves behind.
     *
     * A real Widevine implementation keeps offline keys in the device's own secure storage, outside
     * every app directory and outliving any one `MediaDrm` instance — which is exactly why a key-set
     * id, and not the keys, is what an app persists. So it is held here, on the statement, rather
     * than on the implementation [exoMediaDrm] hands out: a licence acquired through one player must
     * still restore under the next player's device, and an implementation-scoped store would make
     * offline playback work only inside the session that acquired the licence, which is the one thing
     * it must not do. Cleared with the statement ([DeviceStatement.forgetWidevine]).
     */
    private val offlineKeys = mutableMapOf<List<Byte>, OfflineKeys>()

    private var keySetIdsMinted = 0

    /**
     * Remembers what a successful offline key exchange produced, under a fresh key-set id.
     *
     * ref: `MediaDrm.provideKeyResponse` returns a key-set id for a `KEY_TYPE_OFFLINE` response and
     * an empty array for a streaming one, and that id is the handle every later call —
     * `restoreKeys`, a renewal, a release — is made with:
     * https://developer.android.com/reference/android/media/MediaDrm#provideKeyResponse(byte[],%20byte[])
     */
    fun rememberOfflineKeys(keys: OfflineKeys): ByteArray {
        val keySetId = "offline-key-set-${keySetIdsMinted++}".toByteArray(Charsets.UTF_8)
        offlineKeys[keySetId.toList()] = keys
        return keySetId
    }

    fun offlineKeysFor(keySetId: List<Byte>): OfflineKeys? = offlineKeys[keySetId]

    fun forgetOfflineKeys(keySetId: List<Byte>) {
        offlineKeys.remove(keySetId)
    }

    private companion object {

        /**
         * Thirty days, which is the ordinary rental window a studio licence for a download carries
         * and is long enough that a test which is not about expiry never meets one. Nothing here
         * depends on the exact number; what depends on its being comfortably large is that Media3
         * re-requests an offline licence during playback once it is inside sixty seconds of expiry
         * (`DefaultDrmSession.doLicense`), and a default under that would make every offline
         * playback a network round trip.
         */
        const val DEFAULT_OFFLINE_LICENCE_DURATION_SEC = 30L * 24 * 60 * 60

        /**
         * Forty-eight hours: the viewing window a download conventionally gets once it has been
         * started, and deliberately shorter than the licence's so that the two numbers are never
         * accidentally the same one in a test that asserts the split.
         */
        const val DEFAULT_OFFLINE_PLAYBACK_DURATION_SEC = 48L * 60 * 60
    }
}

/**
 * What a device kept when it persisted a licence: enough to restore the keys, and enough to ask the
 * server about them again.
 *
 * The *response* is what `restoreKeys` replays into Media3's own fake, which is how a restored
 * session becomes a keyed session without this file reimplementing key handling. The *request* is
 * what a release exchange sends, because Media3's `FakeExoMediaDrm` composes a request only for a
 * session it has open and a release is addressed at a key-set id instead. The scheme datas are what
 * the licence server matches on, and a renewal arrives without them.
 */
internal class OfflineKeys(
    val response: ByteArray,
    val request: ByteArray,
    val schemeDatas: List<DrmInitData.SchemeData>,
)

/**
 * Media3's fake Widevine implementation, answering the three questions a *device* rather than a
 * licence exchange decides.
 *
 * Delegation rather than a fork of `FakeExoMediaDrm`: the session handling, the key-request encoding
 * and the provisioning bookkeeping are Media3's and are what make the exchange realistic, and the
 * four overrides below are the four things `FakeExoMediaDrm` has no opinion about because Media3's
 * own tests never needed one — its provisioning address included, which Media3 leaves a placeholder
 * because its own tests answer the callback in memory rather than over a transport.
 */
private class StatedExoMediaDrm(
    private val delegate: FakeExoMediaDrm,
    private val statement: WidevineStatement,
) : ExoMediaDrm by delegate {

    /**
     * The level this implementation is operating at: what the device reports, unless it has been
     * asked to work at a lower one and agreed ([setPropertyString]).
     *
     * Per implementation rather than per statement, which is what a real `MediaDrm` does: the
     * property is set on an instance, and a provider that hands out two hands out two devices at
     * whatever level each was asked for.
     */
    private var level: SecurityLevel = statement.securityLevel

    /**
     * ref: `MediaDrm.PROPERTY_SECURITY_LEVEL` — the property a Widevine device answers `L1` or `L3`
     * to, and the one every piece of software that cares about the distinction reads.
     */
    override fun getPropertyString(propertyName: String): String =
        if (propertyName == SECURITY_LEVEL_PROPERTY) {
            level.name
        } else {
            delegate.getPropertyString(propertyName)
        }

    /**
     * ref: `MediaDrm.setPropertyString` with `securityLevel` — the one lever for asking a Widevine
     * implementation to operate below what it is capable of, and the lever ADR-0012 rule 11's
     * downgrade pulls. A device honours a request for a level at or below its own and ignores the
     * rest, which is why this narrows and never raises.
     */
    override fun setPropertyString(propertyName: String, value: String) {
        if (propertyName == SECURITY_LEVEL_PROPERTY && value == SecurityLevel.L3.name) {
            level = SecurityLevel.L3
            return
        }
        delegate.setPropertyString(propertyName, value)
    }

    /**
     * ref: `MediaDrm.requiresSecureDecoderComponent` — whether the licence in force can only be used
     * by a decoder operating on protected memory. True at [SecurityLevel.L1] and false at
     * [SecurityLevel.L3], which is the definition of the two levels rather than a simplification.
     */
    override fun requiresSecureDecoder(sessionId: ByteArray, mimeType: String): Boolean =
        level == SecurityLevel.L1

    /**
     * The provisioning round trip this device would make, addressed at [FakeLicenceServer].
     *
     * ref: `MediaDrm.getProvisionRequest` returns opaque request data **and the URL of the
     * provisioning service the device implementation trusts** — on a Widevine handset, Google's
     * certificate provisioning endpoint, which the device names and the app never does:
     * https://developer.android.com/reference/android/media/MediaDrm.ProvisionRequest#getDefaultUrl()
     * `FakeExoMediaDrm` names `bar.test`, a placeholder Media3's own tests never dial, and the whole
     * of what is changed here is the address.
     *
     * That one substitution is what lets `superplayer-drm` be tested through its **own** callback
     * rather than a stand-in (#207). Media3's `HttpMediaDrmCallback` — the production one — sends a
     * provisioning request to the URL this method names, and until the fake device named an address
     * the harness answers at, a provisioning round trip through a real `SuperPlayer` left the harness
     * entirely and could neither be counted, delayed nor refused. Bending the library's callback to
     * the harness instead would have been the wrong repair: the shape under test would then have been
     * the test's rather than the field's.
     *
     * The request *data* is the delegate's, unaltered, because that is what the licence server
     * recognises and what makes the exchange Media3's rather than this file's.
     */
    override fun getProvisionRequest(): ExoMediaDrm.ProvisionRequest =
        ExoMediaDrm.ProvisionRequest(delegate.provisionRequest.data, FakeLicenceServer.PROVISION_URI)

    /**
     * A device whose provisioning the service will not complete.
     *
     * ref: `MediaDrm.provideProvisionResponse` throws `DeniedByServerException` when the provisioning
     * server has refused this device — a revoked or untrusted implementation, which on a real handset
     * is the commonest reason L1 is unusable while L3 is fine. The refusal is the *device's* answer
     * and not the transfer's, which is why it is stated here and not as a [FaultScript] fault: a
     * fault at [ResourceKind.LICENCE] is a provisioning request that never reached a server, and this
     * is one that reached it and was turned down.
     *
     * **Refused at the level the statement names and not below it**, which is the whole of what makes
     * #225 reachable through a real player. Provisioning is per security level on a real handset — a
     * certificate is issued for the keybox being certified — so a device refused at `L1` is routinely
     * certified at `L3`, which is [DeviceStatement.declareWidevineProvisioningFailure]'s own sentence
     * about the commonest reason L1 is unusable. An implementation that has been lowered
     * ([setPropertyString]) is therefore asking a different question, and gets a different answer.
     */
    override fun provideProvisionResponse(response: ByteArray) {
        if (statement.provisioningFails && level == statement.securityLevel) {
            throw DeniedByServerException("The provisioning service refused this device (stated by DeviceStatement)")
        }
        delegate.provideProvisionResponse(response)
    }

    /**
     * The key-set id each open session has restored, so that a renewal knows which stored licence it
     * is renewing. Per implementation, because a session id is.
     */
    private val restoredBySession = mutableMapOf<List<Byte>, List<Byte>>()

    /** Sessions whose request in flight asked for a *persistable* licence rather than a streaming one. */
    private val offlineRequests = mutableSetOf<List<Byte>>()

    /**
     * What each of those requests was, by session: the bytes sent and the scheme datas they were
     * composed for. Kept only until the response arrives, which is when they become part of what the
     * device persisted ([OfflineKeys]).
     */
    private val requestedBytes = mutableMapOf<List<Byte>, ByteArray>()

    private val requestedSchemeDatas = mutableMapOf<List<Byte>, List<DrmInitData.SchemeData>>()

    /**
     * The offline half of a Widevine device, which `FakeExoMediaDrm` does not have: it refuses
     * `KEY_TYPE_OFFLINE` and `KEY_TYPE_RELEASE` outright ("Offline key requests are not supported")
     * and throws from `restoreKeys`, because Media3's own tests never download a licence.
     *
     * What is added is only the bookkeeping a real implementation does, and none of the exchange:
     * the request that goes to the server, the response that comes back and the entitlement decision
     * are still Media3's and [FakeLicenceServer]'s, exactly as they are for a streaming licence, so a
     * download is the same round trip under a different key type. That is what keeps this a stand-in
     * for a device rather than a second licence protocol nobody has reviewed.
     *
     * ref: `MediaDrm.getKeyRequest` takes the key type — `KEY_TYPE_STREAMING`, `KEY_TYPE_OFFLINE` or
     * `KEY_TYPE_RELEASE` — and a scope that is the *session id* for the first two and the *key-set
     * id* for a release:
     * https://developer.android.com/reference/android/media/MediaDrm#getKeyRequest(byte[],%20byte[],%20java.lang.String,%20int,%20java.util.HashMap)
     */
    override fun getKeyRequest(
        scope: ByteArray,
        schemeDatas: MutableList<DrmInitData.SchemeData>?,
        keyType: Int,
        optionalParameters: HashMap<String, String>?,
    ): ExoMediaDrm.KeyRequest {
        if (keyType == ExoMediaDrm.KEY_TYPE_RELEASE) {
            // A release names the stored licence and no session, so there is nothing for the
            // delegate to compose a request out of. The request that acquired the licence is sent
            // again instead: the licence server recognises it — it is the one it already answered —
            // so the round trip happens, is counted and can be refused, which is the whole of what a
            // release is observable as from outside the device.
            val stored = statement.offlineKeysFor(scope.toList())
                ?: throw IllegalStateException("This device holds no offline licence to release")
            return ExoMediaDrm.KeyRequest(
                stored.request,
                FakeLicenceServer.LICENCE_URI,
                ExoMediaDrm.KeyRequest.REQUEST_TYPE_RELEASE,
            )
        }
        val scopeKey = scope.toList()
        // A renewal arrives with no initialization data — Media3 renews against a key-set id — so the
        // scheme datas the stored licence was issued for stand in, which is what a real device does
        // by knowing what it stored.
        val datas = schemeDatas?.takeIf { it.isNotEmpty() }
            ?: restoredBySession[scopeKey]?.let { statement.offlineKeysFor(it)?.schemeDatas }?.toMutableList()
        val request = delegate.getKeyRequest(scope, datas, ExoMediaDrm.KEY_TYPE_STREAMING, optionalParameters)
        if (keyType == ExoMediaDrm.KEY_TYPE_OFFLINE) {
            offlineRequests += scopeKey
            requestedBytes[scopeKey] = request.data
            requestedSchemeDatas[scopeKey] = datas.orEmpty()
        }
        return request
    }

    /**
     * ref: `MediaDrm.provideKeyResponse` — a response to an offline request is answered with the
     * key-set id the keys were persisted under, a response to a release request removes them, and a
     * streaming response is answered with an empty array.
     */
    override fun provideKeyResponse(scope: ByteArray, response: ByteArray): ByteArray {
        val scopeKey = scope.toList()
        if (statement.offlineKeysFor(scopeKey) != null) {
            // The scope is a key-set id rather than a session id, which Media3 does for exactly one
            // case: the response to a release request. The keys are gone, and nothing is left for a
            // later `restoreKeys` to find — which is the assertion "release leaves no licence behind"
            // is ultimately made against.
            statement.forgetOfflineKeys(scopeKey)
            return ByteArray(0)
        }
        val keySetId = delegate.provideKeyResponse(scope, response)
        if (!offlineRequests.remove(scopeKey)) return keySetId
        // A renewal replaces what it renewed rather than leaving a second licence on the device,
        // which is what makes a renewed download still one licence to release.
        restoredBySession.remove(scopeKey)?.let(statement::forgetOfflineKeys)
        val schemeDatas = requestedSchemeDatas.remove(scopeKey).orEmpty()
        return statement.rememberOfflineKeys(
            OfflineKeys(response, checkNotNull(requestedBytes.remove(scopeKey)), schemeDatas),
        )
    }

    /**
     * ref: `MediaDrm.restoreKeys` loads a persisted licence into an open session, which is what makes
     * playback with no network possible at all.
     *
     * The stored *response* is replayed into Media3's own fake, so the session becomes keyed by the
     * same path a streaming session does and nothing here decides what "has keys" means.
     */
    override fun restoreKeys(sessionId: ByteArray, keySetId: ByteArray) {
        val stored = statement.offlineKeysFor(keySetId.toList())
            ?: throw IllegalStateException("This device holds no offline licence under that key-set id")
        delegate.provideKeyResponse(sessionId, stored.response)
        restoredBySession[sessionId.toList()] = keySetId.toList()
    }

    /**
     * ref: `MediaDrm.queryKeyStatus` is where Widevine reports the two remaining durations, under the
     * property names `LicenseDurationRemaining` and `PlaybackDurationRemaining`
     * (Media3's `WidevineUtil`). `FakeExoMediaDrm` reports neither, and Media3 reads a missing one as
     * long expired — so without this every restored licence would be a dead one.
     */
    override fun queryKeyStatus(sessionId: ByteArray): Map<String, String> =
        delegate.queryKeyStatus(sessionId) + mapOf(
            WidevineUtil.PROPERTY_LICENSE_DURATION_REMAINING to statement.offlineLicenceDurationSec.toString(),
            WidevineUtil.PROPERTY_PLAYBACK_DURATION_REMAINING to statement.offlinePlaybackDurationSec.toString(),
        )
}

/** ref: the value of `MediaDrm.PROPERTY_SECURITY_LEVEL`. */
private const val SECURITY_LEVEL_PROPERTY = "securityLevel"
