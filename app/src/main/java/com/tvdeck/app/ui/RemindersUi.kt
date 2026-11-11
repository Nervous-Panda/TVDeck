package com.tvdeck.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.tvdeck.app.data.Reminders
import com.tvdeck.app.data.Repo
import com.tvdeck.app.data.Store
import com.tvdeck.app.model.Reminder
import kotlinx.coroutines.delay

/** "Starting now" popup for reminders that fire while the app is on screen. */
@Composable
fun ReminderPopup(onWatch: (Reminder) -> Unit) {
    val due by Reminders.due.collectAsState()
    val r = due.firstOrNull() ?: return
    val fr = remember(r.id) { FocusRequester() }
    LaunchedEffect(r.id) {
        runCatching { fr.requestFocus() }
        // Nobody watching: don't leave it on screen for the rest of the programme.
        delay(5 * 60_000)
        Reminders.dismiss(r)
    }
    val now = rememberNow(15_000)
    AlertDialog(
        onDismissRequest = { Reminders.dismiss(r) },
        title = { Text(r.title) },
        text = {
            Text(
                "${if (r.start > now) "Starts at" else "Started at"} ${fmtTime(r.start)} on ${r.channelName}.",
                fontSize = 15.sp,
            )
        },
        confirmButton = {
            TextButton(onClick = { onWatch(r) }, modifier = Modifier.focusRequester(fr)) { Text("Watch") }
        },
        dismissButton = { TextButton(onClick = { Reminders.dismiss(r) }) { Text("Dismiss") } },
        containerColor = Palette.Panel,
    )
}

/** Switches to the channel of a reminder the user chose to watch, loading its profile first if needed. */
@Composable
fun ReminderWatcher(nav: Nav) {
    val ctx = LocalContext.current
    val watch by Reminders.watch.collectAsState()
    val content by Repo.content.collectAsState()
    val state by Repo.state.collectAsState()
    LaunchedEffect(watch, content, state) {
        val r = watch ?: return@LaunchedEffect
        val profile = Store.profiles.value.firstOrNull { it.id == r.profileId }
        if (profile == null) {
            Reminders.watch.value = null
            return@LaunchedEffect
        }
        if (Store.activeId.value != profile.id) {
            Store.setActive(profile.id)
            nav.reset(Route.Home)
        }
        if (Repo.currentProfile?.id != profile.id) {
            Repo.load(ctx, profile)
            return@LaunchedEffect
        }
        if (state is com.tvdeck.app.data.LoadState.Error) {
            Reminders.watch.value = null
            return@LaunchedEffect
        }
        val c = content?.takeIf { it.profile.id == profile.id } ?: return@LaunchedEffect
        Reminders.watch.value = null
        val ch = c.channelByKey[r.channelKey] ?: c.channels.firstOrNull { it.name == r.channelName || it.guideName == r.channelName }
        if (ch == null) {
            Toast.makeText(ctx, "${r.channelName} isn't in your channel list any more", Toast.LENGTH_LONG).show()
        } else {
            Launch.live(ctx, c.profile, c.channelsByGroup[ch.group] ?: c.channels, ch)
        }
    }
}

@Composable
fun RemindersScreen(nav: Nav) {
    val ctx = LocalContext.current
    val all by Store.reminders.collectAsState()
    val profiles by Store.profiles.collectAsState()
    val fr = rememberInitialFocus()
    val multi = profiles.size > 1

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp)) {
        Text("Reminders", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Set a reminder from the TV guide: choose a programme that hasn't started and press Remind me.",
            fontSize = 14.sp, color = Palette.TextDim, modifier = Modifier.widthIn(max = 640.dp),
        )
        Spacer(Modifier.height(16.dp))
        if (all.isEmpty()) {
            Text("No reminders set.", color = Palette.TextDim)
            Spacer(Modifier.height(16.dp))
            ActionButton("Back", Icons.Default.Check, Modifier.focusRequester(fr)) { nav.pop() }
            return@Column
        }
        LazyColumn(Modifier.weight(1f, fill = false).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(all, key = { _, r -> r.id }) { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val who = if (multi) " · " + (profiles.firstOrNull { it.id == r.profileId }?.name ?: "") else ""
                        Text("${fmtDay(r.start)} ${fmtTime(r.start)} · ${r.channelName}$who", fontSize = 13.sp, color = Palette.TextDim, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    ActionButton("Cancel", Icons.Default.Close, if (i == 0) Modifier.focusRequester(fr) else Modifier) {
                        Reminders.cancel(ctx, r.id)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        ActionButton("Back", Icons.Default.Check) { nav.pop() }
    }
}
