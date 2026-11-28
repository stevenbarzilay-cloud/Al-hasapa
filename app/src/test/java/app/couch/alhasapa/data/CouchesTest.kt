package app.couch.alhasapa.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Couches (2.0): every state says which couch it is the state of, and nothing of one couch is merged into another's. */
class CouchesTest {
    private fun couch(id: String, name: String, a: String = "דני", b: String = "מיה"): JSONObject =
        CouchState.empty().also { s -> Couple.put(s, Person("a", a, "", "", 10)); Couple.put(s, Person("b", b, "", "", 10)); Couches.set(s, id, name, if (name.isEmpty()) 0 else 20) }

    @Test
    fun aNewCouchHasNoNamesOfOursAndIsCalledAfterItsPlayers() {
        val fresh = CouchState.empty()
        assertEquals("שחקן 1" to "שחקן 2", CouchState.names(fresh))
        assertFalse("nothing of ours in a new state", fresh.toString().contains("סטיב") || fresh.toString().contains("רוני"))
        assertTrue("a new device chooses first", Couches.needsSetup(fresh))
        assertNull(Couches.of(fresh))
        assertEquals("הספה שלנו", Couches.name(fresh))

        val s = couch(Couches.newId(), "")
        assertFalse(Couches.needsSetup(s))
        assertEquals("called after its two players until it is named", "הספה של דני ומיה", Couches.of(s)!!.name)
        Couple.put(s, Person("b", "מאיה", "", "", 30))
        assertEquals("and follows their names", "הספה של דני ומאיה", Couches.of(s)!!.name)
        Couches.rename(s, "הסלון של דני", now = 40)
        Couple.put(s, Person("b", "מיה", "", "", 50))
        assertEquals("once named it keeps its name", "הסלון של דני", Couches.of(s)!!.name)
        assertEquals("הספה של דני", Couches.defaultName("דני", ""))
    }

    @Test
    fun aCouchBegunWithOnePressIsBareUntilAnythingIsInIt() {
        val s = CouchState.empty().also { Couches.set(it, "c9999999999999ee", "", 0L) }
        assertTrue(Couches.bare(s))
        assertFalse("no couch: not bare, new", Couches.bare(CouchState.empty()))
        fun with(block: (JSONObject) -> Unit) = CouchState.copy(s).also(block)
        assertFalse("named", Couches.bare(with { Couches.rename(it, "הסלון", now = 5) }))
        assertFalse("a player named", Couches.bare(with { Couple.put(it, Person("a", "נועה", "", "", 5)) }))
        assertFalse("something played", Couches.bare(with { CouchState.setEntry(it, "2026-11-28", "wordle", JSONObject().put("w", "").put("t", 5L)) }))
        assertFalse("someone added", Couches.bare(with { Couple.put(it, Person("u1234567", "סבתא", "", "", 5)) }))
        assertFalse("a game played alone", Couches.bare(with { Solo.put(it, "2026-11-28", "bandle", SoloSide("a", null, t = 5), now = 10) }))
        assertFalse("a note", Couches.bare(with { Notes.add(it, Note("n1", "a", null, "שלום", emptyList(), 5)) }))
        assertTrue("a game put on the shelf is not something in it", Couches.bare(with { it.getJSONObject("prefs").put("wordle", "yes") }))
    }

    @Test
    fun aStateFromBefore20KeepsOurNamesAndIsNotANewDevice() {
        // As 1.12 wrote it: no couch record. Names left empty by an old backup come back as they always did.
        val old = CouchState.parse("""{"v":1,"names":{"a":"","b":""},"prefs":{},"log":{"2026-11-20":{"wordle":{"w":"a","t":5}}}}""")!!
        assertEquals("סטיב" to "רוני", CouchState.names(old))
        assertFalse("it is given its couch without a question", Couches.needsSetup(old))
        val named = CouchState.parse("""{"v":1,"names":{"a":"סטיב","b":"רוני"},"prefs":{},"log":{}}""")!!
        assertFalse("even with nothing played: the names are someone's", Couches.needsSetup(named))
        // A state of 2.0 whose names were emptied gets the neutral ones, not ours.
        val now = CouchState.parse("""{"v":1,"names":{"a":"","b":"מיה"},"prefs":{},"log":{},"couch":{"id":"c0123456789abcde","name":"","at":0}}""")!!
        assertEquals("שחקן 1" to "מיה", CouchState.names(now))
    }

    @Test
    fun theDevicesOfOneHomeFromBefore20AgreeOnItsIdWithoutTalking() {
        val topic = "couch-AbCdEfGhIjKlMnOpQrStUv"
        assertEquals(Couches.idFromTopic(topic), Couches.idFromTopic(topic))
        assertTrue(Couches.isId(Couches.idFromTopic(topic)))
        assertNotEquals(Couches.idFromTopic(topic), Couches.idFromTopic(topic + "x"))
        assertTrue(Couches.isId(Couches.newId()))
        assertNotEquals(Couches.newId(), Couches.newId())
        assertFalse(Couches.isId("") || Couches.isId("c123") || Couches.isId("x0123456789abcde"))
    }

