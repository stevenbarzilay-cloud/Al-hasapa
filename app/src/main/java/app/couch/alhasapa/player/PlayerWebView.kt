package app.couch.alhasapa.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat.WebMessageListener
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.display.DisplayLayout
import app.couch.alhasapa.display.DisplayProfile
import app.couch.alhasapa.display.DisplayScript
import app.couch.alhasapa.results.CaptureGate
import app.couch.alhasapa.results.CaptureScript

/** Creates and configures the WebView a game runs in, in its own browser profile. */
object PlayerWebView {
    /** @return the profile actually in use (see [WebProfiles.attach]). */
    @SuppressLint("SetJavaScriptEnabled") // The pages are the games the person chose; JavaScript is the point.
    fun configure(view: WebView, profile: String): String {
        // Explicit match-parent: a WebView at the default wrap-content height tells the page its layout height is 0
        // (that collapsed the hub in M0.1-M0.3). Pages here rely on percentage heights even more.
        view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val used = WebProfiles.attach(view, profile)       // before ANY load
        val cookies: CookieManager = WebProfiles.cookies(used)
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
        view.overScrollMode = WebView.OVER_SCROLL_NEVER
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Music games (Bandle, "איזה שיר!") must be able to play without a tap.
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)          // a new window navigates this one, through the policy
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = 100
            // The default user agent is left alone on purpose: no spoofing (Google blocks OAuth in embedded WebViews,
            // and that is respected, not worked around; the accounts screen recommends email and password).
        }
        // Light only unless a display profile says otherwise (the TV reads best light).
        // Optional WebKit features can report "supported" and still refuse (seen under test, possible on odd WebView builds):
        // every one of them is wrapped, and a refusal only costs that one nicety.
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
            }
        }
        return used
    }

    /**
     * Writes the profile's cookies (the logins) to disk at once, on the main thread, like every other call into the WebView. Only when a
     * sign-in screen is left: that is when a new login must not be lost; otherwise the WebView writes them by itself every few seconds.
     *
     * (1.5 to 1.7 wrote them from a thread of their own whenever a game was left. On a TV box the next game then never opened: the
     * main thread got stuck in the WebView's profile code, most likely waiting for that write. Never again from another thread.)
     */
    fun flushCookies(profile: String) {
        runCatching { WebProfiles.cookies(profile).flush() }
    }
}

/**
 * Keeps one WebView showing its pages the way the display profile says: the injected script (light mode, banner CSS)
 * runs at document start where the WebView allows it, and is re-run on the live page whenever the profile changes.
 * [origins] are the origin rules the script may run on (the game's own sites).
 */
class DisplayInjector(private val view: WebView, private val origins: Set<String>, private val tv: Boolean = false) {
    private var handler: ScriptHandler? = null
    private var script: String? = null
    private var documentStart = false

    /** True when the script is registered to run before the page's own scripts. */
    val runsAtDocumentStart: Boolean get() = documentStart

    fun apply(profile: DisplayProfile) {
        val next = DisplayScript.build(profile, tv)
        if (next == script) return
        script = next
        runCatching { handler?.remove() }
        handler = null
        documentStart = false
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                handler = WebViewCompat.addDocumentStartJavaScript(view, next, origins)
                documentStart = true
            }
        }.onFailure { Diag.log("display: document-start script refused: ${it.javaClass.simpleName}") }
        view.evaluateJavascript(next, null)          // the page that is already there
    }

    /** Without document-start support the script is run as each page begins loading (a little later, still before it is shown). */
    fun onPageStarted() {
        if (!documentStart) script?.let { view.evaluateJavascript(it, null) }
    }

    companion object {
        /** The host itself and its subdomains, over https, for every allowed host; every origin when the session may roam. */
        fun originRules(hosts: Set<String>, roaming: Boolean): Set<String> =
            if (roaming) setOf("*") else hosts.flatMap { listOf("https://$it", "https://*.$it") }.toSet()
    }
}

/**
 * Catches the result a game hands over when ITS Share / Copy button is pressed (see [CaptureScript]). Needs a WebView that supports
 * document-start scripts and web-message listeners; without one, [install] returns false and the clipboard route still works.
 * Only on the game's own sites: the origin rules limit where the hook exists at all, and [CaptureGate] checks the origin of
 * every message again.
 */
