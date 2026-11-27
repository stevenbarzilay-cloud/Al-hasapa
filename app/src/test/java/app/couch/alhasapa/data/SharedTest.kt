package app.couch.alhasapa.data

import app.couch.alhasapa.scoring.Scoring
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the TV and the phones keep alike (1.12): each keeps its own copy, a change made on one is stamped, and where two copies meet the
 * newer of each thing wins, so the evening's points, the library, the links, our games, the jokes and the looks end up the same.
 */
class SharedTest {
    private val day = "2026-11-27"

    private class MemoryStore : StateStore {
        var text: String? = null
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    /** A device: its own state, and a clock the test moves. */
    private class Device(var now: Long = 1_764_000_000_000L) {
        val repo = StateRepository(MemoryStore()) { LocalDate.of(2026, 11, 27) }.also { it.clock = { now } }
        var told = 0
        init { repo.onLocalChange = { told++ } }
        fun state() = repo.snapshot()
        fun share(days: Collection<String>? = null, since: Long = 0, raw: Boolean = true) = Shared.share(state(), days, since, raw)
        fun points(day: String) = Scoring.dayPts(state().optJSONObject("log") ?: JSONObject(), day)
        fun entry(day: String, id: String) = CouchState.entry(state(), day, id)
    }

    private fun result(v: Int, max: Int, t: Long, raw: String = "") =
        JSONObject().put("w", "").put("r", JSONObject().put("v", v).put("max", max).put("fail", false).put("kind", "guesses").apply { if (raw.isNotEmpty()) put("raw", raw) }).put("t", t)

    @Test
    fun aResultSavedOnTheTvReachesThePhoneAndThePointsAreAlike() {
        val tv = Device(); val phone = Device()
        tv.repo.update { CouchState.setEntry(it, day, "wordle", result(3, 6, tv.now, "Wordle 1,618 3/6")) }
        assertEquals("a change made here is told on", 1, tv.told)
        assertTrue(tv.points(day) > 0)
        assertEquals(0, phone.points(day))
        assertTrue(phone.repo.mergeShared(tv.share(listOf(day))))
        assertEquals("the same points on both", tv.points(day), phone.points(day))
        assertEquals("what another device brought is not told on again", 0, phone.told)
        assertEquals(1L, phone.repo.merged.value)
        assertFalse("the same again changes nothing", phone.repo.mergeShared(tv.share(listOf(day))))
        assertEquals(1L, phone.repo.merged.value)
    }

    @Test
    fun whatWasTakenBackStaysTakenBackAndComesBackOnlyWhenPlayedAgain() {
        val tv = Device(); val phone = Device()
        tv.repo.update { CouchState.setEntry(it, day, "wordle", result(3, 6, tv.now)) }
        phone.repo.mergeShared(tv.share())
        val oldCopy = tv.share()                              // a third device's copy, from before the tick was taken back
        // The tick is taken back on the phone, a minute later.
        phone.now += 60_000
        phone.repo.update { CouchState.setEntry(it, day, "wordle", null) }
        assertTrue(phone.state().getJSONObject(Shared.GONE).has("$day|wordle"))
        assertTrue(tv.repo.mergeShared(phone.share()))
        assertNull("gone on the TV too", tv.entry(day, "wordle"))
        assertEquals(0, tv.points(day))
        assertFalse("an older copy does not bring it back", tv.repo.mergeShared(oldCopy))
        assertFalse(phone.repo.mergeShared(oldCopy))
        // Played again on the TV, later: back for good, and on the phone too.
        tv.now += 120_000
        tv.repo.update { CouchState.setEntry(it, day, "wordle", result(4, 6, tv.now)) }
        assertFalse("the mark goes with it", tv.state().optJSONObject(Shared.GONE)?.has("$day|wordle") == true)
        assertTrue(phone.repo.mergeShared(tv.share()))
        assertEquals(4, phone.entry(day, "wordle")!!.getJSONObject("r").getInt("v"))
        assertFalse(phone.state().optJSONObject(Shared.GONE)?.has("$day|wordle") == true)
    }

    @Test
    fun theNewerOfTwoResultsOfTheSameGameWinsOnBoth() {
        val tv = Device(); val phone = Device()
        tv.repo.update { CouchState.setEntry(it, day, "bandle", result(5, 6, tv.now)) }
        phone.now += 30_000
        phone.repo.update { CouchState.setEntry(it, day, "bandle", result(2, 6, phone.now)) }
        assertTrue(tv.repo.mergeShared(phone.share()))
        assertFalse("the TV's older one does not come back", phone.repo.mergeShared(tv.share()))
        assertEquals(2, tv.entry(day, "bandle")!!.getJSONObject("r").getInt("v"))
        assertEquals(tv.points(day), phone.points(day))
        // A change that carries no time of its own (who cracked it, set in place) is given one, so it travels.
        phone.now += 5_000
        phone.repo.update { s -> CouchState.entry(s, day, "bandle")!!.let { e -> e.put("w", "b"); CouchState.setEntry(s, day, "bandle", e) } }
        assertTrue(tv.repo.mergeShared(phone.share()))
        assertEquals("b", tv.entry(day, "bandle")!!.getString("w"))
    }

