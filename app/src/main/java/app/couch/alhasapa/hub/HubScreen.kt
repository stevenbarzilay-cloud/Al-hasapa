package app.couch.alhasapa.hub

import android.annotation.SuppressLint
import android.util.Log
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
import app.couch.alhasapa.ui.theme.Palette
import kotlinx.coroutines.delay

/** The page must have reported its first successful draw within this long after loading, or we say so out loud. */
private const val HUB_DRAW_TIMEOUT_MS = 4_000L

/** The page must have finished loading within this long. */
private const val HUB_LOAD_TIMEOUT_MS = 10_000L

/**
 * The hub: the prototype's web UI, rendered from local assets. The native app owns everything
 * around it (player, accounts, storage); the hub keeps the exact look and copy of the prototype.
 *
 * A blank screen is never an acceptable failure: if the page does not load, or loads but does not draw itself,
 * a native error screen shows what happened and offers a retry.
 */
@Composable
fun HubScreen(
    onExit: () -> Unit,
    reduceMotion: Boolean = false,
    onReady: () -> Unit = {},
) {
    val context = LocalContext.current
    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .setDomain(HubOrigin.HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Bumped to rebuild the WebView from scratch (render process died, or the person tapped retry).
    var generation by remember { mutableIntStateOf(0) }
    var pageReady by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val console = remember { ConsoleToLogcat() }

    // 1) Did the page finish loading at all?
    LaunchedEffect(generation) {
        delay(HUB_LOAD_TIMEOUT_MS)
        if (!pageReady) loadError = "the page did not finish loading"
    }
    // 2) Did it finish loading AND draw itself? (The page sets window.__ready after its first render.)
    LaunchedEffect(generation, pageReady) {
        if (!pageReady) return@LaunchedEffect
        delay(HUB_DRAW_TIMEOUT_MS)
        val view = webView ?: return@LaunchedEffect
        view.evaluateJavascript("(function(){return window.__ready===true?'ok':((window.__errors||[]).slice(0,3).join(' | ')||'no script error, still waiting')})()") { raw ->
            val answer = raw?.trim('"').orEmpty()
            if (answer != "ok") {
                Log.e(TAG, "page loaded but did not draw: $answer")
                loadError = answer.ifBlank { console.lastError.orEmpty() }.ifBlank { "the page loaded but did not draw" }
            }
        }
    }

    BackHandler {
        val view = webView
        if (view == null) onExit() else closeSheetOrExit(view, onExit)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { webView?.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { webView?.onResume() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Cream)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        key(generation) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        configureHub()
                        webChromeClient = console
                        webViewClient = HubWebViewClient(
                            ctx, assetLoader,
                            onPageReady = { pageReady = true; onReady() },
                            onLoadError = { loadError = it },
                        ) { pageReady = false; generation++ }
                        loadUrl(HubOrigin.url(reduceMotion))
                        webView = this
                    }
                },
                onRelease = { view ->
                    if (webView === view) webView = null
                    view.destroy()
                },
            )
        }
    }

    if (loadError != null) {
        HubLoadError(
            detail = loadError.orEmpty(),
            onRetry = { loadError = null; pageReady = false; generation++ },
        )
    }

    DisposableEffect(Unit) { onDispose { webView = null } }
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

/** Back closes an open sheet in the hub first (the hub also reverts anything unsaved); only a second back leaves. */
private fun closeSheetOrExit(view: WebView, onExit: () -> Unit) {
    view.evaluateJavascript("window.__back ? window.__back() : 'none'") { result ->
        if (result != "\"closed\"") onExit()
    }
}

/** What the page logs (errors included) lands in Logcat under the tag AlHasapaHub; the last error is kept for the error screen. */
private class ConsoleToLogcat : WebChromeClient() {
    @Volatile var lastError: String? = null

    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        val priority = when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
            else -> Log.INFO
        }
        val line = "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
        if (priority == Log.ERROR) lastError = line
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
