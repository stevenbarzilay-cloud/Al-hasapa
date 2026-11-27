package app.couch.alhasapa.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Solo games (1.11): sides that travel and the newer wins, results compared by their kind, sealed until the one looking played too. */
class SoloTest {
    private val day = "2026-11-27"
    private fun side(who: String, v: Double?, t: Long, kind: String = "guesses", max: Int? = null, fail: Boolean = false) = SoloSide(who, v, max, fail, kind, t)

    @Test
    fun aSideIsKeptAndTheNewerWinsWhereverTwoCopiesMeet() {
        val s = CouchState.empty()
        assertTrue(Solo.put(s, day, "bandle", side("b", 3.0, 10, max = 6), now = 100))
        assertFalse("an older word does not replace a newer", Solo.put(s, day, "bandle", side("b", 5.0, 9), now = 100))
        assertTrue(Solo.put(s, day, "bandle", side("b", 2.0, 11, max = 6), now = 100))
        assertEquals(2.0, Solo.side(s, day, "bandle", "b")!!.v!!, 0.0)
        assertFalse("written a day ahead of this clock: not taken", Solo.put(s, day, "bandle", side("a", 1.0, 100 + 2 * 86_400_000L), now = 100))
        assertFalse("not one of ours", Solo.put(s, day, "bandle", side("x9", 1.0, 12), now = 100))
        assertFalse("not a game id", Solo.put(s, day, "Bandle!", side("a", 1.0, 12), now = 100))
        assertFalse("not a day", Solo.put(s, "yesterday", "bandle", side("a", 1.0, 12), now = 100))
        // Another device's copy, merged side by side.
        val other = CouchState.empty()
        Solo.put(other, day, "bandle", side("a", 4.0, 20, max = 6), now = 100)
        Solo.put(other, day, "bandle", side("b", 6.0, 5), now = 100)
        assertTrue(Solo.merge(s, other.getJSONObject("solo"), now = 100))
        assertEquals(listOf("b", "a"), Solo.sides(s, day, "bandle").map { it.who })
        assertEquals("b's newer side stays", 2.0, Solo.side(s, day, "bandle", "b")!!.v!!, 0.0)
        assertFalse("nothing new the second time", Solo.merge(s, other.getJSONObject("solo"), now = 100))
    }

    @Test
    fun resultsAreComparedTheWayTheirKindReads() {
        fun w(kind: String, a: SoloSide, b: SoloSide): String? {
            val s = CouchState.empty(); Solo.put(s, day, "g", a, 1_000); Solo.put(s, day, "g", b, 1_000); return Solo.winner(s, day, "g", kind)
        }
        assertEquals("fewer guesses", "a", w("guesses", side("a", 3.0, 1), side("b", 4.0, 2)))
        assertEquals("less time", "b", w("time", side("a", 95.0, 1), side("b", 62.0, 2)))
        assertEquals("fewer mistakes", "a", w("mistakes", side("a", 0.0, 1), side("b", 2.0, 2)))
        assertEquals("more points", "b", w("score", side("a", 70.0, 1), side("b", 88.0, 2)))
        assertEquals("points as a share of their whole", "a", w("score", side("a", 8.0, 1, max = 10), side("b", 15.0, 2, max = 20)))
        assertEquals("more words", "a", w("words", side("a", 31.0, 1), side("b", 20.0, 2)))
        assertEquals("not solved is worse than any result", "b", w("guesses", side("a", null, 1, fail = true), side("b", 6.0, 2)))
        assertEquals("level", "t", w("guesses", side("a", 4.0, 1), side("b", 4.0, 2)))
        assertEquals("both not solved: level", "t", w("guesses", side("a", null, 1, fail = true), side("b", null, 2, fail = true)))
        assertNull("only played, no result: nothing to compare yet", w("guesses", side("a", 3.0, 1), side("b", null, 2)))
        assertNull("a game with nothing to compare", w("none", side("a", 3.0, 1), side("b", 4.0, 2)))
    }

    @Test
    fun theOthersResultsAreSealedUntilTheOneLookingPlayedAloneToo() {
        val s = CouchState.empty()
        Solo.put(s, day, "dialed", side("a", 41.0, 10, kind = "score"), 1_000)
        val sealed = Solo.sealedFor(s, day, "dialed", "b")
        assertEquals(1, sealed.size)
        assertNull("played, not how", sealed[0].v)
        assertFalse(sealed[0].hasResult)
        Solo.put(s, day, "dialed", side("b", null, 11), 1_000)
        assertNull("only played here too: still sealed", Solo.sealedFor(s, day, "dialed", "b").first { it.who == "a" }.v)
        Solo.put(s, day, "dialed", side("b", 44.0, 12, kind = "score"), 1_000)
        assertEquals("with a result here: open", 41.0, Solo.sealedFor(s, day, "dialed", "b").first { it.who == "a" }.v!!, 0.0)
        assertEquals("b", Solo.winner(s, day, "dialed", "score"))
    }

    @Test
    fun aPhoneTellsOnlyItsOwnSidesAndTheMonthIsTallied() {
        val s = CouchState.empty()
        Solo.put(s, day, "bandle", side("a", 3.0, 1), 1_000)
        Solo.put(s, day, "bandle", side("b", 4.0, 2), 1_000)
        Solo.put(s, "2026-11-26", "wordle", side("a", 5.0, 3), 1_000)
        Solo.put(s, "2026-11-26", "wordle", side("b", 2.0, 4), 1_000)
        Solo.put(s, "2026-10-30", "wordle", side("a", 1.0, 5), 1_000)
        Solo.put(s, "2026-10-30", "wordle", side("b", 2.0, 6), 1_000)
        val mine = Solo.mine(s, "a", listOf(day, "2026-11-26"))
        assertEquals(setOf(day, "2026-11-26"), mine.keys().asSequence().toSet())
        assertFalse("never another person's side", mine.toString().contains("\"b\""))
        assertEquals(mapOf("a" to 1, "b" to 1), Solo.wins(s, { "guesses" }, "2026-11-01", "2026-11-30"))
        assertEquals(mapOf("a" to 2, "b" to 1), Solo.wins(s, { "guesses" }, "2026-10-01", "2026-11-30"))
    }

    @Test
    fun aSideFromAnotherDeviceMustBeOneOfOursAndSensible() {
        assertNull(SoloSide.fromJson("a", JSONObject().put("v", 3)))                           // no time
        assertNull(SoloSide.fromJson("zz", JSONObject().put("v", 3).put("t", 1)))              // not one of ours
        assertNull(SoloSide.fromJson("a", JSONObject().put("v", "lots").put("t", 1)))          // not a number
        assertNull(SoloSide.fromJson("a", JSONObject().put("v", 1e12).put("t", 1)))            // too much
        val p = SoloSide.fromJson("u1a2b3c4", JSONObject().put("p", true).put("t", 7))!!
        assertFalse(p.hasResult); assertEquals(7L, p.t)
        assertEquals("", SoloSide.fromJson("a", JSONObject().put("v", 2).put("t", 1).put("kind", "evil"))!!.kind)
    }
}
