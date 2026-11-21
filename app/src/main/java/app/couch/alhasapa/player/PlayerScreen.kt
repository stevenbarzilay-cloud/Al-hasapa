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
import app.couch.alhasapa.display.DisplayLayout
import app.couch.alhasapa.display.DisplayProfile
import app.couch.alhasapa.display.DisplayProfiles
import app.couch.alhasapa.results.CaptureGate
import app.couch.alhasapa.results.Couple
import app.couch.alhasapa.results.ResultDraft
import app.couch.alhasapa.results.ResultInput
import app.couch.alhasapa.results.ResultSheet
import app.couch.alhasapa.results.ResultSupport
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import app.couch.alhasapa.ui.theme.Palette
import kotlinx.coroutines.delay

/** How long the control strip stays on screen after it was last touched. */
private const val STRIP_VISIBLE_MS = 4_000L

/**
 * A game, full screen, in its own browser profile. What is on screen follows the site's display profile: the page as it is
 * (native), as a desktop window (wide), or as a phone held upright with the couch panels at its sides (portrait-fit).
 *
 * The control strip (back, name, display, "we played") sits at the top edge: it shows for a few seconds on entry, then hides;
 * a tap on the top edge brings it back. Everything else belongs to the page.
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
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var injector by remember { mutableStateOf<DisplayInjector?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var stripTick by remember { mutableIntStateOf(0) }
    var stripVisible by remember { mutableStateOf(true) }
    var usedProfile by remember { mutableStateOf(session.profile) }

    // The page being shown decides how it is shown; a navigation to another game of the same site can change it.
    var currentUrl by remember { mutableStateOf(session.url) }
    val resolved = remember(currentUrl, overrides?.toString()) { profiles.resolve(currentUrl, overrides) }
    var draft by remember(resolved.key) { mutableStateOf<DisplayProfile?>(null) }
    var justSaved by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    val profile = draft ?: resolved.profile

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
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }

    LaunchedEffect(stripTick) {
        stripVisible = true
        delay(STRIP_VISIBLE_MS)
        stripVisible = false
    }

    BackHandler {
        val view = webView
        when {
            resultOpen -> resultOpen = false
            sheetOpen -> sheetOpen = false
            view != null && view.canGoBack() -> view.goBack()
            else -> onClose()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        webView?.onPause()
        PlayerWebView.flushCookies(usedProfile)          // logins survive the app being killed
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { webView?.onResume() }

    // The cutout (the Pixel's camera hole) is respected first; the TV margin is on top of that.
    Box(Modifier.fillMaxSize().background(Palette.Cream).windowInsetsPadding(WindowInsets.displayCutout)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val (marginX, marginY) = DisplayLayout.safeInsets(profile, maxWidth.value, maxHeight.value)
            val layout = DisplayLayout.compute(
                profile,
                (maxWidth.value - 2 * marginX).coerceAtLeast(1f),
                (maxHeight.value - 2 * marginY).coerceAtLeast(1f),
            )
            LaunchedEffect(injector, profile, layout) { injector?.apply(profile, layout) }

            // Left and right here are the screen's own, whatever the language direction: the panels are placed by hand.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = marginX.dp, vertical = marginY.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (layout.hasPanels) {
                        ProgressPanel(layout.panelWidth.dp, info) {
                            if (result != null) PanelButton(stringResource(R.string.player_result)) { resultText = ""; resultCaptured = false; resultOpen = true }
                        }
                    }
                    Box(Modifier.size(layout.webWidth.dp, layout.webHeight.dp)) {
                        key(generation) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        usedProfile = PlayerWebView.configure(this, session.profile)
                                        Diag.log("player: webview created, profile ${session.profile} -> $usedProfile, display ${profile.mode.id}")
                                        val inj = DisplayInjector(this, DisplayInjector.originRules(session.allowedHosts, session.roaming))
                                        inj.apply(profile, layout)                 // before the first load, so the first page is already right
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
                                            onFinished = { },
                                            onLoadError = { loadError = it },
                                            onRenderGone = { generation++ },
                                            onUrl = { currentUrl = it },
                                            onPageBegan = { inj.onPageStarted() },
                                        )
                                        loadUrl(session.url)
                                        webView = this
                                    }
                                },
                                onRelease = { view ->
                                    if (webView === view) { webView = null; injector = null }
                                    PlayerWebView.flushCookies(usedProfile)
                                    view.destroy()
                                },
                            )
                        }
                    }
                    if (layout.hasPanels) {
                        IdentityPanel(layout.panelWidth.dp, session.title, formatElapsed((now - startedAt) / 1000))
                    }
                }
            }

            // The top edge is a handle: a tap there brings the strip back. (The rest of the screen is the game's.)
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(36.dp)
                    .pointerInput(Unit) { detectTapGestures { stripTick++ } },
            )

            AnimatedVisibility(
                visible = stripVisible && !sheetOpen,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(), exit = fadeOut(),
            ) {
                ControlStrip(
                    title = session.title,
                    showPlayed = !session.loginMode,
                    played = played,
                    onBack = onClose,
                    onTogglePlayed = { onTogglePlayed(); stripTick++ },
                    onDisplay = { sheetOpen = true },
                    onResult = if (result != null) ({ resultText = ""; resultCaptured = false; resultOpen = true }) else null,
                )
            }

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                AnimatedVisibility(
                    visible = sheetOpen,
                    modifier = Modifier.align(Alignment.CenterStart),
                    enter = fadeIn(), exit = fadeOut(),
                ) {
                    TuningSheet(
                        profile = profile,
                        saved = justSaved,
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
private fun ControlStrip(title: String, showPlayed: Boolean, played: Boolean, onBack: () -> Unit, onTogglePlayed: () -> Unit, onDisplay: () -> Unit, onResult: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout)
            .background(Palette.Ink.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StripButton(stringResource(R.string.player_back), onClick = onBack)
        StripButton("⚙ " + stringResource(R.string.player_display), onClick = onDisplay)
        Text(
            title, color = Palette.Cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1,
        )
        if (onResult != null) StripButton(stringResource(R.string.player_result), onClick = onResult)
        if (showPlayed) {
            val label = stringResource(R.string.player_played)
            StripButton(if (played) "✓ $label" else label, onClick = onTogglePlayed, highlighted = played)
        }
    }
}

@Composable
private fun StripButton(label: String, onClick: () -> Unit, highlighted: Boolean = false) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) Palette.Cream else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (highlighted) Palette.Ink else Palette.Cream, fontSize = 18.sp, fontWeight = FontWeight.Medium)
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
