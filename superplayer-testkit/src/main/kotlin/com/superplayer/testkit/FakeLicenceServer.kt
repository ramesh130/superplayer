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

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (!FakeLicenceServer.serves(dataSpec.uri)) {
            upstreamOpen = true
            return upstream.open(dataSpec)
        }
        val answered = answer(dataSpec)
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
        if (body != null) emptyMap() else upstream.responseHeaders

    override fun close() {
        body = null
        servedUri = null
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
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
                server.executeProvisionRequest(C.WIDEVINE_UUID, ExoMediaDrm.ProvisionRequest(requestBody, "")).data
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

    class Factory(
        private val upstream: DataSource.Factory,
        private val server: FakeExoMediaDrm.LicenseServer,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = LicenceServerDataSource(upstream.createDataSource(), server)
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
 * used. Its key-request half would do: it POSTs the request data to a URL through a
 * `DataSource.Factory`, which is exactly the shape below. Its *provisioning* half will not, because
 * it follows Google's provisioning convention of appending the request to the URL as
 * `&signedRequest=<request as text>` — and `FakeExoMediaDrm`'s provision request is three control
 * bytes, which is not text and does not survive a query string. Both halves are therefore one POST
 * here, which also means a test reads one mechanism rather than two.
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
