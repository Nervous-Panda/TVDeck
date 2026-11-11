package com.tvdeck.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.tvdeck.app.data.Reminders
import com.tvdeck.app.model.Reminder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tvdeck.app.data.Content
import com.tvdeck.app.data.Epg
import com.tvdeck.app.data.Repo
import com.tvdeck.app.data.Store
import com.tvdeck.app.model.Channel
import com.tvdeck.app.model.DAY
import com.tvdeck.app.model.HALF_HOUR
import com.tvdeck.app.model.HOUR
import com.tvdeck.app.model.MINUTE
import com.tvdeck.app.model.Programme
import kotlinx.coroutines.launch
import kotlin.math.abs

private class GuideGeometry(
    val start: Long,
    val end: Long,
    val dpPerMin: Dp,
) {
    val totalWidth: Dp get() = x(end)
    fun x(t: Long): Dp = dpPerMin * ((t - start).toFloat() / MINUTE)
}

/** Classic grid TV guide: channels down the side, time across the top. */
@Composable
fun GuideScreen(c: Content, epg: Epg?, ui: UiState, wide: Boolean) {
    val ctx = LocalContext.current
    val favs by Store.favorites.collectAsState()
    val recents by Store.recents.collectAsState()
    val epgStatus by Repo.epgStatus.collectAsState()
    val groups = remember(c) { (listOf(GROUP_FAV, GROUP_ALL) + c.liveGroups).distinct() }
    val group = ui.guideGroup?.takeIf { it in groups } ?: ui.liveGroup?.takeIf { it in groups } ?: c.liveGroups.firstOrNull() ?: GROUP_ALL
    val channels = remember(c, group, favs) { channelsFor(c, group, favs, recents) }
    val now = rememberNow()
    val geo = remember(wide) {
        val start = (System.currentTimeMillis() / HALF_HOUR) * HALF_HOUR - 2 * HOUR
        GuideGeometry(start, start + 26 * HOUR, if (wide) 4.dp else 3.dp)
    }
    val chanW = if (wide) 190.dp else 112.dp
    val rowH = if (wide) 58.dp else 54.dp
    val hScroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(geo) {
        val px = with(density) { geo.x(System.currentTimeMillis() - 30 * MINUTE).roundToPx() }
        hScroll.scrollTo(px.coerceAtLeast(0))
    }
    var info by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }
    val reminders by Store.reminders.collectAsState()
    val reminderStarts = remember(reminders, c) {
        reminders.filter { it.profileId == c.profile.id }.mapTo(HashSet()) { it.channelKey to it.start }
    }
    // Notifications are how a reminder reaches you when TVDeck isn't on screen (Android 13+ asks first).
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Vertical navigation: moving up/down keeps the same point in time instead of letting the
    // default geometric focus search jump to whichever neighbouring cell happens to be closest.
    // anchor == null means "follow what's on now"; it's only pinned once the user moves left/right.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    var anchor by remember { mutableStateOf<Long?>(null) }
    var focusedRow by remember { mutableStateOf(-1) }
    var verticalMove by remember { mutableStateOf(false) }
    // Set when focus arrives in the grid from outside (group chips, nav bar): the default focus search
    // lands on whatever cell is geometrically nearest, so we redirect it to what's on now.
    var entering by remember { mutableStateOf(false) }
    var gridFocused by remember { mutableStateOf(false) }
    LaunchedEffect(group) { anchor = null }
    val targetTime = (anchor ?: now).coerceIn(geo.start, geo.end - 1)
    val onCellFocused: (Int, Long, Boolean) -> Unit = { idx, cellStart, isNow ->
        focusedRow = idx
        when {
            entering -> {
                entering = false
                anchor = null
                if (!isNow) scope.launch {
                    withFrameNanos { } // let the row move its focus target to the "now" cell first
                    val fr = rowFocus[channels.getOrNull(idx)?.key] ?: return@launch
                    verticalMove = true
                    if (runCatching { fr.requestFocus() }.isFailure) verticalMove = false
                }
            }
            verticalMove -> verticalMove = false
            else -> anchor = if (isNow) null else cellStart
        }
    }
    val moveVertical: (Int) -> Boolean = move@{ dir ->
        val target = focusedRow + dir
        if (focusedRow < 0 || target !in channels.indices) return@move false
        val key = channels[target].key
        scope.launch {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.none { it.index == target }) {
                val span = (visible.size - 1).coerceAtLeast(1)
                listState.scrollToItem(if (dir > 0) (target - span + 1).coerceAtLeast(0) else target)
            }
            repeat(5) {
                val fr = rowFocus[key]
                if (fr != null && listState.layoutInfo.visibleItemsInfo.any { it.index == target }) {
                    verticalMove = true
                    if (runCatching { fr.requestFocus() }.isSuccess) return@launch
                    verticalMove = false
                }
                withFrameNanos { }
            }
        }
        true
    }

    val onProgramme: (Channel, Programme?) -> Unit = { ch, p ->
        val t = System.currentTimeMillis()
        val cu = ch.catchup
        when {
            p == null || (p.start <= t && p.stop > t) -> Launch.live(ctx, c.profile, channels, ch)
            p.stop <= t && cu != null && p.start >= t - cu.days * DAY -> Launch.catchup(ctx, c.profile, ch, p)
            else -> info = ch to p
        }
    }

    Column(Modifier.fillMaxSize()) {
        GroupChips(groups, group) { ui.guideGroup = it }
        if (epg == null) {
            Text(epgStatus.ifEmpty { "Loading TV guide…" }, fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        // Time header
        Row(Modifier.fillMaxWidth().height(30.dp).background(Palette.Panel)) {
            Box(Modifier.width(chanW).fillMaxHeight().padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
                Text(fmtDay(now), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
                Box(Modifier.width(geo.totalWidth).fillMaxHeight()) {
                    var t = geo.start
                    while (t < geo.end) {
                        Text(
                            fmtTime(t), fontSize = 12.sp, color = Palette.TextDim,
                            modifier = Modifier.offset(x = geo.x(t)).padding(start = 4.dp, top = 7.dp),
                        )
                        t += HALF_HOUR
                    }
                    Box(Modifier.offset(x = geo.x(now)).width(2.dp).fillMaxHeight().background(Palette.Live))
                }
            }
        }
        if (channels.isEmpty()) {
            EmptyText(if (group == GROUP_FAV) "No favorite channels yet." else "No channels in this group.", Modifier.fillMaxSize())
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().onFocusChanged {
                    if (it.hasFocus && !gridFocused) entering = true
                    gridFocused = it.hasFocus
                }.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionUp -> moveVertical(-1)
                        Key.DirectionDown -> moveVertical(1)
                        else -> false
                    }
                },
                state = listState,
            ) {
                itemsIndexed(channels, key = { _, ch -> ch.key }) { idx, ch ->
                    val fr = remember(ch.key) { FocusRequester() }
                    DisposableEffect(ch.key) {
                        rowFocus[ch.key] = fr
                        onDispose { if (rowFocus[ch.key] === fr) rowFocus.remove(ch.key) }
                    }
                    GuideRow(ch, epg, now, geo, chanW, rowH, hScroll, targetTime, fr, { s, isNow -> onCellFocused(idx, s, isNow) }, onProgramme, reminderStarts)
                }
            }
        }
    }

    info?.let { (ch, p) ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(p.title) },
            text = {
                Column {
                    Text("${ch.name} · ${fmtDay(p.start)} ${fmtTime(p.start)} – ${fmtTime(p.stop)}", color = Palette.TextDim, fontSize = 13.sp)
                    if (p.desc.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(p.desc, fontSize = 14.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    info = null
                    Launch.live(ctx, c.profile, channels, ch)
                }) { Text("Watch channel") }
            },
            dismissButton = {
                Row {
                    if (p.start > System.currentTimeMillis()) {
                        val set = (ch.key to p.start) in reminderStarts
                        TextButton(onClick = {
                            info = null
                            if (set) {
                                Reminders.cancel(ctx, Reminder(c.profile.id, ch.key, ch.name, p.title, p.start, p.stop).id)
                                Toast.makeText(ctx, "Reminder cancelled", Toast.LENGTH_SHORT).show()
                            } else {
                                Reminders.add(ctx, Reminder(c.profile.id, ch.key, ch.name, p.title, p.start, p.stop))
                                Toast.makeText(ctx, "We'll remind you at ${fmtTime(p.start)}", Toast.LENGTH_SHORT).show()
                                if (Build.VERSION.SDK_INT >= 33 && !Reminders.canNotify(ctx)) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }) { Text(if (set) "Cancel reminder" else "Remind me") }
                    }
                    TextButton(onClick = { info = null }) { Text("Close") }
                }
            },
            containerColor = Palette.Panel,
        )
    }
}

