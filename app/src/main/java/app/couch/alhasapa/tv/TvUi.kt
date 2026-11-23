package app.couch.alhasapa.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.ui.animationsRemoved
import app.couch.alhasapa.ui.theme.Palette

/** The TV controller of the screen being shown, or null on a phone. */
val LocalTv = compositionLocalOf<TvController?> { null }

/** Width of the strip at the left edge that holds the paw and keyboard buttons. */
private val DockWidth = 80.dp

/** The same, when a game fills it with the clock, the score and every control. */
private val DockWidthFull = 184.dp

/**
 * Wraps a screen for the TV: a slim dock on the left edge (the paw button, which switches between moving a focus ring and the free
 * paw, and the keyboard button), the content, the keyboard that slides up from the bottom (the content gives way to it a little, so
 * what is typed into stays in view), a short message when the way of controlling things changes, and the paw itself in paw mode.
 * On a phone it is just the content.
 */
@Composable
fun TvLayer(c: TvController, content: @Composable () -> Unit) {
    if (!c.enabled) { content(); return }
    val appDirection = LocalLayoutDirection.current
    val still = animationsRemoved(LocalContext.current.contentResolver)
    val screenH = LocalWindowInfo.current.containerSize.height / LocalDensity.current.density
    CompositionLocalProvider(LocalTv provides c, LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(Modifier.fillMaxSize().onSizeChanged { c.resize(it.width.toFloat(), it.height.toFloat()) }) {
            Row(Modifier.fillMaxSize()) {
                if (!c.dockHidden) TvDock(c)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // The app's own screens are right to left, like on the phone.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CompositionLocalProvider(LocalLayoutDirection provides appDirection) {
                            content()
                            // The information panel lies over the content (the page stays alive under it) and needs no WebView.
                            if (c.panel == TvPanel.INFO) InfoPanel(c)
                        }
                    }
                    AnimatedVisibility(
                        visible = c.keyboardOpen,
                        enter = if (still) EnterTransition.None else slideInVertically { it },
                        exit = if (still) ExitTransition.None else slideOutVertically { it },
                    ) {
                        // Four rows of keys and a slim header: about a third of the screen (a little more on a short one), never more.
                        TvKeyboard(c, rowHeight = ((screenH * 0.34f - 62f) / 4f).coerceIn(36f, 56f).dp)
                    }
                }
            }
            HintPill(c, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            // A phone asking to be the keyboard: the four digits to type on it. And a short note when one connects.
            val remote by app.couch.alhasapa.remote.RemoteKeys.ui.collectAsState()
            var phones by remember { androidx.compose.runtime.mutableIntStateOf(remote.phones.size) }
            androidx.compose.runtime.LaunchedEffect(remote.phones.size) {
                if (remote.phones.size > phones) c.notice(TvHint.PHONE)
                phones = remote.phones.size
            }
            remote.code?.let { PairingCard(it, remote.codeFor.orEmpty(), Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) }
            CursorOverlay(c)
        }
    }
}

/**
 * The strip at the left edge, always there. On its own (the hub, the accounts) it holds the two buttons that are never far away: the paw, and
 * the keyboard. In a game the screen adds to it (the clock, the score, the controls of the game), so the game's frame is one quiet column
 * on the left and the game keeps the whole middle.
 */
@Composable
private fun TvDock(c: TvController) {
    val extras = c.dockExtras
    val wide = extras != null && !c.slim
    Column(
        Modifier
            .width(if (wide) DockWidthFull else DockWidth)
            .fillMaxHeight()
            .background(Palette.Card)
            .border(BorderStroke(1.dp, Palette.Line2))
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        val linked = app.couch.alhasapa.remote.RemoteKeys.ui.collectAsState().value.phones.isNotEmpty()
        if (linked) PhoneBadge()
        if (extras != null) extras(c.slim) else {
            Spacer(Modifier.weight(1f))
            PawButton(c)
            KeyboardButton(c)
            Spacer(Modifier.weight(1f))
            InfoButton(c)
        }
        // The name of what the focus is on: the buttons are glyphs, and the glyph is not always enough.
        if (wide) {
            val label = c.focusLabel
            Text(label.orEmpty(), fontSize = 20.sp, color = Palette.Ink2, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.heightIn(min = 52.dp))
        }
    }
}

