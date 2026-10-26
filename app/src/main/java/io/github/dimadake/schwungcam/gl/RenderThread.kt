package io.github.dimadake.schwungcam.gl

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.dimadake.schwungcam.sync.StampedScreen

/**
 * Owns the GL context. Each camera frame is drawn once into the preview and,
 * while recording, once into the encoder surface with its sensor timestamp as
 * the presentation time, so the encoded frame keeps its exposure time.
 */
class RenderThread(private val onCameraTexture: (SurfaceTexture) -> Unit) {
    private val thread = HandlerThread("gl").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var comp: Compositor
    private lateinit var pbuf: EGLSurface
    private lateinit var camTex: SurfaceTexture
    private val texMatrix = FloatArray(16)

    private var preview: EGLSurface? = null
    private var previewW = 0; private var previewH = 0
    private var encoder: EGLSurface? = null
    private var encW = 0; private var encH = 0
    private var outAspect = 16f / 9f
    private var camW = 0; private var camH = 0; private var camSensor = 0; private var camUnmirror = false
    private var displayDeg = 90

    @Volatile var overlay = Overlay()
    @Volatile var onFrameDrawn: ((Long) -> Unit)? = null

    private val screens = ArrayDeque<StampedScreen>()
    private var shownScreen: StampedScreen? = null

    init {
        handler.post {
            egl = EglCore()
            pbuf = egl.pbuffer()
            egl.makeCurrent(pbuf)
            comp = Compositor()
            camTex = SurfaceTexture(comp.cameraTex)
            camTex.setOnFrameAvailableListener({ drawFrame() }, handler)
            onCameraTexture(camTex)
        }
    }

    fun setOutput(w: Int, h: Int) = handler.post {
        outAspect = w.toFloat() / h
        applyGeometry()
    }

    fun setCamera(bufW: Int, bufH: Int, sensorDeg: Int, unmirror: Boolean) = handler.post {
        camW = bufW; camH = bufH; camSensor = sensorDeg; camUnmirror = unmirror
        camTex.setDefaultBufferSize(bufW, bufH)
        applyGeometry()
    }

    /** Surface.ROTATION_* in degrees; the picture stays upright whichever way the phone is held. */
    fun setDisplayRotation(deg: Int) = handler.post { displayDeg = deg; applyGeometry() }

    private fun applyGeometry() {
        if (camW > 0) comp.setCameraGeometry(camW, camH, camSensor, displayDeg, camUnmirror, outAspect)
    }

    fun setPreview(surface: Surface?, w: Int, h: Int) = handler.post {
        preview?.let { egl.makeCurrent(pbuf); egl.release(it) }
        preview = surface?.let { egl.windowSurface(it) }
        previewW = w; previewH = h
    }

    /** [surface] is MediaCodec's input surface; null stops feeding it. Blocks until applied. */
    fun setEncoder(surface: Surface?, w: Int, h: Int) {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            encoder?.let { egl.makeCurrent(pbuf); egl.release(it) }
            encoder = surface?.let { egl.windowSurface(it) }
            encW = w; encH = h
            done.countDown()
        }
        done.await()
    }

    fun pushScreen(s: StampedScreen) = handler.post {
        screens.addLast(s)
        while (screens.size > 32) screens.removeFirst()
    }

    /** The newest screen frame the Move showed at or before the exposure [t]. */
    private fun screenFor(t: Long): StampedScreen? {
        var pick: StampedScreen? = null
        for (s in screens) if (s.stampNs <= t) pick = s
        return pick ?: screens.firstOrNull()
    }

    private fun drawFrame() {
        egl.makeCurrent(pbuf)
        camTex.updateTexImage()
        camTex.getTransformMatrix(texMatrix)
        val ts = camTex.timestamp
        val scr = screenFor(ts)
        if (scr != null && scr !== shownScreen) { comp.uploadScreen(scr.lum); shownScreen = scr }
        val ov = overlay
        preview?.let { p ->
            egl.makeCurrent(p)
            // letterbox the output aspect inside the view
            var vw = previewW; var vh = (previewW / outAspect).toInt()
            if (vh > previewH) { vh = previewH; vw = (previewH * outAspect).toInt() }
            comp.draw(texMatrix, ov, scr != null, (previewW - vw) / 2, (previewH - vh) / 2, vw, vh, previewW, previewH)
            egl.swap(p)
        }
        encoder?.let { e ->
            egl.makeCurrent(e)
            comp.draw(texMatrix, ov, scr != null, 0, 0, encW, encH, encW, encH)
            egl.presentationTime(e, ts)
            egl.swap(e)
            onFrameDrawn?.invoke(ts)
        }
    }

    /** Content rectangle of the preview inside a view of this size: x, y, w, h. */
    fun previewRect(viewW: Int, viewH: Int): IntArray {
        var vw = viewW; var vh = (viewW / outAspect).toInt()
        if (vh > viewH) { vh = viewH; vw = (viewH * outAspect).toInt() }
        return intArrayOf((viewW - vw) / 2, (viewH - vh) / 2, vw, vh)
    }

    fun release() {
        handler.post {
            preview?.let { egl.release(it) }; encoder?.let { egl.release(it) }
            camTex.release()
            EGL14.eglMakeCurrent(egl.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            egl.release(pbuf); egl.release()
            thread.quitSafely()
        }
    }
}
