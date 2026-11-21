package app.couch.alhasapa.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.ui.animationsRemoved
import app.couch.alhasapa.ui.theme.Palette

/** The TV controller of the screen being shown, or null on a phone. */
val LocalTv = compositionLocalOf<TvController?> { null }

/**
 * Wraps a screen for the TV: the content, the keyboard that slides up from the bottom (the content gives way to it, so the field
 * being typed into stays in view), the handle that pulls it up, and the cursor on top. On a phone it is just the content.
 */
@Composable
fun TvLayer(c: TvController, content: @Composable () -> Unit) {
    if (!c.enabled) { content(); return }
    val still = animationsRemoved(LocalContext.current.contentResolver)
    val screenH = LocalWindowInfo.current.containerSize.height / LocalDensity.current.density
    CompositionLocalProvider(LocalTv provides c) {
        Box(Modifier.fillMaxSize().onSizeChanged { c.resize(it.width.toFloat(), it.height.toFloat()) }) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                AnimatedVisibility(
                    visible = c.keyboardOpen,
                    enter = if (still) EnterTransition.None else slideInVertically { it },
                    exit = if (still) ExitTransition.None else slideOutVertically { it },
                ) {
                    // Five rows of keys and the line that shows what was typed, about 45% of the screen's height.
                    TvKeyboard(c, rowHeight = ((screenH * 0.45f - 72f) / 5f).coerceIn(44f, 76f).dp)
                }
            }
            // The handle is pulled up by pushing the cursor down to the bottom edge, so it never sits over the page's own controls.
            val edge = with(LocalDensity.current) { 88.dp.toPx() }
            if (!c.keyboardOpen && c.cursorMode && c.cursorVisible && c.cursorY >= c.cursor.height - edge) KeyboardHandle(c, Modifier.align(Alignment.BottomCenter))
            CursorOverlay(c)
        }
    }
}

/** The tab that rises when the cursor is pushed to the bottom edge: select it (OK) and the keyboard slides up. */
@Composable
private fun KeyboardHandle(c: TvController, modifier: Modifier) {
    val label = stringResource(R.string.tv_keyboard_open)
    Box(
        modifier
            .padding(bottom = 6.dp)
            .width(200.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Card.copy(alpha = 0.95f))
            .border(BorderStroke(2.dp, Palette.Ink), RoundedCornerShape(24.dp))
            .clickable { c.openKeyboard() }
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text("⌨  " + stringResource(R.string.tv_keyboard), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Palette.Ink) }
}

/** The remote's finger: a small cat paw, red while it is dragging. Its centre is where a tap lands. */
@Composable
private fun CursorOverlay(c: TvController) {
    if (!c.cursorMode || !c.cursorVisible || c.keyboardOpen) return
    Canvas(Modifier.fillMaxSize()) {
        val u = density
        val x = c.cursorX
        val y = c.cursorY
        val fill = if (c.dragging) Palette.Red else Palette.Ink
        val ring = Stroke(width = 2f * u)
        fun blob(cx: Float, cy: Float, r: Float) {
            drawCircle(Palette.Cream, radius = r + 2f * u, center = Offset(cx, cy))
            drawCircle(fill, radius = r, center = Offset(cx, cy))
        }
        blob(x, y + 3f * u, 9f * u)                                   // the pad
        blob(x - 11f * u, y - 8f * u, 4.5f * u)                       // the toes
        blob(x - 4f * u, y - 14f * u, 4.5f * u)
        blob(x + 4f * u, y - 14f * u, 4.5f * u)
        blob(x + 11f * u, y - 8f * u, 4.5f * u)
        drawCircle(Palette.Cream, radius = 2.2f * u, center = Offset(x, y))   // the exact point
        drawCircle(Color.Transparent, radius = 0f, center = Offset(x, y), style = ring)
    }
}

/** The keys, in the keyboard's own left-to-right order (the Hebrew rows are laid out like a Hebrew keyboard). */
@Composable
fun TvKeyboard(c: TvController, rowHeight: Dp, modifier: Modifier = Modifier) {
    val rows = KbLayouts.rows(c.layout)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier
                .fillMaxWidth()
                .background(Palette.Card)
                .border(BorderStroke(2.dp, Palette.Ink))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val shown = c.preview.ifEmpty { stringResource(R.string.tv_keyboard_hint) }
            Text(
                shown, fontSize = 26.sp, color = if (c.preview.isEmpty()) Palette.Ink2 else Palette.Ink, maxLines = 1,
                modifier = Modifier.fillMaxWidth().height(40.dp).semantics { contentDescription = if (c.password) "" else shown },
            )
            rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEachIndexed { col, key ->
                        KeyCap(c, key, r, col, rowHeight, Modifier.weight(weightOf(key)))
                    }
                }
            }
        }
    }
}

private fun weightOf(key: KbKey) = when (key) {
    KbKey.Space -> 3.5f
    KbKey.Shift, KbKey.Backspace, KbKey.Enter -> 1.5f
    is KbKey.Switch, KbKey.Close -> 1.4f
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
    val lit = key == KbKey.Shift && c.shift
    Box(
        modifier
            .heightIn(min = height)
            .height(height)
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) Palette.Ink else if (lit) Palette.Pink else Palette.Tint)
            .then(if (on) Modifier.border(BorderStroke(3.dp, Palette.Red), RoundedCornerShape(10.dp)) else Modifier)
            .clickable { c.highlight(row, col); c.press(key) }
            .semantics { role = Role.Button; contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 26.sp, fontWeight = FontWeight.Medium, color = if (on) Palette.Cream else Palette.Ink, maxLines = 1) }
}
