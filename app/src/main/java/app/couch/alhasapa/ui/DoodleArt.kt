package app.couch.alhasapa.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.couch.alhasapa.data.Ink
import app.couch.alhasapa.ui.theme.Palette

/**
 * How a note's drawing is drawn (1.12), the same on the phone that makes it and on the TV that shows it: each stroke a smooth line
 * through its points, round at its ends, in its own colour and width ([Ink]); the widths are shares of the paper's width, so a drawing
 * keeps its look at any size.
 */
object DoodleArt {
    /** The paper a note is written on: the eraser paints in it. */
    val PAPER = Color(0xFFFFF8E1)

    /** The drawing's colours, by their names in [Ink.COLORS]: the pieces' own colours (assets/hub/pieces.json), and the app's ink. */
    private val NAMED = mapOf(
        "red" to Color(0xFFD94E38), "orange" to Color(0xFFEE8A3C), "mustard" to Color(0xFFE6B53A), "green" to Color(0xFF6AA874),
        "teal" to Color(0xFF44A3AB), "blue" to Color(0xFF5B84D0), "purple" to Color(0xFF9468C4), "pink" to Color(0xFFE8A5B5),
    )

    /** The colour of [ink]; [own] is the writer's. */
    fun color(ink: Int, own: Color): Color = when (val name = Ink.COLORS[Ink.color(ink)]) {
        "own" -> own
        "ink" -> Palette.Ink
        "paper" -> PAPER
        else -> NAMED[name] ?: own
    }

    /** The named colour as the pieces' file has it (for the test that keeps the two alike). */
    internal fun named(name: String): Color? = NAMED[name]

    /**
     * A stroke through [n] points: a line that bends smoothly (each point pulls the line towards it, and the line passes through the
     * middle between every two), so a hand's quick curve does not come out as corners.
     */
    fun path(n: Int, x: (Int) -> Float, y: (Int) -> Float): Path {
        val p = Path()
        if (n == 0) return p
        p.moveTo(x(0), y(0))
        if (n == 2) { p.lineTo(x(1), y(1)); return p }
        for (i in 1 until n - 1) {
            val mx = (x(i) + x(i + 1)) / 2f; val my = (y(i) + y(i + 1)) / 2f
            p.quadraticTo(x(i), y(i), mx, my)
        }
        if (n > 2) p.lineTo(x(n - 1), y(n - 1))
        return p
    }

    /** One stroke of [n] points, [width] wide: a dot when it is a single point. */
    fun DrawScope.stroke(n: Int, x: (Int) -> Float, y: (Int) -> Float, color: Color, width: Float) {
        if (n <= 0) return
        if (n == 1) { drawCircle(color, radius = width / 2f, center = Offset(x(0), y(0))); return }
        drawPath(path(n, x, y), color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    /**
     * A whole drawing into this box: [doodle]'s strokes (x, y pairs in thousandths of the box), each in its [ink] ([own] is the
     * writer's colour). With no [ink] (a note from before 1.12) every stroke is in the writer's colour, at one width.
     */
    fun DrawScope.doodle(doodle: List<IntArray>, ink: List<Int>?, own: Color) {
        val w = size.width; val h = size.height
        val plain = 4.dp.toPx()
        doodle.forEachIndexed { i, s ->
            val k = ink?.getOrNull(i)
            stroke(
                s.size / 2, { s[2 * it] / 1000f * w }, { s[2 * it + 1] / 1000f * h },
                if (k == null) own else color(k, own), if (k == null) plain else Ink.units(k) / 1000f * w,
            )
        }
    }
}
