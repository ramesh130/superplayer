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

package com.superplayer.effects

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.superplayer.core.FrameOutput
import com.superplayer.core.VideoFrame

/**
 * A consumer's [FrameOutput] as the one Media3 effect the engine runs (ADR-0022 rule 3).
 *
 * The adapter is the whole of this module's Media3 half, and ADR-0022 rule 2 is why it is one object
 * built once: Media3 sets its pipeline up before `prepare()` and SuperPlayer never asks it to re-lay one,
 * so anything that changes during playback changes inside [FrameOutput.process].
 */
internal class FrameOutputEffect(private val output: FrameOutput) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = FrameOutputShaderProgram(output, useHdr)
}

/**
 * Hands each input frame to the [FrameOutput] and draws the frame it returns into the pipeline's own
 * output texture.
 *
 * Media3 owns the output texture and has focused its framebuffer before [drawFrame] is called, so what
 * the consumer returns is copied there with a full-frame quad. A copy rather than handing the returned
 * texture on, because a [VideoFrame] is valid only for the call it arrived in (its KDoc) while the
 * pipeline holds an output texture until the next stage releases it.
 *
 * ref: Media3 1.11's `BaseGlShaderProgram` — `configure` is asked before the first frame and on a size
 * change, `drawFrame` with the output framebuffer focused:
 * https://github.com/androidx/media/blob/1.11.0/libraries/effect/src/main/java/androidx/media3/effect/BaseGlShaderProgram.java
 */
internal class FrameOutputShaderProgram(
    private val output: FrameOutput,
    useHdr: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, TEXTURE_POOL_CAPACITY) {

    private val copy = GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
        setBufferAttribute(POSITION, GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
    }

    private var width = 0
    private var height = 0

    /** The output is the input's size: whether an effect may change resolution is open (ADR-0022 rule 10). */
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val shown = output.process(VideoFrame(inputTexId, width, height), presentationTimeUs)
        copy.use()
        copy.setSamplerTexIdUniform(SAMPLER, shown.textureId, /* texUnitIndex= */ 0)
        copy.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ QUAD_VERTICES)
        GlUtil.checkGlError()
    }

    override fun release() {
        super.release()
        copy.delete()
    }

    private companion object {

        /**
         * One output texture in flight. The consumer's work is the expensive part of a frame, and a
         * deeper pool only lets the pipeline run further ahead of the display, which on a player is
         * latency and memory rather than throughput; Media3's own single-stage programs use one.
         */
        const val TEXTURE_POOL_CAPACITY = 1

        /** The four corners `GlUtil.getNormalizedCoordinateBounds` lists, drawn as a triangle strip. */
        const val QUAD_VERTICES = 4

        const val POSITION = "aFramePosition"
        const val SAMPLER = "uTexSampler"

        /**
         * A full-frame quad, with texture coordinates derived from the clip-space corners.
         *
         * ref: Media3's `vertex_shader_transformation_es2.glsl`, without its transformation matrices,
         * since a copy transforms nothing:
         * https://github.com/androidx/media/blob/1.11.0/libraries/effect/src/main/res/raw/vertex_shader_transformation_es2.glsl
         */
        const val VERTEX_SHADER = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """

        /**
         * A straight copy of the returned texture. `highp`, because an HDR pipeline's textures carry
         * linear BT.2020 in half floats and a medium-precision sampler would band them.
         */
        const val FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexSamplingCoord;
            void main() {
              gl_FragColor = texture2D(uTexSampler, vTexSamplingCoord);
            }
        """
    }
}
