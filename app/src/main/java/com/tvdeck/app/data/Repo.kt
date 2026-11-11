package com.tvdeck.app.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.tvdeck.app.model.AccountInfo
import com.tvdeck.app.model.Catalog
import com.tvdeck.app.model.ChannelLayout
import com.tvdeck.app.model.Catchup
import com.tvdeck.app.model.Channel
import com.tvdeck.app.model.DAY
import com.tvdeck.app.model.Episode
import com.tvdeck.app.model.GroupInfo
import com.tvdeck.app.model.HOUR
import com.tvdeck.app.model.Profile
import com.tvdeck.app.model.SeriesItem
import com.tvdeck.app.model.SourceType
import com.tvdeck.app.model.VodItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.TimeZone

sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(val message: String) : LoadState
    data class Error(val message: String) : LoadState
    data object Ready : LoadState
}

/** fraction is null when how far along the guide is can't be known yet. */
data class EpgProgress(val fraction: Float?)

/** Everything loaded for the active profile. */
data class Content(
    val profile: Profile,
    val channels: List<Channel>,
    val liveGroups: List<String>,
    val movies: List<VodItem>,
    val movieGroups: List<String>,
    val series: List<SeriesItem>,
    val seriesGroups: List<String>,
    val epgUrl: String?,
    val catalog: Catalog = Catalog(),
    /** Channels switched off one by one inside an active group, kept so they can be switched back on. */
    val hiddenChannels: List<Channel> = emptyList(),
    /** The section's list couldn't be loaded, as opposed to the provider not having any. */
    val moviesFailed: Boolean = false,
    val seriesFailed: Boolean = false,
) {
    val channelByKey: Map<String, Channel> by lazy { channels.associateBy { it.key } }
    val channelsByGroup: Map<String, List<Channel>> by lazy { channels.groupBy { it.group } }
    val moviesByGroup: Map<String, List<VodItem>> by lazy { movies.groupBy { it.group } }
    val seriesByGroup: Map<String, List<SeriesItem>> by lazy { series.groupBy { it.group } }
}

object Repo {
    private const val TAG = "TVDeck"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = MutableStateFlow<LoadState>(LoadState.Idle)
    val content = MutableStateFlow<Content?>(null)
    val epg = MutableStateFlow<Epg?>(null)
    val epgStatus = MutableStateFlow("")
    /** Non-null while the guide is loading. */
    val epgProgress = MutableStateFlow<EpgProgress?>(null)
    /** Subscription details (Xtream Codes only); null for M3U or before signing in. */
    val account = MutableStateFlow<AccountInfo?>(null)
    /** Outcome of the last automatic refresh when it failed, shown in Settings. */
    val refreshNote = MutableStateFlow("")
    var currentProfile: Profile? = null
        private set
    private var job: Job? = null
    private var epgJob: Job? = null
    private var refreshJob: Job? = null
    private var accountJob: Job? = null
    /** Set during a background refresh so progress messages don't replace what's on screen. */
    @Volatile private var quiet = false

