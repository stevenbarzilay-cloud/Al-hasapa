package app.couch.alhasapa.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each of us as every device shows us, and the notes we leave each other (1.9.9). */
class CoupleTest {
    private fun state() = CouchState.empty()

    @Test
    fun theNewerNameAndPieceWinPersonByPerson() {
        val tv = state()
        assertEquals("סטיב", Couple.person(tv, "a").name)
        assertEquals(0L, Couple.stamp(tv, "a"))
        // Roni's phone says how Roni looks now.
        assertTrue(Couple.offer(tv, Person("b", "רונית", "heart", "teal", 1_000)))
        assertEquals(Person("b", "רונית", "heart", "teal", 1_000), Couple.person(tv, "b"))
        assertEquals("the other one is untouched", "סטיב", Couple.person(tv, "a").name)
        // An older word from somewhere else changes nothing.
        assertFalse(Couple.offer(tv, Person("b", "רוני", "triangle", "pink", 900)))
        assertFalse(Couple.offer(tv, Person("b", "רוני", "triangle", "pink", 1_000)))
        assertEquals("רונית", Couple.person(tv, "b").name)
        assertFalse("nobody else", Couple.offer(tv, Person("c", "x", "", "", 5_000)))
        // The pieces the native screens draw follow.
        assertEquals("heart", tv.getJSONObject("pieces").getJSONObject("b").getString("shape"))
    }

    @Test
    fun aChangeMadeInTheHubIsStampedSoItTravels() {
        val before = state()
        val after = CouchState.copy(before)
        after.getJSONObject("names").put("a", "סטיבי")
        Couple.stampChanges(before, after, now = 5_000)
        assertEquals(5_000L, Couple.stamp(after, "a"))
        assertEquals("unchanged: not stamped", 0L, Couple.stamp(after, "b"))
        // A piece changed again, at a clock behind the last stamp, still moves the stamp on.
        val again = CouchState.copy(after)
        again.put("pieces", JSONObject().put("a", JSONObject().put("shape", "star").put("color", "blue")))
        Couple.stampChanges(after, again, now = 4_000)
        assertEquals(5_001L, Couple.stamp(again, "a"))
    }

    @Test
    fun aPersonFromTheNetworkIsOneOfUsWithAName() {
        assertNull(Person.fromJson("a", JSONObject().put("name", "  ")))
        assertNull(Person.fromJson("z", JSONObject().put("name", "x")))
        assertEquals("כ".repeat(Couple.MAX_NAME), Person.fromJson("a", JSONObject().put("name", "כ".repeat(50)))!!.name)
        assertEquals(0L, Person.fromJson("a", JSONObject().put("name", "x").put("at", -7))!!.at)
    }

    @Test
    fun aNoteWaitsForItsGameToBePlayedAfterItWasWritten() {
        val tv = state()
        val note = Note("n1", "a", "krillion", "כל הכבוד לנו!", listOf(intArrayOf(0, 0, 500, 500, 1000, 0)), at = 10_000)
        assertTrue(Notes.add(tv, note))
        assertFalse("sent twice, kept once", Notes.add(tv, note))
        assertEquals(1, Notes.all(tv).size)
        val day = "2026-11-27"
        assertTrue("not played yet: nothing on the home", Notes.forHome(tv, day).isEmpty())
        // Played earlier today, before the note was written: it waits for the next time.
        CouchState.setEntry(tv, day, "krillion", JSONObject().put("w", "").put("t", 9_000L))
        assertTrue(Notes.forHome(tv, day).isEmpty())
        // Played after it was written: it shows.
        CouchState.setEntry(tv, day, "krillion", JSONObject().put("w", "").put("t", 11_000L))
        assertEquals(listOf("n1"), Notes.forHome(tv, day).map { it.id })
        assertEquals(listOf("n1"), Notes.waitingFor(tv, "krillion").map { it.id })
        Notes.markSeen(tv, "n1", now = 12_000)
        assertTrue("shown once", Notes.forHome(tv, day).isEmpty() && Notes.waitingFor(tv, "krillion").isEmpty())
        // A note for no game shows on the home at once.
        Notes.add(tv, Note("n2", "b", null, "", listOf(intArrayOf(10, 10)), at = 13_000))
        assertEquals(listOf("n2"), Notes.forHome(tv, day).map { it.id })
    }

    @Test
    fun aNoteIsKeptSmallAndTheOldestSeenGoFirst() {
        assertNull("nothing in it", Note.fromJson(JSONObject().put("id", "x").put("from", "a")))
        assertNull("nobody of us", Note.fromJson(JSONObject().put("id", "x").put("from", "c").put("text", "hi")))
        val big = org.json.JSONArray().apply { repeat(100) { put(org.json.JSONArray().apply { repeat(100) { put(2_000) } }) } }
        val n = Note.fromJson(JSONObject().put("id", "x").put("from", "a").put("text", "א".repeat(500)).put("d", big))!!
        assertEquals(Notes.MAX_TEXT, n.text.length)
        assertTrue(n.doodle.size <= Notes.MAX_STROKES)
        assertTrue(n.doodle.sumOf { it.size / 2 } <= Notes.MAX_POINTS)
        assertTrue("points stay inside the drawing", n.doodle.all { s -> s.all { it in 0..1000 } })
        val tv = state()
        repeat(Notes.KEEP) { i -> Notes.add(tv, Note("s$i", "a", null, "x", emptyList(), at = i.toLong())); Notes.markSeen(tv, "s$i", 1) }
        Notes.add(tv, Note("new", "b", null, "y", emptyList(), at = 1_000))
        assertEquals(Notes.KEEP, Notes.all(tv).size)
        assertFalse("the oldest seen one went", Notes.all(tv).any { it.id == "s0" })
        assertTrue(Notes.all(tv).any { it.id == "new" })
    }
}
