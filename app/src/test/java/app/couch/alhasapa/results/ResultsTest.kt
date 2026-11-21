package app.couch.alhasapa.results

import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.FileStateStore
import app.couch.alhasapa.data.StateRepository
import java.io.File
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResultsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())
    private val today = LocalDate.of(2026, 11, 21)
    private val day = CouchState.dayKey(today)
    private fun repo() = StateRepository(FileStateStore(File(tmp.root, "state.json"))) { today }
    private fun g(id: String) = catalog.game(id)!!
    private fun input(text: String = "", manual: String = "", fail: Boolean = false, credit: String? = null) = ResultInput(text, manual, fail, credit)
    private val noLog = JSONObject()

    // ---- the sheet's arithmetic is the hub's ----

    @Test
    fun aPastedWordleIsReadAndScoredLikeTheHub() {
        val d = ResultDraft.evaluate(g("wordle"), "Wordle 1,618 3/6\n\n⬛🟨⬛⬛⬛\n🟩🟩⬛⬛⬛\n🟩🟩🟩🟩🟩", "", false, noLog, day)
        assertEquals(3.0, d.parsed!!.v, 0.0); assertEquals(6, d.parsed!!.max)
        assertEquals("2 for playing + round(10*(6+1-3)/6)=7", 9, d.points)
        assertEquals("guesses", d.result!!.kind)
    }

    @Test
    fun aMissIsWorthOnlyThePlayingPoints() {
        val d = ResultDraft.evaluate(g("wordle"), "Wordle 1,618 X/6", "", false, noLog, day)
        assertTrue(d.parsed!!.fail); assertEquals(2, d.points)
        val manualFail = ResultDraft.evaluate(g("wordle"), "", "", true, noLog, day)
        assertTrue("the box alone makes a miss", manualFail.result!!.fail); assertEquals(2, manualFail.points)
    }

    @Test
    fun manualEntryTakesANumberOrATimeAndTheTickWinsOverEverything() {
        assertEquals(4.0, ResultDraft.evaluate(g("wordle"), "", "4", false, noLog, day).parsed!!.v, 0.0)
        assertEquals("parseFloat reads the leading number", 3.0, ResultDraft.readManual("guesses", "3 tries")!!.v, 0.0)
        assertNull(ResultDraft.readManual("guesses", "abc"))
        assertEquals(84.0, ResultDraft.evaluate(g("queens"), "", "1:24", false, noLog, day).parsed!!.v, 0.0)
        assertNull("nothing typed is nothing", ResultDraft.evaluate(g("wordle"), "", "", false, noLog, day).parsed)
        assertTrue(ResultDraft.evaluate(g("wordle"), "Wordle 1 2/6", "", true, noLog, day).result!!.fail)
    }

    @Test
    fun aGameWithNoMetricIsEnteredAsGuessesAndATimeBeatsThePersonalBest() {
        assertEquals("guesses", ResultDraft.kindOf(g("krillion")))
        val log = JSONObject("""{"2026-11-20":{"mini":{"w":"","r":{"v":60,"kind":"time","fail":false}}}}""")
        assertEquals("a new best time is worth the full 10", 12, ResultDraft.evaluate(g("mini"), "", "0:50", false, log, day).points)
        assertEquals("within 25% of the best is 7", 9, ResultDraft.evaluate(g("mini"), "", "1:10", false, log, day).points)
    }

    // ---- saving ----

    @Test
    fun aTogetherResultIsSavedWithTheCreditAndATimestampAndKeepsTheTick() {
        val r = repo()
        r.update { CouchState.setEntry(it, day, "wordle", JSONObject().put("w", "a").put("t", 1L)) }
        val o = ResultStore.save(r, g("wordle"), day, null, input(text = "Wordle 1,618 3/6"), now = 1_800_000_000_000L)
        assertEquals(ResultStore.Outcome.Saved(9), o)
        val e = CouchState.entry(r.snapshot(), day, "wordle")!!
        assertEquals("the credit already given stays", "a", e.getString("w"))
        assertEquals(3, e.getJSONObject("r").getInt("v")); assertEquals("guesses", e.getJSONObject("r").getString("kind"))
        assertEquals("Wordle 1,618 3/6", e.getJSONObject("r").getString("raw"))
        assertEquals(1_800_000_000_000L, e.getLong("t"))
        ResultStore.save(r, g("wordle"), day, null, input(manual = "4", credit = "t"), now = 5L)
        assertEquals("t", CouchState.entry(r.snapshot(), day, "wordle")!!.getString("w"))
    }

    @Test
    fun savingWithNothingRecognisedStillTicksTheGame() {
        val r = repo()
        assertEquals(ResultStore.Outcome.MarkedOnly, ResultStore.save(r, g("wordle"), day, null, input(text = "hello"), now = 7L))
        val e = CouchState.entry(r.snapshot(), day, "wordle")!!
        assertEquals("", e.getString("w")); assertFalse(e.has("r")); assertEquals(7L, e.getLong("t"))
    }

    @Test
    fun aHeadToHeadResultGoesToTheOnePlayingAndLeavesTheOtherSideAlone() {
        val r = repo()
        val a = ResultStore.save(r, g("queens"), day, "a", input(text = "Queens #612 | 1:24"), now = 100L)
        assertEquals("waiting for the other side", ResultStore.Outcome.DuelSide(null), a)
        val b = ResultStore.save(r, g("queens"), day, "b", input(text = "Queens #612 | 1:10"), now = 200L)
        assertEquals("the lower time wins", ResultStore.Outcome.DuelSide("b"), b)
        val e = CouchState.entry(r.snapshot(), day, "queens")!!.getJSONObject("d")
        assertEquals(84, e.getJSONObject("a").getInt("v")); assertEquals(70, e.getJSONObject("b").getInt("v"))
        assertEquals(100L, e.getJSONObject("a").getLong("t")); assertEquals(200L, e.getJSONObject("b").getLong("t"))
        // A replayed side replaces only itself.
        ResultStore.save(r, g("queens"), day, "a", input(text = "Queens #612 | 0:50"), now = 300L)
        assertEquals(50, CouchState.entry(r.snapshot(), day, "queens")!!.getJSONObject("d").getJSONObject("a").getInt("v"))
        assertEquals(70, CouchState.entry(r.snapshot(), day, "queens")!!.getJSONObject("d").getJSONObject("b").getInt("v"))
    }

    @Test
    fun aHeadToHeadSideWithNoResultOrNoNameSavesNothing() {
        val r = repo()
        assertEquals(ResultStore.Outcome.Nothing, ResultStore.save(r, g("queens"), day, "a", input(text = "hello")))
        assertEquals(ResultStore.Outcome.Nothing, ResultStore.save(r, g("queens"), day, null, input(text = "Queens #612 | 1:24")))
        assertNull(CouchState.entry(r.snapshot(), day, "queens"))
    }

    @Test
    fun clearingTakesOffTheResultButNotTheTick() {
        val r = repo()
        ResultStore.save(r, g("wordle"), day, null, input(text = "Wordle 1 3/6"))
        ResultStore.clear(r, g("wordle"), day, null)
        val e = CouchState.entry(r.snapshot(), day, "wordle")!!
        assertFalse(e.has("r")); assertNotNull(e)
        ResultStore.save(r, g("queens"), day, "a", input(text = "Queens #1 | 1:00"))
        ResultStore.clear(r, g("queens"), day, "a")
        assertNull("an emptied duel leaves no entry", CouchState.entry(r.snapshot(), day, "queens"))
    }

    // ---- which game a shared text is from ----

    @Test
    fun headersAreRecognised() {
        fun id(t: String) = GameDetector.detect(t, catalog)?.id
        assertEquals("wordle", id("Wordle 1,618 3/6\n\n⬛⬛"))
        assertEquals("connections", id("Connections\nPuzzle #512\n🟨🟨🟨🟨"))
        assertEquals("strands", id("Strands #270\n“Seeing red”\n💡🔵🔵🟡"))
        assertEquals("queens", id("Queens #612 | 1:24\nFirst placed 👑"))
        assertEquals("tango", id("Tango #310 | 0:58"))
        assertEquals("zip", id("Zip #187 | 0:35 and flawless"))
        assertEquals("pinpoint", id("Pinpoint #504 | 2 guesses"))
        assertEquals("mini", id("I solved The Mini Crossword in 0:23!"))
        assertEquals("crossword", id("I solved the Saturday New York Times Crossword in 12:34"))
        assertEquals("connsports", id("Connections: Sports Edition\nPuzzle #100"))
        assertEquals("hz5", id("5 אותיות\n🟩🟩⬛"))
    }

    @Test
    fun whatItCannotTellItDoesNotGuess() {
        assertNull(GameDetector.detect("just some text", catalog))
        assertNull(GameDetector.detect("   ", catalog))
        assertNull("the name has to be in the header, not buried", GameDetector.detect("x".repeat(200) + " Wordle", catalog))
    }

    // ---- what the page may tell the app ----

    private val gate get() = CaptureGate(setOf("nytimes.com"), now = clock::get)
    private val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
    private fun msg(k: String = "share", t: String = "Wordle 1 3/6") = JSONObject().put("k", k).put("t", t).toString()

    @Test
    fun aShareFromTheGamesOwnSiteIsAccepted() {
        val c = gate.accept(msg(), "https://www.nytimes.com")
        assertEquals(CaptureGate.Captured("share", "Wordle 1 3/6"), c)
    }

    @Test
    fun otherOriginsOtherSchemesAndOddShapesAreRefused() {
        val g = gate
        assertNull(g.accept(msg(), "https://evil.example"))
        assertNull(g.accept(msg(), "https://nytimes.com.evil.example"))
        assertNull(g.accept(msg(), "http://www.nytimes.com"))
        assertNull(g.accept(msg(), null))
        assertNull(g.accept(null, "https://www.nytimes.com"))
        assertNull(g.accept("not json", "https://www.nytimes.com"))
        assertNull(g.accept(msg(k = "exec"), "https://www.nytimes.com"))
        assertNull(g.accept("""{"k":"share","t":5}""", "https://www.nytimes.com"))
        assertNull(g.accept(msg(t = "   "), "https://www.nytimes.com"))
        assertNull("too long to be a result", g.accept(msg(t = "x".repeat(CaptureScript.MAX_TEXT + 1)), "https://www.nytimes.com"))
    }

    @Test
    fun theSameTextTwiceInARowIsOneCapture() {
        val g = gate
        assertNotNull(g.accept(msg(), "https://www.nytimes.com"))
        assertNull(g.accept(msg(k = "copy"), "https://www.nytimes.com"))
        assertNull("the clipboard route sees the same text", g.acceptClipboard("Wordle 1 3/6"))
        clock.addAndGet(CaptureGate.REPEAT_MS + 1)
        assertNotNull("later, it is a new capture", g.accept(msg(), "https://www.nytimes.com"))
    }

    @Test
    fun theClipboardRouteTakesOnlyShortNonEmptyText() {
        val g = gate
        assertNull(g.acceptClipboard(null)); assertNull(g.acceptClipboard("  "))
        assertNull(g.acceptClipboard("y".repeat(CaptureScript.MAX_TEXT + 1)))
        assertEquals("copy", g.acceptClipboard("Connections\nPuzzle #5")!!.kind)
    }

    /** Written for the browser check (tools/capture_check.cjs), which runs the real script in Chromium. */
    @Test
    fun dumpCaptureScriptForTheBrowserCheck() {
        val dir = File("build/display-scripts").apply { mkdirs() }
        File(dir, "capture.js").writeText(CaptureScript.JS)
        assertTrue(CaptureScript.JS.contains("couchCapture"))
    }
}
