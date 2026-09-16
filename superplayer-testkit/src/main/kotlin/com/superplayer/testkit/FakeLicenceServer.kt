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

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallbackException
import androidx.media3.test.utils.FakeExoMediaDrm
import com.superplayer.core.SecurityLevelNegotiation
import java.io.IOException
import kotlin.math.min

/**
 * Where a protected stream's licences come from: an origin like any other, at a host of its own.
 *
 * A licence server is not part of the content. It is nominated by the app rather than by the
 * manifest, it usually belongs to a different operator from the CDN, and it answers a request the
 * player *composed* rather than one the manifest named. Giving it its own host is what makes all of
 * that expressible in this module's existing vocabulary: a [FaultScript] addresses it by
 * [ResourceKind.LICENCE], a test reads what was asked of it out of `networkRequests`, and the
 * transfer it answers passes through the same shaper, injector and clock wait as every other.
 *
 * Only the addresses are public. What answers them is Media3's own `FakeExoMediaDrm.LicenseServer`,
 * which is an `@UnstableApi` type and therefore stays behind this module's API for the reason
 * `TestContent`'s KDoc gives.
 */
public object FakeLicenceServer {

    /** The host every licence request goes to, and the one a [FaultScript] names to address them. */
    public const val HOST: String = "licence.superplayer.test"

    /** Where a licence is asked for: `LICENCE#0` on a device that needs no provisioning first. */
    public const val LICENCE_URI: String = "fake://$HOST/licence"

    /** Where a device is provisioned, which is a separate resource of [ResourceKind.LICENCE]. */
    public const val PROVISION_URI: String = "fake://$HOST/provision"

    /** Whether [uri] is one of this server's, which is the whole of how a licence load is recognised. */
    internal fun serves(uri: Uri): Boolean = uri.host == HOST

    /**
     * States that this server permits content to be delivered at [level] on a device that cannot
     * honour the level it reports — ADR-0012 rule 11's permission, answered in the one place a server
     * can answer it ([SecurityLevelNegotiation]).
     *
     * A *server* statement rather than a [DeviceStatement] one, and the distinction is the whole
     * subject: the permission is the licence server's to give and the device's to need, and a test
     * that could only say both at once could not tell a client that obeys the server from one that
     * reads the device and decides for itself.
     *
     * The default is to permit nothing, which is what a licence server that has never heard of the
     * exchange does, and is why every test written before this one is unaffected by it.
     */
    @JvmStatic
    public fun permitSecurityLevel(level: SecurityLevel) {
        permitted = level
    }

    /** What this server permits, or null for a server that permits no downgrade. */
    internal var permitted: SecurityLevel? = null
        private set

    /**
     * Forgets the permission, so one test's server is not the next one's.
     *
     * A field of an object that outlives a test method, exactly like [DeviceStatement.widevine], so
     * the reset has to be written down; [PlaybackHarness] calls it before each test beside that one.
     */
    internal fun forgetPermission() {
        permitted = null
    }
}

/**
 * Serves [FakeLicenceServer]'s two addresses from Media3's `FakeExoMediaDrm.LicenseServer`, and
 * everything else from the origin below.
 *
 * The server is a `MediaDrmCallback` rather than anything HTTP-shaped, so what this class does is the
 * translation: a POST body **is** the request the callback would have been handed, and the response
 * body is what it answers. Nothing is invented in between — the entitlement decision, the
 * provisioning rule and the deliberate failures are all Media3's, which is what stops this file from
 * becoming a second, home-made licence server nobody has reviewed.
 *
 * **One thing is this file's own, and it is the exception rather than a hole in that rule** (#208).
 * ADR-0012 rule 11's downgrade permission travels in headers beside the licence
 * ([SecurityLevelNegotiation]), and Media3's `FakeExoMediaDrm.LicenseServer` has no lever for it at
 * all: it is an allow-list over `SchemeData` byte lists, it inspects no request and it varies no
 * response. So the permission — and only the permission — is answered here, out of what a test stated
 * with [FakeLicenceServer.permitSecurityLevel]. It is a *policy* answer rather than an entitlement
 * one, no key ever comes out of it, and every licence this server issues is still Media3's decision.
 *
 * **It raises no transfer callbacks and reports no bytes to a listener**, and that is deliberate
 * rather than an omission: measurement is a propagated `TransferListener` (`PRD.md` §2.4) feeding a
 * bandwidth estimate, and a licence is not media. A few hundred bytes of key exchange counted as a
 * throughput sample would move an estimate that is supposed to describe how fast segments arrive.
 * The listener registration is still forwarded to the origin, so everything that *is* media is
 * measured exactly as before.
 */
