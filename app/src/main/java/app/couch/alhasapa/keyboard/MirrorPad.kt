package app.couch.alhasapa.keyboard

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.KeyLink
import app.couch.alhasapa.ui.theme.Palette
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The picture of the TV's game in touch mode, for the tests. */
const val MIRROR_TAG = "mirror"
const val MIRROR_ROTATE_TAG = "mirror-rotate"

/** At most one move each this many ms goes to the TV (its screen draws no faster). */
private const val MOVE_EVERY_MS = 16L

/** How long a first finger waits to see whether a second one follows (a zoom of this view) before it touches the game. */
private const val PINCH_WAIT_MS = 90L

/** The most the picture is enlarged on the phone. */
const val MAX_MIRROR_ZOOM = 5f

/**
 * Touch mode (1.9.8): the TV's game, pictured here a few times a second, to touch as if it were on this screen. A finger down on the
 * picture is a finger down on the game at the same spot, moving it drags, lifting it lets go; a swipe scrolls the page. For the games
 * played by dragging (Dialed's colours, Pips' dominoes, TimeGuessr's map), which a paw moved by the remote plays badly. The picture
 * comes a little late (it is sent over the home network); the touch itself reaches the TV at once, and the TV shows where it is.
 *
 * Zoom (1.9.10): two fingers spread or pinch the picture and move it, as on any phone (they never touch the game), and the buttons at
 * the corner do the same with one hand. Zoomed in, the TV is asked for that part of the page only, so it stays sharp.
 */
