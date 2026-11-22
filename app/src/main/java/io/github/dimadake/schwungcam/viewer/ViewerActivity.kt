package io.github.dimadake.schwungcam.viewer

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.dimadake.schwungcam.R

/** What both viewer screens share: the dark frame, sharing, trash with Undo, the pick sheets. */
abstract class ViewerActivity : Activity() {
    lateinit var root: FrameLayout
    lateinit var snack: Snackbar
    private var pending: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        snack = Snackbar(root)
        setContentView(root)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
    }

    /** A top app bar: [lead] icon, a title (and a subtitle), then [actions]. */
    fun topBar(lead: Int, leadDesc: String, onLead: () -> Unit, title: TextView, sub: TextView? = null, vararg actions: View) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64); setPadding(dp(4), 0, dp(4), 0)
            addView(iconButton(lead, leadDesc, C.TEXT) { onLead() })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title.apply { setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END })
                if (sub != null) addView(sub)
            }, lp(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
            actions.forEach { addView(it) }
        }

    // ---- share

    fun share(rows: List<MediaRow>) {
        if (rows.isEmpty()) return
        val uris = ArrayList(rows.map { it.uri })
        val mimes = rows.map { it.mime.ifEmpty { "*/*" } }.distinct()
        val mime = mimes.singleOrNull() ?: mimes.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // the chooser passes the grant only for what is in clipData
        send.clipData = android.content.ClipData.newRawUri(null, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(android.content.ClipData.Item(it)) } }
        startActivity(Intent.createChooser(send, null))
    }

    /** The pick sheet: a row per material the takes have, mastered video ticked. */
    fun shareSheet(takes: List<Take>) {
        val kinds = Kind.entries.filter { k -> takes.any { it.has(k) } }
        if (kinds.isEmpty()) return
        val picked = HashSet<Kind>()
        picked += if (takes.any { it.has(Kind.MASTERED) }) Kind.MASTERED else kinds.first()
        val sheet = Sheet(this, if (takes.size == 1) "Share take" else "Share ${takes.size} takes")
        lateinit var go: TextView
        val marks = ArrayList<Pair<Kind, CheckMark>>()
        fun files() = takes.flatMap { it.of(picked) }
        fun update() {
            val f = files()
            go.text = if (f.isEmpty()) "Share" else "Share ${Fmt.size(f.sumOf { it.size })}"
            go.isEnabled = f.isNotEmpty(); go.alpha = if (f.isEmpty()) 0.4f else 1f
        }
        for (k in kinds) {
            val f = takes.flatMap { it.files[k].orEmpty() }
            val label = if (k == Kind.STEMS) "Stems (${f.size} WAVs)" else if (k == Kind.AI) "AI timeline (.ai.zip)" else k.label
            val sub = Fmt.size(f.sumOf { it.size }) + (if (k == Kind.AI) " · for captioning with an AI" else "") +
                (if (takes.size > 1) " · ${f.size} files" else "")
            val (_, m) = sheet.checkRow(label, sub, C.icon(k), C.of(k), k in picked) { on -> if (on) picked += k else picked -= k; update() }
            marks += k to m
        }
        sheet.body.addView(text("Select all", 14f, C.ACCENT).apply {
            setPadding(dp(24), dp(12), dp(24), dp(12)); background = ripple()
            setOnClickListener { marks.forEach { (k, m) -> m.checked = true; picked += k }; update() }
        })
        go = sheet.buttons("Share") { share(files()) }
        update()
        sheet.show()
    }

    /**
     * "Delete from N takes": Everything, or picked material types. With
     * [freeUp] it starts on the raw kinds and keeps a rescued take's only
     * picture ([TakeIndex.spare]).
     */
    fun deleteSheet(takes: List<Take>, freeUp: Boolean = false, done: (List<MediaRow>) -> Unit) {
        val kinds = Kind.entries.filter { k -> takes.any { it.has(k) } }
        val picked = HashSet<Kind>()
        if (freeUp) picked += listOf(Kind.RAW_VIDEO, Kind.RAW_AUDIO, Kind.STEMS)
        var everything = !freeUp
        fun inScope(t: Take, k: Kind) = if (freeUp) TakeIndex.spare(t).filter { r -> t.files[k].orEmpty().contains(r) } else t.files[k].orEmpty()
        fun files() = if (everything) takes.flatMap { it.all } else takes.flatMap { t -> kinds.filter { it in picked }.flatMap { inScope(t, it) } }
        val sheet = Sheet(this, if (freeUp) "Free up space" else "Delete from ${takes.size} take${if (takes.size == 1) "" else "s"}")
        lateinit var go: TextView
        val rows = ArrayList<Triple<Kind, View, CheckMark>>()
        fun update() {
            rows.forEach { (k, v, m) -> v.alpha = if (everything) 0.45f else 1f; m.checked = everything || k in picked }
            val f = files()
            go.text = if (f.isEmpty()) "Move to trash" else "Trash ${Fmt.size(f.sumOf { it.size })}"
            go.isEnabled = f.isNotEmpty(); go.alpha = if (f.isEmpty()) 0.4f else 1f
        }
        if (!freeUp) {
            sheet.checkRow("Everything (whole takes)", Fmt.size(takes.sumOf { it.size }), null, 0, true) { on -> everything = on; update() }
            sheet.body.addView(View(this).apply { setBackgroundColor(0xFF333333.toInt()) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { setMargins(dp(24), dp(4), dp(24), dp(4)) })
        }
        for (k in kinds) {
            val f = takes.flatMap { inScope(it, k) }
            if (f.isEmpty()) continue
            val (v, m) = sheet.checkRow(k.label, "${f.size} file${if (f.size == 1) "" else "s"} · ${Fmt.size(f.sumOf { it.size })}", C.icon(k), C.of(k), false) { on ->
                if (everything) { everything = false; picked.clear() }
                if (on) picked += k else picked -= k
                update()
            }
            rows += Triple(k, v, m)
        }
        go = sheet.buttons("Move to trash", C.ERROR, C.ON_ERROR) { val f = files(); trash(f) { ok -> if (ok) done(f) } }
        update()
        sheet.show()
    }

    // ---- delete

    /** Moves [rows] to the system trash in one consent (Android 11+); on 10 asks, then deletes for good. */
    fun trash(rows: List<MediaRow>, then: (Boolean) -> Unit) {
        if (rows.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ask(MediaStore.createTrashRequest(contentResolver, rows.map { it.uri }, true), then)
        } else {
            confirmDelete(rows, then)
        }
    }

    fun undoTrash(rows: List<MediaRow>, then: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // the app's own files come back without asking; the rest need the system's yes
        val left = rows.filter { r ->
            runCatching { contentResolver.update(r.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 0) }, null, null) > 0 }
                .getOrDefault(false).not()
        }
        if (left.isEmpty()) then(true) else ask(MediaStore.createTrashRequest(contentResolver, left.map { it.uri }, false), then)
    }

    fun deleteForGood(rows: List<MediaRow>, then: (Boolean) -> Unit) {
        if (rows.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ask(MediaStore.createDeleteRequest(contentResolver, rows.map { it.uri }), then)
        else confirmDelete(rows, then)
    }

    /** Trash, then "Moved to trash · UNDO". */
    fun trashWithUndo(rows: List<MediaRow>, what: String, after: () -> Unit) {
        trash(rows) { ok ->
            if (!ok) return@trash
            after()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                snack.show("$what moved to trash", "Undo") { undoTrash(rows) { back -> if (back) after() } }
            else snack.show("$what deleted")
        }
    }

    private fun confirmDelete(rows: List<MediaRow>, then: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${rows.size} file${if (rows.size == 1) "" else "s"}?")
            .setMessage("${Fmt.size(rows.sumOf { it.size })} will be deleted for good. This can't be undone.")
            .setNegativeButton(android.R.string.cancel) { _, _ -> then(false) }
            .setPositiveButton("Delete") { _, _ -> deleteDirect(rows, then) }
            .show()
    }

    private fun deleteDirect(rows: List<MediaRow>, then: (Boolean) -> Unit) {
        var failed = 0
        for (r in rows) {
            try {
                contentResolver.delete(r.uri, null, null)
            } catch (e: RecoverableSecurityException) {
                // another install's file: Android 10 asks per file; the first one asks, the rest wait for the next try
                ask(e.userAction.actionIntent) { ok -> if (ok) deleteDirect(rows.drop(rows.indexOf(r)), then) else then(false) }
                return
            } catch (e: Exception) {
                Log.w("SchwungCam", "delete ${r.name}", e); failed++
            }
        }
        if (failed > 0) Toast.makeText(this, "$failed file(s) could not be deleted", Toast.LENGTH_LONG).show()
        then(true)
    }

    private fun ask(pi: android.app.PendingIntent, then: (Boolean) -> Unit) {
        pending = then
        try {
            startIntentSenderForResult(pi.intentSender, REQ, null, 0, 0, 0)
        } catch (e: Exception) {
            pending = null
            Log.w("SchwungCam", "media request", e)
            Toast.makeText(this, "Android refused: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ) { val p = pending; pending = null; p?.invoke(resultCode == RESULT_OK); return }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** Runs [work] off the main thread and [done] back on it, unless the screen is gone. */
    fun <T> background(work: () -> T, done: (T) -> Unit) {
        Thread {
            val r = try { work() } catch (e: Exception) { Log.w("SchwungCam", "viewer", e); null }
            runOnUiThread { if (!isDestroyed && r != null) done(r) }
        }.start()
    }

    companion object {
        private const val REQ = 41
        const val EXTRA_TAKE = "take"
        const val EXTRA_TRASHED = "trashed"
    }
}

/** The delete icon of a top bar. */
fun ViewerActivity.deleteIcon(on: () -> Unit) = iconButton(R.drawable.ic_delete, "Delete") { on() }
fun ViewerActivity.shareIcon(on: () -> Unit) = iconButton(R.drawable.ic_share, "Share") { on() }
