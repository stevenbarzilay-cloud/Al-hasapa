package app.couch.alhasapa.hub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.webkit.WebViewAssetLoader
import app.couch.alhasapa.R
import app.couch.alhasapa.bridge.HubBridge
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.tv.CouchWebView
import app.couch.alhasapa.tv.LocalTv
import app.couch.alhasapa.tv.tvPage
import app.couch.alhasapa.diag.DiagOverlay
import app.couch.alhasapa.ui.theme.Palette
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

/** The page must have finished loading within this long. */
private const val HUB_LOAD_TIMEOUT_MS = 10_000L

/** The same on a TV box, which is many times slower to start. */
private const val SLOW_LOAD_TIMEOUT_MS = 60_000L
private val SLOW_CHECK_AT_MS = longArrayOf(6_000, 12_000, 20_000)

/** Moments (after the page reports it finished loading) at which the host checks that the hub is really on screen. */
private val CHECK_AT_MS = longArrayOf(2_500, 5_000, 8_000)

/** Below this share of non-background pixels the window counts as blank. A drawn hub is well above it. */
private const val BLANK_BELOW = 0.004

/** How many times a blank hub is rebuilt automatically before the error screen takes over. */
private const val MAX_REBUILDS = 2

private const val PROBE_JS = """(function(){
  function r(e){if(!e)return null;var x=e.getBoundingClientRect();return [Math.round(x.left),Math.round(x.top),Math.round(x.width),Math.round(x.height)]}
  var b=document.body;
  return JSON.stringify({ready:window.__ready===true,errors:(window.__errors||[]).slice(0,3),vis:document.visibilityState,
    iw:innerWidth,ih:innerHeight,dpr:devicePixelRatio,rows:document.querySelectorAll('#v-home .row').length,
    bg:b?getComputedStyle(b).backgroundColor:null,mast:r(document.querySelector('.mast')),app:r(document.querySelector('.app')),
    scroller:r(document.getElementById('scroller')),htmlH:document.documentElement.clientHeight,appH:getComputedStyle(document.documentElement).getPropertyValue('--app-h')});
})()"""

/**
 * The hub: the prototype's web UI, rendered from local assets. The native app owns everything
 * around it (player, accounts, storage); the hub keeps the exact look and copy of the prototype.
 *
 * A blank screen is never an acceptable failure. After the page loads, the host asks the page what it sees AND
 * captures the real composited window; if the hub is blank it rebuilds the WebView, and if that does not help it shows
 * a native error screen with the evidence.
 */