@Composable
internal fun MirrorPad(
    frame: KeyClient.MirrorFrame?, none: String?, sink: KeySink, modifier: Modifier = Modifier,
    /**
     * Upright (2.0.2): the shape (width over height) of the room the game has on the upright phone, told to the TV, which then shows the
     * game shaped like it; 0 on its side. And the button that turns the phone: upright, or on its side again.
     */
    up: Float = 0f, onRotate: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Ink)) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val latestSink by rememberUpdatedState(sink)
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        var imageCrop by remember { mutableStateOf<KeyLink.Crop?>(null) }
        // The page's own shape, from the pictures (a part of it has the shape of that part).
        var ratio by remember { mutableFloatStateOf(16f / 9f) }
        LaunchedEffect(frame) {
            val f = frame ?: return@LaunchedEffect
            val bmp = withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size) }.getOrNull() }
            if (bmp != null) {
                image = bmp.asImageBitmap(); imageCrop = f.crop
                if (f.w > 0 && f.h > 0) ratio = (f.w / (f.crop?.w ?: 1f)) / (f.h / (f.crop?.h ?: 1f))
            }
            // Said once it is drawn: the TV sends the next one only then, so a slow moment here never piles pictures up.
            latestSink.frameShown(f.n)
        }
        // The TV said there is nothing to picture (no game, or it could not): the old picture goes.
        LaunchedEffect(frame, none) { if (frame == null && none != null) image = null }

        // Where the page is: as large as fits, in the middle, at zoom 1; zoomed, [zoom] times that, with the page's point (cx, cy) at
        // the middle of where it would be. Touches are shares of the page (0 to 1), whatever its size here or there.
        var zoom by remember { mutableFloatStateOf(1f) }
        var cx by remember { mutableFloatStateOf(0.5f) }
        var cy by remember { mutableFloatStateOf(0.5f) }
        val drawW = minOf(boxW, boxH * ratio)
        val drawH = drawW / ratio
        val left = (boxW - drawW) / 2
        val top = (boxH - drawH) / 2
        val geo = Geometry(left, top, drawW, drawH, zoom, cx, cy)
        val latestGeo by rememberUpdatedState(geo)

        fun clampTo(c: Float, z: Float) = if (z <= 1f) 0.5f else c.coerceIn(0.5f / z, 1f - 0.5f / z)
        /**
         * Zoomed by [factor] around [at] (a point of this view), and moved by [pan]. From the zoom as it is this moment, not as the last
         * drawing had it: the fingers of a pinch move several times between two drawings, and each step builds on the one before.
         */
        fun transform(at: Offset, pan: Offset, factor: Float) {
            val g = latestGeo
            val z0 = zoom
            val nz = (z0 * factor).coerceIn(1f, MAX_MIRROR_ZOOM)
            val ux = (at.x - g.left - g.drawW / 2) / g.drawW
            val uy = (at.y - g.top - g.drawH / 2) / g.drawH
            val ncx = cx + ux / z0 - ux / nz - pan.x / (g.drawW * nz)
            val ncy = cy + uy / z0 - uy / nz - pan.y / (g.drawH * nz)
            zoom = nz; cx = clampTo(ncx, nz); cy = clampTo(ncy, nz)
        }

        // Pictures are asked for while this is on the screen, as wide as they are drawn here (the TV sends none wider), and zoomed in,
        // of the part in view (asked again a moment after the zoom stops changing).
        val crop = geo.visible(boxW, boxH)
        // Upright (2.0.2), the TV is told the phone's shape too: it shows the game shaped like it, so the game fills the phone.
        val latestUp by rememberUpdatedState(up)
        DisposableEffect(constraints.maxWidth, up) {
            latestSink.mirror(true, constraints.maxWidth, null, up)
            onDispose { latestSink.mirror(false, 0, null) }
        }
        // (The part last asked for: the first request, of all of it, went as the view came.)
        var asked by remember { mutableStateOf<KeyLink.Crop?>(null) }
        LaunchedEffect(crop) {
            if (crop == asked) return@LaunchedEffect
            delay(120)
            latestSink.mirror(true, constraints.maxWidth, crop, latestUp)
            asked = crop
        }

        var finger by remember { mutableStateOf<Offset?>(null) }
        val description = stringResource(R.string.kb_touch_area)

        Canvas(
            Modifier.fillMaxSize().testTag(MIRROR_TAG).semantics { contentDescription = description }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (!latestGeo.onPage(down.position)) return@awaitEachGesture
                        down.consume()
                        // A second finger soon after the first: a zoom of this view. Lifted at once: a tap. Otherwise a touch.
                        var kind = 0
                        var upAt = down.position
                        withTimeoutOrNull(PINCH_WAIT_MS) {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.count { it.pressed } >= 2) { kind = 2; break }
                                val ch = ev.changes.firstOrNull { it.id == down.id }
                                if (ch == null || !ch.pressed) { kind = 3; upAt = ch?.position ?: down.position; break }
                                if ((ch.position - down.position).getDistance() > viewConfiguration.touchSlop) { kind = 1; break }
                            }
                        }
                        when (kind) {
                            2 -> while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.none { it.pressed }) break
                                val factor = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                if (factor != 1f || pan != Offset.Zero) transform(ev.calculateCentroid(useCurrent = true), pan, factor)
                                ev.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            3 -> {
                                val s = latestGeo.share(down.position)
                                latestSink.touch(KeyLink.TouchAction.DOWN, s.x, s.y)
                                val e = latestGeo.share(upAt)
                                latestSink.touch(KeyLink.TouchAction.UP, e.x, e.y)
                            }
                            else -> {
                                finger = down.position
                                var last = latestGeo.share(down.position)
                                var ended = false
                                latestSink.touch(KeyLink.TouchAction.DOWN, last.x, last.y)
                                var sentAt = down.uptimeMillis
                                try {
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!ch.pressed) {
                                            last = latestGeo.share(ch.position)
                                            latestSink.touch(KeyLink.TouchAction.UP, last.x, last.y)
                                            ended = true
                                            break
                                        }
                                        if (ch.positionChanged()) {
                                            ch.consume()
                                            finger = ch.position
                                            last = latestGeo.share(ch.position)
                                            if (ch.uptimeMillis - sentAt >= MOVE_EVERY_MS) {
                                                latestSink.touch(KeyLink.TouchAction.MOVE, last.x, last.y)
                                                sentAt = ch.uptimeMillis
                                            }
                                        }
                                    }
                                } finally {
                                    // Taken away mid-touch (another gesture, the screen leaving): the TV's finger is lifted too.
                                    if (!ended) latestSink.touch(KeyLink.TouchAction.CANCEL, last.x, last.y)
                                    finger = null
                                }
                            }
                        }
                    }
                },
        ) {
            image?.let {
                // Each picture where its part of the page is: an older, wider one is drawn enlarged until the sharper one comes.
                val c = imageCrop
                val x = geo.pageX + (c?.x0 ?: 0f) * geo.pageW
                val y = geo.pageY + (c?.y0 ?: 0f) * geo.pageH
                val w = (c?.w ?: 1f) * geo.pageW
                val h = (c?.h ?: 1f) * geo.pageH
                drawImage(
                    it, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.Low,
                )
            }
            finger?.let {
                val r = 16.dp.toPx()
                drawCircle(Palette.Cream.copy(alpha = 0.45f), radius = r, center = it)
                drawCircle(Palette.Red, radius = r, center = it, style = Stroke(3.dp.toPx()))
            }
        }
        if (image == null) {
            val text = when (none) {
                KeyLink.NONE_HOME -> stringResource(R.string.kb_touch_home)
                KeyLink.NONE_CAPTURE -> stringResource(R.string.kb_touch_no_picture)
                else -> stringResource(R.string.kb_touch_waiting)
            }
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Cream, textAlign = TextAlign.Center)
            }
        }
        // The phone upright, the game over all of it; or on its side again (2.0.2). Said in words, at the corner across from the zoom.
        if (onRotate != null) {
            val label = stringResource(if (up > 0f) R.string.kb_touch_to_landscape else R.string.kb_touch_to_portrait)
            val desc = stringResource(if (up > 0f) R.string.kb_touch_to_landscape_desc else R.string.kb_touch_to_portrait_desc)
            Box(
                Modifier.align(Alignment.TopEnd).padding(8.dp).height(42.dp).clip(RoundedCornerShape(21.dp)).background(Palette.Ink.copy(alpha = 0.78f))
                    .border(BorderStroke(1.dp, Palette.Cream.copy(alpha = 0.5f)), RoundedCornerShape(21.dp))
                    .clickable(onClick = onRotate).semantics { role = Role.Button; contentDescription = desc }.testTag(MIRROR_ROTATE_TAG)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Cream) }
        }
        // Zoom with one hand: larger, smaller, and the whole game again.
        val middle = Offset(boxW / 2, boxH / 2)
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZoomKey("−", stringResource(R.string.kb_touch_zoom_out), enabled = zoom > 1f) { transform(middle, Offset.Zero, 1f / 1.5f) }
            ZoomKey("+", stringResource(R.string.kb_touch_zoom_in), enabled = zoom < MAX_MIRROR_ZOOM) { transform(middle, Offset.Zero, 1.5f) }
            ZoomKey("⤢", stringResource(R.string.kb_touch_zoom_fit), enabled = zoom > 1f) { zoom = 1f; cx = 0.5f; cy = 0.5f }
        }
    }
}

