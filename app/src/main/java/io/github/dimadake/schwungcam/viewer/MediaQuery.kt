package io.github.dimadake.schwungcam.viewer

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/** This app's files in MediaStore, every kind a take writes. */
object MediaQuery {
    fun rows(resolver: ContentResolver): List<MediaRow> {
        val out = ArrayList<MediaRow>()
        query(resolver, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Movies/SchwungCam%", true, out)
        query(resolver, MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Music/SchwungCam%", true, out)
        // MediaStore keeps the zip in Files only; media there would be counted twice
        query(resolver, MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), "Documents/SchwungCam%", false, out,
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.ai.zip'")
        return out
    }

    fun takes(resolver: ContentResolver): List<Take> = TakeIndex.group(rows(resolver))

    private fun query(resolver: ContentResolver, col: Uri, path: String, media: Boolean, out: MutableList<MediaRow>, extra: String? = null) {
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE) + if (media) arrayOf(MediaStore.Video.VideoColumns.DURATION) else emptyArray()
        val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0" + (extra?.let { " AND $it" } ?: "")
        try {
            resolver.query(col, proj, sel, arrayOf(path), null)?.use { c ->
                while (c.moveToNext()) {
                    val id = ContentUris.withAppendedId(col, c.getLong(0)).toString()
                    out += MediaRow(id, c.getString(1) ?: continue, c.getString(2) ?: "", c.getLong(3),
                        if (media) c.getLong(5) else 0, c.getString(4) ?: "")
                }
            }
        } catch (e: Exception) {
            Log.w("SchwungCam", "query $col", e)
        }
    }
}

val MediaRow.uri: Uri get() = Uri.parse(id)
