package com.tvdeck.app.data

import com.tvdeck.app.model.Catchup

data class M3uEntry(
    val name: String,
    val url: String,
    val attrs: Map<String, String>,
    val group: String,
)

data class M3uPlaylist(
    val header: Map<String, String>,
    val entries: List<M3uEntry>,
) {
    /** Guide URL declared in the #EXTM3U header, if any (first of a comma separated list). */
    val epgUrl: String?
        get() = listOf("x-tvg-url", "url-tvg", "tvg-url")
            .firstNotNullOfOrNull { header[it]?.takeIf { v -> v.isNotBlank() } }
            ?.split(',')
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
}

enum class EntryKind { LIVE, MOVIE, SERIES }

data class EpisodeName(val series: String, val season: Int, val episode: Int)

object M3uParser {
    private val videoExt = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv", ".flv", ".webm", ".mpg", ".mpeg")
    private val episodeRegex = Regex("""^(.*?)[\s._\-]*[Ss](\d{1,2})[\s._\-]*[Ee](\d{1,4})""")

    fun parse(lines: Sequence<String>): M3uPlaylist {
        var header: Map<String, String> = emptyMap()
        val out = ArrayList<M3uEntry>()
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var extGroup: String? = null

        for (raw in lines) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U") -> header = parseAttrs(line)
                line.startsWith("#EXTINF") -> {
                    val comma = nameComma(line)
                    attrs = parseAttrs(if (comma >= 0) line.substring(0, comma) else line)
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    extGroup = null
                }
                line.startsWith("#EXTGRP:") -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit // other directives (#EXTVLCOPT, #KODIPROP, ...) are ignored
                else -> {
                    val title = name?.takeIf { it.isNotEmpty() }
                        ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                        ?: line.substringBefore('?').substringAfterLast('/')
                    val group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                        ?: extGroup?.takeIf { it.isNotBlank() }
                        ?: "Uncategorized"
                    out += M3uEntry(title, line, attrs, group.trim())
                    name = null
                    attrs = emptyMap()
                    extGroup = null
                }
            }
        }
        return M3uPlaylist(header, out)
    }

    /**
     * Reads `key="value"` pairs. A hand-written scanner: this runs for every line of playlists with
     * tens of thousands of entries, where a regular expression was the slowest part of loading.
     */
    fun parseAttrs(s: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        val n = s.length
        var i = 0
        while (i < n) {
            // Find the next '=' and walk back over the key.
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            var keyEnd = eq
            while (keyEnd > i && (s[keyEnd - 1] == ' ' || s[keyEnd - 1] == '\t')) keyEnd--
            var keyStart = keyEnd
            while (keyStart > i && isKeyChar(s[keyStart - 1])) keyStart--
            // Skip spaces after '=', then expect a quoted value. Without a key it isn't an attribute.
            var v = eq + 1
            while (v < n && (s[v] == ' ' || s[v] == '\t')) v++
            if (keyStart == keyEnd || v >= n || s[v] != '"') {
                i = eq + 1
                continue
            }
            val close = s.indexOf('"', v + 1)
            if (close < 0) break
            m[s.substring(keyStart, keyEnd).lowercase()] = s.substring(v + 1, close)
            i = close + 1
        }
        return m
    }

    private fun isKeyChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    /** Index of the comma that separates attributes from the display name (ignores commas inside quotes). */
    fun nameComma(line: String): Int {
        var quoted = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            else if (c == ',' && !quoted) return i
        }
        return -1
    }

    fun classify(e: M3uEntry): EntryKind {
        val u = e.url.lowercase().substringBefore('?')
        if ("/series/" in u) return EntryKind.SERIES
        if ("/movie/" in u) return EntryKind.MOVIE
        if (videoExt.any { u.endsWith(it) }) {
            return if (episodeName(e.name) != null) EntryKind.SERIES else EntryKind.MOVIE
        }
        return EntryKind.LIVE
    }

    fun episodeName(name: String): EpisodeName? {
        val m = episodeRegex.find(name) ?: return null
        val series = m.groupValues[1].trim(' ', '-', '_', '.', '|', ':')
        if (series.isEmpty()) return null
        return EpisodeName(series, m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    /** Catch-up settings from channel attributes, falling back to playlist-wide header attributes. */
    fun catchupFor(e: M3uEntry, header: Map<String, String>): Catchup? {
        fun attr(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> e.attrs[k]?.takeIf { it.isNotBlank() } }
                ?: keys.firstNotNullOfOrNull { k -> header[k]?.takeIf { it.isNotBlank() } }

        var mode = attr("catchup", "catchup-type")?.trim()?.lowercase()
        val source = attr("catchup-source")?.trim() ?: ""
        val days = attr("catchup-days", "tvg-rec", "timeshift")?.trim()?.toIntOrNull() ?: 0
        if (mode == null) {
            if (days <= 0) return null
            mode = if (source.isNotEmpty()) "default" else "shift"
        }
        if (mode == "disabled" || mode == "none" || mode == "0") return null
        return Catchup(mode, source, if (days > 0) days else 7, "UTC")
    }
}
