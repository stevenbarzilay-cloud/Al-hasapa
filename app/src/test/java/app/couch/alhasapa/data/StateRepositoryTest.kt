package app.couch.alhasapa.data

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

class StateRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 21)

    private fun repo(file: File = File(tmp.root, "state.json")) = StateRepository(FileStateStore(file)) { today }

    private fun stateWith(day: String, id: String, entry: JSONObject) = CouchState.empty().also { CouchState.setEntry(it, day, id, entry) }

    @Test
    fun aFirstRunStartsEmptyAndSaysSo() {
        val r = repo()
        assertFalse(r.hadStoredState)
        assertEquals(0, r.snapshot().getJSONObject("log").length())
        assertEquals(CouchState.DEFAULT_A, CouchState.names(r.snapshot()).first)
    }

    @Test
    fun whatIsSavedSurvivesARestart() {
        val first = repo()
        val s = stateWith("2026-11-21", "wordle", JSONObject().put("w", "a"))
        assertEquals(1, first.saveFromHub(s.toString(), 0))
        val second = repo()
        assertTrue(second.hadStoredState)
        assertEquals("a", CouchState.entry(second.snapshot(), "2026-11-21", "wordle")!!.getString("w"))
        assertFalse("no temp file is left behind", File(tmp.root, "state.json.tmp").exists())
    }

    @Test
    fun aStaleSaveIsRefusedSoANativeChangeIsNeverOverwritten() {
        val r = repo()
        r.update { CouchState.setEntry(it, "2026-11-21", "wordle", JSONObject().put("w", "t")) }   // e.g. saved from the player
        val staleHub = CouchState.empty().toString()                                                 // the hub still has the old copy
        assertEquals(-1, r.saveFromHub(staleHub, 0))
        assertNotNull("the native change is still there", CouchState.entry(r.snapshot(), "2026-11-21", "wordle"))
        assertEquals("a save based on the current revision is accepted", 2L, r.saveFromHub(r.json(), r.currentRevision()))
    }

    @Test
    fun garbageNeverReplacesTheState() {
        val r = repo()
        assertEquals(-1, r.saveFromHub("not json", 0))
        assertEquals(-1, r.saveFromHub("{\"v\":2,\"log\":{}}", 0))
        assertEquals(0L, r.currentRevision())
    }

    @Test
    fun aCorruptFileStartsCleanInsteadOfCrashing() {
        val f = File(tmp.root, "state.json").also { it.writeText("{ half a fil") }
        val r = repo(f)
        assertFalse(r.hadStoredState)
        assertEquals(0, r.snapshot().getJSONObject("log").length())
    }

    @Test
    fun historyOlderThan400DaysIsPrunedOnSave() {
        val r = repo()
        val s = CouchState.empty()
        CouchState.setEntry(s, "2024-01-01", "wordle", JSONObject().put("w", "a"))
        CouchState.setEntry(s, "2026-11-01", "wordle", JSONObject().put("w", "a"))
        r.saveFromHub(s.toString(), 0)
        val log = r.snapshot().getJSONObject("log")
        assertFalse(log.has("2024-01-01")); assertTrue(log.has("2026-11-01"))
    }

    @Test
    fun migrationFromTheHubsOwnStorageHappensOnlyOnce() {
        val r = repo()
        val old = stateWith("2026-11-20", "strands", JSONObject().put("w", "b")).put("pieces", JSONObject()).toString()
        assertTrue(r.migrateFromHub(old) > 0)
        assertEquals(-1, r.migrateFromHub(CouchState.empty().toString()))                 // a second attempt is ignored
        assertNotNull(CouchState.entry(r.snapshot(), "2026-11-20", "strands"))
        val again = repo()
        assertEquals("not offered once a state exists", -1, again.migrateFromHub(old))
    }

    @Test
    fun unknownFieldsFromANewerAppAreKept() {
        val r = repo()
        val s = CouchState.empty().put("futureThing", JSONObject().put("x", 1))
        r.saveFromHub(s.toString(), 0)
        assertEquals(1, repo().snapshot().getJSONObject("futureThing").getInt("x"))
    }

    @Test
    fun theRevisionFlowTellsNativeScreensAboutChanges() {
        val r = repo()
        assertEquals(0L, r.revision.value)
        r.update { it.put("jokes", org.json.JSONArray().put("x")) }
        assertEquals(1L, r.revision.value)
    }

    @Test
    fun entryHelpersRoundTrip() {
        val s = CouchState.empty()
        CouchState.setEntry(s, "2026-11-21", "queens", JSONObject().put("w", ""))
        assertNotNull(CouchState.entry(s, "2026-11-21", "queens"))
        CouchState.setEntry(s, "2026-11-21", "queens", null)
        assertNull(CouchState.entry(s, "2026-11-21", "queens"))
        assertFalse("an empty day is removed", s.getJSONObject("log").has("2026-11-21"))
    }
}
