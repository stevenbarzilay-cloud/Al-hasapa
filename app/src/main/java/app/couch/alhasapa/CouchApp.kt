package app.couch.alhasapa

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
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.diag.Startup
import app.couch.alhasapa.diag.Watchdog
import app.couch.alhasapa.remote.RemoteKeys
import app.couch.alhasapa.tv.TvMode
import java.io.File

/** Owns the two things every screen shares: the state and the catalogue. Created lazily, so tests that never touch them pay nothing. */
class CouchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Startup.mark("application")
        Flight.start(this)
        CrashReport.install(this)
        // Before any WebView: if the WebView got stuck in its profile code last time, profiles stay off on this device (games open).
        app.couch.alhasapa.player.WebProfiles.init(this)
        registerActivityLifecycleCallbacks(Visibility(this))
        // Not under the unit tests: their main looper is paused on purpose, which a watchdog would take for a frozen app.
        if (Build.FINGERPRINT != "robolectric") {
            Watchdog(onSample = { Flight.log(Diagnostics.memory(this)) }).start()
        }
        Flight.log(Diagnostics.memory(this))
    }

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
        Flight.flushNow()
    }

    /**
     * Which of the app's screens are showing: the flight recorder's "was it in front when it ended", and on a TV the phone-as-keyboard
     * link, which listens only while the app is in front.
     */
    private class Visibility(private val app: CouchApp) : ActivityLifecycleCallbacks {
        private var started = 0
        private val tv = TvMode.isTelevision(app)

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Flight.log("${activity.javaClass.simpleName} created")
        override fun onActivityStarted(activity: Activity) {
            started++
            Flight.session(true, activity.javaClass.simpleName)
            if (tv && started == 1 && Build.FINGERPRINT != "robolectric") RemoteKeys.foreground(app, true)
        }
        override fun onActivityResumed(activity: Activity) = Flight.log("${activity.javaClass.simpleName} in front")
        override fun onActivityPaused(activity: Activity) = Flight.log("${activity.javaClass.simpleName} paused")
        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) {
                Flight.session(false, activity.javaClass.simpleName); Flight.log("in the background")
                if (tv) RemoteKeys.foreground(app, false)
            }
        }
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) = Flight.log("${activity.javaClass.simpleName} closed")
    }

    val repo: StateRepository by lazy { StateRepository(FileStateStore(File(filesDir, "state.json"))) }

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
