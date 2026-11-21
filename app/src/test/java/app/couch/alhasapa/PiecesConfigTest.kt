package app.couch.alhasapa

import java.io.File
import kotlin.math.pow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pieces.json is shared by the hub and (from milestone 1) the native screens. These tests keep the promise
 * that any shape/colour a person picks leaves the interface readable, and that the defaults are the
 * prototype's exact look.
 */
class PiecesConfigTest {
    private val doc by lazy { JSONObject(File("src/main/assets/hub/pieces.json").readText()) }
    private val colors by lazy { doc.getJSONArray("colors").objects() }
    private val shapes by lazy { doc.getJSONArray("shapes").objects() }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun luminance(hex: String): Double {
        fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val r = hex.substring(1, 3).toInt(16)
        val g = hex.substring(3, 5).toInt(16)
        val b = hex.substring(5, 7).toInt(16)
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    private fun contrast(a: String, b: String): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val cream = "#F2EBDD"
    private val ink = "#30232D"

    @Test
    fun defaultsAreTheExactPrototypeLook() {
        val d = doc.getJSONObject("defaults")
        assertEquals("circle", d.getJSONObject("a").getString("shape"))
        assertEquals("red", d.getJSONObject("a").getString("color"))
        assertEquals("triangle", d.getJSONObject("b").getString("shape"))
        assertEquals("pink", d.getJSONObject("b").getString("color"))
        val red = colors.first { it.getString("id") == "red" }
        val pink = colors.first { it.getString("id") == "pink" }
        assertEquals("#D94E38", red.getString("base")); assertEquals("#D94E38", red.getString("accent"))
        assertEquals("#E8A5B5", pink.getString("base")); assertEquals("#E8A5B5", pink.getString("soft"))
        assertEquals("M10 2.5 18.2 17H1.8z", shapes.first { it.getString("id") == "triangle" }.getString("d"))
    }

    @Test
    fun everyAccentIsReadableOnCream() {
        // 3.4 = the prototype's own red on cream (3.47). Accents carry the big count, the stamp and shadows.
        for (c in colors) {
            val r = contrast(c.getString("accent"), cream)
            assertTrue("${c.getString("id")} accent on cream is only ${"%.2f".format(r)}:1", r >= 3.4)
        }
    }

    @Test
    fun everySoftSurfaceCarriesInkText() {
        for (c in colors) {
            val r = contrast(ink, c.getString("soft"))
            assertTrue("${c.getString("id")} soft surface under ink is only ${"%.2f".format(r)}:1", r >= 7.0)
        }
    }

    @Test
    fun catalogIsCompleteAndWellFormed() {
        assertTrue("need at least 6 shapes", shapes.size >= 6)
        assertTrue("need at least 8 colours", colors.size >= 8)
        assertEquals("unique shape ids", shapes.size, shapes.map { it.getString("id") }.toSet().size)
        assertEquals("unique colour ids", colors.size, colors.map { it.getString("id") }.toSet().size)
        for (s in shapes) {
            assertTrue("${s.getString("id")} needs a Hebrew name", s.getString("n").any { it in '֐'..'׿' })
            assertTrue("${s.getString("id")} path must start with M", s.getString("d").startsWith("M"))
        }
        val hex = Regex("#[0-9A-Fa-f]{6}")
        for (c in colors) {
            for (k in listOf("base", "accent", "soft")) assertTrue("${c.getString("id")}.$k is not #RRGGBB", hex.matches(c.getString(k)))
            assertTrue("${c.getString("id")} needs a Hebrew name", c.getString("n").any { it in '֐'..'׿' })
            assertTrue("${c.getString("id")} needs the definite form (ה...)", c.getString("def").startsWith("ה"))
        }
    }
}
