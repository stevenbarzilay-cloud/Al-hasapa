package app.couch.alhasapa.player

import android.widget.Toast
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.couch.alhasapa.R
import app.couch.alhasapa.diag.Diag
import app.couch.alhasapa.tv.CouchWebView
import app.couch.alhasapa.tv.LocalTv
import app.couch.alhasapa.tv.tvPage
import app.couch.alhasapa.tv.VideoChrome
import app.couch.alhasapa.display.DisplayLayout
import app.couch.alhasapa.display.DisplayMode
import app.couch.alhasapa.display.ScaledFrame
import app.couch.alhasapa.display.DisplayProfile
import app.couch.alhasapa.display.DisplayProfiles
import app.couch.alhasapa.display.UiPlace
import app.couch.alhasapa.results.CaptureGate
import app.couch.alhasapa.results.Couple
import app.couch.alhasapa.results.ResultDraft
import app.couch.alhasapa.results.ResultInput
import app.couch.alhasapa.results.ResultSheet
import app.couch.alhasapa.results.ResultSupport
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import app.couch.alhasapa.ui.theme.Palette
import kotlinx.coroutines.delay

private val ALL_ITEMS = BarItem.entries
private val TOP_OF_BOTH = listOf(BarItem.MENU, BarItem.BACK, BarItem.TITLE, BarItem.TIMER, BarItem.ZOOM, BarItem.MODE, BarItem.UI)
private val BOTTOM_OF_BOTH = listOf(BarItem.PROGRESS, BarItem.POINTS, BarItem.PIECES, BarItem.RESULT, BarItem.PLAYED)

/**
 * A game, full screen, in its own browser profile. What is on screen follows the site's display profile: the page as it is
 * (native), as a desktop window (wide), or as a phone held upright with the couch panels at its sides (portrait-fit).
 *
 * The couch UI is always on screen, never behind a menu that vanishes: panels beside the game, bars above and below it, or at the
 * least a floating hamburger (see UiPlace). The hamburger opens the settings and the quick actions. Everything else is the game's.
 */
