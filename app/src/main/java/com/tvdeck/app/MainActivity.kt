package com.tvdeck.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tvdeck.app.data.Reminders
import com.tvdeck.app.ui.TVDeckApp
import com.tvdeck.app.ui.TVDeckTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { TVDeckTheme { TVDeckApp() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Opened from a reminder notification: switch to the programme's channel. */
    private fun handle(intent: Intent?) {
        Reminders.decode(intent?.getStringArrayExtra(Reminders.EXTRA_REMINDER))?.let { Reminders.watch.value = it }
    }
}
