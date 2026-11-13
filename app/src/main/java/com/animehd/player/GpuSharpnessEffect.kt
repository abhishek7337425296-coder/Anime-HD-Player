package com.animehd.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ConvolutionFunction1D
import androidx.media3.effect.SeparableConvolution

/**
 * GPU convolution-based edge sharpening for Media3 playback.
 * It changes frame pixels without changing the displayed frame dimensions.
 * This is conventional sharpening, not AI super-resolution.
 */
@UnstableApi
class GpuSharpnessEffect(
    private val amount: Float = 0.16f
) : SeparableConvolution() {

    private val kernel = object : ConvolutionFunction1D {
        override fun domainStart(): Float = -1.5f
        override fun domainEnd(): Float = 1.5f

        override fun value(samplePosition: Float): Float {
            val distance = kotlin.math.abs(samplePosition)
            return when {
                distance < 0.5f -> 1f + 2f * amount
                distance <= 1.5f -> -amount
                else -> 0f
            }
        }
    }

    override fun getConvolution(presentationTimeUs: Long): ConvolutionFunction1D = kernel
}
