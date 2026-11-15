package io.github.dimadake.schwungcam.link

import io.github.dimadake.schwungcam.record.WavWriter
import java.io.File

/** One recorded stem, raw in the take's cache dir until the finalizer places it. */
class StemCapture(
    val peer: String, val name: String, val file: File, val rate: Int,
    /** Camera time of the file's first frame. */
    val anchorNs: Long,
    val frames: Long, val lostFrames: Long, val resyncs: Int,
    val gapFrames: Long = 0, val droppedFrames: Long = 0,
)

/** [missing]: picked channels that gave no audio during the take. */
class StemsTake(val stems: List<StemCapture>, val missing: List<String>)

/**
 * Link Audio stems while the viewfinder is open and the setting is on: keeps
 * the picked channels subscribed with a [PRE_ROLL_SEC] pre-roll (their buffers
 * arrive a network delay after the camera saw that moment), and records them
 * into [StemLane]s during a take.
 *
 * Every [LinkPort] call is made on this class's own thread: the native core
 * is single-caller.
 */
class StemRecorder(
    private val openPort: () -> LinkPort?,
    private val clockNs: () -> Long,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile var picks: Set<String> = emptySet()
    @Volatile var channels: List<LinkChannel> = emptyList(); private set
    @Volatile var peers = 0; private set
    /** Keys of the picked channels subscribed now. */
    @Volatile var subscribed: Set<String> = emptySet(); private set
    /** False when the Link library is not in this build. */
    @Volatile var available = true; private set

    private var thread: Thread? = null
    @Volatile private var running = false
    private val lock = Object()
    private val preRoll = HashMap<String, ArrayDeque<StemBlock>>()
    private val preRollFrames = HashMap<String, Long>()
    private var take: Take? = null

    private class Take(val dir: File, val picks: Set<String>) {
        val lanes = LinkedHashMap<String, StemLane>()
        val files = HashMap<String, File>()
    }

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread(::run, "link-stems").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        channels = emptyList(); peers = 0; subscribed = emptySet()
        synchronized(lock) { preRoll.clear(); preRollFrames.clear() }
    }

    fun startTake(dir: File) = synchronized(lock) {
        val t = Take(dir, picks)
        take = t
        for ((key, q) in preRoll) for (b in q) laneFor(t, key)?.add(b)
    }

    /** Camera time up to which every recording stem has audio; null with none. */
    fun coveredNs(): Long? = synchronized(lock) { take?.lanes?.values?.mapNotNull { it.coveredNs() }?.minOrNull() }

    fun stopTake(): StemsTake? = synchronized(lock) {
        val t = take ?: return null
        take = null
        val out = ArrayList<StemCapture>()
        for ((key, lane) in t.lanes) {
            lane.finish()?.close()
            val a = lane.anchorNs() ?: continue
            out.add(StemCapture(LinkChannel.peerOf(key), LinkChannel.nameOf(key), t.files[key]!!, lane.rate, a,
                lane.frames, lane.lostFrames, lane.resyncs, lane.gapFrames, lane.droppedFrames))
        }
        val got = out.map { LinkChannel.keyOf(it.peer, it.name) }.toSet()
        StemsTake(out, t.picks.filter { it !in got }.map(LinkChannel::nameOf).sorted())
    }

    private fun laneFor(t: Take, key: String): StemLane? {
        if (key !in t.picks) return null
        return t.lanes.getOrPut(key) {
            val f = File(t.dir, "stem-${t.lanes.size}.wav")
            t.files[key] = f
            StemLane({ rate -> WavWriter(f, rate) }) { errMs, count ->
                log("stem ${LinkChannel.nameOf(key)}: buffer $count stamped %+.1f ms from its place; re-aligned".format(errMs))
            }
        }
    }

    private fun run() {
        val port = openPort()
        if (port == null) { available = false; running = false; return }
        val meta = LongArray(4 * 64)
        val pcm = ShortArray(2 * 32768)
        var lastList = 0L
        var ids = emptyMap<String, String>()   // id → key
        try {
            port.setActive(true, nowMs())
            while (running) {
                val now = nowMs()
                port.poll(now)
                if (now - lastList >= LIST_MS) {
                    lastList = now
                    val ch = port.channels()
                    channels = ch; peers = port.peers()
                    val want = ch.filter { it.key in picks }
                    val got = port.subscribe(want.map { it.id }).toSet()
                    ids = want.filter { it.id in got }.associate { it.id to it.key }
                    subscribed = ids.values.toSet()
                    synchronized(lock) { preRoll.keys.retainAll(subscribed); preRollFrames.keys.retainAll(subscribed) }
                }
                // read the two clocks back to back each pass, so slew between them never adds up
                val c0 = clockNs(); val u = port.nowUs(); val c1 = clockNs()
                val offNs = (c0 + c1) / 2 - u * 1000
                for ((id, key) in ids) {
                    while (true) {
                        val n = port.drain(id, meta, pcm)
                        if (n == 0) break
                        var at = 0
                        synchronized(lock) {
                            for (i in 0 until n) {
                                val frames = meta[4 * i + 3].toInt()
                                val start = meta[4 * i + 2]
                                val b = StemBlock(meta[4 * i], meta[4 * i + 1].toInt(),
                                    if (start < 0) StemBlock.NO_STAMP else start * 1000 + offNs,
                                    pcm.copyOfRange(2 * at, 2 * (at + frames)), frames)
                                at += frames
                                keep(key, b)
                                take?.let { t -> laneFor(t, key)?.add(b) }
                            }
                        }
                        if (n < meta.size / 4) break
                    }
                }
                Thread.sleep(PASS_MS)
            }
        } catch (e: InterruptedException) {
        } finally {
            port.setActive(false, nowMs())
            port.close()
        }
    }

    private fun keep(key: String, b: StemBlock) {
        val q = preRoll.getOrPut(key) { ArrayDeque() }
        q.addLast(b)
        var total = (preRollFrames[key] ?: 0L) + b.frames
        while (q.size > 1 && total - q.first().frames >= PRE_ROLL_SEC * b.rate) total -= q.removeFirst().frames
        preRollFrames[key] = total
    }

    private fun log(msg: String) { try { android.util.Log.i("SchwungCam", msg) } catch (e: Throwable) {} }

    companion object {
        const val PRE_ROLL_SEC = 2
        const val PASS_MS = 20L
        const val LIST_MS = 500L
    }
}
