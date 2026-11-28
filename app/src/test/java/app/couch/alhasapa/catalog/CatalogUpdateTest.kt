package app.couch.alhasapa.catalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.data.StateStore
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The three games that were asked for by name show where they were asked for, whatever was chosen about them before; once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class CatalogUpdateTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())

    private class Memory(var json: String? = null) : StateStore {
        var writes = 0
        override fun read(): String? = json
        override fun write(json: String) { this.json = json; writes++ }
    }

    private fun stateWith(prefs: Map<String, String>): String =
        JSONObject().put("v", 1).put("prefs", JSONObject().apply { prefs.forEach { (k, v) -> put(k, v) } }).put("log", JSONObject()).toString()

    @Before fun asOnADeviceThatNeverSawThisCatalogue() {
        // (The app's own state is made first, as it is at the start of the app: it applies the catalogue's update itself, once.)
        (context as app.couch.alhasapa.CouchApp).repo
        context.getSharedPreferences("catalogue", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aSkipOfAGameAskedForByNameIsForgottenAndEveryOtherChoiceStays() {
        val store = Memory(stateWith(mapOf("framed" to "no", "timeguessr" to "later", "cinematrix" to "no", "boxoffice" to "no", "letterboxed" to "yes", "wordle" to "no")))
        val repo = StateRepository(store)
        assertFalse("skipped before: not on the shelf", catalog.inLib(catalog.game("framed")!!, repo.snapshot()))
        CatalogUpdate.apply(context, repo)
        val state = repo.snapshot()
        assertTrue("Framed is on the shelf, as asked", catalog.inLib(catalog.game("framed")!!, state))
        val prefs = state.getJSONObject("prefs")
        assertFalse(prefs.has("framed")); assertFalse(prefs.has("timeguessr")); assertFalse(prefs.has("cinematrix"))
        assertEquals("no", prefs.getString("boxoffice")); assertEquals("yes", prefs.getString("letterboxed")); assertEquals("no", prefs.getString("wordle"))
        assertEquals(1, store.writes)

        // Once: skipping one of them after that is final, like any other skip.
        repo.update { it.getJSONObject("prefs").put("framed", "no") }
        CatalogUpdate.apply(context, repo)
        assertEquals("no", repo.snapshot().getJSONObject("prefs").getString("framed"))
    }

    /** 2.0.3: every LinkedIn game on the shelf, the four new to it and any of the eight that had been taken off it; once. */
    @Test
    fun everyLinkedInGameIsOnTheShelfWhatEverWasChosenBeforeAndOnlyOnce() {
        val linkedIn = catalog.games.filter { it.s == "li" }.map { it.id }
        assertEquals(listOf("queens", "tango", "zip", "pinpoint", "crossclimb", "minisudoku", "patches", "wend"), linkedIn)
        // A device that took Zip off the shelf, put Patches off and said no to Crossclimb in the suggestions, and saw revision 2 already.
        context.getSharedPreferences("catalogue", Context.MODE_PRIVATE).edit().putInt("applied", 2).commit()
        val store = Memory(stateWith(mapOf("zip" to "no", "patches" to "later", "crossclimb" to "no", "queens" to "yes", "framed" to "no", "wordle" to "no")))
        val repo = StateRepository(store)
        assertFalse(catalog.inLib(catalog.game("zip")!!, repo.snapshot()))
        CatalogUpdate.apply(context, repo)
        val state = repo.snapshot()
        for (id in linkedIn) assertTrue("$id is on the shelf", catalog.inLib(catalog.game(id)!!, state))
        val prefs = state.getJSONObject("prefs")
        assertEquals("a yes stays", "yes", prefs.getString("queens"))
        assertEquals("revision 2 ran here already: its games are not asked again", "no", prefs.getString("framed"))
        assertEquals("no", prefs.getString("wordle"))
        assertEquals(1, store.writes)
        // Taken off again after that: final.
        repo.update { it.getJSONObject("prefs").put("wend", "no") }
        CatalogUpdate.apply(context, repo)
        assertFalse(catalog.inLib(catalog.game("wend")!!, repo.snapshot()))
    }

    /** A device that never saw revision 2 gets both, in one write. */
    @Test
    fun aDeviceFromBeforeBothRevisionsGetsBoth() {
        val store = Memory(stateWith(mapOf("framed" to "no", "minisudoku" to "later")))
        val repo = StateRepository(store)
        CatalogUpdate.apply(context, repo)
        val prefs = repo.snapshot().getJSONObject("prefs")
        assertFalse(prefs.has("framed")); assertFalse(prefs.has("minisudoku"))
        assertEquals(1, store.writes)
    }

    @Test
    fun aYesStaysAndADeviceWithNothingChosenIsNotWrittenTo() {
        val chosen = Memory(stateWith(mapOf("framed" to "yes", "timeguessr" to "yes")))
        CatalogUpdate.apply(context, StateRepository(chosen))
        assertEquals("nothing to forget: nothing written", 0, chosen.writes)
        assertEquals("yes", JSONObject(chosen.json!!).getJSONObject("prefs").getString("timeguessr"))

        context.getSharedPreferences("catalogue", Context.MODE_PRIVATE).edit().clear().commit()
        val fresh = Memory(null)
        val repo = StateRepository(fresh)
        CatalogUpdate.apply(context, repo)
        assertNull("a first run still has no stored state (the hub's own first-run step depends on that)", fresh.json)
        assertTrue(repo.isFresh())
    }
}
