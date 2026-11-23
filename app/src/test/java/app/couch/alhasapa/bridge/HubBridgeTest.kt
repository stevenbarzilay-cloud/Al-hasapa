package app.couch.alhasapa.bridge

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.StateRepository
import app.couch.alhasapa.data.StateStore
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Kotlin side of `window.couchNative`, against the real catalogue asset, clipboard and Hebrew strings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [37])
class HubBridgeTest {
    private class MemoryStore : StateStore {
        var text: String? = null
        override fun read() = text
        override fun write(json: String) { text = json }
    }

    private lateinit var context: Context
    private lateinit var repo: StateRepository
    private lateinit var bridge: HubBridge
    private val played = mutableListOf<String>()
    private var opened = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repo = StateRepository(MemoryStore()) { LocalDate.of(2026, 11, 21) }
        bridge = HubBridge(context, repo, Catalog.load(context), object : HubBridge.Actions {
            override fun play(gameId: String) { played += gameId }
            override fun openAccounts() { opened++ }
        })
    }

    @Test
    fun aFirstRunIsFreshAndCarriesNoState() {
        val r = JSONObject(bridge.loadState())
        assertTrue(r.getBoolean("fresh")); assertEquals(0, r.getInt("rev")); assertFalse(r.has("state"))
    }

    @Test
    fun saveNeedsTheRevisionThePageLastSaw() {
        val doc = CouchState.empty().toString()
        assertEquals("1", bridge.saveState(doc, "0"))
        assertEquals("a save from a stale copy is refused", "-1", bridge.saveState(doc, "0"))
        assertEquals("-1", bridge.saveState(doc, "not a number"))
        assertEquals("-1", bridge.saveState("garbage", "1"))
        val r = JSONObject(bridge.loadState())
        assertFalse(r.getBoolean("fresh")); assertEquals(1, r.getInt("rev")); assertNotNull(r.getJSONObject("state").getJSONObject("log"))
    }

    @Test
    fun migrationIsAcceptedOnlyOnTheFirstRun() {
        val old = CouchState.empty().put("jokes", org.json.JSONArray().put("ישן")).toString()
        assertEquals("1", bridge.migrateState(old))
        assertEquals("-1", bridge.migrateState(old))
    }

    @Test
    fun theCatalogueReachesThePageWithThePersonsLinksApplied() {
        var c = JSONObject(bridge.loadCatalog())
        assertEquals(68, c.getJSONArray("games").length())
        assertEquals("eleven sites and our own", 12, c.getJSONObject("sites").length())
        fun game(id: String) = (0 until c.getJSONArray("games").length()).map { c.getJSONArray("games").getJSONObject(it) }.first { it.getString("id") == id }
        assertEquals(game("hzchain").getString("u0"), game("hzchain").getString("u"))
        val doc = CouchState.empty().put("urls", JSONObject().put("hzchain", "https://www.haaretz.co.il/riddles/today"))
        bridge.saveState(doc.toString(), "0")
        c = JSONObject(bridge.loadCatalog())
        assertEquals("https://www.haaretz.co.il/riddles/today", game("hzchain").getString("u"))
        assertTrue("the original link stays available for the links sheet", game("hzchain").getString("u0") != game("hzchain").getString("u"))
    }

    @Test
    fun onlyKnownGamesCanBePlayed() {
        bridge.play("wordle"); bridge.play("nonsense"); bridge.play("")
        assertEquals(listOf("wordle"), played)
    }

    @Test
    fun theHubCanOpenTheAccountsScreen() {
        bridge.openAccounts()
        assertEquals(1, opened)
    }

    @Test
    fun exportThenImportOnAnotherPhoneRestoresTheHistory() {
        val s = CouchState.empty()
        CouchState.setEntry(s, "2026-11-20", "wordle", JSONObject().put("w", "a").put("t", 5L))
        bridge.saveState(s.toString(), "0")
        val backup = bridge.exportBackup()
        assertEquals("al-hasapa-shelanu", JSONObject(backup).getString("app"))

        val other = StateRepository(MemoryStore()) { LocalDate.of(2026, 11, 21) }
        val otherBridge = HubBridge(context, other, Catalog.load(context), object : HubBridge.Actions { override fun play(gameId: String) {}; override fun openAccounts() {} })
        val r = JSONObject(otherBridge.importBackup(backup))
        assertTrue(r.getBoolean("ok"))
        assertEquals(1, r.getInt("added"))
        assertEquals("יובא: 1 חדשים, 0 עודכנו, 0 כבר היו.", r.getString("message"))
        assertNotNull(CouchState.entry(other.snapshot(), "2026-11-20", "wordle"))
    }

    @Test
    fun aBadBackupIsRefusedWithAHebrewReason() {
        val r = JSONObject(bridge.importBackup("hello"))
        assertFalse(r.getBoolean("ok")); assertEquals("זה לא נראה כמו גיבוי.", r.getString("message"))
        assertEquals("אין מה לייבא. מדביקים את הגיבוי בתיבה.", JSONObject(bridge.importBackup("  ")).getString("message"))
        assertEquals("זה לא גיבוי של על הספה שלנו.", JSONObject(bridge.importBackup("{\"foo\":1}")).getString("message"))
    }

    @Test
    fun copyingPutsTheTextOnTheClipboard() {
        assertEquals("ok", bridge.copyText("hello couch"))
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("hello couch", cm.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun aGameAddedInTheSettingsCanBePlayedAndTheNextCatalogueHasIt() {
        var cat = Catalog.load(context)
        val b = HubBridge(context, repo, { cat }, object : HubBridge.Actions { override fun play(gameId: String) { played += gameId }; override fun openAccounts() {} })
        b.play("c9")
        assertFalse("not known yet", "c9" in played)
        cat = cat.withCustom(org.json.JSONArray("""[{"id":"c9","n":"Ours","s":"my","k":"ידע","m":"O","u":"https://games.example.org/x","metric":"none"}]"""))
        b.play("c9")
        assertTrue("c9" in played)
        assertEquals(69, JSONObject(b.loadCatalog()).getJSONArray("games").length())
    }

    @Test
    fun theDiagnosticsTheSettingsPageShowsAreTextWithTheVersionAndNothingPrivate() {
        val d = bridge.diagnostics()
        assertTrue(d.isNotBlank()); assertFalse(d.contains("@"))
    }

    @Test
    fun keyboardModeIsOfferedOnThePhoneOnly() {
        var opened = 0
        val b = HubBridge(context, repo, Catalog.load(context), object : HubBridge.Actions {
            override fun play(gameId: String) {}
            override fun openAccounts() {}
            override fun openKeyboardMode() { opened++ }
        })
        b.openKeyboardMode()
        if (app.couch.alhasapa.BuildConfig.TV) { assertEquals("no", b.keyboardMode()); assertEquals("the TV is the one being typed into", 0, opened) }
        else { assertEquals("yes", b.keyboardMode()); assertEquals(1, opened) }
    }
}
