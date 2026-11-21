package app.couch.alhasapa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.couch.alhasapa.bridge.HubBridge
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.hub.HubScreen

/**
 * The hub is created underneath from the first frame so it loads while the splash plays; the splash
 * lifts when the hub reports ready.
 */
@Composable
fun AppRoot(playSplash: Boolean, reduceMotion: Boolean, onExit: () -> Unit, bridge: HubBridge? = null) {
    var hubReady by remember { mutableStateOf(false) }
    var splashDone by remember { mutableStateOf(!playSplash) }
    LaunchedEffect(Unit) { Diag.log("compose: first composition (splash=${playSplash})") }
    Box(Modifier.fillMaxSize()) {
        HubScreen(onExit = onExit, reduceMotion = reduceMotion, bridge = bridge, onReady = { hubReady = true })
        if (!splashDone) SplashScreen(hubReady = hubReady, onFinished = { splashDone = true })
    }
}
