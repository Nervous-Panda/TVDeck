package com.tvdeck.app.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvdeck.app.data.CatchupUrl
import com.tvdeck.app.data.Content
import com.tvdeck.app.data.Epg
import com.tvdeck.app.data.LoadState
import com.tvdeck.app.data.Repo
import com.tvdeck.app.data.Store
import com.tvdeck.app.model.Channel
import com.tvdeck.app.model.Profile
import com.tvdeck.app.model.Programme
import com.tvdeck.app.model.SeriesItem
import com.tvdeck.app.model.VodItem
import com.tvdeck.app.player.PlayItem
import com.tvdeck.app.player.PlaybackSession
import com.tvdeck.app.player.PlayerActivity
import com.tvdeck.app.player.Variant

// ---------- Navigation ----------

sealed interface Route {
    data object Profiles : Route
    data class EditProfile(val profile: Profile?) : Route
    data object Home : Route
    data object Manager : Route
    data object Reminders : Route
    data class MovieDetail(val item: VodItem) : Route
    data class SeriesDetail(val item: SeriesItem) : Route
}

class Nav(initial: Route) {
    val stack = mutableStateListOf(initial)
    val top: Route get() = stack.last()
    fun push(r: Route) {
        stack.add(r)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun reset(r: Route) {
        stack.clear()
        stack.add(r)
    }
}

/** Browsing state kept while moving between tabs and detail screens. */
class UiState {
    var tab by mutableIntStateOf(0)
    var liveGroup by mutableStateOf<String?>(null)
    var guideGroup by mutableStateOf<String?>(null)
    var movieGroup by mutableStateOf<String?>(null)
    var seriesGroup by mutableStateOf<String?>(null)
    var search by mutableStateOf("")
    val movieGrid = LazyGridState()
    val seriesGrid = LazyGridState()
}

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Live TV", Icons.Default.PlayArrow),
    Tab("Guide", Icons.Default.DateRange),
    Tab("Movies", Icons.Default.Star),
    Tab("Series", Icons.AutoMirrored.Filled.List),
    Tab("Search", Icons.Default.Search),
    Tab("Settings", Icons.Default.Settings),
)

const val TAB_SETTINGS = 5

// ---------- Root ----------

@Composable
fun TVDeckApp() {
    val activeId by Store.activeId.collectAsState()
    val nav = remember {
        Nav(
            when {
                Store.profiles.value.isEmpty() -> Route.EditProfile(null)
                Store.activeProfile() != null -> Route.Home
                else -> Route.Profiles
            }
        )
    }
    val ui = remember(activeId) { UiState() }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    Surface(color = Palette.Bg, contentColor = Palette.Text, modifier = Modifier.fillMaxSize()) {
        when (val r = nav.top) {
            Route.Profiles -> ProfilesScreen(nav)
            is Route.EditProfile -> EditProfileScreen(nav, r.profile)
            Route.Home -> HomeScreen(nav, ui)
            Route.Manager -> ManagerScreen(nav)
            Route.Reminders -> RemindersScreen(nav)
            is Route.MovieDetail -> MovieDetailScreen(nav, r.item)
            is Route.SeriesDetail -> SeriesDetailScreen(nav, r.item)
        }
        ReminderWatcher(nav)
        ReminderPopup(onWatch = { com.tvdeck.app.data.Reminders.startWatching(it) })
    }
}

@Composable
private fun HomeScreen(nav: Nav, ui: UiState) {
    val ctx = LocalContext.current
    val activeId by Store.activeId.collectAsState()
    val profiles by Store.profiles.collectAsState()
    val profile = profiles.firstOrNull { it.id == activeId }
    if (profile == null) {
        LaunchedEffect(Unit) { nav.reset(if (profiles.isEmpty()) Route.EditProfile(null) else Route.Profiles) }
        return
    }
    LaunchedEffect(profile) {
        if (Repo.currentProfile != profile || Repo.state.value is LoadState.Error) Repo.load(ctx, profile)
    }
    BackHandler(enabled = ui.tab != 0) { ui.tab = 0 }

    val state by Repo.state.collectAsState()
    val content by Repo.content.collectAsState()
    val epg by Repo.epg.collectAsState()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                SideRail(ui)
                Box(Modifier.weight(1f).fillMaxHeight()) { HomeBody(nav, ui, profile, state, content, epg, true) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { HomeBody(nav, ui, profile, state, content, epg, false) }
                BottomBar(ui)
            }
        }
    }
}

