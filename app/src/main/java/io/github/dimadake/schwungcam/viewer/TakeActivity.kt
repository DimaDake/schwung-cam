package io.github.dimadake.schwungcam.viewer

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.LruCache
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.dimadake.schwungcam.R
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * One take: its video with one playhead, and tabs for what the hands did
 * (a drawn Move), what it sounds like (waveforms, raw and stems), and its
 * files (share or delete any of them). Fullscreen in either orientation.
 */
class TakeActivity : ViewerActivity(), TextureView.SurfaceTextureListener {
    private enum class Tab(val label: String) { AUDIO("Audio"), GESTURES("Gestures"), FILES("Files") }
    private enum class Source(val label: String) { MASTERED("Mastered"), RAW("Raw"), STEMS("Stems mix") }

    private lateinit var name: String
    private var take: Take? = null

    // player
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var surface: Surface? = null
    private var durationMs = 1L
    private var aspect = 16f / 9
    private var startAt = 0L
    private var resumePlaying = false

    // ai
    private var gestures: Gestures? = null
    private var screens: Map<String, ByteArray> = emptyMap()
    private val bitmaps = LruCache<String, Bitmap>(32)
    private var shownScreen: String? = null

    // audio
    private var source = Source.MASTERED
    private var mix: MixPlayer? = null
    private var solo = BooleanArray(0)
    private var mute = BooleanArray(0)
    private var lastDriftCheck = 0L
    // waveforms: a few files at once, in lane order (the master first)
    private val peakPool = java.util.concurrent.Executors.newFixedThreadPool(3)

