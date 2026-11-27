package app.couch.alhasapa.results

import app.couch.alhasapa.catalog.Game
import app.couch.alhasapa.data.CouchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An end screen in words (Haaretz's games copy nothing): the numbers in it, and only those, become the result. */
class HebrewResultTest {
    private fun read(vararg lines: String) = HebrewResult.read(lines.joinToString("\n"))

    @Test
    fun triesAreReadHoweverTheyAreSaid() {
        assertEquals(3, read("כל הכבוד! פתרתם בניסיון השלישי").tries)
        assertEquals(4, read("פתרתם ב-4 ניסיונות").tries)
        assertEquals(4, read("פתרתם ב־4 ניסיונות מתוך 6").tries)
        assertEquals(6, read("פתרתם ב־4 ניסיונות מתוך 6").outOf)
        assertEquals(2, read("הצלחתם בניסיון ה-2").tries)
        assertEquals(1, read("בניסיון אחד! מדהים").tries)
        assertEquals(5, read("5 ניחושים").tries)
    }

    /** 1.11.2: Dialed's end screen in Hebrew; the score was read from inside the decimal ("11 of 50"). */
    @Test
    fun aScoreWithDecimalsIsReadWhole() {
        val r = read("45.11 מתוך 50")
        assertEquals(45.11, r.score!!, 0.0); assertEquals(50.0, r.scoreMax!!, 0.0)
        assertEquals("45.11/50", HebrewResult.summary(r, "score", null))
        assertEquals(8.0, read("ניקוד: 8/10").score!!, 0.0)
        assertNull("a score bigger than its whole is not one", read("51 מתוך 50").score)
    }

    @Test
    fun mistakesHintsTimesAndScores() {
        assertEquals(2, read("הצלחתם עם 2 טעויות").mistakes)
        assertEquals(0, read("בלי טעויות!").mistakes)
        assertEquals(1, read("עם טעות אחת בלבד").mistakes)
        assertEquals(1, read("השתמשתם ברמז אחד").hints)
        assertEquals(0, read("ללא רמזים").hints)
        assertEquals(3, read("3 רמזים").hints)
        assertEquals(245, read("הזמן שלכם: 4:05").seconds)
        val q = read("17 מתוך 20 תשובות נכונות")
        assertEquals(17.0, q.score!!, 0.0); assertEquals(20.0, q.scoreMax!!, 0.0)
        assertNull("tries out of the tries allowed are not a score", read("פתרתם ב-4 ניסיונות מתוך 6").score)
    }

    @Test
    fun howWeDidAgainstEveryoneElse() {
        assertEquals(82, read("הייתם טובים יותר מ-82% מהשחקנים").beat)
        assertEquals(64, read("פתרתם מהר יותר מ-64% מהפותרים").beat)
        assertEquals(90, read("אתם בין ה-10% המהירים").beat)
        assertNull("how many solved it is not how we did", read("99% מהשחקנים פתרו אותה").beat)
        assertNull("a percentage on its own is nothing", read("הנחה של 50%").beat)
    }

    @Test
    fun aLostGameIsSaidAndItsAnswerIsNeverKept() {
        val r = read("לא הצלחתם הפעם", "המילה הייתה: שלום", "רק 12% מהשחקנים פתרו")
        assertTrue(r.failed)
        val text = HebrewResult.summary(r, "guesses", 6)
        assertEquals("X/6 ניסיונות", text)
        assertFalse(text.contains("שלום"))
        // Whatever the kind, never a word of the page itself.
        for (k in listOf("mistakes", "hints", "time", "score", "guesses")) assertFalse(HebrewResult.summary(r, k, 6).contains("שלום"))
    }

    @Test
    fun theSummaryIsWhatTheSheetReadsBack() {
        val r = read("פתרתם ב-3 ניסיונות", "טובים יותר מ-82% מהשחקנים")
        val text = HebrewResult.summary(r, "guesses", 6)
        assertEquals("3/6 ניסיונות · טובים מ-82% מהשחקנים", text)
        val game = Game("hz5", "5 אותיות", "hz", "מילים", "", "https://www.haaretz.co.il/riddles", "", true, null, "guesses", false)
        val log = CouchState.empty().getJSONObject("log")
        val d = ResultDraft.evaluate(game, text, "", false, log, "2026-11-27")
        assertEquals(3.0, d.result!!.v, 0.0); assertEquals(6, d.result!!.max); assertEquals("guesses", d.result!!.kind)
        assertEquals("2 for playing and 7 for three tries of six", 9, d.points)
    }

    @Test
    fun aGameWithNothingButAStandingIsScoredByIt() {
        val game = Game("hzsnake", "שביל הנחש", "hz", "זיכרון", "", "https://www.haaretz.co.il/riddles", "", true, null, "none", false)
        val text = HebrewResult.summary(read("זכרתם טוב יותר מ-82% מהשחקנים"), ResultDraft.kindOf(game), 6)
        assertEquals("טובים מ-82% מהשחקנים", text)
        val d = ResultDraft.evaluate(game, text, "", false, CouchState.empty().getJSONObject("log"), "2026-11-27")
        assertEquals("score", d.result!!.kind); assertEquals(82.0, d.result!!.v, 0.0); assertEquals(100, d.result!!.max)
        assertEquals("2 for playing and 8 for better than 82%", 10, d.points)
        assertFalse("nothing at all: nothing read", read("ברוכים הבאים למשחק", "מאמר על משחקים").any)
    }
}
