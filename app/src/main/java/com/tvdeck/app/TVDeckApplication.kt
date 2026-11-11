package com.tvdeck.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tvdeck.app.data.Http
import com.tvdeck.app.data.Reminders
import com.tvdeck.app.data.Repo
import com.tvdeck.app.data.Store
import com.tvdeck.app.player.PlayerActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TVDeckApplication : Application(), ImageLoaderFactory {
    /** True while a video is on screen: automatic refreshes wait so they don't compete with the stream. */
    @Volatile private var playerVisible = false
    private var lastAutoCheck = 0L

    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        Store.init(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                Reminders.visibleActivities++
                if (a is PlayerActivity) playerVisible = true
                // Catch up straight away when coming back to the app.
                Reminders.check(this@TVDeckApplication)
                if (a !is PlayerActivity) autoRefresh(force = true)
            }

            override fun onActivityStopped(a: Activity) {
                Reminders.visibleActivities = (Reminders.visibleActivities - 1).coerceAtLeast(0)
                if (a is PlayerActivity) playerVisible = false
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        Reminders.schedule(this)
        MainScope().launch {
            while (true) {
                delay(15_000)
                Reminders.check(this@TVDeckApplication)
                autoRefresh(force = false)
            }
        }
    }

    /** Checks at most every 5 minutes (or on return to the app) whether the lists are due a refresh. */
    private fun autoRefresh(force: Boolean) {
        val now = System.currentTimeMillis()
        // Nothing loaded yet (app just started): check again on the next tick, once it has.
        if (playerVisible || Repo.content.value == null || (!force && now - lastAutoCheck < 5 * 60_000)) return
        lastAutoCheck = now
        Repo.autoRefreshTick(this, now)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { Http.client }
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
}
