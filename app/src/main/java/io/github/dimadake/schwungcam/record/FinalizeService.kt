package io.github.dimadake.schwungcam.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the process, and the CPU, alive while a take is finished, so closing
 * the app or turning the screen off does not lose it. The work stays on the
 * "finalize" thread; this only holds a foreground notification with the
 * progress and a partial wake lock, and says how the take ended when the app
 * is not on screen to say it.
 */
class FinalizeService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID_PROGRESS, progressNotification(this, lastPct), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (wake == null) wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "schwungcam:finalize").apply { acquire(WAKE_MAX_MS) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.release(); wake = null
        super.onDestroy()
    }

    companion object {
        private const val CH_PROGRESS = "saving"
        private const val CH_DONE = "saved"
        private const val ID_PROGRESS = 1
        private const val ID_DONE = 2
        /** Far past any take: the lock is released on finish, this only bounds a hang. */
        private const val WAKE_MAX_MS = 3 * 60 * 60 * 1000L

        /** Whether a camera screen is in front, so the result needs no notification. */
        @Volatile var visible = false
        @Volatile private var lastPct = 0

        fun begin(ctx: Context) {
            channels(ctx)
            lastPct = 0
            ctx.startForegroundService(Intent(ctx, FinalizeService::class.java))
        }

        /** From the finalize thread; the shade is redrawn only on a whole percent. */
        fun progress(ctx: Context, p: Float) {
            val pct = (p * 100).toInt().coerceIn(0, 100)
            if (pct == lastPct) return
            lastPct = pct
            manager(ctx).notify(ID_PROGRESS, progressNotification(ctx, pct))
        }

        /** After the last [progress]: drops the service, and tells of the result unless the app shows it. */
        fun end(ctx: Context, res: Result<FinalResult>) {
            ctx.stopService(Intent(ctx, FinalizeService::class.java))
            val nm = manager(ctx)
            nm.cancel(ID_PROGRESS)
            if (visible) return
            val b = Notification.Builder(ctx, CH_DONE).setAutoCancel(true)
            res.fold({ r ->
                b.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Take saved").setContentText(r.name)
                r.warning?.let { b.setStyle(Notification.BigTextStyle().bigText("${r.name}\n\n$it")) }
                b.setContentIntent(open(ctx, r.uri))
            }, { e ->
                val msg = e.message ?: e.toString()
                b.setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle("Recording failed").setContentText(msg)
                    .setStyle(Notification.BigTextStyle().bigText(msg))
            })
            nm.notify(ID_DONE, b.build())
        }

        private fun progressNotification(ctx: Context, pct: Int): Notification =
            Notification.Builder(ctx, CH_PROGRESS)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle("Saving take")
                .setContentText("$pct%")
                .setProgress(100, pct, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(launch(ctx))
                .build()

        private fun open(ctx: Context, uri: Uri): PendingIntent = PendingIntent.getActivity(ctx, 1,
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        private fun launch(ctx: Context): PendingIntent? = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.let { PendingIntent.getActivity(ctx, 0, it, PendingIntent.FLAG_IMMUTABLE) }

        private fun channels(ctx: Context) {
            val nm = manager(ctx)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Saving takes", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Saved takes", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun manager(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
    }
}