    @Test
    fun theLibraryTheLinksOurGamesAndTheJokesFollowTheNewerChange() {
        val tv = Device(); val phone = Device()
        phone.repo.update { s ->
            s.put("prefs", JSONObject().put("wordle", "no").put("bandle", "yes"))
            s.put("urls", JSONObject().put("hzchain", "https://example.org/chain"))
            s.put("custom", JSONArray().put(JSONObject().put("id", "c-ours").put("n", "המשחק שלנו").put("u", "https://example.org/g")))
            s.put("jokes", JSONArray().put("מי שמפסיד מכין תה"))
        }
        assertTrue(tv.repo.mergeShared(phone.share()))
        assertEquals("no", tv.state().getJSONObject("prefs").getString("wordle"))
        assertEquals("https://example.org/chain", tv.state().getJSONObject("urls").getString("hzchain"))
        assertEquals("c-ours", tv.state().getJSONArray("custom").getJSONObject(0).getString("id"))
        assertEquals("מי שמפסיד מכין תה", tv.state().getJSONArray("jokes").getString(0))
        // Back on the shelf from the TV, later; the game of ours deleted there; a link taken away; the jokes rewritten.
        tv.now += 60_000
        tv.repo.update { s ->
            s.getJSONObject("prefs").put("wordle", "yes")
            s.getJSONObject("prefs").remove("bandle")
            s.getJSONObject("urls").remove("hzchain")
            s.put("custom", JSONArray())
            s.put("jokes", JSONArray().put("עוד אחד ונלך לישון").put("בטח"))
        }
        val before = phone.share()
        assertTrue(phone.repo.mergeShared(tv.share()))
        val p = phone.state()
        assertEquals("yes", p.getJSONObject("prefs").getString("wordle"))
        assertFalse("a choice taken back is taken back there too", p.getJSONObject("prefs").has("bandle"))
        assertFalse(p.getJSONObject("urls").has("hzchain"))
        assertEquals(0, p.getJSONArray("custom").length())
        assertEquals(2, p.getJSONArray("jokes").length())
        assertFalse("the phone's older word changes nothing on the TV", tv.repo.mergeShared(before))
        assertEquals("yes", tv.state().getJSONObject("prefs").getString("wordle"))
    }

    @Test
    fun whatWasNeverStampedEndsUpAlikeOnBoth() {
        // From before 1.12: two devices that never told each other anything, each with its own library, links and jokes.
        fun old(prefs: JSONObject, jokes: List<String>, url: String) = CouchState.empty().put("prefs", prefs).put("jokes", JSONArray(jokes))
            .put("urls", JSONObject().put("hzchain", url))
        val tv = old(JSONObject().put("wordle", "no").put("pips", "yes"), listOf("א"), "https://b.example/x")
        val phone = old(JSONObject().put("wordle", "yes").put("tiles", "later"), listOf("ב"), "https://a.example/x")
        val tvShare = Shared.share(tv, null); val phoneShare = Shared.share(phone, null)
        assertTrue(Shared.merge(tv, phoneShare)); assertTrue(Shared.merge(phone, tvShare))
        for (s in listOf(tv, phone)) {
            assertEquals("on one's shelf: on the shelf", "yes", s.getJSONObject("prefs").getString("wordle"))
            assertEquals("yes", s.getJSONObject("prefs").getString("pips"))
            assertEquals("later", s.getJSONObject("prefs").getString("tiles"))
            assertEquals("https://b.example/x", s.getJSONObject("urls").getString("hzchain"))
            assertEquals(setOf("א", "ב"), (0 until s.getJSONArray("jokes").length()).map { i -> s.getJSONArray("jokes").getString(i) }.toSet())
        }
        assertFalse("and they stay so", Shared.merge(tv, Shared.share(phone, null)))
        assertFalse(Shared.merge(phone, Shared.share(tv, null)))
    }

    @Test
    fun theLooksOfUsTravelWithTheRest() {
        val tv = Device(); val phone = Device()
        phone.now += 1_000
        phone.repo.update { s ->
            Couple.put(s, Person("b", "רוני", "star", "blue", phone.now))
            Couple.put(s, Person("u1a2b3c4", "דני", "heart", "green", phone.now))
        }
        assertTrue(tv.repo.mergeShared(phone.share()))
        assertEquals("blue", Couple.person(tv.state(), "b").color)
        assertEquals("דני", Couple.person(tv.state(), "u1a2b3c4").name)
    }

