package com.tvdeck.app.data

import android.content.Context
import com.tvdeck.app.model.AccountInfo
import com.tvdeck.app.model.Episode
import com.tvdeck.app.model.HOUR
import com.tvdeck.app.model.Profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** Client for the Xtream Codes "player_api.php" API used by most IPTV providers. */
class XtreamApi(private val ctx: Context, private val profile: Profile) {
    val base: String = normalizeServer(profile.url)
    private val user = profile.username.trim()
    private val pass = profile.password.trim()
    private val liveExt = if (profile.outputFormat == "m3u8") "m3u8" else "ts"

    val xmltvUrl: String get() = "$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}"

    fun liveUrl(streamId: String) = "$base/live/$user/$pass/$streamId.$liveExt"
    fun movieUrl(streamId: String, ext: String) = "$base/movie/$user/$pass/$streamId.${ext.ifBlank { "mp4" }}"
    fun timeshiftTemplate(streamId: String) =
        "$base/timeshift/$user/$pass/{duration:60}/{Y}-{m}-{d}:{H}-{M}/$streamId.ts"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(action: String?, extra: String = "") = buildString {
        append(base).append("/player_api.php?username=").append(enc(user)).append("&password=").append(enc(pass))
        if (action != null) append("&action=").append(action)
        append(extra)
    }

    private suspend fun text(url: String, maxAge: Long, force: Boolean): String = withContext(Dispatchers.IO) {
        Http.cached(ctx, url, maxAge, force, profile.userAgent).readText()
    }

    class Login(val timeZone: String, val account: AccountInfo)

    /** Signs in and returns the server time zone (used for catch-up URLs) and the account details. */
    suspend fun login(force: Boolean): Login {
        val body = text(api(null), 6 * HOUR, force).trim()
        val o = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("The server didn't return a valid Xtream Codes response. Check the server URL.")
        }
        val ui = o.optJSONObject("user_info")
            ?: throw IOException("Unexpected server response. Check the server URL.")
        if (ui.str("auth") != "1") throw IOException("Login failed. Check your username and password.")
        val status = ui.str("status")
        if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
            throw IOException("Your account status is \"$status\".")
        }
        return Login(o.optJSONObject("server_info")?.str("timezone").orEmpty(), accountOf(ui))
    }

    private fun accountOf(ui: JSONObject): AccountInfo {
        // exp_date is Unix seconds as a string or number; null/0/empty means no expiry.
        val exp = ui.str("exp_date").toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 }
        return AccountInfo(
            status = ui.str("status").ifEmpty { "Active" },
            expiresAt = exp,
            maxConnections = ui.str("max_connections").toIntOrNull(),
            activeConnections = ui.str("active_cons").toIntOrNull(),
            trial = ui.str("is_trial") == "1",
            checkedAt = System.currentTimeMillis(),
        )
    }

    suspend fun categories(action: String, force: Boolean): Map<String, String> {
        val arr = parseArray(text(api(action), 12 * HOUR, force))
        val out = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out[o.str("category_id")] = o.str("category_name").ifEmpty { "Uncategorized" }
        }
        return out
    }

    suspend fun array(action: String, force: Boolean): JSONArray = parseArray(text(api(action), 12 * HOUR, force))

    /** Downloads (or reuses the cached copy of) a list response, to be read with [JsonStream]. */
    suspend fun file(action: String, force: Boolean): java.io.File = withContext(Dispatchers.IO) {
        Http.cached(ctx, api(action), 12 * HOUR, force, profile.userAgent)
    }

    suspend fun vodInfo(vodId: String): Map<String, String> {
        val o = JSONObject(text(api("get_vod_info", "&vod_id=${enc(vodId)}"), 24 * HOUR, false))
        val info = o.optJSONObject("info") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        fun put(k: String, v: String) { if (v.isNotBlank()) out[k] = v }
        put("plot", info.str("plot").ifEmpty { info.str("description") })
        put("genre", info.str("genre"))
        put("year", info.str("releasedate").ifEmpty { info.str("release_date") }.take(4))
        put("duration", info.str("duration"))
        put("cast", info.str("cast").ifEmpty { info.str("actors") })
        put("director", info.str("director"))
        put("cover", info.str("movie_image").takeIf { it.startsWith("http") }.orEmpty())
        return out
    }

    /** Returns the series plot and all its episodes. */
    suspend fun seriesInfo(seriesId: String): Pair<String, List<Episode>> {
        val o = JSONObject(text(api("get_series_info", "&series_id=${enc(seriesId)}"), 6 * HOUR, false))
        val plot = o.optJSONObject("info")?.str("plot").orEmpty()
        val out = ArrayList<Episode>()

        fun addEpisode(e: JSONObject, seasonHint: Int) {
            val id = e.str("id")
            if (id.isEmpty()) return
            val info = e.optJSONObject("info")
            val ext = e.str("container_extension").ifEmpty { "mp4" }
            out += Episode(
                key = "xe:$id",
                title = e.str("title").ifEmpty { "Episode ${e.str("episode_num")}" },
                season = e.str("season").toIntOrNull() ?: seasonHint,
                episode = e.str("episode_num").toIntOrNull() ?: 0,
                url = "$base/series/$user/$pass/$id.$ext",
                plot = info?.str("plot").orEmpty(),
                image = info?.str("movie_image")?.takeIf { it.startsWith("http") },
                duration = info?.str("duration").orEmpty(),
            )
        }

        when (val eps = o.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k ->
                val arr = eps.optJSONArray(k) ?: return@forEach
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, k.toIntOrNull() ?: 0) }
            }
            is JSONArray -> for (i in 0 until eps.length()) {
                when (val s = eps.opt(i)) {
                    is JSONArray -> for (j in 0 until s.length()) s.optJSONObject(j)?.let { addEpisode(it, i + 1) }
                    is JSONObject -> addEpisode(s, 0)
                }
            }
        }
        return plot to out
    }

    companion object {
        fun normalizeServer(raw: String): String {
            var s = raw.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.substringBefore("/player_api.php").substringBefore("/get.php").substringBefore("/xmltv.php")
                .substringBefore('?')
            return s.trimEnd('/')
        }

        fun parseArray(text: String): JSONArray {
            val t = text.trim()
            return when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> {
                    val o = JSONObject(t)
                    val a = JSONArray()
                    o.keys().forEach { k -> o.opt(k)?.let { a.put(it) } }
                    a
                }
                else -> JSONArray()
            }
        }
    }
}

/** Lenient string getter: Xtream servers mix numbers, strings and nulls freely. */
fun JSONObject.str(key: String): String {
    if (!has(key) || isNull(key)) return ""
    val v = optString(key, "")
    return if (v == "null") "" else v.trim()
}
