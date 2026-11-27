package app.couch.alhasapa.keyboard

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.contentDescription
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
import kotlinx.coroutines.withContext

/** The picture of the TV's game in touch mode, for the tests. */
const val MIRROR_TAG = "mirror"

/** At most one move each this many ms goes to the TV (its screen draws no faster). */
private const val MOVE_EVERY_MS = 16L

/**
 * Touch mode (1.9.8): the TV's game, pictured here a few times a second, to touch as if it were on this screen. A finger down on the
 * picture is a finger down on the game at the same spot, moving it drags, lifting it lets go; a swipe scrolls the page. For the games
 * played by dragging (Dialed's colours, Pips' dominoes, TimeGuessr's map), which a paw moved by the remote plays badly. The picture
 * comes a little late (it is sent over the home network); the touch itself reaches the TV at once, and the TV shows where it is.
 */
@Composable
internal fun MirrorPad(frame: KeyClient.MirrorFrame?, none: String?, sink: KeySink, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.Ink)) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        // Pictures are asked for while this is on the screen, as wide as they are drawn here (the TV sends none wider).
        val latestSink by rememberUpdatedState(sink)
        DisposableEffect(constraints.maxWidth) {
            latestSink.mirror(true, constraints.maxWidth)
            onDispose { latestSink.mirror(false, 0) }
        }
        var image by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(frame) {
            val f = frame ?: return@LaunchedEffect
            val bmp = withContext(Dispatchers.Default) { runCatching { BitmapFactory.decodeByteArray(f.jpeg, 0, f.jpeg.size) }.getOrNull() }
            if (bmp != null) image = bmp.asImageBitmap()
            // Said once it is drawn: the TV sends the next one only then, so a slow moment here never piles pictures up.
            latestSink.frameShown(f.n)
        }
        // The TV said there is nothing to picture (no game, or it could not): the old picture goes.
        LaunchedEffect(frame, none) { if (frame == null && none != null) image = null }

        // Where the picture is: as large as fits, in the middle. The touches are shares of it (0 to 1), whatever its size here or there.
        val ratio = frame?.let { if (it.h > 0) it.w.toFloat() / it.h else null } ?: image?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
        val drawW = minOf(boxW, boxH * ratio)
        val drawH = drawW / ratio
        val left = (boxW - drawW) / 2
        val top = (boxH - drawH) / 2
        var finger by remember { mutableStateOf<Offset?>(null) }
        val description = stringResource(R.string.kb_touch_area)

        Canvas(
            Modifier.fillMaxSize().testTag(MIRROR_TAG).semantics { contentDescription = description }
                .pointerInput(left, top, drawW, drawH) {
                    fun share(p: Offset) = Offset(((p.x - left) / drawW).coerceIn(0f, 1f), ((p.y - top) / drawH).coerceIn(0f, 1f))
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val p = down.position
                        // A finger that lands beside the picture does nothing.
                        if (p.x < left || p.x > left + drawW || p.y < top || p.y > top + drawH) return@awaitEachGesture
                        down.consume()
                        finger = p
                        var last = share(p)
                        var ended = false
                        latestSink.touch(KeyLink.TouchAction.DOWN, last.x, last.y)
                        var sentAt = down.uptimeMillis
                        try {
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) {
                                    last = share(ch.position)
                                    latestSink.touch(KeyLink.TouchAction.UP, last.x, last.y)
                                    ended = true
                                    break
                                }
                                if (ch.positionChanged()) {
                                    ch.consume()
                                    finger = ch.position
                                    last = share(ch.position)
                                    if (ch.uptimeMillis - sentAt >= MOVE_EVERY_MS) {
                                        latestSink.touch(KeyLink.TouchAction.MOVE, last.x, last.y)
                                        sentAt = ch.uptimeMillis
                                    }
                                }
                            }
                        } finally {
                            // Taken away mid-touch (another finger's gesture, the screen leaving): the TV's finger is lifted too.
                            if (!ended) latestSink.touch(KeyLink.TouchAction.CANCEL, last.x, last.y)
                            finger = null
                        }
                    }
                },
        ) {
            image?.let {
                drawImage(
                    it, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
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
    }
}
