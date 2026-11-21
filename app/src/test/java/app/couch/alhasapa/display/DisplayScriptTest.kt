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
    fun theViewportIsSetForPhoneShapedAndWideProfilesButNeverForNative() {
        val p = profileOf("https://www.nytimes.com/games/wordle/index.html")
        val l = DisplayLayout.compute(p, 868f, 390f)
        val js = DisplayScript.build(p, l)
        assertTrue(js.contains("width=430,initial-scale="))
        assertTrue(js.contains("user-scalable=no"))
        val n = profileOf("https://krillion.io")
        assertTrue("native: the viewport is left alone", DisplayScript.build(n, DisplayLayout.compute(n, 868f, 390f)).contains("VP=null"))
    }

    @Test
    fun cssTravelsAsADataStringSoItCannotBreakOutOfTheScript() {
        val p = DisplayProfile(css = "</script><script>alert(1)</script> ")
        val js = DisplayScript.build(p, DisplayLayout.compute(p, 868f, 390f))
        assertFalse("no literal closing tag", js.contains("</script>"))
        assertFalse("no raw line separator", js.contains(" "))
    }

    @Test
    fun onlyTheViewportColourSchemeAndBannerCssAreTouched() {
        // A guard on the script's reach: it must never read the page's content, forms or storage, or send anything out.
        val js = DisplayScript.build(DisplayProfile(), DisplayLayout.compute(DisplayProfile(), 868f, 390f))
        for (banned in listOf("innerText", "textContent.match", "cookie", "localStorage", "sessionStorage", "fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "eval(", "input", "password"))
            assertFalse("the display script must not use $banned", js.contains(banned))
    }

    /** Writes the scripts the browser test (tools/display_check.mjs) runs in real Chromium. Always rewritten, harmless. */
    @Test
    fun dumpScriptsForTheBrowserCheck() {
        val dir = File("build/display-scripts").apply { mkdirs() }
        val cases = JSONObject()
        fun add(name: String, p: DisplayProfile, aw: Float, ah: Float) {
            val l = DisplayLayout.compute(p, aw, ah)
            File(dir, "$name.js").writeText(DisplayScript.build(p, l))
            cases.put(name, JSONObject().put("webWidth", l.webWidth.toDouble()).put("webHeight", l.webHeight.toDouble())
                .put("virtualWidth", l.virtualWidth ?: JSONObject.NULL).put("virtualHeight", l.virtualHeight?.toDouble() ?: JSONObject.NULL))
        }
        add("portrait", DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, css = ".smartbanner{display:none!important}"), 868f, 390f)
        add("portrait-zoom125", DisplayProfile(mode = DisplayMode.PORTRAIT_FIT, zoom = 125), 868f, 390f)
        add("wide", DisplayProfile(mode = DisplayMode.WIDE, vw = 1280), 868f, 390f)
        add("native", DisplayProfile(), 868f, 390f)
        File(dir, "cases.json").writeText(cases.toString(1))
        assertEquals(4, cases.length())
    }
}