    fun load(ctx: Context, profile: Profile, force: Boolean = false) {
        val app = ctx.applicationContext
        job?.cancel()
        epgJob?.cancel()
        refreshJob?.cancel()
        accountJob?.cancel()
        quiet = false
        currentProfile = profile
        content.value = null
        epg.value = null
        account.value = null
        epgStatus.value = ""
        epgProgress.value = null
        state.value = LoadState.Loading("Connecting to ${profile.name}…")
        job = scope.launch {
            try {
                val stale = Http.staleFallbacks.get()
                val t0 = SystemClock.elapsedRealtime()
                val c = withContext(Dispatchers.IO) { fetch(app, profile, force) }
                Log.i(TAG, "Lists ready in ${SystemClock.elapsedRealtime() - t0} ms: ${c.channels.size} channels, ${c.movies.size} movies, ${c.series.size} series")
                content.value = c
                state.value = LoadState.Ready
                // Served from the saved copy because the provider couldn't be reached: not a fresh download.
                val downloaded = Http.staleFallbacks.get() == stale
                if (downloaded && (force || Store.lastRefresh(profile.id) == 0L)) Store.setLastRefresh(profile.id, System.currentTimeMillis())
                loadEpg(app, c, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable, not Exception: a huge provider list raises OutOfMemoryError, which would kill the app.
                state.value = LoadState.Error(describe(e))
            }
        }
    }

    private fun guideDownloadProgress(received: Long, total: Long) {
        if (total > 0) {
            publishEpgProgress("Downloading TV guide", received.toFloat() / total)
        } else {
            // Servers often don't send a size, so all we can show is the amount so far.
            epgStatus.value = "Downloading TV guide… ${received / (1024 * 1024)} MB"
        }
    }

    private suspend fun fetch(app: Context, profile: Profile, force: Boolean): Content {
        val loaded = if (profile.type == SourceType.M3U) loadM3u(app, profile, force) else loadXtream(app, profile, force)
        // Build the group lookups here so the first group change doesn't do it on the UI thread.
        loaded.channelsByGroup
        loaded.channelByKey
        loaded.moviesByGroup
        loaded.seriesByGroup
        return loaded
    }

    /**
     * Downloads fresh lists and guide for the loaded profile without clearing the screen: the new
     * content replaces the old only once it has loaded, and a failure keeps what is there.
     */
    fun refreshInBackground(ctx: Context) {
        val app = ctx.applicationContext
        val c = content.value ?: return
        if (state.value != LoadState.Ready || refreshJob?.isActive == true || epgProgress.value != null) return
        val profile = c.profile
        refreshJob = scope.launch {
            quiet = true
            try {
                val stale = Http.staleFallbacks.get()
                val fresh = withContext(Dispatchers.IO) { fetch(app, profile, force = true) }
                if (currentProfile?.id != profile.id) return@launch
                if (Http.staleFallbacks.get() != stale) {
                    // The provider couldn't be reached; keep what's on screen and try again at the next check.
                    refreshNote.value = "Last automatic refresh failed: the provider couldn't be reached. Showing the saved lists."
                    return@launch
                }
                content.value = fresh
                Store.setLastRefresh(profile.id, System.currentTimeMillis())
                refreshNote.value = ""
                loadEpg(app, fresh, force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Tried again at the next check rather than after a whole interval.
                refreshNote.value = "Last automatic refresh failed: ${describe(e)}"
            } finally {
                quiet = false
                if (state.value is LoadState.Loading && content.value != null) state.value = LoadState.Ready
            }
        }
    }

    /** Called regularly: refreshes the active profile when its data is older than the chosen interval. */
    fun autoRefreshTick(ctx: Context, now: Long = System.currentTimeMillis()) {
        val hours = Store.autoRefreshHours.value
        if (hours <= 0) return
        val c = content.value ?: return
        val last = Store.lastRefresh(c.profile.id)
        if (last > 0 && now - last < hours * HOUR) return
        refreshInBackground(ctx)
    }

    /** Re-reads the account details (expiry, connections) if they're older than [maxAgeMs]. */
    fun refreshAccount(ctx: Context, maxAgeMs: Long = 60_000) {
        val app = ctx.applicationContext
        val c = content.value ?: return
        if (c.profile.type != SourceType.XTREAM || accountJob?.isActive == true) return
        val a = account.value
        if (a != null && System.currentTimeMillis() - a.checkedAt < maxAgeMs) return
        accountJob = scope.launch {
            try {
                val login = withContext(Dispatchers.IO) { XtreamApi(app, c.profile).login(force = true) }
                if (currentProfile?.id == c.profile.id) account.value = login.account
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Keep the details from sign-in.
            }
        }
    }

    /** Drops everything loaded, e.g. after the active profile was deleted. */
    fun clear() {
        job?.cancel()
        refreshJob?.cancel()
        accountJob?.cancel()
        account.value = null
        epgJob?.cancel()
        currentProfile = null
        content.value = null
        epg.value = null
        epgStatus.value = ""
        epgProgress.value = null
        state.value = LoadState.Idle
    }

    fun reloadEpg(ctx: Context) {
        if (epgProgress.value != null) return
        content.value?.let { loadEpg(ctx.applicationContext, it, force = true) }
    }

    private fun loadEpg(app: Context, c: Content, force: Boolean) {
        epgJob?.cancel()
        val url = c.epgUrl
        if (url.isNullOrBlank()) {
            epgStatus.value = "No TV guide (EPG) URL for this profile. Add one in the profile settings."
            return
        }
        if (c.channels.isEmpty()) {
            epgStatus.value = "TV guide not loaded: no channels are active."
            return
        }
        epgStatus.value = "Loading TV guide…"
        epgProgress.value = EpgProgress(null)
        val t0 = SystemClock.elapsedRealtime()
        epgJob = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val f = Http.cached(app, url, 12 * HOUR, force, c.profile.userAgent) { received, total ->
                        ensureActive()
                        guideDownloadProgress(received, total)
                    }
                    val now = System.currentTimeMillis()
                    val onRead = { fraction: Float ->
                        ensureActive()
                        publishEpgProgress("Reading TV guide", fraction)
                    }
                    Http.openStream(f, onRead).use { XmlTvParser.parse(it, now - 7 * DAY, now + 4 * DAY, c.channels) }
                }
                epg.value = result
                epgStatus.value = "TV guide: ${result.channelCount} channels, ${result.programmeCount} programmes"
                Log.i(TAG, "Guide ready in ${SystemClock.elapsedRealtime() - t0} ms: ${result.channelCount} channels, ${result.programmeCount} programmes")
                epgProgress.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                epgStatus.value = "TV guide couldn't be loaded: ${describe(e)}"
                epgProgress.value = null
            }
        }
    }

    private fun describe(e: Throwable): String =
        if (e is OutOfMemoryError) "Your provider's list is too large for this device's memory."
        else e.message ?: e.javaClass.simpleName

    private fun publishEpgProgress(label: String, fraction: Float) {
        val percent = (fraction * 100).toInt().coerceIn(0, 100)
        epgStatus.value = "$label… $percent%"
        epgProgress.value = EpgProgress(percent / 100f)
    }

    private fun status(msg: String) {
        if (!quiet) state.value = LoadState.Loading(msg)
    }

    /** Puts [items] whose key is listed in [wanted] first, in that order; the rest follow unchanged. */
    private fun <T> orderBy(items: List<T>, wanted: List<String>, key: (T) -> String): List<T> {
        if (wanted.isEmpty()) return items
        val idx = HashMap<String, Int>()
        wanted.forEachIndexed { i, k -> idx.putIfAbsent(k, i) }
        val (known, rest) = items.partition { key(it) in idx }
        return known.sortedBy { idx[key(it)] } + rest
    }

    /** Applies the user's group order, channel order, names and numbering. */
    private fun arrange(channels: List<Channel>, groups: List<String>, l: ChannelLayout): Pair<List<Channel>, List<String>> {
        if (l.isEmpty) return channels to groups
        val orderedGroups = orderBy(groups, l.groups) { it }
        val byGroup = channels.groupBy { it.group }
        val out = ArrayList<Channel>(channels.size)
        for (g in orderedGroups + (byGroup.keys - orderedGroups.toSet())) {
            val list = byGroup[g] ?: continue
            for (ch in orderBy(list, l.order[g].orEmpty()) { it.key }) {
                val n = l.names[ch.key]?.trim()?.takeIf { it.isNotEmpty() && it != ch.name }
                out += if (n != null) ch.copy(name = n, providerName = ch.name) else ch
            }
        }
        val numbered = if (l.renumber) out.mapIndexed { i, ch -> ch.copy(number = i + 1) } else out
        return numbered to orderedGroups
    }

    private fun arrangeCatalog(live: List<GroupInfo>, l: ChannelLayout) = orderBy(live, l.groups) { it.name }

    private fun unique(keys: HashSet<String>, k: String): String {
        var x = k
        var i = 2
        while (!keys.add(x)) x = "$k#${i++}"
        return x
    }

    private fun orderedGroups(categoryOrder: Collection<String>, used: List<String>): List<String> {
        val usedSet = used.toHashSet()
        val out = LinkedHashSet<String>()
        categoryOrder.filterTo(out) { it in usedSet }
        out.addAll(used)
        return out.toList()
    }

    /** Combines HD/FHD versions (when enabled) and moves favorites saved on an absorbed version. */
    private fun combine(channels: List<Channel>, layout: ChannelLayout): List<Channel> {
        val (manual, manualKeys) = combineManually(channels, layout.merges)
        val merged = if (!Store.combineVersions.value) manual
        else ChannelMerger.merge(manual, Store.preferHighest.value, exclude = manualKeys + layout.noAutoMerge)
        val aliases = HashMap<String, String>()
        merged.forEach { ch -> ch.alternates.forEach { if (it.key != ch.key) aliases[it.key] = ch.key } }
        Store.remapChannelKeys(aliases)
        return merged
    }

    /**
     * Applies the user's own combinations, which may join channels from different groups. The entry
     * takes the first channel's place, number, group and key (so favorites stay); the others become
     * versions to fall back to, labelled with their own names. Returns the channels and every key used.
     */
    private fun combineManually(channels: List<Channel>, merges: List<List<String>>): Pair<List<Channel>, Set<String>> {
        if (merges.isEmpty()) return channels to emptySet()
        val byKey = channels.associateBy { it.key }
        val used = HashSet<String>()
        val combined = HashMap<String, Channel>()
        val absorbed = HashSet<String>()
        for (keys in merges) {
            // Channels switched off or gone from the provider are left out; a channel joins one combination at most.
            val found = keys.distinct().mapNotNull { byKey[it] }.filter { it.key !in used }
            if (found.size < 2) continue
            val first = found.first()
            combined[first.key] = first.copy(
                quality = first.name,
                epgId = first.epgId ?: found.firstNotNullOfOrNull { it.epgId },
                logo = first.logo ?: found.firstNotNullOfOrNull { it.logo },
                alternates = found.drop(1).map { it.copy(quality = it.name) },
                combinedManually = true,
            )
            found.forEach { used += it.key }
            found.drop(1).forEach { absorbed += it.key }
        }
        if (combined.isEmpty()) return channels to emptySet()
        return channels.mapNotNull { ch -> if (ch.key in absorbed) null else combined[ch.key] ?: ch } to used
    }

    private fun groupInfos(order: List<String>, counts: Map<String, Int>) = order.map { GroupInfo(it, counts[it] ?: 0) }

    private class SeriesBuilder(val key: String, val name: String, val cover: String?, val group: String) {
        val episodes = ArrayList<Episode>()
    }

    private suspend fun loadM3u(app: Context, p: Profile, force: Boolean): Content {
        status("Downloading playlist…")
        val file = Http.cached(app, p.url.trim(), 12 * HOUR, force, p.userAgent)
        status("Reading playlist…")
        val pl = Http.openStream(file).bufferedReader().useLines { M3uParser.parse(it) }
        if (pl.entries.isEmpty()) throw java.io.IOException("The playlist is empty or isn't an M3U file.")

        val keys = HashSet<String>()
        val channels = ArrayList<Channel>()
        val movies = ArrayList<VodItem>()
        val seriesMap = LinkedHashMap<String, SeriesBuilder>()
        var number = 0
        for (e in pl.entries) {
            val logo = e.attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
            when (M3uParser.classify(e)) {
                EntryKind.LIVE -> {
                    number++
                    channels += Channel(
                        key = unique(keys, "m:${e.group}|${e.name}"),
                        name = e.name,
                        logo = logo,
                        group = e.group,
                        url = e.url,
                        epgId = e.attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        number = e.attrs["tvg-chno"]?.trim()?.toIntOrNull() ?: number,
                        catchup = M3uParser.catchupFor(e, pl.header),
                    )
                }
                EntryKind.MOVIE -> movies += VodItem(unique(keys, "v:${e.group}|${e.name}"), e.name, logo, e.group, e.url)
                EntryKind.SERIES -> {
                    val en = M3uParser.episodeName(e.name)
                    val seriesName = en?.series ?: e.name
                    val sk = "s:${e.group}|$seriesName"
                    val b = seriesMap.getOrPut(sk) { SeriesBuilder(sk, seriesName, logo, e.group) }
                    b.episodes += Episode(
                        key = unique(keys, "e:$sk|${e.name}"),
                        title = e.name,
                        season = en?.season ?: 1,
                        episode = en?.episode ?: (b.episodes.size + 1),
                        url = e.url,
                        image = logo,
                    )
                }
            }
        }
        val series = seriesMap.values.map { SeriesItem(it.key, it.name, it.cover, it.group, episodes = it.episodes) }

        // Filter after the loop so channel numbers and keys stay the same whatever is switched off.
        val filter = Store.contentFilter(p.id)
        val catalog = Catalog(
            live = groupInfos(channels.map { it.group }.distinct(), channels.groupingBy { it.group }.eachCount()),
            movies = groupInfos(movies.map { it.group }.distinct(), movies.groupingBy { it.group }.eachCount()),
            series = groupInfos(series.map { it.group }.distinct(), series.groupingBy { it.group }.eachCount()),
        )
        val (hidden, visibleChannels) = channels.filter { it.group !in filter.disabledLive }
            .partition { it.key in filter.hiddenChannels }
        val layout = Store.channelLayout(p.id)
        val combined = combine(visibleChannels, layout)
        val (activeChannels, liveGroups) = arrange(combined, combined.map { it.group }.distinct(), layout)
        val activeMovies = movies.filter { it.group !in filter.disabledMovies }
        val activeSeries = series.filter { it.group !in filter.disabledSeries }
        return Content(
            profile = p,
            channels = activeChannels,
            liveGroups = liveGroups,
            movies = activeMovies,
            movieGroups = activeMovies.map { it.group }.distinct(),
            series = activeSeries,
            seriesGroups = activeSeries.map { it.group }.distinct(),
            epgUrl = p.epgUrl.trim().ifEmpty { pl.epgUrl ?: "" }.ifEmpty { null },
            catalog = catalog.copy(live = arrangeCatalog(catalog.live, layout)),
            hiddenChannels = hidden,
        )
    }

    private suspend fun loadXtream(app: Context, p: Profile, force: Boolean): Content = coroutineScope {
        val api = XtreamApi(app, p)
        status("Signing in…")
        val filter = Store.contentFilter(p.id)

        suspend fun cats(action: String) =
            try { api.categories(action, force) } catch (e: CancellationException) { throw e } catch (e: Throwable) { emptyMap() }

        // Sign-in and the three category lists go out together rather than one after the other.
        val loginD = async(Dispatchers.IO) { api.login(force) }
        val liveCatsD = async(Dispatchers.IO) { cats("get_live_categories") }
        val vodCatsD = async(Dispatchers.IO) { cats("get_vod_categories") }
        val serCatsD = async(Dispatchers.IO) { cats("get_series_categories") }

        // A section with every category off is never downloaded: this is the biggest saving, as movie
        // and series lists are usually the largest responses. With nothing switched off in a section
        // its list can start downloading straight away, without waiting for the categories.
        fun skip(cats: Map<String, String>, off: Set<String>) = cats.isNotEmpty() && cats.values.all { it in off }
        fun listFileD(action: String, off: Set<String>, catsD: Deferred<Map<String, String>>) = async(Dispatchers.IO) {
            if (off.isNotEmpty() && skip(catsD.await(), off)) return@async null
            try {
                api.file(action, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A failed sign-in explains the problem better than a failed download.
                loginD.await()
                throw e
            }
        }
        val liveFileD = listFileD("get_live_streams", filter.disabledLive, liveCatsD)
        val vodFileD = listFileD("get_vod_streams", filter.disabledMovies, vodCatsD)
        val serFileD = listFileD("get_series", filter.disabledSeries, serCatsD)

        val login = loginD.await()
        account.value = login.account
        val tz = login.timeZone.ifBlank { TimeZone.getDefault().id }
        status("Loading channels, movies and series…")
        val liveCats = liveCatsD.await()
        val vodCats = vodCatsD.await()
        val serCats = serCatsD.await()

        // The three lists are read in parallel, each streamed from its file.
        class Live(val loaded: List<Channel>, val hidden: List<Channel>, val counts: Map<String, Int>, val skipped: Boolean)
        val liveD = async(Dispatchers.Default) {
            val file = liveFileD.await() ?: return@async Live(emptyList(), emptyList(), emptyMap(), true)
            val keys = HashSet<String>()
            val loaded = ArrayList<Channel>()
            val hidden = ArrayList<Channel>()
            val counts = LinkedHashMap<String, Int>()
            var i = 0
            JsonStream.forEachObject(file, LIVE_FIELDS) { o ->
                i++
                val id = o["stream_id"].orEmpty()
                if (id.isEmpty()) return@forEachObject
                val group = liveCats[o["category_id"].orEmpty()] ?: "Uncategorized"
                counts[group] = (counts[group] ?: 0) + 1
                if (group in filter.disabledLive) return@forEachObject
                val days = o["tv_archive_duration"]?.toIntOrNull() ?: 0
                val hasArchive = o["tv_archive"] == "1" && days > 0
                val ch = Channel(
                    key = unique(keys, "x:$id"),
                    name = o["name"].orEmpty().ifEmpty { "Channel $id" },
                    logo = o["stream_icon"]?.takeIf { it.startsWith("http") },
                    group = group,
                    url = api.liveUrl(id),
                    epgId = o["epg_channel_id"]?.ifEmpty { null },
                    number = o["num"]?.toIntOrNull() ?: i,
                    catchup = if (hasArchive) Catchup("default", api.timeshiftTemplate(id), days, tz) else null,
                )
                if (ch.key in filter.hiddenChannels) hidden += ch else loaded += ch
            }
            Live(loaded, hidden, counts, false)
        }

        // Null when the list failed. Movies or series failing, even by running out of memory, shouldn't cost the user live TV.
        suspend fun <T> section(fileD: Deferred<java.io.File?>, read: (java.io.File) -> T): Pair<T?, Boolean> =
            try {
                val f = fileD.await()
                if (f == null) null to true else read(f) to false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null to false
            }

        class Vod(val items: List<VodItem>, val counts: Map<String, Int>)
        val vodD = async(Dispatchers.Default) {
            section(vodFileD) { file ->
                val keys = HashSet<String>()
                val items = ArrayList<VodItem>()
                val counts = LinkedHashMap<String, Int>()
                JsonStream.forEachObject(file, VOD_FIELDS) { o ->
                    val id = o["stream_id"].orEmpty()
                    if (id.isEmpty()) return@forEachObject
                    val group = vodCats[o["category_id"].orEmpty()] ?: "Uncategorized"
                    counts[group] = (counts[group] ?: 0) + 1
                    if (group in filter.disabledMovies) return@forEachObject
                    items += VodItem(
                        key = unique(keys, "xv:$id"),
                        name = o["name"].orEmpty().ifEmpty { "Movie $id" },
                        poster = o["stream_icon"]?.takeIf { it.startsWith("http") },
                        group = group,
                        url = api.movieUrl(id, o["container_extension"].orEmpty()),
                        rating = o["rating"]?.takeIf { it != "0" && it != "0.0" }.orEmpty(),
                        xtreamId = id,
                    )
                }
                Vod(items, counts)
            }
        }

        class Ser(val items: List<SeriesItem>, val counts: Map<String, Int>)
        val serD = async(Dispatchers.Default) {
            section(serFileD) { file ->
                val keys = HashSet<String>()
                val items = ArrayList<SeriesItem>()
                val counts = LinkedHashMap<String, Int>()
                JsonStream.forEachObject(file, SERIES_FIELDS) { o ->
                    val id = o["series_id"].orEmpty()
                    if (id.isEmpty()) return@forEachObject
                    val group = serCats[o["category_id"].orEmpty()] ?: "Uncategorized"
                    counts[group] = (counts[group] ?: 0) + 1
                    if (group in filter.disabledSeries) return@forEachObject
                    items += SeriesItem(
                        key = unique(keys, "xs:$id"),
                        name = o["name"].orEmpty().ifEmpty { "Series $id" },
                        cover = o["cover"]?.takeIf { it.startsWith("http") },
                        group = group,
                        plot = o["plot"].orEmpty(),
                        rating = o["rating"]?.takeIf { it != "0" && it != "0.0" }.orEmpty(),
                        xtreamId = id,
                    )
                }
                Ser(items, counts)
            }
        }

        fun catalogOf(cats: Map<String, String>, skipped: Boolean, counts: Map<String, Int>) =
            if (skipped) cats.values.distinct().map { GroupInfo(it, null) }
            else groupInfos(orderedGroups(cats.values, counts.keys.toList()), counts)

        val live = liveD.await()
        val layout = Store.channelLayout(p.id)
        val combined = combine(live.loaded, layout)
        val (channels, liveGroupOrder) = arrange(combined, orderedGroups(liveCats.values, combined.map { it.group }), layout)
        val (vod, vodSkipped) = vodD.await()
        val (ser, serSkipped) = serD.await()
        val movies = vod?.items.orEmpty()
        val series = ser?.items.orEmpty()

        Content(
            profile = p,
            channels = channels,
            liveGroups = liveGroupOrder,
            movies = movies,
            movieGroups = orderedGroups(vodCats.values, movies.map { it.group }),
            series = series,
            seriesGroups = orderedGroups(serCats.values, series.map { it.group }),
            epgUrl = p.epgUrl.trim().ifEmpty { api.xmltvUrl },
            catalog = Catalog(
                live = arrangeCatalog(catalogOf(liveCats, live.skipped, live.counts), layout),
                movies = catalogOf(vodCats, vodSkipped, vod?.counts.orEmpty()),
                series = catalogOf(serCats, serSkipped, ser?.counts.orEmpty()),
            ),
            hiddenChannels = live.hidden,
            moviesFailed = vod == null && !vodSkipped,
            seriesFailed = ser == null && !serSkipped,
        )
    }

    private val LIVE_FIELDS = setOf("stream_id", "category_id", "tv_archive", "tv_archive_duration", "name", "stream_icon", "epg_channel_id", "num")
    private val VOD_FIELDS = setOf("stream_id", "category_id", "name", "stream_icon", "container_extension", "rating")
    private val SERIES_FIELDS = setOf("series_id", "category_id", "name", "cover", "plot", "rating")
}