@Composable
private fun HomeBody(
    nav: Nav,
    ui: UiState,
    profile: Profile,
    state: LoadState,
    content: Content?,
    epg: Epg?,
    wide: Boolean,
) {
    val ctx = LocalContext.current
    if (ui.tab == TAB_SETTINGS) {
        SettingsScreen(nav, profile)
        return
    }
    when {
        state is LoadState.Error -> ErrorView(
            state.message,
            onRetry = { Repo.load(ctx, profile, force = true) },
            onEdit = { nav.push(Route.EditProfile(profile)) },
            onProfiles = { nav.push(Route.Profiles) },
        )
        content == null -> LoadingView((state as? LoadState.Loading)?.message ?: "Loading…")
        else -> when (ui.tab) {
            0 -> LiveScreen(content, epg, ui, wide)
            1 -> GuideScreen(content, epg, ui, wide)
            2 -> MoviesScreen(nav, content, ui, wide)
            3 -> SeriesScreen(nav, content, ui, wide)
            else -> SearchScreen(nav, content, ui)
        }
    }
}

@Composable
private fun SideRail(ui: UiState) {
    Column(
        Modifier
            .width(92.dp)
            .fillMaxHeight()
            .background(Palette.Panel)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("TVDeck", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
        Spacer(Modifier.height(10.dp))
        TABS.forEachIndexed { i, t ->
            FocusBox(
                onClick = { ui.tab = i },
                selected = ui.tab == i,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 10.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(t.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(t.label, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BottomBar(ui: UiState) {
    Row(Modifier.fillMaxWidth().background(Palette.Panel).padding(4.dp)) {
        TABS.forEachIndexed { i, t ->
            FocusBox(
                onClick = { ui.tab = i },
                selected = ui.tab == i,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(t.icon, contentDescription = t.label, modifier = Modifier.size(20.dp))
                    Text(t.label, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

// ---------- Starting playback ----------

object Launch {
    fun live(ctx: Context, profile: Profile, list: List<Channel>, ch: Channel) {
        val source = if (list.any { it.key == ch.key }) list else listOf(ch)
        val index = source.indexOfFirst { it.key == ch.key }.coerceAtLeast(0)
        start(ctx, profile, source.map { c ->
            val variants = if (c.alternates.isEmpty()) emptyList()
            else (listOf(c) + c.alternates).map { Variant(it.quality, it.url) }
            PlayItem("live:${c.key}", c.name, c.url, live = true, channel = c, variants = variants)
        }, index, false)
    }

    fun catchup(ctx: Context, profile: Profile, ch: Channel, p: Programme) {
        val cu = ch.catchup
        val url = if (cu != null) CatchupUrl.build(ch.url, cu, p.start, p.stop, System.currentTimeMillis()) else null
        if (url == null) {
            Toast.makeText(ctx, "Catch-up isn't available for this channel", Toast.LENGTH_SHORT).show()
            return
        }
        val item = PlayItem(
            key = "cu:${ch.key}:${p.start}",
            title = "${ch.name} · ${p.title}",
            url = url,
            live = false,
            channel = ch,
            subtitle = "Catch-up · ${fmtDay(p.start)} ${fmtTime(p.start)} – ${fmtTime(p.stop)}",
        )
        start(ctx, profile, listOf(item), 0, true)
    }

    fun vod(ctx: Context, profile: Profile, items: List<PlayItem>, index: Int, fromStart: Boolean) =
        start(ctx, profile, items, index, fromStart)

    private fun start(ctx: Context, profile: Profile, items: List<PlayItem>, index: Int, fromStart: Boolean) {
        if (items.isEmpty()) return
        PlaybackSession.items = items
        PlaybackSession.index = index
        PlaybackSession.userAgent = profile.userAgent
        PlaybackSession.startFromBeginning = fromStart
        ctx.startActivity(Intent(ctx, PlayerActivity::class.java))
    }
}