@Composable
private fun ZoomKey(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(Palette.Ink.copy(alpha = if (enabled) 0.78f else 0.35f))
            .border(BorderStroke(1.dp, Palette.Cream.copy(alpha = 0.5f)), CircleShape)
            .clickable(enabled = enabled, onClick = onClick).semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Cream.copy(alpha = if (enabled) 1f else 0.5f)) }
}

/** Where the page is drawn: its place at zoom 1 ([left], [top], [drawW] by [drawH]), the zoom, and the page's point at the middle. */
private data class Geometry(val left: Float, val top: Float, val drawW: Float, val drawH: Float, val zoom: Float, val cx: Float, val cy: Float) {
    val pageW: Float get() = drawW * zoom
    val pageH: Float get() = drawH * zoom
    val pageX: Float get() = left + drawW / 2 - cx * pageW
    val pageY: Float get() = top + drawH / 2 - cy * pageH

    /** A point of this view as shares of the page (kept on it). */
    fun share(p: Offset) = Offset(((p.x - pageX) / pageW).coerceIn(0f, 1f), ((p.y - pageY) / pageH).coerceIn(0f, 1f))

    /** On the page as it is drawn now (beside it, at zoom 1, a finger touches nothing). */
    fun onPage(p: Offset): Boolean = p.x in pageX..(pageX + pageW) && p.y in pageY..(pageY + pageH)

    /** The part of the page in view ([boxW] by [boxH]), or null for all of it (not zoomed). */
    fun visible(boxW: Float, boxH: Float): KeyLink.Crop? {
        if (zoom <= 1.001f) return null
        val a = share(Offset(0f, 0f)); val b = share(Offset(boxW, boxH))
        // To a hundredth of the page: a picture is asked again only for a real move.
        fun r(v: Float) = Math.round(v * 100f) / 100f
        return KeyLink.Crop(r(a.x), r(a.y), r(b.x), r(b.y)).takeIf { it.w >= 0.05f && it.h >= 0.05f }
    }
}
