package com.tvdeck.app.data

import android.util.Xml
import com.tvdeck.app.model.Channel
import com.tvdeck.app.model.Programme
import kotlinx.coroutines.CancellationException
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream

/** Streaming XMLTV parser. Keeps only [channels]' programmes overlapping [fromMs, toMs] to save memory. */
object XmlTvParser {

    fun parse(input: InputStream, fromMs: Long, toMs: Long, channels: List<Channel>): Epg {
        val map = HashMap<String, ArrayList<Programme>>()
        val names = HashMap<String, String>()
        val wantedIds = HashSet<String>()
        val wantedNames = HashSet<String>()
        channels.forEach { ch ->
            ch.epgId?.let { wantedIds += it.lowercase() }
            wantedNames += Epg.normName(ch.guideName)
        }
        try {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            var channelId: String? = null
            val last = LastChannel()
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "channel" -> channelId = p.getAttributeValue(null, "id")?.lowercase()
                        "display-name" -> {
                            val id = channelId
                            if (id != null) {
                                val n = Epg.normName(p.nextText())
                                // XMLTV lists every <channel> before the first <programme>.
                                if (n.isNotEmpty() && n in wantedNames && !names.containsKey(n)) {
                                    names[n] = id
                                    wantedIds += id
                                }
                            }
                        }
                        "programme" -> readProgramme(p, map, fromMs, toMs, wantedIds, last)
                    }
                } else if (ev == XmlPullParser.END_TAG && p.name == "channel") {
                    channelId = null
                }
                ev = p.next()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A truncated or slightly malformed guide still gives us everything read so far.
            if (map.isEmpty()) throw IOException("Couldn't read the TV guide: ${e.message}")
        }
        val sorted = HashMap<String, List<Programme>>(map.size)
        for ((k, v) in map) {
            v.sortBy { it.start }
            sorted[k] = v
        }
        return Epg(sorted, names)
    }

    /**
     * Guides list a channel's programmes one after another, so remembering the last channel id saves
     * lowercasing and looking it up again for every programme.
     */
    private class LastChannel {
        var raw: String? = null
        var id: String? = null
        var wanted = false
    }

    private fun readProgramme(
        p: XmlPullParser,
        map: HashMap<String, ArrayList<Programme>>,
        fromMs: Long,
        toMs: Long,
        wantedIds: Set<String>,
        last: LastChannel,
    ) {
        val depth = p.depth
        val raw = p.getAttributeValue(null, "channel")
        if (raw != last.raw) {
            last.raw = raw
            last.id = raw?.lowercase()
            last.wanted = last.id?.let { it in wantedIds } ?: false
        }
        val channel = last.id
        // Big shared guides list many channels the provider doesn't have, and days outside the
        // window kept: skip those without reading their text, which is most of the parsing work.
        val start = if (last.wanted) XmltvTime.parse(p.getAttributeValue(null, "start")) else null
        val stop = if (start != null) XmltvTime.parse(p.getAttributeValue(null, "stop")) else null
        val end = if (start == null) 0L else if (stop != null && stop > start) stop else start + 30 * 60_000L
        if (channel == null || start == null || end < fromMs || start > toMs) {
            skipToEnd(p, depth)
            return
        }
        var title = ""
        var desc = ""
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && p.depth == depth) break
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> if (title.isEmpty()) title = p.nextText().trim()
                    "desc" -> if (desc.isEmpty()) desc = p.nextText().trim().take(700)
                }
            }
        }
        map.getOrPut(channel) { ArrayList() }.add(Programme(start, end, title.ifEmpty { "No title" }, desc))
    }

    /** Moves past the end tag of the element opened at [depth] without reading its text. */
    private fun skipToEnd(p: XmlPullParser, depth: Int) {
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT) return
            if (e == XmlPullParser.END_TAG && p.depth == depth) return
        }
    }
}
