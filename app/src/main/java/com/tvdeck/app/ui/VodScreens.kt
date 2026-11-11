package com.tvdeck.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvdeck.app.data.Content
import com.tvdeck.app.data.Repo
import com.tvdeck.app.data.Store
import com.tvdeck.app.data.XtreamApi
import com.tvdeck.app.model.Episode
import com.tvdeck.app.model.SeriesItem
import com.tvdeck.app.model.SourceType
import com.tvdeck.app.model.VodItem
import com.tvdeck.app.player.PlayItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

const val GROUP_EVERYTHING = "All"
const val GROUP_CONTINUE = "Continue watching"

// ---------- Movies ----------

@Composable
fun MoviesScreen(nav: Nav, c: Content, ui: UiState, wide: Boolean) {
    val scope = rememberCoroutineScope()
    val resumeVersion by Store.resumeVersion.collectAsState()
    val groups = remember(c) { (listOf(GROUP_CONTINUE, GROUP_EVERYTHING) + c.movieGroups).distinct() }
    val group = ui.movieGroup?.takeIf { it in groups } ?: c.movieGroups.firstOrNull() ?: GROUP_EVERYTHING
    val entries = remember(c, group, resumeVersion) {
        when (group) {
            GROUP_EVERYTHING -> c.movies
            GROUP_CONTINUE -> Store.resumeKeys().let { keys -> c.movies.filter { it.key in keys } }
            else -> c.moviesByGroup[group].orEmpty()
        }
    }
    PosterBrowser(
        groups = groups,
        group = group,
        onGroup = {
            if (it != group) {
                ui.movieGroup = it
                scope.launch { ui.movieGrid.scrollToItem(0) }
            }
        },
        entries = entries,
        gridState = ui.movieGrid,
        wide = wide,
        emptyText = when {
            c.moviesFailed -> "Movies couldn't be loaded. The provider may be unreachable, or its list too large for this device."
            c.movies.isEmpty() -> "Your provider doesn't list any movies."
            group == GROUP_CONTINUE -> "Movies you start watching will appear here."
            else -> "Nothing in this category."
        },
        key = { it.key },
        title = { it.name },
        image = { it.poster },
    ) { nav.push(Route.MovieDetail(it)) }
}

// ---------- Series ----------

@Composable
fun SeriesScreen(nav: Nav, c: Content, ui: UiState, wide: Boolean) {
    val scope = rememberCoroutineScope()
    val groups = remember(c) { (listOf(GROUP_EVERYTHING) + c.seriesGroups).distinct() }
    val group = ui.seriesGroup?.takeIf { it in groups } ?: c.seriesGroups.firstOrNull() ?: GROUP_EVERYTHING
    val entries = remember(c, group) {
        if (group == GROUP_EVERYTHING) c.series else c.seriesByGroup[group].orEmpty()
    }
    PosterBrowser(
        groups = groups,
        group = group,
        onGroup = {
            if (it != group) {
                ui.seriesGroup = it
                scope.launch { ui.seriesGrid.scrollToItem(0) }
            }
        },
        entries = entries,
        gridState = ui.seriesGrid,
        wide = wide,
        emptyText = when {
            c.seriesFailed -> "Series couldn't be loaded. The provider may be unreachable, or its list too large for this device."
            c.series.isEmpty() -> "Your provider doesn't list any series."
            else -> "Nothing in this category."
        },
        key = { it.key },
        title = { it.name },
        image = { it.cover },
    ) { nav.push(Route.SeriesDetail(it)) }
}

