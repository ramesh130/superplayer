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

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioCapabilitiesReceiver
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.test.utils.FakeAudioRenderer
import androidx.media3.test.utils.FakeVideoRenderer
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Media3's own fake video renderer, with two faults of its own a test can switch on, plus the
 * [DecoderInitFault] it shares with its audio sibling.
 *
 * ## Why the renderer and not the network
 *
 * A rebuffer is *caused* by data not arriving, and the faithful way to provoke one is to stall the
 * loader. Media3's fakes deliver samples immediately by construction, so provoking a genuine
 * network stall means building the throughput shaping that is `#40`'s subject and the fault
 * injection that is `#39`'s.
 *
 * What a rebuffer *is*, from the collector's side, is the engine reporting `STATE_BUFFERING` after
 * playback has started — and a renderer that stops being ready produces exactly that transition,
 * through the same code path, with no timing to get right. **So this is a stub**, and the tests
 * that use it say so: it reproduces the state machine faithfully and the cause not at all. When
 * `#39` and `#40` land, the rebuffer tests should be re-pointed at a shaped transfer and this
 * mechanism kept only for the cases that are genuinely about the renderer.
 *
 * The failure injection has no such caveat: a renderer failing mid-render is a real
 * `ExoPlaybackException` of `TYPE_RENDERER`, which is one of the things `PlaybackFailure` buckets.
 * [DecoderInitFault] has a caveat of its own and states it there.
 */
internal class ControllableVideoRenderer(
    private val handler: HandlerWrapper,
    private val eventListener: VideoRendererEventListener,
    /**
     * The fault this renderer shares with its audio sibling, reachable here because the harness
     * keeps one renderer per player and this is it ([DecoderInitFault] says why it is shared).
     */
    val decoderInitFault: DecoderInitFault,
) : FakeVideoRenderer(handler, eventListener) {

    private val stalled = AtomicBoolean(false)
    private val failing = AtomicBoolean(false)

    /** Stops being ready, which is what moves the player into `STATE_BUFFERING`. */
    fun stall() {
        stalled.set(true)
    }

    /** Becomes ready again; the player leaves `STATE_BUFFERING` on its next pass. */
    fun resume() {
        stalled.set(false)
    }

    /** Fails on the next render pass, as a renderer whose codec has given up would. */
    fun fail() {
        failing.set(true)
    }

    /**
     * Reports [count] frames dropped over [elapsedMs] of playing time, as a real renderer does.
     *
     * Media3's fake renderer never drops anything — it presents every sample it is given — so the
     * callback the schema's `VideoFramesDropped` is derived from has to be raised by hand. It is
     * posted on the renderer's own event handler rather than called from the test thread, because
     * that is the thread `VideoRendererEventListener` is documented to arrive on and the analytics
     * collector below it is not thread-safe.
     *
     * A stub, and a narrower one than [stall]: it exercises the collector's arithmetic over a real
     * callback, and says nothing about whether a decoder that cannot keep up produces these numbers.
     */
    fun reportDroppedFrames(count: Int, elapsedMs: Long) {
        handler.post { eventListener.onDroppedFrames(count, elapsedMs) }
    }

    override fun isReady(): Boolean = !stalled.get() && super.isReady()

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (failing.compareAndSet(true, false)) {
            throw ExoPlaybackException.createForRenderer(
                IOException("Injected renderer failure"),
                name,
                index,
                /* rendererFormat= */ null,
                /* rendererFormatSupport= */ C.FORMAT_HANDLED,
                /* isRecoverable= */ false,
                ExoPlaybackException.ERROR_CODE_DECODING_FAILED,
            )
        }
        decoderInitFault.throwIfArmed(name, index)
        if (stalled.get()) {
            // Nothing is rendered while stalled, which is the whole of what a stall looks like from
            // above: the position stops advancing and the player reports it is waiting for data.
            return
        }
        super.render(positionUs, elapsedRealtimeUs)
    }
}