@Composable
fun HubScreen(
    onExit: () -> Unit,
    reduceMotion: Boolean = false,
    bridge: HubBridge? = null,
    /** Raised when something over the page changed the state (the phone's who-am-I or a note, 1.10): the page reads it again. */
    reloads: Int = 0,
    onReady: () -> Unit = {},
) {
    val context = LocalContext.current
    val tv = LocalTv.current
    // A TV box takes many seconds to start a WebView: nothing may be called broken, or rebuilt, until it has had a fair time.
    val slow = tv != null
    val loadTimeout = if (slow) SLOW_LOAD_TIMEOUT_MS else HUB_LOAD_TIMEOUT_MS
    val checkAt = if (slow) SLOW_CHECK_AT_MS else CHECK_AT_MS
    val maxRebuilds = if (slow) 1 else MAX_REBUILDS
    val activity = remember(context) { context.findActivity() }
    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .setDomain(HubOrigin.HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Bumped to rebuild the WebView from scratch (render process died, blank hub, or the person tapped retry).
    var generation by remember { mutableIntStateOf(0) }
    var pageReady by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var rebuilds by remember { mutableIntStateOf(0) }
    val console = remember { ConsoleToLogcat() }

    // 1) Did the page finish loading at all?
    LaunchedEffect(generation) {
        delay(loadTimeout)
        if (!pageReady) {
            Diag.markProblem("the page did not finish loading in ${loadTimeout / 1000}s")
            loadError = "the page did not finish loading"
        }
    }
    // 2) Once loaded: is the hub really on screen? Ask the page, then look at the actual pixels.
    LaunchedEffect(generation, pageReady) {
        if (!pageReady) return@LaunchedEffect
        var elapsed = 0L
        for (at in checkAt) {
            delay(at - elapsed)
            elapsed = at
            val view = webView ?: return@LaunchedEffect
            Diag.log("check +${at}ms: webview ${view.width}x${view.height} visible=${view.visibility == android.view.View.VISIBLE} shown=${view.isShown} attached=${view.isAttachedToWindow} window=${view.windowVisibility}")
            val page = probePage(view)
            Diag.log("page says: ${page ?: "no answer"}")
            if (page == null || !page.optBoolean("ready")) {
                val why = page?.optJSONArray("errors")?.let { a -> (0 until a.length()).joinToString(" | ") { a.getString(it) } }
                    .orEmpty().ifBlank { console.lastError.orEmpty() }.ifBlank { "the page loaded but did not draw" }
                Diag.markProblem(why)
                loadError = why
                return@LaunchedEffect
            }
            if (Diag.splashShowing) continue // the splash is still up: no verdict yet
            val share = activity?.let { DiagOverlay.nonCreamShare(it, Diag.overlayBottomPx) }
            Diag.log("screen: ${share?.let { "%.1f%% of pixels are not background".format(it * 100) } ?: "capture unavailable"}")
            if (share == null || share >= BLANK_BELOW) {
                Diag.markHubVisible()
                return@LaunchedEffect
            }
            Diag.markProblem("the window is blank although the page says it drew itself")
            if (rebuilds < maxRebuilds) {
                rebuilds++
                Diag.log("recovery: rebuilding the WebView (try $rebuilds of $maxRebuilds)")
                pageReady = false
                generation++
            } else {
                loadError = "the screen stays blank although the page drew itself"
            }
            return@LaunchedEffect
        }
    }

    BackHandler {
        val view = webView
        if (view == null) onExit() else closeSheetOrExit(view, onExit)
    }
    LaunchedEffect(reloads) { if (reloads > 0) webView?.evaluateJavascript("window.__reloadState && window.__reloadState()", null) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { webView?.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        webView?.let { v ->
            v.onResume()
            // The remote walks this page again (a game that was open had its own), and the hub is told to re-read the state, which
            // something else (the player, a shared result) may have changed while it was away.
            tv?.web = v
            v.evaluateJavascript("window.__reloadState && window.__reloadState()", null)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Cream)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .tvPage(),
    ) {
        key(generation) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    Diag.log("webview: created (generation $generation)")
                    CouchWebView(ctx).apply {
                        // On a TV the app's own keyboard types into the hub's text fields (names, backups, links); it opens from its button.
                        ownKeyboard = tv != null
                        tv?.web = this
                        // Explicit match-parent: a WebView left at the default (wrap-content) height tells the page its
                        // layout height is 0, which collapses every percentage height. That blanked the hub in M0.1-M0.3.
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        configureHub()
                        // Only ever our own bundled page is loaded in this WebView (everything else is refused), so it may talk to the app.
                        bridge?.let { addJavascriptInterface(it, HubBridge.NAME) }
                        webChromeClient = console
                        webViewClient = HubWebViewClient(
                            ctx, assetLoader,
                            onPageReady = {
                                if (!pageReady) app.couch.alhasapa.diag.Flight.log("hub: page loaded (generation $generation)")
                                pageReady = true
                                // A WebView that was ever paused does not paint; make sure this one is running.
                                webView?.let { it.onResume(); it.resumeTimers() }
                                onReady()
                            },
                            onLoadError = { Diag.markProblem("load error: $it"); loadError = it },
                        ) { why -> Diag.markProblem("the render process died"); app.couch.alhasapa.diag.Flight.log("hub: $why; a new WebView"); pageReady = false; generation++ }
                        loadUrl(HubOrigin.url(reduceMotion, tv != null))
                        Diag.log("webview: loadUrl")
                        webView = this
                    }
                },
                onRelease = { view ->
                    if (webView === view) webView = null
                    tv?.releaseWeb(view)
                    view.destroy()
                },
            )
        }
    }

    if (loadError != null) {
        HubLoadError(
            detail = loadError.orEmpty() + "\n\n" + Diag.snapshot(8),
            onRetry = { loadError = null; pageReady = false; rebuilds = 0; generation++ },
        )
    }

    DisposableEffect(Unit) { onDispose { webView = null } }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Asks the page what it sees. Null if it does not answer within 3 s. */
private suspend fun probePage(view: WebView): JSONObject? = withTimeoutOrNull(3_000) {
    suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(PROBE_JS) { raw ->
            val parsed = runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()
            if (cont.isActive) cont.resume(parsed)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled") // The hub is our own page, served from local assets.
private fun WebView.configureHub() {
    setBackgroundColor(Palette.Cream.toArgb())
    overScrollMode = WebView.OVER_SCROLL_NEVER
    // Exactly the settings milestone 0 shipped with, which loaded the hub fine on the phone. Nothing else.
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true // interim storage until the native state layer lands (milestone 1)
        allowFileAccess = false
        allowContentAccess = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_DEFAULT
        mediaPlaybackRequiresUserGesture = true
    }
}

/**
 * Back closes an open sheet in the hub first (the hub also reverts anything unsaved); only a second back leaves. If the page does not
 * answer at once (busy, or stuck), Back leaves anyway: it must always work.
 */
private fun closeSheetOrExit(view: WebView, onExit: () -> Unit) {
    var answered = false
    val fallback = Runnable { if (!answered) { answered = true; app.couch.alhasapa.diag.Flight.log("hub: no answer to Back; leaving"); onExit() } }
    view.postDelayed(fallback, 800)
    view.evaluateJavascript("window.__back ? window.__back() : 'none'") { result ->
        if (answered) return@evaluateJavascript
        answered = true
        view.removeCallbacks(fallback)
        if (result != "\"closed\"") onExit()
    }
}

/** What the page logs (errors included) lands in Logcat under the tag AlHasapaHub; the last error is kept for the error screen. */
private class ConsoleToLogcat : WebChromeClient() {
    @Volatile var lastError: String? = null
    private var errors = 0

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        val priority = when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
            else -> Log.INFO
        }
        val line = "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
        if (priority == Log.ERROR) {
            lastError = line
            Diag.log("js error: $line")
            if (errors++ < 20) app.couch.alhasapa.diag.Flight.log("hub js error: ${line.take(300)}")
        }
        Log.println(priority, TAG, line)
        return true
    }
}

@Composable
private fun HubLoadError(detail: String, onRetry: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Palette.Cream).windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(stringResource(R.string.hub_error_title), style = MaterialTheme.typography.titleLarge, color = Palette.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.Ink2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.hub_error_retry)) }
        }
    }
}