    @Test
    fun aSentenceReadsRightWhateverTheCouchIsCalled() {
        assertEquals("בספה של דני", Couches.inCouch("הספה של דני"))
        assertEquals("לספה של סטיב ורוני", Couches.toCouch("הספה של סטיב ורוני"))
        assertEquals("ב«הסלון»", Couches.inCouch("הסלון"))
        assertEquals("ל«הסלון»", Couches.toCouch("הסלון"))
    }

    @Test
    fun aShareOfAnotherCouchIsNeverMerged() {
        val ours = couch("c1111111111111aa", "", "סטיב", "רוני")
        val theirs = couch("c2222222222222bb", "")
        CouchState.setEntry(theirs, "2026-11-27", "wordle", JSONObject().put("w", "a").put("t", 100L))
        Solo.put(theirs, "2026-11-27", "connections", SoloSide("a", 2.0, t = 100), now = 200)
        val before = ours.toString()
        for (share in Shared.chunks(theirs, listOf("2026-11-27"))) {
            assertEquals("every part says whose it is", "c2222222222222bb", share.getString("c"))
            assertFalse("refused whole", Shared.merge(ours, share, now = 1_000))
        }
        assertEquals("nothing of Danny's couch is in ours", before, ours.toString())
        // The same share into a state of the same couch is taken.
        val theirPhone = couch("c2222222222222bb", "")
        assertTrue(Shared.chunks(theirs, listOf("2026-11-27")).map { Shared.merge(theirPhone, it, now = 1_000) }.any { it })
        assertEquals("a", CouchState.entry(theirPhone, "2026-11-27", "wordle")!!.getString("w"))
        assertEquals(2.0, Solo.side(theirPhone, "2026-11-27", "connections", "a")!!.v!!, 0.0)
    }

    @Test
    fun aDeviceWithNoCouchYetTakesTheOneItJoinsAndItsNames() {
        val phone = couch("c3333333333333cc", "הספה של דני")
        val tv = CouchState.empty()
        var changed = false
        for (share in Shared.chunks(phone, emptyList())) if (Shared.merge(tv, share, now = 1_000)) changed = true
        assertTrue(changed)
        assertEquals(CouchRef("c3333333333333cc", "הספה של דני"), Couches.of(tv))
        assertEquals("דני" to "מיה", CouchState.names(tv))
        // Names nobody gave yet are taken even when neither side ever stamped them (a couch from before 1.9.9).
        val old = CouchState.empty().also { it.put("names", JSONObject().put("a", "דני").put("b", "מיה")); Couches.set(it, "c4444444444444dd", "", 0) }
        val newTv = CouchState.empty()
        Shared.chunks(old, emptyList()).forEach { Shared.merge(newTv, it, now = 1_000) }
        assertEquals("דני" to "מיה", CouchState.names(newTv))
        assertEquals("הספה של דני ומיה", Couches.of(newTv)!!.name)
        // But a named player is never renamed by an unstamped word.
        assertFalse(Couple.offer(phone, Person("a", "מישהו", "", "", 0)))
    }

    @Test
    fun theNewerNameOfACouchWinsOnEveryDeviceOfIt() {
        val tv = couch("c5555555555555ee", "")
        val phone = CouchState.copy(tv)
        Couches.rename(phone, "הספה בסלון", now = 500)
        assertTrue(Couches.offer(tv, Couches.toJson(phone)))
        assertEquals("הספה בסלון", Couches.of(tv)!!.name)
        assertFalse("an older name changes nothing", Couches.offer(tv, JSONObject().put("id", "c5555555555555ee").put("name", "ישן").put("at", 400)))
        assertFalse("another couch's word changes nothing", Couches.offer(tv, JSONObject().put("id", "c6666666666666ff").put("name", "אחר").put("at", 9_000)))
        assertEquals("הספה בסלון", Couches.of(tv)!!.name)
        assertEquals("the id never changes", "c5555555555555ee", Couches.of(tv)!!.id)
    }

    @Test
    fun aRelayMessageTooBigForOnePartStillSaysWhoseItIsInEveryPart() {
        val s = couch("c7777777777777aa", "")
        repeat(30) { i -> CouchState.setEntry(s, "2026-11-27", "game-$i", JSONObject().put("w", "a").put("r", JSONObject().put("v", i).put("raw", "x".repeat(120))).put("t", 100L + i)) }
        val share = Shared.share(s, listOf("2026-11-27"))
        val parts = app.couch.alhasapa.remote.SoloRelay.parts(share)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.optString("c") == "c7777777777777aa" })
        val other = couch("c8888888888888bb", "")
        assertTrue(parts.none { Shared.merge(other, it, now = 1_000) })
    }
}

