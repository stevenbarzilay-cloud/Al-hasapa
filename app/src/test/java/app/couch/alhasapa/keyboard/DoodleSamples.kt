package app.couch.alhasapa.keyboard

import app.couch.alhasapa.data.Doodles
import app.couch.alhasapa.data.Ink
import kotlin.math.cos
import kotlin.math.sin

/** A drawing for the pictures the tests make (build/previews): a sun, a smiling face, a heart and some grass, each with its own pen. */
object DoodleSamples {
    private fun ink(name: String, width: Int) = Ink.of(Ink.COLORS.indexOf(name), width)

    /** Points of an arc around ([cx], [cy]) of radius [r] (in thousandths of the paper's width), from [from] to [to] degrees. */
    private fun arc(cx: Float, cy: Float, r: Float, from: Int, to: Int, step: Int = 6): FloatArray {
        val pts = ArrayList<Float>()
        var a = from
        while (a <= to) {
            val t = Math.toRadians(a.toDouble())
            pts += cx + r * cos(t).toFloat(); pts += cy + r * Doodles.ASPECT * sin(t).toFloat()
            a += step
        }
        return pts.toFloatArray()
    }

    private fun heart(cx: Float, cy: Float, s: Float): FloatArray {
        val pts = ArrayList<Float>()
        for (i in 0..60) {
            val t = Math.PI * 2 * i / 60
            val x = 16 * Math.pow(sin(t), 3.0)
            val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
            pts += cx + (x * s).toFloat(); pts += cy - (y * s * Doodles.ASPECT).toFloat()
        }
        return pts.toFloatArray()
    }

    /** The strokes (thousandths) and their pens. */
    val strokes: List<Pair<FloatArray, Int>> by lazy {
        val out = ArrayList<Pair<FloatArray, Int>>()
        // The sun, top left, with its rays.
        out += arc(170f, 230f, 70f, 0, 360) to ink("mustard", 2)
        for (k in 0 until 8) {
            val t = Math.toRadians(k * 45.0)
            out += floatArrayOf(
                170f + 95f * cos(t).toFloat(), 230f + 95f * Doodles.ASPECT * sin(t).toFloat(),
                170f + 130f * cos(t).toFloat(), 230f + 130f * Doodles.ASPECT * sin(t).toFloat(),
            ) to ink("orange", 1)
        }
        // A face: the head, two eyes (dots), a smile.
        out += arc(520f, 430f, 150f, 0, 360) to ink("ink", 1)
        out += floatArrayOf(465f, 360f) to ink("ink", 2)
        out += floatArrayOf(575f, 360f) to ink("ink", 2)
        out += arc(520f, 440f, 85f, 20, 160) to ink("red", 1)
        // A heart in the writer's own colour, and a small one.
        out += heart(830f, 300f, 6.5f) to Ink.DEFAULT
        out += heart(905f, 560f, 3f) to ink("pink", 1)
        // Grass along the bottom.
        val grass = ArrayList<Float>()
        for (i in 0..40) { grass += 40f + i * 23f; grass += 900f + (if (i % 2 == 0) -55f else 20f) }
        out += grass.toFloatArray() to ink("green", 1)
        out
    }

    fun state(): DoodleState = DoodleState().also { s -> for ((p, i) in strokes) s.add(p, i) }
}