internal class LicenceServerDataSource(
    private val upstream: DataSource,
    private val server: FakeExoMediaDrm.LicenseServer,
) : DataSource {

    private var body: ByteArray? = null
    private var readPosition = 0
    private var servedUri: Uri? = null
    private var upstreamOpen = false
    private var answeredHeaders: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (!FakeLicenceServer.serves(dataSpec.uri)) {
            upstreamOpen = true
            return upstream.open(dataSpec)
        }
        answeredHeaders = permissionHeadersFor(dataSpec)
        // A permission question carries no key request and gets no licence back: it is answered out
        // of the stated policy, in headers, and Media3's licence server never sees it. That is what
        // [SecurityLevelNegotiation] describes and the one thing about this class that is not a
        // translation of Media3's own decisions — see the class KDoc's paragraph on it.
        val answered = if (isPermissionQuestion(dataSpec)) ByteArray(0) else answer(dataSpec)
        body = answered
        readPosition = dataSpec.position.toInt()
        servedUri = dataSpec.uri
        return (answered.size - readPosition).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val served = body ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        val remaining = served.size - readPosition
        if (remaining == 0) return C.RESULT_END_OF_INPUT
        val read = min(length, remaining)
        System.arraycopy(served, readPosition, buffer, offset, read)
        readPosition += read
        return read
    }

    override fun getUri(): Uri? = servedUri ?: upstream.uri

    // Delegated rather than left at the interface's empty default, because a wrapper that answered
    // for the origin would hide the headers a stream declares — which is what
    // `HostileManifests.hlsCachedLivePlaylist`'s whole defect is, read one layer above this.
    override fun getResponseHeaders(): Map<String, List<String>> =
        if (body != null) answeredHeaders else upstream.responseHeaders

    override fun close() {
        body = null
        servedUri = null
        answeredHeaders = emptyMap()
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    /**
     * Whether [dataSpec] is a downgrade-permission question rather than a licence or provisioning
     * request: it names the level the client can honour in a request header, and carries no body.
     */
    private fun isPermissionQuestion(dataSpec: DataSpec): Boolean =
        dataSpec.httpRequestHeaders.containsKey(SecurityLevelNegotiation.LEVEL_REQUEST_HEADER)

    /**
     * The permission this server grants [dataSpec], as response headers.
     *
     * Empty unless a test stated one with [FakeLicenceServer.permitSecurityLevel] **and** the client
     * asked about exactly that level. Both halves matter: a server that answered whatever it was
     * asked would permit every downgrade and could not be told from a client that never asked.
     */
    private fun permissionHeadersFor(dataSpec: DataSpec): Map<String, List<String>> {
        if (!isPermissionQuestion(dataSpec)) return emptyMap()
        val asked = dataSpec.httpRequestHeaders[SecurityLevelNegotiation.LEVEL_REQUEST_HEADER]
        val permitted = FakeLicenceServer.permitted?.name ?: return emptyMap()
        if (asked != permitted) return emptyMap()
        return mapOf(SecurityLevelNegotiation.LEVEL_PERMITTED_HEADER to listOf(permitted))
    }

    /**
     * What the server answers [dataSpec] with, or the status a real one would refuse it with.
     *
     * spec: RFC 9110 §15.5.4 — 403 is the status for a request the server understood and will not
     * fulfil, which is what a licence server denying an entitlement returns, and what
     * `superplayer-resilience` classifies today. Media3's server signals the same refusal by
     * throwing, so this is the one place the two vocabularies meet.
     */
    private fun answer(dataSpec: DataSpec): ByteArray {
        val requestBody = dataSpec.httpBody ?: ByteArray(0)
        return try {
            if (dataSpec.uri.toString().startsWith(FakeLicenceServer.PROVISION_URI)) {
                val request = provisionRequestIn(requestBody)
                server.executeProvisionRequest(C.WIDEVINE_UUID, ExoMediaDrm.ProvisionRequest(request, "")).data
            } else {
                server.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(requestBody, "")).data
            }
        } catch (refused: MediaDrmCallbackException) {
            throw HttpDataSource.InvalidResponseCodeException(
                FaultScript.HTTP_FORBIDDEN,
                "The licence server refused ${dataSpec.uri}",
                refused,
                /* headerFields= */ emptyMap(),
                dataSpec,
                /* responseBody= */ ByteArray(0),
            )
        }
    }

    /**
     * The device's provisioning request, out of whichever of its two envelopes [body] arrived in.
     *
     * ref: Google's device provisioning service is POSTed a JSON object whose one member carries the
     * request — `{"signedRequest":<request>}` — and Media3's `HttpMediaDrmCallback` composes exactly
     * that, by concatenating the opaque request bytes between two literals rather than by encoding
     * them, and sends it with `Content-Type: application/json`. That is the shape `superplayer-drm`
     * sends, because the library uses Media3's own callback (ADR-0001) and a provisioning service is
     * not the app's to redesign. Unwrapping it here is what a real one does first.
     *
     * Unwrapped by the same byte concatenation in reverse, and deliberately not by a JSON parser: the
     * request is **not text** — `FakeExoMediaDrm`'s is three control bytes and a real one is a
     * certificate request — so a parser that decoded the member as a JSON string would corrupt it
     * exactly where a real service would not.
     *
     * A body in neither envelope is the request itself, which is what [TransportMediaDrmCallback] —
     * the stock arm's callback — posts, and the reason this reads the shape rather than the sender.
     */
    private fun provisionRequestIn(body: ByteArray): ByteArray {
        val envelope = PROVISION_ENVELOPE_PREFIX.toByteArray(Charsets.UTF_8)
        val close = PROVISION_ENVELOPE_SUFFIX.toByteArray(Charsets.UTF_8)
        val wrapped = body.size >= envelope.size + close.size &&
            body.copyOfRange(0, envelope.size).contentEquals(envelope) &&
            body.copyOfRange(body.size - close.size, body.size).contentEquals(close)
        return if (wrapped) body.copyOfRange(envelope.size, body.size - close.size) else body
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val server: FakeExoMediaDrm.LicenseServer,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = LicenceServerDataSource(upstream.createDataSource(), server)
    }

    private companion object {

        /** ref: the two literals `HttpMediaDrmCallback` concatenates a provisioning request between. */
        const val PROVISION_ENVELOPE_PREFIX = "{\"signedRequest\":\""
        const val PROVISION_ENVELOPE_SUFFIX = "\"}"
    }
}

