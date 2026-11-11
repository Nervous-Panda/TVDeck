package com.tvdeck.app.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.tvdeck.app.data.Epg
import com.tvdeck.app.data.Repo
import com.tvdeck.app.data.Store
import com.tvdeck.app.ui.ActionButton
import com.tvdeck.app.ui.FocusBox
import com.tvdeck.app.ui.Logo
import com.tvdeck.app.ui.Palette
import com.tvdeck.app.ui.ProgressBar
import com.tvdeck.app.ui.ReminderPopup
import com.tvdeck.app.data.Reminders
import com.tvdeck.app.ui.TVDeckTheme
import com.tvdeck.app.ui.fmtTime
import com.tvdeck.app.ui.rememberIsTv
import com.tvdeck.app.ui.rememberNow
import kotlinx.coroutines.delay

class PlayerActivity : ComponentActivity() {
    private var pc: PlayerController? = null
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PlaybackSession.items.isEmpty()) {
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val controller = PlayerController(this)
        pc = controller
        controller.start()
        setContent { TVDeckTheme { PlayerScreen(controller, onExit = { finish() }) } }
    }

    override fun onStart() {
        super.onStart()
        if (started) pc?.onForeground()
        started = true
    }

    override fun onStop() {
        super.onStop()
        pc?.onBackground()
    }

    override fun onDestroy() {
        super.onDestroy()
        pc?.release()
        pc = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pc?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(pc: PlayerController, onExit: () -> Unit) {
    val item = pc.current
    val epg by Repo.epg.collectAsState()
    val favs by Store.favorites.collectAsState()
    val troubleshooting by Store.troubleshooting.collectAsState()
    val now = rememberNow(15_000)

    LaunchedEffect(pc.finished) { if (pc.finished) onExit() }
    LaunchedEffect(pc.infoToken) {
        if (pc.infoVisible) {
            delay(6000)
            if (!pc.channelListOpen) pc.infoVisible = false
        }
    }
    BackHandler(enabled = pc.channelListOpen) { pc.channelListOpen = false }
    // Watching a reminder's channel: leave the player; the main screen opens the channel.
    ReminderPopup(onWatch = {
        Reminders.startWatching(it)
        onExit()
    })

    // The player has no themed Surface behind it, so text without an explicit colour would default to
    // black (unreadable on the dark overlays). Make light text the default for everything on this screen.
    CompositionLocalProvider(LocalContentColor provides Color.White) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    player = pc.player
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    controllerShowTimeoutMs = 4000
                    useController = !pc.current.live
                    pc.playerView = this
                }
            },
            update = { v ->
                v.useController = !item.live
                if (item.live) v.hideController()
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (pc.buffering && pc.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Palette.Accent)
        }

        if (item.live) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { pc.toggleInfo() })
                    }
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -120f) pc.zap(1) else if (total > 120f) pc.zap(-1)
                            },
                        ) { _, dy -> total += dy }
                    }
            )
            if (pc.infoVisible) LiveInfoBar(pc, epg, now, favs, Modifier.align(Alignment.BottomCenter))
        } else if (pc.infoVisible) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                Text(item.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                if (item.subtitle.isNotEmpty()) Text(item.subtitle, fontSize = 14.sp, color = Palette.TextDim, maxLines = 1)
            }
        }

        if (pc.channelListOpen) ChannelListOverlay(pc, epg, now)

        if (pc.numberEntry.isNotEmpty()) {
            Text(
                pc.numberEntry,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        pc.error?.let { ErrorOverlay(it, onRetry = { pc.retry() }, onExit = onExit) }

        if (troubleshooting) DiagnosticStrip(pc, Modifier.align(Alignment.BottomCenter))

        // Brief note after switching to another quality version (or retrying after a connection limit).
        var noticeShown by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(pc.noticeToken) {
            noticeShown = pc.notice
            if (noticeShown != null) {
                delay(5000)
                noticeShown = null
            }
        }
        noticeShown?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = if (troubleshooting) 64.dp else 16.dp)
                    .widthIn(max = 420.dp)
                    .background(Color(0xE6202634), RoundedCornerShape(6.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    }
}

/** Troubleshooting mode: technical reason for failures / long connection attempts, at the bottom of the screen. */
@Composable
private fun DiagnosticStrip(pc: PlayerController, modifier: Modifier) {
    // Also report streams that hang without ever erroring (the endless spinner case).
    var stalledSec by remember { mutableIntStateOf(0) }
    LaunchedEffect(pc.buffering, pc.index) {
        stalledSec = 0
        if (pc.buffering) {
            while (true) {
                delay(1000)
                stalledSec++
            }
        }
    }
    val text = pc.diagnostic
        ?: if (pc.buffering && pc.error == null && stalledSec >= 8) {
            "Still connecting after ${stalledSec}s — the server hasn't sent playable data yet (no error reported)."
        } else null
    if (text == null) return
    Text(
        "⚠ $text",
        fontSize = 12.sp,
        color = Color(0xFFFFD27A),
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xE6000000))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun LiveInfoBar(pc: PlayerController, epg: Epg?, now: Long, favs: Set<String>, modifier: Modifier) {
    val item = pc.current
    val ch = item.channel
    val isTv = rememberIsTv()
    val cur = if (ch != null) epg?.current(ch, now) else null
    val next = if (ch != null) epg?.next(ch, now) else null
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
            .padding(start = 32.dp, end = 32.dp, top = 56.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ch != null) {
                Text(ch.number.toString(), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                Spacer(Modifier.width(16.dp))
                Logo(ch.logo, ch.name, Modifier.size(84.dp, 56.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    val q = pc.variantLabel
                    if (q.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            q, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.background(Palette.Accent, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                if (cur != null) {
                    Text(
                        "${fmtTime(cur.start)} – ${fmtTime(cur.stop)}   ${cur.title}",
                        fontSize = 15.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(5.dp))
                    ProgressBar(
                        (now - cur.start).toFloat() / (cur.stop - cur.start).coerceAtLeast(1),
                        Modifier.fillMaxWidth(0.6f),
                    )
                } else {
                    Text(if (epg == null) "Live" else "No programme information", fontSize = 14.sp, color = Palette.TextDim)
                }
                if (next != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Next  ${fmtTime(next.start)}  ${next.title}", fontSize = 13.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtTime(now), fontSize = 18.sp, color = Color.White)
                if (!isTv) {
                    Row {
                        IconButton(onClick = { pc.zap(-1) }) { Icon(Icons.Default.KeyboardArrowUp, "Previous channel", tint = Color.White) }
                        IconButton(onClick = { pc.zap(1) }) { Icon(Icons.Default.KeyboardArrowDown, "Next channel", tint = Color.White) }
                        IconButton(onClick = { pc.openChannelList() }) { Icon(Icons.AutoMirrored.Filled.List, "Channel list", tint = Color.White) }
                        if (ch != null) {
                            val fav = ch.key in favs
                            IconButton(onClick = { Store.toggleFavorite(ch.key) }) {
                                Icon(if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite", tint = if (fav) Palette.Live else Color.White)
                            }
                        }
                    }
                }
            }
        }
        if (isTv) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${pc.index + 1}/${pc.items.size}   ▲▼ change channel   OK / ◀ channel list   0-9 jump to number",
                fontSize = 11.sp, color = Palette.TextDim,
            )
        }
    }
}

@Composable
private fun ChannelListOverlay(pc: PlayerController, epg: Epg?, now: Long) {
    val listState = rememberLazyListState()
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        listState.scrollToItem((pc.index - 4).coerceAtLeast(0))
        delay(80)
        runCatching { fr.requestFocus() }
    }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(380.dp)
                .fillMaxHeight()
                .background(Color(0xF2101318))
                .padding(12.dp)
        ) {
            Text("Channels", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(pc.items) { i, entry ->
                    val ch = entry.channel
                    FocusBox(
                        onClick = {
                            pc.channelListOpen = false
                            pc.play(i)
                        },
                        selected = i == pc.index,
                        modifier = Modifier.fillMaxWidth().then(if (i == pc.index) Modifier.focusRequester(fr) else Modifier),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text((ch?.number ?: (i + 1)).toString(), fontSize = 13.sp, color = Palette.TextDim, modifier = Modifier.width(40.dp))
                            Logo(ch?.logo, entry.title, Modifier.size(48.dp, 32.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(entry.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val cur = if (ch != null) epg?.current(ch, now) else null
                                if (cur != null) Text(cur.title, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(Unit) { detectTapGestures { pc.channelListOpen = false } }
        )
    }
}

@Composable
private fun ErrorOverlay(message: String, onRetry: () -> Unit, onExit: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(message) {
        delay(80)
        runCatching { fr.requestFocus() }
    }
    Box(Modifier.fillMaxSize().background(Color(0xD9000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp).padding(24.dp)) {
            Text("Can't play this stream", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 15.sp, color = Palette.TextDim, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton("Retry", Icons.Default.Refresh, Modifier.focusRequester(fr)) { onRetry() }
                ActionButton("Back", Icons.AutoMirrored.Filled.ArrowBack) { onExit() }
            }
        }
    }
}
