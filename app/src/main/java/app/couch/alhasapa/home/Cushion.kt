package app.couch.alhasapa.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.core.graphics.PathParser
import app.couch.alhasapa.ui.theme.Palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * The TV home's games are cushions on a shelf (1.9.5): the fabric, the little folds and the prints at the edges are the ones on the
 * cushions of our own illustration (the couple on the couch), the symbol in the middle says what kind of game it is, and the colours
 * come from the two colours the people chose for their pieces. The drawing and the rules for the colours were given as a demo page
 * (an SVG and a few lines of arithmetic); this is that drawing, done with the app's own canvas, so nothing is loaded per game and the
 * shapes exist once.
 */

/** What kind of game a cushion holds: one symbol each, the same for every game of the kind. */
enum class Category(val id: String, /** Its own name, where the catalogue's kinds have none for it. */ val ownLabel: String? = null) {
    WORDS("words"), LOGIC("logic"), TRIVIA("trivia"), CINEMA("cinema", "קולנוע"), MUSIC("music"), GEOGRAPHY("geography"), VISUAL("visual");

    companion object {
        /**
         * By the game's own hint (the catalogue's `cat`, for the film games: their kind is "ידע" like any quiz), else by its kind.
         * A kind nobody thought of gets the quiz's question mark, as the hub gives it the quiz's symbol.
         */
        fun of(kind: String, hint: String?): Category = entries.firstOrNull { it.id == hint } ?: when (kind) {
            "מילים" -> WORDS
            "היגיון", "מספרים" -> LOGIC
            "מוזיקה" -> MUSIC
            "גיאוגרפיה" -> GEOGRAPHY
            "חושים", "זיכרון" -> VISUAL
            else -> TRIVIA
        }
    }
}

/**
 * How one game's cushion looks: the fabric, the two colours of its print, the dark of its zigzag, the colour of its symbol and name,
 * and which of the edges and prints it has. Made once for a game from its id and the two people's colours ([CushionColors.look]), so
 * a game keeps its cushion wherever it stands on the shelf.
 */
data class CushionLook(
    val base: Color,
    val bright: Color,
    val accent: Color,
    val detail: Color,
    val type: Color,
    val outline: Int,
    val print: Int,
    val flip: Boolean,
)

/** The colours of a cushion, from a game's id and the two people's colours. Plain arithmetic, no Android. */
object CushionColors {
    private val CREAM = intArrayOf(251, 237, 214)
    private val INK = intArrayOf(46, 30, 44)
    private val WHITE = intArrayOf(255, 255, 255)
    private val POP = intArrayOf(233, 93, 64)
    private val TINTS = doubleArrayOf(-.16, .18, .42, .57, .71, .85, .95)

    /** Text of this size on a fabric needs at least this contrast to be read from a couch (WCAG AA for text). */
    const val MIN_CONTRAST = 4.5

    /** A number from a game's id that does not change (FNV-1a over its letters): what its cushion is chosen by. */
    fun seed(key: String): Int {
        var s = 2166136261L.toInt()
        var i = 0
        while (i < key.length) {
            val cp = key.codePointAt(i)
            s = (s xor key[i].code) * 16777619
            i += Character.charCount(cp)
        }
        return s
    }

    private fun rgb(c: Color): IntArray = intArrayOf((c.red * 255f).roundToInt(), (c.green * 255f).roundToInt(), (c.blue * 255f).roundToInt())
    private fun color(c: IntArray): Color = Color(c[0], c[1], c[2])
    private fun mix(a: IntArray, b: IntArray, amount: Double): IntArray = IntArray(3) { Math.round(a[it] * (1 - amount) + b[it] * amount).toInt() }