/**
 * The player's side of the same wire: a `MediaDrmCallback` that carries every DRM request over the
 * harness's own transport.
 *
 * This is what makes a licence request a *transfer* at all, and therefore what makes it addressable,
 * delayable, refusable and countable. Without it the `FakeExoMediaDrm.LicenseServer` would be handed
 * to `DefaultDrmSessionManager` directly and the exchange would happen in memory, below everything
 * this module can see — which is the shape Media3's own DRM tests use, and the shape this ticket
 * exists to replace.
 *
 * Media3's own `HttpMediaDrmCallback` is the class this resembles and deliberately not the class
 * used — for the stock arm, which has no `WidevineConfig` to take a licence address from and would
 * otherwise need one invented for it. Both halves are one bare POST here, which also means a test of
 * the stock arm reads one mechanism rather than two.
 *
 * `superplayer-drm` does use `HttpMediaDrmCallback`, and since #207 both of its halves reach this
 * same server: the licence at the address `WidevineConfig` named, and the provisioning at the one the
 * stated device names ([WidevineStatement]). The provisioning request arrives inside Google's JSON
 * envelope there rather than bare, which [LicenceServerDataSource] unwraps — so the two arms differ
 * in the envelope and in nothing else.
 */
internal class TransportMediaDrmCallback(private val transport: DataSource.Factory) : MediaDrmCallback {

    override fun executeProvisionRequest(
        uuid: java.util.UUID,
        request: ExoMediaDrm.ProvisionRequest,
    ): MediaDrmCallback.Response = MediaDrmCallback.Response(post(FakeLicenceServer.PROVISION_URI, request.data))

    override fun executeKeyRequest(
        uuid: java.util.UUID,
        request: ExoMediaDrm.KeyRequest,
    ): MediaDrmCallback.Response = MediaDrmCallback.Response(post(FakeLicenceServer.LICENCE_URI, request.data))

    private fun post(uri: String, requestBody: ByteArray): ByteArray {
        val dataSpec = DataSpec.Builder()
            .setUri(uri)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(requestBody)
            .build()
        val source = transport.createDataSource()
        try {
            source.open(dataSpec)
            return DataSourceUtil.readToEnd(source)
        } catch (failure: IOException) {
            // The shape a DRM load failure reaches `DefaultDrmSession` in, so that everything above
            // — the retry budget, the classifier, the typed error — sees what it would in the field.
            throw MediaDrmCallbackException(
                dataSpec,
                dataSpec.uri,
                /* responseHeaders= */ emptyMap(),
                /* bytesLoaded= */ 0,
                failure,
            )
        } finally {
            DataSourceUtil.closeQuietly(source)
        }
    }
}
