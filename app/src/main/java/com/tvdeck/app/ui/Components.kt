package com.tvdeck.app.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Palette {
    val Bg = Color(0xFF0B0E13)
    val Panel = Color(0xFF141922)
    val PanelAlt = Color(0xFF1D2430)
    val PastCell = Color(0xFF10141B)
    val Focus = Color(0xFF2F6FED)
    val Selected = Color(0xFF223250)
    val Text = Color(0xFFE8ECF2)
    val TextDim = Color(0xFF9AA4B2)
    val Accent = Color(0xFF4C8DFF)
    val Live = Color(0xFFE5484D)
}

@Composable
fun TVDeckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            background = Palette.Bg,
            surface = Palette.Panel,
            surfaceVariant = Palette.PanelAlt,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
            onSurfaceVariant = Palette.TextDim,
            error = Palette.Live,
        ),
        content = content,
    )
}

// ---------- Formatting ----------

private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

fun fmtTime(ms: Long): String = timeFormat.format(Date(ms))
fun fmtDay(ms: Long): String = dayFormat.format(Date(ms))
fun fmtDate(ms: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(ms))

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

fun initials(name: String): String =
    name.split(' ', '-', '_', '.', ':', '|')
        .filter { it.isNotBlank() && it[0].isLetterOrDigit() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }

fun hostOf(url: String): String =
    runCatching { java.net.URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url.take(40)

// ---------- Helpers ----------

@Composable
fun rememberNow(periodMs: Long = 30_000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun rememberIsTv(): Boolean {
    val ctx = LocalContext.current
    return remember { ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
}

/** A focus requester that grabs focus when the screen opens — on TVs only, so phones don't show a highlight. */
@Composable
fun rememberInitialFocus(): FocusRequester {
    val fr = remember { FocusRequester() }
    val tv = rememberIsTv()
    LaunchedEffect(Unit) {
        if (tv) {
            delay(150)
            runCatching { fr.requestFocus() }
        }
    }
    return fr
}

// ---------- Building blocks ----------

/**
 * The core D-pad friendly element: focusable, clickable (OK / Enter / tap), optional long press,
 * with a clear highlight when it has remote focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(8.dp),
    baseColor: Color = Color.Transparent,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    // A focus listener rather than collecting interactions: lists create thousands of these while
    // scrolling, and each collector was a coroutine of its own.
    var focused by remember { mutableStateOf(false) }
    if (onFocused != null) {
        val onFocusedState by rememberUpdatedState(onFocused)
        // Deferred to after composition, as callers (the guide's D-pad handling) rely on that order.
        LaunchedEffect(focused) { if (focused) onFocusedState() }
    }
    val bg = when {
        focused -> Palette.Focus
        selected -> Palette.Selected
        else -> baseColor
    }
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(contentPadding)
    ) {
        content(this, focused)
    }
}

@Composable
fun ActionButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick,
        modifier = modifier,
        baseColor = Palette.PanelAlt,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = text.ifEmpty { null }, modifier = Modifier.size(18.dp))
            if (icon != null && text.isNotEmpty()) Spacer(Modifier.width(6.dp))
            if (text.isNotEmpty()) Text(text, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusBox(
        onClick = onClick,
        modifier = modifier,
        selected = selected,
        shape = RoundedCornerShape(50),
        baseColor = Palette.PanelAlt,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, fontSize = 13.sp, maxLines = 1, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun Logo(url: String?, name: String, modifier: Modifier = Modifier) {
    var loaded by remember(url) { mutableStateOf(false) }
    var broken by remember(url) { mutableStateOf(BrokenImages.has(url)) }
    val ctx = LocalContext.current
    // Channel lists show many logos at once, so skip the fade used for posters.
    val request = remember(url) { ImageRequest.Builder(ctx).data(url).crossfade(false).build() }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Palette.PanelAlt), contentAlignment = Alignment.Center) {
        if (!loaded) Text(initials(name), fontSize = 12.sp, color = Palette.TextDim, maxLines = 1)
        if (!url.isNullOrBlank() && !broken) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                onError = { broken = BrokenImages.check(url, it.result.throwable) },
                modifier = Modifier.fillMaxSize().padding(3.dp),
            )
        }
    }
}

/**
 * Image links the server answered with an error (404 and the like). Providers often have many, and
 * without this every row scrolled back into view would request them again.
 */
object BrokenImages {
    private val urls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun has(url: String?) = url != null && url in urls

    /** Remembers [url] when the failure came from the server; network blips are tried again later. */
    fun check(url: String, error: Throwable): Boolean {
        if (error !is coil.network.HttpException) return false
        urls += url
        return true
    }
}

@Composable
fun Poster(url: String?, name: String, modifier: Modifier = Modifier) {
    var loaded by remember(url) { mutableStateOf(false) }
    var broken by remember(url) { mutableStateOf(BrokenImages.has(url)) }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Palette.PanelAlt), contentAlignment = Alignment.Center) {
        if (!loaded) Text(initials(name), fontSize = 22.sp, color = Palette.TextDim)
        if (!url.isNullOrBlank() && !broken) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { broken = BrokenImages.check(url, it.result.throwable) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

private const val GROUP_SELECT_DELAY_MS = 200L

/**
 * Vertical category list used on wide (TV / tablet) layouts. Focusing a group highlights it at once
 * and selects it after a short pause, so moving past groups doesn't build each one's contents.
 */
@Composable
fun GroupList(groups: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = groups.indexOf(selected).coerceAtLeast(0))
    val onSelectNow by rememberUpdatedState(onSelect)
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending) {
        val g = pending ?: return@LaunchedEffect
        delay(GROUP_SELECT_DELAY_MS)
        onSelectNow(g)
        pending = null
    }
    LazyColumn(
        modifier
            .fillMaxHeight()
            .background(Palette.Panel)
            .onPreviewKeyEvent { e ->
                // Pressing right mid-pause opens the highlighted group rather than moving into the old list.
                val g = pending
                if (g != null && e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                    pending = null
                    onSelectNow(g)
                    true
                } else {
                    false
                }
            },
        state = state,
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(groups, key = { it }) { g ->
            FocusBox(
                onClick = {
                    pending = null
                    onSelectNow(g)
                },
                onFocused = { pending = if (g == selected) null else g },
                selected = g == (pending ?: selected),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(g, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Horizontal category chips used on phones. */
@Composable
fun GroupChips(groups: List<String>, selected: String, onSelect: (String) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = groups.indexOf(selected).coerceAtLeast(0))
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it }) { g -> Chip(g, g == selected) { onSelect(g) } }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = Palette.TextDim,
        modifier = modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

@Composable
fun LoadingView(message: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Palette.Accent)
        Spacer(Modifier.height(16.dp))
        Text(message, color = Palette.TextDim)
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, onEdit: () -> Unit, onProfiles: () -> Unit) {
    val fr = rememberInitialFocus()
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Couldn't load your provider", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Palette.TextDim, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionButton("Retry", Icons.Default.Refresh, Modifier.focusRequester(fr)) { onRetry() }
            ActionButton("Edit profile", Icons.Default.Edit) { onEdit() }
            ActionButton("Profiles", Icons.Default.Person) { onProfiles() }
        }
    }
}

@Composable
fun EmptyText(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, color = Palette.TextDim, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
    }
}
