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
