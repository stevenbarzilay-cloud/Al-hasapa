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

    /** Writes the script the browser check (tools/display_check.cjs) runs in real Chromium. Always rewritten, harmless. */
    @Test
    fun dumpScriptForTheBrowserCheck() {
        val dir = File("build/display-scripts").apply { mkdirs() }
        File(dir, "display.js").writeText(DisplayScript.build(DisplayProfile(css = ".smartbanner{display:none!important}")))
        assertTrue(File(dir, "display.js").length() > 100)
    }
}
