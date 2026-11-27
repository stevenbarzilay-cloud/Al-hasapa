package app.couch.alhasapa.data

/**
 * How one stroke of a note's drawing is drawn (1.12): one of a few colours and one of three widths, kept as one number
 * (colour * 10 + width). A note from before has none: its strokes are all in the writer's colour, at one width.
 */
object Ink {
    /** The colours by number. "own" is the writer's own colour, "ink" the app's dark ink, "paper" the paper itself (the eraser). */
    val COLORS = listOf("own", "ink", "red", "orange", "mustard", "green", "teal", "blue", "purple", "pink", "paper")
    const val OWN = 0
    val PAPER = COLORS.indexOf("paper")
    const val WIDTHS = 3

    /** The writer's own colour, the middle width: what a drawing begins with. */
    const val DEFAULT = OWN * 10 + 1

    fun of(color: Int, width: Int): Int = color.coerceIn(0, COLORS.lastIndex) * 10 + width.coerceIn(0, WIDTHS - 1)
    fun color(ink: Int): Int = (ink / 10).coerceIn(0, COLORS.lastIndex)
    fun width(ink: Int): Int = (ink % 10).coerceIn(0, WIDTHS - 1)

    /** A stroke's width in thousandths of the paper's width (so a drawing looks the same on the phone and on the TV). */
    fun units(ink: Int): Float = (if (color(ink) == PAPER) ERASER else PEN)[width(ink)]

    private val PEN = floatArrayOf(5f, 11f, 24f)
    private val ERASER = floatArrayOf(18f, 36f, 70f)
}

/** The paper of a note's drawing (1.12), and the arithmetic of its strokes. No Android in here. */
object Doodles {
    /** The paper's width to its height: the same on the phone that draws and on the TV that shows. */
    const val ASPECT = 1.5f

    /**
     * [points] (x, y pairs) with the ones that add nothing left out (Ramer-Douglas-Peucker): every point left out lies within [eps] of
     * the line through the ones kept, so a long smooth stroke is a handful of points, and a drawing has room for many strokes.
     */
    fun simplify(points: FloatArray, eps: Float): FloatArray {
        val n = points.size / 2
        if (n <= 2) return points.copyOf(n * 2)
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            val ax = points[2 * a]; val ay = points[2 * a + 1]; val bx = points[2 * b]; val by = points[2 * b + 1]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            var far = -1; var most = eps * eps
            for (i in a + 1 until b) {
                val px = points[2 * i] - ax; val py = points[2 * i + 1] - ay
                val d2 = if (len2 == 0f) px * px + py * py else {
                    val t = ((px * dx + py * dy) / len2).coerceIn(0f, 1f)
                    val ex = px - t * dx; val ey = py - t * dy
                    ex * ex + ey * ey
                }
                if (d2 > most) { most = d2; far = i }
            }
            if (far >= 0) { keep[far] = true; stack.addLast(a to far); stack.addLast(far to b) }
        }
        val out = FloatArray(keep.count { it } * 2)
        var k = 0
        for (i in 0 until n) if (keep[i]) { out[k++] = points[2 * i]; out[k++] = points[2 * i + 1] }
        return out
    }
}