/** A round-cornered button of the dock. [content] draws its glyph, given whether the remote's focus is on it (the glyph then goes cream on the red). */
@Composable
fun DockButton(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    wide: Boolean = false,
    height: Dp = 52.dp,
    onClick: () -> Unit,
    content: @Composable (lit: Boolean) -> Unit,
) {
    val c = LocalTv.current
    val id = remember { newTvId() }
    val lit = c?.isFocused(id) == true
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Palette.Ink else Palette.Tint)
            .tvTarget(id, label, onClick)
            .semantics { role = if (selected) Role.Switch else Role.Button; this.selected = selected; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content(lit) }
}

/** The paw (mouse) switch: dark with a red paw while it is on. */
@Composable
fun PawButton(c: TvController, modifier: Modifier = Modifier.width(64.dp), height: Dp = 52.dp) {
    val paw = c.mode == InputMode.PAW
    val label = stringResource(if (paw) R.string.tv_paw_on else R.string.tv_paw_off)
    DockButton(label, modifier, selected = paw, height = height, onClick = { c.togglePaw() }) { lit ->
        PawIcon(32.dp, if (lit) Palette.Cream else if (paw) Palette.Red else Palette.Ink, outline = if (paw && !lit) Palette.Cream else Color.Transparent)
    }
}

@Composable
fun KeyboardButton(c: TvController, modifier: Modifier = Modifier.width(64.dp), height: Dp = 52.dp) {
    val label = stringResource(R.string.tv_keyboard_open)
    DockButton(label, modifier, height = height, onClick = { c.keyboardButton() }) { lit ->
        Text("⌨", fontSize = 28.sp, color = if (lit) Palette.Cream else Palette.Ink)
    }
}

/** A phone's outline, upright or on its side: how the game is shown (phone-shaped or wide). Drawn, so it never shows as a missing letter. */
@Composable
fun ShapeIcon(portrait: Boolean, color: Color, size: Dp = 30.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val w = if (portrait) s * 0.56f else s * 0.96f
        val h = if (portrait) s * 0.96f else s * 0.6f
        val stroke = s * 0.09f
        val tl = Offset((this.size.width - w) / 2f, (this.size.height - h) / 2f)
        drawRoundRect(color, topLeft = tl, size = androidx.compose.ui.geometry.Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        // the little bar a phone has at its top (or at its side when it lies down)
        if (portrait) drawLine(color, Offset(tl.x + w * 0.35f, tl.y + stroke * 1.9f), Offset(tl.x + w * 0.65f, tl.y + stroke * 1.9f), strokeWidth = stroke * 0.8f)
        else drawLine(color, Offset(tl.x + stroke * 1.9f, tl.y + h * 0.35f), Offset(tl.x + stroke * 1.9f, tl.y + h * 0.65f), strokeWidth = stroke * 0.8f)
    }
}

/** A phone is connected as the keyboard: a small red phone at the top of the dock. Not a button. */
@Composable
private fun PhoneBadge() {
    val label = stringResource(R.string.remote_badge)
    Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Tint).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        ShapeIcon(portrait = true, color = Palette.Red, size = 26.dp)
    }
}

/** The four digits a phone needs to pair as the keyboard, large, at the top of the screen while it waits. */
@Composable
private fun PairingCard(code: String, phone: String, modifier: Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(
            modifier.widthIn(max = 820.dp).clip(RoundedCornerShape(28.dp)).background(Palette.Ink.copy(alpha = 0.96f))
                .padding(horizontal = 28.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            PawIcon(40.dp, Palette.Red, outline = Palette.Ink)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.remote_pair_title) + if (phone.isNotBlank()) " ($phone)" else "", fontSize = 22.sp, color = Palette.Cream, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.remote_pair_body), fontSize = 18.sp, color = Palette.Cream.copy(alpha = 0.8f))
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(code.toCharArray().joinToString(" "), fontSize = 46.sp, fontWeight = FontWeight.Bold, color = Palette.Cream,
                    modifier = Modifier.semantics { contentDescription = code })
            }
        }
    }
}

