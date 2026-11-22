package io.github.dimadake.schwungcam.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.display.DisplayManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.dimadake.schwungcam.R
import io.github.dimadake.schwungcam.ai.SurfaceSnapshot
import io.github.dimadake.schwungcam.camera.CameraCatalog
import io.github.dimadake.schwungcam.camera.CameraChoice
import io.github.dimadake.schwungcam.camera.CameraController
import io.github.dimadake.schwungcam.gl.KeyMode
import io.github.dimadake.schwungcam.gl.Overlay
import io.github.dimadake.schwungcam.gl.RenderThread
import io.github.dimadake.schwungcam.link.LinkChannel
import io.github.dimadake.schwungcam.link.LinkNative
import io.github.dimadake.schwungcam.link.StemRecorder
import io.github.dimadake.schwungcam.record.FinalResult
import io.github.dimadake.schwungcam.record.FinalizeService
import io.github.dimadake.schwungcam.record.Finalizer
import io.github.dimadake.schwungcam.record.PcmRecorder
import io.github.dimadake.schwungcam.record.TakeController
import io.github.dimadake.schwungcam.stream.MoveStream
import io.github.dimadake.schwungcam.stream.StreamState
import io.github.dimadake.schwungcam.sync.ClockEnvelope
import io.github.dimadake.schwungcam.sync.ScreenStamper
import io.github.dimadake.schwungcam.sync.TickStamper
import io.github.dimadake.schwungcam.viewer.TakesActivity

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())

    private val envelope = ClockEnvelope()
    private val pcm = PcmRecorder()
    private lateinit var render: RenderThread
    private lateinit var camera: CameraController
    private lateinit var stream: MoveStream
    private lateinit var stamper: ScreenStamper
    private lateinit var surfaceStamper: TickStamper<SurfaceSnapshot>
    private lateinit var takes: TakeController
    private lateinit var stems: StemRecorder

    private var cameraTex: SurfaceTexture? = null
    private var choices: List<CameraChoice> = emptyList()
    private var streamState = StreamState.OFFLINE
    private var recStartMs = 0L
    private var finalizing = false
    private var finalizeProgress = 0f
    // not saved across launches: a lock from an earlier session would be the wrong exposure for today's light
    private var exposureLock = false
    // the activity is rebuilt on a portrait/landscape turn, so this is fixed for its lifetime
    private var portrait = false
    private var lastTakeUri: Uri? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var mcLock: WifiManager.MulticastLock? = null

    private lateinit var preview: SurfaceView
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var timer: TextView
    private lateinit var shutter: ShutterButton
    private lateinit var lastTake: ImageView
    private lateinit var flip: ImageButton
    private lateinit var lenses: LinearLayout
    private lateinit var aeBtn: ImageButton
    private lateinit var overlayBtn: ImageButton
    private lateinit var settingsBtn: ImageButton
    private lateinit var panel: ScrollView
    private lateinit var panelBody: LinearLayout
    private var hostField: EditText? = null
    private var stemList: LinearLayout? = null
    // what the stem list shows, so the 500 ms refresh rebuilds it only on a change
    private var stemListShown: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        exposureLock = savedInstanceState?.getBoolean(KEY_AE) ?: false

        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        statusDot = findViewById(R.id.status_dot)
        timer = findViewById(R.id.timer)
        shutter = findViewById(R.id.record)
        lastTake = findViewById(R.id.last_take)
        flip = findViewById(R.id.flip)
        lenses = findViewById(R.id.lenses)
        aeBtn = findViewById(R.id.aelock)
        overlayBtn = findViewById(R.id.overlay)
        settingsBtn = findViewById(R.id.settings)
        panel = findViewById(R.id.panel)
        panelBody = findViewById(R.id.panel_body)
        lastTake.clipToOutline = true
        val frame = findViewById<View>(R.id.frame)
        findViewById<View>(R.id.viewfinder).addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l == or - ol && b - t == ob - ot) return@addOnLayoutChangeListener
            val (x, y, w, h) = RenderThread.letterbox(r - l, b - t, outAspect())
            // from inside a layout pass: the new size only takes on the next one
            frame.post { frame.layoutParams = FrameLayout.LayoutParams(w, h).apply { leftMargin = x; topMargin = y } }
        }
        if (!portrait) {
            // a full-width card would cover the whole landscape frame
            panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply { width = dp(400); gravity = Gravity.TOP or Gravity.START }
        }

        camera = CameraController(getSystemService(CameraManager::class.java))
        render = RenderThread { tex -> ui.post { cameraTex = tex; openCamera() } }
        render.overlay = overlay()
        outSize().let { (w, h) -> render.setOutput(w, h) }
        stamper = ScreenStamper(envelope) { render.pushScreen(it); takes.onScreen(it.lum, it.stampNs) }
        surfaceStamper = TickStamper(envelope) { s, ns -> takes.onSurface(s, ns) }
        stems = StemRecorder(openPort = { LinkNative.open() }, clockNs = { camera.clockNs() })
        LinkChannel.migratePicks(prefs.linkPicks).let { if (it != prefs.linkPicks) prefs.linkPicks = it }
        stems.picks = prefs.linkPicks
        takes = TakeController(cacheDir, render, pcm, envelope, Finalizer(contentResolver, peaksDir = io.github.dimadake.schwungcam.viewer.PeakCache.masterDir(this)), stems)
        stream = MoveStream(
            host = { prefs.host },
            clockNs = { camera.clockNs() },
            onScreen = { stamper.onScreen(it) },
            onPcm = { envelope.add(it.arrivalNs, it.endPos); pcm.onChunk(it); stamper.onPcm(it); surfaceStamper.onPcm(it) },
            onSurface = { surfaceStamper.onItem(it, it.arrivalNs) },
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

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putBoolean(KEY_AE, exposureLock)
    }

    /** The encoded frame: the chosen resolution, stood on end in portrait. */
    private fun outSize(): Pair<Int, Int> = prefs.resolution.let { if (portrait) it.h to it.w else it.w to it.h }

    private fun outAspect() = outSize().let { it.first.toFloat() / it.second }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------- camera ----------------

    private fun key(c: CameraChoice) = c.id + "/" + (c.physicalId ?: "")

    /** The saved lens, unless it is no longer a chip (then that side's main lens). */
    private fun current(): CameraChoice? =
        choices.firstOrNull { key(it) == prefs.camera }?.let { c -> c.takeIf { it in CameraCatalog.zoomStops(choices, it.facing) } ?: CameraCatalog.main(choices, c.facing) }
            ?: CameraCatalog.main(choices, CameraMetadata.LENS_FACING_BACK) ?: choices.firstOrNull()

    private fun openCamera() {
        val tex = cameraTex ?: return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        if (choices.isEmpty()) { choices = CameraCatalog.list(getSystemService(CameraManager::class.java)); buildLenses() }
        val choice = current() ?: return
        // the sensor buffer is landscape whichever way the phone is held
        val res = prefs.resolution
        camera.open(choice, tex, res.w, res.h, onReady = { o ->
            // a different camera may stamp on a different clock; re-learn the offset
            envelope.clear()
            render.setCamera(o.bufSize.width, o.bufSize.height, o.sensorDeg, o.unmirror)
        }, onError = { msg -> ui.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } })
        camera.setLocked(exposureLock)
    }

    private fun selectCamera(c: CameraChoice) {
        if (takes.recording || key(c) == prefs.camera) return
        prefs.camera = key(c)
        openCamera()
        buildLenses()
    }

    /** Pixel-style zoom chips for the lenses on the current side; the flip button changes side. */
    private fun buildLenses() {
        lenses.removeAllViews()
        val cur = current() ?: return
        val side = CameraCatalog.zoomStops(choices, cur.facing)
        val main = CameraCatalog.main(choices, cur.facing)
        for (c in side) {
            val sel = key(c) == key(cur)
            lenses.addView(TextView(this).apply {
                text = CameraCatalog.zoomLabel(c, main) + if (sel) "×" else ""
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sel) 13f else 12f)
                typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (sel) 0xFF202124.toInt() else 0xFFFFFFFF.toInt())
                setBackgroundResource(R.drawable.bg_lens)
                isSelected = sel
                contentDescription = c.label
                setOnClickListener { selectCamera(c) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }
        flip.tag = choices.map { it.facing }.toSet().size > 1
        lenses.tag = side.size > 1
        updateChrome()
    }

    private fun flipCamera() {
        val cur = current() ?: return
        val to = if (cur.facing == CameraMetadata.LENS_FACING_FRONT) CameraMetadata.LENS_FACING_BACK else CameraMetadata.LENS_FACING_FRONT
        CameraCatalog.main(choices, to)?.let(::selectCamera)
    }

    private fun displayDegrees(): Int {
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0 else windowManager.defaultDisplay.rotation
        return when (r) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    }

    // a 180° turn is not a configuration change, so only this hears it
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(id: Int) { render.setDisplayRotation(displayDegrees()) }
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
    }

    // ---------------- controls ----------------

    private fun overlay() = prefs.overlay(portrait)

    private fun setupControls() {
        aeBtn.setOnClickListener { exposureLock = !exposureLock; camera.setLocked(exposureLock); updateChrome() }
        overlayBtn.setOnClickListener { setOverlay(overlay().copy(enabled = !overlay().enabled)) }
        settingsBtn.setOnClickListener { if (panel.visibility == View.VISIBLE) closePanel() else openPanel() }
        findViewById<View>(R.id.status_chip).setOnClickListener { openPanel() }
        shutter.setOnClickListener { if (takes.recording) stopTake() else startTake() }
        flip.setOnClickListener { flipCamera() }
        lastTake.setOnClickListener { startActivity(Intent(this, TakesActivity::class.java)) }
        lastTake.setOnLongClickListener { lastTakeUri?.let(::openTake); true }
        updateChrome()
    }

    private fun setOverlay(o: Overlay) {
        val c = o.clamped(outAspect())
        prefs.setOverlay(portrait, c)
        render.overlay = c
        updateChrome()
    }

    /** Which controls show: lens and side changes, the gallery and settings are off-limits during a take. */
    private fun updateChrome() {
        val busy = takes.recording || finalizing
        aeBtn.isActivated = exposureLock
        aeBtn.setImageResource(if (exposureLock) R.drawable.ic_lock else R.drawable.ic_lock_open)
        overlayBtn.isActivated = overlay().enabled
        settingsBtn.isActivated = panel.visibility == View.VISIBLE
        settingsBtn.visibility = if (busy) View.INVISIBLE else View.VISIBLE
        lenses.visibility = if (!busy && lenses.tag == true) View.VISIBLE else View.GONE
        flip.visibility = if (!busy && flip.tag == true) View.VISIBLE else View.INVISIBLE
        lastTake.visibility = if (!busy && lastTakeUri != null) View.VISIBLE else View.INVISIBLE
        shutter.recording = takes.recording
        shutter.contentDescription = getString(if (takes.recording) R.string.stop else R.string.record)
    }

    private fun setupOverlayTouch() {
        var lastX = 0f; var lastY = 0f
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean { setOverlay(overlay().scaled(d.scaleFactor, outAspect())); return true }
        })
        preview.setOnTouchListener { v, e ->
            if (panel.visibility == View.VISIBLE) {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) closePanel()
                return@setOnTouchListener true
            }
            if (!overlay().enabled) return@setOnTouchListener false
            scale.onTouchEvent(e)
            val rect = render.previewRect(v.width, v.height)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_POINTER_DOWN -> { lastX = e.getX(0); lastY = e.getY(0) }
                MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scale.isInProgress && rect[2] > 0) {
                    setOverlay(overlay().dragged((e.x - lastX) / rect[2], (e.y - lastY) / rect[3], outAspect()))
                    lastX = e.x; lastY = e.y
                }
            }
            true
        }
    }

    // ---------------- settings panel ----------------

    private fun openPanel() {
        if (takes.recording || finalizing) return
        buildPanel()
        panel.visibility = View.VISIBLE
        updateChrome()
    }

    private fun closePanel() {
        if (panel.visibility != View.VISIBLE) return
        applyHost()
        panel.visibility = View.GONE
        hostField = null
        stemList = null; stemListShown = null
        updateChrome()
    }

    private fun applyHost() {
        val f = hostField ?: return
        val h = f.text.toString().trim().ifEmpty { "move.local" }
        if (h != prefs.host) { prefs.host = h; stream.reconnect(); updateStatus() }
    }

    private fun buildPanel() {
        val b = panelBody
        b.removeAllViews()
        val ov = overlay()
        b.addView(section(getString(R.string.screen_overlay)))
        b.addView(chips(listOf("Opaque", "Black clear", "White clear"), ov.key.ordinal) { setOverlay(overlay().copy(key = KeyMode.entries[it])) })
        b.addView(label(getString(R.string.opacity)))
        b.addView(SeekBar(this).apply {
            max = 100; progress = (ov.opacity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) { if (fromUser) setOverlay(overlay().copy(opacity = v / 100f)) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        b.addView(section(getString(R.string.video)))
        b.addView(chips(Resolution.entries.map { it.label }, prefs.resolution.ordinal) { i ->
            val r = Resolution.entries[i]
            if (r != prefs.resolution) {
                prefs.resolution = r
                outSize().let { (w, h) -> render.setOutput(w, h) }
                setOverlay(overlay()); openCamera()
            }
        })

        b.addView(section(getString(R.string.audio)))
        b.addView(chips(Target.entries.map { it.label }, prefs.target.ordinal) { prefs.target = Target.entries[it] })
        b.addView(toggle(getString(R.string.master), prefs.masterOn) { prefs.masterOn = it })
        b.addView(toggle(getString(R.string.glue), prefs.glue) { prefs.glue = it })
        b.addView(toggle(getString(R.string.keep_wav), prefs.keepWav) { prefs.keepWav = it })
        b.addView(toggle(getString(R.string.ai_timeline), prefs.aiTimeline) { prefs.aiTimeline = it })

        b.addView(section(getString(R.string.link_stems)))
        b.addView(toggle(getString(R.string.link_stems_on), prefs.linkStems) { on ->
            prefs.linkStems = on
            if (on) stems.start() else stems.stop()
            stemListShown = null; fillStems()
        })
        stemList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        b.addView(stemList)
        fillStems()

        b.addView(section(getString(R.string.host)))
        hostField = EditText(this).apply {
            setText(prefs.host); setSingleLine(); setTextColor(0xFFFFFFFF.toInt())
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { applyHost(); clearFocus() }; false }
        }
        b.addView(hostField)
    }

    /**
     * The session's channels as switches, plus picks that are not in it now
     * (kept, so a pick survives the peer being off for a while).
     */
    private fun fillStems() {
        val list = stemList ?: return
        val picks = prefs.linkPicks
        val present = stems.channels.distinctBy { it.key }.sortedWith(LinkChannel.order)
        val shown = listOf(prefs.linkStems, stems.available, present.map { it.key }, picks)
        if (shown == stemListShown) return
        stemListShown = shown
        list.removeAllViews()
        if (!prefs.linkStems) return
        if (!stems.available) { list.addView(label(getString(R.string.link_unavailable))); return }
        if (present.isEmpty()) list.addView(label(getString(R.string.link_looking)))
        val absent = picks.filter { k -> present.none { it.key == k } }
            .map { LinkChannel("", LinkChannel.peerOf(it), LinkChannel.nameOf(it)) }.sortedWith(LinkChannel.order)
        for (c in present + absent) {
            val text = "${c.name} · ${c.peer}" + if (c.id.isEmpty()) " (${getString(R.string.link_absent)})" else ""
            list.addView(toggle(text, c.key in picks) { on ->
                val p = if (on) prefs.linkPicks + c.key else prefs.linkPicks - c.key
                prefs.linkPicks = p; stems.picks = p
                stemListShown = listOf(prefs.linkStems, stems.available, present.map { it.key }, p)
            })
        }
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(0xFFC4C7C5.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(0, dp(10), 0, dp(2))
    }

    /** One-of-n chips; the pick applies at once, as in Pixel Camera's own settings. */
    private fun chips(options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val views = options.map { o ->
            TextView(this).apply {
                text = o; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundResource(R.drawable.bg_chip)
            }
        }
        fun mark(sel: Int) = views.forEachIndexed { i, v ->
            v.isSelected = i == sel
            v.setTextColor(if (i == sel) 0xFF041E49.toInt() else 0xFFE3E3E3.toInt())
        }
        views.forEachIndexed { i, v ->
            v.setOnClickListener { mark(i); onPick(i) }
            row.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        mark(selected)
        return row
    }

    private fun toggle(t: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t; isChecked = on; setTextColor(0xFFE3E3E3.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        minHeight = dp(44)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    // ---------------- recording ----------------

    private fun startTake() {
        if (finalizing) return
        closePanel()
        val (w, h) = outSize()
        try {
            takes.start(w, h, prefs.resolution.bitrate, prefs.aiTimeline, prefs.linkStems)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot record: ${e.message}", Toast.LENGTH_LONG).show(); return
        }
        recStartMs = SystemClock.elapsedRealtime()
        // one take, one orientation: a turn mid-take would rebuild the activity under the encoder
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        updateChrome(); updateStatus()
        if (streamState != StreamState.LIVE) Toast.makeText(this, "No Schwung audio yet — the take may be silent", Toast.LENGTH_LONG).show()
    }

    private fun stopTake() {
        finalizing = true
        finalizeProgress = 0f
        shutter.progress = 0f
        updateChrome(); updateStatus()
        // the finalize thread outlives this activity: the service keeps the process up
        val app = applicationContext
        FinalizeService.begin(app)
        takes.stop(prefs.master(), prefs.keepWav, progress = { p -> FinalizeService.progress(app, p); ui.post { finalizeProgress = p; shutter.progress = p; updateStatus() } }) { res ->
            FinalizeService.end(app, res)
            ui.post {
                if (isDestroyed) return@post
                finalizing = false
                shutter.progress = null
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                updateChrome(); updateStatus()
                res.fold(::showResult) { e -> AlertDialog.Builder(this).setTitle("Recording failed").setMessage(e.message ?: e.toString()).setPositiveButton(android.R.string.ok, null).show() }
            }
        }
    }

    private fun showResult(r: FinalResult) {
        if (isFinishing || isDestroyed) return
        showLastTake(r.uri)
        val loud = r.report?.outputLufs?.takeIf { it.isFinite() }?.let { " · %.1f LUFS".format(it) } ?: ""
        Toast.makeText(this, "Saved$loud", Toast.LENGTH_SHORT).show()
        // a warning (no audio, device restart) must not slip by as a toast
        r.warning?.let { AlertDialog.Builder(this).setTitle("Take saved").setMessage(it).setPositiveButton(android.R.string.ok, null).show() }
    }

    /** In the gallery (long-press on the thumbnail); a tap opens the app's own takes. */
    private fun openTake(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            Toast.makeText(this, "No app can play the video", Toast.LENGTH_LONG).show()
        }
    }

    /** Shows [uri]'s thumbnail, or, when it is gone (deleted in the gallery), the newest take this app saved. */
    private fun showLastTake(uri: Uri? = null) {
        Thread {
            val u = uri?.takeIf(::exists) ?: newestTake()
            val bmp: Bitmap? = u?.let { runCatching { contentResolver.loadThumbnail(it, Size(240, 240), null) }.getOrNull() }
            ui.post {
                if (isDestroyed) return@post
                lastTakeUri = u
                if (bmp != null) lastTake.setImageBitmap(bmp) else lastTake.setImageDrawable(null)
                updateChrome()
            }
        }.start()
    }

    private fun newestTake(): Uri? {
        val col = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0"
        return runCatching {
            contentResolver.query(col, arrayOf(MediaStore.Video.Media._ID), sel, arrayOf("Movies/SchwungCam%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) ContentUris.withAppendedId(col, c.getLong(0)) else null
            }
        }.getOrNull()
    }

    private fun exists(uri: Uri) = runCatching {
        contentResolver.query(uri, arrayOf(MediaStore.Video.Media._ID), null, null, null)?.use { it.count > 0 } == true
    }.getOrDefault(false)

    private fun updateStatus() {
        val (color, text) = when (streamState) {
            StreamState.OFFLINE -> 0xFFF28B82.toInt() to "Move offline · ${prefs.host}"
            StreamState.CONNECTING -> 0xFFBDC1C6.toInt() to "Connecting to ${prefs.host}…"
            StreamState.SCREEN_ONLY -> 0xFFFDD663.toInt() to "No audio · turn on Mirror Display"
            StreamState.LIVE -> 0xFF81C995.toInt() to "Move live"
        }
        status.text = if (prefs.linkStems) "$text · ${linkStatus()}" else text
        if (panel.visibility == View.VISIBLE) fillStems()
        statusDot.background.mutate().setTint(color)
        when {
            takes.recording -> {
                val t = (SystemClock.elapsedRealtime() - recStartMs) / 1000
                timer.text = "%02d:%02d".format(t / 60, t % 60)
                timer.visibility = View.VISIBLE
            }
            finalizing -> { timer.text = "Saving %d%%".format((finalizeProgress * 100).toInt()); timer.visibility = View.VISIBLE }
            else -> timer.visibility = View.GONE
        }
    }

    private fun linkStatus() = when {
        !stems.available -> "no Link"
        stems.peers == 0 -> "Link: alone"
        else -> "Link ${stems.subscribed.size}/${prefs.linkPicks.size} stems"
    }

    // ---------------- lifecycle ----------------

    override fun onResume() {
        super.onResume()
        FinalizeService.visible = true
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // power-save batching would add delay to the stream that the envelope must then reject
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "schwungcam").apply { acquire() }
        mcLock = wm.createMulticastLock("schwungcam-mdns").apply { setReferenceCounted(false); acquire() }
        stream.start()
        if (prefs.linkStems) stems.start()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, ui)
        render.setDisplayRotation(displayDegrees())
        showLastTake(lastTakeUri)
        val want = listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED && (it != Manifest.permission.POST_NOTIFICATIONS || Build.VERSION.SDK_INT >= 33) }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
        if (Manifest.permission.CAMERA !in want) openCamera()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val cam = permissions.indexOf(Manifest.permission.CAMERA)
        if (cam < 0) return
        if (grantResults.getOrNull(cam) == PackageManager.PERMISSION_GRANTED) openCamera()
        else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    override fun onPause() {
        FinalizeService.visible = false
        if (takes.recording) stopTake()
        closePanel()
        camera.close()
        stream.stop()
        stems.stop()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
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

    private companion object { const val KEY_AE = "aelock" }
}
