package app.couch.alhasapa

import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.couch.alhasapa.hub.HubScreen
import app.couch.alhasapa.ui.theme.AlHasapaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light UI on a cream background: dark system-bar icons, transparent bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // Lets `chrome://inspect` attach to the hub on a debug build. Never in release.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        setContent {
            AlHasapaTheme {
                HubScreen(onExit = { finish() })
            }
        }
    }
}
