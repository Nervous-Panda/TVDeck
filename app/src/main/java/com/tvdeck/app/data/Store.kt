package com.tvdeck.app.data

import android.content.Context
import android.content.SharedPreferences
import com.tvdeck.app.model.ChannelLayout
import com.tvdeck.app.model.ContentFilter
import com.tvdeck.app.model.Reminder
import com.tvdeck.app.model.Profile
import com.tvdeck.app.model.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Small on-device store: profiles, favorites, recently watched channels and resume positions. */
object Store {
    private lateinit var sp: SharedPreferences

    val profiles = MutableStateFlow<List<Profile>>(emptyList())
    val activeId = MutableStateFlow<String?>(null)
    val favorites = MutableStateFlow<Set<String>>(emptySet())
    val recents = MutableStateFlow<List<String>>(emptyList())
    val resumeVersion = MutableStateFlow(0)

    /** Troubleshooting mode: the player shows the technical reason a stream fails. Off by default. */
    val troubleshooting = MutableStateFlow(false)

    fun setTroubleshooting(on: Boolean) {
        sp.edit().putBoolean("troubleshooting", on).apply()
        troubleshooting.value = on
    }

    /** Combine HD/FHD/… versions of a channel into one entry with automatic fallback. On by default. */
    val combineVersions = MutableStateFlow(true)
    /** Which version plays first in a combined channel: true = best picture (FHD), false = HD first. */
    val preferHighest = MutableStateFlow(true)

    fun setCombineVersions(on: Boolean) {
        sp.edit().putBoolean("combine_versions", on).apply()
        combineVersions.value = on
    }

    fun setPreferHighest(on: Boolean) {
        sp.edit().putBoolean("prefer_highest", on).apply()
        preferHighest.value = on
    }

    /** Points favorites/recents saved on a version that is now combined at the combined channel's key. */
    fun remapChannelKeys(aliases: Map<String, String>) {
        val id = activeId.value ?: return
        if (aliases.isEmpty()) return
        val favs = favorites.value
        if (favs.any { it in aliases }) {
            val s = favs.mapTo(HashSet()) { aliases[it] ?: it }
            favorites.value = s
            sp.edit().putStringSet("fav_$id", s).apply()
        }
        val rec = recents.value
        if (rec.any { it in aliases }) {
            val l = rec.map { aliases[it] ?: it }.distinct()
            recents.value = l
            sp.edit().putString("rec_$id", l.joinToString("\n")).apply()
        }
    }

    /** Hours between automatic refreshes of channels and guide; 0 = off. Daily by default. */
    val autoRefreshHours = MutableStateFlow(24)

    fun setAutoRefreshHours(h: Int) {
        sp.edit().putInt("auto_refresh_hours", h).apply()
        autoRefreshHours.value = h
    }

    /** When the profile's lists were last downloaded fresh (not from cache), epoch ms; 0 = unknown. */
    fun lastRefresh(profileId: String): Long = sp.getLong("refreshed_$profileId", 0L)

    fun setLastRefresh(profileId: String, at: Long) {
        sp.edit().putLong("refreshed_$profileId", at).apply()
    }

    // ---------- Channel order & names ----------

