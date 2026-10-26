package io.github.dimadake.schwungcam.camera

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/** Camera2 into the render thread's SurfaceTexture, with the 30 fps and AE/AWB-lock policy. */
class CameraController(private val cm: CameraManager) {
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    @Volatile private var locked = false

    /** The clock Camera2 stamps frames with; stream arrivals must use the same one. */
    @Volatile var clockNs: () -> Long = { SystemClock.elapsedRealtimeNanos() }
        private set

    /** [unmirror]: the stream is mirrored (a front lens) and the compositor must undo it. */
    class Opened(val bufSize: Size, val sensorDeg: Int, val unmirror: Boolean)

    /**
     * Opens [choice]. [onReady] gets the buffer size and orientation before the
     * session starts, so the texture can be sized first.
     */
    @SuppressLint("MissingPermission")
    fun open(choice: CameraChoice, tex: SurfaceTexture, outW: Int, outH: Int, onReady: (Opened) -> Unit, onError: (String) -> Unit) {
        handler.post {
            closeNow()
            val ch = cm.getCameraCharacteristics(choice.id)
            val lensCh = choice.physicalId?.let { cm.getCameraCharacteristics(it) } ?: ch
            val src = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            clockNs = if (src == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) {
                { SystemClock.elapsedRealtimeNanos() }
            } else {
                { System.nanoTime() }
            }
            val size = CameraCatalog.bufferSize(lensCh, outW, outH)
            val sensor = lensCh.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val front = lensCh.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
            val fps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.upper == 30 }?.maxByOrNull { it.lower } ?: Range(30, 30)
            // the video is never mirrored: preview and file must show the same picture. The
            // compositor undoes the stream's mirror; MIRROR_MODE_NONE would be the obvious way, but on a
            // Pixel 9 it drops the flip in buffer space, which on a quarter-turned sensor comes out as a
            // vertical flip: the picture lands upside down
            onReady(Opened(size, sensor, front))
            tex.setDefaultBufferSize(size.width, size.height)
            val surface = Surface(tex)
            try {
                cm.openCamera(choice.id, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        // the camera can be taken away mid-setup (screen locked, app paused); that is a
                        // closed camera, not a crash, and the next onResume opens it again
                        try { configure(d) } catch (e: Exception) {
                            Log.w("SchwungCam", "configure", e)
                            d.close(); if (device === d) device = null
                        }
                    }
                    private fun configure(d: CameraDevice) {
                        val oc = OutputConfiguration(surface)
                        choice.physicalId?.let { oc.setPhysicalCameraId(it) }
                        val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                            // a fixed rate: auto-exposure may not stretch frames in low light
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        }
                        request = rb
                        d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(oc), executor,
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(s: CameraCaptureSession) { session = s; applyLock() }
                                override fun onConfigureFailed(s: CameraCaptureSession) { onError("camera session failed") }
                            }))
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close(); if (device === d) device = null }
                    override fun onError(d: CameraDevice, error: Int) { d.close(); if (device === d) device = null; onError("camera error $error") }
                }, )
            } catch (e: Exception) {
                Log.w("SchwungCam", "open", e); onError(e.message ?: "camera open failed")
            }
        }
    }

    /** Exposure lock freezes white balance too, or colour would still drift mid-take. */
    fun setLocked(on: Boolean) { locked = on; handler.post { applyLock() } }

    private fun applyLock() {
        val s = session ?: return
        val rb = request ?: return
        rb.set(CaptureRequest.CONTROL_AE_LOCK, locked)
        rb.set(CaptureRequest.CONTROL_AWB_LOCK, locked)
        try { s.setRepeatingRequest(rb.build(), null, handler) } catch (e: Exception) { Log.w("SchwungCam", "repeat", e) }
    }

    private fun closeNow() {
        try { session?.close() } catch (_: Exception) { }
        session = null; request = null
        device?.close(); device = null
    }

    fun close() = handler.post { closeNow() }

    fun release() { handler.post { closeNow(); thread.quitSafely() } }
}
