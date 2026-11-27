package app.couch.alhasapa.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
fun Modifier.tvTarget(onClick: () -> Unit): Modifier = tvTarget(null, null, onClick)

/** A fresh id for a button that wants to know for itself whether the remote's focus is on it (see [TvController.isFocused]). */
fun newTvId(): String = "t" + ids.incrementAndGet()

/** As [tvTarget], with the button's own [id] (so it can draw itself differently when focused) and a [label] shown in the dock while focused. */
fun Modifier.tvTarget(id: String?, label: String?, onClick: () -> Unit): Modifier = tvTarget(id, label, bring = true, onClick = onClick)

/**
 * As above. [bring] false: the screen scrolls to the focused button itself (the home's rows of cards), so this one does not ask to be
 * scrolled into view (two scrolls of one row at once fight each other). [paint] false: the button marks the focus itself, in its own
 * shape (a cushion of the home wears a thread around its edge), so it is not filled with the focus's colour here.
 */
fun Modifier.tvTarget(id: String?, label: String?, bring: Boolean, paint: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val tv = LocalTv.current
    val myId = remember { id ?: newTvId() }
    val latest by rememberUpdatedState(onClick)
    var androidFocused by remember { mutableStateOf(false) }
    val remote = tv != null && tv.isFocused(myId)
    val lit = remote || androidFocused
    val requester = remember { BringIntoViewRequester() }
    val interactions = remember { MutableInteractionSource() }
    if (tv != null) DisposableEffect(tv, myId) { onDispose { tv.unregister(myId) } }
    LaunchedEffect(remote, bring) { if (remote && bring) requester.bringIntoView() }
    LaunchedEffect(remote, label) { if (remote && label != null) tv?.focusLabel = label }
    this
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { c -> tv?.register(myId, c.boundsInWindow().toBox()) { latest() } }
        .onFocusChanged { androidFocused = it.isFocused }
        .drawWithContent {
            // The button itself takes the colour, in its own shape (the clip is earlier in the chain), under its label or icon.
            if (lit && paint) drawRect(Palette.Red)
            drawContent()
        }
        .semantics { if (lit) stateDescription = "נבחר" }
        // On a TV the app marks the focus itself (the red, a cushion's threads). Android's own focus, which the box puts on some button
        // when a screen opens and which the remote never moves (the app takes its keys first), drew a grey box behind that button
        // for as long as the screen was up (a cushion of the home, 1.9.9). A phone keeps the ripple of a touch.
        .clickable(interactionSource = interactions, indication = if (tv != null && tv.enabled) null else LocalIndication.current, onClick = onClick)
}

/** Marks the area the page (the WebView) occupies, so the arrows can move between the page and the app's buttons. */
fun Modifier.tvPage(): Modifier = composed {
    val tv = LocalTv.current
    if (tv != null) DisposableEffect(tv) { onDispose { tv.pageBox = null } }
    this.onGloballyPositioned { c -> tv?.pageBox = c.boundsInWindow().toBox() }
}

private fun androidx.compose.ui.geometry.Rect.toBox() = Box(left, top, right, bottom)

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
