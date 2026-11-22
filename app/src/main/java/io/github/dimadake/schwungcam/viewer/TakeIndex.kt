package io.github.dimadake.schwungcam.viewer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a take's file is; the order is the order they are listed in. */
enum class Kind(val label: String) {
    MASTERED("Video (mastered)"),
    RAW_VIDEO("Raw video"),
    RAW_AUDIO("Raw audio WAV"),
    STEMS("Stems"),
    AI("AI timeline"),
}

/** One MediaStore row. [id] is the content URI as a string, so this stays testable on the JVM. */
data class MediaRow(val id: String, val name: String, val path: String, val size: Long, val durationMs: Long = 0, val mime: String = "")

class Take(val name: String, val time: LocalDateTime, val files: Map<Kind, List<MediaRow>>) {
    val size: Long get() = files.values.sumOf { l -> l.sumOf { it.size } }
    val kinds: Set<Kind> get() = files.keys
    val all: List<MediaRow> get() = Kind.entries.flatMap { files[it].orEmpty() }
    /** The file the picture comes from: the mastered video, else the one rescued raw. */
    val video: MediaRow? get() = (files[Kind.MASTERED] ?: files[Kind.RAW_VIDEO])?.firstOrNull()
    val durationMs: Long get() = video?.durationMs ?: 0
    fun has(k: Kind) = !files[k].isNullOrEmpty()
    fun of(kinds: Collection<Kind>): List<MediaRow> = Kind.entries.filter { it in kinds }.flatMap { files[it].orEmpty() }
    fun without(ids: Set<String>): Take? {
        val left = files.mapValues { (_, l) -> l.filter { it.id !in ids } }.filterValues { it.isNotEmpty() }
        return if (left.isEmpty()) null else Take(name, time, left)
    }
}

/** MediaStore rows → takes, grouped by the name every file of a take carries. */
object TakeIndex {
    private val NAME = Regex("SchwungCam_(\\d{8}_\\d{6})")
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    fun group(rows: List<MediaRow>): List<Take> {
        val byTake = LinkedHashMap<String, MutableMap<Kind, MutableList<MediaRow>>>()
        for (r in rows) {
            val (take, kind) = classify(r) ?: continue
            byTake.getOrPut(take) { LinkedHashMap() }.getOrPut(kind) { ArrayList() }.add(r)
        }
        return byTake.mapNotNull { (name, files) ->
            val time = runCatching { LocalDateTime.parse(name.removePrefix("SchwungCam_"), STAMP) }.getOrNull() ?: return@mapNotNull null
            files[Kind.STEMS]?.sortWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            Take(name, time, files)
        }.sortedByDescending { it.time }
    }

    /** The take a row belongs to and what it is there; null for anything this app did not write. */
    fun classify(r: MediaRow): Pair<String, Kind>? {
        val dir = r.path.trimEnd('/')
        val n = r.name
        fun full(s: String) = NAME.matchEntire(s)?.value
        return when {
            dir == "Movies/SchwungCam" && n.endsWith("_raw.mp4") -> full(n.removeSuffix("_raw.mp4"))?.let { it to Kind.RAW_VIDEO }
            dir == "Movies/SchwungCam" && n.endsWith(".mp4") -> full(n.removeSuffix(".mp4"))?.let { it to Kind.MASTERED }
            dir == "Music/SchwungCam" && n.endsWith("_raw.wav") -> full(n.removeSuffix("_raw.wav"))?.let { it to Kind.RAW_AUDIO }
            dir.startsWith("Music/SchwungCam/") && n.endsWith(".wav") -> full(dir.removePrefix("Music/SchwungCam/"))?.let { it to Kind.STEMS }
            dir == "Documents/SchwungCam" && n.endsWith(".ai.zip") -> full(n.removeSuffix(".ai.zip"))?.let { it to Kind.AI }
            else -> null
        }
    }

    /** "Free up space": the raw files a take can do without. A rescued take's raw video is its only picture, so it stays. */
    fun spare(t: Take): List<MediaRow> =
        t.of(listOfNotNull(Kind.RAW_AUDIO, Kind.STEMS, Kind.RAW_VIDEO.takeIf { t.has(Kind.MASTERED) }))

    class Day(val label: String, val takes: List<Take>) {
        val size: Long get() = takes.sumOf { it.size }
    }

    fun days(takes: List<Take>, today: LocalDate): List<Day> =
        takes.groupBy { it.time.toLocalDate() }.map { (d, l) -> Day(dayLabel(d, today), l) }

    fun dayLabel(d: LocalDate, today: LocalDate): String = when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US))
        else -> d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
    }
}

/** "T2" before "T10". */
object NaturalOrder : Comparator<String> {
    private val CHUNK = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val x = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
        val y = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
                else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }
}

object Fmt {
    fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", b / (1024.0 * 1024))
        b >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    /** 9:05, 1:02:03. */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** 09:12.4 */
    fun stamp(t: Double): String {
        val tenths = Math.round(maxOf(0.0, t) * 10)
        return String.format(Locale.US, "%02d:%02d.%d", tenths / 600, tenths / 10 % 60, tenths % 10)
    }
}