@Composable
fun PlayerScreen(
    session: PlaySession,
    played: Boolean,
    info: PlayerInfo,
    profiles: DisplayProfiles,
    overrides: JSONObject?,
    onTogglePlayed: () -> Unit,
    onSaveDisplay: (key: String, profile: DisplayProfile) -> Unit,
    onResetDisplay: (key: String) -> Unit,
    /** Null for the login screens and anything else that is not a game: no result to record. */
    result: ResultSupport? = null,
    onSaveResult: (ResultInput) -> Unit = {},
    onClearResult: () -> Unit = {},
    onClose: () -> Unit,
    onOpenInChrome: (String) -> Unit,
    reduceMotion: Boolean = false,
    dndSignals: app.couch.alhasapa.dnd.DndSignals? = null,
) {
    val tv = LocalTv.current
    val activity = LocalContext.current.findActivity()
    val video = remember(activity) { VideoChrome(activity) }
    val enter = if (reduceMotion) androidx.compose.animation.EnterTransition.None else fadeIn()
    val exit = if (reduceMotion) androidx.compose.animation.ExitTransition.None else fadeOut()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var injector by remember { mutableStateOf<DisplayInjector?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // When the page's process ended (a crash, or the box out of memory), and when: a second time soon after is not reloaded again by itself.
    val pageDeaths = remember { ArrayList<Long>() }
    var usedProfile by remember { mutableStateOf(session.profile) }

    // The page being shown decides how it is shown; a navigation to another game of the same site can change it.
    var currentUrl by remember { mutableStateOf(session.url) }
    // On a TV, a site that once held the box's drawing stuck is drawn without the graphics chip (see LightDrawing).
    val appContext = LocalContext.current.applicationContext
    var light by remember { mutableStateOf(tv != null && LightDrawing.isOn(appContext, hostOf(session.url))) }
    LaunchedEffect(Unit) { if (light) { delay(1_500); tv?.notice(app.couch.alhasapa.tv.TvHint.LIGHT) } }
    val resolved = remember(currentUrl, overrides?.toString()) { profiles.resolve(currentUrl, overrides) }
    var draft by remember(resolved.key) { mutableStateOf<DisplayProfile?>(null) }
    var justSaved by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    // Logging in is always shown as it is: the sign-in forms are made for a whole screen, and a shrunk box hides half of them.
    val profile = (draft ?: resolved.profile).let { if (session.loginMode) it.copy(mode = DisplayMode.NATIVE) else it }

    // What the page believes the screen is, shown in the display sheet so a wrong layout is a number, not a guess.
    var pageSees by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sheetOpen, profile.mode, profile.zoom, webView) {
        if (sheetOpen) {
            delay(500)                                       // let the page re-lay itself out first
            webView?.evaluateJavascript("innerWidth+'x'+innerHeight") { pageSees = it?.trim('"')?.replace('x', '×') }
        }
    }

    // The result sheet: opened by the button, or by the game's own Share / Copy.
    var resultOpen by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var resultCaptured by remember { mutableStateOf(false) }
    val gate = remember { CaptureGate(session.allowedHosts) }
    val latestResult by androidx.compose.runtime.rememberUpdatedState(result)
    val openWith: (CaptureGate.Captured) -> Unit = { c ->
        val r = latestResult
        if (r != null && !resultOpen) {
            val log = r.state.optJSONObject("log") ?: org.json.JSONObject()
            // A Share press is always a result; a bare copy only when it reads as one (people copy links and words too).
            if (c.kind == "share" || ResultDraft.evaluate(r.game, c.text, "", false, log, r.day).result != null) {
                resultText = c.text; resultCaptured = true; resultOpen = true
            }
        }
    }
    val onCaptured by androidx.compose.runtime.rememberUpdatedState(openWith)

    // A second route for games that copy some other way: text that lands on the clipboard while the game is in front.
    val context = LocalContext.current
    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val listener = ClipboardManager.OnPrimaryClipChangedListener {
            val text = runCatching { cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }.getOrNull()
            val r = latestResult
            if (r != null && !resultOpen && text != null && text.length <= app.couch.alhasapa.results.CaptureScript.MAX_TEXT) {
                val log = r.state.optJSONObject("log") ?: org.json.JSONObject()
                if (ResultDraft.evaluate(r.game, text, "", false, log, r.day).result != null) gate.acceptClipboard(text)?.let { onCaptured(it) }
            }
        }
        cm?.addPrimaryClipChangedListener(listener)
        onDispose { cm?.removePrimaryClipChangedListener(listener) }
    }

    val startedAt = remember { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(startedAt) }
    // On a phone the bars show the clock. On a TV the dock's clock ticks by itself, so this screen (and the game's frame around the
    // WebView) is not composed again every second on a slow box.
    LaunchedEffect(tv) { if (tv == null) while (true) { delay(1_000); now = System.currentTimeMillis() } }

    // The zoom buttons are for quick use while playing, so they keep what they set for this site without a second step.
    val keep: (DisplayProfile) -> Unit = { next ->
        draft = next
        onSaveDisplay(profiles.keyFor(currentUrl, resolved), next)
        justSaved = true
    }
    val quickZoom: (Int) -> Unit = { direction -> keep(profile.copy(zoom = zoomBy(profile.zoom, direction))) }
    // Phone-shaped or wide, one press (a page shown as it is becomes phone-shaped first).
    val toggleMode: () -> Unit = {
        keep(profile.copy(mode = if (profile.mode == DisplayMode.PORTRAIT_FIT) DisplayMode.WIDE else DisplayMode.PORTRAIT_FIT))
    }
    // The always-visible UI to its next place: sides (phone-shaped only), top, bottom, both, a button.
    val cycleUi: () -> Unit = {
        val order = buildList {
            if (profile.mode == DisplayMode.PORTRAIT_FIT) add(UiPlace.SIDES)
            addAll(listOf(UiPlace.TOP, UiPlace.BOTTOM, UiPlace.BOTH, UiPlace.MINIMAL))
        }
        keep(profile.copy(ui = order[(order.indexOf(profile.place) + 1).mod(order.size)]))
    }

    val openResult: () -> Unit = { resultText = ""; resultCaptured = false; resultOpen = true }
    val chrome = ChromeData(
        title = session.title, elapsed = formatElapsed((now - startedAt) / 1000), info = info, played = played,
        showPlayed = !session.loginMode, zoom = if (profile.mode != DisplayMode.NATIVE) profile.zoom else null,
        portrait = profile.mode == DisplayMode.PORTRAIT_FIT, startedAt = startedAt,
    )
    val actions = ChromeActions(
        onMenu = { sheetOpen = true }, onBack = onClose, onResult = if (result != null) openResult else null,
        onTogglePlayed = onTogglePlayed, onZoom = quickZoom, onToggleMode = toggleMode, onCycleUi = cycleUi,
    )

    // On a TV the game's frame lives in the dock at the left edge.
    if (tv != null) {
        SideEffect { tv.dockExtras = { slim -> PlayerTvDock(slim, tv, chrome, actions) } }
        DisposableEffect(tv) { onDispose { tv.dockExtras = null } }
    }

    BackHandler {
        val view = webView
        when {
            video.isShowing -> video.onHideCustomView()
            resultOpen -> resultOpen = false
            sheetOpen -> sheetOpen = false
            view != null && view.canGoBack() -> view.goBack()
            else -> onClose()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        webView?.onPause()
        if (session.loginMode) PlayerWebView.flushCookies(usedProfile)          // a new login survives the app being killed
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { webView?.onResume() }

    // The cutout (the Pixel's camera hole) is respected first; the TV margin is on top of that.
    Box(Modifier.fillMaxSize().background(Palette.Cream).windowInsetsPadding(WindowInsets.displayCutout)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val (marginX, marginY) = DisplayLayout.safeInsets(profile, maxWidth.value, maxHeight.value)
            // On a TV the whole frame (clock, score, controls) is the dock at the left edge, so no bars and no panels around the game.
            val layout = DisplayLayout.compute(
                if (tv != null) profile.copy(ui = UiPlace.MINIMAL) else profile,
                (maxWidth.value - 2 * marginX).coerceAtLeast(1f),
                (maxHeight.value - 2 * marginY).coerceAtLeast(1f),
            )
            LaunchedEffect(injector, profile) { injector?.apply(profile) }

            // Left and right here are the screen's own, whatever the language direction: the panels are placed by hand.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.fillMaxSize().padding(horizontal = marginX.dp, vertical = marginY.dp)) {
                if (layout.topBar > 0f) {
                    PlayerBar(if (layout.place == UiPlace.BOTH) TOP_OF_BOTH else ALL_ITEMS, layout.size, chrome, actions)
                }
                Row(
                    Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (layout.hasPanels) {
                        ProgressPanel(layout.panelWidth.dp, info, compact = layout.compactPanels) {
                            if (result != null) PanelButton(stringResource(R.string.player_result)) { openResult() }
                            if (!session.loginMode) {
                                Spacer(Modifier.height(8.dp))
                                val label = stringResource(R.string.player_played)
                                PanelButton(if (played) "✓ $label" else label, primary = played, onClick = onTogglePlayed)
                            }
                        }
                    }
                    Box(Modifier.size(layout.webWidth.dp, layout.webHeight.dp).tvPage()) {
                        key(generation) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    val web = CouchWebView(ctx).apply {
                                        // On a TV the app's own keyboard types (it opens from its button only), into this screen's page.
                                        ownKeyboard = tv != null
                                        tv?.web = this
                                        webChromeClient = video          // a game's full-screen video
                                        usedProfile = PlayerWebView.configure(this, session.profile)
                                        LightDrawing.front = hostOf(session.url)
                                        if (light) {
                                            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                                            app.couch.alhasapa.diag.Flight.log("player: drawn the light way (no graphics chip) for ${hostOf(session.url)}")
                                        }
                                        Diag.log("player: webview created, profile ${session.profile} -> $usedProfile, display ${profile.mode.id}")
                                        val inj = DisplayInjector(this, DisplayInjector.originRules(session.allowedHosts, session.roaming))
                                        inj.apply(profile)                          // before the first load, so the first page is already right
                                        injector = inj
                                        if (result != null) CaptureInjector.install(this, session, gate) { c -> onCaptured(c) }
                                        webViewClient = PlayerWebViewClient(
                                            session = session,
                                            onBlocked = { d ->
                                                val msg = when (d) {
                                                    is NavigationPolicy.Decision.OffDomain -> ctx.getString(R.string.player_blocked_site, d.host ?: "?")
                                                    else -> ctx.getString(R.string.player_blocked_app)
                                                }
                                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                            },
                                            onStarted = { loadError = null },
                                            onFinished = { if (tv != null) PageProbe.later(this) },
                                            onLoadError = { loadError = it },
                                            onRenderGone = {
                                                // A new WebView, the same game. Twice within two minutes is likely the box running out of memory:
                                                // then the error panel says so and waits, instead of reloading forever.
                                                val now = android.os.SystemClock.uptimeMillis()
                                                pageDeaths.removeAll { now - it > 120_000 }; pageDeaths += now
                                                // (The panel's "try again" builds the new WebView; a new one started here would clear the panel.)
                                                if (pageDeaths.size >= 2) loadError = ctx.getString(R.string.player_page_died) else generation++
                                            },
                                            onUrl = { currentUrl = it; LightDrawing.front = hostOf(it) },
                                            onPageBegan = { inj.onPageStarted() },
                                        )
                                        loadUrl(session.url)
                                        webView = this
                                    }
                                    // The WebView lives inside a frame that makes it phone-sized (or desktop-sized) and shrinks it to the box.
                                    ScaledFrame(ctx).apply {
                                        addView(web)
                                        virtualWidthDp = layout.virtualWidth
                                        magnify = layout.magnify
                                        setFocus(profile.panX.toFloat(), profile.panY.toFloat())
                                    }
                                },
                                update = { frame ->
                                    frame.virtualWidthDp = layout.virtualWidth
                                    frame.magnify = layout.magnify
                                    frame.setFocus(profile.panX.toFloat(), profile.panY.toFloat())
                                },
                                onRelease = { frame ->
                                    val view = frame.getChildAt(0) as? WebView
                                    if (view != null && webView === view) { webView = null; injector = null }
                                    if (view != null) tv?.releaseWeb(view)
                                    view?.destroy()
                                },
                            )
                        }
                    }
                    if (layout.hasPanels) {
                        IdentityPanel(
                            layout.panelWidth.dp, session.title, chrome.elapsed, compact = layout.compactPanels,
                            onMenu = actions.onMenu, onBack = onClose, zoom = chrome.zoom, onZoom = quickZoom,
                            portrait = chrome.portrait, onMode = toggleMode, onUi = cycleUi,
                        )
                    }
                }
                if (layout.bottomBar > 0f) {
                    PlayerBar(if (layout.place == UiPlace.BOTH) BOTTOM_OF_BOTH else ALL_ITEMS, layout.size, chrome, actions)
                }
                }
            }

            // The minimal UI is one button; it floats over the game's corner and is never taken away.
            if (layout.place == UiPlace.MINIMAL && tv == null) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    FloatingMenuButton(actions.onMenu, Modifier.align(Alignment.TopEnd).padding(12.dp))
                }
            }

            if (tv == null) app.couch.alhasapa.dnd.DndChipHost(
                Modifier.align(Alignment.TopCenter).padding(top = (layout.topBar + 12f).dp),
                dndSignals ?: app.couch.alhasapa.dnd.DndSignals.real(androidx.compose.ui.platform.LocalContext.current),
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                AnimatedVisibility(
                    visible = sheetOpen,
                    modifier = Modifier.align(Alignment.CenterStart),
                    enter = enter, exit = exit,
                ) {
                    TuningSheet(
                        profile = profile,
                        saved = justSaved,
                        pageSees = pageSees,
                        magnified = layout.magnify > 1f,
                        onChange = { draft = it; justSaved = false },
                        onSave = {
                            onSaveDisplay(profiles.keyFor(currentUrl, resolved), profile)
                            draft = null; justSaved = true
                        },
                        onReset = {
                            onResetDisplay(profiles.keyFor(currentUrl, resolved))
                            draft = null; justSaved = false
                        },
                        onClose = { sheetOpen = false },
                        onBack = { sheetOpen = false; onClose() },
                        onResult = if (result != null) ({ sheetOpen = false; openResult() }) else null,
                        played = played,
                        showPlayed = !session.loginMode,
                        onTogglePlayed = onTogglePlayed,
                        // On a TV only: draw this site with or without the graphics chip (a new WebView, the same game).
                        light = if (tv != null) light else null,
                        onLight = { on -> LightDrawing.set(appContext, hostOf(currentUrl), on); light = on; generation++ },
                    )
                }
            }
        }

        if (resultOpen && result != null) {
            Box(Modifier.fillMaxSize().background(Palette.Ink.copy(alpha = 0.45f)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
                key(resultText) {
                    // The sheet is right to left, like the rest of the app, whatever the player does with left and right.
                    ResultSheet(
                        support = result,
                        couple = Couple(info.nameA, info.nameB, info.pieceA, info.pieceB),
                        who = session.who,
                        initialText = resultText.ifEmpty { existingRaw(result, session.who) },
                        captured = resultCaptured,
                        onSave = { onSaveResult(it); resultOpen = false },
                        onClear = { onClearResult(); resultOpen = false },
                        onCancel = { resultOpen = false },
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
        }

        if (loadError != null) {
            LoadErrorPanel(
                detail = loadError.orEmpty(),
                onRetry = { loadError = null; generation++ },
                onChrome = { onOpenInChrome(session.url) },
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun LoadErrorPanel(detail: String, onRetry: () -> Unit, onChrome: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Cream), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp).widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.player_load_failed), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(detail, fontSize = 14.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text(stringResource(R.string.player_retry)) }
                Button(onClick = onChrome) { Text(stringResource(R.string.player_open_chrome)) }
                Button(onClick = onClose) { Text(stringResource(R.string.player_back)) }
            }
        }
    }
}

/** What is already saved for this game today, so the sheet opens on it (the prototype does the same). */
private fun existingRaw(result: ResultSupport, who: String?): String {
    val e = app.couch.alhasapa.data.CouchState.entry(result.state, result.day, result.game.id) ?: return ""
    return if (result.game.isDuel) e.optJSONObject("d")?.optJSONObject(who ?: "")?.optString("raw").orEmpty()
    else e.optJSONObject("r")?.optString("raw").orEmpty()
}

private fun Context.findActivity(): android.app.Activity {
    var c: Context = this
    while (c is android.content.ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    error("no activity")
}
