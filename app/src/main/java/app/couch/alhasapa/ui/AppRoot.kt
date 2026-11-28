package app.couch.alhasapa.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.couch.alhasapa.dnd.DndChipHost
import app.couch.alhasapa.dnd.DndSignals
import androidx.compose.ui.platform.LocalContext
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
import app.couch.alhasapa.diag.Startup
import app.couch.alhasapa.tv.LocalTv
import app.couch.alhasapa.hub.HubScreen

/**
 * The hub is created underneath from the first frame so it loads while the splash plays; the splash
 * lifts when the hub reports ready.
 */
@Composable
fun AppRoot(
    playSplash: Boolean, reduceMotion: Boolean, onExit: () -> Unit, bridge: HubBridge? = null, dndSignals: DndSignals? = null, reloads: Int = 0,
    /** The splash is over and the hub is what is seen (a line over it, the visit's say, waits for this). */
    onShown: () -> Unit = {},
) {
    var hubReady by remember { mutableStateOf(false) }
    var splashDone by remember { mutableStateOf(!playSplash) }
    val latestShown by androidx.compose.runtime.rememberUpdatedState(onShown)
    LaunchedEffect(splashDone) { if (splashDone) latestShown() }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }      // casting may start while the app was away
    val signals = dndSignals ?: DndSignals.real(LocalContext.current)
    LaunchedEffect(Unit) { Startup.mark("first composition"); Diag.log("compose: first composition (splash=${playSplash})") }
    val tv = LocalTv.current
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        HubScreen(onExit = onExit, reduceMotion = reduceMotion, bridge = bridge, reloads = reloads, onReady = {
            if (!hubReady) { Startup.mark("hub ready"); app.couch.alhasapa.diag.Flight.background { Startup.save(context) }; app.couch.alhasapa.diag.Flight.log("start: " + Startup.summary().replace("\n", "; ")) }
            hubReady = true
        })
        if (!splashDone) {
            // A TV box is too slow to start for an animated splash to be anything but choppy: it shows the cats, still, where the
            // system's starting window already put them, and fades to the hub.
            if (tv != null) LoadingCover(hubReady = hubReady, onFinished = { splashDone = true })
            else SplashScreen(hubReady = hubReady, onFinished = { splashDone = true })
        }
        // Once per evening, while casting: a nudge to turn on Do Not Disturb. Not while the splash plays.
        // Not on a TV: there is no casting there.
        if (splashDone && tv == null) DndChipHost(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp), signals, recheck = resumes)
    }
}
