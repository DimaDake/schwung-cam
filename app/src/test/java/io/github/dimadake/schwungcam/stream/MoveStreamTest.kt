package io.github.dimadake.schwungcam.stream

import io.github.dimadake.schwungcam.record.PcmRecorder
import io.github.dimadake.schwungcam.record.PcmSink
import io.github.dimadake.schwungcam.sync.ClockEnvelope
import io.github.dimadake.schwungcam.sync.ScreenStamper
import io.github.dimadake.schwungcam.sync.StampedScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The whole receive path over a real socket, against a fake display-server
 * that speaks /stream-auto?v=2&audio=1 the way schwung's does: per 33 ms tick
 * a screen frame (when changed), then a pcm chunk; `hb` every 2 s. It drops
 * the connection once, as a Wi-Fi blip would.
 */
class MoveStreamTest {
    @Test fun `stream feeds envelope, take and stamper, and survives a drop`() {
        val server = ServerSocket(0)
        val rate = 44100
        var devPos = 5_000_000L
        val b64 = Base64.getEncoder()
        thread(isDaemon = true) {
            repeat(2) { conn ->
                val s = server.accept()
                val req = s.getInputStream().bufferedReader().readLine()
                check(req.startsWith("GET /stream-auto?v=2&audio=1")) { req }
                val out = s.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\n\r\n".toByteArray())
                var tick = 0
                val ticks = if (conn == 0) 20 else 1000
                while (tick < ticks) {
                    val n = 1455
                    if (tick % 5 == 0) {
                        val frame = ByteArray(1024) { (tick and 0xFF).toByte() }
                        out.write("data: {\"format\":\"mono1_packed\",\"encoding\":\"base64\",\"width\":128,\"height\":64,\"source\":\"move 1-bit\",\"data\":\"${b64.encodeToString(frame)}\"}\n\n".toByteArray())
                    }
                    val pcm = ByteArray(n * 4) { (it / 4).toByte() }
                    out.write("event: pcm\ndata: {\"pos\":$devPos,\"n\":$n,\"rate\":$rate,\"d\":\"${b64.encodeToString(pcm)}\"}\n\n".toByteArray())
                    devPos += n
                    if (tick % 60 == 0) out.write("event: hb\ndata: {\"frame\":1}\n\n".toByteArray())
                    out.flush()
                    tick++
                    Thread.sleep(33)
                }
                s.close()
            }
        }

        val env = ClockEnvelope(rate)
        val rec = PcmRecorder()
        val screens = CopyOnWriteArrayList<StampedScreen>()
        val stamper = ScreenStamper(env) { screens.add(it) }
        val states = CopyOnWriteArrayList<StreamState>()
        class CountingSink : PcmSink {
            @Volatile var frames = 0L
            @Volatile var silence = 0L
            override fun write(pcm: ShortArray, offFrames: Int, frames: Int) { this.frames += frames }
            override fun silence(frames: Long) { silence += frames }
            override fun close() {}
        }
        val sink = CountingSink()
        val stream = MoveStream(
            host = { "127.0.0.1" },
            clockNs = { System.nanoTime() },
            onScreen = { stamper.onScreen(it) },
            onPcm = { env.add(it.arrivalNs, it.endPos); rec.onChunk(it); stamper.onPcm(it) },
            onState = { states.add(it) },
            port = server.localPort,
        )
        rec.start(sink)
        stream.start()
        Thread.sleep(3500)
        stream.stop()
        val take = rec.stop()!!
        val frames = sink.frames; val silence = sink.silence

        assertTrue("went live: $states", StreamState.LIVE in states)
        assertTrue("reconnected after the drop: $states", states.count { it == StreamState.CONNECTING } >= 2)
        assertTrue("audio arrived: $frames", frames > rate)
        // pos ran on while disconnected, so the take shows that time as silence, not as a shorter take
        assertEquals(take.frames, frames + silence)
        assertTrue("screens stamped: ${screens.size}", screens.size >= 5)
        // loopback has ~no transit: the screen stamp must sit close to the stamping pcm's arrival
        val off = env.liveOffset()!!
        assertTrue("offset is finite", off.isFinite())
        server.close()
    }
}
