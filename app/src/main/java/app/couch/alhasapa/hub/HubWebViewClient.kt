package app.couch.alhasapa.hub

import android.content.Context
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import app.couch.alhasapa.R
import java.io.ByteArrayInputStream

internal const val TAG = "AlHasapaHub"

/** Where the hub (the prototype's web UI) lives. Served from the APK's assets, never from the network. */
object HubOrigin {
    const val HOST = "appassets.androidplatform.net"
    private const val PAGE = "https://$HOST/assets/hub/index.html"

    /** `rm=1` tells the hub the system's "remove animations" is on, so it switches its own animations off. */
    fun url(reduceMotion: Boolean): String = if (reduceMotion) "$PAGE?rm=1" else PAGE
}

/**
 * Serves the hub from assets and keeps it sealed: anything that is not the local asset origin is
 * refused at the network layer. That is a hard guarantee that the hub itself can never reach out
 * (no remote fonts, no analytics, nothing), which is part of the TV-privacy goal.
 */
class HubWebViewClient(
    private val context: Context,
    private val assetLoader: WebViewAssetLoader,
    private val onPageReady: () -> Unit,
    private val onLoadError: (String) -> Unit,
    private val onRenderProcessGone: () -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host == HubOrigin.HOST) {
            return assetLoader.shouldInterceptRequest(url)
        }
        return WebResourceResponse(
            "text/plain", "utf-8", 403, "Blocked",
            emptyMap(), ByteArrayInputStream(ByteArray(0)),
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.scheme == "https" && url.host == HubOrigin.HOST) return false // same-document anchors etc.
        // M0 placeholder: game links will open in the isolated player from milestone 2.
        Toast.makeText(context, R.string.player_soon, Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onPageFinished(view: WebView, url: String) {
        Log.i(TAG, "page finished: $url")
        onPageReady()
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        Log.e(TAG, "load error ${error.errorCode} ${error.description} for ${request.url} (main frame: ${request.isForMainFrame})")
        if (request.isForMainFrame) onLoadError("${error.errorCode} ${error.description}")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        Log.e(TAG, "http ${errorResponse.statusCode} for ${request.url} (main frame: ${request.isForMainFrame})")
        if (request.isForMainFrame) onLoadError("HTTP ${errorResponse.statusCode}")
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // Returning true keeps the app alive; the host recreates the WebView.
        Log.e(TAG, "render process gone (crashed=${detail.didCrash()})")
        onRenderProcessGone()
        return true
    }
}
