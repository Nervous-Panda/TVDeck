package com.tvdeck.app.data

import com.tvdeck.app.model.Channel
import com.tvdeck.app.model.Programme

/** In-memory TV guide. Programme lists are sorted by start time. */
class Epg(
    private val byId: Map<String, List<Programme>>,
    private val nameToId: Map<String, String>,
) {
    val channelCount: Int get() = byId.size
    val programmeCount: Int = byId.values.sumOf { it.size }
    private val cache = HashMap<String, List<Programme>>()

    fun programmes(ch: Channel): List<Programme> = cache.getOrPut(ch.key) {
        ch.epgId?.lowercase()?.let { byId[it] }
            ?: normName(ch.guideName).takeIf { it.isNotEmpty() }?.let { nameToId[it] }?.let { byId[it] }
            ?: emptyList()
    }

    fun current(ch: Channel, now: Long): Programme? {
        val l = programmes(ch)
        var lo = 0
        var hi = l.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = l[mid]
            when {
                p.stop <= now -> lo = mid + 1
                p.start > now -> hi = mid - 1
                else -> return p
            }
        }
        return null
    }

    fun next(ch: Channel, now: Long): Programme? {
        val cur = current(ch, now)
        val from = cur?.stop ?: now
        return programmes(ch).firstOrNull { it.start >= from }
    }

    companion object {
        fun normName(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
    }
}

object XmltvTime {
    /** Parses XMLTV times like "20261031203000 +0100" to epoch milliseconds. */
    fun parse(raw: String?): Long? {
        if (raw == null) return null
        val t = raw.trim()
        if (t.length < 12) return null
        fun num(a: Int, b: Int): Int? = if (t.length >= b) t.substring(a, b).toIntOrNull() else null
        val y = num(0, 4) ?: return null
        val mo = num(4, 6) ?: return null
        val d = num(6, 8) ?: return null
        val h = num(8, 10) ?: return null
        val mi = num(10, 12) ?: return null
        val hasSeconds = t.length >= 14 && t[12].isDigit() && t[13].isDigit()
        val sec = if (hasSeconds) num(12, 14) ?: 0 else 0
        val rest = t.substring(if (hasSeconds) 14 else 12).trim()
        var offMin = 0
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
            val hh = rest.substring(1, 3).toIntOrNull() ?: 0
            val mm = rest.substring(3, 5).toIntOrNull() ?: 0
            offMin = (hh * 60 + mm) * (if (rest[0] == '-') -1 else 1)
        }
        val days = daysFromCivil(y, mo, d)
        return ((days * 86_400L + h * 3_600L + mi * 60L + sec) - offMin * 60L) * 1000L
    }

    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