    private fun luminance(c: IntArray): Double {
        fun lin(n: Int): Double { val v = n / 255.0; return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4) }
        return .2126 * lin(c[0]) + .7152 * lin(c[1]) + .0722 * lin(c[2])
    }

    private fun contrast(a: IntArray, b: IntArray): Double {
        val x = luminance(a); val y = luminance(b)
        return (max(x, y) + .05) / (min(x, y) + .05)
    }

    /** How well [a] reads on [b], 1 (not at all) to 21 (black on white). */
    fun contrast(a: Color, b: Color): Double = contrast(rgb(a), rgb(b))

    /** Ink or white, whichever reads better on [base]. */
    private fun inkOn(base: IntArray): IntArray = if (contrast(base, INK) >= contrast(base, WHITE)) INK else WHITE

    /** The demo's rule, to the digit (see the tests' golden values). */
    internal fun raw(key: String, a: Color, b: Color): CushionLook {
        val s = seed(key).toUInt()
        val players = arrayOf(rgb(a), rgb(b))
        val at = (s % 2u).toInt()
        val first = players[at]; val second = players[(at + 1) % 2]
        val pigment = mix(first, second, ((s shr 8) % 29u).toInt() / 100.0)
        val tint = TINTS[((s shr 16) % TINTS.size.toUInt()).toInt()]
        val base = mix(pigment, if (tint < 0) INK else CREAM, abs(tint))
        val bright = if (tint < .25) mix(first, CREAM, .77) else mix(first, POP, .25)
        val accent = mix(second, CREAM, if (tint < .4) .62 else .24)
        return CushionLook(
            base = color(base), bright = color(bright), accent = color(accent), detail = color(mix(INK, second, .12)), type = color(inkOn(base)),
            outline = ((s shr 4) % 2u).toInt(), print = ((s shr 12) % 5u).toInt(), flip = s % 2u == 1u,
        )
    }

    /**
     * The cushion of the game [key] for the people's colours [a] and [b]. As [raw], and then the name and the symbol are made sure of:
     * where neither ink nor white reads well enough on the fabric (a middling tone, which two similar colours make more of), the
     * fabric goes lighter or darker, a little at a time, until one of them does.
     */
    fun look(key: String, a: Color, b: Color): CushionLook {
        val r = raw(key, a, b)
        var base = rgb(r.base)
        var type = inkOn(base)
        var steps = 0
        while (contrast(base, type) < MIN_CONTRAST && steps < 12) {
            base = mix(base, if (type === INK) CREAM else INK, .1)
            type = inkOn(base)
            steps++
        }
        return if (steps == 0) r else r.copy(base = color(base), type = color(type))
    }

    /** The wall behind the shelves: cream, with a breath of the two colours. */
    fun wall(a: Color, b: Color): Color = color(mix(CREAM, mix(rgb(a), rgb(b), .5), .10))
}

/**
 * The cushion's shapes, in the demo's own units (a box of 232 by 150), made once and shared by every cushion: two edges, five prints,
 * the shading and the folds, and the seven symbols.
 */
internal object CushionArt {
    const val W = 232f
    const val H = 150f

    private val THREAD_DARK = Color(0xFF402C37)
    private val THREAD_LIGHT = Color(0xFFFFF6DD)
    private val LINE = Color(0xFF402B37)
    private val SHADE = Color(0xFF432035)
    private val SHINE = Color(0xFFFFF5DF)

    private fun path(d: String): Path = PathParser.createPathFromPathData(d).asComposePath()
    private fun circle(cx: Float, cy: Float, r: Float) = oval(cx, cy, r, r)
    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = "M${cx - rx} ${cy}a$rx $ry 0 1 0 ${2 * rx} 0a$rx $ry 0 1 0 ${-2 * rx} 0z"
    private fun box(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}z"

    private val edges: List<Path> by lazy {
        listOf(
            "M12 11C15 7 26 12 39 14Q65 4 107 7Q162 3 193 13C207 11 218 6 221 10Q222 18 217 28Q230 73 217 112Q218 123 222 133Q223 140 211 137L193 133Q154 145 114 141Q64 145 36 134L17 139Q8 141 10 132L15 115Q2 72 14 33Q12 21 12 11Z",
            "M11 14Q11 7 21 10L39 15Q73 4 118 8Q161 5 190 14L211 9Q223 5 220 17L216 31Q229 70 218 111L222 132Q224 140 216 137L194 132Q156 144 121 141Q69 146 38 133L18 138Q7 141 11 131L15 114Q2 77 14 37Z",
        ).map(::path)
    }

    /** A piece of a print: a patch in one of the print's two colours, or a line (the zigzag, the stair) in the cushion's dark. */
    private class Part(val path: Path, val role: Int)
    private const val BRIGHT = 0
    private const val ACCENT = 1
    private const val LINE_ROLE = 2

