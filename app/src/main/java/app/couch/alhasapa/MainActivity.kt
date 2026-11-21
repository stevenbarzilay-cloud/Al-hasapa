package app.couch.alhasapa

import android.content.ContentResolver
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
import android.widget.Toast
import app.couch.alhasapa.bridge.HubBridge
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.diag.DiagOverlay
import app.couch.alhasapa.ui.AppRoot
import app.couch.alhasapa.ui.theme.AlHasapaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must come before super.onCreate. The system splash is the first instants of a cold start;
        // the animated in-app splash continues from it.
        installSplashScreen()
        super.onCreate(savedInstanceState)
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
        val bridge = HubBridge(this, app.repo, app.catalog, object : HubBridge.Actions {
            override fun play(gameId: String) {
                // The isolated player arrives in the next milestone; until then a game cannot be opened from here.
                runOnUiThread { Toast.makeText(this@MainActivity, R.string.player_soon, Toast.LENGTH_SHORT).show() }
            }
        })
        val reduceMotion = animationsRemoved(contentResolver)
        // Only on a real cold start (not after rotation or coming back), and never with "remove animations" on.
        val playSplash = savedInstanceState == null && !reduceMotion
        setContent {
            AlHasapaTheme {
                AppRoot(playSplash = playSplash, reduceMotion = reduceMotion, onExit = { finish() }, bridge = bridge)
            }
        }
        // Plain views, no Compose and no WebView: readable even if either of those fails. Debug builds only.
        if (BuildConfig.DEBUG) DiagOverlay.install(this)
    }
}

/** True when the user turned on "Remove animations" (or set the animation scale to 0). */
private fun animationsRemoved(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
