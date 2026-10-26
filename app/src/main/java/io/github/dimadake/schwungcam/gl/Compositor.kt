package io.github.dimadake.schwungcam.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import io.github.dimadake.schwungcam.stream.SCREEN_H
import io.github.dimadake.schwungcam.stream.SCREEN_W
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Draws the camera frame and the keyed Move screen into whatever surface is current. */
class Compositor {
    val cameraTex: Int
    private val screenTex: Int
    private val camProg = program(CAM_VS, CAM_FS)
    private val scrProg = program(SCR_VS, SCR_FS)
    private val quad: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val camUv = FloatArray(8)
    private val camUvBuf: FloatBuffer = floats(*camUv)
    private val scrPos: FloatBuffer = floats(*FloatArray(8))
    private val scrUv: FloatBuffer = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
    private val lumBuf: ByteBuffer = ByteBuffer.allocateDirect(SCREEN_W * SCREEN_H)

    init {
        val t = IntArray(2)
        GLES20.glGenTextures(2, t, 0)
        cameraTex = t[0]; screenTex = t[1]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, screenTex)
        // nearest: the Move's pixels stay square-edged at any overlay size
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, SCREEN_W, SCREEN_H, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, lumBuf)
    }

    fun setCameraGeometry(bufW: Int, bufH: Int, sensorDeg: Int, displayDeg: Int, unmirror: Boolean, outAspect: Float) {
        CameraUv.corners(bufW, bufH, sensorDeg, displayDeg, unmirror, outAspect).copyInto(camUv)
        camUvBuf.position(0); camUvBuf.put(camUv); camUvBuf.position(0)
    }

    fun uploadScreen(lum: ByteArray) {
        lumBuf.position(0); lumBuf.put(lum); lumBuf.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, screenTex)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, SCREEN_W, SCREEN_H,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, lumBuf)
    }

    /** Draws a full frame into the viewport (x, y, w, h) of the current surface. */
    fun draw(texMatrix: FloatArray, overlay: Overlay, hasScreen: Boolean, vx: Int, vy: Int, vw: Int, vh: Int, surfW: Int, surfH: Int) {
        GLES20.glViewport(0, 0, surfW, surfH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glUseProgram(camProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(camProg, "uTex"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(camProg, "uTexMatrix"), 1, false, texMatrix, 0)
        attrib(camProg, "aPos", quad); attrib(camProg, "aUv", camUvBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        if (!overlay.enabled || !hasScreen) return
        val aspect = vw.toFloat() / vh
        val (l, t, r, b) = overlay.ndc(aspect).let { listOf(it[0], it[1], it[2], it[3]) }
        scrPos.position(0); scrPos.put(floatArrayOf(l, b, r, b, l, t, r, t)); scrPos.position(0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(scrProg)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, screenTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(scrProg, "uTex"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(scrProg, "uKey"), overlay.key.ordinal)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(scrProg, "uOpacity"), overlay.opacity)
        attrib(scrProg, "aPos", scrPos); attrib(scrProg, "aUv", scrUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun attrib(prog: Int, name: String, buf: FloatBuffer) {
        val loc = GLES20.glGetAttribLocation(prog, name)
        GLES20.glEnableVertexAttribArray(loc)
        buf.position(0)
        GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, buf)
    }

    companion object {
        private fun floats(vararg f: Float): FloatBuffer =
            ByteBuffer.allocateDirect(f.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(f); position(0) }

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }

        private fun program(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "link: " + GLES20.glGetProgramInfoLog(p) }
            return p
        }

        private const val CAM_VS = """
            attribute vec4 aPos; attribute vec2 aUv; uniform mat4 uTexMatrix; varying vec2 vUv;
            void main() { gl_Position = aPos; vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy; }"""
        private const val CAM_FS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES uTex; varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTex, vUv); }"""
        private const val SCR_VS = """
            attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv;
            void main() { gl_Position = aPos; vUv = aUv; }"""
        // uKey follows KeyMode's order: none, black transparent, white transparent
        private const val SCR_FS = """
            precision mediump float; uniform sampler2D uTex; uniform int uKey; uniform float uOpacity; varying vec2 vUv;
            void main() {
                float l = texture2D(uTex, vUv).r;
                float a = uKey == 1 ? l : (uKey == 2 ? 1.0 - l : 1.0);
                gl_FragColor = vec4(vec3(l), a * uOpacity);
            }"""
    }
}