    /** The zigzag and the stair are lines 7 units thick with square ends and sharp corners; here as the shape such a line covers. */
    private fun thick(d: String): Path {
        val line = PathParser.createPathFromPathData(d)
        val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 7f
            strokeCap = android.graphics.Paint.Cap.BUTT; strokeJoin = android.graphics.Paint.Join.MITER; strokeMiter = 4f
        }
        val shape = android.graphics.Path()
        return if (paint.getFillPath(line, shape) && !shape.isEmpty) shape.asComposePath() else line.asComposePath()
    }

    /** The circle, the stair and the zigzag of the cushions on our couch, five ways. */
    private val prints: List<List<Part>> by lazy {
        fun bright(d: String) = Part(path(d), BRIGHT)
        fun accent(d: String) = Part(path(d), ACCENT)
        fun line(d: String) = Part(thick(d), LINE_ROLE)
        listOf(
            listOf(bright(circle(31f, 34f, 25f)), accent("m178 10 39 2-14 29Z"), line("m-8 119 14-12 11 15 11-11 11 13"), accent(circle(220f, 125f, 22f))),
            listOf(bright("M-6 69Q2 14 66 7L53 27Q24 29 14 71Z"), accent("m179 12 28 18-23 7Z"), line("m201 80 15-10 11 12 12-10"), accent(circle(24f, 134f, 21f))),
            listOf(accent("M0 0h67L30 43Z"), bright(circle(201f, 28f, 16f)), line("M0 134h9v-13h12v-12h11"), bright("M179 152q8-34 49-46l5 16q-25 3-31 30Z")),
            listOf(line("m1 32 15 13 14-14 13 13 11-11"), bright("M165 8q16 28 57 30l-6-25Z"), bright("m0 138 33-31 10 32Z"), accent(circle(216f, 104f, 13f))),
            listOf(accent(circle(22f, 67f, 15f)), bright("M160 0h64v54Q196 22 160 20Z"), bright("M0 132q20-26 47-26l-8 20q-18 3-23 15Z"), line("M193 149v-13h14v-15h14v-12")),
        )
    }

    private val shade: Path by lazy { path("M5 82Q11 111 30 127Q52 140 112 136Q158 139 195 126Q211 120 224 101L230 153H0Z") }
    private val shine: Path by lazy { path("M21 21Q57 5 107 9Q63 10 42 19Q33 22 26 29Z") }

    /**
     * Everything inside a cushion (its print, mirrored or not, its shading and its shine), already cut to the cushion's edge. Cutting
     * while drawing is the dearest thing a cushion would ask of a TV box, for every cushion in every frame; cut here, once for each
     * of the twenty ways an edge, a print and a mirroring go together, drawing a cushion is filling a dozen ready shapes.
     */
    private class Inside(val parts: List<Part>, val shade: Path, val shine: Path)

    private val insides = HashMap<Int, Inside?>()

    private fun inside(outline: Int, print: Int, flip: Boolean): Inside? = insides.getOrPut(outline * 100 + print * 2 + (if (flip) 1 else 0)) {
        runCatching {
            val edge = edges[outline]
            fun cut(p: Path, mirrored: Boolean): Path {
                val src = if (!mirrored) p else Path().apply {
                    addPath(p)
                    asAndroidPath().transform(android.graphics.Matrix().apply { setScale(-1f, 1f); postTranslate(W, 0f) })
                }
                return Path.combine(PathOperation.Intersect, src, edge)
            }
            Inside(prints[print].map { Part(cut(it.path, flip), it.role) }, cut(shade, false), cut(shine, false))
        }.getOrNull()             // a platform that cannot cut shapes: the cushion is then cut while it is drawn, as before
    }
    private val folds: Path by lazy { path("M14 13q7 6 12 14m190-14q-6 6-8 13M13 135q10-8 12-15m192 14q-9-8-11-15") }
    private val faintFolds: Path by lazy { path("M20 39q-6 14-6 32m200 18q-1 12-5 20M43 132q17 5 32 6m75-1q21-.5 34-5") }

    /** For the tests: whether the inside of this cushion is cut ready (true wherever shapes can be cut, which is every real device). */
    internal fun isCutReady(outline: Int, print: Int, flip: Boolean): Boolean = inside(outline, print, flip) != null

    /** A symbol on a grid of 24: its lines, and its filled dots. */
    private class Icon(val lines: Path, val dots: Path? = null)

    private val icons: Map<Category, Icon> by lazy {
        mapOf(
            Category.WORDS to Icon(path(box(3f, 3f, 18f, 18f, 3f) + "M8 7l9 10M10 9l-2 8M14 12l2-5")),
            Category.LOGIC to Icon(path(box(9f, 2f, 6f, 6f, 1f) + "M12 8v4M5 16v-4h14v4" + box(2f, 16f, 6f, 6f, 1f) + box(16f, 16f, 6f, 6f, 1f))),
            Category.TRIVIA to Icon(path(circle(12f, 12f, 10f) + "M9 9a3 3 0 1 1 4.7 2.5c-1.4.8-1.7 1.4-1.7 2.6"), path(circle(12f, 17f, 1f))),
            Category.CINEMA to Icon(path(box(2.5f, 5f, 19f, 14f, 2f) + "M7 5v14m10-14v14M3 10h4m-4 4h4m10-4h4m-4 4h4")),
            Category.MUSIC to Icon(path("M9 17V6l11-3v12M9 9l11-3" + oval(6f, 18f, 3f, 2.5f) + oval(17f, 16f, 3f, 2.5f))),
            Category.GEOGRAPHY to Icon(path(circle(12f, 12f, 9.5f) + oval(12f, 12f, 4f, 9.5f) + "M3 12h18")),
            Category.VISUAL to Icon(path("M2 12s3.8-7 10-7 10 7 10 7-3.8 7-10 7S2 12 2 12Z" + circle(12f, 12f, 3f))),
        )
    }

    /** Where the symbol sits on the cushion and how large (the demo's own numbers): in the middle, over the name. */
    private const val ICON_X = 93f
    private const val ICON_Y = 31f
    private const val ICON_SCALE = 1.92f

    /** How far the focus's threads reach beyond the cushion's edge, in the cushion's units: what a cushion needs around it. */
    const val THREADS_REACH = 9f

    /**
     * The mark of the focus: a double thread around the cushion's edge, a dark one and inside it a light one, as the demo has it;
     * and, [oldMark], outside those the red that marked the focus on the home until now (kept until the threads have been seen on
     * the TV itself). Drawn under the cushion, so only what reaches beyond its edge shows.
     *
     * Apart from the cushion on purpose: a cushion is drawn once and kept as a picture, and moving the focus then costs these three
     * lines and nothing of the cushion.
     */
    fun DrawScope.threads(outline: Int, at: Offset, unit: Float, oldMark: Boolean = true) {
        val edge = edges[outline]
        withTransform({ translate(at.x, at.y); scale(unit, unit, pivot = Offset.Zero) }) {
            if (oldMark) drawPath(edge, Palette.Red, style = Stroke(2 * THREADS_REACH, join = StrokeJoin.Round))
            drawPath(edge, THREAD_DARK, style = Stroke(9f, join = StrokeJoin.Round))
            drawPath(edge, THREAD_LIGHT, style = Stroke(6f, join = StrokeJoin.Round))
        }
    }

    /** Draws the cushion into a box of [W] by [H] units, [unit] pixels each, whose top left corner is at [at]. */
    fun DrawScope.cushion(look: CushionLook, category: Category, played: Boolean, at: Offset, unit: Float) {
        val edge = edges[look.outline]
        withTransform({ translate(at.x, at.y); scale(unit, unit, pivot = Offset.Zero) }) {
            drawPath(edge, look.base)
            fun parts(list: List<Part>) {
                for (part in list) drawPath(part.path, when (part.role) { BRIGHT -> look.bright; ACCENT -> look.accent; else -> look.detail })
            }
            val ready = inside(look.outline, look.print, look.flip)
            if (ready != null) {
                parts(ready.parts)
                drawPath(ready.shade, SHADE, alpha = .16f)
                drawPath(ready.shine, SHINE, alpha = .34f)
            } else clipPath(edge) {
                withTransform({ if (look.flip) { translate(W, 0f); scale(-1f, 1f, pivot = Offset.Zero) } }) { parts(prints[look.print]) }
                drawPath(shade, SHADE, alpha = .16f)
                drawPath(shine, SHINE, alpha = .34f)
            }
            drawPath(edge, LINE, style = Stroke(2.6f, join = StrokeJoin.Round))
            drawPath(folds, LINE, style = Stroke(1.8f, cap = StrokeCap.Round))
            drawPath(faintFolds, LINE, alpha = .55f, style = Stroke(1f, cap = StrokeCap.Round))
            withTransform({ translate(ICON_X, ICON_Y); scale(ICON_SCALE, ICON_SCALE, pivot = Offset.Zero) }) {
                val icon = icons.getValue(category)
                drawPath(icon.lines, look.type, style = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                icon.dots?.let { drawPath(it, look.type) }
            }
            if (played) {
                // Played this evening: the stamp the old card had, on the cushion's corner.
                drawCircle(Palette.Ink, radius = 15f, center = Offset(198f, 30f))
                drawPath(tick, Palette.Cream, style = Stroke(3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }

    private val tick: Path by lazy { path("M190.5 30.5l5.5 5.5 10-11.5") }
}
