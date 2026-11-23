package app.couch.alhasapa.keyboard

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.couch.alhasapa.diag.Flight
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.ui.theme.AlHasapaTheme

/**
 * Keyboard mode on the phone: while it is open the phone is the TV app's keyboard. It connects when it comes to the front and lets go when it
 * leaves (the TV then shows the phone is gone). "משחק" at the top goes back to playing on the phone.
 */
class KeyboardModeActivity : ComponentActivity() {
    private lateinit var client: KeyClient

    private val askLocalNetwork = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) client.start() else client.noPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Sideways, and the whole screen: the phone is a keyboard (and a touchpad), and the typing shows on the TV.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        client = KeyClient(this)
        val sink = object : KeySink {
            override fun text(s: String) = client.send(KeyLink.Msg.Text(s))
            override fun key(k: KeyLink.Key, a: KeyLink.Action) = client.send(KeyLink.Msg.Press(k, a))
            override fun code(c: String) = client.sendCode(c)
            override fun retry() = client.retry()
            override fun address(a: String) = client.useAddress(a)
            override fun permission() = askLocalNetwork.launch(LOCAL_NETWORK)
            override fun pointer(m: KeyLink.Msg) = client.send(m)
        }
        setContent {
            AlHasapaTheme {
                val state by client.state.collectAsState()
                KeyboardModeScreen(state, sink, onGame = { finish() })
            }
        }
        Flight.log("keyboard mode opened")
    }

    override fun onStart() {
        super.onStart()
        if (needsLocalNetwork()) askLocalNetwork.launch(LOCAL_NETWORK) else client.start()
    }

    override fun onStop() {
        super.onStop()
        client.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** Android 17 asks the person before an app may reach devices on the home network (the TV). Earlier versions do not. */
    private fun needsLocalNetwork(): Boolean =
        Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

    companion object {
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
