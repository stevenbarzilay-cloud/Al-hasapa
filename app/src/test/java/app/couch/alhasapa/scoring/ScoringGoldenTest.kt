package app.couch.alhasapa.scoring

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays cases recorded from the ORIGINAL prototype's functions (tools/gen_scoring_vectors.mjs). The Kotlin port must
 * reproduce every one, so "port the scoring exactly" is tested, not promised.
 */
class ScoringGoldenTest {
    private val doc = JSONObject(File("src/test/resources/scoring_vectors.json").readText())
    private val log = doc.getJSONObject("log")

    private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }

    private fun resultOf(o: JSONObject?): Result? = Result.fromJson(o)

    @Test
    fun enoughCasesWereRecorded() {
        assertTrue(doc.getJSONArray("parse").length() >= 500)
        assertTrue(doc.getJSONArray("points").length() >= 70)
        assertTrue(doc.getJSONArray("duel").length() >= 80)
    }

    @Test
    fun parseResultMatchesThePrototype() {
        val failures = mutableListOf<String>()
        for (c in doc.getJSONArray("parse").objs()) {
            val kind = c.getString("kind")
            val text = c.getString("text")
            val got = Scoring.parseResult(text, kind)
            val want = if (c.isNull("out")) null else c.getJSONObject("out")
            val ok = if (want == null) got == null else got != null &&
                got.v == want.getDouble("v") &&
                got.fail == want.getBoolean("fail") &&
                got.max == (if (want.has("max")) want.getInt("max") else null)
            if (!ok) failures += "parse($kind, ${text.take(40).replace("\n", "\\n")}) -> $got, prototype said $want"
        }
        assertTrue("${failures.size} parse mismatches:\n" + failures.take(12).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun resultPointsMatchThePrototype() {
        val failures = mutableListOf<String>()
        for (c in doc.getJSONArray("points").objs()) {
            val id = c.getString("id")
            val day = c.getString("day")
            val r = resultOf(if (c.isNull("r")) null else c.getJSONObject("r"))
            val got = Scoring.resultPts(log, id, r, day)
            if (got != c.getInt("pts")) failures += "resultPts($id, ${c.opt("r")}, $day) = $got, prototype said ${c.getInt("pts")}"
            val best = Scoring.bestTime(log, id, day)
            val wantBest = if (c.isNull("best")) null else c.getDouble("best")
            if (best != wantBest) failures += "bestTime($id, $day) = $best, prototype said $wantBest"
        }
        assertTrue("${failures.size} point mismatches:\n" + failures.take(12).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun entryAndDayPointsMatchThePrototype() {
        for (c in doc.getJSONArray("entries").objs()) {
            val e = if (c.isNull("e")) null else c.getJSONObject("e")
            assertEquals("entryPts ${c.getString("id")} $e", c.getInt("pts"), Scoring.entryPts(log, c.getString("id"), e, c.getString("day")))
        }
        for (c in doc.getJSONArray("days").objs()) {
            assertEquals("dayPts ${c.getString("day")}", c.getInt("pts"), Scoring.dayPts(log, c.getString("day")))
        }
    }

    @Test
    fun duelWinnerMatchesThePrototype() {
        val failures = mutableListOf<String>()
        for (c in doc.getJSONArray("duel").objs()) {
            val entry: JSONObject? = when {
                c.optBoolean("noD") -> JSONObject().put("w", "t")
                else -> JSONObject().put("d", JSONObject().apply {
                    if (!c.isNull("a")) put("a", c.getJSONObject("a"))
                    if (!c.isNull("b")) put("b", c.getJSONObject("b"))
                })
            }
            val got = Scoring.duelWinner(entry)
            val want = if (c.isNull("out")) null else c.getString("out")
            if (got != want) failures += "duelWinner(${c.opt("a")}, ${c.opt("b")}) = $got, prototype said $want"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertNull(Scoring.duelWinner(null))
    }

    @Test
    fun formattingMatchesThePrototype() {
        for (c in doc.getJSONArray("fmt").objs()) {
            if (c.getString("fn") == "fmtT") {
                assertEquals("fmtT(${c.get("s")})", c.getString("out"), Scoring.fmtT(c.getDouble("s")))
            } else {
                val kind = c.getString("kind")
                val r = resultOf(if (c.isNull("r")) null else c.getJSONObject("r"))
                assertEquals("fmtVal($kind, ${c.opt("r")})", c.getString("out"), Scoring.fmtVal(kind, r))
            }
        }
        val kinds = doc.getJSONObject("kinds")
        for (k in kinds.keys()) assertEquals(kinds.getString(k), Scoring.KIND_LABEL[k])
    }

    @Test
    fun theBriefsOwnExamples() {
        // Spot checks straight from the product brief, independent of the recorded vectors.
        val empty = JSONObject()
        assertEquals(10, Scoring.resultPts(empty, "wordle", Result(1.0, 6, false, "guesses"), null))
        assertEquals(0, Scoring.resultPts(empty, "wordle", Result(7.0, 6, true, "guesses"), null))
        assertEquals(listOf(10, 7, 4, 2), (0..3).map { Scoring.resultPts(empty, "connections", Result(it.toDouble(), null, false, "mistakes"), null) })
        assertEquals(listOf(10, 7, 5, 3), (0..3).map { Scoring.resultPts(empty, "strands", Result(it.toDouble(), null, false, "hints"), null) })
        assertEquals(12, 2 + Scoring.resultPts(empty, "queens", Result(84.0, null, false, "time"), null)) // first time = a new personal best
    }
}
