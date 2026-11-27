package app.couch.alhasapa.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A note's drawing (1.12): each stroke with its own pen, kept small enough to send, and let go once the note was read. */
class DoodlesTest {
    @Test
    fun aPenIsAColourAndAWidthInOneNumber() {
        val blueThick = Ink.of(Ink.COLORS.indexOf("blue"), 2)
        assertEquals("blue", Ink.COLORS[Ink.color(blueThick)]); assertEquals(2, Ink.width(blueThick))
        assertEquals("own", Ink.COLORS[Ink.color(Ink.DEFAULT)]); assertEquals(1, Ink.width(Ink.DEFAULT))
        assertEquals("what is not a pen is the nearest one", Ink.of(Ink.COLORS.lastIndex, Ink.WIDTHS - 1), Ink.of(99, 9))
        assertTrue("thin is thinner than thick", Ink.units(Ink.of(1, 0)) < Ink.units(Ink.of(1, 2)))
        assertTrue("the eraser is wider than a pen", Ink.units(Ink.of(Ink.PAPER, 1)) > Ink.units(Ink.of(1, 1)))
    }

    @Test
    fun aNoteKeepsHowEachStrokeIsDrawnAndOneFromBeforeHasNone() {
        val n = Note("n1", "b", null, "", listOf(intArrayOf(0, 0, 500, 500), intArrayOf(7, 9)), 5L, ink = listOf(Ink.of(7, 2), Ink.of(Ink.PAPER, 0)))
        val back = Note.fromJson(JSONObject(n.toJson().toString()))!!
        assertEquals(n, back)
        assertEquals(listOf(Ink.of(7, 2), Ink.of(Ink.PAPER, 0)), back.ink)
        val old = Note.fromJson(JSONObject().put("id", "o").put("from", "a").put("d", org.json.JSONArray().put(org.json.JSONArray().put(1).put(2))))!!
        assertNull("from before 1.12: no pens", old.ink)
        // Pens that are missing or make no sense: the default pen, one a stroke, never more than the strokes.
        val odd = Note.fromJson(JSONObject(n.toJson().toString()).put("ink", org.json.JSONArray().put(987654).put("x").put(3).put(4)))!!
        assertEquals(2, odd.ink!!.size)
        assertTrue(odd.ink!!.all { Ink.color(it) in Ink.COLORS.indices && Ink.width(it) in 0 until Ink.WIDTHS })
    }

    @Test
    fun aNoteThatWasReadKeepsNothingOfItsDrawingButIsStillKnown() {
        val tv = CouchState.empty()
        val strokes = List(40) { IntArray(200) { it * 5 } }
        Notes.add(tv, Note("n1", "b", null, "", strokes, at = 10, ink = List(40) { Ink.DEFAULT }))
        val before = tv.toString().length
        Notes.markSeen(tv, "n1", now = 20)
        assertTrue("the heavy part is let go", tv.toString().length < before / 10)
        val n = Notes.all(tv).single()
        assertTrue(n.seen > 0); assertTrue(n.doodle.isEmpty())
        assertFalse("sent again, it is not taken for a new one", Notes.add(tv, Note("n1", "b", null, "", strokes, at = 10)))
        assertTrue(Notes.forHome(tv, "2026-11-27").isEmpty())
    }

    @Test
    fun aStrokeLosesThePointsThatAddNothingAndKeepsItsShape() {
        // A straight line of a hundred points is its two ends.
        val line = FloatArray(200) { if (it % 2 == 0) it / 2 * 3f else it / 2 * 2f }
        assertEquals(4, Doodles.simplify(line, 0.5f).size)
        // A corner stays a corner.
        val corner = floatArrayOf(0f, 0f, 50f, 0f, 100f, 0f, 100f, 50f, 100f, 100f)
        assertEquals(listOf(0f, 0f, 100f, 0f, 100f, 100f), Doodles.simplify(corner, 0.5f).toList())
        // A circle of 360 points keeps its shape within the tolerance, with far fewer points.
        val circle = FloatArray(720) { val a = Math.toRadians((it / 2).toDouble()); (if (it % 2 == 0) 100 + 80 * Math.cos(a) else 100 + 80 * Math.sin(a)).toFloat() }
        val kept = Doodles.simplify(circle, 0.6f)
        assertTrue("${kept.size / 2} points", kept.size / 2 in 12..90)
        for (i in 0 until 360) {
            val x = circle[2 * i]; val y = circle[2 * i + 1]
            var best = Float.MAX_VALUE
            for (k in 0 until kept.size / 2 - 1) {
                val ax = kept[2 * k]; val ay = kept[2 * k + 1]; val bx = kept[2 * k + 2]; val by = kept[2 * k + 3]
                val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                val t = if (l2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
                val ex = x - (ax + t * dx); val ey = y - (ay + t * dy)
                best = minOf(best, ex * ex + ey * ey)
            }
            assertTrue("point $i strays ${Math.sqrt(best.toDouble())}", best <= 0.6f * 0.6f + 0.01f)
        }
        // A dot, and nothing, stay as they are.
        assertEquals(2, Doodles.simplify(floatArrayOf(5f, 6f), 1f).size)
        assertEquals(0, Doodles.simplify(FloatArray(0), 1f).size)
        assertNotNull(Doodles.simplify(floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f), 1f))
    }
}
