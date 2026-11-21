package app.couch.alhasapa.hub

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.webkit.WebViewAssetLoader
import app.couch.alhasapa.ui.theme.Palette

/**
 * The hub: the prototype's web UI, rendered from local assets. The native app owns everything
 * around it (player, accounts, storage); the hub keeps the exact look and copy of the prototype.
 */
@Composable
fun HubScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val assetLoader = remember(context) {
        WebViewAssetLoader.Builder()
            .setDomain(HubOrigin.HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Bumped when the WebView's render process dies, which rebuilds the view from scratch.
    var generation by remember { mutableIntStateOf(0) }

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
                        webViewClient = HubWebViewClient(ctx, assetLoader) { generation++ }
                        loadUrl(HubOrigin.URL)
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

    DisposableEffect(Unit) { onDispose { webView = null } }
}

@SuppressLint("SetJavaScriptEnabled") // The hub is our own page, served from local assets.
private fun WebView.configureHub() {
    setBackgroundColor(Palette.Cream.toArgb())
    overScrollMode = WebView.OVER_SCROLL_NEVER
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

/** Back closes an open sheet in the hub first; only a second back leaves the app. */
private fun closeSheetOrExit(view: WebView, onExit: () -> Unit) {
    val js = """
        (function(){
          var o=document.getElementById('overlay');
          if(o && o.classList.contains('show')){ o.classList.remove('show'); return 'closed'; }
          return 'none';
        })()
    """.trimIndent()
    view.evaluateJavascript(js) { result -> if (result != "\"closed\"") onExit() }
}