    @Test
    fun aMessageForTheRelayHoldsOnlyWhatChangedSinceAndNoTextOfTheResult() {
        val tv = Device()
        tv.repo.update { CouchState.setEntry(it, day, "wordle", result(3, 6, tv.now, "Wordle 1,618 3/6")) }
        val first = tv.now
        tv.now += 60_000
        tv.repo.update { s -> CouchState.setEntry(s, day, "bandle", result(2, 6, tv.now, "Bandle 2/6")); s.put("prefs", JSONObject().put("pips", "no")) }
        val part = tv.share(listOf(day), since = first, raw = false)
        assertEquals(setOf("bandle"), part.getJSONObject("log").getJSONObject(day).keys().asSequence().toSet())
        assertFalse("the text a result was read from stays home", part.toString().contains("Bandle 2/6"))
        assertEquals("no", part.getJSONObject("prefs").getString("pips"))
        // On a device that has the same result with its text, the textless copy is no news.
        val phone = Device()
        phone.repo.mergeShared(tv.share())
        assertFalse(phone.repo.mergeShared(part))
        assertEquals("Bandle 2/6", phone.entry(day, "bandle")!!.getJSONObject("r").getString("raw"))
    }

    @Test
    fun aVeryFullEveningGoesInPartsEachSmallEnoughForALineOfTheLink() {
        val tv = Device()
        tv.repo.update { s -> for (i in 1..60) CouchState.setEntry(s, day, "game$i", result(3, 6, tv.now + i, "x".repeat(380))) }
        tv.repo.update { s -> CouchState.setEntry(s, "2026-11-26", "wordle", result(4, 6, tv.now)) }
        val parts = Shared.chunks(tv.state(), Shared.days(tv.state()), limit = 6_000)
        assertTrue("more than one for the full evening", parts.size > 4)
        assertTrue(parts.all { it.toString().length <= 6_000 + 600 })
        val phone = Device()
        for (p in parts) phone.repo.mergeShared(p)
        assertEquals(60, phone.state().getJSONObject("log").getJSONObject(day).length())
        assertNotNull(phone.entry("2026-11-26", "wordle"))
        assertEquals(tv.points(day), phone.points(day))
        assertEquals(listOf("2026-11-26", day), Shared.days(tv.state()))
    }

    @Test
    fun whatIsNotOursOrFromTheFutureIsNotTaken() {
        val phone = Device()
        val bad = JSONObject().put("v", 1).put("log", JSONObject()
            .put(day, JSONObject().put("wordle", result(1, 6, phone.now + 3 * 86_400_000L)).put("bad id!", result(1, 6, phone.now)))
            .put("not-a-day", JSONObject().put("wordle", result(1, 6, phone.now))))
        assertFalse(phone.repo.mergeShared(bad))
        assertFalse("another version's message", phone.repo.mergeShared(JSONObject().put("v", 2).put("log", JSONObject().put(day, JSONObject().put("wordle", result(1, 6, 5))))))
        assertFalse(phone.repo.mergeShared(null))
    }

    @Test
    fun aSaveFromTheHubsPageThatIsBehindOnlyByWhatOthersBroughtIsKeptAndMerged() {
        val phone = Device(); val tv = Device()
        phone.repo.update { CouchState.setEntry(it, day, "wordle", result(3, 6, phone.now)) }
        // The hub's page reads the state...
        val base = phone.repo.currentRevision()
        val page = JSONObject(phone.repo.json())
        // ...and while a result is being typed there, the TV's evening arrives.
        tv.now += 10_000
        tv.repo.update { CouchState.setEntry(it, day, "bandle", result(2, 6, tv.now)) }
        assertTrue(phone.repo.mergeShared(tv.share()))
        // The page saves what it has: its own change is kept, and so is what the TV brought.
        phone.now += 20_000
        page.getJSONObject("log").getJSONObject(day).put("connections", result(1, 4, phone.now))
        page.put("prefs", JSONObject().put("pips", "no"))
        val saved = phone.repo.saveFromHubMerging(page.toString(), base)
        assertTrue(saved.rev > 0); assertTrue("the page reads the state again", saved.reload)
        assertNotNull(phone.entry(day, "connections")); assertNotNull(phone.entry(day, "bandle")); assertNotNull(phone.entry(day, "wordle"))
        assertEquals("no", phone.state().getJSONObject("prefs").getString("pips"))
        // And it travels on: the TV learns of the page's change.
        assertTrue(tv.repo.mergeShared(phone.share()))
        assertNotNull(tv.entry(day, "connections"))
        // Behind by a change made here (the player saved a result): refused, as always.
        val base2 = phone.repo.currentRevision()
        val page2 = phone.repo.json()
        phone.repo.update { CouchState.setEntry(it, day, "strands", result(1, 1, phone.now)) }
        assertEquals(-1L, phone.repo.saveFromHubMerging(page2, base2).rev)
        // Up to date: saved as it is, nothing to read again.
        val ok = phone.repo.saveFromHubMerging(phone.repo.json(), phone.repo.currentRevision())
        assertTrue(ok.rev > 0); assertFalse(ok.reload)
    }
}
