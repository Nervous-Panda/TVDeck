package com.tvdeck.app.car

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.google.android.apps.auto.sdk.CarActivity
import com.tvdeck.app.data.LoadState
import com.tvdeck.app.data.Repo
import com.tvdeck.app.data.Store
import com.tvdeck.app.ui.TVDeckTheme

/**
 * TVDeck's screen on the Android Auto display. A CarActivity isn't a real Activity, so it provides the
 * lifecycle and saved-state owners Compose needs itself, and hosts the Compose UI in a ComposeView.
 */
class TVDeckCarActivity : CarActivity(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    /** Set while a channel is playing; Back returns to the lists instead of leaving the app. */
    internal val playing = mutableStateOf<CarPlayback?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        savedState.performAttach()
        savedState.performRestore(null)
        super.onCreate(savedInstanceState)
        // Settings changes on the car display (day/night etc.) shouldn't recreate the screen.
        setIgnoreConfigChanges(-1)
        runCatching {
            carUiController.statusBarController.hideAppHeader()
            carUiController.menuController.hideMenuButton()
        }.onFailure { Log.w(TAG, "Couldn't hide the Android Auto header", it) }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // The phone app may not have been opened since the phone started: load the lists ourselves.
        Store.activeProfile()?.let { p ->
            if (Repo.currentProfile != p || Repo.state.value is LoadState.Error) Repo.load(this, p)
        }

        val compose = ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(registry))
            setContent { TVDeckTheme { CarApp(this@TVDeckCarActivity) } }
        }
        // Compose looks the owners up from the window's root view (the car SDK's own decor view), not from
        // the ComposeView. A container attaches before its children, so it sets them on the root just in time.
        val host = object : FrameLayout(this) {
            override fun onAttachedToWindow() {
                installOwners(rootView)
                super.onAttachedToWindow()
            }
        }
        installOwners(host)
        host.addView(compose)
        setContentView(host)
    }

    private fun installOwners(v: View) {
        v.setViewTreeLifecycleOwner(this)
        v.setViewTreeViewModelStoreOwner(this)
        v.setViewTreeSavedStateRegistryOwner(this)
    }

    override fun onStart() {
        super.onStart()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        playing.value?.controller?.onForeground()
    }

    override fun onResume() {
        super.onResume()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        // Another Android Auto app took the screen (or the phone was unplugged): stop live streams,
        // as the phone player does, so the provider connection is freed.
        playing.value?.controller?.onBackground()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        stopPlayback()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (playing.value != null) stopPlayback() else super.onBackPressed()
    }

    internal fun stopPlayback() {
        playing.value?.controller?.release()
        playing.value = null
    }

    private companion object {
        const val TAG = "TVDeckCar"
    }
}