object CaptureInjector {
    fun install(view: WebView, session: PlaySession, gate: CaptureGate, onCaptured: (CaptureGate.Captured) -> Unit): Boolean {
        if (session.roaming || session.loginMode) return false
        val origins = DisplayInjector.originRules(session.allowedHosts, roaming = false)
        if (origins.isEmpty()) return false
        return runCatching {
            // Both are needed: the listener is the way out of the page, the document-start script is the hook.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    val listener = WebMessageListener { _, message: WebMessageCompat, sourceOrigin, _, _ ->
                        gate.accept(message.data, sourceOrigin.toString())?.let(onCaptured)
                    }
                    WebViewCompat.addWebMessageListener(view, CaptureScript.PORT, origins, listener)
                    WebViewCompat.addDocumentStartJavaScript(view, CaptureScript.JS, origins)
                    true
                } else false
            } else false
        }.getOrElse {
            Diag.log("capture: not available (${it.javaClass.simpleName})")
            false
        }
    }
}

/** Applies the navigation policy to the main frame and reports what happened. */
class PlayerWebViewClient(
    private val session: PlaySession,
    private val onBlocked: (NavigationPolicy.Decision) -> Unit,
    private val onStarted: () -> Unit,
    private val onFinished: (String) -> Unit,
    private val onLoadError: (String) -> Unit,
    private val onRenderGone: () -> Unit,
    /** The page the person is looking at changed (a navigation, or a single-page app moving on). */
    private val onUrl: (String) -> Unit = {},
    private val onPageBegan: () -> Unit = {},
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        // Sub-frames (embedded players, payment widgets) are the page's own business; the main frame is what the person sees.
        if (!request.isForMainFrame) return false
        val decision = NavigationPolicy.decide(request.url.toString(), session.allowedHosts, session.roaming)
        if (decision == NavigationPolicy.Decision.Allow) return false
        if (decision is NavigationPolicy.Decision.UpgradeToHttps) {
            Diag.log("player: http -> https for ${request.url.host}")
            view.loadUrl(decision.url)
            return true
        }
        Diag.log("player: refused ${request.url.host ?: request.url.scheme} (${decision::class.simpleName})")
        // In the report too: a game whose page moves to another site stays white otherwise, with only a passing message.
        app.couch.alhasapa.diag.Flight.log("player: refused to leave for ${request.url.host ?: request.url.scheme}")
        onBlocked(decision)
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onStarted()
        onUrl(url)
        onPageBegan()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        onUrl(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        Diag.log("player: page finished")
        app.couch.alhasapa.diag.Flight.log("player: page loaded (${url.toUri().host})")
        // What the page believes the screen is, so a wrong layout shows up in the log (and in the display sheet) as a number.
        view.evaluateJavascript("innerWidth+'x'+innerHeight+' @'+devicePixelRatio") { Diag.log("player: the page sees ${it?.trim('\"')} css px") }
        onFinished(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        // A redirect to http:// that did not pass through shouldOverrideUrlLoading: try the same address over https once.
        if (error.description?.toString()?.contains("ERR_CLEARTEXT_NOT_PERMITTED") == true && request.url.scheme == "http") {
            val d = NavigationPolicy.decide(request.url.toString(), session.allowedHosts, session.roaming)
            if (d is NavigationPolicy.Decision.UpgradeToHttps) { Diag.log("player: cleartext refused, retrying over https"); view.loadUrl(d.url); return }
        }
        // -3 is ERR_ABORTED: the page replaced itself (a redirect); not a failure.
        if (error.errorCode == -3) return
        Log.e("AlHasapaPlayer", "load error ${error.errorCode} ${error.description} for ${request.url}")
        Diag.log("player: load error ${error.errorCode} ${error.description}")
        app.couch.alhasapa.diag.Flight.log("player: the page did not load (${request.url.host}): ${error.errorCode} ${error.description}")
        onLoadError("${error.errorCode} ${error.description}")
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            app.couch.alhasapa.diag.Flight.log("player: ${request.url.host} answered ${errorResponse.statusCode}")
        }
    }

    /** Never proceeds past a bad certificate; only says so in the report (a white page otherwise). */
    override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
        app.couch.alhasapa.diag.Flight.log("player: certificate problem at ${error.url?.toUri()?.host} (${error.primaryError}): refused")
        handler.cancel()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // Returning true keeps the app alive (false would take it down with the page); the screen builds a new WebView.
        Diag.log("player: render process gone")
        app.couch.alhasapa.diag.Flight.log("player: the page's process ended (crashed=${detail.didCrash()}, priority ${detail.rendererPriorityAtExit()}); reloading")
        app.couch.alhasapa.diag.Flight.flush()
        onRenderGone()
        return true
    }
}