@Composable
private fun <T> PosterBrowser(
    groups: List<String>,
    group: String,
    onGroup: (String) -> Unit,
    entries: List<T>,
    gridState: LazyGridState,
    wide: Boolean,
    emptyText: String,
    key: (T) -> String,
    title: (T) -> String,
    image: (T) -> String?,
    onClick: (T) -> Unit,
) {
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            GroupList(groups, group, onGroup, Modifier.width(220.dp))
            PosterGrid(entries, gridState, wide, emptyText, key, title, image, onClick, Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            GroupChips(groups, group, onGroup)
            PosterGrid(entries, gridState, wide, emptyText, key, title, image, onClick, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun <T> PosterGrid(
    entries: List<T>,
    gridState: LazyGridState,
    wide: Boolean,
    emptyText: String,
    key: (T) -> String,
    title: (T) -> String,
    image: (T) -> String?,
    onClick: (T) -> Unit,
    modifier: Modifier,
) {
    if (entries.isEmpty()) {
        EmptyText(emptyText, modifier)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (wide) 132.dp else 104.dp),
        state = gridState,
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = key) { entry -> PosterTile(title(entry), image(entry)) { onClick(entry) } }
    }
}

@Composable
private fun PosterTile(title: String, image: String?, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(5.dp)) {
        Column {
            Poster(image, title, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            Spacer(Modifier.height(5.dp))
            Text(title, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- Movie details ----------

@Composable
fun MovieDetailScreen(nav: Nav, item: VodItem) {
    val content by Repo.content.collectAsState()
    val c = content
    if (c == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val ctx = LocalContext.current
    var info by remember(item.key) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(item.key) {
        val id = item.xtreamId
        if (c.profile.type == SourceType.XTREAM && id != null) {
            info = try {
                XtreamApi(ctx.applicationContext, c.profile).vodInfo(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyMap()
            }
        }
    }
    val resumeVersion by Store.resumeVersion.collectAsState()
    val resume = remember(item.key, resumeVersion) { Store.resume(item.key) }
    val fr = rememberInitialFocus()
    val play = { fromStart: Boolean ->
        Launch.vod(ctx, c.profile, listOf(PlayItem(item.key, item.name, item.url, live = false, resumeKey = item.key)), 0, fromStart)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val poster = info["cover"] ?: item.poster
        if (wide) {
            Row(Modifier.fillMaxSize().padding(32.dp)) {
                Poster(poster, item.name, Modifier.width(220.dp).aspectRatio(2f / 3f))
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { MovieDetails(item, info, resume, fr, play) { nav.pop() } }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Poster(poster, item.name, Modifier.width(150.dp).aspectRatio(2f / 3f))
                Spacer(Modifier.height(16.dp))
                MovieDetails(item, info, resume, fr, play) { nav.pop() }
            }
        }
    }
}

@Composable
private fun MovieDetails(
    item: VodItem,
    info: Map<String, String>,
    resume: Long,
    fr: FocusRequester,
    play: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Text(item.name, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    val meta = listOfNotNull(
        info["year"],
        info["genre"],
        info["duration"],
        item.rating.takeIf { it.isNotBlank() }?.let { "★ $it" },
    ).joinToString("  ·  ")
    if (meta.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(meta, fontSize = 14.sp, color = Palette.TextDim)
    }
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (resume > 0) {
            ActionButton("Resume ${fmtDuration(resume)}", Icons.Default.PlayArrow, Modifier.focusRequester(fr)) { play(false) }
            ActionButton("From start", Icons.Default.Refresh) { play(true) }
        } else {
            ActionButton("Play", Icons.Default.PlayArrow, Modifier.focusRequester(fr)) { play(true) }
        }
        ActionButton("Back", Icons.AutoMirrored.Filled.ArrowBack) { onBack() }
    }
    info["plot"]?.let {
        Spacer(Modifier.height(18.dp))
        Text(it, fontSize = 15.sp, lineHeight = 21.sp)
    }
    info["cast"]?.let {
        Spacer(Modifier.height(12.dp))
        Text("Cast: $it", fontSize = 13.sp, color = Palette.TextDim)
    }
    info["director"]?.let {
        Spacer(Modifier.height(4.dp))
        Text("Director: $it", fontSize = 13.sp, color = Palette.TextDim)
    }
    Spacer(Modifier.height(4.dp))
    Text(item.group, fontSize = 13.sp, color = Palette.TextDim)
}

// ---------- Series details ----------

private fun Episode.toPlayItem(seriesName: String) =
    PlayItem(key, "$seriesName · S${season}E$episode", url, live = false, subtitle = title, resumeKey = key)

@Composable
fun SeriesDetailScreen(nav: Nav, item: SeriesItem) {
    val content by Repo.content.collectAsState()
    val c = content
    if (c == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val ctx = LocalContext.current
    var episodes by remember(item.key) { mutableStateOf(item.episodes) }
    var plot by remember(item.key) { mutableStateOf(item.plot) }
    var loading by remember(item.key) { mutableStateOf(item.xtreamId != null && item.episodes.isEmpty()) }
    var error by remember(item.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.key) {
        val id = item.xtreamId
        if (id != null && episodes.isEmpty()) {
            try {
                val (p, eps) = XtreamApi(ctx.applicationContext, c.profile).seriesInfo(id)
                episodes = eps
                if (p.isNotBlank()) plot = p
                if (eps.isEmpty()) error = "No episodes found for this series."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't load episodes: ${e.message}"
            }
            loading = false
        }
    }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    var season by remember(seasons) { mutableIntStateOf(seasons.firstOrNull() ?: 1) }
    val seasonEpisodes = remember(episodes, season) { episodes.filter { it.season == season }.sortedBy { it.episode } }
    val resumeVersion by Store.resumeVersion.collectAsState()
    val fr = rememberInitialFocus()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        Column(Modifier.fillMaxSize().padding(if (wide) 28.dp else 14.dp)) {
            Row {
                Poster(item.cover, item.name, Modifier.width(if (wide) 140.dp else 96.dp).aspectRatio(2f / 3f))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontSize = if (wide) 26.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.rating.isNotBlank()) Text("★ ${item.rating}", fontSize = 13.sp, color = Palette.TextDim)
                    if (plot.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(plot, fontSize = 14.sp, color = Palette.TextDim, maxLines = if (wide) 5 else 4, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    ActionButton("Back", Icons.AutoMirrored.Filled.ArrowBack) { nav.pop() }
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Accent)
                }
                error != null -> Text(error ?: "", color = Palette.TextDim)
            }
            if (seasons.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    items(seasons) { s -> Chip("Season $s", s == season) { season = s } }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(seasonEpisodes, key = { _, ep -> ep.key }) { i, ep ->
                    val pos = remember(ep.key, resumeVersion) { Store.resume(ep.key) }
                    FocusBox(
                        onClick = { Launch.vod(ctx, c.profile, seasonEpisodes.map { it.toPlayItem(item.name) }, i, false) },
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(fr) else Modifier),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("E${ep.episode}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Accent, modifier = Modifier.width(52.dp))
                            if (!ep.image.isNullOrBlank() && wide) {
                                Logo(ep.image, ep.title, Modifier.size(96.dp, 54.dp))
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(ep.title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = listOfNotNull(
                                    ep.duration.takeIf { it.isNotBlank() },
                                    if (pos > 0) "Resume at ${fmtDuration(pos)}" else null,
                                ).joinToString("  ·  ")
                                if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Palette.TextDim)
                                if (wide && ep.plot.isNotBlank()) {
                                    Text(ep.plot, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
