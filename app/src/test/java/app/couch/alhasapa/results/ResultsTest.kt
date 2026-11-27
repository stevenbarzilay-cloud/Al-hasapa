package app.couch.alhasapa.results

import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.FileStateStore
import app.couch.alhasapa.data.StateRepository
import java.io.File
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import app.couch.alhasapa.scoring.Scoring
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
    fun aGameWhereMoreIsBetterIsScoredOutOfItsWholeOrAgainstOurBest() {
        // Until 1.9.7 these were read as guesses: a quiz's "8/10" was 8 tries out of 10, worth almost nothing.
        assertEquals("score", ResultDraft.kindOf(g("catfishing")))
        assertEquals("8 of 10 right is worth 8, and 2 for playing", 10, ResultDraft.evaluate(g("catfishing"), "Catfishing #812 8/10 🐟", "", false, JSONObject(), day).points)
        assertEquals("all right: the full 10", 12, ResultDraft.evaluate(g("dozen"), "The Dozen Daily Trivia 12/12", "", false, JSONObject(), day).points)
        assertEquals("a decimal score", 10, ResultDraft.evaluate(g("dialed"), "Dialed Daily #120\n🎨 41.23/50", "", false, JSONObject(), day).points)
        assertEquals("thousands, with commas", 9, ResultDraft.evaluate(g("timeguessr"), "TimeGuessr #498 34,567/50,000 🌎", "", false, JSONObject(), day).points)
        assertEquals("nothing right still counts the playing, and 1", 3, ResultDraft.evaluate(g("catfishing"), "0/10", "", false, JSONObject(), day).points)
        assertEquals("not made: only the playing", 2, ResultDraft.evaluate(g("catfishing"), "3/10", "", true, JSONObject(), day).points)
        // With nothing to be out of: our best so far is the measure (higher is better).
        val log = JSONObject("""{"2026-11-20":{"thrice":{"w":"","r":{"v":12,"kind":"score","fail":false}}}}""")
        assertEquals("a new best", 12, ResultDraft.evaluate(g("thrice"), "Thrice #44: 13 points", "", false, log, day).points)
        assertEquals("near our best (80% or more)", 9, ResultDraft.evaluate(g("thrice"), "10 points", "", false, log, day).points)
        assertEquals("further from it", 7, ResultDraft.evaluate(g("thrice"), "", "6", false, log, day).points)
        assertEquals("typed by hand as a fraction", 7, ResultDraft.evaluate(g("dozen"), "", "6/12", false, log, day).points)
        assertEquals("8/10", Scoring.fmtVal("score", ResultDraft.evaluate(g("catfishing"), "8/10", "", false, JSONObject(), day).result))
        assertEquals("13 נקודות", Scoring.fmtVal("score", ResultDraft.evaluate(g("thrice"), "13 points", "", false, JSONObject(), day).result))
        // A date or a number with nothing after it is not a score.
        assertEquals(null, Scoring.parseResult("#812", "score"))
    }

    @Test
    fun aSavedResultIsCelebratedWithAWordThatFitsIt() {
        val k = "guesses"
        assertEquals(app.couch.alhasapa.R.string.cheer_bullseye, app.couch.alhasapa.results.Cheers.line(10, k, false))
        assertEquals(app.couch.alhasapa.R.string.cheer_best, app.couch.alhasapa.results.Cheers.line(10, "time", true))
        assertEquals(app.couch.alhasapa.R.string.cheer_bullseye, app.couch.alhasapa.results.Cheers.line(10, "time", false))
        assertEquals(app.couch.alhasapa.R.string.cheer_great, app.couch.alhasapa.results.Cheers.line(8, k, false))
        assertEquals(app.couch.alhasapa.R.string.cheer_good, app.couch.alhasapa.results.Cheers.line(5, k, false))
        assertEquals(app.couch.alhasapa.R.string.cheer_fun, app.couch.alhasapa.results.Cheers.line(2, k, false))
        assertEquals(app.couch.alhasapa.R.string.cheer_tomorrow, app.couch.alhasapa.results.Cheers.line(0, k, false))
        val c = app.couch.alhasapa.results.Cheers.of(ResultStore.Outcome.Saved(12), k, false, 30)!!
        assertEquals(12, c.points); assertEquals(30, c.evening)
        assertEquals(null, app.couch.alhasapa.results.Cheers.of(ResultStore.Outcome.DuelSide("a"), k, false, 30))
    }

    @Test
    fun theEndScreenWatcherOnlyReportsWhereTheButtonIs() {
        val js = app.couch.alhasapa.results.ResultWatch.INSTALL
        for (banned in listOf("cookie", "localStorage", "sessionStorage", "fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "eval(", ".click(", "dispatchEvent", "postMessage", ".value"))
            assertFalse("the end-screen watcher must not use $banned", js.contains(banned))
        assertTrue(app.couch.alhasapa.results.ResultWatch.ready("\"1\""))
        assertFalse(app.couch.alhasapa.results.ResultWatch.ready("\"0\"")); assertFalse(app.couch.alhasapa.results.ResultWatch.ready(null))
        assertEquals(410f to 922f, app.couch.alhasapa.results.ResultWatch.at("\"410,922\""))
        assertEquals(null, app.couch.alhasapa.results.ResultWatch.at("\"\"")); assertEquals(null, app.couch.alhasapa.results.ResultWatch.at("\"-3,4\""))
        val dir = File("build/display-scripts").apply { mkdirs() }
        File(dir, "result-watch.js").writeText(js)
    }

    @Test
    fun aGameWithNoMetricIsEnteredAsGuessesAndATimeBeatsThePersonalBest() {
        assertEquals("guesses", ResultDraft.kindOf(g("songless")))
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

    /** 1.11.2: Haaretz's share names no game, only its link ("מי אני?" counts its hints in steps). */
    @Test
    fun aShareWithOnlyTheGamesLinkIsThatGameAndItsStepsAreItsHints() {
        val share = "כל הכבוד! הצלחת לזהות את הדמות תוך 6 צעדים\n\nhttps://www.haaretz.co.il/riddles/2023-02-26/ty-article-static/.premium/00000184-9eae-d199-ade4-dfef359e0000"
        assertEquals("hzwho", GameDetector.detect(share, catalog)?.id)
        assertEquals("the same page however the link is dressed", "hzwho", GameDetector.detect(
            "תוך 3 צעדים http://haaretz.co.il/riddles/2023-02-26/ty-article-static/.premium/00000184-9eae-d199-ade4-dfef359e0000/?utm_source=whatsapp#x", catalog)?.id)
        assertEquals("by the page's id when the rest of the address differs", "hzwho", GameDetector.detect(
            "https://www.haaretz.co.il/riddles/ty-article/00000184-9eae-d199-ade4-dfef359e0000", catalog)?.id)
        assertNull("a link to nothing of ours", GameDetector.detect("https://www.haaretz.co.il/news/somewhere", catalog))
        val p = Scoring.parseResult(share, "hints")!!
        assertEquals(6.0, p.v, 0.0); assertEquals(false, p.fail)
        assertEquals(1.0, Scoring.parseResult("כל הכבוד! הצלחת לזהות את הדמות תוך צעד אחד", "hints")!!.v, 0.0)
        assertEquals("read off the end screen too", 6, HebrewResult.read("כל הכבוד! הצלחת לזהות את הדמות תוך 6 צעדים").hints)
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

    /** Written for the browser check (tools/result_check.cjs): the end screen's lines, read when nothing was copied (1.9.10). */
    @Test
    fun dumpTheEndScreenReaderForTheBrowserCheck() {
        val dir = File("build/display-scripts").apply { mkdirs() }
        File(dir, "result-lines.js").writeText(ResultWatch.LINES)
        assertEquals("a\nb", ResultWatch.lines("\"a\\nb\""))
        assertEquals("", ResultWatch.lines(null))
    }

    /** Written for the browser check (tools/capture_check.cjs), which runs the real script in Chromium. */
    @Test
    fun dumpCaptureScriptForTheBrowserCheck() {
        val dir = File("build/display-scripts").apply { mkdirs() }
        File(dir, "capture.js").writeText(CaptureScript.JS)
        assertTrue(CaptureScript.JS.contains("couchCapture"))
    }
}
