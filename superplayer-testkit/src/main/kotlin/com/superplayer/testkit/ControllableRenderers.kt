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

import androidx.media3.common.C
import androidx.media3.common.util.HandlerWrapper
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.test.utils.FakeVideoRenderer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media3's own fake video renderer, with two faults a test can switch on.
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
 */
internal class ControllableVideoRenderer(
    private val handler: HandlerWrapper,
    private val eventListener: VideoRendererEventListener,
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
        if (stalled.get()) {
            // Nothing is rendered while stalled, which is the whole of what a stall looks like from
            // above: the position stops advancing and the player reports it is waiting for data.
            return
        }
        super.render(positionUs, elapsedRealtimeUs)
    }
}
