package app.couch.alhasapa.tv

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import app.couch.alhasapa.ui.theme.Palette

/** What the free cursor (the "paw" mode) looks like: chosen in the TV's settings. */
enum class CursorShape(val id: String, val label: String) {
    PAW("paw", "כפה"),
    MOUSE("mouse", "עכבר"),
    FISH("fish", "דג"),
    YARN("yarn", "כדור צמר"),
    ARROW("arrow", "חץ");

    companion object {
        fun of(id: String?): CursorShape = entries.firstOrNull { it.id == id } ?: PAW
    }
}

/** How large the cursor is, in dp of the screen (the paw of 1.9.5 was about 40). */
enum class CursorSize(val id: String, val label: String, val dp: Float) {
    SMALL("s", "קטן", 22f),
    MEDIUM("m", "בינוני", 30f),
    LARGE("l", "גדול", 42f);

    companion object {
        fun of(id: String?): CursorSize = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

/**
 * The cursors. Every one points with a tip at its top left corner, and that tip is where a tap lands (a small red dot marks it); the
 * rest of it lies below and to the right, away from what is being pointed at. (Until 1.9.5 the paw sat on top of the point it tapped,
 * and covered what was under it.)
 */
object CursorArt {
    /** Where the tip is, as a share of the drawing's size: what is drawn is moved so the tip is on the point. */
    const val TIP = 0.06f

    private val GREY = Color(0xFFA39AA1)
    private val PINK = Color(0xFFE8A5B5)
    private val ORANGE = Color(0xFFEE8A3C)

    /**
     * Draws [shape] in a box of [size] (its top left corner at [at]); [accent] is the colour of the app's interface (the plain arrow wears
     * it), [pressed] while the finger is down (a drag).
     */
    fun DrawScope.cursor(shape: CursorShape, at: Offset, size: Float, accent: Color, pressed: Boolean) {
        val s = size
        val line = (s / 16f).coerceAtLeast(1.5f)
        val tip = Offset(at.x + s * TIP, at.y + s * TIP)
        withTransform({ translate(at.x, at.y) }) {
            when (shape) {
                CursorShape.ARROW -> arrow(s, accent, line)
                CursorShape.PAW -> paw(s, if (pressed) Palette.Red else Palette.Ink, line)
                CursorShape.MOUSE -> mouse(s, line)
                CursorShape.FISH -> fish(s, line)
                CursorShape.YARN -> yarn(s, accent, line)
            }
        }
        // The tip: where the tap lands. Larger while the finger is down.
        val r = if (pressed) s * 0.11f else s * 0.065f
        drawCircle(Palette.Cream, radius = r + line * 0.8f, center = tip)
        drawCircle(Palette.Red, radius = r, center = tip)
    }

    private fun path(s: Float, vararg points: Pair<Float, Float>): Path = Path().apply {
        points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * s, y * s) else lineTo(x * s, y * s) }
        close()
    }

    /** The plain pointer, in the interface's colour, with a cream rim and an ink edge so it shows on any page. */
    private fun DrawScope.arrow(s: Float, fill: Color, line: Float) {
        val p = path(s, TIP to TIP, TIP to 0.80f, 0.25f to 0.62f, 0.38f to 0.93f, 0.52f to 0.87f, 0.39f to 0.57f, 0.66f to 0.57f)
        drawPath(p, Palette.Cream, style = Stroke(line * 3.2f, join = StrokeJoin.Round))
        drawPath(p, fill)
        drawPath(p, Palette.Ink, style = Stroke(line, join = StrokeJoin.Round))
    }

    /** A cat's paw reaching up and left: the pad and four toes, the tip at the end of the first toe. */
    private fun DrawScope.paw(s: Float, fill: Color, line: Float) {
        rotate(-35f, pivot = Offset(s * 0.55f, s * 0.55f)) {
            fun blob(cx: Float, cy: Float, r: Float) {
                drawCircle(Palette.Cream, radius = (r + 0.05f) * s, center = Offset(cx * s, cy * s))
                drawCircle(fill, radius = r * s, center = Offset(cx * s, cy * s))
            }
            blob(0.55f, 0.66f, 0.24f)
            blob(0.25f, 0.43f, 0.11f)
            blob(0.44f, 0.25f, 0.11f)
            blob(0.66f, 0.25f, 0.11f)
            blob(0.85f, 0.43f, 0.11f)
        }
    }

