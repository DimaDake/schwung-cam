package io.github.dimadake.schwungcam.viewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import io.github.dimadake.schwungcam.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** Every take, two to a row by day; tap opens one, long-press selects. */
class TakesActivity : ViewerActivity() {
    private var takes: List<Take> = emptyList()
    private var rows: List<Any> = emptyList()            // TakeIndex.Day or List<Take> (one or two)
    private val selected = LinkedHashSet<String>()
    private val thumbs = LruCache<String, Bitmap>(48)
    private val loader = Executors.newFixedThreadPool(2)
    private val loading = HashSet<String>()

    private lateinit var list: ListView
    private lateinit var bar: FrameLayout
    private lateinit var empty: TextView
    private val adapter = Adapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bar = FrameLayout(this)
        list = ListView(this).apply {
            divider = null; selector = android.graphics.drawable.ColorDrawable(0); adapter = this@TakesActivity.adapter
            setPadding(dp(8), 0, dp(8), dp(24)); clipToPadding = false
        }
        empty = text("No takes yet.\nRecord one and it shows up here.", 15f, C.MUTED).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, lp())
            addView(FrameLayout(context).apply {
                addView(list, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                addView(empty, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }, lp(MATCH_PARENT, 0, 1f))
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        updateBar()
        askToSeeAll()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("framework back")
    override fun onBackPressed() {
        if (selected.isNotEmpty()) { selected.clear(); updateBar(); adapter.notifyDataSetChanged() } else super.onBackPressed()
    }

    /** After a reinstall the old takes belong to another install: reading media lets them show. */
    private fun askToSeeAll() {
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val prefs = getSharedPreferences("schwungcam", MODE_PRIVATE)
        if (missing.isNotEmpty() && !prefs.getBoolean("viewer_asked", false)) {
            prefs.edit().putBoolean("viewer_asked", true).apply()
            requestPermissions(missing.toTypedArray(), 7)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 7) reload()
    }

    private fun reload() {
        background({ MediaQuery.takes(contentResolver) }) { show(it) }
    }

    private fun show(t: List<Take>) {
        takes = t
        selected.retainAll(t.map { it.name }.toSet())
        rows = TakeIndex.days(t, LocalDate.now()).flatMap { d -> listOf<Any>(d) + d.takes.chunked(2) }
        empty.visibility = if (t.isEmpty()) View.VISIBLE else View.GONE
        updateBar()
        adapter.notifyDataSetChanged()
    }

    private fun picked() = takes.filter { it.name in selected }

    private fun updateBar() {
        bar.removeAllViews()
        val v = if (selected.isEmpty()) {
            topBar(R.drawable.ic_arrow_back, "Back", { finish() }, text("Takes", 22f),
                text("${takes.size} take${if (takes.size == 1) "" else "s"} · ${Fmt.size(takes.sumOf { it.size })}", 13f, C.MUTED),
                iconButton(R.drawable.ic_more_vert, "More") { menu(it) })
        } else {
            val p = picked()
            topBar(R.drawable.ic_close, "Clear selection", { selected.clear(); updateBar(); adapter.notifyDataSetChanged() },
                text("${p.size} selected · ${Fmt.size(p.sumOf { it.size })}", 18f), null,
                iconButton(R.drawable.ic_select_all, "Select all") { selected += takes.map { it.name }; updateBar(); adapter.notifyDataSetChanged() },
                shareIcon { shareSheet(picked()) },
                deleteIcon { deleteSheet(picked()) { gone -> afterTrash(gone) } }).apply { setBackgroundColor(C.SURFACE) }
        }
        bar.addView(v)
    }

    private fun menu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Free up space…").setOnMenuItemClickListener { freeUp(); true }
            show()
        }
    }

    private fun freeUp() {
        val spare = takes.filter { TakeIndex.spare(it).isNotEmpty() }
        if (spare.isEmpty()) { snack.show("Nothing to free: no raw files or stems"); return }
        deleteSheet(spare, freeUp = true) { gone -> afterTrash(gone) }
    }

    private fun afterTrash(gone: List<MediaRow>) {
        selected.clear()
        val ids = gone.map { it.id }.toSet()
        show(takes.mapNotNull { it.without(ids) })
        snack.show("${Fmt.size(gone.sumOf { it.size })} moved to trash", if (Build.VERSION.SDK_INT >= 30) "Undo" else null) {
            undoTrash(gone) { reload() }
        }
    }

    @Deprecated("framework result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == OPEN) {
            val ids = data?.getStringArrayListExtra(EXTRA_TRASHED)
            if (resultCode == RESULT_OK && ids != null) {
                val rows = ids.map { MediaRow(it, "", "", 0) }
                snack.show("Take moved to trash", if (Build.VERSION.SDK_INT >= 30) "Undo" else null) { undoTrash(rows) { reload() } }
            }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun open(t: Take) {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, TakeActivity::class.java).putExtra(EXTRA_TAKE, t.name), OPEN)
    }

    private fun toggle(t: Take) {
        if (!selected.remove(t.name)) selected += t.name
        updateBar(); adapter.notifyDataSetChanged()
    }

    // ---- grid

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is TakeIndex.Day) 0 else 1
        override fun isEnabled(position: Int) = false

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val r = rows[p]
            if (r is TakeIndex.Day) {
                val tv = (convert as? TextView) ?: text("", 13f, C.TEXT2).apply { setPadding(dp(8), dp(18), dp(8), dp(8)) }
                tv.text = "${r.label} · ${r.takes.size} take${if (r.takes.size == 1) "" else "s"} · ${Fmt.size(r.size)}"
                return tv
            }
            @Suppress("UNCHECKED_CAST") val pair = r as List<Take>
            val row = (convert as? LinearLayout) ?: LinearLayout(this@TakesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                repeat(2) { addView(Tile(), lp(0, WRAP_CONTENT, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            }
            for (i in 0 until 2) (row.getChildAt(i) as Tile).bind(pair.getOrNull(i))
            return row
        }
    }

    private inner class Tile : FrameLayout(this@TakesActivity) {
        private val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(C.SURFACE2) }
        private val dur = text("", 11f).apply {
            background = rounded(0xAA000000.toInt(), dp(8).toFloat()); setPadding(dp(6), dp(1), dp(6), dp(1))
        }
        private val time = text("", 12f)
        private val mats = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        private val check = CheckMark(context, round = true)
        private var take: Take? = null

        init {
            clipToOutline = true
            background = rounded(C.SURFACE2, dp(14).toFloat())
            addView(img, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(dur, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
            addView(check, LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.START).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(18), dp(8), dp(7))
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0xCC000000.toInt()))
                addView(time); addView(mats, lp().apply { topMargin = dp(3) })
            }, LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
            setOnClickListener { take?.let { if (selected.isNotEmpty()) toggle(it) else open(it) } }
            setOnLongClickListener { take?.let { toggle(it) }; true }
            foreground = context.ripple()
        }

        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 4 / 3, MeasureSpec.EXACTLY))
        }

        fun bind(t: Take?) {
            take = t
            visibility = if (t == null) INVISIBLE else VISIBLE
            if (t == null) return
            val on = t.name in selected
            check.visibility = if (selected.isEmpty()) GONE else VISIBLE
            check.checked = on
            val s = if (on) 0.92f else 1f
            scaleX = s; scaleY = s
            dur.text = Fmt.duration(t.durationMs); dur.visibility = if (t.durationMs > 0) VISIBLE else GONE
            time.text = t.time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
            mats.removeAllViews()
            for (k in Kind.entries) if (t.has(k)) mats.addView(materialBadge(k))
            val v = t.video
            val bmp = v?.let { thumbs.get(it.id) }
            img.setImageBitmap(bmp)
            if (v != null && bmp == null && loading.add(v.id)) loader.execute {
                val b = runCatching { contentResolver.loadThumbnail(v.uri, Size(480, 640), null) }.getOrNull()
                runOnUiThread {
                    loading.remove(v.id)
                    if (b != null && !isDestroyed) { thumbs.put(v.id, b); adapter.notifyDataSetChanged() }
                }
            }
        }
    }

    private fun materialBadge(k: Kind) = FrameLayout(this).apply {
        background = rounded(0x33FFFFFF, dp(5).toFloat())
        addView(icon(C.icon(k), C.of(k), 14), FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(4) }
        contentDescription = k.label
    }

    companion object { private const val OPEN = 12 }
}