    // views
    private lateinit var column: LinearLayout
    private lateinit var bar: FrameLayout
    private lateinit var player: PlayerFrame
    private lateinit var texture: TextureView
    private lateinit var controls: LinearLayout
    private lateinit var seek: SeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var playBtn: ImageView
    private lateinit var fsBtn: ImageView
    private lateinit var fsTop: LinearLayout
    private lateinit var fsScreenBtn: ImageView
    private lateinit var fsScreen: ScreenView
    private lateinit var tabStrip: LinearLayout
    private lateinit var panes: FrameLayout
    private val move by lazy { MoveView(this) }
    private val events by lazy { ListView(this) }
    private val eventAdapter = EventAdapter()
    private val lanes by lazy { LanesView(this) }
    private val chipBox by lazy { LinearLayout(this) }
    private val files by lazy { LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } }
    private var tab = Tab.FILES
    private var seeking = false
    private var userScrolledAt = 0L
    private var lastListed = -2

    // fullscreen
    private var fullscreen = false
    private var fsByRotation = false
    private var fsScreenOn = false
    private var orientationWatch: OrientationEventListener? = null
    private val hideControls = Runnable { if (fullscreen && mp?.isPlaying == true) setFsChrome(false) }

    // files
    private val selectedFiles = LinkedHashSet<String>()
    private var stemsOpen = false
    private var selectBar: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra(EXTRA_TAKE) ?: run { finish(); return }
        startAt = savedInstanceState?.getLong("pos") ?: 0L
        build()
        load()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putLong("pos", currentMs())
    }

    override fun onResume() {
        super.onResume()
        root.postOnAnimation(tick)
    }

    override fun onPause() {
        super.onPause()
        if (mp?.isPlaying == true) pause()
        root.removeCallbacks(tick)
    }

    override fun onDestroy() {
        orientationWatch?.disable()
        peakPool.shutdownNow()
        mix?.release(); mix = null
        mp?.release(); mp = null
        surface?.release()
        super.onDestroy()
    }

    @Deprecated("framework back")
    override fun onBackPressed() {
        when {
            fullscreen -> exitFullscreen()
            selectedFiles.isNotEmpty() -> { selectedFiles.clear(); refreshSelection() }
            else -> super.onBackPressed()
        }
    }

    // ---- layout

    private fun build() {
        bar = FrameLayout(this)
        texture = TextureView(this).also { it.surfaceTextureListener = this }
        player = PlayerFrame()
        fsBtn = iconButton(R.drawable.ic_fullscreen, "Fullscreen", C.TEXT) { if (fullscreen) exitFullscreen() else enterFullscreen(false) }.apply {
            background = rounded(0x88000000.toInt(), dp(18).toFloat())
        }
        fsTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0))
            addView(iconButton(R.drawable.ic_arrow_back, "Exit fullscreen", C.TEXT) { exitFullscreen() })
            addView(text(title(), 16f), lp(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        }
        fsScreen = ScreenView(this).apply { visibility = View.GONE }
        player.addView(texture, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        player.addView(fsTop, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
        player.addView(fsScreen, FrameLayout.LayoutParams(dp(192), dp(96), Gravity.TOP or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        player.addView(fsBtn, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        player.setOnClickListener { if (fullscreen) setFsChrome(fsTop.visibility != View.VISIBLE) else togglePlay() }

        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(C.ACCENT); thumbTintList = ColorStateList.valueOf(C.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF5F6368.toInt())
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                    if (fromUser) { val ms = v.toLong() * durationMs / 1000; posText.text = Fmt.duration(ms); seekTo(ms) }
                }
                override fun onStartTrackingTouch(s: SeekBar) { seeking = true; root.removeCallbacks(hideControls) }
                override fun onStopTrackingTouch(s: SeekBar) { seeking = false; scheduleHide() }
            })
        }
        posText = text("0:00", 12f, C.TEXT2); durText = text("0:00", 12f, C.TEXT2)
        playBtn = iconButton(R.drawable.ic_play_arrow, "Play", C.TEXT) { togglePlay() }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)); scaleX = 1.4f; scaleY = 1.4f
        }
        fsScreenBtn = iconButton(R.drawable.ic_auto_awesome, "Move screen", C.TEXT2) {
            fsScreenOn = !fsScreenOn; updateFsScreen(); scheduleHide()
        }.apply { visibility = View.GONE }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
                addView(posText); addView(seek, lp(0, WRAP_CONTENT, 1f)); addView(durText)
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                addView(View(context), lp(dp(44), 1))
                addView(View(context), lp(0, 1, 1f))
                addView(iconButton(R.drawable.ic_replay_10, "Back 10 s", C.TEXT) { seekTo(maxOf(0, currentMs() - 10_000)) })
                addView(playBtn, LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(dp(24), 0, dp(24), 0) })
                addView(iconButton(R.drawable.ic_forward_10, "Forward 10 s", C.TEXT) { seekTo(minOf(durationMs, currentMs() + 10_000)) })
                addView(View(context), lp(0, 1, 1f))
                addView(fsScreenBtn)
            })
        }

        tabStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panes = FrameLayout(this)

        // gestures pane
        events.apply {
            adapter = eventAdapter; divider = null; selector = android.graphics.drawable.ColorDrawable(0)
            setOnItemClickListener { _, _, pos, _ -> gestures?.listed?.getOrNull(pos)?.let { seekTo(maxOf(0L, ((it.t - 0.5) * 1000).toLong())) } }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView, s: Int) { if (s != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) userScrolledAt = SystemClock.uptimeMillis() }
                override fun onScroll(v: AbsListView, a: Int, b: Int, c: Int) {}
            })
        }
        val gesturePane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = Tab.GESTURES
            addView(move, lp().apply { setMargins(dp(12), dp(10), dp(12), dp(6)) })
            addView(events, lp(MATCH_PARENT, 0, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
        }
        // audio pane
        lanes.onSeek = { s -> seekTo((s * 1000).toLong()) }
        lanes.onSoloMute = { mix?.gains = StemMix.gains(solo, mute) }
        val audioPane = ScrollView(this).apply {
            tag = Tab.AUDIO
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(chipBox.apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(10), dp(12), dp(10)) })
                addView(lanes)
            })
        }
        val filesPane = ScrollView(this).apply { tag = Tab.FILES; addView(files) }
        panes.addView(audioPane); panes.addView(gesturePane); panes.addView(filesPane)

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, lp())
            addView(player, lp())
            addView(controls, lp())
            addView(tabStrip, lp())
            addView(View(context).apply { setBackgroundColor(C.LINE) }, lp(MATCH_PARENT, dp(1)))
            addView(panes, lp(MATCH_PARENT, 0, 1f))
        }
        root.addView(column, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        updateBar()
    }

    private fun title(): String = take?.time?.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.US)) ?: name

    private fun updateBar() {
        bar.removeAllViews()
        val t = take
        val v = if (selectedFiles.isEmpty()) {
            topBar(R.drawable.ic_arrow_back, "Back", { finish() }, text(title(), 18f),
                text(if (t == null) "" else "${Fmt.duration(t.durationMs)} · ${Fmt.size(t.size)}", 12f, C.MUTED),
                shareIcon { t?.let { shareSheet(listOf(it)) } },
                deleteIcon { trashWhole() },
                iconButton(R.drawable.ic_more_vert, "More") { menu(it) })
        } else {
            val rows = selectedRows()
            topBar(R.drawable.ic_close, "Clear selection", { selectedFiles.clear(); refreshSelection() },
                text("${rows.size} selected · ${Fmt.size(rows.sumOf { it.size })}", 18f), null,
                iconButton(R.drawable.ic_select_all, "Select all") { t?.let { selectedFiles += it.all.map { r -> r.id } }; refreshSelection() })
                .apply { setBackgroundColor(C.SURFACE) }
        }
        bar.addView(v)
    }

    private fun menu(anchor: View) {
        val t = take ?: return
        PopupMenu(this, anchor).apply {
            menu.add("Select files…").setOnMenuItemClickListener { showTab(Tab.FILES); selectedFiles += t.video?.id ?: ""; selectedFiles.remove(""); refreshSelection(); true }
            val spare = TakeIndex.spare(t)
            if (spare.isNotEmpty()) menu.add("Free up space · ${Fmt.size(spare.sumOf { it.size })}").setOnMenuItemClickListener {
                trashWithUndo(spare, "Raw files") { reload() }; true
            }
            t.video?.let { v ->
                menu.add("Open in gallery").setOnMenuItemClickListener {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(v.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    true
                }
            }
            menu.add("Delete permanently").setOnMenuItemClickListener {
                deleteForGood(t.all) { ok -> if (ok) { setResult(RESULT_CANCELED); finish() } }; true
            }
            show()
        }
    }

    // ---- loading

    private fun load() {
        background({ MediaQuery.takes(contentResolver).firstOrNull { it.name == name } ?: Unit }) { r ->
            val t = r as? Take ?: run { Toast.makeText(this, "This take is gone", Toast.LENGTH_SHORT).show(); finish(); return@background }
            val first = take == null
            take = t
            durationMs = maxOf(1L, t.durationMs)
            (fsTop.getChildAt(1) as TextView).text = title()
            updateBar()
            buildTabs()
            buildFiles()
            if (first) {
                buildAudio()
                loadAi(t)
                openVideo()
            }
        }
    }

    /** After a partial delete (or its undo): the same take with what is left. */
    private fun reload() {
        background({ MediaQuery.takes(contentResolver).firstOrNull { it.name == name } ?: Unit }) { r ->
            val t = r as? Take ?: run { setResult(RESULT_CANCELED); finish(); return@background }
            val before = take
            take = t
            selectedFiles.retainAll(t.all.map { it.id }.toSet())
            updateBar(); buildTabs(); buildFiles()
            // a material that came or went changes what can play
            if (before == null || before.kinds != t.kinds || before.files[Kind.STEMS]?.size != t.files[Kind.STEMS]?.size) {
                if (!t.has(Kind.AI)) { gestures = null; screens = emptyMap(); move.gestures = null }
                else if (gestures == null) loadAi(t)
                buildAudio()
            }
        }
    }

    private fun loadAi(t: Take) {
        val zip = t.files[Kind.AI]?.firstOrNull() ?: return
        background({
            val png = HashMap<String, ByteArray>()
            val lines = ArrayList<String>()
            contentResolver.openInputStream(zip.uri)!!.use { input ->
                ZipInputStream(input.buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        when {
                            e.name == "timeline.jsonl" -> z.bufferedReader().let { r -> while (true) lines += (r.readLine() ?: break) }
                            e.name.startsWith("screens/") -> png[e.name] = z.readBytes()
                        }
                    }
                }
            }
            Gestures.parse(lines.asSequence()) to png
        }) { (g, png) ->
            gestures = g; screens = png
            move.gestures = g
            eventAdapter.notifyDataSetChanged()
            buildTabs()
            fsScreenBtn.visibility = if (fullscreen) View.VISIBLE else View.GONE
        }
    }

    private fun openVideo() {
        val st = texture.surfaceTexture ?: return
        val v = take?.video ?: return
        if (mp != null) return
        surface = Surface(st)
        mp = MediaPlayer().apply {
            try {
                setDataSource(this@TakeActivity, v.uri)
            } catch (e: Exception) {
                Toast.makeText(this@TakeActivity, "Can't play this video: ${e.message}", Toast.LENGTH_LONG).show(); return
            }
            setSurface(surface)
            setOnPreparedListener {
                prepared = true
                durationMs = maxOf(1L, it.duration.toLong())
                durText.text = Fmt.duration(durationMs)
                lanes.durationSec = durationMs / 1000.0
                if (startAt > 0) it.seekTo(startAt, MediaPlayer.SEEK_CLOSEST) else it.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
                applySource()
            }
            setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) { aspect = w.toFloat() / h; player.requestLayout() } }
            setOnCompletionListener { mix?.pause(); updatePlayIcon(); setFsChrome(true) }
            setOnErrorListener { _, what, extra -> Toast.makeText(this@TakeActivity, "Playback error $what/$extra", Toast.LENGTH_LONG).show(); true }
            prepareAsync()
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { if (take != null) openVideo() }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // ---- transport

    private fun currentMs(): Long = if (prepared) mp?.currentPosition?.toLong() ?: 0L else startAt

    private fun togglePlay() { if (mp?.isPlaying == true) pause() else play() }

    private fun play() {
        val m = mp ?: return
        if (!prepared) return
        if (m.currentPosition >= durationMs - 100) m.seekTo(0L, MediaPlayer.SEEK_CLOSEST)
        m.start()
        mix?.play(m.currentPosition.toLong())
        updatePlayIcon(); scheduleHide()
    }

    private fun pause() {
        mp?.pause(); mix?.pause()
        updatePlayIcon(); setFsChrome(true)
    }

    private fun seekTo(ms: Long) {
        val m = mp
        if (m == null || !prepared) { startAt = ms; return }
        m.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        mix?.seek(ms)
        render(ms)
    }

    private fun updatePlayIcon() {
        val on = mp?.isPlaying == true
        playBtn.setImageResource(if (on) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        playBtn.contentDescription = if (on) "Pause" else "Play"
    }

    private val tick = object : Runnable {
        override fun run() {
            val ms = currentMs()
            render(ms)
            val m = mp
            val mx = mix
            if (m != null && mx != null && m.isPlaying) {
                val now = SystemClock.uptimeMillis()
                if (now - lastDriftCheck > 1000) {
                    lastDriftCheck = now
                    if (kotlin.math.abs(mx.positionMs() - ms) > 40) mx.seek(ms)
                }
            }
            root.postOnAnimation(this)
        }
    }

    private fun render(ms: Long) {
        val t = ms / 1000.0
        if (!seeking) seek.progress = (ms * 1000 / durationMs).toInt()
        posText.text = Fmt.duration(ms)
        val g = gestures
        if (g != null) {
            val key = g.screenAt(t)
            if (key != shownScreen) {
                shownScreen = key
                val b = key?.let { k -> bitmaps.get(k) ?: screens[k]?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.also { bitmaps.put(k, it) } }
                move.screen = b; fsScreen.screen = b
            }
            if (tab == Tab.GESTURES) {
                move.t = t
                val i = g.listedAt(t)
                if (i != lastListed) {
                    lastListed = i
                    eventAdapter.notifyDataSetChanged()
                    if (SystemClock.uptimeMillis() - userScrolledAt > 3000 && i >= 0) events.smoothScrollToPositionFromTop(i, events.height / 3, 120)
                }
            }
        }
        if (tab == Tab.AUDIO) lanes.t = t
    }

    // ---- tabs

    private fun buildTabs() {
        val t = take ?: return
        val shown = Tab.entries.filter { when (it) { Tab.GESTURES -> t.has(Kind.AI); Tab.AUDIO -> t.has(Kind.MASTERED) || t.has(Kind.RAW_AUDIO) || t.has(Kind.STEMS); Tab.FILES -> true } }
        val want = runCatching { Tab.valueOf(getSharedPreferences("schwungcam", MODE_PRIVATE).getString("viewer_tab", "AUDIO")!!) }.getOrDefault(Tab.AUDIO)
        tabStrip.removeAllViews()
        for (tb in shown) tabStrip.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; background = ripple()
            addView(text(tb.label, 14f, if (tb == tab) C.ACCENT else C.MUTED).apply { setPadding(0, dp(12), 0, dp(10)); gravity = Gravity.CENTER })
            addView(View(context).apply { setBackgroundColor(if (tb == tab) C.ACCENT else 0) },
                LinearLayout.LayoutParams(dp(56), dp(3)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            setOnClickListener { showTab(tb, remember = true) }
        }, lp(0, WRAP_CONTENT, 1f))
        if (tab !in shown || tabStrip.tag == null) { tabStrip.tag = true; showTab(if (want in shown) want else shown.first(), rebuild = false) }
        showPane()
    }

    private fun showTab(tb: Tab, remember: Boolean = false, rebuild: Boolean = true) {
        tab = tb
        if (remember) getSharedPreferences("schwungcam", MODE_PRIVATE).edit().putString("viewer_tab", tb.name).apply()
        if (rebuild) buildTabs() else showPane()
        lastListed = -2
        render(currentMs())
    }

    private fun showPane() {
        for (i in 0 until panes.childCount) panes.getChildAt(i).let { it.visibility = if (it.tag == tab) View.VISIBLE else View.GONE }
    }

    // ---- audio

    private fun buildAudio() {
        val t = take ?: return
        val sources = Source.entries.filter { when (it) { Source.MASTERED -> t.has(Kind.MASTERED); Source.RAW -> t.has(Kind.RAW_AUDIO); Source.STEMS -> t.has(Kind.STEMS) } }
        if (source !in sources) source = sources.firstOrNull() ?: Source.MASTERED
        val box = chipBox
        box.removeAllViews()
        for (s in sources) box.addView(text(s.label, 13f, if (s == source) C.ON_ACCENT else C.TEXT2).apply {
            setBackgroundResource(R.drawable.bg_chip); isSelected = s == source
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { source = s; buildAudio(); applySource() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })

        val stems = t.files[Kind.STEMS].orEmpty()
        if (solo.size != stems.size) { solo = BooleanArray(stems.size); mute = BooleanArray(stems.size) }
        lanes.solo = solo; lanes.mute = mute
        lanes.stemsActive = source == Source.STEMS
        val wanted = ArrayList<Pair<LanesView.Lane, MediaRow>>()
        t.files[Kind.MASTERED]?.firstOrNull()?.let { wanted += LanesView.Lane("Master", C.ACCENT) to it }
        t.files[Kind.RAW_AUDIO]?.firstOrNull()?.let { wanted += LanesView.Lane("Raw", C.of(Kind.RAW_AUDIO)) to it }
        stems.forEachIndexed { i, r -> wanted += LanesView.Lane(r.name.removeSuffix(".wav"), C.of(Kind.STEMS), i) to r }
        // keep peaks already read
        val old = lanes.lanes.associateBy { it.name + it.stem }
        lanes.lanes = wanted.map { (l, _) -> old[l.name + l.stem] ?: l }
        lanes.durationSec = durationMs / 1000.0
        val todo = wanted.mapIndexed { i, (_, r) -> lanes.lanes[i] to r }.filter { it.first.loading && !it.first.queued }
        todo.forEach { it.first.queued = true }
        for ((lane, row) in todo) peakPool.execute {
            val p = PeakCache.get(this, row) { part -> runOnUiThread { if (!isDestroyed && lane.loading) { lane.peaks = part; lanes.invalidate() } } }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                lane.peaks = p ?: Peaks(Peaks.PER_SEC, ByteArray(0), ByteArray(0)); lane.loading = false; lanes.invalidate()
            }
        }
    }

    private fun applySource() {
        val m = mp ?: return
        val t = take ?: return
        mix?.release(); mix = null
        val rows = when (source) {
            Source.MASTERED -> emptyList()
            Source.RAW -> t.files[Kind.RAW_AUDIO].orEmpty().take(1)
            Source.STEMS -> t.files[Kind.STEMS].orEmpty()
        }
        if (rows.isEmpty()) { m.setVolume(1f, 1f); return }
        m.setVolume(0f, 0f)
        mix = MixPlayer(this, rows).also { mx ->
            if (source == Source.STEMS) mx.gains = StemMix.gains(solo, mute)
            if (mx.missing > 0) snack.show("${mx.missing} file(s) can't play (other sample rate or unreadable)")
            if (m.isPlaying) mx.play(m.currentPosition.toLong())
        }
    }

    // ---- files

    private fun selectedRows(): List<MediaRow> = take?.all?.filter { it.id in selectedFiles }.orEmpty()

    private fun refreshSelection() {
        updateBar(); buildFiles()
        val on = selectedFiles.isNotEmpty()
        if (on && selectBar == null) {
            selectBar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setBackgroundColor(C.SURFACE); setPadding(0, dp(6), 0, dp(10))
                fun action(icon: Int, label: String, tint: Int, go: () -> Unit) = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = ripple()
                    addView(icon(icon, tint, 24)); addView(text(label, 12f, tint))
                    setOnClickListener { go() }
                }
                addView(action(R.drawable.ic_share, "Share", C.TEXT2) { share(selectedRows()) }, lp(0, WRAP_CONTENT, 1f))
                addView(action(R.drawable.ic_delete, "Delete", C.ERROR) { deleteSelected() }, lp(0, WRAP_CONTENT, 1f))
            }
            root.addView(selectBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        } else if (!on && selectBar != null) {
            root.removeView(selectBar); selectBar = null
        }
    }

    private fun deleteSelected() {
        val t = take ?: return
        val rows = selectedRows()
        if (rows.isEmpty()) return
        if (rows.size == t.all.size) {
            AlertDialog.Builder(this).setTitle("Delete the whole take?")
                .setMessage("These are all of its files (${Fmt.size(t.size)}).")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete take") { _, _ -> trashWhole() }.show()
            return
        }
        trashWithUndo(rows, "${rows.size} file${if (rows.size == 1) "" else "s"}") { selectedFiles.clear(); refreshSelection(); reload() }
    }

    private fun trashWhole() {
        val t = take ?: return
        val rows = t.all
        trash(rows) { ok ->
            if (!ok) return@trash
            setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_TRASHED, ArrayList(rows.map { it.id })))
            finish()
        }
    }

    private fun buildFiles() {
        val t = take ?: return
        files.removeAllViews()
        val selecting = selectedFiles.isNotEmpty()
        for (k in Kind.entries) {
            val rows = t.files[k] ?: continue
            if (k == Kind.STEMS) {
                val n = rows.count { it.id in selectedFiles }
                fileRow(C.icon(k), C.of(k), "Stems · ${rows.size} file${if (rows.size == 1) "" else "s"}", "24-bit WAV · ${Fmt.size(rows.sumOf { it.size })} · Music/SchwungCam/${t.name}",
                    if (n == 0) CheckMark.NONE else if (n == rows.size) CheckMark.ON else CheckMark.SOME, selecting,
                    trailing = if (stemsOpen) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                    onTrailing = { stemsOpen = !stemsOpen; buildFiles() },
                    onToggle = { if (n == rows.size) rows.forEach { selectedFiles.remove(it.id) } else selectedFiles += rows.map { it.id }; refreshSelection() },
                    onLong = { selectedFiles += rows.map { it.id }; refreshSelection() })
                if (stemsOpen) for (r in rows) fileRow(null, 0, r.name, Fmt.size(r.size), if (r.id in selectedFiles) CheckMark.ON else CheckMark.NONE, selecting,
                    indent = true, trailing = if (selecting) null else R.drawable.ic_share, onTrailing = { share(listOf(r)) },
                    onToggle = { if (!selectedFiles.remove(r.id)) selectedFiles += r.id; refreshSelection() },
                    onLong = { selectedFiles += r.id; refreshSelection() })
            } else {
                val r = rows.first()
                val sub = "${Fmt.size(r.size)} · ${r.path.trimEnd('/')}"
                fileRow(C.icon(k), C.of(k), k.label, "${r.name}\n$sub", if (r.id in selectedFiles) CheckMark.ON else CheckMark.NONE, selecting,
                    trailing = if (selecting) null else R.drawable.ic_share, onTrailing = { share(listOf(r)) },
                    onToggle = { if (!selectedFiles.remove(r.id)) selectedFiles += r.id; refreshSelection() },
                    onLong = { selectedFiles += r.id; refreshSelection() })
            }
        }
        files.addView(text("Total ${Fmt.size(t.size)}" + if (!selecting) " · long-press to select" else "", 12f, C.MUTED).apply {
            setPadding(dp(16), dp(14), dp(16), dp(if (selecting) 90 else 24))
        })
    }

    private fun fileRow(iconRes: Int?, tint: Int, title: String, sub: String, state: Int, selecting: Boolean, indent: Boolean = false,
                        trailing: Int? = null, onTrailing: () -> Unit = {}, onToggle: () -> Unit, onLong: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = ripple()
            setPadding(dp(if (indent) 56 else 16), dp(10), dp(8), dp(10))
            if (selecting) addView(CheckMark(context).apply { this.state = state; (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(14) })
            if (iconRes != null) addView(FrameLayout(context).apply {
                background = rounded(C.CHIP, dp(10).toFloat())
                addView(icon(iconRes, tint, 20), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, if (indent) 14f else 15f))
                addView(text(sub, 12f, C.MUTED))
            }, lp(0, WRAP_CONTENT, 1f))
            if (trailing != null) addView(iconButton(trailing, "More", C.TEXT2) { onTrailing() })
            setOnClickListener { if (selecting) onToggle() else if (trailing == R.drawable.ic_expand_less || trailing == R.drawable.ic_expand_more) onTrailing() }
            setOnLongClickListener { onLong(); true }
        }
        files.addView(row)
        files.addView(View(this).apply { setBackgroundColor(0xFF1F1F1F.toInt()) }, lp(MATCH_PARENT, 1))
    }

    // ---- fullscreen

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        val land = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (land && !fullscreen) enterFullscreen(true)
        else if (!land && fullscreen && fsByRotation) exitFullscreen()
        player.requestLayout()
    }

    private fun enterFullscreen(byRotation: Boolean) {
        fullscreen = true; fsByRotation = byRotation
        // a wide take wants the phone on its side; an upright one stays
        if (!byRotation && aspect > 1f) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        for (i in 0 until column.childCount) column.getChildAt(i).let { if (it !== player) it.visibility = View.GONE }
        player.layoutParams = lp(MATCH_PARENT, 0, 1f)
        (controls.parent as? ViewGroup)?.removeView(controls)
        controls.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0))
        controls.setPadding(0, dp(24), 0, dp(8))
        player.addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        fsBtn.visibility = View.GONE
        fsScreenOn = false
        fsScreenBtn.visibility = if (gestures != null) View.VISIBLE else View.GONE
        fsScreenBtn.setImageResource(R.drawable.ic_auto_awesome)
        selectBar?.visibility = View.GONE
        bars(false)
        setFsChrome(true)
    }

    private fun exitFullscreen() {
        fullscreen = false
        root.removeCallbacks(hideControls)
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            watchForUpright()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        player.removeView(controls)
        controls.background = null; controls.setPadding(0, 0, 0, 0)
        column.addView(controls, column.indexOfChild(player) + 1, lp())
        for (i in 0 until column.childCount) column.getChildAt(i).visibility = View.VISIBLE
        player.layoutParams = lp()
        fsTop.visibility = View.GONE; fsScreen.visibility = View.GONE; fsScreenBtn.visibility = View.GONE
        fsBtn.visibility = View.VISIBLE; fsBtn.setImageResource(R.drawable.ic_fullscreen)
        controls.visibility = View.VISIBLE
        selectBar?.visibility = View.VISIBLE
        bars(true)
    }

    /** Held in portrait after leaving fullscreen sideways; rotation is free again once the phone is upright. */
    private fun watchForUpright() {
        orientationWatch?.disable()
        orientationWatch = object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                if (deg < 25 || deg > 335) {
                    disable()
                    if (!fullscreen) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }.also { it.enable() }
    }

    private fun setFsChrome(show: Boolean) {
        if (!fullscreen) return
        val v = if (show) View.VISIBLE else View.GONE
        fsTop.visibility = v; controls.visibility = v
        updateFsScreen()
        if (show) scheduleHide()
    }

    private fun updateFsScreen() {
        fsScreen.visibility = if (fullscreen && fsScreenOn && gestures != null) View.VISIBLE else View.GONE
        fsScreenBtn.imageTintList = ColorStateList.valueOf(if (fsScreenOn) C.ACCENT else C.TEXT2)
    }

    private fun scheduleHide() {
        root.removeCallbacks(hideControls)
        if (fullscreen) root.postDelayed(hideControls, 3000)
    }

    private fun bars(show: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                if (show) it.show(WindowInsets.Type.systemBars()) else {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (show) 0 else
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }

    /** The video's box: as tall as the take's shape allows (at most 45 % of the screen), all of it in fullscreen; the picture letterboxed inside. */
    private inner class PlayerFrame : FrameLayout(this@TakeActivity) {
        init { setBackgroundColor(C.BG) }

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            val height = if (fullscreen) MeasureSpec.getSize(h)
                else minOf((width / aspect).toInt(), (resources.displayMetrics.heightPixels * 0.45f).toInt())
            // the picture: fit inside, keep its shape
            val (vw, vh) = if (width / aspect <= height) width to (width / aspect).toInt() else (height * aspect).toInt() to height
            texture.layoutParams.width = vw; texture.layoutParams.height = vh
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    // ---- event list

    private inner class EventAdapter : BaseAdapter() {
        override fun getCount() = gestures?.listed?.size ?: 0
        override fun getItem(p: Int) = gestures?.listed?.get(p)
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@TakeActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                addView(text("", 12f, C.MUTED).apply { typeface = android.graphics.Typeface.MONOSPACE }, LinearLayout.LayoutParams(dp(64), WRAP_CONTENT))
                addView(View(context), LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
                addView(text("", 13f), lp(0, WRAP_CONTENT, 1f))
            }
            val e = gestures!!.listed[p]
            (row.getChildAt(0) as TextView).text = Fmt.stamp(e.t)
            row.getChildAt(1).background = rounded(dotColor(e), dp(4).toFloat())
            (row.getChildAt(2) as TextView).text = e.text()
            row.background = if (p == lastListed) rounded(0x1AA8C7FA, dp(8).toFloat()) else null
            return row
        }

        private fun dotColor(e: Gesture) = when (e) {
            is Gesture.Pad -> 0xFFFFB4A9.toInt()
            is Gesture.Step -> 0xFFE3C77A.toInt()
            is Gesture.Turn -> C.ACCENT
            is Gesture.Led -> MoveView.ledColor(e.name)
            is Gesture.Gap -> C.ERROR
            else -> C.TEXT2
        }
    }
}
