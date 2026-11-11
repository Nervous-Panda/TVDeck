package com.tvdeck.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tvdeck.app.data.Content
import com.tvdeck.app.data.Epg
import com.tvdeck.app.data.Store
import com.tvdeck.app.model.Channel
import com.tvdeck.app.model.DAY

const val GROUP_FAV = "★ Favorites"
const val GROUP_RECENT = "Recently watched"
const val GROUP_ALL = "All channels"

fun channelsFor(c: Content, group: String, favs: Set<String>, recents: List<String>): List<Channel> = when (group) {
    GROUP_FAV -> c.channels.filter { it.key in favs }
    GROUP_RECENT -> recents.mapNotNull { c.channelByKey[it] }
    GROUP_ALL -> c.channels
    else -> c.channelsByGroup[group].orEmpty()
}

private fun emptyTextFor(group: String) = when (group) {
    GROUP_FAV -> "No favorites yet.\nLong-press a channel (or use the Favorite button) to add it."
    GROUP_RECENT -> "Channels you watch will appear here."
    else -> "No channels in this group."
}

@Composable
fun LiveScreen(c: Content, epg: Epg?, ui: UiState, wide: Boolean) {
    val ctx = LocalContext.current
    val favs by Store.favorites.collectAsState()
    val recents by Store.recents.collectAsState()
    val groups = remember(c) { (listOf(GROUP_FAV, GROUP_RECENT, GROUP_ALL) + c.liveGroups).distinct() }
    val group = ui.liveGroup?.takeIf { it in groups } ?: c.liveGroups.firstOrNull() ?: GROUP_ALL
    val list = remember(c, group, favs, recents) { channelsFor(c, group, favs, recents) }
    val listState = remember(group) { LazyListState() }
    val now = rememberNow()
    var preview by remember { mutableStateOf<Channel?>(null) }
    var dialogFor by remember { mutableStateOf<Channel?>(null) }
    val play: (Channel) -> Unit = { ch -> Launch.live(ctx, c.profile, list, ch) }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            GroupList(groups, group, { ui.liveGroup = it }, Modifier.width(220.dp))
            ChannelList(
                list = list,
                state = listState,
                epg = epg,
                now = now,
                favs = favs,
                selectedKey = preview?.key,
                onFocus = { preview = it },
                onClick = play,
                onLongClick = { Store.toggleFavorite(it.key) },
                emptyText = emptyTextFor(group),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            val shown = remember(preview, list) { preview?.takeIf { p -> list.any { it.key == p.key } } ?: list.firstOrNull() }
            ChannelInfoPanel(shown, c, list, epg, now, favs, Modifier.width(330.dp).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            GroupChips(groups, group) { ui.liveGroup = it }
            ChannelList(
                list = list,
                state = listState,
                epg = epg,
                now = now,
                favs = favs,
                selectedKey = null,
                onFocus = null,
                onClick = play,
                onLongClick = { dialogFor = it },
                emptyText = emptyTextFor(group),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        dialogFor?.let { ch ->
            Dialog(onDismissRequest = { dialogFor = null }) {
                ChannelInfoPanel(
                    ch, c, list, epg, now, favs,
                    Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
        }
    }
}

@Composable
private fun ChannelList(
    list: List<Channel>,
    state: LazyListState,
    epg: Epg?,
    now: Long,
    favs: Set<String>,
    selectedKey: String?,
    onFocus: ((Channel) -> Unit)?,
    onClick: (Channel) -> Unit,
    onLongClick: (Channel) -> Unit,
    emptyText: String,
    modifier: Modifier,
) {
    if (list.isEmpty()) {
        EmptyText(emptyText, modifier)
        return
    }
    LazyColumn(
        modifier,
        state = state,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(list, key = { it.key }) { ch ->
            FocusBox(
                onClick = { onClick(ch) },
                onLongClick = { onLongClick(ch) },
                onFocused = onFocus?.let { f -> { f(ch) } },
                selected = ch.key == selectedKey,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ch.number.toString(), fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.width(44.dp), maxLines = 1)
                    Logo(ch.logo, ch.name, Modifier.size(56.dp, 36.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ch.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                            )
                            if (ch.key in favs) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Favorite, null, tint = Palette.Live, modifier = Modifier.size(14.dp))
                            }
                            if (ch.catchup != null) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Refresh, "Catch-up", tint = Palette.TextDim, modifier = Modifier.size(14.dp))
                            }
                        }
                        val cur = epg?.current(ch, now)
                        if (cur != null) {
                            Text(cur.title, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(3.dp))
                            ProgressBar(
                                (now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1),
                                Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelInfoPanel(
    ch: Channel?,
    c: Content,
    list: List<Channel>,
    epg: Epg?,
    now: Long,
    favs: Set<String>,
    modifier: Modifier,
) {
    val ctx = LocalContext.current
    Column(modifier.background(Palette.Panel).padding(16.dp)) {
        if (ch == null) {
            Text("Select a channel", color = Palette.TextDim)
            return@Column
        }
        var showCatchup by remember(ch.key) { mutableStateOf(false) }
        val cur = epg?.current(ch, now)
        val next = epg?.next(ch, now)
        val catchup = ch.catchup

        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(ch.logo, ch.name, Modifier.size(72.dp, 48.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(ch.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${ch.number} · ${ch.group}", fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        if (cur != null) {
            Text("NOW · ${fmtTime(cur.start)} – ${fmtTime(cur.stop)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Palette.Live)
            Text(cur.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            ProgressBar((now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1), Modifier.fillMaxWidth())
            if (!showCatchup && cur.desc.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(cur.desc, fontSize = 13.sp, color = Palette.TextDim, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text(if (epg == null) "TV guide not loaded yet" else "No programme information", fontSize = 13.sp, color = Palette.TextDim)
        }
        if (next != null && !showCatchup) {
            Spacer(Modifier.height(8.dp))
            Text("NEXT · ${fmtTime(next.start)}  ${next.title}", fontSize = 13.sp, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Watch", Icons.Default.PlayArrow) { Launch.live(ctx, c.profile, list, ch) }
            val fav = ch.key in favs
            ActionButton(if (fav) "Unfavorite" else "Favorite", if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder) {
                Store.toggleFavorite(ch.key)
            }
        }
        if (catchup != null) {
            Spacer(Modifier.height(8.dp))
            ActionButton(if (showCatchup) "Hide catch-up" else "Catch-up (${catchup.days} days)", Icons.Default.Refresh) {
                showCatchup = !showCatchup
            }
            if (showCatchup) {
                val past = remember(ch.key, epg, now / 60_000) {
                    epg?.programmes(ch).orEmpty()
                        .filter { it.start < now && it.start >= now - catchup.days * DAY }
                        .reversed()
                }
                Spacer(Modifier.height(8.dp))
                if (past.isEmpty()) {
                    Text(
                        if (epg == null) "Catch-up needs the TV guide, which is still loading." else "No past programmes in the TV guide.",
                        fontSize = 13.sp, color = Palette.TextDim,
                    )
                }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(past) { p ->
                        FocusBox(onClick = { Launch.catchup(ctx, c.profile, ch, p) }, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                val label = if (p.stop > now) "Start over · " else ""
                                Text("$label${fmtDay(p.start)} ${fmtTime(p.start)}", fontSize = 11.sp, color = Palette.TextDim)
                                Text(p.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