/** Opens the information panel: what to photograph when something does not work. Reachable from every screen, even if a page hangs. */
@Composable
fun InfoButton(c: TvController, modifier: Modifier = Modifier.width(64.dp), height: Dp = 48.dp) {
    val label = stringResource(R.string.tv_info_open)
    DockButton(label, modifier, height = height, onClick = { c.openPanel(TvPanel.INFO) }) { lit ->
        Text("ⓘ", fontSize = 26.sp, color = if (lit) Palette.Cream else Palette.Ink2)
    }
}

/**
 * Everything worth knowing when something goes wrong (versions, memory, the last start, the last crash, how the last run ended, what
 * happened lately), in plain text to photograph. Native, so it opens even when the hub's page is stuck. Up and Down scroll, Back closes.
 */
@Composable
private fun InfoPanel(c: TvController) {
    val context = LocalContext.current
    val text = remember { app.couch.alhasapa.diag.Diagnostics.text(context) }
    val scroll = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var viewport by remember { androidx.compose.runtime.mutableIntStateOf(400) }
    androidx.compose.runtime.DisposableEffect(c) {
        c.textScroller = { dir -> scope.launch { scroll.animateScrollBy(dir * viewport * 0.8f) } }
        onDispose { c.textScroller = null }
    }
    val close = stringResource(R.string.tv_info_close)
    Box(Modifier.fillMaxSize().background(Palette.Cream.copy(alpha = 0.97f)).padding(20.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tv_info_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    Text(stringResource(R.string.tv_info_body), fontSize = 18.sp, color = Palette.Ink2)
                }
                Box(
                    Modifier.height(52.dp).widthIn(min = 140.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Ink)
                        .tvTarget(TvController.PANEL_CLOSE_ID, close) { c.closePanel() }.semantics { role = Role.Button; contentDescription = close },
                    contentAlignment = Alignment.Center,
                ) { Text(close, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Cream, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text, fontSize = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = Palette.Ink,
                    modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Palette.Card)
                        .onSizeChanged { viewport = it.height }.verticalScroll(scroll).padding(14.dp),
                )
            }
        }
    }
}

/** A few words at the top when the control mode changes, so it is never unclear which mode the arrows are in. */
@Composable
private fun HintPill(c: TvController, modifier: Modifier) {
    val text = when (c.hint) {
        TvHint.PAW_ON -> stringResource(R.string.tv_hint_paw)
        TvHint.NAV_ON -> stringResource(R.string.tv_hint_nav)
        TvHint.NO_TARGETS -> stringResource(R.string.tv_hint_none)
        TvHint.TYPE -> stringResource(R.string.tv_hint_type)
        TvHint.PHONE -> stringResource(R.string.tv_hint_phone)
        TvHint.PHONE_PAW -> stringResource(R.string.tv_hint_phone_paw)
        null -> null
    }
    AnimatedVisibility(visible = text != null, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
      // The message is Hebrew with "OK" in it: it must read right to left, which the dock layer (left to right) would not do.
      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Row(
            Modifier
                .widthIn(max = 760.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Palette.Ink.copy(alpha = 0.94f))
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PawIcon(30.dp, Palette.Red, outline = Palette.Ink)
            Text(text.orEmpty(), fontSize = 22.sp, color = Palette.Cream, fontWeight = FontWeight.Medium, textAlign = TextAlign.Start)
        }
      }
    }
}

/**
 * The remote's free finger in paw mode: a paw print, red while it is dragging. Its centre is where a tap lands.
 *
 * A small picture of its own that is only moved (a layer's position): a move of the paw paints nothing again. Until 1.9.3 the paw was
 * drawn on a canvas as large as the screen, so every move had the whole screen painted again, sixty times a second while a finger was
 * on the phone's touchpad; on the TV box that was felt as a slow paw.
 */