/**
 * Media3's own fake audio renderer, carrying the one fault both renderers share.
 *
 * It exists for a reason worth writing down: `superplayer-testmedia`'s synthetic HLS and DASH streams
 * publish **audio only**, so a test of a real protocol enables this renderer and never the video one.
 * A fault armed on the video renderer alone would silently do nothing in exactly the tests that play
 * a real manifest, which is why [DecoderInitFault] is one object handed to both rather than a flag on
 * each.
 *
 * ## An audio output it answers to
 *
 * Media3's fake handles every audio format, which leaves an AV receiver nothing to change (#270). So a
 * format only an output can play — AC-3, E-AC-3, AC-4, DTS or TrueHD, for which the harness's device has no
 * decoder, as a device without the licensed decoders has none — is supported exactly where the audio output
 * `DeviceStatement.declareAudioOutput` stated passes it through. When the output changes, the renderer
 * tells the selector its capabilities changed, as Media3's own audio renderer does when its sink hears the
 * change. Everything else stays the fake's answer, so every test that plays no such format is unchanged.
 *
 * ref: Media3 1.11's `MediaCodecAudioRenderer.supportsFormat` answers `FORMAT_HANDLED` for a format its
 * sink plays directly and `FORMAT_UNSUPPORTED_SUBTYPE` for one with no decoder, and its
 * `onAudioCapabilitiesChanged` calls `BaseRenderer.onRendererCapabilitiesChanged`:
 * https://github.com/androidx/media/blob/1.11.0/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java
 *
 * The reading is Media3's own `AudioCapabilitiesReceiver`, the reader `DefaultAudioSink` uses, registered
 * the first time such a format is asked about and released with the renderer. What stands in is the sink:
 * no audio track is configured, so a sink refusing a format mid-change is not something this renderer
 * raises.
 */
internal class ControllableAudioRenderer(
    handler: HandlerWrapper,
    eventListener: AudioRendererEventListener,
    private val decoderInitFault: DecoderInitFault,
) : FakeAudioRenderer(handler, eventListener) {

    /** Media3's reader of the output, registered at the first question about a passthrough-only format; null until then. */
    private var output: AudioCapabilitiesReceiver? = null

    /** What the output carries, as [output] last read it; null until it is registered. */
    @Volatile
    private var capabilities: AudioCapabilities? = null

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        decoderInitFault.throwIfArmed(name, index)
        super.render(positionUs, elapsedRealtimeUs)
    }

    override fun supportsFormat(format: Format): Int {
        if (format.sampleMimeType !in PASSTHROUGH_ONLY) return super.supportsFormat(format)
        val current = capabilities ?: startReadingTheOutput()
        return if (current.isPassthroughPlaybackSupported(format, AudioAttributes.DEFAULT)) {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
    }

    override fun onRelease() {
        output?.unregister()
        output = null
        super.onRelease()
    }

    @Synchronized
    private fun startReadingTheOutput(): AudioCapabilities {
        capabilities?.let { return it }
        val receiver = AudioCapabilitiesReceiver(
            ApplicationProvider.getApplicationContext(),
            { changed ->
                // A reading inside `register` is the devices already present, which the registration reports
                // before it returns (`docs/testing.md`, *A TV device*): it is the first reading, not a change.
                val registered = output != null
                if (changed != capabilities) {
                    capabilities = changed
                    if (registered) onRendererCapabilitiesChanged()
                }
            },
            // Media3's default attributes rather than the player's: the stated output answers every usage alike.
            AudioAttributes.DEFAULT,
            /* routedDevice= */ null,
        )
        val first = receiver.register()
        capabilities = first
        output = receiver
        return first
    }

    private companion object {
        /** The encodings a device plays only by passing them through to an output that decodes them. */
        val PASSTHROUGH_ONLY = setOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_AC4,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_X,
            MimeTypes.AUDIO_TRUEHD,
        )
    }
}

