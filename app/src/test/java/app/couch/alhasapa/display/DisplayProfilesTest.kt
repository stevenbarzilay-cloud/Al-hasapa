package app.couch.alhasapa.display

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayProfilesTest {
    private val profiles = DisplayProfiles(File("src/main/assets/display_profiles.json").readText())

    private fun mode(url: String, user: JSONObject? = null) = profiles.resolve(url, user).profile.mode

    @Test
    fun theBriefsDefaultsHold() {
        assertEquals(DisplayMode.PORTRAIT_FIT, mode("https://www.haaretz.co.il/riddles/2026-06-07/ty-article-static/0000019d-f71f"))
        assertEquals(DisplayMode.PORTRAIT_FIT, mode("https://www.haaretz.co.il/riddles/5-letters"))
        for (g in listOf("wordle/index.html", "connections", "strands", "pips")) assertEquals(g, DisplayMode.PORTRAIT_FIT, mode("https://www.nytimes.com/games/$g"))
        assertEquals(DisplayMode.WIDE, mode("https://www.nytimes.com/crosswords/game/daily"))
        for (g in listOf("queens/", "tango/", "zip/", "pinpoint/")) assertEquals(g, DisplayMode.PORTRAIT_FIT, mode("https://www.linkedin.com/games/$g"))
    }

    @Test
    fun everythingElseIsShownAsItIs() {
        assertEquals(DisplayMode.NATIVE, mode("https://krillion.io"))
        assertEquals(DisplayMode.NATIVE, mode("https://www.nytimes.com/athletic/connections-sports-edition"))
        assertEquals(DisplayMode.NATIVE, mode("https://example.org/anything"))
    }

    @Test
    fun theLongestMatchWinsAndSubdomainsMatch() {
        assertEquals("nytimes.com/games/wordle", profiles.resolve("https://www.nytimes.com/games/wordle/index.html", null).key)
        assertEquals("nytimes.com/crosswords/game/daily", profiles.resolve("https://www.nytimes.com/crosswords/game/daily/2026/11/21", null).key)
        assertEquals(DisplayMode.PORTRAIT_FIT, mode("https://games.haaretz.co.il/riddles/x"))
        assertFalse("a lookalike host does not match", profiles.resolve("https://evilhaaretz.co.il/riddles", null).profile.mode == DisplayMode.PORTRAIT_FIT)
    }

    @Test
    fun aSavedOverrideBeatsTheBuiltInAndKeepsItsOtherFields() {
        val user = JSONObject().put("nytimes.com/games/wordle", JSONObject().put("mode", "wide").put("zoom", 120))
        val r = profiles.resolve("https://www.nytimes.com/games/wordle/index.html", user)
        assertEquals(DisplayMode.WIDE, r.profile.mode); assertEquals(120, r.profile.zoom); assertTrue(r.fromUser)
        assertEquals("fields it does not mention stay as before", 2.5, r.profile.safeArea, 0.0)
        assertEquals("other games are unaffected", DisplayMode.PORTRAIT_FIT, mode("https://www.nytimes.com/games/connections", user))
    }

    @Test
    fun aMoreSpecificSavedKeyBeatsABroaderBuiltIn() {
        val user = JSONObject().put("haaretz.co.il/riddles/5-letters", JSONObject().put("mode", "native"))
        assertEquals(DisplayMode.NATIVE, mode("https://www.haaretz.co.il/riddles/5-letters", user))
        assertEquals(DisplayMode.PORTRAIT_FIT, mode("https://www.haaretz.co.il/riddles/other", user))
    }

    @Test
    fun anUnknownSiteIsSavedUnderItsHostAndFirstPathSegment() {
        val url = "https://www.krillion.io/play/today"
        val r = profiles.resolve(url, null)
        assertEquals("krillion.io/play", profiles.keyFor(url, r))
        assertEquals("krillion.io", profiles.keyFor("https://krillion.io", profiles.resolve("https://krillion.io", null)))
    }

    @Test
    fun savedValuesAreClampedToSaneRanges() {
        val p = DisplayProfile().overlay(JSONObject().put("zoom", 9999).put("safeArea", 99).put("vw", 5))
        assertEquals(DisplayProfile.MAX_ZOOM, p.zoom); assertEquals(8.0, p.safeArea, 0.0); assertEquals(200, p.vw)
    }

    @Test
    fun defaultsHideCookieAndAppBannersAndKeepTheTvMargin() {
        val d = profiles.resolve("https://example.org", null).profile
        assertTrue(d.css.contains("onetrust")); assertTrue(d.css.contains("smartbanner"))
        assertEquals(2.5, d.safeArea, 0.0); assertFalse(d.dark); assertTrue(d.injectCss)
    }
}
