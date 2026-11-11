package com.tvdeck.app.data

import com.tvdeck.app.model.Channel

/**
 * Combines quality versions of the same channel ("Sport TV 1 HD" + "Sport TV 1 FHD") into one entry.
 * The player starts with the preferred version and falls back to the others if it fails.
 */
object ChannelMerger {
    /** Whole-word quality markers, with their rank (higher = better picture). */
    private val tags = linkedMapOf(
        "UHD" to 5, "4K" to 5, "2160P" to 5,
        "FHD" to 4, "FULLHD" to 4, "1080P" to 4, "1080" to 4,
        "HD" to 3, "720P" to 3, "720" to 3, "HQ" to 3,
        "SD" to 1, "576P" to 1, "480P" to 1, "LQ" to 1,
    )
    /** Markers that don't change which channel it is, so they're ignored when matching names. */
    private val ignored = setOf("HEVC", "H265", "H264", "50FPS", "60FPS")
    private val tagRegex = Regex(
        "(?i)(?<![\\p{L}\\p{N}])(FULL\\s*HD|UHD|4K|2160P|FHD|1080P?|HD|720P?|HQ|SD|576P|480P|LQ|HEVC|H\\.?26[45]|[56]0\\s*FPS)(?![\\p{L}\\p{N}])"
    )

    // Compiled once: split() runs for every channel of the provider.
    private val emptyBrackets = Regex("[\\[({]\\s*[\\])}]")
    private val spaces = Regex("\\s+")

    /** "Sport TV 1 FHD" -> ("Sport TV 1", "FHD"). The tag is empty when the name has none. */
    fun split(name: String): Pair<String, String> {
        var best = ""
        var bestRank = 0
        for (m in tagRegex.findAll(name)) {
            val t = m.value.uppercase().replace(" ", "").replace(".", "")
            if (t in ignored) continue
            val rank = tags[t] ?: 0
            if (rank > bestRank) {
                bestRank = rank
                best = when (t) { "FULLHD" -> "FHD"; "1080" -> "1080P"; "720" -> "720P"; else -> t }
            }
        }
        val base = name.replace(tagRegex, " ")
            .replace(emptyBrackets, " ") // brackets left empty, e.g. "(FHD)"
            .replace(spaces, " ")
            .trim(' ', '-', '_', '|', ':', '.')
        return base.ifEmpty { name.trim() } to best
    }

    private fun rank(tag: String) = tags[tag] ?: 2 // untagged sits between HD and SD

    /**
     * Channels with the same base name in the same group and different quality tags become one entry.
     * The combined entry keeps the position, number and key of the first version in the playlist,
     * so favorites and numbering don't change when the preference is switched.
     */
    /** [exclude]: keys never combined automatically (combined or split by the user). */
    fun merge(channels: List<Channel>, preferHighest: Boolean, exclude: Set<String> = emptySet()): List<Channel> {
        data class Item(val ch: Channel, val base: String, val tag: String)
        val items = channels.filter { it.key !in exclude }.map { ch -> split(ch.name).let { (b, t) -> Item(ch, b, t) } }
        val buckets = LinkedHashMap<String, MutableList<Item>>()
        for (it in items) {
            val k = it.ch.group + "\u0000" + it.base.lowercase().filter { c -> c.isLetterOrDigit() }
            buckets.getOrPut(k) { ArrayList() }.add(it)
        }
        val mergedByFirstKey = HashMap<String, Channel>()
        val absorbed = HashSet<String>()
        for (list in buckets.values) {
            // Only combine real quality variants: at least one tagged, and no two with the same tag
            // (two identically-named channels are more likely different feeds than backups).
            if (list.size < 2) continue
            if (list.all { it.tag.isEmpty() }) continue
            if (list.map { it.tag }.toSet().size != list.size) continue
            val ordered = list.sortedWith(
                if (preferHighest) compareByDescending<Item> { rank(it.tag) } else compareBy<Item> {
                    // Prefer HD, then FHD/UHD, then the rest: HD is usually the most reliable feed.
                    when (it.tag) { "HD", "720P" -> 0; "FHD", "1080P" -> 1; "UHD", "4K", "2160P" -> 2; "" -> 3; else -> 4 }
                }
            )
            val first = list.first().ch // playlist order: stable key, number and position
            val primary = ordered.first()
            val combined = primary.ch.copy(
                key = first.key,
                name = list.first().base,
                number = first.number,
                quality = primary.tag,
                epgId = primary.ch.epgId ?: list.firstNotNullOfOrNull { it.ch.epgId },
                logo = primary.ch.logo ?: list.firstNotNullOfOrNull { it.ch.logo },
                alternates = ordered.drop(1).map { it.ch.copy(quality = it.tag) },
            )
            mergedByFirstKey[first.key] = combined
            list.drop(1).forEach { absorbed += it.ch.key }
        }
        if (mergedByFirstKey.isEmpty()) return channels
        return channels.mapNotNull { ch ->
            when (ch.key) {
                in absorbed -> null
                else -> mergedByFirstKey[ch.key] ?: ch
            }
        }
    }
}
