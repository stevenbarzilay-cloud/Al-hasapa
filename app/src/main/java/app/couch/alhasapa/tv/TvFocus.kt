package app.couch.alhasapa.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.couch.alhasapa.ui.theme.Palette
import java.util.concurrent.atomic.AtomicInteger

private val ids = AtomicInteger()

/**
 * A button that works for touch AND for the TV remote: it is clickable as usual, it tells the TV controller where it is so the arrows
 * can land on it, and it wears a bright ring while the remote's focus is on it (or while it has plain Android focus, for dialogs
 * that the remote's focus cannot reach). Drop-in for `clickable`: put it where the `clickable` was, after clip and background.
 */
fun Modifier.tvTarget(onClick: () -> Unit): Modifier = composed {
    val tv = LocalTv.current
    val id = remember { "t" + ids.incrementAndGet() }
    val latest by rememberUpdatedState(onClick)
    var androidFocused by remember { mutableStateOf(false) }
    val remote = tv != null && tv.mode == InputMode.NAV && tv.zone == Zone.NATIVE && tv.focusedId == id
    val lit = remote || androidFocused
    val bring = remember { BringIntoViewRequester() }
    if (tv != null) DisposableEffect(tv, id) { onDispose { tv.unregister(id) } }
    LaunchedEffect(remote) { if (remote) bring.bringIntoView() }
    this
        .bringIntoViewRequester(bring)
        .onGloballyPositioned { c -> tv?.register(id, c.boundsInWindow().toBox()) { latest() } }
        .onFocusChanged { androidFocused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (lit) drawFocusRing()
        }
        .semantics { if (lit) stateDescription = "נבחר" }
        .clickable(onClick = onClick)
}

/** Marks the area the page (the WebView) occupies, so the arrows can move between the page and the app's buttons. */
fun Modifier.tvPage(): Modifier = composed {
    val tv = LocalTv.current
    if (tv != null) DisposableEffect(tv) { onDispose { tv.pageBox = null } }
    this.onGloballyPositioned { c -> tv?.pageBox = c.boundsInWindow().toBox() }
}

private fun androidx.compose.ui.geometry.Rect.toBox() = Box(left, top, right, bottom)

private fun DrawScope.drawFocusRing() {
    val w = 4.dp.toPx()
    val r = 12.dp.toPx()
    drawRoundRect(Palette.Cream, topLeft = Offset(0f, 0f), size = size, cornerRadius = CornerRadius(r), style = Stroke(w + 4.dp.toPx()))
    drawRoundRect(Palette.Red, topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(r), style = Stroke(w))
}

/** A cat's paw print: the pad and four toes. Its centre is the point that a tap lands on. */
@Composable
fun PawIcon(size: Dp, color: Color, modifier: Modifier = Modifier, outline: Color = Palette.Cream) {
    Canvas(modifier.size(size)) { drawPaw(Offset(this.size.width / 2f, this.size.height / 2f), this.size.minDimension / 44f, color, outline) }
}

/** The paw, drawn with [u] pixels per unit (the whole print is about 40 units across). */
fun DrawScope.drawPaw(at: Offset, u: Float, fill: Color, outline: Color) {
    fun blob(cx: Float, cy: Float, r: Float) {
        drawCircle(outline, radius = r + 2f * u, center = Offset(cx, cy))
        drawCircle(fill, radius = r, center = Offset(cx, cy))
    }
    blob(at.x, at.y + 6f * u, 11f * u)                               // the pad
    blob(at.x - 13f * u, at.y - 7f * u, 5.5f * u)                    // the toes
    blob(at.x - 4.5f * u, at.y - 14f * u, 5.5f * u)
    blob(at.x + 4.5f * u, at.y - 14f * u, 5.5f * u)
    blob(at.x + 13f * u, at.y - 7f * u, 5.5f * u)
}
