package app.couch.alhasapa.catalog

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())

    @Test
    fun everyGameOfThePrototypeIsThere() {
        assertEquals(54, catalog.games.size)
        assertEquals("ids are unique", catalog.games.size, catalog.games.map { it.id }.toSet().size)
        for (g in catalog.games) assertNotNull("${g.id} needs its site ${g.s}", catalog.sites[g.s])
        assertEquals(11, catalog.sites.size)
    }

    @Test
    fun metricsAreTheOnesTheScoringKnows() {
        val ok = setOf("guesses", "mistakes", "hints", "time", "words", "none")
        for (g in catalog.games) assertTrue("${g.id}: ${g.metric}", g.metric in ok)
        fun m(id: String) = catalog.game(id)!!.metric
        assertEquals("guesses", m("wordle")); assertEquals("mistakes", m("connections")); assertEquals("hints", m("strands"))
        assertEquals("time", m("queens")); assertEquals("guesses", m("pinpoint")); assertEquals("words", m("letterboxed"))
        assertEquals("mistakes", m("hzconn")); assertEquals("none", m("krillion")); assertEquals("guesses", m("hz5"))
    }

    @Test
    fun onlyTheLinkedInGamesAreHeadToHead() {
        assertEquals(setOf("queens", "tango", "zip", "pinpoint", "crossclimb", "minisudoku", "patches", "wend"),
            catalog.games.filter { it.isDuel }.map { it.id }.toSet())
    }

    @Test
    fun linksAreHttpsAndPlaceholdersAreFlagged() {
        for (g in catalog.games) assertTrue("${g.id} link must be https: ${g.u}", g.u.startsWith("https://"))
        assertEquals(setOf("midi", "noillirk", "moreless", "cinematrix", "codenames", "cutle", "coffeegolf"),
            catalog.games.filter { it.placeholder }.map { it.id }.toSet())
    }

    @Test
    fun aPersonalLinkOverridesTheCatalogueAndIsAllowedOnItsOwnHost() {
        val g = catalog.game("hzchain")!!
        assertEquals(g.u, catalog.urlFor(g, JSONObject()))
        val state = JSONObject().put("urls", JSONObject().put("hzchain", "https://www.haaretz.co.il/riddles/chain-today"))
        assertEquals("https://www.haaretz.co.il/riddles/chain-today", catalog.urlFor(g, state))
        val q = catalog.game("framed")!!
        val own = "https://example.org/play"
        assertTrue("a direct link on any host is allowed for that game", Catalog.hostAllowed("example.org", catalog.allowedHosts(q, own)))
        assertFalse(Catalog.hostAllowed("ads.example.net", catalog.allowedHosts(q, own)))
    }

    @Test
    fun allowlistMatchesSubdomainsButNotLookalikes() {
        val g = catalog.game("wordle")!!
        val allowed = catalog.allowedHosts(g, g.u)
        assertTrue(Catalog.hostAllowed("www.nytimes.com", allowed))
        assertTrue(Catalog.hostAllowed("myaccount.nytimes.com", allowed))
        assertTrue(Catalog.hostAllowed("nytimes.com", allowed))
        assertFalse("lookalike", Catalog.hostAllowed("evilnytimes.com", allowed))
        assertFalse("lookalike", Catalog.hostAllowed("nytimes.com.evil.org", allowed))
        assertFalse(Catalog.hostAllowed(null, allowed))
        val hz = catalog.allowedHosts(catalog.game("hzchain")!!, catalog.game("hzchain")!!.u)
        assertTrue(Catalog.hostAllowed("www.haaretz.co.il", hz))
        assertTrue("payment and identity helpers", Catalog.hostAllowed("id.piano.io", hz))
    }

    @Test
    fun aPlaceholderMayRoamOnlyUntilItIsReplaced() {
        val g = catalog.game("midi")!!
        assertTrue(catalog.roams(g, g.u))
        assertFalse(catalog.roams(g, "https://www.nytimes.com/crosswords/game/midi"))
        assertFalse(catalog.roams(catalog.game("wordle")!!, catalog.game("wordle")!!.u))
    }

    @Test
    fun libraryFollowsThePersonsChoicesThenTheCatalogue() {
        val wordle = catalog.game("wordle")!!
        val letterboxed = catalog.game("letterboxed")!!
        val state = JSONObject().put("prefs", JSONObject().put("wordle", "no").put("letterboxed", "yes"))
        assertFalse(catalog.inLib(wordle, state)); assertTrue(catalog.inLib(letterboxed, state))
        assertTrue(catalog.inLib(wordle, null)); assertFalse(catalog.inLib(letterboxed, null))
    }

    @Test
    fun theAccountSitesKnowWhereToLogIn() {
        for (id in listOf("nyt", "hz", "li")) {
            val s = catalog.sites[id]!!
            assertTrue(id, s.account && s.domains.isNotEmpty() && s.loginUrl!!.startsWith("https://"))
        }
        assertEquals("player", catalog.sites["li"]!!.profile)
        assertEquals("games", catalog.sites["nyt"]!!.profile)
        assertNull(catalog.sites["kr"]!!.loginUrl)
    }
}
