package com.animehd.player

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Original, offline GLES2 line-enhancement shader for anime-style artwork.
 *
 * This is a lightweight edge-aware sharpening/deblurring approximation. It is
 * not the official Anime4K shader suite and does not create AI super-resolution.
 */
@UnstableApi
class AnimeLineShaderEffect(
    private val highQuality: Boolean,
    initialEnhancementEnabled: Boolean = false
) : GlEffect {
    private val enhancementEnabled = AtomicBoolean(initialEnhancementEnabled)

    /** Safe to call from the UI thread; the next rendered frame picks up the change. */
    fun setEnhancedEnabled(enabled: Boolean) {
        enhancementEnabled.set(enabled)
    }
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return AnimeLineShaderProgram(context, useHdr, highQuality, enhancementEnabled)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

@UnstableApi
private class AnimeLineShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val highQuality: Boolean,
    private val enhancementEnabled: AtomicBoolean
) : BaseGlShaderProgram(useHdr, 1) {

    private val fragmentPath = if (highQuality) {
        "shaders/anime_line_high_es2.glsl"
    } else {
        "shaders/anime_line_lite_es2.glsl"
    }

    private val glProgram: GlProgram = try {
        GlProgram(context, "shaders/vertex_shader_transformation_es2.glsl", fragmentPath)
    } catch (e: IOException) {
        throw VideoFrameProcessingException(e)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private var texelWidth = 1f
    private var texelHeight = 1f

    init {
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        glProgram.setFloatsUniform("uTransformationMatrix", identity)
        glProgram.setFloatsUniform("uTexTransformationMatrix", identity)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        texelWidth = 1f / inputWidth.coerceAtLeast(1).toFloat()
        texelHeight = 1f / inputHeight.coerceAtLeast(1).toFloat()
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatsUniform("uTexelSize", floatArrayOf(texelWidth, texelHeight))
            val enhanced = enhancementEnabled.get()
            glProgram.setFloatUniform("uStrength", when {
                highQuality && enhanced -> 0.78f
                highQuality -> 0.42f
                enhanced -> 0.52f
                else -> 0.22f
            })
            glProgram.setFloatUniform("uContrast", if (enhanced) 1.12f else 1.0f)
            glProgram.setFloatUniform("uSaturation", if (enhanced) 1.18f else 1.0f)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        try {
            super.release()
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
