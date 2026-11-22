package app.couch.alhasapa

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.LifecycleEventObserver
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.couch.alhasapa.accounts.AccountsActivity
import app.couch.alhasapa.keyboard.KeyboardModeActivity
import app.couch.alhasapa.bridge.HubBridge
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.player.PlayerActivity
import app.couch.alhasapa.ui.WhoPlaysDialog
import app.couch.alhasapa.diag.CrashReport
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.diag.Diagnostics
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.diag.Startup
import app.couch.alhasapa.ui.CrashScreen
import app.couch.alhasapa.diag.DiagOverlay
import app.couch.alhasapa.tv.TvHost
import app.couch.alhasapa.ui.AppRoot
import app.couch.alhasapa.ui.animationsRemoved
import app.couch.alhasapa.ui.theme.AlHasapaTheme

class MainActivity : ComponentActivity() {
    private lateinit var tv: TvHost


    /** The head-to-head game waiting for "who plays?" to be answered. */
    private var whoPlaysFor by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must come before super.onCreate. The system splash is the first instants of a cold start;
        // the animated in-app splash continues from it.
        Startup.mark("activity created")
        installSplashScreen()
        super.onCreate(savedInstanceState)
        tv = TvHost(this)
        tv.title = getString(R.string.remote_screen_home)
        Diag.log("activity: onCreate, build ${BuildConfig.VERSION_NAME}, android ${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT})")
        Diag.log("webview package: " + (WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "NONE"))
        val dm = resources.displayMetrics
        Diag.log("screen ${dm.widthPixels}x${dm.heightPixels} density ${dm.density}, animator scale ${Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)}")
        lifecycle.addObserver(LifecycleEventObserver { _, event -> Diag.log("lifecycle: $event") })
        // Light UI on a cream background: dark system-bar icons, transparent bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Lets `chrome://inspect` attach to the hub on a debug build. Never in release.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val app = application as CouchApp
        val pieces = PiecesConfig.load(this)
        val bridge = HubBridge(this, app.repo, { app.catalog }, object : HubBridge.Actions {
            override fun play(gameId: String) {
                runOnUiThread {
                    val game = app.catalog.game(gameId) ?: return@runOnUiThread
                    // Head to head (LinkedIn): each person plays alone, in their own account, so ask whose turn it is.
                    if (game.isDuel) whoPlaysFor = gameId else startActivity(PlayerActivity.gameIntent(this@MainActivity, gameId, null))
                }
            }

            override fun openAccounts() {
                runOnUiThread { startActivity(Intent(this@MainActivity, AccountsActivity::class.java)) }
            }

            override fun openKeyboardMode() {
                runOnUiThread { startActivity(Intent(this@MainActivity, KeyboardModeActivity::class.java)) }
            }
        })
        val reduceMotion = animationsRemoved(contentResolver)
        // Only on a real cold start (not after rotation or coming back), and never with "remove animations" on.
        // On a TV the splash always plays: some boxes ship with the animation scale at zero, and the splash does not depend on it.
        val playSplash = savedInstanceState == null && (!reduceMotion || tv.enabled)
        // If the last run crashed, or stopped while in use without a crash of its own (frozen, out of memory, a crash inside the
        // WebView), say so first, with what happened in text that can be photographed.
        var crash by mutableStateOf(CrashReport.pending(this))
        var stopped by mutableStateOf(if (crash == null) Diagnostics.unexpectedEnd(this) else null)
        if (crash != null || stopped != null) Flight.log("showing the report of the previous run (${if (crash != null) "crash" else "stopped in use"})")
        setContent {
            AlHasapaTheme {
              tv.Layer {
                val report = crash ?: stopped
                if (report != null) {
                    CrashScreen(report, stopped = crash == null) {
                        CrashReport.clear(this@MainActivity); Flight.acknowledgePrevious(); crash = null; stopped = null
                    }
                    return@Layer
                }
                AppRoot(playSplash = playSplash, reduceMotion = reduceMotion, onExit = { finish() }, bridge = bridge)
                whoPlaysFor?.let { id ->
                    val game = app.catalog.game(id)
                    if (game != null) {
                        val state = app.repo.snapshot()
                        val (nameA, nameB) = CouchState.names(state)
                        WhoPlaysDialog(
                            gameName = game.n, nameA = nameA, nameB = nameB,
                            pieceA = pieces.styleFor(state, "a"), pieceB = pieces.styleFor(state, "b"),
                            onPick = { who -> whoPlaysFor = null; startActivity(PlayerActivity.gameIntent(this, id, who)) },
                            onDismiss = { whoPlaysFor = null },
                        )
                    }
                }
              }
            }
        }
        // Plain views, no Compose and no WebView: readable even if either of those fails. Debug builds only.
        if (BuildConfig.DEBUG) DiagOverlay.install(this)
    }
}