    /** A little grey mouse running to the point, its nose first; pink ears, a curl of a tail. */
    private fun DrawScope.mouse(s: Float, line: Float) {
        rotate(45f, pivot = Offset(s * 0.5f, s * 0.5f)) {
            val body = Path().apply {
                // A teardrop lying along the diagonal: the nose at the top left (after the turn), the round back at the bottom right.
                moveTo(0.5f * s, 0.02f * s)
                cubicTo(0.70f * s, 0.20f * s, 0.80f * s, 0.62f * s, 0.62f * s, 0.80f * s)
                cubicTo(0.55f * s, 0.86f * s, 0.45f * s, 0.86f * s, 0.38f * s, 0.80f * s)
                cubicTo(0.20f * s, 0.62f * s, 0.30f * s, 0.20f * s, 0.5f * s, 0.02f * s)
                close()
            }
            val tail = Path().apply {
                moveTo(0.5f * s, 0.84f * s)
                cubicTo(0.52f * s, 1.02f * s, 0.78f * s, 0.98f * s, 0.70f * s, 0.86f * s)
            }
            drawPath(tail, Palette.Ink, style = Stroke(line * 1.4f, cap = StrokeCap.Round))
            for (x in listOf(0.34f, 0.66f)) {
                drawCircle(Palette.Ink, radius = 0.13f * s, center = Offset(x * s, 0.36f * s))
                drawCircle(PINK, radius = 0.10f * s, center = Offset(x * s, 0.36f * s))
            }
            drawPath(body, GREY)
            drawPath(body, Palette.Ink, style = Stroke(line, join = StrokeJoin.Round))
            drawCircle(Palette.Ink, radius = 0.035f * s, center = Offset(0.43f * s, 0.24f * s))
            drawCircle(Palette.Ink, radius = 0.035f * s, center = Offset(0.57f * s, 0.24f * s))
            drawCircle(PINK, radius = 0.04f * s, center = Offset(0.5f * s, 0.05f * s))
        }
    }

    /** An orange fish swimming to the point, mouth first, with a tail and two stripes. */
    private fun DrawScope.fish(s: Float, line: Float) {
        rotate(45f, pivot = Offset(s * 0.5f, s * 0.5f)) {
            val tail = path(s, 0.5f to 0.70f, 0.30f to 0.98f, 0.70f to 0.98f)
            drawPath(tail, ORANGE)
            drawPath(tail, Palette.Ink, style = Stroke(line, join = StrokeJoin.Round))
            val body = Path().apply {
                moveTo(0.5f * s, 0.02f * s)
                cubicTo(0.78f * s, 0.18f * s, 0.78f * s, 0.58f * s, 0.5f * s, 0.76f * s)
                cubicTo(0.22f * s, 0.58f * s, 0.22f * s, 0.18f * s, 0.5f * s, 0.02f * s)
                close()
            }
            drawPath(body, ORANGE)
            for (y in listOf(0.42f, 0.56f)) drawLine(Palette.Cream, Offset(0.36f * s, y * s), Offset(0.64f * s, y * s), strokeWidth = line * 1.2f, cap = StrokeCap.Round)
            drawPath(body, Palette.Ink, style = Stroke(line, join = StrokeJoin.Round))
            drawCircle(Palette.Ink, radius = 0.04f * s, center = Offset(0.43f * s, 0.24f * s))
        }
    }

    /** A ball of yarn in the interface's colour, its loose thread reaching to the point. */
    private fun DrawScope.yarn(s: Float, fill: Color, line: Float) {
        val c = Offset(0.6f * s, 0.6f * s)
        val r = 0.34f * s
        val thread = Path().apply {
            moveTo(TIP * s, TIP * s)
            cubicTo(0.30f * s, 0.10f * s, 0.12f * s, 0.34f * s, 0.34f * s, 0.40f * s)
        }
        drawPath(thread, Palette.Ink, style = Stroke(line * 1.3f, cap = StrokeCap.Round))
        drawCircle(fill, radius = r, center = c)
        // The wound thread: three arcs across the ball.
        for (k in 0 until 3) {
            val y = c.y - r * 0.5f + k * r * 0.5f
            drawArc(Palette.Cream, startAngle = 200f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(c.x - r * 0.9f, y - r * 0.5f), size = Size(r * 1.8f, r), style = Stroke(line, cap = StrokeCap.Round))
        }
        drawCircle(Palette.Ink, radius = r, center = c, style = Stroke(line))
    }
}
