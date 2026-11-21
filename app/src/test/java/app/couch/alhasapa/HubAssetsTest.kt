package app.couch.alhasapa

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the "nothing leaves the device" and "works offline" promises of the hub: it must not pull
 * anything remote (fonts, scripts, styles), and every local file it references must be bundled.
 */
class HubAssetsTest {
    private val hubDir = File("src/main/assets/hub")
    private val html by lazy { File(hubDir, "index.html").readText() }

    @Test
    fun hubAssetsAreBundled() {
        assertTrue("hub/index.html missing (cwd=${File(".").absolutePath})", File(hubDir, "index.html").isFile)
    }

    @Test
    fun noRemoteFontsScriptsOrStyles() {
        for (host in listOf("fonts.googleapis.com", "fonts.gstatic.com", "cdn.", "unpkg.com", "cdnjs.")) {
            assertFalse("hub references $host", html.contains(host))
        }
        assertFalse("remote <script src>", Regex("""<script[^>]+src\s*=\s*["']https?:""", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("remote <link href>", Regex("""<link[^>]+href\s*=\s*["']https?:""", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("remote @import / url()", Regex("""(@import|url\()\s*["']?https?:""", RegexOption.IGNORE_CASE).containsMatchIn(html))
    }

    @Test
    fun everyLocalReferenceExists() {
        val refs = Regex("""(?:src|href)\s*=\s*"([^"$#:]+)"""").findAll(html).map { it.groupValues[1] }.toSet() +
            Regex("""url\("([^"]+)"\)""").findAll(html).map { it.groupValues[1] }.toSet()
        assertTrue("expected some local references", refs.isNotEmpty())
        val missing = refs.filter { !File(hubDir, it).isFile }
        assertEquals("referenced but not bundled: $missing", emptyList<String>(), missing)
    }

    @Test
    fun breakpointsNeverUseTheOrientationFeature() {
        // `orientation` flips when the on-screen keyboard shrinks the viewport, which threw a phone into the wide
        // layout (played rows lost their titles, the check mark squashed into an ellipse). Width/height only.
        val css = Regex("<style>(.*?)</style>", RegexOption.DOT_MATCHES_ALL).findAll(html).joinToString("\n") { it.groupValues[1] }
        assertTrue("expected the hub stylesheet", css.contains(":root"))
        assertFalse("a media query uses orientation", Regex("""@media[^{]*orientation""").containsMatchIn(css))
    }

    @Test
    fun theShellNeverDependsOnPercentageHeights() {
        // Android WebView can report a layout height of 0, so `height:100%` on html/body/.app collapsed the whole hub to
        // nothing (blank screen in M0.1-M0.3, reproduced to the pixel with the phone's own numbers). The shell is sized
        // in pixels from innerHeight instead, and the sheet and toast are positioned inside it, not against the viewport.
        val css = Regex("<style>(.*?)</style>", RegexOption.DOT_MATCHES_ALL).findAll(html).joinToString("\n") { it.groupValues[1] }
        assertFalse("html/body must not set a height", Regex("""html,body\{[^}]*height""").containsMatchIn(css))
        val app = Regex("""\.app\{([^}]*)\}""").find(css)?.groupValues?.get(1) ?: ""
        assertTrue(".app must be fixed with a pixel height from --app-h: $app", app.contains("position:fixed") && app.contains("var(--app-h"))
        assertFalse(".app must not use a percentage height", Regex("""height:\s*\d+%""").containsMatchIn(app))
        assertTrue("the hub must set --app-h from innerHeight", html.contains("setProperty(\"--app-h\"") && html.contains("window.innerHeight"))
        for (sel in listOf(".overlay", ".toast")) {
            val rule = Regex("""\""" + sel + """\{([^}]*)\}""").find(css)?.groupValues?.get(1) ?: ""
            assertFalse("$sel must not be position:fixed (bottom:0 collapses with a zero layout height)", rule.contains("position:fixed"))
        }
    }

    @Test
    fun everyIllustrationHasItsColourFamilyMasks() {
        // The pieces' colours recolour the artwork through two masks per picture (see tools/make_illustration_masks.py).
        for (n in listOf("couple", "cats", "board")) {
            for (f in listOf("$n.webp", "$n-r.webp", "$n-p.webp")) assertTrue("img/$f missing", File(hubDir, "img/$f").isFile)
            assertTrue("hub no longer builds $n through art()", html.contains("art(\"$n\""))
        }
    }

    @Test
    fun theDefaultColoursLeaveTheArtworkUntouched() {
        // The art is drawn in red and pink: with those colours the colour layers must stay switched off.
        assertTrue(html.contains("classList.toggle(\"tint-r\",ca.id!==\"red\")"))
        assertTrue(html.contains("classList.toggle(\"tint-p\",cb.id!==\"pink\")"))
        val css = Regex("<style>(.*?)</style>", RegexOption.DOT_MATCHES_ALL).findAll(html).joinToString("\n") { it.groupValues[1] }
        assertTrue(Regex("""\.art \.tint\{[^}]*opacity:0""").containsMatchIn(css))
    }

    @Test
    fun hubShipsItsPiecesCatalog() {
        assertTrue("pieces.json missing", File(hubDir, "pieces.json").isFile)
        assertTrue("hub no longer loads pieces.json", html.contains("fetch(\"pieces.json\")"))
    }

    @Test
    fun fourPlexWeightsAreBundledForBothHubAndNative() {
        for (w in listOf("Regular", "Medium", "SemiBold", "Bold")) {
            assertTrue("hub font $w", File(hubDir, "fonts/IBMPlexSansHebrew-$w.woff2").isFile)
            assertTrue("native font $w", File("src/main/res/font/ibm_plex_sans_hebrew_${w.lowercase()}.ttf").isFile)
        }
    }
}
