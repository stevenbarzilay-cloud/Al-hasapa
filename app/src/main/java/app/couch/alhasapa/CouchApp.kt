package app.couch.alhasapa

import kotlinx.coroutines.launch
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Bundle
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.FileStateStore
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.diag.CrashReport
import app.couch.alhasapa.diag.Diagnostics
import app.couch.alhasapa.diag.DrawGuard
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.diag.Standby
import app.couch.alhasapa.diag.Startup
import app.couch.alhasapa.diag.Watchdog
import app.couch.alhasapa.remote.RemoteKeys
import app.couch.alhasapa.player.PlayerActivity
import app.couch.alhasapa.tv.TvMode
import java.io.File
import java.lang.ref.WeakReference

/** Owns the two things every screen shares: the state and the catalogue. Created lazily, so tests that never touch them pay nothing. */
class CouchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Startup.mark("application")
        Flight.start(this)
        CrashReport.install(this)
        // Before any WebView: if the WebView got stuck in its profile code last time, profiles stay off on this device (games open).
        app.couch.alhasapa.player.WebProfiles.init(this)
        val screens = Visibility(this)
        registerActivityLifecycleCallbacks(screens)
        visibility = screens
        val tv = TvMode.isTelevision(this)
        // On a TV: the box going to standby closes the game and puts the app behind the home screen (see Standby).
        if (tv) Standby.install(this, screens::alive) { it is MainActivity }
        // 1.8.1's "light drawing" (a software layer) made games crawl on the TV box's processor: it is gone, and so are its marks.
        if (tv) Flight.background { deleteSharedPreferences("drawing") }
        // Not under the unit tests: their main looper is paused on purpose, which a watchdog would take for a frozen app.
        if (Build.FINGERPRINT != "robolectric") {
            val guard = if (tv) drawGuard(screens) else null
            Watchdog(
                onSample = { Flight.log(Diagnostics.memory(this)) },
                onRecover = { ms -> Flight.log("${Watchdog.FREE} after $ms ms"); guard?.onRecover() },
                onStuck = { ms, at -> guard?.onStuck(ms, at) },
            ).start()
        }
        Flight.log(Diagnostics.memory(this))
        if (tv) Flight.log(Diagnostics.screen(this))
        followPieces()
    }

    /**
     * The app's two colours follow the two pieces (1.12), as the hub's page does: now, and whenever the state changes (a piece chosen
     * here, on the other phone, or on the TV, and brought over). Read off the main thread (the start of the app waits for nothing of
     * it), and only the two pieces are looked at, not a copy of the whole state.
     */
    private fun followPieces() {
        val main = android.os.Handler(mainLooper)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            val pieces = runCatching { app.couch.alhasapa.data.PiecesConfig.load(this@CouchApp) }.getOrNull() ?: return@launch
            repo.revision.collect {
                val (a, b) = repo.peek { s -> pieces.styleFor(s, "a") to pieces.styleFor(s, "b") }
                main.post { app.couch.alhasapa.ui.theme.Palette.follow(a, b) }
            }
        }
    }

    /** The app's screens, for the standby and the draw guard (null before onCreate, and in tests that build no app). */
    var visibility: Visibility? = null
        private set

    /** On a TV, a game that holds the drawing stuck is closed (see DrawGuard). */
    private fun drawGuard(screens: Visibility) = DrawGuard(
        front = { screens.front },
        isGame = { it is PlayerActivity },
        afterClose = {
            android.os.Handler(mainLooper).post {
                android.widget.Toast.makeText(this, R.string.draw_closed, android.widget.Toast.LENGTH_LONG).show()
            }
        },
        end = { android.os.Process.killProcess(android.os.Process.myPid()) },
    )

    // Deprecated from Android 14 (it no longer sends these while in front); Android 9, which the Mi Box runs, still does.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The levels that matter while the app is in front: the device is running low and may start closing things.
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            Flight.log("memory pressure (level $level); ${Diagnostics.memory(this)}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        Flight.log("LOW MEMORY; ${Diagnostics.memory(this)}")
        Flight.flush()
    }

    /**
     * Which of the app's screens are showing: the flight recorder's "was it in front when it ended", and on a TV the phone-as-keyboard
     * link, which listens only while the app is in front.
     */
    class Visibility(private val app: CouchApp) : ActivityLifecycleCallbacks {
        private var started = 0
        private val tv = TvMode.isTelevision(app)
        private val live = ArrayList<WeakReference<Activity>>()

        /** The screen in front (resumed), read from other threads. */
        @Volatile var front: Activity? = null
            private set

        /** The screens that exist and are not closing, oldest first. */
        fun alive(): List<Activity> = synchronized(live) { live.mapNotNull { it.get() } }.filter { !it.isFinishing }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            synchronized(live) { live.removeAll { it.get() == null }; live += WeakReference(activity) }
            Flight.log("${activity.javaClass.simpleName} created")
        }
        override fun onActivityStarted(activity: Activity) {
            started++
            Flight.session(true, activity.javaClass.simpleName)
            if (tv && started == 1 && Build.FINGERPRINT != "robolectric") RemoteKeys.foreground(app, true)
        }
        override fun onActivityResumed(activity: Activity) { front = activity; Flight.log("${activity.javaClass.simpleName} in front") }
        override fun onActivityPaused(activity: Activity) {
            if (front === activity) front = null
            Flight.log("${activity.javaClass.simpleName} paused")
        }
        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) {
                Flight.session(false, activity.javaClass.simpleName); Flight.log("in the background")
                if (tv) RemoteKeys.foreground(app, false)
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            synchronized(live) { live.removeAll { it.get() == null || it.get() === activity } }
            Flight.log("${activity.javaClass.simpleName} closed")
        }
    }

    val repo: StateRepository by lazy {
        // The first thing done with the state: what a new catalogue asks of the choices made here before (see CatalogUpdate).
        StateRepository(FileStateStore(File(filesDir, "state.json"))).also { app.couch.alhasapa.catalog.CatalogUpdate.apply(this, it) }
            // A device from before 2.0 is given its couch (its data stays as it is; a new device chooses on its first screen).
            .also { runCatching { app.couch.alhasapa.remote.CouchSetup.migrate(this, it, TvMode.isTelevision(this)) } }
            // A change made here goes to the other devices (1.12): the ones connected at once, and through the relay from anywhere.
            .also { it.onLocalChange = { app.couch.alhasapa.remote.ShareBus.changed(); app.couch.alhasapa.remote.RelayOut.schedule(this) } }
            .also { r -> if (TvMode.isTelevision(this)) followGuests(r) }
    }

    /**
     * On a TV (2.0): what the home's own phones bring (a game one of them played alone, through the relay) changes the contests the
     * guests connected just now may see, so they are told too.
     */
    private fun followGuests(r: StateRepository) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            r.merged.collect { if (it > 0) RemoteKeys.server?.stateChanged() }
        }
    }

    private val base: Catalog by lazy { Catalog.load(this) }
    private var catalogRevision = -1L
    private var catalogCache: Catalog? = null

    /** The built-in games plus the ones added in the settings page. Rebuilt only when the state changes. */
    val catalog: Catalog
        get() = synchronized(this) {
            val rev = repo.currentRevision()
            val cached = catalogCache
            if (cached != null && rev == catalogRevision) return cached
            base.withCustom(repo.snapshot().optJSONArray("custom")).also { catalogCache = it; catalogRevision = rev }
        }
}
