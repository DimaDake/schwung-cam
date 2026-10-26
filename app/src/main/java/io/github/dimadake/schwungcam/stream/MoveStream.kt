package io.github.dimadake.schwungcam.stream

import android.util.Log
import io.github.dimadake.schwungcam.ai.SurfaceSnapshot
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import kotlin.concurrent.thread

/** What the status line shows. */
enum class StreamState { OFFLINE, CONNECTING, SCREEN_ONLY, LIVE }

/**
 * The /mirror stream, on its own thread, reconnecting forever.
 * [clockNs] must be the camera's timestamp clock: arrival times are the sync.
 */
class MoveStream(
    private val host: () -> String,
    private val clockNs: () -> Long,
    private val onScreen: (ScreenFrame) -> Unit,
    private val onPcm: (PcmChunk) -> Unit,
    private val onState: (StreamState) -> Unit,
    private val onSurface: (SurfaceSnapshot) -> Unit = {},
    private val port: Int = PORT,
) {
    @Volatile private var running = false
    // a worker from before a stop() may still be in its backoff sleep; it must not resume
    @Volatile private var generation = 0
    @Volatile private var conn: HttpURLConnection? = null
    private var worker: Thread? = null
    @Volatile var lastPcmNs = 0L; private set

    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        worker = thread(name = "move-stream", isDaemon = true) { loop(gen) }
    }

    fun stop() {
        running = false
        conn?.disconnect()
        worker?.interrupt()
    }

    /** Drops the current connection, e.g. after the host changed. */
    fun reconnect() { conn?.disconnect() }

    private fun loop(gen: Int) {
        while (running && gen == generation) {
            onState(StreamState.CONNECTING)
            try { session(gen) } catch (e: Exception) { Log.i(TAG, "stream: ${e.message}") }
            if (gen != generation) break
            onState(StreamState.OFFLINE)
            if (!running) break
            try { Thread.sleep(1000) } catch (_: InterruptedException) { }
        }
    }

    private fun session(gen: Int) {
        val h = host().trim()
        val addr = resolve(h) ?: throw java.io.IOException("cannot resolve $h")
        val hostPart = if (addr is Inet4Address) addr.hostAddress else "[${addr.hostAddress}]"
        val url = URL("http://$hostPart:$port/stream-auto?v=2&audio=1")
        val c = url.openConnection() as HttpURLConnection
        conn = c
        c.connectTimeout = 3000
        // display-server sends `hb` every 2 s; 5 s of nothing is a dead peer
        c.readTimeout = 5000
        c.setRequestProperty("Accept", "text/event-stream")
        c.setRequestProperty("Host", h)
        c.useCaches = false
        val input = c.inputStream
        val parser = SseParser { ev ->
            when (val d = StreamEvents.decode(ev)) {
                is ScreenFrame -> onScreen(d)
                is PcmChunk -> { lastPcmNs = d.arrivalNs; onPcm(d) }
                is SurfaceSnapshot -> onSurface(d)
            }
            val audio = clockNs() - lastPcmNs < 2_000_000_000L
            onState(if (audio) StreamState.LIVE else StreamState.SCREEN_ONLY)
        }
        val buf = ByteArray(64 * 1024)
        try {
            while (running && gen == generation) {
                val n = input.read(buf)
                if (n < 0) break
                parser.feed(buf, n, clockNs())
            }
        } finally {
            c.disconnect(); conn = null
        }
    }

    private fun resolve(h: String): InetAddress? {
        try { return InetAddress.getByName(h) } catch (_: Exception) { }
        return if (h.endsWith(".local", ignoreCase = true)) Mdns.resolve(h) else null
    }

    companion object {
        const val TAG = "SchwungCam"
        /** schwung-manager, which proxies display-server's stream for /mirror. */
        const val PORT = 7700
    }
}
