package app.couch.alhasapa

import android.content.ContentResolver
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.couch.alhasapa.ui.AppRoot
import app.couch.alhasapa.ui.theme.AlHasapaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must come before super.onCreate. The system splash is the first instants of a cold start;
        // the animated in-app splash continues from it.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Light UI on a cream background: dark system-bar icons, transparent bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Lets `chrome://inspect` attach to the hub on a debug build. Never in release.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val reduceMotion = animationsRemoved(contentResolver)
        // Only on a real cold start (not after rotation or coming back), and never with "remove animations" on.
        val playSplash = savedInstanceState == null && !reduceMotion
        setContent {
            AlHasapaTheme {
                AppRoot(playSplash = playSplash, reduceMotion = reduceMotion, onExit = { finish() })
            }
        }
    }
}

/** True when the user turned on "Remove animations" (or set the animation scale to 0). */
private fun animationsRemoved(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
