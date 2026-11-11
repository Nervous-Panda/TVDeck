package com.tvdeck.app.data

import android.content.Context
import com.tvdeck.app.model.Channel
import com.tvdeck.app.model.ChannelLayout
import com.tvdeck.app.model.ContentFilter
import com.tvdeck.app.model.GroupInfo
import com.tvdeck.app.model.Profile
import com.tvdeck.app.model.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Small web server for managing the IPTV provider (profiles) and the active groups and channels
 * from a phone or computer on the same network, all on one page. It only runs while the manager
 * screen is open and every API call needs the PIN shown on the TV. Passwords, M3U playlist URLs
 * and stream URLs can contain the provider's credentials, so they are never sent to the browser.
 */
class ManagerServer(context: Context) {
    private val app = context.applicationContext
    private val random = SecureRandom()
    private val failures = AtomicInteger(0)
    private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val pin = MutableStateFlow(newPin())
    var port = 0
        private set

    fun start() {
        val server = try {
            ServerSocket(PORT)
        } catch (e: BindException) {
            ServerSocket(0)
        }
        socket = server
        port = server.localPort
        val workers = Executors.newFixedThreadPool(4)
        pool = workers
        thread(name = "manager-server", isDaemon = true) {
            try {
                while (true) {
                    val client = server.accept()
                    workers.execute { client.use { serve(it) } }
                }
            } catch (e: IOException) {
                // The socket was closed by stop().
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        pool?.shutdownNow()
    }

    private fun newPin() = (100_000 + random.nextInt(900_000)).toString()

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 15_000
            val input = BufferedInputStream(s.getInputStream())
            val parts = readLine(input).split(' ')
            if (parts.size < 2) return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                if (headers.size >= 100) return
                val c = line.indexOf(':')
                if (c > 0) headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length !in 0..MAX_BODY) return respond(s, 413, JSON, """{"error":"too large"}""")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) return
                read += n
            }
            route(s, parts[0], parts[1], headers["x-pin"], String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            runCatching { respond(s, 500, JSON, """{"error":"server error"}""") }
        }
    }

    private fun route(s: Socket, method: String, target: String, givenPin: String?, body: String) {
        val path = target.substringBefore('?')
        if (method == "GET" && path == "/") {
            val page = app.assets.open("manager.html").use { it.readBytes() }
            return respond(s, 200, "text/html; charset=utf-8", page)
        }
        if (!path.startsWith("/api/")) return respond(s, 404, JSON, """{"error":"not found"}""")
        if (givenPin != pin.value) {
            // Replacing the PIN after repeated failures stops anyone on the network guessing it.
            if (failures.incrementAndGet() >= MAX_FAILURES) {
                failures.set(0)
                pin.value = newPin()
            }
            return respond(s, 401, JSON, """{"error":"wrong pin"}""")
        }
        failures.set(0)
        when {
            method == "GET" && path == "/api/state" -> respond(s, 200, JSON, state().toString())
            method == "GET" && path == "/api/channels" -> {
                val group = URLDecoder.decode(target.substringAfter("group=", ""), "UTF-8")
                respond(s, 200, JSON, channels(group).toString())
            }
            method == "POST" && path == "/api/apply" -> apply(s, body)
            method == "POST" && path == "/api/profile" -> saveProfile(s, body)
            method == "POST" && path == "/api/activate" -> activate(s, body)
            method == "POST" && path == "/api/delete" -> delete(s, body)
            method == "POST" && path == "/api/settings" -> settings(s, body)
            method == "GET" && path == "/api/search" -> {
                val q = URLDecoder.decode(target.substringAfter("q=", ""), "UTF-8")
                respond(s, 200, JSON, search(q).toString())
            }
            method == "POST" && path == "/api/combine" -> combineChannels(s, body)
            method == "POST" && path == "/api/split" -> splitChannel(s, body)
            else -> respond(s, 404, JSON, """{"error":"not found"}""")
        }
    }

    /** Runs on the main thread, where the UI and Repo change their state. */
    private fun <T> onMain(block: () -> T): T = runBlocking(Dispatchers.Main.immediate) { block() }

    private fun state(): JSONObject {
        val active = Store.activeProfile()
        val o = JSONObject()
            .put("profiles", profiles(active?.id))
            .put("activeId", active?.id ?: JSONObject.NULL)
            .put("autoRefreshHours", Store.autoRefreshHours.value)
        if (active == null) return o.put("status", "none")
        val c = Repo.content.value
        if (c != null && c.profile.id == active.id) {
            val f = Store.contentFilter(active.id)
            onMain { Repo.refreshAccount(app) }
            Repo.account.value?.let { a ->
                o.put(
                    "account", JSONObject()
                        .put("status", a.status)
                        .put("expiresAt", a.expiresAt ?: JSONObject.NULL)
                        .put("maxConnections", a.maxConnections ?: JSONObject.NULL)
                        .put("activeConnections", a.activeConnections ?: JSONObject.NULL)
                        .put("trial", a.trial)
                )
            }
            val last = Store.lastRefresh(active.id)
            if (last > 0) o.put("lastRefresh", last)
            val layout = Store.channelLayout(active.id)
            o.put("renumber", layout.renumber)
            o.put("combinations", combinations(c, layout))
            return o.put("status", "ready")
                .put("live", groups(c.catalog.live, f.disabledLive))
                .put("movies", groups(c.catalog.movies, f.disabledMovies))
                .put("series", groups(c.catalog.series, f.disabledSeries))
        }
        if (Repo.currentProfile != active) {
            // Opened from a screen that hadn't loaded the active profile yet.
            onMain { if (Repo.currentProfile != active) Repo.load(app, active) }
            return o.put("status", "loading").put("message", "Connecting to ${active.name}…")
        }
        return when (val st = Repo.state.value) {
            is LoadState.Error -> o.put("status", "error").put("message", st.message)
            is LoadState.Loading -> o.put("status", "loading").put("message", st.message)
            else -> o.put("status", "loading").put("message", "Loading…")
        }
    }

    private fun profiles(activeId: String?): JSONArray {
        val a = JSONArray()
        Store.profiles.value.forEach { p ->
            a.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("type", p.type.name)
                    .put("host", hostOf(p.url))
                    // An M3U URL usually carries the account's credentials, like the password.
                    .put("url", if (p.type == SourceType.XTREAM) p.url else "")
                    .put("username", p.username)
                    .put("epgUrl", p.epgUrl)
                    .put("userAgent", p.userAgent)
                    .put("outputFormat", p.outputFormat)
                    .put("active", p.id == activeId)
            )
        }
        return a
    }

    private fun saveProfile(s: Socket, body: String) {
        val o = JSONObject(body)
        val id = o.optString("id")
        val existing = Store.profiles.value.firstOrNull { it.id == id }
        if (id.isNotEmpty() && existing == null) return respond(s, 404, JSON, error("That provider no longer exists."))
        val type = if (o.optString("type") == "M3U") SourceType.M3U else SourceType.XTREAM
        // Secrets aren't sent to the browser, so a blank field keeps what is saved.
        val kept = existing?.takeIf { it.type == type }
        val url = o.optString("url").trim().ifEmpty { if (type == SourceType.M3U) kept?.url.orEmpty() else "" }
        val password = o.optString("password").trim().ifEmpty { kept?.password.orEmpty() }
        val username = o.optString("username").trim()
        if (url.isEmpty()) {
            return respond(s, 400, JSON, error(if (type == SourceType.XTREAM) "Please enter the server URL." else "Please enter the playlist URL."))
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return respond(s, 400, JSON, error("The URL must start with http:// or https://"))
        }
        if (type == SourceType.XTREAM && (username.isEmpty() || password.isEmpty())) {
            return respond(s, 400, JSON, error("Username and password are required for Xtream Codes."))
        }
        val p = Profile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = o.optString("name").trim().ifEmpty { hostOf(url) },
            type = type,
            url = url,
            username = if (type == SourceType.XTREAM) username else "",
            password = if (type == SourceType.XTREAM) password else "",
            epgUrl = o.optString("epgUrl").trim(),
            userAgent = o.optString("userAgent").trim(),
            outputFormat = if (o.optString("outputFormat") == "m3u8") "m3u8" else "ts",
        )
        onMain {
            Store.saveProfile(p)
            val wasActive = Store.activeId.value == p.id
            // The first provider, or one saved with "use now", becomes the active one.
            if (wasActive || o.optBoolean("activate") || Store.activeId.value == null) {
                if (!wasActive) Store.setActive(p.id)
                Repo.load(app, p)
            }
        }
        respond(s, 200, JSON, JSONObject().put("ok", true).put("id", p.id).toString())
    }

    private fun activate(s: Socket, body: String) {
        val p = Store.profiles.value.firstOrNull { it.id == JSONObject(body).optString("id") }
            ?: return respond(s, 404, JSON, error("That provider no longer exists."))
        onMain {
            Store.setActive(p.id)
            if (Repo.currentProfile != p || Repo.state.value is LoadState.Error) Repo.load(app, p)
        }
        respond(s, 200, JSON, """{"ok":true}""")
    }

    private fun delete(s: Socket, body: String) {
        val id = JSONObject(body).optString("id")
        if (Store.profiles.value.none { it.id == id }) return respond(s, 404, JSON, error("That provider no longer exists."))
        onMain {
            val wasActive = Store.activeId.value == id
            Store.deleteProfile(id)
            if (wasActive) Repo.clear()
        }
        respond(s, 200, JSON, """{"ok":true}""")
    }

    private fun error(message: String) = JSONObject().put("error", message).toString()

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Playlist"

    private fun groups(list: List<GroupInfo>, off: Set<String>): JSONArray {
        val a = JSONArray()
        list.forEach { g ->
            a.put(
                JSONObject()
                    .put("name", g.name)
                    .put("count", g.count ?: JSONObject.NULL)
                    .put("on", g.name !in off)
            )
        }
        return a
    }

    private fun channels(group: String): JSONArray {
        val a = JSONArray()
        val c = Repo.content.value ?: return a
        val names = Store.channelLayout(c.profile.id).names
        val hidden = c.hiddenChannels.filter { it.group == group }
        // Channels in the group come in the TV's order (the user's order when there is one).
        c.channelsByGroup[group].orEmpty().forEach { ch ->
            val o = channelJson(ch.key, ch.name, ch.guideName, ch.number, true)
            if (ch.alternates.isNotEmpty()) {
                o.put("versions", JSONArray(versionsOf(ch).map { it.second })).put("manual", ch.combinedManually)
            }
            a.put(o)
        }
        hidden.forEach { ch -> a.put(channelJson(ch.key, names[ch.key] ?: ch.name, ch.name, ch.number, false)) }
        return a
    }

    private fun channelJson(key: String, name: String, providerName: String, number: Int, on: Boolean) =
        JSONObject()
            .put("key", key)
            .put("name", name)
            .put("providerName", providerName)
            .put("number", number)
            .put("on", on)

    private fun settings(s: Socket, body: String) {
        val o = JSONObject(body)
        if (o.has("autoRefreshHours")) {
            val h = o.optInt("autoRefreshHours", -1)
            if (h !in listOf(0, 6, 12, 24)) return respond(s, 400, JSON, error("Choose off, 6, 12 or 24 hours."))
            onMain { Store.setAutoRefreshHours(h) }
        }
        respond(s, 200, JSON, """{"ok":true}""")
    }

    private fun apply(s: Socket, body: String) {
        val profile = Repo.content.value?.profile?.takeIf { it.id == Store.activeId.value }
            ?: return respond(s, 409, JSON, error("The TV is still loading."))
        val o = JSONObject(body)
        fun set(k: String): Set<String> {
            val a = o.optJSONArray(k) ?: return emptySet()
            return (0 until a.length()).map { a.optString(it) }.toHashSet()
        }
        val old = Store.contentFilter(profile.id)
        // A combined HD/FHD channel is listed once; hiding or showing it applies to all of its versions.
        val versions = Repo.content.value?.channels.orEmpty().associate { it.key to it.allKeys }
        fun expand(keys: Set<String>) = keys.flatMapTo(HashSet()) { versions[it] ?: listOf(it) }
        o.optJSONObject("layout")?.let { saveLayout(profile.id, it) }
        Store.saveContentFilter(
            profile.id,
            ContentFilter(
                disabledLive = set("live"),
                disabledMovies = set("movies"),
                disabledSeries = set("series"),
                hiddenChannels = old.hiddenChannels - expand(set("showChannels")) + expand(set("hideChannels")),
            ),
        )
        // Reloads from the cached provider data, except for sections that have just been switched on.
        onMain { Repo.load(app, profile) }
        respond(s, 200, JSON, """{"ok":true}""")
    }

    /** Merges the order and names sent by the page into the saved layout. */
    private fun saveLayout(profileId: String, l: JSONObject) {
        fun list(a: JSONArray?) = if (a == null) null else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
        val saved = Store.channelLayout(profileId)
        // Resetting order and names leaves the user's combinations alone.
        val old = if (l.optBoolean("reset")) ChannelLayout(merges = saved.merges, noAutoMerge = saved.noAutoMerge) else saved
        val order = LinkedHashMap(old.order)
        l.optJSONObject("order")?.let { m -> m.keys().forEach { g -> list(m.optJSONArray(g))?.let { order[g] = it } } }
        val names = LinkedHashMap(old.names)
        l.optJSONObject("names")?.let { m ->
            m.keys().forEach { k ->
                // A blank name goes back to the provider's.
                val n = m.optString(k).trim().take(MAX_NAME)
                if (n.isEmpty()) names.remove(k) else names[k] = n
            }
        }
        Store.saveChannelLayout(
            profileId,
            ChannelLayout(
                groups = list(l.optJSONArray("groups")) ?: old.groups,
                order = order,
                names = names,
                renumber = if (l.has("renumber")) l.optBoolean("renumber") else old.renumber,
                merges = old.merges,
                noAutoMerge = old.noAutoMerge,
            ),
        )
    }

    // ---------- Combining channels ----------

    /** Each version of a channel (itself first, then the ones combined into it) as key to name. */
    private fun versionsOf(ch: Channel): List<Pair<String, String>> {
        val first = if (ch.alternates.isNotEmpty() && !ch.combinedManually && ch.quality.isNotEmpty() &&
            !ch.guideName.endsWith(ch.quality, ignoreCase = true)
        ) "${ch.guideName} ${ch.quality}" else ch.guideName
        return listOf(ch.key to first) + ch.alternates.map { it.key to it.name }
    }

    /** Channels and versions whose name contains [q], to pick from when combining. */
    private fun search(q: String): JSONArray {
        val a = JSONArray()
        val c = Repo.content.value ?: return a
        val query = q.trim()
        if (query.length < 2) return a
        for (ch in c.channels) {
            for ((key, name) in versionsOf(ch)) {
                if (!name.contains(query, ignoreCase = true) && !(key == ch.key && ch.name.contains(query, ignoreCase = true))) continue
                val o = JSONObject()
                    .put("key", key)
                    .put("name", name)
                    .put("group", ch.group)
                    .put("number", ch.number)
                if (ch.alternates.isNotEmpty()) {
                    o.put("partOf", JSONObject().put("key", ch.key).put("name", ch.name).put("manual", ch.combinedManually))
                }
                a.put(o)
                if (a.length() >= MAX_RESULTS) return a
            }
        }
        return a
    }

    /** The user's own combinations, with the names of their channels. */
    private fun combinations(c: com.tvdeck.app.data.Content, l: ChannelLayout): JSONArray {
        val names = HashMap<String, Pair<String, String>>()
        for (ch in c.channels) versionsOf(ch).forEach { (k, n) -> names[k] = n to ch.group }
        val a = JSONArray()
        for (keys in l.merges) {
            val versions = JSONArray()
            keys.forEach { k ->
                val (n, g) = names[k] ?: ("Not available" to "")
                versions.put(JSONObject().put("key", k).put("name", n).put("group", g))
            }
            a.put(
                JSONObject()
                    .put("key", keys.first())
                    .put("active", c.channelByKey[keys.first()]?.combinedManually == true)
                    .put("versions", versions)
            )
        }
        return a
    }

    private fun combineChannels(s: Socket, body: String) {
        val c = Repo.content.value?.takeIf { it.profile.id == Store.activeId.value }
            ?: return respond(s, 409, JSON, error("The TV is still loading."))
        val a = JSONObject(body).optJSONArray("keys") ?: JSONArray()
        val keys = (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.distinct()
        if (keys.size < 2) return respond(s, 400, JSON, error("Pick at least two channels to combine."))
        if (keys.size > MAX_COMBINED) return respond(s, 400, JSON, error("Combine at most $MAX_COMBINED channels."))
        val known = c.channels.flatMapTo(HashSet()) { it.allKeys }
        if (keys.any { it !in known }) return respond(s, 400, JSON, error("One of those channels isn't available any more. Search again."))
        val old = Store.channelLayout(c.profile.id)
        val picked = keys.toSet()
        // A channel belongs to one combination: take the picked ones out of any earlier combination.
        val merges = old.merges.map { m -> m.filter { it !in picked } }.filter { it.size >= 2 } + listOf(keys)
        // Channels split apart earlier stay apart if this combination is split again later
        // (combined channels are kept out of automatic combining anyway).
        Store.saveChannelLayout(c.profile.id, old.copy(merges = merges))
        onMain { Repo.load(app, c.profile) }
        respond(s, 200, JSON, """{"ok":true}""")
    }

    private fun splitChannel(s: Socket, body: String) {
        val c = Repo.content.value?.takeIf { it.profile.id == Store.activeId.value }
            ?: return respond(s, 409, JSON, error("The TV is still loading."))
        val key = JSONObject(body).optString("key")
        val old = Store.channelLayout(c.profile.id)
        val manual = old.merges.indexOfFirst { key in it }
        val updated = if (manual >= 0) {
            old.copy(merges = old.merges.filterIndexed { i, _ -> i != manual })
        } else {
            // An automatic HD/FHD combination: keep its channels apart from now on.
            val ch = c.channels.firstOrNull { ch -> ch.alternates.isNotEmpty() && key in ch.allKeys }
                ?: return respond(s, 400, JSON, error("That channel isn't combined."))
            old.copy(noAutoMerge = old.noAutoMerge + ch.allKeys)
        }
        Store.saveChannelLayout(c.profile.id, updated)
        onMain { Repo.load(app, c.profile) }
        respond(s, 200, JSON, """{"ok":true}""")
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < MAX_LINE) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun respond(s: Socket, code: Int, type: String, text: String) =
        respond(s, code, type, text.toByteArray(Charsets.UTF_8))

    private fun respond(s: Socket, code: Int, type: String, bytes: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    companion object {
        private const val PORT = 8787
        private const val JSON = "application/json; charset=utf-8"
        private const val MAX_BODY = 4 * 1024 * 1024
        private const val MAX_LINE = 8 * 1024
        private const val MAX_FAILURES = 10
        private const val MAX_NAME = 80
        private const val MAX_RESULTS = 60
        private const val MAX_COMBINED = 8

        /** This device's address on the local network, or null when it isn't connected. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
