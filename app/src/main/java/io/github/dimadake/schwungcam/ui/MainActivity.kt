package io.github.dimadake.schwungcam.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.dimadake.schwungcam.R
import io.github.dimadake.schwungcam.camera.CameraCatalog
import io.github.dimadake.schwungcam.camera.CameraChoice
import io.github.dimadake.schwungcam.camera.CameraController
import io.github.dimadake.schwungcam.gl.KeyMode
import io.github.dimadake.schwungcam.gl.Overlay
import io.github.dimadake.schwungcam.gl.RenderThread
import io.github.dimadake.schwungcam.record.FinalResult
import io.github.dimadake.schwungcam.record.Finalizer
import io.github.dimadake.schwungcam.record.PcmRecorder
import io.github.dimadake.schwungcam.record.TakeController
import io.github.dimadake.schwungcam.stream.MoveStream
import io.github.dimadake.schwungcam.stream.StreamState
import io.github.dimadake.schwungcam.sync.ClockEnvelope
import io.github.dimadake.schwungcam.sync.ScreenStamper

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    private val envelope = ClockEnvelope()
    private val pcm = PcmRecorder()
    private lateinit var render: RenderThread
    private lateinit var camera: CameraController
    private lateinit var stream: MoveStream
    private lateinit var stamper: ScreenStamper
    private lateinit var takes: TakeController

    private var cameraTex: SurfaceTexture? = null
    private var choices: List<CameraChoice> = emptyList()
    private var streamState = StreamState.OFFLINE
    private var recStartMs = 0L
    private var finalizing = false

    private var wifiLock: WifiManager.WifiLock? = null
    private var mcLock: WifiManager.MulticastLock? = null

    private lateinit var preview: SurfaceView
    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var recordBtn: Button
    private lateinit var cameraSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        timer = findViewById(R.id.timer)
        recordBtn = findViewById(R.id.record)
        cameraSpinner = findViewById(R.id.camera)

        camera = CameraController(getSystemService(CameraManager::class.java))
        render = RenderThread { tex -> ui.post { cameraTex = tex; openCamera() } }
        render.overlay = prefs.overlay
        prefs.resolution.let { render.setOutput(it.w, it.h) }
        stamper = ScreenStamper(envelope) { render.pushScreen(it) }
        takes = TakeController(cacheDir, render, pcm, envelope, Finalizer(contentResolver))
        stream = MoveStream(
            host = { prefs.host },
            clockNs = { camera.clockNs() },
            onScreen = { stamper.onScreen(it) },
            onPcm = { envelope.add(it.arrivalNs, it.endPos); pcm.onChunk(it); stamper.onPcm(it) },
            onState = { s -> if (s != streamState) { streamState = s; ui.post { updateStatus() } } },
        )

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {}
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) = render.setPreview(h.surface, w, hh).let { }
            override fun surfaceDestroyed(h: SurfaceHolder) = render.setPreview(null, 0, 0).let { }
        })
        setupOverlayTouch()
        setupControls()
        ui.post(object : Runnable { override fun run() { updateStatus(); ui.postDelayed(this, 500) } })
    }

    // ---------------- camera ----------------

    private fun setupCameraList() {
        choices = CameraCatalog.list(getSystemService(CameraManager::class.java))
        cameraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, choices.map { it.label })
        val saved = choices.indexOfFirst { key(it) == prefs.camera }
        cameraSpinner.setSelection(if (saved >= 0) saved else 0, false)
        cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val k = key(choices[pos])
                if (k != prefs.camera) { prefs.camera = k; openCamera() }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun key(c: CameraChoice) = c.id + "/" + (c.physicalId ?: "")

    private fun openCamera() {
        val tex = cameraTex ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (choices.isEmpty()) setupCameraList()
        val choice = choices.firstOrNull { key(it) == prefs.camera } ?: choices.firstOrNull() ?: return
        val res = prefs.resolution
        camera.open(choice, tex, res.w, res.h, displayDegrees(), onReady = { o ->
            // a different camera may stamp on a different clock; re-learn the offset
            envelope.clear()
            render.setCamera(o.bufSize.width, o.bufSize.height, o.rotationDeg)
        }, onError = { msg -> ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } })
        camera.setLocked(prefs.exposureLock)
    }

    private fun displayDegrees(): Int {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_90 else windowManager.defaultDisplay.rotation
        return when (r) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    }

    // ---------------- controls ----------------

    private fun setupControls() {
        val ae = findViewById<Switch>(R.id.aelock)
        ae.isChecked = prefs.exposureLock
        ae.setOnCheckedChangeListener { _, on -> prefs.exposureLock = on; camera.setLocked(on) }

        val ov = findViewById<Switch>(R.id.overlay)
        ov.isChecked = prefs.overlay.enabled
        ov.setOnCheckedChangeListener { _, on -> setOverlay(prefs.overlay.copy(enabled = on)) }

        val key = findViewById<Spinner>(R.id.key)
        key.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("None", "Black", "White"))
        key.setSelection(prefs.overlay.key.ordinal, false)
        key.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = setOverlay(prefs.overlay.copy(key = KeyMode.entries[pos]))
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val op = findViewById<SeekBar>(R.id.opacity)
        op.progress = (prefs.overlay.opacity * 100).toInt()
        op.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) setOverlay(prefs.overlay.copy(opacity = v / 100f)) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        recordBtn.setOnClickListener { if (takes.recording) stopTake() else startTake() }
        findViewById<Button>(R.id.settings).setOnClickListener { showSettings() }
    }

    private fun setOverlay(o: Overlay) {
        val aspect = prefs.resolution.let { it.w.toFloat() / it.h }
        val c = o.clamped(aspect)
        prefs.overlay = c
        render.overlay = c
    }

    private fun setupOverlayTouch() {
        var lastX = 0f; var lastY = 0f
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val aspect = prefs.resolution.let { it.w.toFloat() / it.h }
                setOverlay(prefs.overlay.scaled(d.scaleFactor, aspect)); return true
            }
        })
        preview.setOnTouchListener { v, e ->
            if (!prefs.overlay.enabled) return@setOnTouchListener false
            scale.onTouchEvent(e)
            val rect = render.previewRect(v.width, v.height)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_POINTER_DOWN -> { lastX = e.getX(0); lastY = e.getY(0) }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scale.isInProgress && rect[2] > 0) {
                    val aspect = prefs.resolution.let { it.w.toFloat() / it.h }
                    setOverlay(prefs.overlay.dragged((e.x - lastX) / rect[2], (e.y - lastY) / rect[3], aspect))
                    lastX = e.x; lastY = e.y
                }
            }
            true
        }
    }

    private fun showSettings() {
        if (takes.recording) return
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val host = v.findViewById<EditText>(R.id.host).apply { setText(prefs.host) }
        val res = v.findViewById<Spinner>(R.id.resolution).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Resolution.entries.map { it.label })
            setSelection(prefs.resolution.ordinal)
        }
        val master = v.findViewById<Switch>(R.id.master).apply { isChecked = prefs.masterOn }
        val glue = v.findViewById<Switch>(R.id.glue).apply { isChecked = prefs.glue }
        val target = v.findViewById<Spinner>(R.id.target).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Target.entries.map { it.label })
            setSelection(prefs.target.ordinal)
        }
        val keep = v.findViewById<Switch>(R.id.keepwav).apply { isChecked = prefs.keepWav }
        AlertDialog.Builder(this).setTitle(R.string.settings).setView(v)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newHost = host.text.toString().trim().ifEmpty { "move.local" }
                if (newHost != prefs.host) { prefs.host = newHost; stream.reconnect() }
                val r = Resolution.entries[res.selectedItemPosition]
                if (r != prefs.resolution) { prefs.resolution = r; render.setOutput(r.w, r.h); setOverlay(prefs.overlay); openCamera() }
                prefs.masterOn = master.isChecked; prefs.glue = glue.isChecked
                prefs.target = Target.entries[target.selectedItemPosition]; prefs.keepWav = keep.isChecked
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ---------------- recording ----------------

    private fun startTake() {
        if (finalizing) return
        val r = prefs.resolution
        try {
            takes.start(r.w, r.h, r.bitrate)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot record: ${e.message}", Toast.LENGTH_LONG).show(); return
        }
        recStartMs = SystemClock.elapsedRealtime()
        recordBtn.setText(R.string.stop)
        cameraSpinner.isEnabled = false
        if (streamState != StreamState.LIVE) Toast.makeText(this, "No Schwung audio yet — the take may be silent", Toast.LENGTH_LONG).show()
    }

    private fun stopTake() {
        finalizing = true
        recordBtn.setText(R.string.record)
        recordBtn.isEnabled = false
        cameraSpinner.isEnabled = true
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; setPadding(48, 32, 48, 32) }
        val dlg = AlertDialog.Builder(this).setTitle("Mastering…").setView(bar).setCancelable(false).show()
        takes.stop(prefs.master(), prefs.keepWav, progress = { p -> ui.post { bar.progress = (p * 100).toInt() } }) { res ->
            ui.post {
                finalizing = false
                recordBtn.isEnabled = true
                dlg.dismiss()
                res.fold(::showResult) { e -> AlertDialog.Builder(this).setTitle("Recording failed").setMessage(e.message ?: e.toString()).setPositiveButton(android.R.string.ok, null).show() }
            }
        }
    }

    private fun showResult(r: FinalResult) {
        if (isFinishing || isDestroyed) return
        val lines = ArrayList<String>()
        lines.add("Saved Movies/SchwungCam/${r.name}.mp4")
        r.report?.let { rep ->
            if (!rep.outputLufs.isNaN() && rep.outputLufs.isFinite()) lines.add("Audio: %.1f LUFS (was %.1f), gain %+.1f dB".format(rep.outputLufs, rep.inputLufs, rep.gainDb))
        }
        r.warning?.let { lines.add("\n⚠ $it") }
        AlertDialog.Builder(this).setTitle("Take saved").setMessage(lines.joinToString("\n"))
            .setPositiveButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, r.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share video"))
            }
            .setNegativeButton("Close", null).show()
    }

    private fun updateStatus() {
        val s = when (streamState) {
            StreamState.OFFLINE -> "Move offline (${prefs.host})"
            StreamState.CONNECTING -> "Connecting to ${prefs.host}…"
            StreamState.SCREEN_ONLY -> "Screen only — turn on Mirror Display in Schwung for audio"
            StreamState.LIVE -> "Move live · audio + screen"
        }
        status.text = s
        if (takes.recording) {
            val t = (SystemClock.elapsedRealtime() - recStartMs) / 1000
            timer.text = "● %02d:%02d".format(t / 60, t % 60)
        } else if (!finalizing) timer.text = "00:00"
    }

    // ---------------- lifecycle ----------------

    override fun onResume() {
        super.onResume()
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // power-save batching would add delay to the stream that the envelope must then reject
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "schwungcam").apply { acquire() }
        mcLock = wm.createMulticastLock("schwungcam-mdns").apply { setReferenceCounted(false); acquire() }
        stream.start()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        } else openCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera()
        else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        if (takes.recording) stopTake()
        camera.close()
        stream.stop()
        wifiLock?.release(); wifiLock = null
        mcLock?.release(); mcLock = null
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        camera.release()
        render.release()
        super.onDestroy()
    }
}
