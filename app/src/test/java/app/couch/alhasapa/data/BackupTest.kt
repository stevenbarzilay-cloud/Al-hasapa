package app.couch.alhasapa.data

import app.couch.alhasapa.scoring.Scoring
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private fun entry(w: String = "", t: Long? = null, r: JSONObject? = null, d: JSONObject? = null) =
        JSONObject().put("w", w).apply { if (t != null) put("t", t); if (r != null) put("r", r); if (d != null) put("d", d) }

    private fun side(v: Int, t: Long? = null) = JSONObject().put("v", v).put("fail", false).apply { if (t != null) put("t", t) }
    private fun state(vararg e: Triple<String, String, JSONObject>) = CouchState.empty().also { s -> e.forEach { (d, id, en) -> CouchState.setEntry(s, d, id, en) } }
    private fun merge(local: JSONObject, incoming: JSONObject) = Backup.merge(local, incoming)

    private fun importText(local: JSONObject, backup: JSONObject) =
        when (val p = Backup.parse(Backup.export(backup, Instant.parse("2026-11-21T10:00:00Z")))) {
            is Backup.Parsed.Ok -> Backup.merge(local, p.state)
            is Backup.Parsed.Bad -> error("rejected: ${p.reason}")
        }

    @Test
    fun exportIsThePrototypesFormat() {
        val txt = Backup.export(CouchState.empty(), Instant.parse("2026-11-21T10:00:00Z"))
        val o = JSONObject(txt)
        assertEquals("al-hasapa-shelanu", o.getString("app"))
        assertEquals("2026-11-21T10:00:00Z", o.getString("exported"))
        assertEquals(1, o.getJSONObject("state").getInt("v"))
    }

    @Test
    fun roundTripIntoAnEmptyPhoneRestoresEverything() {
        val s = state(Triple("2026-11-20", "wordle", entry("a", 5, JSONObject().put("v", 3).put("max", 6).put("fail", false).put("kind", "guesses"))))
            .put("jokes", JSONArray().put("מי שמפסיד מכין תה")).put("pieces", JSONObject().put("a", JSONObject().put("shape", "star")))
        s.getJSONObject("names").put("a", "אבי")
        val m = importText(CouchState.empty(), s)
        assertEquals(1, m.stats.added)
        assertEquals("אבי", CouchState.names(m.state).first)
        assertEquals("מי שמפסיד מכין תה", m.state.getJSONArray("jokes").getString(0))
        assertEquals("star", m.state.getJSONObject("pieces").getJSONObject("a").getString("shape"))
        assertEquals(Scoring.dayPts(s.getJSONObject("log"), "2026-11-20"), Scoring.dayPts(m.state.getJSONObject("log"), "2026-11-20"))
    }

    @Test
    fun theNewerEntryWinsPerDayAndGame() {
        val local = state(Triple("2026-11-20", "wordle", entry("a", t = 100)))
        val theirs = state(Triple("2026-11-20", "wordle", entry("b", t = 200)))
        assertEquals("b", CouchState.entry(merge(CouchState.copy(local), theirs).state, "2026-11-20", "wordle")!!.getString("w"))
        val older = state(Triple("2026-11-20", "wordle", entry("b", t = 50)))
        assertEquals("a", CouchState.entry(merge(CouchState.copy(local), older).state, "2026-11-20", "wordle")!!.getString("w"))
    }

    @Test
    fun backupsFromTheOriginalPrototypeHaveNoTimestampsSoTheRicherEntryWins() {
        val bare = state(Triple("2026-11-20", "wordle", entry("")))
        val withResult = state(Triple("2026-11-20", "wordle", entry("a", r = JSONObject().put("v", 3).put("kind", "guesses").put("fail", false))))
        val m = merge(CouchState.copy(bare), withResult)
        assertTrue(CouchState.entry(m.state, "2026-11-20", "wordle")!!.has("r"))
        val back = merge(CouchState.copy(withResult), bare)
        assertTrue("a plain tick never wipes a recorded result", CouchState.entry(back.state, "2026-11-20", "wordle")!!.has("r"))
    }

    @Test
    fun headToHeadSidesMergeSideBySide() {
        val steve = state(Triple("2026-11-20", "queens", entry(t = 10, d = JSONObject().put("a", side(84, 10)))))
        val roni = state(Triple("2026-11-20", "queens", entry(t = 20, d = JSONObject().put("b", side(90, 20)))))
        val m = merge(CouchState.copy(steve), roni)
        val e = CouchState.entry(m.state, "2026-11-20", "queens")!!
        assertEquals(84, e.getJSONObject("d").getJSONObject("a").getInt("v"))
        assertEquals(90, e.getJSONObject("d").getJSONObject("b").getInt("v"))
        assertEquals("a", Scoring.duelWinner(e))
    }

    @Test
    fun importingNeverDeletesAndLocalChoicesStand() {
        val local = state(Triple("2026-11-19", "mini", entry("t")))
        local.getJSONObject("prefs").put("wordle", "no")
        local.getJSONObject("names").put("a", "אבי")
        local.put("jokes", JSONArray().put("א"))
        val theirs = state(Triple("2026-11-20", "wordle", entry("a")))
        theirs.getJSONObject("prefs").put("wordle", "yes").put("tiles", "yes")
        theirs.getJSONObject("names").put("a", "סתם")
        theirs.put("jokes", JSONArray().put("א").put("ב"))
        val m = merge(CouchState.copy(local), theirs).state
        assertTrue(CouchState.entry(m, "2026-11-19", "mini") != null)
        assertTrue(CouchState.entry(m, "2026-11-20", "wordle") != null)
        assertEquals("no", m.getJSONObject("prefs").getString("wordle"))
        assertEquals("yes", m.getJSONObject("prefs").getString("tiles"))
        assertEquals("a name someone changed is kept", "אבי", CouchState.names(m).first)
        assertEquals(listOf("א", "ב"), (0 until m.getJSONArray("jokes").length()).map { m.getJSONArray("jokes").getString(it) })
    }

    @Test
    fun aDefaultNameIsReplacedByAChangedOne() {
        val theirs = CouchState.empty().also { it.getJSONObject("names").put("b", "נועה") }
        assertEquals("נועה", CouchState.names(merge(CouchState.empty(), theirs).state).second)
    }

    @Test
    fun mergingTheSameBackupTwiceChangesNothing() {
        val s = state(Triple("2026-11-20", "wordle", entry("a", 5)), Triple("2026-11-20", "queens", entry(d = JSONObject().put("a", side(80, 5)))))
        val once = merge(CouchState.copy(CouchState.empty()), s).state
        val twice = merge(CouchState.copy(once), s)
        assertEquals(once.toString(), twice.state.toString())
        assertEquals(2, twice.stats.kept)
    }

    @Test
    fun aBareStateDocumentIsAccepted() {
        val p = Backup.parse(state(Triple("2026-11-20", "wordle", entry("a"))).toString())
        assertTrue(p is Backup.Parsed.Ok)
    }

    @Test
    fun garbageIsRejectedWithAReason() {
        fun reason(t: String) = (Backup.parse(t) as Backup.Parsed.Bad).reason
        assertEquals(Backup.Reason.EMPTY, reason("   "))
        assertEquals(Backup.Reason.NOT_JSON, reason("hello"))
        assertEquals(Backup.Reason.NOT_OURS, reason("{\"app\":\"other\",\"state\":{\"v\":1,\"log\":{}}}"))
        assertEquals(Backup.Reason.NOT_OURS, reason("{\"foo\":1}"))
        assertEquals(Backup.Reason.WRONG_VERSION, reason("{\"app\":\"al-hasapa-shelanu\",\"state\":{\"v\":2,\"log\":{}}}"))
        assertEquals(Backup.Reason.TOO_BIG, reason("x".repeat(5_000_001)))
    }

    @Test
    fun malformedDaysAndEntriesAreDroppedNotFatal() {
        val txt = """{"app":"al-hasapa-shelanu","state":{"v":1,"names":{"a":"x","b":"y"},"prefs":{},
            "log":{"2026-11-20":{"wordle":{"w":"a"},"bad":7},"not-a-day":{"wordle":{"w":"a"}},"2026-11-21":5}}}"""
        val p = Backup.parse(txt) as Backup.Parsed.Ok
        assertEquals(3, p.skippedEntries)
        assertEquals(setOf("2026-11-20"), p.state.getJSONObject("log").keys().asSequence().toSet())
        assertFalse(p.state.getJSONObject("log").getJSONObject("2026-11-20").has("bad"))
    }

    @Test
    fun aRealisticPrototypeBackupImportsWithIdenticalScores() {
        // Shaped exactly like what the prototype's "להעתיק גיבוי" produced (no timestamps, no pieces, no urls).
        val log = JSONObject()
        val days = listOf("2026-11-15", "2026-11-16", "2026-11-17", "2026-11-18", "2026-11-19", "2026-11-20")
        for ((i, d) in days.withIndex()) {
            val day = JSONObject()
            day.put("wordle", JSONObject().put("w", if (i % 2 == 0) "a" else "t").put("r", JSONObject().put("v", 2 + i % 4).put("max", 6).put("fail", false).put("kind", "guesses").put("raw", "Wordle 1,6$i ${2 + i % 4}/6")))
            day.put("mini", JSONObject().put("w", "b").put("r", JSONObject().put("v", 120 - i * 7).put("fail", false).put("kind", "time")))
            if (i % 2 == 1) day.put("queens", JSONObject().put("w", "").put("d", JSONObject().put("a", side(80 + i)).put("b", side(85 - i))))
            log.put(d, day)
        }
        val original = JSONObject().put("v", 1).put("names", JSONObject().put("a", "סטיב").put("b", "רוני")).put("prefs", JSONObject().put("tiles", "yes")).put("jokes", JSONArray().put("בדיחה")).put("log", log)
        val text = JSONObject().put("app", "al-hasapa-shelanu").put("exported", "2026-11-20T21:00:00.000Z").put("state", original).toString()
        val parsed = Backup.parse(text) as Backup.Parsed.Ok
        val m = Backup.merge(CouchState.empty(), parsed.state)
        val a = original.getJSONObject("log"); val b = m.state.getJSONObject("log")
        for (d in days) {
            assertEquals("team points $d", Scoring.dayPts(a, d), Scoring.dayPts(b, d))
            for (id in listOf("queens")) assertEquals("duel $d", Scoring.duelWinner(a.getJSONObject(d).optJSONObject(id)), Scoring.duelWinner(b.getJSONObject(d).optJSONObject(id)))
        }
        assertEquals(days.size * 2 + days.count { days.indexOf(it) % 2 == 1 }, m.stats.added)
    }
}
