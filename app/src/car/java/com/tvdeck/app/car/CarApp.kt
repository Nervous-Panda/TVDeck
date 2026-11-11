package com.tvdeck.app.car

import android.graphics.Color as AndroidColor
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.tvdeck.app.data.LoadState
import com.tvdeck.app.data.Repo
import com.tvdeck.app.data.Store
import com.tvdeck.app.model.Channel
import com.tvdeck.app.model.Profile
import com.tvdeck.app.player.PlayItem
import com.tvdeck.app.player.PlaybackSession
import com.tvdeck.app.player.PlayerController
import com.tvdeck.app.player.Variant
import com.tvdeck.app.ui.Logo
import com.tvdeck.app.ui.Palette
import kotlinx.coroutines.delay

/** A channel playing on the car screen. */
class CarPlayback(val controller: PlayerController)

private const val FAVORITES = "\u0000favorites"

@Composable
internal fun CarApp(a: TVDeckCarActivity) {
    val playing by a.playing
    val p = playing
    if (p != null) CarPlayer(a, p.controller) else CarBrowser(a)
}

// ---------- Channel lists ----------

@Composable
private fun CarBrowser(a: TVDeckCarActivity) {
    val content by Repo.content.collectAsState()
    val state by Repo.state.collectAsState()
    val favs by Store.favorites.collectAsState()
    val c = content
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        when {
            Store.activeProfile() == null ->
                Message("Open TVDeck on your phone and add your provider first.")
            c == null -> when (val s = state) {
                is LoadState.Error -> Message(s.message, button = "Try again") {
                    Store.activeProfile()?.let { Repo.load(a, it, force = true) }
                }
                is LoadState.Loading -> Message(s.message, loading = true)
                else -> Message("Loading channels…", loading = true)
            }
            else -> {
                val favChannels = remember(c, favs) { c.channels.filter { ch -> ch.allKeys.any { it in favs } } }
                val groups = remember(c, favChannels) {
                    (if (favChannels.isNotEmpty()) listOf(FAVORITES) else emptyList()) + c.liveGroups
                }
                var group by remember(groups) { mutableStateOf(groups.firstOrNull()) }
                val channels = remember(c, group, favChannels) {
                    if (group == FAVORITES) favChannels else c.channelsByGroup[group].orEmpty()
                }
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.width(280.dp).fillMaxHeight().background(Palette.Panel),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        items(groups, key = { it }) { g ->
                            val selected = g == group
                            Text(
                                if (g == FAVORITES) "★ Favorites" else g,
                                color = if (selected) Palette.Text else Palette.TextDim,
                                fontSize = 20.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { group = g }
                                    .background(if (selected) Palette.Selected else Color.Transparent)
                                    .padding(horizontal = 20.dp, vertical = 18.dp),
                            )
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(8.dp)) {
                        items(channels, key = { it.key }) { ch ->
                            ChannelRow(ch) { play(a, c.profile, channels, ch) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: Channel, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.PanelAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ch.number > 0) {
            Text("${ch.number}", color = Palette.TextDim, fontSize = 18.sp, modifier = Modifier.width(56.dp))
        }
        Logo(ch.logo, ch.name, Modifier.size(width = 72.dp, height = 48.dp))
        Spacer(Modifier.width(16.dp))
        Text(ch.name, color = Palette.Text, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Message(text: String, loading: Boolean = false, button: String? = null, onButton: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) {
            CircularProgressIndicator(color = Palette.Accent)
            Spacer(Modifier.height(20.dp))
        }
        Text(text, color = Palette.Text, fontSize = 22.sp, textAlign = TextAlign.Center)
        if (button != null) {
            Spacer(Modifier.height(24.dp))
            BigButton(button, onClick = onButton)
        }
    }
}

/** Same playlist the phone builds: the channels of the list it was picked from, combined versions included. */
private fun play(a: TVDeckCarActivity, profile: Profile, list: List<Channel>, ch: Channel) {
    a.stopPlayback()
    PlaybackSession.items = list.map { c ->
        val variants = if (c.alternates.isEmpty()) emptyList()
        else (listOf(c) + c.alternates).map { Variant(it.quality, it.url) }
        PlayItem("live:${c.key}", c.name, c.url, live = true, channel = c, variants = variants)
    }
    PlaybackSession.index = list.indexOfFirst { it.key == ch.key }.coerceAtLeast(0)
    PlaybackSession.userAgent = profile.userAgent
    PlaybackSession.startFromBeginning = false
    val pc = PlayerController(a)
    a.playing.value = CarPlayback(pc)
    pc.start()
}

// ---------- Player ----------

@OptIn(UnstableApi::class)
@Composable
private fun CarPlayer(a: TVDeckCarActivity, pc: PlayerController) {
    LaunchedEffect(pc.infoToken, pc.infoVisible) {
        if (pc.infoVisible) {
            delay(5000)
            if (pc.error == null) pc.infoVisible = false
        }
    }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pc.noticeToken) {
        notice = pc.notice
        delay(4000)
        notice = null
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(pc) { detectTapGestures(onTap = { pc.toggleInfo() }) },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(AndroidColor.BLACK)
                    setKeepContentOnPlayerReset(true)
                    player = pc.player
                    pc.playerView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        val error = pc.error
        when {
            error != null -> Message(error, button = "Try again") { pc.retry() }
            pc.buffering -> CircularProgressIndicator(color = Palette.Accent, modifier = Modifier.align(Alignment.Center).size(56.dp))
        }

        if (pc.infoVisible || error != null) {
            val item = pc.current
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(Icons.AutoMirrored.Filled.ArrowBack) { a.stopPlayback() }
                Spacer(Modifier.width(16.dp))
                val number = item.channel?.number?.takeIf { it > 0 }?.let { "$it  " }.orEmpty()
                Text(
                    number + item.title + pc.variantLabel.takeIf { it.isNotEmpty() }?.let { "  ·  $it" }.orEmpty(),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (pc.items.size > 1) {
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    RoundIcon(Icons.Filled.KeyboardArrowUp) { pc.zap(-1) }
                    RoundIcon(Icons.Filled.KeyboardArrowDown) { pc.zap(1) }
                }
            }
        }

        notice?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
    )
}