@Composable
private fun CursorOverlay(c: TvController) {
    if (c.mode != InputMode.PAW || !c.cursorVisible || c.keyboardOpen) return
    Canvas(
        Modifier.size(PawBox).graphicsLayer {
            translationX = c.cursorX - size.width / 2f
            translationY = c.cursorY - size.height / 2f
        },
    ) {
        val at = Offset(size.width / 2f, size.height / 2f)
        drawPaw(Offset(at.x, at.y + 8f * density), density * 0.9f, if (c.dragging) Palette.Red else Palette.Ink, Palette.Cream)
        drawCircle(Palette.Red, radius = 3f * density, center = at)
        drawCircle(Palette.Cream, radius = 1.4f * density, center = at)
    }
}

/** The paw's own picture: the print (some 37 dp across, 37 dp tall, mostly below its centre) with room to spare. */
private val PawBox = 72.dp

/**
 * The keyboard, kept small: a slim header (what has been typed, and a close button) over four rows of soft, rounded keys. The
 * layouts are the ordinary left-to-right ones (Hebrew as on a Hebrew keyboard).
 */
@Composable
fun TvKeyboard(c: TvController, rowHeight: Dp, modifier: Modifier = Modifier) {
    val rows = KbLayouts.rows(c.layout)
    val closeLabel = stringResource(R.string.kb_close)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(Palette.Cream)
                .border(BorderStroke(2.dp, Palette.Ink), RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Palette.Card)
                        .border(BorderStroke(1.5.dp, Palette.Line2), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PawIcon(24.dp, Palette.Ink2, outline = Palette.Card)
                    val shown = c.preview.ifEmpty { stringResource(R.string.tv_keyboard_hint) }
                    Text(
                        shown, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Clip,
                        color = if (c.preview.isEmpty()) Palette.Ink2 else Palette.Ink,
                        modifier = Modifier.semantics { contentDescription = if (c.password) "" else shown },
                    )
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Palette.Tint)
                        .tvTarget { c.closeKeyboard() }
                        .semantics { role = Role.Button; contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) { Text("✕", fontSize = 22.sp, color = Palette.Ink) }
            }
            rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEachIndexed { col, key -> KeyCap(c, key, r, col, rowHeight, Modifier.weight(weightOf(key))) }
                }
            }
        }
    }
}

private fun weightOf(key: KbKey) = when (key) {
    KbKey.Space -> 4f
    KbKey.Shift, KbKey.Backspace, KbKey.Enter -> 1.6f
    is KbKey.Switch -> 1.5f
    else -> 1f
}

@Composable
private fun KeyCap(c: TvController, key: KbKey, row: Int, col: Int, height: Dp, modifier: Modifier) {
    val label = when (key) {
        is KbKey.Ch -> if (c.shift) key.upper else key.lower
        KbKey.Shift -> "⇧"
        KbKey.Backspace -> "⌫"
        KbKey.Enter -> "⏎"
        KbKey.Space -> stringResource(R.string.kb_space)
        is KbKey.Switch -> key.label
        KbKey.Close -> "✕"
    }
    val description = when (key) {
        KbKey.Shift -> "Shift"
        KbKey.Backspace -> stringResource(R.string.kb_backspace)
        KbKey.Enter -> stringResource(R.string.kb_enter)
        KbKey.Close -> stringResource(R.string.kb_close)
        else -> label
    }
    val on = c.highlightRow == row && c.highlightCol == col
    val base = when (key) {
        KbKey.Enter -> Palette.Red
        KbKey.Backspace, KbKey.Shift -> Palette.Pink
        is KbKey.Switch -> Palette.Card
        else -> Palette.Tint
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(height)
            .graphicsLayer { val s = if (on) 1.07f else 1f; scaleX = s; scaleY = s }
            .clip(shape)
            .background(if (on) (if (key == KbKey.Enter) Palette.Ink else Palette.Red) else base)
            .border(BorderStroke(1.dp, if (on) Color.Transparent else Palette.Line2), shape)
            .tvKey { c.highlight(row, col); c.press(key) }
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = if (key is KbKey.Switch || key == KbKey.Space) 20.sp else 26.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            color = if (on || key == KbKey.Enter) Palette.Cream else Palette.Ink,
        )
    }
}

/** A key is a plain touch target: the keyboard's own highlight (moved by the arrows) is its focus. */
private fun Modifier.tvKey(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
