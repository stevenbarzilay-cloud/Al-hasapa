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
                TvDock(c)
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // The app's own screens are right to left, like on the phone.
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CompositionLocalProvider(LocalLayoutDirection provides appDirection) { content() }
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
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (extras != null) extras(c.slim) else {
            Spacer(Modifier.weight(1f))
            PawButton(c)
            KeyboardButton(c)
            Spacer(Modifier.weight(1f))
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
    onClick: () -> Unit,
    content: @Composable (lit: Boolean) -> Unit,
) {
    val c = LocalTv.current
    val id = remember { newTvId() }
    val lit = c?.isFocused(id) == true
    Box(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Palette.Ink else Palette.Tint)
            .tvTarget(id, label, onClick)
            .semantics { role = if (selected) Role.Switch else Role.Button; this.selected = selected; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content(lit) }
}

/** The paw (mouse) switch: dark with a red paw while it is on. */
@Composable
fun PawButton(c: TvController, modifier: Modifier = Modifier.width(64.dp)) {
    val paw = c.mode == InputMode.PAW
    val label = stringResource(if (paw) R.string.tv_paw_on else R.string.tv_paw_off)
    DockButton(label, modifier, selected = paw, onClick = { c.togglePaw() }) { lit ->
        PawIcon(32.dp, if (lit) Palette.Cream else if (paw) Palette.Red else Palette.Ink, outline = if (paw && !lit) Palette.Cream else Color.Transparent)
    }
}

@Composable
fun KeyboardButton(c: TvController, modifier: Modifier = Modifier.width(64.dp)) {
    val label = stringResource(R.string.tv_keyboard_open)
    DockButton(label, modifier, onClick = { if (c.keyboardOpen) c.closeKeyboard() else c.openKeyboard() }) { lit ->
        Text("⌨", fontSize = 28.sp, color = if (lit) Palette.Cream else Palette.Ink)
    }
}

/** A few words at the top when the control mode changes, so it is never unclear which mode the arrows are in. */
@Composable
private fun HintPill(c: TvController, modifier: Modifier) {
    val text = when (c.hint) {
        TvHint.PAW_ON -> stringResource(R.string.tv_hint_paw)
        TvHint.NAV_ON -> stringResource(R.string.tv_hint_nav)
        TvHint.NO_TARGETS -> stringResource(R.string.tv_hint_none)
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

/** The remote's free finger in paw mode: a paw print, red while it is dragging. Its centre is where a tap lands. */
@Composable
private fun CursorOverlay(c: TvController) {
    if (c.mode != InputMode.PAW || !c.cursorVisible || c.keyboardOpen) return
    Canvas(Modifier.fillMaxSize()) {
        drawPaw(Offset(c.cursorX, c.cursorY + 8f * density), density * 0.9f, if (c.dragging) Palette.Red else Palette.Ink, Palette.Cream)
        drawCircle(Palette.Red, radius = 3f * density, center = Offset(c.cursorX, c.cursorY))
        drawCircle(Palette.Cream, radius = 1.4f * density, center = Offset(c.cursorX, c.cursorY))
    }
}

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
