package com.tvdeck.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvdeck.app.data.Content
import com.tvdeck.app.data.Http
import com.tvdeck.app.data.LoadState
import com.tvdeck.app.data.ManagerServer
import com.tvdeck.app.data.Repo
import com.tvdeck.app.data.Store
import com.tvdeck.app.model.AccountInfo
import com.tvdeck.app.model.Profile
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Info
import com.tvdeck.app.model.SourceType
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

// ---------- Search ----------

@Composable
fun SearchScreen(nav: Nav, c: Content, ui: UiState) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val q = ui.search.trim()
    // Sequences stop at the 100th match instead of scanning every movie on each key press.
    val channels = remember(c, q) { if (q.length < 2) emptyList() else c.channels.asSequence().filter { it.name.contains(q, true) }.take(100).toList() }
    val movies = remember(c, q) { if (q.length < 2) emptyList() else c.movies.asSequence().filter { it.name.contains(q, true) }.take(100).toList() }
    val series = remember(c, q) { if (q.length < 2) emptyList() else c.series.asSequence().filter { it.name.contains(q, true) }.take(100).toList() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = ui.search,
            onValueChange = { ui.search = it },
            singleLine = true,
            label = { Text("Search channels, movies and series") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        when {
            q.length < 2 -> Text("Type at least 2 letters.", color = Palette.TextDim)
            channels.isEmpty() && movies.isEmpty() && series.isEmpty() -> Text("No results for \"$q\".", color = Palette.TextDim)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (channels.isNotEmpty()) {
                item { SectionTitle("CHANNELS (${channels.size})") }
                items(channels, key = { "c" + it.key }) { ch ->
                    ResultRow(ch.name, ch.group, ch.logo, poster = false) { Launch.live(ctx, c.profile, channels, ch) }
                }
            }
            if (movies.isNotEmpty()) {
                item { SectionTitle("MOVIES (${movies.size})") }
                items(movies, key = { "m" + it.key }) { m ->
                    ResultRow(m.name, m.group, m.poster, poster = true) { nav.push(Route.MovieDetail(m)) }
                }
            }
            if (series.isNotEmpty()) {
                item { SectionTitle("SERIES (${series.size})") }
                items(series, key = { "s" + it.key }) { s ->
                    ResultRow(s.name, s.group, s.cover, poster = true) { nav.push(Route.SeriesDetail(s)) }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(title: String, subtitle: String, image: String?, poster: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (poster) Poster(image, title, Modifier.size(36.dp, 54.dp)) else Logo(image, title, Modifier.size(54.dp, 36.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen(nav: Nav, profile: Profile) {
    val ctx = LocalContext.current
    val epgStatus by Repo.epgStatus.collectAsState()
    val epgProgress by Repo.epgProgress.collectAsState()
    val content by Repo.content.collectAsState()
    val troubleshooting by Store.troubleshooting.collectAsState()
    val combine by Store.combineVersions.collectAsState()
    val preferHighest by Store.preferHighest.collectAsState()
    val account by Repo.account.collectAsState()
    val autoHours by Store.autoRefreshHours.collectAsState()
    val refreshNote by Repo.refreshNote.collectAsState()
    val reminders by Store.reminders.collectAsState()
    var note by remember { mutableStateOf("") }
    val fr = rememberInitialFocus()
    val now = rememberNow()
    LaunchedEffect(content) { Repo.refreshAccount(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Panel).padding(16.dp)
        ) {
            Text("CURRENT PROFILE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.TextDim)
            Spacer(Modifier.height(4.dp))
            Text(profile.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("${typeLabel(profile.type)} · ${hostOf(profile.url)}", fontSize = 13.sp, color = Palette.TextDim)
            content?.let {
                Text("${it.channels.size} channels · ${it.movies.size} movies · ${it.series.size} series", fontSize = 13.sp, color = Palette.TextDim)
            }
            account?.let { AccountLines(it, now) }
            val last = remember(content, now) { Store.lastRefresh(profile.id) }
            if (last > 0) Text("Lists downloaded ${fmtDay(last)} ${fmtTime(last)}", fontSize = 13.sp, color = Palette.TextDim)
            if (refreshNote.isNotEmpty()) Text(refreshNote, fontSize = 13.sp, color = Palette.Live)
            if (epgStatus.isNotEmpty()) Text(epgStatus, fontSize = 13.sp, color = Palette.TextDim)
            epgProgress?.let { p ->
                Spacer(Modifier.height(6.dp))
                val fraction = p.fraction
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = Palette.Accent,
                        trackColor = Palette.PanelAlt,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.Accent, trackColor = Palette.PanelAlt)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Switch profile", Icons.Default.Person, Modifier.focusRequester(fr)) { nav.push(Route.Profiles) }
            ActionButton("Edit this profile", Icons.Default.Edit) { nav.push(Route.EditProfile(profile)) }
            ActionButton("Manage provider & channels from phone or computer", Icons.Default.Phone) { nav.push(Route.Manager) }
            ActionButton("Refresh channels, movies & series", Icons.Default.Refresh) {
                Repo.load(ctx, profile, force = true)
                note = "Refreshing…"
            }
            ActionButton("Reload TV guide", Icons.Default.DateRange) { Repo.reloadEpg(ctx) }
            ActionButton("Automatic refresh: ${autoRefreshLabel(autoHours)}", Icons.Default.Refresh) {
                val i = AUTO_REFRESH_CHOICES.indexOf(autoHours)
                Store.setAutoRefreshHours(AUTO_REFRESH_CHOICES[(i + 1) % AUTO_REFRESH_CHOICES.size])
            }
            Text(
                "Channels, movies, series and the TV guide are downloaded again in the background while TVDeck is open. It waits while you're watching.",
                fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.widthIn(max = 640.dp),
            )
            ActionButton("Reminders (${reminders.size})", Icons.Default.Notifications) { nav.push(Route.Reminders) }
            ActionButton("Clear cached data", Icons.Default.Delete) {
                Http.clearCache(ctx)
                note = "Cache cleared. Data will be downloaded again next time."
            }
            ActionButton(
                "Combine HD / FHD versions of a channel: ${if (combine) "ON" else "OFF"}",
                Icons.Default.Check,
            ) {
                Store.setCombineVersions(!combine)
                Repo.load(ctx, profile)
                note = "Reloading channels…"
            }
            if (combine) {
                ActionButton(
                    "Play first: ${if (preferHighest) "best quality (FHD)" else "HD (more reliable)"}",
                    Icons.Default.Star,
                ) {
                    Store.setPreferHighest(!preferHighest)
                    Repo.load(ctx, profile)
                    note = "Reloading channels…"
                }
            }
            Text(
                "Combined channels switch to the other version automatically if the first one doesn't work.",
                fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.widthIn(max = 640.dp),
            )
            ActionButton(
                "Troubleshooting mode: ${if (troubleshooting) "ON" else "OFF"}",
                Icons.Default.Build,
            ) { Store.setTroubleshooting(!troubleshooting) }
            Text(
                "When on, the player shows the technical reason at the bottom of the screen if a stream fails or keeps reconnecting.",
                fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.widthIn(max = 640.dp),
            )
        }
        if (note.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(note, fontSize = 13.sp, color = Palette.Accent)
        }
        Spacer(Modifier.height(24.dp))
        Text("About", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        val version = remember {
            runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        }
        Text(
            "TVDeck $version — an IPTV player for M3U playlists and Xtream Codes accounts. " +
                "It doesn't include any channels or content; it plays what your own provider gives you.",
            fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.widthIn(max = 640.dp),
        )
    }
}

fun typeLabel(t: SourceType) = if (t == SourceType.XTREAM) "Xtream Codes" else "M3U playlist"

val AUTO_REFRESH_CHOICES = listOf(24, 12, 6, 0)

fun autoRefreshLabel(h: Int) = when (h) {
    0 -> "OFF"
    24 -> "daily"
    else -> "every $h hours"
}

/** Expiry and connections, with the expiry in red when it's close. */
@Composable
fun AccountLines(a: AccountInfo, now: Long) {
    val exp = a.expiresAt
    val days = if (exp != null) ((exp - now) / com.tvdeck.app.model.DAY).toInt() else null
    val expiry = when {
        exp == null -> "No expiry date"
        exp <= now -> "Expired on ${fmtDate(exp)}"
        days == 0 -> "Expires today (${fmtDate(exp)})"
        else -> "Expires ${fmtDate(exp)} · $days day${if (days == 1) "" else "s"} left"
    }
    val warn = exp != null && (days ?: 0) < 7 || !a.status.equals("Active", true)
    val parts = mutableListOf(expiry)
    if (a.maxConnections != null) parts += "${a.activeConnections ?: 0} of ${a.maxConnections} connections in use"
    if (a.trial) parts += "Trial"
    if (!a.status.equals("Active", true)) parts.add(0, "Account ${a.status}")
    Text(parts.joinToString(" · "), fontSize = 13.sp, color = if (warn) Palette.Live else Palette.TextDim)
}

// ---------- Web manager (provider & channels) ----------

/** Runs the web manager for as long as this screen is open. */
@Composable
fun ManagerScreen(nav: Nav) {
    val ctx = LocalContext.current
    val server = remember { ManagerServer(ctx) }
    var address by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val pin by server.pin.collectAsState()
    val activeId by Store.activeId.collectAsState()
    val profiles by Store.profiles.collectAsState()
    val state by Repo.state.collectAsState()
    val account by Repo.account.collectAsState()
    val active = profiles.firstOrNull { it.id == activeId }
    val fr = rememberInitialFocus()

    fun done() {
        nav.pop()
        // A profile form underneath may be out of date after changes made in the browser.
        while (nav.stack.size > 1 && nav.top is Route.EditProfile) nav.pop()
        // After setting up a provider from the browser, go straight to it.
        if (active != null && Route.Home !in nav.stack) nav.reset(Route.Home)
    }
    BackHandler { done() }

    DisposableEffect(server) {
        val job = thread(isDaemon = true) {
            try {
                server.start()
                address = ManagerServer.localAddress()?.let { "http://$it:${server.port}" }
            } catch (e: IOException) {
                failed = true
            }
        }
        onDispose {
            thread(isDaemon = true) {
                job.join()
                server.stop()
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text("Manage from phone or computer", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "On a phone or computer on the same network, open this address in a browser and enter the PIN. " +
                "You can add or edit your IPTV provider and choose which groups and channels to show, all on one page.",
            fontSize = 14.sp, color = Palette.TextDim, modifier = Modifier.widthIn(max = 640.dp),
        )
        Spacer(Modifier.height(20.dp))
        val a = address
        when {
            failed -> Text("The manager couldn't start. Go back and try again.", color = Palette.Live)
            a == null -> Text("Waiting for a network connection…", color = Palette.TextDim)
            else -> {
                Text("ADDRESS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.TextDim)
                Text(a, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                Spacer(Modifier.height(12.dp))
                Text("PIN", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.TextDim)
                Text(pin, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("PROVIDER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.TextDim)
        if (active == null) {
            Text("None yet — add one from the browser.", fontSize = 16.sp)
        } else {
            Text(active.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            val s = state
            Text(
                when (s) {
                    is LoadState.Loading -> s.message
                    is LoadState.Error -> "Couldn't load: ${s.message}"
                    LoadState.Ready -> "Connected"
                    LoadState.Idle -> "${typeLabel(active.type)} · ${hostOf(active.url)}"
                },
                fontSize = 13.sp,
                color = if (s is LoadState.Error) Palette.Live else Palette.TextDim,
            )
            if (s == LoadState.Ready) account?.let { AccountLines(it, System.currentTimeMillis()) }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Changes apply on this TV as soon as you save them in the browser. The manager stops when you leave this screen.",
            fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.widthIn(max = 640.dp),
        )
        Spacer(Modifier.height(16.dp))
        ActionButton("Done", Icons.Default.Check, Modifier.focusRequester(fr)) { done() }
    }
}

// ---------- Profiles ----------

@Composable
fun ProfilesScreen(nav: Nav) {
    val profiles by Store.profiles.collectAsState()
    val activeId by Store.activeId.collectAsState()
    var confirmDelete by remember { mutableStateOf<Profile?>(null) }
    val fr = rememberInitialFocus()

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text("TVDeck", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
        Text("Choose a profile", fontSize = 15.sp, color = Palette.TextDim)
        Spacer(Modifier.height(16.dp))
        LazyColumn(Modifier.weight(1f, fill = false).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(profiles, key = { _, p -> p.id }) { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FocusBox(
                        onClick = {
                            Store.setActive(p.id)
                            nav.reset(Route.Home)
                        },
                        selected = p.id == activeId,
                        baseColor = Palette.Panel,
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.weight(1f).then(if (i == 0) Modifier.focusRequester(fr) else Modifier),
                    ) {
                        Column {
                            Text(p.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${typeLabel(p.type)} · ${hostOf(p.url)}", fontSize = 13.sp, color = Palette.TextDim, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    ActionButton("", Icons.Default.Edit) { nav.push(Route.EditProfile(p)) }
                    Spacer(Modifier.width(8.dp))
                    ActionButton("", Icons.Default.Delete) { confirmDelete = p }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Add profile", Icons.Default.Add, if (profiles.isEmpty()) Modifier.focusRequester(fr) else Modifier) {
                nav.push(Route.EditProfile(null))
            }
            ActionButton("Set up from phone or computer", Icons.Default.Phone) { nav.push(Route.Manager) }
        }
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${p.name}\"?") },
            text = { Text("This removes the profile and its favorites from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.deleteProfile(p.id)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            containerColor = Palette.Panel,
        )
    }
}

@Composable
fun EditProfileScreen(nav: Nav, existing: Profile?) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: SourceType.XTREAM) }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var epg by remember { mutableStateOf(existing?.epgUrl ?: "") }
    var ua by remember { mutableStateOf(existing?.userAgent ?: "") }
    var format by remember { mutableStateOf(existing?.outputFormat ?: "ts") }
    var error by remember { mutableStateOf<String?>(null) }
    val fr = rememberInitialFocus()

    fun save() {
        val u = url.trim()
        if (u.isEmpty()) {
            error = if (type == SourceType.XTREAM) "Please enter the server URL." else "Please enter the playlist URL."
            return
        }
        if (type == SourceType.XTREAM && (user.isBlank() || pass.isBlank())) {
            error = "Username and password are required for Xtream Codes."
            return
        }
        val p = Profile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { hostOf(u) },
            type = type,
            url = u,
            username = user.trim(),
            password = pass.trim(),
            epgUrl = epg.trim(),
            userAgent = ua.trim(),
            outputFormat = format,
        )
        Store.saveProfile(p)
        Store.setActive(p.id)
        nav.reset(Route.Home)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)) {
        Column(Modifier.widthIn(max = 640.dp)) {
            Text(if (existing == null) "Add a profile" else "Edit profile", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Enter the details your IPTV provider gave you.", fontSize = 14.sp, color = Palette.TextDim)
            Spacer(Modifier.height(10.dp))
            ActionButton("Type them on a phone or computer instead", Icons.Default.Phone) { nav.push(Route.Manager) }
            Spacer(Modifier.height(16.dp))
            Text("Connection type", fontSize = 13.sp, color = Palette.TextDim)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Xtream Codes", type == SourceType.XTREAM, Modifier.focusRequester(fr)) { type = SourceType.XTREAM }
                Chip("M3U playlist", type == SourceType.M3U) { type = SourceType.M3U }
            }
            Spacer(Modifier.height(8.dp))
            Field("Profile name (optional)", name) { name = it }
            if (type == SourceType.XTREAM) {
                Field("Server URL, e.g. http://provider.com:8080", url, uri = true) { url = it }
                Field("Username", user) { user = it }
                Field("Password", pass, password = true) { pass = it }
                Spacer(Modifier.height(6.dp))
                Text("Live stream format", fontSize = 13.sp, color = Palette.TextDim)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("MPEG-TS (default)", format == "ts") { format = "ts" }
                    Chip("HLS (m3u8)", format == "m3u8") { format = "m3u8" }
                }
                Spacer(Modifier.height(6.dp))
                Field("TV guide URL (optional — overrides the provider's)", epg, uri = true) { epg = it }
            } else {
                Field("Playlist URL (M3U / M3U8)", url, uri = true) { url = it }
                Field("TV guide / XMLTV URL (optional if the playlist includes one)", epg, uri = true) { epg = it }
            }
            Field("Custom User-Agent (optional)", ua) { ua = it }
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = Palette.Live, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Save & connect", Icons.Default.Check) { save() }
                if (nav.stack.size > 1) ActionButton("Cancel", Icons.Default.Close) { nav.pop() }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    password: Boolean = false,
    uri: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                uri -> KeyboardType.Uri
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            },
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}