@Composable
private fun GuideRow(
    ch: Channel,
    epg: Epg?,
    now: Long,
    geo: GuideGeometry,
    chanW: Dp,
    rowH: Dp,
    hScroll: ScrollState,
    targetTime: Long,
    focusRequester: FocusRequester,
    onCellFocused: (cellStart: Long, isNow: Boolean) -> Unit,
    onProgramme: (Channel, Programme?) -> Unit,
    reminderStarts: Set<Pair<String, Long>>,
) {
    Row(Modifier.fillMaxWidth().height(rowH).padding(vertical = 1.dp)) {
        Row(
            Modifier.width(chanW).fillMaxHeight().background(Palette.Panel).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(ch.logo, ch.name, Modifier.size(40.dp, 28.dp))
            Spacer(Modifier.width(6.dp))
            Text(ch.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(hScroll)) {
            Box(Modifier.width(geo.totalWidth).fillMaxHeight()) {
                val progs = remember(ch.key, epg) {
                    epg?.programmes(ch).orEmpty().filter { it.stop > geo.start && it.start < geo.end }
                }
                if (progs.isEmpty()) {
                    val slots = generateSequence(geo.start) { it + HOUR }.takeWhile { it < geo.end }.toList()
                    val target = targetCell(slots.map { it to it + HOUR }, targetTime)
                    slots.forEachIndexed { i, s ->
                        val isNow = now >= s && now < s + HOUR
                        GuideCell(
                            x = geo.x(s), width = geo.x(s + HOUR) - geo.x(s),
                            title = "No information", subtitle = "",
                            isNow = isNow, isPast = s + HOUR <= now, hasCatchup = false,
                            focusRequester = focusRequester.takeIf { i == target },
                            onFocused = { onCellFocused(s, isNow) },
                        ) { onProgramme(ch, null) }
                    }
                } else {
                    val cu = ch.catchup
                    val target = targetCell(progs.map { maxOf(it.start, geo.start) to minOf(it.stop, geo.end) }, targetTime)
                    progs.forEachIndexed { i, p ->
                        val s = maxOf(p.start, geo.start)
                        val e = minOf(p.stop, geo.end)
                        val isPast = p.stop <= now
                        val isNow = p.start <= now && p.stop > now
                        GuideCell(
                            x = geo.x(s), width = geo.x(e) - geo.x(s),
                            title = p.title, subtitle = "${fmtTime(p.start)} – ${fmtTime(p.stop)}",
                            isNow = isNow, isPast = isPast,
                            hasCatchup = isPast && cu != null && p.start >= now - cu.days * DAY,
                            hasReminder = (ch.key to p.start) in reminderStarts,
                            focusRequester = focusRequester.takeIf { i == target },
                            onFocused = { onCellFocused(s, isNow) },
                        ) { onProgramme(ch, p) }
                    }
                }
            }
        }
    }
}

/** Index of the cell covering [t], or the nearest one if [t] falls in a gap. */
private fun targetCell(cells: List<Pair<Long, Long>>, t: Long): Int {
    val hit = cells.indexOfFirst { (s, e) -> t >= s && t < e }
    if (hit >= 0) return hit
    return cells.indices.minByOrNull { i ->
        val (s, e) = cells[i]
        if (t < s) s - t else abs(t - e)
    } ?: -1
}

@Composable
private fun GuideCell(
    x: Dp,
    width: Dp,
    title: String,
    subtitle: String,
    isNow: Boolean,
    isPast: Boolean,
    hasCatchup: Boolean,
    hasReminder: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    FocusBox(
        onClick = onClick,
        onFocused = onFocused,
        modifier = Modifier.offset(x = x).width(width).fillMaxHeight().padding(horizontal = 1.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        shape = RoundedCornerShape(4.dp),
        baseColor = when {
            isNow -> Palette.Selected
            isPast -> Palette.PastCell
            else -> Palette.PanelAlt
        },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) { focused ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasCatchup) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(12.dp), tint = Palette.TextDim)
                    Spacer(Modifier.width(4.dp))
                }
                if (hasReminder) {
                    Icon(Icons.Default.Notifications, "Reminder set", modifier = Modifier.size(12.dp), tint = Palette.Accent)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (isPast && !focused) Palette.TextDim else Palette.Text,
                    fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 11.sp, color = Palette.TextDim, maxLines = 1)
        }
    }
}
