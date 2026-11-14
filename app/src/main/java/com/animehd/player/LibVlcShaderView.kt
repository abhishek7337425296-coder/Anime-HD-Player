package com.animehd.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.Surface
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * LibVLC-owned decoder output -> SurfaceTexture external OES texture -> GLES2 shader.
 * All GL calls and SurfaceTexture.updateTexImage() run on the GLSurfaceView GL thread.
 * This is a custom real-time anime-style enhancement approximation, not official Anime4K
 * and not AI super-resolution.
 */
class LibVlcShaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    interface Callback {
        fun onSurfaceReady(surface: Surface)
        fun onRendererError(message: String)
        fun onSurfaceReleased()
    }

    @Volatile var callback: Callback? = null
    @Volatile private var mode = 0
    @Volatile private var scanX = 1f
    @Volatile private var videoWidth = 0
    @Volatile private var videoHeight = 0
    @Volatile private var visibleWidth = 0
    @Volatile private var visibleHeight = 0
    @Volatile private var sarNum = 1
    @Volatile private var sarDen = 1

    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameAvailable = AtomicBoolean(false)
    private val frameLock = Any()
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f
            ))
            position(0)
        }

    private var program = 0
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var outputSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private var viewWidth = 1
    private var viewHeight = 1
    private var positionLocation = -1
    private var texCoordLocation = -1
    private var mvpLocation = -1
    private var stLocation = -1
    private var samplerLocation = -1
    private var modeLocation = -1
    private var texelLocation = -1
    private var scanLocation = -1

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        Matrix.setIdentityM(stMatrix, 0)
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    fun setMode(value: Int) {
        mode = value.coerceIn(0, 2)
        requestRender()
    }

    fun setScanX(value: Float) {
        scanX = value.coerceIn(0f, 1f)
        requestRender()
    }

    fun setVideoSize(width: Int, height: Int, visibleW: Int, visibleH: Int, sarN: Int, sarD: Int) {
        videoWidth = width.coerceAtLeast(1)
        videoHeight = height.coerceAtLeast(1)
        visibleWidth = visibleW.coerceAtLeast(1)
        visibleHeight = visibleH.coerceAtLeast(1)
        sarNum = sarN.coerceAtLeast(1)
        sarDen = sarD.coerceAtLeast(1)
        requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            releaseSurfaceObjects(notify = true)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
            mvpLocation = GLES20.glGetUniformLocation(program, "uMvpMatrix")
            stLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
            samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
            modeLocation = GLES20.glGetUniformLocation(program, "uMode")
            texelLocation = GLES20.glGetUniformLocation(program, "uTexelSize")
            scanLocation = GLES20.glGetUniformLocation(program, "uScanX")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(textureId)
            surfaceTexture = st
            st.setOnFrameAvailableListener {
                synchronized(frameLock) { frameAvailable.set(true) }
                requestRender()
            }
            val surface = Surface(st)
            outputSurface = surface
            mainHandler.post { callback?.onSurfaceReady(surface) }
        } catch (e: Exception) {
            reportError("OpenGL surface initialization failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(1)
        viewHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        updateAspectMatrix()
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val st = surfaceTexture ?: return
        if (frameAvailable.compareAndSet(true, false)) {
            try {
                st.updateTexImage()
                st.getTransformMatrix(stMatrix)
            } catch (e: RuntimeException) {
                reportError("Video texture update failed: ${e.message ?: "unknown error"}")
                return
            }
        }
        if (textureId == 0 || program == 0) return
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(samplerLocation, 0)
        GLES20.glUniform1i(modeLocation, mode)
        GLES20.glUniform1f(scanLocation, scanX)
        val w = videoWidth.coerceAtLeast(1).toFloat()
        val h = videoHeight.coerceAtLeast(1).toFloat()
        GLES20.glUniform2f(texelLocation, 1f / w, 1f / h)
        GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(stLocation, 1, false, stMatrix, 0)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
    }

    private fun updateAspectMatrix() {
        Matrix.setIdentityM(mvpMatrix, 0)
        val vw = visibleWidth.takeIf { it > 0 } ?: videoWidth
        val vh = visibleHeight.takeIf { it > 0 } ?: videoHeight
        if (vw <= 0 || vh <= 0) return
        val pixelAspect = sarNum.toFloat() / sarDen.coerceAtLeast(1).toFloat()
        val videoAspect = (vw.toFloat() * pixelAspect) / vh.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.coerceAtLeast(1).toFloat()
        if (videoAspect > viewAspect) {
            Matrix.scaleM(mvpMatrix, 0, 1f, viewAspect / videoAspect, 1f)
        } else {
            Matrix.scaleM(mvpMatrix, 0, videoAspect / viewAspect, 1f, 1f)
        }
    }

    private fun reportError(message: String) {
        mainHandler.post { callback?.onRendererError(message) }
    }

    private fun releaseSurfaceObjects(notify: Boolean) {
        try { outputSurface?.release() } catch (_: Exception) { }
        outputSurface = null
        try { surfaceTexture?.setOnFrameAvailableListener(null) } catch (_: Exception) { }
        try { surfaceTexture?.release() } catch (_: Exception) { }
        surfaceTexture = null
        frameAvailable.set(false)
        if (notify) mainHandler.post { callback?.onSurfaceReleased() }
    }

    override fun onDetachedFromWindow() {
        try {
            queueEvent {
                releaseSurfaceObjects(notify = true)
                if (program != 0) GLES20.glDeleteProgram(program)
                program = 0
                if (textureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                    textureId = 0
                }
            }
        } catch (_: Exception) { }
        super.onDetachedFromWindow()
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val result = GLES20.glCreateProgram()
        GLES20.glAttachShader(result, vs)
        GLES20.glAttachShader(result, fs)
        GLES20.glLinkProgram(result)
        val status = IntArray(1)
        GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(result)
            GLES20.glDeleteProgram(result)
            throw IllegalStateException("Shader link failed: $log")
        }
        return result
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform int uMode;
            uniform vec2 uTexelSize;
            uniform float uScanX;
            varying vec2 vTexCoord;

            vec3 src(vec2 p) {
                return texture2D(uTexture, clamp(p, vec2(0.001), vec2(0.999))).rgb;
            }
            float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            vec3 enhanceLite(vec2 p, vec3 c) {
                vec3 n = src(p + vec2(0.0, uTexelSize.y));
                vec3 s = src(p - vec2(0.0, uTexelSize.y));
                vec3 e = src(p + vec2(uTexelSize.x, 0.0));
                vec3 w = src(p - vec2(uTexelSize.x, 0.0));
                vec3 blur4 = (n + s + e + w) * 0.25;
                vec3 detail = c - blur4;
                float edge = clamp(length(vec2(lum(e) - lum(w), lum(n) - lum(s))) * 3.2, 0.0, 1.0);
                vec3 sharpened = c + detail * 1.8;
                float darkLine = 1.0 - smoothstep(0.06, 0.28, lum(c));
                vec3 tightened = mix(sharpened, sharpened * 0.92, edge * darkLine * 0.12);
                return clamp(tightened, 0.0, 1.0);
            }

            vec3 enhanceHigh(vec2 p, vec3 c) {
                vec2 t = uTexelSize;
                vec3 n = src(p + vec2(0.0, t.y));
                vec3 s = src(p - vec2(0.0, t.y));
                vec3 e = src(p + vec2(t.x, 0.0));
                vec3 w = src(p - vec2(t.x, 0.0));
                vec3 ne = src(p + vec2(t.x, t.y));
                vec3 nw = src(p + vec2(-t.x, t.y));
                vec3 se = src(p + vec2(t.x, -t.y));
                vec3 sw = src(p - t);
                vec3 wide = (n + s + e + w + ne + nw + se + sw) * 0.125;
                vec3 detail = c - wide;
                float gx = lum(e) - lum(w);
                float gy = lum(n) - lum(s);
                float edge = clamp(length(vec2(gx, gy)) * 4.5, 0.0, 1.0);
                float dark = 1.0 - smoothstep(0.08, 0.32, lum(c));
                vec3 reconstructed = c + detail * 2.8;
                reconstructed = mix(reconstructed, reconstructed * 0.84, edge * dark * 0.20);
                float y = lum(reconstructed);
                vec3 color = mix(vec3(y), reconstructed, 1.18);
                color = (color - 0.5) * 1.12 + 0.5;
                return clamp(color, 0.0, 1.0);
            }

            void main() {
                vec3 original = src(vTexCoord);
                if (uMode == 0) {
                    gl_FragColor = vec4(original, 1.0);
                    return;
                }
                vec3 enhanced = uMode == 1 ? enhanceLite(vTexCoord, original) : enhanceHigh(vTexCoord, original);
                if (uScanX < 0.9999) {
                    if (vTexCoord.x >= uScanX) {
                        gl_FragColor = vec4(original, 1.0);
                        return;
                    }
                    float beam = 1.0 - smoothstep(0.0, 0.012, abs(vTexCoord.x - uScanX));
                    enhanced = mix(enhanced, vec3(0.65, 0.96, 1.0), beam * 0.82);
                    float halo = 1.0 - smoothstep(0.008, 0.045, abs(vTexCoord.x - uScanX));
                    enhanced += vec3(0.05, 0.30, 0.42) * halo * 0.35;
                }
                gl_FragColor = vec4(clamp(enhanced, 0.0, 1.0), 1.0);
            }
        """
    }
}