/**
 * One renderer set's armed decoder-initialisation failure: the fault #226 needs, held outside the
 * renderers so that whichever of them the content enables raises it.
 *
 * ## What is real here and what is not
 *
 * The exception is the real one — Media3's own `DecoderInitializationException`, built through the
 * constructor a selected-decoder failure uses and carrying the real `MediaCodecInfo` the classifier
 * reads, inside a real `ExoPlaybackException` of renderer type at the real error code. Everything
 * downstream of it runs on production code over a production object: the classification, the typed
 * error, the security-level re-open, the licence the rebuilt graph acquires.
 *
 * What stands in is only the **origin**. A real renderer reaches that exception because `initCodec`
 * threw — `MediaCodecVideoRenderer` asking `PlaceholderSurface.newInstance(context, codecInfo.secure)`
 * and that call failing its `checkState` on a device that cannot back a protected surface, or
 * `MediaCodec.configure` refusing a secure codec — and neither can happen under Robolectric, which
 * has no `MediaCrypto` and no protected buffer queue. That Media3 turns such a throw into this
 * exception carrying the secure `codecInfo` is established from Media3 1.11's bytecode and cited
 * where the classifier reads it (`ErrorClassifier.theSecureDecoderWouldNotStart`), not asserted
 * anywhere. `docs/testing.md`'s *A Widevine device and a licence server* names that boundary.
 */
internal class DecoderInitFault {

    /** Null is disarmed; a value is armed and says whether the decoder that failed was the secure
     * one. One reference rather than two flags, so arming is a single write. */
    private val armed = AtomicReference<Boolean?>(null)

    /** Arms the fault for the next render pass of whichever renderer the content enabled. */
    fun arm(secureDecoderRequired: Boolean) {
        armed.set(secureDecoderRequired)
    }

    /** Raises the armed failure, once, from the renderer named by [rendererName] and [index]. */
    fun throwIfArmed(rendererName: String, index: Int) {
        val secure = armed.getAndSet(null) ?: return
        throw ExoPlaybackException.createForRenderer(
            DecoderInitializationException(
                INITIALISING_FORMAT,
                IllegalStateException("Injected decoder initialization failure"),
                // Media3 derives this from the DRM session's own state and it is set on every
                // initialisation failure of a protected session, so the classifier reads the
                // `codecInfo` below instead; it is passed faithfully rather than left false.
                /* secureDecoderRequired= */ secure,
                decoderThatFailed(secure),
            ),
            rendererName,
            index,
            /* rendererFormat= */ INITIALISING_FORMAT,
            /* rendererFormatSupport= */ C.FORMAT_HANDLED,
            /* isRecoverable= */ false,
            ExoPlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        )
    }

    /**
     * The decoder Media3 would say it had selected and failed to bring up, secure or not.
     *
     * A real `MediaCodecInfo` rather than a stand-in, because `.secure` is the field the
     * classification turns on. Media3 marks a decoder secure when its name carries the platform's
     * `.secure` suffix or when it is built that way, which is the last argument of
     * `newInstance` (// ref: `MediaCodecInfo.newInstance`'s `forceSecure`); the capabilities are null
     * because nothing on this path reads them.
     */
    private fun decoderThatFailed(secure: Boolean): MediaCodecInfo = MediaCodecInfo.newInstance(
        /* name= */ if (secure) "$DECODER_NAME.secure" else DECODER_NAME,
        /* mimeType= */ MimeTypes.VIDEO_H264,
        /* codecMimeType= */ MimeTypes.VIDEO_H264,
        /* capabilities= */ null,
        /* hardwareAccelerated= */ true,
        /* softwareOnly= */ false,
        /* vendor= */ false,
        /* forceDisableAdaptive= */ false,
        /* forceSecure= */ secure,
    )

    private companion object {

        /**
         * The format the injected failure names. `DecoderInitializationException` reads its
         * `sampleMimeType` for the message, and H.264 is the video a protected stream would have
         * been obliged to decode in protected memory — which is the format this failure is about
         * even where the renderer that raises it is the audio one, since these synthetic streams
         * publish no video track for a decoder to have been selected for.
         */
        val INITIALISING_FORMAT: Format = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .build()

        /** A plausible hardware decoder name; only the `.secure` suffix convention matters. */
        const val DECODER_NAME: String = "c2.android.avc.decoder"
    }
}