/** Solo contests of several (2.0): who joined, who handed in, and when the crown is given. */
class SoloContestTest {
    private val day = "2026-11-27"
    private fun state(vararg sides: SoloSide): JSONObject = CouchState.empty().also { s -> sides.forEach { Solo.put(s, day, "connections", it, now = 10_000) } }

    @Test
    fun withTwoItIsAsItAlwaysWas() {
        val s = state(SoloSide("a", 1.0, t = 1, kind = "mistakes"), SoloSide("b", 3.0, t = 2, kind = "mistakes"))
        assertEquals("a", Solo.winner(s, day, "connections", "mistakes"))
        val one = state(SoloSide("a", 1.0, t = 1, kind = "mistakes"), SoloSide("b", null, t = 2))
        assertNull("the second only played: nothing to open yet", Solo.winner(one, day, "connections", "mistakes"))
        assertNull("nor after the day: one result is no contest", Solo.winner(one, day, "connections", "mistakes", over = true))
    }

    @Test
    fun withThreeTheCrownWaitsForWhoeverJoinedAndIsStillPlaying() {
        val dani = SoloSide("a", 2.0, t = 1, kind = "mistakes")
        val maya = SoloSide("b", 0.0, t = 2, kind = "mistakes")
        val guest = SoloSide("u1234567", null, t = 3)
        val s = state(dani, maya, guest)
        assertEquals(3, Solo.players(s, day, "connections").size)
        assertNull("the guest joined and has not handed in", Solo.winner(s, day, "connections", "mistakes"))
        assertEquals(listOf("u1234567"), Solo.waiting(Solo.sides(s, day, "connections")).map { it.who })
        // Whoever handed in sees the others who did; the one still playing sees nothing of them.
        assertEquals(listOf(2.0, 0.0), Solo.sealedFor(s, day, "connections", "a").filter { it.who != "u1234567" }.map { it.v })
        val forGuest = Solo.sealedFor(s, day, "connections", "u1234567")
        assertTrue(forGuest.filter { it.who != "u1234567" }.all { it.v == null && it.env })
        // They hand in: the envelopes open, and the best takes the crown.
        Solo.put(s, day, "connections", SoloSide("u1234567", 1.0, t = 4, kind = "mistakes"), now = 10_000)
        assertEquals("b", Solo.winner(s, day, "connections", "mistakes"))
        assertEquals(listOf("b"), Solo.leadersOf(Solo.sides(s, day, "connections"), "mistakes"))
    }

    @Test
    fun someoneWhoLeavesOrADayThatEndedHoldsNobodyUp() {
        val s = state(SoloSide("a", 2.0, t = 1, kind = "mistakes"), SoloSide("b", 0.0, t = 2, kind = "mistakes"), SoloSide("u1234567", null, t = 3))
        // The evening ends: whoever handed in is compared.
        assertEquals("b", Solo.winner(s, day, "connections", "mistakes", over = true))
        assertTrue(Solo.over(day, evening = "2026-11-28"))
        assertFalse(Solo.over(day, evening = day))
        assertEquals(mapOf("b" to 1), Solo.wins(s, { "mistakes" }, day, day, evening = "2026-11-28"))
        assertTrue("while the day is on, the scoreboard waits too", Solo.wins(s, { "mistakes" }, day, day, evening = day).isEmpty())
        // Or they leave the contest: the others are not kept waiting.
        Solo.put(s, day, "connections", SoloSide("u1234567", null, t = 5, out = true), now = 10_000)
        assertEquals(2, Solo.players(s, day, "connections").size)
        assertEquals("b", Solo.winner(s, day, "connections", "mistakes"))
        val back = SoloSide.fromJson("u1234567", Solo.side(s, day, "connections", "u1234567")!!.toJson())!!
        assertTrue(back.out && !back.hasResult)
    }

    @Test
    fun aTieAmongSeveralNamesWhoIsLevel() {
        val s = state(
            SoloSide("a", 1.0, t = 1, kind = "mistakes"), SoloSide("b", 1.0, t = 2, kind = "mistakes"),
            SoloSide("u1234567", 3.0, t = 3, kind = "mistakes"), SoloSide("u7654321", null, t = 4, fail = true, kind = "mistakes"),
        )
        assertEquals("t", Solo.winner(s, day, "connections", "mistakes"))
        assertEquals(setOf("a", "b"), Solo.leadersOf(Solo.sides(s, day, "connections"), "mistakes").toSet())
    }

    @Test
    fun aSealedSideSaysOnlyThatThereIsAResult() {
        val sealed = SoloSide("a", 4.0, 6, kind = "guesses", t = 9).sealed()
        assertTrue(sealed.env && sealed.v == null && sealed.max == null && !sealed.hasResult)
        val text = sealed.toJson().toString()
        assertFalse("nothing of the result is in it", text.contains("4") || text.contains("guesses"))
        val back = SoloSide.fromJson("a", sealed.toJson())!!
        assertTrue(back.env && back.t == 9L)
        assertFalse("played, no result: nothing in the envelope", SoloSide("b", null, t = 3).sealed().env)
    }
}
