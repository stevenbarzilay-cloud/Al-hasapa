package app.couch.alhasapa.tv

import android.view.KeyEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlin.math.roundToInt
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * One per activity that shows screens on a TV: owns the [TvController], gets every key first, and wraps the screen in the TV
 * layer. On a phone it does nothing: keys and screens pass straight through.
 *
 *     private val tv = TvHost(this)          // in onCreate, after super.onCreate
 *     setContent { tv.Layer { ... } }
 */
class TvHost(private val activity: ComponentActivity) {
    val enabled: Boolean = TvMode.isTelevision(activity)
    val controller = TvController(enabled, activity.resources.displayMetrics.density).also { c ->
        val prefs = activity.getSharedPreferences("tv", android.content.Context.MODE_PRIVATE)
        c.slim = prefs.getBoolean("slim", false)
        c.onSlimChanged = { prefs.edit().putBoolean("slim", it).apply() }
        // The TV's settings (see TvSettingsPanel), the same for every screen of the app on this TV.
        c.pictureScale = prefs.getInt(PICTURE, 100) / 100f
        c.uiScale = prefs.getInt(UI, 100) / 100f
        c.cursorShape = CursorShape.of(prefs.getString(CURSOR, null))
        c.cursorSize = CursorSize.of(prefs.getString(CURSOR_SIZE, null))
        c.onLookChanged = {
            prefs.edit {
                putInt(PICTURE, (c.pictureScale * 100).roundToInt()); putInt(UI, (c.uiScale * 100).roundToInt())
                putString(CURSOR, c.cursorShape.id); putString(CURSOR_SIZE, c.cursorSize.id)
            }
        }
    }

    /** The first of us's colour (the interface's), for the plain arrow cursor; read again when the state changes. */
    private val couch = activity.application as? app.couch.alhasapa.CouchApp
    private val pieces by lazy { app.couch.alhasapa.data.PiecesConfig.load(activity) }

    init {
        // Keys are taken at the window, before the focused view (a WebView would eat the arrows). Not an Activity override:
        // androidx marks ComponentActivity.dispatchKeyEvent as restricted.
        if (enabled) {
            // Where touches (the paw's) are injected; set now, so the phone's touchpad works before the remote's first key.
            controller.decor = activity.window.decorView
            val original = activity.window.callback
            activity.window.callback = KeyInterceptCallback(original) { controller.decor = activity.window.decorView; controller.handleKey(it) }
            keepDisplayMode()
            // The picture of the cats is the starting window's (what shows while the app starts). Once the screen draws itself it covers
            // the whole window, so the window behind it becomes a plain colour: nothing extra to paint under every frame of a game.
            activity.window.decorView.post { runCatching { activity.window.setBackgroundDrawableResource(app.couch.alhasapa.R.color.cream) } }
        }
        activity.lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE || e == Lifecycle.Event.ON_DESTROY) controller.release()
            // The phone-as-keyboard types into the screen in front.
            if (enabled && e == Lifecycle.Event.ON_RESUME) app.couch.alhasapa.remote.RemoteKeys.attach(controller)
            if (enabled && (e == Lifecycle.Event.ON_PAUSE || e == Lifecycle.Event.ON_DESTROY)) app.couch.alhasapa.remote.RemoteKeys.detach(controller)
        })
    }

    /**
     * The picture's mode (its size and rate) stays what it is while this screen is in front. A page that plays video can make a TV box
     * change the mode to the video's rate, which blanks the TV for seconds over HDMI, and some boxes do not come back from it cleanly.
     * A precaution (the box that this app is used on froze its picture once and wakes badly from standby; neither is explained): asking
     * for the mode that is already on changes nothing by itself.
     */
    private fun keepDisplayMode() {
        runCatching {
            @Suppress("DEPRECATION") // the window's display, on every version the TV build runs on
            val mode = activity.windowManager.defaultDisplay.mode
            activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = mode.modeId }
        }.onFailure { app.couch.alhasapa.diag.Flight.log("tv: could not keep the display mode: $it") }
    }

    /** The name of this screen for the phone's status line. */
    var title: String
        get() = controller.screenTitle
        set(value) { controller.screenTitle = value; if (app.couch.alhasapa.remote.RemoteKeys.front === controller) app.couch.alhasapa.remote.RemoteKeys.server?.screen = value }

    @Composable
    fun Layer(content: @Composable () -> Unit) {
        val app = couch
        if (enabled && app != null) {
            val revision by app.repo.revision.collectAsState()
            LaunchedEffect(revision) {
                runCatching { controller.accent = pieces.styleFor(app.repo.snapshot(), "a").accent }
            }
        }
        TvLayer(controller, content)
    }

    companion object {
        const val PICTURE = "picture"
        const val UI = "ui"
        const val CURSOR = "cursor"
        const val CURSOR_SIZE = "cursor_size"
    }
}

/** The window's callback with the TV remote in front: the controller sees every key first, and anything it does not use goes on as usual. */
private class KeyInterceptCallback(private val original: Window.Callback, private val intercept: (KeyEvent) -> Boolean) : Window.Callback by original {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = intercept(event) || original.dispatchKeyEvent(event)
}
