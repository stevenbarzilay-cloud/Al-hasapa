package app.couch.alhasapa.display

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayScriptTest {
    private val profiles = DisplayProfiles(File("src/main/assets/display_profiles.json").readText())
    private fun profileOf(url: String) = profiles.resolve(url, null).profile

    @Test
    fun theScriptNeverTouchesTheViewport() {
        // The first 1.0.0 set the viewport meta to size the page; the real WebView ignored it and the game was cut off.
        // Sizing is now done by making the WebView itself phone-sized (ScaledFrame), so no profile may reach for the viewport.
        for (url in listOf("https://www.nytimes.com/games/wordle/index.html", "https://www.haaretz.co.il/riddles/5-letters", "https://krillion.io")) {
            val js = DisplayScript.build(profileOf(url))
            assertFalse(url, js.contains("viewport")); assertFalse(url, js.contains("initial-scale")); assertFalse(url, js.contains("user-scalable"))
        }
    }

    @Test
    fun cssTravelsAsADataStringSoItCannotBreakOutOfTheScript() {
        val p = DisplayProfile(css = "</script><script>alert(1)</script> ")
        val js = DisplayScript.build(p)
        assertFalse("no literal closing tag", js.contains("</script>"))
        assertFalse("no raw line separator", js.contains(" "))
    }

    @Test
    fun onlyTheViewportColourSchemeAndBannerCssAreTouched() {
        // A guard on the script's reach: it must never read the page's content, forms or storage, or send anything out.
        val js = DisplayScript.build(DisplayProfile())
        for (banned in listOf("innerText", "textContent.match", "cookie", "localStorage", "sessionStorage", "fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "eval(", "input", "password"))
            assertFalse("the display script must not use $banned", js.contains(banned))
    }

    @Test
    fun onATvKrillionIsAskedForItsOwnLowPowerModeAndNothingElseIsTouched() {
        val krillion = profileOf("https://krillion.io/")
        assertEquals(mapOf("krillion-low-power" to "1"), krillion.tvStorage)
        assertEquals("every page of the site", krillion.tvStorage, profileOf("https://krillion.io/unlimited").tvStorage)
        val tv = DisplayScript.build(krillion, tv = true)
        assertTrue(tv.contains("krillion-low-power"))
        assertTrue("only when the people have not chosen", tv.contains("getItem(k)===null"))
        assertEquals("one look and one write, at the named key", 1, Regex("localStorage").findAll(tv).count() - 1)
        for (banned in listOf("removeItem", ".clear(", ".key(", "sessionStorage", "cookie", "fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "eval("))
            assertFalse("the TV preference must not use $banned", tv.contains(banned))
        // Not on a phone, and not for a site that names nothing.
        assertFalse(DisplayScript.build(krillion).contains("localStorage"))
        assertFalse(DisplayScript.build(profileOf("https://www.nytimes.com/games/wordle/index.html"), tv = true).contains("localStorage"))
        // What is saved for a site never carries it (it belongs to the built-in table), and a saved override keeps it.
        assertFalse(krillion.toJson().has("tvStorage"))
        val saved = JSONObject().put("krillion.io", krillion.copy(zoom = 120).toJson())
        val again = profiles.resolve("https://krillion.io/", saved).profile
        assertEquals(120, again.zoom); assertEquals(krillion.tvStorage, again.tvStorage)
    }

    @Test
    fun aPreferencesNameOrValueCannotBreakOutOfTheScript() {
        val js = DisplayScript.build(DisplayProfile(tvStorage = mapOf("a\"b</script>" to "x';alert(1);//")), tv = true)
        assertFalse(js.contains("</script>"))
        assertFalse("the value stays inside a string", js.contains("';alert(1);//'"))
    }

    /** Writes the scripts the browser check (tools/display_check.cjs) runs in real Chromium. Always rewritten, harmless. */
    @Test
    fun dumpScriptForTheBrowserCheck() {
        val dir = File("build/display-scripts").apply { mkdirs() }
        File(dir, "display.js").writeText(DisplayScript.build(DisplayProfile(css = ".smartbanner{display:none!important}")))
        assertTrue(File(dir, "display.js").length() > 100)
        // As on the TV for Krillion: the real profile from the table.
        File(dir, "display-tv-krillion.js").writeText(DisplayScript.build(profileOf("https://krillion.io/"), tv = true))
        assertTrue(File(dir, "display-tv-krillion.js").readText().contains("krillion-low-power"))
        // As on the TV for Dialed: its blurs and frosted glass out (tools/light_check.cjs).
        File(dir, "display-tv-dialed.js").writeText(DisplayScript.build(profileOf("https://dialed.gg/color"), tv = true))
        assertTrue(File(dir, "display-tv-dialed.js").readText().contains("__couchLight"))
    }

    @Test
    fun onlyOnATvAndOnlyForTheSitesThatAskDoTheBlursGo() {
        val dialed = profileOf("https://dialed.gg/sound")
        assertTrue("the whole site, every game of it", dialed.tvLight)
        assertTrue(DisplayScript.build(dialed, tv = true).contains("__couchLight"))
        assertFalse("not on a phone", DisplayScript.build(dialed, tv = false).contains("__couchLight"))
        assertFalse("not for another site", DisplayScript.build(profileOf("https://krillion.io/"), tv = true).contains("__couchLight"))
        // Whatever was saved for a page of the site, it holds (it is about the box, not about how a page is shown).
        val saved = JSONObject().put("dialed.gg/color", dialed.copy(zoom = 110).toJson())
        assertTrue(profiles.resolve("https://dialed.gg/color", saved).profile.tvLight)
        assertFalse("never part of what is saved", dialed.toJson().has("tvLight"))
    }
}
