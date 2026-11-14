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
import java.util.concurrent.atomic.AtomicLong

/**
 * Offline GLES2 anime-style edge reconstruction for Media3's 2D frame textures.
 * This is a custom sharpening approximation, not the official Anime4K shader suite
 * and not AI super-resolution.
 */
@UnstableApi
class AnimeLineShaderEffect(
    private val highQuality: Boolean,
    initialEnhancementEnabled: Boolean = false
) : GlEffect {
    private val enhancementEnabled = AtomicBoolean(initialEnhancementEnabled)
    private val scanStartNs = AtomicLong(0L)

    /** The render thread reads this on each decoded frame; no player restart is needed. */
    fun setEnhancedEnabled(enabled: Boolean) {
        enhancementEnabled.set(enabled)
        if (!enabled) scanStartNs.set(0L)
    }

    /** Starts a two-second original-vs-enhanced wipe, synchronized to incoming frames. */
    fun startEnhancementScan() {
        enhancementEnabled.set(true)
        // Defer the clock start until the first actual rendered frame, not while buffering.\n        scanStartNs.set(-1L)
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return AnimeLineShaderProgram(context, useHdr, highQuality, enhancementEnabled, scanStartNs)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = false
}

@UnstableApi
private class AnimeLineShaderProgram(
    context: Context,
    useHdr: Boolean,
    private val highQuality: Boolean,
    private val enhancementEnabled: AtomicBoolean,
    private val scanStartNs: AtomicLong
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

            var scanStarted = scanStartNs.get()
            if (scanStarted == -1L) {
                scanStartNs.compareAndSet(-1L, System.nanoTime())
                scanStarted = scanStartNs.get()
            }
            val scanning = scanStarted > 0L && enhancementEnabled.get()
            val split = if (scanning) {
                ((System.nanoTime() - scanStarted).toDouble() / 2_000_000_000.0).toFloat()
                    .coerceIn(0f, 1f)
            } else {
                1f
            }
            if (scanning && split >= 1f) scanStartNs.compareAndSet(scanStarted, 0L)

            val enabled = enhancementEnabled.get()
            glProgram.setFloatUniform("uStrength", when {
                highQuality -> 2.6f
                else -> 1.7f
            })
            glProgram.setFloatUniform("uContrast", if (enabled && highQuality) 1.13f else if (enabled) 1.06f else 1.0f)
            glProgram.setFloatUniform("uSaturation", if (enabled && highQuality) 1.22f else if (enabled) 1.10f else 1.0f)
            glProgram.setFloatUniform("uSplitX", split)
            glProgram.setFloatUniform("uScanActive", if (scanning) 1f else 0f)
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
