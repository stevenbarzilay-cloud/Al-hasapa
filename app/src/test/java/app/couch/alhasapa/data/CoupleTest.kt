package app.couch.alhasapa.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each of us as every device shows us, and the notes we leave each other (1.9.9). */
class CoupleTest {
    private fun state() = ours()

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
        val big = org.json.JSONArray().apply { repeat(400) { put(org.json.JSONArray().apply { repeat(40) { put(2_000) } }) } }
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

/** Our couch (the two of us by name): a new state names nobody (2.0, see CouchesTest). */
private fun ours(): JSONObject = CouchState.empty().also { it.put("names", JSONObject().put("a", "סטיב").put("b", "רוני")) }

/** The others (1.10): someone who comes often, kept until removed; a guest, for the evening they came only. */
class OthersTest {
    @Test
    fun othersAndGuestsAreHereOnTheirEveningsAndARemovalTravels() {
        val s = ours()
        val dani = Person(Couple.newId(), "דני", "star", "green", 10)
        val maya = Person(Couple.newId(), "מיה", "", "", 11, guest = true, day = "2026-11-27")
        assertTrue(Couple.isId(dani.who) && dani.other)
        Couple.put(s, dani); Couple.put(s, maya)
        assertEquals(listOf("סטיב", "רוני", "דני", "מיה"), Couple.everyone(s, "2026-11-27").map { it.name })
        assertEquals("the guest is gone the next evening", listOf("סטיב", "רוני", "דני"), Couple.everyone(s, "2026-11-28").map { it.name })
        assertEquals("but the devices still tell each other of them", 4, Couple.all(s).size)
        // Removed: gone from the evening, kept as a mark that is newer than what any other device has.
        Couple.remove(s, dani.who, now = 5)
        assertFalse(Couple.everyone(s, "2026-11-27").any { it.who == dani.who })
        val mark = Couple.others(s).first { it.who == dani.who }
        assertTrue(mark.removed); assertEquals(11L, mark.at)
        val tv = ours()
        Couple.offer(tv, dani)
        assertTrue("the removal reaches a device that still has them", Couple.offer(tv, mark))
        assertFalse(Couple.everyone(tv, "2026-11-27").any { it.who == dani.who })
        assertFalse("an older word does not bring them back", Couple.offer(tv, dani))
        // The two of us cannot be removed; someone unknown has an honest name.
        Couple.remove(s, "a", now = 99)
        assertEquals("סטיב", Couple.person(s, "a").name)
        assertEquals("אורח", Couple.name(s, "u000000"))
    }

    @Test
    fun theEveningLastsTillFiveInTheMorningSoAGuestStaysThroughALateGame() {
        val at = { d: Int, h: Int, m: Int -> java.time.LocalDateTime.of(2026, 11, d, h, m) }
        assertEquals("2026-11-27", Couple.evening(at(27, 21, 0)))
        assertEquals("2026-11-27", Couple.evening(at(27, 23, 59)))
        assertEquals("still the evening after midnight", "2026-11-27", Couple.evening(at(28, 0, 40)))
        assertEquals("2026-11-27", Couple.evening(at(28, 4, 59)))
        assertEquals("the next morning is the next evening's", "2026-11-28", Couple.evening(at(28, 5, 0)))
        // A guest who came at ten is still here for the game after midnight, and gone by the next evening.
        val s = ours()
        Couple.put(s, Person(Couple.newId(), "מיה", "", "", 11, guest = true, day = Couple.evening(at(27, 22, 0))))
        assertTrue(Couple.everyone(s, Couple.evening(at(28, 1, 15))).any { it.name == "מיה" })
        assertFalse(Couple.everyone(s, Couple.evening(at(28, 20, 0))).any { it.name == "מיה" })
    }

    @Test
    fun aPersonFromTheNetworkMustBeOneOfOurs() {
        assertNull(Person.fromJson("x1", org.json.JSONObject().put("name", "x")))
        assertNull(Person.fromJson("uZZZZZZ", org.json.JSONObject().put("name", "x")))
        assertFalse("the two of us are never guests", Person.fromJson("a", org.json.JSONObject().put("name", "x").put("guest", true))!!.guest)
        assertTrue(Person.fromJson("u1234567", org.json.JSONObject().put("removed", true))!!.removed)
        val n = Note.fromJson(org.json.JSONObject().put("id", "n").put("from", "u1234567").put("to", "a").put("text", "היי"))!!
        assertEquals("a", n.to)
        assertNull("not for the writer", Note.fromJson(org.json.JSONObject().put("id", "n").put("from", "a").put("to", "a").put("text", "x"))!!.to)
    }
}