    fun channelLayout(profileId: String): ChannelLayout {
        val o = try { JSONObject(sp.getString("layout_$profileId", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        fun list(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        val order = LinkedHashMap<String, List<String>>()
        o.optJSONObject("order")?.let { m -> m.keys().forEach { k -> order[k] = list(m.optJSONArray(k)) } }
        val names = LinkedHashMap<String, String>()
        o.optJSONObject("names")?.let { m -> m.keys().forEach { k -> names[k] = m.optString(k) } }
        val merges = o.optJSONArray("merges")?.let { a -> (0 until a.length()).map { list(a.optJSONArray(it)) }.filter { it.size >= 2 } }.orEmpty()
        return ChannelLayout(
            list(o.optJSONArray("groups")), order, names, o.optBoolean("renumber"),
            merges, list(o.optJSONArray("noAutoMerge")).toSet(),
        )
    }

    fun saveChannelLayout(profileId: String, l: ChannelLayout) {
        val order = JSONObject()
        l.order.forEach { (g, keys) -> order.put(g, JSONArray(keys)) }
        val names = JSONObject()
        l.names.forEach { (k, v) -> names.put(k, v) }
        val o = JSONObject()
            .put("groups", JSONArray(l.groups))
            .put("order", order)
            .put("names", names)
            .put("renumber", l.renumber)
            .put("merges", JSONArray(l.merges.map { JSONArray(it) }))
            .put("noAutoMerge", JSONArray(l.noAutoMerge))
        sp.edit().putString("layout_$profileId", o.toString()).apply()
    }

    // ---------- Reminders ----------

    val reminders = MutableStateFlow<List<Reminder>>(emptyList())

    @Synchronized
    fun addReminder(r: Reminder) {
        writeReminders((reminders.value.filter { it.id != r.id } + r).sortedBy { it.start })
    }

    @Synchronized
    fun removeReminders(ids: Set<String>) {
        if (reminders.value.none { it.id in ids }) return
        writeReminders(reminders.value.filter { it.id !in ids })
    }

    private fun writeReminders(list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { r ->
            a.put(
                JSONObject().put("profile", r.profileId).put("channel", r.channelKey).put("channelName", r.channelName)
                    .put("title", r.title).put("start", r.start).put("stop", r.stop)
            )
        }
        sp.edit().putString("reminders", a.toString()).apply()
        reminders.value = list
    }

    private fun readReminders(): List<Reminder> {
        val a = try { JSONArray(sp.getString("reminders", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Reminder(o.optString("profile"), o.optString("channel"), o.optString("channelName"), o.optString("title"), o.optLong("start"), o.optLong("stop"))
        }
    }

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("tvdeck", Context.MODE_PRIVATE)
        autoRefreshHours.value = sp.getInt("auto_refresh_hours", 24)
        reminders.value = readReminders()
        troubleshooting.value = sp.getBoolean("troubleshooting", false)
        combineVersions.value = sp.getBoolean("combine_versions", true)
        preferHighest.value = sp.getBoolean("prefer_highest", true)
        profiles.value = readProfiles()
        val active = sp.getString("active", null)?.takeIf { id -> profiles.value.any { it.id == id } }
        activeId.value = active
        if (active != null) loadProfileState(active)
    }

    fun activeProfile(): Profile? = profiles.value.firstOrNull { it.id == activeId.value }

    fun setActive(id: String) {
        sp.edit().putString("active", id).apply()
        loadProfileState(id)
        activeId.value = id
    }

    private fun loadProfileState(id: String) {
        favorites.value = sp.getStringSet("fav_$id", emptySet())?.toSet() ?: emptySet()
        recents.value = sp.getString("rec_$id", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    }

    fun saveProfile(p: Profile) {
        val list = profiles.value.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        profiles.value = list
        writeProfiles(list)
    }

    fun deleteProfile(id: String) {
        val list = profiles.value.filter { it.id != id }
        profiles.value = list
        writeProfiles(list)
        sp.edit().remove("fav_$id").remove("rec_$id").remove("filter_$id").remove("layout_$id").remove("refreshed_$id").apply()
        removeReminders(reminders.value.filter { it.profileId == id }.mapTo(HashSet()) { it.id })
        if (activeId.value == id) {
            sp.edit().remove("active").apply()
            activeId.value = null
        }
    }

    fun contentFilter(profileId: String): ContentFilter {
        val o = try { JSONObject(sp.getString("filter_$profileId", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
        fun set(k: String): Set<String> {
            val a = o.optJSONArray(k) ?: return emptySet()
            return (0 until a.length()).map { a.optString(it) }.toHashSet()
        }
        return ContentFilter(set("live"), set("movies"), set("series"), set("channels"))
    }

    fun saveContentFilter(profileId: String, f: ContentFilter) {
        val o = JSONObject()
            .put("live", JSONArray(f.disabledLive))
            .put("movies", JSONArray(f.disabledMovies))
            .put("series", JSONArray(f.disabledSeries))
            .put("channels", JSONArray(f.hiddenChannels))
        sp.edit().putString("filter_$profileId", o.toString()).apply()
    }

    fun toggleFavorite(key: String) {
        val id = activeId.value ?: return
        val s = favorites.value.toMutableSet()
        if (!s.add(key)) s.remove(key)
        favorites.value = s
        sp.edit().putStringSet("fav_$id", HashSet(s)).apply()
    }

    fun addRecent(key: String) {
        val id = activeId.value ?: return
        val l = recents.value.toMutableList()
        l.remove(key)
        l.add(0, key)
        val trimmed = l.take(40)
        recents.value = trimmed
        sp.edit().putString("rec_$id", trimmed.joinToString("\n")).apply()
    }

    fun resume(key: String): Long = sp.getLong("pos_$key", 0L)

    fun saveResume(key: String, positionMs: Long, durationMs: Long) {
        val e = sp.edit()
        if (positionMs < 15_000 || positionMs > durationMs * 0.95) e.remove("pos_$key") else e.putLong("pos_$key", positionMs)
        e.apply()
        resumeVersion.value = resumeVersion.value + 1
    }

    fun clearResume(key: String) {
        sp.edit().remove("pos_$key").apply()
        resumeVersion.value = resumeVersion.value + 1
    }

    fun resumeKeys(): Set<String> =
        sp.all.keys.filter { it.startsWith("pos_") }.map { it.removePrefix("pos_") }.toSet()

    private fun readProfiles(): List<Profile> {
        val arr = try { JSONArray(sp.getString("profiles", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Profile(
                id = o.optString("id"),
                name = o.optString("name"),
                type = if (o.optString("type") == "M3U") SourceType.M3U else SourceType.XTREAM,
                url = o.optString("url"),
                username = o.optString("username"),
                password = o.optString("password"),
                epgUrl = o.optString("epgUrl"),
                userAgent = o.optString("userAgent"),
                outputFormat = o.optString("outputFormat", "ts"),
            )
        }
    }

    private fun writeProfiles(list: List<Profile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("type", p.type.name)
                    .put("url", p.url)
                    .put("username", p.username)
                    .put("password", p.password)
                    .put("epgUrl", p.epgUrl)
                    .put("userAgent", p.userAgent)
                    .put("outputFormat", p.outputFormat)
            )
        }
        sp.edit().putString("profiles", arr.toString()).apply()
    }
}
