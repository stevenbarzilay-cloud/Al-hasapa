package app.couch.alhasapa.player

import app.couch.alhasapa.catalog.Catalog
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaySessionTest {
    private val catalog = Catalog.parse(File("src/main/assets/games.json").readText())

    @Test
    fun sharedSitesUseTheSharedProfileAndLinkedInUsesThePersons() {
        val wordle = PlaySession.forGame(catalog, null, catalog.game("wordle")!!, null)
        val hz = PlaySession.forGame(catalog, null, catalog.game("hzchain")!!, null)
        assertEquals("games", wordle.profile); assertEquals("games", hz.profile)
        assertEquals("steve", PlaySession.forGame(catalog, null, catalog.game("queens")!!, "a").profile)
        assertEquals("roni", PlaySession.forGame(catalog, null, catalog.game("queens")!!, "b").profile)
        assertEquals("a head-to-head game never silently falls into the shared profile", "steve", PlaySession.forGame(catalog, null, catalog.game("zip")!!, null).profile)
    }

    @Test
    fun theSessionUsesThePersonsLinkAndAllowsItsHost() {
        val state = JSONObject().put("urls", JSONObject().put("hzchain", "https://www.haaretz.co.il/riddles/chain-today"))
        val s = PlaySession.forGame(catalog, state, catalog.game("hzchain")!!, null)
        assertEquals("https://www.haaretz.co.il/riddles/chain-today", s.url)
        assertTrue("haaretz.co.il" in s.allowedHosts)
        assertFalse(s.roaming); assertFalse(s.loginMode); assertEquals("hzchain", s.gameId)
    }

    @Test
    fun aPlaceholderRoamsUntilItIsReplaced() {
        assertTrue(PlaySession.forGame(catalog, null, catalog.game("midi")!!, null).roaming)
        val replaced = JSONObject().put("urls", JSONObject().put("midi", "https://www.nytimes.com/crosswords/game/midi"))
        assertFalse(PlaySession.forGame(catalog, replaced, catalog.game("midi")!!, null).roaming)
    }

    @Test
    fun loggingInRoamsAndHasNoGame() {
        val site = catalog.sites["nyt"]!!
        val s = PlaySession.forLogin(site, "NYT", ProfileNames.GAMES)
        assertTrue(s.roaming && s.loginMode); assertNull(s.gameId)
        assertEquals("https://myaccount.nytimes.com/auth/login", s.url)
        val li = PlaySession.forLogin(catalog.sites["li"]!!, "LinkedIn", ProfileNames.RONI)
        assertEquals("roni", li.profile)
    }
}
