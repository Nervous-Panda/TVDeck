package com.tvdeck.app.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.AlarmManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tvdeck.app.MainActivity
import com.tvdeck.app.R
import com.tvdeck.app.model.Reminder
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.DateFormat
import java.util.Date

/**
 * Programme reminders. While the app is on screen a popup offers to switch to the channel; otherwise
 * a notification is posted. A single alarm, always set for the next reminder, wakes the app when it
 * isn't running.
 */
object Reminders {
    /** Shown slightly before the start so there's time to switch. */
    const val LEAD_MS = 60_000L
    private const val CHANNEL_ID = "reminders"
    const val EXTRA_REMINDER = "com.tvdeck.app.REMINDER"

    /** Reminders that have fired while the app was on screen, oldest first, waiting for the popup. */
    val due = MutableStateFlow<List<Reminder>>(emptyList())

    /** A reminder the user chose to watch; the main screen switches to its channel once it's loaded. */
    val watch = MutableStateFlow<Reminder?>(null)

    /** Number of started activities, to tell whether the app is on screen. */
    @Volatile var visibleActivities = 0

    fun add(ctx: Context, r: Reminder) {
        Store.addReminder(r)
        schedule(ctx)
    }

    fun cancel(ctx: Context, id: String) {
        Store.removeReminders(setOf(id))
        due.value = due.value.filter { it.id != id }
        schedule(ctx)
    }

    fun isSet(profileId: String, channelKey: String, start: Long) =
        Store.reminders.value.any { it.profileId == profileId && it.channelKey == channelKey && it.start == start }

    /** Fires every reminder that is due and drops the ones whose programme has ended. */
    @Synchronized
    fun check(ctx: Context, now: Long = System.currentTimeMillis()) {
        val all = Store.reminders.value
        val ended = all.filter { it.stop <= now }
        val fire = all.filter { it.stop > now && it.start - LEAD_MS <= now }
        if (ended.isNotEmpty() || fire.isNotEmpty()) {
            Store.removeReminders((ended + fire).mapTo(HashSet()) { it.id })
        }
        if (fire.isNotEmpty()) {
            if (visibleActivities > 0) due.value = due.value + fire
            else fire.forEach { notify(ctx, it) }
        }
        schedule(ctx)
    }

    fun dismiss(r: Reminder) {
        due.value = due.value.filter { it.id != r.id }
    }

    fun startWatching(r: Reminder) {
        dismiss(r)
        watch.value = r
    }

    /** Sets the alarm for the next reminder, or cancels it when there's none. */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            app, 0, Intent(app, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val next = Store.reminders.value.minOfOrNull { it.start - LEAD_MS }
        if (next == null) {
            am.cancel(pi)
            return
        }
        val at = maxOf(next, System.currentTimeMillis() + 1_000)
        if (AlarmManagerCompat.canScheduleExactAlarms(am)) {
            AlarmManagerCompat.setExactAndAllowWhileIdle(am, AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Without the exact-alarm permission, ask for a delivery within a minute of the time.
            am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, pi)
        }
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notify(ctx: Context, r: Reminder) {
        val app = ctx.applicationContext
        if (!canNotify(app)) return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Programme reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_REMINDER, encode(r))
        val pi = PendingIntent.getActivity(app, r.id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.start))
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(r.title)
            .setContentText("Starts at $time on ${r.channelName}. Tap to watch.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(app).notify(r.id.hashCode(), n)
        } catch (e: SecurityException) {
            // Permission withdrawn in the meantime.
        }
    }

    fun encode(r: Reminder): Array<String> =
        arrayOf(r.profileId, r.channelKey, r.channelName, r.title, r.start.toString(), r.stop.toString())

    fun decode(a: Array<String>?): Reminder? {
        if (a == null || a.size != 6) return null
        return Reminder(a[0], a[1], a[2], a[3], a[4].toLongOrNull() ?: return null, a[5].toLongOrNull() ?: return null)
    }
}

/** Woken by the reminder alarm, and after a reboot or app update to set the alarm again. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Reminders.check(context)
    }
}
