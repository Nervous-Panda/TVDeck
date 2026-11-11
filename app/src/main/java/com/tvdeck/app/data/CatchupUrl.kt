package com.tvdeck.app.data

import com.tvdeck.app.model.Catchup
import java.util.Calendar
import java.util.TimeZone

/**
 * Builds archive (catch-up / timeshift) URLs using the same conventions as Kodi's
 * IPTV Simple client and most IPTV players.
 */
object CatchupUrl {
    private val tokenRegex =
        Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp|offset|duration)(?::([^}]*))?\}""")
    private val simpleRegex = Regex("""\$?\{([YmdHMS])\}""")
    private val xcLive = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?(?:\?.*)?$""")

    fun build(liveUrl: String, c: Catchup, startMs: Long, endMs: Long, nowMs: Long): String? {
        val tz = TimeZone.getTimeZone(c.timeZoneId)
        val s = startMs / 1000
        val e = maxOf(endMs, startMs + 60_000) / 1000
        val n = nowMs / 1000
        return when (c.mode.lowercase()) {
            "append" -> liveUrl + fill(c.source, s, e, n, tz)
            "shift", "timeshift" -> shift(liveUrl, s, n)
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> flussonic(liveUrl, s, e - s)
            "xc" -> xc(liveUrl, s, e, tz)
            else -> if (c.source.isBlank()) shift(liveUrl, s, n) else fill(c.source, s, e, n, tz)
        }
    }

    private fun shift(url: String, s: Long, n: Long) =
        url + (if ('?' in url) "&" else "?") + "utc=$s&lutc=$n"

    fun fill(template: String, s: Long, e: Long, n: Long, tz: TimeZone): String {
        val withTokens = tokenRegex.replace(template) { m ->
            val fmt = m.groups[2]?.value
            when (m.groupValues[1]) {
                "utc", "start" -> time(s, fmt, tz)
                "utcend", "end" -> time(e, fmt, tz)
                "lutc", "now", "timestamp" -> time(n, fmt, tz)
                "duration" -> divide(e - s, fmt)
                else -> divide(n - s, fmt) // offset
            }
        }
        return simpleRegex.replace(withTokens) { m -> format(m.groupValues[1], s, tz) }
    }

    private fun divide(v: Long, fmt: String?): String =
        (fmt?.toLongOrNull()?.takeIf { it > 0 }?.let { v / it } ?: v).toString()

    private fun time(sec: Long, fmt: String?, tz: TimeZone): String =
        if (fmt.isNullOrEmpty()) sec.toString() else format(fmt, sec, tz)

    /** Formats epoch seconds with a tiny strftime-like syntax: Y m d H M S (other characters are kept). */
    fun format(fmt: String, sec: Long, tz: TimeZone): String {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = sec * 1000 }
        fun two(v: Int) = v.toString().padStart(2, '0')
        return buildString {
            for (ch in fmt) when (ch) {
                'Y' -> append(cal.get(Calendar.YEAR))
                'y' -> append(two(cal.get(Calendar.YEAR) % 100))
                'm' -> append(two(cal.get(Calendar.MONTH) + 1))
                'd' -> append(two(cal.get(Calendar.DAY_OF_MONTH)))
                'H' -> append(two(cal.get(Calendar.HOUR_OF_DAY)))
                'M' -> append(two(cal.get(Calendar.MINUTE)))
                'S' -> append(two(cal.get(Calendar.SECOND)))
                else -> append(ch)
            }
        }
    }

    private fun xc(url: String, s: Long, e: Long, tz: TimeZone): String? {
        val m = xcLive.find(url) ?: return null
        val (host, user, pass, id) = m.destructured
        return "$host/timeshift/$user/$pass/${(e - s) / 60}/${format("Y-m-d:H-M", s, tz)}/$id.ts"
    }

    private fun flussonic(url: String, s: Long, dur: Long): String? {
        val q = url.indexOf('?')
        val base = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        val slash = base.lastIndexOf('/')
        if (slash < 0) return null
        val dir = base.substring(0, slash)
        val file = base.substring(slash + 1)
        return when {
            file == "mpegts" -> "$dir/timeshift_abs-$s.ts$query"
            file.endsWith(".m3u8") -> "$dir/${file.removeSuffix(".m3u8").ifEmpty { "index" }}-$s-$dur.m3u8$query"
            file.endsWith(".ts") -> "$dir/timeshift_abs-$s.ts$query"
            else -> "$base/index-$s-$dur.m3u8$query"
        }
    }
}
