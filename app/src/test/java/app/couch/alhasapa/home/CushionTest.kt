package app.couch.alhasapa.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.ui.theme.Palette
import java.io.File
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A game's cushion on the TV's home: its colours (the demo's arithmetic, to the digit, and then made sure to be readable), which
 * symbol it wears, that it stays the same wherever it stands, and what is actually painted.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class CushionTest {
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())
    private val pieces = PiecesConfig.parse(File("src/main/assets/hub/pieces.json").readText())
    private val date = LocalDate.of(2026, 11, 23)
    private fun hex(s: String) = PiecesConfig.hex(s)
    private fun String.of(c: Color) = assertEquals(this, String.format("#%06x", c.toArgb() and 0xffffff))

    @Test
    fun theColoursAndThePrintAreTheDemosToTheDigit() {
        // Made by the demo page's own arithmetic (its colour rule, typed out and run): the game, the two colours, then its number,
        // fabric, letters, the print's two colours, the zigzag's dark, and which edge and print it has and whether it is mirrored.
        class Golden(val key: String, val a: String, val b: String, val seed: Long, val base: String, val type: String, val bright: String,
            val accent: String, val detail: String, val outline: Int, val print: Int, val flip: Boolean)
        val golden = listOf(
            Golden("wordle", "#D94E38", "#E8A5B5", 3559438546, "#f9e5cf", "#2e1e2c", "#dd523a", "#edb6bd", "#442e3c", 1, 3, false),
            Golden("connections", "#D94E38", "#E8A5B5", 4130653598, "#c15a52", "#ffffff", "#f3c8b2", "#f4d2c9", "#442e3c", 1, 0, false),
            Golden("framed", "#D94E38", "#E8A5B5", 1752241254, "#f2c5b1", "#2e1e2c", "#dd523a", "#edb6bd", "#442e3c", 0, 3, false),
            Golden("hzsong", "#bc414f", "#9069a7", 2112708374, "#d58a8a", "#2e1e2c", "#c7484b", "#aa89b2", "#3a273b", 1, 2, false),
            Golden("magsize", "#bc414f", "#9069a7", 1417654829, "#aa7aa2", "#2e1e2c", "#e2cfcb", "#e3aca3", "#3f2230", 0, 2, true),
            Golden("originle", "#D94E38", "#D94E38", 3457253940, "#df6b54", "#2e1e2c", "#f3c8b2", "#eeb19a", "#43242d", 1, 1, false),
            Golden("איזה שיר", "#D94E38", "#E8A5B5", 3968507618, "#f1c0a9", "#2e1e2c", "#dd523a", "#edb6bd", "#442e3c", 0, 3, false),
        )
        for (g in golden) {
            assertEquals(g.key, g.seed, CushionColors.seed(g.key).toUInt().toLong())
            val look = CushionColors.raw(g.key, hex(g.a), hex(g.b))
            g.base.of(look.base); g.type.of(look.type); g.bright.of(look.bright); g.accent.of(look.accent); g.detail.of(look.detail)
            assertEquals(g.key, Triple(g.outline, g.print, g.flip), Triple(look.outline, look.print, look.flip))
        }
    }

    @Test
    fun theNameAndTheSymbolCanBeReadOnEveryCushionWhateverTheTwoColours() {
        // Every pair of the colours the app offers, the same one twice too, for every game: the demo's rule alone leaves some two
        // hundred of these under the mark (the worst at 3.96 to 1).
        val colours = JSONObject(File("src/main/assets/hub/pieces.json").readText()).getJSONArray("colors").let { a -> (0 until a.length()).map { hex(a.getJSONObject(it).getString("base")) } }
        assertTrue(colours.size >= 6)
        var mended = 0
        for (a in colours) for (b in colours) for (g in catalog.games) {
            val raw = CushionColors.raw(g.id, a, b)
            val look = CushionColors.look(g.id, a, b)
            val contrast = CushionColors.contrast(look.type, look.base)
            assertTrue("${g.id}: $contrast", contrast >= CushionColors.MIN_CONTRAST)
            if (CushionColors.contrast(raw.type, raw.base) >= CushionColors.MIN_CONTRAST) assertEquals("a fabric that reads well is left as the demo has it", raw, look) else mended++
            assertEquals("only the fabric and the letters change", raw.copy(base = look.base, type = look.type), look)
        }
        assertTrue("some needed it: $mended", mended in 50..400)
        // With our own two colours: Wordle is as the demo has it, Connections' fabric had to move a little.
        val (red, pink) = hex("#D94E38") to hex("#E8A5B5")
        assertEquals(CushionColors.raw("wordle", red, pink), CushionColors.look("wordle", red, pink))
        assertNotEquals(CushionColors.raw("connections", red, pink).base, CushionColors.look("connections", red, pink).base)
        "#f8e2cd".of(CushionColors.wall(red, pink))                      // the wall: cream, a tenth of the way to the two colours
    }

    @Test
    fun aGameKeepsItsCushionWhereverItStandsAndTheCushionsFollowThePeoplesColours() {
        val fresh = HomeModel.from(CouchState.empty(), catalog, pieces, date)
        val played = CouchState.empty().also { CouchState.setEntry(it, CouchState.dayKey(date), "wordle", JSONObject().put("w", "").put("t", 1L)) }
        val later = HomeModel.from(played, catalog, pieces, date)
        assertEquals("wordle", fresh.tonight.first().id); assertEquals("wordle", later.tonight.last().id)      // it moved to the end
        val before = (fresh.tonight + fresh.more).associate { it.id to it.look }
        for (c in later.tonight + later.more) assertEquals(c.id, before[c.id], c.look)
        // The same game on the other shelf (put there by the people) is still the same cushion.
        val moved = HomeModel.from(CouchState.empty().also { it.put("prefs", JSONObject().put("wordle", "no")) }, catalog, pieces, date)
        assertEquals(before["wordle"], moved.more.first { it.id == "wordle" }.look)
        // One of the two chose another colour: other fabrics, another wall, the same prints.
        val teal = HomeModel.from(CouchState.empty().also { it.put("pieces", JSONObject().put("a", JSONObject().put("shape", "circle").put("color", "teal"))) }, catalog, pieces, date)
        assertNotEquals(fresh.wall, teal.wall)
        val changed = teal.tonight.count { it.look.base != before[it.id]!!.base }
        assertTrue("most fabrics changed: $changed of ${teal.tonight.size}", changed > teal.tonight.size / 2)
        for (c in teal.tonight) assertEquals(c.id, Triple(before[c.id]!!.outline, before[c.id]!!.print, before[c.id]!!.flip), Triple(c.look.outline, c.look.print, c.look.flip))
        // A shelf is not one colour: the games get different fabrics and prints.
        assertTrue(fresh.tonight.map { it.look.base }.toSet().size >= 10)
        assertEquals(5, fresh.tonight.map { it.look.print }.toSet().size)
        assertEquals(2, fresh.tonight.map { it.look.outline }.toSet().size)
    }

    @Test
    fun theSameKindOfGameWearsTheSameSymbol() {
        val m = HomeModel.from(CouchState.empty(), catalog, pieces, date)
        val all = (m.tonight + m.more).associateBy { it.id }
        fun cat(id: String) = all.getValue(id).category
        assertEquals(Category.WORDS, cat("wordle")); assertEquals(Category.WORDS, cat("hz5"))
        assertEquals(Category.LOGIC, cat("pips")); assertEquals(Category.LOGIC, cat("hzmath"))             // numbers are puzzles
        assertEquals(Category.TRIVIA, cat("krillion")); assertEquals(Category.TRIVIA, cat("dozen"))
        assertEquals(Category.MUSIC, cat("bandle")); assertEquals(Category.MUSIC, cat("hzsong"))
        assertEquals(Category.GEOGRAPHY, cat("originle")); assertEquals(Category.GEOGRAPHY, cat("timeguessr"))
        assertEquals(Category.VISUAL, cat("dialed")); assertEquals(Category.VISUAL, cat("hzsnake"))
        // The film games are quizzes in the catalogue; on the TV they wear the film strip and are called by its name.
        for (id in listOf("framed", "boxoffice", "cinematrix", "actorle", "flickle")) {
            assertEquals(id, Category.CINEMA, cat(id)); assertEquals(id, "קולנוע", all.getValue(id).kind)
            assertEquals("the catalogue's own kind is untouched (the phone shows it)", "ידע", catalog.game(id)!!.k)
        }
        // Everything else is called what the catalogue calls it, and every kind there is has a symbol.
        for (g in catalog.games.filter { it.cat == null }) assertEquals(g.id, g.k, all.getValue(g.id).kind)
        assertEquals(Category.TRIVIA, Category.of("משהו חדש", null)); assertEquals(Category.TRIVIA, Category.of("ידע", "no-such-symbol"))
        assertEquals("every symbol is worn by some game", Category.entries.toSet(), all.values.map { it.category }.toSet())
    }

    /** A cushion painted for real, two pixels to the unit: 464 by 300. */
    private fun paint(look: CushionLook, focused: Boolean, oldMark: Boolean = true, played: Boolean = false): (Int, Int) -> Color {
        val bitmap = ImageBitmap(464 + 40, 300 + 40)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(504f, 340f)) {
            with(CushionArt) {
                if (focused) threads(look.outline, Offset(20f, 20f), 2f, oldMark)
                cushion(look, Category.WORDS, played, Offset(20f, 20f), 2f)
            }
        }
        val map = bitmap.toPixelMap()
        return { x, y -> map[x + 20, y + 20] }               // in the cushion's own box, two pixels to the unit
    }

    @Test
    fun whatIsPaintedIsTheFabricItsEdgeAndInFocusTheThreadsAroundIt() {
        // Teal and blue, so nothing on the cushion is the red of the old focus mark.
        val look = CushionColors.look("wordle", hex("#44A3AB"), hex("#5B84D0"))
        assertTrue("its inside was cut to its edge when the shapes were made, not while painting", CushionArt.isCutReady(look.outline, look.print, look.flip))
        val plain = paint(look, focused = false)
        assertEquals("the fabric, under the name", look.base, plain(232, 236))
        assertEquals("nothing beyond the edge", 0f, plain(2, 2).alpha, 0f)
        assertEquals("nor beyond it at the far corner, where a print would reach", 0f, plain(460, 296).alpha, 0f)
        fun count(px: (Int, Int) -> Color, c: Color): Int { var n = 0; for (y in -18 until 318 step 2) for (x in -18 until 482 step 2) if (px(x, y) == c) n++; return n }
        val red = Palette.Red; val cream = Color(0xFFFFF6DD); val dark = Color(0xFF402C37)
        assertEquals("no thread on a cushion that is not in focus", 0, count(plain, red) + count(plain, cream))
        val focused = paint(look, focused = true)
        assertTrue("the light thread", count(focused, cream) > 150)
        assertTrue("the dark thread", count(focused, dark) > 60)
        assertTrue("and, for now, the red that marked the focus before", count(focused, red) > 300)
        assertEquals("the cushion itself is the same picture in and out of focus", look.base, focused(232, 236))
        // The threads go round the cushion: outwards from its left edge at mid height, the light one comes before the dark one, then the red.
        val row = (60 downTo -18).map { focused(it, 150) }
        val light = row.indexOfFirst { it == cream }; val darkAt = row.indexOfFirst { it == dark && row.indexOf(it) > light }; val redAt = row.indexOfFirst { it == red }
        assertTrue("light $light, red $redAt", light in 0 until redAt)
        // Without the old mark (as it will be once the threads have been seen on the TV): the two threads only.
        val demo = paint(look, focused = true, oldMark = false)
        assertEquals(0, count(demo, red)); assertTrue(count(demo, cream) > 150)
        // Played this evening: the stamp on its corner.
        val stamped = paint(look, focused = false, played = true)
        assertEquals("the stamp", Palette.Ink, stamped(396, 40)); assertEquals("and the tick in it", Palette.Cream, stamped(382, 62))
        assertNotEquals(Palette.Ink, plain(396, 40))
    }
}
